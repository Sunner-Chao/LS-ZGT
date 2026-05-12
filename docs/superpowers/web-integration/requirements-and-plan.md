# LS-ZGT Web 项目增强计划

> 以现有 Web 应用为基础，集成桌面客户端所有高级特性，构建生产级 RAG 应用

## 1. 背景与目标

### 1.1 现有 Web 应用架构

```
┌─────────────────────────────────────────────────────────────────┐
│                      LS-ZGT Web 应用（现状）                     │
├─────────────────────────────────────────────────────────────────┤
│  前端：Vue 3 + TypeScript + Element Plus                        │
│  后端：Spring Boot 3 + Java 17                                 │
│  AI：**Docker 内 LLM 服务** → 宿主机模型                       │
│     对话：Qwen3.5B-VLM-GGUF | 嵌入：nomic-embed-text-v2-moe-gguf│
│  向量数据库：Milvus                                              │
│  特性：基础 RAG、基础文档上传、基础对话                         │
└─────────────────────────────────────────────────────────────────┘
```

### 1.2 问题与差距

| 现状 | 目标 | 差距 |
|------|------|------|
| 基础 PDF 解析 | Docling 专业解析 | 表格错位、OCR 缺失 |
| 固定分块策略 | 多种分块策略可配置 | 分块颗粒度无法调优 |
| 单一向量检索 | 混合搜索 + RRF + Reranker | 准确率只有 60% |
| 用户直接提问 | Query Rewrite 查询改写 | "货快没了"搜不到 |
| 单轮对话 | Chain 架构 + 多轮记忆 | 无上下文保持 |
| 纯在线 | 支持离线模式 | 现场无网络不可用 |
| 无模型评估 | 内置评估体系 | 选型无标准 |

### 1.3 目标

在现有 Web 应用基础上，集成桌面客户端所有高级特性：
- **Advanced RAG**：混合搜索 + Query Rewrite + Reranker
- **Docling 解析**：复杂文档表格、公式、OCR
- **Chain 架构**：模块化、可组合的 RAG 流程
- **离线支持**：本地模式 + 本地模型
- **评估体系**：模型选型标准化

---

## 2. 功能需求清单

### 2.1 功能需求总览

```
┌─────────────────────────────────────────────────────────────────┐
│                     LS-ZGT Web 增强功能                          │
├─────────────────────────────────────────────────────────────────┤
│                                                                 │
│  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐              │
│  │  文档解析   │  │  RAG 管道   │  │  Chain 架构 │              │
│  │  ⭐ 新增   │  │  ⭐ 新增   │  │  ⭐ 新增   │              │
│  └─────────────┘  └─────────────┘  └─────────────┘              │
│                                                                 │
│  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐              │
│  │  模型评估   │  │  离线支持   │  │  商业功能   │              │
│  │  ⭐ 新增   │  │  🔄 复用   │  │  🔄 复用   │              │
│  └─────────────┘  └─────────────┘  └─────────────┘              │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘

图例：⭐ 新增 = 桌面端实现，需要迁移到 Web
      🔄 复用 = Web 已有，需要扩展
```

### 2.2 详细功能需求

#### F1: Docling 文档解析服务 ⭐

| 需求ID | 功能描述 | 优先级 | 来源 |
|--------|----------|--------|------|
| F1.1 | 后端集成 Docling Python 服务，支持 PDF/DOCX/PPTX/XLSX 解析 | P0 | 桌面端 Task 1 |
| F1.2 | 表格结构检测与还原（行列、多级表头） | P0 | 桌面端 Task 1 |
| F1.3 | 公式检测与 LaTeX 提取 | P1 | 桌面端 Task 1 |
| F1.4 | OCR 扫描 PDF 文字识别 | P1 | 桌面端 Task 1 |
| F1.5 | 图片分类与图表理解 | P2 | 桌面端 Task 1 |
| F1.6 | 智能分块（语义/段落/章节策略） | P0 | 桌面端 Task 1 |
| F1.7 | 文档解析状态实时推送（WebSocket） | P0 | 新增 |

