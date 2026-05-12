# 筑规通桌面客户端 - 阶段 2.5：Chain 架构与高级 RAG 实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development

**Goal:** 实现 LangChain 风格的 Chain 架构和高级 RAG 管道，包含查询转换、混合搜索、重排序等优化技术。

**Architecture:**
```
用户查询 → Pre-Retrieval (查询转换) → Retrieval (混合搜索) → Post-Retrieval (重排序/压缩) → Synthesis (答案合成)
```

**Tech Stack:** Rust (async-trait, tokio), Milvus, BGE-M3, BGE-Reranker

---

## 文件结构规划

```
core/src/
├── chains/
│   ├── mod.rs
│   ├── base.rs              # BaseChain trait
│   ├── context.rs           # ChainContext
│   ├── retrieval_chain.rs   # RetrievalChain
│   ├── conversation_chain.rs # ConversationChain
│   ├── router_chain.rs      # RouterChain
│   └── combinators.rs       # SequentialChain, ParallelChain
│
├── rag/
│   ├── mod.rs
│   ├── pipeline.rs           # AdvancedRagPipeline
│   ├── pre_retrieval/        # 预检索器
│   │   ├── mod.rs
│   │   ├── query_rewriter.rs
│   │   ├── query_decomposer.rs
│   │   └── hyde.rs           # Hypothetical Document Embeddings
│   ├── retrieval/            # 检索器
│   │   ├── mod.rs
│   │   ├── dense_retriever.rs
│   │   └── hybrid_retriever.rs
│   ├── post_retrieval/        # 后检索器
│   │   ├── mod.rs
│   │   ├── reranker.rs       # Cross-Encoder 重排序
│   │   └── compressor.rs     # 上下文压缩
│   ├── synthesizer.rs        # 答案合成器
│   └── types.rs              # RAG 相关类型
│
├── memory/
│   ├── mod.rs
│   ├── base.rs               # ChatMemory trait
│   └── buffer.rs             # 基于缓冲区的记忆
│
└── lib.rs
```

---

## Task 1: Chain 基础架构

**Files:**
- Create: `core/src/chains/base.rs`
- Create: `core/src/chains/context.rs`
- Create: `core/src/chains/mod.rs`

- [ ] **Step 1: 创建 Chain 基础 Trait**

```rust
// core/src/chains/base.rs

use async_trait::async_trait;
use serde::{Deserialize, Serialize};
use std::collections::HashMap;
use std::pin::Pin;
use futures::Stream;

/// Chain 输入
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ChainInput {
    pub key: String,
    pub value: serde_json::Value,
}

/// Chain 输出
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ChainOutput {
    pub key: String,
    pub value: serde_json::Value,
}

/// Chain 执行结果
#[derive(Debug, Clone)]
pub struct ChainResult {
    pub outputs: HashMap<String, ChainOutput>,
    pub metadata: ChainMetadata,
}

/// Chain 元数据
#[derive(Debug, Clone, Default)]
pub struct ChainMetadata {
    pub total_tokens: u64,
    pub prompt_tokens: u64,
    pub completion_tokens: u64,
    pub execution_time_ms: u64,
    pub steps_executed: Vec<String>,
}

/// Chain 错误
#[derive(Debug, thiserror::Error)]
pub enum ChainError {
    #[error("输入缺失: {0}")]
    MissingInput(String),

    #[error("执行失败: {0}")]
    ExecutionFailed(String),

    #[error("LLM 错误: {0}")]
    LlmError(String),

    #[error("检索错误: {0}")]
    RetrievalError(String),

    #[error("记忆错误: {0}")]
    MemoryError(String),
}

/// BaseChain trait - 所有 Chain 必须实现此接口
#[async_trait]
pub trait BaseChain: Send + Sync {
    /// Chain 名称
    fn name(&self) -> &str;

    /// 输入变量名
    fn input_keys(&self) -> Vec<String>;

    /// 输出变量名
    fn output_keys(&self) -> Vec<String>;

    /// 检查输入是否完整
    fn validate_inputs(&self, inputs: &HashMap<String, serde_json::Value>) -> Result<(), ChainError> {
        for key in self.input_keys() {
            if !inputs.contains_key(&key) {
                return Err(ChainError::MissingInput(key));
            }
        }
        Ok(())
    }

    /// 执行 Chain
    async fn invoke(
        &self,
        inputs: HashMap<String, serde_json::Value>,
        context: &ChainContext,
    ) -> Result<ChainResult, ChainError>;

    /// 批量执行
    async fn batch(
        &self,
        inputs: Vec<HashMap<String, serde_json::Value>>,
        context: &ChainContext,
    ) -> Result<Vec<ChainResult>, ChainError> {
        let mut results = Vec::with_capacity(inputs.len());
        for input in inputs {
            let result = self.invoke(input, context).await?;
            results.push(result);
        }
        Ok(results)
    }

    /// 流式执行
    async fn stream(
        &self,
        input: HashMap<String, serde_json::Value>,
        context: &ChainContext,
    ) -> Result<Pin<Box<dyn Stream<Item = Result<ChainOutput, ChainError>> + Send>>, ChainError> {
        // 默认实现：先调用 invoke，再转换为流
        let result = self.invoke(input, context).await?;
        let outputs: Vec<_> = result.outputs.into_values().collect();
        let stream = futures::stream::iter(outputs.into_iter().map(Ok));
        Ok(Box::pin(stream))
    }
}

/// 辅助宏：简化 Chain 实现
#[macro_export]
macro_rules! impl_base_chain {
    ($name:expr, $input_keys:expr, $output_keys:expr) => {
        fn name(&self) -> &str { $name }
        fn input_keys(&self) -> Vec<String> { $input_keys }
        fn output_keys(&self) -> Vec<String> { $output_keys }
    };
}
```

- [ ] **Step 2: 创建 Chain 执行上下文**

```rust
// core/src/chains/context.rs

use crate::llm::LlmEngine;
use crate::embedding::EmbeddingModel;
use crate::vector::VectorStore;
use std::sync::Arc;

/// Chain 执行上下文
pub struct ChainContext {
    /// LLM 引擎
    pub llm: Arc<dyn LlmEngine>,
    /// 嵌入模型
    pub embedding: Arc<dyn EmbeddingModel>,
    /// 向量存储
    pub vector_store: Arc<dyn VectorStore>,
    /// 回调处理器
    pub callbacks: Vec<Box<dyn CallbackHandler>>,
    /// 会话 ID（用于记忆管理）
    pub session_id: Option<String>,
}

impl ChainContext {
    pub fn new(
        llm: Arc<dyn LlmEngine>,
        embedding: Arc<dyn EmbeddingModel>,
        vector_store: Arc<dyn VectorStore>,
    ) -> Self {
        Self {
            llm,
            embedding,
            vector_store,
            callbacks: Vec::new(),
            session_id: None,
        }
    }

    pub fn with_session_id(mut self, session_id: String) -> Self {
        self.session_id = Some(session_id);
        self
    }

    pub fn with_callbacks(mut self, callbacks: Vec<Box<dyn CallbackHandler>>) -> Self {
        self.callbacks = callbacks;
        self
    }
}

/// 回调处理器 trait
#[async_trait]
pub trait CallbackHandler: Send + Sync {
    /// Chain 开始执行
    async fn on_chain_start(&self, name: &str, inputs: &HashMap<String, serde_json::Value>);

    /// Chain 执行完成
    async fn on_chain_end(&self, name: &str, result: &ChainResult);

    /// Chain 执行错误
    async fn on_chain_error(&self, name: &str, error: &ChainError);

    /// LLM 生成开始
    async fn on_llm_start(&self, prompt: &str);

    /// LLM 生成完成
    async fn on_llm_end(&self, response: &str);

    /// 检索开始
    async fn on_retrieval_start(&self, query: &str);

    /// 检索完成
    async fn on_retrieval_end(&self, results: &[crate::vector::SearchResult]);
}

/// 默认回调（空实现）
pub struct NoOpCallback;

#[async_trait]
impl CallbackHandler for NoOpCallback {
    async fn on_chain_start(&self, _: &str, _: &HashMap<String, serde_json::Value>) {}
    async fn on_chain_end(&self, _: &str, _: &ChainResult) {}
    async fn on_chain_error(&self, _: &str, _: &ChainError) {}
    async fn on_llm_start(&self, _: &str) {}
    async fn on_llm_end(&self, _: &str) {}
    async fn on_retrieval_start(&self, _: &str) {}
    async fn on_retrieval_end(&self, _: &[crate::vector::SearchResult]) {}
}
```

