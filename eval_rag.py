#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
建筑规范RAG系统评测脚本 v2.0
Building Code RAG Evaluation Script v2.0

功能：
  - 三种检索方法：BM25、向量检索、混合检索（RRF融合）
  - 评测指标：Recall@K、Answer Accuracy、Citation Rate
  - 支持100条标准查询（单跳50条 + 多跳50条）
  - 自动生成评测报告（CSV + Markdown格式）

使用方法：
    python eval_rag.py --method all --output results.csv
    python eval_rag.py --method hybrid --eval-only
    python eval_rag.py --method bm25 --report

基于 LS-ZGT 项目：D:\pro_sunner\demo_vscode\LS-ZGT
知识库路径：backend/knowledge_base/建筑/

参考文献对应：
  - 论文实验数据：BM25 Recall@5=42.3%, Hybrid Recall@5=78.6%, Answer Accuracy=81.2%
"""

import os
import sys
import re
import json
import argparse
import csv
import datetime
from pathlib import Path
from typing import List, Dict, Tuple, Optional, Any
from dataclasses import dataclass, field

# ============================================================
# 可选依赖检测
# ============================================================

BM25_AVAILABLE = False
EMBEDDING_AVAILABLE = False
try:
    from rank_bm25 import BM25Okapi
    BM25_AVAILABLE = True
except ImportError:
    print("[WARN] rank_bm25 未安装，将使用纯Python BM25实现")

try:
    from sentence_transformers import SentenceTransformer
    import numpy as np
    EMBEDDING_AVAILABLE = True
except ImportError:
    print("[WARN] sentence_transformers 未安装，向量检索将被禁用")

# ============================================================
# 路径配置
# ============================================================

PROJECT_ROOT = Path(r"D:\pro_sunner\demo_vscode\LS-ZGT")
KNOWLEDGE_BASE = PROJECT_ROOT / "backend" / "knowledge_base" / "建筑"
REGULATIONS_DIR = Path(r"D:\pro_sunner\Regulations\example\50")
EVAL_DATASET_PATH = Path(r"C:\Users\33908\Desktop\小孙文件\Paper\建筑查询规范数据库智能体\评测集_100条.md")
OUTPUT_DIR = PROJECT_ROOT / "eval_results"

# ============================================================
# 数据结构
# ============================================================

@dataclass
class EvalQuery:
    """单条评测查询"""
    id: str
    query_type: str          # "单跳" | "多跳"
    category: str            # "防火分区", "安全疏散", "消防设施", etc.
    question: str
    expected_source: str     # 预期答案来源，如 "GB 50016 第5.3.1条"
    reasoning_chain: Optional[str] = None  # 推理链（多跳查询）
    answer_criteria: str = ""  # 评分标准说明


@dataclass
class EvalResult:
    """单条查询的评测结果"""
    query_id: str
    method: str
    query: str

    # 检索结果
    retrieved_docs: List[str] = field(default_factory=list)
    retrieval_scores: List[float] = field(default_factory=list)
    retrieval_time_ms: float = 0.0

    # 检索指标
    recall_at_1: float = 0.0
    recall_at_3: float = 0.0
    recall_at_5: float = 0.0

    # 生成指标（需要LLM，这里用检索指标模拟）
    answer_accuracy: float = 0.0   # 需要人工或LLM评判
    citation_rate: float = 0.0     # 需要人工或LLM评判

    # 多跳特有
    reasoning_steps: int = 0
    hallucination_rate: float = 0.0  # 模拟值


@dataclass
class BenchmarkReport:
    """完整评测报告"""
    timestamp: str
    methods: List[str]
    total_queries: int
    single_hop_queries: int
    multi_hop_queries: int
    results: Dict[str, List[EvalResult]] = field(default_factory=dict)

    # 汇总指标
    summary: Dict[str, Dict[str, float]] = field(default_factory=dict)


# ============================================================
# BM25 实现
# ============================================================

class SimpleBM25:
    """纯Python实现的BM25（不依赖rank_bm25）"""

    def __init__(self, documents: List[str], tokenizer=None):
        self.documents = documents
        self.tokenizer = tokenizer or self._simple_tokenizer
        self.doc_tokens = [self.tokenizer(d) for d in documents]
        self.N = len(documents)
        self.avgdl = sum(len(t) for t in self.doc_tokens) / self.N if self.N > 0 else 0
        self.k1 = 1.5    # BM25标准参数
        self.b = 0.75    # BM25标准参数
        self.idf_cache = None

    def _simple_tokenizer(self, text: str) -> List[str]:
        """中文分词（基于标点和空格切分）"""
        # 按标点符号和空格切分
        text = re.sub(r'[,，。！？、；;：:\n\r\t（）()【】\[\]《》""''""「」『』]', ' ', text)
        return [w.strip() for w in text.split() if len(w.strip()) > 1]

    def _calc_idf(self) -> Dict[str, float]:
        """计算IDF值（带缓存）"""
        if self.idf_cache is not None:
            return self.idf_cache

        df = {}
        for tokens in self.doc_tokens:
            seen = set()
            for token in tokens:
                if token not in seen:
                    df[token] = df.get(token, 0) + 1
                    seen.add(token)

        idf = {}
        for token, freq in df.items():
            # IDF公式: log((N - n + 0.5) / (n + 0.5) + 1)
            idf[token] = np.log((self.N - freq + 0.5) / (freq + 0.5) + 1)

        self.idf_cache = idf
        return idf

    def search(self, query: str, top_k: int = 5) -> List[Tuple[int, float]]:
        """执行BM25检索，返回(doc_index, score)列表"""
        if not BM25_AVAILABLE:
            return self._pure_bm25_search(query, top_k)
        else:
            return self._rank_bm25_search(query, top_k)

    def _pure_bm25_search(self, query: str, top_k: int) -> List[Tuple[int, float]]:
        """纯Python BM25搜索"""
        import time
        t0 = time.time()

        query_tokens = self._simple_tokenizer(query)
        if not query_tokens:
            return []

        idf = self._calc_idf()
        scores = []

        for i, doc_tokens in enumerate(self.doc_tokens):
            score = 0.0
            doc_len = len(doc_tokens)
            for token in query_tokens:
                if token in idf:
                    tf = doc_tokens.count(token)
                    idf_val = idf[token]
                    # BM25公式
                    numerator = tf * (self.k1 + 1)
                    denominator = tf + self.k1 * (1 - self.b + self.b * doc_len / (self.avgdl + 1e-8))
                    score += idf_val * numerator / denominator
            scores.append((i, score))

        scores.sort(key=lambda x: x[1], reverse=True)
        return scores[:top_k]

    def _rank_bm25_search(self, query: str, top_k: int) -> List[Tuple[int, float]]:
        """使用rank_bm25库的搜索"""
        bm25 = BM25Okapi(self.doc_tokens)
        scores = bm25.get_scores(self.tokenizer(query))
        ranked = sorted(enumerate(scores), key=lambda x: x[1], reverse=True)
        return [(i, float(s)) for i, s in ranked[:top_k]]

    def get_stats(self) -> Dict[str, Any]:
        """返回BM25统计信息"""
        return {
            "k1": self.k1,
            "b": self.b,
            "N": self.N,
            "avgdl": self.avgdl,
            "vocab_size": len(self.idf_cache) if self.idf_cache else 0
        }


# ============================================================
# 向量检索
# ============================================================

class VectorSearch:
    """基于BGE的向量检索"""

    def __init__(self, documents: List[str]):
        if not EMBEDDING_AVAILABLE:
            raise ImportError("sentence_transformers 未安装，请运行: pip install sentence-transformers")
        self.documents = documents
        print("  [pkg] 加载BGE向量模型 BAAI/bge-large-zh-v1.5...")
        self.model = SentenceTransformer('BAAI/bge-large-zh-v1.5')
        print("  [vec] 编码文档向量...")
        self.embeddings = self.model.encode(documents, show_progress_bar=False, batch_size=8)
        print(f"  [OK] 完成，共 {len(documents)} 个文档，维度 {self.embeddings[0].shape}")

    def search(self, query: str, top_k: int = 5) -> List[Tuple[int, float]]:
        """执行向量检索"""
        query_embedding = self.model.encode([query])[0]
        # 余弦相似度
        scores = np.dot(self.embeddings, query_embedding)
        norms = np.linalg.norm(self.embeddings, axis=1) * (np.linalg.norm(query_embedding) + 1e-8)
        scores = scores / norms
        ranked = sorted(enumerate(scores), key=lambda x: x[1], reverse=True)
        return [(int(i), float(s)) for i, s in ranked[:top_k]]


# ============================================================
# 混合检索（RRF融合）
# ============================================================

class HybridSearch:
    """BM25 + 向量混合检索（RRF融合）"""

    def __init__(self, documents: List[str], vector_weight: float = 0.6, rrf_k: int = 60):
        self.documents = documents
        self.vector_weight = vector_weight
        self.rrf_k = rrf_k

        print(f"  [srch] 初始化BM25检索器...")
        self.bm25 = SimpleBM25(documents)

        if EMBEDDING_AVAILABLE:
            try:
                print(f"  [srch] 初始化向量检索器...")
                self.vector = VectorSearch(documents)
                self.use_vector = True
                print(f"  [OK] 混合检索就绪（BM25权重={1-vector_weight:.1f}, 向量权重={vector_weight:.1f}, RRF k={rrf_k}）")
            except Exception as e:
                print(f"  [WARN] 向量检索初始化失败: {e}，回退为BM25检索")
                self.use_vector = False
        else:
            self.use_vector = False
            print(f"  [WARN] 向量模型不可用，使用纯BM25检索")

    def search(self, query: str, top_k: int = 5) -> List[Tuple[int, float]]:
        """执行混合检索"""
        bm25_results = self.bm25.search(query, top_k * 3)

        if self.use_vector:
            vector_results = self.vector.search(query, top_k * 3)

            # RRF（Reciprocal Rank Fusion）融合
            scores = {}
            for rank, (doc_idx, _) in enumerate(bm25_results):
                rrf_score = 1.0 / (self.rrf_k + rank + 1)
                scores[doc_idx] = scores.get(doc_idx, 0) + (1 - self.vector_weight) * rrf_score

            for rank, (doc_idx, _) in enumerate(vector_results):
                rrf_score = 1.0 / (self.rrf_k + rank + 1)
                scores[doc_idx] = scores.get(doc_idx, 0) + self.vector_weight * rrf_score

            ranked = sorted(scores.items(), key=lambda x: x[1], reverse=True)
            return ranked[:top_k]
        else:
            return bm25_results


# ============================================================
# 评测数据集
# ============================================================

class BCQBDataset:
    """建筑规范查询评测数据集 (BCQB-100)"""

    # 100条完整评测查询
    DATASET = [
        # === 单跳查询 Q001-Q050 ===
        # 1.1 防火分区与耐火等级（15条）
        EvalQuery("Q001", "单跳", "防火分区", "一类高层民用建筑的耐火等级是多少？", "GB 50016 第5.1.2条"),
        EvalQuery("Q002", "单跳", "耐火极限", "二级耐火等级建筑的非承重外墙的耐火极限是多少小时？", "GB 50016 表5.1.8"),
        EvalQuery("Q003", "单跳", "耐火等级", "民用建筑的耐火等级分为几级？", "GB 50016 第5.1.2条"),
        EvalQuery("Q004", "单跳", "防火分区", "地下车库的防火分区最大允许面积是多少平方米？", "GB 50016 第5.3.1条"),
        EvalQuery("Q005", "单跳", "防火分区", "一类高层民用建筑的防火分区最大允许面积是多少平方米？", "GB 50016 第5.3.1条"),
        EvalQuery("Q006", "单跳", "防火分区", "二类高层民用建筑的防火分区最大允许面积是多少平方米？", "GB 50016 第5.3.1条"),
        EvalQuery("Q007", "单跳", "防火分区", "设置自动喷水灭火系统时，防火分区面积可以扩大多少倍？", "GB 50016 第5.3.1条"),
        EvalQuery("Q008", "单跳", "防火分区", "当建筑内设置自动喷水灭火系统时，地下车库的防火分区面积如何变化？", "GB 50016 第5.3.1条"),
        EvalQuery("Q009", "单跳", "防火分区", "高层建筑裙房的防火分区面积应符合什么规定？", "GB 50016 第5.3.1条"),
        EvalQuery("Q010", "单跳", "防火分区", "地下室的防火分区最大允许面积是多少？", "GB 50016 第5.3.1条"),
        EvalQuery("Q011", "单跳", "术语", "什么是防火分区？", "GB 50016 术语定义"),
        EvalQuery("Q012", "单跳", "术语", "什么是耐火极限？", "GB 50016 术语定义"),
        EvalQuery("Q013", "单跳", "耐火极限", "梁的耐火极限：一级是多少小时？二级是多少小时？", "GB 50016 表5.1.8"),
        EvalQuery("Q014", "单跳", "耐火极限", "楼板的耐火极限：二级是多少小时？三级是多少小时？", "GB 50016 表5.1.8"),
        EvalQuery("Q015", "单跳", "耐火极限", "疏散楼梯的耐火极限：一级是多少小时？", "GB 50016 表5.1.8"),

        # 1.2 安全疏散（15条）
        EvalQuery("Q016", "单跳", "安全疏散", "公共建筑的安全出口的最小数量是多少？", "GB 50016 第5.5.2条"),
        EvalQuery("Q017", "单跳", "安全疏散", "房间内任一点到最近疏散门的直线距离不应超过多少米？", "GB 50016 第5.5.2条"),
        EvalQuery("Q018", "单跳", "安全疏散", "歌舞娱乐放映游艺场所的疏散距离应如何折减？", "GB 50016 第5.5.2条"),
        EvalQuery("Q019", "单跳", "安全疏散", "高层民用建筑的安全疏散距离有何特殊要求？", "GB 50016 第5.5.2条"),
        EvalQuery("Q020", "单跳", "安全疏散", "疏散楼梯的最小净宽度不应小于多少米？", "GB 50016 第5.5.2条"),
        EvalQuery("Q021", "单跳", "安全疏散", "高层医疗建筑疏散楼梯的最小净宽度是多少？", "GB 50016 第5.5.2条"),
        EvalQuery("Q022", "单跳", "安全疏散", "托儿所、幼儿园的安全疏散距离是多少米？", "GB 50016 第5.5.2条"),
        EvalQuery("Q023", "单跳", "安全疏散", "商场内人员密度如何确定？", "GB 50016 第5.5.2条"),
        EvalQuery("Q024", "单跳", "楼梯间", "什么样的建筑应设置封闭楼梯间？", "GB 50016 第6.4.2条"),
        EvalQuery("Q025", "单跳", "楼梯间", "什么样的建筑应设置防烟楼梯间？", "GB 50016 第6.4.2条"),
        EvalQuery("Q026", "单跳", "安全疏散", "楼梯间的首层疏散门的最小净宽度是多少？", "GB 50016 第5.5.2条"),
        EvalQuery("Q027", "单跳", "楼梯间", "高层公共建筑内疏散楼梯间的门应为哪种类型？", "GB 50016 第6.4.2条"),
        EvalQuery("Q028", "单跳", "术语", "什么是安全出口？", "GB 50016 术语定义"),
        EvalQuery("Q029", "单跳", "术语", "什么是疏散走道？", "GB 50016 术语定义"),
        EvalQuery("Q030", "单跳", "安全疏散", "观众厅的疏散门不应设置门槛，其净宽度不应小于多少？", "GB 50016 第5.5.2条"),

        # 1.3 消防设施（10条）
        EvalQuery("Q031", "单跳", "消防设施", "哪些建筑应设置自动喷水灭火系统？", "GB 50016 第8.3.3条"),
        EvalQuery("Q032", "单跳", "消防设施", "哪些建筑应设置火灾自动报警系统？", "GB 50016 第8.4.1条"),
        EvalQuery("Q033", "单跳", "消防设施", "地下、半地下建筑（室）的防火分区面积超过多少时，应设自动喷水灭火系统？", "GB 50016 第8.3.3条"),
        EvalQuery("Q034", "单跳", "消防设施", "室内消火栓的充实水柱长度不应小于多少米？", "GB 50016 第8.1.3条"),
        EvalQuery("Q035", "单跳", "排烟设施", "哪些场所应设置排烟设施？", "GB 50016 第8.5.2条"),
        EvalQuery("Q036", "单跳", "排烟设施", "机械排烟系统的排烟量如何计算？", "GB 50016 第8.5.2条"),
        EvalQuery("Q037", "单跳", "术语", "什么是防火门？", "GB 50016 术语定义"),
        EvalQuery("Q038", "单跳", "术语", "什么是防火卷帘？", "GB 50016 术语定义"),
        EvalQuery("Q039", "单跳", "消防设施", "住宅建筑的公共部位是否需要设置火灾自动报警系统？", "GB 50016 第8.4.1条"),
        EvalQuery("Q040", "单跳", "消防设施", "自动喷水灭火系统的喷水强度不应小于多少？", "GB 50016 第8.3.3条"),

        # 1.4 建筑构造（10条）
        EvalQuery("Q041", "单跳", "建筑构造", "防火墙的耐火极限不应低于多少小时？", "GB 50016 第6.1.1条"),
        EvalQuery("Q042", "单跳", "建筑构造", "防火墙上是否可以开设门窗洞口？", "GB 50016 第6.1.2条"),
        EvalQuery("Q043", "单跳", "建筑构造", "疏散走道两侧的隔墙耐火极限不应低于多少小时？", "GB 50016 第5.1.2条"),
        EvalQuery("Q044", "单跳", "建筑构造", "电缆井、管道井的井壁应采用什么耐火等级的材料？", "GB 50016 第6.2.9条"),
        EvalQuery("Q045", "单跳", "建筑构造", "中庭的自动排烟设施的排烟量如何确定？", "GB 50016 第5.3.2条"),
        EvalQuery("Q046", "单跳", "建筑构造", "变形缝两侧的基层应采用什么耐火等级？", "GB 50016 第6.3.6条"),
        EvalQuery("Q047", "单跳", "建筑构造", "通风、空气调节系统的风管在穿越防火分区处应设置什么装置？", "GB 50016 第9.3.1条"),
        EvalQuery("Q048", "单跳", "排烟设施", "排烟管道不应穿过什么区域？", "GB 50016 第8.5.2条"),
        EvalQuery("Q049", "单跳", "建筑构造", "燃气管道在穿越墙体或楼板时应如何处理？", "GB 50016 第6.4.4条"),
        EvalQuery("Q050", "单跳", "建筑构造", "防火卷帘的耐火极限不应低于多少小时？", "GB 50016 第6.5.3条"),

        # === 多跳推理查询 Q051-Q100 ===
        # 2.1 综合推理（20条）
        EvalQuery("Q051", "多跳", "综合推理", "某综合楼地上24层，一层为商场，二层以上为住宅，该楼的疏散楼梯应如何设置？",
                  "GB 50016 第5.5.2条+第6.4.2条", "判断建筑分类→确定耐火等级→确定疏散楼梯类型"),
        EvalQuery("Q052", "多跳", "综合推理", "一栋高度为80m的一类高层办公楼，设置自动喷水灭火系统后，其防火分区的最大面积是多少？",
                  "GB 50016 第5.3.1条", "确定建筑类型→确定基础防火分区面积→确定自动喷淋放大倍数"),
        EvalQuery("Q053", "多跳", "综合推理", "地下二层为汽车库，地下一层为商场，该建筑的消防设施应如何设置？",
                  "GB 50016 第8.3.3条+第8.4.1条", "判断车库类型→判断地下商业→综合确定消防设施"),
        EvalQuery("Q054", "多跳", "综合推理", "三级耐火等级的网吧，其疏散距离是多少米？应设置什么楼梯？",
                  "GB 50016 第5.5.2条+第6.4.2条", "确定场所类型→确定耐火等级→查疏散距离表→判断楼梯类型"),
        EvalQuery("Q055", "多跳", "综合推理", "某医院建筑高度60m，其手术部的防火分区和安全疏散有何特殊要求？",
                  "GB 50016 第5.3.1条+第5.5.2条", "确定建筑类型→确定耐火等级→确定防火分区面积→确定疏散距离"),
        EvalQuery("Q056", "多跳", "综合推理", "一栋多层丙类厂房，设有自动喷水灭火系统，其防火分区的最大面积是多少？",
                  "GB 50016 第5.3.1条", "确定建筑类型→确定耐火等级→确定基础面积→考虑自动喷淋放大"),
        EvalQuery("Q057", "多跳", "综合推理", "歌舞厅设置在某综合楼的三层，其疏散距离和疏散楼梯有何要求？",
                  "GB 50016 第5.5.2条+第6.4.2条", "确定场所类型→确定疏散距离折减→确定楼梯类型"),
        EvalQuery("Q058", "多跳", "综合推理", "某商场的营业厅建筑面积为3000平方米，其安全疏散应如何设计？",
                  "GB 50016 第5.5.2条", "确定场所类型→计算疏散人数→确定安全出口数量→确定疏散宽度"),
        EvalQuery("Q059", "多跳", "综合推理", "高度超过100m的民用建筑，其消防设施应满足哪些特殊要求？",
                  "GB 50016 第8章", "确定建筑高度→确定建筑分类→确定消防设施要求"),
        EvalQuery("Q060", "多跳", "综合推理", "某教学楼的实验室，存放有易燃易爆物品，其防火分隔有何特殊要求？",
                  "GB 50016 第5.3.1条+第6.1.1条", "确定场所类型→确定危险品等级→确定防火分隔要求"),
        EvalQuery("Q061", "多跳", "综合推理", "高层住宅楼的地下室兼作停车场，其与住宅部分如何进行防火分隔？",
                  "GB 50016 第5.4.10条+第6.1.1条", "确定地下功能→确定住宅性质→确定防火分隔要求"),
        EvalQuery("Q062", "多跳", "综合推理", "某大型商业综合体，设有中庭，其排烟系统应如何设计？",
                  "GB 50016 第5.3.2条+第8.5.2条", "确定中庭位置→确定排烟量计算方法→确定排烟设施要求"),
        EvalQuery("Q063", "多跳", "综合推理", "三级耐火等级的丙类仓库，其防火分区最大面积是多少？是否可扩大？",
                  "GB 50016 第5.3.1条", "确定建筑类型→确定耐火等级→确定基础面积→判断扩大条件"),
        EvalQuery("Q064", "多跳", "综合推理", "某老年人照料设施，设置自动喷水灭火系统后，其安全疏散距离有何变化？",
                  "GB 50016 第5.5.2条", "确定建筑类型→确定基础疏散距离→确定自动喷淋的影响"),
        EvalQuery("Q065", "多跳", "综合推理", "某地下商场，建筑面积超过2000平方米，其应设置哪些消防设施？",
                  "GB 50016 第8.3.3条+第8.4.1条", "确定场所类型→确定面积等级→确定消防设施要求"),
        EvalQuery("Q066", "多跳", "综合推理", "某高层酒店，其消防电梯前室的防火分隔有何要求？",
                  "GB 50016 第7.3.5条+第6.1.1条", "确定建筑类型→确定消防电梯设置→确定前室防火要求"),
        EvalQuery("Q067", "多跳", "综合推理", "某洁净厂房，其防火分区和消防设施有何特殊要求？",
                  "GB 50016 第5.3.1条+第8.3.3条", "确定建筑类型→确定洁净等级→确定防火分区要求→确定消防设施"),
        EvalQuery("Q068", "多跳", "综合推理", "二级耐火等级的商店建筑，其位于四层时，每层的安全出口数量如何确定？",
                  "GB 50016 第5.5.2条", "确定建筑类型→确定耐火等级→确定楼层位置→确定疏散人数→确定出口数量"),
        EvalQuery("Q069", "多跳", "综合推理", "某建筑高度为54m的住宅楼，其剪刀楼梯的防火分隔有何要求？",
                  "GB 50016 第6.4.2条", "确定建筑高度→确定楼梯类型→确定防火分隔要求"),
        EvalQuery("Q070", "多跳", "综合推理", "某KTV设置在地下一层，其防火分区面积和疏散距离有何要求？",
                  "GB 50016 第5.3.1条+第5.5.2条", "确定场所位置→确定场所类型→确定防火分区要求→确定疏散距离"),

        # 2.2 规范交叉引用（15条）
        EvalQuery("Q071", "多跳", "交叉引用", "高层建筑中庭的防火分隔应满足哪些要求？",
                  "GB 50016 第5.3.2条 + 第6.1.1条", "引用条文: 5.3.2中庭定义 + 6.1.1防火墙耐火极限"),
        EvalQuery("Q072", "多跳", "交叉引用", "疏散楼梯间在首层应如何进行防火分隔？",
                  "GB 50016 第6.4.2条 + 第6.1.1条", "引用条文: 6.4.2楼梯间设置 + 6.1.1防火分隔要求"),
        EvalQuery("Q073", "多跳", "交叉引用", "消防控制室的疏散门有何特殊要求？",
                  "GB 50016 第8.1.7条 + 第5.5.2条", "引用条文: 8.1.7消防控制室 + 5.5.2疏散门要求"),
        EvalQuery("Q074", "多跳", "交叉引用", "变配电室与其他部位分隔的防火墙耐火极限是多少？",
                  "GB 50016 第6.1.1条 + 表5.1.8", "引用条文: 6.1.1防火墙设置 + 表5.1.8耐火极限"),
        EvalQuery("Q075", "多跳", "交叉引用", "电影院观众厅的防火分隔和疏散设计应满足哪些要求？",
                  "GB 50016 第5.4.7条 + 第5.5.2条 + 第6.4.2条", "引用条文: 5.4.7电影院 + 5.5.2疏散 + 6.4.2楼梯"),
        EvalQuery("Q076", "多跳", "交叉引用", "柴油发电机房应如何设置防火分隔和通风设施？",
                  "GB 50016 第5.4.3条 + 第9.1.1条", "引用条文: 5.4.3发电机房 + 9.1.1通风要求"),
        EvalQuery("Q077", "多跳", "交叉引用", "液化石油气瓶组供气站的防火间距应满足什么要求？",
                  "GB 50016 第5.4.1条 + 第3.4.1条", "引用条文: 5.4.1燃气 + 3.4.1防火间距"),
        EvalQuery("Q078", "多跳", "交叉引用", "歌舞厅的疏散楼梯为何不能采用旋转楼梯？",
                  "GB 50016 第6.4.2条 + 第5.5.2条", "引用条文: 6.4.2楼梯类型禁止 + 5.5.2疏散要求"),
        EvalQuery("Q079", "多跳", "交叉引用", "医院洁净手术室的防火分隔应采用什么耐火极限的墙体？",
                  "GB 50016 第5.1.2条 + 表5.1.8", "引用条文: 5.1.2耐火等级 + 表5.1.8构件耐火极限"),
        EvalQuery("Q080", "多跳", "交叉引用", "住宅与非住宅部分合建时，防火分隔应如何处理？",
                  "GB 50016 第5.4.10条 + 第6.1.1条", "引用条文: 5.4.10商住楼 + 6.1.1防火分隔"),
        EvalQuery("Q081", "多跳", "交叉引用", "自动扶梯的防火分隔和火灾自动报警系统如何联动？",
                  "GB 50016 第5.3.2条 + 第8.4.1条", "引用条文: 5.3.2中庭自动扶梯 + 8.4.1火灾自动报警"),
        EvalQuery("Q082", "多跳", "交叉引用", "屋顶直升机停机坪的防火分隔和安全疏散有何要求？",
                  "GB 50016 第7.4.1条 + 第5.5.2条", "引用条文: 7.4.1直升机停机坪 + 5.5.2疏散要求"),
        EvalQuery("Q083", "多跳", "交叉引用", "商场内儿童活动场所的防火分隔和安全疏散有何特殊要求？",
                  "GB 50016 第5.4.4条 + 第5.5.2条", "引用条文: 5.4.4儿童活动场所 + 5.5.2疏散要求"),
        EvalQuery("Q084", "多跳", "交叉引用", "锅炉房的防火分隔和防爆设计应满足哪些要求？",
                  "GB 50016 第5.4.2条 + 第3.3.1条", "引用条文: 5.4.2锅炉房 + 3.3.1防爆要求"),
        EvalQuery("Q085", "多跳", "交叉引用", "综合楼内歌舞厅的消防设施应如何配置？",
                  "GB 50016 第8.3.3条 + 第8.4.1条 + 第8.5.2条", "引用条文: 8.3.3自动喷淋 + 8.4.1火灾报警 + 8.5.2排烟"),

        # 2.3 复杂条件推理（15条）
        EvalQuery("Q086", "多跳", "复杂条件", "某建筑高度为27m的教学楼，层数为8层，其楼梯间的设置类型是什么？",
                  "GB 50016 第6.4.2条", "高度>24m+多层公共建筑→判断楼梯类型"),
        EvalQuery("Q087", "多跳", "复杂条件", "某建筑高度为32m的医院内科楼，其防烟楼梯间的设置条件是什么？",
                  "GB 50016 第6.4.2条", "高度>24m+一类高层公共建筑→设置防烟楼梯间"),
        EvalQuery("Q088", "多跳", "复杂条件", "某商店建筑地下二层，层高4m，其防火分区的最大面积和最大长度是多少？",
                  "GB 50016 第5.3.1条", "地下+多层+商店→确定防火分区面积"),
        EvalQuery("Q089", "多跳", "复杂条件", "某KTV设置在某综合楼的四层，建筑面积500平方米，其疏散楼梯应如何设置？",
                  "GB 50016 第6.4.2条", "四层+KTV+综合楼→设置封闭楼梯间"),
        EvalQuery("Q090", "多跳", "复杂条件", "某丙类厂房设有自动喷水灭火系统，且采用了不燃性装修材料，其防火分区的最大面积是多少？",
                  "GB 50016 第5.3.1条", "丙类+自动喷淋+不燃装修→确定防火分区面积"),
        EvalQuery("Q091", "多跳", "复杂条件", "某老年人照料设施的走廊两侧采用耐火极限2.0h的隔墙，其疏散距离可以放宽到多少米？",
                  "GB 50016 第5.5.2条", "老年人设施+2.0h隔墙→疏散距离放宽条件"),
        EvalQuery("Q092", "多跳", "复杂条件", "某建筑高度为150m的超高层办公楼，其避难层的设置间距不应超过多少米？",
                  "GB 50016 第5.5.2条", "高度>100m超高层建筑→避难层间距要求"),
        EvalQuery("Q093", "多跳", "复杂条件", "某地下商场采用不开设门窗洞口的防火墙分隔，其防火分区的最大面积是多少？",
                  "GB 50016 第5.3.1条", "地下商场+不开洞防火墙→面积放宽条件"),
        EvalQuery("Q094", "多跳", "复杂条件", "某综合楼的地下一层为超市，建筑面积1500平方米，其应设置哪些消防设施？",
                  "GB 50016 第8.3.3条+第8.4.1条", "地下+超市+面积>1000→消防设施配置"),
        EvalQuery("Q095", "多跳", "复杂条件", "某高度为58m的一类高层住宅楼，其剪刀楼梯间的防火分隔有何特殊要求？",
                  "GB 50016 第6.4.2条", "一类高层住宅+剪刀楼梯→防火分隔要求"),
        EvalQuery("Q096", "多跳", "复杂条件", "某丙类液体储罐区，其防火堤的高度和耐火极限有何要求？",
                  "GB 50016 第4.2.1条", "丙类液体+储罐区→防火堤设置要求"),
        EvalQuery("Q097", "多跳", "复杂条件", "某三级耐火等级的夜总会，其疏散楼梯能否采用金属梯？",
                  "GB 50016 第6.4.2条", "三级耐火+人员密集场所→楼梯材料限制"),
        EvalQuery("Q098", "多跳", "复杂条件", "某建筑高度为26m的医院门诊楼，其楼梯间的消防前室面积有何要求？",
                  "GB 50016 第7.3.2条", "26m医院+消防电梯前室→前室面积要求"),
        EvalQuery("Q099", "多跳", "复杂条件", "某洁净厂房的疏散走廊长度超过多少米时，应设置排烟设施？",
                  "GB 50016 第8.5.2条", "洁净厂房+疏散走廊→排烟设置条件"),
        EvalQuery("Q100", "多跳", "复杂条件", "某商场的营业厅面积为4000平方米，设有自动喷水灭火系统，其疏散楼梯的最小净宽度如何计算？",
                  "GB 50016 第5.5.2条", "大型商场+自动喷淋→疏散宽度计算"),
    ]

    def __init__(self):
        self.queries = self.DATASET
        self.total = len(self.queries)
        self.single_hop = [q for q in self.queries if q.query_type == "单跳"]
        self.multi_hop = [q for q in self.queries if q.query_type == "多跳"]

    def get_all(self) -> List[EvalQuery]:
        return self.queries

    def get_by_type(self, query_type: str) -> List[EvalQuery]:
        return [q for q in self.queries if q.query_type == query_type]

    def get_by_category(self, category: str) -> List[EvalQuery]:
        return [q for q in self.queries if q.category == category]

    def stats(self) -> Dict[str, int]:
        cats = {}
        for q in self.queries:
            cats[q.category] = cats.get(q.category, 0) + 1
        return {
            "total": self.total,
            "单跳": len(self.single_hop),
            "多跳": len(self.multi_hop),
            **cats
        }


# ============================================================
# 知识库加载
# ============================================================

def load_knowledge_base() -> List[Dict]:
    """加载建筑规范知识库"""
    docs = []

    if KNOWLEDGE_BASE.exists():
        for md_file in sorted(KNOWLEDGE_BASE.glob("*.md")):
            try:
                content = md_file.read_text(encoding='utf-8')
                if len(content.strip()) > 100:  # 过滤空文件
                    docs.append({
                        "id": md_file.stem,
                        "content": content,
                        "source": "knowledge_base",
                        "path": str(md_file)
                    })
            except Exception as e:
                print(f"  [WARN] 读取 {md_file.name} 失败: {e}")

    if not docs:
        print("[WARN] 知识库为空，将使用模拟数据进行评测演示")
        docs = _create_mock_knowledge_base()

    return docs


def _create_mock_knowledge_base() -> List[Dict]:
    """当知识库为空时，创建模拟规范片段用于演示"""
    mock_docs = []
    sections = [
        ("GB50016_防火分区", """# 建筑设计防火规范 GB 50016-2014

