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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Chain 执行器 (Chain Executor)
 *
 * 实现模块化的 RAG 流程编排，支持：
 * 1. RetrievalChain: 检索 -> 重排序 -> 生成
 * 2. ConversationChain: 对话历史 + 上下文检索
 * 3. SubQuestionChain: 子问题分解 -> 分别检索 -> 综合答案
 *
 * 设计原则：
 * - 每个 Chain 可独立配置
 * - 支持条件分支（根据查询类型选择不同流程）
 * - 支持并行检索（多个查询同时执行）
 */
@Service
public class ChainExecutor {

    private static final Logger log = LoggerFactory.getLogger(ChainExecutor.class);

    @Autowired
    private WebClient.Builder webClientBuilder;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private HybridSearchService hybridSearchService;

    @Autowired
    private QueryRewriteService queryRewriteService;

    @Autowired
    private RerankingService rerankingService;

    @Value("${app.llm.chat-model:Qwen2-7B-Instruct-Q5_K_M.gguf}")
    private String chatModel;

    @Value("${app.llm.chat-url:http://localhost:8080/v1/chat/completions}")
    private String chatUrl;

    @Value("${app.llm.timeout:120000}")
    private long timeout;

    /** 检索候选文档数量 */
    private static final int RETRIEVAL_CANDIDATES = 100;

    /** 最终返回文档数量 */
    private static final int FINAL_TOP_K = 10;

    /**
     * 执行标准 RAG Chain
     *
     * 流程：Query Rewrite -> Hybrid Search -> Rerank -> Generate
     *
     * @param query 用户查询
     * @param collectionName Milvus 集合名
     * @param enableRewrite 是否启用 Query Rewrite
     * @param enableRerank 是否启用 Rerank
     * @return Chain 执行结果
     */
    public ChainResult executeRetrievalChain(String query, String collectionName,
                                             boolean enableRewrite, boolean enableRerank) {
        long start = System.currentTimeMillis();
        ChainResult result = new ChainResult(query);

        try {
            // Step 1: Query Rewrite (可选)
            String searchQuery = query;
            List<String> expandedQueries = Collections.emptyList();

            if (enableRewrite) {
                QueryRewriteService.QueryRewriteResult rewriteResult =
                        queryRewriteService.rewriteWithHyde(query);
                searchQuery = rewriteResult.getOriginalQuery();
                expandedQueries = rewriteResult.getExpandedQueries();
                result.setQueryRewrite(rewriteResult);
            }

            // Step 2: Hybrid Search
            List<Map<String, Object>> candidates;
            if (expandedQueries.isEmpty()) {
                candidates = hybridSearchService.search(query, collectionName, RETRIEVAL_CANDIDATES);
            } else {
                // 并行执行多个查询
                candidates = parallelSearch(expandedQueries, collectionName);
            }
            result.setCandidates(candidates);

            // Step 3: Rerank (可选)
            List<Map<String, Object>> reranked;
            if (enableRerank && !candidates.isEmpty()) {
                reranked = rerankingService.rerank(query, candidates, FINAL_TOP_K, RETRIEVAL_CANDIDATES);
            } else {
                reranked = candidates.stream().limit(FINAL_TOP_K).collect(Collectors.toList());
            }
            result.setRerankedDocs(reranked);

            result.setSuccess(true);
            log.info("[Chain] Retrieval chain completed in {}ms (candidates={}, final={})",
                    System.currentTimeMillis() - start, candidates.size(), reranked.size());

        } catch (Exception e) {
            log.error("[Chain] Retrieval chain failed: {}", e.getMessage());
            result.setSuccess(false);
            result.setError(e.getMessage());
        }

        result.setLatencyMs(System.currentTimeMillis() - start);
        return result;
    }

