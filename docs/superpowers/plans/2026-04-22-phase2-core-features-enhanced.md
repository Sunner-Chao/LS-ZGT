# 筑规通桌面客户端 - 核心功能增强方案 (Phase 2 Enhanced)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development

**Goal:** 引入LangChain风格架构、增强RAG能力、Agent能力和记忆系统

**Architecture:** LangChain风格链式架构 + 混合检索RAG + ReAct Agent + 多层记忆系统

**Tech Stack:** Rust (llama.cpp, candle, reqwest), Milvus, LangChain-style abstractions

---

## 1. 架构改进概述

### 1.1 当前架构分析

**现有技术栈**:
- UI层: Flutter 3.22+ + Riverpod
- 核心服务: Rust + Tokio
- FFI桥接: flutter_rust_bridge v2
- 本地LLM: llama.cpp
- 向量数据库: Milvus
- 嵌入模型: BGE-M3

**现有架构的优势**:
- ✅ Flutter跨平台成熟
- ✅ Rust性能优秀
- ✅ 本地LLM嵌入式推理
- ✅ 流式输出支持

**现有架构的不足**:
- ❌ 缺乏链式组合能力
- ❌ Prompt管理硬编码
- ❌ 单一检索策略
- ❌ 无查询改写能力
- ❌ 无Agent工具调用机制
- ❌ 无结构化记忆系统
- ❌ 无性能追踪监控

### 1.2 目标架构

```
┌─────────────────────────────────────────────────────────────────────────┐
│                           UI层 (Flutter)                                 │
├─────────────────────────────────────────────────────────────────────────┤
│  ChatPage  │  KnowledgePage  │  AgentPage  │  SettingsPage              │
└────────┬─────────┴──────────┬────────────┴──────────┬───────────────────┘
         │                    │                       │
         │ FFI (flutter_rust_bridge)                  │
         ▼                    ▼                       ▼
┌─────────────────────────────────────────────────────────────────────────┐
│                           编排层 (Orchestration)                         │
├─────────────────────────────────────────────────────────────────────────┤
│  ChainExecutor   │  AgentExecutor      │  MemoryManager                 │
│  ┌─────────────┐ │  ┌───────────────┐  │  ┌─────────────────┐           │
│  │ LLMChain    │ │  │ ReActAgent    │  │  │ ConversationMem │           │
│  │ RAGChain    │ │  │ ToolAgent     │  │  │ VectorMemory    │           │
│  │ TransformCh │ │  │ PlannerAgent  │  │  │ SummaryMemory   │           │
│  └─────────────┘ │  └───────────────┘  │  └─────────────────┘           │
├──────────────────┴─────────────────────┴─────────────────────────────────┤
│                           核心层 (Core)                                  │
├─────────────────────────────────────────────────────────────────────────┤
│  PromptEngine     │  RetrievalEngine    │  LLMAdapter                   │
│  ┌─────────────┐ │  ┌───────────────┐  │  ┌─────────────────┐           │
│  │TemplateStore│ │  │ VectorRetriev │  │  │ LocalLLM        │           │
│  │ PromptCache │ │  │ KeywordRetriev│  │  │ CloudLLM        │           │
│  │ VariableSub │ │  │ HybridRetriev │  │  │ EmbeddingService│           │
│  └─────────────┘ │  │ Reranker      │  │  └─────────────────┘           │
│                  │  │ QueryRewriter │  │                                 │
│                  │  │ HyDE          │  │                                 │
│                  │  └───────────────┘  │                                 │
├──────────────────┴──────────────────────┴────────────────────────────────┤
│                           基础层 (Infrastructure)                        │
├─────────────────────────────────────────────────────────────────────────┤
│  MilvusClient    │  SQLiteClient        │  FileStorage                  │
│  MetricsCollector│  TracingService      │  CacheManager                 │
└─────────────────────────────────────────────────────────────────────────┘
```

### 1.3 核心改进点

| 改进领域 | 现状 | 目标 |
|---------|------|------|
| **链式架构** | 硬编码流程 | 可组合的 Chain 抽象 |
| **检索策略** | 单一向量检索 | 混合检索 + 重排序 + 查询改写 |
| **Prompt管理** | 字符串拼接 | 模板化 + 版本管理 |
| **Agent能力** | 无 | ReAct + 工具调用 + 规划执行 |
| **记忆系统** | 简单对话历史 | 短期 + 长期 + 压缩摘要 |
| **可观测性** | 日志 | 追踪 + 指标 + 告警 |

---

## 2. LangChain风格链式架构设计

### 2.1 核心抽象

#### 2.1.1 Chain Trait定义

```rust
// core/src/chain/mod.rs

use async_trait::async_trait;
use serde::{Deserialize, Serialize};
use std::collections::HashMap;
use futures::Stream;

/// 链式执行的基础接口
/// 灵感来自 LangChain 的 Chain 抽象
#[async_trait]
pub trait Chain: Send + Sync {
    /// 获取链的名称
    fn name(&self) -> &str;
    
    /// 获取链的输入变量定义
    fn input_keys(&self) -> Vec<&str>;
    
    /// 获取链的输出变量定义
    fn output_keys(&self) -> Vec<&str>;
    
    /// 执行链（返回单个结果）
    async fn execute(&self, inputs: HashMap<String, serde_json::Value>) 
        -> Result<HashMap<String, serde_json::Value>, ChainError>;
    
    /// 流式执行（返回流）
    async fn execute_stream(
        &self, 
        inputs: HashMap<String, serde_json::Value>
    ) -> Result<impl Stream<Item = Result<ChainStep, ChainError>>, ChainError>;
    
    /// 验证输入
    fn validate_inputs(&self, inputs: &HashMap<String, serde_json::Value>) 
        -> Result<(), ChainError> {
        for key in self.input_keys() {
            if !inputs.contains_key(key) {
                return Err(ChainError::MissingInput(key.to_string()));
            }
        }
        Ok(())
    }
}

/// 链执行步骤（用于流式输出）
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ChainStep {
    pub chain_name: String,
    pub step_type: StepType,
    pub output: serde_json::Value,
    pub metadata: HashMap<String, String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub enum StepType {
    Start,
    Intermediate,
    Token(String),
    End,
    Error(String),
}

/// 链执行错误
#[derive(Debug, thiserror::Error)]
pub enum ChainError {
    #[error("缺少输入: {0}")]
    MissingInput(String),
    
    #[error("执行错误: {0}")]
    ExecutionError(String),
    
    #[error("下游链错误: {0}")]
    DownstreamError(String),
    
    #[error("超时: {0}")]
    Timeout(String),
}
```

#### 2.1.2 SequentialChain - 顺序链

