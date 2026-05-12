# LS-ZGT Web 增强项目

> 以现有 LS-ZGT Web 应用为基础，集成桌面客户端所有高级特性

## 文档目录

| 文档 | 说明 |
|------|------|
| [requirements-and-plan.md](./requirements-and-plan.md) | 完整需求清单与实施计划 |

## 快速概览

### 目标

在现有 Web 应用基础上，集成以下桌面端高级特性：

```
┌─────────────────────────────────────────────────────────┐
│                    Web 增强目标                           │
├─────────────────────────────────────────────────────────┤
│                                                          │
│  ⭐ Docling 专业文档解析（表格/公式/OCR）              │
│  ⭐ Advanced RAG（混合搜索 + Query Rewrite + Reranker） │
│  ⭐ Chain 架构（模块化 RAG 流程）                      │
│  ⭐ 模型评估体系（逻辑/指令遵循/防幻觉）               │
│  ⭐ 离线支持（Docker 一键部署）                         │
│  ⭐ 商业功能（Token 统计 + 配额）                      │
│                                                          │
└─────────────────────────────────────────────────────────┘
```

### 痛点 → 解决方案映射

| RAG 痛点 | Web 端解决方案 |
|----------|----------------|
| 文档解析困难 | Docling 微服务 |
| 检索准确率 60% | 混合搜索 + RRF + Reranker |
| 提问模糊性 | Query Rewrite |
| 分块颗粒度 | 多种分块策略可配置 |
| 无模型评估 | 内置三维度评估体系 |
| 无法离线 | Docker 本地部署 |

### 实施周期

| 阶段 | 内容 | 周期 |
|------|------|------|
| 阶段 1 | 基础增强（Docling + 混合搜索） | 2周 |
| 阶段 2 | Advanced RAG（Query Rewrite + Reranker） | 3周 |
| 阶段 3 | Chain 架构 | 2周 |
| 阶段 4 | 模型评估 | 1周 |
| 阶段 5 | 离线支持 | 2周 |
| 阶段 6 | 商业功能 | 1周 |
| **合计** | | **11周** |

### 桌面端 vs Web 端

| 特性 | 桌面端 | Web 端 |
|------|--------|--------|
| 技术栈 | Rust + Flutter | Java + Vue |
| 部署 | 本地安装包 | 云服务 |
| 离线 | 原生支持 | Docker 部署 |
| 并发 | 单用户 | 多用户 |
| Docling | Subprocess | 独立微服务 |
| 向量库 | Milvus Lite | Milvus 服务 |

---

## 与桌面端文档对照

```
superpowers/
├── specs/                          # 设计规格
│   └── 2026-04-21-desktop-client-design.md  ← 桌面端设计
│
├── plans/                         # 实现计划
│   ├── 2026-04-21-phase1-foundation.md
│   ├── 2026-04-22-phase2-core-features.md
│   ├── 2026-04-22-phase2-advanced-rag-chains.md  ← Chain + RAG
│   ├── 2026-04-22-phase3-document-processing.md  ← Docling
│   ├── 2026-04-22-phase4-cloud-api.md
│   ├── 2026-04-22-phase5-billing.md
│   └── 2026-04-22-phase6-integrations.md
│
└── web-integration/               # Web 增强 ⭐
    ├── README.md                   ← 本文件
    └── requirements-and-plan.md     ← Web 端需求与计划
```

---

**更新日期**: 2026-04-22
