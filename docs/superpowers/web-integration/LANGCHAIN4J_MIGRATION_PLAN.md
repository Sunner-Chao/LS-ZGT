# LangChain4j Agent 平台重构计划

## 一、背景与目标

**现状问题**：
- RAG 链路手写，LangChain4j 依赖已引但未用
- 文档解析多套工具（PDFBox/POI/Tesseract）不统一
- 无 Agent 能力（Tool/Function Calling/Skills）
- 无 MCP 协议支持
- 无外部平台接入（QQ/飞书/微信）

**迁移目标**（全量覆盖）：
1. LangChain4j Agent 架构（ReAct + AiServices + ChatMemory + Streaming）
2. Docling 统一文档解析（所有格式 → 结构化）
3. Skills 技能系统（可注册、可组合）
4. Function Calling / Tool Use（LangChain4j 内置）
5. MCP (Model Context Protocol) 支持（Server + Client）
6. 外部平台接入：QQ / 飞书 / 微信

---

## 二、目标架构

```
┌────────────────────────────────────────────────────────────────────┐
│                         前端 (Vue 3)                               │
│   AgentDrawer / HomeView  ── SSE ──►  /api/chat/stream             │
└────────────────────────────────────────────────────────────────────┘

┌────────────────────────────────────────────────────────────────────┐
│                    ApiController (保持不变)                        │
│                    SSE 协议 data.type 不变                          │
└──────────────────────────────────────────────��─────────────────────┘
                              │
                              ▼
┌────────────────────────────────────────────────────────────────────┐
│                    LangChain4jAgentService (NEW)                   │
│   - AiServices Agent (ReAct 模式)                                   │
│   - ChatMemory (多轮对话)                                          │
│   - StreamingResponseHandler → SSE 转发                            │
│   - Skills 注册中心                                                │
│   - Tool 调度器                                                    │
└────────────────────────────────────────────────────────────────────┘
          │          │           │           │
          ▼          ▼           ▼           ▼
   ┌──────────┐ ┌──────────┐ ┌──────────┐ ┌──────────┐
   │ Knowledge│ │ Document │ │ WebSearch│ │ Calc    │
   │ Search   │ │ Read     │ │ Skill   │ │ Skill   │
   └──────────┘ └──────────┘ └──────────┘ └──────────┘
       │            │           │           │
       └────────────┴───────────┴───────────┘
                        │
          ┌─────────────┼─────────────┐
          ▼             ▼             ▼
   ┌────────────┐ ┌──────────────┐ ┌────────────┐
   │ Milvus     │ │ Docling      │ │ External   │
   │ Embedding  │ │ Service      │ │ Platforms  │
   │ Store      │ │ (Python)     │ │ (QQ/飞书   │
   └────────────┘ └──────────────┘ │ /微信/MCP) │
         │              │          └────────────┘
         │              ▼
         │    ┌──────────────┐
         │    │ docling-api  │
         │    │ (Python)     │
         │    │ :8001        │
         │    └──────────────┘
         ▼
   ┌────────────┐
   │ Milvus 2.4 │
   └────────────┘

┌────────────────────────────────────────────────────────────────────┐
│                    MCP Server Layer (NEW)                          │
│   - agent-mcp-server: 暴露 Agent 能力 via MCP JSON-RPC 2.0          │
│   - 可被 Claude Desktop / Cursor 等 MCP Client 调用                  │
└────────────────────────────────────────────────────────────────────┘

┌────────────────────────────────────────────────────────────────────┐
│                    外部平台适配层 (NEW)                            │
│   - QQAdapter: go-cqhttp / QQ 官方 Bot API                         │
│   - FeishuAdapter: 飞书开放平台 Webhook + 消息 API                   │
│   - WeChatAdapter: 企业微信 / 公众号                               │
│   - 统一消息格式: Message → ChatMessage → Agent → 回复              │
└────────────────────────────────────────────────────────────────────┘
```

---

## 三、依赖更新（pom.xml）

