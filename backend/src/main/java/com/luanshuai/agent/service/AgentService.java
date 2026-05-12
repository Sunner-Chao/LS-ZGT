package com.luanshuai.agent.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.luanshuai.agent.model.FunctionCallRequest;
import com.luanshuai.agent.model.FunctionCallRequest.ChatMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * AgentService - Agent 核心编排引擎，支持 Function Calling
 *
 * 工作流：
 * 1. 接收用户消息和对话历史
 * 2. 组装 messages 列表，附加 tools 定义
 * 3. 调用 LLM，检查返回是否包含 tool_calls（函数调用请求）
 * 4. 若是：解析函数名和参数，执行对应函数
 * 5. 将函数结果作为 assistant tool message 追加回 messages
 * 6. 再次调用 LLM，直至模型返回普通文本回答
 * 7. 若未启用 function calling，走原有 RAG 流程直接生成回答
 *
 * 支持本地 llama.cpp (tools) 和云端 OpenAI (function_call) 两种协议。
 */
@Service
public class AgentService {

 private static final Logger log = LoggerFactory.getLogger(AgentService.class);
 private final ObjectMapper objectMapper = new ObjectMapper();
 private final WebClient webClient = WebClient.builder().build();

 private static final int MAX_TOOL_CALLS = 10; // 防止死循环

 @Autowired
 private LLMService llmService;

 @Autowired
 private RagService ragService;

 @Autowired
 private FunctionCallingService functionCallingService;

 @Value("${app.llm.local-url:${LLM_LOCAL_URL:http://llama-cpp:8080}}")
 private String localUrl;

 @Value("${app.llm.chat-model:${LLM_CHAT_MODEL:all}}")
 private String chatModel;

 @Value("${app.llm.temperature:0.7}")
 private double temperature;

 @Value("${app.llm.max-tokens:1024}")
 private int maxTokens;

 // ==================== Agent Chat（非流式）====================

 /**
 * Agent 模式对话（非流式）
 *
 * @param request 对话请求
 * @param enableFunctionCalling 是否启用函数调用
 * @return 包含 answer, sources, toolCalls 的响应 Map
 */
 public Mono<Map<String, Object>> chat(FunctionCallRequest request, boolean enableFunctionCalling) {
 String question = request.getQuestion();
 List<ChatMessage> history = request.getHistory();
 String knowledgeBaseId = request.getKnowledgeBaseId();

 log.info("[Agent] chat request: question='{}', kbId='{}', enableFunctionCalling={}",
 question, knowledgeBaseId, enableFunctionCalling);

 if (!enableFunctionCalling) {
 // 走普通 RAG 流程
 return ragChat(question, history, knowledgeBaseId);
 }

 // 构建初始 messages
 List<Map<String, Object>> messages = new ArrayList<>();

 // 先放 system prompt
 messages.add(buildSystemPrompt(knowledgeBaseId));

 // 追加历史消息
 if (history != null) {
 for (ChatMessage hm : history) {
 Map<String, Object> m = new LinkedHashMap<>();
 m.put("role", hm.getRole());
 m.put("content", hm.getContent());
 if (hm.getToolCallId() != null) m.put("tool_call_id", hm.getToolCallId());
 if (hm.getName() != null) m.put("name", hm.getName());
 messages.add(m);
 }
 }

 // 追加用户当前问题
 Map<String, Object> userMsg = new LinkedHashMap<>();
 userMsg.put("role", "user");
 userMsg.put("content", question);
 messages.add(userMsg);

 // 追加 tools（llama.cpp 使用 tools 字段）
 Map<String, Object> payload = new LinkedHashMap<>();
 payload.put("model", chatModel);
 payload.put("messages", messages);
 payload.put("temperature", temperature);
 payload.put("max_tokens", maxTokens);

 List<Map<String, Object>> toolsSpec = functionCallingService.getLlamaCppTools();
 payload.put("tools", toolsSpec);

 log.info("[Agent] payload tools count: {}", toolsSpec.size());
 log.debug("[Agent] payload tools: {}", payload.get("tools"));
 log.info("[Agent] payload message count: {}", messages.size());

 return executeAgentLoop(payload, 0)
 .map(result -> {
 log.info("[Agent] agent loop completed: hasToolCalls={}", result.containsKey("toolCalls"));
 return result;
 });
 }

