// 包声明：高级搜索与RAG控制器
package com.luanshuai.agent.controller;

// ============ 标准库 & Spring Framework ============
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.*;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

// ============ 应用内模型/服务 ============
import com.luanshuai.agent.config.AppConfig;
import com.luanshuai.agent.config.JwtTokenProvider;
import com.luanshuai.agent.config.OfflineConfiguration;
import com.luanshuai.agent.model.ApiResponse;
import com.luanshuai.agent.service.search.*;
import com.luanshuai.agent.service.evaluation.EvaluationService;
import com.luanshuai.agent.service.billing.UsageTrackingService;
import com.luanshuai.agent.service.MilvusDbService;
import com.luanshuai.agent.util.TenantContext;
import com.luanshuai.agent.util.TenantUtils;

// ============ Controller ============
@RestController
@RequestMapping("/api/advanced")
@CrossOrigin(origins = "*")
public class AdvancedSearchController {

    private static final Logger log = LoggerFactory.getLogger(AdvancedSearchController.class);

    // =============== 注入所有服务 ===============
    @Autowired
    private HybridSearchService hybridSearchService;

    @Autowired
    private ChainExecutor chainExecutor;

    @Autowired
    private DocumentParserService documentParserService;

    @Autowired
    private QueryRewriteService queryRewriteService;

    @Autowired
    private RerankingService rerankingService;

    @Autowired
    private EvaluationService evaluationService;

    @Autowired
    private UsageTrackingService usageTrackingService;

    @Autowired
    private OfflineConfiguration offlineConfiguration;

    @Autowired
    private MilvusDbService milvusDbService;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    @Autowired
    private AppConfig appConfig;

    // =============== 请求/响应 DTO ===============

    /** 混合搜索请求 */
    public static class HybridSearchRequest {
        public String query;
        public String collectionName;
        public Integer topK = 20;
        public Integer vectorTopK = 50;
        public Integer bm25TopK = 50;
        public Integer rrfK = 60;
        public Boolean enableRerank = true;
        public Boolean enableQueryRewrite = false;
        public String rewriteStrategy; // "hyde", "expand", "decompose", "stepback"
    }

    /** RAG Chain 执行请求 */
    public static class ChainRequest {
        public String query;
        public String collectionName;
        public String chainType = "retrieval"; // "retrieval" | "subquestion"
        public Boolean enableRewrite = true;
        public Boolean enableRerank = true;
        public Boolean streaming = false;
        public Integer topK = 10;
    }

    /** 文档解析请求 */
    public static class DocumentParseRequest {
        public String sourceUrl;
        public String sourceName;
        public String text; // 直接传文本
        public String filePath; // 或传文件路径
        public String documentType = "auto"; // "pdf", "html", "markdown", "auto"
        public Integer chunkSize = 500;
        public Integer chunkOverlap = 50;
    }

    /** 评估请求 */
    public static class EvaluateRequest {
        public String query;
        public String answer;
        public List<Map<String, Object>> documents;
        public String mode = "quick"; // "quick" | "llm"
        public String tenantId;
        public String userId;
    }

    /** 评估结果 (用于批量) */
    public static class EvaluationReport {
        public String query;
        public double logicScore;
        public double instructionScore;
        public double hallucinationScore;
        public double overallScore;
        public String logicReasoning;
        public String hallucinationReasoning;
        public Map<String, Object> details;
    }

    /** 用量查询请求 */
    public static class UsageQueryRequest {
        public String tenantId;
        public String userId;
        public Long startTime;
        public Long endTime;
    }

    // =============== 辅助方法 ===============

    private String resolveTenantId(String authorization) {
        if (authorization == null || authorization.trim().isEmpty()) return "default";
        String value = authorization.trim();
        if (!value.startsWith("Bearer ")) return "default";
        String token = value.substring(7);
        if (!jwtTokenProvider.validateToken(token)) return "default";
        try {
            String tid = jwtTokenProvider.getTenantIdFromJWT(token);
            return (tid != null && !tid.trim().isEmpty()) ? tid.trim() : "default";
        } catch (Exception e) {
            return "default";
        }
    }

    private String resolveUserId(String authorization) {
        if (authorization == null || authorization.trim().isEmpty()) return "anonymous";
        String value = authorization.trim();
        if (!value.startsWith("Bearer ")) return "anonymous";
        String token = value.substring(7);
        if (!jwtTokenProvider.validateToken(token)) return "anonymous";
        try {
            return jwtTokenProvider.getUsernameFromJWT(token);
        } catch (Exception e) {
            return "anonymous";
        }
    }

    // =============== Phase 1: 混合搜索 ===============

