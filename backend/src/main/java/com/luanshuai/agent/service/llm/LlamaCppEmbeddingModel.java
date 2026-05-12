package com.luanshuai.agent.service.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.*;
import java.util.stream.Collectors;

/**
 * LlamaCpp Embedding 模型服务
 *
 * 调用 llama.cpp 的 embedding API 生成文本向量
 * 支持 OpenAI 兼容的 /v1/embeddings 端点
 */
@Service
public class LlamaCppEmbeddingModel {

    private static final Logger log = LoggerFactory.getLogger(LlamaCppEmbeddingModel.class);

    @Autowired
    private WebClient.Builder webClientBuilder;

    @Autowired
    private ObjectMapper objectMapper;

    @Value("${app.llm.embedding-url:${LLM_EMBEDDING_URL:http://llama-cpp:8080}}")
    private String embeddingUrl;

    @Value("${app.llm.embedding-model:${LLM_EMBEDDING_MODEL:nomic-embed-text-v2-moe}}")
    private String embeddingModel;

    @Value("${app.llm.embedding-timeout:30000}")
    private long timeout;

    /**
     * 生成文本的 embedding 向量
     *
     * @param text 输入文本
     * @return EmbeddingResult 包含向量
     */
    public EmbeddingResult embed(String text) {
        return embed(Collections.singletonList(text)).get(0);
    }

    /**
     * 批量生成 embedding 向量（同步阻塞接口，供 HybridSearchService 等调用，内部已通过 HybridSearchService.vectorSearch() 在 boundedElastic 线程执行）
     *
     * @param texts 输入文本列表
     * @return EmbeddingResult 列表
     */
    public List<EmbeddingResult> embed(List<String> texts) {
        if (texts == null || texts.isEmpty()) {
            return Collections.emptyList();
        }

        try {
            Map<String, Object> request = new LinkedHashMap<>();
            request.put("model", embeddingModel);
            request.put("input", texts);

            String response = webClientBuilder.build()
                    .post()
                    .uri(embeddingUrl + "/v1/embeddings")
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(request)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();

            return parseEmbeddingResponse(response);
        } catch (Exception e) {
            log.error("[Embedding] Failed to generate embeddings: {}", e.getMessage());
            // 返回空向量作为降级
            return texts.stream()
                    .map(t -> new EmbeddingResult(new float[768], 0)) // 默认 768 维
                    .collect(Collectors.toList());
        }
    }

    /**
     * 解析 embedding API 响应
     */
    @SuppressWarnings("unchecked")
    private List<EmbeddingResult> parseEmbeddingResponse(String response) {
        List<EmbeddingResult> results = new ArrayList<>();

        try {
            Map<String, Object> json = objectMapper.readValue(response, Map.class);
            List<Map<String, Object>> dataList = (List<Map<String, Object>>) json.get("data");

            if (dataList != null) {
                for (Map<String, Object> data : dataList) {
                    List<Number> embeddingList = (List<Number>) data.get("embedding");
                    if (embeddingList != null) {
                        float[] embedding = new float[embeddingList.size()];
                        for (int i = 0; i < embeddingList.size(); i++) {
                            embedding[i] = embeddingList.get(i).floatValue();
                        }
                        results.add(new EmbeddingResult(embedding, embedding.length));
                    }
                }
            }
        } catch (Exception e) {
            log.error("[Embedding] Failed to parse response: {}", e.getMessage());
        }

        return results;
    }

    /**
     * Embedding 结果封装
     */
    public static class EmbeddingResult {
        private final float[] vector;
        private final int dimensions;

        public EmbeddingResult(float[] vector, int dimensions) {
            this.vector = vector;
            this.dimensions = dimensions;
        }

        public float[] vector() {
            return vector;
        }

        public List<Double> vectorAsList() {
            List<Double> list = new ArrayList<>(vector.length);
            for (float v : vector) {
                list.add((double) v);
            }
            return list;
        }

        public int dimensions() {
            return dimensions;
        }
    }
}