### 3.1 Java 侧依赖（LangChain4j 升级 + 新增）

```xml
<!-- LangChain4j 升级到 v1.3.0（移除旧的 1.0.0） -->
<dependency>
    <groupId>dev.langchain4j</groupId>
    <artifactId>langchain4j</artifactId>
    <version>1.3.0</version>
</dependency>
<dependency>
    <groupId>dev.langchain4j</groupId>
    <artifactId>langchain4j-open-ai</artifactId>
    <version>1.3.0</version>
</dependency>
<!-- LangChain4j Milvus 向量存储（新增） -->
<dependency>
    <groupId>dev.langchain4j</groupId>
    <artifactId>langchain4j-milvus</artifactId>
    <version>1.3.0</version>
</dependency>

<!-- MCP SDK（新增） -->
<dependency>
    <groupId>io.github._MODELCONTEXTPROTOCOL</groupId>
    <artifactId>mcp-java-sdk</artifactId>
    <version>0.2.0</version>
</dependency>

<!-- Spring Boot WebClient（已引入） -->
<!-- Spring Security（已引入） -->
<!-- Milvus SDK（已引入） -->

<!-- JSON-RPC 2.0（MCP 协议基础） -->
<dependency>
    <groupId>com.googlecode.json-simple</groupId>
    <artifactId>json-simple</artifactId>
    <version>1.1.1</version>
</dependency>

<!-- HTTP Client for Docling Service -->
<dependency>
    <groupId>org.springframework</groupId>
    <artifactId>spring-webflux</artifactId>
</dependency>

<!-- YAML 配置解析（Skills 定义文件） -->
<dependency>
    <groupId>org.yaml</groupId>
    <artifactId>snakeyaml</artifactId>
    <version>2.2</version>
</dependency>
```

### 3.2 Python 侧依赖（Docling 微服务）

```bash
# requirements.txt
docling>=2.8.0
fastapi>=0.110.0
uvicorn>=0.27.0
pydantic>=2.6.0
python-multipart>=0.0.9
httpx>=0.27.0
```

---

## 四、文件结构（新增 + 重构）

