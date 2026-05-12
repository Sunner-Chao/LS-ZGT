package com.luanshuai.agent.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.luanshuai.agent.config.AppConfig;
import com.luanshuai.agent.model.FunctionCallRequest.ChatMessage;
import com.luanshuai.agent.util.TenantContext;
import com.luanshuai.agent.util.TenantUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * FunctionCallingService - 管理函数注册、调用解析与执行
 *
 * 支持本地 llama.cpp (tools) 和云端 OpenAI (function_call) 两种 function calling 协议。
 * 函数注册后以 OpenAI Function Calling 规范输出 tools 参数，
 * 执行时根据模型返回的 tool_calls / function_call 调用对应的 Java 实现。
 *
 * 内置函数：
 * - search_knowledge_base(question, knowledge_base_id) - 语义检索知识库
 * - get_collection_stats(knowledge_base_id) - 获取集合文档数量
 * - list_knowledge_bases() - 列出当前租户所有知识库
 * - search_by_keyword(keyword, knowledge_base_id) - 关键词全文检索
 */
@Service
public class FunctionCallingService {

 private static final Logger log = LoggerFactory.getLogger(FunctionCallingService.class);
 private final ObjectMapper objectMapper = new ObjectMapper();

 @Autowired
 private MilvusDbService milvusDbService;

 @Autowired
 private RagService ragService;

 @Autowired
 private LLMService llmService;

 @Autowired
 private AppConfig appConfig;

 @Value("${app.llm.provider:local}")
 private String provider;

 // ==================== 函数注册表 ====================

 /**
 * 所有可用函数的元信息，格式符合 OpenAI Function Calling schema
 * 用于发给模型的 tools 参数
 */
 private final Map<String, ToolDefinition> tools = new LinkedHashMap<>();

 public FunctionCallingService() {
 registerBuiltinTools();
 }

 private void registerBuiltinTools() {
 // 语义检索知识库
 registerTool("search_knowledge_base", "语义检索知识库内容",
 List.of(
 ToolParameter.builder()
 .name("question")
 .type("string")
 .description("用户的自然语言问题")
 .required(true)
 .build(),
 ToolParameter.builder()
 .name("knowledge_base_id")
 .type("string")
 .description("知识库名称/ID（可选，不填则搜索默认知识库）")
 .required(false)
 .build()
 ));

 // 获取集合统计
 registerTool("get_collection_stats", "获取知识库集合的统计信息",
 List.of(
 ToolParameter.builder()
 .name("knowledge_base_id")
 .type("string")
 .description("知识库名称/ID")
 .required(true)
 .build()
 ));

 // 列出所有知识库
 registerTool("list_knowledge_bases", "列出当前用户可用的所有知识库",
 List.of());

 // 关键词全文检索
 registerTool("search_by_keyword", "使用关键词在知识库中进行全文检索",
 List.of(
 ToolParameter.builder()
 .name("keyword")
 .type("string")
 .description("搜索关键词（中文或英文）")
 .required(true)
 .build(),
 ToolParameter.builder()
 .name("knowledge_base_id")
 .type("string")
 .description("知识库名称/ID（可选）")
 .required(false)
 .build()
 ));
 }

 private void registerTool(String name, String description, List<ToolParameter> params) {
 tools.put(name, new ToolDefinition(name, description, params));
 }

 // ==================== 对外接口 ====================

 /**
 * 获取所有已注册函数，用于发给 LLM 的 tools 参数
 */
 public List<Map<String, Object>> getToolsSpec() {
 List<Map<String, Object>> spec = new ArrayList<>();
 for (ToolDefinition tool : tools.values()) {
 spec.add(tool.toMap());
 }
 return spec;
 }

