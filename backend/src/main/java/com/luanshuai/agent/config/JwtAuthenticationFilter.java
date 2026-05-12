package com.luanshuai.agent.config;

import org.springframework.http.HttpHeaders;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import org.springframework.web.server.ServerWebExchange;
import com.luanshuai.agent.util.TenantContext;
import reactor.core.publisher.Mono;

/**
 * JwtAuthenticationFilter
 * 作用：在 WebFlux 的过滤链中解析并验证 JWT，并将租户信息及认证信息写入 Reactor Context 与 SecurityContext
 * 行为说明：
 *  - 对调试路径 (/debug/, /api/debug/) 直接放行并写入默认租户
 *  - 从 Authorization: Bearer <token> 中提取 token，验证 token 有效性
 *  - 若 token 有效，从 token 中解析 tenantId 并获取 Authentication 写入上下文
 *  - 若 token 无效或缺失，则将请求视为匿名并写入默认租户
 */
public class JwtAuthenticationFilter implements WebFilter {

    // 注：用于验证、解析 JWT 并获取认证信息的提供器（由外部注入）
    private final JwtTokenProvider jwtTokenProvider;

    /**
     * 构造函数：注入用于验证/解析 JWT 的提供器
     * @param jwtTokenProvider 提供 token 验证与解析的方法
     */
    public JwtAuthenticationFilter(JwtTokenProvider jwtTokenProvider) {
        // 将注入的 JwtTokenProvider 保存到实例字段中以便后续使用
        this.jwtTokenProvider = jwtTokenProvider;
    }

    /**
     * 从请求的 Authorization 头中提取 Bearer token（若存在）
     * @param exchange 当前请求上下文
     * @return token 字符串或 null（表示未提供或格式不正确）
     */
    private String extractToken(ServerWebExchange exchange) {
        // 从请求头中读取 Authorization 字段（常见格式："Bearer <token>")
        String authHeader = exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        // 检查 Authorization 是否为 Bearer token（存在且以 "Bearer " 开头）
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            // 截取并返回实际的 token 部分（移除前缀）
            return authHeader.substring(7);
        }
        // 无效或缺失 Authorization 时返回 null 表示未认证
        return null;
    }

    /**
     * 过滤方法：根据 token 状态设置租户上下文与安全上下文后继续过滤链
     */
    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        // 获取当前请求的路径，用于判断是否需要特殊放行
        String path = exchange.getRequest().getPath().value();
        
        // 如果请求是调试接口，直接放行并写入默认租户上下文（避免对开发接口进行验证）
        if (path.startsWith("/debug/") || path.startsWith("/api/debug/")) {
            return chain.filter(exchange)
                .contextWrite(ctx -> ctx.put(TenantContext.REACTOR_TENANT_ID_CONTEXT_KEY, "default"));
        }
        
        // 从请求中提取出 Bearer token（如果存在）
        String token = extractToken(exchange);

        // 如果没有 token（匿名请求或 permitAll），设置默认租户并继续过滤链
        if (token == null) {
            return chain.filter(exchange)
                .contextWrite(ctx -> ctx.put(TenantContext.REACTOR_TENANT_ID_CONTEXT_KEY, "default"));
        }

        // 验证 token 的有效性（签名、过期等）
        if (jwtTokenProvider.validateToken(token)) {
            // 从 token 中解析 tenantId（如果 token 中携带该信息）
            String tenantId = jwtTokenProvider.getTenantIdFromJWT(token);
            // 如果 tenantId 为空或仅空白，则使用默认租户
            if (tenantId == null || tenantId.trim().isEmpty()) {
                tenantId = "default";
            }
            final String finalTenantId = tenantId; // lambda 中的不可变捕获变量

            // 异步获取 Authentication（可能包含用户角色/权限），并将其写入 Reactive Security Context
            return jwtTokenProvider.getAuthentication(token)
                .flatMap(auth -> {
                    // 将租户 ID 写入 Reactor Context，随后写入安全上下文并继续过滤链
                    return chain.filter(exchange)
                        .contextWrite(ctx -> ctx.put(TenantContext.REACTOR_TENANT_ID_CONTEXT_KEY, finalTenantId))
                        .contextWrite(ReactiveSecurityContextHolder.withAuthentication(auth));
                });
        }

        // 如果 token 无效或验证失败，视为匿名请求并写入默认租户后继续过滤链
        return chain.filter(exchange)
            .contextWrite(ctx -> ctx.put(TenantContext.REACTOR_TENANT_ID_CONTEXT_KEY, "default"));
    }
}