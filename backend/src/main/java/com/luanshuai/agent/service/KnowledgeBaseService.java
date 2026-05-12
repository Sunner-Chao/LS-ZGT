package com.luanshuai.agent.service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.luanshuai.agent.config.AppConfig;
import com.luanshuai.agent.model.ApiResponse;
import com.luanshuai.agent.model.KbNode;  // 添加IOException导入
import com.luanshuai.agent.util.TenantContext;
import com.luanshuai.agent.util.TenantUtils;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * 知识库服务（KnowledgeBaseService）
 * 主要职责：
 * - 管理租户隔离的知识库目录（创建、重命名、删除、列举文件）
 * - 协调 Milvus 向量数据库的集合/文档操作（创建集合、创建索引、删除、同步等）
 * - 提供同步/重建索引和清理孤立向量数据的运维方法
 */
@Service
public class KnowledgeBaseService {
    
    private static final Logger log = LoggerFactory.getLogger(KnowledgeBaseService.class);

    private static final String ANSI_RED = "\u001B[31m";
    private static final String ANSI_BLUE = "\u001B[34m";
    private static final String ANSI_YELLOW = "\u001B[33m";
    private static final String ANSI_RESET = "\u001B[0m";
    // 日志高亮辅助：在控制台输出中为不同级别的消息添加颜色，便于快速定位问题
    private static String red(String msg) { // 红色：用于错误或高优先级告警
        return ANSI_RED + msg + ANSI_RESET;
    }

    private static String blue(String msg) { // 蓝色：用于信息或调试级别的消息
        return ANSI_BLUE + msg + ANSI_RESET;
    }

    private static String yellow(String msg) { // 黄色：用于警告或提醒
        return ANSI_YELLOW + msg + ANSI_RESET;
    }
    
    @Autowired
    private AppConfig appConfig; // 应用配置：包含知识库路径、租户存储模式等
    
    @Autowired
    private MilvusDbService milvusDbService; // Milvus 向量数据库操作封装
    
    @Autowired
    private RagService ragService; // RAG 服务，用于文档入库与检索调用（异步）
    
    /**
     * 获取当前用户的知识库根路径
     * 逻辑：
     * - 如果为 admin、default 或 null/空，则直接使用全局知识库路径（不做租户隔离）
     * - 否则根据租户ID与配置（storageMode）构建租户隔离路径，若路径不存在则尝试创建
     */
    private Path getKnowledgeBaseRootPath() {
        String tenantId = TenantContext.getCurrentTenantId(); // 从线程上下文获取当前租户ID

        // admin/default账户或无租户上下文时，使用全局知识库路径（便于管理员查看/管理）
        if (tenantId == null || tenantId.trim().isEmpty() || "default".equals(tenantId) || "admin".equals(tenantId)) {
            log.info(blue("[KB_TRACE] kb root path (default/admin): tenantId={}, path={}"), tenantId, appConfig.getKnowledgeBase().getPath());
            return Paths.get(appConfig.getKnowledgeBase().getPath());
        } else {
            // 为普通租户构建租户隔离的根路径（基于存储模式等配置）
            String tenantKbPath = TenantUtils.buildTenantKnowledgeBasePath(
                tenantId, 
                null,
                appConfig.getTenant().getStorageMode(),
                appConfig.getTenant().getTenantKbPath()
            );
            Path tenantPath = Paths.get(tenantKbPath);
            // 若目录不存在，尝试创建（保证首次使用时能自动初始化）
            if (!Files.exists(tenantPath)) {
                try {
                    log.warn(yellow("[KB_TRACE] kb root path missing -> creating directories: tenantId={}, storageMode={}, path={}"), tenantId, appConfig.getTenant().getStorageMode(), tenantPath);
                    Files.createDirectories(tenantPath);
                    log.warn(yellow("[KB_TRACE] kb root path created: tenantId={}, path={}, existsNow={}"), tenantId, tenantPath, Files.exists(tenantPath));
                } catch (IOException e) {
                    // 创建失败时记录错误并继续（调用方应注意目录状态）
                    log.error(red("[KB_TRACE] kb root path create failed: tenantId={}, path={}, err={}"), tenantId, tenantPath, e.getMessage());
                }
            }
            log.warn(yellow("[KB_TRACE] kb root path (tenant): tenantId={}, storageMode={}, path={}, exists={}"), tenantId, appConfig.getTenant().getStorageMode(), tenantPath, Files.exists(tenantPath));
            return tenantPath; // 返回租户路径（可能已创建或预先存在）
        }
    }
    
    /**
     * 获取当前租户的知识库树（目录与文件结构）
     * 说明：返回的 KbNode 列表用于前端构建侧边目录树
     */
    public ApiResponse<List<KbNode>> getKnowledgeBaseTree() {
        try {
            String tenantId = TenantContext.getCurrentTenantId();
            Path basePath = getKnowledgeBaseRootPath(); // 获取租户根路径
            
            // 确保根路径存在（首次访问时自动创建）
            if (!Files.exists(basePath)) {
                Files.createDirectories(basePath);
            }
            
            List<KbNode> tree = buildTree(basePath, ""); // 递归构建目录树
            log.info("[KB] 获取知识库树成功（租户隔离）: tenantId={}, 节点数={}", tenantId, tree.size());
            return ApiResponse.success("获取知识库树成功", tree);
        } catch (Exception e) {
            log.error("[KB] 获取知识库树失败: {} {}", e.getClass().getName(), e.getMessage());
            return ApiResponse.error("获取知识库树失败: " + e.getMessage());
        }
    }
    
    /**
     * 递归构建目录树
     * - 对目录项进行排序后遍历
     * - 目录被表示为 type='folder' 并包含 children，文件为 type='file'
     * - 过滤临时文件（.tmp/.temp）并容错处理特殊挂载等非标准条目
     * - 文件节点包含大小和最后修改时间信息
     */
    private List<KbNode> buildTree(Path basePath, String relativePath) {
        List<KbNode> nodes = new ArrayList<>();
        Path currentPath = basePath.resolve(relativePath);

        if (!Files.exists(currentPath)) {
            return nodes; // 目录不存在则返回空列表
        }

        try {
            List<Path> items = Files.list(currentPath)
                    .sorted()
                    .collect(Collectors.toList()); // 列出并排序当前目录下的所有条目

            for (Path item : items) {
                String itemName = item.getFileName().toString();
                String itemRelativePath = relativePath.isEmpty() ? itemName : relativePath + "/" + itemName;

                try {
                    boolean isDir = Files.isDirectory(item);
                    boolean isRegular = Files.isRegularFile(item);

                    if (isDir) { // 目录：递归构建子节点
                        List<KbNode> children = buildTree(basePath, itemRelativePath);
                        KbNode node = new KbNode("folder", itemName, children);
                        node.setPath(itemRelativePath); // 设置相对路径
                        nodes.add(node);
                    } else if (isRegular) { // 常规文件：过滤临时文件
                        String fileName = itemName.toLowerCase();
                        if (!fileName.endsWith(".tmp") &&
                            !fileName.endsWith(".temp")) {
                            KbNode node = new KbNode("file", itemName);
                            node.setPath(itemRelativePath); // 设置相对路径
                            // 添加文件大小和最后修改时间
                            try {
                                node.setSize(Files.size(item));
                                node.setLastModified(Files.getLastModifiedTime(item).toInstant().toString());
                            } catch (Exception e) {
                                log.debug("获取文件属性失败: {} - {}", item, e.getMessage());
                            }
                            nodes.add(node);
                        }
                    } else {
                        // 处理特殊条目（如某些挂载或短暂状态），记录并尽量容错
                        log.debug("DEBUG134646: 非标准条目 detected in buildTree: {} (exists={}, isDirectory={}, isRegularFile={})", item, Files.exists(item), isDir, isRegular);
                        if (Files.exists(item)) {
                            // 将其当作目录节点处理，前端在进一步请求时会进行更严格的校验
                            List<KbNode> children = buildTree(basePath, itemRelativePath);
                            KbNode node = new KbNode("folder", itemName, children);
                            node.setPath(itemRelativePath); // 设置路径
                            nodes.add(node);
                        }
                    }
                } catch (Exception e) {
                    // 单个条目处理异常时记录日志并继续处理其他条目
                    log.debug("DEBUG134646: 处理条目时出错: {} - {}", item, e.getMessage());
                }
            }
        } catch (Exception e) {
            // 记录错误但继续处理其他项目，避免单个路径错误影响整体
            System.err.println("处理路径时出错: " + currentPath + ", 错误: " + e.getMessage());
        }

        return nodes; // 返回构建好的节点列表
    }
    