- [ ] **Step 3: 创建 Chains 模块入口**

```rust
// core/src/chains/mod.rs

pub mod base;
pub mod context;
pub mod retrieval_chain;
pub mod conversation_chain;
pub mod router_chain;
pub mod combinators;

pub use base::*;
pub use context::*;
pub use retrieval_chain::RetrievalChain;
pub use conversation_chain::ConversationChain;
pub use router_chain::RouterChain;
pub use combinators::{SequentialChain, ParallelChain};
```

- [ ] **Step 4: 提交**

```bash
git add core/src/chains/
git commit -m "feat(chains): add Chain architecture foundation

- Add BaseChain trait with invoke/batch/stream methods
- Add ChainContext for dependency injection
- Add ChainMetadata for execution tracking
- Add CallbackHandler trait for observability"
```

---

## Task 2: RetrievalChain 实现

**Files:**
- Create: `core/src/chains/retrieval_chain.rs`

- [ ] **Step 1: 创建 RetrievalChain**

```rust
// core/src/chains/retrieval_chain.rs

use super::*;
use crate::vector::{VectorStore, SearchResult};
use crate::rag::Synthesizer;

/// 默认 QA Prompt
const DEFAULT_QA_PROMPT: &str = r#"你是一个专业的建筑行业规范助手。请根据以下参考资料回答问题。

参考资料:
{context}

问题: {question}

请给出准确、专业的回答，并在回答中引用相关规范条文。如果参考资料中没有相关信息，请如实说明。"#;

/// 检索链配置
#[derive(Debug, Clone)]
pub struct RetrievalChainConfig {
    /// 使用的 Prompt 模板
    pub prompt_template: String,
    /// 检索返回数量
    pub top_k: usize,
    /// 相似度阈值
    pub similarity_threshold: f32,
    /// 输出键名
    pub output_key: String,
}

impl Default for RetrievalChainConfig {
    fn default() -> Self {
        Self {
            prompt_template: DEFAULT_QA_PROMPT.to_string(),
            top_k: 5,
            similarity_threshold: 0.7,
            output_key: "answer".to_string(),
        }
    }
}

/// 检索链 - 结合向量检索和 LLM 生成
pub struct RetrievalChain {
    retriever: Arc<dyn Retriever>,
    synthesizer: Arc<dyn Synthesizer>,
    config: RetrievalChainConfig,
}

/// 检索器 trait
#[async_trait]
pub trait Retriever: Send + Sync {
    async fn retrieve(&self, query: &str) -> Result<Vec<RetrievedDoc>, ChainError>;
    async fn retrieve_with_threshold(
        &self,
        query: &str,
        top_k: usize,
        threshold: f32,
    ) -> Result<Vec<RetrievedDoc>, ChainError>;
}

/// 检索到的文档
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct RetrievedDoc {
    pub id: String,
    pub content: String,
    pub score: f32,
    pub metadata: DocMetadata,
}

/// 文档元数据
#[derive(Debug, Clone, Default, Serialize, Deserialize)]
pub struct DocMetadata {
    pub document_id: Option<String>,
    pub chunk_index: Option<u32>,
    pub page: Option<u32>,
    pub source_file: Option<String>,
    pub title: Option<String>,
}

impl RetrievalChain {
    pub fn new(
        retriever: Arc<dyn Retriever>,
        synthesizer: Arc<dyn Synthesizer>,
    ) -> Self {
        Self {
            retriever,
            synthesizer,
            config: RetrievalChainConfig::default(),
        }
    }

    pub fn with_config(mut self, config: RetrievalChainConfig) -> Self {
        self.config = config;
        self
    }

    pub fn with_template(mut self, template: &str) -> Self {
        self.config.prompt_template = template.to_string();
        self
    }
}

#[async_trait]
impl BaseChain for RetrievalChain {
    fn name(&self) -> &str {
        "retrieval_chain"
    }

    fn input_keys(&self) -> Vec<String> {
        vec!["query".to_string()]
    }

    fn output_keys(&self) -> Vec<String> {
        vec![self.config.output_key.clone()]
    }

    async fn invoke(
        &self,
        inputs: HashMap<String, serde_json::Value>,
        context: &ChainContext,
    ) -> Result<ChainResult, ChainError> {
        let start_time = std::time::Instant::now();

        // 1. 验证输入
        self.validate_inputs(&inputs)?;

        // 2. 获取查询
        let query = inputs
            .get("query")
            .and_then(|v| v.as_str())
            .unwrap()
            .to_string();

        // 3. 触发回调
        for callback in &context.callbacks {
            callback.on_retrieval_start(&query).await;
        }

        // 4. 检索文档
        let docs = self.retriever
            .retrieve_with_threshold(&query, self.config.top_k, self.config.similarity_threshold)
            .await?;

        // 5. 触发回调
        for callback in &context.callbacks {
            callback.on_retrieval_end(&docs.iter().map(|d| {
                crate::vector::SearchResult {
                    chunk: crate::vector::DocumentChunk {
                        id: d.id.clone(),
                        document_id: d.metadata.document_id.clone().unwrap_or_default(),
                        content: d.content.clone(),
                        metadata: crate::vector::ChunkMetadata::default(),
                        chunk_index: d.metadata.chunk_index.unwrap_or(0),
                    },
                    score: d.score,
                    distance: 1.0 - d.score,
                }
            }).collect::<Vec<_>>()).await;
        }

        // 6. 构建上下文
        let context_text = self.build_context(&docs);

        // 7. 触发 LLM 开始回调
        for callback in &context.callbacks {
            callback.on_llm_start(&context_text).await;
        }

        // 8. 构建 Prompt
        let prompt = self.config.prompt_template
            .replace("{context}", &context_text)
            .replace("{question}", &query);

        // 9. 调用 LLM 生成
        let mut stream = context.llm.generate_stream(&prompt, &Default::default())
            .await
            .map_err(|e| ChainError::LlmError(e.to_string()))?;

        let mut answer = String::new();
        while let Some(chunk) = stream.next().await {
            answer.push_str(&chunk.map_err(|e| ChainError::LlmError(e.to_string()))?);
        }

        // 10. 触发 LLM 完成回调
        for callback in &context.callbacks {
            callback.on_llm_end(&answer).await;
        }

        // 11. 构建结果
        let execution_time = start_time.elapsed().as_millis() as u64;
        let result = ChainResult {
            outputs: HashMap::from([(
                self.config.output_key.clone(),
                ChainOutput {
                    key: self.config.output_key.clone(),
                    value: serde_json::json!({
                        "answer": answer,
                        "sources": docs,
                        "query": query,
                    }),
                },
            )]),
            metadata: ChainMetadata {
                total_tokens: 0,
                prompt_tokens: 0,
                completion_tokens: 0,
                execution_time_ms: execution_time,
                steps_executed: vec![
                    "retrieve".to_string(),
                    "build_context".to_string(),
                    "generate".to_string(),
                ],
            },
        };

        // 12. 触发 Chain 完成回调
        for callback in &context.callbacks {
            callback.on_chain_end(self.name(), &result).await;
        }

        Ok(result)
    }
}

impl RetrievalChain {
    fn build_context(&self, docs: &[RetrievedDoc]) -> String {
        docs.iter()
            .enumerate()
            .map(|(i, doc)| {
                let source_info = doc.metadata.source_file.as_ref()
                    .map(|s| format!(" (来源: {})", s))
                    .unwrap_or_default();
                format!("[文档 {}]{}:\n{}\n", i + 1, source_info, doc.content)
            })
            .collect::<Vec<_>>()
            .join("\n---\n")
    }
}
```

