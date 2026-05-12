package com.luanshuai.agent.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.util.*;

/**
 * VLM 增强 OCR 服务
 *
 * 利用 Qwen3.5-9B-VLM（GGUF Q4_K_M）进行端到端视觉文字识别。
 *
 * 核心能力：
 * - 图片文件（ JPG / PNG / GIF / BMP / TIFF ）的文字 OCR
 * - 扫描版 PDF 逐页渲染后送 VLM 识别（替代 Tesseract OCR）
 * - 表格结构化提取（Markdown 格式返回）
 * - 手写体、印章、公式等复杂视觉元素的理解
 *
 * 工作模式：
 * 1. OCR 模式：直接提取图片中的所有文字，保持段落格式和阅读顺序
 * 2. TABLE 模式：尝试将表格区域转换为 Markdown 格式
 *
 * 调用方式：通过内部 HTTP 调用宿主机的 llama-chat 服务（VLM 模型）
 * 建议 llama.cpp 启动参数（用于提升 OCR 质量）：
 *   ./llama-server ... --image-min-tokens 2048 --image-max-tokens 8192
 *
 * 回退策略：VLM OCR 失败时自动降级到 Tesseract OCR
 */
@Service
public class VlmOcrService {

    private static final Logger log = LoggerFactory.getLogger(VlmOcrService.class);

    /** llama-chat 服务的 VLM HTTP 端点（llama.cpp server-cuda） */
    private final String vlmApiUrl;

    /** VLM OCR 超时时间（秒），视觉推理比纯文本慢 */
    private final int timeoutSeconds;

    /** 是否启用 VLM OCR（可通过配置关闭，强制走 Tesseract） */
    private final boolean enabled;

    /** 最大图片边长（像素），超过则缩放以节省 token */
    private final int maxImageDimension;

    /** Tesseract 兜底服务 */
    @Autowired
    private TesseractOcrService tesseractOcrService;

    /** Spring WebClient（响应式，非阻塞） */
    @Autowired
    private org.springframework.web.reactive.function.client.WebClient.Builder webClientBuilder;

    /**
     * 构造器：注入配置
     */
    @Autowired
    public VlmOcrService(
            @Value("${app.vlm.ocr.url:http://host.docker.internal:8081}") String vlmApiUrl,
            @Value("${app.vlm.ocr.timeout:120}") int timeoutSeconds,
            @Value("${app.vlm.ocr.enabled:true}") boolean enabled,
            @Value("${app.vlm.ocr.max-image-dimension:2048}") int maxImageDimension) {
        this.vlmApiUrl = vlmApiUrl;
        this.timeoutSeconds = timeoutSeconds;
        this.enabled = enabled;
        this.maxImageDimension = maxImageDimension;
    }

    // ==================== 公共 API ====================

    /**
     * OCR 图片文件（响应式）
     *
     * @param imageFile 图片文件（支持 JPG, PNG, GIF, BMP, TIFF, WEBP）
     * @return 识别的文字内容
     */
    public Mono<String> ocrImage(File imageFile) {
        return ocrImage(imageFile, OcrMode.TEXT);
    }

    /**
     * OCR 图片文件（指定模式，响应式）
     *
     * @param imageFile 图片文件
     * @param mode      OCR 模式：TEXT = 纯文字提取；TABLE = 表格结构化提取
     * @return 识别的文字 / Markdown 表格内容
     */
    public Mono<String> ocrImage(File imageFile, OcrMode mode) {
        if (!enabled) {
            log.info("[VLM-OCR] VLM OCR 已禁用，降级到 Tesseract: {}", imageFile.getName());
            return Mono.fromCallable(() -> tesseractOcrService.ocrImage(imageFile))
                    .subscribeOn(Schedulers.boundedElastic());
        }

        if (!imageFile.exists() || !imageFile.canRead()) {
            log.error("[VLM-OCR] 图片文件不可读: {}", imageFile.getAbsolutePath());
            return Mono.error(new IOException("图片文件不存在或无法读取: " + imageFile.getAbsolutePath()));
        }

        String fileName = imageFile.getName().toLowerCase();
        if (!isImageFile(fileName)) {
            return Mono.error(new IOException("不支持的图片格式: " + fileName));
        }

        return prepareImage(imageFile)
                .flatMap(base64Image -> sendToVlm(base64Image, mode, fileName))
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .onErrorResume(e -> {
                    log.warn("[VLM-OCR] VLM OCR 失败，降级到 Tesseract (image={}, mode={}): {}",
                            imageFile.getName(), mode, e.getMessage());
                    return Mono.fromCallable(() -> tesseractOcrService.ocrImage(imageFile))
                            .subscribeOn(Schedulers.boundedElastic());
                });
    }

