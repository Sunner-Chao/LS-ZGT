"""
Docling Service - IBM Docling 文档解析微服务
============================================

基于 IBM 开源的 Docling 工具包，提供企业级文档解析能力。

核心优势：
- 高精度表格识别（准确率高达 97.9%）
- 原生数字文档（PDF/Word/PPT/Excel/HTML/Markdown）结构化解析
- 输出的 LLM-Ready 数据（JSON / Markdown / HTML）
- 支持 LangChain / LlamaIndex 集成
- 纯 CPU 运行，不强制 GPU，硬件门槛低

对比 VLM：
- Docling 擅长：原生数字文档、精准表格
- VLM 擅长：扫描件、手写体、印章、复杂图表
- 两者协同：智能路由 + Docling 主力 + VLM 补盲

启动方式：
    python docling_service.py
    或在 Docker 中运行（参见 Dockerfile.docling）
"""

from __future__ import annotations

import io
import logging
import os
import traceback
from datetime import datetime
from typing import Any, Optional

from flask import Flask, jsonify, request

# Docling Core（文档解析引擎，延迟导入避免启动失败）
DOCLING_AVAILABLE = False
_docling_core = None


def _import_docling():
    global DOCLING_AVAILABLE, _docling_core
    if _docling_core is not None:
        return _docling_core

    try:
        from docling.datamodel.base_models import InputFormat
        from docling.datamodel.document import DocumentStream
        from docling.document_converter import (
            DocumentConverter,
            FormatOption,
            PdfFormatOption,
        )
        from docling.backend.pypdfium2_backend import PyPdfiumDocumentBackend

        _docling_core = {
            "InputFormat": InputFormat,
            "DocumentStream": DocumentStream,
            "DocumentConverter": DocumentConverter,
            "FormatOption": FormatOption,
            "PdfFormatOption": PdfFormatOption,
            "PdfBackend": PyPdfiumDocumentBackend,
        }
        DOCLING_AVAILABLE = True
        log.info("Docling 模块导入成功（版本: docling 2.x）")
        return _docling_core
    except ImportError as e:
        log.warning("Docling 未安装，请运行: pip install 'docling[all]' | 错误: %s", e)
        DOCLING_AVAILABLE = False
        return None
    except Exception as e:
        log.warning("Docling 导入异常: %s", e)
        DOCLING_AVAILABLE = False
        return None


app = Flask(__name__)
logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s | %(levelname)-8s | %(name)s | %(message)s",
)
log = logging.getLogger("docling-service")


# ==================== 全局配置 ====================

# Docling 是否启用（设为 False 时快速降级）
DOCLING_ENABLED = os.getenv("DOCLING_ENABLED", "true").lower() == "true"

# 输出格式：markdown（推荐，LLM友好）/ html / text
DEFAULT_OUTPUT_FORMAT = os.getenv("DOCLING_OUTPUT_FORMAT", "markdown")

# 最大文件大小（MB）
MAX_FILE_SIZE_MB = int(os.getenv("DOCLING_MAX_FILE_SIZE_MB", "100"))

# Docling 文档转换器（全局单例，按需初始化）
_converter: Optional[Any] = None


def get_converter() -> Optional[Any]:
    """延迟初始化 Docling DocumentConverter（首次调用时创建）"""
    global _converter
    if _converter is not None:
        return _converter

    if not DOCLING_ENABLED:
        log.warning("Docling 已被配置禁用")
        return None

    dl = _import_docling()
    if dl is None:
        return None

    try:
        log.info("初始化 Docling DocumentConverter ...")

        from docling.datamodel.pipeline_options import PdfPipelineOptions

        pipeline_opts = PdfPipelineOptions(
            do_ocr=True,
            do_table_structure=True,
        )
        log.info("Docling 初始化：智能模式（do_ocr=True，由解析器自动判断引擎）")

        # 配置 PDF 格式选项
        pdf_option = dl["PdfFormatOption"](
            pipeline_options=pipeline_opts,
        )

        format_options = {
            dl["InputFormat"].PDF: pdf_option,
        }

        _converter = dl["DocumentConverter"](
            format_options=format_options,
        )
        log.info("Docling DocumentConverter 初始化完成")
        return _converter
    except Exception as e:
        log.error("Docling 初始化失败: %s", e)
        import traceback as tb
        log.error(tb.format_exc())
        return None