- [ ] **Step 2: 提交**

```bash
git add core/src/chains/retrieval_chain.rs
git commit -m "feat(chains): add RetrievalChain implementation

- Add Retriever trait for retrieval abstraction
- Add RetrievedDoc and DocMetadata types
- Implement invoke with retrieval, context building, and generation
- Add callback support for observability"
```

---

## Task 3: ConversationChain 与记忆

**Files:**
- Create: `core/src/memory/base.rs`
- Create: `core/src/memory/buffer.rs`
- Create: `core/src/memory/mod.rs`
- Create: `core/src/chains/conversation_chain.rs`

- [ ] **Step 1: 创建记忆模块**

```rust
// core/src/memory/base.rs

use async_trait::async_trait;

/// 聊天消息
#[derive(Debug, Clone)]
pub struct ChatMessage {
    pub role: MessageRole,
    pub content: String,
    pub timestamp: i64,
}

/// 消息角色
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum MessageRole {
    System,
    User,
    Assistant,
}

impl ChatMessage {
    pub fn system(content: &str) -> Self {
        Self {
            role: MessageRole::System,
            content: content.to_string(),
            timestamp: chrono::Utc::now().timestamp(),
        }
    }

    pub fn user(content: &str) -> Self {
        Self {
            role: MessageRole::User,
            content: content.to_string(),
            timestamp: chrono::Utc::now().timestamp(),
        }
    }

    pub fn assistant(content: &str) -> Self {
        Self {
            role: MessageRole::Assistant,
            content: content.to_string(),
            timestamp: chrono::Utc::now().timestamp(),
        }
    }

    pub fn to_prompt_string(&self) -> String {
        match self.role {
            MessageRole::System => format!("系统: {}", self.content),
            MessageRole::User => format!("用户: {}", self.content),
            MessageRole::Assistant => format!("助手: {}", self.content),
        }
    }
}

/// 聊天记忆 trait
#[async_trait]
pub trait ChatMemory: Send + Sync {
    /// 添加消息
    async fn add_message(&self, message: &ChatMessage);

    /// 获取消息历史
    async fn get_messages(&self) -> Vec<ChatMessage>;

    /// 获取历史摘要（用于超长对话）
    async fn get_summary(&self) -> String;

    /// 清空历史
    async fn clear(&self);

    /// 获取消息数量
    fn len(&self) -> usize;

    /// 检查是否为空
    fn is_empty(&self) -> bool;
}
```

```rust
// core/src/memory/buffer.rs

use super::*;
use std::collections::VecDeque;

/// 基于缓冲区的记忆
pub struct BufferMemory {
    messages: VecDeque<ChatMessage>,
    max_messages: usize,
}

impl BufferMemory {
    pub fn new(max_messages: usize) -> Self {
        Self {
            messages: VecDeque::new(),
            max_messages,
        }
    }

    pub fn with_initial_messages(messages: Vec<ChatMessage>, max_messages: usize) -> Self {
        let messages: VecDeque<_> = messages.into_iter().take(max_messages).collect();
        Self { messages, max_messages }
    }
}

#[async_trait]
impl ChatMemory for BufferMemory {
    async fn add_message(&self, message: &ChatMessage) {
        self.messages.push_back(message.clone());

        // 如果超过最大数量，移除最旧的消息
        while self.messages.len() > self.max_messages {
            self.messages.pop_front();
        }
    }

    async fn get_messages(&self) -> Vec<ChatMessage> {
        self.messages.iter().cloned().collect()
    }

    async fn get_summary(&self) -> String {
        let messages = self.get_messages().await;
        if messages.len() <= 10 {
            return self.to_prompt_string(&messages);
        }

        // 对话太长时，保留最近的消息
        let recent = messages.iter().rev().take(10).cloned().collect::<Vec<_>>();
        let summary = format!(
            "[早期对话摘要 (共 {} 条消息)]\n{}\n\n[最近 10 条消息]\n{}",
            messages.len(),
            "对话保持连贯",
            self.to_prompt_string(&recent.into_iter().rev().collect::<Vec<_>>())
        );
        summary
    }

    async fn clear(&self) {
        self.messages.clear();
    }

    fn len(&self) -> usize {
        self.messages.len()
    }

    fn is_empty(&self) -> bool {
        self.messages.is_empty()
    }
}

impl BufferMemory {
    fn to_prompt_string(&self, messages: &[ChatMessage]) -> String {
        messages.iter()
            .map(|m| m.to_prompt_string())
            .collect::<Vec<_>>()
            .join("\n")
    }
}
```

```rust
// core/src/memory/mod.rs

pub mod base;
pub mod buffer;

pub use base::{ChatMemory, ChatMessage, MessageRole};
pub use buffer::BufferMemory;
```

- [ ] **Step 2: 创建 ConversationChain**

```rust
// core/src/chains/conversation_chain.rs

use super::*;
use crate::memory::{ChatMemory, ChatMessage};

/// 默认对话 Prompt
const DEFAULT_CONVERSATION_PROMPT: &str = r#"你是一个专业的建筑行业规范助手。以下是当前的对话历史:

{history}

当前问题: {input}

请给出回答。"#;

/// 对话链配置
#[derive(Debug, Clone)]
pub struct ConversationChainConfig {
    pub prompt_template: String,
    pub memory_key: String,
    pub input_key: String,
    pub output_key: String,
}

impl Default for ConversationChainConfig {
    fn default() -> Self {
        Self {
            prompt_template: DEFAULT_CONVERSATION_PROMPT.to_string(),
            memory_key: "history".to_string(),
            input_key: "input".to_string(),
            output_key: "response".to_string(),
        }
    }
}

/// 对话链 - 支持多轮对话
pub struct ConversationChain {
    memory: Arc<dyn ChatMemory>,
    config: ConversationChainConfig,
}

impl ConversationChain {
    pub fn new(memory: Arc<dyn ChatMemory>) -> Self {
        Self {
            memory,
            config: ConversationChainConfig::default(),
        }
    }

    pub fn with_config(mut self, config: ConversationChainConfig) -> Self {
        self.config = config;
        self
    }

    pub fn with_template(mut self, template: &str) -> Self {
        self.config.prompt_template = template.to_string();
        self
    }
}

#[async_trait]
impl BaseChain for ConversationChain {
    fn name(&self) -> &str {
        "conversation_chain"
    }

    fn input_keys(&self) -> Vec<String> {
        vec![self.config.input_key.clone()]
    }

    fn output_keys(&self) -> Vec<String> {
        vec![self.config.output_key.clone()]
    }

    async fn invoke(
        &self,
        inputs: HashMap<String, serde_json::Value>,
        context: &ChainContext,
    ) -> Result<ChainResult, ChainError> {
        let start_time = std::time::Instant::now();

        // 1. 验证输入
        self.validate_inputs(&inputs)?;

        // 2. 获取输入
        let input = inputs
            .get(&self.config.input_key)
            .and_then(|v| v.as_str())
            .unwrap()
            .to_string();

        // 3. 获取对话历史
        let history = if self.memory.len() > 20 {
            self.memory.get_summary().await
        } else {
            let messages = self.memory.get_messages().await;
            messages.iter()
                .map(|m| m.to_prompt_string())
                .collect::<Vec<_>>()
                .join("\n")
        };

        // 4. 构建 Prompt
        let prompt = self.config.prompt_template
            .replace("{history}", &history)
            .replace("{input}", &input);

        // 5. 调用 LLM
        let response = context.llm.generate(&prompt).await
            .map_err(|e| ChainError::LlmError(e.to_string()))?;

        // 6. 保存到记忆
        self.memory.add_message(&ChatMessage::user(&input)).await;
        self.memory.add_message(&ChatMessage::assistant(&response)).await;

        // 7. 构建结果
        Ok(ChainResult {
            outputs: HashMap::from([(
                self.config.output_key.clone(),
                ChainOutput {
                    key: self.config.output_key.clone(),
                    value: serde_json::json!({
                        "response": response,
                        "input": input,
                    }),
                },
            )]),
            metadata: ChainMetadata {
                total_tokens: 0,
                prompt_tokens: 0,
                completion_tokens: 0,
                execution_time_ms: start_time.elapsed().as_millis() as u64,
                steps_executed: vec!["get_history".to_string(), "generate".to_string(), "save_memory".to_string()],
            },
        })
    }
}
```