```rust
// core/src/chain/sequential.rs

use super::*;

/// 顺序执行多个链
pub struct SequentialChain {
    name: String,
    chains: Vec<Box<dyn Chain>>,
    input_keys: Vec<String>,
    output_keys: Vec<String>,
    input_mappings: Vec<HashMap<String, String>>, // 输出变量映射
}

impl SequentialChain {
    pub fn builder() -> SequentialChainBuilder {
        SequentialChainBuilder::default()
    }
    
    /// 将前一个链的输出传递给下一个链
    fn propagate_outputs(
        &self,
        chain_index: usize,
        prev_outputs: &mut HashMap<String, serde_json::Value>,
        current_inputs: &mut HashMap<String, serde_json::Value>,
    ) {
        if chain_index == 0 {
            return;
        }
        
        let mapping = &self.input_mappings[chain_index];
        for (from_key, to_key) in mapping {
            if let Some(value) = prev_outputs.get(from_key) {
                current_inputs.insert(to_key.clone(), value.clone());
            }
        }
    }
}

#[async_trait]
impl Chain for SequentialChain {
    fn name(&self) -> &str {
        &self.name
    }
    
    fn input_keys(&self) -> Vec<&str> {
        self.input_keys.iter().map(|s| s.as_str()).collect()
    }
    
    fn output_keys(&self) -> Vec<&str> {
        self.output_keys.iter().map(|s| s.as_str()).collect()
    }
    
    async fn execute(
        &self, 
        mut inputs: HashMap<String, serde_json::Value>
    ) -> Result<HashMap<String, serde_json::Value>, ChainError> {
        self.validate_inputs(&inputs)?;
        
        let mut outputs = HashMap::new();
        
        for (i, chain) in self.chains.iter().enumerate() {
            // 传播上一链的输出
            self.propagate_outputs(i, &mut outputs, &mut inputs);
            
            // 执行当前链
            let chain_output = chain.execute(inputs.clone()).await
                .map_err(|e| ChainError::DownstreamError(e.to_string()))?;
            
            // 合并输出
            outputs.extend(chain_output);
        }
        
        // 只返回指定的输出键
        let result: HashMap<String, serde_json::Value> = self.output_keys
            .iter()
            .filter_map(|k| outputs.get(k).map(|v| (k.clone(), v.clone())))
            .collect();
        
        Ok(result)
    }
    
    async fn execute_stream(
        &self,
        mut inputs: HashMap<String, serde_json::Value>
    ) -> Result<impl Stream<Item = Result<ChainStep, ChainError>>, ChainError> {
        // 实现流式顺序执行...
        Ok(async_stream::stream! {
            for (i, chain) in self.chains.iter().enumerate() {
                yield Ok(ChainStep {
                    chain_name: self.name.clone(),
                    step_type: StepType::Intermediate,
                    output: serde_json::json!({
                        "message": format!("Executing chain: {}", chain.name())
                    }),
                    metadata: HashMap::new(),
                });
                
                match chain.execute(inputs.clone()).await {
                    Ok(output) => {
                        inputs.extend(output);
                    }
                    Err(e) => {
                        yield Ok(ChainStep {
                            chain_name: self.name.clone(),
                            step_type: StepType::Error(e.to_string()),
                            output: serde_json::Value::Null,
                            metadata: HashMap::new(),
                        });
                        return;
                    }
                }
            }
            
            yield Ok(ChainStep {
                chain_name: self.name.clone(),
                step_type: StepType::End,
                output: serde_json::to_value(&inputs).unwrap_or_default(),
                metadata: HashMap::new(),
            });
        })
    }
}

/// 顺序链构建器
pub struct SequentialChainBuilder {
    name: String,
    chains: Vec<Box<dyn Chain>>,
    input_keys: Vec<String>,
    output_keys: Vec<String>,
    input_mappings: Vec<HashMap<String, String>>,
}

impl SequentialChainBuilder {
    pub fn name(mut self, name: impl Into<String>) -> Self {
        self.name = name.into();
        self
    }
    
    pub fn add_chain(mut self, chain: Box<dyn Chain>) -> Self {
        self.chains.push(chain);
        self
    }
    
    pub fn input_keys(mut self, keys: Vec<&str>) -> Self {
        self.input_keys = keys.iter().map(|s| s.to_string()).collect();
        self
    }
    
    pub fn output_keys(mut self, keys: Vec<&str>) -> Self {
        self.output_keys = keys.iter().map(|s| s.to_string()).collect();
        self
    }
    
    pub fn build(self) -> Result<SequentialChain, ChainError> {
        if self.chains.is_empty() {
            return Err(ChainError::ExecutionError("至少需要一个链".to_string()));
        }
        Ok(SequentialChain {
            name: self.name,
            chains: self.chains,
            input_keys: self.input_keys,
            output_keys: self.output_keys,
            input_mappings: self.input_mappings,
        })
    }
}

impl Default for SequentialChainBuilder {
    fn default() -> Self {
        Self {
            name: "sequential_chain".to_string(),
            chains: Vec::new(),
            input_keys: Vec::new(),
            output_keys: Vec::new(),
            input_mappings: Vec::new(),
        }
    }
}
```

### 2.2 Prompt模板管理

#### 2.2.1 PromptTemplate

```rust
// core/src/prompt/mod.rs

use serde::{Deserialize, Serialize};
use std::collections::HashMap;
use regex::Regex;

/// Prompt模板
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct PromptTemplate {
    /// 模板ID
    pub id: String,
    
    /// 模板名称
    pub name: String,
    
    /// 模板内容
    pub template: String,
    
    /// 输入变量定义
    pub input_variables: Vec<String>,
    
    /// 部分变量（预填充）
    pub partial_variables: HashMap<String, String>,
    
    /// 模板格式
    pub format: TemplateFormat,
    
    /// 版本
    pub version: String,
    
    /// 元数据
    pub metadata: TemplateMetadata,
}

/// 模板格式
#[derive(Debug, Clone, Copy, Serialize, Deserialize)]
pub enum TemplateFormat {
    /// f-string 风格: {variable}
    FString,
    /// Mustache风格: {{variable}}
    Mustache,
    /// Jinja2风格: {{ variable }}
    Jinja2,
}

#[derive(Debug, Clone, Serialize, Deserialize, Default)]
pub struct TemplateMetadata {
    pub description: Option<String>,
    pub author: Option<String>,
    pub created_at: i64,
    pub updated_at: i64,
    pub tags: Vec<String>,
}

impl PromptTemplate {
    /// 创建新的Prompt模板
    pub fn new(
        id: impl Into<String>,
        name: impl Into<String>,
        template: impl Into<String>,
    ) -> Self {
        let template_str = template.into();
        let input_variables = Self::extract_variables(&template_str);
        
        Self {
            id: id.into(),
            name: name.into(),
            template: template_str,
            input_variables,
            partial_variables: HashMap::new(),
            format: TemplateFormat::FString,
            version: "1.0.0".to_string(),
            metadata: TemplateMetadata::default(),
        }
    }
    
    /// 从文件加载模板
    pub fn from_file(path: &std::path::Path) -> Result<Self, PromptError> {
        let content = std::fs::read_to_string(path)?;
        let template: Self = serde_yaml::from_str(&content)?;
        Ok(template)
    }
    
    /// 提取模板变量
    fn extract_variables(template: &str) -> Vec<String> {
        let re = Regex::new(r"\{(\w+)\}").unwrap();
        re.captures_iter(template)
            .filter_map(|cap| cap.get(1).map(|m| m.as_str().to_string()))
            .collect()
    }
    
    /// 渲染模板
    pub fn render(&self, variables: &HashMap<String, serde_json::Value>) -> Result<String, PromptError> {
        let mut result = self.template.clone();
        
        // 合并部分变量
        let mut all_vars = variables.clone();
        for (k, v) in &self.partial_variables {
            all_vars.insert(k.clone(), serde_json::Value::String(v.clone()));
        }
        
        // 检查必需变量
        for var in &self.input_variables {
            if !all_vars.contains_key(var) {
                return Err(PromptError::MissingVariable(var.clone()));
            }
        }
        
        // 替换变量
        for (key, value) in all_vars {
            let placeholder = format!("{{{}}}", key);
            let value_str = match value {
                serde_json::Value::String(s) => s,
                _ => serde_json::to_string(&value).unwrap_or_default(),
            };
            result = result.replace(&placeholder, &value_str);
        }
        
        Ok(result)
    }
    
    /// 设置部分变量
    pub fn with_partial(mut self, key: impl Into<String>, value: impl Into<String>) -> Self {
        self.partial_variables.insert(key.into(), value.into());
        self
    }
}

/// Prompt模板存储
pub struct PromptTemplateStore {
    templates: HashMap<String, PromptTemplate>,
    cache: HashMap<String, String>, // 渲染缓存
}

impl PromptTemplateStore {
    pub fn new() -> Self {
        let mut store = Self {
            templates: HashMap::new(),
            cache: HashMap::new(),
        };
        store.load_builtin_templates();
        store
    }
    
    /// 加载内置模板
    fn load_builtin_templates(&mut self) {
        // RAG问答模板
        self.register(PromptTemplate::new(
            "rag_qa",
            "RAG问答模板",
            r#"你是一个专业的建筑行业规范助手。请根据以下参考资料回答问题。

## 参考资料
{context}

## 问题
{question}

## 回答要求
1. 准确引用相关规范条文
2. 回答要专业、具体
3. 如果参考资料不足以回答问题，请明确说明
4. 回答格式要清晰，必要时使用列表或表格

请给出你的回答："#,
        ));
        
        // 查询改写模板
        self.register(PromptTemplate::new(
            "query_rewrite",
            "查询改写模板",
            r#"请将以下用户问题改写为更适合检索的形式。

原问题: {original_query}
上下文: {context}

改写要求:
1. 保留原问题的核心意图
2. 扩展相关关键词
3. 提取专业术语
4. 去除无关信息

改写后的问题:"#,
        ));
        
        // HyDE模板
        self.register(PromptTemplate::new(
            "hyde",
            "假设文档嵌入模板",
            r#"请生成一段假设性文档，该文档能够回答以下问题。

问题: {question}

要求:
1. 文档应该包含问题的答案
2. 使用专业术语和规范用语
3. 长度约200-300字
4. 格式规范

假设文档:"#,
        ));
        
        // 摘要模板
        self.register(PromptTemplate::new(
            "summarize",
            "对话摘要模板",
            r#"请对以下对话进行摘要，保留关键信息。

对话历史:
{conversation}

摘要要求:
1. 提取主要讨论的问题和结论
2. 保留关键数据和引用
3. 控制在100字以内
4. 使用简洁的语言

摘要:"#,
        ));
        
        // Agent规划模板
        self.register(PromptTemplate::new(
            "agent_planning",
            "Agent规划模板",
            r#"你是一个智能助手，需要规划如何完成用户的任务。

可用工具:
{tools}

用户任务: {task}

请分析任务并制定执行计划:
1. 识别需要的工具
2. 确定执行顺序
3. 预估可能的依赖

计划:"#,
        ));
    }
    
    /// 注册模板
    pub fn register(&mut self, template: PromptTemplate) {
        self.templates.insert(template.id.clone(), template);
    }
    
    /// 获取模板
    pub fn get(&self, id: &str) -> Option<&PromptTemplate> {
        self.templates.get(id)
    }
    
    /// 渲染模板
    pub fn render(
        &self, 
        template_id: &str, 
        variables: &HashMap<String, serde_json::Value>
    ) -> Result<String, PromptError> {
        let template = self.templates.get(template_id)
            .ok_or_else(|| PromptError::TemplateNotFound(template_id.to_string()))?;
        template.render(variables)
    }
}

#[derive(Debug, thiserror::Error)]
pub enum PromptError {
    #[error("模板不存在: {0}")]
    TemplateNotFound(String),
    
    #[error("缺少变量: {0}")]
    MissingVariable(String),
    
    #[error("渲染错误: {0}")]
    RenderError(String),
    
    #[error("IO错误: {0}")]
    IoError(#[from] std::io::Error),
    
    #[error("解析错误: {0}")]
    ParseError(#[from] serde_yaml::Error),
}
```

