package com.luanshuai.agent.service; // 声明包名，表明该类所属的Java包

import com.luanshuai.agent.config.AppConfig; // 导入AppConfig配置类
import com.luanshuai.agent.model.ChatRequest; // 导入ChatRequest模型类
import com.luanshuai.agent.util.TenantContext; // 导入租户上下文工具类
import com.luanshuai.agent.util.TenantUtils; // 导入租户相关工具类
import org.slf4j.Logger; // 导入日志接口
import org.slf4j.LoggerFactory; // 导入日志工厂类
import org.springframework.beans.factory.annotation.Autowired; // 导入Autowired注解
import org.springframework.stereotype.Service; // 导入Service注解
import reactor.core.publisher.Mono; // 导入Reactor的Mono响应式类型
import reactor.core.publisher.Flux; // 导入Reactor的Flux响应式类型
import java.io.File; // 导入File类用于文件操作
import java.nio.file.Files; // 导入Files工具类用于文件检测等
import java.nio.file.Path; // 导入Path表示文件路径
import java.nio.file.Paths; // 导入Paths用于创建Path实例
import java.util.*; // 导入java.util包中的所有类（List/Map等）
import java.util.stream.Collectors; // 导入Collectors用于流的收集
import com.fasterxml.jackson.databind.ObjectMapper; // 导入Jackson的ObjectMapper用于JSON序列化/反序列化
import java.time.Duration; // 导入Duration表示时间长度

@Service // 标记此类为Spring的Service组件
/**
 * RagService - 检索增强生成（RAG）核心服务
 * 
 * 主要责任：
 * - 结合 Milvus 向量检索与 LLM 模型，提供问答（chat）与流式对话（chatStream）能力
 * - 实施多层回退策略（集合名回退、关键词检索、放宽阈值、返回最相似文档等）以提高检索可用性
 * - 对检索到的文档进行切片、生成 embedding、写入 Milvus，并返回带来源引用的回答与 SSE 流
 * - 提供丰富的诊断与调试方法（如 diagnoseGuanZongDocument、debugSearch）以便快速定位检索异常
 */
public class RagService {
        private static final String ANSI_GREEN = "\u001B[32m"; // 定义绿色终端颜色控制序列常量
        private static String green(String msg) { // 定义一个辅助方法，用于给日志文本添加绿色前缀
            return ANSI_GREEN + msg + ANSI_RESET; // 返回带颜色的字符串
        }
    private static final Logger log = LoggerFactory.getLogger(RagService.class); // 创建类级别的SLF4J日志记录器
    private final ObjectMapper objectMapper = new ObjectMapper(); // 创建Jackson的ObjectMapper实例用于JSON处理

    private static final String ANSI_RED = "\u001B[31m"; // 定义红色终端颜色控制序列常量
    private static final String ANSI_BLUE = "\u001B[34m"; // 定义蓝色终端颜色控制序列常量
    private static final String ANSI_RESET = "\u001B[0m"; // 定义终端颜色重置控制序列常量
    private static String red(String msg) { // 定义一个辅助方法，用于给日志文本添加红色前缀
        return ANSI_RED + msg + ANSI_RESET; // 返回带颜色的字符串
    }

    private static String blue(String msg) { // 定义一个辅助方法，用于给日志文本添加蓝色前缀
        return ANSI_BLUE + msg + ANSI_RESET; // 返回带颜色的字符串
    }

    private int maxSearchResults() {
        try {
            int configured = appConfig != null && appConfig.getPerformance() != null
                    ? appConfig.getPerformance().getMaxSearchResults()
                    : 5;
            return Math.max(1, configured);
        } catch (Exception e) {
            return 5;
        }
    }

    private int maxContextLength() {
        try {
            int configured = appConfig != null && appConfig.getPerformance() != null
                    ? appConfig.getPerformance().getMaxContextLength()
                    : 3000;
            return Math.max(200, configured);
        } catch (Exception e) {
            return 3000;
        }
    }

    private void logMilvusConnectionTrace() {
        try {
            log.warn(red("[KB_TRACE] milvus connection (current request): {}"), milvusDbService.getConnectionSummary());
        } catch (Exception e) {
            log.warn(red("[KB_TRACE] milvus connection summary failed: {}"), e.getMessage());
        }
    }

    private void logMilvusCollectionsTrace(String reason) {
        try {
            List<String> collections = milvusDbService.listCollections();
            log.warn(red("[KB_TRACE] milvus collections ({}): count={}, names={}"), reason, collections.size(), collections);
        } catch (Exception e) {
            log.warn(red("[KB_TRACE] milvus collections list failed ({}): {}"), reason, e.getMessage());
        }
    }

    @Autowired // 注入MilvusDbService的实例
    private MilvusDbService milvusDbService; // Milvus数据库服务，用于向量检索与管理

    @Autowired // 注入应用配置
    private AppConfig appConfig; // 应用配置对象

    @Autowired // 注入LLM服务（支持本地llama.cpp和云端API）
    private LLMService llmService; // 与模型服务交互的封装（统一接口）

    @Autowired // 注入混合搜索服务（BM25 + 向量 RRF 融合）
    private com.luanshuai.agent.service.search.HybridSearchService hybridSearchService;

    @Autowired // 注入BM25服务（用于文档入库时填充倒排索引）
    private com.luanshuai.agent.service.search.Bm25Service bm25Service;

    @Autowired // 注入查询意图分类器（LLM 辅助智能分类）
    private QueryIntentClassifier queryIntentClassifier;

    @Autowired
    private ChatSessionService chatSessionService;

    @Autowired // 注入文件解析服务
    private FileParserService fileParserService; // 用于解析上传的文件并拆分文本

    // Embedding 查询缓存（按 query text 哈希，TTL 5 分钟）
    private final java.util.concurrent.ConcurrentHashMap<String, CachedEmbedding> embeddingCache = new java.util.concurrent.ConcurrentHashMap<>();
    private static final long EMBEDDING_CACHE_TTL_MS = 5 * 60 * 1000L; // 5 分钟

    private static class CachedEmbedding {
        final List<Double> embedding;
        final long timestamp;
        CachedEmbedding(List<Double> embedding) {
            this.embedding = embedding;
            this.timestamp = System.currentTimeMillis();
        }
        boolean isExpired() {
            return System.currentTimeMillis() - timestamp > EMBEDDING_CACHE_TTL_MS;
        }
    }

    /** 从缓存获取 embedding，缓存未命中则调用原始生成器并缓存结果 */
    private Mono<List<Double>> cachedGenerateEmbedding(String text) {
        String cacheKey = text.hashCode() + "_" + text.length(); // 简单哈希
        CachedEmbedding cached = embeddingCache.get(cacheKey);
        if (cached != null && !cached.isExpired()) {
            log.debug("[BGE-M3] embedding cache hit: key={}", cacheKey);
            return Mono.just(cached.embedding);
        }
        log.debug("[BGE-M3] embedding cache miss: key={}", cacheKey);
        return generateEmbedding(text)
            .map(emb -> {
                embeddingCache.put(cacheKey, new CachedEmbedding(emb));
                return emb;
            });
    }

    public Mono<Map<String, Object>> chat(ChatRequest request) {
        String tenantId = TenantContext.getCurrentTenantId();
        return chat(request, tenantId);
    }

    /**
     * 基于知识库的问答接口（非流式）
     * 
     * 功能与流程：
     * 1. 基于 request 中的 knowledgeBaseId 与租户ID 构建目标集合名（支持租户隔离）
     * 2. 如果目标集合不存在或为空，执行回退策略查找其他可用集合
     * 3. 生成查询 embedding 并对 Milvus 执行检索，若无结果按多种备用策略尝试（关键词、放宽阈值、topK）
     * 4. 将检索结果拼接成上下文并构建 LLM 提示词，调用 LLM 生成回答
     * 5. 返回包含回答与来源列表（sources）的 Map
     * 
     * 注意：该方法会在内部记录大量诊断日志，便于追踪集合映射与回退选择。
     */
    public Mono<Map<String, Object>> chat(ChatRequest request, String tenantId) { // 重载方法，允许显式传入租户ID
        String effectiveTenantId = (tenantId == null || tenantId.trim().isEmpty()) ? "default" : tenantId.trim(); // 计算有效租户ID，缺省为default

        String rawCollectionName = request.getKnowledgeBaseId() != null ? request.getKnowledgeBaseId() : "default_knowledge_base"; // 获取原始知识库ID或使用默认
        String kbKey = TenantUtils.buildKbCollectionKey(rawCollectionName);
        String tenantCollectionName = TenantUtils.buildTenantCollectionName(effectiveTenantId, rawCollectionName); // 构建租户作用域的集合名（KB 名称统一 hash）

        log.info(red("[KB_TRACE] chat start: tenantId={} rawKbId='{}' kbKey='{}' targetCollection={} question='{}'"),
            effectiveTenantId,
            rawCollectionName,
            kbKey,
            tenantCollectionName,
            request.getQuestion());

        // 日志2：每次问答打印实际连接的 Milvus
        logMilvusConnectionTrace();
        // 日志3：每次问答打印当前 Milvus 的所有集合名（用于核对实际命中的是哪个库/实例）
        logMilvusCollectionsTrace("request start (chat)");

        log.info("[Milvus] Chat querying collection: {} (tenantId={}) with question: {}", tenantCollectionName, effectiveTenantId, request.getQuestion());
        
        // 检查集合是否存在，如果不存在或为空则记录警告（不再 fallback 到其他集合）
        boolean collectionExists = milvusDbService.hasCollection(tenantCollectionName); // 检查集合是否存在
        log.info(red("[KB_TRACE] target collection exists? {} => {}"), tenantCollectionName, collectionExists); // 记录检查结果
        if (!collectionExists) {
            // 集合不存在，记录警告，继续使用该集合（搜索结果为空时模型会基于知识回答）
            log.warn(red("[KB_TRACE] target collection NOT found: {} (user's knowledge base '{}')"), tenantCollectionName, rawCollectionName);
            logMilvusCollectionsTrace("target collection not found: " + tenantCollectionName);
            // 不再 fallback——知识库无数据时让模型基于专业知识回答
        } else {
            // 集合存在，检查是否有数据
            try {
                int count = milvusDbService.getCollectionCount(tenantCollectionName); // 获取集合中文档数量
                if (count == 0) {
                    log.warn(red("[KB_TRACE] target collection {} exists but EMPTY ({} documents)"), tenantCollectionName, count);
                    logMilvusCollectionsTrace("collection empty: " + tenantCollectionName);
                    // 不再 fallback——知识库为空时让模型基于专业知识回答
                } else {
                    log.info("[Milvus] 集合 {} 有 {} 个文档", tenantCollectionName, count); // 记录集合文档数量
                }
            } catch (Exception e) {
                log.warn("[Milvus] 检查集合 {} 数据时出错: {}", tenantCollectionName, e.getMessage());
                logMilvusCollectionsTrace("count_check_error: " + tenantCollectionName);
                // 不再 fallback——让模型基于知识回答，即使检查失败
            }
        }
        
        // 创建final变量用于lambda表达式
        final String finalCollectionName = tenantCollectionName; // 将最终集合名绑定为final以供lambda使用

        log.info(red("[KB_TRACE] final collection for retrieval: {} (tenantId={})"), finalCollectionName, effectiveTenantId);
        
        // 优化：并行处理检索和生成，减少响应时间
        return cachedGenerateEmbedding(request.getQuestion())
            .flatMap(queryEmbedding -> {
                // 并行执行文档检索和提示词构建
                Mono<List<Map<String, Object>>> docsMono = Mono.fromCallable(() -> {
                    // 在搜索前进行诊断
                    Map<String, Object> diagnosis = milvusDbService.diagnoseCollection(finalCollectionName);
                    log.info("[Milvus] 集合诊断结果: {}", diagnosis);

                    // 如果查询包含"管综原则"，进行详细调试
                    if (request.getQuestion().contains("管综原则")) {
                        log.info("[RAG] 检测到管综原则查询，开始详细调试");
                        diagnoseGuanZongDocument(finalCollectionName);
                        debugSearch(finalCollectionName, request.getQuestion());
                    }

                    // 使用 HybridSearchService 执行 BM25+向量混合搜索 + RRF 融合
                    List<Map<String, Object>> docs = hybridSearchService.search(
                        request.getQuestion(), finalCollectionName, maxSearchResults());
                    log.info("[Milvus] 混合搜索 returned docs: {}", docs.size()); // 记录检索到的文档数量

                    if (docs.isEmpty()) { // 如果结果为空
                        log.warn(red("[KB_TRACE] retrieval returned 0 docs: collection={} question='{}'"), finalCollectionName, request.getQuestion()); // 记录告警
                    }
                    
                    // 如果搜索结果为空，尝试多种备用搜索策略
                    if (docs.isEmpty()) { // 当初始检索没有命中时，执行降级策略
                        log.warn("[Milvus] 向量搜索结果为空，尝试备用搜索策略"); // 记录正在尝试备用策略
                        String query = request.getQuestion(); // 保存原始查询文本

                        // 策略1：关键词搜索
                        String[] keywords = extractKeywords(query); // 提取关键词
                        for (String keyword : keywords) { // 遍历关键词
                            List<Map<String, Object>> keywordDocs = milvusDbService.searchDocumentsByKeyword(finalCollectionName, keyword); // 关键词搜索
                            if (!keywordDocs.isEmpty()) { // 如果找到结果
                                log.info("[Milvus] 关键词'{}'找到{}个文档，使用作为备用结果", keyword, keywordDocs.size()); // 记录命中信息
                                docs.addAll(keywordDocs); // 将找到的文档追加到结果中
                                break; // 找到结果则停止其他关键词搜索
                            }
                        }

                        // 策略2：如果还是没找到，尝试放宽相似度阈值
                        if (docs.isEmpty()) { // 如果仍为空
                            log.warn("[Milvus] 关键词搜索也未找到结果，尝试放宽相似度阈值"); // 记录尝试放宽阈值
                            try {
                                List<Map<String, Object>> relaxedDocs = milvusDbService.queryDocumentsRelaxed(finalCollectionName, toFloatList(queryEmbedding), 10, request.getQuestion()); // 进行放宽阈值的查询
                                if (!relaxedDocs.isEmpty()) { // 如果找到
                                    log.info("[Milvus] 放宽阈值后找到{}个文档", relaxedDocs.size()); // 记录找到的数量
                                    docs.addAll(relaxedDocs); // 追加到结果
                                }
                            } catch (Exception e) { // 捕获异常
                                log.warn("[Milvus] 放宽阈值检索失败: {}", e.getMessage()); // 记录失败
                            }
                        }

                        // 策略3：如果还是没找到，返回最相似的几个文档
                        if (docs.isEmpty()) { // 仍为空则使用fallback top相似文档
                            log.warn("[Milvus] 所有搜索策略都失败，返回最相似的文档"); // 记录降级行为
                            try {
                                List<Map<String, Object>> topDocs = milvusDbService.queryTopSimilarDocuments(finalCollectionName, toFloatList(queryEmbedding), 5); // 获取最相似的5个文档
                                if (!topDocs.isEmpty()) { // 如果有结果
                                    log.info("[Milvus] 返回最相似的{}个文档", topDocs.size()); // 记录数量
                                    docs.addAll(topDocs); // 追加
                                }
                            } catch (Exception e) { // 捕获异常
                                log.warn("[Milvus] 获取最相似文档失败: {}", e.getMessage()); // 记录异常
                            }
                        }

                        if (docs.isEmpty()) { // 最终若仍无结果
                            log.warn("[Milvus] 所有搜索策略都失败，诊断信息: {}", diagnosis); // 记录诊断信息用于调查
                        }
                    }

                    return docs; // 返回最终的检索文档列表
                });
                
                return docsMono.flatMap(docs -> {
                    log.info(green("[RAG_DEBUG] chat() - 检索到相关文档数量: {}"), docs.size());
                    log.info(green("[RAG_DEBUG] chat() - 文档ID列表: {}"), docs.stream().map(doc -> doc.get("id")).collect(Collectors.toList()));
                    List<Map<String, Object>> sources = new ArrayList<>(); // 初始化sources列表，用于存储文档来源信息
                    for (Map<String, Object> doc : docs) { // 遍历检索到的文档列表
                        Map<String, Object> src = new HashMap<>(); // 为每个文档创建来源信息映射
                        src.put("text", doc.get("text")); // 设置文档文本内容
                        // 解析metadata
                        Object metaObj = doc.get("metadata"); // 获取文档的metadata对象
                        String metaStr; // 声明metadata字符串变量
                        if (metaObj instanceof String) { // 如果metadata是字符串类型
                            metaStr = (String) metaObj; // 直接赋值
                        } else if (metaObj instanceof List && !((List<?>)metaObj).isEmpty()) { // 如果是列表且不为空
                            Object first = ((List<?>)metaObj).get(0); // 获取列表第一个元素
                            metaStr = first != null ? first.toString() : ""; // 转换为字符串
                        } else { // 其他情况
                            metaStr = metaObj != null ? metaObj.toString() : ""; // 转换为字符串或空
                        }
                        Map<String, Object> meta = parseMetadata(metaStr); // 解析metadata JSON字符串
                        src.put("documentName", meta.getOrDefault("source", "未知文档")); // 设置文档名称，默认未知文档
                        src.put("page", meta.getOrDefault("page", 1)); // 设置页码，默认第1页
                        src.put("score", doc.get("score")); // 设置相似度分数
                        src.put("documentId", doc.get("id")); // 添加documentId字段
                        sources.add(src); // 将来源信息添加到列表
                    }
                    log.info(green("[RAG_DEBUG] chat() - sources组装: {}"), sources.size());
                    // 优化：限制上下文长度，提高生成速度
                        String context = sources.stream() // 从sources列表创建流
                            .limit(maxSearchResults()) // 只使用最相关的前 N 个文档（可配置）
                            .map(s -> "- " + s.getOrDefault("text", "")) // 格式化每个文档的文本内容
                            .collect(Collectors.joining("\n")); // 用换行符连接所有文本
                    if (context.trim().isEmpty()) { // 检查上下文是否为空
                        context = "没有找到直接相关的文档内容，请基于建筑行业专业知识回答。"; // 设置默认上下文
                        log.warn(green("[RAG_DEBUG] chat() - 没有找到相关文档，使用默认上下文"));
                    }
                    String prompt = buildPrompt(request.getQuestion(), context, request.getHistory()); // 构建LLM提示词
                    log.info(green("[RAG_DEBUG] chat() - LLM prompt构建完成，长度: {}"), prompt.length());
                    // 调用统一LLM服务生成回答（支持本地llama.cpp和云端API）
                    return llmService.generateCompletion(prompt) // 调用LLMService生成回答
                        .map(response -> { // 处理响应
                            String answer; // 声明回答变量
                            try { // 尝试解析响应
                                if (response.containsKey("choices") && response.get("choices") instanceof List) { // 检查choices字段
                                    List<?> choices = (List<?>) response.get("choices"); // 获取choices列表
                                    if (!choices.isEmpty() && choices.get(0) instanceof Map) { // 检查第一个choice
                                        Map<?, ?> choice = (Map<?, ?>) choices.get(0); // 获取choice映射
                                        if (choice.containsKey("message") && choice.get("message") instanceof Map) { // 检查message字段
                                            Map<?, ?> message = (Map<?, ?>) choice.get("message"); // 获取message映射
                                            answer = message.get("content").toString(); // 提取content作为回答
                                        } else { // message字段不存在或格式错误
                                            answer = "抱歉，无法解析LLM的回答。"; // 设置错误回答
                                        }
                                    } else { // choices为空或格式错误
                                        answer = "抱歉，LLM返回的回答格式不正确。"; // 设置错误回答
                                    }
                                } else { // 没有choices字段
                                    answer = "抱歉，LLM返回的回答格式不正确。"; // 设置错误回答
                                }
                            } catch (Exception e) { // 捕获解析异常
                                log.error(green("[RAG_DEBUG] chat() - LLM回答解析异常: {} - {}"), e.getClass().getSimpleName(), e.getMessage());
                                answer = "抱歉，解析LLM回答时出错。"; // 设置错误回答
                            }
                            log.info(green("[RAG_DEBUG] chat() - LLM回答生成成功，内容前200字: {}"), answer.length() > 200 ? answer.substring(0, 200) + "..." : answer);
                            Map<String, Object> result = new HashMap<>(); // 创建结果映射
                            result.put("thought", "基于知识库检索到相关信息，使用LLM生成回答"); // 设置思考过程
                            result.put("answer", answer); // 设置回答内容
                            result.put("sources", sources); // 设置来源信息
                            return result; // 返回结果
                        })
                        .onErrorResume(e -> { // 处理生成错误
                            log.error(green("[RAG_DEBUG] chat() - LLM回答生成异常: {} - {}"), e.getClass().getSimpleName(), e.getMessage());
                            Map<String, Object> errorResult = new HashMap<>(); // 创建错误结果
                            errorResult.put("thought", "LLM回答生成失败"); // 设置错误思考
                            errorResult.put("answer", "抱歉，我暂时无法回答您的问题。请稍后再试。"); // 设置错误回答
                            errorResult.put("sources", sources); // 设置来源信息
                            return Mono.just(errorResult); // 返回错误结果
                        });
                });
            })
            .onErrorResume(e -> { // 处理整体错误
                log.error("[Milvus] Chat error: {}", e.getMessage()); // 记录错误
                Map<String, Object> errorResult = new HashMap<>(); // 创建错误结果
                errorResult.put("thought", "处理请求时发生错误"); // 设置错误思考
                errorResult.put("answer", "抱歉，处理您的问题时遇到了技术问题：" + e.getMessage()); // 设置错误回答
                errorResult.put("sources", new ArrayList<>()); // 设置空来源列表
                return Mono.just(errorResult); // 返回错误结果
            });
    }