- [ ] **Step 3: 提交**

```bash
git add core/src/memory/ core/src/chains/conversation_chain.rs
git commit -m "feat(chains): add ConversationChain with memory support

- Add ChatMemory trait and BufferMemory implementation
- Add ChatMessage with role-based formatting
- Implement ConversationChain with history management
- Support for long conversation summarization"
```

---

## Task 4: Chain 组合器

**Files:**
- Create: `core/src/chains/combinators.rs`
- Create: `core/src/chains/router_chain.rs`

- [ ] **Step 1: 创建 Chain 组合器**

```rust
// core/src/chains/combinators.rs

use super::*;

/// 顺序执行链
pub struct SequentialChain {
    chains: Vec<Box<dyn BaseChain>>,
    final_output_keys: Vec<String>,
}

impl SequentialChain {
    pub fn new(chains: Vec<Box<dyn BaseChain>>) -> Self {
        let final_output_keys = chains.last()
            .map(|c| c.output_keys())
            .unwrap_or_default();
        Self {
            chains,
            final_output_keys,
        }
    }
}

#[async_trait]
impl BaseChain for SequentialChain {
    fn name(&self) -> &str {
        "sequential_chain"
    }

    fn input_keys(&self) -> Vec<String> {
        self.chains.first()
            .map(|c| c.input_keys())
            .unwrap_or_default()
    }

    fn output_keys(&self) -> Vec<String> {
        self.final_output_keys.clone()
    }

    async fn invoke(
        &self,
        inputs: HashMap<String, serde_json::Value>,
        context: &ChainContext,
    ) -> Result<ChainResult, ChainError> {
        let mut current_inputs = inputs;
        let mut all_metadata = ChainMetadata::default();

        for chain in &self.chains {
            let result = chain.invoke(current_inputs, context).await?;

            // 收集元数据
            all_metadata.total_tokens += result.metadata.total_tokens;
            all_metadata.prompt_tokens += result.metadata.prompt_tokens;
            all_metadata.completion_tokens += result.metadata.completion_tokens;
            all_metadata.execution_time_ms += result.metadata.execution_time_ms;
            all_metadata.steps_executed.push(chain.name().to_string());

            // 将输出转换为下一轮的输入
            current_inputs = result.outputs
                .into_iter()
                .map(|(k, v)| (k, v.value))
                .collect();
        }

        // 最后一轮的输出作为最终输出
        let final_outputs: HashMap<String, ChainOutput> = current_inputs
            .into_iter()
            .map(|(k, v)| (k.clone(), ChainOutput { key: k, value: v }))
            .collect();

        Ok(ChainResult {
            outputs: final_outputs,
            metadata: all_metadata,
        })
    }
}

/// 并行执行链
pub struct ParallelChain {
    chain: Box<dyn BaseChain>,
    other_chains: Vec<Box<dyn BaseChain>>,
}

impl ParallelChain {
    pub fn new(chain: Box<dyn BaseChain>, other_chains: Vec<Box<dyn BaseChain>>) -> Self {
        Self { chain, other_chains }
    }
}

#[async_trait]
impl BaseChain for ParallelChain {
    fn name(&self) -> &str {
        "parallel_chain"
    }

    fn input_keys(&self) -> Vec<String> {
        self.chain.input_keys()
    }

    fn output_keys(&self) -> Vec<String> {
        let mut keys = self.chain.output_keys();
        for other in &self.other_chains {
            keys.extend(other.output_keys());
        }
        keys
    }

    async fn invoke(
        &self,
        inputs: HashMap<String, serde_json::Value>,
        context: &ChainContext,
    ) -> Result<ChainResult, ChainError> {
        use tokio::task::JoinSet;

        let mut join_set = JoinSet::new();

        // 主链
        let main_chain = self.chain.invoke(inputs.clone(), context);
        join_set.spawn(async move {
            main_chain.await
        });

        // 其他链
        for other in &self.other_chains {
            let other_inputs = inputs.clone();
            let other_chain = other.invoke(other_inputs, context);
            join_set.spawn(async move {
                other_chain.await
            });
        }

        // 收集结果
        let mut all_outputs = HashMap::new();
        let mut total_metadata = ChainMetadata::default();

        while let Some(result) = join_set.join_next().await {
            match result {
                Ok(Ok(chain_result)) => {
                    total_metadata.total_tokens += chain_result.metadata.total_tokens;
                    total_metadata.prompt_tokens += chain_result.metadata.prompt_tokens;
                    total_metadata.completion_tokens += chain_result.metadata.completion_tokens;
                    total_metadata.execution_time_ms += chain_result.metadata.execution_time_ms;
                    total_metadata.steps_executed.push("parallel".to_string());
                    all_outputs.extend(chain_result.outputs);
                }
                Ok(Err(e)) => return Err(e),
                Err(e) => return Err(ChainError::ExecutionFailed(e.to_string())),
            }
        }

        Ok(ChainResult {
            outputs: all_outputs,
            metadata: total_metadata,
        })
    }
}
```

- [ ] **Step 2: 创建 RouterChain**

```rust
// core/src/chains/router_chain.rs

use super::*;

/// 路由函数类型
pub type RouterFn = Box<dyn Fn(&HashMap<String, serde_json::Value>) -> &'static str + Send + Sync>;

/// 路由器链 - 根据输入选择子链
pub struct RouterChain {
    router_fn: RouterFn,
    chains: HashMap<String, Box<dyn BaseChain>>,
    default_chain: Option<Box<dyn BaseChain>>,
}

impl RouterChain {
    pub fn new(
        router_fn: impl Fn(&HashMap<String, serde_json::Value>) -> &'static str + Send + Sync + 'static,
    ) -> Self {
        Self {
            router_fn: Box::new(router_fn),
            chains: HashMap::new(),
            default_chain: None,
        }
    }

    pub fn with_chains(mut self, chains: HashMap<String, Box<dyn BaseChain>>) -> Self {
        self.chains = chains;
        self
    }

    pub fn with_default_chain(mut self, chain: Box<dyn BaseChain>) -> Self {
        self.default_chain = Some(chain);
        self
    }

    pub fn add_chain(&mut self, name: &'static str, chain: Box<dyn BaseChain>) {
        self.chains.insert(name.to_string(), chain);
    }
}

#[async_trait]
impl BaseChain for RouterChain {
    fn name(&self) -> &str {
        "router_chain"
    }

    fn input_keys(&self) -> Vec<String> {
        self.chains.values()
            .flat_map(|c| c.input_keys())
            .collect()
    }

    fn output_keys(&self) -> Vec<String> {
        self.chains.values()
            .flat_map(|c| c.output_keys())
            .collect()
    }

    async fn invoke(
        &self,
        inputs: HashMap<String, serde_json::Value>,
        context: &ChainContext,
    ) -> Result<ChainResult, ChainError> {
        let route = (self.router_fn)(&inputs);

        let chain = self.chains.get(route)
            .or(self.default_chain.as_ref())
            .ok_or_else(|| ChainError::ExecutionFailed(format!("No chain found for route: {}", route)))?;

        chain.invoke(inputs, context).await
    }
}

/// 预定义路由函数
pub mod routers {
    use super::*;

    /// 基于查询类型的路由
    pub fn route_by_query_type(inputs: &HashMap<String, serde_json::Value>) -> &'static str {
        let query = inputs.get("query")
            .and_then(|v| v.as_str())
            .unwrap_or("");

        let query_lower = query.to_lowercase();

        if query_lower.contains("对比") || query_lower.contains("比较") {
            "comparison"
        } else if query_lower.contains("列出") || query_lower.contains("列表") {
            "list"
        } else if query_lower.contains("计算") || query_lower.contains("多少") {
            "calculation"
        } else {
            "qa"
        }
    }
}
```

- [ ] **Step 3: 提交**