 /**
  * 获取用于发送给 llama.cpp 的 tools 数组
 * 兼容 llama.cpp server 的 tool_use 格式
 */
 public List<Map<String, Object>> getLlamaCppTools() {
 List<Map<String, Object>> result = new ArrayList<>();
 for (ToolDefinition tool : tools.values()) {
 Map<String, Object> t = new LinkedHashMap<>();
 t.put("type", "function");
 Map<String, Object> fn = new LinkedHashMap<>();
 fn.put("name", tool.name);
 fn.put("description", tool.description);
 Map<String, Object> params = new LinkedHashMap<>();
 params.put("type", "object");
 Map<String, Object> props = new LinkedHashMap<>();
 List<String> required = new ArrayList<>();
 for (ToolParameter p : tool.parameters) {
 Map<String, Object> prop = new LinkedHashMap<>();
 prop.put("type", p.type);
 prop.put("description", p.description);
 props.put(p.name, prop);
 if (p.required) required.add(p.name);
 }
 params.put("properties", props);
 if (!required.isEmpty()) params.put("required", required);
 fn.put("parameters", params);
 t.put("function", fn);
 result.add(t);
 }
 return result;
 }

 /**
 * 执行工具调用
 *
 * @param toolName 函数名
 * @param arguments JSON 字符串或 Map 参数
 * @return 执行结果（字符串），出错时返回错误信息
 */
 public String executeTool(String toolName, Object arguments) {
 log.info("[FunctionCalling] executeTool: name={}, args={}", toolName, arguments);

 try {
 Map<String, Object> args;
 if (arguments instanceof String) {
 args = objectMapper.readValue((String) arguments, new TypeReference<Map<String, Object>>() {});
 } else if (arguments instanceof Map) {
 @SuppressWarnings("unchecked")
 Map<String, Object> m = (Map<String, Object>) arguments;
 args = m;
 } else {
 return "错误：参数格式不支持";
 }

 switch (toolName) {
 case "search_knowledge_base":
 return executeSearchKnowledgeBase(args);
 case "get_collection_stats":
 return executeGetCollectionStats(args);
 case "list_knowledge_bases":
 return executeListKnowledgeBases();
 case "search_by_keyword":
 return executeSearchByKeyword(args);
 default:
 return "错误：未知函数 " + toolName;
 }
 } catch (Exception e) {
 log.error("[FunctionCalling] executeTool error: name={}, error={}", toolName, e.getMessage(), e);
 return "执行函数 " + toolName + " 时出错：" + e.getMessage();
 }
 }

 // ==================== 内置函数实现 ====================

 private String executeSearchKnowledgeBase(Map<String, Object> args) {
 String question = args.get("question") != null ? String.valueOf(args.get("question")) : "";
 String kbId = args.get("knowledge_base_id") != null ? String.valueOf(args.get("knowledge_base_id")) : null;

 if (question.trim().isEmpty()) {
 return "错误：question 参数不能为空";
 }

 String effectiveTenantId = getEffectiveTenantId();
 String collectionName = resolveCollectionName(effectiveTenantId, kbId);

 if (!milvusDbService.hasCollection(collectionName)) {
 return "知识库 '" + kbId + "' 不存在或为空";
 }

 try {
 List<Double> embedding = ragService.generateEmbeddingSync(question);
 List<Float> vector = new ArrayList<>();
 for (Double d : embedding) vector.add(d.floatValue());

 int maxResults = 5;
 try {
 if (appConfig != null && appConfig.getPerformance() != null) {
 maxResults = appConfig.getPerformance().getMaxSearchResults();
 }
 } catch (Exception ignored) {}

 List<Map<String, Object>> docs = milvusDbService.queryDocuments(collectionName, vector, maxResults, question);

 if (docs.isEmpty()) {
 return "在知识库 '" + kbId + "' 中未找到与 '" + question + "' 相关的内容";
 }

 StringBuilder sb = new StringBuilder();
 sb.append("在知识库 '").append(kbId).append("' 中找到 ").append(docs.size()).append(" 条相关内容：\n\n");
 for (int i = 0; i < docs.size(); i++) {
 Map<String, Object> doc = docs.get(i);
 Object metaObj = doc.get("metadata");
 String docName = extractSourceFromMeta(metaObj);
 Object textObj = doc.get("text");
 String text = textObj != null ? String.valueOf(textObj) : "";
 if (text.length() > 500) text = text.substring(0, 500) + "...";
 Object scoreObj = doc.get("score");
 String scoreStr = scoreObj != null ? String.format(" (相似度: %.4f)", ((Number) scoreObj).doubleValue()) : "";
 sb.append("【文档").append(i + 1).append("】").append(docName).append(scoreStr).append("\n");
 sb.append(text).append("\n\n");
 }
 return sb.toString();
 } catch (Exception e) {
  log.error("[FunctionCalling] search_knowledge_base error: {}", e.getMessage(), e);
 return "检索知识库时出错：" + e.getMessage();
 }
 }