    public Flux<String> chatStream(ChatRequest request) {
        String tenantId = TenantContext.getCurrentTenantId();
        return chatStream(request, tenantId);
    }

    /**
     * 流式对话（SSE）接口
     * 
     * 功能与特性：
     * - 立即发送一个 loading 事件以便前端快速建立连接与展示加载态
     * - 生成查询 embedding 并检索 Milvus（使用较小的 topK），将检索片段做为 sources 先发送给前端
     * - 构建 prompt 并调用 LLM 的流式接口（/v1/chat/completions, stream=true），将 LLM 返回的 NDJSON 逐条解析并转为 SSE
     * - 在 AI 首次输出前持续发送 loading 心跳，并确保最终发送 done 事件避免前端长时间卡住
     * - 对于异常会返回 type=error 的 SSE 消息
     */
    public Flux<String> chatStream(ChatRequest request, String tenantId) { // 流式对话方法，支持SSE
        log.info("[DEBUG] RagService.chatStream called with request: " + request); // 记录方法调用
        log.info("[DEBUG] request.getQuestion(): " + request.getQuestion()); // 记录问题内容
        log.info("[DEBUG] request.getKnowledgeBaseId(): " + request.getKnowledgeBaseId()); // 记录知识库ID
        String effectiveTenantId = (tenantId == null || tenantId.trim().isEmpty()) ? "default" : tenantId.trim(); // 计算有效租户ID
        String rawCollectionName = request.getKnowledgeBaseId() != null ? request.getKnowledgeBaseId() : "default_knowledge_base"; // 获取原始知识库名称
        String kbKey = TenantUtils.buildKbCollectionKey(rawCollectionName);
        String tenantCollectionName = TenantUtils.buildTenantCollectionName(effectiveTenantId, rawCollectionName); // 构建租户集合名称（KB 名称统一 hash）
        log.debug("[RAG] 开始AI对话流程，问题: {}", request.getQuestion());

        log.info(red("[KB_TRACE] chatStream start: tenantId={} rawKbId='{}' kbKey='{}' question='{}' getQuestion()='{}' getKnowledgeBaseId()='{}'"),
            effectiveTenantId,
            rawCollectionName,
            kbKey,
            request.getQuestion(),
            request.getQuestion(),
            request.getKnowledgeBaseId());

        // 日志2：每次问答打印实际连接的 Milvus
        logMilvusConnectionTrace();
        // 日志3：每次问答打印当前 Milvus 的所有集合名（用于核对实际命中的是哪个库/实例）
        logMilvusCollectionsTrace("request start (chatStream)");
        
        
        // 简化集合检查，减少数据库查询
        boolean exists = milvusDbService.hasCollection(tenantCollectionName); // 检查集合是否存在
        log.info(red("[KB_TRACE] target collection exists? {} => {}"), tenantCollectionName, exists);
        if (!exists) {
            // 集合不存在，记录警告，但继续使用该集合名称（搜索结果为空时模型会基于知识回答）
            log.warn(red("[KB_TRACE] target collection NOT found: {} (user's knowledge base '{}')"), tenantCollectionName, rawCollectionName);
            logMilvusCollectionsTrace("target collection not found: " + tenantCollectionName);
            // 不再 fallback 到其他集合——知识库无数据时让模型基于专业知识回答
        } else {
            // 集合存在，检查是否有数据
            try {
                int count = milvusDbService.getCollectionCount(tenantCollectionName);
                if (count == 0) {
                    log.warn(red("[KB_TRACE] target collection {} exists but EMPTY ({} documents)"), tenantCollectionName, count);
                    logMilvusCollectionsTrace("collection empty: " + tenantCollectionName);
                    // 不再 fallback 到其他集合——知识库为空时让模型基于专业知识回答
                } else {
                    log.info("[Milvus] 集合 {} 有 {} 个文档", tenantCollectionName, count);
                }
            } catch (Exception e) {
                log.warn("[Milvus] 检查集合 {} 数据时出错: {}", tenantCollectionName, e.getMessage());
                logMilvusCollectionsTrace("count_check_error: " + tenantCollectionName);
                // 不再 fallback——让模型基于知识回答，即使检查失败
            }
        }

        // 创建final变量用于lambda表达式
        final String finalCollectionName = tenantCollectionName; // 绑定最终集合名
        log.info(red("[KB_TRACE] final collection for retrieval: {} (tenantId={})"), finalCollectionName, effectiveTenantId);
        log.debug("[RAG] 开始生成embedding"); // 记录开始生成嵌入

        // 先立刻发送一个 loading 事件，确保 SSE 连接尽快建立并让前端有即时反馈。
        String initialLoadingSse;
        try {
            Map<String, Object> loadMsg = new HashMap<>();
            loadMsg.put("type", "loading");
            loadMsg.put("content", "模型加载中，请稍候...");
            initialLoadingSse = "data: " + objectMapper.writeValueAsString(loadMsg) + "\n\n";
        } catch (Exception e) {
            initialLoadingSse = "data: {\"type\":\"loading\",\"content\":\"模型加载中，请稍候...\"}\n\n";
        }

        Flux<String> mainFlow = Flux.defer(() -> {
            // ========== Phase 1: 查询预处理 ==========
            QueryPreprocessResult preprocessResult = queryPreprocessor(request.getQuestion(), rawCollectionName, effectiveTenantId);
            if (!preprocessResult.isValidQuery()) {
                log.info("[RAG_PREPROCESS] 查询无效，跳过向量检索: reason={} category={} question='{}'",
                    preprocessResult.getFallbackReason(), preprocessResult.getInvalidCategory(), request.getQuestion());
                // Layer 3 分级无效回复
                String invalidPrompt = buildInvalidQueryPrompt(request.getQuestion(), preprocessResult.getInvalidCategory(), effectiveTenantId);
                return buildAndStreamResponse(invalidPrompt, new ArrayList<>(), finalCollectionName);
            }

            String effectiveQuery = preprocessResult.getEnhancedQuery();
            String queryType = preprocessResult.getQueryType();
            log.info("[RAG_PREPROCESS] 查询预处理完成: 原始='{}' 扩展后='{}' 类型={}", request.getQuestion(), effectiveQuery, queryType);

            // ========== Phase 2: 混合检索（BM25 + 向量 + RRF 融合，响应式非阻塞）==========
            return generateEmbedding(effectiveQuery)
                .flatMapMany(queryEmbedding -> {
                    // 使用响应式 searchReactive 在 boundedElastic 线程执行混合搜索
                    return hybridSearchService.searchReactive(effectiveQuery, finalCollectionName, maxSearchResults())
                        .flatMapMany(docs -> {
                            log.info("[RAG] 混合搜索完成，找到文档数量: {}", docs.size());
                            if (docs.isEmpty()) {
                                log.warn(red("[KB_TRACE] hybrid search returned 0 docs (stream): collection={} effectiveQuery='{}'"), finalCollectionName, effectiveQuery);
                            }

                            // ========== Phase 3: 检索质量过滤（放宽阈值，尽量多保留结果）==========
                            List<Map<String, Object>> filteredDocs = filterLowQualityResults(docs, 5.0);
                            log.info("[RAG_PREPROCESS] 检索质量过滤: 原始={} 过滤后={}", docs.size(), filteredDocs.size());

                            // ========== Phase 3.5: Layer 2 - 低置信度标记（不再清空结果，尽量保留）==========
                            boolean layer2Triggered = false;
                            if (!filteredDocs.isEmpty()) {
                                double topScore = getTopScore(filteredDocs);
                                if (topScore > 5.0) {
                                    log.warn("[RAG_PREPROCESS] Layer2 低置信度标记: topScore={} > 5.0，保留但标记为低置信", topScore);
                                    layer2Triggered = true;
                                    // 不再清空结果，让模型参考这些片段，哪怕置信度低
                                }
                            }

                            // 组装sources和context
                            List<Map<String, Object>> sources = buildSourcesFromDocs(filteredDocs);
                            String context = buildContextFromSources(sources, 4000);
                            boolean hasRelevantDocs = !sources.isEmpty();
                            log.info("[RAG_PREPROCESS] context组装完成: hasRelevantDocs={} sources={} contextLength={}",
                                hasRelevantDocs, sources.size(), context.length());

                            // ========== Phase 4: 构建Prompt（Layer 3 分级回复）==========
                            String prompt = buildRagPrompt(
                                effectiveQuery, context, hasRelevantDocs, sources.size(),
                                request.getHistory(), queryType, effectiveQuery
                            );

                            // ========== Phase 5: 流式生成并包装SSE ==========
                            return streamSseResponse(prompt, sources);
                        });
                });
        });

        return Flux.concat(
            Flux.just(initialLoadingSse),
            mainFlow
        );
    }

    public Mono<String> addDocument(String filePath, String knowledgeBaseId) {
        String tenantId = TenantContext.getCurrentTenantId();
        return addDocument(filePath, knowledgeBaseId, tenantId);
    }

