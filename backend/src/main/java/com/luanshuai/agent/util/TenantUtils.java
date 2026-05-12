package com.luanshuai.agent.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 租户工具类
 * 用于生成租户隔离的集合名和路径
 */
public class TenantUtils {
    private static final Logger log = LoggerFactory.getLogger(TenantUtils.class);

    private static final String DEFAULT_TENANT_KB_BASE_PATH_SERVER = "/data/knowledge_base/tenants";
    private static final String DEFAULT_TENANT_KB_BASE_PATH_LOCAL = "./data/knowledge_base/tenants";
    
    /**
     * 构建租户隔离的Milvus集合名
     * 格式: t_{tenantId}__{kbName}
     * 
     * @param tenantId 租户ID
     * @param kbName 知识库名称（原始名称；将统一做 hash 处理以避免命名冲突）
     * @return 租户隔离的集合名
     */
    public static String buildTenantCollectionName(String tenantId, String kbName) {
        if (tenantId == null || tenantId.isEmpty()) {
            tenantId = "default";
        }
        if (kbName == null || kbName.isEmpty()) {
            kbName = "default_knowledge_base";
        }
        
        // tenantId 仍需清理为合法标识；kbName 统一使用 hash 映射，避免任何名称导致集合名冲突
        String cleanTenantId = sanitizeCollectionName(tenantId);
        String cleanKbName = buildKbCollectionKey(kbName);
        
        String collectionName = "t_" + cleanTenantId + "__" + cleanKbName;
        
        // 确保长度不超过64字符（Milvus限制）
        if (collectionName.length() > 64) {
            int maxKbNameLength = 64 - 3 - cleanTenantId.length() - 2; // t_ + tenantId + __ + kbName
            if (maxKbNameLength > 0) {
                cleanKbName = cleanKbName.substring(0, Math.min(cleanKbName.length(), maxKbNameLength));
                collectionName = "t_" + cleanTenantId + "__" + cleanKbName;
            } else {
                // 如果租户ID太长，截断
                cleanTenantId = cleanTenantId.substring(0, Math.min(cleanTenantId.length(), 20));
                collectionName = "t_" + cleanTenantId + "__" + cleanKbName;
            }
        }
        
        log.debug("构建租户集合名: tenantId={}, kbName={}, collectionName={}", tenantId, kbName, collectionName);
        return collectionName;
    }

    /**
     * 将“知识库名称(原始)”映射为 Milvus 允许的集合 key（不含租户前缀）。
     * 规则：
     * - 统一使用 SHA-256 的短前缀，保证确定性并避免中文/符号清理导致的冲突
     * - 产物固定格式：kb_{hexPrefix}
     */
    public static String buildKbCollectionKey(String rawKbName) {
        if (rawKbName == null || rawKbName.trim().isEmpty()) {
            return "default_knowledge_base";
        }
        String trimmed = rawKbName.trim();
        if ("default_knowledge_base".equals(trimmed)) {
            return "default_knowledge_base";
        }
        // 16 hex chars ~= 64-bit，碰撞概率在本场景足够低，同时长度可控
        String hex = sha256Hex(trimmed).substring(0, 16);
        return "kb_" + hex;
    }

    /**
     * 兼容旧版本的集合命名（基于“清理字符”而非 hash）。
     * 注意：该映射可能冲突，仅用于迁移/回退查找。
     */
    public static String buildTenantCollectionNameLegacy(String tenantId, String kbName) {
        if (tenantId == null || tenantId.isEmpty()) {
            tenantId = "default";
        }
        if (kbName == null || kbName.isEmpty()) {
            kbName = "default_knowledge_base";
        }

        String cleanTenantId = sanitizeCollectionName(tenantId);
        String cleanKbName = sanitizeCollectionName(kbName);

        String collectionName = "t_" + cleanTenantId + "__" + cleanKbName;
        if (collectionName.length() > 64) {
            int maxKbNameLength = 64 - 3 - cleanTenantId.length() - 2;
            if (maxKbNameLength > 0) {
                cleanKbName = cleanKbName.substring(0, Math.min(cleanKbName.length(), maxKbNameLength));
                collectionName = "t_" + cleanTenantId + "__" + cleanKbName;
            } else {
                cleanTenantId = cleanTenantId.substring(0, Math.min(cleanTenantId.length(), 20));
                collectionName = "t_" + cleanTenantId + "__" + cleanKbName;
            }
        }
        return collectionName;
    }
    
    /**
     * 构建租户隔离的知识库路径（服务器存储）
     * 格式: /data/knowledge_base/tenants/{tenantId}/{kbName}/...
     * 
     * @param tenantId 租户ID（用户ID）
     * @param kbName 知识库名称（可选）
     * @return 租户隔离的路径
     */
    public static String buildTenantKnowledgeBasePath(String tenantId, String kbName) {
        if (tenantId == null || tenantId.isEmpty()) {
            tenantId = "default";
        }
        
        // 服务器存储路径：/data/knowledge_base/tenants/{userId}/{kbName}/
        String basePath = DEFAULT_TENANT_KB_BASE_PATH_SERVER + "/" + sanitizePath(tenantId);
        if (kbName != null && !kbName.isEmpty()) {
            basePath += "/" + sanitizePath(kbName);
        }
        
        return basePath;
    }
    
