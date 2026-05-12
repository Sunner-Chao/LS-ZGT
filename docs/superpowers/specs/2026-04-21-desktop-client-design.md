# 筑规通桌面客户端设计文档

> 基于 LS-ZGT Web 应用，设计支持 Linux 和 Windows 的跨平台桌面客户端

## 1. 项目概述

### 1.1 背景

LS-ZGT (筑规通) 是一个基于 RAG 技术的建筑行业规范智能问答系统，当前为 Web 应用架构：
- 前端：Vue 3 + TypeScript + Element Plus
- 后端：Spring Boot 3 + Java 17
- AI：**Docker 内 Llama.cpp 服务** → 宿主机模型                       │
│     对话：Qwen3.5B-VLM-GGUF | 嵌入：nomic-embed-text-v2-moe-gguf
- 向量数据库：Milvus

### 1.2 目标

开发一个跨平台桌面客户端应用，具备以下特点：
- 支持 Linux 和 Windows 双平台
- 完全离线可用（本地模式）
- 支持云端 API（联网模式）
- 无需用户安装 Docker 或其他依赖
- 安装包精简，适合普通办公电脑

### 1.3 设计决策

| 维度 | 决策 | 理由 |
|------|------|------|
| 架构方式 | 完全原生重写 | 性能最优，体验原生 |
| 开发框架 | Flutter | 现代 UI、开发效率高、跨平台成熟 |
| 后端服务 | Rust | 性能优秀、内存安全、部署简单 |
| 本地 LLM | llama.cpp | 嵌入式推理、无独立进程、资源占用低 |
| 云端 API | Anthropic 格式为主 + OpenAI 兼容 | Claude/DeepSeek 主用 Anthropic 格式，其他服务兼容 OpenAI |
| 向量数据库 | Milvus | 延续现有架构、功能强大 |
| 文档解析 | Rust 原生重写 | 部署简单、性能好 |
| 目标硬件 | 普通办公电脑 (16GB 内存) | 覆盖主流用户群体 |

---

## 2. 系统架构

### 2.1 整体架构

```
┌──────────────────────────────────────────────────────────────────┐
│                        用户界面层 (Flutter)                       │
│  ┌────────────┐ ┌────────────┐ ┌────────────┐ ┌────────────┐    │
│  │  智能对话   │ │  知识库管理 │ │  AI 配置   │ │  系统设置   │    │
│  └────────────┘ └────────────┘ └────────────┘ └────────────┘    │
└──────────────────────────────────────────────────────────────────┘
                              │
                              │ FFI (flutter_rust_bridge)
                              ▼
┌──────────────────────────────────────────────────────────────────┐
│                      核心服务层 (Rust)                           │
│  ┌─────────────────────────────────────────────────────────────┐ │
│  │                      API 网关模块                            │ │
│  └─────────────────────────────────────────────────────────────┘ │
│  ┌───────────────┐ ┌───────────────┐ ┌───────────────┐          │
│  │   RAG 引擎    │ │  文档解析器   │ │  向量管理器   │          │
│  └───────────────┘ └───────────────┘ └───────────────┘          │
│  ┌───────────────┐ ┌───────────────┐ ┌───────────────┐          │
│  │  LLM 适配器   │ │  嵌入模型     │ │  配置管理器   │          │
│  └───────────────┘ └───────────────┘ └───────────────┘          │
└──────────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌──────────────────────────────────────────────────────────────────┐
│                      基础设施层                                  │
│  ┌──────────┐ ┌──────────┐ ┌──────────┐ ┌──────────┐           │
│  │ Milvus   │ │ llama.cpp│ │ 云端API  │ │ 本地存储  │           │
│  │ (向量库)  │ │ (本地LLM) │ │(Anthropic)│ │ (SQLite) │           │
│  └──────────┘ └──────────┘ └──────────┘ └──────────┘           │
└──────────────────────────────────────────────────────────────────┘
```

### 2.2 目录结构

