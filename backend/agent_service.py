#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
建筑规范查询Agent模块
Building Code Query Agent Module

基于ReAct范式的规范智能查询系统
支持多步推理、工具调用、降级策略

使用方法：
    from agent_service import CodeQueryAgent
    agent = CodeQueryAgent(knowledge_base_path)
    result = await agent.query("地下车库的防火分区面积是多少？")

项目路径：D:\pro_sunner\demo_vscode\LS-ZGT
"""

import os
import re
import json
import asyncio
from typing import List, Dict, Tuple, Optional, Any
from dataclasses import dataclass, field
from enum import Enum
from pathlib import Path
import logging

# 配置日志
logging.basicConfig(level=logging.INFO)
logger = logging.getLogger(__name__)


class ThoughtStep:
    """ReAct推理步骤"""

    def __init__(self, step_num: int, thought: str, action: Optional[str] = None,
                 action_input: Optional[Dict] = None, observation: Optional[str] = None,
                 is_final: bool = False, final_answer: Optional[str] = None):
        self.step_num = step_num
        self.thought = thought
        self.action = action
        self.action_input = action_input or {}
        self.observation = observation
        self.is_final = is_final
        self.final_answer = final_answer

    def to_dict(self) -> Dict:
        return {
            "step": self.step_num,
            "thought": self.thought,
            "action": self.action,
            "action_input": self.action_input,
            "observation": self.observation,
            "is_final": self.is_final,
            "final_answer": self.final_answer
        }


class AgentTool:
    """Agent工具基类"""

    def __init__(self, name: str, description: str):
        self.name = name
        self.description = description

    async def execute(self, **kwargs) -> str:
        """执行工具，返回结果字符串"""
        raise NotImplementedError

    def get_schema(self) -> Dict:
        """获取工具的输入Schema"""
        return {
            "name": self.name,
            "description": self.description,
            "parameters": {}
        }


class KnowledgeSearchTool(AgentTool):
    """知识库搜索工具"""

    def __init__(self, retriever, top_k: int = 5):
        super().__init__(
            name="knowledge_search",
            description="搜索知识库获取相关建筑规范文档。输入查询词，返回最相关的规范条文。"
        )
        self.retriever = retriever
        self.top_k = top_k

    async def execute(self, query: str, top_k: int = None) -> str:
        """执行知识库搜索"""
        k = top_k or self.top_k
        try:
            results = self.retriever.search(query, top_k=k)
            if not results:
                return "未找到相关结果"

            output_parts = [f"找到 {len(results)} 条相关结果：\n"]
            for i, (doc_idx, score) in enumerate(results, 1):
                doc = self.retriever.documents[doc_idx]
                content_preview = doc.get("content", "")[:500]
                output_parts.append(
                    f"【结果{i}】(相关度: {score:.3f})\n"
                    f"来源: {doc.get('id', 'unknown')}\n"
                    f"内容预览: {content_preview}...\n"
                )
            return "\n".join(output_parts)
        except Exception as e:
            logger.error(f"知识库搜索失败: {e}")
            return f"搜索出错: {str(e)}"


class CalculatorTool(AgentTool):
    """计算工具"""

    def __init__(self):
        super().__init__(
            name="calculator",
            description="执行简单的数学计算。用于计算防火分区面积、疏散人数等数值。"
        )

    async def execute(self, expression: str) -> str:
        """执行计算"""
        try:
            # 安全评估：只允许数字和基本运算符
            if not re.match(r'^[\d\s\+\-\*\/\.\(\)]+$', expression):
                return "表达式包含非法字符"

            result = eval(expression)
            return f"计算结果: {expression} = {result}"
        except Exception as e:
            return f"计算错误: {str(e)}"


class NormLookupTool(AgentTool):
    """规范条文查阅工具"""

    def __init__(self, knowledge_base_path: Path):
        super().__init__(
            name="norm_lookup",
            description="直接查阅特定规范的条文。输入规范编号和条文号，如 'GB 50016 5.3.1'。"
        )
        self.knowledge_base_path = knowledge_base_path
        self._norm_index = None

    def _build_index(self) -> Dict:
        """构建规范索引"""
        if self._norm_index is not None:
            return self._norm_index

        self._norm_index = {}
        for md_file in self.knowledge_base_path.glob("**/*.md"):
            try:
                content = md_file.read_text(encoding='utf-8')
                # 提取章节标题
                chapters = re.findall(r'^#+\s+(.+)$', content, re.MULTILINE)
                self._norm_index[md_file.stem] = {
                    "path": str(md_file),
                    "chapters": chapters,
                    "content": content
                }
            except Exception as e:
                logger.warning(f"无法读取 {md_file}: {e}")

        return self._norm_index

    async def execute(self, norm_code: str, article: str = None) -> str:
        """查阅规范条文"""
        self._build_index()

        # 搜索匹配的规范
        for name, info in self._norm_index.items():
            if norm_code.lower() in name.lower():
                if article:
                    # 搜索特定条文
                    pattern = rf'{article}[^.]\d*'
                    matches = re.findall(
                        rf'{pattern}.*?(?=\n\d+\.\d+|\n#|\Z)',
                        info["content"],
                        re.DOTALL
                    )
                    if matches:
                        return f"【{name}】第{article}条：\n{matches[0][:1000]}"
                return f"【{name}】找到规范，包含{len(info['chapters'])}个章节"

        return f"未找到规范: {norm_code}"


class FallbackSearchTool(AgentTool):
    """降级搜索工具（纯关键词）"""

    def __init__(self, documents: List[Dict]):
        super().__init__(
            name="fallback_search",
            description="当主检索失败时使用的降级搜索，使用简单关键词匹配。"
        )
        self.documents = documents

    async def execute(self, query: str, top_k: int = 5) -> str:
        """关键词匹配搜索"""
        query_lower = query.lower()
        query_words = set(query_lower.split())

        scored_docs = []
        for doc in self.documents:
            content_lower = doc.get("content", "").lower()
            # 计算关键词匹配数
            matches = sum(1 for word in query_words if word in content_lower)
            if matches > 0:
                scored_docs.append((doc, matches))

        scored_docs.sort(key=lambda x: x[1], reverse=True)

        if not scored_docs:
            return "降级搜索也未找到结果"

        results = scored_docs[:top_k]
        output_parts = [f"降级搜索找到 {len(results)} 条结果：\n"]
        for doc, score in results:
            output_parts.append(
                f"【{doc.get('id', 'unknown')}】(匹配数: {score})\n"
                f"{doc.get('content', '')[:300]}...\n"
            )
        return "\n".join(output_parts)


@dataclass
class AgentConfig:
    """Agent配置"""
    max_iterations: int = 10
    temperature: float = 0.7
    model_name: str = "gpt-4"
    enable_fallback: bool = True
    fallback_threshold: float = 0.3


class CodeQueryAgent:
    """
    建筑规范查询Agent

    基于ReAct范式实现的多步推理智能体
    支持多种工具调用：知识搜索、计算、规范查阅
    """

    def __init__(
        self,
        knowledge_base_path: Optional[Path] = None,
        retriever=None,
        llm_client=None,
        config: AgentConfig = None
    ):
        """
        初始化Agent

        Args:
            knowledge_base_path: 知识库路径
            retriever: 检索器（支持search方法）
            llm_client: LLM客户端
            config: Agent配置
        """
        self.config = config or AgentConfig()

        if knowledge_base_path is None:
            knowledge_base_path = Path(r"D:\pro_sunner\demo_vscode\LS-ZGT\backend\knowledge_base\建筑")

        self.knowledge_base_path = knowledge_base_path
        self.documents = self._load_documents()

        # 初始化工具
        self.tools = {}
        self._register_tools(retriever)

        # LLM客户端（可配置）
        self.llm_client = llm_client or self._default_llm_call

        # 推理历史
        self.history: List[ThoughtStep] = []

    def _load_documents(self) -> List[Dict]:
        """加载文档"""
        docs = []
        if self.knowledge_base_path.exists():
            for md_file in self.knowledge_base_path.glob("*.md"):
                try:
                    content = md_file.read_text(encoding='utf-8')
                    docs.append({
                        "id": md_file.stem,
                        "content": content,
                        "source": "knowledge_base"
                    })
                except Exception as e:
                    logger.warning(f"无法读取 {md_file}: {e}")
        return docs

    def _register_tools(self, retriever):
        """注册工具"""
        # 知识搜索工具
        if retriever:
            self.tools["knowledge_search"] = KnowledgeSearchTool(retriever)
        elif self.documents:
            # 如果没有提供检索器，使用简单检索
            from eval_rag import SimpleBM25
            bm25 = SimpleBM25([d["content"] for d in self.documents])
            bm25.documents = self.documents
            self.tools["knowledge_search"] = KnowledgeSearchTool(bm25)

        # 计算工具
        self.tools["calculator"] = CalculatorTool()

        # 规范查阅工具
        self.tools["norm_lookup"] = NormLookupTool(self.knowledge_base_path)

        # 降级搜索工具
        self.tools["fallback_search"] = FallbackSearchTool(self.documents)

    def _default_llm_call(self, prompt: str) -> str:
        """
        默认的LLM调用（需要配置实际的API）
        这里返回示例响应
        """
        # 实际使用时替换为真实的LLM调用
        return """Thought: 我需要先搜索相关的建筑规范条文
