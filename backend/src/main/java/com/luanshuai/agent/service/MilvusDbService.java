package com.luanshuai.agent.service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.LinkedHashSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.luanshuai.agent.config.AppConfig;
import com.luanshuai.agent.util.TenantContext;
import com.luanshuai.agent.util.TenantUtils;

import io.milvus.client.MilvusServiceClient;
import io.milvus.exception.ServerException;
import io.milvus.grpc.DataType;
import io.milvus.grpc.DescribeCollectionResponse;
import io.milvus.grpc.DescribeIndexResponse;
import io.milvus.grpc.FlushResponse;
import io.milvus.grpc.MutationResult;
import io.milvus.grpc.QueryResults;
import io.milvus.grpc.SearchResults;
import io.milvus.grpc.ShowCollectionsResponse;
import io.milvus.param.ConnectParam;
import io.milvus.param.IndexType;
import io.milvus.param.MetricType;
import io.milvus.param.R;
import io.milvus.param.RpcStatus;
import io.milvus.param.collection.CreateCollectionParam;
import io.milvus.param.collection.DescribeCollectionParam;
import io.milvus.param.collection.DropCollectionParam;
import io.milvus.param.collection.FieldType;
import io.milvus.param.collection.FlushParam;
import io.milvus.param.collection.HasCollectionParam;
import io.milvus.param.collection.LoadCollectionParam;
import io.milvus.param.collection.ShowCollectionsParam;
import io.milvus.param.dml.DeleteParam;
import io.milvus.param.dml.InsertParam;
import io.milvus.param.dml.QueryParam;
import io.milvus.param.dml.SearchParam;
import io.milvus.param.index.CreateIndexParam;
import io.milvus.param.index.DescribeIndexParam;
import io.milvus.response.QueryResultsWrapper;
import io.milvus.response.SearchResultsWrapper;

/**
 * Milvus 数据库服务封装
 * 责任：
 * - 管理与 Milvus 的连接生命周期（连接、重试、关闭）
 * - 基于租户构建集合名，支持租户隔离（tenant-aware collection naming）
 * - 提供集合创建、插入、查询、索引创建、删除等操作，并包含周到的错误处理与重试策略
 * - 提供搜索结果的后处理、语义验证与回退机制，兼顾稳定性与可观测性
 */
@Service
public class MilvusDbService {
    private static final Logger log = LoggerFactory.getLogger(MilvusDbService.class); // 日志记录器
    private final AppConfig appConfig; // 注入的应用配置，包含 Milvus 相关配置
    private MilvusServiceClient milvusClient; // Milvus 客户端实例
    // 控制台高亮颜色常量（仅用于本地调试输出）
    private static final String ANSI_RED = "\u001B[31m";
    private static final String ANSI_YELLOW = "\u001B[33m";
    private static final String ANSI_RESET = "\u001B[0m";
    // 在日志信息中添加颜色（仅调试用）
    private static String red(String msg) {
        return ANSI_RED + msg + ANSI_RESET;
    }
    private static String yellow(String msg) {
        return ANSI_YELLOW + msg + ANSI_RESET;
    }
    
    // 同步索引创建的锁，避免并发多线程重复创建索引导致冲突
    private static final Object INDEX_LOCK = new Object();
    // 跟踪当前正在创建索引的集合名（线程安全集合）
    private static final Set<String> INDEX_CREATING = Collections.synchronizedSet(new HashSet<>());
    // 记录索引创建失败的重试次数（用于防止无限重试）
    private static final Map<String, Integer> INDEX_RETRY_COUNT = Collections.synchronizedMap(new HashMap<>());
    // 缓存已加载的集合，避免重复 loadCollection（Milvus load 是高开销操作）
    private final ConcurrentHashMap<String, Boolean> loadedCollections = new ConcurrentHashMap<>();

    @Autowired
    public MilvusDbService(AppConfig appConfig) {
        this.appConfig = appConfig;
    }

    @PostConstruct
    public void init() {
        // 初始化 Milvus 连接并添加重试机制，以应对 Milvus 服务尚未就绪的场景（例如容器启动顺序）
        AppConfig.Milvus cfg = appConfig.getMilvus();
        
        // 增加连接重试逻辑，处理 Milvus 启动延迟或网络不稳定
        int maxRetries = 5;
        int retryDelay = 5000; // 毫秒（5秒）
        
        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                log.info("尝试连接Milvus (尝试 {}/{}): {}:{}", attempt, maxRetries, cfg.getHost(), cfg.getPort());
                
                // 创建 Milvus 客户端，设置更长的连接超时以提高健壮性
                milvusClient = new MilvusServiceClient(
                        ConnectParam.newBuilder()
                                .withHost(cfg.getHost())
                                .withPort(cfg.getPort())
                                .withDatabaseName(cfg.getDatabase())
                                .withAuthorization(cfg.getUsername(), cfg.getPassword())
                                .withConnectTimeout(30, TimeUnit.SECONDS) // 增加连接超时到30秒
                                .build()
                );
                
                // 简单测试连接是否生效（调用 showCollections）
                R<ShowCollectionsResponse> testResponse = milvusClient.showCollections(ShowCollectionsParam.newBuilder().build());
                if (testResponse != null) {
                    log.info("Milvus client initialized successfully: {}:{} (db={}, user={})", cfg.getHost(), cfg.getPort(), cfg.getDatabase(), cfg.getUsername());
                    log.warn(yellow("[KB_TRACE] milvus connection initialized: host={}, port={}, db={}, user={}"), cfg.getHost(), cfg.getPort(), cfg.getDatabase(), cfg.getUsername());
                    return; // 初始化成功，返回
                } else {
                    throw new RuntimeException("Milvus连接测试失败：响应为空");
                }
                
            } catch (Exception e) {
                // 记录并决定是否重试或最终失败
                log.warn("Milvus连接失败 (尝试 {}/{}): {}", attempt, maxRetries, e.getMessage());
                
                if (attempt < maxRetries) {
                    try {
                        log.info("等待 {} 毫秒后重试...", retryDelay);
                        Thread.sleep(retryDelay);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException("连接重试被中断", ie);
                    }
                } else {
                    // 达到最大重试次数则抛出异常，通常会使应用启动失败以便人工干预
                    log.error("Milvus连接失败，已达到最大重试次数 ({})", maxRetries);
                    throw new RuntimeException("无法连接到Milvus服务器: " + e.getMessage(), e);
                }
            }
        }
        
        // 调试：测试关键词提取（保留仅用于诊断）
        System.out.println("[DEBUG] Testing extractQueryKeywords...");
        List<String> testKeywords = extractQueryKeywords("标准值和卫生要求");
        System.out.println("[DEBUG] Test result: " + testKeywords);
    }

    public String getConnectionSummary() {
        AppConfig.Milvus cfg = appConfig.getMilvus();
        return String.format("host=%s, port=%s, db=%s, user=%s", cfg.getHost(), cfg.getPort(), cfg.getDatabase(), cfg.getUsername());
    }

    public List<String> listCollections() {
        try {
            R<ShowCollectionsResponse> resp = milvusClient.showCollections(ShowCollectionsParam.newBuilder().build());
            if (resp == null || resp.getData() == null) {
                return Collections.emptyList();
            }
            ShowCollectionsResponse data = resp.getData();
            if (data.getCollectionNamesList() == null) {
                return Collections.emptyList();
            }
            return new ArrayList<>(data.getCollectionNamesList());
        } catch (Exception e) {
            log.error(red("[KB_TRACE] listCollections failed: {}"), e.getMessage());
            return Collections.emptyList();
        }
    }

    @PreDestroy
    public void close() {
        if (milvusClient != null) {
            milvusClient.close();
        }
    }
    
    /**
     * 构建租户隔离的集合名
     * 如果传入的collectionName已经是租户隔离格式，则直接返回
     * 否则根据当前租户ID和知识库名称构建租户隔离的集合名
     */
    private String buildTenantCollectionName(String collectionName, String knowledgeBaseId) {
        // 如果集合名已经是租户隔离格式（以t_开头），直接返回
        if (collectionName != null && collectionName.startsWith("t_") && collectionName.contains("__")) {
            return collectionName;
        }
        
        // 获取当前租户ID
        String tenantId = TenantContext.getCurrentTenantId();
        
        // 使用知识库ID或集合名作为知识库名称
        String kbName = knowledgeBaseId != null ? knowledgeBaseId : collectionName;
        if (kbName == null || kbName.isEmpty()) {
            kbName = "default_knowledge_base";
        }
        
        // 构建租户隔离的集合名
        return TenantUtils.buildTenantCollectionName(tenantId, kbName);
    }
    
    /**
     * 重载方法：只传入集合名，自动从集合名推断知识库名称
     */
    private String buildTenantCollectionName(String collectionName) {
        return buildTenantCollectionName(collectionName, null);
    }

    /**
     * 创建集合（租户隔离）
     * - 会基于传入的 collectionName 生成租户隔离后的集合名
     * - 若集合已存在则幂等返回
     * - 定义集合的 schema（id, embedding, text, metadata）并调用 Milvus 创建
     */
    public void createCollection(String collectionName) {
        // 构建租户隔离的集合名
        String tenantCollectionName = buildTenantCollectionName(collectionName);
        log.info("[Milvus] 创建集合（租户隔离）: {} -> {}", collectionName, tenantCollectionName);
        
        // 检查是否已存在（幂等）
        HasCollectionParam hasParam = HasCollectionParam.newBuilder().withCollectionName(tenantCollectionName).build();
        R<Boolean> response = milvusClient.hasCollection(hasParam);
        boolean exists = response.getData() != null && response.getData();
        if (exists) {
            log.info("[Milvus] Collection already exists: {}", tenantCollectionName);
            return; // 已存在则不重复创建
        }
        log.warn(yellow("[KB_TRACE] creating collection schema (no index yet): {}"), tenantCollectionName);
        
        // 定义 schema 字段（注意：embedding 的维度需与上游 embedding 模型一致）
        List<FieldType> fields = new ArrayList<>();
        fields.add(FieldType.newBuilder()
                .withName("id")
                .withDataType(DataType.VarChar)
                .withMaxLength(64)
                .withPrimaryKey(true)
                .withAutoID(false)
                .build());
        fields.add(FieldType.newBuilder()
                .withName("embedding")
                .withDataType(DataType.FloatVector)
                .withDimension(768) // nomic-embed-text-v2-moe 输出768维
                .build());
        fields.add(FieldType.newBuilder()
                .withName("text")
                .withDataType(DataType.VarChar)
                .withMaxLength(4096)
                .build());
        fields.add(FieldType.newBuilder()
                .withName("metadata")
                .withDataType(DataType.VarChar)
                .withMaxLength(1024)
                .build());
        CreateCollectionParam param = CreateCollectionParam.newBuilder()
                .withCollectionName(tenantCollectionName)
                .withDescription("RAG知识库（租户隔离）")
                .withShardsNum(2)
                .withFieldTypes(fields)
                .build();
        
        try {
            milvusClient.createCollection(param);
            log.info("[Milvus] Collection created successfully: {}", tenantCollectionName);
        } catch (Exception e) {
            log.error("[Milvus] Failed to create collection {}: {}", tenantCollectionName, e.getMessage());
            throw e;
        }
    }

    /**
     * 批量插入文档到集合
     * - 对文本字段进行安全截断（防止字段过长导致 Milvus 插入失败）
     * - 确保 metadata 为字符串类型
     * - 插入后执行 flush 并同步创建索引（同步等待索引建立）
     */
    public void addDocuments(String collectionName, List<String> ids, List<List<Float>> embeddings, List<String> texts, List<String> metadatas) {
        // 构建租户隔离的集合名
        String tenantCollectionName = buildTenantCollectionName(collectionName);
        log.info("[Milvus] addDocuments（租户隔离）: {} -> {}, documents={}", collectionName, tenantCollectionName, ids.size());
        
        // 确保集合存在（幂等创建）
        createCollection(collectionName);
        
        // 文本长度保护：严格限制在 3000 字符以内，以防止 Milvus 因字段过长失败
        List<String> safeTexts = texts.stream()
            .map(text -> {
                if (text == null) return "";
                String str = text.toString();
                // 严格截断：确保不超过3000字符
                if (str.length() > 3000) {
                    log.warn("[Milvus] 文本长度超过限制，原长度: {}, 截断到: {}", str.length(), 3000);
                    
                    // 尝试在中文句号/标点处截断以保留完整句子
                    String truncated = str.substring(0, 3000);
                    int lastPeriod = truncated.lastIndexOf('。');
                    int lastExclamation = truncated.lastIndexOf('！');
                    int lastQuestion = truncated.lastIndexOf('？');
                    int lastDot = truncated.lastIndexOf('.');
                    
                    int bestBreak = Math.max(Math.max(lastPeriod, lastExclamation), Math.max(lastQuestion, lastDot));
                    
                    if (bestBreak > 3000 * 0.7) { // 若断句点位于前70%范围内，则在断句处截断
                        str = truncated.substring(0, bestBreak + 1);
                        log.info("[Milvus] 在句号处截断，保留长度: {}", str.length());
                    } else {
                        str = truncated; // 否则强制截断
                        log.info("[Milvus] 强制截断，保留长度: {}", str.length());
                    }
                }
                return str;
            })
            .collect(Collectors.toList());
        
        // metadata 同样确保为字符串
        List<String> safeMetadatas = metadatas.stream()
            .map(meta -> meta != null ? meta.toString() : "")
            .collect(Collectors.toList());
        
        // 构建 InsertParam 并提交
        List<InsertParam.Field> fields = new ArrayList<>();
        fields.add(new InsertParam.Field("id", ids));
        fields.add(new InsertParam.Field("embedding", embeddings));
        fields.add(new InsertParam.Field("text", safeTexts));
        fields.add(new InsertParam.Field("metadata", safeMetadatas));
        InsertParam param = InsertParam.newBuilder()
                .withCollectionName(tenantCollectionName)
                .withFields(fields)
                .build();
        
        log.info("[Milvus] InsertParam prepared for collection: {}", tenantCollectionName);
        try {
            R<MutationResult> result = milvusClient.insert(param);
            // 避免在 INFO 下打印 Milvus 返回对象的完整结构（过于冗长），改为仅在 DEBUG 下输出原始结果
            log.info("[Milvus] raw insert result");
            
            // 检查插入是否成功
            if (result.getStatus() != R.Status.Success.getCode()) {
                log.error("[Milvus] 插入失败: {} - {}", result.getStatus(), 
                    result.getException() != null ? result.getMessage() : "No exception");
                throw new RuntimeException("插入失败: " + 
                    (result.getException() != null ? result.getMessage() : "No exception"));
            }
            
            // 检查插入的数据数量
            if (result.getData() == null || result.getData().getInsertCnt() == 0) {
                log.error("[Milvus] 插入数据为空，可能因为字段长度超限");
                throw new RuntimeException("插入数据为空，可能因为字段长度超限");
            }
            
            log.info("[Milvus] 成功插入 {} 个文档到集合 {}", result.getData().getInsertCnt(), tenantCollectionName);
            
            // 强制刷新数据以确保可查询
            milvusClient.flush(FlushParam.newBuilder().withCollectionNames(Collections.singletonList(tenantCollectionName)).build());
            log.info("[Milvus] 集合 {} 已刷新", tenantCollectionName);
            
            // 同步创建索引（阻塞等待索引建立或记录失败），以提高后续检索的稳定性
            createIndexSync(tenantCollectionName);
            
        } catch (Exception e) {
            log.error("[Milvus] insert exception: {} {}", e.getClass().getName(), e.getMessage());
            throw e;
        }
    }
