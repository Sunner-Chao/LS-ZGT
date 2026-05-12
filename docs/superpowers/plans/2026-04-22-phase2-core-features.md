# 筑规通桌面客户端 - 阶段2：核心功能实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development

**Goal:** 实现 RAG 引擎、本地 LLM 集成、嵌入模型、Milvus 向量管理、对话功能。

**Architecture:** RAG 流程：用户问题 → 预处理 → 向量嵌入 → Milvus 检索 → 上下文构建 → LLM 生成 → 流式响应

**Tech Stack:** Rust (llama.cpp, candle, reqwest), Milvus Lite

---

## Task 1: LLM 适配器架构

**Files:**
- Modify: `core/src/llm/mod.rs`
- Create: `core/src/llm/local.rs`
- Create: `core/src/llm/anthropic.rs`
- Create: `core/src/llm/openai.rs`

- [ ] **Step 1: 创建 LLM 模块入口**

```rust
// core/src/llm/mod.rs

pub mod local;
pub mod anthropic;
pub mod openai;

use async_trait::async_trait;
use serde::{Deserialize, Serialize};
use futures::Stream;

/// LLM 提供者枚举
#[derive(Debug, Clone, Copy, Serialize, Deserialize, PartialEq, Eq)]
pub enum LlmProvider {
 Local, // 本地 llama.cpp
 Anthropic, // Claude API
 DeepSeek, // DeepSeek API (Anthropic 格式)
 OpenAI, // OpenAI API
 Qwen,  // 通义千问
}

impl Default for LlmProvider {
 fn default() -> Self {
 Self::Local
 }
}

/// LLM trait - 所有 LLM 实现必须实现此接口
#[async_trait]
pub trait LlmEngine: Send + Sync {
 /// 流式生成回复
 async fn generate_stream(
 &self,
 prompt: &str,
 config: &GenerationConfig,
 ) -> Result<impl Stream<Item = Result<String, LlmError>>, LlmError>;
 
 /// 获取模型信息
 fn get_model_info(&self) -> ModelInfo;
}

/// 生成配置
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct GenerationConfig {
  pub max_tokens: Option<u32>,
 pub temperature: f32,
 pub top_p: Option<f32>,
 pub stop_sequences: Option<Vec<String>>,
}

impl Default for GenerationConfig {
 fn default() -> Self {
  Self {
 max_tokens: Some(4096),
 temperature: 0.7,
 top_p: None,
 stop_sequences: None,
 }
 }
}

/// 模型信息
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ModelInfo {
 pub name: String,
 pub provider: LlmProvider,
 pub context_length: usize,
}

/// LLM 错误
#[derive(Debug, thiserror::Error)]
pub enum LlmError {
 #[error("模型未加载: {0}")]
 ModelNotLoaded(String),
 
 #[error("生成失败: {0}")]
 GenerationFailed(String),
 
  #[error("API 请求失败: {0}")]
 ApiError(String),
 
 #[error("超时: {0}")]
 Timeout(String),
 
 #[error("无效参数: {0}")]
 InvalidArgument(String),
}
```

- [ ] **Step 2: 创建本地 LLM 实现 (llama.cpp)**