```
zhu-gui-tong-desktop/
├── app/                          # Flutter 应用
│   ├── lib/
│   │   ├── main.dart
│   │   ├── app.dart
│   │   ├── features/             # 功能模块
│   │   │   ├── chat/             # 智能对话
│   │   │   ├── knowledge/        # 知识库管理
│   │   │   ├── settings/         # AI 配置
│   │   │   └── profile/          # 用户中心
│   │   ├── shared/               # 共享组件
│   │   │   ├── widgets/
│   │   │   ├── theme/
│   │   │   └── utils/
│   │   └── bridge/               # Rust FFI 桥接
│   ├── pubspec.yaml
│   └── ...
│
├── core/                         # Rust 核心服务
│   ├── src/
│   │   ├── lib.rs
│   │   ├── api/                  # API 网关
│   │   ├── rag/                  # RAG 引擎
│   │   ├── parser/               # 文档解析
│   │   │   ├── pdf.rs
│   │   │   ├── cad.rs
│   │   │   └── bim.rs
│   │   ├── llm/                  # LLM 适配器
│   │   │   ├── local.rs          # llama.cpp
│   │   │   ├── anthropic.rs      # Anthropic 格式
│   │   │   └── openai.rs         # OpenAI 格式
│   │   ├── embedding/            # 嵌入模型
│   │   ├── vector/               # 向量管理
│   │   ├── storage/              # 数据存储
│   │   └── config/               # 配置管理
│   ├── Cargo.toml
│   └── ...
│
├── native/                       # 原生依赖
│   ├── milvus/                   # Milvus 嵌入式
│   └── llama/                    # llama.cpp 库
│
├── docs/                         # 文档
├── scripts/                      # 构建脚本
└── README.md
```

---

## 3. Flutter UI 层设计

### 3.1 页面结构

主窗口采用左侧导航 + 右侧内容区的经典布局：

- **标题栏**：自定义窗口控制按钮（最小化、最大化、关闭）
- **侧边栏**：可折叠的导航菜单
- **内容区**：各功能模块页面
- **状态栏**：显示模型状态、文档数量、系统状态

### 3.2 功能模块

#### 3.2.1 智能对话模块

核心功能：
- 多轮对话上下文管理
- 流式输出（打字机效果）
- 答案来源追溯（显示参考文档）
- 对话历史管理（搜索、删除、导出）
- 附件上传（关联文档提问）
- 模型切换

#### 3.2.2 知识库管理模块

核心功能：
- 文档上传（拖拽/选择，支持批量）
- 分类管理（创建、编辑、删除）
- 文档解析状态跟踪（进度显示）
- 文档预览
- 批量操作（删除、移动、重新索引）
- 存储空间管理

#### 3.2.3 AI 配置模块

核心功能：
- 运行模式切换（本地/云端/混合）
- 本地模型管理（下载、删除、切换）
- 云端 API 配置（服务商选择、API Key 管理）
- 硬件加速配置（GPU 层数）
- 生成参数调优（温度、Top-P、最大长度）
- RAG 参数配置（检索数量、相似度阈值）

#### 3.2.4 系统设置模块

核心功能：
- 外观设置（主题、字体大小）
- 快捷键配置
- 数据目录设置
- 日志查看
- 关于信息

### 3.3 状态管理

使用 Riverpod 进行状态管理：

```dart
// 全局状态 Providers
├── authProvider          // 用户认证状态
├── chatProvider          // 对话状态
│   ├── messages          // 消息列表
│   ├── currentSession    // 当前会话
│   └── history           // 历史记录
├── knowledgeProvider     // 知识库状态
│   ├── documents         // 文档列表
│   ├── categories        // 分类
│   └── uploadProgress    // 上传进度
├── settingsProvider      // 配置状态
│   ├── modelConfig       // 模型配置
│   ├── ragConfig         // RAG 配置
│   └── uiConfig          // UI 配置
└── systemProvider        // 系统状态
    ├── serviceStatus     // 服务状态
    ├── storageInfo       // 存储信息
    └── resourceUsage     // 资源使用
```

---

## 4. Rust 核心服务设计

### 4.1 FFI 桥接层

使用 `flutter_rust_bridge v2` 实现 Flutter 与 Rust 通信：

```rust
// 核心导出函数

/// 初始化核心服务
pub fn init_services(config: ServiceConfig) -> Result<(), ServiceError>;

/// 发送聊天消息（流式返回）
pub fn chat_send(
    message: String,
    session_id: Option<String>,
) -> impl Stream<Item = ChatChunk>;

/// 上传文档并解析
pub async fn upload_document(
    file_path: String,
    category: Option<String>,
) -> Result<Document, ParseError>;

/// 搜索知识库
pub fn search_knowledge(
    query: String,
    limit: i32,
    threshold: f32,
) -> Result<Vec<SearchResult>, SearchError>;

/// 获取服务状态
pub fn get_service_status() -> ServiceStatus;

/// 切换模型模式
pub fn switch_model_mode(mode: ModelMode) -> Result<(), ConfigError>;
```