```
backend/src/main/java/com/luanshuai/agent/
├── service/
│   ├── llm/                        # 【NEW】LangChain4j 适配器层
│   │   ├── LlmFactory.java          # 工厂：根据 provider 创建 ChatLanguageModel
│   │   ├── LlamaCppChatModel.java   # 适配 llama.cpp → LangChain4j
│   │   ├── CloudChatModel.java      # 适配 OpenAI/Claude → LangChain4j
│   │   └── LlamaCppEmbeddingModel.java # 适配 llama.cpp embedding
│   │
│   ├── vector/                     # 【NEW】LangChain4j 向量存储层
│   │   ├── TenantAwareEmbeddingStore.java  # 封装 MilvusEmbeddingStore + tenant 隔离
│   │   └── EmbeddingStoreFactory.java      # 工厂：缓存 Milvus 连接
│   │
│   ├── rag/                        # 【REFACTOR】LangChain4j RAG 层
│   │   ├── ConversationalRagService.java    # AiServices + ConversationalRetrievalChain
│   │   ├── TenantAwareTextSplitter.java    # ~1000字符分块，保留现有策略
│   │   └── AnswerRenderer.java              # 渲染为 SSE 消息
│   │
│   ├── agent/                      # 【NEW】Agent 核心
│   │   ├── LangChain4jAgentService.java    # AiServices Agent + ReAct
│   │   ├── AgentStreamHandler.java        # StreamingResponseHandler → SSE
│   │   └── SystemPromptBuilder.java       # System Prompt 构建
│   │
│   ├── skill/                      # 【NEW】Skills 系统
│   │   ├── Skill.java              # Skill 接口（@FunctionalInterface）
│   │   ├── SkillRegistry.java     # Skill 注册中心
│   │   ├── SkillLoader.java       # 从 YAML 加载 Skill 定义
│   │   └── impl/                  # 预置 Skill 实现
│   │       ├── KnowledgeSearchSkill.java
│   │       ├── DocumentReadSkill.java
│   │       ├── WebSearchSkill.java
│   │       ├── CalculationSkill.java
│   │       └── CodeExecuteSkill.java
│   │
│   ├── tool/                       # 【NEW】Tool 映射层
│   │   ├── SkillToToolAdapter.java # Skill → LangChain4j @Tool 转换
│   │   └── ToolExecutor.java      # Tool 执行器
│   │
│   ├── document/                  # 【NEW】Docling 服务客户端
│   │   ├── DoclingClient.java     # HTTP 调用 docling-api 微服务
│   │   └── DocumentConverter.java # 转换结果 → LangChain4j Document
│   │
│   └── mcp/                        # 【NEW】MCP 支持
│       ├── AgentMcpServer.java    # MCP Server：暴露 Agent 能力
│       ├── McpClientManager.java  # MCP Client：连接外部 MCP Server
│       └── McpToolBridge.java     # MCP Tool → LangChain4j Tool 桥接
│
├── adapter/                        # 【NEW】外部平台适配
│   ├── message/                    # 统一消息格式
│   │   ├── ChatMessage.java       # 统一消息模型
│   │   └── MessageConverter.java   # 各平台消息格式转换
│   │
│   ├── qq/                         # QQ 机器人
│   │   ├── QQAdapter.java         # 消息接收/发送
│   │   └── GoCqHttpClient.java    # go-cqhttp WebSocket 客户端
│   │
│   ├── feishu/                     # 飞书机器人
│   │   ├── FeishuAdapter.java     # Webhook 接收 + 消息发送
│   │   ├── FeishuCardBuilder.java # 卡片消息构建器
│   │   └── FeishuAuthService.java # OAuth2 认证
│   │
│   └── wechat/                    # 微信（企业微信/公众号）
│       ├── WechatAdapter.java     # 统一微信适配入口
│       ├── WxWorkAdapter.java     # 企业微信
│       └── WxOfficialAdapter.java # 公众号
│
├── memory/                        # 【NEW】ChatMemory
│   ├── TenantAwareChatMemoryFactory.java
│   └── InMemoryChatMemoryStore.java  # 可替换为 Redis
│
├── config/
│   ├── LangChain4jConfig.java      # LangChain4j Bean 配置
│   ├── MilvusConfig.java          # Milvus 连接配置
│   └── SkillsConfig.java          # Skills YAML 加载
│
└── controller/
    ├── ApiController.java         # 【不变】保持 /api/chat/stream 接口
    ├── McpController.java         # 【NEW】MCP Server HTTP 端点
    ├── QQWebhookController.java   # 【NEW】QQ 消息 Webhook
    ├── FeishuWebhookController.java # 【NEW】飞书消息 Webhook
    └── WechatCallbackController.java # 【NEW】微信回调

backend/src/main/resources/
├── skills/                        # 【NEW】Skill 定义文件
│   ├── knowledge_search.yaml
│   ├── document_read.yaml
│   ├── web_search.yaml
│   ├── calculation.yaml
│   └── code_execute.yaml
└── mcp/
    └── server-config.yaml         # MCP Server 配置

docling-service/                   # 【NEW】Python Docling 微服务
├── main.py                        # FastAPI 入口
├── routers/
│   └── parse.py                   # 解析接口
├── core/
│   ├── docling_converter.py       # Docling 核心封装
│   └── chunker.py                 # 分块策略
└── requirements.txt
```

---

## 五、实施阶段（6个阶段，20-26工作日）

### Phase 1: LangChain4j 适配器层（底层）

**目标**：替换现有 LLMService 的 if-else 分支为 LangChain4j 统一抽象