 private String executeGetCollectionStats(Map<String, Object> args) {
 String kbId = args.get("knowledge_base_id") != null ? String.valueOf(args.get("knowledge_base_id")) : "default_knowledge_base";
 String effectiveTenantId = getEffectiveTenantId();
 String collectionName = TenantUtils.buildTenantCollectionName(effectiveTenantId, kbId);

 try {
 boolean exists = milvusDbService.hasCollection(collectionName);
 if (!exists) {
 return "知识库 '" + kbId + "' 不存在";
 }
 int count = milvusDbService.getCollectionCount(collectionName);
 return "知识库 '" + kbId + "' 共有 " + count + " 个文档片段";
 } catch (Exception e) {
 return "获取统计信息时出错：" + e.getMessage();
 }
 }

 private String executeListKnowledgeBases() {
 String effectiveTenantId = getEffectiveTenantId();
 try {
 List<String> allCollections = milvusDbService.listCollections();
 String prefix = TenantUtils.buildTenantCollectionName(effectiveTenantId, "").replace("default_knowledge_base", "");
 StringBuilder sb = new StringBuilder("当前可用的知识库：\n");
 int count = 0;
 for (String col : allCollections) {
 if (col.startsWith(prefix) || (effectiveTenantId.equals("default") && col.contains("__"))) {
 String kbName;
 if (col.contains("__")) {
 kbName = col.substring(col.indexOf("__") + 2);
 } else {
 kbName = col;
 }
 if (kbName.startsWith("kb_")) kbName = kbName.substring(3);
 if (kbName.equals("default_knowledge_base")) kbName = "默认知识库";
 int docCount = milvusDbService.getCollectionCount(col);
 sb.append("- ").append(kbName).append(" (").append(col).append("): ").append(docCount).append(" 文档\n");
 count++;
 }
 }
 if (count == 0) {
 sb.append("（暂无知识库）");
 }
 return sb.toString();
 } catch (Exception e) {
 return "列出知识库时出错：" + e.getMessage();
 }
 }

 private String executeSearchByKeyword(Map<String, Object> args) {
 String keyword = args.get("keyword") != null ? String.valueOf(args.get("keyword")) : "";
 String kbId = args.get("knowledge_base_id") != null ? String.valueOf(args.get("knowledge_base_id")) : null;

 if (keyword.trim().isEmpty()) {
 return "错误：keyword 参数不能为空";
 }

 String effectiveTenantId = getEffectiveTenantId();
 String collectionName = resolveCollectionName(effectiveTenantId, kbId);

 if (!milvusDbService.hasCollection(collectionName)) {
 return "知识库 '" + kbId + "' 不存在";
 }

 try {
 List<Map<String, Object>> docs = milvusDbService.searchDocumentsByKeyword(collectionName, keyword);
 if (docs.isEmpty()) {
 return "在知识库 '" + kbId + "' 中未找到包含关键词 '" + keyword + "' 的内容";
 }

 StringBuilder sb = new StringBuilder();
 sb.append("在知识库 '").append(kbId).append("' 中通过关键词 '").append(keyword)
  .append("' 找到 ").append(docs.size()).append(" 条结果：\n\n");
 for (int i = 0; i < Math.min(docs.size(), 5); i++) {
 Map<String, Object> doc = docs.get(i);
 Object metaObj = doc.get("metadata");
 String docName = extractSourceFromMeta(metaObj);
 Object textObj = doc.get("text");
 String text = textObj != null ? String.valueOf(textObj) : "";
 if (text.length() > 300) text = text.substring(0, 300) + "...";
 sb.append("【").append(i + 1).append("】").append(docName).append("\n").append(text).append("\n\n");
 }
 return sb.toString();
 } catch (Exception e) {
 return "关键词搜索时出错：" + e.getMessage();
 }
 }

