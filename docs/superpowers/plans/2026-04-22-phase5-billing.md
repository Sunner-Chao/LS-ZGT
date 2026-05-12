# 筑规通桌面客户端 - 阶段5：计费系统实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development

**Goal:** 实现 Token 计费系统、套餐管理、用量统计。

**Architecture:** 用量追踪 → 配额检查 → 扣费 → 统计

---

## Task 1: 计费数据模型

**Files:**
- Create: `core/src/billing/mod.rs`
- Create: `core/src/billing/plans.rs`
- Create: `core/src/billing/usage.rs`

- [ ] **Step 1: 创建计费模块**

```rust
// core/src/billing/mod.rs

pub mod plans;
pub mod usage;

pub use plans::*;
pub use usage::*;
```

- [ ] **Step 2: 创建套餐模块**

```rust
// core/src/billing/plans.rs

/// 套餐类型
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
pub enum PlanType {
 Free,
 Basic,
 Pro,
 Enterprise,
 PayAsYouGo,
}

/// 套餐信息
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Plan {
 pub id: String,
 pub name: String,
 pub plan_type: PlanType,
 pub price_monthly: f64,
 pub price_yearly: Option<f64>,
 pub local_quota: Option<u64>, // None = 不限量
 pub cloud_quota: Option<u64>, // None = 不限量
 pub features: FeatureSet,
}

/// 功能权限
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct FeatureSet {
 pub max_knowledge_bases: Option<u32>,
 pub api_access: bool,
 pub wechat_integration: bool,
 pub feishu_integration: bool,
 pub mcp_server: bool,
 pub custom_tools: bool,
 pub script_tools: bool,
 pub priority_support: bool,
}

impl Plan {
 pub fn free() -> Self { ... }
 pub fn basic() -> Self { ... }
 pub fn pro() -> Self { ... }
 pub fn enterprise() -> Self { ... }
 pub fn pay_as_you_go() -> Self { ... }
}
```

- [ ] **Step 3: 创建用量模块**

```rust
// core/src/billing/usage.rs

/// 用量记录
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct UsageRecord {
 pub id: String,
 pub user_id: String,
 pub mode: LlmMode,
 pub prompt_tokens: u64,
 pub completion_tokens: u64,
 pub total_tokens: u64,
 pub timestamp: i64,
}

/// 用量统计
#[derive(Debug, Clone)]
pub struct UsageStats {
 pub period_start: i64,
 pub period_end: i64,
 pub local_usage: TokenCount,
 pub cloud_usage: TokenCount,
 pub total_usage: TokenCount,
}

pub struct TokenCount {
 pub prompt: u64,
 pub completion: u64,
 pub total: u64,
}

/// 配额结果
pub enum QuotaResult {
 Enough { remaining: u64 },
 Exceeded { required: u64, available: u64 },
 Unlimited,
}
```

- [ ] **Step 4: 提交**

```bash
git add core/src/billing/
git commit -m "feat(billing): add billing data models and usage tracking"
```

---

## Task 2: 计费管理器

**Files:**
- Create: `core/src/billing/manager.rs`

- [ ] **Step 1: 创建计费管理器**

```rust
// core/src/billing/manager.rs

pub struct BillingManager {
 storage: Arc<Storage>,
 current_plan: Plan,
 usage_tracker: UsageTracker,
}

impl BillingManager {
 pub fn new(storage: Arc<Storage>, plan: Plan) -> Self;

 /// 检查配额
 pub fn check_quota(&self, mode: LlmMode, tokens: u64) -> QuotaResult;

 /// 记录用量
 pub async fn record_usage(&self, record: UsageRecord) -> Result<(), Error>;

 /// 获取当前统计
 pub async fn get_usage_stats(&self) -> Result<UsageStats, Error>;

 /// 切换套餐
 pub fn switch_plan(&mut self, plan: Plan);

 /// 获取剩余配额
 pub fn get_remaining_quota(&self, mode: LlmMode) -> QuotaResult;
}
```

- [ ] **Step 2: 提交**

```bash
git add core/src/billing/
git commit -m "feat(billing): add billing manager with quota checking"
```

---

## Task 3: 更新 lib.rs

- [ ] **Step 1: 更新 core/src/lib.rs**

```rust
pub mod billing;
pub use billing::*;
```

- [ ] **Step 2: 构建测试**

```bash
cd core && cargo build
```

- [ ] **Step 3: 提交**

```bash
git add core/
git commit -m "feat: integrate billing system module"
```

---

## 验收检查

- [ ] `cargo build` 成功
- [ ] 套餐定义完整
- [ ] 用量追踪正常

**计划创建日期**: 2026-04-22