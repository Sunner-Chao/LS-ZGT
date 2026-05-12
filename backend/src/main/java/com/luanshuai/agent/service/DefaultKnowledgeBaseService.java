package com.luanshuai.agent.service; // 包声明：该类属于 com.luanshuai.agent.service

import com.luanshuai.agent.config.AppConfig; // 应用配置类，包含租户与知识库相关配置
import com.luanshuai.agent.util.TenantContext; // 租户上下文工具类（线程/任务内保存当前租户）
import com.luanshuai.agent.util.TenantUtils; // 租户路径构建辅助类
import org.slf4j.Logger; // 日志接口
import org.slf4j.LoggerFactory; // 日志工厂，用于创建Logger
import org.springframework.beans.factory.annotation.Autowired; // 自动注入注解
import org.springframework.stereotype.Service; // Service注解，标注为Spring管理的服务

import java.io.File; // 文件操作辅助类
import java.io.IOException; // IO异常
import java.nio.file.*; // NIO 文件和路径操作
import java.nio.file.attribute.BasicFileAttributes; // 文件属性访问
import java.util.concurrent.CompletableFuture; // 异步任务执行的Future
import java.util.concurrent.ExecutorService; // 线程池接口
import java.util.concurrent.Executors; // Executors工厂类，用于创建线程池

/**
 * 默认知识库注入服务
 * 说明：在用户首次登录或首次使用时，为租户初始化默认知识库（如“建筑设计规范”），并异步触发入库流程
 */
@Service // 声明为Spring托管的服务组件
public class DefaultKnowledgeBaseService {
    private static final Logger log = LoggerFactory.getLogger(DefaultKnowledgeBaseService.class); // 日志记录器
    
    @Autowired // 注入应用配置，包含租户存储模式和默认KB路径
    private AppConfig appConfig;
    
    @Autowired // 注入RAG服务，用于将文档入向量数据库/检索系统
    private RagService ragService;
    
    // 使用固定线程池执行异步入库任务，避免阻塞主线程
    private final ExecutorService executorService = Executors.newFixedThreadPool(2);
    
    /**
     * 初始化用户的默认知识库
     * 说明：
     * - 对于 admin 账户：不复制默认知识库，直接使用已有的全局知识库
     * - 对于其他租户：当租户知识库目录为空或不存在时，将镜像内置或已有的“建筑设计规范”复制到租户目录，并异步触发入库
     * 
     * @param tenantId 租户ID（用户ID）
     * @return true 表示初始化成功或不需要初始化；false 表示初始化失败
     */
    public boolean initializeDefaultKnowledgeBase(String tenantId) {
        try {
            // admin账户特殊处理：直接使用现有知识库，不执行复制与入库
            if ("admin".equals(tenantId)) {
                log.info("[DefaultKB] admin账户使用现有知识库，跳过初始化: tenantId={}", tenantId);
                return true; // 管理员不需要初始化
            }
            
            // 构建租户知识库路径（考虑存储模式 local/remote 等）
            String tenantKbPath = TenantUtils.buildTenantKnowledgeBasePath(
                tenantId, 
                null,
                appConfig.getTenant().getStorageMode(),
                appConfig.getTenant().getTenantKbPath()
            );
            
            Path tenantPath = Paths.get(tenantKbPath); // 租户目录Path对象
            
            // 如果目录已存在，检查是否为空；若非空则跳过初始化
            if (Files.exists(tenantPath)) {
                try {
                    boolean isEmpty = Files.list(tenantPath).findAny().isEmpty(); // 检查是否存在任意文件或子目录
                    if (!isEmpty) {
                        log.info("[DefaultKB] 租户 {} 的知识库目录已存在且不为空，跳过初始化", tenantId);
                        return true; // 已有内容，无需初始化
                    }
                } catch (IOException e) {
                    // 记录检查失败的警告，但继续尝试创建/初始化以避免阻塞用户
                    log.warn("[DefaultKB] 检查租户知识库目录失败: {}", e.getMessage());
                }
            } else {
                // 目录不存在则创建租户知识库根目录
                Files.createDirectories(tenantPath);
                log.info("[DefaultKB] 创建租户知识库目录: {}", tenantKbPath);
            }
            
            // 尝试定位默认知识库：优先使用镜像内置路径（appConfig 配置），否则回退到已存在的知识库目录下的“建筑设计规范”
            String defaultKbPath = appConfig.getTenant().getDefaultKbPath();
            Path defaultPath = Paths.get(defaultKbPath);
            
            if (!Files.exists(defaultPath)) { // 镜像内置路径不存在，尝试已有知识库路径
                String existingKbPath = appConfig.getKnowledgeBase().getPath() + File.separator + "建筑设计规范";
                Path existingPath = Paths.get(existingKbPath);
                if (Files.exists(existingPath)) {
                    defaultPath = existingPath; // 使用已有的知识库目录作为默认来源
                    log.info("[DefaultKB] 使用现有知识库路径作为默认知识库: {}", existingKbPath);
                } else {
                    // 如果都不存在，记录警告并返回失败
                    log.warn("[DefaultKB] 默认知识库路径不存在: {} 和 {}", defaultKbPath, existingKbPath);
                    return false;
                }
            }
            
            // 在租户目录下创建“建筑设计规范”子目录并复制内容
            Path defaultKbDir = tenantPath.resolve("建筑设计规范");
            Files.createDirectories(defaultKbDir); // 确保目录存在
            
            log.info("[DefaultKB] 开始复制默认知识库（建筑设计规范）: {} -> {}", defaultPath, defaultKbDir);
            copyDirectory(defaultPath, defaultKbDir); // 递归复制目录内容
            log.info("[DefaultKB] 默认知识库复制完成: tenantId={}", tenantId);
            
            // 异步入库：使用线程池提交任务，避免阻塞当前线程
            CompletableFuture.runAsync(() -> {
                try {
                    // 将复制后的目录入库到检索系统（RAG）中
                    ingestDefaultKnowledgeBase(tenantId, defaultKbDir, "建筑设计规范");
                } catch (Exception e) {
                    log.error("[DefaultKB] 默认知识库入库失败: tenantId={}, error={}", tenantId, e.getMessage());
                }
            }, executorService);
            
            return true; // 初始复制并触发入库成功
        } catch (Exception e) {
            log.error("[DefaultKB] 初始化默认知识库失败: tenantId={}, error={}", tenantId, e.getMessage());
            return false; // 捕获任何异常并返回失败
        }
    }
    
