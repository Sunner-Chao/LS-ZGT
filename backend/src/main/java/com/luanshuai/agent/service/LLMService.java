package com.luanshuai.agent.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.*;

/**
 * LLMService - 统一的 LLM 服务，支持本地 (llama.cpp) 和云端 (OpenAI/Claude) 模型调用
 *
 * 本地模式：通过 llama.cpp 的 OpenAI 兼容 API (/v1/chat/completions, /v1/embeddings) 调用本机模型
 * 云端模式：通过 OpenAI 兼容 API 或 Claude API 调用云端模型
 * Embedding 始终使用本地 llama.cpp
 */
@Service
public class LLMService {
    private static final Logger log = LoggerFactory.getLogger(LLMService.class);

    private final WebClient webClient;
    private final ObjectMapper objectMapper;

    // =============== 提供方配置 ===============
    @Value("${app.llm.provider:local}")
    private String provider; // "local" or "cloud"

    @Value("${app.llm.local-url:${LLM_LOCAL_URL:http://llama-cpp:8080}}")
    private String localUrl;

    @Value("${app.llm.embedding-url:${LLM_EMBEDDING_URL:http://llama-cpp:8080}}")
    private String embeddingUrl;

    @Value("${app.llm.cloud-provider:${LLM_CLOUD_PROVIDER:openai}}")
    private String cloudProvider; // "openai" or "claude"

    @Value("${app.llm.cloud-api-key:${LLM_CLOUD_API_KEY:}}")
    private String cloudApiKey;

    @Value("${app.llm.cloud-base-url:${LLM_CLOUD_BASE_URL:https://api.openai.com/v1}}")
    private String cloudBaseUrl;

    // =============== 模型配置 ===============
    @Value("${app.llm.chat-model:${LLM_CHAT_MODEL:deepseek-r1:14b}}")
    private String chatModel;

    @Value("${app.llm.embedding-model:${LLM_EMBEDDING_MODEL:nomic-embed-text}}")
    private String embeddingModel;

    @Value("${app.llm.temperature:0.7}")
    private double temperature;

    @Value("${app.llm.max-tokens:1024}")
    private int maxTokens;

    public LLMService(WebClient.Builder webClientBuilder, ObjectMapper objectMapper) {
        this.webClient = webClientBuilder.build();
        this.objectMapper = objectMapper;
    }

    // =============== Chat Completion (non-streaming) ===============

    /**
     * 非流式对话生成，返回解析后的 Map（兼容 RagService 的 chat() 调用）
     * 对于 OpenAI 格式，返回 {"choices": [{"message": {"content": "..."}}]}
     */
    public Mono<Map<String, Object>> generateCompletion(String prompt) {
        if ("cloud".equals(provider)) {
            return cloudChatCompletion(prompt, false);
        }
        return localChatCompletion(prompt, false);
    }

    /**
     * Map payload 版本（兼容 RagService 的 chatStream 调用）
     * payload 中应包含 messages, stream 等字段
     */
    public Mono<String> generateCompletion(Map<String, Object> payload) {
        // Ensure model is set
        payload.putIfAbsent("model", chatModel);
        if ("cloud".equals(provider)) {
            return cloudChatCompletionRaw(payload, false);
        }
        return localChatCompletionRaw(payload, false);
    }

    // =============== Chat Completion (streaming) ===============

    /**
     * 流式对话生成，返回原始文本流
     * - 本地 (llama.cpp): 返回 SSE 格式 "data: {...}\n\n"
     * - 云端 OpenAI: 返回 SSE 格式 "data: {...}\n\n"
     * - 云端 Claude: 返回 SSE 格式 "event: ...\ndata: {...}\n\n"
     */
    public Flux<String> generateCompletionStream(Map<String, Object> payload) {
        payload.putIfAbsent("model", chatModel);
        payload.put("stream", true);

        if ("cloud".equals(provider)) {
            if ("claude".equals(cloudProvider)) {
                return claudeChatCompletionStream(payload);
            }
            return cloudOpenAIStream(payload);
        }
        return localStream(payload);
    }

    // =============== Embedding ===============