```rust
// core/src/llm/local.rs

use super::*;
use std::path::Path;
use std::sync::Arc;
use tokio::sync::Mutex;

/// 本地 LLM 引擎 (llama.cpp)
pub struct LocalLlmEngine {
 model: Option<Arc<Mutex<llama_core::Model>>>,
 params: LocalModelParams,
}

/// 本地模型参数
#[derive(Debug, Clone)]
pub struct LocalModelParams {
 pub model_path: PathBuf,
 pub context_length: usize,
 pub gpu_layers: i32,
 pub threads: i32,
 pub batch_size: usize,
}

impl Default for LocalModelParams {
 fn default() -> Self {
 Self {
 model_path: PathBuf::new(),
 context_length: 4096,
 gpu_layers: 35,
 threads: 4,
 batch_size: 512,
 }
 }
}

#[async_trait]
impl LlmEngine for LocalLlmEngine {
 async fn generate_stream(
 &self,
 prompt: &str,
 config: &GenerationConfig,
 ) -> Result<impl Stream<Item = Result<String, LlmError>>, LlmError> {
 let model = self.model.as_ref()
 .ok_or_else(|| LlmError::ModelNotLoaded("模型未加载".to_string()))?
 .lock().await;
 
 let params = llama_core::InferenceParams {
 n_predict: config.max_tokens.unwrap_or(4096) as usize,
 temperature: config.temperature,
 top_p: config.top_p.unwrap_or(0.9),
 ..Default::default()
 };
 
 let mut stream = model.generate(prompt, params);
 
 Ok(async_stream::stream! {
 while let Some(token) = stream.next().await {
 yield Ok(token);
 }
 })
 }
 
 fn get_model_info(&self) -> ModelInfo {
 ModelInfo {
  name: "local-model".to_string(),
 provider: LlmProvider::Local,
 context_length: self.params.context_length,
 }
 }
}

impl LocalLlmEngine {
 pub fn new(params: LocalModelParams) -> Self {
 Self {
 model: None,
 params,
 }
 }
 
 /// 加载模型
 pub async fn load_model(&mut self) -> Result<(), LlmError> {
 if self.params.model_path.to_str() == Some("") {
 return Err(LlmError::ModelNotLoaded("未指定模型路径".to_string()));
 }
 
 let model = llama_core::Model::load(&self.params.model_path)
 .map_err(|e| LlmError::GenerationFailed(e.to_string()))?;
 
 self.model = Some(Arc::new(Mutex::new(model)));
 Ok(())
 }
 
  /// 卸载模型
 pub fn unload(&mut self) {
 self.model = None;
 }
 
 /// 检查模型是否已加载
 pub fn is_loaded(&self) -> bool {
 self.model.is_some()
 }
}
```

- [ ] **Step 3: 创建 Anthropic 格式客户端**

```rust
// core/src/llm/anthropic.rs

use super::*;
use reqwest::Client;
use serde_json::json;

/// Anthropic API 客户端
pub struct AnthropicClient {
 client: Client,
 api_key: String,
 base_url: String,
 model: String,
}

impl AnthropicClient {
 pub fn new(api_key: String, model: String) -> Self {
 Self {
 client: Client::new(),
 api_key,
 base_url: "https://api.anthropic.com/v1".to_string(),
 model,
 }
 }
 
 pub fn with_base_url(mut self, base_url: String) -> Self {
 self.base_url = base_url;
 self
 }
}

#[async_trait]
impl LlmEngine for AnthropicClient {
 async fn generate_stream(
 &self,
 prompt: &str,
 config: &GenerationConfig,
 ) -> Result<impl Stream<Item = Result<String, LlmError>>, LlmError> {
 let body = json!({
 "model": self.model,
 "prompt": prompt,
 "max_tokens_to_sample": config.max_tokens.unwrap_or(4096),
 "temperature": config.temperature,
 "stream": true
 });
 
 let response = self.client
 .post(format!("{}/complete", self.base_url))
 .header("x-api-key", &self.api_key)
 .header("anthropic-version", "2023-06-01")
 .json(&body)
 .send()
 .await
 .map_err(|e| LlmError::ApiError(e.to_string()))?;
 
 // 处理流式响应...
 Ok(async_stream::stream! {
 // 解析 SSE 流
 })
 }
 
 fn get_model_info(&self) -> ModelInfo {
 ModelInfo {
 name: self.model.clone(),
 provider: LlmProvider::Anthropic,
 context_length: 200_000, // Claude 3.5
 }
 }
}
```

- [ ] **Step 4: 创建 OpenAI 格式客户端**

```rust
// core/src/llm/openai.rs

use super::*;
use reqwest::Client;
use serde_json::json;

/// OpenAI 兼容格式客户端
pub struct OpenAiCompatibleClient {
 client: Client,
 api_key: String,
 base_url: String,
 model: String,
}

impl OpenAiCompatibleClient {
 pub fn new(api_key: String, model: String, base_url: String) -> Self {
 Self {
 client: Client::new(),
 api_key,
 base_url,
 model,
 }
 }
}

#[async_trait]
impl LlmEngine for OpenAiCompatibleClient {
 async fn generate_stream(
 &self,
 prompt: &str,
 config: &GenerationConfig,
 ) -> Result<impl Stream<Item = Result<String, LlmError>>, LlmError> {
 let body = json!({
 "model": self.model,
 "messages": [{"role": "user", "content": prompt}],
 "max_tokens": config.max_tokens.unwrap_or(4096),
  "temperature": config.temperature,
 "top_p": config.top_p,
 "stream": true
 });
 
 let response = self.client
 .post(format!("{}/chat/completions", self.base_url))
 .header("Authorization", format!("Bearer {}", self.api_key))
 .header("Content-Type", "application/json")
 .json(&body)
 .send()
 .await
 .map_err(|e| LlmError::ApiError(e.to_string()))?;
 
 // 处理流式响应...
  Ok(async_stream::stream! {
 // 解析 SSE 流
 })
 }
 
 fn get_model_info(&self) -> ModelInfo {
 ModelInfo {
 name: self.model.clone(),
 provider: LlmProvider::OpenAI,
 context_length: 128_000, // GPT-4 Turbo
 }
 }
}
```