    /**
     * 文档入库接口（将本地文件解析为切片并写入 Milvus）
     * 
     * 流程：
     * - 根据租户和知识库ID构建文件的物理路径（兼容 admin 特殊路径）
     * - 使用 FileParserService 进行解析并将文本切成较小分片
     * - 为每个分片生成 embedding（并发限制），构建 metadata 并将数据批量写入 Milvus
     * - 写入后会触发 flush 与索引创建校验；若索引未创建会进行手动创建尝试，并带有限次重试
     * - 返回友好的状态信息或抛出异常以便上层重试/报告
     */
    public Mono<String> addDocument(String filePath, String knowledgeBaseId, String tenantId) {
        String effectiveTenantId = (tenantId == null || tenantId.trim().isEmpty()) ? "default" : tenantId.trim();

        log.info(blue("[KB_TRACE_BLUE] RagService.addDocument called: tenantId={} knowledgeBaseId='{}' filePath='{}'"),
            effectiveTenantId,
            knowledgeBaseId,
            filePath);
        
        String fullPath;

        // filePath 既可能是"相对 KB 根目录"的路径（如 GB9665.doc），也可能是"带 KB 前缀"的路径（如 test/GB9665.doc）。
        // 为避免拼接成 .../kbId/kbId/...，这里将其规范化成"相对 KB 根目录"的路径用于文件读取。
        String filePathWithinKb = (filePath == null) ? "" : filePath;
        try {
            if (knowledgeBaseId != null && !knowledgeBaseId.trim().isEmpty()) {
                String fpNorm = filePathWithinKb.replace('\\', '/');
                String kbNorm = knowledgeBaseId.trim().replace('\\', '/');
                // 兼容前端/上传逻辑传入 "kbId/xxx" 的情况
                if (!kbNorm.isEmpty() && fpNorm.startsWith(kbNorm + "/")) {
                    fpNorm = fpNorm.substring(kbNorm.length() + 1);
                    filePathWithinKb = fpNorm.replace('/', File.separatorChar);
                    log.info(red("[KB_TRACE] normalized ingest filePath: raw='{}' kbId='{}' -> withinKb='{}'"), filePath, knowledgeBaseId, filePathWithinKb);
                }
            }
        } catch (Exception ignore) {
            // 规范化失败不影响后续逻辑，继续按原 filePath 处理
        }
        
        // admin账户特殊处理：使用现有知识库根目录
        // 约定：admin 的知识库存储结构为 <kbRoot>/<knowledgeBaseId>/<filePathWithinKb>
        // 兼容：部分知识库实际存储在 <kbRoot>/<filePathWithinKb>（无 kbId 中间目录），先尝试前者，找不到再回退
        if ("admin".equals(effectiveTenantId)) {
            String kbRoot = appConfig.getKnowledgeBase().getPath();
            String kbId = (knowledgeBaseId == null) ? "" : knowledgeBaseId.trim();

            String safeRelativePath = (filePathWithinKb == null) ? "" : filePathWithinKb;
            while (safeRelativePath.startsWith("/") || safeRelativePath.startsWith("\\")) {
                safeRelativePath = safeRelativePath.substring(1);
            }

            if (!kbId.isEmpty()) {
                // 先尝试 kbRoot/kbId/relPath；找不到则回退到 kbRoot/relPath
                Path withKbId = Paths.get(kbRoot, kbId, safeRelativePath);
                Path withoutKbId = Paths.get(kbRoot, safeRelativePath);
                if (Files.exists(withKbId)) {
                    fullPath = withKbId.toString();
                } else if (Files.exists(withoutKbId)) {
                    fullPath = withoutKbId.toString();
                    log.info("[RAG] admin账户回退到 kbRoot 直连路径（无 kbId 中间目录）: {}", fullPath);
                } else {
                    fullPath = withKbId.toString(); // 两边都没有就用前者，后续会报错
                    log.warn("[RAG] admin账户路径两边都不存在: withKbId={}, withoutKbId={}", withKbId, withoutKbId);
                }
            } else {
                fullPath = Paths.get(kbRoot, safeRelativePath).toString();
            }
            log.info("[RAG] admin账户使用现有知识库路径: {}", fullPath);
        } else {
            // 其他用户使用租户隔离的知识库路径
            String tenantKbPath = TenantUtils.buildTenantKnowledgeBasePath(
                effectiveTenantId,
                knowledgeBaseId != null ? knowledgeBaseId : "default_knowledge_base",
                appConfig.getTenant().getStorageMode(),
                appConfig.getTenant().getTenantKbPath()
            );
            // 兼容：租户路径不存在时，回退到 kbRoot（与 admin 账户一致）
            String kbId = (knowledgeBaseId == null) ? "" : knowledgeBaseId.trim();
            Path tenantPath = Paths.get(tenantKbPath, filePathWithinKb);
            String kbRoot = appConfig.getKnowledgeBase().getPath();
            // 优先尝试 kbRoot/kbId/relPath（实际文件存放位置）
            Path kbRootWithKbId = Paths.get(kbRoot, kbId, filePathWithinKb);
            Path kbRootPath = Paths.get(kbRoot, filePathWithinKb);
            if (Files.exists(tenantPath)) {
                fullPath = tenantPath.toString();
                log.info("[RAG] 使用租户隔离路径: {}", fullPath);
            } else if (Files.exists(kbRootWithKbId)) {
                // 优先使用 kbRoot/kbId/relPath（实际文档存放位置）
                fullPath = kbRootWithKbId.toString();
                log.info("[RAG] 回退到 kbRoot/kbId 直连路径（实际文档位置）: {}", fullPath);
            } else if (Files.exists(kbRootPath)) {
                fullPath = kbRootPath.toString();
                log.info("[RAG] 回退到 kbRoot 直连路径: {}", fullPath);
            } else {
                // 两边都没有就用租户路径，后续会报错
                fullPath = tenantPath.toString();
                log.warn("[RAG] 租户路径和 kbRoot 路径都不存在: tenantPath={}, kbRootWithKbId={}, kbRootPath={}", tenantPath, kbRootWithKbId, kbRootPath);
            }
        }
        
        // 日志1：检查是否在文件系统里创建了目录/文件是否存在
        try {
            Path p = Paths.get(fullPath);
            boolean fileExists = Files.exists(p);
            boolean parentExists = (p.getParent() != null) && Files.exists(p.getParent());
            log.warn(red("[KB_TRACE] ingest fs check: tenantId={}, kbId={}, filePath(raw)={}, fullPath={}, fileExists={}, parentExists={}, parent={}"),
                effectiveTenantId,
                knowledgeBaseId,
                filePath,
                fullPath,
                fileExists,
                parentExists,
                p.getParent());
        } catch (Exception e) {
            log.warn(red("[KB_TRACE] ingest fs check failed: {}"), e.getMessage());
        }

        // 日志2：入库时也打印 Milvus 连接信息（用于核对是不是同一个 Milvus）
        logMilvusConnectionTrace();

        log.info("[RAG] 开始处理文档（租户隔离）: tenantId={}, kbId={}, path={}", effectiveTenantId, knowledgeBaseId, fullPath);
        
        return fileParserService.parseFileReactive(fullPath)
            .map(content -> {
                log.info("[分片] 文档解析后内容长度: {}", content.length());

                // 保存解析后的 markdown 内容到 .md 文件
                try {
                    String mdFileName = fullPath;
                    if (mdFileName.toLowerCase().endsWith(".pdf")) {
                        mdFileName = mdFileName.substring(0, mdFileName.length() - 4);
                    } else if (mdFileName.toLowerCase().endsWith(".docx")) {
                        mdFileName = mdFileName.substring(0, mdFileName.length() - 5);
                    } else if (mdFileName.toLowerCase().endsWith(".doc")) {
                        mdFileName = mdFileName.substring(0, mdFileName.length() - 4);
                    }
                    mdFileName = mdFileName + ".md";
                    java.nio.file.Path mdPath = java.nio.file.Paths.get(mdFileName);
                    // 确保父目录存在
                    if (mdPath.getParent() != null) {
                        java.nio.file.Files.createDirectories(mdPath.getParent());
                    }
                    java.nio.file.Files.writeString(mdPath, content, java.nio.charset.StandardCharsets.UTF_8);
                    log.info("[入库] 已保存解析后的 markdown 文件: {}", mdPath);
                } catch (Exception mdEx) {
                    log.warn("[入库] 保存 markdown 文件失败（非阻塞，继续入库）: {}", mdEx.getMessage());
                }

                // 使用带章节信息的分片器，保留页码和章节信息
                List<ChunkWithMetadata> chunksWithMetadata = splitDocumentIntoChunksWithMetadata(content, 1000);
                log.info("[分片] 文档分片数量: {}", chunksWithMetadata.size());
                return chunksWithMetadata;
            })
            .onErrorResume(e -> {
                log.error("[入库] 文档处理失败: {} - {}", filePath, e.getMessage());
                return Mono.just(new ArrayList<ChunkWithMetadata>());
            })
            .flatMap(chunksWithMetadata -> {
                if (chunksWithMetadata.isEmpty()) {
                    log.warn("[入库] 文档解析为空，跳过向量入库但标记成功: {}", filePath);
                    return Mono.just("文档上传成功但内容解析为空，已保存文件: " + filePath);
                }

                List<String> chunkIds = chunksWithMetadata.stream()
                    .map(cwm -> UUID.randomUUID().toString())
                    .collect(Collectors.toList());
                List<String> chunks = chunksWithMetadata.stream().map(cwm -> cwm.text).collect(Collectors.toList());
                List<Integer> pages = chunksWithMetadata.stream().map(cwm -> cwm.page).collect(Collectors.toList());
                List<String> sections = chunksWithMetadata.stream().map(cwm -> cwm.section).collect(Collectors.toList());
                List<String> sectionPaths = chunksWithMetadata.stream().map(cwm -> cwm.sectionPath).collect(Collectors.toList());

                // 并行处理，提高embedding生成速度
                return Flux.fromIterable(chunks)
                    .flatMap(chunk -> generateEmbedding(chunk).map(this::toFloatList), 3)
                    .collectList()
                    .flatMap(embeddings -> {
                        if (embeddings.size() != chunks.size()) {
                            log.error("[入库] embedding数量与分片数量不匹配: embeddings={}, chunks={}", embeddings.size(), chunks.size());
                            return Mono.error(new RuntimeException("embedding生成失败，数量不匹配"));
                        }

                        List<String> metadatas = new ArrayList<>();
                        String sourceKey = TenantUtils.buildVectorSourceKey(filePath);
                        String timestamp = new Date().toString();
                        for (int i = 0; i < chunks.size(); i++) {
                            Map<String, Object> metadata = new HashMap<>();
                            metadata.put("source", sourceKey);
                            metadata.put("timestamp", timestamp);
                            metadata.put("page", pages.get(i));
                            metadata.put("section", sections.get(i));
                            metadata.put("sectionPath", sectionPaths.get(i));
                            String metadataJson = toJson(metadata);
                            metadatas.add(metadataJson);
                        }
                        
        // 使用租户隔离 + KB 名称 hash 映射，避免中文/符号清理导致集合名冲突
        String rawCollectionName = (knowledgeBaseId != null && !knowledgeBaseId.trim().isEmpty()) ? knowledgeBaseId : "default_knowledge_base";
        String kbKey = TenantUtils.buildKbCollectionKey(rawCollectionName);
        String tenantCollectionName = TenantUtils.buildTenantCollectionName(effectiveTenantId, rawCollectionName);

        log.warn(red("[KB_TRACE] ingest start -> tenantId={}, kbId={}, rawKbId='{}', kbKey='{}', collection={}"),
            effectiveTenantId,
            knowledgeBaseId,
            rawCollectionName,
            kbKey,
            tenantCollectionName);
        
        // 确保集合存在，即使没有内容也要创建空集合
        boolean collectionExisted = milvusDbService.hasCollection(tenantCollectionName);
        if (!collectionExisted) {
            log.info("[入库] 集合不存在，预先创建空集合: {}", tenantCollectionName);
            milvusDbService.createCollection(tenantCollectionName);
            log.warn(red("[KB_TRACE] empty collection created: {}"), tenantCollectionName);
        }
        
        boolean beforeExists = milvusDbService.hasCollection(tenantCollectionName);
        log.warn(red("[KB_TRACE] ingest precheck collection exists? {} => {}"), tenantCollectionName, beforeExists);

                        log.info("[入库] 开始写入Milvus（租户隔离）: tenantId={}, collection={}, 分片数={}, embedding数={}", effectiveTenantId, tenantCollectionName, chunks.size(), embeddings.size());
                        
                        // 添加索引创建重试机制
                        return Mono.fromCallable(() -> {
                            int attempts = 0;
                            int maxAttempts = 3;
                            while (attempts < maxAttempts) {
                                try {
                                    milvusDbService.addDocuments(tenantCollectionName, chunkIds, embeddings, chunks, metadatas);
                                    log.info("[入库] 写入Milvus完成: collection={}, 分片数={}", tenantCollectionName, chunks.size());

                                    boolean afterExists = milvusDbService.hasCollection(tenantCollectionName);
                                    log.warn(red("[KB_TRACE] ingest postcheck collection exists? {} => {}"), tenantCollectionName, afterExists);
                                    
                                    // 强制刷新集合，确保新数据立即可查询
                                    milvusDbService.flushCollection(tenantCollectionName);
                                    log.info("[入库] 集合已刷新: {}", tenantCollectionName);

                                    // 同步填充 BM25 内存倒排索引，确保混合检索立即生效
                                    try {
                                        List<Map<String, Object>> bm25Docs = new ArrayList<>();
                                        for (int i = 0; i < chunks.size(); i++) {
                                            Map<String, Object> doc = new HashMap<>();
                                            doc.put("id", chunkIds.get(i));
                                            doc.put("text", chunks.get(i));
                                            // 从 metadata JSON 中提取 source
                                            String metaJson = metadatas.get(i);
                                            try {
                                                @SuppressWarnings("unchecked")
                                                Map<String, Object> meta = objectMapper.readValue(metaJson, Map.class);
                                                doc.put("source", meta.get("source"));
                                            } catch (Exception e) {
                                                doc.put("source", "unknown");
                                            }
                                            bm25Docs.add(doc);
                                        }
                                        bm25Service.indexDocuments(bm25Docs);
                                        log.info("[入库] BM25索引填充完成: collection={}, 文档数={}", tenantCollectionName, bm25Docs.size());
                                    } catch (Exception e) {
                                        log.warn("[入库] BM25索引填充失败（不影响向量检索）: {}", e.getMessage());
                                    }

                                    // 验证索引是否创建成功
                                    boolean indexExists = milvusDbService.hasIndex(tenantCollectionName);
                                    if (indexExists) {
                                        log.info("[入库] 索引创建成功: {}", tenantCollectionName);
                                        return "文档处理成功：" + filePath + "，共处理" + chunks.size() + "个片段，存储到集合：" + tenantCollectionName + "，索引已创建";
                                    } else {
                                        log.warn("[入库] 索引创建失败，尝试手动创建: {}", tenantCollectionName);
                                        boolean indexCreated = milvusDbService.createIndex(tenantCollectionName);
                                        if (indexCreated) {
                                            log.info("[入库] 手动索引创建成功: {}", tenantCollectionName);
                                            return "文档处理成功：" + filePath + "，共处理" + chunks.size() + "个片段，存储到集合：" + tenantCollectionName + "，索引已手动创建";
                                        } else {
                                            log.error("[入库] 手动索引创建失败: {}", tenantCollectionName);
                                                throw new RuntimeException("索引创建失败: " + tenantCollectionName);
                                        }
                                    }
                                } catch (Exception e) {
                                    attempts++;
                                    log.warn("[入库] 第{}次尝试失败: {}", attempts, e.getMessage());
                                    if (attempts >= maxAttempts) {
                                        throw new RuntimeException("文档处理失败，已重试" + maxAttempts + "次: " + e.getMessage());
                                    }
                                    try {
                                        Thread.sleep(2000); // 等待后重试
                                    } catch (InterruptedException ie) {
                                        Thread.currentThread().interrupt();
                                        throw new RuntimeException("重试被中断");
                                    }
                                }
                            }
                            throw new RuntimeException("文档处理失败");
                        });
                    });
            })
            .onErrorResume(e -> {
                log.error("[入库] 文档处理失败: {}", e.getMessage());
                return Mono.error(new RuntimeException("文档处理失败：" + e.getMessage()));
            });
    }

