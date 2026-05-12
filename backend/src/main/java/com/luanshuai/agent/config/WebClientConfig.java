// 包声明：应用配置包
package com.luanshuai.agent.config;

// Spring 注解与 WebClient 类型
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

// 声明配置类用于创建 WebClient.Builder Bean
@Configuration
public class WebClientConfig {
    
    // 提供可注入的 WebClient.Builder，供各处按需构建 WebClient
    @Bean
    public WebClient.Builder webClientBuilder() {
        return WebClient.builder(); // 返回默认构造的 builder
    }
}