    /**
     * POST /api/advanced/search/hybrid
     * 混合搜索：BM25 + 向量搜索 + RRF融合 + 可选重排
     */
    @PostMapping("/search/hybrid")
    public Mono<ResponseEntity<?>> hybridSearch(
            @RequestBody HybridSearchRequest request,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization) {
        String tenantId = resolveTenantId(authorization);
        String effectiveCollection = buildCollectionName(request.collectionName, tenantId);

        log.info("[AdvancedSearch] 混合搜索: query='{}', collection='{}', tenant='{}'",
                request.query, effectiveCollection, tenantId);

        if (request.query == null || request.query.trim().isEmpty()) {
            return Mono.just(ResponseEntity.badRequest().body(ApiResponse.error("query 不能为空")));
        }

        return Mono.defer(() -> {
            try {
                // 1. Query Rewrite (可选)
                String finalQuery = request.query;
                if (Boolean.TRUE.equals(request.enableQueryRewrite) && request.rewriteStrategy != null) {
                    try {
                        switch (request.rewriteStrategy) {
                            case "hyde":
                                QueryRewriteService.QueryRewriteResult hydeResult = queryRewriteService.rewriteWithHyde(request.query);
                                finalQuery = hydeResult.getOriginalQuery();
                                break;
                            case "expand":
                                List<String> expanded = queryRewriteService.expandQuery(request.query);
                                finalQuery = expanded.isEmpty() ? request.query : String.join(" ", expanded);
                                break;
                            case "decompose":
                                List<String> decomposed = queryRewriteService.decomposeQuery(request.query);
                                finalQuery = decomposed.isEmpty() ? request.query : String.join("; ", decomposed);
                                break;
                            case "stepback":
                                finalQuery = queryRewriteService.stepBack(request.query);
                                break;
                            default:
                                List<String> expandedDefault = queryRewriteService.expandQuery(request.query);
                                finalQuery = expandedDefault.isEmpty() ? request.query : String.join(" ", expandedDefault);
                        }
                        log.info("[AdvancedSearch] Query重写: '{}' -> '{}'", request.query, finalQuery);
                    } catch (Exception e) {
                        log.warn("[AdvancedSearch] Query重写失败，使用原查询: {}", e.getMessage());
                        finalQuery = request.query;
                    }
                }

                // 2. 混合搜索
                int topK = request.topK != null ? request.topK : 20;
                int vecTopK = request.vectorTopK != null ? request.vectorTopK : 50;
                int bmTopK = request.bm25TopK != null ? request.bm25TopK : 50;

                List<Map<String, Object>> results = hybridSearchService.search(
                        finalQuery, effectiveCollection, topK, vecTopK, bmTopK);

                // 3. Rerank (可选)
                if (Boolean.TRUE.equals(request.enableRerank) && !results.isEmpty()) {
                    try {
                        results = rerankingService.rerank(finalQuery, results, topK);
                    } catch (Exception e) {
                        log.warn("[AdvancedSearch] Rerank失败，跳过: {}", e.getMessage());
                    }
                }

                Map<String, Object> response = new LinkedHashMap<>();
                response.put("query", request.query);
                response.put("rewrittenQuery", finalQuery);
                response.put("collection", effectiveCollection);
                response.put("resultCount", results.size());
                response.put("results", results);
                response.put("mode", "hybrid_search");

                return Mono.just(ResponseEntity.ok(ApiResponse.success("混合搜索完成", response)));

            } catch (Exception e) {
                log.error("[AdvancedSearch] 混合搜索异常: {}", e.getMessage(), e);
                return Mono.just(ResponseEntity.status(500).body(ApiResponse.error("混合搜索失败: " + e.getMessage())));
            }
        });
    }

    /**
     * POST /api/advanced/search/vector
     * 纯向量搜索
     */
    @PostMapping("/search/vector")
    public Mono<ResponseEntity<?>> vectorSearch(
            @RequestBody HybridSearchRequest request,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization) {
        String tenantId = resolveTenantId(authorization);
        String effectiveCollection = buildCollectionName(request.collectionName, tenantId);

        if (request.query == null || request.query.trim().isEmpty()) {
            return Mono.just(ResponseEntity.badRequest().body(ApiResponse.error("query 不能为空")));
        }

        return Mono.defer(() -> {
            try {
                int topK = request.topK != null ? request.topK : 20;
                // 使用关键词搜索作为简化实现（向量搜索需要 embedding）
                List<Map<String, Object>> results = milvusDbService.searchDocumentsByKeyword(
                        effectiveCollection, request.query);

                Map<String, Object> response = new LinkedHashMap<>();
                response.put("query", request.query);
                response.put("collection", effectiveCollection);
                response.put("resultCount", results.size());
                response.put("results", results);
                response.put("mode", "vector_search");

                return Mono.just(ResponseEntity.ok(ApiResponse.success("向量搜索完成", response)));
            } catch (Exception e) {
                log.error("[AdvancedSearch] 向量搜索异常: {}", e.getMessage(), e);
                return Mono.just(ResponseEntity.status(500).body(ApiResponse.error("向量搜索失败: " + e.getMessage())));
            }
        });
    }

    /**
     * POST /api/advanced/search/bm25
     * 纯 BM25 关键词搜索
     */
    @PostMapping("/search/bm25")
    public Mono<ResponseEntity<?>> bm25Search(
            @RequestBody HybridSearchRequest request) {
        if (request.query == null || request.query.trim().isEmpty()) {
            return Mono.just(ResponseEntity.badRequest().body(ApiResponse.error("query 不能为空")));
        }

        return Mono.defer(() -> {
            try {
                int topK = request.topK != null ? request.topK : 20;
                // 需要一个Bm25Service实例，这里使用HybridSearchService中的Bm25Service
                List<Map<String, Object>> results = milvusDbService.searchDocumentsByKeyword(
                        request.collectionName != null ? request.collectionName : "default",
                        request.query);

                Map<String, Object> response = new LinkedHashMap<>();
                response.put("query", request.query);
                response.put("resultCount", results.size());
                response.put("results", results);
                response.put("mode", "bm25_search");

                return Mono.just(ResponseEntity.ok(ApiResponse.success("BM25搜索完成", response)));
            } catch (Exception e) {
                log.error("[AdvancedSearch] BM25搜索异常: {}", e.getMessage(), e);
                return Mono.just(ResponseEntity.status(500).body(ApiResponse.error("BM25搜索失败: " + e.getMessage())));
            }
        });
    }