    /**
     * 提取查询中的关键词
     * @param query 查询文本
     * @return 关键词数组
     */
    private String[] extractKeywords(String query) {
        // 简单的中文关键词提取
        String[] commonWords = {"的", "是", "有", "哪些", "什么", "如何", "怎么", "为什么", "在", "和", "与", "或", "但", "然而", "因此", "所以"};
        
        // 移除常见停用词
        String processedQuery = query;
        for (String word : commonWords) {
            processedQuery = processedQuery.replace(word, " ");
        }
        
        // 按空格和标点符号分割
        String[] words = processedQuery.split("[\\s\\p{Punct}]+");
        
        // 过滤掉太短的词
        List<String> keywords = new ArrayList<>();
        for (String word : words) {
            if (word.length() >= 2) {
                keywords.add(word.trim());
            }
        }
        
        log.info("[RAG] 提取的关键词: {}", keywords);
        return keywords.toArray(new String[0]);
    }
    
    // ========== 查询预处理相关 ==========

    /**
     * 查询预处理结果
     */
    public static class QueryPreprocessResult {
        private final String enhancedQuery;
        private final boolean isValidQuery;
        private final String fallbackReason;   // 详细原因（用于 Layer 3 分级回复）
        private final String queryType;        // 分类（invalid/normal/short_expanded）
        private final String invalidCategory;   // 无效查询分类（闲聊/太短/超标点等，用于 Layer 3）

        public QueryPreprocessResult(String enhancedQuery, boolean isValidQuery, String fallbackReason, String queryType, String invalidCategory) {
            this.enhancedQuery = enhancedQuery;
            this.isValidQuery = isValidQuery;
            this.fallbackReason = fallbackReason;
            this.queryType = queryType;
            this.invalidCategory = invalidCategory;
        }

        public String getEnhancedQuery() { return enhancedQuery; }
        public boolean isValidQuery() { return isValidQuery; }
        public String getFallbackReason() { return fallbackReason; }
        public String getQueryType() { return queryType; }
        public String getInvalidCategory() { return invalidCategory; }

        public static QueryPreprocessResult invalid(String reason) {
            return new QueryPreprocessResult("", false, reason, "invalid", reason);
        }

        public static QueryPreprocessResult invalid(String reason, String category) {
            return new QueryPreprocessResult("", false, reason, "invalid", category);
        }
    }

    /**
     * 预处理用户查询（防止无意义查询污染向量检索）
     * 核心逻辑：
     * 1. 检测无意义查询（太短、纯停用词、纯标点）
     * 2. 短查询+有知识库名称 → 用 KB 名称扩展
     * 3. 超长输入截断
     * 4. 返回结构化结果供后续流程判断
     *
     * Layer 0 扩展停用词表（~150 条，6 大类别）：
     * - 闲聊问候、确认语/简单回应、无效测试、情绪表情
     * - 知识库自指查询、对话元查询、其他系统/元级查询
     */
    public QueryPreprocessResult queryPreprocessor(String rawQuery, String knowledgeBaseId, String tenantId) {
        if (rawQuery == null || rawQuery.trim().isEmpty()) {
            return QueryPreprocessResult.invalid("查询为空");
        }

        // ===== Layer 0: 清理换行符和多余空白 =====
        // 将换行符、制表符替换为空格，合并多个连续空白为单个空格
        String cleanedQuery = rawQuery.replaceAll("[\\r\\n\\t]+", " ").replaceAll("\\s+", " ");
        String trimmed = cleanedQuery.trim();

        // ===== Layer 0.1: 超长输入截断 =====
        String effectiveQuery = trimmed;
        if (trimmed.length() > 1500) {
            effectiveQuery = "以下是一个长文本片段的核心问题：" + trimmed.substring(0, 800);
            log.info("[RAG_PREPROCESS] 查询超长已截断: 原始={} 截断后={}", trimmed.length(), effectiveQuery.length());
        }

        // ===== Layer 0.2: 扩展停用词表（~150 条，6 类别）+ 智能匹配（去尾号/子串包含）=====
        // 停用词集合（不含标点，与输入去掉末尾标点后的内容做匹配）
        Set<String> stopWords = new HashSet<>(Arrays.asList(
            // 闲聊问候（~25 条）
            "你好", "打招呼", "在不在", "有没有人", "hello", "hi", "hey", "嗨", "您好呀",
            "在吗", "有人吗", "哪位", "有人能帮我吗", "你好啊", "请问有人吗", "哈喽",
            "good morning", "good afternoon", "good evening", "morning", "afternoon", "evening",
            "晚上好", "早上好", "中午好",
            // 确认语/简单回应（~15 条）
            "对", "没错", "是的", "好的", "知道了", "了解", "明白了", "可以", "行",
            "ok", "okay", "收到", "嗯", "嗯嗯", "对对",
            // 无效测试（~15 条）
            "test", "testing", "tests", "just a test",
            "123", "asdf", "qwer", "ttt", "测试消息",
            // 情绪/表情符号（~10 条）
            "😭", "😂", "👍", "❤️", "🎉", "😊", "🥰", "🤔", "😄", "👏"
        ));
        // 知识库自指查询（~25 条）- 用关键词片段检测，无需精确匹配
        // 对话元查询（~25 条）- 关键词片段
        // 其他系统/元级查询（~20 条）
        Set<String> systemKeywords = new HashSet<>(Arrays.asList(
            "你是怎么工作的", "你的原理是什么", "你怎么回答问题", "你是基于什么",
            "你的算法", "模型信息", "用的什么模型", "llm", "embedding", "rag",
            "怎么检索的", "检索原理", "你是人工智能吗", "你是聊天机器人吗",
            "你是chatgpt吗", "你是gpt吗", "你是大模型吗", "你是llm吗"
        ));

        // 去除末尾标点符号后进行匹配（处理用户输入带"？"的情况）
        String normalized = trimmed.replaceAll("[？?。.]+$", "").trim().toLowerCase();
        if (stopWords.contains(normalized)) {
            log.info("[RAG_PREPROCESS] 检测到停用词查询: '{}'，跳过向量检索", normalized);
            return QueryPreprocessResult.invalid("停用词查询", "停用词查询");
        }

        // ===== Layer 0.2a: 操作型查询快速通路（优先级最高）=====
        // 包含操作动词的查询是用户想"使用"知识库，必须分类为 NORMAL，不走 LLM 分类
        boolean hasOperationalVerb = normalized.contains("查询") || normalized.contains("搜索")
            || normalized.contains("帮我") || normalized.contains("总结") || normalized.contains("解释")
            || normalized.contains("概括") || normalized.contains("列出") || normalized.contains("找")
            || normalized.contains("查看") || normalized.contains("检索") || normalized.contains("归纳")
            || normalized.contains("梳理") || normalized.contains("分析") || normalized.contains("对比")
            || normalized.contains("比较") || normalized.contains("说明") || normalized.contains("介绍")
            || normalized.contains("详细") || normalized.contains("展开") || normalized.contains("阐述");
        if (hasOperationalVerb) {
            log.info("[RAG_PREPROCESS] 操作型查询快速通路: '{}'，直接走正常检索流程", normalized);
            // 跳过后续所有规则和 LLM 分类，直接返回有效查询
            String cleaned = effectiveQuery
                .replaceAll("[啊呀哇哦呢吧嘛哟呗]+$", "")  // 去除末尾语气词
                .replaceAll("[？?！!。.，,；;：:]+$", "")    // 去除末尾标点
                .trim();
            if (!cleaned.isEmpty()) {
                return new QueryPreprocessResult(cleaned, true, null, "operational", "normal");
            }
            return new QueryPreprocessResult(effectiveQuery, true, null, "operational", "normal");
        }

        // ===== Layer 0.2: 关键词智能匹配（快速通路，覆盖最常见模式）=====
        // 知识库自指查询：必须同时包含"知识库" + 疑问词/引介词
        // 模式 = 知识库 + (哪些/什么/有什么/支持/涵盖/能查/能回答/关于/介绍)
        boolean kbHasQ = normalized.contains("哪些") || normalized.contains("什么")
            || normalized.contains("有") || normalized.contains("支持")
            || normalized.contains("涵盖") || normalized.contains("关于")
            || normalized.contains("介绍");
        if (normalized.contains("知识库") && kbHasQ) {
            log.info("[RAG_PREPROCESS] 规则命中 KB_SELF: '{}'，跳过向量检索", normalized);
            return QueryPreprocessResult.invalid("知识库自指查询（规则）", "知识库自指查询");
        }
        // 单独引介类："介绍一下你自己"（不需要知识库词）
        if (normalized.contains("介绍自己") || normalized.contains("介绍一下")
            || normalized.contains("你是谁") || normalized.contains("你叫什么")
            || normalized.contains("关于你自己")) {
            log.info("[RAG_PREPROCESS] 规则命中 KB_SELF（引介类）: '{}'，跳过向量检索", normalized);
            return QueryPreprocessResult.invalid("知识库自指查询（规则）", "知识库自指查询");
        }

        // 对话元查询：包含"本次对话"或"之前"/"上条"+"对话/聊/问"
        boolean hasConversationWord = normalized.contains("对话") || normalized.contains("聊过")
            || normalized.contains("交谈") || normalized.contains("会话");
        boolean hasHistoryWord = normalized.contains("之前") || normalized.contains("本次")
            || normalized.contains("历史") || normalized.contains("上条") || normalized.contains("上一");
        if (hasConversationWord && (hasHistoryWord || normalized.contains("记录"))
            || normalized.contains("本次对话") || normalized.contains("交谈了几次")) {
            log.info("[RAG_PREPROCESS] 规则命中 CONV_HISTORY: '{}'，跳过向量检索", normalized);
            return QueryPreprocessResult.invalid("对话元查询（规则）", "对话元查询");
        }
        // 对话元查询：包含"我之前问"或"我说了哪些"或"第一次问"或"第几次"
        if (normalized.contains("我之前问") || normalized.contains("我说了哪些")
            || normalized.contains("我的问题记录") || normalized.contains("这次对话多久")
            || normalized.contains("第一次问") || normalized.contains("第几次")
            || normalized.contains("问了什么") || normalized.contains("问了你什么")) {
            log.info("[RAG_PREPROCESS] 规则命中 CONV_HISTORY: '{}'，跳过向量检索", normalized);
            return QueryPreprocessResult.invalid("对话元查询（规则）", "对话元查询");
        }

        // 系统/元级查询：问"你是谁/怎么工作的/用的什么模型/llm/chatgpt/gpt"
        if (normalized.contains("你是怎么工作的") || normalized.contains("你的原理")
            || normalized.contains("你的算法") || normalized.contains("模型信息")
            || normalized.contains("用的什么模型") || normalized.contains("llm")
            || normalized.contains("embedding") || normalized.contains("rag")
            || normalized.contains("怎么检索") || normalized.contains("你是人工智能")
            || normalized.contains("聊天机器人") || normalized.contains("chatgpt")
            || normalized.contains("gpt") || normalized.contains("大模型")) {
            log.info("[RAG_PREPROCESS] 规则命中 SYSTEM_QUERY: '{}'，跳过向量检索", normalized);
            return QueryPreprocessResult.invalid("系统/元级查询（规则）", "系统/元级查询");
        }

        // ===== Layer 0.5: LLM 智能意图分类（仅拦截闲聊和对话历史，KB_SELF/OUT_OF_DOMAIN 放行）=====
        // 只拦截 CONV_HISTORY 和 CHITCHAT，因为规则层已覆盖大部分
        // KB_SELF 和 OUT_OF_DOMAIN 放行，避免 LLM 误判导致正常查询被阻塞
        QueryIntentClassifier.ClassifyResult llmResult =
            queryIntentClassifier.classify(effectiveQuery, null);
        if (llmResult == QueryIntentClassifier.ClassifyResult.CONV_HISTORY
            || llmResult == QueryIntentClassifier.ClassifyResult.CHITCHAT) {
            log.info("[RAG_PREPROCESS] LLM分类命中 {}: '{}'，跳过向量检索", llmResult.name(), effectiveQuery);
            return QueryPreprocessResult.invalid("LLM分类-" + llmResult.name(), llmResult.name());
        }

        // ===== Layer 0.3: 纯标点符号检测 =====
        if (!trimmed.matches(".*[\u4e00-\u9fa5a-zA-Z0-9].*")) {
            log.info("[RAG_PREPROCESS] 检测到纯标点查询: '{}'，跳过向量检索", trimmed);
            return QueryPreprocessResult.invalid("纯标点查询", "纯标点查询");
        }

        // ===== Layer 0.4: 纯数字/代码片段检测 =====
        if (trimmed.matches("^[\\d\\s.,;:\\-+=*/%()]+$")) {
            log.info("[RAG_PREPROCESS] 检测到纯数字/代码片段查询: '{}'，跳过向量检索", trimmed);
            return QueryPreprocessResult.invalid("纯数字/代码片段", "代码片段");
        }
        if (trimmed.matches("^[_$a-zA-Z][_$a-zA-Z0-9]*\\s*[(=].*")
            || trimmed.matches("^import\\s+\\w+.*")
            || trimmed.matches("^class\\s+\\w+.*")
            || trimmed.matches("^def\\s+\\w+.*")
            || trimmed.matches("^function\\s+\\w+.*")) {
            log.info("[RAG_PREPROCESS] 检测到代码片段查询: '{}'，跳过向量检索", trimmed);
            return QueryPreprocessResult.invalid("代码片段", "代码片段");
        }

        // ===== Layer 0.5: 过短查询检测（<2个有意义字符）=====
        int meaningfulChars = 0;
        for (char c : trimmed.toCharArray()) {
            if (Character.isLetterOrDigit(c)) meaningfulChars++;
        }
        if (meaningfulChars < 2 && trimmed.length() < 4) {
            log.info("[RAG_PREPROCESS] 检测到过短查询: '{}'，meaningfulChars={}", trimmed, meaningfulChars);
            if (knowledgeBaseId != null && !knowledgeBaseId.trim().isEmpty() && !"default_knowledge_base".equals(knowledgeBaseId)) {
                String expandedQuery = knowledgeBaseId + " " + effectiveQuery;
                log.info("[RAG_PREPROCESS] 短查询扩展: '{}' -> '{}'", effectiveQuery, expandedQuery);
                return new QueryPreprocessResult(expandedQuery, true, null, "short_expanded", "短查询");
            }
            return QueryPreprocessResult.invalid("查询过短", "短查询");
        }

        // ===== Layer 0.6: 移除语气词和标点 =====
        String cleaned = effectiveQuery
            .replaceAll("^[啊呢吧呀哇嘛哦呵呃]+", "")
            .replaceAll("[啊呢吧呀哇嘛哦呵呃。.。]+$", "")
            .trim();

        // 5. 返回有效结果
        if (!cleaned.isEmpty() && cleaned.length() >= 2) {
            return new QueryPreprocessResult(cleaned, true, null, "normal", "normal");
        } else {
            return QueryPreprocessResult.invalid("清理后为空", "空内容");
        }
    }

    /**
     * 获取检索结果中的最高分（L2 距离最小 = 最相关）
     * @param docs 文档列表
     * @return top score（L2 距离，最小最相关）
     */
    private double getTopScore(List<Map<String, Object>> docs) {
        double topScore = Double.MAX_VALUE;
        for (Map<String, Object> doc : docs) {
            try {
                Object scoreObj = doc.get("vector_score");
                if (scoreObj instanceof Number) {
                    double score = ((Number) scoreObj).doubleValue();
                    if (score < topScore) topScore = score;
                }
            } catch (Exception ignored) {}
        }
        return topScore;
    }

