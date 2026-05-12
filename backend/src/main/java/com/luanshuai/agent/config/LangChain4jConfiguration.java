package com.luanshuai.agent.config;

import org.springframework.context.annotation.Configuration;

/**
 * LangChain4j 配置类
 * 配置虚拟线程 Executor 用于桥接 WebFlux 的同步调用
 */
@Configuration
public class LangChain4jConfiguration {

    /**
     * 获取配置说明
     * LangChain4j 的 ChatLanguageModel 和 EmbeddingModel 适配器通过 @Component 自动注册
     * 虚拟线程 Executor 在各适配器类中创建
     */
    public String getInfo() {
        return "LangChain4j 1.0.0 配置 - 使用虚拟线程桥接 WebFlux";
    }
}