    // =============== Phase 2: Query Rewrite & Rerank ===============

    /**
     * POST /api/advanced/rewrite
     * Query Rewrite: HyDE / 扩展 / 分解 / Step-back
     */
    @PostMapping("/rewrite")
    public Mono<ResponseEntity<?>> rewriteQuery(@RequestBody Map<String, Object> request) {
        String query = (String) request.get("query");
        String strategy = (String) request.getOrDefault("strategy", "expand");

        if (query == null || query.trim().isEmpty()) {
            return Mono.just(ResponseEntity.badRequest().body(ApiResponse.error("query 不能为空")));
        }

        return Mono.fromCallable(() -> {
            try {
                Object rewritten;
                switch (strategy) {
                    case "hyde":
                        QueryRewriteService.QueryRewriteResult hydeResult = queryRewriteService.rewriteWithHyde(query);
                        rewritten = hydeResult;
                        break;
                    case "expand":
                        rewritten = queryRewriteService.expandQuery(query);
                        break;
                    case "decompose":
                        rewritten = queryRewriteService.decomposeQuery(query);
                        break;
                    case "stepback":
                        rewritten = queryRewriteService.stepBack(query);
                        break;
                    default:
                        rewritten = queryRewriteService.expandQuery(query);
                }
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("originalQuery", query);
                result.put("strategy", strategy);
                result.put("rewrittenQuery", rewritten);
                return ResponseEntity.ok(ApiResponse.success("Query重写完成", result));
            } catch (Exception e) {
                log.error("[AdvancedSearch] Query重写异常: {}", e.getMessage(), e);
                return ResponseEntity.status(500).body(ApiResponse.error("Query重写失败: " + e.getMessage()));
            }
        });
    }

    /**
     * POST /api/advanced/rerank
     * 文档重排
     */
    @PostMapping("/rerank")
    public Mono<ResponseEntity<?>> rerankDocuments(@RequestBody Map<String, Object> request) {
        String query = (String) request.get("query");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> candidates = (List<Map<String, Object>>) request.get("candidates");
        Integer topK = request.get("topK") != null ? ((Number) request.get("topK")).intValue() : 10;

        if (query == null || query.trim().isEmpty()) {
            return Mono.just(ResponseEntity.badRequest().body(ApiResponse.error("query 不能为空")));
        }
        if (candidates == null || candidates.isEmpty()) {
            return Mono.just(ResponseEntity.badRequest().body(ApiResponse.error("candidates 不能为空")));
        }

        return Mono.fromCallable(() -> {
            try {
                List<Map<String, Object>> reranked = rerankingService.rerank(query, candidates, topK);
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("query", query);
                result.put("inputCount", candidates.size());
                result.put("outputCount", reranked.size());
                result.put("results", reranked);
                return ResponseEntity.ok(ApiResponse.success("重排完成", result));
            } catch (Exception e) {
                log.error("[AdvancedSearch] 重排异常: {}", e.getMessage(), e);
                return ResponseEntity.status(500).body(ApiResponse.error("重排失败: " + e.getMessage()));
            }
        });
    }

    // =============== Phase 3: Chain 架构 ===============

    /**
     * POST /api/advanced/chain/retrieval
     * Retrieval Chain: 搜索 → 重排 → 生成
     */
    @PostMapping("/chain/retrieval")
    public Mono<ResponseEntity<?>> retrievalChain(
            @RequestBody ChainRequest request,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization) {
        String tenantId = resolveTenantId(authorization);
        String userId = resolveUserId(authorization);
        String effectiveCollection = buildCollectionName(request.collectionName, tenantId);

        log.info("[AdvancedSearch] RetrievalChain: query='{}', collection='{}'",
                request.query, effectiveCollection);

        if (request.query == null || request.query.trim().isEmpty()) {
            return Mono.just(ResponseEntity.badRequest().body(ApiResponse.error("query 不能为空")));
        }

        return Mono.defer(() -> {
            try {
                ChainExecutor.ChainResult chainResult = chainExecutor.executeRetrievalChain(
                        request.query,
                        effectiveCollection,
                        Boolean.TRUE.equals(request.enableRewrite),
                        Boolean.TRUE.equals(request.enableRerank)
                );

                Map<String, Object> response = new LinkedHashMap<>();
                response.put("query", request.query);
                response.put("rewrittenQuery", chainResult.getQueryRewrite() != null ? chainResult.getQueryRewrite().getOriginalQuery() : null);
                response.put("documents", chainResult.getRerankedDocs());
                response.put("answer", chainResult.getGeneratedAnswer());
                response.put("mode", "retrieval_chain");

                return Mono.just(ResponseEntity.ok(ApiResponse.success("Retrieval Chain 完成", response)));

            } catch (Exception e) {
                log.error("[AdvancedSearch] RetrievalChain异常: {}", e.getMessage(), e);
                return Mono.just(ResponseEntity.status(500).body(ApiResponse.error("Retrieval Chain 失败: " + e.getMessage())));
            }
        });
    }