### 2.3 LLMChain实现

```rust
// core/src/chain/llm_chain.rs

use super::*;
use crate::llm::{LlmEngine, GenerationConfig};
use crate::prompt::PromptTemplate;

/// LLM链 - 执行Prompt并调用LLM
pub struct LLMChain<E: LlmEngine> {
    name: String,
    llm: E,
    prompt_template: PromptTemplate,
    output_key: String,
}

impl<E: LlmEngine> LLMChain<E> {
    pub fn builder() -> LLMChainBuilder<E> {
        LLMChainBuilder::default()
    }
    
    pub fn new(
        name: impl Into<String>,
        llm: E,
        prompt_template: PromptTemplate,
        output_key: impl Into<String>,
    ) -> Self {
        Self {
            name: name.into(),
            llm,
            prompt_template,
            output_key: output_key.into(),
        }
    }
}

#[async_trait]
impl<E: LlmEngine + 'static> Chain for LLMChain<E> {
    fn name(&self) -> &str {
        &self.name
    }
    
    fn input_keys(&self) -> Vec<&str> {
        self.prompt_template.input_variables.iter()
            .map(|s| s.as_str())
            .collect()
    }
    
    fn output_keys(&self) -> Vec<&str> {
        vec![&self.output_key]
    }
    
    async fn execute(
        &self,
        inputs: HashMap<String, serde_json::Value>
    ) -> Result<HashMap<String, serde_json::Value>, ChainError> {
        self.validate_inputs(&inputs)?;
        
        // 渲染Prompt
        let prompt = self.prompt_template.render(&inputs)
            .map_err(|e| ChainError::ExecutionError(e.to_string()))?;
        
        // 调用LLM
        let config = GenerationConfig::default();
        let response = self.llm.generate(&prompt, &config).await
            .map_err(|e| ChainError::ExecutionError(e.to_string()))?;
        
        // 返回结果
        let mut outputs = HashMap::new();
        outputs.insert(self.output_key.clone(), serde_json::Value::String(response));
        
        Ok(outputs)
    }
    
    async fn execute_stream(
        &self,
        inputs: HashMap<String, serde_json::Value>
    ) -> Result<impl Stream<Item = Result<ChainStep, ChainError>>, ChainError> {
        self.validate_inputs(&inputs)?;
        
        // 渲染Prompt
        let prompt = self.prompt_template.render(&inputs)
            .map_err(|e| ChainError::ExecutionError(e.to_string()))?;
        
        // 流式调用LLM
        let stream = self.llm.generate_stream(&prompt, &GenerationConfig::default()).await
            .map_err(|e| ChainError::ExecutionError(e.to_string()))?;
        
        let chain_name = self.name.clone();
        let output_key = self.output_key.clone();
        
        Ok(async_stream::stream! {
            yield Ok(ChainStep {
                chain_name: chain_name.clone(),
                step_type: StepType::Start,
                output: serde_json::json!({"output_key": output_key}),
                metadata: HashMap::new(),
            });
            
            let mut full_response = String::new();
            
            #[for_await]
            for token in stream {
                match token {
                    Ok(t) => {
                        full_response.push_str(&t);
                        yield Ok(ChainStep {
                            chain_name: chain_name.clone(),
                            step_type: StepType::Token(t),
                            output: serde_json::Value::Null,
                            metadata: HashMap::new(),
                        });
                    }
                    Err(e) => {
                        yield Ok(ChainStep {
                            chain_name: chain_name.clone(),
                            step_type: StepType::Error(e.to_string()),
                            output: serde_json::Value::Null,
                            metadata: HashMap::new(),
                        });
                        return;
                    }
                }
            }
            
            yield Ok(ChainStep {
                chain_name: chain_name.clone(),
                step_type: StepType::End,
                output: serde_json::json!({output_key: full_response}),
                metadata: HashMap::new(),
            });
        })
    }
}

/// LLM链构建器
pub struct LLMChainBuilder<E: LlmEngine> {
    name: String,
    llm: Option<E>,
    prompt_template: Option<PromptTemplate>,
    output_key: String,
}

impl<E: LlmEngine> LLMChainBuilder<E> {
    pub fn name(mut self, name: impl Into<String>) -> Self {
        self.name = name.into();
        self
    }
    
    pub fn llm(mut self, llm: E) -> Self {
        self.llm = Some(llm);
        self
    }
    
    pub fn prompt(mut self, template: PromptTemplate) -> Self {
        self.prompt_template = Some(template);
        self
    }
    
    pub fn output_key(mut self, key: impl Into<String>) -> Self {
        self.output_key = key.into();
        self
    }
    
    pub fn build(self) -> Result<LLMChain<E>, ChainError> {
        let llm = self.llm.ok_or_else(|| 
            ChainError::ExecutionError("需要指定LLM".to_string()))?;
        let prompt_template = self.prompt_template.ok_or_else(|| 
            ChainError::ExecutionError("需要指定Prompt模板".to_string()))?;
        
        Ok(LLMChain {
            name: self.name,
            llm,
            prompt_template,
            output_key: self.output_key,
        })
    }
}

impl<E: LlmEngine> Default for LLMChainBuilder<E> {
    fn default() -> Self {
        Self {
            name: "llm_chain".to_string(),
            llm: None,
            prompt_template: None,
            output_key: "output".to_string(),
        }
    }
}
```