Action: knowledge_search:{"query": "防火分区面积", "top_k": 5}
Observation: 找到5条相关结果，GB 50016-2014第5.3.1条包含相关信息

Thought: 现在我需要从搜索结果中提取具体数值
Action: norm_lookup:{"norm_code": "GB 50016", "article": "5.3.1"}
Observation: 【GB 50016-2014 建筑地基基础设计规范】包含章节

Final Answer: 根据GB 50016-2014《建筑设计防火规范》第5.3.1条，地下车库的防火分区最大允许面积为...（需要根据实际规范内容补充）"""

    def _build_tools_description(self) -> str:
        """构建工具描述"""
        desc_parts = ["你可以使用以下工具：\n"]
        for name, tool in self.tools.items():
            desc_parts.append(f"- {name}: {tool.description}")
        return "\n".join(desc_parts)

    def _build_history_text(self) -> str:
        """构建历史记录文本"""
        if not self.history:
            return "（暂无历史记录）"

        parts = []
        for step in self.history:
            parts.append(f"第{step.step_num}步：")
            parts.append(f"思考: {step.thought}")
            if step.action:
                parts.append(f"动作: {step.action}")
            if step.observation:
                parts.append(f"观察: {step.observation[:200]}...")
            parts.append("")
        return "\n".join(parts)

    def _parse_llm_response(self, response: str) -> ThoughtStep:
        """解析LLM响应"""
        thought = ""
        action = None
        action_input = {}
        observation = None
        is_final = False
        final_answer = None

        # 提取Thought
        thought_match = re.search(r'Thought:\s*(.+?)(?=\nAction:|\nFinal Answer:|$)', response, re.DOTALL)
        if thought_match:
            thought = thought_match.group(1).strip()

        # 提取Action
        action_match = re.search(
            r'Action:\s*(\w+)(?:\[(.*?)\])?(?:\{(.*?)\})?',
            response,
            re.DOTALL
        )
        if action_match:
            action = action_match.group(1)
            # 解析参数
            params_str = action_match.group(2) or action_match.group(3) or "{}"
            try:
                action_input = json.loads(params_str)
            except:
                # 简单解析 key=value 格式
                action_input = {}
                for pair in params_str.split(","):
                    if "=" in pair:
                        k, v = pair.split("=", 1)
                        action_input[k.strip()] = v.strip().strip('"\'')

        # 提取Final Answer
        final_match = re.search(r'Final Answer:\s*(.+?)$', response, re.DOTALL)
        if final_match:
            is_final = True
            final_answer = final_match.group(1).strip()

        # 如果有Observation但LLM没有返回，从响应中尝试提取
        if not observation:
            obs_match = re.search(r'Observation:\s*(.+?)(?=\nThought:|\Z)', response, re.DOTALL)
            if obs_match:
                observation = obs_match.group(1).strip()

        step_num = len(self.history) + 1
        return ThoughtStep(
            step_num=step_num,
            thought=thought,
            action=action,
            action_input=action_input,
            observation=observation,
            is_final=is_final,
            final_answer=final_answer
        )

    async def _execute_tool(self, tool_name: str, **kwargs) -> str:
        """执行工具"""
        if tool_name not in self.tools:
            return f"未知工具: {tool_name}"

        try:
            tool = self.tools[tool_name]
            result = await tool.execute(**kwargs)
            return result
        except Exception as e:
            logger.error(f"工具执行失败: {tool_name}, 错误: {e}")
            return f"工具执行出错: {str(e)}"

    def _should_fallback(self, step: ThoughtStep) -> bool:
        """判断是否需要降级"""
        if not self.config.enable_fallback:
            return False

        # 如果知识搜索连续失败，触发降级
        if step.action == "knowledge_search" and not step.observation:
            return True

        # 如果连续N步没有进展
        recent_steps = self.history[-3:]
        if len(recent_steps) >= 3:
            no_progress = all(
                s.observation and len(s.observation) < 50
                for s in recent_steps
            )
            if no_progress:
                return True

        return False

    async def query(self, user_query: str) -> Dict[str, Any]:
        """
        执行查询

        Args:
            user_query: 用户的问题

        Returns:
            {
                "answer": str,  # 最终答案
                "steps": List[ThoughtStep],  # 推理步骤
                "success": bool,  # 是否成功
                "method": str,  # 使用的方法
            }
        """
        self.history = []
        current_prompt = self._build_prompt(user_query)
        fallback_triggered = False

        for iteration in range(self.config.max_iterations):
            # 调用LLM
            llm_response = await self._llm_call_async(current_prompt)

            # 解析响应
            step = self._parse_llm_response(llm_response)
            step.iteration = iteration

            # 检查是否需要降级
            if self._should_fallback(step) and not fallback_triggered:
                logger.info("触发降级策略")
                fallback_triggered = True
                fallback_result = await self.tools["fallback_search"].execute(
                    user_query, top_k=5
                )
                step.observation = f"[降级搜索] {fallback_result}"
                self.history.append(step)
                continue

            # 如果是最终答案
            if step.is_final:
                self.history.append(step)
                return {
                    "answer": step.final_answer,
                    "steps": [s.to_dict() for s in self.history],
                    "success": True,
                    "method": "fallback" if fallback_triggered else "agent"
                }

            # 执行工具
            if step.action and step.action in self.tools:
                tool_result = await self._execute_tool(
                    step.action, **step.action_input
                )
                step.observation = tool_result

            self.history.append(step)

            # 更新Prompt（添加新的思考历史）
            current_prompt = self._build_prompt(user_query, include_history=True)

        # 达到最大迭代次数
        return {
            "answer": "抱歉，经过多次尝试未能给出明确答案。建议您查阅相关规范原文或缩小查询范围。",
            "steps": [s.to_dict() for s in self.history],
            "success": False,
            "method": "timeout"
        }

    def _build_prompt(self, user_query: str, include_history: bool = False) -> str:
        """构建Prompt"""
        tools_desc = self._build_tools_description()
        history_text = self._build_history_text() if include_history else "（暂无历史记录）"

        prompt = f"""你是一个专业的建筑规范智能助手。你需要帮助用户查询建筑规范相关的问题。