# ==================== 路由 ====================

@app.route("/health", methods=["GET"])
def health():
    # 触发延迟导入检查
    _import_docling()
    status = {
        "status": "ok",
        "docling_available": DOCLING_AVAILABLE,
        "docling_enabled": DOCLING_ENABLED,
        "converter_ready": get_converter() is not None,
        "output_format": DEFAULT_OUTPUT_FORMAT,
        "native_mode": os.getenv("DOCLING_NATIVE_MODE", "true").lower() == "true",
    }
    return jsonify(status)


@app.route("/api/parse", methods=["POST"])
@app.route("/parse", methods=["POST"])
def parse_document():
    """
    文档解析主接口

    支持格式：PDF, DOCX, PPTX, XLSX, HTML, Markdown, 图片 (JPG/PNG)

    请求参数（multipart/form-data）：
        file: 文件（必填）
        output_format: 输出格式（可选，markdown/html/text，默认 markdown）
        ocr: 是否启用 OCR（可选，true/false，默认 true）

    返回：
        {
            "text": "...",           # 解析后的文本内容
            "pages": <int>,         # 页数
            "format": "...",         # 实际使用的输出格式
            "tables": <int>,        # 检测到的表格数量
            "filename": "...",      # 原始文件名
        }
    """
    if not DOCLING_ENABLED:
        return jsonify({"error": "Docling service is disabled"}), 503

    upload = request.files.get("file")
    if upload is None:
        return jsonify({"error": "missing required field: file"}), 400

    output_format = request.form.get("output_format", DEFAULT_OUTPUT_FORMAT).lower()
    enable_ocr = request.form.get("ocr", str(DOCLING_ENABLED).lower()).lower() == "true"

    try:
        file_bytes = upload.read()
        file_size_mb = len(file_bytes) / (1024 * 1024)

        if file_size_mb > MAX_FILE_SIZE_MB:
            return jsonify({
                "error": f"File too large: {file_size_mb:.1f}MB (max {MAX_FILE_SIZE_MB}MB)"
            }), 413

        if not file_bytes:
            return jsonify({"error": "empty file"}), 400

        filename = upload.filename or "unknown"

        # 根据文件扩展名判断格式
        file_ext = filename.rsplit(".", 1)[-1].lower() if "." in filename else ""

        result = process_document(
            file_bytes=file_bytes,
            file_format=file_ext,
            filename=filename,
            output_format=output_format,
            enable_ocr=enable_ocr,
        )

        return jsonify(result)

    except Exception as e:
        log.error("解析失败 [%s]: %s\n%s", filename, e, traceback.format_exc())
        return jsonify({"error": f"parse failed: {e}"}), 500


def process_document(
    file_bytes: bytes,
    file_format: str,
    filename: str,
    output_format: str = "markdown",
    enable_ocr: bool = True,
) -> dict[str, Any]:
    """
    核心解析逻辑：根据文件格式路由到对应解析器
    """
    log.info("开始解析: filename=%s format=%s size=%d bytes OCR=%s",
             filename, file_format, len(file_bytes), enable_ocr)

    # 图片文件 → VLM OCR（不在 Docling 流程中，此处跳过）
    if file_format in ("jpg", "jpeg", "png", "gif", "bmp", "tiff", "webp"):
        return {
            "error": f"图片格式 ({file_format}) 请使用 VLM OCR 服务处理",
            "hint": "POST to /api/vlm-ocr for image files",
            "text": "",
            "pages": 1,
        }

    # 通用文档 → Docling
    return parse_with_docling(
        file_bytes=file_bytes,
        file_format=file_format,
        filename=filename,
        output_format=output_format,
        enable_ocr=enable_ocr,
    )