    /**
     * 生成 embedding 向量（始终使用本地 llama.cpp）
     */
    public Mono<List<Double>> generateEmbedding(String input) {
        Map<String, Object> body = new HashMap<>();
        body.put("model", embeddingModel);
        body.put("input", input);

        return webClient.post()
                .uri(embeddingUrl + "/v1/embeddings")
                .contentType(MediaType.APPLICATION_JSON)
                .body(BodyInserters.fromValue(body))
                .retrieve()
                .bodyToMono(String.class)
                .flatMap(s -> {
                    try {
                        Map<String, Object> m = objectMapper.readValue(s, new TypeReference<Map<String, Object>>() {});
                        // OpenAI format: {"data": [{"embedding": [...]}]}
                        Object dataObj = m.get("data");
                        if (dataObj instanceof List) {
                            List<?> dataList = (List<?>) dataObj;
                            if (!dataList.isEmpty() && dataList.get(0) instanceof Map) {
                                Object embObj = ((Map<?, ?>) dataList.get(0)).get("embedding");
                                if (embObj instanceof List) {
                                    List<?> embList = (List<?>) embObj;
                                    List<Double> result = new ArrayList<>(embList.size());
                                    for (Object o : embList) {
                                        result.add(((Number) o).doubleValue());
                                    }
                                    return Mono.just(result);
                                }
                            }
                        }
                        log.warn("[LLM] Embedding response missing data[0].embedding: {}", s.length() > 200 ? s.substring(0, 200) + "..." : s);
                    } catch (Exception e) {
                        log.warn("[LLM] Failed to parse embedding response: {}", e.getMessage());
                    }
                    return Mono.just(Collections.<Double>emptyList());
                })
                .onErrorResume(e -> {
                    log.warn("[LLM] Embedding request failed: {}", e.getMessage());
                    return Mono.just(Collections.<Double>emptyList());
                });
    }

    // =============== Model Listing ===============

    /**
     * 列出可用模型
     * - 本地: GET {localUrl}/v1/models
     * - 云端: GET {cloudBaseUrl}/models (with auth)
     */
    public Mono<List<String>> listModels() {
        if ("cloud".equals(provider)) {
            return listCloudModels();
        }
        return listLocalModels();
    }

    // =============== Connection Test ===============

    public Mono<Boolean> checkConnection() {
        String url = "cloud".equals(provider) ? cloudBaseUrl : localUrl;
        return webClient.get()
                .uri(url + "/v1/models")
                .accept(MediaType.APPLICATION_JSON)
                .retrieve()
                .bodyToMono(String.class)
                .map(s -> true)
                .onErrorResume(e -> Mono.just(false));
    }

    // =============== Config Getters/Setters ===============

    public String getProvider() { return provider; }
    public void setProvider(String provider) { this.provider = provider; }

    public String getLocalUrl() { return localUrl; }
    public void setLocalUrl(String localUrl) { this.localUrl = localUrl; }

    public String getEmbeddingUrl() { return embeddingUrl; }
    public void setEmbeddingUrl(String embeddingUrl) { this.embeddingUrl = embeddingUrl; }

    public String getCloudProvider() { return cloudProvider; }
    public void setCloudProvider(String cloudProvider) { this.cloudProvider = cloudProvider; }

    public String getCloudApiKey() { return cloudApiKey; }
    public void setCloudApiKey(String cloudApiKey) { this.cloudApiKey = cloudApiKey; }

    public String getCloudBaseUrl() { return cloudBaseUrl; }
    public void setCloudBaseUrl(String cloudBaseUrl) { this.cloudBaseUrl = cloudBaseUrl; }

    public String getChatModel() { return chatModel; }
    public void setChatModel(String chatModel) { this.chatModel = chatModel; }

    public String getEmbeddingModel() { return embeddingModel; }
    public void setEmbeddingModel(String embeddingModel) { this.embeddingModel = embeddingModel; }

    public double getTemperature() { return temperature; }
    public void setTemperature(double temperature) { this.temperature = temperature; }

    public int getMaxTokens() { return maxTokens; }
    public void setMaxTokens(int maxTokens) { this.maxTokens = maxTokens; }

    // =============== Private: Local (llama.cpp) ===============