**文件变更**：
| 操作 | 文件 | 说明 |
|------|------|------|
| 新增 | `service/llm/LlamaCppChatModel.java` | 实现 `ChatLanguageModel`，调用 llama.cpp `/v1/chat/completions` |
| 新增 | `service/llm/LlamaCppEmbeddingModel.java` | 实现 `EmbeddingModel`，调用 `/v1/embeddings` |
| 新增 | `service/llm/CloudChatModel.java` | 实现 `ChatLanguageModel`，支持 OpenAI/Claude |
| 新增 | `service/llm/LlmFactory.java` | 工厂类，读取 `app.llm.provider` 创建对应实现 |
| 新增 | `service/llm/CloudLlmFactory.java` | 云端 LLM 工厂 |
| 新增 | `config/LangChain4jConfig.java` | `@Configuration`，配置 LangChain4j Bean |
| 删除 | `service/LLMService.java` | 旧实现，备份后移除 |

**关键实现 - LlamaCppChatModel 流式响应**：
```java
public class LlamaCppChatModel implements ChatLanguageModel {
    // generate() → 同步返回 AiMessage
    // generateStream(Prompt, StreamingResponseHandler) → handler.onToken() 回调
    // 解析 llama.cpp SSE 格式：data: {...}\n\n + data: [DONE]
}
```

**验证**：本地测试 llama.cpp 对话，确认与现有 LLMService 行为一致

---

### Phase 2: Docling Python 微服务 + Java 客户端

**目标**：统一所有文档解析为 Docling

**文件变更**：
| 操作 | 文件 | 说明 |
|------|------|------|
| 新增 | `docling-service/` | Python FastAPI 项目 |
| 新增 | `service/document/DoclingClient.java` | 调用 docling-api 的 HTTP 客户端 |
| 新增 | `service/document/DocumentConverter.java` | Docling 结果 → LangChain4j Document |
| 重构 | `service/FileParserService.java` | 改为委托给 DoclingClient |

**Docling 微服务 API 设计**：
```
POST /parse
Content-Type: multipart/form-data

file: [PDF/Word/Excel/PPT/图片/...]

Response:
{
  "document": {
    "text": "...",
    "tables": [...],
    "metadata": {
      "source": "filename.pdf",
      "page": 1,
      "images": [...]
    }
  },
  "chunks": [
    {"text": "...", "offset": 0, "length": 950, "metadata": {...}},
    ...
  ]
}
```

**验证**：上传各种格式文件，确认分块结果与现有逻辑一致（~1000字符/块）

---

### Phase 3: Milvus EmbeddingStore + Tenant 隔离

**目标**：用 LangChain4j Milvus 集成替换 MilvusDbService

**文件变更**：
| 操作 | 文件 | 说明 |
|------|------|------|
| 新增 | `service/vector/TenantAwareEmbeddingStore.java` | 实现 `EmbeddingStore<String>`，注入 tenant 前缀逻辑 |
| 新增 | `service/vector/EmbeddingStoreFactory.java` | 工厂类，按 `tenantId + kbId` 缓存 EmbeddingStore 实例 |
| 保留 | `service/MilvusDbService.java` | 作为 fallback，保留诊断能力 |
| 新增 | `config/MilvusConfig.java` | Milvus 连接配置 Bean |

**TenantAwareEmbeddingStore 核心逻辑**：
```java
public class TenantAwareEmbeddingStore implements EmbeddingStore<String> {
    // 1. add() / search() 时自动拼接 collection 名称
    // 2. collectionName = "t_" + tenantId + "__" + sha256(kbId).substring(0, 16)
    // 3. 内部委托给 MilvusEmbeddingStore.builder() 创建
    // 4. 缓存：ConcurrentHashMap<String, MilvusEmbeddingStore>
}
```

**验证**：新建知识库 → 上传文档 → 检索，确认 tenant 隔离正确

---

### Phase 4: Skills 系统 + Tool 映射

**目标**：构建可注册的 Skill 系统，暴露为 LangChain4j Tool