#### F2: Advanced RAG 管道 ⭐

| 需求ID | 功能描述 | 优先级 | 来源 |
|--------|----------|--------|------|
| F2.1 | Query Rewrite 查询改写（口语 → 专业检索词） | P0 | 桌面端 Task 5 |
| F2.2 | Query Decomposition 查询分解（复杂问题拆解） | P1 | 桌面端 Task 5 |
| F2.3 | HyDE 假设性文档检索 | P2 | 桌面端 Task 5 |
| F2.4 | 混合搜索（向量 + 关键词 BM25） | P0 | 桌面端 Task 7 |
| F2.5 | RRF 融合（Reciprocal Rank Fusion） | P0 | 桌面端 Task 7 |
| F2.6 | Cross-Encoder Reranker 重排序 | P0 | 桌面端 Task 6 |
| F2.7 | 上下文压缩（Context Compression） | P1 | 桌面端 Task 6 |
| F2.8 | RAG Pipeline 可视化配置（启用/禁用各阶段） | P0 | 新增 |

#### F3: Chain 架构 ⭐

| 需求ID | 功能描述 | 优先级 | 来源 |
|--------|----------|--------|------|
| F3.1 | RetrievalChain 检索链 | P0 | 桌面端 Task 2 |
| F3.2 | ConversationChain 对话链（多轮记忆） | P0 | 桌面端 Task 3 |
| F3.3 | SequentialChain 顺序链组合 | P1 | 桌面端 Task 4 |
| F3.4 | RouterChain 路由器（按问题类型路由） | P2 | 桌面端 Task 4 |
| F3.5 | Chain 可视化编辑器 | P1 | 新增 |
| F3.6 | Chain 执行日志与追踪 | P1 | 新增 |

#### F4: 模型评估体系 ⭐

| 需求ID | 功能描述 | 优先级 | 来源 |
|--------|----------|--------|------|
| F4.1 | 逻辑推理能力评估（混乱资料因果关系） | P1 | 痛点5 |
| F4.2 | 指令遵循能力评估（字数限制、格式要求） | P1 | 痛点5 |
| F4.3 | 防幻觉能力评估（不知道时说不知道） | P1 | 痛点5 |
| F4.4 | 评估报告生成与对比 | P2 | 新增 |
| F4.5 | 多模型 A/B 测试框架 | P2 | 新增 |

#### F5: 离线支持（扩展）🔄

| 需求ID | 功能描述 | 优先级 | 来源 |
|--------|----------|--------|------|
| F5.1 | 本地模式切换开关 | P0 | 桌面端设计 |
| F5.2 | 本地 Llama.cpp 服务集成 | P0 | 桌面端设计 |
| F5.3 | 本地 Milvus Lite 集成 | P1 | 桌面端设计 |
| F5.4 | 本地 Docling 服务（Docker） | P1 | 桌面端设计 |
| F5.5 | 离线模式降级策略（功能提示） | P2 | 新增 |

#### F6: 商业功能（扩展）🔄

| 需求ID | 功能描述 | 优先级 | 来源 |
|--------|----------|--------|------|
| F6.1 | Token 用量统计与配额管理 | P0 | 桌面端设计 |
| F6.2 | 套餐管理（Free/Basic/Pro/Enterprise） | P0 | 桌面端设计 |
| F6.3 | API Key 管理与调用限制 | P1 | 桌面端设计 |
| F6.4 | Webhook 事件通知 | P2 | 桌面端设计 |

---

## 3. 技术架构设计

### 3.1 增强后架构