def _detect_pdf_type_quick(file_bytes: bytes) -> bool:
    """
    快速检测 PDF 类型：

    返回 True  → 数字 PDF（文本层有效），走文本提取（快速）
    返回 False → 扫描 PDF（无/无效文本层），走 OCR（慢但准确）

    原理：用 pypdfium2 提取首页文字数。
    数字 PDF 有大量文字（>100 字符），扫描 PDF 几乎没有文字。
    """
    try:
        import pypdfium2 as pdfium
    except Exception:
        log.warn("PDF 类型检测：pypdfium2 未安装，默认走文本提取")
        return True  # 无法检测，默认文本提取

    try:
        pdf = pdfium.PdfDocument(file_bytes)
        if len(pdf) == 0:
            log.warn("PDF 类型检测：页数为 0，切换 OCR")
            return False

        page = pdf[0]
        textpage = page.get_textpage()
        char_count = textpage.count_chars()

        log.info("PDF 类型检测：首页文字数=%d", char_count)

        if char_count < 100:
            # 文字太少（<100），判定为扫描版
            log.info("PDF 类型检测：文字稀疏（%d < 100），切换 OCR", char_count)
            return False

        # 文字量足够，判定为数字 PDF
        log.info("PDF 类型检测：数字 PDF（首页文字数=%d）", char_count)
        return True

    except Exception as e:
        log.warn("PDF 类型检测失败，默认走文本提取: %s", e)
        return True


def parse_with_docling(
    file_bytes: bytes,
    file_format: str,
    filename: str,
    output_format: str,
    enable_ocr: bool = True,
) -> dict[str, Any]:
    """
    使用 Docling 解析文档，智能选择引擎：
    - 数字 PDF（文本层有效）→ 文本提取（快速）
    - 扫描 PDF（无/无效文本层）→ OCR（慢但准确）
    """
    dl = _import_docling()
    if dl is None:
        return {
            "error": "Docling 未安装或初始化失败",
            "text": "",
            "pages": 0,
        }

    from docling.datamodel.pipeline_options import PdfPipelineOptions

    # 对 PDF 做快速类型检测（仅当 OCR 全局启用时）
    use_text_extraction = False
    if file_format == "pdf" and enable_ocr:
        use_text_extraction = _detect_pdf_type_quick(file_bytes)

    if use_text_extraction:
        # 数字 PDF → 极速文本提取 + 表格结构提取
        pipeline_opts = PdfPipelineOptions(
            do_ocr=False,
            force_backend_text=True,
            do_table_structure=True,  # 启用表格结构提取
        )
        log.info("PDF 类型：数字 PDF，文本提取模式（含表格结构）")
    else:
        # 扫描 PDF / 乱码 PDF → OCR 模式
        pipeline_opts = PdfPipelineOptions(
            do_ocr=True,
            do_table_structure=True,
        )
        log.info("PDF 类型：扫描版，OCR 模式")

    pdf_option = dl["PdfFormatOption"](pipeline_options=pipeline_opts)
    converter = dl["DocumentConverter"](
        format_options={dl["InputFormat"].PDF: pdf_option},
    )

    try:
        input_stream = dl["DocumentStream"](
            name=filename,
            stream=io.BytesIO(file_bytes),
        )

        start = datetime.now()
        result = converter.convert(input_stream, raises_on_error=True)
        dl_doc = result.document
        elapsed = (datetime.now() - start).total_seconds()
        log.info("Docling 转换完成: %s (%.2fs)", filename, elapsed)

        if output_format == "markdown":
            text = _export_markdown_with_page_markers(dl_doc)
        elif output_format == "html":
            text = _export_html_with_page_markers(dl_doc)
        elif output_format == "text":
            text = _export_text_with_page_markers(dl_doc)
        else:
            text = _export_markdown_with_page_markers(dl_doc)

        table_count = text.count("\n|") if text else 0

        result_out = {
            "text": text,
            "pages": len(dl_doc.pages) if hasattr(dl_doc, "pages") else 1,
            "format": output_format,
            "tables": table_count,
            "filename": filename,
            "elapsed_seconds": round(elapsed, 2),
            "parse_mode": "text" if use_text_extraction else "ocr",
        }

        log.info("解析成功: %s mode=%s tables=%d chars=%d",
                 filename, result_out["parse_mode"], table_count, len(text))
        return result_out

    except Exception as e:
        log.error("Docling 解析失败 [%s]: %s", filename, e)
        raise