请按照以下格式思考和回答：
Thought: 你的思考过程，分析需要查询什么
Action: 工具名称[参数]（如果需要查询的话）
Observation: 工具返回的结果
... (可以重复多次)
Final Answer: 最终答案（如果你已经有足够的信息）

{tools_desc}

用户问题: {user_query}

历史记录:
{history_text}

请开始推理："""
        return prompt

    async def _llm_call_async(self, prompt: str) -> str:
        """异步调用LLM"""
        # 实际使用时，这里应该调用真实的LLM API
        # 这里使用模拟实现
        loop = asyncio.get_event_loop()
        return await loop.run_in_executor(None, self._default_llm_call, prompt)


class StreamingCodeQueryAgent(CodeQueryAgent):
    """支持流式输出的Agent"""

    async def query_stream(self, user_query: str):
        """流式查询"""
        self.history = []

        for iteration in range(self.config.max_iterations):
            prompt = self._build_prompt(user_query, include_history=True)

            # 模拟流式输出
            async for token in self._stream_llm_response(prompt):
                yield token

            # 解析并执行
            llm_response = await self._llm_call_async(prompt)
            step = self._parse_llm_response(llm_response)

            if step.is_final:
                self.history.append(step)
                yield {"type": "final", "content": step.final_answer}
                break

            # 执行工具
            if step.action and step.action in self.tools:
                tool_result = await self._execute_tool(
                    step.action, **step.action_input
                )
                step.observation = tool_result

            self.history.append(step)

    async def _stream_llm_response(self, prompt: str):
        """模拟流式LLM响应"""
        # 实际使用时使用真实的流式API
        words = ["思考中", "...", "搜索中", "...", "完成"]
        for word in words:
            yield {"type": "token", "content": word}
            await asyncio.sleep(0.1)


async def demo():
    """演示"""
    print("=" * 60)
    print("建筑规范查询Agent演示")
    print("=" * 60)

    # 初始化Agent
    agent = CodeQueryAgent()

    # 测试查询
    queries = [
        "一类高层民用建筑的耐火等级是多少？",
        "地下车库的防火分区最大允许面积是多少？",
        "疏散楼梯的最小净宽度不应小于多少米？"
    ]

    for query in queries:
        print(f"\n查询: {query}")
        print("-" * 40)

        result = await agent.query(query)

        print(f"答案: {result['answer']}")
        print(f"推理步骤数: {len(result['steps'])}")
        print(f"方法: {result['method']}")


if __name__ == "__main__":
    asyncio.run(demo())