```
┌─────────────────────────────────────────────────────────────────────────┐
│                            LS-ZGT Web 增强架构                            │
├─────────────────────────────────────────────────────────────────────────┤
│                                                                         │
│  ┌─────────────────────────────────────────────────────────────────┐   │
│  │                         前端（Vue 3）                            │   │
│  │  ┌─────────┐ ┌─────────┐ ┌─────────┐ ┌─────────┐             │   │
│  │  │ 对话页  │ │ 知识库  │ │ 评估页  │ │ 配置页  │             │   │
│  │  └─────────┘ └─────────┘ └─────────┘ └─────────┘             │   │
│  │  ┌─────────────────────────────────────────────────┐          │   │
│  │  │  RAG Pipeline 可视化配置 / Chain 可视化编辑器   │          │   │
│  │  └─────────────────────────────────────────────────┘          │   │
│  └─────────────────────────────────────────────────────────────────┘   │
│                                    │                                    │
│                                    ▼                                    │
│  ┌─────────────────────────────────────────────────────────────────┐   │
│  │                    API Gateway (Spring Boot)                    │   │
│  │  ┌─────────┐ ┌─────────┐ ┌─────────┐ ┌─────────┐             │   │
│  │  │ 对话API  │ │ 知识库API│ │ 评估API │ │ 计费API │             │   │
│  │  └─────────┘ └─────────┘ └─────────┘ └─────────┘             │   │
│  └─────────────────────────────────────────────────────────────────┘   │
│                                    │                                    │
│                                    ▼                                    │
│  ┌─────────────────────────────────────────────────────────────────┐   │
│  │                    Chain Executor (Java) ⭐ 新增                  │   │
│  │  ┌──────────────────────────────────────────────────────┐      │   │
│  │  │              RAG Pipeline Orchestrator               │      │   │
│  │  │  ┌──────────┐ ┌──────────┐ ┌──────────┐ ┌──────────┐ │      │   │
│  │  │  │ Pre-Ret  │ │Retrieval │ │ Post-Ret │ │Synthesis │ │      │   │
│  │  │  └──────────┘ └──────────┘ └──────────┘ └──────────┘ │      │   │
│  │  └──────────────────────────────────────────────────────┘      │   │
│  └─────────────────────────────────────────────────────────────────┘   │
│                                    │                                    │
│         ┌──────────────────────────┼──────────────────────────┐        │
│         ▼                          ▼                          ▼        │
│  ┌─────────────┐          ┌─────────────┐          ┌─────────────┐  │
│  │ Docling    │          │ Milvus      │          │ Llama.cpp /   │  │
│  │ Service    │          │ (向量库)     │          │ 云端 API   │  │
│  │ (Python)   │          │             │          │             │  │
│  └─────────────┘          └─────────────┘          └─────────────┘  │
│                                                                         │
└─────────────────────────────────────────────────────────────────────────┘
```

### 3.2 技术栈对比

| 层级 | 现状 | 增强后 |
|------|------|--------|
| 前端框架 | Vue 3 + Element Plus | Vue 3 + Element Plus + 状态管理 |
| 后端框架 | Spring Boot 3 | Spring Boot 3 + Chain 框架 |
| 文档解析 | 基础 PDF 解析 | Docling Python 服务 |
| 向量数据库 | Milvus | Milvus + Milvus Lite（离线） |
| 嵌入模型 | BGE-M3 | BGE-M3 + BGE-Reranker |
| LLM | Llama.cpp/云端 API | Llama.cpp + 多云端 API + 本地量化 |
| RAG | 基础 RAG | Advanced RAG Pipeline |
| Chain | 无 | LangChain 风格 Chain |

### 3.3 目录结构