 /**
 * 普通 RAG 流程（无 function calling）
 */
 private Mono<Map<String, Object>> ragChat(String question, List<ChatMessage> history, String knowledgeBaseId) {
 com.luanshuai.agent.model.ChatRequest ragRequest = new com.luanshuai.agent.model.ChatRequest();
 ragRequest.setQuestion(question);
 ragRequest.setKnowledgeBaseId(knowledgeBaseId);

 // 转换 FunctionCallRequest.ChatMessage -> ChatRequest.ChatMessage
 if (history != null && !history.isEmpty()) {
 List<com.luanshuai.agent.model.ChatRequest.ChatMessage> convertedHistory = new ArrayList<>();
 for (ChatMessage h : history) {
 com.luanshuai.agent.model.ChatRequest.ChatMessage converted = new com.luanshuai.agent.model.ChatRequest.ChatMessage();
 converted.setContent(h.getContent());
 converted.setUser("user".equals(h.getRole()));
 convertedHistory.add(converted);
 }
 ragRequest.setHistory(convertedHistory);
 }

 return ragService.chat(ragRequest);
 }

 // ==================== Agent Chat（流式 SSE）====================

 /**
 * Agent 模式流式对话
 *
 * 流程：先发 sources，启用 function calling 后在对话中动态注入 tool_calls，
 * 并将 tool 结果通过 SSE "tool" 事件推送给前端。
 */
 public Flux<String> chatStream(FunctionCallRequest request) {
 String question = request.getQuestion();
 List<ChatMessage> history = request.getHistory();
 String knowledgeBaseId = request.getKnowledgeBaseId();
 boolean enableFunctionCalling = request.isEnableFunctionCalling();

 log.info("[Agent] chatStream: question='{}', kbId='{}', enableFunctionCalling={}",
 question, knowledgeBaseId, enableFunctionCalling);

 // 立即发送初始 loading
 String initialLoadingSse;
 try {
 Map<String, Object> loadMsg = new HashMap<>();
 loadMsg.put("type", "loading");
 loadMsg.put("content", "Agent 准备中...");
 initialLoadingSse = "data: " + objectMapper.writeValueAsString(loadMsg) + "\n\n";
 } catch (Exception e) {
 initialLoadingSse = "data: {\"type\":\"loading\",\"content\":\"Agent 准备中...\"}\n\n";
 }

 // 先走 RAG 检索获取 sources
 Mono<Map<String, Object>> ragResultMono = ragService.chat(
 buildChatRequest(question, history, knowledgeBaseId)
 ).cache();

 // 获取 sources 用于先发送
 Map<String, Object> sourcesData = new HashMap<>();
 sourcesData.put("type", "sources");
 sourcesData.put("sources", new ArrayList<>());

 Mono<String> sourcesSseMono = ragResultMono
 .map(result -> {
 try {
 @SuppressWarnings("unchecked")
 List<Map<String, Object>> sources = (List<Map<String, Object>>) result.getOrDefault("sources", new ArrayList<>());
 sourcesData.put("sources", sources);
 String json = objectMapper.writeValueAsString(sourcesData);
 return "data: " + json + "\n\n";
 } catch (Exception e) {
 log.warn("[Agent] sources SSE error: {}", e.getMessage());
 return "data: {\"type\":\"sources\",\"sources\":[]}\n\n";
 }
 })
 .onErrorReturn("data: {\"type\":\"sources\",\"sources\":[]}\n\n");

 if (!enableFunctionCalling) {
 // 纯 RAG 流式
 return Flux.concat(
 Flux.just(initialLoadingSse),
 sourcesSseMono,
 ragService.chatStream(buildChatRequest(question, history, knowledgeBaseId))
 );
 }

 // Agent with Function Calling 流式
 // 构建 messages（system + history + user）
 List<Map<String, Object>> messages = new ArrayList<>();
 messages.add(buildSystemPrompt(knowledgeBaseId));
 if (history != null) {
 for (ChatMessage hm : history) {
 Map<String, Object> m = new LinkedHashMap<>();
 m.put("role", hm.getRole());
 m.put("content", hm.getContent());
 if (hm.getToolCallId() != null) m.put("tool_call_id", hm.getToolCallId());
 if (hm.getName() != null) m.put("name", hm.getName());
 messages.add(m);
 }
 }
 Map<String, Object> userMsg = new LinkedHashMap<>();
 userMsg.put("role", "user");
 userMsg.put("content", question);
 messages.add(userMsg);

 List<Map<String, Object>> toolsSpec = functionCallingService.getLlamaCppTools();

 Map<String, Object> payload = new LinkedHashMap<>();
 payload.put("model", chatModel);
 payload.put("messages", messages);
 payload.put("temperature", temperature);
 payload.put("max_tokens", maxTokens);
 payload.put("tools", toolsSpec);

 AtomicBoolean toolCallPhase = new AtomicBoolean(false);

 return Flux.concat(
 Flux.just(initialLoadingSse),
 sourcesSseMono,
 streamAgentLoop(payload, 0, toolCallPhase)
 );
 }