    /**
     * POST /api/advanced/chain/retrieval/stream
     * Retrieval Chain 流式版本 (SSE)
     */
    @PostMapping(value = "/chain/retrieval/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> retrievalChainStream(
            @RequestBody ChainRequest request,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization) {
        String tenantId = resolveTenantId(authorization);
        String effectiveCollection = buildCollectionName(request.collectionName, tenantId);

        log.info("[AdvancedSearch] RetrievalChain Stream: query='{}', collection='{}'",
                request.query, effectiveCollection);

        if (request.query == null || request.query.trim().isEmpty()) {
            return Flux.just("data: {\"type\":\"error\",\"content\":\"query不能为空\"}\n\n");
        }

        return chainExecutor.executeRetrievalChainStream(request.query, effectiveCollection,
                Boolean.TRUE.equals(request.enableRewrite), Boolean.TRUE.equals(request.enableRerank))
                .startWith(Flux.just("data: {\"type\":\"sources\",\"content\":\"检索中...\",\"count\":0}\n\n"))
                .onErrorResume(e -> {
                    log.error("[AdvancedSearch] RetrievalChain Stream异常: {}", e.getMessage());
                    return Flux.just("data: {\"type\":\"error\",\"content\":\"Stream失败: " +
                            e.getMessage().replace("\"", "\\\"") + "\"}\n\n");
                });
    }

    /**
     * POST /api/advanced/chain/subquestion
     * SubQuestion Chain: 复杂问题分解 → 并行检索 → 综合回答
     */
    @PostMapping("/chain/subquestion")
    public Mono<ResponseEntity<?>> subQuestionChain(
            @RequestBody ChainRequest request,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization) {
        String tenantId = resolveTenantId(authorization);
        String effectiveCollection = buildCollectionName(request.collectionName, tenantId);

        log.info("[AdvancedSearch] SubQuestionChain: query='{}', collection='{}'",
                request.query, effectiveCollection);

        if (request.query == null || request.query.trim().isEmpty()) {
            return Mono.just(ResponseEntity.badRequest().body(ApiResponse.error("query 不能为空")));
        }

        return Mono.defer(() -> {
            try {
                ChainExecutor.ChainResult chainResult = chainExecutor.executeSubQuestionChain(
                        request.query, effectiveCollection);

                Map<String, Object> response = new LinkedHashMap<>();
                response.put("query", request.query);
                response.put("subQueries", chainResult.getSubQuestions());
                response.put("subResults", chainResult.getSubQuestionResults());
                response.put("documents", chainResult.getRerankedDocs());
                response.put("answer", chainResult.getGeneratedAnswer());
                response.put("mode", "subquestion_chain");

                return Mono.just(ResponseEntity.ok(ApiResponse.success("SubQuestion Chain 完成", response)));

            } catch (Exception e) {
                log.error("[AdvancedSearch] SubQuestionChain异常: {}", e.getMessage(), e);
                return Mono.just(ResponseEntity.status(500).body(ApiResponse.error("SubQuestion Chain 失败: " + e.getMessage())));
            }
        });
    }

    /**
     * POST /api/advanced/chain/subquestion/stream
     * SubQuestion Chain 流式版本
     */
    @PostMapping(value = "/chain/subquestion/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> subQuestionChainStream(
            @RequestBody ChainRequest request,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization) {
        String tenantId = resolveTenantId(authorization);
        String effectiveCollection = buildCollectionName(request.collectionName, tenantId);

        log.info("[AdvancedSearch] SubQuestionChain Stream: query='{}', collection='{}'",
                request.query, effectiveCollection);

        if (request.query == null || request.query.trim().isEmpty()) {
            return Flux.just("data: {\"type\":\"error\",\"content\":\"query不能为空\"}\n\n");
        }

        return chainExecutor.executeSubQuestionChainStream(request.query, effectiveCollection)
                .startWith(Flux.just("data: {\"type\":\"thinking\",\"content\":\"问题分解中...\"}\n\n"))
                .onErrorResume(e -> {
                    log.error("[AdvancedSearch] SubQuestionChain Stream异常: {}", e.getMessage());
                    return Flux.just("data: {\"type\":\"error\",\"content\":\"Stream失败: " +
                            e.getMessage().replace("\"", "\\\"") + "\"}\n\n");
                });
    }

    // =============== Phase 4: 评估体系 ===============

    /**
     * POST /api/advanced/evaluate
     * RAG 评估：逻辑正确性 / 指令遵循 / 幻觉检测
     */
    @PostMapping("/evaluate")
    public Mono<ResponseEntity<?>> evaluate(
            @RequestBody EvaluateRequest request,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization) {
        String tenantId = request.tenantId != null ? request.tenantId : resolveTenantId(authorization);
        String userId = request.userId != null ? request.userId : resolveUserId(authorization);

        if (request.query == null || request.answer == null) {
            return Mono.just(ResponseEntity.badRequest().body(ApiResponse.error("query 和 answer 不能为空")));
        }

        log.info("[AdvancedSearch] 评估: query='{}', tenant='{}', mode='{}'",
                request.query, tenantId, request.mode);

        return Mono.defer(() -> {
            try {
                EvaluationService.EvaluationResult result;
                if ("llm".equalsIgnoreCase(request.mode)) {
                    result = evaluationService.evaluate(request.query, request.answer, request.documents);
                } else {
                    result = evaluationService.evaluate(request.query, request.answer, request.documents, false);
                }

                EvaluationReport report = new EvaluationReport();
                report.query = request.query;
                report.logicScore = result.getLogicScore();
                report.instructionScore = result.getInstructionScore();
                report.hallucinationScore = result.getHallucinationScore();
                report.overallScore = result.getOverallScore();
                report.logicReasoning = result.getReasoning();
                report.details = result.toMap();

                // 记录评估消耗（估算token）
                long estimatedTokens = estimateTokens(request.query + request.answer);
                usageTrackingService.recordTokenUsage(tenantId, userId + "_eval",
                        (int) (estimatedTokens * 0.5), (int) (estimatedTokens * 0.5));

                return Mono.just(ResponseEntity.ok(ApiResponse.success("评估完成", report)));

            } catch (Exception e) {
                log.error("[AdvancedSearch] 评估异常: {}", e.getMessage(), e);
                return Mono.just(ResponseEntity.status(500).body(ApiResponse.error("评估失败: " + e.getMessage())));
            }
        });
    }