    /**
     * 在租户知识库中创建文件夹
     * 步骤：
     * 1) 解析并创建物理目录
     * 2) 基于文件夹名（第一个路径段）自动在 Milvus 中创建集合并尝试建立索引（非阻塞）
     */
    public ApiResponse<String> createFolder(String folderPath) {
        try {
            String tenantId = TenantContext.getCurrentTenantId();
            Path basePath = getKnowledgeBaseRootPath();
            Path fullPath = basePath.resolve(folderPath);
            log.debug("[KB_TRACE_DEBUG] createFolder start: tenantId={}, basePath={}, folderPath={}, fullPath={}", tenantId, basePath, folderPath, fullPath);
            log.warn(yellow("[KB_TRACE] createFolder request: tenantId={}, basePath={}, folderPath(raw)={}, fullPath={}"), tenantId, basePath, folderPath, fullPath);
            log.info(blue("[KB_TRACE_BLUE] createFolder called: tenantId={} folderPath='{}' fullPath={} (NOTE: only creates directories; does NOT ingest; Milvus collection created when upload/add_document triggers RagService.addDocument)"),
                tenantId,
                folderPath,
                fullPath);

            // 创建目录（若已存在则为幂等操作）
            Files.createDirectories(fullPath);

            // 自动在 Milvus 中创建对应的集合并尝试创建索引（失败不阻塞文件夹创建）
            try {
                String rawKb = folderPath;
                if (rawKb == null || rawKb.trim().isEmpty()) {
                    rawKb = "default_knowledge_base"; // 默认知识库标识
                }
                // 仅使用第一个路径段作为知识库标识，例如 a/b -> a
                if (rawKb.contains("/") || rawKb.contains("\\\\")) {
                    rawKb = rawKb.split("[/\\\\]")[0];
                }
                // 重要：与入库/检索保持一致，统一使用 TenantUtils 的 hash 映射 + tenant 隔离
                String tenantCollectionName = TenantUtils.buildTenantCollectionName(tenantId, rawKb);
                String kbKey = TenantUtils.buildKbCollectionKey(rawKb);
                log.info("[KB] 自动创建 Milvus 集合: {} (kb='{}', kbKey='{}', 触发点: createFolder)", tenantCollectionName, rawKb, kbKey);
                try {
                    milvusDbService.createCollection(tenantCollectionName); // 尝试创建集合
                } catch (Exception e) {
                    log.warn("[KB] milvus createCollection 失败: {} - {}", tenantCollectionName, e.getMessage());
                }

                // 尝试创建索引（索引在没有数据时可能失败或很快完成），失败不应阻塞主流程
                try {
                    boolean indexCreated = milvusDbService.createIndex(tenantCollectionName);
                    if (indexCreated) {
                        log.info("[KB] 自动创建 Milvus 索引成功: {}", tenantCollectionName);
                    } else {
                        log.warn("[KB] 自动创建 Milvus 索引未完成或失败（可由后续入库触发）: {}", tenantCollectionName);
                    }
                } catch (Exception ie) {
                    log.warn("[KB] milvus createIndex 异常: {} - {}", tenantCollectionName, ie.getMessage());
                }
            } catch (Exception ex) {
                // 集合/索引创建失败不应影响文件夹创建流程，记录警告即可
                log.warn("[KB] 创建 Milvus 集合/索引时发生异常（忽略）: {}", ex.getMessage());
            }
            log.debug("[KB_TRACE_DEBUG] createFolder after createDirectories: exists={}, isDirectory={}", Files.exists(fullPath), Files.isDirectory(fullPath));
            log.warn(yellow("[KB_TRACE] createFolder result: tenantId={}, fullPath={}, exists={}, isDirectory={}"), tenantId, fullPath, Files.exists(fullPath), Files.isDirectory(fullPath));
            log.info(blue("[KB_TRACE_BLUE] createFolder done: tenantId={} fullPath={} exists={} isDirectory={}"),
                tenantId,
                fullPath,
                Files.exists(fullPath),
                Files.isDirectory(fullPath));
            log.info("[KB] 创建文件夹成功（租户隔离）: tenantId={}, path={}", tenantId, fullPath);
            return ApiResponse.success("文件夹创建成功: " + folderPath, fullPath.toString());
        } catch (Exception e) {
            log.error("[KB] 创建文件夹失败: {} {}", e.getClass().getName(), e.getMessage());
            log.error(blue("[KB_TRACE_BLUE] createFolder failed: tenantId={} folderPath='{}' err='{}'"), TenantContext.getCurrentTenantId(), folderPath, e.getMessage());
            return ApiResponse.error("创建文件夹失败: " + e.getMessage());
        }
    }
    