# ==================== 健康检查 & 批量接口 ====================

@app.route("/api/tables", methods=["POST"])
def extract_tables():
    """
    专门提取文档中的表格（返回 JSON 结构化格式）

    请求：multipart/form-data，字段 file
    返回：{ "tables": [ {"page": 1, "markdown": "| col1 | col2 |...", "json": {...} }, ... ] }
    """
    if not DOCLING_ENABLED:
        return jsonify({"error": "Docling service is disabled"}), 503

    upload = request.files.get("file")
    if upload is None:
        return jsonify({"error": "missing required field: file"}), 400

    try:
        file_bytes = upload.read()
        filename = upload.filename or "unknown"
        converter = get_converter()

        if converter is None:
            return jsonify({"error": "Docling not available"}), 503

        dl = _import_docling()
        input_stream = dl["DocumentStream"](name=filename, stream=io.BytesIO(file_bytes))
        result = converter.convert(input_stream, raises_on_error=True)
        dl_doc = result.document

        tables = []
        for idx, element in enumerate(dl_doc.document.iterate_items()):
            if hasattr(element, "export_to_dict"):
                try:
                    tables.append({
                        "index": idx,
                        "markdown": element.export_to_markdown() if hasattr(element, "export_to_markdown") else "",
                        "json": element.export_to_dict(),
                    })
                except Exception:
                    pass

        return jsonify({
            "filename": filename,
            "table_count": len(tables),
            "tables": tables,
        })

    except Exception as e:
        log.error("表格提取失败: %s", e)
        return jsonify({"error": f"table extraction failed: {e}"}), 500


# ==================== 带页码标记的导出函数 ====================
# 注意：这些函数必须在 if __name__ == "__main__": 之前定义

PAGE_MARKER = "\n\n[PAGE: %d]\n\n"


def _get_item_page(item) -> int:
    """从 Docling 文档元素中提取页码（1-based），失败返回 0"""
    try:
        # Docling 2.x: item.prov 是 ProvenanceItem 列表，每个有 page_no (1-based)
        if hasattr(item, "prov") and item.prov:
            prov = item.prov[0] if isinstance(item.prov, list) else item.prov
            if hasattr(prov, "page_no"):
                return int(prov.page_no)
            if hasattr(prov, "page"):
                return int(prov.page) + 1  # 有些版本 page 是 0-based
        # 备选：item.header.prov
        if hasattr(item, "header") and hasattr(item.header, "prov") and item.header.prov:
            prov = item.header.prov[0] if isinstance(item.header.prov, list) else item.header.prov
            if hasattr(prov, "page_no"):
                return int(prov.page_no)
    except Exception:
        pass
    return 0