    /**
     * POST /api/advanced/evaluate/batch
     * 批量评估
     */
    @PostMapping("/evaluate/batch")
    public Mono<ResponseEntity<ApiResponse<Map<String, Object>>>> batchEvaluate(
            @RequestBody List<EvaluateRequest> requests,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization) {
        String tenantId = resolveTenantId(authorization);
        String userId = resolveUserId(authorization);

        if (requests == null || requests.isEmpty()) {
            return Mono.just(ResponseEntity.badRequest().body(ApiResponse.error("请求列表不能为空")));
        }

        log.info("[AdvancedSearch] 批量评估: {} 个请求", requests.size());

        return Mono.fromCallable(() -> {
            List<EvaluationReport> reports = new ArrayList<>();
            for (EvaluateRequest req : requests) {
                try {
                    EvaluationService.EvaluationResult result;
                    if ("llm".equalsIgnoreCase(req.mode)) {
                        result = evaluationService.evaluate(req.query, req.answer, req.documents);
                    } else {
                        result = evaluationService.evaluate(req.query, req.answer, req.documents, false);
                    }
                    EvaluationReport report = new EvaluationReport();
                    report.query = req.query;
                    report.logicScore = result.getLogicScore();
                    report.instructionScore = result.getInstructionScore();
                    report.hallucinationScore = result.getHallucinationScore();
                    report.overallScore = result.getOverallScore();
                    reports.add(report);
                } catch (Exception e) {
                    log.warn("[AdvancedSearch] 单条评估失败: query='{}', error={}", req.query, e.getMessage());
                    EvaluationReport failed = new EvaluationReport();
                    failed.query = req.query;
                    reports.add(failed);
                }
            }

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("totalCount", requests.size());
            response.put("reports", reports);

            // 计算统计
            Map<String, Object> stats = new LinkedHashMap<>();
            stats.put("avgLogicScore", reports.stream().filter(r -> r.logicScore > 0)
                    .mapToDouble(r -> r.logicScore).average().orElse(0));
            stats.put("avgInstructionScore", reports.stream().filter(r -> r.instructionScore > 0)
                    .mapToDouble(r -> r.instructionScore).average().orElse(0));
            stats.put("avgHallucinationScore", reports.stream().filter(r -> r.hallucinationScore > 0)
                    .mapToDouble(r -> r.hallucinationScore).average().orElse(0));
            response.put("stats", stats);

            return ResponseEntity.ok(ApiResponse.success("批量评估完成", response));

        }).onErrorResume(e -> Mono.just(ResponseEntity.status(500)
                .body(ApiResponse.error("批量评估失败: " + e.getMessage()))));
    }

    // =============== Phase 5: 离线支持 ===============

    /**
     * GET /api/advanced/offline/status
     * 离线模式状态检查
     */
    @GetMapping("/offline/status")
    public Mono<ResponseEntity<ApiResponse<Map<String, Object>>>> offlineStatus() {
        return Mono.fromCallable(() -> {
            try {
                boolean isOffline = offlineConfiguration.isOfflineMode();
                int cacheSize = offlineConfiguration.getCacheSize();
                Map<String, Object> status = new LinkedHashMap<>();
                status.put("offlineMode", isOffline);
                status.put("cachedDocuments", cacheSize);
                status.put("maxCacheSize", 5000);
                status.put("cacheUsagePercent", String.format("%.1f%%", (cacheSize * 100.0) / 5000));
                return ResponseEntity.ok(ApiResponse.success("离线状态", status));
            } catch (Exception e) {
                ApiResponse<Map<String, Object>> errorResp = new ApiResponse<>(false, "离线状态检查失败: " + e.getMessage());
                return ResponseEntity.status(500).body(errorResp);
            }
        });
    }