 // ==================== Agent Loop（核心逻辑）====================

 /**
 * 非流式 Agent 循环：反复调用 LLM 直到返回普通回答或达到最大调用次数
 */
 private Mono<Map<String, Object>> executeAgentLoop(Map<String, Object> payload, int depth) {
 if (depth >= MAX_TOOL_CALLS) {
 log.warn("[Agent] Max tool call depth reached ({})", MAX_TOOL_CALLS);
 Map<String, Object> result = new LinkedHashMap<>();
 result.put("thought", "达到最大函数调用次数");
 result.put("answer", "抱歉，问题比较复杂，我已经尝试了多种方法。请尝试简化您的问题。");
 return Mono.just(result);
 }

 String baseUrl = localUrl + "/v1/chat/completions";
 String requestJson;
 try {
 requestJson = objectMapper.writeValueAsString(payload);
 } catch (Exception e) {
 Map<String, Object> err = new LinkedHashMap<>();
 err.put("error", "序列化请求失败: " + e.getMessage());
 return Mono.just(err);
 }

 log.info("[Agent] Calling LLM (depth={}), payload size={}", depth, requestJson.length());

 return webClient.post()
 .uri(baseUrl)
 .contentType(MediaType.APPLICATION_JSON)
 .accept(MediaType.APPLICATION_JSON)
 .body(BodyInserters.fromValue(payload))
 .retrieve()
 .bodyToMono(String.class)
 .flatMap(responseRaw -> {
 try {
 log.debug("[Agent] LLM response (depth={}): {}", depth, responseRaw.length() > 500 ? responseRaw.substring(0, 500) + "..." : responseRaw);

 Map<String, Object> response = objectMapper.readValue(responseRaw, new TypeReference<Map<String, Object>>() {});

 // 提取 assistant message
 Map<String, Object> assistantMsg = extractAssistantMessage(response);
 if (assistantMsg == null) {
 // 无有效响应
 Map<String, Object> result = new LinkedHashMap<>();
 result.put("thought", "LLM 响应解析失败");
 result.put("answer", "抱歉，暂时无法获取回答。");
 return Mono.just(result);
 }

 @SuppressWarnings("unchecked")
 List<Map<String, Object>> messages = (List<Map<String, Object>>) payload.get("messages");
 messages.add(assistantMsg);

 // 检查是否有 tool_calls
 Object toolCallsObj = assistantMsg.get("tool_calls");
 if (toolCallsObj == null) {
 toolCallsObj = assistantMsg.get("function_call"); // OpenAI 兼容格式
 }

 if (toolCallsObj == null) {
 // 没有函数调用，返回最终回答
 String answer = extractContent(assistantMsg);
 List<Map<String, Object>> sources = (List<Map<String, Object>>) assistantMsg.get("_sources");
 List<Map<String, Object>> srcList = sources != null ? sources : new ArrayList<>();
 Map<String, Object> doneResult = new LinkedHashMap<>();
 doneResult.put("thought", "Agent 回答完成");
 doneResult.put("answer", answer);
 doneResult.put("sources", srcList);
 return Mono.just(doneResult);
 }

 // 有函数调用，解析并执行
 List<Map<String, Object>> toolCalls;
 if (toolCallsObj instanceof List) {
 @SuppressWarnings("unchecked")
 List<Map<String, Object>> rawList = (List<Map<String, Object>>) toolCallsObj;
 toolCalls = rawList;
 } else {
 toolCalls = Collections.singletonList((Map<String, Object>) toolCallsObj);
 }

 log.info("[Agent] Detected {} tool_call(s) (depth={})", toolCalls.size(), depth);

 // 执行每个 tool_call
 List<Map<String, Object>> toolResults = new ArrayList<>();
 for (Map<String, Object> toolCall : toolCalls) {
 Map<String, Object> fn = (Map<String, Object>) toolCall.get("function");
 String toolName = fn != null ? String.valueOf(fn.get("name")) : String.valueOf(toolCall.get("name"));
 String arguments = fn != null ? String.valueOf(fn.get("arguments")) : String.valueOf(toolCall.get("arguments"));

 String toolCallId = toolCall.containsKey("id") ? String.valueOf(toolCall.get("id")) : "call_" + depth + "_" + toolResults.size();

 log.info("[Agent] Executing tool: name={}, arguments={}", toolName, arguments);

 String result = functionCallingService.executeTool(toolName, arguments);
 log.info("[Agent] Tool result length: {}", result.length());

 // 构建 tool message
 Map<String, Object> toolMsg = new LinkedHashMap<>();
 toolMsg.put("role", "tool");
 toolMsg.put("tool_call_id", toolCallId);
 toolMsg.put("name", toolName);
 toolMsg.put("content", result);
 messages.add(toolMsg);

 Map<String, Object> tr = new HashMap<>();
 tr.put("id", toolCallId);
 tr.put("name", toolName);
 tr.put("result", result.length() > 200 ? result.substring(0, 200) + "..." : result);
 toolResults.add(tr);
 }

 Map<String, Object> continueMap = new LinkedHashMap<>();
 continueMap.put("_continue", true);
 continueMap.put("_payload", payload);
 continueMap.put("_toolResults", toolResults);
 continueMap.put("_messages", messages);
 return Mono.just(continueMap);

 } catch (Exception e) {
 log.error("[Agent] Error parsing LLM response (depth={}): {}", depth, e.getMessage(), e);
 Map<String, Object> errorMap = new LinkedHashMap<>();
 errorMap.put("error", "解析 LLM 响应失败: " + e.getMessage());
 return Mono.just(errorMap);
 }
 })
 .flatMap(result -> {
 if (!result.containsKey("_continue")) {
 @SuppressWarnings("unchecked")
 List<Map<String, Object>> toolResults = (List<Map<String, Object>>) result.getOrDefault("toolCalls", new ArrayList<>());
 List<Object> emptySources = new ArrayList<>();
 Map<String, Object> finalMap = new LinkedHashMap<>();
 finalMap.put("thought", result.getOrDefault("thought", "完成").toString());
 finalMap.put("answer", result.getOrDefault("answer", "").toString());
 finalMap.put("sources", result.getOrDefault("sources", emptySources));
 finalMap.put("toolCalls", toolResults);
 return Mono.just((Map<String, Object>) finalMap);
 }

 // 继续循环
 @SuppressWarnings("unchecked")
 List<Map<String, Object>> loopMessages = (List<Map<String, Object>>) result.get("_messages");
 Map<String, Object> nextPayload = new LinkedHashMap<>();
 nextPayload.putAll(result.get("_payload") != null ? (Map<String, Object>) result.get("_payload") : new HashMap<>());
 nextPayload.put("messages", loopMessages);

 return executeAgentLoop(nextPayload, depth + 1);
 });
 }