### 4.2 RAG 引擎

```
RAG 处理流程:

用户问题 → 问题预处理 → 向量嵌入 → 向量检索 → 上下文构建 → LLM 生成 → 流式响应
```

核心组件：
- **问题预处理**：去噪、规范化、意图识别
- **向量嵌入**：调用本地嵌入模型 (BGE-M3)
- **向量检索**：Milvus 相似度搜索
- **上下文构建**：拼接相关文档片段，构建 prompt
- **LLM 生成**：本地 llama.cpp 或云端 API

### 4.3 文档解析器

使用 Rust 原生库重写：

| 文件类型 | 解析库 | 说明 |
|----------|--------|------|
| PDF | lopdf + pdf-extract | 文字提取 + OCR 回退 |
| CAD (DXF) | dxf-rs | 提取标注、图层信息 |
| BIM (IFC) | ifc-rs | 提取构件、属性信息 |

解析流程：
1. 读取文件结构
2. 提取文本/标注/属性
3. 智能分块（按段落/章节）
4. 生成嵌入向量
5. 存入 Milvus

### 4.4 LLM 适配器

#### 4.4.1 本地 LLM (llama.cpp)

```rust
pub struct LocalLlmEngine {
    model: Option<LlamaModel>,
    model_path: PathBuf,
    params: LlmParams,
    gpu_layers: i32,
}

impl LocalLlmEngine {
    /// 加载 GGUF 模型
    pub async fn load_model(&mut self, model_path: &Path) -> Result<(), LlmError>;
    
    /// 流式生成
    pub async fn generate_stream(
        &self,
        prompt: &str,
        config: &GenerationConfig,
    ) -> impl Stream<Item = Result<String, LlmError>>;
}
```

特点：
- 嵌入式推理，无独立进程
- 支持 GPU 加速 (CUDA/Metal)
- 支持量化模型 (Q4_K_M, Q5_K_M 等)
- 内存映射加载，启动快速

#### 4.4.2 云端 API

支持两种格式：

**Anthropic 格式 (主要)**：
- Claude API (官方)
- DeepSeek API (兼容)

**OpenAI 格式 (兼容)**：
- OpenAI API (官方)
- 通义千问 (兼容)
- 月之暗面 (兼容)
- 智谱 AI (兼容)
- 自定义服务

```rust
pub enum CloudProvider {
    // Anthropic 格式
    Anthropic,
    DeepSeek,
    
    // OpenAI 格式
    OpenAI,
    Qwen,
    Moonshot,
    Zhipu,
    
    // 自定义
    CustomAnthropic,
    CustomOpenAI,
}
```

### 4.5 向量管理 (Milvus)

```rust
pub struct MilvusClient {
    client: milvus::Client,
    collection_name: String,
}

impl MilvusClient {
    /// 创建集合
    pub async fn create_collection(&self, dimension: usize) -> Result<(), MilvusError>;
    
    /// 插入向量
    pub async fn insert(&self, chunks: Vec<DocumentChunk>, embeddings: Vec<Vec<f32>>) -> Result<(), MilvusError>;
    
    /// 相似度搜索
    pub async fn search(&self, query_embedding: Vec<f32>, top_k: i32, threshold: f32) -> Result<Vec<SearchResult>, MilvusError>;
}
```

### 4.6 本地存储 (SQLite)

数据表：
- `config`: 用户配置
- `sessions`: 对话会话
- `messages`: 对话消息
- `documents`: 知识库文档
- `models`: 模型管理
- `logs`: 操作日志

---

## 5. 系统集成

### 5.1 系统托盘

功能：
- 显示/隐藏主窗口
- 快速提问入口
- 模型状态显示
- 开机自启动设置
- 退出应用

### 5.2 全局快捷键

- `Ctrl+Shift+Z`: 呼出主窗口
- `Ctrl+Shift+Q`: 快速提问

### 5.3 通知系统

- 文档解析完成通知
- 模型下载完成通知
- 错误通知

### 5.4 自动更新

- 检查更新
- 下载更新包
- 安装更新

---

## 6. 部署设计

### 6.1 安装包