    /**
     * 重命名文件或目录
     * 注意：
     * - 对于目录重命名，需要同时在 Milvus 中重命名对应集合以保持向量数据一致性
     * - 对于文件重命名，仅修改文件系统并尝试删除/更新向量数据库中文档（如果适用）
     */
    public ApiResponse<String> renameItem(String srcPath, String dstPath) {
        try {
            log.info("[重命名] 开始重命名: {} -> {}", srcPath, dstPath);
            Path basePath = getKnowledgeBaseRootPath();
            Path src = basePath.resolve(srcPath);
            Path dst = basePath.resolve(dstPath);
            
            if (!Files.exists(src)) {
                log.warn("[重命名] 源文件或文件夹不存在: {}", src);
                return ApiResponse.error("源文件或文件夹不存在");
            }
            
            // 检查路径安全性，防止越权访问其他目录
            if (!src.normalize().startsWith(basePath.normalize()) || 
                !dst.normalize().startsWith(basePath.normalize())) {
                log.error("[重命名] 非法路径访问: {} -> {}", srcPath, dstPath);
                return ApiResponse.error("非法路径");
            }
            
            // 如果是目录重命名，需要同步更新向量数据库
            if (Files.isDirectory(src)) {
                log.info("[重命名] 重命名目录，需要同步向量数据库");
                
                // 检查目标路径是否已存在，避免覆盖
                if (Files.exists(dst)) {
                    log.error("[重命名] 目标路径已存在: {}", dst);
                    return ApiResponse.error("目标路径已存在");
                }
                
                String tenantId = TenantContext.getCurrentTenantId();
                String effectiveTenantId = (tenantId == null || tenantId.trim().isEmpty()) ? "default" : tenantId.trim();

                // 确定旧/新 KB 名称（仅使用第一个路径段，避免子目录影响集合名）
                String rawOld = basePath.relativize(src).toString().replace('\\', '/');
                if (rawOld.isEmpty()) rawOld = "default_knowledge_base";
                String oldKb = rawOld.contains("/") ? rawOld.substring(0, rawOld.indexOf('/')) : rawOld;
                if (oldKb.isEmpty()) oldKb = "default_knowledge_base";

                String rawNew = basePath.relativize(dst).toString().replace('\\', '/');
                if (rawNew.isEmpty()) rawNew = "default_knowledge_base";
                String newKb = rawNew.contains("/") ? rawNew.substring(0, rawNew.indexOf('/')) : rawNew;
                if (newKb.isEmpty()) newKb = "default_knowledge_base";

                String oldCollectionName = TenantUtils.buildTenantCollectionName(effectiveTenantId, oldKb);
                String newCollectionName = TenantUtils.buildTenantCollectionName(effectiveTenantId, newKb);

                log.info("[重命名] 向量数据库集合重命名 (hash): {} -> {} (kb: {} -> {})", oldCollectionName, newCollectionName, oldKb, newKb);

                // 在文件系统移动前，先检查 Milvus 中是否已存在目标集合，避免冲突
                try {
                    if (milvusDbService.hasCollection(newCollectionName)) {
                        log.warn("[重命名] Milvus 中目标集合已存在: {}", newCollectionName);
                        return ApiResponse.error("目标知识库名在向量库中已存在，请选择其他名称或先删除冲突的集合");
                    }
                } catch (Exception ex) {
                    // 如果无法检查 Milvus 状态，记录警告但尝试继续操作（需人工后处理）
                    log.warn("[重命名] 检查 Milvus 目标集合存在性失败，继续但请注意: {}", ex.getMessage());
                }

                // 先重命名文件系统
                Files.move(src, dst);
                log.info("[重命名] 文件系统重命名成功");

                // 验证重命名结果
                if (!Files.exists(dst)) {
                    log.error("[重命名] 重命名后目标路径不存在: {}", dst);
                    return ApiResponse.error("重命名失败：目标路径不存在");
                }

                if (!Files.isDirectory(dst)) {
                    log.warn("[重命名] 重命名后目标路径不是目录（可能是9p特殊条目）: {}", dst);
                    // 继续处理，因为可能是文件系统延迟导致的特殊条目状态
                } else {
                    log.info("[重命名] 重命名验证成功: {}", dst);
                }
                
                // 然后重命名向量数据库集合
                try {
                    boolean renamed = milvusDbService.renameCollection(oldCollectionName, newCollectionName);
                    if (renamed) {
                        log.info("[重命名] 向量数据库集合重命名成功");
                        return ApiResponse.success("重命名成功: " + srcPath + " -> " + dstPath + " (包含向量数据同步)");
                    } else {
                        log.warn("[重命名] 向量数据库集合重命名失败，但文件系统重命名成功");
                        return ApiResponse.success("重命名成功: " + srcPath + " -> " + dstPath + " (文件系统已更新，向量数据同步失败)");
                    }
                } catch (Exception e) {
                    log.error("[重命名] 向量数据库集合重命名异常: {} {}", e.getClass().getName(), e.getMessage());
                    return ApiResponse.success("重命名成功: " + srcPath + " -> " + dstPath + " (文件系统已更新，向量数据同步异常)");
                }
            } else {
                // 文件重命名，直接操作
                Files.move(src, dst);
                log.info("[重命名] 文件重命名成功");
                return ApiResponse.success("重命名成功: " + srcPath + " -> " + dstPath);
            }
        } catch (Exception e) {
            log.error("[重命名] 重命名失败: {} -> {} - {} {}", srcPath, dstPath, e.getClass().getName(), e.getMessage());
            return ApiResponse.error("重命名失败: " + e.getMessage());
        }
    }
    
    /**
     * 删除文件或目录
     * - 对目录：递归删除文件系统，并尝试删除 Milvus 中对应的集合与索引
     * - 对文件：删除后尝试从 Milvus 中删除对应 source 的文档
     */
    public ApiResponse<String> deleteItem(String path) {
        try {
            log.info("[删除] 开始删除项目: {}", path);
            Path basePath = getKnowledgeBaseRootPath();
            Path itemPath = basePath.resolve(path);
            
            if (!Files.exists(itemPath)) {
                log.warn("[删除] 文件或文件夹不存在: {}", itemPath);
                return ApiResponse.error("文件或文件夹不存在");
            }
            
            // 检查路径安全性，避免越权删除
            if (!itemPath.normalize().startsWith(basePath.normalize())) {
                log.error("[删除] 非法路径访问: {}", path);
                return ApiResponse.error("非法路径");
            }
            
            if (Files.isDirectory(itemPath)) {
                // 目录删除：先删除文件系统，再尝试删除向量库集合
                log.info("[删除] 删除目录: {}", itemPath);
                deleteDirectoryRecursively(itemPath);

                // 同步删除向量数据库中的集合与索引（tenant 隔离 + hash 映射）
                try {
                    String rawKb = basePath.relativize(itemPath).toString();
                    if (rawKb == null || rawKb.trim().isEmpty()) rawKb = "default_knowledge_base";

                    // 只使用第一个路径段作为知识库标识（删除子目录不应把整个知识库集合删掉）
                    String kbName = rawKb.replace('\\', '/');
                    if (kbName.contains("/")) {
                        kbName = kbName.substring(0, kbName.indexOf('/'));
                    }
                    if (kbName.isEmpty()) {
                        kbName = "default_knowledge_base";
                    }

                    String tenantId = TenantContext.getCurrentTenantId();
                    String effectiveTenantId = (tenantId == null || tenantId.trim().isEmpty()) ? "default" : tenantId.trim();
                    String tenantCollectionName = TenantUtils.buildTenantCollectionName(effectiveTenantId, kbName);
                    log.info("[删除] 开始删除 Milvus 集合及索引: {} -> collectionName={} (kbName='{}', 触发点: deleteItem directory)", itemPath, tenantCollectionName, kbName);
                    boolean deleted = milvusDbService.deleteCollection(tenantCollectionName);
                    if (deleted) {
                        log.info("[删除] Milvus 集合及索引删除成功: {} -> {}", itemPath, tenantCollectionName);
                    } else {
                        log.warn("[删除] Milvus 集合及索引删除失败: {} -> {}", itemPath, tenantCollectionName);
                    }
                } catch (Exception e) {
                    log.error("[删除] 删除 Milvus 集合异常: {} - {}", itemPath, e.getMessage());
                }

            } else {
                // 文件删除：删除本地文件并尝试删除向量库中的对应文档
                log.info("[删除] 删除文件: {}", itemPath);
                Files.delete(itemPath);

                try {
                    String relativePath = basePath.relativize(itemPath).toString().replace('\\', '/');
                    String kbName = relativePath.contains("/") ? relativePath.substring(0, relativePath.indexOf('/')) : "default_knowledge_base";
                    if (kbName.isEmpty()) kbName = "default_knowledge_base";
                    String tenantId = TenantContext.getCurrentTenantId();
                    String effectiveTenantId = (tenantId == null || tenantId.trim().isEmpty()) ? "default" : tenantId.trim();

                    String tenantCollectionName = TenantUtils.buildTenantCollectionName(effectiveTenantId, kbName);
                    String sourceKey = TenantUtils.buildVectorSourceKey(relativePath);

                    log.info("[删除] 删除文件后，尝试删除向量数据库中文档: kb='{}' collection={} sourceKey={}", kbName, tenantCollectionName, sourceKey);
                    boolean vectorDeleted = milvusDbService.deleteDocumentsBySource(tenantCollectionName, sourceKey);
                    if (vectorDeleted) log.info("[删除] 向量数据库文档删除成功: {}", relativePath);
                    else log.warn("[删除] 向量数据库文档删除失败: {}", relativePath);
                } catch (Exception e) {
                    log.error("[删除] 删除向量数据库文档异常: {} - {}", itemPath, e.getMessage());
                }
            }

            log.info("[删除] 删除成功: {}", path);
            return ApiResponse.success("删除成功: " + path);
        } catch (Exception e) {
            log.error("[删除] 删除失败: {} - {}", path, e.getMessage());
            return ApiResponse.error("删除失败: " + e.getMessage());
        }
    }
    
