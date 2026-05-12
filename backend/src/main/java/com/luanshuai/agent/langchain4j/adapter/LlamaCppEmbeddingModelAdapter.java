package com.luanshuai.agent.langchain4j.adapter;

import com.luanshuai.agent.service.LLMService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ExecutionException;

/**
 * LlamaCpp EmbeddingModel 适配器
 * 将 LLMService 的 generateEmbedding 方法包装为 LangChain4j 的 EmbeddingModel 接口
 */
@Component
public class LlamaCppEmbeddingModelAdapter {

    private static final Logger log = LoggerFactory.getLogger(LlamaCppEmbeddingModelAdapter.class);

    private final LLMService llmService;
    private final ExecutorService virtualThreadExecutor;
    private final Duration timeout;
    private final int dimensions;

    @Autowired
    public LlamaCppEmbeddingModelAdapter(LLMService llmService) {
        this.llmService = llmService;
        this.virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();
        this.timeout = Duration.ofSeconds(60);
        // nomic-embed-text-v2-moe 默认 1024 维
        this.dimensions = 1024;
        log.info("[LlamaCppEmbeddingModelAdapter] Initialized with model={}, dimensions={}",
                llmService.getEmbeddingModel(), dimensions);
    }

    /**
     * 生成文本的 embedding 向量
     */
    public float[] embed(String text) {
        try {
            return virtualThreadExecutor.submit(() -> {
                List<Double> vector = llmService.generateEmbedding(text).block(timeout);
                if (vector == null || vector.isEmpty()) {
                    log.warn("[LlamaCppEmbeddingModelAdapter] Empty embedding for text: {}",
                            text.length() > 50 ? text.substring(0, 50) + "..." : text);
                    return new float[0];
                }
                float[] floats = new float[vector.size()];
                for (int i = 0; i < vector.size(); i++) {
                    floats[i] = vector.get(i).floatValue();
                }
                return floats;
            }).get(timeout.toSeconds(), TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new RuntimeException("Embedding generation failed", e);
        }
    }

    /**
     * 批量生成 embedding
     */
    public List<float[]> embedAll(List<String> texts) {
        return texts.stream().map(this::embed).toList();
    }

    /**
     * 获取 embedding 维度
     */
    public int dimensions() {
        return dimensions;
    }

    /**
     * 获取模型名称
     */
    public String modelName() {
        return llmService.getEmbeddingModel();
    }
}