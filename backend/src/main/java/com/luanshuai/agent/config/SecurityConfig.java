// 包声明：安全相关配置
package com.luanshuai.agent.config;

// 导入 Spring 与安全相关类
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.ReactiveAuthenticationManager;
import org.springframework.security.authentication.UserDetailsRepositoryReactiveAuthenticationManager;
import org.springframework.security.config.annotation.method.configuration.EnableReactiveMethodSecurity;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.config.web.server.SecurityWebFiltersOrder;
import org.springframework.security.core.userdetails.MapReactiveUserDetailsService;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.ReactiveUserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.context.NoOpServerSecurityContextRepository;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import java.util.Arrays;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import reactor.core.publisher.Mono;

// 开启 WebFlux 的安全支持与方法级别的安全注解
@Configuration
@EnableWebFluxSecurity
@EnableReactiveMethodSecurity
public class SecurityConfig {

    // JwtTokenProvider 用于解析/验证 JWT，并与安全过滤器集成
    private final JwtTokenProvider jwtTokenProvider;

    // 注册 JwtAuthenticationFilter 为 Bean，便于在 Security 过滤链中使用
    @Bean
    public JwtAuthenticationFilter jwtAuthenticationFilter() {
        return new JwtAuthenticationFilter(jwtTokenProvider);
    }

    // 构造注入 JwtTokenProvider
    public SecurityConfig(JwtTokenProvider jwtTokenProvider) {
        this.jwtTokenProvider = jwtTokenProvider;
    }

    // CORS 配置：允许跨域访问（在 dev 或特定场景下需要）
    @Bean
    public org.springframework.web.cors.reactive.CorsWebFilter corsWebFilter() {
        CorsConfiguration corsConfig = new CorsConfiguration();
        // 允许所有来源（生产可进一步限制）
        corsConfig.setAllowedOriginPatterns(Arrays.asList("*"));
        // 预检请求缓存时长（秒）
        corsConfig.setMaxAge(3600L);
        // 允许的方法
        corsConfig.setAllowedMethods(Arrays.asList("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        // 允许的请求头
        corsConfig.setAllowedHeaders(Arrays.asList("Content-Type", "Authorization"));
        // 允许携带 Cookie
        corsConfig.setAllowCredentials(true);

        // 将 CORS 配置注册到所有路径
        org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource source = 
            new org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", corsConfig);

        // 返回 WebFilter 实例用于处理跨域请求
        return new org.springframework.web.cors.reactive.CorsWebFilter(source);
    }

    // Security 过滤链：配置鉴权策略与异常处理
    @Bean
    public SecurityWebFilterChain securityWebFilterChain(ServerHttpSecurity http, JwtAuthenticationFilter jwtAuthenticationFilter) {
        return http
            // 不使用默认的 SecurityContext 存储（使用 NoOp 表示不在 HTTP session 中存储）
            .securityContextRepository(NoOpServerSecurityContextRepository.getInstance())
            // 在认证之前添加自定义的 JWT 过滤器
            .addFilterBefore(jwtAuthenticationFilter, SecurityWebFiltersOrder.AUTHENTICATION)
            // 授权规则
            .authorizeExchange(exchanges -> exchanges
                // 放行所有 OPTIONS 预检请求
                .pathMatchers(HttpMethod.OPTIONS, "/**").permitAll() // 新增：彻底放行所有预检请求
                // 放行一系列公开 API 和静态资源
                .pathMatchers("/api/token", "/api/upload", "/api/upload/**", "/api/chat/**", "/api/kb/tree", "/api/kb/*/files", "/api/kb/files/**", "/api/kb/preview", "/api/kb/page-preview", "/api/kb/download", "/api/kb/markdown_preview", "/api/kb/create_folder", "/api/kb/rename", "/api/kb/delete", "/api/kb/debug/**", "/api/kb/search", "/api/sync_vector_db", "/api/reindex_all", "/api/debug/**", "/api/agent/config", "/api/llm/**", "/api/health/llm", "/", "/index.html", "/static/**", "/api/health", "/favicon.ico", "/static/**", "/*.ico", "/*.png", "/*.jpg", "/*.jpeg", "/*.gif", "/*.svg", "/*.css", "/*.js").permitAll()
                // 其他请求需要认证
                .anyExchange().authenticated()
            )
            // 关闭表单登录（无状态 API 应禁用）
            .formLogin().disable()
            // 关闭 HTTP Basic
            .httpBasic().disable()
            // 关闭 CSRF（对纯 API 服务通常禁用）
            .csrf().disable()
            // 允许同源 iframe 嵌入（用于 PDF 预览）
            .headers(headers -> headers
                .xssProtection(xss -> xss.disable())
                .contentSecurityPolicy(csp -> csp.policyDirectives("frame-ancestors 'self'"))
            )
            // 异常处理：未认证响应返回 401 JSON
            .exceptionHandling()
                .authenticationEntryPoint((exchange, ex) -> {
                    ServerHttpResponse response = exchange.getResponse();
                    // 设置状态码为 401
                    response.setStatusCode(HttpStatus.UNAUTHORIZED);
                    // 设置返回类型为 JSON
                    response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
                    String body = "{\"error\":\"Unauthorized\"}";
                    // 返回 JSON 响应体
                    return response.writeWith(Mono.just(response.bufferFactory().wrap(body.getBytes())));
                })
            .and()
            .build();
    }

    // Reactive AuthenticationManager：结合用户服务与密码编码器进行认证
    @Bean
    public ReactiveAuthenticationManager authenticationManager(ReactiveUserDetailsService userDetailsService, PasswordEncoder passwordEncoder) {
        UserDetailsRepositoryReactiveAuthenticationManager manager = new UserDetailsRepositoryReactiveAuthenticationManager(userDetailsService);
        // 设置密码编码器（BCrypt）
        manager.setPasswordEncoder(passwordEncoder);
        return manager;
    }

    // 密码编码器 Bean（BCrypt）
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    // 内存用户服务（示例用户，适用于开发/测试）
    @Bean
    public MapReactiveUserDetailsService userDetailsService() {
        PasswordEncoder encoder = passwordEncoder();
        
        // 管理员用户（用户名：admin，密码：admin，角色：ADMIN）
        UserDetails admin = User.withUsername("admin")
            .password(encoder.encode("admin"))
            .roles("ADMIN")
            .build();
        
        // 测试用户1（用户名：user1）
        UserDetails user1 = User.withUsername("user1")
            .password(encoder.encode("user1"))
            .roles("USER")
            .build();
        
        // 测试用户2（用户名：user2）
        UserDetails user2 = User.withUsername("user2")
            .password(encoder.encode("user2"))
            .roles("USER")
            .build();
        
        // 返回包含示例用户的 MapReactiveUserDetailsService
        return new MapReactiveUserDetailsService(admin, user1, user2);
    }


} 