// 同步索引创建方法 - 改为同步执行
    // 注意：此方法接收的collectionName应该是租户隔离的集合名
    /**
     * 同步创建索引（线程安全）
     * 说明：该方法会阻塞直到索引创建成功或达到超时/重试上限，避免后续搜索因缺少索引而失败
     */
    private void createIndexSync(String collectionName) {
        synchronized (INDEX_LOCK) {
            try {
                // 如果索引已存在则直接返回（幂等）
                if (indexExists(collectionName)) {
                    log.info("[Milvus] 索引已存在: {}", collectionName);
                    return;
                }
                
                // 检查重试次数，避免无限重试
                int retryCount = INDEX_RETRY_COUNT.getOrDefault(collectionName, 0);
                if (retryCount >= 3) {
                    log.error("[Milvus] 索引创建重试次数已达上限，跳过: {}", collectionName);
                    return;
                }
                
                log.info("[Milvus] 开始创建索引: {}", collectionName);
                log.debug("[Milvus] createIndex param preview: collection={}, field=embedding, type=IVF_FLAT, nlist=128", collectionName);
                CreateIndexParam indexParam = CreateIndexParam.newBuilder()
                    .withCollectionName(collectionName)
                    .withFieldName("embedding")
                    .withIndexType(IndexType.IVF_FLAT)
                    .withMetricType(MetricType.L2)
                    .withExtraParam("{\"nlist\":128}")
                    .build();
                
                R<RpcStatus> result = milvusClient.createIndex(indexParam);
                if (result == null) {
                    log.error("[Milvus] createIndex returned null for {}", collectionName);
                    INDEX_RETRY_COUNT.put(collectionName, retryCount + 1);
                    return;
                }
                if (result.getStatus() != R.Status.Success.getCode()) {
                    log.error("[Milvus] 索引创建失败: {} - {}", collectionName, 
                        result.getException() != null ? result.getMessage() : "No exception");
                    try {
                        DescribeIndexParam dip = DescribeIndexParam.newBuilder().withCollectionName(collectionName).build();
                        R<DescribeIndexResponse> dr = milvusClient.describeIndex(dip);
                        if (dr != null) {
                            log.warn("[Milvus] DescribeIndex after createIndex for {}: status={} message={} dataSummary={}", collectionName, dr.getStatus(), 
                                dr.getException() != null ? dr.getMessage() : "No exception", summarizeDescribeIndexData(dr.getData()));
                        }
                    } catch (Exception dex) {
                        log.debug("[Milvus] describeIndex after createIndex exception for {}: {}", collectionName, dex.getMessage());
                    }
                    INDEX_RETRY_COUNT.put(collectionName, retryCount + 1);
                    return;
                }
                
                // 等待索引构建完成（有额外的日志和超时保护）
                boolean success = waitForIndexBuilt(collectionName);
                if (success) {
                    log.info("[Milvus] 索引创建完成: {}", collectionName);
                    INDEX_RETRY_COUNT.remove(collectionName); // 清除重试计数
                } else {
                    log.error("[Milvus] 索引构建超时: {}", collectionName);
                    INDEX_RETRY_COUNT.put(collectionName, retryCount + 1);
                }
                
            } catch (ServerException e) {
                // 记录服务端异常并增加重试计数
                log.error("[Milvus] 服务端异常: {}", e.getMessage());
                int retryCount = INDEX_RETRY_COUNT.getOrDefault(collectionName, 0);
                INDEX_RETRY_COUNT.put(collectionName, retryCount + 1);
            } catch (Exception e) {
                log.error("[Milvus] 索引操作异常: {}", e.getMessage());
                int retryCount = INDEX_RETRY_COUNT.getOrDefault(collectionName, 0);
                INDEX_RETRY_COUNT.put(collectionName, retryCount + 1);
            }
        }
    }
    
    // 检查索引是否存在
    private boolean indexExists(String collectionName) {
        try {
            // 先检查集合是否存在，避免对不存在或空集合调用 describeIndex 导致 Milvus 客户端记录 ERROR
            if (!hasCollection(collectionName)) {
                log.warn("[Milvus] collection does not exist, skip describeIndex: {}", collectionName);
                return false;
            }

            // 不在此处调用 getCollectionCount()（会触发 loadCollection），仅靠 hasCollection 与 describeIndex 判定索引存在性

            DescribeIndexParam param = DescribeIndexParam.newBuilder()
                .withCollectionName(collectionName)
                .build();
            
            R<DescribeIndexResponse> resp = milvusClient.describeIndex(param);
            if (resp == null) {
                log.debug("[Milvus] describeIndex returned null for {}", collectionName);
                return false;
            }
            log.debug("[Milvus] describeIndex status: {} message: {}", resp.getStatus(), 
                resp.getException() != null ? resp.getMessage() : "No exception");
            if (resp.getData() != null) {
                log.debug("[Milvus] describeIndex dataSummary: {}", summarizeDescribeIndexData(resp.getData()));
            }
            return resp.getStatus() == R.Status.Success.getCode() && 
                   resp.getData() != null &&
                   resp.getData().getIndexDescriptionsCount() > 0;
        } catch (ServerException e) {
            String msg = e.getMessage() == null ? "" : e.getMessage();
            if (msg.contains("index not found")) {
                log.warn("[Milvus] 索引不存在 (正常短暂状态): collection={}", collectionName);
                return false;
            }
            log.debug("[Milvus] describeIndex ServerException for {}: {}", collectionName, e.getMessage());
            throw e;
        } catch (Exception e) {
            log.warn("[Milvus] describeIndex failed for {}: {}", collectionName, e.getMessage());
            return false;
        }
    }

    // 将 DescribeIndexResponse 摘要化，避免打印大量冗长内部结构
    private String summarizeDescribeIndexData(DescribeIndexResponse data) {
        if (data == null) return "null";
        try {
            int count = data.getIndexDescriptionsCount();
            long totalIndexed = 0;
            long totalRows = 0;
            Set<String> states = new LinkedHashSet<>();
            for (int i = 0; i < count; i++) {
                try {
                    // 使用DescribeIndexResponse提供的IndexDescription API安全获取信息
                    try {
                        String state = data.getIndexDescriptions(i).getState().toString();
                        states.add(state);
                    } catch (Throwable ignore) {}
                    try { totalIndexed += data.getIndexDescriptions(i).getIndexedRows(); } catch (Throwable ignore){}
                    try { totalRows += data.getIndexDescriptions(i).getTotalRows(); } catch (Throwable ignore){}
                } catch (Throwable ex) {
                    // ignore per-item errors
                }
            }
            return String.format("indexCount=%d,indexedRows=%d,totalRows=%d,states=%s", count, totalIndexed, totalRows, String.join("|", states));
        } catch (Throwable t) {
            return "<summarize-failed>";
        }
    }
    
    // 等待索引构建完成 - 返回是否成功
    private boolean waitForIndexBuilt(String collectionName) {
        int retries = 0;
        int maxRetries = 120; // 增加到120秒超时
        while (retries++ < maxRetries) {
            try {
                if (indexExists(collectionName)) {
                    log.info("[Milvus] 索引构建完成: {}", collectionName);
                    return true;
                }
                
                // 检查索引构建状态
                DescribeIndexParam param = DescribeIndexParam.newBuilder()
                    .withCollectionName(collectionName)
                    .build();
                
                R<DescribeIndexResponse> resp = milvusClient.describeIndex(param);
                if (resp == null) {
                    log.debug("[Milvus] describeIndex returned null for {} at attempt {}", collectionName, retries);
                } else {
                    log.debug("[Milvus] describeIndex status={} message={}", resp.getStatus(), 
                        resp.getException() != null ? resp.getMessage() : "No exception");
                    if (resp.getData() != null) log.debug("[Milvus] describeIndex data: {}", resp.getData());
                }
                if (resp.getStatus() == R.Status.Success.getCode() && 
                    resp.getData() != null &&
                    resp.getData().getIndexDescriptionsCount() > 0) {
                    
                    // 检查索引状态
                    boolean allBuilt = true;
                    for (int i = 0; i < resp.getData().getIndexDescriptionsCount(); i++) {
                        String state = resp.getData().getIndexDescriptions(i).getState().toString();
                        if (!"Finished".equals(state)) {
                            allBuilt = false;
                            break;
                        }
                    }
                    
                    if (allBuilt) {
                        log.info("[Milvus] 索引构建完成: {}", collectionName);
                        return true;
                    }
                }
                
                Thread.sleep(1000);
                if (retries % 10 == 0) { // 每10秒记录一次日志
                    log.info("[Milvus] 等待索引构建...({}/{})", retries, maxRetries);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("[Milvus] 索引构建等待被中断");
                return false;
            } catch (Exception e) {
                log.warn("[Milvus] 检查索引状态时出错: {}", e.getMessage());
                if (retries % 5 == 0) { // 每5次错误记录一次
                    log.error("[Milvus] 索引状态检查异常: {}", e.getMessage());
                }
            }
        }
        log.warn("[Milvus] 索引构建超时: {}", collectionName);
        return false;
    }

    public List<Map<String, Object>> queryDocuments(String collectionName, List<Float> queryEmbedding, int nResults) {
        return queryDocuments(collectionName, queryEmbedding, nResults, null);
    }
    
    public List<Map<String, Object>> queryDocuments(String collectionName, List<Float> queryEmbedding, int nResults, String queryText) {
        // 构建租户隔离的集合名
        String tenantCollectionName = buildTenantCollectionName(collectionName);
        log.info("[Milvus] queryDocuments（租户隔离）: {} -> {}", collectionName, tenantCollectionName);
        log.info(red("[KB_TRACE] queryDocuments start: requested='{}' resolvedCollection='{}' vectorDim={} topK={}"),
            collectionName,
            tenantCollectionName,
            (queryEmbedding == null ? -1 : queryEmbedding.size()),
            nResults);
        
        // 确保集合存在
        createCollection(collectionName);
        
        // 检查集合是否存在
        boolean hasCollection = hasCollection(tenantCollectionName);
        log.info("[Milvus] Collection {} exists: {}", tenantCollectionName, hasCollection);
        if (!hasCollection) {
            log.error(red("[KB_TRACE] collection does NOT exist after createCollection: {} (check tenantId/kbName mapping)"), tenantCollectionName);
        }
        
        if (!hasCollection) {
            log.warn("[Milvus] Collection {} does not exist!", tenantCollectionName);
            return Collections.emptyList();
        }
        // 关键诊断：load 前先判断索引是否存在（缺索引会导致 loadCollection 失败）
        try {
            boolean hasIndex = indexExists(tenantCollectionName);
            if (!hasIndex) {
                log.info("[Milvus] 索引不存在 (新集合的正常情况): collection={}", tenantCollectionName);
            } else {
                log.info(red("[KB_TRACE] index exists BEFORE loadCollection: collection={}"), tenantCollectionName);
            }
        } catch (Exception e) {
            log.warn(red("[KB_TRACE] index check failed before loadCollection: collection={} err='{}'"), tenantCollectionName, e.getMessage());
        }
        
        // 优化：减少重试次数，提高响应速度
        int maxRetry = 2; // 从3减少到2
        boolean loadSuccess = false;
        for (int attempt = 1; attempt <= maxRetry; attempt++) {
            try {
                milvusClient.loadCollection(LoadCollectionParam.newBuilder()
                    .withCollectionName(tenantCollectionName)
                    .build());
                log.info("[Milvus] loadCollection: {} success (attempt {}/{})", tenantCollectionName, attempt, maxRetry);
                loadSuccess = true;
                break;
            } catch (Exception e) {
                log.warn("[Milvus] loadCollection failed (attempt {}/{}): {}", attempt, maxRetry, e.getMessage());
                log.debug("[Milvus] loadCollection exception for {}: {}", tenantCollectionName, e.getMessage());
                String msg = e.getMessage() == null ? "" : e.getMessage();
                if (msg.contains("index not found") || msg.contains("Index not found") || msg.contains("index not found[collection")) {
                    log.warn("[Milvus] loadCollection failed due to missing index: collection={} (this is normal for new collections)", tenantCollectionName);
                } else if (msg.contains("collection not found") || msg.contains("Collection not found")) {
                    log.warn("[Milvus] loadCollection failed because collection does not exist: {} (this should not happen after createCollection)", tenantCollectionName);
                    // 如果集合不存在，直接返回空结果，不要继续尝试搜索
                    return Collections.emptyList();
                }
                if (attempt == maxRetry) {
                    log.warn("[Milvus] Failed to load collection {} after {} attempts, continuing with search anyway", tenantCollectionName, maxRetry);
                    // 优化：减少重试间隔
                    try { Thread.sleep(500); } catch (InterruptedException ignored) {} // 从1000ms减少到500ms
                }
            }
        }
        
        if (!loadSuccess) {
            log.warn("[Milvus] Collection {} may not be loaded, but continuing with search", tenantCollectionName);
        }
        
        log.info("[Milvus] queryDocuments: collectionName={}, queryEmbedding dim={}, nResults={}", tenantCollectionName, queryEmbedding.size(), nResults);
        
        // 检查集合中的文档数量
        try {
            int docCount = getCollectionCount(tenantCollectionName);
            log.info("[Milvus] 集合 {} 中共有 {} 个文档", tenantCollectionName, docCount);
            if (docCount == 0) {
                log.warn("[Milvus] 集合 {} 为空，无法进行搜索", tenantCollectionName);
                // 注意：不要在“空知识库”场景下注入测试文档，否则会污染 RAG 引用并造成“参考文献货不对板”。
                return Collections.emptyList();
            }
        } catch (Exception e) {
            log.warn("[Milvus] 检查集合文档失败: {}", e.getMessage());
        }
        
        // 添加集合状态检查
        try {
            log.info("[Milvus] 查询集合: {}", tenantCollectionName);
        } catch (Exception e) {
            log.warn("[Milvus] 获取集合信息失败: {}", e.getMessage());
        }
        
        // 优化：平衡搜索结果数量和质量
        int optimizedResults = Math.min(nResults, 20); // 减少到20，提高精确率
        
        // 获取搜索超时配置
        int searchTimeoutMs = appConfig.getMilvus().getSearchTimeout();
        
        SearchParam searchParam = SearchParam.newBuilder()
                .withCollectionName(tenantCollectionName)
                .withMetricType(MetricType.L2)
                .withOutFields(Arrays.asList("id", "text", "metadata"))
                .withTopK(optimizedResults) // 使用优化后的结果数
                .withVectors(Collections.singletonList(queryEmbedding))
                .withVectorFieldName("embedding")
                .withParams("{\"nprobe\": 32}") // 平衡搜索精度和性能
                .build();
        
        log.info("[Milvus] 搜索参数: collectionName={}, topK={}, vectorDim={}", 
                tenantCollectionName, optimizedResults, queryEmbedding.size());
        
        // 检查embedding向量是否全为零
        boolean allZero = queryEmbedding.stream().allMatch(d -> d == 0.0);
        if (allZero) {
            log.warn("[Milvus] 警告：查询向量全为零，使用关键词搜索作为备用方案！");
            // 如果向量全为零，直接使用关键词搜索
            if (queryText != null && !queryText.trim().isEmpty()) {
                return searchDocumentsByKeyword(tenantCollectionName, queryText);
            } else {
                return Collections.emptyList();
            }
        }
        
        // 确保集合已加载
        try {
            milvusClient.loadCollection(LoadCollectionParam.newBuilder()
                .withCollectionName(tenantCollectionName)
                .build());
            log.info("[Milvus] 集合已加载: {}", tenantCollectionName);
        } catch (Exception loadEx) {
            String msg = loadEx.getMessage() == null ? "" : loadEx.getMessage();
            if (msg.contains("collection not found") || msg.contains("Collection not found")) {
                log.info("[Milvus] 集合不存在 (正常情况): {}", tenantCollectionName);
            } else {
                log.warn("[Milvus] 加载集合失败，但继续尝试搜索: {}", loadEx.getMessage());
            }
        }
        
        // 优化：减少搜索重试次数
        int searchMaxRetry = 2; // 从3减少到2
        for (int attempt = 1; attempt <= searchMaxRetry; attempt++) {
            try {
                // 使用完全安全的搜索方法
                R<SearchResults> searchResponse = performSafeSearch(searchParam);
                
                if (searchResponse == null) {
                    log.error("[Milvus] 搜索响应为null，搜索失败");
                    return Collections.emptyList();
                }
                
                log.info("[Milvus] 搜索响应状态: {}", searchResponse.getStatus());
                
                // 安全地获取响应消息，避免null异常
                String responseMessage = getSafeResponseMessage(searchResponse);
                log.info("[Milvus] 搜索响应消息: {}", responseMessage);
                
                // 安全地获取响应数据
                SearchResults responseData = getSafeResponseData(searchResponse);
                log.info("[Milvus] 搜索响应数据: {}", responseData != null ? "数据存在" : "数据为空");
                
                if (searchResponse.getStatus() != R.Status.Success.getCode()) {
                    log.error("[Milvus] 搜索失败: {} - {}", searchResponse.getStatus(), responseMessage);
                    return Collections.emptyList();
                }
                
                log.info("[Milvus] 搜索响应状态检查通过，开始处理响应数据");
                
                // 使用之前安全获取的响应数据
                SearchResults searchResults = responseData;
                if (searchResults == null) {
                    log.error("[Milvus] searchResults is null! 响应状态: {}, 消息: {}", 
                            searchResponse.getStatus(), responseMessage);
                    return Collections.emptyList();
                }
                
                // 安全地创建SearchResultsWrapper
                SearchResultsWrapper wrapper;
                try {
                    wrapper = new SearchResultsWrapper(searchResults.getResults());
                } catch (Exception wrapperEx) {
                    log.error("[Milvus] 创建SearchResultsWrapper失败: {}", wrapperEx.getMessage());
                    return Collections.emptyList();
                }
                
                List<Map<String, Object>> docs = new ArrayList<>();
                
                // 安全地获取行记录
                List<QueryResultsWrapper.RowRecord> rowRecords;
                try {
                    rowRecords = wrapper.getRowRecords();
                } catch (Exception rowEx) {
                    log.error("[Milvus] 获取行记录失败: {}", rowEx.getMessage());
                    return Collections.emptyList();
                }
                
                int numResults = rowRecords != null ? rowRecords.size() : 0;
                
                log.info("[Milvus] Found {} results", numResults);
                if (numResults == 0) {
                    log.warn("[Milvus] 没有找到任何搜索结果，可能的原因：1.集合为空 2.向量维度不匹配 3.搜索参数问题");
                    log.warn("[Milvus] 调试信息 - 集合: {}, 向量维度: {}, topK: {}", 
                            collectionName, queryEmbedding.size(), optimizedResults);
                }
                
                // 候选结果：通过“相似度阈值”的文档（用于语义过滤过严时的回退）
                List<Map<String, Object>> similarityCandidates = new ArrayList<>();

                for (int i = 0; i < numResults; i++) {
                    try {
                        Map<String, Object> doc = new HashMap<>();
                        
                        // 直接从行记录获取字段数据
                        QueryResultsWrapper.RowRecord rowRecord = rowRecords.get(i);
                        Object idData = rowRecord.get("id");
                        Object textData = rowRecord.get("text");
                        Object metadataData = rowRecord.get("metadata");
                        
                        // 处理ID字段 - 可能是List或单个值
                        if (idData instanceof List) {
                            List<?> idList = (List<?>) idData;
                            doc.put("id", idList.isEmpty() ? "" : idList.get(0));
                        } else {
                            doc.put("id", idData);
                        }
                        
                        // 处理文本字段 - 可能是List或单个值
                        if (textData instanceof List) {
                            List<?> textList = (List<?>) textData;
                            doc.put("text", textList.isEmpty() ? "" : textList.get(0));
                        } else {
                            doc.put("text", textData);
                        }
                        
                        // 处理元数据字段 - 可能是List或单个值
                        if (metadataData instanceof List) {
                            List<?> metadataList = (List<?>) metadataData;
                            doc.put("metadata", metadataList.isEmpty() ? "" : metadataList.get(0));
                        } else {
                            doc.put("metadata", metadataData);
                        }
                        
                        // 获取分数 - 从SearchResults中获取
                        float score = 0.0f;
                        if (i < searchResults.getResults().getScoresList().size()) {
                            score = searchResults.getResults().getScoresList().get(i);
                        }
                        doc.put("score", score);
                        
                        // 应用相似度阈值过滤，提高相关性要求
                        // 分数越低表示越相似，根据实际分数分布调整阈值
                        // 默认从 AppConfig.Performance.similarityThreshold 读取（默认 5.0）
                        float similarityThreshold = 5.0f;
                        try {
                            similarityThreshold = appConfig.getPerformance().getSimilarityThreshold();
                        } catch (Exception ignored) {}
                        boolean passedSimilarityFilter = score <= similarityThreshold;
                        
                        // 如果提供了查询文本，进行语义验证
                        boolean passedSemanticFilter = true;
                        if (queryText != null && !queryText.trim().isEmpty()) {
                            String docText = doc.get("text").toString();
                            String metadata = doc.get("metadata").toString();
                            
                            // 改进的语义验证：多重检查
                            passedSemanticFilter = isSemanticallyRelevantImproved(docText, metadata, queryText, score);
                        }
                        
                        if (passedSimilarityFilter) {
                            similarityCandidates.add(doc);
                        }

                        if (passedSimilarityFilter && passedSemanticFilter) {
                            docs.add(doc);
                            log.info("[Milvus] 添加文档: score={} (通过相似度和语义过滤)", score);
                        } else {
                            String reason = !passedSimilarityFilter ? "相似度过滤" : "语义过滤";
                            log.debug("[Milvus] 过滤文档: score={} (未通过{})", score, reason);
                        }
                        
                        // 记录日志以便调试
                        log.debug("[Milvus] Result {}: id={}, score={}, metadata={}", 
                            i, doc.get("id"), doc.get("score"), doc.get("metadata"));
                        
                        // 记录文档信息用于调试
                        String metadata = doc.get("metadata").toString();
                        log.debug("[Milvus] 处理文档: {}", metadata);
                    } catch (Exception e) {
                        log.error("[Milvus] Failed to process result at index {}: {}", i, e.getMessage());
                        // 继续处理下一个结果
                        continue;
                    }
                }
                
                if (docs.isEmpty()) {
                    if (!similarityCandidates.isEmpty()) {
                        // 语义过滤可能过严（不同模型/Metric 下分数分布差异很大）。
                        // 为避免 sources 为空导致前端不显示参考文献，这里回退到“仅相似度过滤”的候选结果。
                        log.warn("[Milvus] No semantic-passed results; fallback to similarity candidates: {}", similarityCandidates.size());
                        docs = similarityCandidates;
                    } else {
                        log.warn("[Milvus] No valid results found after processing");
                        return Collections.emptyList();
                    }
                }
                
                // 后处理：每个文档最多取3个片段，总共限制为10个片段
                List<Map<String, Object>> optimizedDocs = optimizeSearchResults(docs);
                log.info("[Milvus] 优化后结果: {} 个片段", optimizedDocs.size());
                
                return optimizedDocs;
            } catch (Exception e) {
                // 安全地获取异常信息，避免null异常
                String msg = "Unknown error";
                String exceptionType = "Unknown";
                try {
                    msg = e.getMessage() != null ? e.getMessage() : "No message available";
                } catch (Exception msgEx) {
                    log.warn("[Milvus] 获取异常消息失败: {}", msgEx.getClass().getSimpleName());
                    msg = "Exception message unavailable";
                }
                
                try {
                    exceptionType = e.getClass().getSimpleName();
                } catch (Exception typeEx) {
                    exceptionType = "UnknownException";
                }
                
                log.warn("[Milvus] search attempt {}/{} failed: {}", attempt, searchMaxRetry, msg);
                log.warn("[Milvus] 异常类型: {}", exceptionType);
                
                // 安全地记录异常堆栈
                try {
                    log.warn("[Milvus] 异常信息: {}", e.getMessage());
                } catch (Exception stackEx) {
                    log.warn("[Milvus] 无法记录异常堆栈");
                }
                
                if (msg.contains("collection not loaded") && attempt < searchMaxRetry) {
                    try { Thread.sleep(500); } catch (InterruptedException ignored) {} // 减少等待时间
                    continue;
                } else if (attempt == searchMaxRetry) {
                    log.error("[Milvus] search failed after {} attempts: {}", searchMaxRetry, msg);
                    return Collections.emptyList();
                }
            }
        }
        return Collections.emptyList();
    }

    public boolean hasCollection(String collectionName) {
        // 构建租户隔离的集合名
        String tenantCollectionName = buildTenantCollectionName(collectionName);
        try {
            HasCollectionParam hasParam = HasCollectionParam.newBuilder().withCollectionName(tenantCollectionName).build();
            R<Boolean> response = milvusClient.hasCollection(hasParam);
            return response.getData() != null && response.getData();
        } catch (Exception e) {
            log.error("[Milvus] 检查集合是否存在异常: {} -> {} - {}", collectionName, tenantCollectionName, e.getMessage());
            return false;
        }
    }
    
    /**
     * 安全地执行搜索操作
     * @param searchParam 搜索参数
     * @return 搜索响应，如果失败返回null
     */
    private R<SearchResults> performSafeSearch(SearchParam searchParam) {
        try {
            return milvusClient.search(searchParam);
        } catch (Exception e) {
            // 检查是否是null异常
            String errorMsg = e.getMessage();
            if (errorMsg != null) {
                if (errorMsg.contains("Cannot invoke") && 
                    errorMsg.contains("getMessage") && errorMsg.contains("is null")) {
                    log.warn("[Milvus] 检测到内部null异常，尝试使用备用搜索方法");
                    return performAlternativeSearch(searchParam);
                } else if (errorMsg.contains("collection not found") || errorMsg.contains("Collection not found")) {
                    log.warn("[Milvus] 搜索失败：集合不存在 (这是正常的降级情况): {}", errorMsg);
                    return null;
                } else if (errorMsg.contains("index not found") || errorMsg.contains("Index not found")) {
                    log.warn("[Milvus] 搜索失败：索引不存在 (新集合的正常情况): {}", errorMsg);
                    return null;
                }
            }
            log.error("[Milvus] 搜索异常: {}", errorMsg);
            return null;
        }
    }
    
    /**
     * 备用搜索方法
     * @param searchParam 搜索参数
     * @return 搜索响应
     */
    private R<SearchResults> performAlternativeSearch(SearchParam searchParam) {
        try {
            // 尝试重新初始化连接
            log.info("[Milvus] 尝试重新初始化连接");
            milvusClient.close();
            Thread.sleep(1000);
            init();
            
            // 重新尝试搜索
            return milvusClient.search(searchParam);
        } catch (Exception e) {
            log.error("[Milvus] 备用搜索方法也失败: {}", e.getMessage());
            return null;
        }
    }
    
    /**
     * 安全地获取响应消息
     * @param response 响应对象
     * @return 响应消息
     */
    private String getSafeResponseMessage(R<SearchResults> response) {
        try {
            String msg = response.getMessage();
            return msg != null ? msg : "无消息";
        } catch (Exception e) {
            log.warn("[Milvus] 获取响应消息失败: {}", e.getClass().getSimpleName());
            return "无法获取响应消息";
        }
    }
    
    /**
     * 安全地获取响应数据
     * @param response 响应对象
     * @return 响应数据
     */
    private SearchResults getSafeResponseData(R<SearchResults> response) {
        try {
            return response.getData();
        } catch (Exception e) {
            log.warn("[Milvus] 获取响应数据失败: {}", e.getClass().getSimpleName());
            return null;
        }
    }
    
    /**
     * 诊断Milvus连接和集合状态
     * @param collectionName 集合名称
     * @return 诊断信息
     */
    public Map<String, Object> diagnoseCollection(String collectionName) {
        Map<String, Object> diagnosis = new HashMap<>();
        
        try {
            // 检查连接状态
            diagnosis.put("connection", "正常");
            
            // 检查集合是否存在
            boolean exists = hasCollection(collectionName);
            diagnosis.put("collectionExists", exists);
            
            if (exists) {
                // 检查集合是否已加载
                try {
                    milvusClient.loadCollection(LoadCollectionParam.newBuilder()
                        .withCollectionName(collectionName)
                        .build());
                    diagnosis.put("collectionLoaded", true);
                } catch (Exception e) {
                    diagnosis.put("collectionLoaded", false);
                    diagnosis.put("loadError", e.getMessage());
                }
                
                // 获取文档数量
                int count = getCollectionCount(collectionName);
                diagnosis.put("documentCount", count);
                
                // 检查索引状态
                boolean hasIndex = indexExists(collectionName);
                diagnosis.put("hasIndex", hasIndex);
            }
            
        } catch (Exception e) {
            diagnosis.put("connection", "异常");
            diagnosis.put("error", e.getMessage());
        }
        
        return diagnosis;
    }
    
    /**
     * 获取集合中的文档数量
     * @param collectionName 集合名称
     * @return 文档数量，如果出错返回0
     */
    public int getCollectionCount(String collectionName) {
        // 构建租户隔离的集合名
        String tenantCollectionName = buildTenantCollectionName(collectionName);
        try {
            if (!hasCollection(collectionName)) {
                return 0;
            }
            
            // 确保集合已加载
            loadCollection(tenantCollectionName);
            
            // 使用query来获取文档数量 - 修复：id是VarChar类型，不能使用数值比较
            String expr = "id != \"\""; // 查询所有非空id的文档
            List<String> outputFields = Arrays.asList("id"); // 只返回id字段
            
            QueryParam queryParam = QueryParam.newBuilder()
                .withCollectionName(tenantCollectionName)
                .withExpr(expr)
                .withOutFields(outputFields)
                .build();
            
            R<QueryResults> response = milvusClient.query(queryParam);
            if (response.getStatus() == R.Status.Success.getCode()) {
                QueryResultsWrapper wrapper = new QueryResultsWrapper(response.getData());
                return wrapper.getFieldWrapper("id").getFieldData().size();
            } else {
                log.warn("[Milvus] 查询集合文档数量失败: {} - {}", collectionName, response.getMessage());
                return 0;
            }
        } catch (Exception e) {
            String errorMsg = e.getMessage();
            if (errorMsg != null && (errorMsg.contains("collection not found") || errorMsg.contains("Collection not found"))) {
                log.info("[Milvus] 集合不存在，无法获取文档数量: {} (这是正常的)", collectionName);
                return 0;
            }
            log.warn("[Milvus] 获取集合文档数量异常: {} - {}", collectionName, e.getMessage());
            return 0;
        }
    }
    
    /**
     * 刷新集合，确保新插入的数据立即可查询
     * @param collectionName 集合名称
     */
    public void flushCollection(String collectionName) {
        try {
            FlushParam flushParam = FlushParam.newBuilder()
                .withCollectionNames(Arrays.asList(collectionName))
                .build();
            R<FlushResponse> response = milvusClient.flush(flushParam);
            if (response.getStatus() == R.Status.Success.getCode()) {
                log.info("[Milvus] 集合刷新成功: {}", collectionName);
            } else {
                log.warn("[Milvus] 集合刷新失败: {} - {}", collectionName, response.getMessage());
            }
        } catch (Exception e) {
            log.warn("[Milvus] 集合刷新异常: {} - {}", collectionName, e.getMessage());
        } finally {
            // flush 后重置加载状态，下次查询会重新加载
            loadedCollections.remove(collectionName);
        }
    }
    
    /**
     * 加载集合到内存（带缓存，避免重复 load）
     * @param collectionName 集合名称
     */
    private void loadCollection(String collectionName) {
        // 缓存命中：已加载则跳过
        if (loadedCollections.getOrDefault(collectionName, false)) {
            log.debug("[Milvus] loadCollection skipped (cached): {}", collectionName);
            return;
        }

        try {
            // 在尝试 load 前，先检查集合与索引状态，避免触发底层客户端打印 ERROR（例如 index not found）
            if (!hasCollection(collectionName)) {
                log.warn("[Milvus] loadCollection skipped: collection does not exist: {}", collectionName);
                return;
            }

            // 不在此处调用 getCollectionCount()，以避免与 loadCollection() 互相调用产生递归副作用。
            // 仅依赖集合存在性与索引状态进行加载判断。

            // 如果索引尚未建立也跳过 load（load 依赖索引存在），以减少 Milvus 客户端报错
            try {
                if (!indexExists(collectionName)) {
                    log.warn("[Milvus] loadCollection skipped: index not present or not ready for {}", collectionName);
                    return;
                }
            } catch (Exception e) {
                log.debug("[Milvus] indexExists check failed for {}: {}", collectionName, e.getMessage());
            }

            LoadCollectionParam loadParam = LoadCollectionParam.newBuilder()
                .withCollectionName(collectionName)
                .build();
            log.info("[Milvus] loadCollection request: {}", collectionName);
            log.debug("[Milvus] loadParam: {}", loadParam);

            R<RpcStatus> response = milvusClient.loadCollection(loadParam);
            if (response == null) {
                log.warn("[Milvus] loadCollection returned null response for {}", collectionName);
            } else if (response.getStatus() != R.Status.Success.getCode()) {
                String respMsg;
                try {
                    respMsg = response.getMessage();
                } catch (Exception ex) {
                    // 某些 Milvus SDK 版本在 message/exception 为空时 getMessage() 可能抛 NPE
                    respMsg = "";
                }
                if (respMsg == null) respMsg = "";
                log.warn("[Milvus] loadCollection failed: {} - {}", collectionName, respMsg);
                if (respMsg.contains("index not found") || respMsg.contains("Index not found")) {
                    log.warn("[KB_TRACE] loadCollection failed due to missing index: collection={} -> err='{}'", collectionName, respMsg);
                }

                // 尝试获取索引描述，便于定位问题
                try {
                    DescribeIndexParam dip = DescribeIndexParam.newBuilder().withCollectionName(collectionName).build();
                    R<DescribeIndexResponse> indexResp = milvusClient.describeIndex(dip);
                    if (indexResp != null && indexResp.getStatus() == R.Status.Success.getCode()) {
                        log.warn("[Milvus] DescribeIndex for {}: {}", collectionName, indexResp.getData());
                    } else if (indexResp != null) {
                        String idxMsg;
                        try {
                            idxMsg = indexResp.getMessage();
                        } catch (Exception ex) {
                            idxMsg = "";
                        }
                        log.warn("[Milvus] DescribeIndex returned non-success for {}: {}", collectionName,
                            indexResp.getException() != null ? idxMsg : "No exception");
                    }
                } catch (Exception dex) {
                    log.debug("[Milvus] describeIndex exception for {}: {}", collectionName, dex.getMessage());
                }
            } else {
                String respMsg;
                try {
                    respMsg = response.getMessage();
                } catch (Exception ex) {
                    respMsg = "";
                }
                log.debug("[Milvus] loadCollection success: {}, msg: {}", collectionName, respMsg);
                loadedCollections.put(collectionName, Boolean.TRUE);
            }
        } catch (Exception e) {
            // 将异常降级为 WARN，避免噪音级别的 ERROR 日志
            log.warn("[Milvus] 加载集合异常（降级为 WARN）: {} - {}", collectionName, e.getMessage());
            String exMsg = e.getMessage() == null ? "" : e.getMessage();
            if (exMsg.contains("index not found") || exMsg.contains("Index not found") || exMsg.contains("index not found[collection")) {
                log.warn("[KB_TRACE] loadCollection exception due to missing index: collection={} -> err='{}'", collectionName, exMsg);
            }
        }
    }
    
    /**
     * 手动创建索引（公共方法）
     * @param collectionName 集合名称
     * @return 是否成功
     */
    public boolean createIndex(String collectionName) {
        try {
            String tenantCollection = buildTenantCollectionName(collectionName);
            createIndexSync(tenantCollection);
            return indexExists(tenantCollection);
        } catch (Exception e) {
            log.error("[Milvus] 手动创建索引失败: {}", e.getMessage());
            return false;
        }
    }
    
    /**
     * 检查索引是否存在（公共方法）
     * @param collectionName 集合名称
     * @return 是否存在
     */
    public boolean hasIndex(String collectionName) {
        String tenantCollection = buildTenantCollectionName(collectionName);
        return indexExists(tenantCollection);
    }
    
    /**
     * 获取索引创建状态
     * @param collectionName 集合名称
     * @return 是否正在创建
     */
    public boolean isIndexCreating(String collectionName) {
        String tenantCollection = buildTenantCollectionName(collectionName);
        return INDEX_CREATING.contains(tenantCollection);
    }
    
    /**
     * 获取索引重试次数
     * @param collectionName 集合名称
     * @return 重试次数
     */
    public int getIndexRetryCount(String collectionName) {
        return INDEX_RETRY_COUNT.getOrDefault(collectionName, 0);
    }
    
    /**
     * 根据源文件路径删除文档
     * @param collectionName 集合名称
     * @param sourcePath 源文件路径
     * @return 是否成功
*/
    public boolean deleteDocumentsBySource(String collectionName, String sourcePath) {
        // 构建租户隔离的集合名
        String tenantCollectionName = buildTenantCollectionName(collectionName);
        log.info("[Milvus] 开始删除文档（租户隔离），集合: {} -> {}, 源文件: {}", collectionName, tenantCollectionName, sourcePath);
        
        try {
            // 确保集合存在
            if (!hasCollection(collectionName)) {
                log.warn("[Milvus] 集合 {} -> {} 不存在，无法删除文档", collectionName, tenantCollectionName);
                return false;
            }
            
            // 强制 load collection（通过封装方法，这里会记录更多信息）
            log.debug("[Milvus] hasCollection check result: {}", hasCollection(collectionName));
            try {
                DescribeIndexParam dip = DescribeIndexParam.newBuilder().withCollectionName(tenantCollectionName).build();
                R<DescribeIndexResponse> dr = milvusClient.describeIndex(dip);
                if (dr != null) {
                    log.debug("[Milvus] describeIndex tenantCollection={} status={} message={} data={}", tenantCollectionName, dr.getStatus(), dr.getMessage(), dr.getData());
                } else {
                    log.debug("[Milvus] describeIndex returned null for {}", tenantCollectionName);
                }
            } catch (Exception e) {
                log.debug("[Milvus] describeIndex for {} failed: {}", tenantCollectionName, e.getMessage());
            }
            loadCollection(tenantCollectionName);
            
            // 查询包含该源文件的所有文档ID
            // 尝试多种匹配模式，因为metadata格式可能不同
            List<String> expressions = new ArrayList<>();
            
            // 模式1：标准JSON格式
            expressions.add(String.format("metadata like '%%\"source\":\"%s%%'", sourcePath.replace("\\", "\\\\")));
            
            // 模式2：简单字符串匹配
            expressions.add(String.format("metadata like '%%%s%%'", sourcePath.replace("\\", "\\\\")));
            
            // 模式3：只匹配文件名（去掉路径）
            String fileName = sourcePath;
            if (sourcePath.contains("/")) {
                fileName = sourcePath.substring(sourcePath.lastIndexOf("/") + 1);
            }
            expressions.add(String.format("metadata like '%%%s%%'", fileName));
            
            log.info("[Milvus] 尝试多种查询表达式: {}", expressions);
            
            List<QueryResultsWrapper.RowRecord> allRowRecords = new ArrayList<>();
            String successfulExpr = null;
            
            for (String expr : expressions) {
                QueryParam queryParam = QueryParam.newBuilder()
                    .withCollectionName(tenantCollectionName)
                    .withExpr(expr)
                    .withOutFields(Arrays.asList("id"))
                    .build();
                
                R<QueryResults> queryResponse = milvusClient.query(queryParam);
                QueryResults queryResults = queryResponse.getData();
                
                if (queryResults != null) {
                    List<QueryResultsWrapper.RowRecord> rowRecords = new QueryResultsWrapper(queryResults).getRowRecords();
                    if (!rowRecords.isEmpty()) {
                        allRowRecords.addAll(rowRecords);
                        successfulExpr = expr;
                        log.info("[Milvus] 表达式 '{}' 找到 {} 个匹配文档", expr, rowRecords.size());
                        break; // 找到匹配就停止
                    }
                }
            }
            
            if (allRowRecords.isEmpty()) {
                log.info("[Milvus] 所有表达式都未找到匹配的文档，源文件: {}", sourcePath);
                
                // 调试：查看集合中实际存储的文档
                try {
                    QueryParam debugParam = QueryParam.newBuilder()
                        .withCollectionName(tenantCollectionName)
                        .withExpr("id != \"\"")
                        .withOutFields(Arrays.asList("id", "metadata"))
                        .withLimit(5L)
                        .build();
                    
                    R<QueryResults> debugResponse = milvusClient.query(debugParam);
                    if (debugResponse.getData() != null) {
                        List<QueryResultsWrapper.RowRecord> debugRecords = new QueryResultsWrapper(debugResponse.getData()).getRowRecords();
                        log.info("[Milvus] 调试：集合 {} -> {} 中有 {} 个文档", collectionName, tenantCollectionName, debugRecords.size());
                        for (int i = 0; i < Math.min(3, (int)debugRecords.size()); i++) {
                            Object metadata = debugRecords.get(i).get("metadata");
                            log.info("[Milvus] 调试：文档 {} 的metadata: {}", i, metadata);
                        }
                    }
                } catch (Exception e) {
                    log.warn("[Milvus] 调试查询失败: {}", e.getMessage());
                }
                
                return false; // 没有找到匹配的文档，删除失败
            }
            
            log.info("[Milvus] 使用表达式 '{}' 找到 {} 个匹配文档", successfulExpr, allRowRecords.size());
            
            // 先查询所有文档的metadata，看看实际存储的内容
            QueryParam debugParam = QueryParam.newBuilder()
                .withCollectionName(tenantCollectionName)
                .withExpr("id != \"\"")
                .withOutFields(Arrays.asList("id", "metadata"))
                .withLimit(10L)
                .build();
            
            R<QueryResults> debugResponse = milvusClient.query(debugParam);
            if (debugResponse.getData() != null) {
                List<QueryResultsWrapper.RowRecord> debugRecords = new QueryResultsWrapper(debugResponse.getData()).getRowRecords();
                log.info("[Milvus] 调试：集合 {} 中有 {} 个文档", collectionName, debugRecords.size());
                for (int i = 0; i < Math.min(3, (int)debugRecords.size()); i++) {
                    Object metadata = debugRecords.get(i).get("metadata");
                    log.info("[Milvus] 调试：文档 {} 的metadata: {}", i, metadata);
                }
            }
            
            List<String> idsToDelete = new ArrayList<>();
            for (QueryResultsWrapper.RowRecord rowRecord : allRowRecords) {
                // 直接从行记录获取字段值
                Object idData = rowRecord.get("id");
                if (idData instanceof List) {
                    List<?> idList = (List<?>) idData;
                    if (!idList.isEmpty()) {
                        idsToDelete.add("'" + idList.get(0).toString() + "'");
                    }
                } else if (idData != null) {
                    idsToDelete.add("'" + idData.toString() + "'");
                }
            }
            
            log.info("[Milvus] 找到 {} 个文档需要删除", idsToDelete.size());
            
            if (idsToDelete.isEmpty()) {
                return true;
            }
            
            // 删除文档 - 使用正确的表达式格式，ID是字符串类型
            String deleteExpr = "id in [" + String.join(",", idsToDelete) + "]";
            log.info("[Milvus] 删除表达式: {}", deleteExpr);

            DeleteParam deleteParam = DeleteParam.newBuilder()
                .withCollectionName(tenantCollectionName)
                .withExpr(deleteExpr)
                .build();
            
            R<MutationResult> deleteResponse = milvusClient.delete(deleteParam);
            MutationResult deleteResult = deleteResponse.getData();
            if (deleteResult != null) {
                log.info("[Milvus] 成功删除 {} 个文档", deleteResult.getDeleteCnt());
                // 验证删除是否真的生效
                if (deleteResult.getDeleteCnt() > 0) {
                    log.info("[Milvus] 删除操作确实删除了 {} 个文档", deleteResult.getDeleteCnt());
                } else {
                    log.warn("[Milvus] 删除操作返回成功但实际删除数量为0");
                }
                return true;
            } else {
                log.warn("[Milvus] 删除文档失败，响应为空 - 可能是临时网络问题");
                return false;
            }
            
        } catch (Exception e) {
            log.error("[Milvus] 删除文档异常: {}", e.getMessage());
            return false;
        }
    }
    
    /**
     * 查询集合中的所有文档
     * @param collectionName 集合名称
     * @return 所有文档列表
     */
    public List<Map<String, Object>> queryAllDocuments(String collectionName) {
        // 构建租户隔离的集合名
        String tenantCollectionName = buildTenantCollectionName(collectionName);
        log.info("[Milvus] 查询集合 {} -> {} 中的所有文档", collectionName, tenantCollectionName);
        
        try {
            // 确保集合存在
            if (!hasCollection(collectionName)) {
                log.warn("[Milvus] 集合 {} -> {} 不存在", collectionName, tenantCollectionName);
                return Collections.emptyList();
            }
            
            // 强制 load collection
            milvusClient.loadCollection(LoadCollectionParam.newBuilder()
                .withCollectionName(tenantCollectionName)
                .build());
            
            // 查询所有文档
            QueryParam queryParam = QueryParam.newBuilder()
                .withCollectionName(tenantCollectionName)
                .withExpr("id != \"\"") // 查询所有文档
                .withOutFields(Arrays.asList("id", "text", "metadata", "embedding"))
                .build();
            
            R<QueryResults> queryResponse = milvusClient.query(queryParam);
            QueryResults queryResults = queryResponse.getData();
            
            if (queryResults == null) {
                log.info("[Milvus] 集合 {} -> {} 中没有文档", collectionName, tenantCollectionName);
                return Collections.emptyList();
            }

            // 使用新的API获取行记录
            List<QueryResultsWrapper.RowRecord> rowRecords = new QueryResultsWrapper(queryResults).getRowRecords();
            if (rowRecords.isEmpty()) {
                log.info("[Milvus] 集合 {} 中没有文档", collectionName);
                return Collections.emptyList();
            }

            List<Map<String, Object>> docs = new ArrayList<>();
            for (QueryResultsWrapper.RowRecord rowRecord : rowRecords) {
                try {
                    Map<String, Object> doc = new HashMap<>();
                    
                    // 直接从行记录获取字段值
                    Object idData = rowRecord.get("id");
                    Object textData = rowRecord.get("text");
                    Object metadataData = rowRecord.get("metadata");
                    
                    // 处理ID字段
                    if (idData instanceof List) {
                        List<?> idList = (List<?>) idData;
                        doc.put("id", idList.isEmpty() ? "" : idList.get(0));
                    } else {
                        doc.put("id", idData);
                    }
                    
                    // 处理文本字段
                    if (textData instanceof List) {
                        List<?> textList = (List<?>) textData;
                        doc.put("text", textList.isEmpty() ? "" : textList.get(0));
                    } else {
                        doc.put("text", textData);
                    }
                    
                    // 处理元数据字段
                    if (metadataData instanceof List) {
                        List<?> metadataList = (List<?>) metadataData;
                        doc.put("metadata", metadataList.isEmpty() ? "" : metadataList.get(0));
                    } else {
                        doc.put("metadata", metadataData);
                    }
                    
                    // 处理embedding字段
                    Object embeddingData = rowRecord.get("embedding");
                    if (embeddingData instanceof List) {
                        doc.put("embedding", embeddingData);
                    } else if (embeddingData instanceof float[]) {
                        // 如果是float数组，转换为List<Float>
                        float[] floatArray = (float[]) embeddingData;
                        List<Float> embeddingList = new ArrayList<>();
                        for (float f : floatArray) {
                            embeddingList.add(f);
                        }
                        doc.put("embedding", embeddingList);
                    } else {
                        log.warn("[Milvus] 未知的embedding数据类型: {}", embeddingData != null ? embeddingData.getClass() : "null");
                        doc.put("embedding", new ArrayList<Float>()); // 提供空列表作为默认值
                    }
                    
                    docs.add(doc);
                    
                } catch (Exception e) {
                    log.error("[Milvus] Failed to process document: {}", e.getMessage());
                    continue;
                }
            }
            
            log.info("[Milvus] 成功查询到 {} 个文档", docs.size());
            return docs;
            
        } catch (Exception e) {
            log.error("[Milvus] 查询所有文档异常: {}", e.getMessage());
            return Collections.emptyList();
        }
    }
    
    /**
     * 清理重复的txt文件文档
     * @param collectionName 集合名称
     * @return 是否成功
     */
    public boolean cleanupDuplicateTxtFiles(String collectionName) {
        // 构建租户隔离的集合名
        String tenantCollectionName = buildTenantCollectionName(collectionName);
        log.info("[Milvus] 开始清理重复的txt文件文档: {} -> {}", collectionName, tenantCollectionName);
        
        try {
            // 确保集合存在
            if (!hasCollection(collectionName)) {
                log.info("[Milvus] 集合 {} -> {} 不存在 (正常情况)", collectionName, tenantCollectionName);
                return true;
            }
            
            // 强制 load collection
            try {
                milvusClient.loadCollection(LoadCollectionParam.newBuilder()
                    .withCollectionName(tenantCollectionName)
                    .build());
                log.info("[Milvus] 集合加载成功: {}", tenantCollectionName);
            } catch (Exception loadEx) {
                String msg = loadEx.getMessage() == null ? "" : loadEx.getMessage();
                if (msg.contains("collection not found") || msg.contains("Collection not found")) {
                    log.info("[Milvus] 集合不存在 (正常情况): {}", tenantCollectionName);
                    return true;
                } else {
                    log.warn("[Milvus] 加载集合失败，但继续尝试清理: {}", loadEx.getMessage());
                }
            }
            
            // 查询所有包含重复.txt的文件（更宽松的匹配，包含所有重复的.txt）
            String expr = "metadata like '%.txt.txt%' or metadata like '%.txt.txt.txt%' or metadata like '%.txt.txt.txt.txt%'";
            log.info("[Milvus] 清理查询表达式: {}", expr);
            
            QueryParam queryParam = QueryParam.newBuilder()
                .withCollectionName(tenantCollectionName)
                .withExpr(expr)
                .withOutFields(Arrays.asList("id"))
                .build();
            
            R<QueryResults> queryResponse = milvusClient.query(queryParam);
            QueryResults queryResults = queryResponse.getData();
            
            if (queryResults == null) {
                log.info("[Milvus] 未找到重复的txt文件");
                return true;
            }

            List<QueryResultsWrapper.RowRecord> rowRecords = new QueryResultsWrapper(queryResults).getRowRecords();
            if (rowRecords.isEmpty()) {
                log.info("[Milvus] 未找到重复的txt文件");
                return true;
            }

            List<String> idsToDelete = new ArrayList<>();
            for (QueryResultsWrapper.RowRecord rowRecord : rowRecords) {
                Object idData = rowRecord.get("id");
                if (idData instanceof List) {
                    List<?> idList = (List<?>) idData;
                    if (!idList.isEmpty()) {
                        idsToDelete.add("'" + idList.get(0).toString() + "'");
                    }
                } else if (idData != null) {
                    idsToDelete.add("'" + idData.toString() + "'");
                }
            }
            
            log.info("[Milvus] 找到 {} 个重复txt文件需要删除", idsToDelete.size());
            
            if (idsToDelete.isEmpty()) {
                return true;
            }
            
            // 删除文档 - ID是字符串类型
            String deleteExpr = "id in [" + String.join(",", idsToDelete) + "]";
            log.info("[Milvus] 删除重复txt文件表达式: {}", deleteExpr);
            
            DeleteParam deleteParam = DeleteParam.newBuilder()
                .withCollectionName(tenantCollectionName)
                .withExpr(deleteExpr)
                .build();
            
            R<MutationResult> deleteResponse = milvusClient.delete(deleteParam);
            MutationResult deleteResult = deleteResponse.getData();
            
            if (deleteResult != null) {
                log.info("[Milvus] 成功删除 {} 个重复txt文件", deleteResult.getDeleteCnt());
                return true;
            } else {
                log.error("[Milvus] 删除重复txt文件失败，响应为空");
                return false;
            }
            
        } catch (Exception e) {
            log.error("[Milvus] 清理重复txt文件异常: {}", e.getMessage());
            return false;
        }
    }
    
    /**
     * 重命名集合
     * @param oldCollectionName 旧集合名称
     * @param newCollectionName 新集合名称
     * @return 是否成功
     */
    public boolean renameCollection(String oldCollectionName, String newCollectionName) {
        log.info("[Milvus][rename_debug] 开始重命名集合: {} -> {}", oldCollectionName, newCollectionName);

        try {
            // 检查旧集合是否存在
            boolean oldExists = hasCollection(oldCollectionName);
            log.info("[Milvus][rename_debug] 检查旧集合存在性: {} -> {}", oldCollectionName, oldExists);
            if (!oldExists) {
                log.warn("[Milvus][rename_debug] 旧集合 {} 不存在，无法重命名", oldCollectionName);
                return false;
            }

            // 检查新集合是否已存在
            boolean newExists = hasCollection(newCollectionName);
            log.info("[Milvus][rename_debug] 检查目标集合存在性: {} -> {}", newCollectionName, newExists);
            if (newExists) {
                log.warn("[Milvus][rename_debug] 新集合 {} 已存在，无法重命名", newCollectionName);
                return false;
            }


            // 创建新集合（先创建新集合与索引，保证重命名后目标集合存在）
            log.info("[Milvus][rename_debug] 尝试创建新集合: {}", newCollectionName);
            try {
                createCollection(newCollectionName);
                log.info("[Milvus][rename_debug] 新集合 {} 创建成功", newCollectionName);
            } catch (Exception ce) {
                log.error("[Milvus][rename_debug] 新集合 {} 创建失败: {}", newCollectionName, ce.getMessage());
                return false;
            }

            // 在新集合上尝试创建索引（若失败继续，但记录日志）
            try {
                log.info("[Milvus][rename_debug] 尝试在新集合上创建索引: {}", newCollectionName);
                boolean indexCreated = createIndex(newCollectionName);
                if (indexCreated) {
                    log.info("[Milvus][rename_debug] 新集合 {} 的索引创建成功", newCollectionName);
                } else {
                    log.warn("[Milvus][rename_debug] 新集合 {} 的索引未创建或未完成", newCollectionName);
                }
            } catch (Exception ie) {
                log.error("[Milvus][rename_debug] 在新集合 {} 创建索引失败: {}", newCollectionName, ie.getMessage());
            }

            // 获取旧集合的所有数据
            List<Map<String, Object>> allDocuments = queryAllDocuments(oldCollectionName);
            log.info("[Milvus][rename_debug] 获取到旧集合 {} 的文档数量: {}", oldCollectionName, allDocuments.size());

            if (allDocuments.isEmpty()) {
                log.info("[Milvus][rename_debug] 旧集合 {} 为空，已创建目标集合 {}，直接删除旧集合（无需迁移）", oldCollectionName, newCollectionName);
                boolean del = deleteCollection(oldCollectionName);
                if (del) log.info("[Milvus][rename_debug] 旧集合 {} 删除成功", oldCollectionName);
                else log.error("[Milvus][rename_debug] 删除旧集合 {} 失败", oldCollectionName);
                return del;
            }

            // 准备并执行数据迁移
            log.info("[Milvus][rename_debug] 开始将数据从 {} 迁移到 {}", oldCollectionName, newCollectionName);
            List<String> ids = new ArrayList<>();
            List<List<Float>> embeddings = new ArrayList<>();
            List<String> texts = new ArrayList<>();
            List<String> metadatas = new ArrayList<>();

            for (Map<String, Object> doc : allDocuments) {
                try {
                    ids.add((String) doc.get("id"));
                    Object embeddingObj = doc.get("embedding");
                    if (embeddingObj instanceof List) {
                        embeddings.add((List<Float>) embeddingObj);
                    } else {
                        log.warn("[Milvus][rename_debug] 文档 {} 的 embedding 字段类型异常: {}", doc.get("id"), embeddingObj != null ? embeddingObj.getClass() : "null");
                        embeddings.add(new ArrayList<>());
                    }
                    texts.add((String) doc.get("text"));
                    metadatas.add((String) doc.get("metadata"));
                } catch (Exception e) {
                    log.error("[Milvus][rename_debug] 处理文档以迁移时出错: {} - {}", doc.get("id"), e.getMessage());
                }
            }

            try {
                addDocuments(newCollectionName, ids, embeddings, texts, metadatas);
                log.info("[Milvus][rename_debug] 数据迁移到新集合 {} 成功, 迁移文档数: {}", newCollectionName, ids.size());
            } catch (Exception me) {
                log.error("[Milvus][rename_debug] 数据迁移到新集合 {} 失败: {}", newCollectionName, me.getMessage());
                return false;
            }

            // 删除旧集合
            try {
                boolean deleted = deleteCollection(oldCollectionName);
                if (deleted) {
                    log.info("[Milvus][rename_debug] 旧集合 {} 删除成功", oldCollectionName);
                } else {
                    log.error("[Milvus][rename_debug] 删除旧集合 {} 失败", oldCollectionName);
                    return false;
                }
            } catch (Exception de) {
                log.error("[Milvus][rename_debug] 删除旧集合 {} 异常: {}", oldCollectionName, de.getMessage());
                return false;
            }

            // 旧索引删除：删除集合时索引随之删除，记录日志
            log.info("[Milvus][rename_debug] 旧集合 {} 的索引及资源应已随集合删除（若删除成功）", oldCollectionName);

            log.info("[Milvus][rename_debug] 集合重命名并迁移完成: {} -> {}", oldCollectionName, newCollectionName);
            return true;

        } catch (Exception e) {
            log.error("[Milvus][rename_debug] 重命名集合异常: {} -> {} - {}", oldCollectionName, newCollectionName, e.getMessage());
            return false;
        }
    }
    
    /**
     * 删除整个集合
     * @param collectionName 集合名称
     * @return 是否成功
     */
    public boolean deleteCollection(String collectionName) {
        // 使用租户隔离的物理集合名执行删除，避免传入逻辑名导致误删或假成功的日志
        String tenantCollectionName = buildTenantCollectionName(collectionName);
        log.info("[Milvus] 开始删除集合: {} -> {}", collectionName, tenantCollectionName);

        try {
            // 检查集合是否存在（使用租户隔离名）
            if (!hasCollection(collectionName)) {
                log.warn("[Milvus] 集合 {} ({}) 不存在，无需删除", collectionName, tenantCollectionName);
                return true;
            }

            // 如果存在索引，先删除索引并等待索引被确认移除（同步删除索引）
            try {
                if (indexExists(tenantCollectionName)) {
                    log.info("[Milvus][delete_sync] 集合 {} 存在索引，尝试先删除索引", tenantCollectionName);
                    try {
                        // Milvus 限制：collection 处于 loaded 状态时不能 drop index，需要先 release。
                        try {
                            io.milvus.param.collection.ReleaseCollectionParam releaseParam = io.milvus.param.collection.ReleaseCollectionParam
                                .newBuilder()
                                .withCollectionName(tenantCollectionName)
                                .build();
                            R<RpcStatus> releaseResp = milvusClient.releaseCollection(releaseParam);
                            if (releaseResp != null && releaseResp.getStatus() == R.Status.Success.getCode()) {
                                log.info("[Milvus][delete_sync] 已释放集合(若此前已加载): {}", tenantCollectionName);
                            } else {
                                String rmsg = releaseResp == null ? "null response" : releaseResp.getMessage();
                                log.debug("[Milvus][delete_sync] releaseCollection returned non-success (ignored): {} - {}", tenantCollectionName, rmsg);
                            }
                        } catch (Exception re) {
                            log.debug("[Milvus][delete_sync] releaseCollection failed (ignored): {} - {}", tenantCollectionName, re.getMessage());
                        }

                        io.milvus.param.index.DropIndexParam dropIndexParam = io.milvus.param.index.DropIndexParam.newBuilder()
                            .withCollectionName(tenantCollectionName)
                            .withIndexName("embedding")
                            .build();
                        R<RpcStatus> dropIndexResp = milvusClient.dropIndex(dropIndexParam);
                        if (dropIndexResp == null || dropIndexResp.getStatus() != R.Status.Success.getCode()) {
                            String msg = dropIndexResp == null ? "null response" : dropIndexResp.getMessage();
                            // 常见失败：collection is loaded。此时再 release 一次并重试一次 drop index。
                            if (msg != null && msg.contains("collection is loaded")) {
                                log.warn("[Milvus][delete_sync] dropIndex 失败(集合已加载)，尝试 release 后重试: {} - {}", tenantCollectionName, msg);
                                try {
                                    io.milvus.param.collection.ReleaseCollectionParam releaseParam2 = io.milvus.param.collection.ReleaseCollectionParam
                                        .newBuilder()
                                        .withCollectionName(tenantCollectionName)
                                        .build();
                                    milvusClient.releaseCollection(releaseParam2);
                                } catch (Exception ignore) {
                                }

                                try {
                                    R<RpcStatus> retryResp = milvusClient.dropIndex(dropIndexParam);
                                    if (retryResp == null || retryResp.getStatus() != R.Status.Success.getCode()) {
                                        String retryMsg = retryResp == null ? "null response" : retryResp.getMessage();
                                        log.warn("[Milvus][delete_sync] dropIndex 重试仍失败，继续尝试删除集合: {} - {}", tenantCollectionName, retryMsg);
                                    }
                                } catch (Exception retryEx) {
                                    log.warn("[Milvus][delete_sync] dropIndex 重试异常，继续尝试删除集合: {} - {}", tenantCollectionName, retryEx.getMessage());
                                }
                            } else {
                                log.warn("[Milvus][delete_sync] 删除索引响应非成功，继续尝试删除集合: {} - {}", tenantCollectionName, msg);
                            }
                        }

                        // 等待索引被移除（最多等待30次，每次等待300ms）
                        boolean indexRemoved = false;
                        for (int i = 0; i < 30; i++) {
                            try {
                                DescribeIndexParam dip = DescribeIndexParam.newBuilder().withCollectionName(tenantCollectionName).build();
                                R<DescribeIndexResponse> dr = milvusClient.describeIndex(dip);
                                if (dr == null) {
                                    indexRemoved = true;
                                    break;
                                }
                                String msg = dr.getMessage() == null ? "" : dr.getMessage();
                                if (dr.getStatus() != R.Status.Success.getCode() && msg.contains("index not found")) {
                                    indexRemoved = true;
                                    break;
                                }
                            } catch (Exception ex) {
                                // 如果 describeIndex 抛出 index not found 异常，也视为已删除
                                String em = ex.getMessage() == null ? "" : ex.getMessage();
                                if (em.contains("index not found") || em.contains("Index not found")) {
                                    indexRemoved = true;
                                    break;
                                }
                            }
                            try {
                                Thread.sleep(300);
                            } catch (InterruptedException ie) {
                                Thread.currentThread().interrupt();
                                break;
                            }
                        }

                        if (!indexRemoved) {
                            log.error("[Milvus][delete_sync] 等待索引删除超时: {}", tenantCollectionName);
                            return false;
                        }
                        log.info("[Milvus][delete_sync] 索引已确认删除: {}", tenantCollectionName);
                    } catch (Exception ie) {
                        log.error("[Milvus][delete_sync] 删除索引异常: {} - {}", tenantCollectionName, ie.getMessage());
                        return false;
                    }
                }
            } catch (Exception eCheck) {
                log.warn("[Milvus][delete_sync] 检查索引存在性时异常，继续尝试删除集合: {} - {}", tenantCollectionName, eCheck.getMessage());
            }

            // 删除集合（传入物理集合名）
            DropCollectionParam dropParam = DropCollectionParam.newBuilder()
                .withCollectionName(tenantCollectionName)
                .build();

            R<RpcStatus> resp = milvusClient.dropCollection(dropParam);
            if (resp != null && resp.getStatus() == R.Status.Success.getCode()) {
                log.info("[Milvus] 成功删除集合: {} -> {}", collectionName, tenantCollectionName);
                return true;
            } else {
                String msg = resp == null ? "null response" : resp.getMessage();
                log.warn("[Milvus] 删除集合响应非成功: {} -> {} - {}", collectionName, tenantCollectionName, msg);
                return false;
            }
        } catch (Exception e) {
            log.error("[Milvus] 删除集合异常: {} -> {} - {}", collectionName, tenantCollectionName, e.getMessage());
            return false;
        }
    }
    
    /**
     * 删除集合中的所有文档
     * @param collectionName 集合名称
     * @return 是否成功
     */
    public boolean deleteAllDocuments(String collectionName) {
        log.info("[Milvus] 开始删除集合 {} 中的所有文档", collectionName);
        
        try {
            // 确保集合存在
            if (!hasCollection(collectionName)) {
                log.warn("[Milvus] 集合 {} 不存在", collectionName);
                return true; // 集合不存在也算成功
            }
            
            // 强制 load collection
            milvusClient.loadCollection(LoadCollectionParam.newBuilder()
                .withCollectionName(collectionName)
                .build());
            
            // 删除所有文档
            DeleteParam deleteParam = DeleteParam.newBuilder()
                .withCollectionName(collectionName)
                .withExpr("id != \"\"") // 删除所有文档
                .build();
            
            R<MutationResult> deleteResponse = milvusClient.delete(deleteParam);
            MutationResult deleteResult = deleteResponse.getData();
            
            if (deleteResult != null) {
                log.info("[Milvus] 成功删除 {} 个文档", deleteResult.getDeleteCnt());
                return true;
            } else {
                log.warn("[Milvus] 删除文档失败，响应为空 - 可能是临时网络问题");
                return false;
            }
            
        } catch (Exception e) {
            log.error("[Milvus] 删除所有文档异常: {}", e.getMessage());
            return false;
        }
    }
    
    /**
     * 优化搜索结果：每个文档最多取3个片段，总共限制为10个片段
     * @param docs 原始搜索结果
     * @return 优化后的结果
     */
    private List<Map<String, Object>> optimizeSearchResults(List<Map<String, Object>> docs) {
        if (docs == null || docs.isEmpty()) {
            return Collections.emptyList();
        }
        
        // 按文档源分组
        Map<String, List<Map<String, Object>>> docsBySource = new HashMap<>();
        
        for (Map<String, Object> doc : docs) {
            String metadata = doc.get("metadata").toString();
            // 从metadata中提取文件名
            String source = extractSourceFromMetadata(metadata);
            
            docsBySource.computeIfAbsent(source, k -> new ArrayList<>()).add(doc);
        }
        
        log.info("[Milvus] 搜索结果按文档分组: {} 个文档", docsBySource.size());
        
        // 对每个文档的片段按分数排序（分数越低越相关）
        for (List<Map<String, Object>> docFragments : docsBySource.values()) {
            docFragments.sort((a, b) -> {
                float scoreA = (Float) a.get("score");
                float scoreB = (Float) b.get("score");
                return Float.compare(scoreA, scoreB); // 升序排列，分数低的在前
            });
        }
        
        // 构建最终结果：每个文档最多取3个片段，总共限制为10个片段
        List<Map<String, Object>> optimizedDocs = new ArrayList<>();
        int maxDocsPerSource = 3; // 每个文档最多3个片段
        int maxTotalDocs = 10; // 总共最多10个片段
        
        // 按文档的相关性排序（取每个文档的最佳片段分数）
        List<Map.Entry<String, List<Map<String, Object>>>> sortedSources = new ArrayList<>(docsBySource.entrySet());
        sortedSources.sort((a, b) -> {
            float bestScoreA = a.getValue().isEmpty() ? Float.MAX_VALUE : (Float) a.getValue().get(0).get("score");
            float bestScoreB = b.getValue().isEmpty() ? Float.MAX_VALUE : (Float) b.getValue().get(0).get("score");
            return Float.compare(bestScoreA, bestScoreB); // 升序排列，分数低的在前
        });
        
        // 按优先级选择片段
        for (Map.Entry<String, List<Map<String, Object>>> entry : sortedSources) {
            if (optimizedDocs.size() >= maxTotalDocs) {
                break;
            }
            
            String source = entry.getKey();
            List<Map<String, Object>> fragments = entry.getValue();
            
            log.info("[Milvus] 处理文档: {} ({} 个片段)", source, fragments.size());
            
            // 取该文档的前3个最佳片段
            int fragmentsToTake = Math.min(maxDocsPerSource, fragments.size());
            for (int i = 0; i < fragmentsToTake && optimizedDocs.size() < maxTotalDocs; i++) {
                Map<String, Object> fragment = fragments.get(i);
                optimizedDocs.add(fragment);
                log.info("[Milvus] 选择片段: {} (score={})", source, fragment.get("score"));
            }
        }
        
        log.info("[Milvus] 优化完成: {} 个文档 -> {} 个片段", docs.size(), optimizedDocs.size());
        return optimizedDocs;
    }
    
    /**
     * 改进的语义验证：多重检查机制
     * @param docText 文档文本
     * @param metadata 文档元数据
     * @param queryText 查询文本
     * @param score 相似度分数
     * @return 是否相关
     */
    private boolean isSemanticallyRelevantImproved(String docText, String metadata, String queryText, float score) {
        if (docText == null || queryText == null) {
            return false;
        }
        
        String lowerDocText = docText.toLowerCase();
        String lowerMetadata = metadata.toLowerCase();
        String lowerQueryText = queryText.toLowerCase();
        
        // 1. 检查metadata中是否包含查询关键词
        if (lowerMetadata.contains(lowerQueryText)) {
            log.debug("[Milvus] 语义验证通过：metadata包含查询文本");
            return true;
        }
        
        // 2. 提取查询关键词
        List<String> queryKeywords = extractQueryKeywords(lowerQueryText);
        log.debug("[Milvus] 提取的查询关键词: {}", queryKeywords);
        
        // 3. 检查文档文本中是否包含关键词
        int matchedKeywords = 0;
        for (String keyword : queryKeywords) {
            if (lowerDocText.contains(keyword) || lowerMetadata.contains(keyword)) {
                matchedKeywords++;
            }
        }
        
        // 4. 多重判断条件
        boolean keywordMatch = matchedKeywords > 0;
        boolean highSimilarity = score < 400.0f; // 高相似度
        boolean reasonableSimilarity = score < 500.0f; // 合理相似度
        
        // 5. 综合判断
        boolean isRelevant = false;
        String reason = "";
        
        if (keywordMatch && highSimilarity) {
            isRelevant = true;
            reason = "关键词匹配且高相似度";
        } else if (keywordMatch && reasonableSimilarity) {
            isRelevant = true;
            reason = "关键词匹配且合理相似度";
        } else if (highSimilarity && queryKeywords.size() <= 2) {
            isRelevant = true;
            reason = "高相似度且查询简单";
        } else if (reasonableSimilarity && matchedKeywords >= queryKeywords.size() * 0.5) {
            isRelevant = true;
            reason = "合理相似度且关键词匹配率>=50%";
        }
        
        log.debug("[Milvus] 语义验证: 查询='{}', 关键词={}, 匹配={}/{}, 分数={}, 结果={} ({})", 
                queryText, queryKeywords, matchedKeywords, queryKeywords.size(), score, isRelevant, reason);
        
        return isRelevant;
    }
    
    /**
     * 提取查询关键词
     * @param queryText 查询文本
     * @return 关键词列表
     */
    private List<String> extractQueryKeywords(String queryText) {
        System.out.println("[DEBUG] extractQueryKeywords called with: " + queryText);
        log.debug("[Milvus] 开始提取查询关键词: {}", queryText);
        List<String> keywords = new ArrayList<>();
        
        // 1. 先提取关键的建筑行业关键词
        String[] keyTerms = {"标准", "卫生", "要求", "规范", "建筑", "设计", "施工", "验收", "质量", "安全", "消防", "环保"};
        for (String term : keyTerms) {
            if (queryText.contains(term)) {
                keywords.add(term);
                System.out.println("[DEBUG] Found keyword: " + term);
                log.debug("[Milvus] 找到关键词: {}", term);
            }
        }
        
        System.out.println("[DEBUG] After key terms extraction, keywords: " + keywords);

        // 2. 移除停用词和标点符号，但保留关键术语
        String processedQuery = queryText.replaceAll("[的|是|有|哪些|什么|如何|怎么|为什么|在|和|与|或|但|然而|因此|所以|请|为|我|查询|相关|内容]", "")
                .replaceAll("[、，,。！？!?\\s]", " ");

        // 3. 按空格分割并过滤
        String[] parts = processedQuery.trim().split("\\s+");
        for (String part : parts) {
            part = part.trim();
            if (part.length() >= 2 && !keywords.contains(part)) {
                keywords.add(part);
            }
        }

        // 4. 如果还是没有关键词，使用原始查询
        if (keywords.isEmpty()) {
            keywords.add(queryText);
        }

        log.debug("[Milvus] 提取的查询关键词: {}", keywords);
        return keywords.stream().distinct().collect(Collectors.toList());
    }
    
    /**
     * 调试方法：搜索包含特定关键词的文档
     * @param collectionName 集合名称
     * @param keyword 搜索关键词
     * @return 包含关键词的文档列表
     */
    public List<Map<String, Object>> searchDocumentsByKeyword(String collectionName, String keyword) {
        // 构建租户隔离的集合名
        String tenantCollectionName = buildTenantCollectionName(collectionName);
        log.info("[Milvus] 在集合 {} -> {} 中搜索关键词: {}", collectionName, tenantCollectionName, keyword);
        
        List<Map<String, Object>> allDocs = queryAllDocuments(collectionName);
        List<Map<String, Object>> matchingDocs = new ArrayList<>();
        
        // 提取关键词进行搜索
        List<String> searchKeywords = extractSearchKeywords(keyword);
        log.info("[Milvus] 提取的搜索关键词: {}", searchKeywords);
        
        for (Map<String, Object> doc : allDocs) {
            String text = doc.get("text").toString().toLowerCase();
            String metadata = doc.get("metadata").toString().toLowerCase();
            
            // 检查是否包含任何关键词
            boolean matches = false;
            for (String searchKeyword : searchKeywords) {
                if (text.contains(searchKeyword.toLowerCase()) || metadata.contains(searchKeyword.toLowerCase())) {
                    matches = true;
                    break;
                }
            }
            
            if (matches) {
                // 添加一个默认分数
                doc.put("score", 0.0f);
                matchingDocs.add(doc);
                log.info("[Milvus] 找到匹配文档: {}", doc.get("metadata"));
            }
        }
        
        log.info("[Milvus] 关键词 '{}' 匹配到 {} 个文档", keyword, matchingDocs.size());
        return matchingDocs;
    }
    
    /**
     * 提取搜索关键词
     * @param query 查询文本
     * @return 关键词列表
     */
    private List<String> extractSearchKeywords(String query) {
        List<String> keywords = new ArrayList<>();
        String lowerQuery = query.toLowerCase();
        
        // 1. 添加完整查询
        keywords.add(lowerQuery);
        
        // 2. 移除停用词后提取关键词
        String processedQuery = lowerQuery.replaceAll("[的|是|有|哪些|什么|如何|怎么|为什么|在|和|与|或|但|然而|因此|所以]", "");
        
        // 3. 按2-4个字符的窗口提取关键词
        for (int i = 0; i <= processedQuery.length() - 2; i++) {
            for (int len = 2; len <= Math.min(4, processedQuery.length() - i); len++) {
                String word = processedQuery.substring(i, i + len);
                if (word.length() >= 2 && !keywords.contains(word)) {
                    keywords.add(word);
                }
            }
        }
        
        return keywords;
    }
    
    /**
     * 放宽相似度阈值的搜索方法
     * @param collectionName 集合名称
     * @param queryEmbedding 查询向量
     * @param nResults 结果数量
     * @param queryText 查询文本
     * @return 文档列表
     */
    public List<Map<String, Object>> queryDocumentsRelaxed(String collectionName, List<Float> queryEmbedding, int nResults, String queryText) {
        // 构建租户隔离的集合名
        String tenantCollectionName = buildTenantCollectionName(collectionName);
        log.info("[Milvus] 放宽阈值搜索（租户隔离）: {} -> {}, nResults={}", collectionName, tenantCollectionName, nResults);
        
        // 使用更宽松的阈值进行搜索
        int optimizedResults = Math.min(nResults, 30);
        
        SearchParam searchParam = SearchParam.newBuilder()
                .withCollectionName(tenantCollectionName)
                .withMetricType(MetricType.L2)
                .withOutFields(Arrays.asList("id", "text", "metadata"))
                .withTopK(optimizedResults)
                .withVectors(Collections.singletonList(queryEmbedding))
                .withVectorFieldName("embedding")
                .withParams("{\"nprobe\": 32}")
                .build();
        
        try {
            R<SearchResults> searchResponse = performSafeSearch(searchParam);
            if (searchResponse == null) {
                return Collections.emptyList();
            }
            
            SearchResults searchResults = getSafeResponseData(searchResponse);
            if (searchResults == null) {
                return Collections.emptyList();
            }
            
            SearchResultsWrapper wrapper = new SearchResultsWrapper(searchResults.getResults());
            List<QueryResultsWrapper.RowRecord> rowRecords = wrapper.getRowRecords();
            
            List<Map<String, Object>> docs = new ArrayList<>();
            for (int i = 0; i < Math.min(rowRecords.size(), optimizedResults); i++) {
                try {
                    Map<String, Object> doc = new HashMap<>();
                    QueryResultsWrapper.RowRecord rowRecord = rowRecords.get(i);
                    
                    doc.put("id", rowRecord.get("id"));
                    doc.put("text", rowRecord.get("text"));
                    doc.put("metadata", rowRecord.get("metadata"));
                    
                    float score = 0.0f;
                    if (i < searchResults.getResults().getScoresList().size()) {
                        score = searchResults.getResults().getScoresList().get(i);
                    }
                    doc.put("score", score);
                    
                    // 使用更宽松的阈值：800.0f
                    if (score <= 800.0f) {
                        docs.add(doc);
                        log.info("[Milvus] 放宽阈值添加文档: score={}", score);
                    }
                } catch (Exception e) {
                    log.error("[Milvus] 处理放宽阈值搜索结果失败: {}", e.getMessage());
                }
            }
            
            return docs;
        } catch (Exception e) {
            log.error("[Milvus] 放宽阈值搜索失败: {}", e.getMessage());
            return Collections.emptyList();
        }
    }
    
    /**
     * 获取最相似的文档（忽略所有过滤条件）
     * @param collectionName 集合名称
     * @param queryEmbedding 查询向量
     * @param nResults 结果数量
     * @return 文档列表
     */
    public List<Map<String, Object>> queryTopSimilarDocuments(String collectionName, List<Float> queryEmbedding, int nResults) {
        log.info("[Milvus] 获取最相似文档: collectionName={}, nResults={}", collectionName, nResults);
        
        SearchParam searchParam = SearchParam.newBuilder()
                .withCollectionName(collectionName)
                .withMetricType(MetricType.L2)
                .withOutFields(Arrays.asList("id", "text", "metadata"))
                .withTopK(nResults)
                .withVectors(Collections.singletonList(queryEmbedding))
                .withVectorFieldName("embedding")
                .withParams("{\"nprobe\": 32}")
                .build();
        
        try {
            R<SearchResults> searchResponse = performSafeSearch(searchParam);
            if (searchResponse == null) {
                return Collections.emptyList();
            }
            
            SearchResults searchResults = getSafeResponseData(searchResponse);
            if (searchResults == null) {
                return Collections.emptyList();
            }
            
            SearchResultsWrapper wrapper = new SearchResultsWrapper(searchResults.getResults());
            List<QueryResultsWrapper.RowRecord> rowRecords = wrapper.getRowRecords();
            
            List<Map<String, Object>> docs = new ArrayList<>();
            for (int i = 0; i < Math.min(rowRecords.size(), nResults); i++) {
                try {
                    Map<String, Object> doc = new HashMap<>();
                    QueryResultsWrapper.RowRecord rowRecord = rowRecords.get(i);
                    
                    doc.put("id", rowRecord.get("id"));
                    doc.put("text", rowRecord.get("text"));
                    doc.put("metadata", rowRecord.get("metadata"));
                    
                    float score = 0.0f;
                    if (i < searchResults.getResults().getScoresList().size()) {
                        score = searchResults.getResults().getScoresList().get(i);
                    }
                    doc.put("score", score);
                    
                    docs.add(doc);
                    log.info("[Milvus] 添加最相似文档: score={}", score);
                } catch (Exception e) {
                    log.error("[Milvus] 处理最相似文档失败: {}", e.getMessage());
                }
            }
            
            return docs;
        } catch (Exception e) {
            log.error("[Milvus] 获取最相似文档失败: {}", e.getMessage());
            return Collections.emptyList();
        }
    }
    
    /**
     * 从metadata中提取文档源文件名
     * @param metadata metadata字符串
     * @return 文档源文件名
     */
    private String extractSourceFromMetadata(String metadata) {
        try {
            // metadata格式: {"source":"湖畔酒店项目/管综原则.pdf","timestamp":"..."}
            if (metadata.contains("\"source\":")) {
                int startIndex = metadata.indexOf("\"source\":\"") + 10;
                int endIndex = metadata.indexOf("\"", startIndex);
                if (startIndex > 9 && endIndex > startIndex) {
                    return metadata.substring(startIndex, endIndex);
                }
            }
            // 如果解析失败，返回原始metadata
            return metadata;
        } catch (Exception e) {
            log.warn("[Milvus] 解析metadata失败: {}", metadata);
            return metadata;
        }
    }

    /**
     * 创建测试文档用于验证关键词提取功能
     */
    private List<Map<String, Object>> createTestDocuments() {
        List<Map<String, Object>> testDocs = new ArrayList<>();
        
        // 创建测试文档1
        Map<String, Object> doc1 = new HashMap<>();
        doc1.put("id", "test-doc-1");
        doc1.put("text", "建筑标准值和卫生要求规范文档。建筑设计需要满足卫生标准，包括通风、照明和安全要求。");
        doc1.put("metadata", "{\"source\":\"test/test_content.txt\",\"timestamp\":\"2025-12-22\"}");
        doc1.put("score", 850.0f); // 高分，确保能通过相似度过滤
        testDocs.add(doc1);
        
        // 创建测试文档2
        Map<String, Object> doc2 = new HashMap<>();
        doc2.put("id", "test-doc-2");
        doc2.put("text", "消防安全规范和质量验收标准。施工过程必须遵守消防要求。");
        doc2.put("metadata", "{\"source\":\"test/test_content.txt\",\"timestamp\":\"2025-12-22\"}");
        doc2.put("score", 820.0f);
        testDocs.add(doc2);
        
        log.info("[Milvus] 创建了 {} 个测试文档用于验证关键词提取", testDocs.size());
        return testDocs;
    }
}