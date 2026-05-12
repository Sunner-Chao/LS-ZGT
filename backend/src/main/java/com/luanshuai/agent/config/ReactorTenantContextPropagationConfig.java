// 包声明：应用配置包
package com.luanshuai.agent.config;

// 导入 TenantContext（应用封装的线程本地租户上下文工具类）
import com.luanshuai.agent.util.TenantContext;
// Micrometer ContextRegistry：用于注册 ThreadLocal 访问器，支持跨线程传播 context
import io.micrometer.context.ContextRegistry;
// PostConstruct 注解，用于在 Spring 容器初始化后执行方法
import jakarta.annotation.PostConstruct;
// 日志记录
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
// 声明为 Spring 配置类
import org.springframework.context.annotation.Configuration;
// Reactor Hooks，用于启用自动上下文传播
import reactor.core.publisher.Hooks;

/**
 * 启用将 Reactor Context 自动传播到 ThreadLocal 的支持。
 *
 * 目的：在 WebFlux 环境下，响应式流水线可能会跨线程切换（publishOn/subscribeOn），
 * 为了让基于 ThreadLocal 的 TenantContext 在 reactive 流程中可用，需要做两件事：
 * 1) 在 Micrometer 的 ContextRegistry 中注册 ThreadLocal 访问器
 * 2) 启用 Reactor 的 Hooks 自动上下文传播
 */
@Configuration
public class ReactorTenantContextPropagationConfig {
    // 日志记录器
    private static final Logger log = LoggerFactory.getLogger(ReactorTenantContextPropagationConfig.class);

    // 在容器启动后执行的初始化方法
    @PostConstruct
    public void enableTenantContextPropagation() {
        try {
            // 注册一个 ThreadLocal 访问器，使 ContextRegistry 知道如何从 ThreadLocal 读写租户 id
            ContextRegistry.getInstance().registerThreadLocalAccessor(
                TenantContext.REACTOR_TENANT_ID_CONTEXT_KEY, // 上下文键
                TenantContext::getTenantIdFromThreadLocal,   // 从 ThreadLocal 读取值的方法引用
                TenantContext::setTenantId,                 // 将值写入 ThreadLocal 的方法引用
                TenantContext::clear                        // 清理 ThreadLocal 的方法引用
            );
        } catch (Exception e) {
            // 如果已经注册（例如开发时热重载），则忽略并记录调试信息
            log.debug("[Tenant] ThreadLocalAccessor already registered: {}", e.getMessage());
        }

        try {
            // 启用 Reactor 的自动上下文传播功能，使 Reactor 在切换线程时携带 Context
            Hooks.enableAutomaticContextPropagation();
            log.info("[Tenant] Enabled Reactor automatic context propagation");
        } catch (Exception e) {
            // 若启用失败，可能导致 tenantId 在跨线程时丢失，记录警告
            log.warn("[Tenant] Failed to enable Reactor context propagation: {}", e.getMessage());
        }
    }
} 
