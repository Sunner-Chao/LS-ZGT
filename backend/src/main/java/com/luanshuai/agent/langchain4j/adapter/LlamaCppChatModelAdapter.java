package com.luanshuai.agent.langchain4j.adapter;

import com.luanshuai.agent.config.LangChain4jProperties;
import com.luanshuai.agent.service.LLMService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

/**
 * LlamaCpp ChatLanguageModel 适配器
 * 将 LLMService 包装为 LangChain4j 的 ChatLanguageModel 接口
 *
 * 支持：
 * - 同步 generate 调用（使用虚拟线程）
 * - Tool Calling（ToolSpecification）
 */
@Component
public class LlamaCppChatModelAdapter {

    private static final Logger log = LoggerFactory.getLogger(LlamaCppChatModelAdapter.class);

    private final LLMService llmService;
    private final ExecutorService virtualThreadExecutor;
    private final Duration timeout;
    private final double temperature;
    private final int maxTokens;

    @Autowired
    public LlamaCppChatModelAdapter(LLMService llmService, LangChain4jProperties properties) {
        this.llmService = llmService;
        this.virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();
        this.timeout = Duration.ofSeconds(properties.getChatModelTimeout());
        this.temperature = 0.7;
        this.maxTokens = 2048;
        log.info("[LlamaCppChatModelAdapter] Initialized with timeout={}s", timeout.toSeconds());
    }

    /**
     * 生成聊天响应（同步）
     */
    public String generate(String prompt) {
        try {
            Future<String> future = virtualThreadExecutor.submit(() -> {
                return llmService.generateSync(prompt);
            });
            return future.get(timeout.toSeconds(), TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Chat generation interrupted", e);
        } catch (ExecutionException e) {
            throw new RuntimeException("Chat generation failed", e);
        } catch (TimeoutException e) {
            throw new RuntimeException("Chat generation timed out after " + timeout.toSeconds() + "s", e);
        }
    }

    /**
     * 生成聊天响应（带 Tool Calling）
     */
    public Map<String, Object> generateWithTools(String prompt, List<Map<String, Object>> functions) {
        try {
            return virtualThreadExecutor.submit(() -> {
                return llmService.generateWithFunctions(prompt, functions);
            }).get(timeout.toSeconds(), TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new RuntimeException("Chat generation with tools failed", e);
        }
    }

    /**
     * 获取当前模型名称
     */
    public String getModelName() {
        return llmService.getChatModel();
    }

    /**
     * 获取配置的超时时间
     */
    public Duration getTimeout() {
        return timeout;
    }
}