---

## 3. 增强RAG流程设计

### 3.1 混合检索架构

```rust
// core/src/retrieval/mod.rs

pub mod vector;
pub mod keyword;
pub mod hybrid;
pub mod reranker;
pub mod query_rewriter;
pub mod hyde;

use async_trait::async_trait;
use serde::{Deserialize, Serialize};

/// 检索器接口
#[async_trait]
pub trait Retriever: Send + Sync {
    /// 检索相关文档
    async fn retrieve(
        &self, 
        query: &str, 
        top_k: u32,
        options: &RetrievalOptions
    ) -> Result<Vec<RetrievedDocument>, RetrievalError>;
    
    /// 获取检索器名称
    fn name(&self) -> &str;
}

/// 检索选项
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct RetrievalOptions {
    /// 相似度阈值
    pub threshold: f32,
    
    /// 租户ID（多租户隔离）
    pub tenant_id: Option<String>,
    
    /// 类别过滤
    pub category: Option<String>,
    
    /// 元数据过滤
    pub metadata_filter: Option<HashMap<String, serde_json::Value>>,
    
    /// 是否返回分数
    pub include_scores: bool,
}

impl Default for RetrievalOptions {
    fn default() -> Self {
        Self {
            threshold: 0.7,
            tenant_id: None,
            category: None,
            metadata_filter: None,
            include_scores: true,
        }
    }
}

/// 检索到的文档
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct RetrievedDocument {
    pub id: String,
    pub document_id: String,
    pub content: String,
    pub score: f32,
    pub metadata: DocumentMetadata,
    pub retrieval_source: RetrievalSource,
}

#[derive(Debug, Clone, Copy, Serialize, Deserialize)]
pub enum RetrievalSource {
    Vector,
    Keyword,
    Hybrid,
    Reranked,
}

#[derive(Debug, Clone, Serialize, Deserialize, Default)]
pub struct DocumentMetadata {
    pub title: Option<String>,
    pub source: Option<String>,
    pub page: Option<u32>,
    pub chunk_index: Option<u32>,
    pub created_at: Option<i64>,
}

/// 检索错误
#[derive(Debug, thiserror::Error)]
pub enum RetrievalError {
    #[error("向量检索失败: {0}")]
    VectorError(String),
    
    #[error("关键词检索失败: {0}")]
    KeywordError(String),
    
    #[error("重排序失败: {0}")]
    RerankError(String),
    
    #[error("嵌入生成失败: {0}")]
    EmbeddingError(String),
}
```

### 3.2 查询改写器

```rust
// core/src/retrieval/query_rewriter.rs

use super::*;
use crate::llm::{LlmEngine, GenerationConfig};
use crate::prompt::PromptTemplateStore;

/// 查询改写器
pub struct QueryRewriter<E: LlmEngine> {
    llm: E,
    template_store: PromptTemplateStore,
}

impl<E: LlmEngine> QueryRewriter<E> {
    pub fn new(llm: E) -> Self {
        Self {
            llm,
            template_store: PromptTemplateStore::new(),
        }
    }
    
    /// 改写查询
    pub async fn rewrite(
        &self,
        query: &str,
        context: Option<&str>,
    ) -> Result<RewrittenQuery, RetrievalError> {
        let mut variables = HashMap::new();
        variables.insert("original_query".to_string(), serde_json::json!(query));
        variables.insert("context".to_string(), serde_json::json!(context.unwrap_or("无")));
        
        let prompt = self.template_store.render("query_rewrite", &variables)
            .map_err(|e| RetrievalError::EmbeddingError(e.to_string()))?;
        
        let response = self.llm.generate(&prompt, &GenerationConfig::default()).await
            .map_err(|e| RetrievalError::EmbeddingError(e.to_string()))?;
        
        // 解析改写结果
        let expanded_keywords = Self::extract_keywords(&response);
        
        Ok(RewrittenQuery {
            original: query.to_string(),
            rewritten: response.trim().to_string(),
            expanded_keywords,
        })
    }
    
    /// 提取关键词
    fn extract_keywords(text: &str) -> Vec<String> {
        // 简单提取，实际可使用NLP工具
        text.split(|c: char| c.is_whitespace() || c == '，' || c == '、')
            .filter(|s| s.len() >= 2)
            .map(|s| s.to_string())
            .collect()
    }
}

/// 改写后的查询
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct RewrittenQuery {
    pub original: String,
    pub rewritten: String,
    pub expanded_keywords: Vec<String>,
}
```

### 3.3 HyDE (假设文档嵌入)

```rust
// core/src/retrieval/hyde.rs

use super::*;
use crate::llm::{LlmEngine, GenerationConfig};
use crate::embedding::EmbeddingModel;
use crate::prompt::PromptTemplateStore;

/// HyDE检索器
pub struct HyDERetriever<E: LlmEngine, M: EmbeddingModel> {
    llm: E,
    embedding_model: M,
    vector_retriever: Box<dyn Retriever>,
    template_store: PromptTemplateStore,
}

impl<E: LlmEngine, M: EmbeddingModel> HyDERetriever<E, M> {
    pub fn new(
        llm: E,
        embedding_model: M,
        vector_retriever: Box<dyn Retriever>,
    ) -> Self {
        Self {
            llm,
            embedding_model,
            vector_retriever,
            template_store: PromptTemplateStore::new(),
        }
    }
    
    /// 生成假设文档
    async fn generate_hypothetical_document(&self, question: &str) -> Result<String, RetrievalError> {
        let mut variables = HashMap::new();
        variables.insert("question".to_string(), serde_json::json!(question));
        
        let prompt = self.template_store.render("hyde", &variables)
            .map_err(|e| RetrievalError::EmbeddingError(e.to_string()))?;
        
        let response = self.llm.generate(&prompt, &GenerationConfig::default()).await
            .map_err(|e| RetrievalError::EmbeddingError(e.to_string()))?;
        
        Ok(response.trim().to_string())
    }
    
    /// 使用假设文档检索
    pub async fn retrieve_with_hyde(
        &self,
        question: &str,
        top_k: u32,
        options: &RetrievalOptions,
    ) -> Result<Vec<RetrievedDocument>, RetrievalError> {
        // 1. 生成假设文档
        let hypothetical_doc = self.generate_hypothetical_document(question).await?;
        
        // 2. 使用假设文档检索
        let results = self.vector_retriever.retrieve(&hypothetical_doc, top_k, options).await?;
        
        Ok(results)
    }
}

#[async_trait]
impl<E: LlmEngine + 'static, M: EmbeddingModel + 'static> Retriever for HyDERetriever<E, M> {
    async fn retrieve(
        &self,
        query: &str,
        top_k: u32,
        options: &RetrievalOptions,
    ) -> Result<Vec<RetrievedDocument>, RetrievalError> {
        self.retrieve_with_hyde(query, top_k, options).await
    }
    
    fn name(&self) -> &str {
        "hyde_retriever"
    }
}
```