| 平台 | 格式 | 大小 |
|------|------|------|
| Linux | .deb / .rpm / .AppImage | ~80-85MB |
| Windows | .exe (NSIS) / .msi | ~85-90MB |

### 6.2 数据目录

**Linux**:
```
~/.local/share/zhu-gui-tong/
├── config/          # 配置文件
├── data/            # SQLite 数据库
├── models/          # 模型文件
├── knowledge/       # 知识库文档
├── logs/            # 日志
├── cache/           # 缓存
└── milvus/          # Milvus 数据
```

**Windows**:
```
C:\Users\<User>\AppData\Local\ZhuGuiTong\
├── config/
├── data/
├── models/
├── knowledge/
├── logs/
├── cache/
└── milvus/
```

### 6.3 安装向导

步骤：
1. 欢迎页面
2. 选择安装位置
3. 附加选项（桌面快捷方式、开机启动等）
4. 安装进度
5. 完成页面

---

## 7. 技术栈总结

| 层级 | 技术 | 版本 |
|------|------|------|
| UI 框架 | Flutter | 3.22+ |
| 状态管理 | Riverpod | 2.x |
| 核心服务 | Rust | 1.78+ |
| 异步运行时 | Tokio | 1.x |
| FFI 桥接 | flutter_rust_bridge | 2.x |
| 本地 LLM | llama.cpp | latest |
| 嵌入模型 | Candle (BGE-M3) | - |
| 向量数据库 | Milvus | 2.4+ |
| 本地存储 | SQLite (rusqlite) | - |
| HTTP 客户端 | reqwest | 0.12+ |
| 序列化 | serde | 1.x |

---

## 8. 开发计划建议

### 阶段 1: 基础框架 (2周)
- Flutter 项目搭建
- Rust 核心服务框架
- FFI 桥接层
- 基础 UI 布局

### 阶段 2: 核心功能 (4周)
- RAG 引擎实现
- 本地 LLM 集成 (llama.cpp)
- 嵌入模型集成
- Milvus 集成
- 对话功能

### 阶段 3: 文档处理 (2周)
- PDF 解析器
- CAD 解析器
- BIM 解析器
- 知识库管理

### 阶段 4: 云端 API (1周)
- Anthropic 格式客户端
- OpenAI 格式客户端
- API 配置界面

### 阶段 5: 系统集成 (1周)
- 系统托盘
- 全局快捷键
- 通知系统
- 自动更新

### 阶段 6: 打包发布 (1周)
- Linux 打包
- Windows 打包
- 安装向导
- 测试与修复

---

## 9. 风险与缓解

| 风险 | 影响 | 缓解措施 |
|------|------|----------|
| llama.cpp 集成复杂 | 高 | 使用成熟的 Rust binding (llama-cpp-rs) |
| Milvus 嵌入式部署 | 中 | 使用 Milvus Lite 或独立进程管理 |
| CAD/BIM 解析库不成熟 | 中 | 优先 PDF，CAD/BIM 后续迭代 |
| 跨平台 UI 一致性 | 低 | Flutter 跨平台特性良好 |
| 模型下载慢 | 中 | 提供国内镜像源 |

---

## 10. 商业计划与 Token 计费系统

### 10.1 计费模式

采用 **混合计费模式**：
- 本地模型：免费基础额度 + 超出付费
- 云端 API：按实际用量计费

### 10.2 套餐方案

| 套餐 | 价格 | 本地额度 | 云端额度 | 功能权限 |
|------|------|----------|----------|----------|
| 免费版 Free | ¥0/月 | 10万/月 | 5万/月 | 基础对话、3个知识库 |
| 基础版 Basic | ¥29/月 | 100万/月 | 50万/月 | 全功能、10个知识库、API调用、微信接入 |
| 专业版 Pro | ¥99/月 | 不限量 | 200万/月 | 全功能、不限知识库、高级API、全部接入 |
| 企业版 Enterprise | 联系销售 | 不限量 | 不限量 | 私有部署、专属支持、定制开发 |
| 按量付费 PayAsYouGo | ¥0.01/千token | - | 按需 | 无月费，按实际用量 |

### 10.3 核心实现