- [ ] **Step 5: 提交**

```bash
git add core/src/llm/
git commit -m "feat(llm): add LLM adapter architecture

- Add LlmEngine trait for unified interface
- Add LocalLlmEngine for llama.cpp
- Add AnthropicClient for Claude API
- Add OpenAiCompatibleClient for OpenAI-compatible APIs"
```

---

## Task 2: 嵌入模型集成

**Files:**
- Create: `core/src/embedding/mod.rs`
- Create: `core/src/embedding/bge_m3.rs`

- [ ] **Step 1: 创建嵌入模型模块**

```rust
// core/src/embedding/mod.rs

pub mod bge_m3;

use async_trait::async_trait;
use serde::{Deserialize, Serialize};

#[async_trait]
pub trait EmbeddingModel: Send + Sync {
 /// 生成嵌入向量
 async fn embed(&self, text: &str) -> Result<Vec<f32>, EmbeddingError>;
 
 /// 批量生成嵌入向量
 async fn embed_batch(&self, texts: &[String]) -> Result<Vec<Vec<f32>>, EmbeddingError>;
 
 /// 获取嵌入维度
 fn dimension(&self) -> usize;
 
 /// 获取模型名称
 fn name(&self) -> &str;
}

#[derive(Debug, thiserror::Error)]
pub enum EmbeddingError {
 #[error("模型未加载: {0}")]
 ModelNotLoaded(String),
 
 #[error("推理失败: {0}")]
 InferenceFailed(String),
}
```

- [ ] **Step 2: 创建 BGE-M3 实现**

```rust
// core/src/embedding/bge_m3.rs

use super::*;

pub struct BgeM3Model {
 // 使用 candle 加载模型
 // 简化实现
}

#[async_trait]
impl EmbeddingModel for BgeM3Model {
 async fn embed(&self, text: &str) -> Result<Vec<f32>, EmbeddingError> {
 // 调用 candle 推理
 Ok(vec![0.0; 1024]) // BGE-M3 维度
 }
 
 async fn embed_batch(&self, texts: &[String]) -> Result<Vec<Vec<f32>>, EmbeddingError> {
 texts.iter().map(|t| self.embed(t)).collect()
 }
 
 fn dimension(&self) -> usize {
 1024 // BGE-M3 维度
 }
 
 fn name(&self) -> &str {
 "BAAI/bge-m3"
 }
}

impl BgeM3Model {
 pub fn new() -> Self {
 Self {}
 }
}
```

- [ ] **Step 3: 提交**

```bash
git add core/src/embedding/
git commit -m "feat(embedding): add embedding model module with BGE-M3"
```

---

## Task 3: 向量管理 (Milvus)

**Files:**
- Create: `core/src/vector/mod.rs`
- Create: `core/src/vector/milvus.rs`

- [ ] **Step 1: 创建向量管理模块**

```rust
// core/src/vector/mod.rs

pub mod milvus;

use serde::{Deserialize, Serialize};

/// 文档片段
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct DocumentChunk {
  pub id: String,
 pub document_id: String,
 pub content: String,
 pub metadata: ChunkMetadata,
 pub chunk_index: u32,
}

/// 片段元数据
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ChunkMetadata {
 pub page: Option<u32>,
 pub line_start: Option<u32>,
 pub line_end: Option<u32>,
}

/// 搜索结果
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct SearchResult {
 pub chunk: DocumentChunk,
 pub score: f32,
 pub distance: f32,
}
```

