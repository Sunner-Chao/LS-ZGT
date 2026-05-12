package com.luanshuai.agent.service.llm;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * LlamaCpp 连接配置
 * 所有配置均从环境变量读取，遵循 AGENTS.md 动态配置原则
 */
@Component
@ConfigurationProperties(prefix = "app.llama-cpp")
public class LlamaCppProperties {

    /** llama.cpp 服务器地址（默认 Docker 内访问宿主机） */
    private String baseUrl = System.getenv().getOrDefault("LLAMA_CPP_URL", "http://host.docker.internal:8081");

    /** Chat 对话模型名称 */
    private String chatModel = System.getenv().getOrDefault("LLAMA_CPP_CHAT_MODEL", "ChatLLM");

    /** Embedding 向量化模型名称 */
    private String embeddingModel = System.getenv().getOrDefault("LLAMA_CPP_EMBEDDING_MODEL", "nomic-embed-text-v2-moe");

    /** Chat 请求超时（毫秒） */
    private int chatTimeout = 120000;

    /** Embedding 请求超时（毫秒） */
    private int embeddingTimeout = 30000;

    /** Chat 最大 token 数 */
    private int maxTokens = 2048;

    /** Chat 温度参数 */
    private double temperature = 0.7;

    // Getters and Setters
    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }

    public String getChatModel() { return chatModel; }
    public void setChatModel(String chatModel) { this.chatModel = chatModel; }

    public String getEmbeddingModel() { return embeddingModel; }
    public void setEmbeddingModel(String embeddingModel) { this.embeddingModel = embeddingModel; }

    public int getChatTimeout() { return chatTimeout; }
    public void setChatTimeout(int chatTimeout) { this.chatTimeout = chatTimeout; }

    public int getEmbeddingTimeout() { return embeddingTimeout; }
    public void setEmbeddingTimeout(int embeddingTimeout) { this.embeddingTimeout = embeddingTimeout; }

    public int getMaxTokens() { return maxTokens; }
    public void setMaxTokens(int maxTokens) { this.maxTokens = maxTokens; }

    public double getTemperature() { return temperature; }
    public void setTemperature(double temperature) { this.temperature = temperature; }

    /** 获取 Chat API 完整 URL */
    public String getChatUrl() {
        return baseUrl + "/v1/chat/completions";
    }

    /** 获取 Embedding API 完整 URL */
    public String getEmbeddingUrl() {
        return baseUrl + "/v1/embeddings";
    }

    /** 获取 Models 列表 API URL */
    public String getModelsUrl() {
        return baseUrl + "/v1/models";
    }
}