```rust
// core/src/billing/mod.rs

/// 套餐信息
pub struct Plan {
    pub id: String,
    pub name: String,
    pub price_monthly: f64,
    pub local_quota: Option<u64>,      // None = 不限量
    pub cloud_quota: Option<u64>,
    pub features: FeatureSet,
    pub api_calls_limit: Option<u64>,
}

/// 用量统计
pub struct UsageTracker {
    pub local_usage: TokenUsage,
    pub cloud_usage: TokenUsage,
    pub api_calls: u64,
}

impl UsageTracker {
    /// 检查是否有足够额度
    pub fn check_quota(&self, mode: LlmMode, estimated_tokens: u64) -> QuotaResult;
    
    /// 扣减额度
    pub fn deduct(&mut self, mode: LlmMode, usage: TokenUsage);
}
```

### 10.4 功能权限

```rust
pub struct FeatureSet {
    pub max_knowledge_bases: Option<u32>,    // None = 不限
    pub api_access: bool,
    pub wechat_integration: bool,
    pub feishu_integration: bool,
    pub mcp_server: bool,
    pub custom_tools: bool,
    pub script_tools: bool,
    pub priority_support: bool,
}
```

---

## 11. 开放 API 与第三方接入

### 11.1 API 架构

桌面客户端内置 HTTP API 服务，支持第三方应用接入：

```
┌─────────────────────────────────────────────────────────────────┐
│              内嵌 HTTP API 服务 (localhost:port)                │
│                                                                 │
│  RESTful API:                                                   │
│  • POST /v1/chat/completions    对话接口 (OpenAI 兼容)          │
│  • POST /v1/embeddings          嵌入接口                        │
│  • POST /v1/knowledge/upload    知识库上传                      │
│  • GET  /v1/knowledge/search    知识库检索                      │
│  • GET  /v1/models              模型列表                        │
│  • GET  /v1/usage               用量统计                        │
│                                                                 │
│  WebSocket API:                                                 │
│  • ws://localhost:port/ws/chat   实时对话                       │
│  • ws://localhost:port/ws/events 事件订阅                       │
│                                                                 │
│  Webhook 回调:                                                  │
│  • 配置事件通知推送到外部 URL                                   │
└─────────────────────────────────────────────────────────────────┘
```

### 11.2 API Key 管理

```rust
pub struct ApiKey {
    pub id: String,
    pub name: String,
    pub key: String,           // sk-xxx 格式
    pub scopes: Vec<ApiScope>,
    pub rate_limit: RateLimit,
    pub quota_limit: Option<QuotaLimit>,
    pub ip_whitelist: Option<Vec<String>>,
    pub created_at: i64,
    pub expires_at: Option<i64>,
}

pub enum ApiScope {
    Chat,           // 对话
    Embedding,      // 嵌入
    KnowledgeRead,  // 知识库读取
    KnowledgeWrite, // 知识库写入
    Admin,          // 管理权限
}
```

### 11.3 支持的第三方平台

| 平台 | 接入方式 | 功能 |
|------|----------|------|
| 微信公众号 | Webhook | 消息自动回复、菜单触发 |
| 飞书机器人 | Webhook | 消息卡片、事件订阅 |
| 钉钉机器人 | Webhook | 消息推送、互动卡片 |
| 企业微信 | Webhook | 应用消息、群机器人 |
| Slack | Webhook | Slash Commands、事件订阅 |
| Telegram | Webhook | Bot API |
| 自定义 | API/Webhook | 灵活配置 |

### 11.4 Webhook 事件系统

```rust
pub enum WebhookEvent {
    // 对话事件
    ChatStarted,
    ChatCompleted,
    
    // 知识库事件
    DocumentUploaded,
    DocumentProcessed,
    DocumentDeleted,
    
    // 用量事件
    QuotaWarning,    // 配额预警
    QuotaExceeded,   // 配额超限
    
    // 系统事件
    ApiKeyCreated,
    ApiKeyExpired,
}
```

---

## 12. MCP 集成

### 12.1 双模式架构

桌面客户端同时支持 MCP Client 和 MCP Server 模式：

```
┌─────────────────────────────────────────────────────────────────┐
│  MCP Client 模式 (连接外部服务)                                 │
│                                                                 │
│  筑规通客户端 ──连接──▶ 文件系统 MCP、Web搜索 MCP、GitHub MCP...│
│                                                                 │
│  功能：发现工具、调用工具、获取资源                              │
├─────────────────────────────────────────────────────────────────┤
│  MCP Server 模式 (对外暴露服务)                                 │
│                                                                 │
│  Claude Desktop、其他AI客户端 ──连接──▶ 筑规通客户端           │
│                                                                 │
│  暴露能力：知识库搜索、文档上传、AI问答、文档解析                │
└─────────────────────────────────────────────────────────────────┘
```

