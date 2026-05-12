package com.luanshuai.agent.service.billing;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * 使用量追踪服务 (Usage Tracking)
 *
 * 追踪和统计：
 * 1. Token 使用量（输入 + 输出）
 * 2. API 调用次数
 * 3. 检索文档数量
 * 4. 按用户/租户统计
 *
 * 支持内存存储（开发环境），Redis 可扩展
 */
@Service
public class UsageTrackingService {

    private static final Logger log = LoggerFactory.getLogger(UsageTrackingService.class);

    @Autowired
    private ObjectMapper objectMapper;

    @Value("${app.tenant.default-tenant-id:default}")
    private String defaultTenantId;

    /** 内存存储 */
    private final Map<String, TenantUsage> tenantUsageMap = new ConcurrentHashMap<>();
    private final Map<String, UserUsage> userUsageMap = new ConcurrentHashMap<>();

    /**
     * 记录 LLM Token 使用
     */
    public void recordTokenUsage(String tenantId, String userId, long inputTokens, long outputTokens) {
        if (tenantId == null) tenantId = defaultTenantId;

        try {
            TenantUsage tenantUsage = tenantUsageMap.computeIfAbsent(tenantId, TenantUsage::new);
            tenantUsage.incrementInputTokens(inputTokens);
            tenantUsage.incrementOutputTokens(outputTokens);
            tenantUsage.incrementApiCalls();

            if (userId != null) {
                UserUsage userUsage = userUsageMap.computeIfAbsent(userId, UserUsage::new);
                userUsage.incrementInputTokens(inputTokens);
                userUsage.incrementOutputTokens(outputTokens);
                userUsage.incrementApiCalls();
            }

            log.debug("[Usage] Recorded {} input + {} output tokens for tenant {}", inputTokens, outputTokens, tenantId);
        } catch (Exception e) {
            log.error("[Usage] Failed to record token usage: {}", e.getMessage());
        }
    }

    /**
     * 记录检索操作
     */
    public void recordRetrieval(String tenantId, int retrievedDocs) {
        if (tenantId == null) tenantId = defaultTenantId;

        try {
            TenantUsage tenantUsage = tenantUsageMap.computeIfAbsent(tenantId, TenantUsage::new);
            tenantUsage.incrementRetrievals(retrievedDocs);
            log.debug("[Usage] Recorded {} retrievals for tenant {}", retrievedDocs, tenantId);
        } catch (Exception e) {
            log.error("[Usage] Failed to record retrieval: {}", e.getMessage());
        }
    }

    /**
     * 获取租户使用统计
     */
    public Map<String, Object> getTenantUsage(String tenantId) {
        if (tenantId == null) tenantId = defaultTenantId;
        return getTenantUsageFromMemory(tenantId);
    }

    /**
     * 获取租户使用统计（带时间范围）
     */
    public Map<String, Object> getTenantUsage(String tenantId, Long startTime, Long endTime) {
        return getTenantUsage(tenantId);
    }

    /**
     * 获取用户使用统计
     */
    public Map<String, Object> getUserUsage(String userId) {
        if (userId == null) {
            return Collections.emptyMap();
        }
        return getUserUsageFromMemory(userId);
    }

    /**
     * 获取所有租户的使用统计
     */
    public List<Map<String, Object>> getAllTenantUsage() {
        return tenantUsageMap.entrySet().stream()
                .map(e -> {
                    Map<String, Object> data = e.getValue().toMap();
                    data.put("tenantId", e.getKey());
                    return data;
                })
                .collect(Collectors.toList());
    }

    /**
     * 检查租户配额
     */
    public QuotaCheckResult checkQuota(String tenantId, long requiredInputTokens) {
        if (tenantId == null) tenantId = defaultTenantId;

        Map<String, Object> usage = getTenantUsage(tenantId);
        long currentInput = ((Number) usage.getOrDefault("input_tokens", 0L)).longValue();
        long monthlyLimit = 1_000_000L;

        double usagePercentage = (double) currentInput / monthlyLimit * 100;

        if (usagePercentage >= 100) {
            return new QuotaCheckResult(false, "配额已用完", currentInput, monthlyLimit);
        } else if (usagePercentage >= 80) {
            return new QuotaCheckResult(true, "配额即将用完 (80%)", currentInput, monthlyLimit);
        } else {
            return new QuotaCheckResult(true, "配额充足", currentInput, monthlyLimit);
        }
    }

