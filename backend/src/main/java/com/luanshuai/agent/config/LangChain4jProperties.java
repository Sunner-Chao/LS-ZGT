package com.luanshuai.agent.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * LangChain4j 配置属性
 * 用于绑定 application.yml 中的 langchain4j.* 配置
 */
@Data
@Component
@ConfigurationProperties(prefix = "langchain4j")
public class LangChain4jProperties {

    private VirtualThread virtualThread = new VirtualThread();
    private ChatModel chatModel = new ChatModel();
    private Tool tool = new Tool();
    private Agent agent = new Agent();
    private Memory memory = new Memory();

    @Data
    public static class VirtualThread {
        /**
         * 是否启用虚拟线程
         */
        private boolean enabled = true;
        /**
         * 核心线程池大小
         */
        private int corePoolSize = 10;
        /**
         * 最大线程池大小
         */
        private int maxPoolSize = 100;
        /**
         * 队列容量
         */
        private int queueCapacity = 1000;
    }

    @Data
    public static class ChatModel {
        /**
         * ChatModel 默认超时（秒）
         */
        private int timeout = 120;
    }

    /**
     * 获取 ChatModel 超时（兼容旧代码）
     */
    public int getChatModelTimeout() {
        return (chatModel != null && chatModel.timeout > 0) ? chatModel.timeout : 120;
    }

    @Data
    public static class Tool {
        /**
         * Tool 执行最大重试次数
         */
        private int maxAttempts = 3;
        /**
         * 重试延迟（毫秒）
         */
        private long retryDelay = 1000;
    }

    @Data
    public static class Agent {
        /**
         * Agent 最大迭代次数
         */
        private int maxIterations = 10;
        /**
         * 是否输出详细日志
         */
        private boolean verbose = true;
    }

    @Data
    public static class Memory {
        /**
         * 最大历史消息数
         */
        private int maxHistorySize = 100;
        /**
         * 会话 TTL（秒）
         */
        private long sessionTtl = 3600;
    }
}