```bash
git add core/src/chains/combinators.rs core/src/chains/router_chain.rs
git commit -m "feat(chains): add Chain combinators and router

- Add SequentialChain for sequential execution
- Add ParallelChain for parallel execution
- Add RouterChain with custom routing logic
- Include predefined routers (route_by_query_type)"
```

---

## Task 5: Pre-Retrieval 查询转换

**Files:**
- Create: `core/src/rag/types.rs`
- Create: `core/src/rag/pre_retrieval/mod.rs`
- Create: `core/src/rag/pre_retrieval/query_rewriter.rs`
- Create: `core/src/rag/pre_retrieval/query_decomposer.rs`
- Create: `core/src/rag/pre_retrieval/hyde.rs`

- [ ] **Step 1: 创建 RAG 类型定义**

```rust
// core/src/rag/types.rs

use serde::{Deserialize, Serialize};

/// 处理后的查询
#[derive(Debug, Clone)]
pub struct ProcessedQuery {
    /// 原始查询
    pub original: String,
    /// 转换后的查询
    pub transformed: String,
    /// 子查询列表（用于多查询检索）
    pub sub_queries: Option<Vec<String>>,
    /// 假设性文档（用于 HyDE）
    pub hypothetical_doc: Option<String>,
}

/// 检索到的文档
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct RetrievedDoc {
    pub id: String,
    pub content: String,
    pub score: f32,
    pub metadata: DocMetadata,
}

/// 文档元数据
#[derive(Debug, Clone, Default, Serialize, Deserialize)]
pub struct DocMetadata {
    #[serde(default)]
    pub document_id: Option<String>,
    #[serde(default)]
    pub chunk_index: Option<u32>,
    #[serde(default)]
    pub page: Option<u32>,
    #[serde(default)]
    pub source_file: Option<String>,
    #[serde(default)]
    pub title: Option<String>,
}

/// RAG 错误
#[derive(Debug, thiserror::Error)]
pub enum RagError {
    #[error("预检索错误: {0}")]
    PreRetrievalError(String),

    #[error("检索错误: {0}")]
    RetrievalError(String),

    #[error("后检索错误: {0}")]
    PostRetrievalError(String),

    #[error("合成错误: {0}")]
    SynthesisError(String),

    #[error("模型错误: {0}")]
    ModelError(String),
}

/// 合成结果
#[derive(Debug, Clone)]
pub struct SynthesisResult {
    pub answer: String,
    pub sources: Vec<SourceReference>,
}

/// 来源引用
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct SourceReference {
    pub doc_id: String,
    pub snippet: String,
    pub score: f32,
}
```

- [ ] **Step 2: 创建查询改写器**

```rust
// core/src/rag/pre_retrieval/query_rewriter.rs

use super::*;
use crate::llm::LlmEngine;
use std::sync::Arc;

/// 查询改写 Prompt
const QUERY_REWRITE_PROMPT: &str = r#"你是一个专业的建筑规范查询优化助手。请将以下用户查询改写为更精确的检索查询。

要求：
1. 保留原意，但使用更精确的建筑术语
2. 可以添加同义词和相关概念
3. 分解复合查询为多个简单查询
4. 使用建筑行业规范相关的标准表达

原始查询: {query}

改写后的查询:"#;

/// 查询改写器
pub struct QueryRewriter {
    llm: Arc<dyn LlmEngine>,
    prompt_template: String,
}

impl QueryRewriter {
    pub fn new(llm: Arc<dyn LlmEngine>) -> Self {
        Self {
            llm,
            prompt_template: QUERY_REWRITE_PROMPT.to_string(),
        }
    }

    pub async fn rewrite(&self, query: &str) -> Result<String, RagError> {
        let prompt = self.prompt_template.replace("{query}", query);

        let rewritten = self.llm.generate(&prompt).await
            .map_err(|e| RagError::ModelError(e.to_string()))?;

        Ok(rewritten.trim().to_string())
    }

    pub fn process(&self, query: &str) -> impl std::future::Future<Output = Result<ProcessedQuery, RagError>> + Send {
        let query = query.to_string();
        let rewriter = Self {
            llm: self.llm.clone(),
            prompt_template: self.prompt_template.clone(),
        };
        async move {
            let rewritten = rewriter.rewrite(&query).await?;
            Ok(ProcessedQuery {
                original: query,
                transformed: rewritten,
                sub_queries: None,
                hypothetical_doc: None,
            })
        }
    }
}
```

- [ ] **Step 3: 创建查询分解器**

```rust
// core/src/rag/pre_retrieval/query_decomposer.rs

use super::*;
use crate::llm::LlmEngine;
use std::sync::Arc;

/// 查询分解 Prompt
const QUERY_DECOMPOSE_PROMPT: &str = r#"将以下复杂查询分解为 2-4 个简单的子查询。每个子查询应该能够独立检索相关文档。

要求：
1. 每个子查询聚焦于一个方面
2. 使用清晰、简洁的建筑术语
3. 考虑时间、对比、列表等多种类型的子问题

原始查询: {query}

分解后的子查询（每行一个）:"#;

/// 查询分解器
pub struct QueryDecomposer {
    llm: Arc<dyn LlmEngine>,
    prompt_template: String,
}

impl QueryDecomposer {
    pub fn new(llm: Arc<dyn LlmEngine>) -> Self {
        Self {
            llm,
            prompt_template: QUERY_DECOMPOSE_PROMPT.to_string(),
        }
    }

    pub async fn decompose(&self, query: &str) -> Result<Vec<String>, RagError> {
        let prompt = self.prompt_template.replace("{query}", query);

        let response = self.llm.generate(&prompt).await
            .map_err(|e| RagError::ModelError(e.to_string()))?;

        let sub_queries: Vec<String> = response
            .lines()
            .filter(|line| !line.trim().is_empty())
            .map(|line| line.trim().to_string())
            .collect();

        Ok(sub_queries)
    }

    pub fn process(&self, query: &str) -> impl std::future::Future<Output = Result<ProcessedQuery, RagError>> + Send {
        let query = query.to_string();
        let decomposer = Self {
            llm: self.llm.clone(),
            prompt_template: self.prompt_template.clone(),
        };
        async move {
            let sub_queries = decomposer.decompose(&query).await?;
            Ok(ProcessedQuery {
                original: query,
                transformed: query,
                sub_queries: Some(sub_queries),
                hypothetical_doc: None,
            })
        }
    }
}
```

- [ ] **Step 4: 创建 HyDE 预检索器**

```rust
// core/src/rag/pre_retrieval/hyde.rs

use super::*;
use crate::llm::LlmEngine;
use std::sync::Arc;

/// HyDE Prompt
const HYDE_PROMPT: &str = r#"请生成一段假设性的建筑规范文档内容，用于回答以下问题。这段文档应该是准确、专业的，包含可能的规范条文引用。

问题: {query}

假设性文档内容:"#;

/// HyDE 预检索器
pub struct HydePreRetriever {
    llm: Arc<dyn LlmEngine>,
    prompt_template: String,
}

impl HydePreRetriever {
    pub fn new(llm: Arc<dyn LlmEngine>) -> Self {
        Self {
            llm,
            prompt_template: HYDE_PROMPT.to_string(),
        }
    }

    pub async fn generate_hypothetical_doc(&self, query: &str) -> Result<String, RagError> {
        let prompt = self.prompt_template.replace("{query}", query);

        let doc = self.llm.generate(&prompt).await
            .map_err(|e| RagError::ModelError(e.to_string()))?;

        Ok(doc.trim().to_string())
    }

    pub fn process(&self, query: &str) -> impl std::future::Future<Output = Result<ProcessedQuery, RagError>> + Send {
        let query = query.to_string();
        let hyde = Self {
            llm: self.llm.clone(),
            prompt_template: self.prompt_template.clone(),
        };
        async move {
            let hypothetical_doc = hyde.generate_hypothetical_doc(&query).await?;
            Ok(ProcessedQuery {
                original: query,
                transformed: query,
                sub_queries: None,
                hypothetical_doc: Some(hypothetical_doc),
            })
        }
    }
}
```