```
ls-zgt-web/
├── src/
│   ├── main/
│   │   ├── java/com/zgt/
│   │   │   ├── controller/
│   │   │   │   ├── ChatController.java      # 对话接口
│   │   │   │   ├── KnowledgeController.java # 知识库接口
│   │   │   │   ├── EvaluationController.java # 模型评估接口 ⭐
│   │   │   │   └── ConfigController.java    # 配置接口
│   │   │   │
│   │   │   ├── service/
│   │   │   │   ├── ChatService.java
│   │   │   │   ├── KnowledgeService.java
│   │   │   │   ├── chain/                  # Chain 架构 ⭐
│   │   │   │   │   ├── ChainExecutor.java
│   │   │   │   │   ├── RetrievalChain.java
│   │   │   │   │   ├── ConversationChain.java
│   │   │   │   │   └── combinators/
│   │   │   │   ├── rag/                    # RAG 管道 ⭐
│   │   │   │   │   ├── pipeline/
│   │   │   │   │   │   ├── RagPipeline.java
│   │   │   │   │   │   ├── PreRetrieval.java
│   │   │   │   │   │   ├── PostRetrieval.java
│   │   │   │   │   │   └── Synthesizer.java
│   │   │   │   │   ├── retrieval/
│   │   │   │   │   │   ├── HybridRetriever.java
│   │   │   │   │   │   └── RrfFusion.java
│   │   │   │   │   ├── query/
│   │   │   │   │   │   ├── QueryRewriter.java
│   │   │   │   │   │   └── QueryDecomposer.java
│   │   │   │   │   └── rerank/
│   │   │   │   │       └── CrossEncoderReranker.java
│   │   │   │   ├── embedding/
│   │   │   │   │   ├── BgeM3Service.java
│   │   │   │   │   └── Bm25Service.java      # BM25 关键词检索 ⭐
│   │   │   │   ├── evaluation/               # 模型评估 ⭐
│   │   │   │   │   ├── EvaluatorService.java
│   │   │   │   │   └── ReportGenerator.java
│   │   │   │   └── billing/
│   │   │   │       ├── BillingService.java
│   │   │   │       └── QuotaManager.java
│   │   │   │
│   │   │   ├── config/
│   │   │   │   ├── RagConfig.java          # RAG 配置
│   │   │   │   ├── ChainConfig.java         # Chain 配置
│   │   │   │   └── ModelConfig.java         # 模型配置
│   │   │   │
│   │   │   └── model/
│   │   │       ├── dto/
│   │   │       │   ├── ChatRequest.java
│   │   │       │   ├── RagConfigDto.java
│   │   │       │   └── EvaluationRequest.java
│   │   │       └── chain/                   # Chain 类型 ⭐
│   │   │           ├── ChainInput.java
│   │   │           ├── ChainOutput.java
│   │   │           └── ChainMetadata.java
│   │   │
│   │   └── resources/
│   │       ├── application.yml
│   │       ├── chain/                       # Chain 模板配置
│   │       │   ├── default.yaml
│   │       │   └── building-code.yaml
│   │       └── prompts/
│   │           ├── query_rewrite.txt
│   │           ├── query_decompose.txt
│   │           └── synthesis.txt
│   │
│   └── python/                              # Docling 服务 ⭐
│       ├── docling_service/
│       │   ├── main.py
│       │   ├── converter.py
│       │   ├── chunker.py
│       │   └── models.py
│       └── requirements.txt
│
├── web/
│   └── src/
│       ├── views/
│       │   ├── Chat.vue                     # 增强：显示 RAG 链路
│       │   ├── Knowledge.vue                # 增强：分块策略配置
│       │   ├── Evaluation.vue               # ⭐ 新增：模型评估
│       │   └── Settings/
│       │       ├── RagConfig.vue           # ⭐ 新增：RAG 管道配置
│       │       └── ChainEditor.vue         # ⭐ 新增：Chain 编辑器
│       │
│       └── components/
│           ├── chat/
│           │   ├── MessageItem.vue
│           │   └── SourceReferences.vue     # 增强：显示来源
│           ├── rag/
│           │   ├── PipelineDiagram.vue      # ⭐ RAG 管道可视化
│           │   ├── RetrievalStats.vue        # ⭐ 检索统计
│           │   └── ChainFlow.vue             # ⭐ Chain 流程图
│           └── evaluation/
│               ├── EvaluationPanel.vue       # ⭐ 评估面板
│               └── ReportCard.vue           # ⭐ 评估报告
│
└── docker-compose.yml                       # 增强：Docling + Milvus Lite
```

---

## 4. 实现计划

### 4.1 阶段划分