 // ==================== 流式 Agent Loop ====================

 /**
 * 流式 Agent 循环
 *
 * 先发 tool_calls 的 "开始调用" 事件，然后执行工具，结果作为 tool_result 事件推送，
 * 最后用工具结果继续调用 LLM 并流式返回最终回答。
 */
 private Flux<String> streamAgentLoop(Map<String, Object> payload, int depth, AtomicBoolean toolCallPhase) {
 if (depth >= MAX_TOOL_CALLS) {
 try {
 Map<String, Object> doneMsg = new HashMap<>();
 doneMsg.put("type", "done");
 doneMsg.put("content", "达到最大函数调用次数，请简化问题。");
 String json = objectMapper.writeValueAsString(doneMsg);
 return Flux.just("data: " + json + "\n\n");
 } catch (Exception e) {
 return Flux.just("data: {\"type\":\"done\",\"content\":\"达到最大调用次数\"}\n\n");
 }
 }

 String baseUrl = localUrl + "/v1/chat/completions";

 // 流式调用 LLM
 Flux<String> llmStream = webClient.post()
 .uri(baseUrl)
 .contentType(MediaType.APPLICATION_JSON)
 .accept(MediaType.valueOf("text/event-stream"))
 .body(BodyInserters.fromValue(payload))
 .retrieve()
 .bodyToFlux(String.class)
 .doOnError(e -> log.error("[Agent] Stream LLM call error (depth={}): {}", depth, e.getMessage()));

 AtomicReference<String> assistantContent = new AtomicReference<>("");
 AtomicReference<List<Map<String, Object>>> detectedToolCalls = new AtomicReference<>(new ArrayList<>());
 AtomicBoolean toolCallHandled = new AtomicBoolean(false);
 String[] pendingToolCallJson = {null};

 // 第一阶段：收集 tool_calls（通过解析每个 SSE chunk）
 Flux<String> phase1 = llmStream
 .flatMap(chunk -> {
 // 跳过 SSE 前缀
 if (chunk == null || chunk.trim().isEmpty() || chunk.startsWith("data: ")) {
 if ("data: [DONE]".equals(chunk.trim()) || "[DONE]".equals(chunk.trim())) {
 return Flux.empty();
 }
 if (chunk.trim().startsWith("data: ")) {
 chunk = chunk.trim().substring(6).trim();
 }
 }

 try {
 Map<String, Object> delta = objectMapper.readValue(chunk, Map.class);

 // llama.cpp streaming: choices[0].delta
 @SuppressWarnings("unchecked")
 List<Map<String, Object>> choices = (List<Map<String, Object>>) delta.get("choices");
 if (choices == null || choices.isEmpty()) {
 return Flux.empty();
 }
 Map<String, Object> choice = choices.get(0);
 Map<String, Object> deltaMsg = (Map<String, Object>) choice.get("delta");
 if (deltaMsg == null) {
 deltaMsg = (Map<String, Object>) choice.get("message");
 }

 if (deltaMsg == null) return Flux.empty();

 // 收集 content
 Object contentObj = deltaMsg.get("content");
 if (contentObj != null) {
 String prev = assistantContent.get();
 String curr = String.valueOf(contentObj);
 if (prev == null || !curr.equals(prev)) {
 String deltaContent = prev == null ? curr : (curr.startsWith(prev) ? curr.substring(prev.length()) : curr);
 assistantContent.set(curr);

 if (!deltaContent.isEmpty()) {
 Map<String, Object> outMsg = new HashMap<>();
 outMsg.put("type", "answer");
 outMsg.put("content", deltaContent);
 return Flux.just("data: " + objectMapper.writeValueAsString(outMsg) + "\n\n");
 }
 }
 }

 // 检查 tool_calls delta
 Object toolCallsObj = deltaMsg.get("tool_calls");
 if (toolCallsObj != null && toolCallsObj instanceof List) {
 @SuppressWarnings("unchecked")
 List<Map<String, Object>> tcDelta = (List<Map<String, Object>>) toolCallsObj;
 List<Map<String, Object>> current = detectedToolCalls.get();
 if (current.isEmpty()) {
 detectedToolCalls.set(new ArrayList<>(tcDelta));
 } else {
 // 追加到现有 tool_calls
 for (Map<String, Object> tcd : tcDelta) {
 int idx = tcd.containsKey("index") ? ((Number) tcd.get("index")).intValue() : 0;
 while (current.size() <= idx) current.add(new LinkedHashMap<>());
 Map<String, Object> existing = current.get(idx);
 existing.putAll(tcd);
 // 追加 arguments
 Object fnObj = tcd.get("function");
 if (fnObj != null) {
 Map<String, Object> fn = (Map<String, Object>) fnObj;
 Object fnExistingObj = existing.get("function");
 Map<String, Object> fnExisting = fnExistingObj != null ? (Map<String, Object>) fnExistingObj : new LinkedHashMap<>();
 fnExistingObj = fnExistingObj != null ? (Map<String, Object>) fnExistingObj : new LinkedHashMap<>();
 if (fnExistingObj instanceof Map) {
 fnExisting = (Map<String, Object>) fnExistingObj;
 }
 fnExisting.putAll(fn);
 existing.put("function", fnExisting);
 Object argsObj = fn.get("arguments");
 if (argsObj != null) {
 Object existingArgsObj = fnExisting.get("arguments");
 String existingArgs = existingArgsObj != null ? String.valueOf(existingArgsObj) : "";
 String newArgs = String.valueOf(argsObj);
 fnExisting.put("arguments", existingArgs + newArgs);
 }
 }
 current.set(idx, existing);
 }
 }
 toolCallPhase.set(true);
 }

 } catch (Exception e) {
 // 解析失败，忽略该 chunk
 }

 return Flux.empty();
 })
 .doOnComplete(() -> {
 log.info("[Agent] Phase1 complete. toolCallPhase={}, toolCalls={}",
 toolCallPhase.get(), detectedToolCalls.get().size());
 });

 // 第二阶段：检查是否有 tool_calls 需要执行
 return phase1
 .collectList()
 .flatMapMany(chunks -> {
 if (!toolCallPhase.get() || detectedToolCalls.get().isEmpty()) {
 // 没有 tool_calls，直接返回 chunks
 if (chunks.isEmpty()) {
 // 流式没有 content，发送一个空完成
 return Flux.just("data: {\"type\":\"done\",\"content\":\"\"}\n\n");
 }
 // 追加 done
 List<String> result = new ArrayList<>(chunks);
 result.add("data: {\"type\":\"done\",\"content\":\"\"}\n\n");
 return Flux.fromIterable(result);
 }

 // 有 tool_calls，执行并递归
 List<Map<String, Object>> toolCalls = detectedToolCalls.get();
 log.info("[Agent] Executing {} tool_call(s) in stream mode (depth={})", toolCalls.size(), depth);

 // 发 tool_calls 通知
 List<Map<String, Object>> toolCallInfos = new ArrayList<>();
 for (Map<String, Object> tc : toolCalls) {
 Map<String, Object> fn = tc.containsKey("function") ? (Map<String, Object>) tc.get("function") : tc;
 String toolName = fn != null ? String.valueOf(fn.get("name")) : "unknown";
 String arguments = fn != null ? String.valueOf(fn.get("arguments")) : "{}";
 String toolCallId = tc.containsKey("id") ? String.valueOf(tc.get("id")) : "call_" + depth;

 Map<String, Object> info = new LinkedHashMap<>();
 info.put("id", toolCallId);
 info.put("name", toolName);
 info.put("arguments", arguments);
 toolCallInfos.add(info);
 }

 Map<String, Object> toolStartMsg = new HashMap<>();
 toolStartMsg.put("type", "tool_start");
 toolStartMsg.put("toolCalls", toolCallInfos);
 try {
 String toolStartJson = "data: " + objectMapper.writeValueAsString(toolStartMsg) + "\n\n";
 return Flux.<String>merge(
 Flux.just(toolStartJson),
 executeToolCallsStream(toolCalls, depth),
 Flux.defer(() -> {
 // 追加 tool message 到 payload
 @SuppressWarnings("unchecked")
 List<Map<String, Object>> messages = (List<Map<String, Object>>) payload.get("messages");

 // 添加 assistant message with tool_calls
 Map<String, Object> assistantMsg = new LinkedHashMap<>();
 assistantMsg.put("role", "assistant");
 assistantMsg.put("tool_calls", toolCalls);
 messages.add(assistantMsg);

 // 添加 tool results
 for (Map<String, Object> tc : toolCalls) {
 Map<String, Object> fn = tc.containsKey("function") ? (Map<String, Object>) tc.get("function") : tc;
 String toolName = fn != null ? String.valueOf(fn.get("name")) : "unknown";
 String arguments = fn != null ? String.valueOf(fn.get("arguments")) : "{}";
 String toolCallId = tc.containsKey("id") ? String.valueOf(tc.get("id")) : "call_" + depth + "_0";
 String result = functionCallingService.executeTool(toolName, arguments);

 Map<String, Object> toolMsg = new LinkedHashMap<>();
 toolMsg.put("role", "tool");
 toolMsg.put("tool_call_id", toolCallId);
 toolMsg.put("name", toolName);
 toolMsg.put("content", result);
 messages.add(toolMsg);
 }

 // 继续调用 LLM
 return streamAgentLoop(payload, depth + 1, toolCallPhase);
 })
 );
 } catch (Exception e) {
 return Flux.just("data: {\"type\":\"error\",\"content\":\"tool 结果序列化失败\"}\n\n");
 }
 });
 }