### 3.4 混合检索器

```rust
// core/src/retrieval/hybrid.rs

use super::*;
use crate::embedding::EmbeddingModel;
use std::sync::Arc;
use tokio::join;

/// 混合检索器配置
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct HybridRetrieverConfig {
    /// 向量检索权重
    pub vector_weight: f32,
    
    /// 关键词检索权重
    pub keyword_weight: f32,
    
    /// 向量检索数量
    pub vector_top_k: u32,
    
    /// 关键词检索数量
    pub keyword_top_k: u32,
    
    /// 最终返回数量
    pub final_top_k: u32,
    
    /// 是否启用RRF (Reciprocal Rank Fusion)
    pub use_rrf: bool,
    
    /// RRF参数
    pub rrf_k: u32,
}

impl Default for HybridRetrieverConfig {
    fn default() -> Self {
        Self {
            vector_weight: 0.6,
            keyword_weight: 0.4,
            vector_top_k: 20,
            keyword_top_k: 20,
            final_top_k: 10,
            use_rrf: true,
            rrf_k: 60,
        }
    }
}

/// 混合检索器
pub struct HybridRetriever {
    vector_retriever: Arc<dyn Retriever>,
    keyword_retriever: Arc<dyn Retriever>,
    config: HybridRetrieverConfig,
}

impl HybridRetriever {
    pub fn new(
        vector_retriever: Arc<dyn Retriever>,
        keyword_retriever: Arc<dyn Retriever>,
        config: HybridRetrieverConfig,
    ) -> Self {
        Self {
            vector_retriever,
            keyword_retriever,
            config,
        }
    }
    
    /// 融合检索结果
    fn fuse_results(
        &self,
        vector_results: Vec<RetrievedDocument>,
        keyword_results: Vec<RetrievedDocument>,
    ) -> Vec<RetrievedDocument> {
        if self.config.use_rrf {
            self.rrf_fusion(vector_results, keyword_results)
        } else {
            self.weighted_fusion(vector_results, keyword_results)
        }
    }
    
    /// RRF融合
    fn rrf_fusion(
        &self,
        vector_results: Vec<RetrievedDocument>,
        keyword_results: Vec<RetrievedDocument>,
    ) -> Vec<RetrievedDocument> {
        let mut scores: HashMap<String, f32> = HashMap::new();
        let mut docs: HashMap<String, RetrievedDocument> = HashMap::new();
        
        // 计算向量检索的RRF分数
        for (rank, doc) in vector_results.iter().enumerate() {
            let rrf_score = 1.0 / (self.config.rrf_k as f32 + rank as f32 + 1.0);
            *scores.entry(doc.id.clone()).or_insert(0.0) += 
                rrf_score * self.config.vector_weight;
            docs.entry(doc.id.clone()).or_insert_with(|| doc.clone());
        }
        
        // 计算关键词检索的RRF分数
        for (rank, doc) in keyword_results.iter().enumerate() {
            let rrf_score = 1.0 / (self.config.rrf_k as f32 + rank as f32 + 1.0);
            *scores.entry(doc.id.clone()).or_insert(0.0) += 
                rrf_score * self.config.keyword_weight;
            docs.entry(doc.id.clone()).or_insert_with(|| doc.clone());
        }
        
        // 排序并返回
        let mut results: Vec<RetrievedDocument> = docs.into_values().map(|mut doc| {
            doc.score = scores.remove(&doc.id).unwrap_or(0.0);
            doc.retrieval_source = RetrievalSource::Hybrid;
            doc
        }).collect();
        
        results.sort_by(|a, b| b.score.partial_cmp(&a.score).unwrap());
        results.truncate(self.config.final_top_k as usize);
        
        results
    }
    
    /// 加权融合
    fn weighted_fusion(
        &self,
        vector_results: Vec<RetrievedDocument>,
        keyword_results: Vec<RetrievedDocument>,
    ) -> Vec<RetrievedDocument> {
        let mut scores: HashMap<String, f32> = HashMap::new();
        let mut docs: HashMap<String, RetrievedDocument> = HashMap::new();
        
        for doc in vector_results {
            let weighted_score = doc.score * self.config.vector_weight;
            *scores.entry(doc.id.clone()).or_insert(0.0) += weighted_score;
            docs.entry(doc.id.clone()).or_insert(doc);
        }
        
        for doc in keyword_results {
            let weighted_score = doc.score * self.config.keyword_weight;
            *scores.entry(doc.id.clone()).or_insert(0.0) += weighted_score;
            docs.entry(doc.id.clone()).or_insert(doc);
        }
        
        let mut results: Vec<RetrievedDocument> = docs.into_values().map(|mut doc| {
            doc.score = scores.remove(&doc.id).unwrap_or(0.0);
            doc.retrieval_source = RetrievalSource::Hybrid;
            doc
        }).collect();
        
        results.sort_by(|a, b| b.score.partial_cmp(&a.score).unwrap());
        results.truncate(self.config.final_top_k as usize);
        
        results
    }
}

#[async_trait]
impl Retriever for HybridRetriever {
    async fn retrieve(
        &self,
        query: &str,
        _top_k: u32,
        options: &RetrievalOptions,
    ) -> Result<Vec<RetrievedDocument>, RetrievalError> {
        // 并行执行向量检索和关键词检索
        let vector_fut = self.vector_retriever.retrieve(
            query, 
            self.config.vector_top_k, 
            options
        );
        let keyword_fut = self.keyword_retriever.retrieve(
            query, 
            self.config.keyword_top_k, 
            options
        );
        
        let (vector_results, keyword_results) = join!(vector_fut, keyword_fut);
        
        let vector_results = vector_results?;
        let keyword_results = keyword_results?;
        
        // 融合结果
        Ok(self.fuse_results(vector_results, keyword_results))
    }
    
    fn name(&self) -> &str {
        "hybrid_retriever"
    }
}
```

### 3.5 RAGChain

