package com.luanshuai.agent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * QueryIntentClassifier - 查询意图智能分类器
 *
 * 职责：对经过规则过滤后的"灰度"查询进行 LLM 辅助分类，
 *       判断是否为"知识库自指/对话元/闲聊/超出领域"等需要特殊处理的类型，
 *       从而决定是否跳过向量检索直接返回分级回复。
 *
 * 设计原则：
 * - 快速：使用 num_predict=64 限制输出，temperature=0（确定性）
 * - 轻量：prompt 极简，只返回1个英文关键词
 * - 缓存：同类查询3分钟内不重复调用 LLM
 * - 兜底：LLM 异常时默认走正常检索流程，不阻塞用户请求
 */
@Service
public class QueryIntentClassifier {

    private static final Logger log = LoggerFactory.getLogger(QueryIntentClassifier.class);
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    private LLMService llmService;

    /** 查询文本 → 分类结果的本地缓存（TTL 3分钟） */
    private final ConcurrentHashMap<String, CachedIntent> cache = new ConcurrentHashMap<>();

    /** 缓存 TTL（毫秒） */
    private static final long CACHE_TTL_MS = 3 * 60 * 1000L;

    /**
     * 智能分类入口
     * @param query 用户查询（已通过规则过滤）
     * @param tenantId 租户ID（用于缓存key）
     * @return 分类结果（永不返回 null，超时/异常时返回 NORMAL）
     */
    public ClassifyResult classify(String query, String tenantId) {
        if (query == null || query.trim().isEmpty()) {
            return ClassifyResult.NORMAL;
        }

        String tenant = (tenantId == null || tenantId.trim().isEmpty()) ? "default" : tenantId.trim();
        String cacheKey = tenant + "::" + query.trim();

        // 1. 命中缓存
        CachedIntent cached = cache.get(cacheKey);
        if (cached != null && System.currentTimeMillis() - cached.timestamp < CACHE_TTL_MS) {
            log.debug("[IntentClassifier] 缓存命中: '{}' -> {}", query, cached.result);
            return cached.result;
        }

        // 2. 调用 LLM 分类（同步等待，最多3秒超时）
        ClassifyResult result;
        try {
            result = callLlmClassifier(query);
        } catch (Exception e) {
            log.warn("[IntentClassifier] LLM 分类失败，降级为 NORMAL: query='{}' err={}", query, e.getMessage());
            result = ClassifyResult.NORMAL;
        }

        // 3. 写入缓存
        cache.put(cacheKey, new CachedIntent(result, System.currentTimeMillis()));

        // 4. 定期清理过期缓存（约1%概率触发）
        if (Math.random() < 0.01) {
            cleanupExpired();
        }

        return result;
    }