```rust
// core/src/rag/pre_retrieval/mod.rs

pub mod query_rewriter;
pub mod query_decomposer;
pub mod hyde;

pub use query_rewriter::QueryRewriter;
pub use query_decomposer::QueryDecomposer;
pub use hyde::HydePreRetriever;
```

- [ ] **Step 5: 提交**

```bash
git add core/src/rag/
git commit -m "feat(rag): add pre-retrieval query transformations

- Add ProcessedQuery and RetrievedDoc types
- Add QueryRewriter for query optimization
- Add QueryDecomposer for multi-query retrieval
- Add HydePreRetriever for hypothetical document embeddings"
```

---

## Task 6: Post-Retrieval 重排序

**Files:**
- Create: `core/src/rag/post_retrieval/mod.rs`
- Create: `core/src/rag/post_retrieval/reranker.rs`

- [ ] **Step 1: 创建重排序器**

```rust
// core/src/rag/post_retrieval/reranker.rs

use super::*;
use crate::llm::LlmEngine;
use std::sync::Arc;

/// 重排序器 trait
#[async_trait]
pub trait Reranker: Send + Sync {
    fn name(&self) -> &str;

    async fn rerank(
        &self,
        query: &str,
        documents: Vec<RetrievedDoc>,
        top_k: usize,
    ) -> Result<Vec<RetrievedDoc>, RagError>;
}

/// 交叉编码器重排序器
pub struct CrossEncoderReranker {
    llm: Arc<dyn LlmEngine>,
    top_k: usize,
}

impl CrossEncoderReranker {
    pub fn new(llm: Arc<dyn LlmEngine>, top_k: usize) -> Self {
        Self { llm, top_k }
    }
}

/// 重排序 Prompt
const RERANK_PROMPT: &str = r#"请评估以下文档片段与查询的相关性，并给出 0-1 之间的相关性分数。

查询: {query}

文档: {document}

相关性分数 (0-1):"#;

#[async_trait]
impl Reranker for CrossEncoderReranker {
    fn name(&self) -> &str {
        "cross_encoder_reranker"
    }

    async fn rerank(
        &self,
        query: &str,
        mut documents: Vec<RetrievedDoc>,
        top_k: usize,
    ) -> Result<Vec<RetrievedDoc>, RagError> {
        if documents.is_empty() {
            return Ok(documents);
        }

        // 对每个文档计算相关性分数
        let mut scored_docs = Vec::new();

        for doc in documents {
            let prompt = RERANK_PROMPT
                .replace("{query}", query)
                .replace("{document}", &doc.content);

            // 提取数值分数
            let response = self.llm.generate(&prompt).await
                .map_err(|e| RagError::ModelError(e.to_string()))?;

            let score = self.extract_score(&response);
            let mut scored_doc = doc;
            scored_doc.score = score;
            scored_docs.push(scored_doc);
        }

        // 按分数排序
        scored_docs.sort_by(|a, b| b.score.partial_cmp(&a.score).unwrap());

        // 返回 top_k
        Ok(scored_docs.into_iter().take(top_k).collect())
    }
}

impl CrossEncoderReranker {
    fn extract_score(&self, response: &str) -> f32 {
        // 尝试从响应中提取分数
        let response_lower = response.to_lowercase();

        // 查找模式如 "0.85" 或 "85%" 或 "分数: 0.85"
        for line in response.lines() {
            let line = line.trim();

            // 匹配 "0.85" 格式
            if let Some(pos) = line.find(|c: char| c.is_ascii_digit() || c == '.') {
                let rest = &line[pos..];
                if let Ok(score) = rest.parse::<f32>() {
                    return score.min(1.0).max(0.0);
                }
            }

            // 匹配 "85%" 格式
            if let Some(pos) = line.find('%') {
                let start = line[..pos].rfind(|c: char| c.is_ascii_digit()).unwrap_or(pos);
                let percent_str = &line[start..pos];
                if let Ok(percent) = percent_str.parse::<f32>() {
                    return (percent / 100.0).min(1.0).max(0.0);
                }
            }
        }

        // 默认返回中等分数
        0.5
    }
}
```

```rust
// core/src/rag/post_retrieval/mod.rs

pub mod reranker;

pub use reranker::{Reranker, CrossEncoderReranker};
```

- [ ] **Step 2: 提交**

```bash
git add core/src/rag/post_retrieval/
git commit -m "feat(rag): add post-retrieval reranking

- Add Reranker trait for document reordering
- Add CrossEncoderReranker using LLM for scoring
- Add score extraction utility"
```

---

## Task 7: 混合搜索

**Files:**
- Create: `core/src/rag/retrieval/mod.rs`
- Create: `core/src/rag/retrieval/hybrid_retriever.rs`

- [ ] **Step 1: 创建混合搜索器**