    /**
     * 执行 SubQuestion Chain（子问题分解）
     *
     * 流程：分解 -> 分别检索 -> 综合生成
     *
     * @param query 用户查询
     * @param collectionName Milvus 集合名
     * @return Chain 执行结果
     */
    public ChainResult executeSubQuestionChain(String query, String collectionName) {
        long start = System.currentTimeMillis();
        ChainResult result = new ChainResult(query);

        try {
            // Step 1: 分解查询
            List<String> subQuestions = queryRewriteService.decomposeQuery(query);
            if (subQuestions.isEmpty()) {
                // 分解失败，回退到标准流程
                return executeRetrievalChain(query, collectionName, true, true);
            }

            result.setSubQuestions(subQuestions);
            log.info("[Chain] Decomposed into {} sub-questions", subQuestions.size());

            // Step 2: 并行检索
            Map<String, List<Map<String, Object>>> subResults = new HashMap<>();
            for (String subQ : subQuestions) {
                List<Map<String, Object>> docs = hybridSearchService.search(subQ, collectionName, 20);
                subResults.put(subQ, docs);
            }
            result.setSubQuestionResults(subResults);

            // Step 3: 综合答案
            String combinedContext = buildContextFromSubResults(subResults);
            List<Map<String, Object>> combinedDocs = subResults.values().stream()
                    .flatMap(List::stream)
                    .distinct()
                    .collect(Collectors.toList());

            result.setCandidates(combinedDocs);
            result.setRerankedDocs(combinedDocs.stream().limit(FINAL_TOP_K).collect(Collectors.toList()));
            result.setCombinedContext(combinedContext);

            result.setSuccess(true);
            log.info("[Chain] SubQuestion chain completed in {}ms ({} sub-questions)",
                    System.currentTimeMillis() - start, subQuestions.size());

        } catch (Exception e) {
            log.error("[Chain] SubQuestion chain failed: {}", e.getMessage());
            result.setSuccess(false);
            result.setError(e.getMessage());
        }

        result.setLatencyMs(System.currentTimeMillis() - start);
        return result;
    }

    /**
     * 执行标准 RAG Chain（流式 SSE 版本）
     *
     * 流程：Query Rewrite -> Hybrid Search -> Rerank -> 流式 LLM 生成
     * 每一步结果都通过 SSE 帧发送，供 TokenUsageWebFilter 解析 token 使用量。
     *
     * SSE 帧格式：
     * - data: {"type":"rewrite","content":"改写后的查询"}\n\n
     * - data: {"type":"sources","content":"文档内容(截断)","count":N,"ids":[...]}\n\n
     * - data: {"type":"answer","content":"部分回答"}\n\n (增量发送)
     * - data: {"type":"token_usage","inputTokens":I,"outputTokens":O}\n\n
     * - data: {"type":"done"}\n\n
     *
     * @param query 用户查询
     * @param collectionName Milvus 集合名
     * @param enableRewrite 是否启用 Query Rewrite
     * @param enableRerank 是否启用 Rerank
     * @return SSE 流 (Flux<String>)
     */
    public Flux<String> executeRetrievalChainStream(String query, String collectionName,
                                                    boolean enableRewrite, boolean enableRerank) {
        long start = System.currentTimeMillis();
        AtomicInteger inputTokens = new AtomicInteger(0);
        AtomicInteger outputTokens = new AtomicInteger(0);
        String rewrittenQuery = query;
        List<Map<String, Object>> rerankedDocs = Collections.emptyList();

        try {
            // Step 1: Query Rewrite (可选)
            if (enableRewrite) {
                QueryRewriteService.QueryRewriteResult rewriteResult =
                        queryRewriteService.rewriteWithHyde(query);
                rewrittenQuery = rewriteResult.getOriginalQuery();

                String hydeDoc = rewriteResult.getHypotheticalDoc();
                String content = hydeDoc != null && hydeDoc.length() > 500
                        ? hydeDoc.substring(0, 500) + "..." : (hydeDoc != null ? hydeDoc : "");
                String rewriteFrame = String.format(
                        "data: {\"type\":\"rewrite\",\"content\":\"%s\"}\n\n",
                        escapeJson(content));
                return Flux.just(rewriteFrame).concatWith(processRetrievalStream(query, rewrittenQuery,
                        collectionName, enableRerank, inputTokens, outputTokens, start));
            }

        } catch (Exception e) {
            log.warn("[Chain] Query rewrite failed in stream, using original query: {}", e.getMessage());
        }

        // 无需 rewrite 时直接进入检索流程
        return processRetrievalStream(query, rewrittenQuery, collectionName,
                enableRerank, inputTokens, outputTokens, start);
    }