    /**
     * 复制目录（递归）
     * 说明：深度遍历 source 下所有文件和目录，按相对路径复制到 target 中，若目标存在则覆盖
     */
    private void copyDirectory(Path source, Path target) throws IOException {
        Files.walkFileTree(source, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                Path targetDir = target.resolve(source.relativize(dir)); // 计算目标目录的相对位置
                Files.createDirectories(targetDir); // 创建目标子目录（若不存在）
                return FileVisitResult.CONTINUE; // 继续遍历
            }
            
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Path targetFile = target.resolve(source.relativize(file)); // 计算目标文件路径
                Files.copy(file, targetFile, StandardCopyOption.REPLACE_EXISTING); // 复制并覆盖已存在文件
                return FileVisitResult.CONTINUE; // 继续遍历
            }
        });
    }
    
    /**
     * 入库默认知识库
     * 对指定的知识库目录进行遍历并将支持的文档提交到RAG服务进行入库
     * 
     * @param tenantId 租户ID
     * @param kbPath 知识库目录路径（已复制到租户目录）
     * @param kbName 知识库名称（显示名）
     */
    private void ingestDefaultKnowledgeBase(String tenantId, Path kbPath, String kbName) {
        try {
            log.info("[DefaultKB] 开始入库默认知识库: tenantId={}, kbName={}", tenantId, kbName); // 记录开始入库
            
            // 设置租户上下文（线程/任务范围内），以便 RagService 能够识别目标租户
            TenantContext.setTenantId(tenantId);
            
            try {
                // 获取租户知识库根路径，用于计算文件的相对路径（作为文档ID或存储路径）
                String tenantKbPath = TenantUtils.buildTenantKnowledgeBasePath(
                    tenantId, 
                    null,
                    appConfig.getTenant().getStorageMode(),
                    appConfig.getTenant().getTenantKbPath()
                );
                Path tenantPath = Paths.get(tenantKbPath);
                
                // 遍历 kbPath 下的所有文件，并过滤出支持的文档类型进行入库
                Files.walk(kbPath)
                    .filter(Files::isRegularFile) // 仅处理常规文件
                    .filter(path -> {
                        String fileName = path.getFileName().toString().toLowerCase();
                        // 支持的文件类型：PDF、DOC、DOCX、TXT
                        return fileName.endsWith(".pdf") || 
                               fileName.endsWith(".doc") || 
                               fileName.endsWith(".docx") || 
                               fileName.endsWith(".txt");
                    })
                    .forEach(filePath -> { // 对每个符合条件的文件进行处理
                        try {
                            // 计算相对路径（相对于租户知识库根目录），并将 Windows 路径分隔符转换为“/”以统一存储格式
                            String relativePath = tenantPath.relativize(filePath).toString().replace("\\", "/");
                            
                            // 记录并调用 RagService.addDocument 进行入库（异步订阅结果）
                            log.info("[DefaultKB] 入库文档: tenantId={}, kbName={}, file={}", tenantId, kbName, relativePath);
                            ragService.addDocument(relativePath, kbName, tenantId)
                                .subscribe(
                                    result -> log.info("[DefaultKB] 文档入库成功: {}", relativePath), // 入库成功回调
                                    error -> log.error("[DefaultKB] 文档入库失败: {}, error={}", relativePath, error.getMessage()) // 入库失败回调
                                );
                            
                            // 为避免并发提交过多导致资源过载，稍作延迟
                            Thread.sleep(200); // 延迟 200ms
                        } catch (Exception e) {
                            // 单个文件处理失败时记录错误并继续处理其余文件
                            log.error("[DefaultKB] 处理文件失败: {}, error={}", filePath, e.getMessage());
                        }
                    });
                
                log.info("[DefaultKB] 默认知识库入库完成: tenantId={}, kbName={}", tenantId, kbName); // 入库完成日志
            } finally {
                // 清除租户上下文，避免影响其他线程或后续任务
                TenantContext.clear();
            }
        } catch (Exception e) {
            // 捕获并记录任何入库过程中的异常
            log.error("[DefaultKB] 入库默认知识库失败: tenantId={}, kbName={}, error={}", tenantId, kbName, e.getMessage(), e);
        }
    }
    
    /**
     * 检查并初始化当前用户的默认知识库
     * 说明：通常在用户登录或首次访问时调用，以确保租户有一个初始的知识库内容
     */
    public void checkAndInitializeCurrentUser() {
        String tenantId = TenantContext.getCurrentTenantId(); // 从上下文读取当前租户ID
        // 忽略无效或默认租户，只有明确的租户ID才需要初始化
        if (tenantId != null && !tenantId.isEmpty() && !"default".equals(tenantId)) {
            initializeDefaultKnowledgeBase(tenantId); // 异步或同步执行初始化操作
        }
    }
}

