package com.luanshuai.agent.service.search;

import com.luanshuai.agent.service.MilvusDbService;
import com.luanshuai.agent.service.llm.LlamaCppEmbeddingModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 混合搜索服务
 *
 * 将 BM25 稀疏检索与 Milvus 向量搜索结果通过 RRF (Reciprocal Rank Fusion) 融合
 *
 * RRF 公式: score(d) = Σ 1 / (k + rank(d))
 * - k: 平滑参数 (通常 60)
 * - rank(d): 该文档在不同检索结果中的排名
 *
 * 优势：
 * - 向量搜索：捕获语义相似性
 * - BM25：精准关键词匹配
 * - RRF 融合：平衡两者优点
 */
@Service
public class HybridSearchService {

    private static final Logger log = LoggerFactory.getLogger(HybridSearchService.class);

    /** RRF 平滑参数 */
    private static final int RRF_K = 60;

    /** 默认向量搜索返回数量 */
    private static final int DEFAULT_VECTOR_TOP_K = 100;

    /** 默认 BM25 返回数量 */
    private static final int DEFAULT_BM25_TOP_K = 100;

    @Autowired
    private Bm25Service bm25Service;

    @Autowired
    private MilvusDbService milvusDbService;

    @Autowired
    private LlamaCppEmbeddingModel embeddingModel;

    /**
     * 执行混合搜索
     *
     * @param query 用户查询
     * @param collectionName Milvus 集合名
     * @param topK 最终返回结果数
     * @return RRF 融合后的文档列表
     */
    public List<Map<String, Object>> search(String query, String collectionName, int topK) {
        return search(query, collectionName, topK, DEFAULT_VECTOR_TOP_K, DEFAULT_BM25_TOP_K);
    }

    /**
     * 执行混合搜索（可配置参数）
     *
     * @param query 用户查询
     * @param collectionName Milvus 集合名
     * @param topK 最终返回结果数
     * @param vectorTopK 向量搜索返回数量
     * @param bm25TopK BM25 返回数量
     * @return RRF 融合后的文档列表
     */
    public List<Map<String, Object>> search(String query, String collectionName, int topK,
                                            int vectorTopK, int bm25TopK) {
        long start = System.currentTimeMillis();

        // 1. 并行执行向量搜索和 BM25 搜索
        List<Map<String, Object>> vectorResults = Collections.emptyList();
        List<Map<String, Object>> bm25Results = Collections.emptyList();

        try {
            // 向量搜索
            vectorResults = vectorSearch(query, collectionName, vectorTopK);
        } catch (Exception e) {
            log.warn("[Hybrid] Vector search failed: {}", e.getMessage());
        }

        try {
            // BM25 搜索
            bm25Results = bm25Service.search(query, bm25TopK);
        } catch (Exception e) {
            log.warn("[Hybrid] BM25 search failed: {}", e.getMessage());
        }

        // 2. RRF 融合
        List<Map<String, Object>> fused = rrfFusion(vectorResults, bm25Results, topK);

        log.info("[Hybrid] search '{}' -> {} results (vec={}, bm25={}) in {}ms",
                query, fused.size(), vectorResults.size(), bm25Results.size(),
                System.currentTimeMillis() - start);

        return fused;
    }

    /**
     * 执行混合搜索（仅返回 ID 列表 + 分数，不含文本）
     * 用于需要按融合分数排序后再获取文档详情的场景
     */
    public List<Map<String, Object>> searchWithScores(String query, String collectionName, int topK) {
        long start = System.currentTimeMillis();

        List<Map<String, Object>> fullResults = search(query, collectionName, topK);

        List<Map<String, Object>> results = new ArrayList<>();
        for (Map<String, Object> r : fullResults) {
            Map<String, Object> item = new HashMap<>();
            item.put("id", String.valueOf(r.get("id")));
            item.put("score", r.get("rrf_score"));
            item.put("vector_rank", r.get("vector_rank"));
            item.put("bm25_rank", r.get("bm25_rank"));
            results.add(item);
        }

        log.debug("[Hybrid] searchWithScores '{}' -> {} in {}ms",
                query, results.size(), System.currentTimeMillis() - start);

        return results;
    }