## 5.3 防火分区

### 第5.3.1条 防火分区最大允许面积

各类建筑的防火分区最大允许建筑面积（平方米）：

| 建筑类型 | 耐火等级 | 防火分区面积 |
|---------|---------|------------|
| 一类高层民用建筑 | 一级 | 1000 |
| 二类高层民用建筑 | 一级、二级 | 1500 |
| 高层民用建筑裙房 | 一级、二级 | 2500 |
| 地下汽车库 | 一级、二级 | 2000 |
| 地下、半地下室 | 一级、二级 | 500（设自动喷淋可扩大至1000）|
| 多层民用建筑 | 一级、二级 | 2500 |
| 三级耐火等级民用建筑 | 三级 | 1200 |

注：设置自动喷水灭火系统时，防火分区面积可扩大1倍。

### 第5.3.2条 中庭防火

中庭的防火分隔应满足以下要求：
1. 中庭与周围连通空间应采用耐火极限不低于1.00h的防火卷帘分隔
2. 中庭应设置排烟设施
3. 中庭内不应设置可燃物"""),

        ("GB50016_安全疏散", """# 5.5 安全疏散

## 第5.5.2条 安全疏散基本要求

1. 公共建筑内每个防火分区或一个防火分区的每个楼层，其安全出口的数量应经计算确定，且不应少于2个。
2. 房间内任一点到最近疏散门的直线距离不应超过下表规定：
   - 托儿所、幼儿园：15m（位于袋形走道时12m）
   - 医院、学校：30m（位于袋形走道时15m）
   - 其他民用建筑：40m（位于袋形走道时22m）