    /**
     * 根据检索质量过滤文档
     * @param docs 原始检索结果
     * @param minScoreThreshold L2距离阈值（越小越相关），默认2.5
     * @return 过滤后的文档列表
     */
    private List<Map<String, Object>> filterLowQualityResults(List<Map<String, Object>> docs, double minScoreThreshold) {
        if (docs == null || docs.isEmpty()) return docs;
        List<Map<String, Object>> filtered = new ArrayList<>();
        int total = docs.size();
        int kept = 0;
        for (Map<String, Object> doc : docs) {
            try {
                Object scoreObj = doc.get("vector_score");
                double score = Double.MAX_VALUE;
                if (scoreObj instanceof Number) {
                    score = ((Number) scoreObj).doubleValue();
                } else if (scoreObj != null) {
                    score = Double.parseDouble(scoreObj.toString().trim());
                }
                if (score <= minScoreThreshold) {
                    filtered.add(doc);
                    kept++;
                }
            } catch (Exception e) {
                // 解析失败默认保留
                filtered.add(doc);
                kept++;
            }
        }
        log.info("[RAG_PREPROCESS] 检索质量过滤: 原始={} 保留={} (阈值={})", total, kept, minScoreThreshold);
        return filtered;
    }

    /**
     * 从metadata中提取知识库名称（用于查询扩展）
     */
    private String extractKnowledgeBaseName(String metadataStr) {
        try {
            Map<String, Object> meta = parseMetadata(metadataStr);
            String source = (String) meta.get("source");
            if (source != null && source.contains("/")) {
                String[] parts = source.split("/");
                return parts[0]; // 返回第一个路径段作为KB名称
            }
            return source;
        } catch (Exception e) {
            return null;
        }
    }

    // ========== 诊断管综原则文档是否存在 ==========

    /**
     * 诊断管综原则文档是否存在
     * @param collectionName 集合名称
     */
    public void diagnoseGuanZongDocument(String collectionName) {
        log.info("[RAG] === 开始诊断管综原则文档 ===");
        
        // 1. 检查集合是否存在
        boolean exists = milvusDbService.hasCollection(collectionName);
        log.info("[RAG] 集合存在: {}", exists);
        
        if (exists) {
            // 2. 检查集合中的文档数量
            int count = milvusDbService.getCollectionCount(collectionName);
            log.info("[RAG] 集合文档数量: {}", count);
            
            // 3. 搜索包含"管综原则"的文档
            List<Map<String, Object>> guanzongDocs = milvusDbService.searchDocumentsByKeyword(collectionName, "管综原则");
            log.info("[RAG] 包含'管综原则'的文档数量: {}", guanzongDocs.size());
            
            // 4. 搜索包含"孪数BIM004"的文档
            List<Map<String, Object>> bim004Docs = milvusDbService.searchDocumentsByKeyword(collectionName, "孪数BIM004");
            log.info("[RAG] 包含'孪数BIM004'的文档数量: {}", bim004Docs.size());
            
            // 5. 搜索包含"管综"的文档
            List<Map<String, Object>> guanzongDocs2 = milvusDbService.searchDocumentsByKeyword(collectionName, "管综");
            log.info("[RAG] 包含'管综'的文档数量: {}", guanzongDocs2.size());
            
            // 6. 显示找到的文档详情
            if (!guanzongDocs.isEmpty()) {
                log.info("[RAG] 找到的管综原则文档:");
                for (Map<String, Object> doc : guanzongDocs) {
                    log.info("[RAG] - 文档: {}", doc.get("metadata"));
                    String text = doc.get("text").toString();
                    log.info("[RAG] - 文本预览: {}", text.length() > 200 ? text.substring(0, 200) + "..." : text);
                }
            } else {
                log.warn("[RAG] 未找到包含'管综原则'的文档！");
                
                // 7. 如果没找到，尝试搜索所有文档的metadata
                log.info("[RAG] 尝试搜索所有文档的metadata...");
                List<Map<String, Object>> allDocs = milvusDbService.queryAllDocuments(collectionName);
                int foundInMetadata = 0;
                for (Map<String, Object> doc : allDocs) {
                    String metadata = doc.get("metadata").toString().toLowerCase();
                    if (metadata.contains("管综原则") || metadata.contains("孪数bim004")) {
                        foundInMetadata++;
                        log.info("[RAG] 在metadata中找到: {}", doc.get("metadata"));
                    }
                }
                log.info("[RAG] 在metadata中找到 {} 个相关文档", foundInMetadata);
            }
        }
        
        log.info("[RAG] === 管综原则文档诊断完成 ===");
    }
    
    /**
     * 调试方法：检查搜索问题
     * @param collectionName 集合名称
     * @param query 查询文本
     */
    public void debugSearch(String collectionName, String query) {
        log.info("[RAG] === 开始调试搜索问题 ===");
        log.info("[RAG] 查询: {}", query);
        log.info("[RAG] 集合: {}", collectionName);
        
        // 1. 检查集合是否存在
        boolean exists = milvusDbService.hasCollection(collectionName);
        log.info("[RAG] 集合存在: {}", exists);
        
        if (exists) {
            // 2. 检查集合中的文档数量
            int count = milvusDbService.getCollectionCount(collectionName);
            log.info("[RAG] 集合文档数量: {}", count);
            
            // 3. 搜索包含关键词的文档
            List<Map<String, Object>> keywordDocs = milvusDbService.searchDocumentsByKeyword(collectionName, "管综原则");
            log.info("[RAG] 包含'管综原则'的文档数量: {}", keywordDocs.size());
            
            // 4. 生成embedding并测试搜索
            try {
                List<Double> embedding = generateEmbeddingSync(query);
                log.info("[RAG] Embedding生成成功，维度: {}", embedding.size());
                
                // 5. 执行向量搜索
                List<Map<String, Object>> searchResults = milvusDbService.queryDocuments(collectionName, toFloatList(embedding), 20, query);
                log.info("[RAG] 向量搜索结果数量: {}", searchResults.size());
                
                // 6. 显示搜索结果
                for (int i = 0; i < Math.min(searchResults.size(), 5); i++) {
                    Map<String, Object> doc = searchResults.get(i);
                    log.info("[RAG] 结果{}: score={}, metadata={}", i+1, doc.get("score"), doc.get("metadata"));
                }
                
            } catch (Exception e) {
                log.error("[RAG] 调试过程中出错: {}", e.getMessage());
            }
        }
        
        log.info("[RAG] === 调试完成 ===");
    }
    
    /**
     * 生成 embedding（异步，返回 Mono<List<Double>>）
     * - 内部会对文本进行 nomic-embed-text 特殊预处理（清理控制字符、智能截断、添加前缀）
     * - 若 LLM服务 返回空结果或出错会触发降级：返回一个全零的默认向量（长度 768），以保证后续流程不崩溃
     * - 对网络相关错误进行有限次重试
     */
    public Mono<List<Double>> generateEmbedding(String text) {
        // 预处理文本，确保nomic-embed-text兼容性
        String processedText = preprocessTextForEmbedding(text);
        
        // 移除详细的调试日志
        
        log.info("[BGE-M3] 开始生成embedding，文本长度: {}", processedText.length());
        return llmService.generateEmbedding(processedText)
            .flatMap(embedding -> {
                if (embedding == null || embedding.isEmpty()) {
                    log.warn("[BGE-M3] embedding为空或维度为0，触发降级处理。原始文本片段长度: {}", processedText.length());
                    // 返回一个默认的embedding向量，避免将空向量传入Milvus导致索引越界
                    List<Double> defaultEmbedding = new ArrayList<>();
                    for (int i = 0; i < 768; i++) {
                        defaultEmbedding.add(0.0);
                    }
                    log.warn("[BGE-M3] 使用默认embedding向量，维度: {}", defaultEmbedding.size());
                    return Mono.just(defaultEmbedding);
                } else {
                    log.info("[BGE-M3] embedding生成成功，维度: {}", embedding.size());
                    return Mono.just(embedding);
                }
            })
            .retryWhen(reactor.util.retry.Retry.backoff(2, Duration.ofSeconds(2))
                .filter(e -> {
                    // 只重试网络错误
                    if (e instanceof RuntimeException) {
                        String msg = e.getMessage();
                        return msg != null && (
                            msg.contains("timeout") || 
                            msg.contains("connection")
                        );
                    }
                    return false;
                }))
            // 已移除对 embedding 请求的短超时限制，使用重试与降级策略保证稳定性
            .onErrorResume(e -> {
                log.warn("[BGE-M3] embedding生成失败，使用默认向量 (正常降级): {}", e.getMessage());
                // 返回一个默认的embedding向量，避免完全失败
                List<Double> defaultEmbedding = new ArrayList<>();
                for (int i = 0; i < 768; i++) {
                    defaultEmbedding.add(0.0);
                }
                log.warn("[BGE-M3] 使用默认embedding向量，维度: {}", defaultEmbedding.size());
                return Mono.just(defaultEmbedding);
            });
    }
    
    /**
     * 同步生成embedding，用于调试
     * 注意：此方法不应在响应式上下文中使用
     */
    public List<Double> generateEmbeddingSync(String text) {
        // 使用阻塞调用的安全方式
        try {
            return generateEmbedding(text).block(java.time.Duration.ofSeconds(30));
        } catch (Exception e) {
            log.error("[RAG] 同步生成embedding失败: {}", e.getMessage());
            throw new RuntimeException("生成embedding失败: " + e.getMessage());
        }
    }
    
    private String preprocessTextForEmbedding(String text) { // 为BGE-M3模型预处理文本
        if (text == null || text.trim().isEmpty()) { // 检查文本是否为空
            return "empty text"; // 返回空文本占位符
        }
        
        // 恢复完整的文本清理，保证embedding质量
        String cleaned = text.replaceAll("[\\x00-\\x1F\\x7F]", "")  // 移除控制字符
                            .replaceAll("[\\uFFFD]", "")              // 移除替换字符
                            .replaceAll("\\s+", " ")                  // 合并多个空格
                            .trim(); // 去除前后空格
        
        // 智能截断：优先保留完整句子
        int maxChars = 1024; // 设置最大字符数
        if (cleaned.length() > maxChars) { // 如果超过限制
            log.warn("[向量化] 文本长度超过限制，原长度: {}, 截断到: {}", cleaned.length(), maxChars); // 记录警告
            
            // 尝试在句号处截断
            String truncated = cleaned.substring(0, maxChars); // 截取前maxChars字符
            int lastPeriod = truncated.lastIndexOf('。'); // 查找最后一个句号
            int lastExclamation = truncated.lastIndexOf('！'); // 查找最后一个感叹号
            int lastQuestion = truncated.lastIndexOf('？'); // 查找最后一个问号
            int lastDot = truncated.lastIndexOf('.'); // 查找最后一个点
            
            int bestBreak = Math.max(Math.max(lastPeriod, lastExclamation), Math.max(lastQuestion, lastDot)); // 选择最佳断点
            
            if (bestBreak > maxChars * 0.7) { // 如果断点在前70%范围内
                cleaned = truncated.substring(0, bestBreak + 1); // 在断点处截断
                log.info("[向量化] 在句号处截断，保留长度: {}", cleaned.length()); // 记录截断信息
            } else { // 否则强制截断
                cleaned = truncated; // 使用强制截断
                log.info("[向量化] 强制截断，保留长度: {}", cleaned.length()); // 记录截断信息
            }
        }
        
        // 添加特殊标记，确保nomic-embed-text正确处理
        if (!cleaned.startsWith("Represent this sentence for searching relevant passages:")) { // 如果没有前缀
            cleaned = "Represent this sentence for searching relevant passages: " + cleaned; // 添加前缀
        }
        
        return cleaned.isEmpty() ? "empty text" : cleaned; // 返回处理后的文本
    }
    
    /**
     * 查找存在的集合名称
     * @param rawCollectionName 原始集合名称
     * @return 找到的集合名称，如果没找到则返回default_knowledge_base
     */
    private String findExistingCollection(String tenantId, String rawCollectionName) {
        String effectiveTenantId = (tenantId == null || tenantId.trim().isEmpty()) ? "default" : tenantId.trim();
        String safeRawCollectionName = (rawCollectionName == null || rawCollectionName.trim().isEmpty())
            ? "default_knowledge_base"
            : rawCollectionName;
        // 动态查找所有可能的集合名称
        List<String> possibleCollections = new ArrayList<>();
        possibleCollections.add("default_knowledge_base");

        log.info(red("[KB_TRACE] findExistingCollection: tenantId={} rawKbId='{}' candidates(init)={}"),
            effectiveTenantId,
            safeRawCollectionName,
            possibleCollections);
        
        // 根据原始知识库ID生成可能的集合名称
        if (!safeRawCollectionName.equals("default_knowledge_base")) {
            // 尝试不同的集合名称格式
            possibleCollections.add(safeRawCollectionName); // 原始名称
            possibleCollections.add("kb_" + safeRawCollectionName); // 添加kb_前缀
            possibleCollections.add(sanitizeCollectionName(safeRawCollectionName)); // 清理后的名称
            
            // 如果是中文名称，尝试不同的编码方式
            if (safeRawCollectionName.matches(".*[\\u4e00-\\u9fa5].*")) {
                // 中文名称，尝试不同的下划线替换方式
                String chineseCleaned = safeRawCollectionName.replaceAll("[^a-zA-Z0-9\\u4e00-\\u9fa5]", "_");
                possibleCollections.add("kb_" + chineseCleaned);
                possibleCollections.add(chineseCleaned);
                
                // 尝试拼音转换（简化版）
                if (safeRawCollectionName.contains("湖畔酒店项目")) {
                    possibleCollections.add("kb_hupan_hotel_project");
                    possibleCollections.add("hupan_hotel_project");
                } else if (safeRawCollectionName.contains("建筑设计规范")) {
                    possibleCollections.add("kb_architectural_design_standards");
                    possibleCollections.add("architectural_design_standards");
                    // 建筑设计规范应该使用自己的集合，不再优先使用hupan_hotel_project
                    // possibleCollections.add("hupan_hotel_project");
                    // possibleCollections.add("kb_hupan_hotel_project");
                    // 也尝试kb集合，因为可能所有文档都在这里
                    possibleCollections.add("kb");
                } else if (safeRawCollectionName.contains("BIM导则及标准")) {
                    possibleCollections.add("kb_bim_guidelines_standards");
                    possibleCollections.add("bim_guidelines_standards");
                }
            }
        }
        
        // 添加一些常见的集合名称，优先检查有数据的集合
        // 根据原始知识库名称优先查找对应的集合
        if (safeRawCollectionName.contains("建筑设计规范")) {
            possibleCollections.add("architectural_design_standards");
            possibleCollections.add("kb_architectural_design_standards");
        }
        if (safeRawCollectionName.contains("湖畔酒店项目")) {
            possibleCollections.add("hupan_hotel_project");
            possibleCollections.add("kb_hupan_hotel_project");
        }
        if (safeRawCollectionName.contains("BIM导则及标准")) {
            possibleCollections.add("bim_guidelines_standards");
            possibleCollections.add("kb_bim_guidelines_standards");
        }
        
        possibleCollections.add("kb");
        possibleCollections.add("hupan_hotel_project");
        possibleCollections.add("kb_hupan_hotel_project");
        
        log.info("[RAG] 原始知识库名称: '{}', tenantId={}, 尝试查找的集合列表: {}", safeRawCollectionName, effectiveTenantId, possibleCollections);

        log.info(red("[KB_TRACE] candidate collections to probe (tenant-scoped)={}"), possibleCollections);
        
        // 优先返回有数据的集合：先按新 hash 映射探测，再按旧映射回退探测（用于兼容升级前的集合）
        for (String possibleCollection : possibleCollections) {
            List<String> probes = Arrays.asList(
                TenantUtils.buildTenantCollectionName(effectiveTenantId, possibleCollection),
                TenantUtils.buildTenantCollectionNameLegacy(effectiveTenantId, possibleCollection)
            );
            for (String tenantCollection : probes) {
                if (!milvusDbService.hasCollection(tenantCollection)) {
                    continue;
                }
                try {
                    int count = milvusDbService.getCollectionCount(tenantCollection);
                    if (count > 0) {
                        log.warn(red("[KB_TRACE] selected collection: {} (candidate='{}', count={})"), tenantCollection, possibleCollection, count);
                        log.info("[RAG] 找到有数据的集合: {} (文档数: {})", tenantCollection, count);
                        return tenantCollection;
                    } else {
                        log.info("[RAG] 集合 {} 存在但无数据，继续查找", tenantCollection);
                    }
                } catch (Exception e) {
                    log.warn("[RAG] 检查集合 {} 数据时出错: {}", tenantCollection, e.getMessage());
                }
            }
        }
        
        // 如果所有指定集合都没有数据，列出所有现有集合并查找有数据的
        log.warn("[RAG] 指定的集合都没有数据，列出所有现有集合...");
        try {
            List<String> allCollections = milvusDbService.listCollections();
            log.warn("[RAG] Milvus 中所有集合: {}", allCollections);
            for (String coll : allCollections) {
                // 只检查 admin 租户的集合
                if (!coll.startsWith("t_admin__")) continue;
                try {
                    int count = milvusDbService.getCollectionCount(coll);
                    if (count > 0) {
                        log.warn(red("[KB_TRACE] selected collection from all list: {} (count={})"), coll, count);
                        log.info("[RAG] 从现有集合中找到有数据的: {} (文档数: {})", coll, count);
                        return coll;
                    }
                } catch (Exception e) {
                    log.warn("[RAG] 检查集合 {} 数据时出错: {}", coll, e.getMessage());
                }
            }
        } catch (Exception e) {
            log.warn("[RAG] 列出集合失败: {}", e.getMessage());
        }

        String fallback = TenantUtils.buildTenantCollectionName(effectiveTenantId, "default_knowledge_base");
        log.warn(red("[KB_TRACE] no usable collection found; fallback to default: {}"), fallback);
        log.warn("[RAG] 未找到任何有数据的集合，使用默认集合");
        return fallback;
    }
    