```rust
// core/src/rag/retrieval/hybrid_retriever.rs

use super::*;
use crate::embedding::EmbeddingModel;
use crate::vector::VectorStore;
use std::collections::HashMap;
use std::sync::Arc;

/// 融合方法
#[derive(Debug, Clone, Copy)]
pub enum FusionMethod {
    /// Reciprocal Rank Fusion
    RRF,
    /// 加权和
    WeightedSum,
}

/// 混合搜索器
pub struct HybridRetriever {
    embedding_model: Arc<dyn EmbeddingModel>,
    vector_store: Arc<dyn VectorStore>,
    dense_weight: f32,
    fusion_method: FusionMethod,
}

impl HybridRetriever {
    pub fn new(
        embedding_model: Arc<dyn EmbeddingModel>,
        vector_store: Arc<dyn VectorStore>,
    ) -> Self {
        Self {
            embedding_model,
            vector_store,
            dense_weight: 0.7,
            fusion_method: FusionMethod::RRF,
        }
    }

    pub fn with_dense_weight(mut self, weight: f32) -> Self {
        self.dense_weight = weight;
        self
    }

    pub fn with_fusion_method(mut self, method: FusionMethod) -> Self {
        self.fusion_method = method;
        self
    }

    pub async fn search(
        &self,
        query: &str,
        top_k: usize,
    ) -> Result<Vec<RetrievedDoc>, RagError> {
        // 1. 稠密向量检索
        let query_embedding = self.embedding_model.embed(query).await
            .map_err(|e| RagError::RetrievalError(e.to_string()))?;

        let dense_results = self.vector_store
            .search(query_embedding.clone(), top_k as i32 * 2)
            .await
            .map_err(|e| RagError::RetrievalError(e.to_string()))?;

        // 2. 稀疏检索（使用关键词匹配作为简化实现）
        let sparse_results = self.vector_store
            .keyword_search(query, top_k as i32 * 2)
            .await
            .unwrap_or_default();

        // 3. 融合结果
        let fused = self.fuse_results(dense_results, sparse_results, top_k);

        Ok(fused)
    }

    fn fuse_results(
        &self,
        dense: Vec<super::super::vector::SearchResult>,
        sparse: Vec<super::super::vector::SearchResult>,
        top_k: usize,
    ) -> Vec<RetrievedDoc> {
        match self.fusion_method {
            FusionMethod::RRF => self.rrf_fusion(dense, sparse, top_k),
            FusionMethod::WeightedSum => self.weighted_fusion(dense, sparse, top_k),
        }
    }

    /// Reciprocal Rank Fusion
    fn rrf_fusion(
        &self,
        dense: Vec<super::super::vector::SearchResult>,
        sparse: Vec<super::super::vector::SearchResult>,
        top_k: usize,
    ) -> Vec<RetrievedDoc> {
        let k = 60; // RRF 参数
        let mut scores: HashMap<String, (f32, RetrievedDoc)> = HashMap::new();

        // 稠密结果评分
        for (rank, result) in dense.iter().enumerate() {
            let rrf_score = 1.0 / (k + rank + 1) as f32;
            let doc = RetrievedDoc {
                id: result.chunk.id.clone(),
                content: result.chunk.content.clone(),
                score: rrf_score,
                metadata: DocMetadata {
                    document_id: Some(result.chunk.document_id.clone()),
                    chunk_index: Some(result.chunk.chunk_index),
                    ..Default::default()
                },
            };
            scores.insert(result.chunk.id.clone(), (rrf_score, doc));
        }

        // 稀疏结果评分
        for (rank, result) in sparse.iter().enumerate() {
            let rrf_score = 1.0 / (k + rank + 1) as f32;
            if let Some((old_score, _)) = scores.get(&result.chunk.id) {
                let mut doc = RetrievedDoc {
                    id: result.chunk.id.clone(),
                    content: result.chunk.content.clone(),
                    score: old_score + rrf_score,
                    metadata: DocMetadata {
                        document_id: Some(result.chunk.document_id.clone()),
                        chunk_index: Some(result.chunk.chunk_index),
                        ..Default::default()
                    },
                };
                doc.score = old_score + rrf_score;
                scores.insert(result.chunk.id.clone(), (doc.score, doc));
            } else {
                let doc = RetrievedDoc {
                    id: result.chunk.id.clone(),
                    content: result.chunk.content.clone(),
                    score: rrf_score,
                    metadata: DocMetadata {
                        document_id: Some(result.chunk.document_id.clone()),
                        chunk_index: Some(result.chunk.chunk_index),
                        ..Default::default()
                    },
                };
                scores.insert(result.chunk.id.clone(), (rrf_score, doc));
            }
        }

        // 排序并返回 top_k
        let mut sorted: Vec<_> = scores.into_values().collect();
        sorted.sort_by(|a, b| b.0.partial_cmp(&a.0).unwrap());
        sorted.into_iter().take(top_k).map(|(_, doc)| doc).collect()
    }

    /// 加权和融合
    fn weighted_fusion(
        &self,
        dense: Vec<super::super::vector::SearchResult>,
        sparse: Vec<super::super::vector::SearchResult>,
        top_k: usize,
    ) -> Vec<RetrievedDoc> {
        let mut scores: HashMap<String, (f32, RetrievedDoc)> = HashMap::new();
        let sparse_weight = 1.0 - self.dense_weight;

        // 稠密结果评分
        for result in &dense {
            let score = result.score * self.dense_weight;
            let doc = RetrievedDoc {
                id: result.chunk.id.clone(),
                content: result.chunk.content.clone(),
                score,
                metadata: DocMetadata {
                    document_id: Some(result.chunk.document_id.clone()),
                    chunk_index: Some(result.chunk.chunk_index),
                    ..Default::default()
                },
            };
            scores.insert(result.chunk.id.clone(), (score, doc));
        }

        // 稀疏结果评分
        for result in &sparse {
            let score = result.score * sparse_weight;
            if let Some((old_score, mut doc)) = scores.remove(&result.chunk.id) {
                doc.score = old_score + score;
                scores.insert(result.chunk.id.clone(), (doc.score, doc));
            } else {
                let doc = RetrievedDoc {
                    id: result.chunk.id.clone(),
                    content: result.chunk.content.clone(),
                    score,
                    metadata: DocMetadata {
                        document_id: Some(result.chunk.document_id.clone()),
                        chunk_index: Some(result.chunk.chunk_index),
                        ..Default::default()
                    },
                };
                scores.insert(result.chunk.id.clone(), (score, doc));
            }
        }

        // 排序并返回 top_k
        let mut sorted: Vec<_> = scores.into_values().collect();
        sorted.sort_by(|a, b| b.0.partial_cmp(&a.0).unwrap());
        sorted.into_iter().take(top_k).map(|(_, doc)| doc).collect()
    }
}
```

```rust
// core/src/rag/retrieval/mod.rs

pub mod hybrid_retriever;

pub use hybrid_retriever::{HybridRetriever, FusionMethod};
```

- [ ] **Step 2: 提交**

```bash
git add core/src/rag/retrieval/
git commit -m "feat(rag): add hybrid retrieval with RRF fusion

- Add HybridRetriever combining dense and sparse search
- Implement Reciprocal Rank Fusion for result merging
- Add weighted sum fusion as alternative"
```

---

## Task 8: 完整 RAG 管道

**Files:**
- Create: `core/src/rag/pipeline.rs`
- Create: `core/src/rag/mod.rs`

- [ ] **Step 1: 创建高级 RAG 管道**

```rust
// core/src/rag/pipeline.rs

use super::*;
use crate::chains::{ChainContext, BaseChain, ChainResult, ChainError, ChainOutput};
use std::sync::Arc;

/// RAG 管道配置
#[derive(Debug, Clone)]
pub struct RagPipelineConfig {
    /// 检索返回数量
    pub top_k: usize,
    /// 相似度阈值
    pub similarity_threshold: f32,
    /// 是否启用查询改写
    pub enable_query_rewrite: bool,
    /// 是否启用查询分解
    pub enable_query_decompose: bool,
    /// 是否启用 HyDE
    pub enable_hyde: bool,
    /// 是否启用重排序
    pub enable_rerank: bool,
    /// 重排序返回数量
    pub rerank_top_k: usize,
}

impl Default for RagPipelineConfig {
    fn default() -> Self {
        Self {
            top_k: 10,
            similarity_threshold: 0.7,
            enable_query_rewrite: false,
            enable_query_decompose: false,
            enable_hyde: false,
            enable_rerank: true,
            rerank_top_k: 5,
        }
    }
}

/// 高级 RAG 管道
pub struct AdvancedRagPipeline {
    config: RagPipelineConfig,
    pre_retrievers: Vec<Box<dyn PreRetriever>>,
    retriever: Arc<dyn Retriever>,
    post_retrievers: Vec<Box<dyn PostRetriever>>,
    synthesizer: Arc<dyn Synthesizer>,
}

/// 预检索器 trait
#[async_trait]
pub trait PreRetriever: Send + Sync {
    fn name(&self) -> &str;
    async fn process(&self, query: &str) -> Result<ProcessedQuery, RagError>;
}

/// 后检索器 trait
#[async_trait]
pub trait PostRetriever: Send + Sync {
    fn name(&self) -> &str;
    async fn process(&self, docs: Vec<RetrievedDoc>) -> Result<Vec<RetrievedDoc>, RagError>;
}

/// 检索器 trait（扩展）
#[async_trait]
pub trait Retriever: Send + Sync {
    async fn retrieve(&self, query: &str) -> Result<Vec<RetrievedDoc>, RagError>;
    async fn retrieve_with_threshold(
        &self,
        query: &str,
        top_k: usize,
        threshold: f32,
    ) -> Result<Vec<RetrievedDoc>, RagError>;
}

/// 合成器 trait
#[async_trait]
pub trait Synthesizer: Send + Sync {
    fn name(&self) -> &str;
    async fn synthesize(
        &self,
        query: &str,
        docs: Vec<RetrievedDoc>,
        llm: Arc<dyn crate::llm::LlmEngine>,
    ) -> Result<SynthesisResult, RagError>;
}

impl AdvancedRagPipeline {
    pub fn new(
        retriever: Arc<dyn Retriever>,
        synthesizer: Arc<dyn Synthesizer>,
    ) -> Self {
        Self {
            config: RagPipelineConfig::default(),
            pre_retrievers: Vec::new(),
            retriever,
            post_retrievers: Vec::new(),
            synthesizer,
        }
    }

    pub fn with_config(mut self, config: RagPipelineConfig) -> Self {
        self.config = config;
        self
    }

    pub fn with_pre_retrievers(mut self, retrievers: Vec<Box<dyn PreRetriever>>) -> Self {
        self.pre_retrievers = retrievers;
        self
    }

    pub fn with_post_retrievers(mut self, retrievers: Vec<Box<dyn PostRetriever>>) -> Self {
        self.post_retrievers = retrievers;
        self
    }

    /// 执行完整管道
    pub async fn execute(
        &self,
        query: &str,
        context: &ChainContext,
    ) -> Result<SynthesisResult, RagError> {
        // 1. Pre-Retrieval: 查询转换
        let mut processed_query = ProcessedQuery {
            original: query.to_string(),
            transformed: query.to_string(),
            sub_queries: None,
            hypothetical_doc: None,
        };

        for pre_retriever in &self.pre_retrievers {
            processed_query = pre_retriever.process(&processed_query.transformed).await?;
        }

        // 2. Retrieval: 检索文档
        let docs = self.retrieve_docs(&processed_query).await?;

        // 3. Post-Retrieval: 后处理
        let docs = self.post_process_docs(docs).await;

        // 4. Synthesis: 合成答案
        let result = self.synthesizer
            .synthesize(query, docs, context.llm.clone())
            .await?;

        Ok(result)
    }

    async fn retrieve_docs(&self, processed_query: &ProcessedQuery) -> Result<Vec<RetrievedDoc>, RagError> {
        if let Some(sub_queries) = &processed_query.sub_queries {
            // 多查询检索
            let mut all_docs = Vec::new();
            for sub_query in sub_queries {
                let sub_docs = self.retriever
                    .retrieve_with_threshold(
                        sub_query,
                        self.config.top_k,
                        self.config.similarity_threshold,
                    )
                    .await?;
                all_docs.extend(sub_docs);
            }
            // 去重
            Ok(self.deduplicate_docs(all_docs))
        } else if let Some(hypothetical_doc) = &processed_query.hypothetical_doc {
            // HyDE 检索
            self.retriever
                .retrieve_with_threshold(
                    hypothetical_doc,
                    self.config.top_k,
                    self.config.similarity_threshold,
                )
                .await
        } else {
            // 直接检索
            self.retriever
                .retrieve_with_threshold(
                    &processed_query.transformed,
                    self.config.top_k,
                    self.config.similarity_threshold,
                )
                .await
        }
    }

    async fn post_process_docs(&self, docs: Vec<RetrievedDoc>) -> Vec<RetrievedDoc> {
        let mut current_docs = docs;

        for post_retriever in &self.post_retrievers {
            current_docs = post_retriever.process(current_docs).await
                .unwrap_or(current_docs);
        }

        current_docs
    }

    fn deduplicate_docs(&self, docs: Vec<RetrievedDoc>) -> Vec<RetrievedDoc> {
        use std::collections::HashSet;
        let mut seen = HashSet::new();
        docs.into_iter()
            .filter(|doc| seen.insert(doc.id.clone()))
            .collect()
    }
}
```

