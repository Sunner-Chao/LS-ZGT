package com.luanshuai.agent.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import org.springframework.http.MediaType;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.client.MultipartBodyBuilder;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.time.Duration;
import java.util.Map;

/**
 * PDF-Extract-Kit 服务封装
 * 说明：
 * - 负责将本地 PDF 文件上传到外部的 PDF-Extract-Kit 服务并获取解析后的文本
 * - 提供严格的超时与重试策略以应对网络或解析延迟
 * - 支持通过配置开关（enabled）在不同环境下启用/禁用该功能
 */
@Service
public class PdfExtractKitService {
    private static final Logger log = LoggerFactory.getLogger(PdfExtractKitService.class);
    
    // 非阻塞 HTTP 客户端，用于向 PDF-Extract-Kit 发起请求
    private final WebClient webClient;
    // PDF-Extract-Kit 的 HTTP 地址（从配置注入）
    private final String pdfExtractKitUrl;
    // 配置开关：是否启用 PDF-Extract-Kit 集成，方便在无该服务的部署环境下降级
    private final boolean enabled;
    // 请求超时时间（秒），用于控制大文件解析的最大等待时间
    private final int timeoutSeconds;
    
    @Autowired
    public PdfExtractKitService(
            @Value("${app.pdf.extract.kit.url}") String pdfExtractKitUrl,
            @Value("${app.pdf.extract.kit.enabled:true}") boolean enabled,
            @Value("${app.pdf.extract.kit.timeout:1800}") int timeoutSeconds) {
        this.webClient = WebClient.builder()
                // 提高内存上限以支持大文件（例如：100MB）
                .codecs(configurer -> configurer.defaultCodecs().maxInMemorySize(100 * 1024 * 1024)) // 100MB
                .build();
        this.pdfExtractKitUrl = pdfExtractKitUrl;
        this.enabled = enabled;
        this.timeoutSeconds = timeoutSeconds;
    }
    
    /**
     * 使用 PDF-Extract-Kit 解析 PDF 文件并返回解析后的纯文本
     * 步骤说明：
     * 1. 检查服务是否启用（enabled 配置）
     * 2. 将文件读入内存并包装为 ByteArrayResource（保持原始文件名以便服务端记录）
     * 3. 使用 multipart/form-data 上传文件到 PDF-Extract-Kit
     * 4. 设置超时（可配置）并对常见的网络错误进行退避重试（最多 5 次，指数回退）
     * 5. 解析返回的 Map：若包含 error 字段则视为失败，否则读取 text 字段并返回
     * 6. 在发生 IO 异常时返回错误的 Mono
     */
    public Mono<String> parsePdfWithExtractKit(File file) {
        // 表示在当前部署中该集成被显式禁用
        if (!enabled) {
            return Mono.error(new RuntimeException("PDF-Extract-Kit is disabled"));
        }
        
        try {
            // 将文件全部读取为字节数组（注意：对于非常大的 PDF 可能占用较多内存）
            byte[] fileBytes = Files.readAllBytes(file.toPath());
            // 使用 ByteArrayResource 包装，以便在 multipart 时带上文件名
            ByteArrayResource fileResource = new ByteArrayResource(fileBytes) {
                @Override
                public String getFilename() {
                    return file.getName();
                }
            };
            
            // 构建 multipart 请求体，字段名与 PDF-Extract-Kit 的接口约定为 "file"
            MultipartBodyBuilder builder = new MultipartBodyBuilder();
            builder.part("file", fileResource);
            
            log.info("[PDF-Extract-Kit] 开始解析文件: {}", file.getName());
            
            // 发送请求并处理响应
            return webClient.post()
                    .uri(pdfExtractKitUrl)
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .bodyValue(builder.build())
                    .retrieve()
                    // 期望返回一个 Map（JSON）结构，包含 text 或 error 字段
                    .bodyToMono(Map.class)
                    // 使用配置的超时时间以避免被长时间阻塞
                    .timeout(java.time.Duration.ofSeconds(timeoutSeconds))
                    // 针对常见网络相关错误进行重试（避免短暂网络波动导致失败），同时跳过 500 错误
                    .retryWhen(reactor.util.retry.Retry.backoff(5, java.time.Duration.ofSeconds(10))
                        .filter(throwable -> {
                            // 重试网络相关的错误（尤其是 Connection refused），避免服务启动慢/短暂重启导致解析失败
                            String message = throwable.getMessage();
                            if (message == null) {
                                return false;
                            }
                            String lower = message.toLowerCase();
                            boolean isRetriable = lower.contains("connection refused")
                                    || lower.contains("finishconnect")
                                    || lower.contains("connection prematurely closed")
                                    || lower.contains("connection has been closed")
                                    || lower.contains("connection reset")
                                    || lower.contains("readtimeoutexception")
                                    || lower.contains("connectexception")
                                    || lower.contains("timeout");

                            // 5xx 由服务端抛出时，多数情况下重试意义不大（也避免雪崩）
                            boolean isServer500 = lower.contains("500 internal server error");
                            return isRetriable && !isServer500;
                        }))
                    .map(response -> {
                        // 如果服务端返回 error 字段，则视为业务错误并抛出
                        if (response.containsKey("error")) {
                            throw new RuntimeException("PDF-Extract-Kit error: " + response.get("error"));
                        }
                        String text = (String) response.get("text");
                        log.info("[PDF-Extract-Kit] 解析成功，内容长度: {}", text != null ? text.length() : 0);
                        return text != null ? text : "";
                    })
                    .doOnError(e -> {
                        // 记录解析失败的详细日志，便于后续排查
                        log.error("[PDF-Extract-Kit] 解析失败: {}", e.getMessage());
                    });
                    
        } catch (IOException e) {
            // 文件读取异常（IO）场景下返回失败 Mono，由调用者处理
            return Mono.error(new RuntimeException("Failed to read file: " + e.getMessage()));
        }
    }
    
    /**
     * 简单的服务可用性检查（用于健康检查或降级逻辑）
     * - 如果未启用集成则直接返回 false
     * - 否则发起一个 GET 请求并依据是否能成功得到响应来判断服务是否可用
     */
    public Mono<Boolean> isServiceAvailable() {
        if (!enabled) {
            return Mono.just(false);
        }

        // 走 /health 更可靠，也更轻量
        String healthUrl = pdfExtractKitUrl;
        if (healthUrl.contains("/api/parse")) {
            healthUrl = healthUrl.replace("/api/parse", "/health");
        } else {
            // 兜底：即便配置不是 /api/parse，也尽量拼出一个 health
            if (healthUrl.endsWith("/")) {
                healthUrl = healthUrl + "health";
            } else {
                healthUrl = healthUrl + "/health";
            }
        }

        return webClient.get()
                .uri(healthUrl)
                .retrieve()
                .toBodilessEntity()
                .timeout(Duration.ofSeconds(5))
                .map(response -> true)
                .onErrorReturn(false);
    }
} 