package com.luanshuai.agent.service.evaluation;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.*;
import java.util.regex.Pattern;

/**
 * RAG 评估服务
 *
 * 实现三维度评估体系：
 * 1. 逻辑性 (Logic): 答案是否合理、推理是否正确
 * 2. 指令遵循 (Instruction Following): 是否按要求格式输出
 * 3. 防幻觉 (Hallucination Prevention): 是否出现无依据内容
 *
 * 使用 LLM-as-a-Judge 进行自动评估
 */
@Service
public class EvaluationService {

    private static final Logger log = LoggerFactory.getLogger(EvaluationService.class);

    @Autowired
    private WebClient.Builder webClientBuilder;

    @Autowired
    private ObjectMapper objectMapper;

    @Value("${app.llm.chat-model:Qwen2-7B-Instruct-Q5_K_M.gguf}")
    private String chatModel;

    @Value("${app.llm.chat-url:http://localhost:8080/v1/chat/completions}")
    private String chatUrl;

    @Value("${app.llm.timeout:120000}")
    private long timeout;

    // 评估 prompt 模板
    private static final String EVALUATION_PROMPT = """
            请评估以下问答的质量，从三个维度打分（每项1-10分）：

            问题：%s

            参考信息（知识库中的相关内容）：
            %s

            生成的答案：
            %s

            请给出：
            1. 逻辑性得分（1-10）：答案是否合理，推理是否正确
            2. 指令遵循得分（1-10）：是否按要求的格式和风格输出
            3. 防幻觉得分（1-10）：答案是否有充分依据，是否包含无来源内容

            请用以下 JSON 格式输出：
            {
            "logic_score": X,
            "instruction_score": X,
            "hallucination_score": X,
            "reasoning": "简要说明评分理由"
            }
            """;

    /** 幻觉检测关键词（高风险词汇） */
    private static final List<String> HALLUCINATION_KEYWORDS = Arrays.asList(
            "研究表明", "根据专家", "数据表明", "权威机构", "统计显示",
            "通常来说", "普遍认为", "大家知道", "众所周知", "一般来说"
    );

    /** 无依据声明模式 */
    private static final Pattern UNSUPPORTED_CLAIM_PATTERN = Pattern.compile(
            "(必须|一定|绝对|所有|从不|总是|每个|全部)\\s*[^，。！？]*",
            Pattern.CASE_INSENSITIVE
    );

    /**
     * 评估 RAG 答案质量
     *
     * @param query 用户问题
     * @param answer 生成的答案
     * @param referenceDocs 参考文档列表
     * @return 评估结果
     */
    public EvaluationResult evaluate(String query, String answer, List<Map<String, Object>> referenceDocs) {
        return evaluate(query, answer, referenceDocs, true);
    }

    /**
     * 评估（可选择是否使用 LLM）
     *
     * @param query 用户问题
     * @param answer 生成的答案
     * @param referenceDocs 参考文档
     * @param useLLM 是否使用 LLM 评估
     */
    public EvaluationResult evaluate(String query, String answer,
                                      List<Map<String, Object>> referenceDocs, boolean useLLM) {
        long start = System.currentTimeMillis();

        EvaluationResult result = new EvaluationResult(query, answer);

        // 1. 快速规则检测（始终执行）
        double quickLogicScore = quickLogicCheck(answer);
        double quickHallucinationScore = quickHallucinationCheck(answer);

        result.setQuickLogicScore(quickLogicScore);
        result.setQuickHallucinationScore(quickHallucinationScore);

        // 2. LLM 深度评估（可选）
        if (useLLM && !answer.trim().isEmpty() && referenceDocs != null && !referenceDocs.isEmpty()) {
            try {
                String context = buildContextFromDocs(referenceDocs);
                Map<String, Object> llmScores = llmEvaluate(query, context, answer);

                result.setLogicScore(((Number) llmScores.getOrDefault("logic_score", quickLogicScore * 10)).doubleValue());
                result.setInstructionScore(((Number) llmScores.getOrDefault("instruction_score", 7.0)).doubleValue());
                result.setHallucinationScore(((Number) llmScores.getOrDefault("hallucination_score", quickHallucinationScore * 10)).doubleValue());
                result.setReasoning(String.valueOf(llmScores.getOrDefault("reasoning", "")));

            } catch (Exception e) {
                log.warn("[Evaluation] LLM evaluation failed, using quick scores: {}", e.getMessage());
                // 回退到快速检测结果
                result.setLogicScore(quickLogicScore * 10);
                result.setInstructionScore(7.0); // 默认
                result.setHallucinationScore(quickHallucinationScore * 10);
                result.setReasoning("LLM评估失败，使用规则检测结果");
            }
        } else {
            // 仅使用快速检测
            result.setLogicScore(quickLogicScore * 10);
            result.setInstructionScore(7.0);
            result.setHallucinationScore(quickHallucinationScore * 10);
            result.setReasoning("快速规则检测");
        }

        result.setLatencyMs(System.currentTimeMillis() - start);

        log.info("[Evaluation] evaluated '{}' -> logic={:.1f}, instruction={:.1f}, hallucination={:.1f} in {}ms",
                query, result.getLogicScore(), result.getInstructionScore(),
                result.getHallucinationScore(), result.getLatencyMs());

        return result;
    }