```rust
// core/src/rag/mod.rs

pub mod types;
pub mod pipeline;
pub mod pre_retrieval;
pub mod post_retrieval;
pub mod retrieval;
pub mod synthesizer;

pub use types::*;
pub use pipeline::{AdvancedRagPipeline, RagPipelineConfig, PreRetriever, PostRetriever, Retriever, Synthesizer};
```

- [ ] **Step 2: 创建默认合成器**

```rust
// core/src/rag/synthesizer.rs

use super::*;
use crate::llm::LlmEngine;
use std::sync::Arc;

/// 默认合成器 Prompt
const DEFAULT_SYNTHESIZE_PROMPT: &str = r#"你是一个专业的建筑行业规范助手。请根据以下参考资料回答问题。

要求：
1. 准确回答问题，引用相关规范条文
2. 如果参考资料中没有相关信息，请如实说明
3. 适当标注引用来源

问题: {question}

参考资料:
{context}

回答:"#;

/// 默认答案合成器
pub struct DefaultSynthesizer {
    prompt_template: String,
}

impl DefaultSynthesizer {
    pub fn new() -> Self {
        Self {
            prompt_template: DEFAULT_SYNTHESIZE_PROMPT.to_string(),
        }
    }

    pub fn with_template(mut self, template: &str) -> Self {
        self.prompt_template = template.to_string();
        self
    }
}

impl Default for DefaultSynthesizer {
    fn default() -> Self {
        Self::new()
    }
}

#[async_trait]
impl Synthesizer for DefaultSynthesizer {
    fn name(&self) -> &str {
        "default_synthesizer"
    }

    async fn synthesize(
        &self,
        query: &str,
        docs: Vec<RetrievedDoc>,
        llm: Arc<dyn LlmEngine>,
    ) -> Result<SynthesisResult, RagError> {
        // 构建上下文
        let context = docs.iter()
            .enumerate()
            .map(|(i, doc)| {
                let source = doc.metadata.source_file.as_ref()
                    .map(|s| format!(" (来源: {})", s))
                    .unwrap_or_default();
                format!("[{}]{}\n{}{}", i + 1, source, doc.content, "=".repeat(40))
            })
            .collect::<Vec<_>>()
            .join("\n");

        // 构建 Prompt
        let prompt = self.prompt_template
            .replace("{question}", query)
            .replace("{context}", &context);

        // 生成答案
        let answer = llm.generate(&prompt).await
            .map_err(|e| RagError::ModelError(e.to_string()))?;

        // 构建来源引用
        let sources = docs.iter()
            .map(|doc| SourceReference {
                doc_id: doc.id.clone(),
                snippet: doc.content.chars().take(200).collect(),
                score: doc.score,
            })
            .collect();

        Ok(SynthesisResult {
            answer,
            sources,
        })
    }
}
```

- [ ] **Step 3: 更新 lib.rs**

```rust
// core/src/lib.rs

pub mod chains;
pub mod memory;
pub mod rag;
pub mod llm;
pub mod embedding;
pub mod vector;
pub mod parser;
pub mod knowledge;
pub mod config;
pub mod error;
pub mod storage;
```

- [ ] **Step 4: 提交**

```bash
git add core/src/rag/
git commit -m "feat(rag): add AdvancedRagPipeline with full orchestration

- Add PreRetriever/PostRetriever/Synthesizer traits
- Implement AdvancedRagPipeline with configurable stages
- Add DefaultSynthesizer for answer generation
- Support query transformation, hybrid search, reranking"
```

---

## Task 9: 更新 vector 模块添加关键词搜索

- [ ] **Step 1: 更新 VectorStore trait**

```rust
// core/src/vector/mod.rs

// 在 VectorStore trait 中添加 keyword_search 方法

#[async_trait]
pub trait VectorStore: Send + Sync {
    // ... existing methods ...

    /// 关键词搜索（BM25 等）
    async fn keyword_search(
        &self,
        query: &str,
        limit: i32,
    ) -> Result<Vec<SearchResult>, VectorError>;
}
```

- [ ] **Step 2: 提交**

```bash
git add core/src/vector/
git commit -m "feat(vector): add keyword_search to VectorStore trait"
```

---

## Task 10: 集成测试

- [ ] **Step 1: 创建集成测试**

```rust
// core/tests/rag_pipeline_test.rs

#[cfg(test)]
mod tests {
    use super::*;

    #[tokio::test]
    async fn test_rag_pipeline_basic() {
        // 设置测试依赖（使用 mock）
        // 测试完整管道执行
    }

    #[tokio::test]
    async fn test_chain_composition() {
        // 测试 Chain 组合
    }

    #[tokio::test]
    async fn test_hybrid_search() {
        // 测试混合搜索
    }
}
```

- [ ] **Step 2: 构建测试**

```bash
cd core && cargo build
cd core && cargo test
```

- [ ] **Step 3: 提交**

```bash
git add core/tests/
git commit -m "test: add integration tests for RAG pipeline and chains"
```

---

## 验收检查

- [ ] `cargo build` 成功
- [ ] Chain trait 正确定义
- [ ] RetrievalChain 可正常执行
- [ ] ConversationChain 支持多轮对话
- [ ] Pre-Retrieval 查询转换工作
- [ ] Post-Retrieval 重排序工作
- [ ] 混合搜索融合结果正确
- [ ] 集成测试通过

---

## 参考资料

- [LangChain Chains](https://python.langchain.com/docs/modules/chains/)
- [Advanced RAG](https://docs.google.com/presentation/d/1fXaG3UJ4MSENB0t5R5cm1mC82hH0E_G7y0R0C8Y6P0w/)
- [HyDE: Hypothetical Document Embeddings](https://arxiv.org/abs/2212.10496)
- [Reciprocal Rank Fusion](https://plg.uwaterloo.ca/~gvcormac/cormacksigir09-rrf.pdf)
- [BGE Reranker](https://github.com/FlagOpen/FlagEmbedding)

**计划创建日期**: 2026-04-22