```rust
// core/src/chain/rag_chain.rs

use super::*;
use crate::retrieval::{Retriever, RetrievalOptions, QueryRewriter};
use crate::llm::{LlmEngine, GenerationConfig};
use crate::prompt::PromptTemplateStore;

/// RAG链配置
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct RAGChainConfig {
    /// 检索数量
    pub top_k: u32,
    
    /// 相似度阈值
    pub threshold: f32,
    
    /// 是否启用查询改写
    pub enable_query_rewrite: bool,
    
    /// 是否启用HyDE
    pub enable_hyde: bool,
    
    /// 最大上下文长度
    pub max_context_length: usize,
}

impl Default for RAGChainConfig {
    fn default() -> Self {
        Self {
            top_k: 5,
            threshold: 0.7,
            enable_query_rewrite: false,
            enable_hyde: false,
            max_context_length: 4096,
        }
    }
}

/// RAG链
pub struct RAGChain<R: Retriever, E: LlmEngine> {
    name: String,
    retriever: R,
    llm: E,
    config: RAGChainConfig,
    template_store: PromptTemplateStore,
}

impl<R: Retriever, E: LlmEngine> RAGChain<R, E> {
    pub fn new(
        name: impl Into<String>,
        retriever: R,
        llm: E,
        config: RAGChainConfig,
    ) -> Self {
        Self {
            name: name.into(),
            retriever,
            llm,
            config,
            template_store: PromptTemplateStore::new(),
        }
    }
    
    /// 构建上下文
    fn build_context(&self, docs: &[crate::retrieval::RetrievedDocument]) -> String {
        let context: Vec<String> = docs.iter()
            .map(|doc| {
                format!(
                    "【来源: {}】\n{}\n",
                    doc.metadata.source.as_deref().unwrap_or("未知"),
                    doc.content
                )
            })
            .collect();
        
        let context_str = context.join("\n---\n");
        
        // 截断到最大长度
        if context_str.len() > self.config.max_context_length {
            context_str[..self.config.max_context_length].to_string()
        } else {
            context_str
        }
    }
}

#[async_trait]
impl<R: Retriever + 'static, E: LlmEngine + 'static> Chain for RAGChain<R, E> {
    fn name(&self) -> &str {
        &self.name
    }
    
    fn input_keys(&self) -> Vec<&str> {
        vec!["question"]
    }
    
    fn output_keys(&self) -> Vec<&str> {
        vec!["answer", "sources"]
    }
    
    async fn execute(
        &self,
        inputs: HashMap<String, serde_json::Value>
    ) -> Result<HashMap<String, serde_json::Value>, ChainError> {
        self.validate_inputs(&inputs)?;
        
        let question = inputs.get("question")
            .and_then(|v| v.as_str())
            .ok_or_else(|| ChainError::MissingInput("question".to_string()))?;
        
        // 1. 检索
        let options = RetrievalOptions {
            threshold: self.config.threshold,
            ..Default::default()
        };
        
        let docs = self.retriever.retrieve(question, self.config.top_k, &options).await
            .map_err(|e| ChainError::ExecutionError(e.to_string()))?;
        
        // 2. 构建上下文
        let context = self.build_context(&docs);
        
        // 3. 渲染Prompt
        let mut variables = HashMap::new();
        variables.insert("context".to_string(), serde_json::json!(context));
        variables.insert("question".to_string(), serde_json::json!(question));
        
        let prompt = self.template_store.render("rag_qa", &variables)
            .map_err(|e| ChainError::ExecutionError(e.to_string()))?;
        
        // 4. 调用LLM
        let answer = self.llm.generate(&prompt, &GenerationConfig::default()).await
            .map_err(|e| ChainError::ExecutionError(e.to_string()))?;
        
        // 5. 返回结果
        let mut outputs = HashMap::new();
        outputs.insert("answer".to_string(), serde_json::Value::String(answer));
        outputs.insert("sources".to_string(), serde_json::to_value(&docs).unwrap_or_default());
        
        Ok(outputs)
    }
    
    async fn execute_stream(
        &self,
        inputs: HashMap<String, serde_json::Value>
    ) -> Result<impl Stream<Item = Result<ChainStep, ChainError>>, ChainError> {
        // 实现流式RAG...
        self.execute(inputs).await?;
        
        Ok(async_stream::stream! {
            yield Ok(ChainStep {
                chain_name: self.name.clone(),
                step_type: StepType::End,
                output: serde_json::Value::Null,
                metadata: HashMap::new(),
            });
        })
    }
}
```

---

## 4. Agent能力设计

### 4.1 ReAct Agent

```rust
// core/src/agent/react.rs

use crate::llm::{LlmEngine, GenerationConfig};
use crate::prompt::PromptTemplateStore;
use serde::{Deserialize, Serialize};
use std::collections::HashMap;
use async_trait::async_trait;

/// Agent工具
#[async_trait]
pub trait Tool: Send + Sync {
    /// 工具名称
    fn name(&self) -> &str;
    
    /// 工具描述
    fn description(&self) -> &str;
    
    /// 输入Schema
    fn input_schema(&self) -> serde_json::Value;
    
    /// 执行工具
    async fn execute(&self, input: serde_json::Value) -> Result<serde_json::Value, ToolError>;
}

/// 工具错误
#[derive(Debug, thiserror::Error)]
pub enum ToolError {
    #[error("执行错误: {0}")]
    ExecutionError(String),
    
    #[error("输入无效: {0}")]
    InvalidInput(String),
    
    #[error("超时: {0}")]
    Timeout(String),
}

/// Agent执行步骤
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct AgentStep {
    pub thought: String,
    pub action: Option<ToolCall>,
    pub observation: Option<String>,
    pub is_final: bool,
    pub final_answer: Option<String>,
}

/// 工具调用
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ToolCall {
    pub tool_name: String,
    pub tool_input: serde_json::Value,
}

/// ReAct Agent
pub struct ReActAgent<E: LlmEngine> {
    llm: E,
    tools: HashMap<String, Box<dyn Tool>>,
    max_iterations: u32,
    template_store: PromptTemplateStore,
}

impl<E: LlmEngine> ReActAgent<E> {
    pub fn new(llm: E) -> Self {
        Self {
            llm,
            tools: HashMap::new(),
            max_iterations: 10,
            template_store: PromptTemplateStore::new(),
        }
    }
    
    /// 注册工具
    pub fn register_tool<T: Tool + 'static>(&mut self, tool: T) {
        self.tools.insert(tool.name().to_string(), Box::new(tool));
    }
    
    /// 构建工具描述
    fn build_tools_description(&self) -> String {
        self.tools.values()
            .map(|t| {
                format!(
                    "- {}: {}\n  输入: {}",
                    t.name(),
                    t.description(),
                    serde_json::to_string(&t.input_schema()).unwrap_or_default()
                )
            })
            .collect::<Vec<_>>()
            .join("\n\n")
    }
    
    /// 解析Agent响应
    fn parse_response(&self, response: &str) -> AgentStep {
        // 解析 Thought/Action/Observation 格式
        let thought = Self::extract_section(response, "Thought");
        let action = Self::extract_section(response, "Action")
            .map(|a| self.parse_action(&a));
        let observation = Self::extract_section(response, "Observation");
        let final_answer = Self::extract_section(response, "Final Answer");
        
        AgentStep {
            thought: thought.unwrap_or_default(),
            action,
            observation,
            is_final: final_answer.is_some(),
            final_answer,
        }
    }
    
    fn extract_section(text: &str, section: &str) -> Option<String> {
        let pattern = format!("{}:", section);
        text.lines()
            .skip_while(|l| !l.starts_with(&pattern))
            .skip(1)
            .take_while(|l| !l.starts_with("Thought:") 
                && !l.starts_with("Action:") 
                && !l.starts_with("Observation:")
                && !l.starts_with("Final Answer:"))
            .collect::<Vec<_>>()
            .first()
            .map(|s| s.trim().to_string())
    }
    
    fn parse_action(&self, action_text: &str) -> ToolCall {
        // 简单解析，实际应使用更健壮的解析
        let parts: Vec<&str> = action_text.splitn(2, ':').collect();
        if parts.len() == 2 {
            ToolCall {
                tool_name: parts[0].trim().to_string(),
                tool_input: serde_json::from_str(parts[1].trim()).unwrap_or_default(),
            }
        } else {
            ToolCall {
                tool_name: action_text.trim().to_string(),
                tool_input: serde_json::Value::Null,
            }
        }
    }
    
    /// 执行Agent循环
    pub async fn run(
        &self,
        task: &str,
    ) -> Result<AgentResult, AgentError> {
        let tools_desc = self.build_tools_description();
        let mut history = String::new();
        let mut steps = Vec::new();
        
        for i in 0..self.max_iterations {
            // 构建Prompt
            let prompt = format!(
                r#"你是一个智能助手，使用ReAct模式完成任务。

可用工具:
{}

任务: {}

历史记录:
{}

请按照以下格式思考和行动:
Thought: 你的思考
Action: 工具名称: 工具输入
Observation: 工具返回结果
... (重复多次)
Final Answer: 最终答案

Thought:"#,
                tools_desc, task, history
            );
            
            // 调用LLM
            let response = self.llm.generate(&prompt, &GenerationConfig::default()).await
                .map_err(|e| AgentError::LLMError(e.to_string()))?;
            
            // 解析响应
            let step = self.parse_response(&response);
            
            // 检查是否完成
            if step.is_final {
                return Ok(AgentResult {
                    answer: step.final_answer.unwrap_or_default(),
                    steps,
                });
            }
            
            // 执行工具
            if let Some(ref tool_call) = step.action {
                let observation = if let Some(tool) = self.tools.get(&tool_call.tool_name) {
                    match tool.execute(tool_call.tool_input.clone()).await {
                        Ok(result) => serde_json::to_string(&result).unwrap_or_default(),
                        Err(e) => format!("错误: {}", e),
                    }
                } else {
                    format!("未知工具: {}", tool_call.tool_name)
                };
                
                history.push_str(&format!(
                    "Thought: {}\nAction: {}:{}\nObservation: {}\n",
                    step.thought,
                    tool_call.tool_name,
                    serde_json::to_string(&tool_call.tool_input).unwrap_or_default(),
                    observation
                ));
            }
            
            steps.push(step);
        }
        
        Err(AgentError::MaxIterationsReached)
    }
}

/// Agent结果
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct AgentResult {
    pub answer: String,
    pub steps: Vec<AgentStep>,
}

/// Agent错误
#[derive(Debug, thiserror::Error)]
pub enum AgentError {
    #[error("LLM错误: {0}")]
    LLMError(String),
    
    #[error("工具错误: {0}")]
    ToolError(String),
    
    #[error("达到最大迭代次数")]
    MaxIterationsReached,
    
    #[error("解析错误: {0}")]
    ParseError(String),
}
```