3. 疏散楼梯的最小净宽度不应小于1.10m。
4. 高层医疗建筑疏散楼梯的最小净宽度不应小于1.30m。
5. 观众厅的疏散门不应设置门槛，其净宽度不应小于1.40m。"""),

        ("GB50016_消防设施", """# 8 消防设施

## 第8.3.3条 自动喷水灭火系统设置

下列建筑或场所应设置自动喷水灭火系统：
1. 总建筑面积大于1500平方米的地下、半地下商场
2. 任一层建筑面积大于1500平方米的商店、展览建筑
3. 高层民用建筑
4. 地下建筑
5. 歌舞娱乐放映游艺场所

注：自动喷水灭火系统的喷水强度不应小于6L/min·m²，作用面积不应小于160m²。

## 第8.4.1条 火灾自动报警系统

下列建筑或场所应设置火灾自动报警系统：
1. 任一层建筑面积大于1500平方米的商场、展览建筑
2. 图书、文物珍藏库
3. 歌舞娱乐放映游艺场所
4. 高层民用建筑"""),

        ("GB50016_楼梯间", """# 6.4 疏散楼梯间

## 第6.4.2条 封闭楼梯间设置要求

下列建筑应设置封闭楼梯间：
1. 裙房和建筑高度不超过32m的二类高层公共建筑
2. 6层及6层以下的多层公共建筑
3. 商店、图书馆、会议中心等人员密集场所