- [ ] **Step 2: 创建 Milvus 客户端**

```rust
// core/src/vector/milvus.rs

use super::*;
use milvus_client::Client;

pub struct MilvusVectorStore {
 client: Client,
 collection_name: String,
 dimension: usize,
}

impl MilvusVectorStore {
 pub async fn new(uri: &str, collection_name: &str, dimension: usize) -> Result<Self, VectorError> {
 let client = Client::new(uri).await
 .map_err(|e| VectorError::ConnectionFailed(e.to_string()))?;
 
 Ok(Self {
 client,
  collection_name: collection_name.to_string(),
 dimension,
 })
 }
 
 /// 创建集合
 pub async fn create_collection(&self) -> Result<(), VectorError> {
 self.client.create_collection(&milvus_client::CreateCollectionRequest {
 collection_name: self.collection_name.clone(),
 dimension: self.dimension as i64,
 ..Default::default()
 }).await
 .map_err(|e| VectorError::OperationFailed(e.to_string()))?;
 Ok(())
 }
 
 /// 插入向量
 pub async fn insert(&self, chunks: Vec<DocumentChunk>, embeddings: Vec<Vec<f32>>) -> Result<(), VectorError> {
 if chunks.len() != embeddings.len() {
 return Err(VectorError::InvalidInput("chunks and embeddings length mismatch".to_string()));
 }
 
 let vectors = embeddings.iter().map(|e| e.as_slice()).collect::<Vec<_>>();
 
 self.client.insert(&milvus_client::InsertRequest {
 collection_name: self.collection_name.clone(),
 vectors,
 ..Default::default()
 }).await
 .map_err(|e| VectorError::OperationFailed(e.to_string()))?;
 
 Ok(())
 }
 
 /// 搜索
 pub async fn search(&self, query_embedding: Vec<f32>, top_k: u32, threshold: f32) -> Result<Vec<SearchResult>, VectorError> {
 let results = self.client.search(&milvus_client::SearchRequest {
 collection_name: self.collection_name.clone(),
 vectors: vec![&query_embedding],
 top_k: top_k as i64,
 ..Default::default()
 }).await
 .map_err(|e| VectorError::OperationFailed(e.to_string()))?;
 
 // 转换结果...
 Ok(vec![])
 }
}

#[derive(Debug, thiserror::Error)]
pub enum VectorError {
 #[error("连接失败: {0}")]
 ConnectionFailed(String),
 
 #[error("操作失败: {0}")]
  OperationFailed(String),
 
 #[error("无效输入: {0}")]
 InvalidInput(String),
}
```

- [ ] **Step 3: 提交**

```bash
git add core/src/vector/
git commit -m "feat(vector): add Milvus vector store integration"
```

---

## Task 4: RAG 引擎

**Files:**
- Create: `core/src/rag/mod.rs`
- Create: `core/src/rag/engine.rs`

- [ ] **Step 1: 创建 RAG 引擎**