 /**
 * 流式执行工具调用，发送 tool_result 事件
 */
 private Flux<String> executeToolCallsStream(List<Map<String, Object>> toolCalls, int depth) {
 List<Flux<String>> streams = new ArrayList<>();

 for (int i = 0; i < toolCalls.size(); i++) {
 Map<String, Object> tc = toolCalls.get(i);
 Map<String, Object> fn = tc.containsKey("function") ? (Map<String, Object>) tc.get("function") : tc;
 String toolName = fn != null ? String.valueOf(fn.get("name")) : "unknown";
 String arguments = fn != null ? String.valueOf(fn.get("arguments")) : "{}";
 String toolCallId = tc.containsKey("id") ? String.valueOf(tc.get("id")) : "call_" + depth + "_" + i;

 try {
 String result = functionCallingService.executeTool(toolName, arguments);
 log.info("[Agent] Tool '{}' executed, result length: {}", toolName, result.length());

 Map<String, Object> toolResultMsg = new HashMap<>();
 toolResultMsg.put("type", "tool_result");
 toolResultMsg.put("toolCallId", toolCallId);
 toolResultMsg.put("name", toolName);
 // 截断过长结果
 toolResultMsg.put("result", result.length() > 1000 ? result.substring(0, 1000) + "...(已截断)" : result);

 String json = objectMapper.writeValueAsString(toolResultMsg);
 streams.add(Flux.just("data: " + json + "\n\n"));
 } catch (Exception e) {
 log.error("[Agent] executeTool error: {}", e.getMessage());
 try {
 Map<String, Object> errMsg = new HashMap<>();
 errMsg.put("type", "tool_error");
 errMsg.put("toolCallId", toolCallId);
 errMsg.put("error", e.getMessage());
 String json = objectMapper.writeValueAsString(errMsg);
 streams.add(Flux.just("data: " + json + "\n\n"));
 } catch (Exception ignored) {}
 }
 }

 return Flux.concat(streams);
 }

