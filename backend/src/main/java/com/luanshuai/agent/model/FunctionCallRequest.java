package com.luanshuai.agent.model;

import java.util.List;
import java.util.Map;

/**
 * FunctionCallRequest - 封装 Agent 模式下带 function calling 的对话请求
 */
public class FunctionCallRequest {

 private String question;
 private List<ChatMessage> history;
 private String knowledgeBaseId;
 private boolean stream = true;
 private boolean enableFunctionCalling = true;

 public String getQuestion() {
 return question != null ? question : "";
 }
 public void setQuestion(String question) {
 this.question = question;
 }

 public List<ChatMessage> getHistory() {
 return history;
 }
 public void setHistory(List<ChatMessage> history) {
 this.history = history;
 }

 public String getKnowledgeBaseId() {
 return knowledgeBaseId != null ? knowledgeBaseId : "default_knowledge_base";
 }
 public void setKnowledgeBaseId(String knowledgeBaseId) {
 this.knowledgeBaseId = knowledgeBaseId;
 }

 public boolean isStream() {
 return stream;
 }
 public void setStream(boolean stream) {
 this.stream = stream;
 }

 public boolean isEnableFunctionCalling() {
 return enableFunctionCalling;
 }
  public void setEnableFunctionCalling(boolean enableFunctionCalling) {
 this.enableFunctionCalling = enableFunctionCalling;
 }

 public static class ChatMessage {
 private String role; // "user" or "assistant"
  private String content;
 private Map<String, Object> toolCall; // 模型发出的 tool_call
 private String toolCallId;  // tool_call 的 id
 private String name; // 被调用的工具名

 public ChatMessage() {}

 public ChatMessage(String role, String content) {
 this.role = role;
 this.content = content;
 }

 public String getRole() {
 return role;
 }
 public void setRole(String role) {
 this.role = role;
 }

 public String getContent() {
 return content;
 }
 public void setContent(String content) {
 this.content = content;
 }

 public Map<String, Object> getToolCall() {
 return toolCall;
 }
 public void setToolCall(Map<String, Object> toolCall) {
 this.toolCall = toolCall;
 }

 public String getToolCallId() {
 return toolCallId;
 }
 public void setToolCallId(String toolCallId) {
 this.toolCallId = toolCallId;
 }

 public String getName() {
 return name;
 }
 public void setName(String name) {
 this.name = name;
 }
 }
}