    /**
     * 列出指定目录下的文件（仅列出当前目录的常规文件，不递归）
     * 步骤说明：
     * 1) 校验路径存在性与目录属性（兼容挂载文件系统的短暂不一致情形）
     * 2) 过滤掉临时文件、隐藏文件和系统文件
     * 3) 返回每个文件的基本信息（id/name/size/uploadTime/path）供前端展示
     */
    public List<Map<String, Object>> getFilesInPath(String path) throws Exception {
        // 方法：列出指定知识库路径下的文件（仅列出普通文件，不递归目录）
        try {
            // DEBUG134646: 方法开始执行
            log.debug("DEBUG134646: 进入 getFilesInPath 方法, 参数 path={}", path);

            // 1) 获取当前租户的知识库根路径（this 会根据 TenantContext 返回不同的 basePath）
            Path basePath = getKnowledgeBaseRootPath();

            // 2) 读取当前请求的租户ID（仅用于日志记录，实际路径解析以 basePath 为准）
            String tenantId = TenantContext.getCurrentTenantId();
            log.debug("DEBUG134646: 解析后的 basePath={}, tenantId={}", basePath, tenantId);

            // 3) 记录调试信息：租户、基路径以及前端请求的相对路径
            Path targetPath = basePath.resolve(path);
            log.debug("DEBUG134646: 解析后的目标路径 targetPath={}, 是否存在 exists={}, 是否为目录 isDirectory={}",
                      targetPath, Files.exists(targetPath), Files.isDirectory(targetPath));

            // 4) 校验：目标路径必须存在，否则抛出异常（上层会返回错误给前端）
            if (!Files.exists(targetPath)) {
                log.debug("DEBUG134646: 目标路径不存在: {}", targetPath);
                throw new Exception("路径不存在: " + path);
            }

            // 5) 校验：目标路径必须为目录，否则尝试探测（兼容 9p 等挂载的短暂不一致）
            if (!Files.isDirectory(targetPath)) {
                log.debug("DEBUG134646: 目标路径初次检查不是目录: {}", targetPath);

                boolean dir = Files.isDirectory(targetPath);

                // 尝试用 DirectoryStream 探测
                if (!dir) {
                    try (java.nio.file.DirectoryStream<Path> ds = Files.newDirectoryStream(targetPath)) {
                        dir = true;
                        log.debug("DEBUG134646: DirectoryStream 对 targetPath 成功，视为目录");
                    } catch (Exception e) {
                        log.debug("DEBUG134646: DirectoryStream 读取失败（targetPath 可能不是目录）: {}", e.getMessage());
                    }
                }

                if (!dir) {
                    log.debug("DEBUG134646: 进入特殊条目检查分支");
                    log.debug("DEBUG134646: 进入特殊条目检查: exists={}, isRegularFile={}, isDirectory={}", Files.exists(targetPath), Files.isRegularFile(targetPath), Files.isDirectory(targetPath));
                    // 检查是否是特殊条目（既不是文件也不是目录）
                    if (Files.exists(targetPath) && !Files.isRegularFile(targetPath) && !Files.isDirectory(targetPath)) {
                        log.warn("目标路径 {} 是特殊条目（既不是文件也不是目录），当作空目录处理", targetPath);
                        return new ArrayList<>();
                    } else {
                        log.debug("DEBUG134646: 目标路径最终判断不是目录: {}", targetPath);
                        throw new Exception("指定路径不是目录: " + path);
                    }
                }
            }

            // 6) 准备返回的文件列表容器
            List<Map<String, Object>> files = new ArrayList<>();

            // 7) 遍历目标目录下的条目，仅处理常规文件（不进入子目录）
            Files.list(targetPath)
                // 只保留普通文件（排除目录、链接等）
                .filter(Files::isRegularFile)
                // 过滤掉临时文件、系统文件和隐藏文件
                .filter(filePath -> {
                    String fileName = filePath.getFileName().toString();
                    log.debug("DEBUG134646: 检查文件: {}", fileName);
                    String lowerFileName = fileName.toLowerCase();
                    // 返回 true 表示保留该文件
                    boolean keep = !lowerFileName.endsWith(".tmp") &&
                                  !lowerFileName.endsWith(".temp") &&
                                  !fileName.startsWith(".") &&
                                  !lowerFileName.equals(".ds_store") &&
                                  !lowerFileName.contains("thumbs.db") &&
                                  !lowerFileName.contains("desktop.ini");
                    log.debug("DEBUG134646: 文件 {} 是否保留 keep={}", fileName, keep);
                    return keep;
                })
                // 对每个满足条件的文件构造文件信息对象并加入结果列表
                .forEach(filePath -> {
                    try {
                        // 单个文件的信息 map
                        Map<String, Object> fileInfo = new HashMap<>();
                        // id：使用 filePath.hashCode() 作为简单唯一标识（前端依赖此值进行删除操作）
                        fileInfo.put("id", filePath.hashCode()); // 简单的ID生成
                        // 文件名
                        fileInfo.put("name", filePath.getFileName().toString());
                        // 文件大小（字节）
                        fileInfo.put("size", Files.size(filePath));
                        // 文件最后修改时间，转为 ISO 字符串
                        String lastModifiedTime = Files.getLastModifiedTime(filePath).toInstant().toString();
                        fileInfo.put("lastModified", lastModifiedTime);
                        fileInfo.put("uploadTime", lastModifiedTime);
                        // path：相对于 basePath 的相对路径，用于前端显示和后续操作
                        fileInfo.put("path", basePath.relativize(filePath).toString());
                        // 将构造好的文件信息加入返回列表
                        files.add(fileInfo);
                        log.debug("DEBUG134646: 添加文件信息: {}", fileInfo);
                    } catch (Exception e) {
                        // 单个文件信息获取异常时记录错误，但不影响其他文件的处理
                        log.error("DEBUG134646: 处理文件 {} 时发生错误: {}", filePath, e.getMessage());
                    }
                });

            // 8) 返回收集到的文件信息列表
            log.debug("DEBUG134646: 返回文件列表, 文件数量 size={}", files.size());
            return files;
        } catch (Exception e) {
            // 记录整体异常并向上抛出，供 Controller 层统一返回错误给前端
            log.error("DEBUG134646: getFilesInPath 方法异常: path={} - {}", path, e.getMessage());
            throw e;
        }
    }
    
/**
     * 删除文件（根据前端传入的 fileId 来定位文件）
     *
     * 说明：当前前端使用的是 `filePath.hashCode()` 作为 fileId，因此服务端需要遍历租户知识库路径
     * 来定位文件。删除流程：
     * 1. 遍历所有符合条件的文件并比较 hashCode；
     * 2. 找到匹配文件后删除物理文件；
     * 3. 尝试删除 Milvus 中对应的向量文档（使用相对路径作为 source 标识）；
     * 4. 返回删除是否成功（true 表示文件已删除）。
     */
    public boolean deleteFile(String fileId) {
        log.info("[删除] 开始删除文件，fileId: {}", fileId);
        try {
            // 说明：前端传递的是 file.hashCode()，因此需要遍历所有文件并比较 hashCode 来定位文件
            Path basePath = getKnowledgeBaseRootPath();
            String tenantId = TenantContext.getCurrentTenantId();
            log.debug("[删除] tenantId={}, basePath={}", tenantId, basePath);
            log.info("[删除] 知识库基础路径: {}", basePath);
            final boolean[] fileFound = {false};
            final boolean[] fileDeleted = {false};
            
            // 遍历所有知识库目录并查找匹配的文件（通过 hashCode 匹配）
            // 过滤条件：排除临时文件、系统文件与隐藏文件，减少无效遍历
            Files.walk(basePath)
                .filter(Files::isRegularFile)
                .filter(filePath -> {
                    String fileName = filePath.getFileName().toString();
                    String lowerFileName = fileName.toLowerCase();
                    // 过滤掉临时文件、系统文件和隐藏文件
                    return !lowerFileName.endsWith(".tmp") && 
                           !lowerFileName.endsWith(".temp") &&
                           !fileName.startsWith(".") &&
                           !lowerFileName.equals(".ds_store") &&
                           !lowerFileName.contains("thumbs.db") &&
                           !lowerFileName.contains("desktop.ini");
                })
                .forEach(filePath -> {
                    try {
                        int currentHashCode = filePath.hashCode();
                        log.debug("[删除] 检查文件: {}, hashCode: {}, 目标fileId: {}", filePath, currentHashCode, fileId);
                        if (currentHashCode == Integer.parseInt(fileId)) {
                            // 找到匹配文件：标记、删除文件并尝试删除对应向量数据
                            fileFound[0] = true;
                            log.info("[删除] 找到匹配文件: {}", filePath);
                            
                            // 获取相对路径用于向量数据库删除
                            String relativePath = basePath.relativize(filePath).toString().replace('\\', '/');
                            log.info("[删除] 文件相对路径: {}", relativePath);
                            
                            // 先删除文件系统中的文件
                            Files.delete(filePath);
                            fileDeleted[0] = true;
                            log.info("[删除] 删除文件成功: {}", filePath);
                            
                            // 删除向量数据库中的相关文档
                            try {
                                // 确定知识库ID
                                String kbName = relativePath.contains("/") ? relativePath.substring(0, relativePath.indexOf('/')) : "default_knowledge_base";
                                if (kbName.isEmpty()) kbName = "default_knowledge_base";

                                String effectiveTenantId = (tenantId == null || tenantId.trim().isEmpty()) ? "default" : tenantId.trim();
                                String tenantCollectionName = TenantUtils.buildTenantCollectionName(effectiveTenantId, kbName);
                                String sourceKey = TenantUtils.buildVectorSourceKey(relativePath);

                                log.info("[删除] 知识库: {} collection={} sourceKey={}", kbName, tenantCollectionName, sourceKey);

                                // 删除向量数据库中的文档
                                boolean vectorDeleted = milvusDbService.deleteDocumentsBySource(tenantCollectionName, sourceKey);
                                if (vectorDeleted) {
                                    log.info("[删除] 向量数据库文档删除成功: {}", relativePath);
                                } else {
                                    log.warn("[删除] 向量数据库文档删除失败: {}", relativePath);
                                }
                            } catch (Exception e) {
                                log.error("[删除] 删除向量数据库文档异常: {}", e.getMessage());
                            }
                        }
                    } catch (Exception e) {
                        log.error("[删除] 删除文件失败: {} - {}", filePath, e.getMessage());
                    }
                });
            
            if (!fileFound[0]) {
                log.warn("[删除] 未找到文件，fileId={}", fileId);
                return false;
            }
            
            log.info("[删除] 删除文件操作完成，fileFound: {}, fileDeleted: {}", fileFound[0], fileDeleted[0]);
            return fileDeleted[0];
        } catch (Exception e) {
            log.error("[删除] 删除文件异常: fileId={} - {}", fileId, e.getMessage());
            return false;
        }
    }