    /**
     * 执行 SubQuestion Chain（流式 SSE 版本）
     *
     * 流程：分解 -> 分别检索 -> 流式综合生成
     */
    public Flux<String> executeSubQuestionChainStream(String query, String collectionName) {
        long start = System.currentTimeMillis();
        AtomicInteger inputTokens = new AtomicInteger(0);
        AtomicInteger outputTokens = new AtomicInteger(0);

        try {
            // Step 1: 分解查询
            List<String> subQuestions = queryRewriteService.decomposeQuery(query);
            if (subQuestions.isEmpty()) {
                // 分解失败，回退到标准流式流程
                return executeRetrievalChainStream(query, collectionName, true, true);
            }

            int count = subQuestions.size();
            String decomposeFrame = String.format(
                    "data: {\"type\":\"subquestions\",\"content\":\"分解为%d个子问题\",\"count\":%d,\"questions\":[%s]}\n\n",
                    count, count,
                    subQuestions.stream().map(q -> "\"" + escapeJson(q) + "\"").collect(Collectors.joining(","))
            );

            // Step 2: 并行检索所有子问题
            Map<String, List<Map<String, Object>>> subResults = new HashMap<>();
            List<CompletableFuture<Void>> futures = new ArrayList<>();

            for (String subQ : subQuestions) {
                CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                    try {
                        List<Map<String, Object>> docs = hybridSearchService.search(subQ, collectionName, 20);
                        subResults.put(subQ, docs);
                    } catch (Exception e) {
                        log.warn("[Chain] Sub-question search failed for '{}': {}", subQ, e.getMessage());
                        subResults.put(subQ, Collections.emptyList());
                    }
                });
                futures.add(future);
            }