    /**
     * POST /api/advanced/offline/preload
     * 预加载文档到离线缓存
     */
    @PostMapping("/offline/preload")
    public Mono<ResponseEntity<?>> preloadDocuments(
            @RequestBody Map<String, Object> request,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization) {
        String collectionName = (String) request.get("collectionName");
        String tenantId = resolveTenantId(authorization);

        if (collectionName == null || collectionName.trim().isEmpty()) {
            return Mono.just(ResponseEntity.badRequest().body(ApiResponse.error("collectionName 不能为空")));
        }

        String effectiveCollection = buildCollectionName(collectionName, tenantId);
        log.info("[AdvancedSearch] 预加载离线文档: collection='{}'", effectiveCollection);

        return Mono.defer(() -> {
            try {
                int preloaded = offlineConfiguration.preloadDocuments(effectiveCollection);
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("collection", effectiveCollection);
                result.put("preloadedCount", preloaded);
                result.put("totalCacheSize", offlineConfiguration.getCacheSize());
                return Mono.just(ResponseEntity.ok(ApiResponse.success("文档预加载完成", result)));
            } catch (Exception e) {
                log.error("[AdvancedSearch] 预加载异常: {}", e.getMessage(), e);
                return Mono.just(ResponseEntity.status(500).body(ApiResponse.error("预加载失败: " + e.getMessage())));
            }
        });
    }

    /**
     * POST /api/advanced/offline/clear
     * 清除离线缓存
     */
    @PostMapping("/offline/clear")
    public Mono<ResponseEntity<?>> clearOfflineCache() {
        return Mono.fromCallable(() -> {
            try {
                offlineConfiguration.clearCache();
                return ResponseEntity.ok(ApiResponse.success("离线缓存已清除"));
            } catch (Exception e) {
                return ResponseEntity.status(500).body(ApiResponse.error("清除缓存失败: " + e.getMessage()));
            }
        });
    }

    // =============== Phase 6: 商业功能 - 用量统计 ===============

    /**
     * GET /api/advanced/usage/tenant/{tenantId}
     * 查询租户用量
     */
    @GetMapping("/usage/tenant/{tenantId}")
    public Mono<ResponseEntity<?>> getTenantUsage(
            @PathVariable String tenantId,
            @RequestParam(required = false) Long startTime,
            @RequestParam(required = false) Long endTime) {
        if (tenantId == null || tenantId.trim().isEmpty()) {
            tenantId = "default";
        }
        try {
            Map<String, Object> usage = usageTrackingService.getTenantUsage(tenantId);
            usage.put("startTime", startTime);
            usage.put("endTime", endTime);
            return Mono.just(ResponseEntity.ok(ApiResponse.success("获取租户用量成功", usage)));
        } catch (Exception e) {
            log.error("[AdvancedSearch] 获取租户用量异常: {}", e.getMessage(), e);
            return Mono.just(ResponseEntity.status(500).body(ApiResponse.error("获取租户用量失败: " + e.getMessage())));
        }
    }

    /**
     * GET /api/advanced/usage/user/{userId}
     * 查询用户用量
     */
    @GetMapping("/usage/user/{userId}")
    public Mono<ResponseEntity<?>> getUserUsage(
            @PathVariable String userId,
            @RequestParam(required = false) String tenantId,
            @RequestParam(required = false) Long startTime,
            @RequestParam(required = false) Long endTime) {
        if (userId == null || userId.trim().isEmpty()) {
            return Mono.just(ResponseEntity.badRequest().body(ApiResponse.error("userId 不能为空")));
        }
        try {
            Map<String, Object> usage = usageTrackingService.getUserUsage(userId);
            usage.put("tenantId", tenantId != null ? tenantId : "default");
            usage.put("startTime", startTime);
            usage.put("endTime", endTime);
            return Mono.just(ResponseEntity.ok(ApiResponse.success("获取用户用量成功", usage)));
        } catch (Exception e) {
            log.error("[AdvancedSearch] 获取用户用量异常: {}", e.getMessage(), e);
            return Mono.just(ResponseEntity.status(500).body(ApiResponse.error("获取用户用量失败: " + e.getMessage())));
        }
    }

    /**
     * POST /api/advanced/usage/record
     * 手动记录Token消耗（供其他服务调用）
     */
    @PostMapping("/usage/record")
    public Mono<ResponseEntity<?>> recordUsage(
            @RequestBody Map<String, Object> request,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization) {
        String tenantId = (String) request.getOrDefault("tenantId", resolveTenantId(authorization));
        String userId = (String) request.getOrDefault("userId", resolveUserId(authorization));
        Integer inputTokens = request.get("inputTokens") != null ? ((Number) request.get("inputTokens")).intValue() : 0;
        Integer outputTokens = request.get("outputTokens") != null ? ((Number) request.get("outputTokens")).intValue() : 0;

        if (tenantId == null || tenantId.trim().isEmpty()) tenantId = "default";
        if (userId == null || userId.trim().isEmpty()) userId = "anonymous";

        try {
            usageTrackingService.recordTokenUsage(tenantId, userId, inputTokens, outputTokens);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("tenantId", tenantId);
            result.put("userId", userId);
            result.put("inputTokens", inputTokens);
            result.put("outputTokens", outputTokens);
            result.put("totalTokens", inputTokens + outputTokens);
            return Mono.just(ResponseEntity.ok(ApiResponse.success("Token消耗已记录", result)));
        } catch (Exception e) {
            log.error("[AdvancedSearch] 记录Token消耗异常: {}", e.getMessage(), e);
            return Mono.just(ResponseEntity.status(500).body(ApiResponse.error("记录失败: " + e.getMessage())));
        }
    }

