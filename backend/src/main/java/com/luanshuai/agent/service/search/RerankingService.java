package com.luanshuai.agent.service.search;

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
 * 重排序服务 (Reranker)
 *
 * 在检索后对候选文档进行相关性重排序：
 * 1. Cross-Encoder 评分：对每个 (query, document) 对计算相关性分数
 * 2. 语义相关性：考虑查询意图和文档语义的匹配程度
 * 3. 多特征融合：结合 BM25、向量相似度、语义评分
 *
 * 使用 LLM 进行重排序 (LLM-as-a-Judge 风格)
 */
@Service
public class RerankingService {

    private static final Logger log = LoggerFactory.getLogger(RerankingService.class);

    @Autowired
    private WebClient.Builder webClientBuilder;

    @Autowired
    private ObjectMapper objectMapper;

    @Value("${app.llm.chat-model:Qwen2-7B-Instruct-Q5_K_M.gguf}")
    private String chatModel;

    @Value("${app.llm.chat-url:http://localhost:8080/v1/chat/completions}")
    private String chatUrl;

    @Value("${app.llm.timeout:120000}")
    private long timeout;

    /** Reranking 默认返回数量 */
    private static final int DEFAULT_RERANK_TOP_K = 20;

    /** LLM-as-a-Judge prompt */
    private static final String RERANK_PROMPT = """
            你是一个专业的文档相关性评估专家。请评估以下查询与文档的相关性。

            查询：%s

            文档内容：
            %s

            请从以下角度评估（每项1-5分）：
            1. 主题相关性：文档是否讨论了查询涉及的主题？
            2. 信息覆盖：文档是否包含回答查询所需的信息？
            3. 语义匹配：文档是否用与查询相同或相近的概念？

            请直接给出一个0-100的总分（0=完全不相关，100=完全相关），只需输出数字：
            """;

    /**
     * 重排序候选文档
     *
     * @param query 用户查询
     * @param candidates 候选文档列表
     * @param topK 返回前 topK 个
     * @return 按相关性分数排序的文档列表
     */
    public List<Map<String, Object>> rerank(String query, List<Map<String, Object>> candidates, int topK) {
        return rerank(query, candidates, topK, DEFAULT_RERANK_TOP_K);
    }

    /**
     * 重排序候选文档（可配置参数）
     *
     * @param query 用户查询
     * @param candidates 候选文档列表
     * @param topK 最终返回数量
     * @param crossEncodeLimit 发送给 LLM 评分的文档数量上限
     */
    public List<Map<String, Object>> rerank(String query, List<Map<String, Object>> candidates,
                                             int topK, int crossEncodeLimit) {
        if (candidates == null || candidates.isEmpty()) {
            return Collections.emptyList();
        }

        long start = System.currentTimeMillis();
        int n = Math.min(candidates.size(), crossEncodeLimit);

        log.info("[Reranker] Reranking {} candidates with LLM-as-a-Judge", n);

        // 1. 获取 LLM 相关性评分
        Map<String, Double> llmScores = new HashMap<>();
        for (int i = 0; i < n; i++) {
            Map<String, Object> doc = candidates.get(i);
            String docText = String.valueOf(doc.getOrDefault("text", ""));

            double score = scoreWithLLM(query, docText);
            llmScores.put(String.valueOf(doc.get("id")), score);
        }

        // 2. 融合多维度分数
        List<Map<String, Object>> scoredDocs = new ArrayList<>();
        for (int i = 0; i < candidates.size(); i++) {
            Map<String, Object> doc = candidates.get(i);
            String docId = String.valueOf(doc.get("id"));

            // 原始分数（向量搜索或 BM25）
            double originalScore = 0.0;
            Object origScore = doc.get("score");
            if (origScore instanceof Double) {
                originalScore = (Double) origScore;
            } else if (origScore instanceof Number) {
                originalScore = ((Number) origScore).doubleValue();
            }

            // LLM 相关性评分
            double llmScore = llmScores.getOrDefault(docId, 0.0);

            // 原始排名（用于打破平局）
            double originalRank = i + 1;

            // 综合分数：LLM 评分权重 0.7，原始分数权重 0.3
            double finalScore = llmScore * 0.7 + (100 - originalRank) / 100.0 * 30;

            Map<String, Object> scored = new HashMap<>(doc);
            scored.put("llm_score", llmScore);
            scored.put("original_score", originalScore);
            scored.put("final_score", finalScore);
            scoredDocs.add(scored);
        }

        // 3. 按综合分数排序
        scoredDocs.sort((a, b) -> {
            double scoreA = ((Number) a.getOrDefault("final_score", 0.0)).doubleValue();
            double scoreB = ((Number) b.getOrDefault("final_score", 0.0)).doubleValue();
            return Double.compare(scoreB, scoreA); // 降序
        });

        // 4. 返回 topK
        List<Map<String, Object>> results = scoredDocs.stream()
                .limit(topK)
                .collect(Collectors.toList());

        // 添加排名
        for (int i = 0; i < results.size(); i++) {
            results.get(i).put("rerank_rank", i + 1);
        }

        log.info("[Reranker] reranked {} -> {} in {}ms",
                candidates.size(), results.size(), System.currentTimeMillis() - start);

        return results;
    }