**文件变更**：
| 操作 | 文件 | 说明 |
|------|------|------|
| 新增 | `service/skill/Skill.java` | `@FunctionalInterface`，方法签名 `String execute(Map<String, Object> params)` |
| 新增 | `service/skill/SkillRegistry.java` | `Map<String, Skill>` + `@PostConstruct` 从 YAML 加载 |
| 新增 | `service/skill/impl/KnowledgeSearchSkill.java` | @Tool，委托给 TenantAwareEmbeddingStore |
| 新增 | `service/skill/impl/DocumentReadSkill.java` | @Tool，读取文档原文 |
| 新增 | `service/skill/impl/WebSearchSkill.java` | @Tool，调用搜索 API |
| 新增 | `service/skill/impl/CalculationSkill.java` | @Tool，数学计算 |
| 新增 | `service/skill/impl/CodeExecuteSkill.java` | @Tool，沙箱代码执行 |
| 新增 | `service/tool/SkillToToolAdapter.java` | Skill → LangChain4j @Tool 注解转换 |
| 新增 | `resources/skills/*.yaml` | Skill 定义（名称、描述、参数 schema） |

**Skill YAML 格式**：
```yaml
# knowledge_search.yaml
name: knowledge_search
description: 在知识库中搜索相关内容
parameters:
  - name: query
    type: string
    required: true
    description: 搜索查询文本
  - name: knowledge_base_id
    type: string
    required: false
    description: 指定知识库，未指定则搜索当前租户下所有
  - name: top_k
    type: integer
    required: false
    default: 5
```

**验证**：Agent 推理时能正确选择 Tool（观察 ReAct 推理日志）

---

### Phase 5: Agent 核心 + 流式响应

**目标**：构建 LangChain4j Agent，流式输出 SSE

**文件变更**：
| 操作 | 文件 | 说明 |
|------|------|------|
| 新增 | `service/agent/LangChain4jAgentService.java` | AiServices Agent，ReAct 模式 |
| 新增 | `service/agent/AgentStreamHandler.java` | `StreamingResponseHandler` 实现，推送 SSE |
| 新增 | `service/agent/SystemPromptBuilder.java` | 构建 System Prompt（含思维链指令） |
| 新增 | `service/memory/TenantAwareChatMemoryFactory.java` | 每个 tenant 一个 ChatMemory |
| 新增 | `service/memory/InMemoryChatMemoryStore.java` | 内存存储（可选 Redis 替换） |
| 重构 | `controller/ApiController.java` | 委托给 LangChain4jAgentService |

**Agent 流式输出适配**：
```java
// AgentStreamHandler 关键逻辑
class AgentStreamHandler implements StreamingResponseHandler {
    void onToken(String token) {
        // token 分类判断：
        // - 含 <tool_call> → SSE: {"type":"thinking","content":"..."}
        // - 含 <answer> → SSE: {"type":"answer","content":"..."}
        // - 推理过程 → SSE: {"type":"thinking","content":"..."}
    }
    void onComplete(AiMessage message) {
        // 推送 sources 元数据
        // 推送 done 信号
    }
}
```

**验证**：前端 AgentDrawer 收到完整 SSE 流（sources/thinking/answer/done）

---

### Phase 6: MCP + 外部平台接入

**目标**：MCP Server 暴露 Agent 能力，接入 QQ/飞书/微信

**文件变更**：
| 操作 | 文件 | 说明 |
|------|------|------|
| 新增 | `service/mcp/AgentMcpServer.java` | MCP Server，实现 JSON-RPC 2.0 |
| 新增 | `service/mcp/McpClientManager.java` | MCP Client，管理外部 MCP Server 连接 |
| 新增 | `service/mcp/McpToolBridge.java` | MCP Tool → LangChain4j Tool 桥接 |
| 新增 | `adapter/message/ChatMessage.java` | 统一消息模型 |
| 新增 | `adapter/message/MessageConverter.java` | 各平台消息格式转换 |
| 新增 | `adapter/qq/QQAdapter.java` | QQ 消息适配 |
| 新增 | `adapter/feishu/FeishuAdapter.java` | 飞书消息适配 |
| 新增 | `adapter/wechat/WechatAdapter.java` | 微信消息适配 |
| 新增 | `controller/QQWebhookController.java` | QQ Webhook 接收 |
| 新增 | `controller/FeishuWebhookController.java` | 飞书 Webhook 接收 |
| 新增 | `controller/WechatCallbackController.java` | 微信回调接收 |
| 新增 | `resources/mcp/server-config.yaml` | MCP Server 配置 |