    /**
     * POST /api/advanced/usage/check-quota
     * 配额检查
     */
    @PostMapping("/usage/check-quota")
    public Mono<ResponseEntity<?>> checkQuota(
            @RequestBody Map<String, Object> request,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization) {
        String tenantId = (String) request.getOrDefault("tenantId", resolveTenantId(authorization));
        Integer requiredTokens = request.get("requiredTokens") != null ?
                ((Number) request.get("requiredTokens")).intValue() : 1000;

        if (tenantId == null || tenantId.trim().isEmpty()) tenantId = "default";

        try {
            UsageTrackingService.QuotaCheckResult quotaResult = usageTrackingService.checkQuota(tenantId, requiredTokens);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("allowed", quotaResult.isAllowed());
            result.put("message", quotaResult.getMessage());
            result.put("currentUsage", quotaResult.getCurrentUsage());
            result.put("limit", quotaResult.getLimit());
            result.put("remaining", quotaResult.getLimit() - quotaResult.getCurrentUsage());
            return Mono.just(ResponseEntity.ok(ApiResponse.success("配额检查完成", result)));
        } catch (Exception e) {
            log.error("[AdvancedSearch] 配额检查异常: {}", e.getMessage(), e);
            return Mono.just(ResponseEntity.status(500).body(ApiResponse.error("配额检查失败: " + e.getMessage())));
        }
    }

    // =============== 文档解析 ===============

    /**
     * POST /api/advanced/document/parse
     * 解析文档（PDF/HTML/Markdown）并分块
     */
    @PostMapping("/document/parse")
    public Mono<ResponseEntity<?>> parseDocument(@RequestBody DocumentParseRequest request) {
        log.info("[AdvancedSearch] 文档解析: source='{}', type='{}'",
                request.sourceUrl != null ? request.sourceUrl : request.filePath, request.documentType);

        return Mono.defer(() -> {
            try {
                List<Map<String, Object>> chunks;

                if (request.text != null && !request.text.trim().isEmpty()) {
                    // 直接解析文本
                    chunks = documentParserService.chunkText(
                            request.text,
                            request.chunkSize,
                            request.chunkOverlap,
                            buildMetadata(request.sourceName, request.sourceUrl)
                    );
                } else if (request.filePath != null && !request.filePath.trim().isEmpty()) {
                    // 解析文件
                    if ("pdf".equalsIgnoreCase(request.documentType) || request.filePath.toLowerCase().endsWith(".pdf")) {
                        String text = documentParserService.parsePdf(request.filePath).stream()
                                .map(DocumentParserService.StructuredDocument::getText)
                                .collect(Collectors.joining("\n"));
                        chunks = documentParserService.chunkText(text, request.chunkSize, request.chunkOverlap,
                                buildMetadata(request.sourceName, request.filePath));
                    } else if ("html".equalsIgnoreCase(request.documentType) || request.filePath.toLowerCase().endsWith(".html")) {
                        String text = documentParserService.parseHtml(
                                Files.readString(Paths.get(request.filePath)), request.filePath).stream()
                                .map(DocumentParserService.StructuredDocument::getText)
                                .collect(Collectors.joining("\n"));
                        chunks = documentParserService.chunkText(text, request.chunkSize, request.chunkOverlap,
                                buildMetadata(request.sourceName, request.filePath));
                    } else {
                        String text = documentParserService.parseMarkdown(
                                Files.readString(Paths.get(request.filePath)), request.filePath).stream()
                                .map(DocumentParserService.StructuredDocument::getText)
                                .collect(Collectors.joining("\n"));
                        chunks = documentParserService.chunkText(text, request.chunkSize, request.chunkOverlap,
                                buildMetadata(request.sourceName, request.filePath));
                    }
                } else if (request.sourceUrl != null && !request.sourceUrl.trim().isEmpty()) {
                    // 从URL解析（简化处理，实际应使用HTTP客户端获取内容）
                    String html = "<!-- 从URL读取的内容 -->";
                    String text = documentParserService.parseHtml(html, request.sourceUrl).stream()
                            .map(DocumentParserService.StructuredDocument::getText)
                            .collect(Collectors.joining("\n"));
                    chunks = documentParserService.chunkText(text, request.chunkSize, request.chunkOverlap,
                            buildMetadata(request.sourceName, request.sourceUrl));
                } else {
                    return Mono.just(ResponseEntity.badRequest()
                            .body(ApiResponse.error("text、filePath 或 sourceUrl 至少需要提供一个")));
                }

                Map<String, Object> result = new LinkedHashMap<>();
                result.put("sourceName", request.sourceName);
                result.put("sourceUrl", request.sourceUrl);
                result.put("totalChunks", chunks.size());
                result.put("totalChars", chunks.stream().mapToInt(c -> String.valueOf(c.get("text")).length()).sum());
                result.put("chunks", chunks);

                return Mono.just(ResponseEntity.ok(ApiResponse.success("文档解析完成", result)));

            } catch (Exception e) {
                log.error("[AdvancedSearch] 文档解析异常: {}", e.getMessage(), e);
                return Mono.just(ResponseEntity.status(500).body(ApiResponse.error("文档解析失败: " + e.getMessage())));
            }
        });
    }