### 12.2 核心实现

```rust
// MCP Client
pub struct McpClient {
    connections: HashMap<String, McpConnection>,
    tool_cache: HashMap<String, Vec<McpTool>>,
}

impl McpClient {
    /// 连接到 MCP Server (Stdio/WebSocket/HTTP)
    pub async fn connect_stdio(&mut self, name: &str, command: &str, args: Vec<&str>) -> Result<(), McpError>;
    pub async fn connect_websocket(&mut self, name: &str, url: &str) -> Result<(), McpError>;
    
    /// 列出所有可用工具
    pub async fn list_tools(&mut self) -> Result<Vec<McpTool>, McpError>;
    
    /// 调用工具
    pub async fn call_tool(&self, server_name: &str, tool_name: &str, arguments: serde_json::Value) -> Result<McpToolResult, McpError>;
}

// MCP Server
pub struct McpServer {
    tools: Vec<McpTool>,
    resources: Vec<McpResource>,
    tool_handlers: HashMap<String, Box<dyn ToolHandler>>,
}

impl McpServer {
    /// 注册工具
    pub fn register_tool<H: ToolHandler + 'static>(&mut self, tool: McpTool, handler: H);
    
    /// 注册资源
    pub fn register_resource(&mut self, resource: McpResource);
    
    /// 启动服务器
    pub async fn start(&self) -> Result<(), McpError>;
}
```

### 12.3 内置暴露工具

作为 MCP Server 时，默认暴露以下工具：

| 工具名 | 描述 |
|--------|------|
| `knowledge_search` | 搜索知识库获取相关文档 |
| `knowledge_upload` | 上传文档到知识库 |
| `ask_question` | 向 AI 助手提问建筑规范相关问题 |
| `parse_document` | 解析文档（PDF/CAD/BIM） |

---

## 13. Skills 系统

### 13.1 双格式支持

系统支持两种 Skills 格式：

**Claude Skills 格式（完整功能）**：
- Markdown + YAML frontmatter
- 触发条件定义（关键词/正则/斜杠命令）
- 依赖工具声明
- 完整的 prompt 工程

**简化格式（快速创建）**：
- 纯模板 + 参数占位符
- 快速创建，易于上手

### 13.2 Skill 定义

```rust
pub struct Skill {
    pub id: String,
    pub name: String,
    pub description: String,
    pub format: SkillFormat,          // Claude / Simple
    pub triggers: Option<Vec<Trigger>>,
    pub params: Option<Vec<ParamDef>>,
    pub dependencies: Option<Vec<String>>,
    pub template: String,
    pub metadata: SkillMetadata,
}

pub enum Trigger {
    Keywords { keywords: Vec<String> },
    Pattern { pattern: String },
    Regex { regex: String },
    Command { command: String },      // 斜杠命令
}
```

### 13.3 内置 Skills

| Skill | 描述 | 触发方式 |
|-------|------|----------|
| 规范查询 | 查询建筑规范条文 | 关键词：规范、条文、GB、JGJ |
| 条文检索 | 精确检索规范条文 | 关键词：检索、查找 |
| CAD解读 | 解读CAD图纸内容 | 关键词：CAD、图纸 |
| 表格分析 | 分析表格数据 | 关键词：表格、分析 |
| 计算助手 | 工程计算 | 关键词：计算 |
| 报告生成 | 生成专业报告 | 斜杠命令：/report |

### 13.4 Skills 管理器

```rust
pub struct SkillManager {
    skills: HashMap<String, Skill>,
    skill_dir: PathBuf,
}

impl SkillManager {
    /// 加载所有 Skills
    pub fn load_skills(&mut self) -> Result<(), SkillError>;
    
    /// 匹配触发的 Skills
    pub fn match_skills(&self, input: &str) -> Vec<&Skill>;
    
    /// 执行 Skill
    pub fn execute(&self, skill_id: &str, params: HashMap<String, serde_json::Value>, context: &SkillContext) -> Result<SkillResult, SkillError>;
}
```

---

## 14. Function Calling / 工具系统

### 14.1 工具类型

系统支持三类工具：

