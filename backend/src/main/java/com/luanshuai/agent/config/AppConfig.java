package com.luanshuai.agent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.Data;

// 应用配置类：绑定 application.yml/properties 中以 "app" 为前缀的配置
// 使用 @ConfigurationProperties 将配置注入为强类型对象，@Configuration 将其作为 Spring Bean 注册
@Configuration
@ConfigurationProperties(prefix = "app")
@Data // Lombok 注解：自动生成 getter/setter/toString 等方法，简化代码
public class AppConfig {
    
    // 为应用提供一个 ObjectMapper 实例并标为主 Bean，供其他组件注入使用
    @Bean
    @Primary
    public ObjectMapper objectMapper() {
        return new ObjectMapper();
    }
    
    // 子配置对象的初始化（作为嵌套配置分组）
    private KnowledgeBase knowledgeBase = new KnowledgeBase(); // 知识库相关配置
    private Jwt jwt = new Jwt(); // JWT 鉴权相关配置
    private Milvus milvus = new Milvus(); // Milvus 向量数据库配置
    private CadParser cadParser = new CadParser(); // CAD 解析服务配置
    private BimParser bimParser = new BimParser(); // BIM 解析服务配置
    private Performance performance = new Performance(); // 性能/缓存相关设置
    private Tenant tenant = new Tenant(); // 多租户相关配置
    
    // --------------------------- KnowledgeBase ---------------------------
    public static class KnowledgeBase {
        // 知识库的根路径（本地或挂载目录）
        private String path;
        
        public String getPath() { return path; }
        public void setPath(String path) { this.path = path; }
    }
    
    // --------------------------- JWT ---------------------------
    public static class Jwt {
        // 用于签名的密钥（务必在生产中使用安全的、受保护的密钥）
        private String secret;
        // JWT 有效期（秒或毫秒，取决于实现），需与应用其他部分约定
        private long expiration;
        
        public String getSecret() { return secret; }
        public void setSecret(String secret) { this.secret = secret; }
        
        public long getExpiration() { return expiration; }
        public void setExpiration(long expiration) { this.expiration = expiration; }
    }
    
    // --------------------------- Milvus ---------------------------
    public static class Milvus {
        // Milvus 服务地址与端口
        private String host;
        private int port;
        // 如果 Milvus 需要鉴权，可在此配置
        private String username;
        private String password;
        // 数据库/命名空间名称
        private String database;
        // 连接与搜索的超时设置（毫秒）
        private int connectionTimeout = 5000;
        private int searchTimeout = 10000;
        // 在发生临时错误时的重试策略
        private int maxRetry = 2; // 最大重试次数
        private int retryInterval = 500; // 重试间隔（毫秒）
        
        public String getHost() { return host; }
        public void setHost(String host) { this.host = host; }
        public int getPort() { return port; }
        public void setPort(int port) { this.port = port; }
        public String getUsername() { return username; }
        public void setUsername(String username) { this.username = username; }
        public String getPassword() { return password; }
        public void setPassword(String password) { this.password = password; }
        public String getDatabase() { return database; }
        public void setDatabase(String database) { this.database = database; }
        public int getConnectionTimeout() { return connectionTimeout; }
        public void setConnectionTimeout(int connectionTimeout) { this.connectionTimeout = connectionTimeout; }
        public int getSearchTimeout() { return searchTimeout; }
        public void setSearchTimeout(int searchTimeout) { this.searchTimeout = searchTimeout; }
        public int getMaxRetry() { return maxRetry; }
        public void setMaxRetry(int maxRetry) { this.maxRetry = maxRetry; }
        public int getRetryInterval() { return retryInterval; }
        public void setRetryInterval(int retryInterval) { this.retryInterval = retryInterval; }
    }
    
    // --------------------------- CAD Parser ---------------------------
    public static class CadParser {
        // CAD 解析服务地址（例如内部服务的 base URL）
        private String url;
        
        public String getUrl() { return url; }
        public void setUrl(String url) { this.url = url; }
    }
    
    // --------------------------- BIM Parser ---------------------------
    public static class BimParser {
        // BIM 解析服务地址
        private String url;
        
        public String getUrl() { return url; }
        public void setUrl(String url) { this.url = url; }
    }
    
