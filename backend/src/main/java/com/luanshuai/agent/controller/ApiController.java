// 包声明：控制器属于 controller 包
package com.luanshuai.agent.controller;

// 引入标准库和 io 操作相关的类
import java.io.File; // 文件操作
import java.io.IOException; // IO 异常
import java.nio.file.Files; // 文件系统工具类
import java.nio.file.Path; // 文件路径表示
import java.nio.file.Paths; // 构造 Path 的工具
import java.util.ArrayList; // 动态数组实现
import java.util.Arrays; // 数组工具类
import java.util.HashMap; // Map 的实现
import java.util.HashSet; // Set 的实现
import java.util.LinkedHashMap; // 保序 Map（用于调试输出更稳定）
import java.util.LinkedHashSet; // 保序 Set（用于调试输出更稳定）
import java.util.List; // 列表接口
import java.util.Map; // 映射接口
import java.util.Set; // 集合接口
import java.util.stream.Collectors; // Stream 工具

// 引入日志与 Spring 框架相关注解/类型
import org.slf4j.Logger; // 日志接口
import org.slf4j.LoggerFactory; // 日志工厂
import org.springframework.beans.factory.annotation.Autowired; // 自动注入注解
import org.springframework.http.HttpHeaders; // HTTP 头常量
import org.springframework.http.MediaType; // 媒体类型
import org.springframework.http.ResponseEntity; // HTTP 响应封装
import org.springframework.http.codec.multipart.FilePart; // Reactor 文件分片
import org.springframework.security.authentication.ReactiveAuthenticationManager; // Reactive 认证管理器
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken; // 认证令牌
import org.springframework.security.core.Authentication; // 认证接口
import org.springframework.web.bind.annotation.CrossOrigin; // 跨域配置注解
import org.springframework.web.bind.annotation.DeleteMapping; // DELETE 映射
import org.springframework.web.bind.annotation.GetMapping; // GET 映射
import org.springframework.web.bind.annotation.PathVariable; // 路径变量
import org.springframework.web.bind.annotation.PostMapping; // POST 映射
import org.springframework.web.bind.annotation.RequestBody; // 请求体映射
import org.springframework.web.bind.annotation.RequestHeader; // 请求头映射
import org.springframework.web.bind.annotation.RequestMapping; // 路由前缀
import org.springframework.web.bind.annotation.RequestParam; // 查询参数映射
import org.springframework.web.bind.annotation.RequestPart; // multipart 表单部分
import org.springframework.web.bind.annotation.RestController; // Rest 控制器注解
import org.springframework.web.multipart.MultipartFile; // 传统 servlet 文件上传

// 引入 JSON 与应用内模型/服务
import com.fasterxml.jackson.core.JsonProcessingException; // JSON 处理异常
import com.fasterxml.jackson.databind.JsonNode; // JSON 节点
import com.fasterxml.jackson.databind.ObjectMapper; // JSON 映射器
import com.luanshuai.agent.config.AppConfig; // 应用配置
import com.luanshuai.agent.config.JwtTokenProvider; // JWT 提供器
import com.luanshuai.agent.model.ApiResponse; // 统一 API 响应模型
import com.luanshuai.agent.model.BimModelResult; // BIM 结果模型
import com.luanshuai.agent.model.CadDrawingResult; // CAD 结果模型
import com.luanshuai.agent.model.ChatRequest; // 聊天请求模型
import com.luanshuai.agent.model.KbNode; // 知识库树节点模型
import com.luanshuai.agent.service.BimParserService; // BIM 解析服务
import com.luanshuai.agent.service.CadParserService; // CAD 解析服务
import com.luanshuai.agent.service.ChatSessionService; // 会话历史存储服务
import com.luanshuai.agent.service.FileParserService; // 文件解析服务
import com.luanshuai.agent.service.KnowledgeBaseService; // 知识库服务
import com.luanshuai.agent.service.MilvusDbService; // Milvus 向量库服务
import com.luanshuai.agent.service.PdfExtractKitService; // PDF-Extract-Kit 服务
import com.luanshuai.agent.service.PdfPreviewService; // PDF 页面预览渲染服务
import com.luanshuai.agent.service.RagService; // RAG 服务
import com.luanshuai.agent.service.LLMService; // 统一LLM接口服务（本地llama.cpp + 云端API）
import org.springframework.http.HttpStatus; // HTTP 状态码枚举
import org.springframework.web.server.ResponseStatusException; // 响应状态异常
import com.luanshuai.agent.util.TenantContext; // 租户上下文工具（线程本地）
import com.luanshuai.agent.util.TenantUtils; // 租户工具

import reactor.core.publisher.Flux; // Reactor 流
import reactor.core.publisher.Mono; // Reactor 单值

// 标注为 RestController，路由前缀 /api，并允许跨域请求
@RestController
@RequestMapping("/api")
@CrossOrigin(origins = "*")
public class ApiController {
    
    // 日志记录器，用于记录控制器中的操作日志
    private static final Logger log = LoggerFactory.getLogger(ApiController.class);

    // 用于在日志中打印蓝色文本的控制台转义序列（仅调试输出用）
    private static final String ANSI_BLUE = "\u001B[34m"; // 蓝色
    private static final String ANSI_RESET = "\u001B[0m"; // 重置颜色
    private static String blue(String msg) {
        // 将消息包裹成蓝色文本并返回
        return ANSI_BLUE + msg + ANSI_RESET;
    }
    
    // 以下为服务与组件的自动注入（按需扩展）
    @Autowired
    private RagService ragService; // RAG 主逻辑服务

    @Autowired
    private LLMService llmService; // 统一LLM服务（本地llama.cpp + 云端API）
    
    @Autowired
    private AppConfig appConfig; // 应用配置

    @Autowired
    private JwtTokenProvider jwtTokenProvider; // JWT 工具

    @Autowired
    private ChatSessionService chatSessionService; // 会话历史存储服务

    @Autowired
    private KnowledgeBaseService knowledgeBaseService; // 知识库操作服务

    @Autowired
    private CadParserService cadParserService; // CAD 服务

    @Autowired
    private BimParserService bimParserService; // BIM 服务

    @Autowired
    private ReactiveAuthenticationManager authenticationManager; // 反应式认证管理器
    
    @Autowired
    private MilvusDbService milvusDbService; // Milvus 向量数据库操作服务
    
    @Autowired
    private FileParserService fileParserService; // 通用文件解析服务
    
    @Autowired
    private PdfExtractKitService pdfExtractKitService; // PDF 提取服务
    
    @Autowired
    private com.luanshuai.agent.service.DefaultKnowledgeBaseService defaultKnowledgeBaseService; // 默认知识库服务

    @Autowired
    private PdfPreviewService pdfPreviewService; // PDF 页面预览渲染服务
    
    
    // =============== Debug API: Milvus 状态检查 ===============
    @GetMapping("/debug/milvus-status")
    public ResponseEntity<Map<String, Object>> debugMilvusStatus() {
        // 返回对象
        Map<String, Object> result = new HashMap<>();
        try {
            // 动态检查所有可能的集合名称候选
            List<String> possibleCollections = new ArrayList<>();
            possibleCollections.add("default_knowledge_base"); // 常见默认集合
            possibleCollections.add("kb"); // 旧版默认名
            
            // 额外按已知业务中文名生成可能的集合名
            possibleCollections.add("kb_hupan_hotel_project");
            possibleCollections.add("hupan_hotel_project");
            possibleCollections.add("kb_architectural_design_standards");
            possibleCollections.add("architectural_design_standards");
            possibleCollections.add("kb_bim_guidelines_standards");
            possibleCollections.add("bim_guidelines_standards");
            
            // 历史遗留集合名，作为兜底检查
            possibleCollections.add("kb_______");
            possibleCollections.add("kb_BIM_____");
            
            Map<String, Object> collections = new HashMap<>(); // 存放结果
            for (String collectionName : possibleCollections) {
                Map<String, Object> collectionInfo = new HashMap<>(); // 单集合信息
                boolean exists = milvusDbService.hasCollection(collectionName); // 检查是否存在
                collectionInfo.put("exists", exists);
                
                if (exists) {
                    try {
                        // 查询集合中所有文档以收集统计信息（注意：量大时需谨慎）
                        List<Map<String, Object>> docs = milvusDbService.queryAllDocuments(collectionName);
                        collectionInfo.put("documentCount", docs.size());
                        
                        // 打印日志供调试使用
                        log.info("[Debug] 集合 {} 包含 {} 个文档", collectionName, docs.size());
                        
                        // 仅示例性返回前几条文档信息，避免返回过多
                        collectionInfo.put("documents", docs.stream().limit(3).collect(Collectors.toList()));
                    } catch (Exception e) {
                        // 如果查询失败，将错误信息记录到结果中
                        collectionInfo.put("error", e.getMessage());
                    }
                }
                // 将单集合信息加入总体结果
                collections.put(collectionName, collectionInfo);
            }
            
            // 返回 success 和集合信息
            result.put("success", true);
            result.put("collections", collections);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            // 捕获异常并返回 500
            result.put("success", false);
            result.put("error", e.getMessage());
            return ResponseEntity.status(500).body(result);
        }
    }
    
    // =============== Debug API: 模拟同步过程 ===============
    @GetMapping("/debug/test-sync-process")
    public ResponseEntity<Map<String, Object>> testSyncProcess() {
        Map<String, Object> result = new HashMap<>();
        try {
            // 记录调试日志
            log.info("[Debug] 测试同步过程");
            
            // 模拟同步过程：遍历知识库根目录，计算每个目录是否已存在于 Milvus 以及文档统计
            Path basePath = Paths.get(appConfig.getKnowledgeBase().getPath());
            Map<String, Object> syncInfo = new HashMap<>();
            
            if (Files.exists(basePath)) {
                Files.list(basePath)
                    .filter(Files::isDirectory)
                    .forEach(dir -> {
                        String dirName = dir.getFileName().toString();
                        String kbKey = com.luanshuai.agent.util.TenantUtils.buildKbCollectionKey(dirName);
                        String tenantId = TenantContext.getCurrentTenantId();
                        String effectiveTenantId = (tenantId == null || tenantId.trim().isEmpty()) ? "default" : tenantId.trim();
                        String tenantCollection = com.luanshuai.agent.util.TenantUtils.buildTenantCollectionName(effectiveTenantId, dirName);
                        boolean exists = milvusDbService.hasCollection(tenantCollection);
                        
                        Map<String, Object> dirInfo = new HashMap<>();
                        dirInfo.put("originalName", dirName);
                        dirInfo.put("kbKey", kbKey);
                        dirInfo.put("tenantCollection", tenantCollection);
                        dirInfo.put("exists", exists);
                        
                        if (exists) {
                            try {
                                List<Map<String, Object>> docs = milvusDbService.queryAllDocuments(tenantCollection);
                                dirInfo.put("documentCount", docs.size());
                            } catch (Exception e) {
                                dirInfo.put("error", e.getMessage());
                            }
                        }
                        
                        syncInfo.put(dirName, dirInfo);
                    });
            }
            
            result.put("success", true);
            result.put("syncInfo", syncInfo);
            return ResponseEntity.ok(result);
        } catch (IOException e) {
            result.put("success", false);
            result.put("error", e.getMessage());
            return ResponseEntity.status(500).body(result);
        }
    }
    
    // =============== Debug API: 验证集合名称映射 ===============
    @GetMapping("/debug/collection-mapping")
    public ResponseEntity<Map<String, Object>> debugCollectionMapping() {
        Map<String, Object> result = new HashMap<>();
        try {
            // 记录开始检查日志
            log.info("[Debug] 检查集合名称映射");
            
            // 测试不同的原始名称到集合名称的映射
            List<String> testNames = Arrays.asList(
                "湖畔酒店项目",
                "建筑设计规范", 
                "BIM导则及标准",
                "default_knowledge_base",
                "hupan_hotel_project",
                "architectural_design_standards",
                "bim_guidelines_standards"
            );
            
            Map<String, Object> mappings = new HashMap<>();
            for (String testName : testNames) {
                String tenantId = TenantContext.getCurrentTenantId();
                String effectiveTenantId = (tenantId == null || tenantId.trim().isEmpty()) ? "default" : tenantId.trim();
                String kbKey = com.luanshuai.agent.util.TenantUtils.buildKbCollectionKey(testName);
                String tenantCollection = com.luanshuai.agent.util.TenantUtils.buildTenantCollectionName(effectiveTenantId, testName);
                boolean exists = milvusDbService.hasCollection(tenantCollection);
                Map<String, Object> mappingInfo = new HashMap<>();
                mappingInfo.put("kbKey", kbKey);
                mappingInfo.put("tenantCollection", tenantCollection);
                mappingInfo.put("exists", exists);
                if (exists) {
                    try {
                        List<Map<String, Object>> docs = milvusDbService.queryAllDocuments(tenantCollection);
                        mappingInfo.put("documentCount", docs.size());
                    } catch (Exception e) {
                        mappingInfo.put("error", e.getMessage());
                    }
                }
                mappings.put(testName, mappingInfo);
            }
            
            result.put("success", true);
            result.put("mappings", mappings);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            result.put("success", false);
            result.put("error", e.getMessage());
            return ResponseEntity.status(500).body(result);
        }
    }