```
┌─────────────────────────────────────────────────────────────────┐
│                        LS-ZGT Web 增强路线图                      │
├─────────────────────────────────────────────────────────────────┤
│                                                                 │
│  阶段 1: 基础增强 (2周)                                        │
│  ├─ S1.1: Docling 服务部署 + 后端集成                         │
│  ├─ S1.2: 混合搜索 + BM25 服务                                 │
│  └─ S1.3: 基础 RAG Pipeline API                                │
│                                                                 │
│  阶段 2: Advanced RAG (3周)                                    │
│  ├─ S2.1: Query Rewrite 实现                                   │
│  ├─ S2.2: RRF 融合 + Reranker                                 │
│  ├─ S2.3: RAG Pipeline 可视化配置                             │
│  └─ S2.4: 检索准确率评估工具                                    │
│                                                                 │
│  阶段 3: Chain 架构 (2周)                                      │
│  ├─ S3.1: Chain 核心框架实现                                    │
│  ├─ S3.2: RetrievalChain + ConversationChain                  │
│  ├─ S3.3: Chain 可视化编辑器                                    │
│  └─ S3.4: 多轮对话记忆集成                                      │
│                                                                 │
│  阶段 4: 模型评估 (1周)                                         │
│  ├─ S4.1: 评估指标体系                                          │
│  ├─ S4.2: 评估报告生成                                          │
│  └─ S4.3: 多模型对比                                            │
│                                                                 │
│  阶段 5: 离线支持 (2周)                                         │
│  ├─ S5.1: 本地模式切换                                          │
│  ├─ S5.2: Milvus Lite 集成                                     │
│  └─ S5.3: Docker 一键部署                                       │
│                                                                 │
│  阶段 6: 商业功能 (1周)                                         │
│  ├─ S6.1: Token 统计 + 配额                                     │
│  └─ S6.2: API Key 管理                                          │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘
```

### 4.2 详细计划

#### 阶段 1: 基础增强 (2周)

**S1.1: Docling 服务部署 + 后端集成**

| 任务 | 负责人 | 交付物 | 验收标准 |
|------|--------|--------|----------|
| 部署 Docling Python 服务 | 后端 | Docling 服务运行 | POST /parse 返回正确 JSON |
| 实现 Java Docling Client | 后端 | DoclingClient.java | 单元测试通过 |
| 集成到知识库上传流程 | 后端 | KnowledgeService 改造 | PDF 解析表格正确 |
| 前端解析状态实时推送 | 前端 | WebSocket 集成 | 进度条实时更新 |
| 表格结构还原验证 | QA | 测试报告 | 行列正确率 ≥ 95% |

**S1.2: 混合搜索 + BM25 服务**

| 任务 | 负责人 | 交付物 | 验收标准 |
|------|--------|--------|----------|
| 实现 BM25 关键词检索 | 后端 | Bm25Service.java | Top-K 结果正确 |
| 集成向量检索 | 后端 | HybridSearchService.java | 双路结果合并 |
| RRF 融合实现 | 后端 | RrfFusion.java | 融合分数计算正确 |
| 配置化接口 | 后端 | 混合搜索开关 | 可动态配置权重 |

**S1.3: 基础 RAG Pipeline API**

| 任务 | 负责人 | 交付物 | 验收标准 |
|------|--------|--------|----------|
| 定义 Pipeline 接口 | 后端 | RagPipeline.java | 接口文档 |
| 实现 Pre/Post Retriever 接口 | 后端 | Trait 接口定义 | 可插拔 |
| 实现 Synthesizer | 后端 | Synthesizer.java | 生成质量达标 |
| 端到端集成测试 | QA | 测试报告 | P95 < 3s |

---

#### 阶段 2: Advanced RAG (3周)

**S2.1: Query Rewrite 实现**

| 任务 | 负责人 | 交付物 | 验收标准 |
|------|--------|--------|----------|
| 设计 Prompt 模板 | 后端 | query_rewrite.txt | 改写质量自评 |
| 实现 QueryRewriter | 后端 | QueryRewriter.java | 调用 LLM 重写 |
| Query Decomposer | 后端 | QueryDecomposer.java | 多子查询生成 |
| 集成到 Pipeline | 后端 | PreRetrieval.java | 链路测试通过 |
| 对比实验 | QA | 实验报告 | 重写后相关性提升 ≥ 30% |