 // ==================== 辅助方法 ====================

 private String getEffectiveTenantId() {
 String tenantId = TenantContext.getCurrentTenantId();
 return (tenantId == null || tenantId.trim().isEmpty()) ? "default" : tenantId.trim();
 }

 private String resolveCollectionName(String tenantId, String kbId) {
 if (kbId == null || kbId.trim().isEmpty() || kbId.equals("default_knowledge_base")) {
 return TenantUtils.buildTenantCollectionName(tenantId, "default_knowledge_base");
 }
 return TenantUtils.buildTenantCollectionName(tenantId, kbId);
 }

 private String extractSourceFromMeta(Object metaObj) {
 if (metaObj == null) return "未知文档";
 try {
  Map<String, Object> meta;
 if (metaObj instanceof String) {
 meta = objectMapper.readValue((String) metaObj, Map.class);
 } else if (metaObj instanceof List) {
 List<?> list = (List<?>) metaObj;
 if (!list.isEmpty() && list.get(0) instanceof Map) {
 @SuppressWarnings("unchecked")
 Map<String, Object> m0 = (Map<String, Object>) list.get(0);
 meta = m0;
 } else if (!list.isEmpty()) {
 return String.valueOf(list.get(0));
 } else {
 return "未知文档";
 }
 } else {
 @SuppressWarnings("unchecked")
 Map<String, Object> m = (Map<String, Object>) metaObj;
 meta = m;
 }
 Object source = meta.get("source");
 return source != null ? String.valueOf(source) : "未知文档";
 } catch (Exception e) {
 return metaObj.toString();
 }
 }

 // ==================== 内部类 ====================

 public static class ToolDefinition {
 public final String name;
 public final String description;
 public final List<ToolParameter> parameters;

 public ToolDefinition(String name, String description, List<ToolParameter> parameters) {
 this.name = name;
 this.description = description;
 this.parameters = parameters;
 }

 public Map<String, Object> toMap() {
 Map<String, Object> m = new LinkedHashMap<>();
 m.put("name", name);
 m.put("description", description);
 Map<String, Object> props = new LinkedHashMap<>();
 List<String> required = new ArrayList<>();
 for (ToolParameter p : parameters) {
 Map<String, Object> prop = new LinkedHashMap<>();
 prop.put("type", p.type);
 prop.put("description", p.description);
 props.put(p.name, prop);
 if (p.required) required.add(p.name);
 }
 Map<String, Object> params = new LinkedHashMap<>();
 params.put("type", "object");
 params.put("properties", props);
  if (!required.isEmpty()) params.put("required", required);
 m.put("parameters", params);
 return m;
 }
 }

 public static class ToolParameter {
 public final String name;
 public final String type;
 public final String description;
 public final boolean required;

 public ToolParameter(String name, String type, String description, boolean required) {
 this.name = name;
 this.type = type;
 this.description = description;
 this.required = required;
 }

 public static Builder builder() {
 return new Builder();
 }

 public static class Builder {
 private String name;
 private String type = "string";
 private String description = "";
 private boolean required = false;

 public Builder name(String name) { this.name = name; return this; }
  public Builder type(String type) { this.type = type; return this; }
 public Builder description(String description) { this.description = description; return this; }
 public Builder required(boolean required) { this.required = required; return this; }
 public ToolParameter build() {
 return new ToolParameter(name, type, description, required);
 }
 }
 }
}