    @GetMapping("/debug/list-collections")
    public ResponseEntity<String> listAllCollections() {
        try {
            List<String> cols = milvusDbService.listCollections();
            // 返回原始 JSON 字符串并设置为 text/plain，避免客户端（如 PowerShell 的 Invoke-RestMethod）对数组进行缩略显示
            ObjectMapper mapper = new ObjectMapper();
            String json = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(cols);
            return ResponseEntity.ok().contentType(MediaType.TEXT_PLAIN).body(json);
        } catch (JsonProcessingException e) {
            String err = "{\"success\":false, \"error\": \"" + e.getMessage() + "\"}";
            return ResponseEntity.status(500).contentType(MediaType.TEXT_PLAIN).body(err);
        }
    }

    /**
     * Debug: 审计"文件系统知识库目录"与"Milvus 实际集合"的对应关系。
     * 用途：定位类似"删除的是消防规范，但 Milvus 显示/操作的是 chinesestandards"这类命名不一致问题。
     *
     * 输出包含：
     * - tenantId / storageMode / kbRoot
     * - 文件系统顶层 KB 目录
     * - 使用 RagService.sanitizeCollectionName 推导出的期望集合（t_{tenant}__{sanitizedKb}）
     * - Milvus 中实际存在的该租户集合（按 t_{tenant}__ 前缀过滤）
     * - missing / orphan 集合列表
     */
    @GetMapping("/debug/tenant-collections-audit")
    public ResponseEntity<Map<String, Object>> debugTenantCollectionsAudit(
        @RequestParam(value = "tenantId", required = false) String tenantId
    ) {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            String currentTenant = (tenantId != null && !tenantId.trim().isEmpty())
                ? tenantId.trim()
                : (TenantContext.getCurrentTenantId() != null && !TenantContext.getCurrentTenantId().trim().isEmpty()
                    ? TenantContext.getCurrentTenantId().trim()
                    : "admin");

            String storageMode = appConfig.getTenant().getStorageMode();
            String tenantKbBasePath = appConfig.getTenant().getTenantKbPath();

            // 计算该租户的知识库根目录（不依赖 TenantContext，避免调试时上下文为空）
            Path kbRoot;
            if ("admin".equals(currentTenant)) {
                kbRoot = Paths.get(appConfig.getKnowledgeBase().getPath());
            } else {
                String tenantKbPath = com.luanshuai.agent.util.TenantUtils.buildTenantKnowledgeBasePath(
                    currentTenant,
                    null,
                    storageMode,
                    tenantKbBasePath
                );
                kbRoot = Paths.get(tenantKbPath);
            }

            // 1) 文件系统顶层目录
            List<String> kbDirs = new ArrayList<>();
            if (Files.exists(kbRoot)) {
                try {
                    kbDirs = Files.list(kbRoot)
                        .filter(Files::isDirectory)
                        .map(p -> p.getFileName().toString())
                        .sorted()
                        .collect(Collectors.toList());
                } catch (Exception e) {
                    result.put("fsListError", e.getMessage());
                }
            }

            // 2) 期望集合（使用 RagService 的 sanitize 作为"唯一真相"）
            List<Map<String, Object>> mappings = new ArrayList<>();
            Set<String> expectedTenantCollections = new LinkedHashSet<>();
            // 默认集合
            expectedTenantCollections.add(TenantUtils.buildTenantCollectionName(currentTenant, "default_knowledge_base"));

            for (String dir : kbDirs) {
                String kbKey = TenantUtils.buildKbCollectionKey(dir);
                String tenantCollection = TenantUtils.buildTenantCollectionName(currentTenant, dir);
                expectedTenantCollections.add(tenantCollection);
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("kbDir", dir);
                m.put("kbKey", kbKey);
                m.put("tenantCollection", tenantCollection);
                mappings.add(m);
            }

            // 3) Milvus 实际集合（按租户前缀过滤）
            List<String> allCollections = milvusDbService.listCollections();
            String tenantDefault = TenantUtils.buildTenantCollectionName(currentTenant, "default_knowledge_base");
            String tenantPrefix = tenantDefault.contains("__") ? tenantDefault.substring(0, tenantDefault.indexOf("__") + 2) : ("t_" + currentTenant + "__");

            List<String> actualTenantCollections = allCollections.stream()
                .filter(name -> name != null && name.startsWith(tenantPrefix))
                .sorted()
                .collect(Collectors.toList());

            Set<String> actualSet = new LinkedHashSet<>(actualTenantCollections);
            Set<String> missing = new LinkedHashSet<>(expectedTenantCollections);
            missing.removeAll(actualSet);
            Set<String> orphan = new LinkedHashSet<>(actualSet);
            orphan.removeAll(expectedTenantCollections);

            result.put("success", true);
            result.put("tenantId", currentTenant);
            result.put("storageMode", storageMode);
            result.put("tenantKbBasePath", tenantKbBasePath);
            result.put("kbRoot", kbRoot.toString());
            result.put("kbDirs", kbDirs);
            result.put("mappings", mappings);
            result.put("expectedTenantCollections", new ArrayList<>(expectedTenantCollections));
            result.put("tenantPrefix", tenantPrefix);
            result.put("actualTenantCollections", actualTenantCollections);
            result.put("missingTenantCollections", new ArrayList<>(missing));
            result.put("orphanTenantCollections", new ArrayList<>(orphan));
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            result.put("success", false);
            result.put("error", e.getMessage());
            return ResponseEntity.status(500).body(result);
        }
    }

    @PostMapping("/debug/delete-collection")
    public ResponseEntity<Map<String, Object>> deleteCollection(@RequestBody Map<String, String> body) {
        Map<String, Object> result = new HashMap<>();
        try {
            String collection = body.get("collection");
            if (collection == null || collection.trim().isEmpty()) {
                result.put("success", false);
                result.put("error", "collection name required");
                return ResponseEntity.badRequest().body(result);
            }

            boolean deleted = milvusDbService.deleteCollection(collection);
            result.put("success", deleted);
            if (deleted) {
                result.put("message", "collection deleted: " + collection);
                return ResponseEntity.ok(result);
            } else {
                result.put("error", "failed to delete collection: " + collection);
                return ResponseEntity.status(500).body(result);
            }
        } catch (Exception e) {
            result.put("success", false);
            result.put("error", e.getMessage());
            return ResponseEntity.status(500).body(result);
        }
    }

    @PostMapping("/debug/delete-all-collections")
    public ResponseEntity<Map<String, Object>> deleteAllCollections() {
        Map<String, Object> result = new HashMap<>();
        try {
            log.warn("[Debug] 即将删除 Milvus 上的所有集合 —— 此操作不可恢复！");
            List<String> cols = milvusDbService.listCollections();
            List<String> deleted = new ArrayList<>();
            List<String> failed = new ArrayList<>();

            for (String col : cols) {
                try {
                    boolean ok = milvusDbService.deleteCollection(col);
                    if (ok) {
                        deleted.add(col);
                    } else {
                        failed.add(col);
                    }
                } catch (Exception e) {
                    failed.add(col + ": " + e.getMessage());
                }
            }

            result.put("success", failed.isEmpty());
            result.put("deleted", deleted);
            result.put("failed", failed);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            result.put("success", false);
            result.put("error", e.getMessage());
            return ResponseEntity.status(500).body(result);
        }
    }

    @PostMapping("/debug/migrate-collection")
    public ResponseEntity<Map<String, Object>> migrateCollection(@RequestBody Map<String, String> body) {
        Map<String, Object> result = new HashMap<>();
        try {
            String oldName = body.get("old");
            String newName = body.get("new");
            if (oldName == null || newName == null || oldName.trim().isEmpty() || newName.trim().isEmpty()) {
                result.put("success", false);
                result.put("error", "old and new collection names are required");
                return ResponseEntity.badRequest().body(result);
            }

            // sanitize incoming names similar to KB logic
            String sanitizedOld = ragService.sanitizeCollectionName(oldName);
            String sanitizedNew = ragService.sanitizeCollectionName(newName);

            log.info("[Debug] 手动触发集合迁移：{} -> {} (sanitized: {} -> {})", oldName, newName, sanitizedOld, sanitizedNew);

            // Attempt to detect physical (tenant-prefixed) collection name from Milvus list
            List<String> currentCols = milvusDbService.listCollections();
            String matchedOldPhysical = null;
            for (String col : currentCols) {
                if (col.endsWith("__" + sanitizedOld) || col.equals(sanitizedOld) || col.contains("__" + sanitizedOld + "_")) {
                    matchedOldPhysical = col;
                    break;
                }
            }

            String oldArg;
            String newArg;
            if (matchedOldPhysical != null) {
                // use physical names directly to avoid tenant-context mismatch
                oldArg = matchedOldPhysical;
                // build new physical by replacing trailing kb name
                int idx = matchedOldPhysical.indexOf("__");
                String tenantPrefix = matchedOldPhysical.substring(0, idx + 2); // includes __
                newArg = tenantPrefix + sanitizedNew;
            } else {
                oldArg = sanitizedOld;
                newArg = sanitizedNew;
            }

            log.info("[Debug] renameCollection args: oldArg={} newArg={}", oldArg, newArg);

            boolean ok = milvusDbService.renameCollection(oldArg, newArg);

            result.put("success", ok);
            result.put("old", sanitizedOld);
            result.put("new", sanitizedNew);
            // always include current collections for diagnosis
            try { result.put("collections", milvusDbService.listCollections()); } catch (Exception ignore) {}

            if (!ok) {
                // collect diagnostics to help debug why renameCollection failed
                try {
                    boolean hasOld = milvusDbService.hasCollection(sanitizedOld);
                    boolean hasNew = milvusDbService.hasCollection(sanitizedNew);
                    result.put("hasOld", hasOld);
                    result.put("hasNew", hasNew);
                    result.put("oldCount", milvusDbService.getCollectionCount(sanitizedOld));
                    result.put("newCount", hasNew ? milvusDbService.getCollectionCount(sanitizedNew) : 0);
                    result.put("oldSampleDocs", milvusDbService.queryAllDocuments(sanitizedOld).stream().limit(3).collect(Collectors.toList()));
                } catch (Exception diagEx) {
                    result.put("diagnosticError", diagEx.getMessage());
                }
                return ResponseEntity.status(500).body(result);
            }

            return ResponseEntity.ok(result);
        } catch (Exception e) {
            result.put("success", false);
            result.put("error", e.getMessage());
            return ResponseEntity.status(500).body(result);
        }
    }
    
    @PostMapping("/debug/process-pdf")
    public Mono<ResponseEntity<ApiResponse<String>>> processPdfFile(@RequestParam String filePath) {
        log.info("[Debug] 处理PDF文件: {}", filePath);
        
        return ragService.addDocument(filePath, "湖畔酒店项目", "admin")
            .map(result -> {
                log.info("[Debug] PDF处理结果: {}", result);
                return ResponseEntity.ok(ApiResponse.success("PDF处理完成", result));
            })
            .onErrorResume(e -> {
                log.error("[Debug] PDF处理失败: {}", e.getMessage());
                return Mono.just(ResponseEntity.ok(ApiResponse.error("PDF处理失败: " + e.getMessage())));
            });
    }
    
    @PostMapping("/debug/test-pdf-extract-kit")
    public Mono<ResponseEntity<ApiResponse<String>>> testPdfExtractKit(@RequestParam String filePath) {
        log.info("[Debug] 测试PDF-Extract-Kit: {}", filePath);
        
        String fullPath = appConfig.getKnowledgeBase().getPath() + File.separator + filePath;
        File file = new File(fullPath);
        
        if (!file.exists()) {
            return Mono.just(ResponseEntity.ok(ApiResponse.error("文件不存在: " + fullPath)));
        }
        
        return pdfExtractKitService.parsePdfWithExtractKit(file)
            .map(text -> {
                log.info("[Debug] PDF-Extract-Kit解析成功，内容长度: {}", text.length());
                return ResponseEntity.ok(ApiResponse.success("PDF-Extract-Kit解析成功", text.substring(0, Math.min(500, text.length()))));
            })
            .onErrorResume(e -> {
                log.error("[Debug] PDF-Extract-Kit解析失败: {}", e.getMessage());
                return Mono.just(ResponseEntity.ok(ApiResponse.error("PDF-Extract-Kit解析失败: " + e.getMessage())));
            });
    }

    @PostMapping("/debug/drop-and-recreate-collection")
    public ResponseEntity<Map<String, Object>> dropAndRecreateCollection(@RequestParam String kbId) {
        Map<String, Object> result = new HashMap<>();
        try {
            String collectionName = com.luanshuai.agent.util.TenantUtils.buildTenantCollectionName("admin", kbId);
            log.info("[Debug] 删除并重建集合: {}", collectionName);

            // 删除集合
            boolean dropped = milvusDbService.deleteCollection(collectionName);
            log.info("[Debug] 删除集合结果: {}", dropped);

            result.put("success", true);
            result.put("collectionName", collectionName);
            result.put("dropped", dropped);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            result.put("success", false);
            result.put("error", e.getMessage());
            return ResponseEntity.status(500).body(result);
        }
    }

    @PostMapping("/debug/force-reprocess-pdf")
    public Mono<ResponseEntity<ApiResponse<String>>> forceReprocessPdf(@RequestParam String filePath) {
        log.info("[Debug] 强制重新处理PDF文件: {}", filePath);

        String kbId = "湖畔酒店项目";
        String relPath = filePath;
        // 派生正确的 collection name（与 RagService/TenantUtils 保持一致）
        String collectionName = com.luanshuai.agent.util.TenantUtils.buildTenantCollectionName("admin", kbId);

        // 1. 先从向量数据库中删除旧片段
        boolean deleted = milvusDbService.deleteDocumentsBySource(collectionName, relPath);
        log.info("[Debug] 删除旧片段: {} (collection='{}', source='{}')", deleted, collectionName, relPath);

        // 2. 重新处理 PDF（使用新的分页版分片器）
        return ragService.addDocument(relPath, kbId, "admin")
            .map(result -> {
                log.info("[Debug] PDF重新处理完成: {}", result);
                return ResponseEntity.ok(ApiResponse.success("PDF重新处理完成", result));
            })
            .onErrorResume(e -> {
                log.error("[Debug] PDF重新处理失败: {}", e.getMessage(), e);
                return Mono.just(ResponseEntity.ok(ApiResponse.error("PDF重新处理失败: " + e.getMessage())));
            });
    }
    
    @PostMapping("/debug/test-pdf-parse")
    public Mono<ResponseEntity<Map<String, Object>>> testPdfParse(@RequestParam("file") MultipartFile file) {
        Map<String, Object> result = new HashMap<>();
        
        if (file.isEmpty()) {
            result.put("success", false);
            result.put("error", "文件为空");
            return Mono.just(ResponseEntity.badRequest().body(result));
        }
        
        String fileName = file.getOriginalFilename();
        if (fileName == null || !fileName.toLowerCase().endsWith(".pdf")) {
            result.put("success", false);
            result.put("error", "只支持PDF文件");
            return Mono.just(ResponseEntity.badRequest().body(result));
        }
        
        log.info("[PDF测试] 开始测试PDF解析: {}", fileName);
        
        try {
            // 保存临时文件
            File tempFile = File.createTempFile("pdf_test_", ".pdf");
            file.transferTo(tempFile);
            
            // 测试PDF智能解析（Docling主力 + VLM OCR兜底）
            return fileParserService.parseFileReactive(tempFile.getAbsolutePath())
                .map(text -> {
                    result.put("success", true);
                    result.put("fileName", fileName);
                    result.put("fileSize", file.getSize());
                    result.put("parseMethod", "PDF-Docling-Smart");
                    result.put("textLength", text.length());
                    result.put("text", text);
                    result.put("preview", text.length() > 1000 ? text.substring(0, 1000) + "..." : text);
                    
                    // 清理临时文件
                    tempFile.delete();
                    
                    log.info("[PDF测试] PDF智能解析成功: {}, 内容长度: {}", fileName, text.length());
                    return ResponseEntity.ok(result);
                })
                .onErrorResume(e -> {
                    log.error("[PDF测试] PDF智能解析失败: {}", e.getMessage());
                    
                    // 尝试使用Apache PDFBox作为备用方案
                    return fileParserService.parseFileReactive(tempFile.getAbsolutePath())
                        .map(text -> {
                            result.put("success", true);
                            result.put("fileName", fileName);
                            result.put("fileSize", file.getSize());
                            result.put("parseMethod", "Apache PDFBox (备用)");
                            result.put("textLength", text.length());
                            result.put("text", text);
                            result.put("preview", text.length() > 1000 ? text.substring(0, 1000) + "..." : text);
                            result.put("fallbackReason", "PDF-Extract-Kit解析失败: " + e.getMessage());
                            
                            // 清理临时文件
                            tempFile.delete();
                            
                            log.info("[PDF测试] Apache PDFBox解析成功: {}, 内容长度: {}", fileName, text.length());
                            return ResponseEntity.ok(result);
                        })
                        .onErrorResume(fallbackError -> {
                            result.put("success", false);
                            result.put("fileName", fileName);
                            result.put("fileSize", file.getSize());
                            result.put("error", "所有解析方法都失败");
                            result.put("pdfExtractKitError", e.getMessage());
                            result.put("pdfBoxError", fallbackError.getMessage());
                            
                            // 清理临时文件
                            tempFile.delete();
                            
                            log.error("[PDF测试] 所有解析方法都失败: PDF-Extract-Kit={}, PDFBox={}", e.getMessage(), fallbackError.getMessage());
                            return Mono.just(ResponseEntity.ok(result));
                        });
                });
                
        } catch (IOException | IllegalStateException e) {
            result.put("success", false);
            result.put("error", "文件处理失败: " + e.getMessage());
            log.error("[PDF测试] 文件处理失败: {}", e.getMessage(), e);
            return Mono.just(ResponseEntity.ok(result));
        }
    }
    
    @GetMapping("/debug/test-search")
    public ResponseEntity<Map<String, Object>> testSearch(@RequestParam String collectionName, @RequestParam String query) {
        Map<String, Object> result = new HashMap<>();
        
        log.info("[Debug] 测试搜索: collection={}, query={}", collectionName, query);
        
        if (!milvusDbService.hasCollection(collectionName)) {
            result.put("success", false);
            result.put("error", "集合不存在: " + collectionName);
            return ResponseEntity.ok(result);
        }
        
        try {
            // 使用同步方法进行测试
            List<Double> queryEmbedding = ragService.generateEmbeddingSync(query);
            List<Float> queryVector = queryEmbedding.stream()
                .map(Double::floatValue)
                .collect(Collectors.toList());
            
            // 执行搜索
            int topK = 5;
            try {
                if (appConfig != null && appConfig.getPerformance() != null) {
                    topK = Math.max(1, appConfig.getPerformance().getMaxSearchResults());
                }
            } catch (Exception ignore) {
                // keep default topK
            }
            List<Map<String, Object>> searchResults = milvusDbService.queryDocuments(collectionName, queryVector, topK);
            
            result.put("success", true);
            result.put("collectionName", collectionName);
            result.put("query", query);
            result.put("queryVectorSize", queryVector.size());
            result.put("searchResults", searchResults);
            result.put("resultCount", searchResults.size());
            
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            result.put("success", false);
            result.put("error", e.getMessage());
            return ResponseEntity.status(500).body(result);
        }
    }
    
    @GetMapping("/debug/collection-details")
    public ResponseEntity<Map<String, Object>> debugCollectionDetails(@RequestParam String collectionName) {
        Map<String, Object> result = new HashMap<>();
        try {
            log.info("[Debug] 检查集合详情: {}", collectionName);
            
            if (!milvusDbService.hasCollection(collectionName)) {
                result.put("success", false);
                result.put("error", "集合不存在: " + collectionName);
                return ResponseEntity.ok(result);
            }
            
            List<Map<String, Object>> docs = milvusDbService.queryAllDocuments(collectionName);
            result.put("success", true);
            result.put("collectionName", collectionName);
            result.put("documentCount", docs.size());
            result.put("documents", docs.stream().limit(5).collect(Collectors.toList())); // 只返回前5个文档
            
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            result.put("success", false);
            result.put("error", e.getMessage());
            return ResponseEntity.status(500).body(result);
        }
    }
    
    @GetMapping("/debug/diagnose-guanzong")
    public ResponseEntity<Map<String, Object>> diagnoseGuanZongDocument(@RequestParam(defaultValue = "hupan_hotel_project") String collectionName) {
        Map<String, Object> result = new HashMap<>();
        try {
            log.info("[Debug] 诊断管综原则文档: {}", collectionName);
            
            // 执行诊断
            ragService.diagnoseGuanZongDocument(collectionName);
            
            // 搜索相关文档
            List<Map<String, Object>> guanzongDocs = milvusDbService.searchDocumentsByKeyword(collectionName, "管综原则");
            List<Map<String, Object>> bim004Docs = milvusDbService.searchDocumentsByKeyword(collectionName, "孪数BIM004");
            List<Map<String, Object>> guanzongDocs2 = milvusDbService.searchDocumentsByKeyword(collectionName, "管综");
            
            result.put("success", true);
            result.put("collectionName", collectionName);
            result.put("guanzongDocuments", guanzongDocs.size());
            result.put("bim004Documents", bim004Docs.size());
            result.put("guanzongDocuments2", guanzongDocs2.size());
            result.put("guanzongDocs", guanzongDocs);
            result.put("bim004Docs", bim004Docs);
            result.put("guanzongDocs2", guanzongDocs2);
            
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            result.put("success", false);
            result.put("error", e.getMessage());
            return ResponseEntity.status(500).body(result);
        }
    }
    
    @GetMapping("/debug/sync-status")
    public ResponseEntity<Map<String, Object>> debugSyncStatus() {
        Map<String, Object> result = new HashMap<>();
        try {
            log.info("[Debug] 检查同步状态");
            
            // 1. 检查文件系统中的文件
            Path basePath = Paths.get(appConfig.getKnowledgeBase().getPath());
            Set<String> fileSystemFiles = new HashSet<>();
            
            if (Files.exists(basePath)) {
                Files.walk(basePath)
                    .filter(Files::isRegularFile)
                    .filter(path -> {
                        String fileName = path.getFileName().toString().toLowerCase();
                        return fileName.endsWith(".pdf") || 
                               fileName.endsWith(".doc") || 
                               fileName.endsWith(".docx") ||
                               fileName.endsWith(".txt");
                    })
                    .forEach(filePath -> {
                        String relativePath = basePath.relativize(filePath).toString();
                        fileSystemFiles.add(relativePath);
                    });
            }
            
            // 2. 检查向量数据库中的文件
            Set<String> vectorDatabaseFiles = new HashSet<>();
            List<String> collections = Arrays.asList(
                "default_knowledge_base",
                "kb_hupan_hotel_project",
                "hupan_hotel_project",
                "kb_architectural_design_standards",
                "architectural_design_standards",
                "kb_bim_guidelines_standards",
                "bim_guidelines_standards",
                "kb",
                "kb_______",
                "kb_BIM_____"
            );
            
            Map<String, Object> collectionDetails = new HashMap<>();
            
            for (String collectionName : collections) {
                if (milvusDbService.hasCollection(collectionName)) {
                    try {
                        List<Map<String, Object>> docs = milvusDbService.queryAllDocuments(collectionName);
                        Map<String, Object> collectionInfo = new HashMap<>();
                        collectionInfo.put("documentCount", docs.size());
                        
                        Set<String> collectionFiles = new HashSet<>();
                        for (Map<String, Object> doc : docs) {
                            Object metadataObj = doc.get("metadata");
                            if (metadataObj != null) {
                                String metadata = metadataObj.toString();
                                // 简单解析source字段
                                if (metadata.contains("\"source\":")) {
                                    int start = metadata.indexOf("\"source\":\"") + 10;
                                    int end = metadata.indexOf("\"", start);
                                    if (end > start) {
                                        String source = metadata.substring(start, end);
                                        collectionFiles.add(source);
                                        vectorDatabaseFiles.add(source);
                                    }
                                }
                            }
                        }
                        
                        collectionInfo.put("files", new ArrayList<>(collectionFiles));
                        collectionDetails.put(collectionName, collectionInfo);
                        
                    } catch (Exception e) {
                        log.error("[Debug] 查询集合 {} 失败: {}", collectionName, e.getMessage());
                    }
                }
            }
            
            result.put("success", true);
            result.put("fileSystemFiles", new ArrayList<>(fileSystemFiles));
            result.put("vectorDatabaseFiles", new ArrayList<>(vectorDatabaseFiles));
            result.put("collections", collectionDetails);
            result.put("fileSystemCount", fileSystemFiles.size());
            result.put("vectorDatabaseCount", vectorDatabaseFiles.size());
            
            // 计算差异
            Set<String> missingInVector = new HashSet<>(fileSystemFiles);
            missingInVector.removeAll(vectorDatabaseFiles);
            
            Set<String> extraInVector = new HashSet<>(vectorDatabaseFiles);
            extraInVector.removeAll(fileSystemFiles);
            
            result.put("missingInVector", new ArrayList<>(missingInVector));
            result.put("extraInVector", new ArrayList<>(extraInVector));
            result.put("missingCount", missingInVector.size());
            result.put("extraCount", extraInVector.size());
            
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            result.put("success", false);
            result.put("error", e.getMessage());
            return ResponseEntity.status(500).body(result);
        }
    }
    
    @GetMapping("/debug/search-file")
    public ResponseEntity<Map<String, Object>> searchSpecificFile(@RequestParam String fileName) {
        Map<String, Object> result = new HashMap<>();
        try {
            log.info("[Debug] 搜索特定文件: {}", fileName);
            
            // 检查所有可能的集合
            List<String> possibleCollections = Arrays.asList(
                "default_knowledge_base",
                "kb_hupan_hotel_project",
                "hupan_hotel_project",
                "kb_architectural_design_standards",
                "architectural_design_standards",
                "kb_bim_guidelines_standards",
                "bim_guidelines_standards",
                "kb",
                "kb_______",
                "kb_BIM_____"
            );
            
            List<Map<String, Object>> allFoundDocs = new ArrayList<>();
            
            for (String collectionName : possibleCollections) {
                if (milvusDbService.hasCollection(collectionName)) {
                    try {
                        List<Map<String, Object>> docs = milvusDbService.queryAllDocuments(collectionName);
                        
                        // 搜索包含指定文件名的文档
                        List<Map<String, Object>> foundDocs = docs.stream()
                            .filter(doc -> {
                                String metadata = doc.get("metadata").toString();
                                return metadata.contains(fileName);
                            })
                            .collect(Collectors.toList());
                        
                        if (!foundDocs.isEmpty()) {
                            log.info("[Debug] 在集合 {} 中找到文件 {} 的 {} 个片段", collectionName, fileName, foundDocs.size());
                            allFoundDocs.addAll(foundDocs);
                        }
                        
                    } catch (Exception e) {
                        log.error("[Debug] 查询集合 {} 失败: {}", collectionName, e.getMessage());
                    }
                }
            }
            
            result.put("success", true);
            result.put("fileName", fileName);
            result.put("foundFragments", allFoundDocs.size());
            result.put("fragments", allFoundDocs);
            
            if (allFoundDocs.isEmpty()) {
                result.put("message", "未找到该文件的任何片段");
            } else {
                result.put("message", String.format("找到 %d 个片段", allFoundDocs.size()));
            }

            return ResponseEntity.ok(result);
        } catch (Exception e) {
            result.put("success", false);
            result.put("error", e.getMessage());
            return ResponseEntity.status(500).body(result);
        }
    }

    /**
     * 新建对话：清除当前会话历史，开启新的 session
     */
    @DeleteMapping("/chat/session")
    public Mono<ResponseEntity<Map<String, Object>>> clearSession(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization) {
        String tenantId = resolveTenantIdFromAuthorizationHeader(authorization);
        log.info("[Session] 新建对话，清除会话 tenantId={}", tenantId);
        chatSessionService.clearSession(tenantId);
        return Mono.just(ResponseEntity.ok(Map.of(
            "success", true,
            "message", "会话已清除，开始新的对话"
        )));
    }

    @PostMapping("/chat")
    public Mono<ResponseEntity<Map<String, Object>>> chat(
            @RequestBody ChatRequest request,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization) {
        log.info("收到AI问答请求: {} - 使用RAG模式", request);
        // 使用RAG模式，基于知识库进行问答
        String tenantId = resolveTenantIdFromAuthorizationHeader(authorization);
        // 直接调用RAG服务（llama.cpp不需要预热）
        return ragService.chat(request, tenantId)
                .map(response -> {
                    log.info("RAG问答响应: {}", response);
                    return ResponseEntity.ok(response);
                })
                .doOnError(e -> log.error("RAG问答异常", e))
                .defaultIfEmpty(ResponseEntity.ok(Map.of(
                    "thought", "处理请求时发生错误",
                    "answer", "未获取到响应结果",
                    "mode", "rag"
                )));
    }
    
    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> chatStream(
            @RequestBody ChatRequest request,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization) {
        log.info("[DEBUG] chatStream controller called with request: question='{}', knowledgeBaseId='{}', tenantId='{}'",
            request.getQuestion(), request.getKnowledgeBaseId(), request.getTenantId());

        // 1. 先从 Authorization header 解析 tenantId（作为备选）
        String headerTenantId = resolveTenantIdFromAuthorizationHeader(authorization);
        log.info("[DEBUG] chatStream tenantId from header: {}", headerTenantId);

        // 2. 确定最终 tenantId：优先使用请求体中的 tenantId，否则使用 header 中的
        String tenantId = request.getTenantId();
        if (tenantId == null || tenantId.trim().isEmpty()) {
            tenantId = headerTenantId;
        }
        log.info("[DEBUG] chatStream final tenantId: {} (from body={}, from header={})", tenantId, request.getTenantId(), headerTenantId);

        // 3. 从后端会话服务获取历史
        List<ChatRequest.ChatMessage> history = chatSessionService.getHistory(tenantId);
        log.info("[DEBUG] chatStream history count: {}", history.size());

        // 4. 注入后端会话历史
        request.setHistory(history);

        log.info("[DEBUG] parsed request.question: {}", request.getQuestion());
        log.info("[DEBUG] parsed request.knowledgeBaseId: {}", request.getKnowledgeBaseId());
        log.info("[DEBUG] parsed request.enableThinking: {}", request.isEnableThinking());
        log.info("[Stream] 收到AI流式问答请求: {} - 使用RAG模式", request);

        // 5. 追加用户消息到会话历史（立即记录，AI 回复在 RagService 中记录）
        String question = request.getQuestion();
        if (question != null && !question.trim().isEmpty()) {
            chatSessionService.appendUserMessage(tenantId, question);
        }

        // 6. 流式调用 RAG 服务，并在完成后追加 AI 回复到会话历史
        Flux<String> ragFlow = ragService.chatStream(request, tenantId);

        // 收集 AI 回复内容（从 SSE "answer" 类型的 data 中提取 content）
        final String finalTenantId = tenantId;
        final String userQuestion = question;
        final ObjectMapper mapper = new ObjectMapper();

        return ragFlow
            .transform(stream -> {
                // 累积 AI 回复正文
                final StringBuilder aiContent = new StringBuilder();
                return stream
                    .doOnNext(chunk -> {
                        // 从 SSE data 中提取 answer content
                        String data = chunk;
                        if (data.startsWith("data: ")) {
                            data = data.substring(6).trim();
                        }
                        if (data.startsWith("[")) {
                            // 可能多个 JSON 同行，用 [] 包裹，逐个尝试
                            data = data.replaceAll("^\\[|\\]$", "");
                        }
                        if (!data.isEmpty() && !data.equals("[DONE]") && !data.startsWith("event:")) {
                            try {
                                JsonNode node = mapper.readTree(data);
                                String type = node.has("type") ? node.get("type").asText() : "";
                                if ("answer".equals(type) || "content".equals(type)) {
                                    String content = node.has("content") ? node.get("content").asText() : "";
                                    if (!content.isEmpty()) {
                                        aiContent.append(content);
                                    }
                                }
                            } catch (Exception ignored) {
                                // 非 JSON 行或解析失败，忽略
                            }
                        }
                    })
                    .doOnComplete(() -> {
                        // 流结束后记录 AI 回复
                        String aiReply = aiContent.toString().trim();
                        if (!aiReply.isEmpty()) {
                            chatSessionService.appendAiMessage(finalTenantId, aiReply);
                            log.debug("[Session] 追加 AI 回复 tenantId={} length={}", finalTenantId, aiReply.length());
                        }
                    })
                    .doOnError(e -> log.error("[Session] 流式对话异常 tenantId={}", finalTenantId, e));
            });
    }
    
    
    @PostMapping("/chat/rag")
    public Mono<ResponseEntity<Map<String, Object>>> ragChat(
            @RequestBody ChatRequest request,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization) {
        log.info("[RAG] 收到RAG模式对话请求: {}", request);
        String tenantId = resolveTenantIdFromAuthorizationHeader(authorization);
        // 直接调用RAG服务（llama.cpp不需要预热）
        return ragService.chat(request, tenantId)
                .map(response -> {
                    log.info("[RAG] RAG模式对话响应: {}", response);
                    return ResponseEntity.ok(response);
                })
                .doOnError(e -> log.error("[RAG] RAG模式对话异常", e))
                .defaultIfEmpty(ResponseEntity.ok(Map.of(
                    "thought", "处理请求时发生错误",
                    "answer", "未获取到响应结果",
                    "mode", "rag"
                )));
    }
    
    @PostMapping(value = "/chat/rag/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> ragChatStream(
            @RequestBody ChatRequest request,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization) {
        log.info("[RAG] 收到RAG模式流式对话请求: {}", request);
        String tenantId = resolveTenantIdFromAuthorizationHeader(authorization);
        // 不阻塞在 warmup 上：warmup 会在后台触发/继续；流式接口会自行发送 loading 心跳保持连接。
        return ragService.chatStream(request, tenantId);
    }

    @GetMapping("/health/llm")
    public Mono<ResponseEntity<Map<String, Object>>> llmHealth() {
        return llmService.checkConnection()
            .map(connected -> {
                Map<String, Object> result = new HashMap<>();
                result.put("ready", connected);
                result.put("provider", llmService.getProvider());
                if (connected) {
                    return ResponseEntity.ok(result);
                } else {
                    return ResponseEntity.status(503).body(result);
                }
            })
            .defaultIfEmpty(ResponseEntity.status(503).body(Map.of("ready", false)));
    }
    
    @GetMapping("/kb/tree")
    public ResponseEntity<ApiResponse<List<KbNode>>> getKnowledgeBaseTree(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization) {
        String tenantId = resolveTenantIdFromAuthorizationHeader(authorization);
        try {
            if (tenantId != null && !tenantId.trim().isEmpty()) {
                TenantContext.setTenantId(tenantId);
            }
            ApiResponse<List<KbNode>> response = knowledgeBaseService.getKnowledgeBaseTree();
            return ResponseEntity.ok(response);
        } finally {
            TenantContext.clear();
        }
    }
    
    @GetMapping("/kb/{path}/files")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> getFilesInPath(
            @PathVariable String path,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization) {
        String tenantId = resolveTenantIdFromAuthorizationHeader(authorization);
        try {
            if (tenantId != null && !tenantId.trim().isEmpty()) {
                TenantContext.setTenantId(tenantId);
            }
            List<Map<String, Object>> files = knowledgeBaseService.getFilesInPath(path);
            return ResponseEntity.ok(ApiResponse.success("获取文件列表成功", files));
        } catch (Exception e) {
            return ResponseEntity.ok(ApiResponse.error("获取文件列表失败: " + e.getMessage()));
        } finally {
            TenantContext.clear();
        }
    }
    
    @PostMapping("/kb/create_folder")
    public ResponseEntity<ApiResponse<String>> createFolder(
            @RequestBody Map<String, String> request,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization) {
        String folderPath = request.get("path");
        if (folderPath == null || folderPath.trim().isEmpty()) {
            return ResponseEntity.ok(ApiResponse.error("文件夹路径不能为空"));
        }

        String tenantId = resolveTenantIdFromAuthorizationHeader(authorization);
        try {
            if (tenantId != null && !tenantId.trim().isEmpty()) {
                TenantContext.setTenantId(tenantId);
            }

            String effectiveTenantId = (tenantId == null || tenantId.trim().isEmpty()) ? "default" : tenantId.trim();
            log.info(blue("[KB_TRACE_BLUE] /kb/create_folder called: tenantId={} folderPath='{}' (NOTE: folder creation only; does NOT ingest; does NOT create Milvus collection)"),
                effectiveTenantId,
                folderPath.trim());

            ApiResponse<String> response = knowledgeBaseService.createFolder(folderPath.trim());

            log.info(blue("[KB_TRACE_BLUE] /kb/create_folder result: tenantId={} success={} message='{}'"),
                effectiveTenantId,
                response != null && response.isSuccess(),
                response != null ? response.getMessage() : "null");

            return ResponseEntity.ok(response);
        } finally {
            TenantContext.clear();
        }
    }
    
    @PostMapping("/kb/rename")
    public ResponseEntity<ApiResponse<String>> renameItem(
            @RequestBody Map<String, String> request,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization) {
        String src = request.get("src");
        String dst = request.get("dst");
        
        log.info("[API] 重命名请求: src={}, dst={}", src, dst);
        
        if (src == null || src.trim().isEmpty() || dst == null || dst.trim().isEmpty()) {
            log.warn("[API] 重命名参数无效: src={}, dst={}", src, dst);
            return ResponseEntity.ok(ApiResponse.error("源路径和目标路径不能为空"));
        }
        
        String tenantId = resolveTenantIdFromAuthorizationHeader(authorization);
        log.info("[API] 重命名租户ID: {}", tenantId);
        try {
            if (tenantId != null && !tenantId.trim().isEmpty()) {
                TenantContext.setTenantId(tenantId);
            }
            ApiResponse<String> response = knowledgeBaseService.renameItem(src.trim(), dst.trim());
            log.info("[API] 重命名响应: {}", response);
            return ResponseEntity.ok(response);
        } finally {
            TenantContext.clear();
        }
    }
    
    @PostMapping("/kb/delete")
    public ResponseEntity<ApiResponse<String>> deleteItem(
            @RequestBody Map<String, String> request,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization) {
        String path = request.get("path");
        if (path == null || path.trim().isEmpty()) {
            return ResponseEntity.ok(ApiResponse.error("路径不能为空"));
        }

        String tenantId = resolveTenantIdFromAuthorizationHeader(authorization);
        try {
            if (tenantId != null && !tenantId.trim().isEmpty()) {
                TenantContext.setTenantId(tenantId);
            }
            ApiResponse<String> response = knowledgeBaseService.deleteItem(path.trim());
            return ResponseEntity.ok(response);
        } finally {
            TenantContext.clear();
        }
    }
    
    @DeleteMapping("/kb/files/{fileId}")
    public ResponseEntity<ApiResponse<String>> deleteFile(@PathVariable String fileId) {
        log.info("[删除] 收到文件删除请求，fileId: {}", fileId);

        // 验证fileId格式
        if (fileId == null || fileId.trim().isEmpty()) {
            log.warn("[删除] fileId为空");
            return ResponseEntity.ok(ApiResponse.error("文件ID不能为空"));
        }

        try {
            // 验证fileId是否为有效的整数
            Integer.parseInt(fileId);
        } catch (NumberFormatException e) {
            log.warn("[删除] fileId格式无效: {}", fileId);
            return ResponseEntity.ok(ApiResponse.error("文件ID格式无效"));
        }

        try {
            boolean success = knowledgeBaseService.deleteFile(fileId);
            if (success) {
                log.info("[删除] 文件删除成功，fileId: {}", fileId);
                return ResponseEntity.ok(ApiResponse.success("文件删除成功"));
            } else {
                log.warn("[删除] 文件删除失败，fileId: {}", fileId);
                return ResponseEntity.ok(ApiResponse.error("文件删除失败"));
            }
        } catch (Exception e) {
            log.error("[删除] 文件删除异常，fileId: {} - {}", fileId, e.getMessage(), e);
            return ResponseEntity.status(500).body(ApiResponse.error("文件删除失败: " + e.getMessage()));
        }
    }

    /**
     * 文件预览接口 - 返回文件内容用于预览
     */
    @GetMapping("/kb/files/{fileId}/preview")
    public ResponseEntity<?> previewFile(
            @PathVariable String fileId,
            @RequestParam(value = "token", required = false) String token,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization) {
        log.info("[预览] 收到文件预览请求，fileId: {}", fileId);

        // 支持 token 查询参数或 Authorization header
        String effectiveAuth = authorization;
        if ((authorization == null || authorization.trim().isEmpty()) && token != null && !token.trim().isEmpty()) {
            effectiveAuth = "Bearer " + token;
        }

        String tenantId = resolveTenantIdFromAuthorizationHeader(effectiveAuth);
        try {
            if (tenantId != null && !tenantId.trim().isEmpty()) {
                TenantContext.setTenantId(tenantId);
            }

            // 获取文件路径
            String filePath = knowledgeBaseService.getFilePathById(fileId);
            if (filePath == null || filePath.trim().isEmpty()) {
                log.warn("[预览] 文件不存在，fileId: {}", fileId);
                return ResponseEntity.ok(ApiResponse.error("文件不存在"));
            }

            Path kbRootPath = resolveKnowledgeBaseRootPath();
            Path fullPath = kbRootPath.resolve(filePath);

            if (!Files.exists(fullPath)) {
                log.warn("[预览] 文件物理路径不存在: {}", fullPath);
                return ResponseEntity.ok(ApiResponse.error("文件不存在"));
            }

            // 检查文件类型
            String fileName = fullPath.getFileName().toString().toLowerCase();
            String contentType = "application/octet-stream";

            if (fileName.endsWith(".pdf")) {
                contentType = "application/pdf";
            } else if (fileName.endsWith(".doc")) {
                contentType = "application/msword";
            } else if (fileName.endsWith(".docx")) {
                contentType = "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
            } else if (fileName.endsWith(".txt")) {
                contentType = "text/plain";
            } else if (fileName.endsWith(".md")) {
                contentType = "text/markdown; charset=utf-8";
            } else if (fileName.endsWith(".png") || fileName.endsWith(".jpg") || fileName.endsWith(".jpeg")) {
                contentType = Files.probeContentType(fullPath);
            }

            // 读取文件内容
            byte[] fileContent = Files.readAllBytes(fullPath);

            log.info("[预览] 文件预览成功，fileId: {}, size: {} bytes", fileId, fileContent.length);

            return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(contentType))
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + fullPath.getFileName().toString() + "\"")
                .body(fileContent);

        } catch (Exception e) {
            log.error("[预览] 文件预览异常，fileId: {} - {}", fileId, e.getMessage(), e);
            return ResponseEntity.status(500).body(ApiResponse.error("文件预览失败: " + e.getMessage()));
        } finally {
            TenantContext.clear();
        }
    }

    /**
     * 文件下载接口
     */
    @GetMapping("/kb/files/{fileId}/download")
    public ResponseEntity<?> downloadFile(
            @PathVariable String fileId,
            @RequestParam(value = "token", required = false) String token,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization) {
        log.info("[下载] 收到文件下载请求，fileId: {}", fileId);

        // 支持 token 查询参数或 Authorization header
        String effectiveAuth = authorization;
        if ((authorization == null || authorization.trim().isEmpty()) && token != null && !token.trim().isEmpty()) {
            effectiveAuth = "Bearer " + token;
        }

        String tenantId = resolveTenantIdFromAuthorizationHeader(effectiveAuth);
        try {
            if (tenantId != null && !tenantId.trim().isEmpty()) {
                TenantContext.setTenantId(tenantId);
            }

            // 获取文件路径
            String filePath = knowledgeBaseService.getFilePathById(fileId);
            if (filePath == null || filePath.trim().isEmpty()) {
                log.warn("[下载] 文件不存在，fileId: {}", fileId);
                return ResponseEntity.ok(ApiResponse.error("文件不存在"));
            }

            Path kbRootPath = resolveKnowledgeBaseRootPath();
            Path fullPath = kbRootPath.resolve(filePath);

            if (!Files.exists(fullPath)) {
                log.warn("[下载] 文件物理路径不存在: {}", fullPath);
                return ResponseEntity.ok(ApiResponse.error("文件不存在"));
            }

            // 读取文件内容
            byte[] fileContent = Files.readAllBytes(fullPath);
            String fileName = fullPath.getFileName().toString();

            log.info("[下载] 文件下载成功，fileId: {}, fileName: {}, size: {} bytes", fileId, fileName, fileContent.length);

            return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + fileName + "\"")
                .body(fileContent);

        } catch (Exception e) {
            log.error("[下载] 文件下载异常，fileId: {} - {}", fileId, e.getMessage(), e);
            return ResponseEntity.status(500).body(ApiResponse.error("文件下载失败: " + e.getMessage()));
        } finally {
            TenantContext.clear();
        }
    }

    /**
     * 通过路径预览文件
     */
    @GetMapping("/kb/preview")
    public ResponseEntity<?> previewFileByPath(
            @RequestParam String path,
            @RequestParam(value = "token", required = false) String token,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization) {
        log.info("[预览] 收到文件路径预览请求，path: {}", path);

        // 支持 token 查询参数或 Authorization header
        String effectiveAuth = authorization;
        if ((authorization == null || authorization.trim().isEmpty()) && token != null && !token.trim().isEmpty()) {
            effectiveAuth = "Bearer " + token;
        }

        String tenantId = resolveTenantIdFromAuthorizationHeader(effectiveAuth);
        try {
            if (tenantId != null && !tenantId.trim().isEmpty()) {
                TenantContext.setTenantId(tenantId);
            }

            if (path == null || path.trim().isEmpty()) {
                return ResponseEntity.ok(ApiResponse.error("文件路径不能为空"));
            }

            Path kbRootPath = resolveKnowledgeBaseRootPath();
            Path fullPath = kbRootPath.resolve(path);

            // 安全检查：防止路径穿越
            if (!fullPath.normalize().startsWith(kbRootPath.normalize())) {
                log.warn("[预览] 非法路径访问: {}", path);
                return ResponseEntity.status(403).body(ApiResponse.error("非法路径访问"));
            }

            if (!Files.exists(fullPath)) {
                log.warn("[预览] 文件不存在: {}", fullPath);
                return ResponseEntity.ok(ApiResponse.error("文件不存在"));
            }

            // 检查文件类型
            String fileName = fullPath.getFileName().toString().toLowerCase();
            String contentType = "application/octet-stream";

            if (fileName.endsWith(".pdf")) {
                contentType = "application/pdf";
            } else if (fileName.endsWith(".doc")) {
                contentType = "application/msword";
            } else if (fileName.endsWith(".docx")) {
                contentType = "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
            } else if (fileName.endsWith(".txt")) {
                contentType = "text/plain";
            } else if (fileName.endsWith(".md")) {
                contentType = "text/markdown; charset=utf-8";
            } else if (fileName.endsWith(".png") || fileName.endsWith(".jpg") || fileName.endsWith(".jpeg")) {
                contentType = Files.probeContentType(fullPath);
            }

            // 读取文件内容
            byte[] fileContent = Files.readAllBytes(fullPath);

            log.info("[预览] 文件路径预览成功，path: {}, size: {} bytes", path, fileContent.length);

            return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(contentType))
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + fullPath.getFileName().toString() + "\"")
                .body(fileContent);

        } catch (Exception e) {
            log.error("[预览] 文件路径预览异常，path: {} - {}", path, e.getMessage(), e);
            return ResponseEntity.status(500).body(ApiResponse.error("文件预览失败: " + e.getMessage()));
        } finally {
            TenantContext.clear();
        }
    }

    /**
     * Markdown 文件预览接口 - 返回渲染后的 HTML
     */
    @GetMapping("/kb/markdown_preview")
    public ResponseEntity<?> previewMarkdownByPath(
            @RequestParam String path,
            @RequestParam(value = "token", required = false) String token,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization) {
        log.info("[Markdown预览] 收到请求，path: {}", path);

        String effectiveAuth = authorization;
        if ((authorization == null || authorization.trim().isEmpty()) && token != null && !token.trim().isEmpty()) {
            effectiveAuth = "Bearer " + token;
        }

        String tenantId = resolveTenantIdFromAuthorizationHeader(effectiveAuth);
        try {
            if (tenantId != null && !tenantId.trim().isEmpty()) {
                TenantContext.setTenantId(tenantId);
            }

            if (path == null || path.trim().isEmpty()) {
                return ResponseEntity.ok(ApiResponse.error("文件路径不能为空"));
            }

            Path kbRootPath = resolveKnowledgeBaseRootPath();
            Path fullPath = kbRootPath.resolve(path);

            if (!fullPath.normalize().startsWith(kbRootPath.normalize())) {
                log.warn("[Markdown预览] 非法路径访问: {}", path);
                return ResponseEntity.status(403).body(ApiResponse.error("非法路径访问"));
            }

            if (!Files.exists(fullPath)) {
                log.warn("[Markdown预览] 文件不存在: {}", fullPath);
                return ResponseEntity.ok(ApiResponse.error("文件不存在"));
            }

            String fileName = fullPath.getFileName().toString().toLowerCase();
            if (!fileName.endsWith(".md") && !fileName.endsWith(".markdown")) {
                return ResponseEntity.ok(ApiResponse.error("仅支持 .md 或 .markdown 文件"));
            }

            // 读取 markdown 原文
            String markdownContent = Files.readString(fullPath);

            // 构造一个带样式的 HTML 页面返回给前端渲染
            String html = buildMarkdownHtml(markdownContent, fileName);

            return ResponseEntity.ok()
                .contentType(MediaType.TEXT_HTML)
                .body(html);

        } catch (Exception e) {
            log.error("[Markdown预览] 异常，path: {} - {}", path, e.getMessage(), e);
            return ResponseEntity.status(500).body(ApiResponse.error("预览失败: " + e.getMessage()));
        } finally {
            TenantContext.clear();
        }
    }

    /**
     * 将 markdown 内容渲染为独立 HTML 页面（含 GitHub 风格样式）
     */
    private String buildMarkdownHtml(String markdown, String fileName) {
        StringBuilder html = new StringBuilder();
        html.append("<!DOCTYPE html>");
        html.append("<html lang=\"zh-CN\">");
        html.append("<head>");
        html.append("<meta charset=\"UTF-8\">");
        html.append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">");
        html.append("<title>").append(escapeHtml(fileName)).append("</title>");
        html.append("<style>");
        html.append(getMarkdownStyles());
        html.append("</style>");
        html.append("</head>");
        html.append("<body>");
        html.append("<div class=\"markdown-body\">");
        html.append(convertMarkdownToHtml(markdown));
        html.append("</div>");
        html.append("</body>");
        html.append("</html>");
        return html.toString();
    }

    private String getMarkdownStyles() {
        return """
            * { box-sizing: border-box; margin: 0; padding: 0; }
            body {
                font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Helvetica, Arial, sans-serif,
                             'Apple Color Emoji', 'Segoe UI Emoji', 'Segoe UI Symbol', 'Noto Sans SC', 'Microsoft YaHei';
                font-size: 15px;
                line-height: 1.6;
                color: #24292e;
                background: #fff;
                padding: 24px 40px;
                max-width: 900px;
                margin: 0 auto;
            }
            .markdown-body h1, .markdown-body h2, .markdown-body h3,
            .markdown-body h4, .markdown-body h5, .markdown-body h6 {
                margin-top: 24px; margin-bottom: 16px; font-weight: 600;
                line-height: 1.25; border-bottom: 1px solid #eaecef; padding-bottom: 8px;
            }
            .markdown-body h1 { font-size: 2em; }
            .markdown-body h2 { font-size: 1.5em; }
            .markdown-body h3 { font-size: 1.25em; }
            .markdown-body h4 { font-size: 1em; }
            .markdown-body p { margin-bottom: 16px; }
            .markdown-body a { color: #0366d6; text-decoration: none; }
            .markdown-body a:hover { text-decoration: underline; }
            .markdown-body code {
                padding: 0.2em 0.4em; margin: 0; font-size: 85%;
                background-color: rgba(27,31,35,0.05); border-radius: 3px;
                font-family: 'SFMono-Regular', Consolas, 'Liberation Mono', Menlo, monospace;
            }
            .markdown-body pre {
                padding: 16px; overflow: auto; font-size: 85%;
                line-height: 1.45; background-color: #f6f8fa;
                border-radius: 3px; margin-bottom: 16px;
            }
            .markdown-body pre code {
                padding: 0; margin: 0; background: transparent; border: 0;
                font-size: 100%; word-break: normal; white-space: pre;
            }
            .markdown-body blockquote {
                padding: 0 1em; color: #6a737d; border-left: 0.25em solid #dfe2e5;
                margin: 0 0 16px 0;
            }
            .markdown-body table {
                border-collapse: collapse; width: 100%; margin-bottom: 16px;
            }
            .markdown-body table th, .markdown-body table td {
                padding: 6px 13px; border: 1px solid #dfe2e5;
            }
            .markdown-body table th {
                font-weight: 600; background-color: #f6f8fa;
            }
            .markdown-body table tr:nth-child(2n) { background-color: #f6f8fa; }
            .markdown-body ul, .markdown-body ol {
                padding-left: 2em; margin-bottom: 16px;
            }
            .markdown-body li + li { margin-top: 0.25em; }
            .markdown-body hr {
                height: 0.25em; padding: 0; margin: 24px 0;
                background-color: #e1e4e8; border: 0;
            }
            .markdown-body img {
                max-width: 100%; box-sizing: content-box;
                display: block; margin: 0 auto;
            }
            .markdown-body strong { font-weight: 600; }
            .markdown-body em { font-style: italic; }
            @media (max-width: 768px) {
                body { padding: 12px 16px; }
            }
            """;
    }

    /**
     * 简单 markdown → HTML 转换（处理常用语法）
     */
    private String convertMarkdownToHtml(String md) {
        if (md == null) return "";
        String html = escapeHtml(md);

        // 标题
        html = html.replaceAll("(?m)^###### (.+)$", "<h6>$1</h6>");
        html = html.replaceAll("(?m)^##### (.+)$", "<h5>$1</h5>");
        html = html.replaceAll("(?m)^#### (.+)$", "<h4>$1</h4>");
        html = html.replaceAll("(?m)^### (.+)$", "<h3>$1</h3>");
        html = html.replaceAll("(?m)^## (.+)$", "<h2>$1</h2>");
        html = html.replaceAll("(?m)^# (.+)$", "<h1>$1</h1>");

        // 代码块（``` 包裹）
        html = html.replaceAll("(?s)```(\\w*)\\r?\\n(.*?)```", "<pre><code>$2</code></pre>");

        // 行内代码
        html = html.replaceAll("`([^`]+)`", "<code>$1</code>");

        // 粗体和斜体
        html = html.replaceAll("\\*\\*\\*(.+?)\\*\\*\\*", "<strong><em>$1</em></strong>");
        html = html.replaceAll("\\*\\*(.+?)\\*\\*", "<strong>$1</strong>");
        html = html.replaceAll("\\*(.+?)\\*", "<em>$1</em>");
        html = html.replaceAll("___(.+?)___", "<strong><em>$1</em></strong>");
        html = html.replaceAll("__(.+?)__", "<strong>$1</strong>");
        html = html.replaceAll("_(.+?)_", "<em>$1</em>");

        // 链接
        html = html.replaceAll("\\[([^\\]]+)\\]\\(([^)]+)\\)", "<a href=\"$2\" target=\"_blank\">$1</a>");

        // 图片
        html = html.replaceAll("!\\[([^\\]]*)\\]\\(([^)]+)\\)", "<img src=\"$2\" alt=\"$1\" />");

        // 引用块
        html = html.replaceAll("(?m)^&gt; (.+)$", "<blockquote>$1</blockquote>");

        // 无序列表
        html = html.replaceAll("(?m)^[-*] (.+)$", "<li>$1</li>");
        html = html.replaceAll("(?s)(<li>.*?</li>\\n?)+", "<ul>$0</ul>");

        // 有序列表
        html = html.replaceAll("(?m)^\\d+\\. (.+)$", "<li>$1</li>");

        // 水平线
        html = html.replaceAll("(?m)^---+$", "<hr>");
        html = html.replaceAll("(?m)^\\*\\*\\*+$", "<hr>");

        // 段落（用双换行分割）
        String[] paragraphs = html.split("\\n\\n+");
        StringBuilder result = new StringBuilder();
        for (String para : paragraphs) {
            String trimmed = para.trim();
            if (trimmed.isEmpty()) continue;
            // 已经是块级元素则不包裹
            if (trimmed.matches("^<(h[1-6]|pre|blockquote|ul|ol|li|hr|img).*$")) {
                result.append(trimmed).append("\n");
            } else {
                result.append("<p>").append(trimmed.replaceAll("\\n", "<br>")).append("</p>\n");
            }
        }
        return result.toString();
    }

    private String escapeHtml(String text) {
        if (text == null) return "";
        return text
            .replaceAll("&", "&amp;")
            .replaceAll("<", "&lt;")
            .replaceAll(">", "&gt;")
            .replaceAll("\"", "&quot;")
            .replaceAll("'", "&#39;");
    }

    /**
     * 通过路径下载文件
     */
    @GetMapping("/kb/download")
    public ResponseEntity<?> downloadFileByPath(
            @RequestParam String path,
            @RequestParam(value = "token", required = false) String token,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization) {
        log.info("[下载] 收到文件路径下载请求，path: {}", path);

        // 支持 token 查询参数或 Authorization header
        String effectiveAuth = authorization;
        if ((authorization == null || authorization.trim().isEmpty()) && token != null && !token.trim().isEmpty()) {
            effectiveAuth = "Bearer " + token;
        }

        String tenantId = resolveTenantIdFromAuthorizationHeader(effectiveAuth);
        try {
            if (tenantId != null && !tenantId.trim().isEmpty()) {
                TenantContext.setTenantId(tenantId);
            }

            if (path == null || path.trim().isEmpty()) {
                return ResponseEntity.ok(ApiResponse.error("文件路径不能为空"));
            }

            Path kbRootPath = resolveKnowledgeBaseRootPath();
            Path fullPath = kbRootPath.resolve(path);

            // 安全检查：防止路径穿越
            if (!fullPath.normalize().startsWith(kbRootPath.normalize())) {
                log.warn("[下载] 非法路径访问: {}", path);
                return ResponseEntity.status(403).body(ApiResponse.error("非法路径访问"));
            }

            if (!Files.exists(fullPath)) {
                log.warn("[下载] 文件不存在: {}", fullPath);
                return ResponseEntity.ok(ApiResponse.error("文件不存在"));
            }

            // 读取文件内容
            byte[] fileContent = Files.readAllBytes(fullPath);
            String fileName = fullPath.getFileName().toString();

            log.info("[下载] 文件路径下载成功，path: {}, fileName: {}, size: {} bytes", path, fileName, fileContent.length);

            return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + fileName + "\"")
                .body(fileContent);

        } catch (Exception e) {
            log.error("[下载] 文件路径下载异常，path: {} - {}", path, e.getMessage(), e);
            return ResponseEntity.status(500).body(ApiResponse.error("文件下载失败: " + e.getMessage()));
        } finally {
            TenantContext.clear();
        }
    }

    /**
     * PDF 页面预览 - 将指定 PDF 的指定页渲染为 PNG 图片返回
     *
     * @param source   文档 source 路径（Milvus metadata 中的 source 字段，如 "建筑/GB 50007-2011 建筑地基基础设计规范.pdf"）
     * @param page     页码（1-based，默认 1）
     * @param tenantId 租户ID（可选，默认 admin）
     */
    @GetMapping("/kb/page-preview")
    public ResponseEntity<?> previewPdfPage(
            @RequestParam String source,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(value = "tenantId", required = false) String tenantId,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization) {
        log.info("[页面预览] source={}, page={}, tenantId={}", source, page, tenantId);

        // 解析租户ID
        String effectiveTenantId = tenantId;
        if (effectiveTenantId == null || effectiveTenantId.trim().isEmpty()) {
            effectiveTenantId = resolveTenantIdFromAuthorizationHeader(authorization);
        }
        if (effectiveTenantId == null || effectiveTenantId.trim().isEmpty()) {
            effectiveTenantId = "admin";
        }

        try {
            // 根据租户ID确定知识库根路径
            Path kbRootPath;
            if ("admin".equals(effectiveTenantId) || "default".equals(effectiveTenantId)) {
                kbRootPath = Paths.get(appConfig.getKnowledgeBase().getPath());
            } else {
                String tenantKbPath = TenantUtils.buildTenantKnowledgeBasePath(
                    effectiveTenantId, null,
                    appConfig.getTenant().getStorageMode(),
                    appConfig.getTenant().getTenantKbPath()
                );
                kbRootPath = Paths.get(tenantKbPath);
            }

            // 查找 PDF 文件：先尝试直接拼接，再尝试模糊搜索
            Path pdfPath = findPdfFile(kbRootPath, source);

            if (pdfPath == null) {
                log.warn("[页面预览] 未找到PDF文件: source={}, kbRoot={}", source, kbRootPath);
                return ResponseEntity.status(404)
                    .body(Map.of("error", "PDF文件未找到: " + source));
            }

            // 渲染页面为 PNG
            byte[] pngBytes = pdfPreviewService.renderPage(pdfPath.toString(), page);

            log.info("[页面预览] 渲染成功: source={}, page={}, size={}KB", source, page, pngBytes.length / 1024);

            return ResponseEntity.ok()
                .contentType(MediaType.IMAGE_PNG)
                .header(HttpHeaders.CACHE_CONTROL, "max-age=3600, public")
                .body(pngBytes);

        } catch (IOException e) {
            log.error("[页面预览] 渲染失败: source={}, page={}, error={}", source, page, e.getMessage());
            return ResponseEntity.status(500)
                .body(Map.of("error", "页面渲染失败: " + e.getMessage()));
        } catch (Exception e) {
            log.error("[页面预览] 异常: source={}, page={}, error={}", source, page, e.getMessage());
            return ResponseEntity.status(500)
                .body(Map.of("error", "预览失败: " + e.getMessage()));
        }
    }

    /**
     * 在知识库根目录下查找 PDF 文件
     * 1. 直接拼接路径
     * 2. 提取文件名精确匹配
     * 3. 模糊匹配（去除空格/连字符后匹配，或提取 GB 标准号匹配）
     */
    private Path findPdfFile(Path kbRoot, String source) throws IOException {
        if (source == null || source.trim().isEmpty()) return null;

        // 1. 直接拼接
        Path direct = kbRoot.resolve(source);
        if (Files.exists(direct) && Files.isRegularFile(direct)) return direct;

        // 2. 提取文件名，在所有子目录中搜索
        String fileName = source;
        if (source.contains("/")) {
            fileName = source.substring(source.lastIndexOf("/") + 1);
        } else if (source.contains("\\")) {
            fileName = source.substring(source.lastIndexOf("\\") + 1);
        }

        // 4. 模糊匹配：去除空格/连字符后匹配，若失败则提取 GB 标准号匹配
        if (Files.exists(kbRoot)) {
            String coreName = fileName;
            for (String suffix : Arrays.asList(".pdf", ".PDF", ".doc", ".DOC", ".docx", ".DOCX")) {
                if (coreName.toLowerCase().endsWith(suffix)) {
                    coreName = coreName.substring(0, coreName.length() - suffix.length());
                    break;
                }
            }
            final String searchKey = coreName.replaceAll("[\\s\\-_]", "").toLowerCase();
            if (!searchKey.isEmpty()) {
                Path[] found = new Path[1];
                Files.walk(kbRoot)
                    .filter(Files::isRegularFile)
                    .filter(p -> {
                        String fname = p.getFileName().toString();
                        String fnameNorm = fname.replaceAll("[\\s\\-_]", "").toLowerCase();
                        String fnameCore = fnameNorm;
                        for (String suffix : Arrays.asList(".pdf", ".doc", ".docx")) {
                            if (fnameCore.endsWith(suffix)) {
                                fnameCore = fnameCore.substring(0, fnameCore.length() - suffix.length());
                                break;
                            }
                        }
                        if (fnameCore.contains(searchKey) || searchKey.contains(fnameCore)) return true;
                        // 提取 GB 标准号进行匹配（如 GB50007、GB50007-2011）
                        java.util.regex.Pattern gbPattern = java.util.regex.Pattern.compile("gb\\d{4,}(?:-\\d{4,})?", java.util.regex.Pattern.CASE_INSENSITIVE);
                        java.util.regex.Matcher srcMatcher = gbPattern.matcher(searchKey);
                        java.util.regex.Matcher fnameMatcher = gbPattern.matcher(fnameCore);
                        if (srcMatcher.find() && fnameMatcher.find()) {
                            return srcMatcher.group().equalsIgnoreCase(fnameMatcher.group());
                        }
                        return false;
                    })
                    .findFirst()
                    .ifPresent(p -> found[0] = p);
                if (found[0] != null) return found[0];
            }
        }

        return null;
    }

    @PostMapping("/kb/files/batch-delete")
    public ResponseEntity<ApiResponse<Map<String, Object>>> batchDeleteFiles(@RequestBody Map<String, Object> request) {
        log.info("[批量删除] 收到批量删除请求: {}", request);
        
        try {
            @SuppressWarnings("unchecked")
            List<Object> fileIdsRaw = (List<Object>) request.get("fileIds");
            String knowledgeBaseId = (String) request.get("knowledgeBaseId");
            
            if (fileIdsRaw == null || fileIdsRaw.isEmpty()) {
                log.warn("[批量删除] fileIds为空");
                return ResponseEntity.ok(ApiResponse.error("文件ID列表不能为空"));
            }
            
            if (knowledgeBaseId == null || knowledgeBaseId.trim().isEmpty()) {
                log.warn("[批量删除] knowledgeBaseId为空");
                return ResponseEntity.ok(ApiResponse.error("知识库ID不能为空"));
            }
            
            // 转换fileIds为字符串列表，处理整数和字符串两种情况
            List<String> fileIds = new ArrayList<>();
            for (Object fileIdObj : fileIdsRaw) {
                String fileId;
                if (fileIdObj instanceof Integer) {
                    fileId = String.valueOf((Integer) fileIdObj);
                } else if (fileIdObj instanceof String) {
                    fileId = (String) fileIdObj;
                } else {
                    log.warn("[批量删除] fileId类型无效: {}", fileIdObj.getClass().getSimpleName());
                    return ResponseEntity.ok(ApiResponse.error("文件ID类型无效: " + fileIdObj));
                }
                fileIds.add(fileId);
            }
            
            // 验证所有fileId格式
            for (String fileId : fileIds) {
                try {
                    Integer.parseInt(fileId);
                } catch (NumberFormatException e) {
                    log.warn("[批量删除] fileId格式无效: {}", fileId);
                    return ResponseEntity.ok(ApiResponse.error("文件ID格式无效: " + fileId));
                }
            }
            
            log.info("[批量删除] 开始删除 {} 个文件，知识库: {}", fileIds.size(), knowledgeBaseId);
            
            int successCount = 0;
            int failCount = 0;
            List<String> failedFiles = new ArrayList<>();
            
            for (String fileId : fileIds) {
                try {
                    boolean success = knowledgeBaseService.deleteFile(fileId);
                    if (success) {
                        successCount++;
                        log.info("[批量删除] 文件删除成功，fileId: {}", fileId);
                    } else {
                        failCount++;
                        failedFiles.add(fileId);
                        log.warn("[批量删除] 文件删除失败，fileId: {}", fileId);
                    }
                } catch (Exception e) {
                    failCount++;
                    failedFiles.add(fileId);
                    log.error("[批量删除] 文件删除异常，fileId: {} - {}", fileId, e.getMessage());
                }
            }
            
            Map<String, Object> result = new HashMap<>();
            result.put("totalCount", fileIds.size());
            result.put("deletedCount", successCount);
            result.put("failedCount", failCount);
            result.put("failedFiles", failedFiles);
            
            if (successCount == fileIds.size()) {
                log.info("[批量删除] 所有文件删除成功，共 {} 个", successCount);
                return ResponseEntity.ok(ApiResponse.success("批量删除成功", result));
            } else if (successCount > 0) {
                log.warn("[批量删除] 部分文件删除成功，成功: {}, 失败: {}", successCount, failCount);
                return ResponseEntity.ok(ApiResponse.success("部分文件删除成功", result));
            } else {
                log.error("[批量删除] 所有文件删除失败");
                return ResponseEntity.ok(ApiResponse.error("所有文件删除失败"));
            }
            
        } catch (Exception e) {
            log.error("[批量删除] 批量删除异常: {}", e.getMessage(), e);
            return ResponseEntity.status(500).body(ApiResponse.error("批量删除失败: " + e.getMessage()));
        }
    }
    
    public static class LoginRequest {
        public String username;
        public String password;
        @Override
        public String toString() {
            return "LoginRequest{" +
                    "username='" + username + '\'' +
                    ", password='" + password + '\'' +
                    '}';
        }
    }

    @PostMapping("/token")
    public Mono<ResponseEntity<Map<String, String>>> createToken(@RequestBody LoginRequest credentials) {
        System.err.println("=== 登录接口被调用 ===");
        System.err.println("收到登录请求: " + credentials);
        System.err.println("请求体内容: username=" + credentials.username + ", password=" + credentials.password);
        
        try {
            String username = credentials.username;
            String password = credentials.password;
            System.err.println("用户名: " + username + ", 密码: " + password);
            Authentication authentication = new UsernamePasswordAuthenticationToken(username, password);
            return authenticationManager.authenticate(authentication)
                    .map(auth -> {
                        System.err.println("认证成功: " + auth);
                        String token = jwtTokenProvider.generateToken(auth);
                        System.err.println("生成token: " + token);
                        
                        // 异步初始化用户的默认知识库（不阻塞登录响应）
                        // 使用用户名作为租户ID
                        String userId = credentials.username;
                        java.util.concurrent.CompletableFuture.runAsync(() -> {
                            try {
                                defaultKnowledgeBaseService.initializeDefaultKnowledgeBase(userId);
                            } catch (Exception e) {
                                log.error("[Login] 初始化默认知识库失败: userId={}, error={}", userId, e.getMessage());
                            }
                        });
                        
                        return ResponseEntity.ok(Map.of("token", token, "type", "Bearer"));
                    })
                    .doOnError(e -> {
                        System.err.println("认证异常: " + e);
                        e.printStackTrace();
                    });
        } catch (Exception e) {
            System.err.println("Controller 方法异常: " + e);
            e.printStackTrace();
            return Mono.error(e);
        }
    }

    private String resolveTenantIdFromAuthorizationHeader(String authorization) {
        if (authorization == null || authorization.trim().isEmpty()) {
            return null;
        }
        String value = authorization.trim();
        if (!value.startsWith("Bearer ")) {
            return null;
        }
        String token = value.substring(7);
        if (!jwtTokenProvider.validateToken(token)) {
            return null;
        }
        try {
            return jwtTokenProvider.getTenantIdFromJWT(token);
        } catch (Exception e) {
            log.debug("从JWT解析tenantId失败: {}", e.getMessage());
            return null;
        }
    }

    private <T> Mono<T> withTenantContext(String tenantId, Mono<T> mono) {
        return Mono.defer(() -> {
            if (tenantId != null && !tenantId.trim().isEmpty()) {
                TenantContext.setTenantId(tenantId);
            }
            return mono;
        }).doFinally(signal -> TenantContext.clear());
    }

    private <T> Flux<T> withTenantContext(String tenantId, Flux<T> flux) {
        return Flux.defer(() -> {
            if (tenantId != null && !tenantId.trim().isEmpty()) {
                TenantContext.setTenantId(tenantId);
            }
            return flux;
        }).doFinally(signal -> TenantContext.clear());
    }

    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Mono<ResponseEntity<ApiResponse<String>>> uploadFiles(
            @RequestPart("files") Flux<FilePart> files,
            @RequestPart(value = "category", required = false) Mono<String> categoryMono,
            @RequestPart(value = "knowledgeBaseId", required = false) Mono<String> knowledgeBaseIdMono,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization) {
        String tenantId = resolveTenantIdFromAuthorizationHeader(authorization);
        final String finalTenantId = tenantId;
        return withTenantContext(tenantId, Mono.defer(() -> {
            Path kbRootPath = resolveKnowledgeBaseRootPath();
            String basePath = kbRootPath.toString();

            return Mono.zip(categoryMono.defaultIfEmpty(""), knowledgeBaseIdMono.defaultIfEmpty(""))
            .flatMap(tuple -> {
                String category = tuple.getT1();
                String knowledgeBaseId = tuple.getT2();
                // 磁盘路径优先使用knowledgeBaseId（包含完整相对路径），否则回退到category
                String targetPath = (knowledgeBaseId != null && !knowledgeBaseId.isEmpty()) 
                    ? knowledgeBaseId 
                    : (category == null ? "" : category);

                // 重要：Milvus集合名不要用空字符串。
                // 如果前端没传 knowledgeBaseId（常见于节点只有 name/path 没有 id），则用 targetPath 作为集合标识；
                // targetPath 也为空时回退到 default_knowledge_base。
                String effectiveKnowledgeBaseId = (knowledgeBaseId != null && !knowledgeBaseId.trim().isEmpty())
                    ? knowledgeBaseId.trim()
                    : (!targetPath.trim().isEmpty() ? targetPath.trim() : "default_knowledge_base");

                final String finalCategory = category;
                final String finalKnowledgeBaseId = effectiveKnowledgeBaseId;
                final String finalTargetPath = targetPath;
                log.info("=== 文件上传开始 ===");
                log.info("上传文件到分类: {}", finalCategory);
                log.info("知识库ID: {}", finalKnowledgeBaseId);
                log.info("最终使用的存储路径: {}", finalTargetPath);
                log.info("最终使用的集合名称: {}", finalKnowledgeBaseId);
                log.info("Debug - category.isEmpty(): {}, knowledgeBaseId.isEmpty(): {}", 
                    finalCategory == null ? "null" : finalCategory.isEmpty(), finalKnowledgeBaseId == null ? "null" : finalKnowledgeBaseId.isEmpty());
                
                return files
                .flatMap(file -> {
                    String filePath = finalTargetPath.isEmpty() ? file.filename() : finalTargetPath + File.separator + file.filename();
                    Path savePath = Paths.get(basePath, filePath);
                    // 防止路径穿越
                    if (finalTargetPath.contains("..") || finalTargetPath.startsWith("/") || finalTargetPath.startsWith("\\")) {
                        log.error("非法目录参数: targetPath={}, filePath={}, savePath={}", finalTargetPath, filePath, savePath);
                        return Mono.error(new RuntimeException("目录参数非法: " + finalTargetPath));
                    }
                    final String finalFilePath = filePath;
                    return file.content().reduce(new byte[0], (a, b) -> {
                        byte[] bytes = new byte[a.length + b.readableByteCount()];
                        System.arraycopy(a, 0, bytes, 0, a.length);
                        b.read(bytes, a.length, b.readableByteCount());
                        return bytes;
                    })
                    .flatMap(bytes -> Mono.fromCallable(() -> {
                        try {
                            if (savePath.getParent() != null && !Files.exists(savePath.getParent())) {
                                Files.createDirectories(savePath.getParent());
                            }
                            Files.write(savePath, bytes);
                            log.info("文件已保存: {}", savePath);
                            return finalFilePath;
                        } catch (IOException e) {
                            log.error("文件保存失败: category={}, filePath={}, savePath={}, error={}", finalCategory, finalFilePath, savePath, e.getMessage(), e);
                            throw new RuntimeException("文件保存失败: " + e.getMessage());
                        }
                    }))
                            .then(Mono.defer(() -> {
                                log.info(blue("[KB_TRACE_BLUE] upload saved -> trigger ingest: tenantId={} knowledgeBaseId='{}' filePath='{}'"),
                                    finalTenantId,
                                    finalKnowledgeBaseId,
                                    finalFilePath);
                                return ragService.addDocument(finalFilePath, finalKnowledgeBaseId, finalTenantId);
                            }))
                    .map(message -> file.filename() + ": " + message + "; ");
                })
                .collectList()
                .map(results -> String.join("", results))
                .map(result -> {
                    log.info("文件上传处理完成: {}", result);
                    return ResponseEntity.ok(ApiResponse.success("文件上传成功", result));
                })
                .onErrorResume(e -> {
                    log.error("文件上传失败: category={}, knowledgeBaseId={}, error={}", finalCategory, finalKnowledgeBaseId, e.getMessage(), e);
                    return Mono.just(ResponseEntity.ok(ApiResponse.<String>error("文件上传失败: " + e.getMessage())));
                });
            });
        }));
    }

    @PostMapping("/add_document")
    public Mono<ResponseEntity<ApiResponse<String>>> addDocument(@RequestBody Map<String, String> request, @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization) {
        String filePath = request.get("filePath");
        String knowledgeBaseId = request.get("knowledgeBaseId");
        if (filePath == null || filePath.trim().isEmpty()) {
            return Mono.just(ResponseEntity.ok(ApiResponse.error("filePath不能为空")));
        }
        String tenantId = resolveTenantIdFromAuthorizationHeader(authorization);

        String effectiveTenantId = (tenantId == null || tenantId.trim().isEmpty()) ? "default" : tenantId.trim();
        log.info(blue("[KB_TRACE_BLUE] /add_document called: tenantId={} knowledgeBaseId='{}' filePath='{}'"),
            effectiveTenantId,
            knowledgeBaseId,
            filePath);

        return ragService.addDocument(filePath, knowledgeBaseId, tenantId)
                        .map(result -> ResponseEntity.ok(ApiResponse.success("文档处理结果", result)))
                .onErrorResume(e -> Mono.just(ResponseEntity.ok(ApiResponse.error("文档处理失败: " + e.getMessage()))));
    }
    
    @PostMapping("/reindex_all")
    public Mono<ResponseEntity<ApiResponse<String>>> reindexAllDocuments() {
        log.info("开始重新索引所有文档");
        return knowledgeBaseService.reindexAllDocuments()
                .map(result -> ResponseEntity.ok(ApiResponse.success("重新索引完成", result)))
                .onErrorResume(e -> Mono.just(ResponseEntity.ok(ApiResponse.error("重新索引失败: " + e.getMessage()))));
    }
    
    @PostMapping("/sync_vector_db")
    public Mono<ResponseEntity<ApiResponse<String>>> syncVectorDatabase() {
        log.info("开始同步向量数据库");
        return knowledgeBaseService.syncVectorDatabase()
                .map(result -> ResponseEntity.ok(ApiResponse.success("向量数据库同步完成", result)))
                .onErrorResume(e -> Mono.just(ResponseEntity.ok(ApiResponse.error("向量数据库同步失败: " + e.getMessage()))));
    }
    
    @PostMapping("/force_sync")
    public Mono<ResponseEntity<ApiResponse<String>>> forceSync() {
        log.info("开始强制同步向量数据库");
        return knowledgeBaseService.forceSyncVectorDatabase()
                .map(result -> ResponseEntity.ok(ApiResponse.success("强制同步完成", result)))
                .onErrorResume(e -> Mono.just(ResponseEntity.ok(ApiResponse.error("强制同步失败: " + e.getMessage()))));
    }
    

    
    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        Map<String, Object> health = new HashMap<>();
        health.put("status", "running");
        health.put("message", "LuanShuAgent Backend API 运行正常");
        health.put("timestamp", System.currentTimeMillis());
        return ResponseEntity.ok(health);
    }

    /**
     * 按租户解析知识库根路径，确保上传与文件列表使用同一目录。
     */
    private Path resolveKnowledgeBaseRootPath() {
        String tenantId = TenantContext.getCurrentTenantId();
        // 当 tenantId 为 null、"default" 或空时，使用默认知识库路径
        if (tenantId == null || tenantId.trim().isEmpty() || "default".equals(tenantId) || "admin".equals(tenantId)) {
            return Paths.get(appConfig.getKnowledgeBase().getPath());
        }

        String tenantKbPath = TenantUtils.buildTenantKnowledgeBasePath(
            tenantId,
            null,
            appConfig.getTenant().getStorageMode(),
            appConfig.getTenant().getTenantKbPath()
        );

        Path tenantPath = Paths.get(tenantKbPath);
        if (!Files.exists(tenantPath)) {
            try {
                Files.createDirectories(tenantPath);
            } catch (IOException e) {
                log.error("[KB] 创建租户知识库目录失败: tenantId={}, error={}", tenantId, e.getMessage());
            }
        }
        return tenantPath;
    }
    

    
    @GetMapping("/health/services")
    public ResponseEntity<Map<String, Object>> servicesHealth() {
        Map<String, Object> healthStatus = new HashMap<>();
        healthStatus.put("message", "健康检查服务已简化");
        healthStatus.put("allHealthy", true);
        return ResponseEntity.ok(healthStatus);
    }

    // ==================== CAD/BIM 解析接口 ====================

    /**
     * 解析CAD文件
     */
    @PostMapping("/cad/parse")
    public Mono<ResponseEntity<ApiResponse<CadDrawingResult>>> parseCadFile(@RequestBody Map<String, String> request) {
        String filePath = request.get("filePath");
        if (filePath == null || filePath.trim().isEmpty()) {
            return Mono.just(ResponseEntity.ok(ApiResponse.error("文件路径不能为空")));
        }
        
        return cadParserService.parseCadFile(filePath.trim())
            .map(result -> ResponseEntity.ok(ApiResponse.success("CAD文件解析成功", result)))
            .onErrorResume(e -> Mono.just(ResponseEntity.ok(ApiResponse.error("CAD文件解析失败: " + e.getMessage()))));
    }

    /**
     * 获取CAD文件信息
     */
    @GetMapping("/cad/info")
    public Mono<ResponseEntity<ApiResponse<Map<String, Object>>>> getCadFileInfo(@RequestParam String filePath) {
        if (filePath == null || filePath.trim().isEmpty()) {
            return Mono.just(ResponseEntity.ok(ApiResponse.error("文件路径不能为空")));
        }
        
        return cadParserService.getCadFileInfo(filePath.trim())
            .map(result -> ResponseEntity.ok(ApiResponse.success("获取CAD文件信息成功", result)))
            .onErrorResume(e -> Mono.just(ResponseEntity.ok(ApiResponse.error("获取CAD文件信息失败: " + e.getMessage()))));
    }

    /**
     * 提取CAD文件文本
     */
    @PostMapping("/cad/extract-text")
    public Mono<ResponseEntity<ApiResponse<Map<String, Object>>>> extractCadText(@RequestBody Map<String, String> request) {
        String filePath = request.get("filePath");
        if (filePath == null || filePath.trim().isEmpty()) {
            return Mono.just(ResponseEntity.ok(ApiResponse.error("文件路径不能为空")));
        }
        
        return cadParserService.extractTextFromCad(filePath.trim())
            .map(result -> ResponseEntity.ok(ApiResponse.success("CAD文本提取成功", result)))
            .onErrorResume(e -> Mono.just(ResponseEntity.ok(ApiResponse.error("CAD文本提取失败: " + e.getMessage()))));
    }

    /**
     * 解析BIM文件
     */
    @PostMapping("/bim/parse")
    public Mono<ResponseEntity<ApiResponse<BimModelResult>>> parseBimFile(@RequestBody Map<String, String> request) {
        String filePath = request.get("filePath");
        if (filePath == null || filePath.trim().isEmpty()) {
            return Mono.just(ResponseEntity.ok(ApiResponse.error("文件路径不能为空")));
        }
        
        return bimParserService.parseBimFile(filePath.trim())
            .map(result -> ResponseEntity.ok(ApiResponse.success("BIM文件解析成功", result)))
            .onErrorResume(e -> Mono.just(ResponseEntity.ok(ApiResponse.error("BIM文件解析失败: " + e.getMessage()))));
    }

    /**
     * 获取BIM文件信息
     */
    @GetMapping("/bim/info")
    public Mono<ResponseEntity<ApiResponse<Map<String, Object>>>> getBimFileInfo(@RequestParam String filePath) {
        if (filePath == null || filePath.trim().isEmpty()) {
            return Mono.just(ResponseEntity.ok(ApiResponse.error("文件路径不能为空")));
        }
        
        return bimParserService.getBimFileInfo(filePath.trim())
            .map(result -> ResponseEntity.ok(ApiResponse.success("获取BIM文件信息成功", result)))
            .onErrorResume(e -> Mono.just(ResponseEntity.ok(ApiResponse.error("获取BIM文件信息失败: " + e.getMessage()))));
    }

    /**
     * 提取BIM模型元素
     */
    @PostMapping("/bim/extract-elements")
    public Mono<ResponseEntity<ApiResponse<Map<String, Object>>>> extractBimElements(@RequestBody Map<String, String> request) {
        String filePath = request.get("filePath");
        if (filePath == null || filePath.trim().isEmpty()) {
            return Mono.just(ResponseEntity.ok(ApiResponse.error("文件路径不能为空")));
        }
        
        return bimParserService.extractElementsFromBim(filePath.trim())
            .map(result -> ResponseEntity.ok(ApiResponse.success("BIM元素提取成功", result)))
            .onErrorResume(e -> Mono.just(ResponseEntity.ok(ApiResponse.error("BIM元素提取失败: " + e.getMessage()))));
    }

    /**
     * 生成BIM碰撞检测报告
     */
    @PostMapping("/bim/collision-report")
    public Mono<ResponseEntity<ApiResponse<Map<String, Object>>>> generateCollisionReport(@RequestBody Map<String, String> request) {
        String filePath = request.get("filePath");
        if (filePath == null || filePath.trim().isEmpty()) {
            return Mono.just(ResponseEntity.ok(ApiResponse.error("文件路径不能为空")));
        }
        
        return bimParserService.generateCollisionReport(filePath.trim())
            .map(result -> ResponseEntity.ok(ApiResponse.success("BIM碰撞检测报告生成成功", result)))
            .onErrorResume(e -> Mono.just(ResponseEntity.ok(ApiResponse.error("BIM碰撞检测报告生成失败: " + e.getMessage()))));
    }

    /**
     * 检查CAD/BIM解析服务状态
     */
    @GetMapping("/cad-bim/health")
    public Mono<ResponseEntity<Map<String, Object>>> checkCadBimHealth() {
        return Mono.zip(
            cadParserService.checkServiceHealth(),
            bimParserService.checkServiceHealth()
        ).map(tuple -> {
            boolean cadHealthy = tuple.getT1();
            boolean bimHealthy = tuple.getT2();
            
            Map<String, Object> healthStatus = new HashMap<>();
            healthStatus.put("cadService", cadHealthy ? "healthy" : "unhealthy");
            healthStatus.put("bimService", bimHealthy ? "healthy" : "unhealthy");
            healthStatus.put("allHealthy", cadHealthy && bimHealthy);
            healthStatus.put("timestamp", System.currentTimeMillis());
            
            return ResponseEntity.ok(healthStatus);
        }).onErrorReturn(ResponseEntity.ok(Map.of(
            "cadService", "unknown",
            "bimService", "unknown", 
            "allHealthy", false,
            "error", "服务检查失败"
        )));
    }
    
    // =============== AI Agent 配置端点 ===============

    /**
     * 获取当前 LLM 配置
     */
    @GetMapping("/agent/config")
    public ResponseEntity<?> getAgentConfig() {
        Map<String, Object> config = new HashMap<>();
        config.put("provider", llmService.getProvider());
        config.put("url", llmService.getLocalUrl());
        config.put("model", llmService.getChatModel());
        config.put("embeddingModel", llmService.getEmbeddingModel());
        config.put("embeddingUrl", llmService.getEmbeddingUrl());
        config.put("cloudProvider", llmService.getCloudProvider());
        config.put("cloudBaseUrl", llmService.getCloudBaseUrl());
        // 不返回 API key（安全考虑）
        config.put("cloudApiKeySet", llmService.getCloudApiKey() != null && !llmService.getCloudApiKey().isEmpty());
        config.put("warmupDone", true); // llama.cpp 不需要预热
        return ResponseEntity.ok(ApiResponse.success("ok", config));
    }

    /**
     * 更新 LLM 配置（运行时）
     */
    @PostMapping("/agent/config")
    public ResponseEntity<?> updateAgentConfig(@RequestBody Map<String, Object> body) {
        try {
            if (body.containsKey("provider") && body.get("provider") != null) {
                llmService.setProvider(String.valueOf(body.get("provider")));
                log.info("[Agent Config] Updated provider to: {}", llmService.getProvider());
            }
            if (body.containsKey("url") && body.get("url") != null) {
                llmService.setLocalUrl(String.valueOf(body.get("url")));
                log.info("[Agent Config] Updated localUrl to: {}", llmService.getLocalUrl());
            }
            if (body.containsKey("model") && body.get("model") != null) {
                llmService.setChatModel(String.valueOf(body.get("model")));
                log.info("[Agent Config] Updated model to: {}", llmService.getChatModel());
            }
            if (body.containsKey("embeddingModel") && body.get("embeddingModel") != null) {
                llmService.setEmbeddingModel(String.valueOf(body.get("embeddingModel")));
                log.info("[Agent Config] Updated embeddingModel to: {}", llmService.getEmbeddingModel());
            }
            if (body.containsKey("embeddingUrl") && body.get("embeddingUrl") != null) {
                llmService.setEmbeddingUrl(String.valueOf(body.get("embeddingUrl")));
                log.info("[Agent Config] Updated embeddingUrl to: {}", llmService.getEmbeddingUrl());
            }
            if (body.containsKey("cloudProvider") && body.get("cloudProvider") != null) {
                llmService.setCloudProvider(String.valueOf(body.get("cloudProvider")));
                log.info("[Agent Config] Updated cloudProvider to: {}", llmService.getCloudProvider());
            }
            if (body.containsKey("cloudApiKey") && body.get("cloudApiKey") != null) {
                llmService.setCloudApiKey(String.valueOf(body.get("cloudApiKey")));
                log.info("[Agent Config] Updated cloudApiKey");
            }
            if (body.containsKey("cloudBaseUrl") && body.get("cloudBaseUrl") != null) {
                llmService.setCloudBaseUrl(String.valueOf(body.get("cloudBaseUrl")));
                log.info("[Agent Config] Updated cloudBaseUrl to: {}", llmService.getCloudBaseUrl());
            }
            Map<String, Object> result = new HashMap<>();
            result.put("provider", llmService.getProvider());
            result.put("url", llmService.getLocalUrl());
            result.put("model", llmService.getChatModel());
            result.put("embeddingModel", llmService.getEmbeddingModel());
            result.put("embeddingUrl", llmService.getEmbeddingUrl());
            result.put("cloudProvider", llmService.getCloudProvider());
            result.put("cloudBaseUrl", llmService.getCloudBaseUrl());
            result.put("cloudApiKeySet", llmService.getCloudApiKey() != null && !llmService.getCloudApiKey().isEmpty());
            return ResponseEntity.ok(ApiResponse.success("配置已更新", result));
        } catch (Exception e) {
            log.error("[Agent Config] Failed to update config: {}", e.getMessage());
            return ResponseEntity.ok(ApiResponse.error("配置更新失败: " + e.getMessage()));
        }
    }

    /**
     * 获取可用模型列表
     */
    @GetMapping("/llm/models")
    public Mono<ResponseEntity<?>> listModels() {
        return llmService.listModels()
                .<ResponseEntity<?>>map(models -> ResponseEntity.ok(ApiResponse.success("ok", models)))
                .onErrorResume(e -> Mono.just(ResponseEntity.ok(ApiResponse.error("获取模型列表失败: " + e.getMessage()))));
    }

}