    /**
     * 清理集合名称，确保符合Milvus规范
     * Milvus要求：集合名称必须以字母或下划线开头，只能包含字母、数字、下划线
     */
    public String sanitizeCollectionName(String rawName) {
        String key = TenantUtils.buildKbCollectionKey(rawName);
        log.info("[集合名称] 原始名称: '{}' -> hashKey: '{}'", rawName, key);
        return key;
    }
    
    /**
     * 将中文字符转换为英文标识符
     */
    private String convertChineseToEnglish(String text) { // 将中文字符转换为英文标识符
        if (text == null || text.isEmpty()) { // 检查文本是否为空
            return text; // 返回原文本
        }
        
        // 常见的中文映射
        Map<String, String> chineseToEnglish = new HashMap<>(); // 创建映射表
        chineseToEnglish.put("湖畔酒店项目", "hupan_hotel_project"); // 添加映射
        chineseToEnglish.put("建筑设计规范", "architectural_design_standards"); // 添加映射
        chineseToEnglish.put("BIM导则及标准", "bim_guidelines_standards"); // 添加映射
        chineseToEnglish.put("项目", "project"); // 添加映射
        chineseToEnglish.put("酒店", "hotel"); // 添加映射
        chineseToEnglish.put("建筑", "architecture"); // 添加映射
        chineseToEnglish.put("设计", "design"); // 添加映射
        chineseToEnglish.put("规范", "standards"); // 添加映射
        chineseToEnglish.put("导则", "guidelines"); // 添加映射
        chineseToEnglish.put("标准", "standards"); // 添加映射
        chineseToEnglish.put("BIM", "bim"); // 添加映射
        
        String result = text; // 初始化结果
        for (Map.Entry<String, String> entry : chineseToEnglish.entrySet()) { // 遍历映射表
            result = result.replace(entry.getKey(), entry.getValue()); // 替换中文为英文
        }
        
        // 如果还有中文字符，用拼音或通用标识符替换
        if (result.matches(".*[\\u4e00-\\u9fa5].*")) { // 如果仍包含中文
            // 对于"湖畔酒店项目"，特殊处理
            if (text.contains("湖畔酒店项目")) { // 特殊处理
                return "hupan_hotel_project"; // 返回英文标识符
            }
            // 对于"建筑设计规范"，特殊处理
            if (text.contains("建筑设计规范")) { // 特殊处理
                return "architectural_design_standards"; // 返回英文标识符
            }
            // 对于"BIM导则及标准"，特殊处理
            if (text.contains("BIM导则及标准")) { // 特殊处理
                return "bim_guidelines_standards"; // 返回英文标识符
            }
            // 对于其他中文，用更安全的标识符
            result = result.replaceAll("[\\u4e00-\\u9fa5]+", "kb"); // 替换为kb
        }
        
        return result; // 返回转换后的结果
    }

    private List<Float> toFloatList(List<Double> doubles) { // 将Double列表转换为Float列表
        List<Float> floats = new ArrayList<>(); // 初始化Float列表
        for (Double d : doubles) { // 遍历Double列表
            floats.add(d.floatValue()); // 转换为Float并添加
        }
        return floats; // 返回Float列表
    }

    public Map<String, Object> parseMetadata(String json) { // 解析metadata JSON字符串
        try {
            return objectMapper.readValue(json, Map.class); // 使用ObjectMapper解析
        } catch (Exception e) { // 捕获异常
            return new HashMap<>(); // 返回空映射
        }
    }

    private String toJson(Map<String, Object> map) { // 将映射转换为JSON字符串
        try {
            return objectMapper.writeValueAsString(map); // 使用ObjectMapper序列化
        } catch (Exception e) { // 捕获异常
            return "{}"; // 返回空JSON
        }
    }

    /**
     * 根据文档列表构建sources（与原流程保持一致的格式）
     */
    private List<Map<String, Object>> buildSourcesFromDocs(List<Map<String, Object>> docs) {
        List<Map<String, Object>> sources = new ArrayList<>();
        for (Map<String, Object> doc : docs) {
            try {
                Map<String, Object> src = new HashMap<>();
                // 抽取并截断文档文本
                Object textObj = doc.get("text");
                if (textObj != null) {
                    String fullText = String.valueOf(textObj);
                    int snipLen = 800;
                    src.put("text", fullText.length() > snipLen ? fullText.substring(0, snipLen) + "..." : fullText);
                }
                // 解析metadata
                Object metaObj = doc.get("metadata");
                String metaStr;
                if (metaObj instanceof String) {
                    metaStr = (String) metaObj;
                } else if (metaObj instanceof List && !((List<?>)metaObj).isEmpty()) {
                    metaStr = String.valueOf(((List<?>)metaObj).get(0));
                } else {
                    metaStr = metaObj != null ? metaObj.toString() : "";
                }
                Map<String, Object> meta = parseMetadata(metaStr);
                src.put("documentName", meta.getOrDefault("source", "未知文档"));
                src.put("page", meta.getOrDefault("page", 1));
                src.put("section", meta.getOrDefault("section", ""));
                src.put("sectionPath", meta.getOrDefault("sectionPath", ""));
                // RRF 综合分数（用于排序和显示）
                try {
                    Object scoreObj = doc.get("score");
                    if (scoreObj instanceof Number) {
                        src.put("score", ((Number) scoreObj).doubleValue());
                    } else if (scoreObj != null) {
                        String scoreStr = scoreObj.toString().trim();
                        if (!scoreStr.isEmpty()) {
                            src.put("score", Double.parseDouble(scoreStr));
                        }
                    }
                } catch (Exception ignore) {}
                // 向量搜索 L2 距离（越小越相关）
                try {
                    Object vecScoreObj = doc.get("vector_score");
                    if (vecScoreObj instanceof Number) {
                        src.put("vectorScore", ((Number) vecScoreObj).doubleValue());
                    } else if (vecScoreObj != null) {
                        src.put("vectorScore", Double.parseDouble(vecScoreObj.toString().trim()));
                    }
                } catch (Exception ignore) {}
                // 各维度排名
                try {
                    Object vecRankObj = doc.get("vector_rank");
                    if (vecRankObj instanceof Number) {
                        src.put("vectorRank", ((Number) vecRankObj).intValue());
                    }
                } catch (Exception ignore) {}
                try {
                    Object bm25RankObj = doc.get("bm25_rank");
                    if (bm25RankObj instanceof Number) {
                        src.put("bm25Rank", ((Number) bm25RankObj).intValue());
                    }
                } catch (Exception ignore) {}
                try {
                    Object rrfRankObj = doc.get("rrf_rank");
                    if (rrfRankObj instanceof Number) {
                        src.put("rank", ((Number) rrfRankObj).intValue());
                    }
                } catch (Exception ignore) {}
                // documentId
                try {
                    Object idObj = doc.get("id");
                    if (idObj != null) src.put("documentId", String.valueOf(idObj));
                } catch (Exception ignore) {}
                sources.add(src);
            } catch (Exception e) {
                log.error("[RAG] Error building source from doc: {}", e.getMessage());
            }
        }
        log.info("[Milvus] sources assembled: {}", sources.size());
        return sources;
    }

    /**
     * 根据sources列表构建context字符串
     */
    private String buildContextFromSources(List<Map<String, Object>> sources, int maxLen) {
        String context = sources.stream()
            .map(s -> {
                String sectionPath = String.valueOf(s.getOrDefault("sectionPath", ""));
                String text = String.valueOf(s.getOrDefault("text", ""));
                String documentName = String.valueOf(s.getOrDefault("documentName", ""));
                Object pageObj = s.get("page");
                String page = pageObj != null ? String.valueOf(pageObj) : "";
                StringBuilder sb = new StringBuilder();
                sb.append("- 【来源: ").append(documentName);
                if (!page.isEmpty() && !"null".equals(page)) {
                    sb.append(" 第").append(page).append("页");
                }
                if (!sectionPath.isEmpty() && !"null".equals(sectionPath)) {
                    sb.append(" / ").append(sectionPath);
                }
                sb.append("】\n").append(text);
                return sb.toString();
            })
            .collect(Collectors.joining("\n"));
        if (context.length() > maxLen) {
            context = context.substring(0, maxLen) + "\n... (truncated)";
            log.warn("[RAG] 上下文被截断，原始长度超过 {} 字符", maxLen);
        }
        return context;
    }

    /**
     * 构建 RAG Prompt（重构版，带检索置信度提示）
     * @param question 当前问题
     * @param context 检索到的上下文（可为空）
     * @param hasRelevantDocs 是否有相关文档
     * @param sourceCount 相关文档片段数
     * @param history 对话历史（可为空）
     * @param queryType 查询类型（用于 Layer 3 无匹配场景分类）
     * @param effectiveQuery 经过预处理的有效查询文本
     */
    private String buildRagPrompt(String question, String context, boolean hasRelevantDocs, int sourceCount,
            java.util.List<ChatRequest.ChatMessage> history, String queryType, String effectiveQuery) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("你是一个专业的AI助手。请严格按照以下要求回答问题：\n\n");

        if (hasRelevantDocs && context != null && !context.trim().isEmpty()) {
            prompt.append("=== 检索到的相关文档内容（置信度：").append(sourceCount).append(" 个相关片段）===\n");
            prompt.append(context).append("\n");
            prompt.append("=== 文档内容结束 ===\n\n");
            prompt.append("重要指示：上述文档内容与用户问题相关。请基于文档内容给出准确、详细的回答。");
            prompt.append("如果文档内容包含具体数据、条款或规范，请引用具体内容。\n\n");
        } else {
            // ===== Layer 3: 分级无匹配回复 =====
            String tieredInstruction = buildTieredPromptInstruction(queryType, effectiveQuery);
            prompt.append(tieredInstruction).append("\n\n");
        }

        if (history != null && !history.isEmpty()) {
            prompt.append("对话历史：\n");
            for (ChatRequest.ChatMessage msg : history) {
                prompt.append(msg.isUser() ? "用户: " : "助手: ").append(msg.getContent()).append("\n");
            }
            prompt.append("\n");
        }