    /**
     * 使用 LLM 对文档评分 (0-100)
     */
    private double scoreWithLLM(String query, String docText) {
        if (docText == null || docText.trim().isEmpty()) {
            return 0.0;
        }

        // 截断过长的文档
        String truncatedDoc = docText.length() > 2000
                ? docText.substring(0, 2000) + "..."
                : docText;

        try {
            String prompt = String.format(RERANK_PROMPT, query, truncatedDoc);

            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("model", chatModel);
            requestBody.put("temperature", 0.1); // 低温度确保评分一致
            requestBody.put("max_tokens", 10);

            List<Map<String, Object>> messages = new ArrayList<>();
            messages.add(Map.of("role", "user", "content", prompt));
            requestBody.put("messages", messages);

            String response = webClientBuilder.build()
                    .post()
                    .uri(chatUrl)
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .bodyValue(requestBody)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block(java.time.Duration.ofMillis(timeout));

            // 解析响应，提取数字评分
            String content = extractContent(response);

            // 尝试提取数字
            String numberStr = content.replaceAll("[^0-9.]", "").trim();
            if (!numberStr.isEmpty()) {
                try {
                    double score = Double.parseDouble(numberStr);
                    return Math.min(100, Math.max(0, score));
                } catch (NumberFormatException e) {
                    // 尝试匹配百分比
                    if (content.contains("100")) return 100;
                    if (content.contains("80")) return 80;
                    if (content.contains("60")) return 60;
                    if (content.contains("40")) return 40;
                    if (content.contains("20")) return 20;
                    if (content.contains("0")) return 0;
                }
            }

            log.warn("[Reranker] Failed to parse LLM score: {}", content);
            return 50.0; // 默认中性分数

        } catch (Exception e) {
            log.error("[Reranker] LLM scoring failed: {}", e.getMessage());
            return 50.0;
        }
    }

    /**
     * 从 LLM 响应中提取内容
     */
    private String extractContent(String response) {
        try {
            Map<String, Object> resp = objectMapper.readValue(response, Map.class);
            Object choices = resp.get("choices");
            if (choices instanceof List && !((List<?>) choices).isEmpty()) {
                Object firstChoice = ((List<?>) choices).get(0);
                if (firstChoice instanceof Map) {
                    Map<?, ?> choice = (Map<?, ?>) firstChoice;
                    Object message = choice.get("message");
                    if (message instanceof Map) {
                        Object content = ((Map<?, ?>) message).get("content");
                        if (content != null) {
                            return content.toString().trim();
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.error("[Reranker] Failed to parse response: {}", e.getMessage());
        }
        return "";
    }

    /**
     * 轻量级重排序：仅使用原始分数，无需 LLM 调用
     * 适用于对延迟敏感的场景
     */
    public List<Map<String, Object>> rerankLight(List<Map<String, Object>> candidates, int topK) {
        if (candidates == null || candidates.isEmpty()) {
            return Collections.emptyList();
        }

        // 按原始分数排序
        List<Map<String, Object>> sorted = new ArrayList<>(candidates);
        sorted.sort((a, b) -> {
            double scoreA = getNumericScore(a);
            double scoreB = getNumericScore(b);
            return Double.compare(scoreB, scoreA);
        });

        return sorted.stream().limit(topK).collect(Collectors.toList());
    }

    /**
     * 提取数值分数
     */
    private double getNumericScore(Map<String, Object> doc) {
        Object score = doc.get("score");
        if (score instanceof Double) return (Double) score;
        if (score instanceof Number) return ((Number) score).doubleValue();
        return 0.0;
    }

    /**
     * 获取重排序统计信息
     */
    public Map<String, Object> getStats() {
        Map<String, Object> stats = new HashMap<>();
        stats.put("type", "reranker");
        stats.put("version", "1.0.0");
        return stats;
    }
}