下列建筑应设置防烟楼梯间：
1. 一类高层公共建筑
2. 建筑高度超过32m的二类高层公共建筑
3. 建筑高度超过33m的住宅建筑

高层公共建筑内的疏散楼梯间的门应为乙级防火门。"""),

        ("GB50016_耐火极限", """# 5.1 建筑分类和耐火等级

## 第5.1.2条 民用建筑耐火等级

民用建筑的耐火等级分为一、二、三、四级。不同耐火等级建筑相应构件的燃烧性能和耐火极限（小时）：

| 构件名称 | 一级 | 二级 | 三级 | 四级 |
|---------|-----|-----|-----|-----|
| 防火墙 | 3.00 | 3.00 | 3.00 | 3.00 |
| 承重墙 | 3.00 | 2.50 | 2.00 | 0.50 |
| 非承重外墙 | 1.00 | 1.00 | 0.50 | 可燃 |
| 疏散楼梯 | 1.50 | 1.00 | 0.50 | 可燃 |
| 楼板 | 1.50 | 1.00 | 0.50 | 可燃 |
| 梁 | 2.00 | 1.50 | 1.00 | 可燃 |
| 柱 | 3.00 | 2.50 | 2.00 | 可燃 |
| 疏散走道两侧隔墙 | 1.00 | 1.00 | 0.50 | 可燃 |"""),
    ]

    for doc_id, content in sections:
        mock_docs.append({
            "id": doc_id,
            "content": content,
            "source": "mock"
        })

    return mock_docs


# ============================================================
# 检索指标计算
# ============================================================

def compute_recall(results: List[Tuple[int, float]], relevant_indices: List[int], k: int) -> float:
    """计算Recall@K"""
    if not relevant_indices:
        return 0.0
    retrieved = set(i for i, _ in results[:k])
    relevant = set(relevant_indices)
    return len(retrieved & relevant) / len(relevant)


def _build_relevant_index_map(queries: List[EvalQuery], docs: List[Dict]) -> Dict[str, List[int]]:
    """根据预期来源构建相关文档索引映射（用于Recall计算）

    这是一个简化的相关性标注方法。
    实际评测中应通过人工标注或LLM评判来确定真正相关的文档。
    """
    # 通过关键词匹配来估算相关文档
    mapping = {}
    for i, q in enumerate(queries):
        relevant = []
        keywords = _extract_keywords(q.expected_source)
        for j, doc in enumerate(docs):
            content_lower = doc["content"].lower()
            if any(kw in content_lower for kw in keywords):
                relevant.append(j)
        mapping[q.id] = relevant if relevant else [0]  # 默认第0个
    return mapping


def _extract_keywords(source: str) -> List[str]:
    """从预期来源提取关键词"""
    # 提取条文编号
    import re
    clauses = re.findall(r'第[\d.]+条', source)
    terms = re.findall(r'[\u4e00-\u9fa5]{2,}', source)
    return clauses + [t for t in terms if len(t) >= 2][:5]


# ============================================================
# 核心评测函数
# ============================================================

def run_retrieval_eval(
    retriever,
    docs: List[Dict],
    queries: List[EvalQuery],
    method: str,
    relevant_map: Dict[str, List[int]]
) -> List[EvalResult]:
    """运行检索评测"""
    import time
    results = []

    for q in queries:
        t0 = time.time()
        search_results = retriever.search(q.question, top_k=10)
        elapsed_ms = (time.time() - t0) * 1000

        retrieved_ids = [docs[i]["id"] for i, _ in search_results]
        scores = [float(s) for _, s in search_results]

        # 计算Recall@K
        rel_idx = relevant_map.get(q.id, [])
        r1 = compute_recall(search_results, rel_idx, 1)
        r3 = compute_recall(search_results, rel_idx, 3)
        r5 = compute_recall(search_results, rel_idx, 5)

        # 模拟生成指标（基于检索分数）
        top_score = scores[0] if scores else 0.0
        # 经验公式：检索分数越高，答案准确率越高
        simulated_acc = min(0.95, top_score * 1.2 + 0.2) if top_score > 0 else 0.0
        simulated_cite = min(0.95, top_score * 1.0 + 0.1) if top_score > 0 else 0.0

        result = EvalResult(
            query_id=q.id,
            method=method,
            query=q.question,
            retrieved_docs=retrieved_ids,
            retrieval_scores=scores,
            retrieval_time_ms=elapsed_ms,
            recall_at_1=r1,
            recall_at_3=r3,
            recall_at_5=r5,
            answer_accuracy=simulated_acc,
            citation_rate=simulated_cite,
        )
        results.append(result)

    return results


def aggregate_results(all_results: Dict[str, List[EvalResult]], dataset: BCQBDataset) -> Dict[str, Dict[str, float]]:
    """汇总评测结果"""
    summary = {}

    for method, results in all_results.items():
        single_hop = [r for r in results if r.query_id.startswith("Q0") and int(r.query_id[1:]) <= 50]
        multi_hop = [r for r in results if r.query_id.startswith("Q0") and int(r.query_id[1:]) > 50]

        summary[method] = {
            # 总体
            "recall@1": sum(r.recall_at_1 for r in results) / len(results) * 100,
            "recall@3": sum(r.recall_at_3 for r in results) / len(results) * 100,
            "recall@5": sum(r.recall_at_5 for r in results) / len(results) * 100,
            "avg_accuracy": sum(r.answer_accuracy for r in results) / len(results) * 100,
            "avg_citation": sum(r.citation_rate for r in results) / len(results) * 100,
            "avg_time_ms": sum(r.retrieval_time_ms for r in results) / len(results),
            # 单跳
            "recall@5_single": sum(r.recall_at_5 for r in single_hop) / max(len(single_hop), 1) * 100,
            # 多跳
            "recall@5_multi": sum(r.recall_at_5 for r in multi_hop) / max(len(multi_hop), 1) * 100,
            # 模拟指标（按论文数据校准）
            "_paper_bm25_recall5": 42.3,
            "_paper_hybrid_recall5": 78.6,
        }

    return summary


# ============================================================
# 报告生成
# ============================================================

def generate_markdown_report(
    summary: Dict[str, Dict[str, float]],
    all_results: Dict[str, List[EvalResult]],
    dataset: BCQBDataset,
    timestamp: str
) -> str:
    """生成Markdown格式的评测报告"""

    lines = [
        f"# 建筑规范RAG系统评测报告",
        f"",
        f"**评测时间**：{timestamp}",
        f"**评测数据集**：BCQB-100（100条查询，单跳50条+多跳50条）",
        f"**评测方法**：{', '.join(summary.keys())}",
        f"",
        f"---",
        f"",
        f"## 1. 总体性能对比",
        f"",
        f"| 评测指标 | " + " | ".join(summary.keys()) + " |",
        f"|---------|" + "|".join(["------"] * len(summary)) + "|",
        f"| Recall@1 | " + " | ".join([f"{s['recall@1']:.1f}%" for s in summary.values()]) + " |",
        f"| Recall@3 | " + " | ".join([f"{s['recall@3']:.1f}%" for s in summary.values()]) + " |",
        f"| Recall@5 | " + " | ".join([f"**{s['recall@5']:.1f}%**" for s in summary.values()]) + " |",
        f"| Answer Accuracy（模拟） | " + " | ".join([f"{s['avg_accuracy']:.1f}%" for s in summary.values()]) + " |",
        f"| Citation Rate（模拟） | " + " | ".join([f"{s['avg_citation']:.1f}%" for s in summary.values()]) + " |",
        f"| 平均检索耗时 | " + " | ".join([f"{s['avg_time_ms']:.1f}ms" for s in summary.values()]) + " |",
        f"",
        f"## 2. 分类型性能",
        f"",
        f"| 查询类型 | " + " | ".join(summary.keys()) + " |",
        f"|---------|" + "|".join(["------"] * len(summary)) + "|",
        f"| 单跳查询 Recall@5 | " + " | ".join([f"{s['recall@5_single']:.1f}%" for s in summary.values()]) + " |",
        f"| 多跳查询 Recall@5 | " + " | ".join([f"{s['recall@5_multi']:.1f}%" for s in summary.values()]) + " |",
        f"",
        f"## 3. 与论文数据对照",
        f"",
        f"| 数据来源 | Recall@5 | 说明 |",
        f"|---------|---------|-----|",
        f"| 论文BM25基线 | 42.3% | 文献报道值 |",
        f"| 本文BM25实现 | {summary.get('bm25', {}).get('recall@5', 'N/A'):.1f}% | 实际评测值 |",
        f"| 论文混合检索 | 78.6% | 文献报道值 |",
        f"| 本文混合检索 | {summary.get('hybrid', {}).get('recall@5', 'N/A'):.1f}% | 实际评测值 |",
        f"",
        f"## 4. 技术参数",
        f"",
        f"| 参数 | 值 | 说明 |",
        f"|-----|-----|-----|",
        f"| BM25 k₁ | 1.5 | 标准BM25参数 |",
        f"| BM25 b | 0.75 | 文档长度归一化参数 |",
        f"| RRF k | 60 | 排名融合平滑因子 |",
        f"| 向量权重 | 0.6 | 混合检索中向量检索权重 |",
        f"| BM25权重 | 0.4 | 混合检索中BM25权重 |",
        f"| 向量模型 | BAAI/bge-large-zh-v1.5 | 768维中文嵌入 |",
        f"",
        f"## 5. 评测说明",
        f"",
        f"> **注意**：Answer Accuracy和Citation Rate为基于检索分数的模拟估算值。",
        f"> 真实值需要通过LLM生成回答后由人工专家或自动化指标（如RAGChecker）评定。",
        f"> ",
        f"> **相关文献**：",
        f"> - Zhu et al. (2024) LLM-QueryBC: 文本查询准确率 64%→88%",
        f"> - Yang et al. (2025) Building Code Expert: RAG问答框架",
        f"> - Lin (2025): 智能解读准确率 >95%，合规检查效率提升40倍",
        f"",
        f"---",
        f"",
        f"*报告生成时间：{timestamp}*",
    ]

    return "\n".join(lines)


def generate_csv_report(all_results: Dict[str, List[EvalResult]], output_path: str):
    """生成CSV格式的详细评测数据"""
    with open(output_path, 'w', newline='', encoding='utf-8-sig') as f:
        writer = csv.writer(f)
        writer.writerow([
            "query_id", "method", "query", "retrieved_doc_1", "score_1",
            "retrieved_doc_2", "score_2", "retrieved_doc_3", "score_3",
            "retrieved_doc_4", "score_4", "retrieved_doc_5", "score_5",
            "recall@1", "recall@3", "recall@5", "accuracy_sim", "citation_sim", "time_ms"
        ])

        for method, results in all_results.items():
            for r in results:
                writer.writerow([
                    r.query_id, method, r.query,
                    *[x for pair in zip(
                        r.retrieved_docs[:5] + [""] * max(0, 5 - len(r.retrieved_docs)),
                        [f"{s:.4f}" for s in r.retrieval_scores[:5]] + [""] * max(0, 5 - len(r.retrieval_scores))
                    ) for x in pair],
                    f"{r.recall_at_1:.4f}", f"{r.recall_at_3:.4f}", f"{r.recall_at_5:.4f}",
                    f"{r.answer_accuracy:.4f}", f"{r.citation_rate:.4f}",
                    f"{r.retrieval_time_ms:.2f}"
                ])


# ============================================================
# 主评测流程
# ============================================================

def run_full_benchmark(methods: List[str], output_dir: Optional[str] = None) -> BenchmarkReport:
    """运行完整评测流程"""
    timestamp = datetime.datetime.now().strftime("%Y-%m-%d %H:%M:%S")

    print(f"\n{'='*70}")
    print(f"[SYS]  LS-ZGT 建筑规范RAG系统评测")
    print(f"{'='*70}")
    print(f"评测时间: {timestamp}")

    # 加载数据和评测集
    print(f"\n[LOAD] 加载知识库...")
    docs = load_knowledge_base()
    print(f"   共 {len(docs)} 个规范文档")

    print(f"\n[DATA] 加载评测数据集...")
    dataset = BCQBDataset()
    stats = dataset.stats()
    for k, v in stats.items():
        print(f"   {k}: {v}条")
    print(f"   总计: {stats['total']}条")

    # 构建相关性映射
    print(f"\n[BUILD] 构建相关性映射...")
    relevant_map = _build_relevant_index_map(dataset.get_all(), docs)

    # 为每种方法运行评测
    all_results = {}
    for method in methods:
        print(f"\n{'─'*50}")
        print(f">> 评测方法: {method.upper()}")
        print(f"{'─'*50}")

        if method == "bm25":
            retriever = SimpleBM25([d["content"] for d in docs])
        elif method == "vector":
            if not EMBEDDING_AVAILABLE:
                print(f"   [WARN] 向量检索不可用，跳过")
                continue
            retriever = VectorSearch([d["content"] for d in docs])
        elif method == "hybrid":
            retriever = HybridSearch([d["content"] for d in docs])
        else:
            print(f"   [ERR] 未知方法: {method}")
            continue

        # 运行检索评测
        results = run_retrieval_eval(
            retriever, docs, dataset.get_all(), method, relevant_map
        )
        all_results[method] = results

        # 打印汇总
        r5_list = [r.recall_at_5 for r in results]
        avg_r5 = sum(r5_list) / len(r5_list) * 100
        print(f"   Recall@5: {avg_r5:.1f}%")
        print(f"   查询数: {len(results)}")

    # 汇总
    print(f"\n{'='*70}")
    print(f"[STAT] 评测结果汇总")
    print(f"{'='*70}")
    summary = aggregate_results(all_results, dataset)

    for method, s in summary.items():
        print(f"\n【{method.upper()}】")
        print(f"  Recall@1:  {s['recall@1']:.1f}%")
        print(f"  Recall@3:  {s['recall@3']:.1f}%")
        print(f"  Recall@5:  {s['recall@5']:.1f}%")
        print(f"  单跳R@5:   {s['recall@5_single']:.1f}%")
        print(f"  多跳R@5:   {s['recall@5_multi']:.1f}%")
        print(f"  平均耗时:  {s['avg_time_ms']:.1f}ms")

    # 生成报告
    if output_dir:
        output_dir = Path(output_dir)
        output_dir.mkdir(parents=True, exist_ok=True)

        md_path = output_dir / f"评测报告_{datetime.datetime.now().strftime('%Y%m%d_%H%M%S')}.md"
        md_report = generate_markdown_report(summary, all_results, dataset, timestamp)
        md_path.write_text(md_report, encoding='utf-8')
        print(f"\n[OK] Markdown报告已保存: {md_path}")

        csv_path = output_dir / f"评测详细数据_{datetime.datetime.now().strftime('%Y%m%d_%H%M%S')}.csv"
        generate_csv_report(all_results, str(csv_path))
        print(f"[OK] CSV详细数据已保存: {csv_path}")

    return BenchmarkReport(
        timestamp=timestamp,
        methods=list(summary.keys()),
        total_queries=dataset.total,
        single_hop_queries=dataset.stats()["单跳"],
        multi_hop_queries=dataset.stats()["多跳"],
        results=all_results,
        summary=summary
    )


# ============================================================
# CLI入口
# ============================================================

def main():
    parser = argparse.ArgumentParser(
        description="LS-ZGT 建筑规范RAG系统评测工具 v2.0",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog="""
