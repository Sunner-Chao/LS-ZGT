// 包声明：本类位于应用的配置包中
package com.luanshuai.agent.config;

// 引入所需的 Java 标准库类型
import java.security.Key;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

// 引入日志记录与 Spring / Security / Reactor 相关类型
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.ReactiveUserDetailsService;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Component;

// 引入 JWT 处理库（jjwt）与 Reactor
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.JwtParser;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;
import reactor.core.publisher.Mono;

// 声明为 Spring 组件，以便在运行时注入使用
@Component
public class JwtTokenProvider {

    // 应用配置对象（用于读取 JWT 密钥、过期时间等配置）
    private final AppConfig appConfig;
    // 日志记录器，用于记录调试/错误信息
    private static final Logger log = LoggerFactory.getLogger(JwtTokenProvider.class);
    // JWT 解析器，复用以提高性能
    private final JwtParser jwtParser;

    // ReactiveUserDetailsService 异步加载用户信息（懒加载注入以避免循环依赖）
    @Autowired
    @Lazy
    private ReactiveUserDetailsService userDetailsService;
    // 用于签名/验证 JWT 的对称密钥（HMAC）
    private final Key key;

    // 构造函数：注入 AppConfig 并初始化签名密钥与解析器
    @Autowired
    public JwtTokenProvider(AppConfig appConfig) {
        this.appConfig = appConfig; // 保存配置引用
        // 从配置中读取 JWT secret 并构造 HMAC-SHA 密钥
        this.key = Keys.hmacShaKeyFor(appConfig.getJwt().getSecret().getBytes());
        // 构建可复用的 JwtParser 实例
        this.jwtParser = Jwts.parserBuilder().setSigningKey(key).build();
    }

    // 生成 JWT：包含用户名、租户 ID、签发时间与过期时间
    public String generateToken(Authentication authentication) {
        // 从 Authentication 中取出 UserDetails（当前认证主体）
        UserDetails userDetails = (UserDetails) authentication.getPrincipal();
        // 当前时间
        Date now = new Date();
        // 过期时间 = 当前时间 + 配置中的过期毫秒数
        Date expiryDate = new Date(now.getTime() + appConfig.getJwt().getExpiration());

        // 租户ID策略：默认使用用户名作为租户ID（每个用户独立租户）
        // 这样每个账号都有自己独立的知识库（可部署为单租户或多租户）
        String tenantId = userDetails.getUsername();
        
        // 如果部署环境设置了 TENANT_ID 环境变量（私有化单租户部署），优先使用环境变量
        String envTenantId = System.getenv("TENANT_ID");
        if (envTenantId != null && !envTenantId.isEmpty()) {
            tenantId = envTenantId; // 使用 env 指定的租户 ID
            log.debug("使用环境变量TENANT_ID作为租户ID: {}", tenantId); // 打印调试信息
        }

        // 使用 JJWT 构造并签名 token
        return Jwts.builder()
                .setSubject(userDetails.getUsername()) // 设置 subject 为用户名
                .claim("tenantId", tenantId)  // 在 Claims 中加入租户 ID
                .setIssuedAt(new Date()) // 签发时间
                .setExpiration(expiryDate) // 过期时间
                .signWith(key, SignatureAlgorithm.HS256) // 使用 HS256 签名
                .compact(); // 产生最终的 JWT 字符串
    }

    // 从 JWT 中解析用户名（sub/subject）
    public String getUsernameFromJWT(String token) {
        // 解析 token，取出 Claims
        Claims claims = jwtParser
                .parseClaimsJws(token)
                .getBody();

        // 返回 subject（通常为用户名）
        return claims.getSubject();
    }

    // 从 JWT Claims 中提取 tenantId（兼容旧版本若无 tenantId 则回退到 subject）
    public String getTenantIdFromJWT(String token) {
        // 解析 token 的 Claims
        Claims claims = jwtParser
                .parseClaimsJws(token)
                .getBody();
        // 从 Claims 中读取 tenantId 字段
        String tenantId = claims.get("tenantId", String.class);
        // 如果 tenantId 存在且非空则直接返回
        if (tenantId != null && !tenantId.trim().isEmpty()) {
            return tenantId;
        }
        // 向后兼容：如果旧 token 没有 tenantId，则使用 subject（用户名）作为降级方案
        return claims.getSubject();
    }

    // 异步生成 Authentication 对象（用于 Reactive Security）
    public Mono<Authentication> getAuthentication(String token) {
        // 先解析用户名
        String username = getUsernameFromJWT(token);
        // 解析 Claims 中的 tenantId
        Claims claims = jwtParser.parseClaimsJws(token).getBody();
        String tenantId = claims.get("tenantId", String.class);
        
        // 通过 ReactiveUserDetailsService 异步加载用户信息并映射为 Authentication
        return userDetailsService.findByUsername(username)
            .map(userDetails -> {
                // 从 Claims 或用户信息中提取租户 ID
                String extractedTenantId = tenantId;
                if (extractedTenantId == null || extractedTenantId.isEmpty()) {
                    extractedTenantId = userDetails.getUsername();
                }
                
                // 构造一个 principal map，包含 username、tenantId、authorities，方便后续访问
                Map<String, Object> principalMap = new HashMap<>();
                principalMap.put("username", userDetails.getUsername());
                principalMap.put("tenantId", extractedTenantId);
                principalMap.put("authorities", userDetails.getAuthorities());
                
                // 返回一个 UsernamePasswordAuthenticationToken，credentials 置为空字符串
                return new UsernamePasswordAuthenticationToken(principalMap, "", userDetails.getAuthorities());
            });
    }

    // 验证 token 的有效性（签名、格式、过期等）
    public boolean validateToken(String authToken) {
        // 空或全为空白字符串视为无效
        if (authToken == null || authToken.trim().isEmpty()) {
            log.warn("JWT令牌为空");
            return false;
        }
        
        try {
            // 使用预构建的 jwtParser 解析 token，如有异常则表示无效
            jwtParser.parseClaimsJws(authToken);
            // 记录调试信息，表明 token 验证成功
            log.debug("JWT令牌验证成功 from: {} at {}", Thread.currentThread().getName(), System.currentTimeMillis());
            return true; // 验证通过
        } catch (JwtException | IllegalArgumentException ex) {
            // 捕获常见的 JWT 相关异常并记录更具体的错误
            if (ex instanceof JwtException) {
                log.error("JWT验证失败: {}", ex.getMessage());
                String msg = ex.getMessage() == null ? "" : ex.getMessage();
                if (msg.contains("signature")) {
                    // 签名验证失败，可能是 secret 不匹配或 token 被修改
                    log.error("JWT签名验证失败，可能是密钥不匹配或token被篡改");
                } else if (msg.contains("expired")) {
                    // token 已过期
                    log.error("JWT令牌已过期");
                } else if (msg.contains("malformed")) {
                    // token 格式错误
                    log.error("JWT令牌格式错误");
                }
            } else {
                // 非 JwtException 的 IllegalArgumentException（参数异常等）
                log.error("JWT令牌为空或格式无效: {}", ex.getMessage());
            }
        }
        // 默认返回 false 表示验证失败
        return false;
    }
}