### 4.2 内置工具示例

```rust
// core/src/agent/tools/mod.rs

pub mod knowledge_search;
pub mod calculator;
pub mod web_search;

use super::*;

/// 知识库搜索工具
pub struct KnowledgeSearchTool<R: Retriever> {
    retriever: R,
}

impl<R: Retriever> KnowledgeSearchTool<R> {
    pub fn new(retriever: R) -> Self {
        Self { retriever }
    }
}

#[async_trait]
impl<R: Retriever + 'static> Tool for KnowledgeSearchTool<R> {
    fn name(&self) -> &str {
        "knowledge_search"
    }
    
    fn description(&self) -> &str {
        "搜索知识库获取相关建筑规范文档"
    }
    
    fn input_schema(&self) -> serde_json::Value {
        serde_json::json!({
            "type": "object",
            "properties": {
                "query": {
                    "type": "string",
                    "description": "搜索查询"
                },
                "top_k": {
                    "type": "integer",
                    "description": "返回数量",
                    "default": 5
                }
            },
            "required": ["query"]
        })
    }
    
    async fn execute(&self, input: serde_json::Value) -> Result<serde_json::Value, ToolError> {
        let query = input.get("query")
            .and_then(|v| v.as_str())
            .ok_or_else(|| ToolError::InvalidInput("缺少query参数".to_string()))?;
        
        let top_k = input.get("top_k")
            .and_then(|v| v.as_u64())
            .unwrap_or(5) as u32;
        
        let options = crate::retrieval::RetrievalOptions::default();
        
        let results = self.retriever.retrieve(query, top_k, &options).await
            .map_err(|e| ToolError::ExecutionError(e.to_string()))?;
        
        Ok(serde_json::to_value(results).unwrap_or_default())
    }
}

/// 计算器工具
pub struct CalculatorTool;

#[async_trait]
impl Tool for CalculatorTool {
    fn name(&self) -> &str {
        "calculator"
    }
    
    fn description(&self) -> &str {
        "执行数学计算"
    }
    
    fn input_schema(&self) -> serde_json::Value {
        serde_json::json!({
            "type": "object",
            "properties": {
                "expression": {
                    "type": "string",
                    "description": "数学表达式"
                }
            },
            "required": ["expression"]
        })
    }
    
    async fn execute(&self, input: serde_json::Value) -> Result<serde_json::Value, ToolError> {
        let expression = input.get("expression")
            .and_then(|v| v.as_str())
            .ok_or_else(|| ToolError::InvalidInput("缺少expression参数".to_string()))?;
        
        // 简单计算器实现
        let result = self.evaluate(expression)?;
        
        Ok(serde_json::json!({ "result": result }))
    }
}

impl CalculatorTool {
    fn evaluate(&self, expr: &str) -> Result<f64, ToolError> {
        // 简化实现，实际应使用eval库
        // 这里只支持简单的四则运算
        let expr = expr.replace(" ", "");
        
        if expr.contains('+') {
            let parts: Vec<&str> = expr.split('+').collect();
            if parts.len() == 2 {
                return Ok(parts[0].parse::<f64>().unwrap_or(0.0) 
                    + parts[1].parse::<f64>().unwrap_or(0.0));
            }
        }
        
        // 更多运算...
        
        Ok(0.0)
    }
}
```

---

## 5. 记忆系统设计

### 5.1 多层记忆架构

```rust
// core/src/memory/mod.rs

pub mod conversation;
pub mod vector_memory;
pub mod summary;

use async_trait::async_trait;
use serde::{Deserialize, Serialize};
use std::collections::HashMap;

/// 记忆接口
#[async_trait]
pub trait Memory: Send + Sync {
    /// 添加记忆
    async fn add(&mut self, content: &str, metadata: HashMap<String, String>) 
        -> Result<String, MemoryError>;
    
    /// 搜索相关记忆
    async fn search(&self, query: &str, top_k: u32) 
        -> Result<Vec<MemoryItem>, MemoryError>;
    
    /// 获取所有记忆
    async fn get_all(&self) -> Result<Vec<MemoryItem>, MemoryError>;
    
    /// 清除记忆
    async fn clear(&mut self) -> Result<(), MemoryError>;
    
    /// 获取记忆数量
    async fn count(&self) -> usize;
}

/// 记忆项
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct MemoryItem {
    pub id: String,
    pub content: String,
    pub metadata: HashMap<String, String>,
    pub created_at: i64,
    pub relevance_score: Option<f32>,
}

/// 记忆错误
#[derive(Debug, thiserror::Error)]
pub enum MemoryError {
    #[error("存储错误: {0}")]
    StorageError(String),
    
    #[error("检索错误: {0}")]
    RetrievalError(String),
    
    #[error("嵌入错误: {0}")]
    EmbeddingError(String),
}

/// 复合记忆系统
pub struct CompositeMemory {
    /// 短期记忆 (对话历史)
    short_term: conversation::ConversationMemory,
    
    /// 长期记忆 (向量存储)
    long_term: Option<vector_memory::VectorMemory>,
    
    /// 摘要记忆
    summary: Option<summary::SummaryMemory>,
    
    /// 配置
    config: CompositeMemoryConfig,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct CompositeMemoryConfig {
    /// 短期记忆最大条数
    pub short_term_limit: usize,
    
    /// 是否启用长期记忆
    pub enable_long_term: bool,
    
    /// 是否启用摘要
    pub enable_summary: bool,
    
    /// 摘要触发条数
    pub summary_trigger: usize,
}

impl Default for CompositeMemoryConfig {
    fn default() -> Self {
        Self {
            short_term_limit: 20,
            enable_long_term: true,
            enable_summary: true,
            summary_trigger: 10,
        }
    }
}

impl CompositeMemory {
    pub fn new(config: CompositeMemoryConfig) -> Self {
        Self {
            short_term: conversation::ConversationMemory::new(config.short_term_limit),
            long_term: None,
            summary: None,
            config,
        }
    }
    
    /// 添加用户消息
    pub async fn add_user_message(&mut self, content: &str) -> Result<(), MemoryError> {
        let mut metadata = HashMap::new();
        metadata.insert("role".to_string(), "user".to_string());
        
        self.short_term.add(content, metadata).await?;
        
        // 检查是否需要摘要
        if self.config.enable_summary && 
           self.short_term.count().await >= self.config.summary_trigger {
            self.compress_history().await?;
        }
        
        Ok(())
    }
    
    /// 添加助手消息
    pub async fn add_assistant_message(&mut self, content: &str) -> Result<(), MemoryError> {
        let mut metadata = HashMap::new();
        metadata.insert("role".to_string(), "assistant".to_string());
        
        self.short_term.add(content, metadata).await
    }
    
    /// 压缩历史
    async fn compress_history(&mut self) -> Result<(), MemoryError> {
        // 实现历史压缩...
        Ok(())
    }
    
    /// 获取上下文
    pub async fn get_context(&self, query: Option<&str>) -> Result<String, MemoryError> {
        let mut context_parts = Vec::new();
        
        // 添加短期记忆
        let short_term = self.short_term.get_all().await?;
        if !short_term.is_empty() {
            context_parts.push("## 对话历史\n".to_string());
            for item in short_term {
                let role = item.metadata.get("role").unwrap_or(&"user".to_string());
                context_parts.push(format!("[{}] {}\n", role, item.content));
            }
        }
        
        // 添加摘要
        if let Some(ref summary) = self.summary {
            let summary_content = summary.get_all().await?;
            if !summary_content.is_empty() {
                context_parts.push("\n## 历史摘要\n".to_string());
                for item in summary_content {
                    context_parts.push(format!("{}\n", item.content));
                }
            }
        }
        
        // 添加相关长期记忆
        if let (Some(ref long_term), Some(q)) = (&self.long_term, query) {
            let relevant = long_term.search(q, 3).await?;
            if !relevant.is_empty() {
                context_parts.push("\n## 相关记忆\n".to_string());
                for item in relevant {
                    context_parts.push(format!("{}\n", item.content));
                }
            }
        }
        
        Ok(context_parts.join("\n"))
    }
}
```