**S2.2: RRF 融合 + Reranker**

| 任务 | 负责人 | 交付物 | 验收标准 |
|------|--------|--------|----------|
| Cross-Encoder 接口定义 | 后端 | CrossEncoder.java | 接口文档 |
| 调用 LLM 做重排 | 后端 | LlmReranker.java | 重排后顺序合理 |
| BGE-Reranker 集成 | 后端 | BgeReranker.java | GPU 加速 |
| Pipeline 集成 | 后端 | PostRetrieval.java | 重排后准确率提升 |
| 准确率评估 | QA | 评估报告 | Top-5 准确率 ≥ 85% |

**S2.3: RAG Pipeline 可视化配置**

| 任务 | 负责人 | 交付物 | 验收标准 |
|------|--------|--------|----------|
| 后端配置模型 | 后端 | RagConfig.java | 支持各阶段开关 |
| 前端配置面板 | 前端 | RagConfig.vue | 可拖拽配置 |
| Pipeline 可视化图 | 前端 | PipelineDiagram.vue | 实时显示阶段 |
| 检索统计面板 | 前端 | RetrievalStats.vue | 显示 Top-K 分数 |
| 端到端测试 | QA | 测试报告 | 配置生效 |

**S2.4: 检索准确率评估工具**

| 任务 | 负责人 | 交付物 | 验收标准 |
|------|--------|--------|----------|
| 评估数据集定义 | 后端 | EvaluationDataset.java | 测试集定义 |
| 准确率计算 | 后端 | AccuracyCalculator.java | 指标正确 |
| 评估报告生成 | 后端 | ReportGenerator.java | HTML 报告 |
| 集成到管理后台 | 前端 | 评估页面 | 可查看报告 |

---

#### 阶段 3: Chain 架构 (2周)

**S3.1: Chain 核心框架实现**

| 任务 | 负责人 | 交付物 | 验收标准 |
|------|--------|--------|----------|
| 定义 Chain 接口 | 后端 | BaseChain.java | 接口文档 |
| 定义 ChainContext | 后端 | ChainContext.java | 依赖注入 |
| 定义 ChainInput/Output | 后端 | ChainIO.java | 序列化 |
| ChainExecutor | 后端 | ChainExecutor.java | 执行调度 |

**S3.2: RetrievalChain + ConversationChain**

| 任务 | 负责人 | 交付物 | 验收标准 |
|------|--------|--------|----------|
| 实现 RetrievalChain | 后端 | RetrievalChain.java | 检索+生成 |
| 实现 ConversationChain | 后端 | ConversationChain.java | 多轮记忆 |
| 实现 BufferMemory | 后端 | BufferMemory.java | 记忆存取 |
| 集成到对话 API | 后端 | ChatService 改造 | 多轮正常 |

**S3.3: Chain 可视化编辑器**

| 任务 | 负责人 | 交付物 | 验收标准 |
|------|--------|--------|----------|
| Chain 模板定义 | 后端 | ChainTemplate.java | YAML 配置 |
| 前端 Chain 编辑器 | 前端 | ChainEditor.vue | 可视化拖拽 |
| Chain 执行日志 | 前端 | ChainFlow.vue | 执行步骤显示 |
| 保存/加载 Chain | 后端 | ChainConfig API | 持久化 |

**S3.4: 多轮对话记忆集成**

| 任务 | 负责人 | 交付物 | 验收标准 |
|------|--------|--------|----------|
| 会话记忆管理 | 后端 | SessionMemoryManager | 按会话隔离 |
| 记忆策略配置 | 前端 | 记忆设置页 | 可配置历史条数 |
| 长对话摘要 | 后端 | MemorySummarizer | 超长历史自动摘要 |
| E2E 测试 | QA | 测试报告 | 20 轮对话上下文保持 |

---

#### 阶段 4: 模型评估 (1周)