 // ==================== 辅助方法 ====================

 private List<Map<String, Object>> buildMessages(String question, List<ChatMessage> history) {
 List<Map<String, Object>> messages = new ArrayList<>();

 if (history != null) {
 for (ChatMessage hm : history) {
 Map<String, Object> m = new LinkedHashMap<>();
 m.put("role", hm.getRole());
 m.put("content", hm.getContent());
 if (hm.getToolCallId() != null) {
 m.put("tool_call_id", hm.getToolCallId());
 }
 if (hm.getName() != null) {
 m.put("name", hm.getName());
 }
 messages.add(m);
 }
 }

 Map<String, Object> userMsg = new LinkedHashMap<>();
 userMsg.put("role", "user");
 userMsg.put("content", question);
 messages.add(userMsg);

 return messages;
 }

 private Map<String, Object> buildSystemPrompt(String knowledgeBaseId) {
 String kbHint = knowledgeBaseId != null && !knowledgeBaseId.isEmpty()
 ? "当前选中的知识库: " + knowledgeBaseId
 : "未指定知识库";

 return new LinkedHashMap<String, Object>() {{
 put("role", "system");
 put("content",
 "你是一个专业的建筑行业AI助手，名字叫「智建助手」。" +
 kbHint + "\n\n" +
 "【核心规则】当知识库检索返回了文档片段时，你必须严格遵循以下要求：\n" +
 "1. 在回答中明确引用来源，格式为：\n" +
 "   「根据《文档名》第X页 / 章节名：具体内容」\n" +
 "   例如：「根据《GB 50007-2011 建筑地基基础设计规范》第3.1.1条：地基基础设计应根据地基复杂程度、建筑物规模和功能特征以及由于地基问题可能造成建筑物破坏或影响正常使用的后果严重程度，分为甲、乙、丙三个设计等级。」\n" +
 "2. 如果返回了多个片段，必须逐条引用，不得遗漏\n" +
 "3. 禁止只说\"根据知识库\"或\"从检索结果中可以看到\"这种模糊表述，必须给出具体文档名和页码/条款\n" +
 "4. 只有当知识库检索结果为空时，才能基于自己的专业知识回答，但仍需告知用户\n\n" +
 "可用的工具：\n" +
 "- search_knowledge_base(question, knowledge_base_id): 语义检索知识库，适合自然语言问题\n" +
 "- search_by_keyword(keyword, knowledge_base_id): 关键词检索，适合精确查找\n" +
 "- get_collection_stats(knowledge_base_id): 查看知识库文档数量\n" +
 "- list_knowledge_bases(): 列出所有可用知识库\n\n" +
 "重要：你必须实际调用工具来获取信息，而不是凭空编造知识库内容！"
 );
 }};
 }