    /**
     * 根据文件ID获取文件路径
     * 说明：前端使用 filePath.hashCode() 作为 fileId，因此需要遍历查找
     * @param fileId 文件ID（hashCode）
     * @return 文件相对路径，未找到返回 null
     */
    public String getFilePathById(String fileId) {
        log.info("[预览] 根据fileId查找文件路径: {}", fileId);
        try {
            Path basePath = getKnowledgeBaseRootPath();
            final String[] foundPath = {null};

            // 遍历所有文件，通过 hashCode 匹配
            Files.walk(basePath)
                .filter(Files::isRegularFile)
                .filter(filePath -> {
                    String fileName = filePath.getFileName().toString();
                    String lowerFileName = fileName.toLowerCase();
                    return !lowerFileName.endsWith(".tmp") &&
                           !lowerFileName.endsWith(".temp") &&
                           !fileName.startsWith(".") &&
                           !lowerFileName.equals(".ds_store") &&
                           !lowerFileName.contains("thumbs.db") &&
                           !lowerFileName.contains("desktop.ini");
                })
                .forEach(filePath -> {
                    try {
                        int currentHashCode = filePath.hashCode();
                        if (currentHashCode == Integer.parseInt(fileId)) {
                            String relativePath = basePath.relativize(filePath).toString().replace('\\', '/');
                            foundPath[0] = relativePath;
                            log.info("[预览] 找到文件: {} -> {}", fileId, relativePath);
                        }
                    } catch (Exception e) {
                        log.debug("[预览] 检查文件时出错: {} - {}", filePath, e.getMessage());
                    }
                });

            if (foundPath[0] == null) {
                log.warn("[预览] 未找到文件，fileId={}", fileId);
            }

            return foundPath[0];
        } catch (Exception e) {
            log.error("[预览] 查找文件路径异常: fileId={} - {}", fileId, e.getMessage());
            return null;
        }
    }

    /**
     * 递归删除目录（含所有子文件与子目录）
     *
     * 实现要点：
     * - 使用 Files.walk 递归列出路径，并按逆序删除（先子后父）以避免目录非空导致删除失败
     * - 对单个删除异常进行记录并抛出 RuntimeException 以便调用方能感知失败并中止流程
     *
     * 注意：此方法在删除大量文件时可能比较耗时且需要权限，调用方应谨慎使用
     */
    private void deleteDirectoryRecursively(Path directory) throws Exception {
        log.info("[删除] 递归删除目录: {}", directory);
        // 先收集所有路径并按倒序删除：从最深层的文件/目录开始删除，最后删除根目录
        Files.walk(directory)
                .sorted((a, b) -> b.compareTo(a)) // 先删除子文件，再删除父目录
                .forEach(path -> {
                    try {
                        // DEBUG134646: 删除操作前记录路径状态
                        log.debug("DEBUG134646: 准备删除路径: {}, 是否存在: {}, 是否为目录: {}",
                                  path, Files.exists(path), Files.isDirectory(path));

                        Files.delete(path);

                        // DEBUG134646: 删除成功日志
                        log.debug("DEBUG134646: 删除成功: {}", path);
                    } catch (Exception e) {
                        // DEBUG134646: 删除失败日志，记录异常类型和堆栈信息
                        log.error("DEBUG134646: 删除失败: {}, 异常类型: {}, 异常信息: {}",
                                  path, e.getClass().getName(), e.getMessage(), e);

                        // 抛出异常，确保调用方感知到删除失败
                        throw new RuntimeException("删除路径失败: " + path, e);
                    }
                });
        log.info("[删除] 目录删除完成: {}", directory);
    }
    