**S4.1: 评估指标体系**

| 任务 | 负责人 | 交付物 | 验收标准 |
|------|--------|--------|----------|
| 逻辑推理评估 Prompt | 后端 | logic_eval.txt | 评估 Prompt |
| 指令遵循评估 Prompt | 后端 | instruction_eval.txt | 评估 Prompt |
| 防幻觉评估 Prompt | 后端 | hallucination_eval.txt | 评估 Prompt |
| EvaluatorService | 后端 | EvaluatorService.java | 三维度评估 |

**S4.2: 评估报告生成**

| 任务 | 负责人 | 交付物 | 验收标准 |
|------|--------|--------|----------|
| 报告数据模型 | 后端 | EvaluationReport.java | 数据结构 |
| 报告生成器 | 后端 | ReportGenerator.java | HTML 报告 |
| 评估页面 | 前端 | Evaluation.vue | 可执行评估 |

**S4.3: 多模型对比**

| 任务 | 负责人 | 交付物 | 验收标准 |
|------|--------|--------|----------|
| 模型配置管理 | 后端 | ModelConfig.java | 多模型配置 |
| A/B 测试框架 | 后端 | ABTestRunner.java | 并行测试 |
| 对比图表 | 前端 | ModelCompare.vue | 雷达图对比 |

---

#### 阶段 5: 离线支持 (2周)

**S5.1: 本地模式切换**

| 任务 | 负责人 | 交付物 | 验收标准 |
|------|--------|--------|----------|
| 模式检测与切换 | 后端 | ModeDetector.java | 模式判断 |
| 本地 Llama.cpp 集成 | 后端 | Llama.cppClient.java | 本地模型调用 |
| API 统一抽象 | 后端 | LlmFactory.java | 模式透明切换 |
| 前端模式切换 UI | 前端 | ModeSwitch.vue | 一键切换 |

**S5.2: Milvus Lite 集成**

| 任务 | 负责人 | 交付物 | 验收标准 |
|------|--------|--------|----------|
| Milvus Lite 部署 | DevOps | docker-compose.yml | 本地向量库 |
| 切换逻辑 | 后端 | VectorStoreFactory.java | 动态切换 |
| 数据同步方案 | 后端 | DataSync.java | 云端/本地同步 |

**S5.3: Docker 一键部署**

| 任务 | 负责人 | 交付物 | 验收标准 |
|------|--------|--------|----------|
| Docker Compose 配置 | DevOps | docker-compose.yml | 一键启动 |
| 环境变量配置 | DevOps | .env.example | 配置示例 |
| 部署文档 | 文档 | DEPLOY.md | 部署说明 |

---

#### 阶段 6: 商业功能 (1周)

**S6.1: Token 统计 + 配额**

| 任务 | 负责人 | 交付物 | 验收标准 |
|------|--------|--------|----------|
| Token 计算服务 | 后端 | TokenCounter.java | 精确统计 |
| 配额检查拦截器 | 后端 | QuotaInterceptor.java | 超额拒绝 |
| 配额显示 UI | 前端 | QuotaDisplay.vue | 实时显示 |
| 配额预警 | 后端 | QuotaNotifier.java | 邮件通知 |

**S6.2: API Key 管理**

| 任务 | 负责人 | 交付物 | 验收标准 |
|------|--------|--------|----------|
| API Key 生成 | 后端 | ApiKeyService.java | 密钥生成 |
| 调用限流 | 后端 | RateLimiter.java | QPS 控制 |
| Key 管理页面 | 前端 | ApiKey.vue | CRUD + 禁用 |

---

## 5. 与桌面客户端功能对照

### 5.1 功能对照表