    /**
     * 从参考文档构建上下文
     */
    private String buildContextFromDocs(List<Map<String, Object>> docs) {
        if (docs == null || docs.isEmpty()) {
            return "（无参考文档）";
        }

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(docs.size(), 5); i++) {
            Map<String, Object> doc = docs.get(i);
            String text = String.valueOf(doc.getOrDefault("text", ""));
            if (text.length() > 500) {
                text = text.substring(0, 500) + "...";
            }
            sb.append("[").append(i + 1).append("] ").append(text).append("\n\n");
        }
        return sb.toString();
    }

    /**
     * 快速逻辑检查
     */
    private double quickLogicCheck(String answer) {
        if (answer == null || answer.trim().isEmpty()) {
            return 0.0;
        }

        double score = 5.0; // 基础分

        // 检查答案长度
        int len = answer.length();
        if (len < 20) {
            score -= 2; // 答案太短
        } else if (len > 5000) {
            score -= 1; // 答案太长
        } else {
            score += 0.5; // 长度合理
        }

        // 检查是否包含"不知道"或"无法回答"等合理回应
        if (answer.contains("不知道") || answer.contains("无法确定") || answer.contains("无法回答")) {
            score += 1.0; // 诚实回答加分
        }

        // 检查是否有明确的结论性语句
        if (answer.contains("综上所述") || answer.contains("总之") || answer.contains("因此")) {
            score += 0.5; // 结构清晰
        }

        return Math.min(10, Math.max(1, score));
    }

    /**
     * 快速幻觉检测
     */
    private double quickHallucinationCheck(String answer) {
        if (answer == null || answer.trim().isEmpty()) {
            return 10.0; // 无内容可检测
        }

        double score = 10.0; // 基础分（高=低风险）

        // 检查高风险关键词
        int riskCount = 0;
        for (String keyword : HALLUCINATION_KEYWORDS) {
            if (answer.contains(keyword)) {
                riskCount++;
            }
        }
        score -= riskCount * 0.5;

        // 检查绝对性声明
        java.util.regex.Matcher matcher = UNSUPPORTED_CLAIM_PATTERN.matcher(answer);
        while (matcher.find()) {
            score -= 1.0;
        }

        // 检查是否提到具体数据但没有来源
        if (answer.matches(".*\\d{4}年.*") && !answer.contains("根据") && !answer.contains("来源")) {
            score -= 1.0;
        }

        return Math.min(10, Math.max(1, score));
    }

    /**
     * LLM 评估
     */
    private Map<String, Object> llmEvaluate(String query, String context, String answer) {
        try {
            String prompt = String.format(EVALUATION_PROMPT, query, context, answer);

            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("model", chatModel);
            requestBody.put("temperature", 0.1);
            requestBody.put("max_tokens", 300);

            List<Map<String, Object>> messages = new ArrayList<>();
            messages.add(Map.of("role", "user", "content", prompt));
            requestBody.put("messages", messages);

            String response = webClientBuilder.build()
                    .post()
                    .uri(chatUrl)
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .bodyValue(requestBody)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block(java.time.Duration.ofMillis(timeout));

            // 提取 JSON 内容
            String content = extractContent(response);
            return parseEvaluationResponse(content);

        } catch (Exception e) {
            log.error("[Evaluation] LLM evaluation failed: {}", e.getMessage());
            throw new RuntimeException(e);
        }
    }

    /**
     * 提取 LLM 响应内容
     */
    private String extractContent(String response) {
        try {
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
                            return content.toString();
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.error("[Evaluation] Failed to parse response: {}", e.getMessage());
        }
        return "";
    }

    /**
     * 解析评估响应
     */
    private Map<String, Object> parseEvaluationResponse(String content) {
        Map<String, Object> result = new HashMap<>();

        // 尝试 JSON 解析
        try {
            // 提取 JSON 部分
            int jsonStart = content.indexOf("{");
            int jsonEnd = content.lastIndexOf("}");
            if (jsonStart >= 0 && jsonEnd > jsonStart) {
                String jsonStr = content.substring(jsonStart, jsonEnd + 1);
                Map<String, Object> parsed = objectMapper.readValue(jsonStr, Map.class);
                result.putAll(parsed);
                return result;
            }
        } catch (Exception e) {
            // JSON 解析失败，使用正则提取
        }

        // 回退到正则提取
        java.util.regex.Pattern logicPattern = java.util.regex.Pattern.compile(
                "logic[\\s_]*score[：:]*\\s*(\\d+(?:\\.\\d+)?)", java.util.regex.Pattern.CASE_INSENSITIVE
        );
        java.util.regex.Pattern instrPattern = java.util.regex.Pattern.compile(
                "instruction[\\s_]*score[：:]*\\s*(\\d+(?:\\.\\d+)?)", java.util.regex.Pattern.CASE_INSENSITIVE
        );
        java.util.regex.Pattern hallPattern = java.util.regex.Pattern.compile(
                "hallucination[\\s_]*score[：:]*\\s*(\\d+(?:\\.\\d+)?)", java.util.regex.Pattern.CASE_INSENSITIVE
        );

        java.util.regex.Matcher m;

        m = logicPattern.matcher(content);
        if (m.find()) result.put("logic_score", Double.parseDouble(m.group(1)));

        m = instrPattern.matcher(content);
        if (m.find()) result.put("instruction_score", Double.parseDouble(m.group(1)));

        m = hallPattern.matcher(content);
        if (m.find()) result.put("hallucination_score", Double.parseDouble(m.group(1)));

        result.putIfAbsent("logic_score", 5.0);
        result.putIfAbsent("instruction_score", 7.0);
        result.putIfAbsent("hallucination_score", 8.0);
        result.put("reasoning", "正则提取评分");

        return result;
    }

    /**
     * 批量评估（用于评估测试集）
     */
    public List<EvaluationResult> evaluateBatch(List<Map<String, Object>> testCases) {
        List<EvaluationResult> results = new ArrayList<>();

        for (Map<String, Object> testCase : testCases) {
            String query = String.valueOf(testCase.get("query"));
            String answer = String.valueOf(testCase.get("answer"));

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> docs = (List<Map<String, Object>>) testCase.get("referenceDocs");

            EvaluationResult result = evaluate(query, answer, docs, true);
            results.add(result);
        }

        return results;
    }

    /**
     * 计算评估统计
     */
    public Map<String, Object> calculateStats(List<EvaluationResult> results) {
        if (results == null || results.isEmpty()) {
            return Collections.emptyMap();
        }

        double avgLogic = results.stream()
                .mapToDouble(EvaluationResult::getLogicScore)
                .average().orElse(0);

        double avgInstruction = results.stream()
                .mapToDouble(EvaluationResult::getInstructionScore)
                .average().orElse(0);

        double avgHallucination = results.stream()
                .mapToDouble(EvaluationResult::getHallucinationScore)
                .average().orElse(0);

        double overallScore = (avgLogic + avgInstruction + avgHallucination) / 3;

        Map<String, Object> stats = new HashMap<>();
        stats.put("totalCases", results.size());
        stats.put("avgLogicScore", Math.round(avgLogic * 10) / 10.0);
        stats.put("avgInstructionScore", Math.round(avgInstruction * 10) / 10.0);
        stats.put("avgHallucinationScore", Math.round(avgHallucination * 10) / 10.0);
        stats.put("overallScore", Math.round(overallScore * 10) / 10.0);

        return stats;
    }

    /**
     * 评估结果数据类
     */
    public static class EvaluationResult {
        private final String query;
        private final String answer;
        private double logicScore;
        private double instructionScore;
        private double hallucinationScore;
        private double quickLogicScore;
        private double quickHallucinationScore;
        private String reasoning;
        private long latencyMs;

        public EvaluationResult(String query, String answer) {
            this.query = query;
            this.answer = answer;
            this.logicScore = 0;
            this.instructionScore = 0;
            this.hallucinationScore = 0;
            this.quickLogicScore = 0;
            this.quickHallucinationScore = 0;
            this.reasoning = "";
            this.latencyMs = 0;
        }

        public String getQuery() { return query; }
        public String getAnswer() { return answer; }
        public double getLogicScore() { return logicScore; }
        public double getInstructionScore() { return instructionScore; }
        public double getHallucinationScore() { return hallucinationScore; }
        public double getQuickLogicScore() { return quickLogicScore; }
        public double getQuickHallucinationScore() { return quickHallucinationScore; }
        public String getReasoning() { return reasoning; }
        public long getLatencyMs() { return latencyMs; }

        public void setLogicScore(double logicScore) { this.logicScore = logicScore; }
        public void setInstructionScore(double instructionScore) { this.instructionScore = instructionScore; }
        public void setHallucinationScore(double hallucinationScore) { this.hallucinationScore = hallucinationScore; }
        public void setQuickLogicScore(double quickLogicScore) { this.quickLogicScore = quickLogicScore; }
        public void setQuickHallucinationScore(double quickHallucinationScore) { this.quickHallucinationScore = quickHallucinationScore; }
        public void setReasoning(String reasoning) { this.reasoning = reasoning; }
        public void setLatencyMs(long latencyMs) { this.latencyMs = latencyMs; }

        public double getOverallScore() {
            return (logicScore + instructionScore + hallucinationScore) / 3;
        }

        public Map<String, Object> toMap() {
            Map<String, Object> map = new HashMap<>();
            map.put("query", query);
            map.put("answer", answer);
            map.put("logicScore", logicScore);
            map.put("instructionScore", instructionScore);
            map.put("hallucinationScore", hallucinationScore);
            map.put("overallScore", getOverallScore());
            map.put("reasoning", reasoning);
            map.put("latencyMs", latencyMs);
            return map;
        }
    }
}