    /**
     * 强制同步向量数据库，删除所有集合并重新创建
     * @return 同步结果
     */
    public Mono<String> forceSyncVectorDatabase() {
        return Mono.fromCallable(() -> {
            log.info("[强制同步] 开始强制同步向量数据库");
            
            // 1. 删除所有现有集合
            List<String> existingCollections = Arrays.asList(
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
            
            for (String collectionName : existingCollections) {
                if (milvusDbService.hasCollection(collectionName)) {
                    try {
                        milvusDbService.deleteCollection(collectionName);
                        log.info("[强制同步] 删除集合: {}", collectionName);
                    } catch (Exception e) {
                        log.warn("[强制同步] 删除集合失败: {} - {}", collectionName, e.getMessage());
                    }
                }
            }
            
            // 2. 重新同步所有文件
            return "强制同步完成，正在重新同步文件...";
        });
    }
    
    /**
     * 同步向量数据库与文件系统，确保两者保持一致
     * @return 同步结果
     */
    public Mono<String> syncVectorDatabase() {
        return Mono.fromCallable(() -> {
            Path basePath = getKnowledgeBaseRootPath();
            if (!Files.exists(basePath)) {
                return "知识库目录不存在";
            }

            // 当前租户：同步集合必须与入库/检索一致（TenantUtils.buildTenantCollectionName）
            String currentTenantId = TenantContext.getCurrentTenantId();
            final String tenantId = (currentTenantId == null || currentTenantId.isBlank()) ? "default" : currentTenantId;
            
            List<String> processedFiles = new ArrayList<>();
            List<String> errors = new ArrayList<>();
            List<String> deletedVectors = new ArrayList<>();
            
            // 1. 获取文件系统中的所有文件
            // - fileSystemKeyToPath: sourceKey -> 相对路径（用于后续入库）
            // - fileSystemKeys: 用于与向量库 metadata.source 做集合差集计算
            Map<String, String> fileSystemKeyToPath = new HashMap<>();
            Set<String> fileSystemKeys = new HashSet<>();
            Files.walk(basePath)
                .filter(Files::isRegularFile)
                .filter(path -> {
                    String fileName = path.getFileName().toString().toLowerCase();
                    return fileName.endsWith(".pdf") || 
                           fileName.endsWith(".doc") || 
                           fileName.endsWith(".docx") ||
                           fileName.endsWith(".pptx") ||
                           fileName.endsWith(".ppt") ||
                           fileName.endsWith(".xlsx") ||
                           fileName.endsWith(".xls") ||
                           fileName.endsWith(".jpg") ||
                           fileName.endsWith(".jpeg") ||
                           fileName.endsWith(".png") ||
                           fileName.endsWith(".gif") ||
                           fileName.endsWith(".txt") ||
                           fileName.endsWith(".md") ||
                           fileName.endsWith(".csv");
                })
                .forEach(filePath -> {
                    // 统一分隔符为 /，避免 Windows(\\) 与 metadata(/) 比较不一致
                    String relativePath = basePath.relativize(filePath).toString().replace("\\\\", "/");
                    String sourceKey = TenantUtils.buildVectorSourceKey(relativePath);
                    fileSystemKeys.add(sourceKey);
                    // 同一个 key 多次出现时，保留更长/更具体的路径（通常不会发生）
                    fileSystemKeyToPath.putIfAbsent(sourceKey, relativePath);
                });
            
            System.out.println("[同步] 文件系统中找到 " + fileSystemKeys.size() + " 个文件");
            
            // 2. 获取向量数据库中的所有文档源文件
            Set<String> vectorDatabaseFiles = new HashSet<>();
            // 动态获取所有知识库集合，而不是硬编码
            List<String> collections = new ArrayList<>();
            collections.add(TenantUtils.buildTenantCollectionName(tenantId, "default_knowledge_base")); // 默认集合（租户隔离）
            
            // 从文件系统中动态发现知识库集合
            try {
                Files.list(basePath)
                    .filter(Files::isDirectory)
                    .forEach(dir -> {
                        String dirName = dir.getFileName().toString();
                        // 使用 tenant 隔离 + hash 映射（传入原始 KB 名称，避免清理导致冲突）
                        String tenantCollectionName = TenantUtils.buildTenantCollectionName(tenantId, dirName);
                        if (!collections.contains(tenantCollectionName)) {
                            collections.add(tenantCollectionName);
                        }
                    });
            } catch (Exception e) {
                System.err.println("动态发现知识库集合失败: " + e.getMessage());
            }
            
            System.out.println("[同步] 发现的集合: " + collections);
            
            // 检查每个集合的状态
            for (String collectionName : collections) {
                boolean exists = milvusDbService.hasCollection(collectionName);
                System.out.println("[同步] 集合状态检查: " + collectionName + " - 存在: " + exists);
                if (exists) {
                    try {
                        List<Map<String, Object>> docs = milvusDbService.queryAllDocuments(collectionName);
                        System.out.println("[同步] 集合 " + collectionName + " 包含 " + docs.size() + " 个文档");
                    } catch (Exception e) {
                        System.out.println("[同步] 查询集合 " + collectionName + " 失败: " + e.getMessage());
                    }
                }
            }
            
            for (String collectionName : collections) {
                if (milvusDbService.hasCollection(collectionName)) {
                    // 查询该集合中的所有文档
                    List<Map<String, Object>> allDocs = milvusDbService.queryAllDocuments(collectionName);
                    for (Map<String, Object> doc : allDocs) {
                        Object metadataObj = doc.get("metadata");
                        if (metadataObj != null) {
                            String metadata = metadataObj.toString();
                            // 解析metadata中的source字段
                            try {
                                Map<String, Object> meta = parseMetadata(metadata);
                                String source = (String) meta.get("source");
                                if (source != null) {
                                    vectorDatabaseFiles.add(TenantUtils.buildVectorSourceKey(source.replace("\\\\", "/")));
                                }
                            } catch (Exception e) {
                                System.err.println("解析metadata失败: " + metadata);
                            }
                        }
                    }
                }
            }
            
            System.out.println("[同步] 向量数据库中找到 " + vectorDatabaseFiles.size() + " 个文件");
            
            // 2.5. 清理重复的txt文件 - 使用更强力的方法
            System.out.println("[同步] 开始清理重复的txt文件");
            Set<String> collectionsToRecreate = new HashSet<>();
            for (String collectionName : collections) {
                if (milvusDbService.hasCollection(collectionName)) {
                    // 先尝试清理重复文件
                    boolean cleaned = milvusDbService.cleanupDuplicateTxtFiles(collectionName);
                    if (cleaned) {
                        System.out.println("[同步] 清理重复txt文件成功: " + collectionName);
                    } else {
                        System.out.println("[同步] 清理重复txt文件失败，标记集合需要重新创建: " + collectionName);
                        collectionsToRecreate.add(collectionName);
                    }
                }
            }
            
            // 删除需要重新创建的集合
            for (String collectionName : collectionsToRecreate) {
                try {
                    milvusDbService.deleteCollection(collectionName);
                    System.out.println("[同步] 删除集合成功: " + collectionName);
                } catch (Exception e) {
                    System.err.println("[同步] 删除集合失败: " + collectionName + " - " + e.getMessage());
                }
            }
            
            // 3. 找出需要删除的向量数据（文件系统中不存在的文件）
            Set<String> filesToDeleteFromVector = new HashSet<>(vectorDatabaseFiles);
            filesToDeleteFromVector.removeAll(fileSystemKeys);
            
            System.out.println("[同步] 需要从向量数据库删除 " + filesToDeleteFromVector.size() + " 个文件");
            
            // 4. 删除向量数据库中多余的文件 - 使用直接比较方法
            System.out.println("[同步] 开始删除向量数据库中多余的文件");
            for (String collectionName : collections) {
                if (milvusDbService.hasCollection(collectionName)) {
                    try {
                        // 获取该集合中的所有文档
                        List<Map<String, Object>> allDocs = milvusDbService.queryAllDocuments(collectionName);
                        System.out.println("[同步] 集合 " + collectionName + " 中有 " + allDocs.size() + " 个文档");
                        
                        for (Map<String, Object> doc : allDocs) {
                            Object metadataObj = doc.get("metadata");
                            if (metadataObj != null) {
                                String metadata = metadataObj.toString();
                                // 解析metadata中的source字段
                                try {
                                    Map<String, Object> meta = parseMetadata(metadata);
                                    String source = (String) meta.get("source");
                                    if (source != null) {
                                        // 检查这个sourceKey是否在文件系统文件列表中
                                        String sourceKey = TenantUtils.buildVectorSourceKey(source.replace("\\\\", "/"));
                                        if (!fileSystemKeys.contains(sourceKey)) {
                                            // 这个文档对应的文件不存在于文件系统中，需要删除
                                            System.out.println("[同步] 发现孤立文档，source: " + source);
                                            boolean deleted = milvusDbService.deleteDocumentsBySource(collectionName, source);
                                            if (deleted) {
                                                deletedVectors.add(source);
                                                System.out.println("[同步] 删除孤立文档成功: " + source);
                                            } else {
                                                errors.add("删除孤立文档失败: " + source);
                                            }
                                        }
                                    }
                                } catch (Exception e) {
                                    System.err.println("解析metadata失败: " + metadata);
                                    errors.add("解析metadata失败: " + metadata);
                                }
                            }
                        }
                    } catch (Exception e) {
                        System.err.println("处理集合 " + collectionName + " 失败: " + e.getMessage());
                        errors.add("处理集合 " + collectionName + " 失败: " + e.getMessage());
                    }
                }
            }
            
            // 5. 找出需要添加的向量数据（向量数据库中不存在的文件）
            Set<String> filesToAddToVector = new HashSet<>(fileSystemKeys);
            filesToAddToVector.removeAll(vectorDatabaseFiles);
            
            // 如果删除了集合，需要重新添加该集合下的所有文件
            for (String collectionName : collectionsToRecreate) {
                System.out.println("[同步] 集合 " + collectionName + " 被删除，需要重新添加该集合下的所有文件");
                // 找出属于该集合的文件
                for (String filePath : fileSystemKeyToPath.values()) {
                    String fileCollection = TenantUtils.buildTenantCollectionName(tenantId, "default_knowledge_base"); // 默认知识库（租户隔离）
                    if (filePath.contains("/")) {
                        String kbName = filePath.substring(0, filePath.indexOf('/'));
                        if (!kbName.isEmpty()) {
                            fileCollection = TenantUtils.buildTenantCollectionName(tenantId, kbName);
                        }
                    }
                    if (fileCollection.equals(collectionName)) {
                        filesToAddToVector.add(TenantUtils.buildVectorSourceKey(filePath));
                    }
                }
            }
            
            System.out.println("[同步] 需要添加到向量数据库 " + filesToAddToVector.size() + " 个文件");
            
            // 6. 添加缺失的向量数据
            List<Mono<String>> addDocumentMonos = new ArrayList<>();
            for (String fileToAddKey : filesToAddToVector) {
                try {
                    String fileToAdd = fileSystemKeyToPath.get(fileToAddKey);
                    if (fileToAdd == null || fileToAdd.isBlank()) {
                        // key 找不到对应文件，跳过
                        continue;
                    }
                    // 确定知识库ID
                    String knowledgeBaseId = "default_knowledge_base"; // 默认知识库
                    if (fileToAdd.contains("/")) {
                        String kbName = fileToAdd.substring(0, fileToAdd.indexOf('/'));
                        if (!kbName.isEmpty()) {
                            knowledgeBaseId = kbName; // 仅使用第一个路径段作为知识库标识
                        }
                    }
                    
                    // 获取当前租户ID
                    // 复用本次同步计算出的 tenantId，避免中途上下文变化
                    
                    // 创建Mono但不立即订阅，使用显式tenantId
                    Mono<String> addMono = ragService.addDocument(fileToAdd, knowledgeBaseId, tenantId)
                        .doOnSuccess(result -> {
                            System.out.println("[同步] 添加向量数据成功: " + fileToAdd + " - " + result);
                            processedFiles.add(fileToAdd);
                        })
                        .doOnError(error -> {
                            String errorMsg = "添加向量数据失败: " + fileToAdd + " - " + error.getMessage();
                            System.err.println(errorMsg);
                            errors.add(errorMsg);
                        });
                    
                    addDocumentMonos.add(addMono);
                } catch (Exception e) {
                    String error = "添加向量数据异常: " + fileToAddKey + " - " + e.getMessage();
                    System.err.println(error);
                    errors.add(error);
                }
            }
            
            // 执行异步处理
            if (!addDocumentMonos.isEmpty()) {
                System.out.println("[同步] 开始异步处理 " + addDocumentMonos.size() + " 个文档");
                // 使用Flux来处理异步操作
                Flux.fromIterable(addDocumentMonos)
                    .flatMap(mono -> mono)
                    .collectList()
                    .subscribe(
                        result -> System.out.println("[同步] 异步处理完成，处理了 " + result.size() + " 个文档"),
                        error -> System.err.println("[同步] 异步处理失败: " + error.getMessage())
                    );
                System.out.println("[同步] 异步处理已启动");
            }
            
            // 7. 生成同步报告
            StringBuilder result = new StringBuilder();
            result.append("向量数据库同步完成。\n");
            result.append("文件系统文件数量: ").append(fileSystemKeys.size()).append("\n");
            result.append("向量数据库文件数量: ").append(vectorDatabaseFiles.size()).append("\n");
            result.append("删除的向量数据: ").append(deletedVectors.size()).append(" 个\n");
            result.append("添加的向量数据: ").append(processedFiles.size()).append(" 个\n");
            result.append("错误数量: ").append(errors.size()).append(" 个\n");
            
            if (!deletedVectors.isEmpty()) {
                result.append("删除的文件:\n");
                for (String file : deletedVectors) {
                    result.append("- ").append(file).append("\n");
                }
            }
            
            if (!processedFiles.isEmpty()) {
                result.append("添加的文件:\n");
                for (String file : processedFiles) {
                    result.append("- ").append(file).append("\n");
                }
            }
            
            if (!errors.isEmpty()) {
                result.append("错误信息:\n");
                for (String error : errors) {
                    result.append("- ").append(error).append("\n");
                }
            }
            
            // 8. 添加详细的集合和文件片段统计
            result.append("\n=== 详细统计信息 ===\n");
            for (String collectionName : collections) {
                if (milvusDbService.hasCollection(collectionName)) {
                    try {
                        List<Map<String, Object>> allDocs = milvusDbService.queryAllDocuments(collectionName);
                        result.append("集合: ").append(collectionName).append(" (共 ").append(allDocs.size()).append(" 个片段)\n");
                        
                        // 按文件分组统计
                        Map<String, Integer> fileFragmentCount = new HashMap<>();
                        for (Map<String, Object> doc : allDocs) {
                            Object metadataObj = doc.get("metadata");
                            if (metadataObj != null) {
                                String metadata = metadataObj.toString();
                                try {
                                    Map<String, Object> meta = parseMetadata(metadata);
                                    String source = (String) meta.get("source");
                                    if (source != null) {
                                        fileFragmentCount.put(source, fileFragmentCount.getOrDefault(source, 0) + 1);
                                    }
                                } catch (Exception e) {
                                    // 忽略解析错误
                                }
                            }
                        }
                        
                        // 输出每个文件的片段数量
                        for (Map.Entry<String, Integer> entry : fileFragmentCount.entrySet()) {
                            result.append("  - ").append(entry.getKey()).append(": ").append(entry.getValue()).append(" 个片段\n");
                        }
                        
                        // 记录集合中的文件统计信息
                        result.append("  - 文件总数: ").append(fileFragmentCount.size()).append(" 个\n");
                        
                    } catch (Exception e) {
                        result.append("集合: ").append(collectionName).append(" - 查询失败: ").append(e.getMessage()).append("\n");
                    }
                } else {
                    result.append("集合: ").append(collectionName).append(" - 不存在\n");
                }
            }
            
            return result.toString();
        });
    }
    
    /**
     * 重新索引所有文档，清理孤立的向量数据
     * @return 重新索引结果
     */
    public Mono<String> reindexAllDocuments() {
        return Mono.fromCallable(() -> {
            Path basePath = getKnowledgeBaseRootPath();
            if (!Files.exists(basePath)) {
                return "知识库目录不存在";
            }
            
            List<String> processedFiles = new ArrayList<>();
            List<String> errors = new ArrayList<>();
            
            // 遍历所有文件
            Files.walk(basePath)
                .filter(Files::isRegularFile)
                .filter(path -> {
                    String fileName = path.getFileName().toString().toLowerCase();
                    // 处理所有支持的文件类型，包括txt文件
                    return fileName.endsWith(".pdf") || 
                           fileName.endsWith(".doc") || 
                           fileName.endsWith(".docx") ||
                           fileName.endsWith(".pptx") ||
                           fileName.endsWith(".ppt") ||
                           fileName.endsWith(".jpg") ||
                           fileName.endsWith(".jpeg") ||
                           fileName.endsWith(".png") ||
                           fileName.endsWith(".gif") ||
                           fileName.endsWith(".txt") ||
                           fileName.endsWith(".md") ||
                           fileName.endsWith(".csv");
                })
                .forEach(filePath -> {
                    try {
                        String relativePath = basePath.relativize(filePath).toString();
                        String knowledgeBaseId = basePath.relativize(filePath.getParent()).toString();
                        if (knowledgeBaseId.isEmpty()) {
                            knowledgeBaseId = "default_knowledge_base";
                        }
                        
                        System.out.println("处理文件: " + relativePath + ", 知识库ID: " + knowledgeBaseId);
                        
                        // 异步处理文档
                        ragService.addDocument(relativePath, knowledgeBaseId)
                            .subscribe(
                                result -> {
                                    System.out.println("处理成功: " + relativePath + " - " + result);
                                    processedFiles.add(relativePath);
                                },
                                error -> {
                                    String errorMsg = "处理文件 " + relativePath + " 失败: " + error.getMessage();
                                    System.err.println(errorMsg);
                                    errors.add(errorMsg);
                                }
                            );
                        
                    } catch (Exception e) {
                        String error = "处理文件 " + filePath + " 失败: " + e.getMessage();
                        System.err.println(error);
                        errors.add(error);
                    }
                });
            
            // 等待一段时间让异步处理完成
            try {
                Thread.sleep(5000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            
            StringBuilder result = new StringBuilder();
            result.append("重新索引完成。\n");
            result.append("成功处理文件数量: ").append(processedFiles.size()).append("\n");
            result.append("失败文件数量: ").append(errors.size()).append("\n");
            
            if (!processedFiles.isEmpty()) {
                result.append("成功处理的文件:\n");
                for (String file : processedFiles) {
                    result.append("- ").append(file).append("\n");
                }
            }
            
            if (!errors.isEmpty()) {
                result.append("失败的文件:\n");
                for (String error : errors) {
                    result.append("- ").append(error).append("\n");
                }
            }
            
            return result.toString();
        });
    }
    
    /**
     * 清理集合名称，确保符合Milvus规范
     * Milvus要求：集合名称必须以字母或下划线开头，只能包含字母、数字、下划线
     */
    private String sanitizeCollectionName(String rawName) {
        String key = TenantUtils.buildKbCollectionKey(rawName);
        System.out.println("[集合名称] 原始名称: '{}' -> hashKey: '{}'".replace("{}", String.valueOf(rawName)).replace("{}", key));
        return key;
    }
    
    /**
     * 将中文字符转换为英文标识符
     */
    private String convertChineseToEnglish(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        
        // 常见的中文映射
        Map<String, String> chineseToEnglish = new HashMap<>();
        chineseToEnglish.put("湖畔酒店项目", "hupan_hotel_project");
        chineseToEnglish.put("建筑设计规范", "architectural_design_standards");
        chineseToEnglish.put("BIM导则及标准", "bim_guidelines_standards");
        chineseToEnglish.put("项目", "project");
        chineseToEnglish.put("酒店", "hotel");
        chineseToEnglish.put("建筑", "architecture");
        chineseToEnglish.put("设计", "design");
        chineseToEnglish.put("规范", "standards");
        chineseToEnglish.put("导则", "guidelines");
        chineseToEnglish.put("标准", "standards");
        chineseToEnglish.put("BIM", "bim");
        
        String result = text;
        for (Map.Entry<String, String> entry : chineseToEnglish.entrySet()) {
            result = result.replace(entry.getKey(), entry.getValue());
        }
        
        // 如果还有中文字符，用拼音或通用标识符替换
        if (result.matches(".*[\\u4e00-\\u9fa5].*")) {
            // 对于"湖畔酒店项目"，特殊处理
            if (text.contains("湖畔酒店项目")) {
                return "hupan_hotel_project";
            }
            // 对于其他中文，用通用标识符
            result = result.replaceAll("[\\u4e00-\\u9fa5]+", "chinese");
        }
        
        return result;
    }
    
    /**
     * 解析JSON格式的metadata
     * @param json JSON字符串
     * @return 解析后的Map
     */
    private Map<String, Object> parseMetadata(String json) {
        try {
            if (json == null || json.trim().isEmpty()) {
                return new HashMap<>();
            }
            
            // 简单的JSON解析，处理常见的metadata格式
            Map<String, Object> result = new HashMap<>();
            
            // 移除花括号
            String content = json.trim();
            if (content.startsWith("{") && content.endsWith("}")) {
                content = content.substring(1, content.length() - 1);
            }
            
            // 分割键值对
            String[] pairs = content.split(",");
            for (String pair : pairs) {
                String[] kv = pair.split(":");
                if (kv.length == 2) {
                    String key = kv[0].trim().replace("\"", "");
                    String value = kv[1].trim().replace("\"", "");
                    result.put(key, value);
                }
            }
            
            return result;
        } catch (Exception e) {
            System.err.println("解析metadata失败: " + json + ", 错误: " + e.getMessage());
            return new HashMap<>();
        }
    }
}