    /**
     * OCR 扫描版 PDF 文件（响应式）
     *
     * 流程：逐页渲染为图片 → Base64 编码 → VLM 识别 → 合并返回
     * 与传统 Tesseract OCR 相比，对模糊/倾斜/印章等有更强鲁棒性
     *
     * @param pdfFile PDF 文件
     * @return 全文文字内容
     */
    public Mono<String> ocrPdf(File pdfFile) {
        return ocrPdf(pdfFile, OcrMode.TEXT);
    }

    /**
     * OCR 扫描版 PDF 文件（指定模式）
     *
     * @param pdfFile PDF 文件
     * @param mode    OCR 模式
     * @return 全文文字 / Markdown 内容
     */
    public Mono<String> ocrPdf(File pdfFile, OcrMode mode) {
        if (!enabled) {
            log.info("[VLM-OCR] VLM OCR 已禁用，降级到 Tesseract: {}", pdfFile.getName());
            return Mono.fromCallable(() -> tesseractOcrService.ocrPdf(pdfFile))
                    .subscribeOn(Schedulers.boundedElastic());
        }

        if (!pdfFile.exists() || !pdfFile.canRead()) {
            return Mono.error(new IOException("PDF 文件不存在或无法读取: " + pdfFile.getAbsolutePath()));
        }

        return renderPdfToImages(pdfFile)
                .collectList()
                .flatMap(images -> {
                    if (images.isEmpty()) {
                        log.warn("[VLM-OCR] PDF 渲染出 0 页图片: {}", pdfFile.getName());
                        return Mono.just("");
                    }
                    log.info("[VLM-OCR] PDF 渲染完成，共 {} 页，开始 VLM OCR: {}", images.size(), pdfFile.getName());
                    return processImagesConcurrently(images, mode, pdfFile.getName());
                })
                .timeout(Duration.ofSeconds(timeoutSeconds * 3)) // PDF 多页，给予更长超时
                .onErrorResume(e -> {
                    log.warn("[VLM-OCR] VLM PDF OCR 失败，降级到 Tesseract (file={}): {}",
                            pdfFile.getName(), e.getMessage());
                    return Mono.fromCallable(() -> tesseractOcrService.ocrPdf(pdfFile))
                            .subscribeOn(Schedulers.boundedElastic());
                });
    }

    /**
     * 智能文档解析（路由到最优解析器）
     *
     * Docling 擅长：原生数字文档、精准表格（97.9% 准确率）
     * VLM 擅长：扫描件、手写体、印章、复杂图表
     *
     * 此方法根据文件类型和预估内容选择策略：
     * - 扫描版 PDF / 图片 → VLM OCR
     * - 原生数字 PDF / Office 文档 → 建议交给 DoclingService（不在此服务处理）
     *
     * @param file 文件
     * @return 解析后文本
     */
    public Mono<String> smartParse(File file) {
        String name = file.getName().toLowerCase();

        if (name.endsWith(".pdf")) {
            // 尝试先提取文本（原生数字 PDF 有文本层），提取失败则降级 VLM
            return extractTextOrFallback(file)
                    .flatMap(text -> {
                        if (text != null && text.trim().length() > 50) {
                            // 文本层提取成功，内容充足，直接返回
                            log.info("[VLM-OCR] PDF 文本层提取成功（{} 字符），跳过 VLM: {}", text.length(), file.getName());
                            return Mono.just(text);
                        } else {
                            // 内容为空或极少，判定为扫描版，走 VLM OCR
                            log.info("[VLM-OCR] PDF 文本层贫瘠，判定为扫描版，走 VLM OCR: {}", file.getName());
                            return ocrPdf(file, OcrMode.TEXT);
                        }
                    });
        } else if (isImageFile(name)) {
            return ocrImage(file, OcrMode.TEXT);
        } else {
            return Mono.error(new IOException("smartParse 不支持的文件类型: " + name));
        }
    }

    // ==================== 内部方法 ====================

    /**
     * 判断是否为支持的图片文件
     */
    private boolean isImageFile(String fileName) {
        return fileName.endsWith(".jpg") || fileName.endsWith(".jpeg")
                || fileName.endsWith(".png") || fileName.endsWith(".gif")
                || fileName.endsWith(".bmp") || fileName.endsWith(".tiff")
                || fileName.endsWith(".webp") || fileName.endsWith(".tif");
    }