**MCP Server 实现**（JSON-RPC 2.0 over HTTP）：
```
POST /mcp/rpc
{
  "jsonrpc": "2.0",
  "id": 1,
  "method": "tools/list",
  "params": {}
}

Response:
{
  "jsonrpc": "2.0",
  "id": 1,
  "result": {
    "tools": [
      {"name": "knowledge_search", "description": "...", "inputSchema": {...}},
      ...
    ]
  }
}
```

**外部平台消息流**：
```
QQ消息 → QQAdapter → ChatMessage → LangChain4jAgentService → 回复 → QQAdapter → QQ
```

**验证**：
- MCP Client 连接 Claude Desktop，观察 tools/list 返回
- QQ/飞书/微信分别发送消息，Agent 正常响应

---

## 六、工时与风险

| 阶段 | 工时 | 主要风险 |
|------|------|---------|
| Phase 1: LLM 适配器 | 3-5天 | llama.cpp SSE 解析兼容性 |
| Phase 2: Docling | 3-5天 | Python 服务部署 + 分块一致性 |
| Phase 3: Milvus EmbeddingStore | 2-3天 | LangChain4j Milvus v1.3.0 兼容性 |
| Phase 4: Skills + Tool | 4-6天 | ReAct 推理链调试复杂 |
| Phase 5: Agent + 流式 | 4-5天 | WebFlux 与 StreamingResponseHandler 集成 |
| Phase 6: MCP + 外部平台 | 5-8天 | 各平台 API 限制（尤其微信） |

**总计**：约 21-32 个工作日（4-6 周）

---

## 七、关键决策点（需确认）

1. **Docling Python vs 纯 Java**：推荐 Python 微服务方案（docling 官方生态），Java 仅做 HTTP 客户端。但需要额外部署 Python 服务。

2. **ChatMemory 持久化**：当前用内存（InMemoryChatMemory），后续可换 Redis。建议先内存快速上线。

3. **MCP 实现方式**：自己实现 JSON-RPC 2.0 还是用现成库（如 `wisp-mcp` Java 实现）？推荐自研（轻量控制）。

4. **QQ 接入方式**：go-cqhttp（反向 WebSocket）还是 QQ 官方机器人 API？推荐 go-cqhttp（支持更多特性）。

5. **代码废弃策略**：原 RagService/LLMService/MilvusDbService 是否直接删除还是保留备份逐步废弃？推荐备份后删除，避免技术债务。

---

## 八、验证方案

每个阶段完成后验证：

### Phase 1 验证
```bash
curl -X POST http://localhost:8080/api/chat
{"question": "你好", "knowledgeBaseId": "default"}
# 确认返回正常（不要求流式）

mvn test -Dtest=LlmFactoryTest
```

### Phase 2 验证
```bash
# 启动 docling-service
python docling-service/main.py &

# 上传不同格式文件，确认分块结果
curl -X POST http://localhost:8001/parse -F "file=@test.pdf"
```

### Phase 3 验证
```bash
# 新建知识库，上传文档
# 使用 Milvus Dashboard 观察 collection 命名：t_xxx__kb_xxxxxx
# 检索确认 tenant 隔离
```

### Phase 4 验证
```bash
# Agent 日志中观察 Tool 选择
# "Calling tool: knowledge_search" 等日志
```

### Phase 5 验证
```bash
# 前端 AgentDrawer 测试完整 SSE 流
# sources → thinking → answer → done 全链路
```

### Phase 6 验证
```bash
# MCP: npx @anthropic/mcp-server-cli 测试 tools/list
# QQ: 发送消息测试群聊/私聊
# 飞书: 发送消息测试卡片消息
# 微信: 发送消息测试被动回复
```