def _export_markdown_with_page_markers(dl_doc) -> str:
    """
    导出 Markdown 并在页码变化处注入 [PAGE: N] 标记。

    策略：先导出完整 markdown，再遍历文档元素获取页码边界，
    在元素文本首次出现的位置前插入页码标记。
    """
    log.info("[PAGE_DEBUG] _export_markdown_with_page_markers 被调用, type: %s", type(dl_doc).__name__)
    try:
        full_md = dl_doc.export_to_markdown()
        log.info("[PAGE_DEBUG] full_md 长度: %d", len(full_md) if full_md else 0)

        # dl_doc 本身就是 DoclingDocument 对象，它的 iterate_items() 方法可以直接使用
        if not full_md:
            return ""

        # 检查是否有 iterate_items 方法（Docling 2.x）
        if not hasattr(dl_doc, "iterate_items"):
            log.info("[PAGE_DEBUG] dl_doc 没有 iterate_items 方法，尝试访问 .document 属性")
            if hasattr(dl_doc, "document"):
                doc = dl_doc.document
            else:
                log.info("[PAGE_DEBUG] 无法获取 document 对象，返回原始 markdown")
                return full_md
        else:
            doc = dl_doc

        # 收集 (页码, 元素markdown) 对
        items_with_pages = []
        page_set = set()  # 调试：收集所有页码
        item_count = 0
        for item, _ in doc.iterate_items():
            item_count += 1
            page = _get_item_page(item)
            page_set.add(page)  # 调试
            # 尝试多种方式获取元素的文本
            item_md = ""
            if hasattr(item, "export_to_markdown"):
                try:
                    item_md = item.export_to_markdown()
                except Exception:
                    pass
            if not item_md and hasattr(item, "text"):
                try:
                    item_md = str(item.text) if item.text else ""
                except Exception:
                    pass
            if item_md and item_md.strip():
                items_with_pages.append((page, item_md.strip()))

        log.info("[PAGE_DEBUG] iterate_items 共 %d 个元素, 提取到的页码集合: %s, 有效元素: %d", item_count, sorted(page_set)[:20], len(items_with_pages))

        if not items_with_pages:
            return full_md

        # 在完整 markdown 中找到每个元素文本的位置，插入页码标记
        # 使用从后向前插入避免偏移
        markers = []  # (insert_position, page_number)
        search_start = 0
        for page, item_text in items_with_pages:
            if not item_text:
                continue
            # 在 full_md 中找 item_text 的位置（取前80字符作为搜索锚点）
            anchor = item_text[:80]
            pos = full_md.find(anchor, search_start)
            if pos >= 0:
                markers.append((pos, page))
                search_start = pos + len(anchor)
            # 找不到就跳过（可能被 Docling 格式化改变了）

        # 从后向前插入页码标记（避免位置偏移）
        result = full_md
        prev_page = 0
        marker_count = 0  # 调试计数
        for pos, page in reversed(markers):
            if page > 0 and page != prev_page:
                marker = PAGE_MARKER % page
                result = result[:pos] + marker + result[pos:]
                prev_page = page
                marker_count += 1

        log.info("[PAGE_DEBUG] 插入了 %d 个页码标记, markers 总数: %d", marker_count, len(markers))

        # 确保开头有第1页标记
        if not result.lstrip().startswith("[PAGE:"):
            result = (PAGE_MARKER % 1).lstrip("\n") + result

        return result

    except Exception as e:
        log.warning("带页码标记导出失败，回退到普通导出: %s", e)
        return dl_doc.export_to_markdown()


def _export_html_with_page_markers(dl_doc) -> str:
    """导出 HTML 并注入页码标记（使用 HTML 注释形式）"""
    try:
        text = _export_markdown_with_page_markers(dl_doc)
        # 简单转换：将 [PAGE: N] 替换为 HTML 注释
        import re
        text = re.sub(r'\[PAGE:\s*(\d+)\]', r'<!-- PAGE: \1 -->', text)
        return text
    except Exception:
        return dl_doc.export_to_html()


def _export_text_with_page_markers(dl_doc) -> str:
    """导出纯文本并注入页码标记"""
    try:
        return _export_markdown_with_page_markers(dl_doc)
    except Exception:
        return dl_doc.export_to_text()


# ==================== 主程序 ====================

if __name__ == "__main__":
    port = int(os.getenv("PORT", "8001"))
    log.info("=" * 60)
    log.info("Docling Service 启动中 ...")
    log.info("  Docling 可用: %s", DOCLING_AVAILABLE)
    log.info("  监听端口: %d", port)
    log.info("  默认输出格式: %s", DEFAULT_OUTPUT_FORMAT)
    log.info("=" * 60)

    if not DOCLING_AVAILABLE:
        log.warning("⚠ Docling 未安装！请运行：pip install docling")
        log.warning("  或者在容器中：docker build -f Dockerfile.docling ...")

    app.run(host="0.0.0.0", port=port, debug=False, threaded=True)