    /**
     * 准备图片：缩放到最大边长限制内，编码为 Base64 JPEG
     */
    private Mono<String> prepareImage(File imageFile) {
        return Mono.fromCallable(() -> {
            BufferedImage original = ImageIO.read(imageFile);
            if (original == null) {
                throw new IOException("无法读取图片: " + imageFile.getAbsolutePath());
            }

            BufferedImage resized = resizeImageIfNeeded(original);

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            boolean success = ImageIO.write(resized, "jpg", baos);
            if (!success) {
                throw new IOException("JPEG 编码失败");
            }

            String base64 = Base64.getEncoder().encodeToString(baos.toByteArray());
            log.debug("[VLM-OCR] 图片准备完成: {}x{}, Base64 长度: {}",
                    resized.getWidth(), resized.getHeight(), base64.length());
            return base64;
        }).subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * 如果图片过大，按比例缩放以节省 token
     */
    private BufferedImage resizeImageIfNeeded(BufferedImage original) {
        int w = original.getWidth();
        int h = original.getHeight();
        int maxDim = Math.max(w, h);

        if (maxDim <= maxImageDimension) {
            return original;
        }

        double scale = (double) maxImageDimension / maxDim;
        int newW = (int) (w * scale);
        int newH = (int) (h * scale);

        // 高质量双线性缩放
        java.awt.Image scaledInstance = original.getScaledInstance(newW, newH, java.awt.Image.SCALE_SMOOTH);
        BufferedImage resized = new BufferedImage(newW, newH, BufferedImage.TYPE_INT_RGB);
        java.awt.Graphics2D g2d = resized.createGraphics();
        g2d.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g2d.drawImage(scaledInstance, 0, 0, newW, newH, null);
        g2d.dispose();

        log.info("[VLM-OCR] 图片已缩放: {}x{} → {}x{}", w, h, newW, newH);
        return resized;
    }

    /**
     * 发送 Base64 图片到 llama.cpp VLM 服务
     */
    private Mono<String> sendToVlm(String base64Image, OcrMode mode, String fileName) {
        String systemPrompt = buildSystemPrompt(mode);
        String userPrompt = buildUserPrompt(mode, fileName);

        List<Map<String, Object>> messages = List.of(
                Map.of("role", "system", "content", systemPrompt),
                Map.of("role", "user", "content", List.of(
                        Map.of("type", "image_url", "image_url", Map.of("url", "data:image/jpeg;base64," + base64Image)),
                        Map.of("type", "text", "text", userPrompt)
                ))
        );

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("model", "qwen3.5-9b-vlm");
        payload.put("messages", messages);
        payload.put("temperature", 0.1);  // 低温度保证 OCR 准确性
        payload.put("stream", false);

        log.debug("[VLM-OCR] 发送请求到 VLM: mode={}, imageSize={} chars", mode, base64Image.length());

        return webClientBuilder.build()
                .post()
                .uri(vlmApiUrl + "/v1/chat/completions")
                .header("Content-Type", "application/json")
                .bodyValue(payload)
                .retrieve()
                .bodyToMono(String.class)
                .map(response -> parseVlmResponse(response, fileName))
                .doOnSuccess(text -> log.info("[VLM-OCR] VLM OCR 成功: {} ({} chars)", fileName, text.length()))
                .doOnError(e -> log.error("[VLM-OCR] VLM 请求失败: {}", e.getMessage()));
    }

    /**
     * 构建 System Prompt（根据 OCR 模式）
     */
    private String buildSystemPrompt(OcrMode mode) {
        return switch (mode) {
            case TEXT -> """
                    你是一个专业的 OCR 文字识别助手。请准确地从用户提供的图片中提取所有文字内容。
                    要求：
                    - 严格保持原文，不要总结、不要翻译、不要省略
                    - 保持原有的段落格式和阅读顺序
                    - 对于表格，尽量以 Markdown 表格形式呈现
                    - 对于印章、手写体、公式等特殊元素，也请一并提取为文字
                    - 只输出识别到的文字，不要输出其他解释或评论
                    """;
            case TABLE -> """
                    你是一个专业的表格提取助手。请将图片中的表格转换为 Markdown 格式。
                    要求：
                    - 输出标准的 Markdown 表格（| 列1 | 列2 | 格式）
                    - 如果是多行表格，确保列对齐正确
                    - 如果不是表格或表格无法识别，输出：无法识别表格
                    - 只输出 Markdown 表格，不要输出其他内容
                    """;
        };
    }

    /**
     * 构建 User Prompt
     */
    private String buildUserPrompt(OcrMode mode, String fileName) {
        return switch (mode) {
            case TEXT -> "请准确提取这张图片中的所有文字内容，保持原文格式。文件名：" + fileName;
            case TABLE -> "请将这张图片中的表格转换为 Markdown 格式。如果不是表格请说明。文件名：" + fileName;
        };
    }

    /**
     * 解析 llama.cpp 返回的 JSON 响应
     */
    private String parseVlmResponse(String jsonResponse, String fileName) {
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            @SuppressWarnings("unchecked")
            Map<String, Object> resp = mapper.readValue(jsonResponse, Map.class);

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> choices = (List<Map<String, Object>>) resp.get("choices");
            if (choices == null || choices.isEmpty()) {
                throw new IOException("VLM 响应缺少 choices 字段");
            }

            Map<String, Object> choice = choices.get(0);
            @SuppressWarnings("unchecked")
            Map<String, Object> message = (Map<String, Object>) choice.get("message");
            if (message == null) {
                throw new IOException("VLM 响应缺少 message 字段");
            }

            Object content = message.get("content");
            if (content == null) {
                throw new IOException("VLM 响应 content 为空");
            }

            return content.toString().trim();
        } catch (Exception e) {
            log.error("[VLM-OCR] 解析 VLM 响应失败 (file={}): {}", fileName, e.getMessage());
            throw new RuntimeException("解析 VLM 响应失败: " + e.getMessage(), e);
        }
    }