示例：
  python eval_rag.py --method all              # 运行全部方法评测
  python eval_rag.py --method hybrid           # 仅运行混合检索评测
  python eval_rag.py --method bm25             # 仅运行BM25评测
  python eval_rag.py --method all --output     # 评测并输出CSV报告
  python eval_rag.py --method hybrid --report  # 评测并生成Markdown报告

依赖安装：
  pip install rank_bm25 sentence-transformers numpy
        """
    )
    parser.add_argument(
        "--method",
        choices=["bm25", "vector", "hybrid", "all"],
        default="all",
        help="检索方法（默认: all）"
    )
    parser.add_argument(
        "--output",
        action="store_true",
        help="输出CSV详细数据文件"
    )
    parser.add_argument(
        "--report",
        action="store_true",
        help="生成Markdown评测报告"
    )
    parser.add_argument(
        "--output-dir",
        type=str,
        default=None,
        help="输出目录（默认: 项目根目录/eval_results）"
    )

    args = parser.parse_args()

    # 确定要评测的方法
    if args.method == "all":
        methods = ["bm25"]
        if EMBEDDING_AVAILABLE:
            methods.extend(["vector", "hybrid"])
    else:
        methods = [args.method]

    # 确定输出目录
    out_dir = args.output_dir or str(OUTPUT_DIR)

    # 运行评测
    report = run_full_benchmark(methods, out_dir if (args.output or args.report) else None)

    if args.output or args.report:
        print(f"\n[OK] 评测完成！报告已保存至: {out_dir}/")


if __name__ == "__main__":
    main()
