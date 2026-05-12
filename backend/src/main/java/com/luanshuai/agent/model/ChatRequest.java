// 包声明：模型层，表示客户端发送的聊天请求
package com.luanshuai.agent.model;

// 导入集合类型
import java.util.List;

// 日志工具
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

// Jackson 注解：支持别名字段解析
import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * ChatRequest：封装前端或客户端发起的问答请求
 * - question: 问题文本
 * - history: 对话历史（可为空）
 * - knowledgeBaseId: 指定的知识库 id
 */
public class ChatRequest {
    // 日志记录器，用于调试 ChatRequest 的使用情况
    private static final Logger log = LoggerFactory.getLogger(ChatRequest.class);
    
    // 将 JSON 的 question 字段映射到此属性；同时兼容别名 message
    @JsonProperty("question")
    @JsonAlias({"message"})
    private String question;
    // 对话历史：列表形式保存历史消息
    private List<ChatMessage> history;
    // 知识库 ID 字段，兼容别名 knowledgeBase
    @JsonProperty("knowledgeBaseId")
    @JsonAlias({"knowledgeBase"})
    private String knowledgeBaseId;
    // 可选：指定知识库的物理路径
    private String knowledgeBasePath;
    // 租户 ID（用于多租户隔离）
    private String tenantId;
    // 是否启用深度思考模式（发送给 llama.cpp 的 enable_thinking 参数）
    private boolean enableThinking = false;
    
    // 兼容性字段注释（如需启用可使用 JsonAlias）
    // @JsonAlias("message")
    // private String message;
    // @JsonAlias("knowledgeBase")
    // private String knowledgeBase;
    
    // 默认构造：记录一次构造调用日志，便于调试
    public ChatRequest() {
        log.info("[ChatRequest] Default constructor called");
    }
    
    // 带参构造器
    public ChatRequest(String question, List<ChatMessage> history) {
        this.question = question; // 问题文本
        this.history = history;   // 历史消息
    }
    
    // 获取问题文本（若为 null 则返回空字符串以简化调用方逻辑）
    public String getQuestion() { 
        log.info("[ChatRequest] getQuestion called: question='{}'", question);
        return question != null ? question : ""; 
    }
    // 设置问题文本
    public void setQuestion(String question) { 
        this.question = question; 
    }
    
    // 历史消息的 getter/setter
    public List<ChatMessage> getHistory() { return history; }
    public void setHistory(List<ChatMessage> history) { this.history = history; }

    // 获取知识库 ID（若未设置则返回默认知识库 id）
    public String getKnowledgeBaseId() { 
        log.info("[ChatRequest] getKnowledgeBaseId called: knowledgeBaseId='{}'", knowledgeBaseId);
        return knowledgeBaseId != null ? knowledgeBaseId : "default_knowledge_base"; 
    }
    public void setKnowledgeBaseId(String knowledgeBaseId) { 
        this.knowledgeBaseId = knowledgeBaseId; 
    }
    
    // 知识库路径的 getter/setter
    public String getKnowledgeBasePath() { return knowledgeBasePath; }
    public void setKnowledgeBasePath(String knowledgeBasePath) { this.knowledgeBasePath = knowledgeBasePath; }

    // 租户 ID 的 getter/setter
    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }

    // 深度思考模式 getter/setter
    public boolean isEnableThinking() { return enableThinking; }
    public void setEnableThinking(boolean enableThinking) { this.enableThinking = enableThinking; }
    
    // 兼容性字段的 getter/setter（注释状态；如需开启可取消注释）
    // public String getMessage() { return message; }
    // public void setMessage(String message) { this.message = message; }
    
    // public String getKnowledgeBase() { return knowledgeBase; }
    // public void setKnowledgeBase(String knowledgeBase) { this.knowledgeBase = knowledgeBase; }
    
    // 内部类：表示单条消息（用户或系统）
    public static class ChatMessage {
        // 消息内容文本
        private String content;
        // 是否为用户发送的消息（true）或系统/代理（false）
        private boolean isUser;
        
        // 默认构造
        public ChatMessage() {}
        
        // 带参构造
        public ChatMessage(String content, boolean isUser) {
            this.content = content; // 文本
            this.isUser = isUser;   // 标记发信方
        }
        
        // Getter/Setter
        public String getContent() { return content; }
        public void setContent(String content) { this.content = content; }
        
        public boolean isUser() { return isUser; }
        public void setUser(boolean user) { isUser = user; }
    }
}