        prompt.append("当前问题：").append(question).append("\n\n");
        prompt.append("回答要求：请直接输出最终回答，不要输出思考过程或 <think> 标签。\n");
        prompt.append("- 准确、专业、尽量简洁（优先一段话给出结论；必要时再补充要点）。\n");
        prompt.append("- 引用格式：当引用文档内容时，请标注具体出处，格式为「出自《文档名》第X页 / 章节名称」。\n");
        prompt.append("- 例如：「根据《建筑抗震设计规范》第5页 / 3.1 抗震设防分类，建筑抗震设防类别分为...」\n");
        prompt.append("- 即使没有相关文档，也要基于专业知识给出有用回答。\n");
        return prompt.toString();
    }

    /**
     * Layer 3：构建分级无匹配回复指令
     * 根据 queryType（无效查询分类）区分不同场景，给 LLM 不同的回复策略。
     */
    private String buildTieredPromptInstruction(String queryType, String effectiveQuery) {
        StringBuilder sb = new StringBuilder();
        sb.append("注意：知识库中没有检索到与用户问题直接相关的内容。\n");
        sb.append("请基于你的专业知识回答，并明确告知用户知识库中暂无相关内容。\n\n");
        sb.append("【回复策略】知识库中没有找到与您问题直接相关的内容。\n");
        sb.append("请基于你的专业知识给出专业回答，并告知用户：\n");
        sb.append("1. 这个问题可能涉及较新的技术或尚未入库的内容；\n");
        sb.append("2. 建议您提供更多背景信息，或换个角度描述问题；\n");
        sb.append("3. 如需查询特定内容，请提供更具体的关键词或文档名称。\n");
        return sb.toString();
    }

    /**
     * 构建 RAG Prompt（兼容旧调用，自动判断 Layer 3 场景）
     */
    private String buildRagPrompt(String question, String context, boolean hasRelevantDocs, int sourceCount,
            java.util.List<ChatRequest.ChatMessage> history) {
        // 对于兼容调用，默认为"正常无匹配"类型
        return buildRagPrompt(question, context, hasRelevantDocs, sourceCount, history, "normal", question);
    }

    /**
     * Layer 3：为无效查询构建分级 prompt（停用词/纯标点/代码/短查询等不同场景）
     */
    private String buildInvalidQueryPrompt(String rawQuery, String invalidCategory, String tenantId) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("你是一个专业的AI助手。\n\n");

        switch (invalidCategory) {
            case "停用词查询":
            case "闲聊":
            case "CHITCHAT":
                // 闲聊类 → 友好引导到专业领域
                prompt.append("用户刚才发送的是问候语或闲聊内容（\"").append(rawQuery != null ? rawQuery : "").append("\"）。\n");
                prompt.append("请用简洁友好的方式欢迎用户，并引导其进入建筑行业专业领域。\n");
                prompt.append("回复示例：\"您好！我是您的建筑行业AI助手，可以帮您查询规范标准、BIM技术、工程管理等方面的问题。请问有什么可以帮您的？\"\n");
                break;

            case "纯标点查询":
            case "纯数字/代码片段":
            case "代码片段":
                // 纯符号/代码 → 友好告知无效
                prompt.append("用户发送了纯标点或代码片段（\"").append(rawQuery != null ? rawQuery : "").append("\"）。\n");
                prompt.append("请友好地告知用户这不是有效的问题，并引导其提出建筑相关的问题。\n");
                prompt.append("回复示例：\"您输入的内容无法理解。建议您用自然语言提问，例如：'《建筑抗震设计规范》GB 50011 的抗震设防标准是什么？'\"\n");
                break;

            case "短查询":
            case "查询过短":
                // 过短/模糊 → 追问引导
                prompt.append("用户输入过于简短（\"").append(rawQuery != null ? rawQuery : "").append("\"），无法准确理解意图。\n");
                prompt.append("请友好地追问，引导用户提供更具体的问题。\n");
                prompt.append("回复示例：\"您的问题比较简短，我需要更多信息才能准确回答。请具体说明您想了解的内容，\n");
                prompt.append("例如：'《建筑抗震设计规范》GB 50011 的抗震设防类别有哪些？'\"\n");
                break;

            case "知识库自指查询":
            case "KB_SELF":
                // 知识库自指查询 → 引导用户查看知识库内容
                prompt.append("用户询问了关于本知识库内容的问题（\"").append(rawQuery != null ? rawQuery : "").append("\"）。\n");
                prompt.append("请这样回复用户：\n");
                prompt.append("\"本知识库目前已收录了多种建筑规范标准、BIM技术资料、项目管理文档等资料，涵盖建筑、结构、机电、施工、造价等多个专业领域。\n");
                prompt.append("具体内容取决于知识库中实际上传的文档。建议您直接提出想了解的具体问题，比如：'《建筑抗震设计规范》GB 50011 的主要内容是什么？'\n");
                prompt.append("我会根据知识库中的实际内容为您检索和解答。您也可以在知识库管理页面查看已上传的文档列表。\"\n");
                break;

            case "对话元查询":
            case "CONV_HISTORY":
                // 对话元查询 → 查询 ChatSessionService 获取实际对话历史并如实回答
                prompt.append("用户询问了关于本次对话历史的问题（\"").append(rawQuery != null ? rawQuery : "").append("\"）。\n");
                try {
                    String effectiveTid = (tenantId == null || tenantId.trim().isEmpty()) ? "default" : tenantId.trim();
                    List<ChatRequest.ChatMessage> history = chatSessionService.getHistory(effectiveTid);

                    // 分离用户消息和AI消息（最后一条用户消息可能还没有配对的AI回复）
                    List<ChatRequest.ChatMessage> userMessages = history.stream()
                        .filter(ChatRequest.ChatMessage::isUser).collect(java.util.stream.Collectors.toList());
                    List<ChatRequest.ChatMessage> aiMessages = history.stream()
                        .filter(m -> !m.isUser()).collect(java.util.stream.Collectors.toList());

                    // 已完成的对话轮数 = AI回复数（每轮以AI回复结束为准）
                    int completedTurns = aiMessages.size();
                    // 用户提问次数（含当前未配对的）
                    int userMsgCount = userMessages.size();

                    prompt.append("请根据以下实际数据如实回答用户，不要编造或猜测：\n");
                    prompt.append("- 本次会话用户已提问 ").append(userMsgCount).append(" 次\n");
                    prompt.append("- AI 已回复 ").append(aiMessages.size()).append(" 次\n");
                    prompt.append("- 已完成的对话轮数：").append(completedTurns).append(" 轮\n\n");

                    // 列出所有用户提问（按顺序编号），这对"第一次问了什么"等问题至关重要
                    if (!userMessages.isEmpty()) {
                        prompt.append("用户提问记录（按时间顺序）：\n");
                        for (int i = 0; i < userMessages.size(); i++) {
                            String content = userMessages.get(i).getContent();
                            if (content != null && content.length() > 100) content = content.substring(0, 100) + "...";
                            prompt.append("  第").append(i + 1).append("次提问: ").append(content).append("\n");
                        }
                        prompt.append("\n");
                    }

                    // 列出最近的AI回复摘要
                    if (!aiMessages.isEmpty()) {
                        prompt.append("最近AI回复摘要：\n");
                        int start = Math.max(0, aiMessages.size() - 3);
                        for (int i = start; i < aiMessages.size(); i++) {
                            String content = aiMessages.get(i).getContent();
                            if (content != null && content.length() > 80) content = content.substring(0, 80) + "...";
                            prompt.append("  第").append(i + 1).append("次回复: ").append(content).append("\n");
                        }
                        prompt.append("\n");
                    }

                    prompt.append("请用自然语言如实回答，严格依据上面的数据，不要编造不存在的提问记录。\n");
                } catch (Exception e) {
                    prompt.append("抱歉，暂时无法获取本次对话的详细记录。请直接提出您想了解的问题，我会尽力帮您解答。\n");
                }
                break;

            case "系统/元级查询":
            case "SYSTEM_QUERY":
                // 系统/元级查询 → 礼貌说明能力边界
                prompt.append("用户询问了关于本系统自身的问题（\"").append(rawQuery != null ? rawQuery : "").append("\"）。\n");
                prompt.append("请这样回复用户：\n");
                prompt.append("您好！我是您的AI助手，基于RAG技术结合知识库为您提供准确的回答。请问有什么可以帮您？\n");
                break;

            case "超出领域":
            case "OUT_OF_DOMAIN":
                // 非建筑领域查询
                prompt.append("用户的问题（\"").append(rawQuery != null ? rawQuery : "").append("\"）超出了当前知识库的范围。\n");
                prompt.append("请礼貌地说明本助手的能力范围。\n");
                prompt.append("回复示例：\"抱歉，知识库中暂未收录您问题涉及的内容。\n");
                prompt.append("请您尝试换一个问题，或提供更具体的关键词，我会尽力帮助您。\"\n");
                break;

            default:
                // 通用无效 → 标准引导
                prompt.append("用户刚才输入了「").append(rawQuery != null ? rawQuery : "").append("」，这并不是一个有效的问题。\n");
                prompt.append("请礼貌地回复用户：请提出一个具体的问题，我会尽力帮您解答。\n");
                prompt.append("请用简洁友好的方式引导用户提出有意义的问题，不要过度解释。\n");
        }

        prompt.append("\n回答要求：直接输出回复内容，不要输出思考过程或 <think> 标签。");
        return prompt.toString();
    }

    /**
     * 处理无效查询：直接让模型回复"请提出有效问题"
     */
    private Flux<String> buildAndStreamResponse(String prompt, List<Map<String, Object>> sources, String collectionName) {
        // 组装sources JSON（空sources也要发，告诉前端清空之前的引用）
        Map<String, Object> sourcesData = new HashMap<>();
        sourcesData.put("type", "sources");
        sourcesData.put("sources", sources);
        String sourcesJson;
        try {
            sourcesJson = objectMapper.writeValueAsString(sourcesData);
        } catch (Exception e) {
            sourcesJson = "{\"type\":\"sources\",\"sources\":[]}";
        }
        String sseSources = "data: " + sourcesJson + "\n\n";

        // 构建请求
        Map<String, Object> chatPayload = new HashMap<>();
        chatPayload.put("model", llmService.getChatModel());
        chatPayload.put("messages", java.util.List.of(java.util.Map.of("role", "user", "content", prompt)));
        chatPayload.put("stream", true);
        Map<String, Object> chatOptions = new HashMap<>();
        chatOptions.put("num_predict", 512);
        chatOptions.put("num_ctx", 2048);
        chatOptions.put("temperature", 0.7);
        chatPayload.put("options", chatOptions);

        Flux<String> aiStream = llmService.generateCompletionStream(chatPayload)
            .filter(chunk -> chunk != null && !chunk.trim().isEmpty() && !chunk.trim().equals("[DONE]"))
            .flatMap(chunk -> {
                try {
                    Map<String, Object> ollamaResponse = objectMapper.readValue(chunk, Map.class);
                    String content = null;
                    try {
                        Object choicesObj = ollamaResponse.get("choices");
                        if (choicesObj instanceof List && !((List<?>) choicesObj).isEmpty()) {
                            Object choiceObj = ((List<?>) choicesObj).get(0);
                            if (choiceObj instanceof Map) {
                                Map<?, ?> choice = (Map<?, ?>) choiceObj;
                                Object deltaObj = choice.get("delta");
                                if (deltaObj instanceof Map) {
                                    Map<?, ?> delta = (Map<?, ?>) deltaObj;
                                    Object c = delta.get("content");
                                    if (c != null && String.valueOf(c).trim().length() > 0) {
                                        content = String.valueOf(c);
                                    }
                                }
                            }
                        }
                    } catch (Exception ignored) {}
                    if (content == null) {
                        Object resp = ollamaResponse.get("response");
                        if (resp != null && String.valueOf(resp).trim().length() > 0) {
                            content = String.valueOf(resp);
                        }
                    }
                    if (content != null && !content.isEmpty()) {
                        Map<String, Object> response = new HashMap<>();
                        response.put("type", "answer");
                        response.put("content", content);
                        return Flux.just(objectMapper.writeValueAsString(response));
                    }
                    return Flux.empty();
                } catch (Exception e) {
                    log.error("Failed to parse LLM response chunk: {}", e.getMessage());
                    return Flux.empty();
                }
            })
            .map(json -> "data: " + json + "\n\n");

        // done 信号
        Map<String, Object> doneData = new HashMap<>();
        doneData.put("type", "done");
        doneData.put("content", "");
        String doneJson;
        try {
            doneJson = objectMapper.writeValueAsString(doneData);
        } catch (Exception e) {
            doneJson = "{\"type\":\"done\",\"content\":\"\"}";
        }
        String sseDone = "data: " + doneJson + "\n\n";

        return Flux.concat(
            Flux.just(sseSources),
            aiStream.mergeWith(Flux.just(sseDone))
        );
    }

    /**
     * 处理有效查询：检索 → 流式生成
     */
    private Flux<String> streamSseResponse(String prompt, List<Map<String, Object>> sources) {
        // 组装 sources JSON
        Map<String, Object> sourcesData = new HashMap<>();
        sourcesData.put("type", "sources");
        sourcesData.put("sources", sources);
        String sourcesJson;
        try {
            sourcesJson = objectMapper.writeValueAsString(sourcesData);
        } catch (Exception e) {
            sourcesJson = "{\"type\":\"sources\",\"sources\":[]}";
        }
        String sseSources = "data: " + sourcesJson + "\n\n";

        // loading 心跳
        Flux<String> loadingHeartbeat = Flux.interval(Duration.ofSeconds(5))
            .map(t -> {
                try {
                    Map<String, Object> loadMsg = new HashMap<>();
                    loadMsg.put("type", "loading");
                    loadMsg.put("content", "模型加载中，请稍候...");
                    return objectMapper.writeValueAsString(loadMsg);
                } catch (Exception e) {
                    return "{\"type\":\"loading\",\"content\":\"模型加载中，请稍候...\"}";
                }
            })
            .map(json -> "data: " + json + "\n\n");

        // 构建请求
        Map<String, Object> chatPayload = new HashMap<>();
        chatPayload.put("model", llmService.getChatModel());
        chatPayload.put("messages", java.util.List.of(java.util.Map.of("role", "user", "content", prompt)));
        chatPayload.put("stream", true);
        Map<String, Object> chatOptions = new HashMap<>();
        chatOptions.put("num_predict", 2048);
        chatOptions.put("num_ctx", 2048);
        chatOptions.put("temperature", 0.6);
        chatOptions.put("top_p", 0.7);
        chatOptions.put("repeat_penalty", 1.05);
        chatOptions.put("num_thread", 6);
        chatOptions.put("top_k", 20);
        chatPayload.put("options", chatOptions);

        Flux<String> aiResponseStream = llmService.generateCompletionStream(chatPayload)
            .filter(chunk -> chunk != null && !chunk.trim().isEmpty() && !chunk.trim().equals("[DONE]"))
            .doOnNext(chunk -> log.info("[RAG_chunk] raw chunk ({}): {}", chunk.length(), chunk.length() > 200 ? chunk.substring(0, 200) + "..." : chunk))
            .flatMap(chunk -> {
                try {
                    Map<String, Object> ollamaResponse = objectMapper.readValue(chunk, Map.class);
                    String content = null;
                    try {
                        Object choicesObj = ollamaResponse.get("choices");
                        if (choicesObj instanceof List && !((List<?>) choicesObj).isEmpty()) {
                            Object choiceObj = ((List<?>) choicesObj).get(0);
                            if (choiceObj instanceof Map) {
                                Map<?, ?> choice = (Map<?, ?>) choiceObj;
                                Object deltaObj = choice.get("delta");
                                if (deltaObj instanceof Map) {
                                    Map<?, ?> delta = (Map<?, ?>) deltaObj;
                                    Object c = delta.get("content");
                                    if (c != null && String.valueOf(c).trim().length() > 0) {
                                        content = String.valueOf(c);
                                    }
                                }
                            }
                        }
                    } catch (Exception ignored) {}
                    if (content == null) {
                        Object resp = ollamaResponse.get("response");
                        if (resp != null && String.valueOf(resp).trim().length() > 0) {
                            content = String.valueOf(resp);
                        }
                    }
                    if (content != null && !content.isEmpty()) {
                        Map<String, Object> response = new HashMap<>();
                        response.put("type", "answer");
                        response.put("content", content);
                        return Flux.just(objectMapper.writeValueAsString(response));
                    }
                    return Flux.empty();
                } catch (Exception e) {
                    log.error("Failed to parse LLM response chunk: {}", e.getMessage());
                    return Flux.empty();
                }
            })
            .doOnComplete(() -> log.info("[RAG] AI回答流处理完成"))
            .doOnError(e -> log.error("[RAG] AI回答流处理错误: {}", e.getMessage()));

        // done 信号
        String doneJson;
        try {
            Map<String, Object> doneData = new HashMap<>();
            doneData.put("type", "done");
            doneData.put("content", "");
            doneJson = objectMapper.writeValueAsString(doneData);
        } catch (Exception e) {
            doneJson = "{\"type\":\"done\",\"content\":\"\"}";
        }
        String sseDone = "data: " + doneJson + "\n\n";

        Flux<String> sseAiStream = aiResponseStream
            .map(json -> "data: " + json + "\n\n")
            .onErrorResume(e -> {
                log.error("[RAG] AI stream map error: {}", e.getMessage());
                try {
                    Map<String, Object> errorData = new HashMap<>();
                    errorData.put("type", "error");
                    errorData.put("content", "AI stream error: " + e.getMessage());
                    return Flux.just("data: " + objectMapper.writeValueAsString(errorData) + "\n\n");
                } catch (Exception ex) {
                    return Flux.just("data: {\"type\":\"error\",\"content\":\"未知错误\"}\n\n");
                }
            });

        Flux<String> mergedAfterSources = sseAiStream.publish(shared ->
            Flux.merge(
                loadingHeartbeat.takeUntilOther(shared.take(1)),
                shared.concatWithValues(sseDone)
            )
        );

        return Flux.concat(Flux.just(sseSources), mergedAfterSources);
    }

    /**
     * 构建 LLM 提示词（prompt）
     * - 将检索到的 context 插入到 prompt 中并明确要求基于文档回答
     * - 将会将对话历史（若存在）以用户/助手形式追加到 prompt 中
     * - 返回最终的字符串供 LLM 使用
     */
    private String buildPrompt(String question, String context, java.util.List<ChatRequest.ChatMessage> history) { // 构建LLM提示词的方法
        StringBuilder prompt = new StringBuilder(); // 创建字符串构建器
        prompt.append("你是一个专业的AI助手。请严格按照以下要求回答问题：\n\n"); // 添加角色定义
        
        if (context != null && !context.trim().isEmpty()) { // 如果有上下文
            prompt.append("=== 检索到的相关文档内容 ===\n"); // 添加文档内容标记
            prompt.append(context).append("\n"); // 添加上下文内容
            prompt.append("=== 文档内容结束 ===\n\n"); // 添加结束标记
            prompt.append("重要提示：你必须基于上述检索到的文档内容来回答问题。如果文档内容与问题相关，请详细引用文档中的具体信息。如果文档内容与问题不相关，请明确说明并基于你的专业知识回答。\n\n"); // 添加重要提示
        } else { // 如果没有上下文
            prompt.append("注意：没有检索到相关的文档内容，请基于你的专业知识回答。\n\n"); // 添加无文档提示
        }
        
        if (history != null && !history.isEmpty()) { // 如果有对话历史
            prompt.append("对话历史：\n"); // 添加历史标记
            for (ChatRequest.ChatMessage msg : history) { // 遍历历史消息
                prompt.append(msg.isUser() ? "用户: " : "助手: ").append(msg.getContent()).append("\n"); // 添加格式化的历史消息
            }
            prompt.append("\n"); // 添加换行
        }
        
        prompt.append("当前问题：").append(question).append("\n\n"); // 添加当前问题
        prompt.append("回答要求：请直接输出最终回答，不要输出思考过程或 <think> 标签。\n");
        prompt.append("- 准确、专业、尽量简洁（优先一段话给出结论；必要时再补充要点）。\n");
        prompt.append("- 引用格式：当引用文档内容时，请标注具体出处，格式为「出自《文档名》第X页 / 章节名称」。\n");
        prompt.append("- 例如：「根据《建筑抗震设计规范》第5页 / 3.1 抗震设防分类，建筑抗震设防类别分为...」\n");
        prompt.append("- 即使没有相关文档，也要基于专业知识给出有用回答。\n");
        return prompt.toString(); // 返回构建的提示词字符串
    }

    /**
     * 带页码信息的文档片段
     */
    private static class ChunkWithPage {
        final String text;
        final int page; // 1-based
        ChunkWithPage(String text, int page) { this.text = text; this.page = page; }
    }

    /**
     * 带完整元数据的文档片段（包含章节信息）
     */
    private static class ChunkWithMetadata {
        final String text;
        final int page;
        final String section;
        final String sectionPath;
        ChunkWithMetadata(String text, int page, String section, String sectionPath) {
            this.text = text;
            this.page = page;
            this.section = section != null ? section : "";
            this.sectionPath = sectionPath != null ? sectionPath : "";
        }
    }

    /**
     * 将文档内容分割为若干片段，保留页码和章节信息
     */
    private List<ChunkWithMetadata> splitDocumentIntoChunksWithMetadata(String content, int chunkSize) {
        List<ChunkWithMetadata> result = new ArrayList<>();
        java.util.regex.Pattern pagePattern = java.util.regex.Pattern.compile("\\[PAGE:\\s*(\\d+)\\]");
        List<String[]> sectionStack = new ArrayList<>();
        String[] pageBlocks = content.split("\\[PAGE:");
        int currentPage = 1;
        StringBuilder combined = new StringBuilder();
        for (int i = 0; i < pageBlocks.length; i++) {
            if (i == 0) {
                combined.append(pageBlocks[i]);
            } else {
                String block = pageBlocks[i];
                java.util.regex.Matcher m = pagePattern.matcher("[PAGE:" + block.substring(0, Math.min(10, block.length())));
                int blockPage = 1;
                if (m.find()) blockPage = Integer.parseInt(m.group(1));
                if (combined.length() > 0) {
                    result.addAll(splitByParagraphsWithMetadata(combined.toString(), currentPage, chunkSize, sectionStack));
                    combined = new StringBuilder();
                }
                currentPage = blockPage;
                combined.append(block);
            }
        }
        if (combined.length() > 0) {
            result.addAll(splitByParagraphsWithMetadata(combined.toString(), currentPage, chunkSize, sectionStack));
        }
        // 智能截断：每个超长 chunk 都在语义边界处优雅断开，而非硬切断
        return result.stream().map(cwm -> smartTruncate(cwm, 1500)).collect(java.util.stream.Collectors.toList());
    }

    /**
     * 动态智能截断：当 chunk 超长时，在语义边界处优雅断开，而非硬切断在任意字符处
     * 断点优先级：句末 > 逗号/顿号 > 换行 > 表格行 > 短语边界 > 强制截断
     */
    private ChunkWithMetadata smartTruncate(ChunkWithMetadata cwm, int maxLen) {
        if (cwm.text.length() <= maxLen) return cwm;

        // 1. 正向优先：找 maxLen~maxLen*0.4 区间内的最佳断点（避免断在开头）
        int startWindow = (int)(maxLen * 0.5);   // 至少保留前50%
        int endWindow = Math.min(cwm.text.length(), maxLen + 200);
        int bestPos = -1;
        int bestPriority = -1;

        for (int pos = startWindow; pos < endWindow; pos++) {
            int priority = getBreakPriority(cwm.text, pos);
            if (priority > bestPriority) {
                bestPriority = priority;
                bestPos = pos;
            }
        }

        String result;
        if (bestPos > 0 && bestPriority >= 0) {
            result = cwm.text.substring(0, bestPos + 1);
            if (bestPos < cwm.text.length() - 1) result = result + "…（截）";
        } else {
            // 2. 回退：从 maxLen 向前找最近的有效断点
            result = cwm.text.substring(0, maxLen);
            int lastBreak = findLastBreak(result);
            if (lastBreak > maxLen * 0.7) {
                result = result.substring(0, lastBreak + 1) + "…（截）";
            } else {
                result = result + "…（截）";
            }
        }
        return new ChunkWithMetadata(result, cwm.page, cwm.section, cwm.sectionPath);
    }

    /** 评估指定位置作为断点的语义价值，返回优先级（越高越好，-1=不可断） */
    private int getBreakPriority(String text, int pos) {
        if (pos <= 0 || pos >= text.length()) return -1;
        char curr = text.charAt(pos - 1);
        char next = text.charAt(pos);

        // 句末标点（最高优先级）
        if (curr == '。' || curr == '！' || curr == '？') return 5;
        // 省略号本身不允许断开
        if (curr == '…' || curr == '…') return -1;
        // 中英文句号
        if (curr == '.' && (next == ' ' || next == '\n' || next == '）' || next == ')')) return 4;
        // 中文逗号/顿号（次高优先级，但不如句末）
        if (curr == '，' || curr == '、' || curr == ';') return 3;
        // 英文逗号/分号
        if (curr == ',' || curr == ';') return 2;
        // 换行边界（保留列表格式）
        if (curr == '\n' && (next != ' ' && next != '\n')) return 3;
        // markdown 表格行分隔
        if (curr == '|' && next != '|') return 2;
        // 括号匹配处（断开后括号闭合）
        if (curr == '）' || curr == ')' || curr == '」' || curr == '】') {
            // 检查前方是否有对应开括号（说明括号内容结束）
            if (text.substring(0, pos).matches(".*[（(【[「].*")) return 2;
        }
        return -1; // 其他位置不优先
    }

    /** 从文本末尾向前找最近的有效断点（回退策略） */
    private int findLastBreak(String text) {
        int best = -1;
        for (int i = text.length() - 1; i >= 0; i--) {
            char ch = text.charAt(i);
            // 句末优先
            if (ch == '。' || ch == '！' || ch == '？' || ch == '.' || ch == '!') return i;
            // 逗号/顿号/全角分号作为备选
            if (ch == '，' || ch == '、' || ch == '；') best = i;
        }
        return best;
    }

    private List<ChunkWithMetadata> splitByParagraphsWithMetadata(String content, int page, int chunkSize, List<String[]> sectionStack) {
        List<ChunkWithMetadata> chunks = new ArrayList<>();
        java.util.regex.Pattern headingPattern = java.util.regex.Pattern.compile("^(#{1,6})\\s+(.+)$");
        java.util.regex.Pattern pagePattern = java.util.regex.Pattern.compile("\\[PAGE:\\s*(\\d+)\\]");
        String[] paragraphs = content.split("\n\n");
        StringBuilder currentChunk = new StringBuilder();
        int currentPage = page;
        String currentSection = "";
        String currentSectionPath = "";
        for (String paragraph : paragraphs) {
            for (String line : paragraph.split("\n")) {
                java.util.regex.Matcher hm = headingPattern.matcher(line.trim());
                if (hm.find()) {
                    int level = hm.group(1).length();
                    String title = hm.group(2).trim();
                    while (!sectionStack.isEmpty() && Integer.parseInt(sectionStack.get(sectionStack.size() - 1)[0]) >= level) {
                        sectionStack.remove(sectionStack.size() - 1);
                    }
                    sectionStack.add(new String[]{String.valueOf(level), title});
                    currentSection = title;
                    currentSectionPath = buildSectionPath(sectionStack);
                }
            }
            String cleanParagraph = paragraph.replaceAll("\\[PAGE:\\s*\\d+\\]", "")
                                           .replaceAll("[\\x00-\\x1F\\x7F]", "")
                                           .trim();
            if (cleanParagraph.isEmpty()) continue;
            java.util.regex.Matcher pm = pagePattern.matcher(paragraph);
            if (pm.find()) currentPage = Integer.parseInt(pm.group(1));
            String chunkSection = currentSection;
            String chunkSectionPath = currentSectionPath;
            if (cleanParagraph.length() > chunkSize) {
                if (currentChunk.length() > 0) {
                    String chunk = currentChunk.toString().trim();
                    if (!chunk.isEmpty()) chunks.add(new ChunkWithMetadata(chunk, currentPage, chunkSection, chunkSectionPath));
                    currentChunk = new StringBuilder();
                }
                for (String sentence : cleanParagraph.split("[。！？.!?]")) {
                    sentence = sentence.trim();
                    if (sentence.isEmpty()) continue;
                    // 【修复瑕疵1】单个句子本身就超过 chunkSize，按字符级滑动切分
                    if (sentence.length() > chunkSize) {
                        int pos = 0;
                        while (pos < sentence.length()) {
                            int end = Math.min(pos + chunkSize, sentence.length());
                            String sub = sentence.substring(pos, end);
                            // 非最后一片时追加省略号，提示语义被截断
                            if (end < sentence.length()) {
                                sub = sub + "…";
                            }
                            if (!sub.isEmpty()) chunks.add(new ChunkWithMetadata(sub, currentPage, chunkSection, chunkSectionPath));
                            pos = end;
                        }
                    } else if (currentChunk.length() + sentence.length() > chunkSize) {
                        String chunk = currentChunk.toString().trim();
                        if (!chunk.isEmpty()) chunks.add(new ChunkWithMetadata(chunk, currentPage, chunkSection, chunkSectionPath));
                        currentChunk = new StringBuilder();
                        currentChunk.append(sentence).append("。");
                    } else {
                        currentChunk.append(sentence).append("。");
                    }
                }
            } else {
                if (currentChunk.length() + cleanParagraph.length() > chunkSize) {
                    String chunk = currentChunk.toString().trim();
                    if (!chunk.isEmpty()) chunks.add(new ChunkWithMetadata(chunk, currentPage, chunkSection, chunkSectionPath));
                    currentChunk = new StringBuilder();
                }
                currentChunk.append(cleanParagraph).append("\n\n");
            }
        }
        if (currentChunk.length() > 0) {
            String chunk = currentChunk.toString().trim();
            if (!chunk.isEmpty()) chunks.add(new ChunkWithMetadata(chunk, currentPage, currentSection, currentSectionPath));
        }
        return chunks;
    }

    private String buildSectionPath(List<String[]> sectionStack) {
        if (sectionStack.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < sectionStack.size(); i++) {
            if (i > 0) sb.append(" > ");
            sb.append(sectionStack.get(i)[1]);
        }
        return sb.toString();
    }

    /**
     * 将文档内容分割为若干片段（chunks）以便存储与检索
     * - 优先按段落分割，对于过长的段落会按句子进一步分割
     * - 每个片段会在最后进行长度检查并强制截断到 1500 字符以内（为 Milvus 留出安全边界）
     * - 该函数尽量保证语义完整的句子边界
     * - 从 parsePdf 注入的 [PAGE: N] 标记中提取页码信息
     */
    private List<ChunkWithPage> splitDocumentIntoChunksWithPage(String content, int chunkSize) {
        List<ChunkWithPage> result = new ArrayList<>();
        java.util.regex.Pattern pagePattern = java.util.regex.Pattern.compile("\\[PAGE:\\s*(\\d+)\\]");

        // 将文档按页码标记分割，每段附带起始页码
        String[] pageBlocks = content.split("\\[PAGE:");
        int currentPage = 1;
        StringBuilder combined = new StringBuilder();

        for (int i = 0; i < pageBlocks.length; i++) {
            if (i == 0) {
                // 第一块没有 PAGE: 前缀，直接拼入
                combined.append(pageBlocks[i]);
            } else {
                // 提取页码
                String block = pageBlocks[i];
                java.util.regex.Matcher m = pagePattern.matcher("[PAGE:" + block.substring(0, Math.min(10, block.length())));
                int blockPage = 1;
                if (m.find()) blockPage = Integer.parseInt(m.group(1));
                // 先把之前的累积内容分片
                if (combined.length() > 0) {
                    List<ChunkWithPage> chunks = splitByParagraphs(combined.toString(), currentPage, chunkSize);
                    result.addAll(chunks);
                    combined = new StringBuilder();
                }
                currentPage = blockPage;
                combined.append(block);
            }
        }
        // 处理最后剩余内容
        if (combined.length() > 0) {
            List<ChunkWithPage> chunks = splitByParagraphs(combined.toString(), currentPage, chunkSize);
            result.addAll(chunks);
        }

        // 最终检查：截断超长 chunk（截断时保留同一页码）
        List<ChunkWithPage> finalResult = new ArrayList<>();
        for (ChunkWithPage cwp : result) {
            if (cwp.text.length() > 1500) {
                log.warn("[分片] 发现超长chunk，长度: {}, 页码: {}, 强制截断", cwp.text.length(), cwp.page);
                finalResult.add(new ChunkWithPage(cwp.text.substring(0, 1500), cwp.page));
            } else {
                finalResult.add(cwp);
            }
        }
        return finalResult;
    }

    /**
     * 按段落分割文本为 chunks，保留页码
     */
    private List<ChunkWithPage> splitByParagraphs(String content, int page, int chunkSize) {
        List<ChunkWithPage> chunks = new ArrayList<>();
        String[] paragraphs = content.split("\n\n");
        StringBuilder currentChunk = new StringBuilder();
        int currentPage = page;

        for (String paragraph : paragraphs) {
            String cleanParagraph = paragraph
                    .replaceAll("\\[PAGE:\\s*\\d+\\]", "")
                    .replaceAll("[\\x00-\\x1F\\x7F]", "")
                    .replaceAll("[\\uFFFD]", "")
                    .trim();
            if (cleanParagraph.isEmpty()) continue;

            // 提取段落中的页码标记
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\[PAGE:\\s*(\\d+)\\]").matcher(paragraph);
            if (m.find()) currentPage = Integer.parseInt(m.group(1));

            if (cleanParagraph.length() > chunkSize) {
                if (currentChunk.length() > 0) {
                    String chunk = currentChunk.toString().trim();
                    if (!chunk.isEmpty()) chunks.add(new ChunkWithPage(chunk, currentPage));
                    currentChunk = new StringBuilder();
                }
                String[] sentences = cleanParagraph.split("[。！？.!?]");
                StringBuilder sentenceChunk = new StringBuilder();
                for (String sentence : sentences) {
                    sentence = sentence.trim();
                    if (sentence.isEmpty()) continue;
                    if (sentenceChunk.length() + sentence.length() > chunkSize) {
                        String chunk = sentenceChunk.toString().trim();
                        if (!chunk.isEmpty()) chunks.add(new ChunkWithPage(chunk, currentPage));
                        sentenceChunk = new StringBuilder();
                    }
                    sentenceChunk.append(sentence).append("。");
                }
                if (sentenceChunk.length() > 0) {
                    String chunk = sentenceChunk.toString().trim();
                    if (!chunk.isEmpty()) chunks.add(new ChunkWithPage(chunk, currentPage));
                }
            } else {
                if (currentChunk.length() + cleanParagraph.length() > chunkSize) {
                    String chunk = currentChunk.toString().trim();
                    if (!chunk.isEmpty()) chunks.add(new ChunkWithPage(chunk, currentPage));
                    currentChunk = new StringBuilder();
                }
                currentChunk.append(cleanParagraph).append("\n\n");
            }
        }
        if (currentChunk.length() > 0) {
            String chunk = currentChunk.toString().trim();
            if (!chunk.isEmpty()) chunks.add(new ChunkWithPage(chunk, currentPage));
        }
        return chunks;
    }

    /**
     * 兼容旧接口：返回纯文本列表（用于不需要页码的场景）
     */
    private List<String> splitDocumentIntoChunks(String content, int chunkSize) {
        return splitDocumentIntoChunksWithPage(content, chunkSize)
                .stream().map(cwp -> cwp.text).collect(Collectors.toList());
    }
    
    
}