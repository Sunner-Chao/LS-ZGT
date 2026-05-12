package com.luanshuai.agent.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

import java.time.Duration;

/**
 * Docling 文档解析服务封装
 *
 * IBM Docling 是专为 LLM 设计的文档解析工具包，核心优势：
 * - 高精度表格识别（准确率 97.9%）
 * - 原生数字文档（PDF/DOCX/PPTX/XLSX/HTML/Markdown）结构化解析
 * - 输出 LLM-Ready 数据（Markdown / HTML / Text）
 * - 纯 CPU 运行，硬件门槛低
 *
 * 工作流程：
 * 1. 原生数字文档（Word/PPT/Excel/原生 PDF）→ Docling 主力解析
 * 2. 表格密集型文档 → Docling 提取表格结构（Markdown）
 * 3. 扫描版 PDF / 手写体 / 印章 → VLM OCR（见 VlmOcrService）
 *
 * Docling 与 VLM 的智能路由策略：
 * ┌─────────────────────────────────────────────────────┐
 * │  文件类型           │  主力解析器  │  备选/补充      │
 * ├─────────────────────────────────────────────────────┤
 * │  原生数字 PDF       │  Docling    │  Apache PDFBox  │
 * │  Word (docx/doc)   │  Docling    │  Apache POI     │
 * │  PPT (pptx/ppt)    │  Docling    │  Apache POI     │
 * │  Excel (xlsx/xls)  │  Docling    │  Apache POI     │
 * │  扫描版 PDF         │  VLM OCR    │  Tesseract OCR  │
 * │  图片 (JPG/PNG)     │  VLM OCR    │  Tesseract OCR  │
 * │  复杂表格           │  Docling    │  VLM TABLE 模式 │
 * │  手写/印章/公式      │  VLM OCR    │  —              │
 * │  简单 TXT/MD/CSV    │  标准 IO    │  —              │
 * └─────────────────────────────────────────────────────┘
 */
@Service
public class DoclingService {

    private static final Logger log = LoggerFactory.getLogger(DoclingService.class);

    private final WebClient webClient;
    private final String doclingUrl;
    private final boolean enabled;
    private final int timeoutSeconds;

    @Autowired
    public DoclingService(
            @Value("${app.docling.url:http://docling-service:8001}") String doclingUrl,
            @Value("${app.docling.enabled:true}") boolean enabled,
            @Value("${app.docling.timeout:180}") int timeoutSeconds) {

        this.doclingUrl = doclingUrl;
        this.enabled = enabled;
        this.timeoutSeconds = timeoutSeconds;

        this.webClient = WebClient.builder()
                .codecs(configurer -> configurer.defaultCodecs().maxInMemorySize(100 * 1024 * 1024))
                .build();

        log.info("[Docling] 初始化: enabled={}, url={}, timeout={}s",
                enabled, doclingUrl, timeoutSeconds);
    }

    /**
     * 解析文档（通用接口）
     *
     * @param file 文件
     * @return 解析后的文本内容
     */
    public Mono<String> parseDocument(java.io.File file) {
        return parseDocument(file, "markdown");
    }

    /**
     * 解析文档（指定输出格式）
     *
     * @param file 文件
     * @param outputFormat 输出格式：markdown / html / text
     * @return 解析后的文本内容
     */
    public Mono<String> parseDocument(java.io.File file, String outputFormat) {
        if (!enabled) {
            return Mono.error(new RuntimeException("Docling is disabled"));
        }

        if (!file.exists() || !file.canRead()) {
            return Mono.error(new java.io.IOException("文件不存在或无法读取: " + file.getAbsolutePath()));
        }

        return uploadAndParse(file, outputFormat);
    }

    /**
     * 上传文件到 Docling 服务并获取解析结果
     */
    private Mono<String> uploadAndParse(java.io.File file, String outputFormat) {
        return Mono.fromCallable(() -> {
            try {
                return java.nio.file.Files.readAllBytes(file.toPath());
            } catch (java.io.IOException e) {
                throw new RuntimeException("读取文件失败: " + e.getMessage(), e);
            }
        }).flatMap(fileBytes -> {
            ByteArrayResource resource = new ByteArrayResource(fileBytes) {
                @Override
                public String getFilename() {
                    return file.getName();
                }
            };

            MultipartBodyBuilder builder = new MultipartBodyBuilder();
            builder.part("file", resource);
            builder.part("output_format", outputFormat);

            log.info("[Docling] 解析文档: {} (format={}, size={}MB)",
                    file.getName(), outputFormat, fileBytes.length / (1024 * 1024));

            return webClient.post()
                    .uri(doclingUrl + "/api/parse")
                    .contentType(org.springframework.http.MediaType.MULTIPART_FORM_DATA)
                    .bodyValue(builder.build())
                    .retrieve()
                    .bodyToMono(java.util.Map.class)
                    .timeout(Duration.ofSeconds(timeoutSeconds))
                    .retryWhen(Retry.backoff(3, Duration.ofSeconds(5))
                            .filter(throwable -> {
                                String msg = throwable.getMessage();
                                return msg != null && (
                                        msg.contains("Connection refused") ||
                                        msg.contains("timeout") ||
                                        msg.contains("Connection reset")
                                );
                            }))
                    .map(response -> {
                        if (response.containsKey("error")) {
                            throw new RuntimeException("Docling error: " + response.get("error"));
                        }
                        String text = (String) response.get("text");
                        Integer pages = (Integer) response.get("pages");
                        Integer tables = (Integer) response.get("tables");
                        log.info("[Docling] 解析成功: {} (pages={}, tables={}, chars={})",
                                file.getName(), pages, tables,
                                text != null ? text.length() : 0);
                        return text != null ? text : "";
                    })
                    .doOnError(e -> log.error("[Docling] 解析失败: {}: {}",
                            file.getName(), e.getMessage()));
        });
    }

    /**
     * 服务可用性检查
     */
    public Mono<Boolean> isAvailable() {
        if (!enabled) return Mono.just(false);

        String healthUrl = doclingUrl;
        if (!healthUrl.endsWith("/")) healthUrl += "/";
        healthUrl += "health";

        return webClient.get()
                .uri(healthUrl)
                .retrieve()
                .toBodilessEntity()
                .timeout(Duration.ofSeconds(5))
                .map(r -> true)
                .onErrorReturn(false)
                .defaultIfEmpty(false);
    }
}