    /**
     * 将 PDF 逐页渲染为 BufferedImage 列表
     */
    private reactor.core.publisher.Flux<BufferedImage> renderPdfToImages(File pdfFile) {
        return Mono.fromCallable(() -> {
            List<BufferedImage> images = new ArrayList<>();

            // 使用 Apache PDFBox 渲染
            try (org.apache.pdfbox.pdmodel.PDDocument document = org.apache.pdfbox.Loader.loadPDF(pdfFile)) {
                int pageCount = document.getNumberOfPages();
                log.info("[VLM-OCR] PDF 总页数: {}", pageCount);

                org.apache.pdfbox.rendering.PDFRenderer renderer = new org.apache.pdfbox.rendering.PDFRenderer(document);

                for (int i = 0; i < pageCount; i++) {
                    // 渲染为 300 DPI 高质量图片，确保 VLM 能看清小字
                    BufferedImage pageImage = renderer.renderImageWithDPI(i, 300);
                    BufferedImage resized = resizeImageIfNeeded(pageImage);
                    images.add(resized);
                    log.debug("[VLM-OCR] PDF 第 {}/{} 页渲染完成: {}x{}", i + 1, pageCount, resized.getWidth(), resized.getHeight());
                }
            }

            return images;
        }).flatMapMany(Flux::fromIterable)
          .subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * 尝试从 PDF 提取文本层（用于判断是否为扫描版）
     */
    private Mono<String> extractTextOrFallback(File pdfFile) {
        return Mono.fromCallable(() -> {
            try (org.apache.pdfbox.pdmodel.PDDocument document = org.apache.pdfbox.Loader.loadPDF(pdfFile)) {
                org.apache.pdfbox.text.PDFTextStripper stripper = new org.apache.pdfbox.text.PDFTextStripper();
                return stripper.getText(document);
            }
        }).subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * 并发处理多张图片（PDF 每页），限制并发数
     */
    private Mono<String> processImagesConcurrently(List<BufferedImage> images, OcrMode mode, String fileName) {
        return Flux.fromIterable(images)
                .flatMap(image -> encodeAndOcr(image, mode, fileName), 2) // 最多 2 页并发
                .collectList()
                .map(pages -> String.join("\n\n--- 第 X 页 ---\n\n", pages));
    }

    /**
     * 将单张 BufferedImage 编码为 Base64 并送 VLM 识别
     */
    private Mono<String> encodeAndOcr(BufferedImage image, OcrMode mode, String fileName) {
        return Mono.fromCallable(() -> {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ImageIO.write(image, "jpg", baos);
            return Base64.getEncoder().encodeToString(baos.toByteArray());
        }).subscribeOn(Schedulers.boundedElastic())
          .flatMap(base64 -> sendToVlm(base64, mode, fileName));
    }

    // ==================== 枚举 & 内部类 ====================

    /**
     * VLM OCR 工作模式
     */
    public enum OcrMode {
        /** 纯文字提取模式 */
        TEXT,
        /** 表格结构化提取模式 */
        TABLE
    }
}
