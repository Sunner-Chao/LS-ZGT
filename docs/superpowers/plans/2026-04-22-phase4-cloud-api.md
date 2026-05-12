# 筑规通桌面客户端 - 阶段4：云端 API 实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development

**Goal:** 实现云端 API 集成、API 网关、配置管理界面。

**Architecture:** 本地服务 → API 网关 → 云端 LLM/Embedding 服务

---

## Task 1: API 配置管理

**Files:**
- Create: `core/src/api/config.rs`
- Modify: `core/src/config/settings.rs`

- [ ] **Step 1: 创建 API 配置模块**

```rust
// core/src/api/config.rs

/// API 配置管理器
pub struct ApiConfigManager {
 configs: HashMap<String, ProviderConfig>,
 active_provider: String,
}

impl ApiConfigManager {
 pub fn new() -> Self;
 pub fn add_provider(&mut self, name: String, config: ProviderConfig);
 pub fn remove_provider(&mut self, name: &str);
 pub fn get_provider(&self, name: &str) -> Option<&ProviderConfig>;
 pub fn set_active(&mut self, name: &str);
 pub fn list_providers(&self) -> Vec<&ProviderConfig>;
}

/// 提供商配置
pub struct ProviderConfig {
 pub name: String,
 pub provider_type: ProviderType,
 pub api_key: SecretString,
 pub base_url: String,
 pub model: String,
 pub max_tokens: Option<u32>,
 pub timeout_secs: u64,
}

/// 提供商类型
pub enum ProviderType {
 Anthropic,
 DeepSeek,
 OpenAI,
 Qwen,
 Moonshot,
 Zhipu,
 CustomAnthropic,
 CustomOpenAI,
}
```

- [ ] **Step 2: 更新 settings.rs**

```rust
// 添加到 settings.rs

/// API 配置
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ApiSettings {
 pub providers: Vec<ProviderSetting>,
 pub active_provider: String,
 pub default_timeout: u64,
}

/// 提供商设置
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ProviderSetting {
 pub name: String,
 pub provider_type: String,
 pub api_key: String,
 pub base_url: String,
 pub model: String,
 pub max_tokens: Option<u32>,
}
```

- [ ] **Step 3: 提交**

```bash
git add core/src/api/
git commit -m "feat(api): add API configuration management"
```

---

## Task 2: API 网关

**Files:**
- Create: `core/src/api/gateway.rs`
- Create: `core/src/api/mod.rs`

- [ ] **Step 1: 创建 API 网关**

```rust
// core/src/api/gateway.rs

use crate::llm::LlmAdapter;
use crate::embedding::EmbeddingModel;

/// API 网关
pub struct ApiGateway {
 llm_adapter: Arc<dyn LlmAdapter>,
 embedding_model: Arc<dyn EmbeddingModel>,
 config_manager: ApiConfigManager,
}

impl ApiGateway {
 pub fn new(
 llm_adapter: Arc<dyn LlmAdapter>,
 embedding_model: Arc<dyn EmbeddingModel>,
 config_manager: ApiConfigManager,
 ) -> Self;

 /// 聊天完成
 pub async fn chat_complete(
 &self,
 request: ChatRequest,
 ) -> Result<ChatResponse, ApiError>;

 /// 流式聊天完成
 pub async fn chat_complete_stream(
 &self,
 request: ChatRequest,
 ) -> Result<Pin<Box<dyn Stream<Item = Result<String, ApiError>> + Send>>, ApiError>;

 /// 嵌入生成
 pub async fn create_embeddings(
 &self,
 request: EmbeddingRequest,
 ) -> Result<EmbeddingResponse, ApiError>;

 /// 获取模型列表
 pub fn list_models(&self) -> Vec<ModelInfo>;
}

/// 聊天请求
pub struct ChatRequest {
 pub model: Option<String>,
 pub messages: Vec<Message>,
 pub temperature: Option<f32>,
 pub max_tokens: Option<u32>,
 pub stream: bool,
}
```

- [ ] **Step 2: 创建 API 模块入口**

```rust
// core/src/api/mod.rs

pub mod config;
pub mod gateway;

pub use config::*;
pub use gateway::*;
```

- [ ] **Step 3: 提交**

```bash
git add core/src/api/
git commit -m "feat(api): add API gateway with chat and embedding endpoints"
```

---

## Task 3: 更新 lib.rs

- [ ] **Step 1: 更新 core/src/lib.rs**

```rust
pub mod api;
// ... existing modules ...

pub use api::{ApiGateway, ApiConfigManager, ProviderConfig, ProviderType, ChatRequest, ChatResponse};
```

- [ ] **Step 2: 构建测试**

```bash
cd core && cargo build
```

- [ ] **Step 3: 提交**

```bash
git add core/
git commit -m "feat: integrate API gateway module"
```

---

## 验收检查

- [ ] `cargo build` 成功
- [ ] API 配置管理正常工作
- [ ] API 网关支持聊天和嵌入

**计划创建日期**: 2026-04-22