    /**
     * 向量搜索（同步阻塞版 - 供非响应式方法使用）
     * 注意：调用方必须确保在 boundedElastic 线程执行，或使用 searchReactive
     */
    private List<Map<String, Object>> vectorSearch(String query, String collectionName, int topK) {
        List<Float> embedding = embeddingModel.embed(query)
                .vectorAsList()
                .stream()
                .map(d -> (float) d.doubleValue())
                .collect(java.util.stream.Collectors.toList());
        List<Map<String, Object>> results = milvusDbService.queryDocuments(collectionName, embedding, topK);
        for (int i = 0; i < results.size(); i++) {
            results.get(i).put("vector_rank", i + 1);
        }
        return results;
    }

    /**
     * 向量搜索（异步非阻塞版 - 响应式）
     * 使用 Mono.defer 在 boundedElastic 线程执行，避免阻塞 reactor 线程
     */
    private reactor.core.publisher.Mono<List<Map<String, Object>>> vectorSearchReactive(String query, String collectionName, int topK) {
        return reactor.core.publisher.Mono.defer(() -> {
            List<Float> embedding = embeddingModel.embed(query)
                    .vectorAsList()
                    .stream()
                    .map(d -> (float) d.doubleValue())
                    .collect(java.util.stream.Collectors.toList());
            List<Map<String, Object>> results = milvusDbService.queryDocuments(collectionName, embedding, topK);
            for (int i = 0; i < results.size(); i++) {
                results.get(i).put("vector_rank", i + 1);
            }
            return reactor.core.publisher.Mono.just(results);
        }).subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic());
    }

    /**
     * 执行混合搜索（完全响应式，返回 Mono）
     */
    public reactor.core.publisher.Mono<List<Map<String, Object>>> searchReactive(String query, String collectionName, int topK) {
        return searchReactive(query, collectionName, topK, DEFAULT_VECTOR_TOP_K, DEFAULT_BM25_TOP_K);
    }

    public reactor.core.publisher.Mono<List<Map<String, Object>>> searchReactive(String query, String collectionName, int topK,
                                            int vectorTopK, int bm25TopK) {
        long start = System.currentTimeMillis();

        // 并行执行向量搜索和 BM25 搜索
        reactor.core.publisher.Mono<List<Map<String, Object>>> vectorMono = vectorSearchReactive(query, collectionName, vectorTopK)
                .onErrorResume(e -> {
                    log.warn("[Hybrid] Vector search failed: {}", e.getMessage());
                    return reactor.core.publisher.Mono.just(java.util.Collections.emptyList());
                });

        reactor.core.publisher.Mono<List<Map<String, Object>>> bm25Mono = reactor.core.publisher.Mono.fromCallable(() -> {
                    List<Map<String, Object>> bm25Results = bm25Service.search(query, bm25TopK);
                    return bm25Results;
                })
                .subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic())
                .onErrorResume(e -> {
                    log.warn("[Hybrid] BM25 search failed: {}", e.getMessage());
                    return reactor.core.publisher.Mono.just(java.util.Collections.emptyList());
                });

        return reactor.core.publisher.Mono.zip(vectorMono, bm25Mono)
                .map(tuple -> {
                    List<Map<String, Object>> fused = rrfFusion(tuple.getT1(), tuple.getT2(), topK);
                    log.info("[Hybrid] search '{}' -> {} results (vec={}, bm25={}) in {}ms",
                            query, fused.size(), tuple.getT1().size(), tuple.getT2().size(),
                            System.currentTimeMillis() - start);
                    return fused;
                });
    }

    /**
     * RRF 融合
     *
     * @param vectorResults 向量搜索结果（按相关性降序）
     * @param bm25Results BM25 结果（按 BM25 分数降序）
     * @param topK 返回前 topK 个
     */
    private List<Map<String, Object>> rrfFusion(List<Map<String, Object>> vectorResults,
                                                List<Map<String, Object>> bm25Results,
                                                int topK) {
        if (vectorResults.isEmpty() && bm25Results.isEmpty()) {
            return Collections.emptyList();
        }

        // 构建 docId -> BM25 score 映射
        Map<String, Double> bm25Scores = new HashMap<>();
        for (Map<String, Object> doc : bm25Results) {
            String id = String.valueOf(doc.get("id"));
            Double score = ((Number) doc.getOrDefault("score", 0.0)).doubleValue();
            bm25Scores.put(id, score);
        }

        // 构建 docId -> vector score 映射
        Map<String, Double> vectorScores = new HashMap<>();
        for (Map<String, Object> doc : vectorResults) {
            String id = String.valueOf(doc.get("id"));
            Double score = ((Number) doc.getOrDefault("score", 0.0)).doubleValue();
            vectorScores.put(id, score);
        }

        // 计算 RRF 分数
        Map<String, Double> rrfScores = new HashMap<>();
        Map<String, Map<String, Object>> docMap = new HashMap<>();

        // 向量搜索贡献
        for (int i = 0; i < vectorResults.size(); i++) {
            Map<String, Object> doc = vectorResults.get(i);
            String id = String.valueOf(doc.get("id"));
            docMap.put(id, doc);
            double rrfContrib = 1.0 / (RRF_K + i + 1);
            rrfScores.merge(id, rrfContrib, Double::sum);
        }

        // BM25 贡献
        for (int i = 0; i < bm25Results.size(); i++) {
            Map<String, Object> doc = bm25Results.get(i);
            String id = String.valueOf(doc.get("id"));
            if (!docMap.containsKey(id)) {
                docMap.put(id, doc);
            }
            double rrfContrib = 1.0 / (RRF_K + i + 1);
            rrfScores.merge(id, rrfContrib, Double::sum);
        }

        // 按 RRF 分数排序
        List<Map.Entry<String, Double>> sorted = rrfScores.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .limit(topK)
                .collect(Collectors.toList());

        // 构建返回结果
        List<Map<String, Object>> results = new ArrayList<>();
        for (int rank = 0; rank < sorted.size(); rank++) {
            Map.Entry<String, Double> entry = sorted.get(rank);
            String docId = entry.getKey();
            Map<String, Object> baseDoc = docMap.get(docId);

            Map<String, Object> result = new HashMap<>();
            result.put("id", docId);
            result.put("rrf_score", entry.getValue());

            // 保留向量分数
            if (vectorScores.containsKey(docId)) {
                result.put("vector_score", vectorScores.get(docId));
            }
            // 保留 BM25 分数
            if (bm25Scores.containsKey(docId)) {
                result.put("bm25_score", bm25Scores.get(docId));
            }

            // 保留文本
            Object text = baseDoc.get("text");
            if (text != null) {
                result.put("text", text);
            }

            // 保留 metadata
            Object metadata = baseDoc.get("metadata");
            if (metadata != null) {
                result.put("metadata", metadata);
            }

            // RRF 排名
            result.put("rrf_rank", rank + 1);

            // 原始排名
            Object vecRank = baseDoc.get("vector_rank");
            Object bm25Rank = baseDoc.get("bm25_rank");
            if (vecRank != null) {
                result.put("vector_rank", vecRank);
            }
            if (bm25Rank != null) {
                result.put("bm25_rank", bm25Rank);
            }

            results.add(result);
        }

        return results;
    }

    /**
     * 获取搜索统计信息
     */
    public Map<String, Object> getStats() {
        Map<String, Object> stats = new HashMap<>();
        stats.put("bm25Stats", bm25Service.getStats());
        return stats;
    }
}