    /**
     * 重置租户配额
     */
    public void resetTenantQuota(String tenantId) {
        if (tenantId == null) tenantId = defaultTenantId;
        TenantUsage usage = tenantUsageMap.get(tenantId);
        if (usage != null) {
            usage.reset();
            log.info("[Usage] Reset quota for tenant {}", tenantId);
        }
    }

    // ========== 内存实现 ==========

    private Map<String, Object> getTenantUsageFromMemory(String tenantId) {
        TenantUsage usage = tenantUsageMap.get(tenantId);
        if (usage == null) {
            return Map.of(
                    "tenantId", tenantId,
                    "input_tokens", 0L,
                    "output_tokens", 0L,
                    "api_calls", 0L,
                    "retrievals", 0L,
                    "total_tokens", 0L,
                    "storage", "memory"
            );
        }

        Map<String, Object> result = usage.toMap();
        result.put("tenantId", tenantId);
        result.put("storage", "memory");
        return result;
    }

    private Map<String, Object> getUserUsageFromMemory(String userId) {
        UserUsage usage = userUsageMap.get(userId);
        if (usage == null) {
            return Map.of(
                    "userId", userId,
                    "input_tokens", 0L,
                    "output_tokens", 0L,
                    "api_calls", 0L,
                    "total_tokens", 0L,
                    "storage", "memory"
            );
        }

        Map<String, Object> result = usage.toMap();
        result.put("userId", userId);
        result.put("storage", "memory");
        return result;
    }

    // ========== 数据类 ==========

    public static class TenantUsage {
        private final String tenantId;
        private final AtomicLong inputTokens = new AtomicLong(0);
        private final AtomicLong outputTokens = new AtomicLong(0);
        private final AtomicLong apiCalls = new AtomicLong(0);
        private final AtomicLong retrievals = new AtomicLong(0);

        public TenantUsage(String tenantId) { this.tenantId = tenantId; }

        public void incrementInputTokens(long delta) { inputTokens.addAndGet(delta); }
        public void incrementOutputTokens(long delta) { outputTokens.addAndGet(delta); }
        public void incrementApiCalls() { apiCalls.incrementAndGet(); }
        public void incrementRetrievals(int delta) { retrievals.addAndGet(delta); }
        public void reset() {
            inputTokens.set(0);
            outputTokens.set(0);
            apiCalls.set(0);
            retrievals.set(0);
        }

        public long getInputTokens() { return inputTokens.get(); }
        public long getOutputTokens() { return outputTokens.get(); }
        public long getApiCalls() { return apiCalls.get(); }
        public long getRetrievals() { return retrievals.get(); }

        public Map<String, Object> toMap() {
            Map<String, Object> map = new HashMap<>();
            map.put("input_tokens", inputTokens.get());
            map.put("output_tokens", outputTokens.get());
            map.put("api_calls", apiCalls.get());
            map.put("retrievals", retrievals.get());
            map.put("total_tokens", inputTokens.get() + outputTokens.get());
            return map;
        }
    }

    public static class UserUsage {
        private final String userId;
        private final AtomicLong inputTokens = new AtomicLong(0);
        private final AtomicLong outputTokens = new AtomicLong(0);
        private final AtomicLong apiCalls = new AtomicLong(0);

        public UserUsage(String userId) { this.userId = userId; }

        public void incrementInputTokens(long delta) { inputTokens.addAndGet(delta); }
        public void incrementOutputTokens(long delta) { outputTokens.addAndGet(delta); }
        public void incrementApiCalls() { apiCalls.incrementAndGet(); }

        public long getInputTokens() { return inputTokens.get(); }
        public long getOutputTokens() { return outputTokens.get(); }
        public long getApiCalls() { return apiCalls.get(); }

        public Map<String, Object> toMap() {
            Map<String, Object> map = new HashMap<>();
            map.put("input_tokens", inputTokens.get());
            map.put("output_tokens", outputTokens.get());
            map.put("api_calls", apiCalls.get());
            map.put("total_tokens", inputTokens.get() + outputTokens.get());
            return map;
        }
    }

    public static class QuotaCheckResult {
        private final boolean allowed;
        private final String message;
        private final long currentUsage;
        private final long limit;

        public QuotaCheckResult(boolean allowed, String message, long currentUsage, long limit) {
            this.allowed = allowed;
            this.message = message;
            this.currentUsage = currentUsage;
            this.limit = limit;
        }

        public boolean isAllowed() { return allowed; }
        public String getMessage() { return message; }
        public long getCurrentUsage() { return currentUsage; }
        public long getLimit() { return limit; }
        public double getUsagePercentage() { return (double) currentUsage / limit * 100; }
    }
}
