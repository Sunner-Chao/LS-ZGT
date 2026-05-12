package com.luanshuai.agent.service.search;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 查询改写服务 (Query Rewrite)
 *
 * 在检索前对用户查询进行增强：
 * 1. HyDE (Hypothetical Document Embeddings): 生成假设性答案
 * 2. 查询扩展: 增加相关概念词
 * 3. 查询分解: 拆分为多个子问题
 * 4. Step-back: 抽象化查询
 */
@Service
public class QueryRewriteService {

    private static final Logger log = LoggerFactory.getLogger(QueryRewriteService.class);

    @Autowired
    private WebClient.Builder webClientBuilder;

    @Autowired
    private ObjectMapper objectMapper;

    @Value("${app.llm.chat-model:${LLM_CHAT_MODEL:Qwen3.5-9B-Q4_K_M}}")
    private String chatModel;

    @Value("${app.llm.local-url:${LLM_LOCAL_URL:http://llama-cpp:8080}}")
    private String chatUrl;

    @Value("${app.llm.timeout:120000}")
    private long timeout;

    private static final String HYPE_PROMPT = """
你是一个文档写作助手。请根据用户问题，生成一段假设性的文档内容，
假设这段文档能完美回答用户的问题。

要求：
- 内容长度适中（100-200字）
- 内容要具体、详细，像是从真实文档中摘录的
- 直接给出答案内容，不要添加解释

用户问题：%s

假设性文档内容：
""";

    private static final String EXPAND_PROMPT = """
请将以下用户问题扩展为多个相关的搜索查询，用于检索相关文档。

要求：
- 生成3-5个不同的查询
- 每个查询要简洁（不超过20个字）
- 覆盖问题的不同方面
- 用换行符分隔各查询

用户问题：%s

扩展后的查询：
""";

    private static final String DECOMPOSE_PROMPT = """
请将以下复杂问题分解为多个简单的子问题，每个子问题可以独立检索和回答。

要求：
- 生成3-5个子问题
- 每个子问题要明确、具体
- 子问题之间保持逻辑连贯
- 用换行符分隔各子问题

复杂问题：%s

分解后的子问题：
""";

    private static final String STEPBACK_PROMPT = """
请将以下问题抽象为一个更高层次的概念或原则，用于检索更广泛的背景知识。

要求：
- 用一句话概括问题的本质
- 保留核心概念，去除具体细节
- 结果要简洁（不超过50字）

具体问题：%s

抽象问题：
""";

    /**
     * HyDE: 生成假设性文档
     */
    public String generateHypotheticalDocument(String query) {
        return generateWithPrompt(HYPE_PROMPT, query, "hyde");
    }

    /**
     * 查询扩展
     */
    public List<String> expandQuery(String query) {
        String result = generateWithPrompt(EXPAND_PROMPT, query, "expand");
        return parseMultiline(result);
    }

    /**
     * 查询分解
     */
    public List<String> decomposeQuery(String query) {
        String result = generateWithPrompt(DECOMPOSE_PROMPT, query, "decompose");
        return parseMultiline(result);
    }

    /**
     * Step-back 抽象化
     */
    public String stepBack(String query) {
        return generateWithPrompt(STEPBACK_PROMPT, query, "stepback");
    }

    /**
     * 组合策略：HyDE + 查询扩展
     */
    public QueryRewriteResult rewriteWithHyde(String query) {
        String hydeDoc = generateHypotheticalDocument(query);
        List<String> expandedQueries = expandQuery(query);
        log.debug("[QueryRewrite] HyDE: {} chars, expanded to {} queries", hydeDoc.length(), expandedQueries.size());
        return new QueryRewriteResult(query, hydeDoc, expandedQueries);
    }

    private String generateWithPrompt(String template, String input, String strategy) {
        try {
            String prompt = String.format(template, input);

            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("model", chatModel);
            requestBody.put("temperature", strategy.equals("hyde") ? 0.8 : 0.3);
            requestBody.put("max_tokens", strategy.equals("hyde") ? 300 : 100);

            List<Map<String, Object>> messages = new ArrayList<>();
            messages.add(Map.of("role", "user", "content", prompt));
            requestBody.put("messages", messages);

            String fullUrl = chatUrl + "/v1/chat/completions";
            String response = webClientBuilder.build()
                    .post()
                    .uri(fullUrl)
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .bodyValue(requestBody)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block(java.time.Duration.ofMillis(timeout));

            // 解析 OpenAI 格式响应
            Map<String, Object> resp = objectMapper.readValue(response, Map.class);
            Object choices = resp.get("choices");
            if (choices instanceof List && !((List<?>) choices).isEmpty()) {
                Object firstChoice = ((List<?>) choices).get(0);
                if (firstChoice instanceof Map) {
                    Map<?, ?> choice = (Map<?, ?>) firstChoice;
                    Object message = choice.get("message");
                    if (message instanceof Map) {
                        Object content = ((Map<?, ?>) message).get("content");
                        if (content != null) {
                            return content.toString().trim();
                        }
                    }
                    // 流式格式
                    Object delta = choice.get("delta");
                    if (delta instanceof Map) {
                        Object content = ((Map<?, ?>) delta).get("content");
                        if (content != null) {
                            return content.toString().trim();
                        }
                    }
                }
            }

            log.warn("[QueryRewrite] Unexpected response format for {}: {}", strategy, response.length() > 100 ? response.substring(0, 100) + "..." : response);
            return "";

        } catch (Exception e) {
            log.error("[QueryRewrite] {} failed: {}", strategy, e.getMessage());
            return "";
        }
    }

    private List<String> parseMultiline(String text) {
        if (text == null || text.isEmpty()) {
            return Collections.emptyList();
        }
        return Arrays.stream(text.split("\n"))
                .map(line -> line.trim())
                .filter(line -> !line.isEmpty())
                .filter(line -> !line.startsWith("-") && !line.startsWith("*"))
                .collect(Collectors.toList());
    }

    public static class QueryRewriteResult {
        private final String originalQuery;
        private final String hypotheticalDoc;
        private final List<String> expandedQueries;

        public QueryRewriteResult(String originalQuery, String hypotheticalDoc, List<String> expandedQueries) {
            this.originalQuery = originalQuery;
            this.hypotheticalDoc = hypotheticalDoc;
            this.expandedQueries = expandedQueries;
        }

        public String getOriginalQuery() { return originalQuery; }
        public String getHypotheticalDoc() { return hypotheticalDoc; }
        public List<String> getExpandedQueries() { return expandedQueries; }

        public List<String> getSearchQueries() {
            List<String> queries = new ArrayList<>();
            queries.add(originalQuery);
            if (hypotheticalDoc != null && !hypotheticalDoc.isEmpty()) {
                queries.add(hypotheticalDoc);
            }
            queries.addAll(expandedQueries);
            return queries;
        }
    }
}