```rust
// core/src/rag/engine.rs

use crate::embedding::EmbeddingModel;
use crate::vector::{MilvusVectorStore, SearchResult, DocumentChunk};
use crate::llm::{LlmEngine, GenerationConfig};

pub struct RagEngine<E: EmbeddingModel, L: LlmEngine> {
 embedding_model: E,
 vector_store: MilvusVectorStore,
 llm: L,
 config: RagConfig,
}

#[derive(Debug, Clone)]
pub struct RagConfig {
 pub top_k: u32,
 pub similarity_threshold: f32,
 pub max_context_length: usize,
}

impl Default for RagConfig {
 fn default() -> Self {
 Self {
 top_k: 5,
 similarity_threshold: 0.7,
 max_context_length: 4096,
 }
 }
}

impl<E: EmbeddingModel, L: LlmEngine> RagEngine<E, L> {
 pub fn new(
 embedding_model: E,
 vector_store: MilvusVectorStore,
 llm: L,
 config: RagConfig,
 ) -> Self {
 Self {
 embedding_model,
 vector_store,
 llm,
 config,
 }
 }
 
 /// 处理用户问题并生成回复
 pub async fn process(
 &self,
 question: &str,
 config: &GenerationConfig,
 ) -> Result<RagResponse, RagError> {
 // 1. 问题预处理
 let processed_question = self.preprocess_question(question);
 
 // 2. 生成嵌入向量
 let embedding = self.embedding_model.embed(&processed_question)
 .await
 .map_err(|e| RagError::EmbeddingFailed(e.to_string()))?;
 
 // 3. 向量检索
 let search_results = self.vector_store.search(
 embedding,
 self.config.top_k,
 self.config.similarity_threshold,
 ).await
 .map_err(|e| RagError::RetrievalFailed(e.to_string()))?;
 
 // 4. 构建上下文
 let context = self.build_context(&search_results);
 
 // 5. 构建 prompt
 let prompt = self.build_prompt(&question, &context);
 
 // 6. 生成回复
 let mut stream = self.llm.generate_stream(&prompt, config)
 .await
 .map_err(|e| RagError::GenerationFailed(e.to_string()))?;
 
 Ok(RagResponse {
 question: question.to_string(),
 answer: String::new(), // 流式更新
 sources: search_results,
 context_used: context,
 })
 }
 
  fn preprocess_question(&self, question: &str) -> String {
 question.trim().to_string()
 }
 
 fn build_context(&self, results: &[SearchResult]) -> String {
 results.iter()
 .map(|r| format!("[来源 {}]\n{}\n", r.chunk.document_id, r.chunk.content))
 .collect::<Vec<_>>()
 .join("\n---\n")
 }
 
 fn build_prompt(&self, question: &str, context: &str) -> String {
 format!(
 "你是一个专业的建筑行业规范助手。请根据以下参考资料回答问题。\n\n参考资料:\n{}\n\n问题: {}\n\n请给出准确、专业的回答，并在回答中引用相关规范条文。",
 context,
 question
 )
 }
}

pub struct RagResponse {
 pub question: String,
 pub answer: String,
 pub sources: Vec<SearchResult>,
 pub context_used: String,
}

#[derive(Debug, thiserror::Error)]
pub enum RagError {
 #[error("预处理失败: {0}")]
  PreprocessFailed(String),
 
 #[error("嵌入生成失败: {0}")]
 EmbeddingFailed(String),
 
 #[error("检索失败: {0}")]
 RetrievalFailed(String),
 
 #[error("生成失败: {0}")]
 GenerationFailed(String),
}
```

- [ ] **Step 2: 提交**

```bash
git add core/src/rag/
git commit -m "feat(rag): add RAG engine with retrieval and generation"
```

---

## Task 5: 对话管理

**Files:**
- Create: `core/src/chat/mod.rs`
- Create: `core/src/chat/session.rs`

- [ ] **Step 1: 创建对话模块**

```rust
// core/src/chat/session.rs

use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ChatSession {
 pub id: String,
 pub title: String,
 pub created_at: i64,
 pub updated_at: i64,
 pub message_count: u32,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ChatMessage {
 pub id: String,
 pub session_id: String,
 pub role: MessageRole,
 pub content: String,
 pub sources: Option<Vec<SourceReference>>,
 pub created_at: i64,
}

#[derive(Debug, Clone, Copy, Serialize, Deserialize, PartialEq, Eq)]
pub enum MessageRole {
 User,
 Assistant,
 System,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct SourceReference {
 pub document_id: String,
 pub chunk_id: String,
 pub snippet: String,
 pub score: f32,
}
```

- [ ] **Step 2: 提交**

```bash
git add core/src/chat/
git commit -m "feat(chat): add chat session and message models"
```

---

## Task 6: 更新 lib.rs 并测试

- [ ] **Step 1: 更新 core/src/lib.rs**

```rust
pub mod llm;
pub mod embedding;
pub mod vector;
pub mod rag;
pub mod chat;
```

- [ ] **Step 2: 添加依赖到 Cargo.toml**

```toml
# 添加到 [dependencies]
async-trait = "0.1"
reqwest = { version = "0.12", features = ["json", "stream"] }
async-stream = "0.3"
milvus-client = "0.5"
```

- [ ] **Step 3: 运行测试**

```bash
cd core && cargo build
```

- [ ] **Step 4: 提交**

```bash
git add core/
git commit -m "feat: integrate all core modules (llm, embedding, vector, rag, chat)"
```

---

## 验收检查

- [ ] `cargo build` 成功
- [ ] LLM trait 正确实现
- [ ] RAG 引擎流程完整

**计划创建日期**: 2026-04-22