| 类型 | 说明 | 示例 |
|------|------|------|
| 内置工具 | 预定义工具，开箱即用 | 知识检索、计算器、代码执行、文件操作 |
| 自定义工具 | 用户配置的 API 调用 | 天气查询、数据库查询、内部服务 |
| 脚本工具 | 用户编写的脚本 | Python 数据处理、Shell 系统操作 |

### 14.2 工具定义

```rust
pub struct Tool {
    pub id: String,
    pub name: String,
    pub display_name: String,
    pub description: String,
    pub tool_type: ToolType,           // BuiltIn / Custom / Script
    pub input_schema: serde_json::Value,
    pub output_schema: Option<serde_json::Value>,
    pub requires_confirmation: bool,
    pub danger_level: DangerLevel,     // Safe / Moderate / Dangerous
    pub timeout_ms: u64,
    pub enabled: bool,
}

pub enum DangerLevel {
    Safe,         // 安全，无需确认
    Moderate,     // 中等风险，可选确认
    Dangerous,    // 高风险，强制确认
}
```

### 14.3 内置工具集

| 工具 | 描述 | 危险级别 |
|------|------|----------|
| `knowledge_search` | 知识库检索 | Safe |
| `knowledge_upload` | 文档上传 | Safe |
| `document_parse` | 文档解析 | Safe |
| `calculator` | 数学计算 | Safe |
| `unit_converter` | 单位转换 | Safe |
| `formula` | 公式计算 | Safe |
| `web_search` | 网络搜索 | Safe |
| `web_fetch` | 网页抓取 | Safe |
| `code_execute` | 代码执行 | Moderate |
| `file_read` | 文件读取 | Moderate |
| `file_write` | 文件写入 | Dangerous |

### 14.4 自定义工具配置

```rust
pub struct CustomToolConfig {
    pub name: String,
    pub description: String,
    pub input_schema: serde_json::Value,
    pub api_config: ApiConfig,
    pub result_path: Option<String>,   // JSONPath 提取
}

pub struct ApiConfig {
    pub method: HttpMethod,            // GET/POST/PUT/DELETE
    pub url_template: String,          // 支持变量替换
    pub headers: HashMap<String, String>,
    pub body_template: Option<String>,
    pub auth: Option<AuthConfig>,
    pub timeout_ms: u64,
}
```

### 14.5 脚本工具配置

```rust
pub struct ScriptToolConfig {
    pub name: String,
    pub description: String,
    pub input_schema: serde_json::Value,
    pub language: ScriptLanguage,      // Python / Shell / NodeJs
    pub script: String,
    pub timeout_ms: u64,
    pub env: HashMap<String, String>,
    pub working_dir: Option<String>,
}
```

### 14.6 工具管理器

```rust
pub struct ToolManager {
    tools: HashMap<String, Box<dyn ToolExecutor>>,
    validator: SchemaValidator,
}

impl ToolManager {
    /// 注册工具
    pub fn register<E: ToolExecutor + 'static>(&mut self, executor: E);
    
    /// 列出所有工具 (供 LLM 选择)
    pub fn list_tools(&self) -> Vec<ToolDefinition>;
    
    /// 执行工具调用
    pub async fn call(&self, request: ToolCallRequest) -> Result<ToolCallResult, ToolError>;
}
```

### 14.7 Function Calling 流程

```rust
impl LlmAdapter {
    /// 处理 Function Calling
    pub async fn process_with_tools(
        &self,
        messages: Vec<Message>,
        tools: &[Tool],
        tool_manager: &ToolManager,
    ) -> Result<ChatResponse, LlmError> {
        // 循环处理，直到 LLM 不再调用工具
        // 1. 发送请求（包含工具定义）
        // 2. 检查是否需要调用工具
        // 3. 执行工具调用
        // 4. 将结果返回给 LLM
        // 5. 重复直到获得最终响应
    }
}
```

---

## 15. 更新后的目录结构