            // 等待所有检索完成
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).get(timeout, TimeUnit.MILLISECONDS);

            // Step 3: 流式发送检索结果
            Flux<String> sourcesFlux = buildSourcesFlux(subResults, collectionName);

            // Step 4: 构建综合上下文并流式生成
            String combinedContext = buildContextFromSubResults(subResults);
            List<Map<String, Object>> combinedDocs = subResults.values().stream()
                    .flatMap(List::stream)
                    .distinct()
                    .limit(50)
                    .collect(Collectors.toList());

            // 流式 LLM 生成
            Flux<String> answerFlux = streamChatWithContext(query, combinedDocs, combinedContext,
                    inputTokens, outputTokens);

            // Step 5: 发送结束帧
            Flux<String> doneFlux = Flux.defer(() -> {
                String finalFrame = String.format(
                        "data: {\"type\":\"token_usage\",\"inputTokens\":%d,\"outputTokens\":%d}\n\n" +
                                "data: {\"type\":\"done\"}\n\n",
                        inputTokens.get(), outputTokens.get());
                return Flux.just(finalFrame);
            });

            long latency = System.currentTimeMillis() - start;
            String latencyFrame = String.format(
                    "data: {\"type\":\"latency\",\"content\":\"SubQuestion Chain 完成，耗时 %dms\"}\n\n",
                    latency);

            return Flux.just(decomposeFrame, latencyFrame).concatWith(sourcesFlux)
                    .concatWith(answerFlux).concatWith(doneFlux);

        } catch (Exception e) {
            log.error("[Chain] SubQuestion chain stream failed: {}", e.getMessage());
            String errorFrame = String.format(
                    "data: {\"type\":\"error\",\"content\":\"SubQuestion Chain 失败: %s\"}\n\n",
                    escapeJson(e.getMessage()));
            return Flux.just(errorFrame);
        }
    }

    /**
     * 处理检索 + 流式生成的主流程
     */
    private Flux<String> processRetrievalStream(String query, String rewrittenQuery,
                                                String collectionName, boolean enableRerank,
                                                AtomicInteger inputTokens, AtomicInteger outputTokens, long startTime) {
        try {
            // Hybrid Search
            List<Map<String, Object>> candidates = hybridSearchService.search(
                    rewrittenQuery.isEmpty() ? query : rewrittenQuery, collectionName, RETRIEVAL_CANDIDATES);

            // Rerank (可选)
            List<Map<String, Object>> reranked;
            if (enableRerank && !candidates.isEmpty()) {
                reranked = rerankingService.rerank(query, candidates, FINAL_TOP_K, RETRIEVAL_CANDIDATES);
            } else {
                reranked = candidates.stream().limit(FINAL_TOP_K).collect(Collectors.toList());
            }

            final List<Map<String, Object>> finalDocs = reranked;

            // 流式发送 sources
            Flux<String> sourcesFlux = buildSingleSourcesFlux(query, finalDocs);

            // 流式 LLM 生成
            Flux<String> answerFlux = streamChatWithContext(query, finalDocs, null,
                    inputTokens, outputTokens);

            // 结束帧
            Flux<String> doneFlux = Flux.defer(() -> {
                long latency = System.currentTimeMillis() - startTime;
                String finalFrame = String.format(
                        "data: {\"type\":\"token_usage\",\"inputTokens\":%d,\"outputTokens\":%d}\n\n" +
                                "data: {\"type\":\"done\"}\n\n",
                        inputTokens.get(), outputTokens.get());
                log.info("[Chain] Retrieval stream completed: {}ms, tokens={}+{}",
                        latency, inputTokens.get(), outputTokens.get());
                return Flux.just(finalFrame);
            });

            return sourcesFlux.concatWith(answerFlux).concatWith(doneFlux);

        } catch (Exception e) {
            log.error("[Chain] processRetrievalStream failed: {}", e.getMessage());
            return Flux.just(String.format(
                    "data: {\"type\":\"error\",\"content\":\"检索失败: %s\"}\n\n",
                    escapeJson(e.getMessage())));
        }
    }

    /**
     * 构建单次检索的 sources SSE 帧
     */
    private Flux<String> buildSingleSourcesFlux(String query, List<Map<String, Object>> docs) {
        if (docs == null || docs.isEmpty()) {
            return Flux.just("data: {\"type\":\"sources\",\"content\":\"未找到相关文档\",\"count\":0}\n\n");
        }

        StringBuilder ids = new StringBuilder();
        StringBuilder preview = new StringBuilder();

        for (int i = 0; i < Math.min(docs.size(), 5); i++) {
            Map<String, Object> doc = docs.get(i);
            if (i > 0) ids.append(",");
            ids.append("\"").append(escapeJson(String.valueOf(doc.get("id")))).append("\"");

            String text = String.valueOf(doc.getOrDefault("text", ""));
            if (text.length() > 200) text = text.substring(0, 200) + "...";
            if (i > 0) preview.append("\n---\n");
            preview.append("[").append(i + 1).append("] ").append(text);
        }

        return Flux.just(String.format(
                "data: {\"type\":\"sources\",\"content\":\"%s\",\"count\":%d,\"ids\":[%s]}\n\n",
                escapeJson(preview.toString()), docs.size(), ids.toString()));
    }

    /**
     * 构建多源检索的 sources SSE 帧
     */
    private Flux<String> buildSourcesFlux(Map<String, List<Map<String, Object>>> subResults,
                                          String collectionName) {
        int totalCount = subResults.values().stream().mapToInt(List::size).sum();
        if (totalCount == 0) {
            return Flux.just("data: {\"type\":\"sources\",\"content\":\"未找到相关文档\",\"count\":0}\n\n");
        }

        StringBuilder preview = new StringBuilder();
        StringBuilder ids = new StringBuilder();
        int idx = 0;

        for (Map.Entry<String, List<Map<String, Object>>> entry : subResults.entrySet()) {
            String subQ = entry.getKey();
            List<Map<String, Object>> docs = entry.getValue();

            if (preview.length() > 0) preview.append("\n\n");
            preview.append("【").append(subQ).append("】");
            idx = 0;

            for (Map<String, Object> doc : docs.stream().limit(3).collect(Collectors.toList())) {
                String text = String.valueOf(doc.getOrDefault("text", ""));
                if (text.length() > 150) text = text.substring(0, 150) + "...";
                if (idx > 0) preview.append("; ");
                preview.append(text);
                idx++;
            }

            for (Map<String, Object> doc : docs.stream().limit(5).collect(Collectors.toList())) {
                if (ids.length() > 0) ids.append(",");
                ids.append("\"").append(escapeJson(String.valueOf(doc.get("id")))).append("\"");
            }
        }

        return Flux.just(String.format(
                "data: {\"type\":\"sources\",\"content\":\"%s\",\"count\":%d,\"ids\":[%s]}\n\n",
                escapeJson(preview.toString()), totalCount, ids.toString()));
    }

    /**
     * 流式 LLM 生成（带上下文）
     */
    private Flux<String> streamChatWithContext(String query, List<Map<String, Object>> documents,
                                               String combinedContext, AtomicInteger inputTokens, AtomicInteger outputTokens) {
        StringBuilder context = new StringBuilder();
        if (combinedContext != null && !combinedContext.isEmpty()) {
            context.append(combinedContext).append("\n\n");
        } else {
            context.append("参考信息：\n");
            for (int i = 0; i < Math.min(documents.size(), 5); i++) {
                Map<String, Object> doc = documents.get(i);
                String text = String.valueOf(doc.getOrDefault("text", ""));
                context.append("[").append(i + 1).append("] ").append(text).append("\n\n");
            }
        }

        String prompt = String.format(
                "你是一个专业的知识库问答助手。请根据以下参考信息回答用户问题。\n\n" +
                        "如果参考信息中没有明确答案，请说明。\n" +
                        "如果参考信息中有相关内容，请基于内容准确回答。\n\n" +
                        "%s\n\n" +
                        "用户问题：%s\n\n" +
                        "回答：",
                context.toString(), query
        );

        // 估算 input tokens
        int estimatedInput = estimateInputTokens(prompt);
        inputTokens.set(estimatedInput);

        return streamChat(prompt, outputTokens);
    }

    /**
     * 流式调用 LLM，实时返回 SSE chunks
     */
    private Flux<String> streamChat(String prompt, AtomicInteger outputTokens) {
        try {
            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("model", chatModel);
            requestBody.put("temperature", 0.3);
            requestBody.put("max_tokens", 2000);
            requestBody.put("stream", true);

            List<Map<String, Object>> messages = new ArrayList<>();
            messages.add(Map.of("role", "user", "content", prompt));
            requestBody.put("messages", messages);

            return webClientBuilder.build()
                    .post()
                    .uri(chatUrl)
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.parseMediaType("application/x-ndjson"))
                    .bodyValue(requestBody)
                    .retrieve()
                    .bodyToFlux(String.class)
                    .takeUntil(line -> line == null || line.trim().isEmpty() ||
                            line.contains("[DONE]") || line.contains("done"))
                    .map(line -> {
                        // 解析 SSE 行: data: {...}
                        if (line.startsWith("data:")) {
                            String json = line.substring(5).trim();
                            return parseAndEmitAnswer(json, outputTokens);
                        }
                        return "";
                    })
                    .filter(frame -> !frame.isEmpty())
                    .onErrorResume(e -> {
                        log.warn("[Chain] Stream failed, falling back to non-streaming: {}", e.getMessage());
                        return streamFallback(prompt, outputTokens);
                    });

        } catch (Exception e) {
            log.error("[Chain] streamChat failed: {}", e.getMessage());
            return Flux.just(String.format(
                    "data: {\"type\":\"error\",\"content\":\"生成失败: %s\"}\n\n",
                    escapeJson(e.getMessage())));
        }
    }

    /**
     * 解析 OpenAI 兼容流式响应，提取增量内容并发送 SSE 帧
     */
    private String parseAndEmitAnswer(String json, AtomicInteger outputTokens) {
        try {
            Map<String, Object> data = objectMapper.readValue(json, Map.class);
            List<?> choices = (List<?>) data.get("choices");
            if (choices != null && !choices.isEmpty()) {
                Map<?, ?> choice = (Map<?, ?>) choices.get(0);
                Map<?, ?> delta = (Map<?, ?>) choice.get("delta");
                if (delta != null) {
                    Object content = delta.get("content");
                    if (content != null) {
                        String text = content.toString();
                        int chars = text.length();
                        outputTokens.addAndGet(chars / 4); // 粗略估算
                        return "data: {\"type\":\"answer\",\"content\":\"" + escapeJson(text) + "\"}\n\n";
                    }
                }
            }
            // 检查是否结束
            Boolean finish = (Boolean) ((Map<?, ?>) choices.get(0)).get("finish_reason");
            if (finish != null && finish) {
                return ""; // 空串表示结束标记
            }
        } catch (Exception e) {
            // 尝试解析 ndjson 行（某些 llama.cpp 格式）
            try {
                Map<String, Object> data = objectMapper.readValue(json, Map.class);
                if (data.containsKey("choices")) {
                    List<?> choices = (List<?>) data.get("choices");
                    if (choices != null && !choices.isEmpty() && choices.get(0) instanceof Map) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> choice = (Map<String, Object>) choices.get(0);
                        Object delta = choice.get("delta");
                        if (delta instanceof Map) {
                            @SuppressWarnings("unchecked")
                            Map<String, Object> deltaMap = (Map<String, Object>) delta;
                            Object content = deltaMap.get("content");
                            if (content != null) {
                                String text = content.toString();
                                outputTokens.addAndGet(text.length() / 4);
                                return "data: {\"type\":\"answer\",\"content\":\"" + escapeJson(text) + "\"}\n\n";
                            }
                        }
                    }
                }
            } catch (Exception ignored) {}
        }
        return "";
    }

    /**
     * 流式失败时的非流式降级
     */
    private Flux<String> streamFallback(String prompt, AtomicInteger outputTokens) {
        return Mono.fromCallable(() -> {
            String response = chatGenerate(prompt, false);
            outputTokens.set(estimateInputTokens(response));
            return "data: {\"type\":\"answer\",\"content\":\"" + escapeJson(response) + "\"}\n\n" +
                    "data: {\"type\":\"token_usage\",\"inputTokens\":0,\"outputTokens\":" + outputTokens.get() + "}\n\n";
        }).flux();
    }

    /**
     * 估算输入 token 数量
     */
    private int estimateInputTokens(String text) {
        if (text == null || text.isEmpty()) return 0;
        int chars = text.length();
        // 中英混合: 约 1 token ≈ 2.5 字符
        return Math.max(1, chars / 2);
    }

    /**
     * JSON 字符串转义
     */
    private String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    /**
     * 提取分数
     */
    private double getScore(Map<String, Object> doc) {
        Object score = doc.get("rrf_score");
        if (score instanceof Double) return (Double) score;
        if (score instanceof Number) return ((Number) score).doubleValue();
        score = doc.get("score");
        if (score instanceof Number) return ((Number) score).doubleValue();
        return 0.0;
    }

    /**
     * 并行执行多个查询并合并结果
     */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> parallelSearch(List<String> queries, String collectionName) {
        List<CompletableFuture<List<Map<String, Object>>>> futures = queries.stream()
                .map(q -> CompletableFuture.supplyAsync(() -> {
                    try {
                        return hybridSearchService.search(q, collectionName, 50);
                    } catch (Exception e) {
                        log.warn("[Chain] Parallel search failed for '{}': {}", q, e.getMessage());
                        return Collections.<Map<String, Object>>emptyList();
                    }
                }))
                .collect(Collectors.toList());

        // 等待所有查询完成
        List<List<Map<String, Object>>> allResults = new ArrayList<>();
        for (CompletableFuture<List<Map<String, Object>>> future : futures) {
            try {
                allResults.add(future.get(timeout, TimeUnit.MILLISECONDS));
            } catch (Exception e) {
                allResults.add(Collections.<Map<String, Object>>emptyList());
            }
        }

        // 合并结果（去重 + 按分数排序）
        Map<String, Double> docScores = new LinkedHashMap<>();
        Map<String, Map<String, Object>> docMap = new HashMap<>();

        for (List<Map<String, Object>> results : allResults) {
            for (Map<String, Object> doc : results) {
                String id = String.valueOf(doc.get("id"));
                double score = getScore(doc);
                docScores.merge(id, score, Double::sum);
                docMap.putIfAbsent(id, doc);
            }
        }

        // 按合并分数排序
        List<Map.Entry<String, Double>> sorted = docScores.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .collect(Collectors.toList());

        return sorted.stream()
                .map(e -> docMap.get(e.getKey()))
                .collect(Collectors.toList());
    }

    /**
     * 从子问题检索结果构建上下文
     */
    private String buildContextFromSubResults(Map<String, List<Map<String, Object>>> subResults) {
        StringBuilder sb = new StringBuilder();
        sb.append("以下是从多个角度检索到的相关信息：\n\n");

        int idx = 1;
        for (Map.Entry<String, List<Map<String, Object>>> entry : subResults.entrySet()) {
            sb.append("【问题").append(idx).append("】").append(entry.getKey()).append("\n");

            List<Map<String, Object>> docs = entry.getValue();
            for (int i = 0; i < Math.min(3, docs.size()); i++) {
                Map<String, Object> doc = docs.get(i);
                String text = String.valueOf(doc.getOrDefault("text", ""));
                if (text.length() > 300) {
                    text = text.substring(0, 300) + "...";
                }
                sb.append("- ").append(text).append("\n\n");
            }
            idx++;
        }

        return sb.toString();
    }

    /**
     * 生成答案（使用 RAG 上下文）
     */
    public String generateWithContext(String query, List<Map<String, Object>> documents,
                                     boolean streaming) {
        return generateWithContext(query, documents, null, streaming);
    }

    /**
     * 生成答案（使用指定上下文）
     */
    public String generateWithContext(String query, List<Map<String, Object>> documents,
                                      String combinedContext, boolean streaming) {
        StringBuilder context = new StringBuilder();
        if (combinedContext != null && !combinedContext.isEmpty()) {
            context.append(combinedContext).append("\n\n");
        } else {
            context.append("参考信息：\n");
            for (int i = 0; i < Math.min(documents.size(), 5); i++) {
                Map<String, Object> doc = documents.get(i);
                String text = String.valueOf(doc.getOrDefault("text", ""));
                context.append("[").append(i + 1).append("] ").append(text).append("\n\n");
            }
        }

        String prompt = String.format(
                "你是一个专业的知识库问答助手。请根据以下参考信息回答用户问题。\n\n" +
                        "如果参考信息中没有明确答案，请说明。" +
                        "如果参考信息中有相关内容，请基于内容准确回答。\n\n" +
                        "%s\n\n" +
                        "用户问题：%s\n\n" +
                        "回答：",
                context.toString(), query
        );

        return chatGenerate(prompt, streaming);
    }

    /**
     * 调用 LLM 生成内容
     */
    public String chatGenerate(String prompt, boolean streaming) {
        try {
            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("model", chatModel);
            requestBody.put("temperature", 0.3);
            requestBody.put("max_tokens", 2000);

            List<Map<String, Object>> messages = new ArrayList<>();
            messages.add(Map.of("role", "user", "content", prompt));
            requestBody.put("messages", messages);

            if (streaming) {
                requestBody.put("stream", true);
            }

            String response = webClientBuilder.build()
                    .post()
                    .uri(chatUrl)
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .bodyValue(requestBody)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block(java.time.Duration.ofMillis(timeout));

            return extractContent(response);

        } catch (Exception e) {
            log.error("[Chain] chatGenerate failed: {}", e.getMessage());
            return "生成答案时发生错误：" + e.getMessage();
        }
    }

    /**
     * 提取响应内容
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
                            return content.toString();
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.error("[Chain] Failed to parse response: {}", e.getMessage());
        }
        return "";
    }

    /**
     * Chain 执行结果
     */
    public static class ChainResult {
        private String query;
        private boolean success;
        private String error;
        private long latencyMs;

        // 中间结果
        private QueryRewriteService.QueryRewriteResult queryRewrite;
        private List<String> subQuestions;
        private Map<String, List<Map<String, Object>>> subQuestionResults;
        private List<Map<String, Object>> candidates;
        private List<Map<String, Object>> rerankedDocs;
        private String combinedContext;
        private String generatedAnswer;

        public ChainResult(String query) {
            this.query = query;
            this.success = false;
        }

        public String getQuery() { return query; }
        public boolean isSuccess() { return success; }
        public String getError() { return error; }
        public long getLatencyMs() { return latencyMs; }
        public List<Map<String, Object>> getCandidates() { return candidates; }
        public List<Map<String, Object>> getRerankedDocs() { return rerankedDocs; }
        public String getGeneratedAnswer() { return generatedAnswer; }
        public QueryRewriteService.QueryRewriteResult getQueryRewrite() { return queryRewrite; }
        public List<String> getSubQuestions() { return subQuestions; }
        public Map<String, List<Map<String, Object>>> getSubQuestionResults() { return subQuestionResults; }
        public String getCombinedContext() { return combinedContext; }

        public void setSuccess(boolean success) { this.success = success; }
        public void setError(String error) { this.error = error; }
        public void setLatencyMs(long latencyMs) { this.latencyMs = latencyMs; }
        public void setCandidates(List<Map<String, Object>> candidates) { this.candidates = candidates; }
        public void setRerankedDocs(List<Map<String, Object>> rerankedDocs) { this.rerankedDocs = rerankedDocs; }
        public void setGeneratedAnswer(String generatedAnswer) { this.generatedAnswer = generatedAnswer; }
        public void setQueryRewrite(QueryRewriteService.QueryRewriteResult queryRewrite) { this.queryRewrite = queryRewrite; }
        public void setSubQuestions(List<String> subQuestions) { this.subQuestions = subQuestions; }
        public void setSubQuestionResults(Map<String, List<Map<String, Object>>> subQuestionResults) { this.subQuestionResults = subQuestionResults; }
        public void setCombinedContext(String combinedContext) { this.combinedContext = combinedContext; }

        /**
         * 转换为 Map 用于 JSON 序列化
         */
        public Map<String, Object> toMap() {
            Map<String, Object> map = new HashMap<>();
            map.put("query", query);
            map.put("success", success);
            map.put("latencyMs", latencyMs);

            if (error != null) map.put("error", error);
            if (candidates != null) map.put("candidates", candidates);
            if (rerankedDocs != null) map.put("documents", rerankedDocs);
            if (queryRewrite != null) {
                map.put("queryRewrite", Map.of(
                        "originalQuery", queryRewrite.getOriginalQuery(),
                        "hypotheticalDoc", queryRewrite.getHypotheticalDoc(),
                        "expandedQueries", queryRewrite.getExpandedQueries()
                ));
            }
            if (subQuestions != null) map.put("subQuestions", subQuestions);
            if (combinedContext != null) map.put("combinedContext", combinedContext);
            if (generatedAnswer != null) map.put("answer", generatedAnswer);

            return map;
        }
    }
}