 private Map<String, Object> extractAssistantMessage(Map<String, Object> response) {
 try {
 @SuppressWarnings("unchecked")
 List<Map<String, Object>> choices = (List<Map<String, Object>>) response.get("choices");
 if (choices == null || choices.isEmpty()) return null;

 Map<String, Object> choice = choices.get(0);
 Map<String, Object> message = (Map<String, Object>) choice.get("message");
 if (message == null) {
 // 流式格式（通常在 agent loop 中走 SSE）
 message = (Map<String, Object>) choice.get("delta");
 }
 return message;
 } catch (Exception e) {
 log.warn("[Agent] extractAssistantMessage error: {}", e.getMessage());
 return null;
 }
 }

 private String extractContent(Map<String, Object> message) {
 if (message == null) return "";
 Object content = message.get("content");
 if (content != null) return String.valueOf(content);

 // 如果没有 content，可能是拒绝回答
 Object refusal = message.get("refusal");
 if (refusal != null) return String.valueOf(refusal);

 return "（未获取到有效回答）";
 }

 private com.luanshuai.agent.model.ChatRequest buildChatRequest(String question, List<ChatMessage> history, String kbId) {
 com.luanshuai.agent.model.ChatRequest request = new com.luanshuai.agent.model.ChatRequest();
 request.setQuestion(question);
 request.setKnowledgeBaseId(kbId);
 if (history != null) {
 List<com.luanshuai.agent.model.ChatRequest.ChatMessage> converted = new ArrayList<>();
 for (ChatMessage hm : history) {
 converted.add(new com.luanshuai.agent.model.ChatRequest.ChatMessage(hm.getContent(), "user".equals(hm.getRole())));
 }
 request.setHistory(converted);
 }
 return request;
 }
}
