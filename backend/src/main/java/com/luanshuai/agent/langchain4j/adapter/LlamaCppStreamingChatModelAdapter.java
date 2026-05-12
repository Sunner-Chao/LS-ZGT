package com.luanshuai.agent.langchain4j.adapter;

import com.luanshuai.agent.config.LangChain4jProperties;
import com.luanshuai.agent.service.LLMService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

/**
 * LlamaCpp 流式 ChatLanguageModel 适配器
 * 用于 SSE 流式输出的场景
 */
@Component
public class LlamaCppStreamingChatModelAdapter {

    private static final Logger log = LoggerFactory.getLogger(LlamaCppStreamingChatModelAdapter.class);

    private final LLMService llmService;
    private final ExecutorService virtualThreadExecutor;
    private final Duration timeout;
    private final double temperature;
    private final int maxTokens;

    @Autowired
    public LlamaCppStreamingChatModelAdapter(LLMService llmService, LangChain4jProperties properties) {
        this.llmService = llmService;
        this.virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();
        this.timeout = Duration.ofSeconds(properties.getChatModelTimeout());
        this.temperature = 0.7;
        this.maxTokens = 2048;
        log.info("[LlamaCppStreamingChatModelAdapter] Initialized with timeout={}s", timeout.toSeconds());
    }

    /**
     * 流式生成（返回 SSE 事件流）
     */
    public Flux<String> generateStream(String prompt) {
        java.util.Map<String, Object> payload = new java.util.HashMap<>();
        payload.put("model", llmService.getChatModel());
        payload.put("messages", List.of(java.util.Map.of("role", "user", "content", prompt)));
        payload.put("max_tokens", maxTokens);
        payload.put("temperature", temperature);
        payload.put("stream", true);

        return llmService.generateCompletionStream(payload);
    }

    /**
     * 流式生成（带消息列表）
     */
    public Flux<String> generateStreamWithMessages(List<java.util.Map<String, String>> messages) {
        java.util.Map<String, Object> payload = new java.util.HashMap<>();
        payload.put("model", llmService.getChatModel());
        payload.put("messages", messages);
        payload.put("max_tokens", maxTokens);
        payload.put("temperature", temperature);
        payload.put("stream", true);

        return llmService.generateCompletionStream(payload);
    }

    /**
     * 生成并收集完整响应
     */
    public String generateAndCollect(String prompt) {
        AtomicReference<String> fullContent = new AtomicReference<>("");

        try {
            return virtualThreadExecutor.submit(() -> {
                generateStream(prompt)
                        .subscribe(
                                chunk -> {
                                    String data = extractDataFromSSE(chunk);
                                    if (data != null) {
                                        fullContent.updateAndGet(v -> v + data);
                                    }
                                },
                                error -> log.error("[LlamaCppStreamingChatModelAdapter] Stream error: {}", error.getMessage()),
                                () -> log.debug("[LlamaCppStreamingChatModelAdapter] Stream completed")
                        );
                Thread.sleep(5000);
                return fullContent.get();
            }).get(timeout.toSeconds(), TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new RuntimeException("Streaming generation failed", e);
        }
    }

    /**
     * 从 SSE 事件中提取数据
     */
    private String extractDataFromSSE(String sseChunk) {
        if (sseChunk == null || !sseChunk.startsWith("data:")) {
            return null;
        }
        String data = sseChunk.substring(5).trim();
        if ("[DONE]".equals(data)) {
            return null;
        }
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            java.util.Map<String, Object> parsed = mapper.readValue(data, java.util.Map.class);
            Object choices = parsed.get("choices");
            if (choices instanceof List && !((List<?>) choices).isEmpty()) {
                Object firstChoice = ((List<?>) choices).get(0);
                if (firstChoice instanceof java.util.Map) {
                    Object delta = ((java.util.Map<?, ?>) firstChoice).get("delta");
                    if (delta instanceof java.util.Map) {
                        return String.valueOf(((java.util.Map<?, ?>) delta).get("content"));
                    }
                }
            }
        } catch (Exception e) {
            log.debug("[LlamaCppStreamingChatModelAdapter] Failed to parse SSE: {}", e.getMessage());
        }
        return null;
    }

    /**
     * 获取模型名称
     */
    public String getModelName() {
        return llmService.getChatModel();
    }
}