    /**
     * POST /api/advanced/document/parse/upload
     * 上传并解析文档
     */
    @PostMapping(value = "/document/parse/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Mono<ResponseEntity<ApiResponse<Map<String, Object>>>> parseUploadedDocument(
            @RequestPart("file") FilePart file,
            @RequestPart(value = "chunkSize", required = false) Mono<String> chunkSizeMono,
            @RequestPart(value = "chunkOverlap", required = false) Mono<String> chunkOverlapMono) {
        log.info("[AdvancedSearch] 解析上传文档: filename='{}'", file.filename());

        int chunkSize = 500;
        int chunkOverlap = 50;

        return Mono.zip(
                chunkSizeMono.defaultIfEmpty("500"),
                chunkOverlapMono.defaultIfEmpty("50")
        ).flatMap(tuple -> {
            int parsedCs = 500;
            int parsedCo = 50;
            try {
                parsedCs = Integer.parseInt(tuple.getT1());
            } catch (Exception ignored) {}
            try {
                parsedCo = Integer.parseInt(tuple.getT2());
            } catch (Exception ignored) {}
            final int cs = parsedCs;
            final int co = parsedCo;
            final String filename = file.filename();
            final Path tempDir = Paths.get(System.getProperty("java.io.tmpdir"));
            final Path tempFile = tempDir.resolve("upload_" + System.currentTimeMillis() + "_" + filename);

            return file.transferTo(tempFile.toFile())
                    .then(Mono.fromCallable(() -> {
                        try {
                            String text = documentParserService.parsePdf(tempFile.toString()).stream()
                                    .map(DocumentParserService.StructuredDocument::getText)
                                    .collect(Collectors.joining("\n"));
                            List<Map<String, Object>> chunks =
                                    documentParserService.chunkText(text, cs, co,
                                            buildMetadata(filename, tempFile.toString()));

                            Map<String, Object> result = new LinkedHashMap<>();
                            result.put("filename", filename);
                            result.put("totalChunks", chunks.size());
                            result.put("chunks", chunks);

                            // 清理临时文件
                            Files.deleteIfExists(tempFile);

                            return ResponseEntity.ok(ApiResponse.success("文档解析完成", result));
                        } catch (Exception e) {
                            ApiResponse<Map<String, Object>> errorResp = new ApiResponse<>(false, "文档解析失败: " + e.getMessage());
                            return ResponseEntity.status(500).body(errorResp);
                        }
                    }));
        }).onErrorResume(e -> {
            ApiResponse<Map<String, Object>> errorResp = new ApiResponse<>(false, "文件上传失败: " + e.getMessage());
            ResponseEntity<ApiResponse<Map<String, Object>>> errorEntity = ResponseEntity.status(500).body(errorResp);
            return Mono.just(errorEntity);
        });
    }

    // =============== 健康检查 ===============

    /**
     * GET /api/advanced/health
     * 高级搜索服务健康检查
     */
    @GetMapping("/health")
    public Mono<ResponseEntity<ApiResponse<Map<String, Object>>>> healthCheck(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization) {
        String tenantId = resolveTenantId(authorization);

        return Mono.fromCallable(() -> {
            Map<String, Object> health = new LinkedHashMap<>();
            health.put("status", "running");
            health.put("service", "AdvancedSearch");
            health.put("timestamp", System.currentTimeMillis());

            // 检查各服务状态
            Map<String, Object> services = new LinkedHashMap<>();
            services.put("hybridSearch", hybridSearchService != null ? "available" : "unavailable");
            services.put("chainExecutor", chainExecutor != null ? "available" : "unavailable");
            services.put("evaluation", evaluationService != null ? "available" : "unavailable");
            services.put("usageTracking", usageTrackingService != null ? "available" : "unavailable");
            services.put("offline", offlineConfiguration != null ? "available" : "unavailable");
            services.put("documentParser", documentParserService != null ? "available" : "unavailable");
            health.put("services", services);

            // 检查 Milvus
            try {
                List<String> collections = milvusDbService.listCollections();
                health.put("milvusCollections", collections.size());
            } catch (Exception e) {
                health.put("milvusStatus", "error: " + e.getMessage());
            }

            // 离线模式
            health.put("offlineMode", offlineConfiguration.isOfflineMode());

            // Token 统计
            try {
                Map<String, Object> usage = usageTrackingService.getTenantUsage(tenantId);
                health.put("tenantUsage", usage);
            } catch (Exception e) {
                health.put("usageStatus", "error: " + e.getMessage());
            }

            return ResponseEntity.ok(ApiResponse.success("服务正常", health));
        }).onErrorResume(e -> {
            ApiResponse<Map<String, Object>> errorResp = new ApiResponse<>(false, "健康检查失败: " + e.getMessage());
            ResponseEntity<ApiResponse<Map<String, Object>>> errorEntity = ResponseEntity.status(500).body(errorResp);
            return Mono.just(errorEntity);
        });
    }

    // =============== 辅助方法 ===============

    private String buildCollectionName(String collectionName, String tenantId) {
        if (collectionName == null || collectionName.trim().isEmpty()) {
            return TenantUtils.buildTenantCollectionName(tenantId, "default_knowledge_base");
        }
        return TenantUtils.buildTenantCollectionName(tenantId, collectionName.trim());
    }

    private Map<String, Object> buildMetadata(String name, String source) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        if (name != null) metadata.put("name", name);
        if (source != null) metadata.put("source", source);
        metadata.put("indexedAt", System.currentTimeMillis());
        return metadata;
    }

    private long estimateTokens(String text) {
        // 粗略估算: 中文按字符数/2, 英文按空格分词
        if (text == null || text.isEmpty()) return 0;
        long chineseChars = text.chars().filter(c -> c > 0x4E00 && c < 0x9FA5).count();
        long englishWords = text.split("\\s+").length;
        return (text.length() - (int) chineseChars) / 4 + (int) englishWords;
    }
}