    /**
     * 调用 LLM 进行意图分类
     * 使用 CompletableFuture + boundedElastic 线程池，确保整个阻塞操作（含 block()）
     * 在允许阻塞的线程上执行，而非在 parallel-1 等非阻塞线程上。
     */
    private ClassifyResult callLlmClassifier(String query) throws Exception {
        String classifyPrompt = buildPrompt(query);

        Map<String, Object> payload = new ConcurrentHashMap<>();
        payload.put("model", llmService.getChatModel());
        payload.put("messages", List.of(Map.of("role", "user", "content", classifyPrompt)));
        payload.put("stream", false);
        Map<String, Object> options = new ConcurrentHashMap<>();
        options.put("num_predict", 64);   // 限制输出，快速返回
        options.put("temperature", 0);    // 确定性输出
        options.put("num_ctx", 1024);
        payload.put("options", options);

        // 将整个阻塞操作（含 block()）提交到 boundedElastic 线程池
        // parallel-1 等线程不允许 block()，所以必须把 block() 也移到允许阻塞的线程上
        CompletableFuture<ClassifyResult> future = new CompletableFuture<>();
        reactor.core.scheduler.Schedulers.boundedElastic().schedule(() -> {
            try {
                String response = llmService.generateCompletion(payload).block();
                future.complete(parseResponse(response, query));
            } catch (Exception e) {
                future.completeExceptionally(e);
            }
        });

        // 在调用线程等待结果（最多 3 秒），这是轻量级 Future.get，不是 Mono.block()
        try {
            return future.get(3, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            log.warn("[IntentClassifier] LLM 分类超时（3秒），降级为 NORMAL: query='{}'", query);
            return ClassifyResult.NORMAL;
        }
    }

    /**
     * 构建分类 prompt
     * 只要求 LLM 返回一个英文关键词：KB_SELF / CONV_HISTORY / CHITCHAT / OUT_OF_DOMAIN / NORMAL
     */
    private String buildPrompt(String query) {
        return "你是一个查询意图分类器。请根据用户问题，判断它属于哪一类。只输出一个英文关键词，不要解释。\n\n" +
               "分类标准：\n" +
               "KB_SELF       - 用户询问本知识库本身的内容、结构、能力（如：你有哪些知识/知识库有什么/你的能力）\n" +
               "CONV_HISTORY  - 用户询问本次对话历史、已交流次数、之前聊过什么\n" +
               "CHITCHAT      - 闲聊、寒暄、问候，不涉及建筑专业领域（如：你好/今天天气怎么样）\n" +
               "OUT_OF_DOMAIN - 明显超出建筑行业领域的问题（如：今天北京天气/今天星期几/帮我写诗）\n" +
               "NORMAL        - 正常的建筑行业专业问题，可以检索知识库回答；" +
               "也包括用户要求总结、概括、解释知识库内容等操作性请求（如：帮我总结主要内容/有哪些关键要点/详细解释这个概念/查询知识库中的文档）\n\n" +
               "重要：'帮我总结'、'有哪些关键要点'、'详细解释'、'查询知识库中的文档' 等是用户想使用知识库的正常请求，必须分类为 NORMAL，不要误判为 CHITCHAT 或 OUT_OF_DOMAIN！\n\n" +
               "示例：\n" +
               "Q: 有哪些知识库？          A: KB_SELF\n" +
               "Q: 刚才我问过什么？        A: CONV_HISTORY\n" +
               "Q: 你好啊                 A: CHITCHAT\n" +
               "Q: 今天天气怎么样？        A: OUT_OF_DOMAIN\n" +
               "Q: 抗震规范是什么          A: NORMAL\n" +
               "Q: 帮我总结主要内容        A: NORMAL\n" +
               "Q: 有哪些关键要点？        A: NORMAL\n" +
               "Q: 详细解释这个概念        A: NORMAL\n" +
               "Q: 查询知识库中的文档      A: NORMAL\n" +
               "Q: 帮我概括一下            A: NORMAL\n" +
               "Q: 总结一下规范要点        A: NORMAL\n\n" +
               "Q: " + query + "\nA:";
    }

    /**
     * 解析 LLM 返回结果
     */
    private ClassifyResult parseResponse(String response, String query) {
        if (response == null || response.isBlank()) {
            return ClassifyResult.NORMAL;
        }

        String text = response.trim().toUpperCase().split("\\s+")[0]; // 取第一个词

        // 兼容格式：可能是纯文本，也可能是 JSON {"response": "KB_SELF"}
        try {
            if (text.startsWith("{")) {
                Map<?, ?> m = objectMapper.readValue(response, Map.class);
                Object resp = m.get("response");
                if (resp != null) text = resp.toString().trim().toUpperCase();
            }
        } catch (Exception ignored) {}

        for (ClassifyResult r : ClassifyResult.values()) {
            if (text.contains(r.keyword)) {
                log.info("[IntentClassifier] LLM 分类结果: '{}' -> {} (keyword={})", query, r.name(), r.keyword);
                return r;
            }
        }

        log.warn("[IntentClassifier] LLM 返回未知分类 '{}'，降级为 NORMAL（query='{}'）", text, query);
        return ClassifyResult.NORMAL;
    }

    private void cleanupExpired() {
        long now = System.currentTimeMillis();
        cache.entrySet().removeIf(e -> now - e.getValue().timestamp > CACHE_TTL_MS);
    }

    // ===== 分类结果枚举 =====
    public enum ClassifyResult {
        KB_SELF("KB_SELF"),       // 知识库自指查询
        CONV_HISTORY("CONV_HISTORY"), // 对话元查询
        CHITCHAT("CHITCHAT"),     // 闲聊/问候
        OUT_OF_DOMAIN("OUT_OF_DOMAIN"), // 超出领域
        NORMAL("NORMAL");         // 正常查询

        public final String keyword;

        ClassifyResult(String keyword) {
            this.keyword = keyword;
        }
    }

    // ===== 缓存条目 =====
    private static class CachedIntent {
        final ClassifyResult result;
        final long timestamp;

        CachedIntent(ClassifyResult result, long timestamp) {
            this.result = result;
            this.timestamp = timestamp;
        }
    }
}