| 桌面端特性 | Web 端实现方式 | 差异说明 |
|------------|----------------|----------|
| Docling Python 解析 | Docling 微服务 | 桌面端 subprocess 调用，Web 端独立服务 |
| Chain 架构 | Chain Executor | Java 重写，接口兼容 |
| Hybrid Retriever | HybridSearchService | 功能一致 |
| Query Rewriter | QueryRewriteService | 功能一致 |
| Reranker | CrossEncoderService | 桌面端可本地 LLM，Web 端用 API |
| ConversationChain | ChatWithMemory | 功能一致 |
| 离线模式 | 本地 Docker 部署 | 桌面端更紧密，Web 端需 Docker |
| 模型评估 | EvaluationService | 功能一致 |
| 计费系统 | BillingService | Web 端更完善 |

### 5.2 技术差异说明

| 方面 | 桌面端 (Rust) | Web 端 (Java) |
|------|---------------|---------------|
| 文档解析 | Subprocess 调用 Python | 独立 Docling 微服务 |
| Chain 执行 | Rust trait + async | Java Interface + CompletableFuture |
| RAG Pipeline | Rust 模块化 | Java Service 组合 |
| 向量存储 | Milvus embedded | Milvus 远程服务 |
| LLM 调用 | llama.cpp 本地 | Llama.cpp/云端 API |
| 状态管理 | Rust 异步 | Java 响应式 |

---

## 6. 风险与缓解

| 风险 | 影响 | 缓解措施 |
|------|------|----------|
| Docling 服务性能 | 高 | 异步队列 + 缓存 + 横向扩展 |
| Query Rewrite 延迟 | 中 | LLM 调用异步 + 结果缓存 |
| 多模型切换复杂 | 中 | 统一抽象工厂模式 |
| 准确率提升瓶颈 | 高 | 持续迭代 Prompt + 人工标注 |
| Java Chain 性能 | 中 | 响应式编程 + 连接池 |

---

## 7. 验收标准

### 7.1 功能验收

| 特性 | 验收标准 | 测试方法 |
|------|----------|----------|
| Docling 解析 | 表格行列正确率 ≥ 95% | 建筑规范 PDF 样本测试 |
| 混合搜索 | Top-5 准确率 ≥ 85% | 100 题评测集 |
| Query Rewrite | 改写后相关性提升 ≥ 30% | A/B 对比测试 |
| Reranker | 重排后 NDCG@5 ≥ 0.8 | 评测集计算 |
| Chain 执行 | 20 轮对话上下文保持 | E2E 自动化测试 |
| 模型评估 | 三维度评估可执行 | 评估报告生成 |
| 离线模式 | Docker 一键启动成功 | 部署测试 |

### 7.2 性能验收

| 指标 | 目标 | 说明 |
|------|------|------|
| API P95 延迟 | < 3s | 复杂查询 |
| API P99 延迟 | < 5s | 最坏情况 |
| 并发用户 | ≥ 100 | QPS ≥ 50 |
| Docling 解析 | < 30s/文档 | 100 页 PDF |
| 准确率基线 | ≥ 85% | 检索 Top-5 |

---

## 8. 资源估算

### 8.1 开发资源

| 阶段 | 后端 | 前端 | QA | 合计 |
|------|------|------|-----|------|
| 阶段 1 | 2人 | 1人 | 0.5人 | 3.5人 |
| 阶段 2 | 2人 | 1人 | 0.5人 | 3.5人 |
| 阶段 3 | 1.5人 | 1.5人 | 0.5人 | 3.5人 |
| 阶段 4 | 1人 | 0.5人 | 0.5人 | 2人 |
| 阶段 5 | 1人 | 0.5人 | 0.5人 | 2人 |
| 阶段 6 | 1人 | 0.5人 | 0.5人 | 2人 |
| **总计** | **8.5人周** | **5人周** | **3人周** | **16.5人周** |

### 8.2 基础设施

| 资源 | 规格 | 数量 | 用途 |
|------|------|------|------|
| Docling 服务 | 4C8G | 2台 | 高可用 |
| Milvus | 4C8G | 1台 | 向量存储 |
| Redis | 2C4G | 1台 | 缓存/队列 |
| Llama.cpp (离线) | GPU 16G | 按需 | 本地推理 |

---

**文档版本**: 1.0
**创建日期**: 2026-04-22
**作者**: Claude AI