### 5.2 对话记忆

```rust
// core/src/memory/conversation.rs

use super::*;
use std::collections::VecDeque;

/// 对话记忆 (滑动窗口)
pub struct ConversationMemory {
    messages: VecDeque<MemoryItem>,
    max_size: usize,
}

impl ConversationMemory {
    pub fn new(max_size: usize) -> Self {
        Self {
            messages: VecDeque::with_capacity(max_size),
            max_size,
        }
    }
}

#[async_trait]
impl Memory for ConversationMemory {
    async fn add(&mut self, content: &str, metadata: HashMap<String, String>) 
        -> Result<String, MemoryError> 
    {
        let item = MemoryItem {
            id: uuid::Uuid::new_v4().to_string(),
            content: content.to_string(),
            metadata,
            created_at: chrono::Utc::now().timestamp(),
            relevance_score: None,
        };
        
        // 如果超过容量，移除最旧的
        if self.messages.len() >= self.max_size {
            self.messages.pop_front();
        }
        
        self.messages.push_back(item.clone());
        
        Ok(item.id)
    }
    
    async fn search(&self, _query: &str, top_k: u32) 
        -> Result<Vec<MemoryItem>, MemoryError> 
    {
        // 对话记忆的搜索就是返回最近的几条
        Ok(self.messages.iter()
            .rev()
            .take(top_k as usize)
            .cloned()
            .collect())
    }
    
    async fn get_all(&self) -> Result<Vec<MemoryItem>, MemoryError> {
        Ok(self.messages.iter().cloned().collect())
    }
    
    async fn clear(&mut self) -> Result<(), MemoryError> {
        self.messages.clear();
        Ok(())
    }
    
    async fn count(&self) -> usize {
        self.messages.len()
    }
}
```

### 5.3 向量记忆

```rust
// core/src/memory/vector_memory.rs

use super::*;
use crate::embedding::EmbeddingModel;
use crate::vector::MilvusVectorStore;
use std::sync::Arc;

/// 向量记忆 (长期记忆)
pub struct VectorMemory {
    embedding_model: Arc<dyn EmbeddingModel>,
    vector_store: Arc<MilvusVectorStore>,
    collection_name: String,
}

impl VectorMemory {
    pub fn new(
        embedding_model: Arc<dyn EmbeddingModel>,
        vector_store: Arc<MilvusVectorStore>,
        collection_name: impl Into<String>,
    ) -> Self {
        Self {
            embedding_model,
            vector_store,
            collection_name: collection_name.into(),
        }
    }
}

#[async_trait]
impl Memory for VectorMemory {
    async fn add(&mut self, content: &str, metadata: HashMap<String, String>) 
        -> Result<String, MemoryError> 
    {
        let id = uuid::Uuid::new_v4().to_string();
        
        // 生成嵌入
        let embedding = self.embedding_model.embed(content).await
            .map_err(|e| MemoryError::EmbeddingError(e.to_string()))?;
        
        // 存储到向量数据库
        let chunk = crate::vector::DocumentChunk {
            id: id.clone(),
            document_id: "memory".to_string(),
            content: content.to_string(),
            metadata: crate::vector::ChunkMetadata::default(),
            chunk_index: 0,
        };
        
        self.vector_store.insert(vec![chunk], vec![embedding]).await
            .map_err(|e| MemoryError::StorageError(e.to_string()))?;
        
        Ok(id)
    }
    
    async fn search(&self, query: &str, top_k: u32) 
        -> Result<Vec<MemoryItem>, MemoryError> 
    {
        // 生成查询嵌入
        let query_embedding = self.embedding_model.embed(query).await
            .map_err(|e| MemoryError::EmbeddingError(e.to_string()))?;
        
        // 向量搜索
        let results = self.vector_store.search(query_embedding, top_k, 0.5).await
            .map_err(|e| MemoryError::RetrievalError(e.to_string()))?;
        
        // 转换为MemoryItem
        Ok(results.into_iter().map(|r| MemoryItem {
            id: r.chunk.id,
            content: r.chunk.content,
            metadata: HashMap::new(),
            created_at: 0,
            relevance_score: Some(r.score),
        }).collect())
    }
    
    async fn get_all(&self) -> Result<Vec<MemoryItem>, MemoryError> {
        // 向量记忆不支持获取全部
        Ok(Vec::new())
    }
    
    async fn clear(&mut self) -> Result<(), MemoryError> {
        // 清空collection
        Ok(())
    }
    
    async fn count(&self) -> usize {
        0
    }
}
```

---

## 6. 与现有方案对比

| 特性 | 现有方案 | 增强方案 |
|------|---------|---------|
| **架构模式** | 硬编码流程 | LangChain风格链式架构 |
| **检索策略** | 单一向量检索 | 混合检索 + HyDE + 查询改写 |
| **Prompt管理** | 字符串拼接 | 模板化 + 版本管理 |
| **Agent能力** | 无 | ReAct + 工具调用 |
| **记忆系统** | 简单历史 | 短期 + 长期 + 摘要 |
| **可组合性** | 低 | 高 |
| **可扩展性** | 低 | 高 |
| **可观测性** | 日志 | 追踪 + 指标 |

---

## 7. 迁移路径

### 7.1 阶段一：基础架构 (1周)
1. 引入Chain trait和相关抽象
2. 实现SequentialChain和LLMChain
3. 创建PromptTemplateStore

### 7.2 阶段二：检索增强 (1周)
1. 实现Retriever trait
2. 开发HybridRetriever
3. 集成QueryRewriter和HyDE

### 7.3 阶段三：Agent系统 (1周)
1. 实现ReActAgent
2. 开发内置工具集
3. 集成到对话流程

### 7.4 阶段四：记忆系统 (1周)
1. 实现ConversationMemory
2. 开发VectorMemory
3. 集成CompositeMemory

### 7.5 阶段五：RAGChain集成 (1周)
1. 将现有RAG流程迁移到RAGChain
2. 测试和优化
3. 文档更新

---

**计划创建日期**: 2026-04-22
**版本**: v2.0
**状态**: 设计阶段