    private Mono<Map<String, Object>> localChatCompletion(String prompt, boolean stream) {
        Map<String, Object> body = new HashMap<>();
        body.put("model", chatModel);
        body.put("messages", List.of(Map.of("role", "user", "content", prompt)));
        body.put("stream", stream);
        body.put("max_tokens", maxTokens);
        body.put("temperature", temperature);

        return webClient.post()
                .uri(localUrl + "/v1/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .body(BodyInserters.fromValue(body))
                .retrieve()
                .bodyToMono(String.class)
                .flatMap(s -> {
                    try {
                        Map<String, Object> m = objectMapper.readValue(s, new TypeReference<Map<String, Object>>() {});
                        return Mono.just(m);
                    } catch (Exception e) {
                        log.warn("[LLM] Failed to parse local chat response: {}", e.getMessage());
                        return Mono.just(Map.of("error", "parse failed", "raw", s));
                    }
                });
    }

    private Mono<String> localChatCompletionRaw(Map<String, Object> payload, boolean stream) {
        payload.put("stream", stream);
        return webClient.post()
                .uri(localUrl + "/v1/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .body(BodyInserters.fromValue(payload))
                .retrieve()
                .bodyToMono(String.class);
    }

    private Flux<String> localStream(Map<String, Object> payload) {
        return webClient.post()
                .uri(localUrl + "/v1/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.valueOf("text/event-stream"))
                .body(BodyInserters.fromValue(payload))
                .retrieve()
                .bodyToFlux(String.class)
                .doOnSubscribe(s -> log.debug("[LLM] Subscribed to local stream"))
                .doOnError(e -> log.warn("[LLM] Local stream error: {}", e.getMessage()));
    }

    private Mono<List<String>> listLocalModels() {
        return webClient.get()
                .uri(localUrl + "/v1/models")
                .accept(MediaType.APPLICATION_JSON)
                .retrieve()
                .bodyToMono(String.class)
                .flatMap(s -> {
                    List<String> names = new ArrayList<>();
                    try {
                        Map<String, Object> m = objectMapper.readValue(s, new TypeReference<Map<String, Object>>() {});
                        Object dataObj = m.get("data");
                        if (dataObj instanceof List) {
                            for (Object item : (List<?>) dataObj) {
                                if (item instanceof Map) {
                                    Object id = ((Map<?, ?>) item).get("id");
                                    if (id != null) names.add(String.valueOf(id));
                                }
                            }
                        }
                    } catch (Exception e) {
                        log.warn("[LLM] Failed to parse local models: {}", e.getMessage());
                    }
                    return Mono.just(names);
                })
                .onErrorResume(e -> {
                    log.warn("[LLM] Failed to list local models: {}", e.getMessage());
                    return Mono.just(Collections.emptyList());
                });
    }

    // =============== Private: Cloud (OpenAI-compatible) ===============

    private Mono<Map<String, Object>> cloudChatCompletion(String prompt, boolean stream) {
        if ("claude".equals(cloudProvider)) {
            return claudeChatCompletion(prompt, stream);
        }
        return openAIChatCompletion(prompt, stream);
    }

    private Mono<Map<String, Object>> openAIChatCompletion(String prompt, boolean stream) {
        Map<String, Object> body = new HashMap<>();
        body.put("model", chatModel);
        body.put("messages", List.of(Map.of("role", "user", "content", prompt)));
        body.put("stream", stream);
        body.put("max_tokens", maxTokens);
        body.put("temperature", temperature);

        return webClient.post()
                .uri(cloudBaseUrl + "/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .header("Authorization", "Bearer " + cloudApiKey)
                .body(BodyInserters.fromValue(body))
                .retrieve()
                .bodyToMono(String.class)
                .flatMap(s -> {
                    try {
                        return Mono.just(objectMapper.readValue(s, new TypeReference<Map<String, Object>>() {}));
                    } catch (Exception e) {
                        log.warn("[LLM] Failed to parse cloud chat response: {}", e.getMessage());
                        return Mono.just(Map.of("error", "parse failed"));
                    }
                });
    }

    private Mono<String> cloudChatCompletionRaw(Map<String, Object> payload, boolean stream) {
        payload.put("stream", stream);
        return webClient.post()
                .uri(cloudBaseUrl + "/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .header("Authorization", "Bearer " + cloudApiKey)
                .body(BodyInserters.fromValue(payload))
                .retrieve()
                .bodyToMono(String.class);
    }

    private Flux<String> cloudOpenAIStream(Map<String, Object> payload) {
        return webClient.post()
                .uri(cloudBaseUrl + "/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.valueOf("text/event-stream"))
                .header("Authorization", "Bearer " + cloudApiKey)
                .body(BodyInserters.fromValue(payload))
                .retrieve()
                .bodyToFlux(String.class)
                .doOnSubscribe(s -> log.debug("[LLM] Subscribed to cloud OpenAI stream"))
                .doOnError(e -> log.warn("[LLM] Cloud OpenAI stream error: {}", e.getMessage()));
    }

    private Mono<List<String>> listCloudModels() {
        String url = cloudBaseUrl.endsWith("/v1") ? cloudBaseUrl : cloudBaseUrl + "/v1";
        return webClient.get()
                .uri(url + "/models")
                .accept(MediaType.APPLICATION_JSON)
                .header("Authorization", "Bearer " + cloudApiKey)
                .retrieve()
                .bodyToMono(String.class)
                .flatMap(s -> {
                    List<String> names = new ArrayList<>();
                    try {
                        Map<String, Object> m = objectMapper.readValue(s, new TypeReference<Map<String, Object>>() {});
                        Object dataObj = m.get("data");
                        if (dataObj instanceof List) {
                            for (Object item : (List<?>) dataObj) {
                                if (item instanceof Map) {
                                    Object id = ((Map<?, ?>) item).get("id");
                                    if (id != null) names.add(String.valueOf(id));
                                }
                            }
                        }
                    } catch (Exception e) {
                        log.warn("[LLM] Failed to parse cloud models: {}", e.getMessage());
                    }
                    return Mono.just(names);
                })
                .onErrorResume(e -> {
                    log.warn("[LLM] Failed to list cloud models: {}", e.getMessage());
                    return Mono.just(Collections.emptyList());
                });
    }

    // =============== Private: Cloud (Claude) ===============

    private Mono<Map<String, Object>> claudeChatCompletion(String prompt, boolean stream) {
        Map<String, Object> body = new HashMap<>();
        body.put("model", chatModel);
        body.put("messages", List.of(Map.of("role", "user", "content", prompt)));
        body.put("max_tokens", maxTokens);
        body.put("stream", stream);

        return webClient.post()
                .uri("https://api.anthropic.com/v1/messages")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .header("x-api-key", cloudApiKey)
                .header("anthropic-version", "2023-06-01")
                .body(BodyInserters.fromValue(body))
                .retrieve()
                .bodyToMono(String.class)
                .flatMap(s -> {
                    try {
                        Map<String, Object> claudeResp = objectMapper.readValue(s, new TypeReference<Map<String, Object>>() {});
                        // Convert Claude format to OpenAI format for compatibility
                        return Mono.just(convertClaudeToOpenAI(claudeResp));
                    } catch (Exception e) {
                        log.warn("[LLM] Failed to parse Claude response: {}", e.getMessage());
                        return Mono.just(Map.of("error", "parse failed"));
                    }
                });
    }

    private Flux<String> claudeChatCompletionStream(Map<String, Object> payload) {
        // Convert OpenAI-format payload to Claude format
        Map<String, Object> claudeBody = new HashMap<>();
        claudeBody.put("model", chatModel);
        claudeBody.put("max_tokens", maxTokens);
        claudeBody.put("stream", true);

        // Convert messages
        Object messagesObj = payload.get("messages");
        if (messagesObj instanceof List) {
            List<?> msgs = (List<?>) messagesObj;
            List<Map<String, String>> claudeMsgs = new ArrayList<>();
            for (Object msg : msgs) {
                if (msg instanceof Map) {
                    Map<?, ?> m = (Map<?, ?>) msg;
                    Map<String, String> claudeMsg = new HashMap<>();
                    claudeMsg.put("role", String.valueOf(m.get("role")));
                    claudeMsg.put("content", String.valueOf(m.get("content")));
                    claudeMsgs.add(claudeMsg);
                }
            }
            claudeBody.put("messages", claudeMsgs);
        } else {
            claudeBody.put("messages", List.of(Map.of("role", "user", "content", "hello")));
        }

        return webClient.post()
                .uri("https://api.anthropic.com/v1/messages")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.valueOf("text/event-stream"))
                .header("x-api-key", cloudApiKey)
                .header("anthropic-version", "2023-06-01")
                .body(BodyInserters.fromValue(claudeBody))
                .retrieve()
                .bodyToFlux(String.class)
                .doOnSubscribe(s -> log.debug("[LLM] Subscribed to Claude stream"))
                .doOnError(e -> log.warn("[LLM] Claude stream error: {}", e.getMessage()));
    }

    /**
     * Convert Claude Messages API response to OpenAI format for compatibility with RagService
     */
    private Map<String, Object> convertClaudeToOpenAI(Map<String, Object> claudeResp) {
        Map<String, Object> openaiResp = new HashMap<>();
        List<Map<String, Object>> choices = new ArrayList<>();
        Map<String, Object> choice = new HashMap<>();
        Map<String, Object> message = new HashMap<>();

        // Extract content from Claude response
        Object contentObj = claudeResp.get("content");
        StringBuilder contentText = new StringBuilder();
        if (contentObj instanceof List) {
            for (Object item : (List<?>) contentObj) {
                if (item instanceof Map) {
                    Map<?, ?> contentBlock = (Map<?, ?>) item;
                    if ("text".equals(String.valueOf(contentBlock.get("type")))) {
                        contentText.append(String.valueOf(contentBlock.get("text")));
                    }
                }
            }
        }

        message.put("role", "assistant");
        message.put("content", contentText.toString());
        choice.put("message", message);
        choice.put("finish_reason", "stop");
        choices.add(choice);
        openaiResp.put("choices", choices);
        return openaiResp;
    }

    // =============== LangChain4j 适配器支持方法 ===============

    /**
     * 同步生成方法（用于 LangChain4j 适配器）
     * 将 WebFlux Mono 转换为阻塞调用
     */
    public String generateSync(String prompt) {
        Map<String, Object> body = new HashMap<>();
        body.put("model", chatModel);
        body.put("messages", List.of(Map.of("role", "user", "content", prompt)));
        body.put("max_tokens", maxTokens);
        body.put("temperature", temperature);

        return webClient.post()
                .uri(localUrl + "/v1/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .body(BodyInserters.fromValue(body))
                .retrieve()
                .bodyToMono(String.class)
                .map(s -> {
                    try {
                        Map<String, Object> m = objectMapper.readValue(s, new TypeReference<Map<String, Object>>() {});
                        Object choices = m.get("choices");
                        if (choices instanceof List && !((List<?>) choices).isEmpty()) {
                            Object firstChoice = ((List<?>) choices).get(0);
                            if (firstChoice instanceof Map) {
                                Object message = ((Map<?, ?>) firstChoice).get("message");
                                if (message instanceof Map) {
                                    return String.valueOf(((Map<?, ?>) message).get("content"));
                                }
                            }
                        }
                        return "";
                    } catch (Exception e) {
                        log.warn("[LLM] Failed to parse sync response: {}", e.getMessage());
                        return "";
                    }
                })
                .block(Duration.ofSeconds(120));
    }

    /**
     * 带 Function Calling 的同步生成（用于 LangChain4j 适配器）
     */
    public Map<String, Object> generateWithFunctions(String prompt, List<Map<String, Object>> functions) {
        Map<String, Object> body = new HashMap<>();
        body.put("model", chatModel);
        body.put("messages", List.of(Map.of("role", "user", "content", prompt)));
        body.put("max_tokens", maxTokens);
        body.put("temperature", temperature);
        body.put("functions", functions);

        // 如果只有一个函数，自动选择
        if (functions != null && functions.size() == 1) {
            body.put("function_call", Map.of("name", functions.get(0).get("name")));
        }

        return webClient.post()
                .uri(localUrl + "/v1/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .body(BodyInserters.fromValue(body))
                .retrieve()
                .bodyToMono(String.class)
                .flatMap(s -> {
                    try {
                        Map<String, Object> m = objectMapper.readValue(s, new TypeReference<Map<String, Object>>() {});
                        return Mono.just(m);
                    } catch (Exception e) {
                        log.warn("[LLM] Failed to parse function response: {}", e.getMessage());
                        return Mono.just(Map.<String, Object>of("error", e.getMessage()));
                    }
                })
                .onErrorResume(e -> Mono.just(Map.<String, Object>of("error", e.getMessage())))
                .block(Duration.ofSeconds(120));
    }
}