```
zhu-gui-tong-desktop/
├── app/                          # Flutter 应用
│   ├── lib/
│   │   ├── main.dart
│   │   ├── app.dart
│   │   ├── features/             # 功能模块
│   │   │   ├── chat/             # 智能对话
│   │   │   ├── knowledge/        # 知识库管理
│   │   │   ├── settings/         # AI 配置
│   │   │   ├── billing/          # 账户与计费
│   │   │   ├── integrations/     # 第三方接入
│   │   │   ├── mcp/              # MCP 配置
│   │   │   ├── skills/           # Skills 管理
│   │   │   ├── tools/            # 工具管理
│   │   │   └── profile/          # 用户中心
│   │   ├── shared/               # 共享组件
│   │   └── bridge/               # Rust FFI 桥接
│   └── ...
│
├── core/                         # Rust 核心服务
│   ├── src/
│   │   ├── lib.rs
│   │   ├── api/                  # API 网关
│   │   ├── rag/                  # RAG 引擎
│   │   ├── parser/               # 文档解析
│   │   ├── llm/                  # LLM 适配器
│   │   ├── embedding/            # 嵌入模型
│   │   ├── vector/               # 向量管理
│   │   ├── storage/              # 数据存储
│   │   ├── config/               # 配置管理
│   │   ├── billing/              # 计费系统 ⭐ 新增
│   │   ├── integrations/         # 第三方接入 ⭐ 新增
│   │   │   ├── wechat.rs
│   │   │   ├── feishu.rs
│   │   │   ├── dingtalk.rs
│   │   │   └── webhook.rs
│   │   ├── mcp/                  # MCP 集成 ⭐ 新增
│   │   │   ├── mod.rs
│   │   │   ├── client.rs
│   │   │   └── server.rs
│   │   ├── skills/               # Skills 系统 ⭐ 新增
│   │   │   ├── mod.rs
│   │   │   ├── parser.rs
│   │   │   └── executor.rs
│   │   └── tools/                # 工具系统 ⭐ 新增
│   │       ├── mod.rs
│   │       ├── builtin/
│   │       ├── custom/
│   │       └── script/
│   └── ...
│
├── skills/                       # Skills 文件目录 ⭐ 新增
│   ├── builtin/
│   │   ├── building-code-query.md
│   │   ├── cad-analysis.md
│   │   └── ...
│   └── custom/
│
├── native/                       # 原生依赖
├── docs/                         # 文档
├── scripts/                      # 构建脚本
└── README.md
```

---

## 16. 更新后的开发计划

### 阶段 1: 基础框架 (2周)
- Flutter 项目搭建
- Rust 核心服务框架
- FFI 桥接层
- 基础 UI 布局

### 阶段 2: 核心功能 (4周)
- RAG 引擎实现
- 本地 LLM 集成 (llama.cpp)
- 嵌入模型集成
- Milvus 集成
- 对话功能

### 阶段 3: 文档处理 (2周)
- PDF 解析器
- CAD 解析器
- BIM 解析器
- 知识库管理

### 阶段 4: 云端 API (1周)
- Anthropic 格式客户端
- OpenAI 格式客户端
- API 配置界面

### 阶段 5: 商业化功能 (2周) ⭐ 新增
- Token 计费系统
- 套餐管理
- 用量统计
- 支付集成

### 阶段 6: 开放能力 (2周) ⭐ 新增
- 内嵌 API 服务
- API Key 管理
- 微信/飞书接入
- Webhook 系统

### 阶段 7: AI 能力扩展 (2周) ⭐ 新增
- MCP Client 实现
- MCP Server 实现
- Skills 系统
- Function Calling 集成

### 阶段 8: 工具系统 (1周) ⭐ 新增
- 内置工具集
- 自定义工具配置
- 脚本工具支持

### 阶段 9: 系统集成 (1周)
- 系统托盘
- 全局快捷键
- 通知系统
- 自动更新

### 阶段 10: 打包发布 (1周)
- Linux 打包
- Windows 打包
- 安装向导
- 测试与修复

---

## 17. 更新后的风险与缓解

| 风险 | 影响 | 缓解措施 |
|------|------|----------|
| llama.cpp 集成复杂 | 高 | 使用成熟的 Rust binding (llama-cpp-rs) |
| Milvus 嵌入式部署 | 中 | 使用 Milvus Lite 或独立进程管理 |
| CAD/BIM 解析库不成熟 | 中 | 优先 PDF，CAD/BIM 后续迭代 |
| 跨平台 UI 一致性 | 低 | Flutter 跨平台特性良好 |
| 模型下载慢 | 中 | 提供国内镜像源 |
| 支付集成复杂 | 中 | 先实现基础套餐，支付后续迭代 |
| 第三方平台 API 变更 | 中 | 抽象适配层，便于更新 |
| MCP 协议演进 | 低 | 跟进官方规范，版本兼容 |

---

**文档版本**: 2.0
**创建日期**: 2026-04-21
**更新日期**: 2026-04-21
**作者**: Claude AI
