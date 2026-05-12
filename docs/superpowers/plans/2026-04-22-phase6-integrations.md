# 筑规通桌面客户端 - 阶段6：开放 API 与第三方接入实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development

**Goal:** 实现内嵌 HTTP API 服务、API Key 管理、第三方平台接入（微信、飞书、钉钉）。

**Architecture:** 内嵌 HTTP Server → RESTful API → Webhook 回调

---

## Task 1: HTTP API 服务

**Files:**
- Create: `core/src/integrations/http_server.rs`
- Create: `core/src/integrations/mod.rs`

- [ ] **Step 1: 创建 HTTP 服务器**

```rust
// core/src/integrations/http_server.rs

/// 内嵌 HTTP API 服务器
pub struct HttpApiServer {
 port: u16,
 router: Router,
 middleware: Vec<Box<dyn Middleware>>,
}

impl HttpApiServer {
 pub fn new(port: u16) -> Self;
 pub async fn start(&self) -> Result<(), Error>;
 pub async fn stop(&self) -> Result<(), Error>;
 pub fn is_running(&self) -> bool;
 pub fn add_route(&mut self, method: Method, path: &str, handler: Handler);
}

/// 路由器
pub struct Router {
 routes: HashMap<MethodPath, Handler>,
 middleware: Vec<Box<dyn Middleware>>,
}
```

- [ ] **Step 2: 创建模块入口**

```rust
// core/src/integrations/mod.rs

pub mod http_server;
pub mod wechat;
pub mod feishu;
pub mod dingtalk;
pub mod webhook;

pub use http_server::*;
pub use webhook::*;
```

- [ ] **Step 3: 提交**

```bash
git add core/src/integrations/
git commit -m "feat(integrations): add HTTP API server and webhook system"
```

---

## Task 2: API Key 管理

**Files:**
- Create: `core/src/integrations/api_keys.rs`

- [ ] **Step 1: 创建 API Key 管理**

```rust
// core/src/integrations/api_keys.rs

pub struct ApiKeyManager {
 storage: Arc<Storage>,
 keys: RwLock<HashMap<String, ApiKey>>,
}

impl ApiKeyManager {
 pub fn new(storage: Arc<Storage>) -> Self;
 pub fn create_key(&self, name: &str, scopes: Vec<ApiScope>, rate_limit: Option<RateLimit>) -> Result<ApiKey, Error>;
 pub fn revoke_key(&self, key_id: &str) -> Result<(), Error>;
 pub fn validate_key(&self, key: &str) -> Result<ApiKey, Error>;
 pub fn list_keys(&self) -> Vec<ApiKeyInfo>;
}

/// API Key 范围
pub enum ApiScope {
 Chat,
 Embedding,
 KnowledgeRead,
 KnowledgeWrite,
 Admin,
}
```

- [ ] **Step 2: 提交**

```bash
git add core/src/integrations/api_keys.rs
git commit -m "feat(integrations): add API key management"
```

---

## Task 3: 更新 lib.rs

- [ ] **Step 1: 更新 core/src/lib.rs**

```rust
pub mod integrations;
// ...
pub use integrations::{HttpApiServer, ApiKeyManager, ApiScope, ApiKey, WebhookEvent};
```

- [ ] **Step 2: 构建测试**

```bash
cd core && cargo build
```

- [ ] **Step 3: 提交**

```bash
git add core/
git commit -m "feat: integrate integrations module"
```

---

## 验收检查

- [ ] `cargo build` 成功
- [ ] HTTP API 服务可启动
- [ ] API Key 管理正常

**计划创建日期**: 2026-04-22