    // --------------------------- Performance ---------------------------
    public static class Performance {
        // 检索返回的最大结果数，限制上游负载与下游上下文大小
        private int maxSearchResults = 20;
        // 拼接进 prompt 的最大上下文长度（字符或 token，根据实现）
        private int maxContextLength = 4000;
        // 是否启用缓存以加速重复请求
        private boolean enableCaching = true;
        // 缓存过期时间，单位为秒
        private int cacheTtl = 300;
        // Milvus 向量搜索相似度阈值（L2 距离，越小越严格；归一化向量最大约 27.7）
        private float similarityThreshold = 5.0f;

        public int getMaxSearchResults() { return maxSearchResults; }
        public void setMaxSearchResults(int maxSearchResults) { this.maxSearchResults = maxSearchResults; }
        public int getMaxContextLength() { return maxContextLength; }
        public void setMaxContextLength(int maxContextLength) { this.maxContextLength = maxContextLength; }
        public boolean isEnableCaching() { return enableCaching; }
        public void setEnableCaching(boolean enableCaching) { this.enableCaching = enableCaching; }
        public int getCacheTtl() { return cacheTtl; }
        public void setCacheTtl(int cacheTtl) { this.cacheTtl = cacheTtl; }
        public float getSimilarityThreshold() { return similarityThreshold; }
        public void setSimilarityThreshold(float similarityThreshold) { this.similarityThreshold = similarityThreshold; }
    }
    
    // --------------------------- Getters and setters for top-level groups ---------------------------
    public KnowledgeBase getKnowledgeBase() { return knowledgeBase; }
    public void setKnowledgeBase(KnowledgeBase knowledgeBase) { this.knowledgeBase = knowledgeBase; }

    public Jwt getJwt() { return jwt; }
    public void setJwt(Jwt jwt) { this.jwt = jwt; }

    public Milvus getMilvus() { return milvus; }
    public void setMilvus(Milvus milvus) { this.milvus = milvus; }
    
    public CadParser getCadParser() { return cadParser; }
    public void setCadParser(CadParser cadParser) { this.cadParser = cadParser; }
    
    public BimParser getBimParser() { return bimParser; }
    public void setBimParser(BimParser bimParser) { this.bimParser = bimParser; }
    
    public Performance getPerformance() { return performance; }
    public void setPerformance(Performance performance) { this.performance = performance; }
    
    public Tenant getTenant() { return tenant; }
    public void setTenant(Tenant tenant) { this.tenant = tenant; }
    
    // --------------------------- Tenant ---------------------------
    public static class Tenant {
        // 默认租户 ID（当请求未指定租户时使用）
        private String defaultTenantId = "default";
        // 存储模式：local（本地磁盘）、server（远程服务）、hybrid（混合）
        private String storageMode = "local"; // local, server, hybrid
        // 默认知识库路径（用于单租户或默认租户）
        private String defaultKbPath = "/app/default_kb";
        // 多租户时每租户的知识库存放路径
        private String tenantKbPath = "/data/knowledge_base/tenants";
        // 是否启用许可校验（在商业部署时启用）
        private boolean enableLicenseCheck = false;
        // 许可密钥（仅在启用许可校验时使用）
        private String licenseKey = "";
        
        public String getDefaultTenantId() { return defaultTenantId; }
        public void setDefaultTenantId(String defaultTenantId) { this.defaultTenantId = defaultTenantId; }
        
        public String getStorageMode() { return storageMode; }
        public void setStorageMode(String storageMode) { this.storageMode = storageMode; }
        
        public String getDefaultKbPath() { return defaultKbPath; }
        public void setDefaultKbPath(String defaultKbPath) { this.defaultKbPath = defaultKbPath; }
        
        public String getTenantKbPath() { return tenantKbPath; }
        public void setTenantKbPath(String tenantKbPath) { this.tenantKbPath = tenantKbPath; }
        
        public boolean isEnableLicenseCheck() { return enableLicenseCheck; }
        public void setEnableLicenseCheck(boolean enableLicenseCheck) { this.enableLicenseCheck = enableLicenseCheck; }
        
        public String getLicenseKey() { return licenseKey; }
        public void setLicenseKey(String licenseKey) { this.licenseKey = licenseKey; }
    }
}