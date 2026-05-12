package com.luanshuai.agent.util;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 租户上下文工具类
 * 用于从SecurityContext获取当前租户ID
 */
public class TenantContext {
    private static final Logger log = LoggerFactory.getLogger(TenantContext.class);

    /**
     * Reactor Context key used for propagating tenantId into ThreadLocal via context-propagation.
     */
    public static final String REACTOR_TENANT_ID_CONTEXT_KEY = "tenantId";
    
    // 默认租户ID（用于单租户私有化部署）
    private static final String DEFAULT_TENANT_ID = System.getenv("TENANT_ID");
    
    // 线程本地存储租户ID（用于非Spring Security场景）
    private static final ThreadLocal<String> TENANT_ID_HOLDER = new ThreadLocal<>();

    /**
     * Returns the raw ThreadLocal tenantId without any fallback logic.
     * Intended for context-propagation integration.
     */
    public static String getTenantIdFromThreadLocal() {
        return TENANT_ID_HOLDER.get();
    }
    
    /**
     * 获取当前租户ID
     * 优先级：ThreadLocal > JWT Claims > 环境变量 > 默认值
     */
    public static String getCurrentTenantId() {
        // 1. 优先从ThreadLocal获取（用于后台任务等场景）
        String tenantId = TENANT_ID_HOLDER.get();
        if (tenantId != null && !tenantId.isEmpty()) {
            return tenantId;
        }
        
        // 2. 从Spring Security获取（JWT中提取）
        try {
            Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
            if (authentication != null && authentication.getPrincipal() != null) {
                // 从Authentication的principal中获取tenantId（JWT Claims中包含）
                if (authentication.getPrincipal() instanceof java.util.Map) {
                    @SuppressWarnings("unchecked")
                    java.util.Map<String, Object> claims = (java.util.Map<String, Object>) authentication.getPrincipal();
                    Object tenantIdObj = claims.get("tenantId");
                    if (tenantIdObj != null && !tenantIdObj.toString().isEmpty()) {
                        return tenantIdObj.toString();
                    }
                    // 如果没有tenantId，尝试使用username作为租户ID
                    Object usernameObj = claims.get("username");
                    if (usernameObj != null && !usernameObj.toString().isEmpty()) {
                        return usernameObj.toString();
                    }
                }
            }
        } catch (Exception e) {
            log.debug("无法从SecurityContext获取租户ID: {}", e.getMessage());
        }
        
        // 3. 从环境变量获取（单租户私有化部署）
        if (DEFAULT_TENANT_ID != null && !DEFAULT_TENANT_ID.isEmpty()) {
            return DEFAULT_TENANT_ID;
        }
        
        // 4. 返回默认租户（开发环境）
        log.warn("未找到租户ID，使用默认租户: default");
        return "default";
    }
    
    /**
     * 设置当前线程的租户ID（用于后台任务等场景）
     */
    public static void setTenantId(String tenantId) {
        TENANT_ID_HOLDER.set(tenantId);
    }
    
    /**
     * 清除当前线程的租户ID
     */
    public static void clear() {
        TENANT_ID_HOLDER.remove();
    }
}