    /**
     * 构建租户隔离的知识库路径（支持配置的存储模式）
     * 如果storageMode为local，使用本地路径；否则使用服务器路径
     */
    public static String buildTenantKnowledgeBasePath(String tenantId, String kbName, String storageMode) {
        return buildTenantKnowledgeBasePath(tenantId, kbName, storageMode, null);
    }

    /**
     * 构建租户隔离的知识库路径（支持配置 tenantKbBasePath）
     * - local 模式默认使用 ./data/knowledge_base/tenants
     * - server 模式默认使用 /data/knowledge_base/tenants
     *
     * @param tenantId 租户ID
     * @param kbName 知识库名称（可选）
     * @param storageMode local/server
     * @param tenantKbBasePath 可选：外部配置的租户知识库根目录（例如来自 TENANT_KB_PATH）
     */
    public static String buildTenantKnowledgeBasePath(String tenantId, String kbName, String storageMode, String tenantKbBasePath) {
        if (tenantId == null || tenantId.isEmpty()) {
            tenantId = "default";
        }

        boolean isLocal = "local".equalsIgnoreCase(storageMode);
        String base = isLocal ? DEFAULT_TENANT_KB_BASE_PATH_LOCAL : DEFAULT_TENANT_KB_BASE_PATH_SERVER;

        if (!isLocal && tenantKbBasePath != null && !tenantKbBasePath.trim().isEmpty()) {
            // 仅 server 模式允许通过配置覆盖（避免 local 模式意外写到容器不可见目录）
            base = tenantKbBasePath.trim().replaceAll("/+$", "");
        }

        String full = base + "/" + sanitizePath(tenantId);
        if (kbName != null && !kbName.isEmpty()) {
            full += "/" + sanitizePath(kbName);
        }
        return full;
    }
    
    /**
     * 获取默认知识库路径（镜像内置）
     * 格式: /app/default_kb/{kbName}/...
     */
    public static String getDefaultKnowledgeBasePath(String kbName) {
        if (kbName == null || kbName.isEmpty()) {
            return "/app/default_kb";
        }
        return "/app/default_kb/" + sanitizePath(kbName);
    }
    
    /**
     * 清理集合名称，确保符合Milvus规范
     * 只能包含字母、数字、下划线，且必须以字母或下划线开头
     */
    private static String sanitizeCollectionName(String name) {
        if (name == null || name.isEmpty()) {
            return "default";
        }

        boolean hasAsciiAlnum = name.matches(".*[a-zA-Z0-9].*");
        
        // 移除前后空格
        String cleaned = name.trim();
        
        // 替换不合法字符为下划线
        cleaned = cleaned.replaceAll("[^a-zA-Z0-9_]", "_");
        
        // 如果第一个字符不是字母或下划线，添加前缀
        if (!cleaned.matches("^[a-zA-Z_].*")) {
            cleaned = "kb_" + cleaned;
        }

        // 移除连续的下划线
        cleaned = cleaned.replaceAll("_+", "_");

        // 移除末尾的下划线
        cleaned = cleaned.replaceAll("_+$", "");

        // 如果清理后为空（例如原始名全部为非ASCII字符被替换为下划线并移除），
        // 使用原始名称的短哈希作为后缀，确保在 Milvus 集合名上具有确定性且低冲突性
        if (cleaned.isEmpty() || (!hasAsciiAlnum && "kb".equals(cleaned))) {
            String hex = Integer.toHexString(name.hashCode());
            if (hex.startsWith("-")) hex = hex.substring(1);
            if (hex.length() > 8) hex = hex.substring(0, 8);
            return "kb_" + hex;
        }

        return cleaned;
    }
    
    /**
     * 清理路径名称，防止路径遍历攻击
     */
    private static String sanitizePath(String name) {
        if (name == null || name.isEmpty()) {
            return "";
        }
        
        // 移除路径分隔符和危险字符
        String cleaned = name.replaceAll("[\\\\/:*?\"<>|]", "_");
        
        // 移除前后空格和点
        cleaned = cleaned.trim().replaceAll("^\\.+|\\.+$", "");
        
        return cleaned.isEmpty() ? "default" : cleaned;
    }

    /**
     * 为向量数据库 metadata.source 生成稳定的 key。
     * 
     * 目的：sync_vector_db 需要用 metadata.source 与文件系统相对路径做比对。
     * 之前使用省略号截断会导致“永远认为缺失，从而重复入库”。
     * 
     * 规则：
     * - 统一分隔符为 '/'
     * - 去掉开头的 '/'
     * - 若过长则用 sha256 的短前缀代替（可重复计算，避免长度/编码问题）
     */
    public static String buildVectorSourceKey(String rawRelativePath) {
        if (rawRelativePath == null) {
            return "";
        }
        String normalized = rawRelativePath.replace('\\', '/');
        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        // 180 是经验阈值：足够覆盖常见路径，同时避免 metadata 过长
        if (normalized.length() <= 180) {
            return normalized;
        }
        return "h:" + sha256Hex(normalized).substring(0, 16);
    }

    private static String sha256Hex(String s) {
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(s.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            // 极端情况下退化为 hashCode（仍然确定性，但冲突概率更高）
            String hex = Integer.toHexString(s.hashCode());
            if (hex.startsWith("-")) hex = hex.substring(1);
            return hex;
        }
    }
}

