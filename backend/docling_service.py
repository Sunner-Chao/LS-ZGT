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

import os
os.environ["TORCH_DYNAMO_DISABLE"] = "1"

import io
import logging
import math
import multiprocessing
import queue as queue_module
import re
import sys
import traceback
from datetime import datetime
import threading
from typing import Any, Optional

from flask import Flask, jsonify, request


if not hasattr(sys, "get_int_max_str_digits"):
    # Ubuntu 22.04 的 python3.11 包是 3.11.0rc1，缺少 torch/transformers 需要的
    # sys API。补齐兼容函数，避免 Docling 导入时在 torch._dynamo 中失败。
    def _get_int_max_str_digits() -> int:
        return 4300

    def _set_int_max_str_digits(maxdigits: int) -> None:
        return None

    sys.get_int_max_str_digits = _get_int_max_str_digits
    sys.set_int_max_str_digits = _set_int_max_str_digits

# Docling Core（文档解析引擎，延迟导入避免启动失败）
DOCLING_AVAILABLE = False
_docling_core = None
_import_lock = threading.Lock()


def _import_docling():
    global DOCLING_AVAILABLE, _docling_core
    if _docling_core is not None:
        return _docling_core

    with _import_lock:
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
            log.warning("Docling 导入异常: %s", e, exc_info=True)
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

# 文本型 PDF 默认走本地文本层提取，避免 Docling layout 模型未缓存时阻塞解析。
FAST_TEXT_PDF = os.getenv("DOCLING_FAST_TEXT_PDF", "true").lower() == "true"

# 最大文件大小（MB）
MAX_FILE_SIZE_MB = int(os.getenv("DOCLING_MAX_FILE_SIZE_MB", "100"))

# Docling 文档转换器（全局单例，按需初始化）
_converter: Optional[Any] = None
_converter_lock = threading.Lock()
_parse_lock = threading.Lock()
DOCLING_PARSE_LOCK_TIMEOUT_SECONDS = int(os.getenv("DOCLING_PARSE_LOCK_TIMEOUT_SECONDS", "30"))
DOCLING_CONVERT_TIMEOUT_SECONDS = int(os.getenv("DOCLING_CONVERT_TIMEOUT_SECONDS", "120"))

PDF_TEXT_PAGE_MIN_EFFECTIVE_CHARS = int(os.getenv("PDF_TEXT_PAGE_MIN_EFFECTIVE_CHARS", "80"))
PDF_TEXT_PAGE_MIN_EFFECTIVE_LINES = int(os.getenv("PDF_TEXT_PAGE_MIN_EFFECTIVE_LINES", "2"))
PDF_TEXT_LAYER_MIN_PAGE_RATIO = float(os.getenv("PDF_TEXT_LAYER_MIN_PAGE_RATIO", "0.30"))
PDF_TEXT_LAYER_MIN_TOTAL_CHARS_SHORT = int(os.getenv("PDF_TEXT_LAYER_MIN_TOTAL_CHARS_SHORT", "120"))
PDF_TEXT_LAYER_MIN_TOTAL_CHARS_LONG = int(os.getenv("PDF_TEXT_LAYER_MIN_TOTAL_CHARS_LONG", "400"))
PDF_TEXT_LAYER_MIN_CHARS_PER_PAGE = int(os.getenv("PDF_TEXT_LAYER_MIN_CHARS_PER_PAGE", "30"))
PDF_PAGE_NUMBER_RE = re.compile(
    r"^[\s\-—–_]*(第?\s*\d+\s*(页|/\s*\d+)?|\d+|[ivxlcdmIVXLCDM]+)[\s\-—–_]*$"
)


def get_converter() -> Optional[Any]:
    """延迟初始化 Docling DocumentConverter（首次调用时创建）"""
    global _converter
    if _converter is not None:
        return _converter

    with _converter_lock:
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
    dl = _import_docling()
    converter_ready = _converter is not None
    status = {
        "status": "ok" if (dl is not None and DOCLING_ENABLED) else "unavailable",
        "docling_available": DOCLING_AVAILABLE,
        "docling_enabled": DOCLING_ENABLED,
        "converter_ready": converter_ready,
        "output_format": DEFAULT_OUTPUT_FORMAT,
        "native_mode": os.getenv("DOCLING_NATIVE_MODE", "true").lower() == "true",
    }
    return jsonify(status), 200 if (dl is not None and DOCLING_ENABLED) else 503


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
    filename = upload.filename or "unknown"

    parse_locked = False
    try:
        file_bytes = upload.read()
        file_size_mb = len(file_bytes) / (1024 * 1024)

        if file_size_mb > MAX_FILE_SIZE_MB:
            return jsonify({
                "error": f"File too large: {file_size_mb:.1f}MB (max {MAX_FILE_SIZE_MB}MB)"
            }), 413

        if not file_bytes:
            return jsonify({"error": "empty file"}), 400

        # 根据文件扩展名判断格式
        file_ext = filename.rsplit(".", 1)[-1].lower() if "." in filename else ""

        # 文本型 PDF 不需要进入 Docling 重解析，也不应该被全局解析锁阻塞。
        if file_ext == "pdf" and FAST_TEXT_PDF and (not enable_ocr or _detect_pdf_type_quick(file_bytes)):
            result = _parse_pdf_text_locally(file_bytes, filename, output_format, "pypdfium-text-unlocked")
            status_code = 503 if result.get("error") else 200
            return jsonify(result), status_code

        log.info("等待解析锁: %s", filename)
        parse_locked = _parse_lock.acquire(timeout=DOCLING_PARSE_LOCK_TIMEOUT_SECONDS)
        if not parse_locked:
            log.warning("获取解析锁超时: %s timeout=%ss", filename, DOCLING_PARSE_LOCK_TIMEOUT_SECONDS)
            return jsonify({
                "error": f"Docling parser is busy, lock timeout after {DOCLING_PARSE_LOCK_TIMEOUT_SECONDS}s",
                "filename": filename,
                "text": "",
                "pages": 0,
            }), 429
        log.info("获得解析锁: %s", filename)

        result = process_document(
            file_bytes=file_bytes,
            file_format=file_ext,
            filename=filename,
            output_format=output_format,
            enable_ocr=enable_ocr,
        )

        status_code = 503 if result.get("error") else 200
        return jsonify(result), status_code

    except Exception as e:
        log.error("解析失败 [%s]: %s\n%s", filename, e, traceback.format_exc())
        return jsonify({"error": f"parse failed: {e}"}), 500
    finally:
        if parse_locked:
            _parse_lock.release()
            log.info("释放解析锁: %s", filename)


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

    判定规则：过滤页码、极短行、跨页重复的页眉/页脚/水印后，
    只有达到有效文本页占比和总有效字符数门槛，才认为存在可用文本层。
    """
    try:
        import pypdfium2 as pdfium
    except Exception:
        log.warn("PDF 类型检测：pypdfium2 未安装，默认走文本提取")
        return True  # 无法检测，默认文本提取

    try:
        pdf = pdfium.PdfDocument(file_bytes)
        pages = len(pdf)
        if pages == 0:
            log.warn("PDF 类型检测：页数为 0，切换 OCR")
            return False

        page_lines: list[list[str]] = []
        line_pages: dict[str, set[int]] = {}
        for page_index in range(pages):
            page = pdf[page_index]
            text = ""
            try:
                textpage = page.get_textpage()
                try:
                    text = textpage.get_text_range()
                except TypeError:
                    text = textpage.get_text_range(0, textpage.count_chars())
            except Exception as page_error:
                log.warn("PDF 类型检测：第 %d 页文字提取失败: %s", page_index + 1, page_error)

            lines = _normalize_pdf_text_lines(text)
            page_lines.append(lines)
            for line in set(lines):
                line_pages.setdefault(line, set()).add(page_index)

        repeated_line_threshold = max(3, math.ceil(pages * PDF_TEXT_LAYER_MIN_PAGE_RATIO))
        repeated_lines = {
            line for line, seen_pages in line_pages.items()
            if len(seen_pages) >= repeated_line_threshold
        }

        meaningful_pages = 0
        total_effective_chars = 0
        max_effective_chars = 0
        for lines in page_lines:
            effective_chars, effective_lines = _get_effective_pdf_page_text_stats(lines, repeated_lines)
            total_effective_chars += effective_chars
            max_effective_chars = max(max_effective_chars, effective_chars)
            if (
                effective_chars >= PDF_TEXT_PAGE_MIN_EFFECTIVE_CHARS
                and effective_lines >= PDF_TEXT_PAGE_MIN_EFFECTIVE_LINES
            ):
                meaningful_pages += 1

        min_meaningful_pages = (
            1 if pages <= 2 else max(2, math.ceil(pages * PDF_TEXT_LAYER_MIN_PAGE_RATIO))
        )
        min_total_effective_chars = (
            PDF_TEXT_LAYER_MIN_TOTAL_CHARS_SHORT
            if pages <= 2
            else max(PDF_TEXT_LAYER_MIN_TOTAL_CHARS_LONG, pages * PDF_TEXT_LAYER_MIN_CHARS_PER_PAGE)
        )
        has_effective_text_layer = (
            meaningful_pages >= min_meaningful_pages
            and total_effective_chars >= min_total_effective_chars
        )

        log.info(
            "PDF 类型检测：pages=%d meaningfulPages=%d/%d totalEffectiveChars=%d "
            "maxPageEffectiveChars=%d repeatedLines=%d minPages=%d minChars=%d result=%s",
            pages,
            meaningful_pages,
            pages,
            total_effective_chars,
            max_effective_chars,
            len(repeated_lines),
            min_meaningful_pages,
            min_total_effective_chars,
            "数字PDF" if has_effective_text_layer else "扫描版/图片型",
        )
        return has_effective_text_layer

    except Exception as e:
        log.warn("PDF 类型检测失败，默认走文本提取: %s", e)
        return True


def _normalize_pdf_text_lines(text: str | None) -> list[str]:
    if not text:
        return []
    lines: list[str] = []
    for line in text.splitlines():
        normalized = re.sub(r"\s+", " ", line).strip()
        if normalized:
            lines.append(normalized)
    return lines


def _compact_pdf_text(text: str | None) -> str:
    return re.sub(r"\s+", "", text or "")


def _is_ignorable_pdf_line(line: str, repeated_lines: set[str]) -> bool:
    compact = _compact_pdf_text(line)
    return len(compact) <= 3 or line in repeated_lines or PDF_PAGE_NUMBER_RE.fullmatch(line) is not None


def _get_effective_pdf_page_text_stats(lines: list[str], repeated_lines: set[str]) -> tuple[int, int]:
    effective_chars = 0
    effective_lines = 0
    for line in lines:
        if _is_ignorable_pdf_line(line, repeated_lines):
            continue
        compact_length = len(_compact_pdf_text(line))
        effective_chars += compact_length
        if compact_length >= 8:
            effective_lines += 1
    return effective_chars, effective_lines


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
    # 对 PDF 做快速类型检测（仅当 OCR 全局启用时）
    use_text_extraction = False
    if file_format == "pdf":
        use_text_extraction = not enable_ocr or _detect_pdf_type_quick(file_bytes)

    dl = _import_docling()
    if dl is None:
        if file_format == "pdf" and use_text_extraction:
            return _parse_pdf_text_locally(file_bytes, filename, output_format, "pypdfium-fallback")

        return {
            "error": "Docling 未安装或初始化失败",
            "text": "",
            "pages": 0,
        }

    if file_format == "pdf" and use_text_extraction and FAST_TEXT_PDF:
        return _parse_pdf_text_locally(file_bytes, filename, output_format, "pypdfium-text")

    try:
        start = datetime.now()
        if file_format == "pdf":
            if use_text_extraction:
                log.info("PDF 类型：数字 PDF，文本提取模式（含表格结构）")
            else:
                log.info("PDF 类型：扫描版，OCR 模式")
            result_out = _convert_pdf_with_timeout(file_bytes, filename, use_text_extraction, output_format)
            elapsed = (datetime.now() - start).total_seconds()
            result_out["elapsed_seconds"] = round(elapsed, 2)
            result_out["parse_mode"] = "text" if use_text_extraction else "ocr"
            log.info(
                "解析成功: %s mode=%s tables=%d chars=%d",
                filename,
                result_out["parse_mode"],
                result_out.get("tables", 0),
                len(result_out.get("text", "")),
            )
            return result_out
        else:
            input_stream = dl["DocumentStream"](
                name=filename,
                stream=io.BytesIO(file_bytes),
            )
            converter = get_converter()
            if converter is None:
                return {
                    "error": "Docling 未安装或初始化失败",
                    "text": "",
                    "pages": 0,
                }
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
        if file_format == "pdf" and use_text_extraction:
            fallback = _parse_pdf_text_locally(
                file_bytes,
                filename,
                output_format,
                "pypdfium-fallback-after-docling-error",
            )
            if not fallback.get("error"):
                return fallback
        if file_format == "pdf":
            log.warning("扫描版/图片型 PDF OCR 失败，不使用文本层兜底: %s", filename)
        raise


def _docling_pdf_convert_worker(
    result_queue: multiprocessing.Queue,
    file_bytes: bytes,
    filename: str,
    use_text_extraction: bool,
    output_format: str,
) -> None:
    try:
        dl = _import_docling()
        if dl is None:
            result_queue.put({"error": "Docling 未安装或初始化失败"})
            return

        from docling.datamodel.pipeline_options import PdfPipelineOptions

        if use_text_extraction:
            pipeline_opts = PdfPipelineOptions(
                do_ocr=False,
                force_backend_text=True,
                do_table_structure=True,
            )
        else:
            pipeline_opts = PdfPipelineOptions(
                do_ocr=True,
                do_table_structure=True,
            )

        pdf_option = dl["PdfFormatOption"](pipeline_options=pipeline_opts)
        converter = dl["DocumentConverter"](
            format_options={dl["InputFormat"].PDF: pdf_option},
        )
        input_stream = dl["DocumentStream"](
            name=filename,
            stream=io.BytesIO(file_bytes),
        )
        result = converter.convert(input_stream, raises_on_error=True)
        dl_doc = result.document

        if output_format == "markdown":
            text = _export_markdown_with_page_markers(dl_doc)
        elif output_format == "html":
            text = _export_html_with_page_markers(dl_doc)
        elif output_format == "text":
            text = _export_text_with_page_markers(dl_doc)
        else:
            text = _export_markdown_with_page_markers(dl_doc)

        result_queue.put({
            "text": text,
            "pages": len(dl_doc.pages) if hasattr(dl_doc, "pages") else 1,
            "format": output_format,
            "tables": text.count("\n|") if text else 0,
            "filename": filename,
        })
    except Exception as e:
        result_queue.put({
            "error": str(e),
            "traceback": traceback.format_exc(),
        })


def _convert_pdf_with_timeout(
    file_bytes: bytes,
    filename: str,
    use_text_extraction: bool,
    output_format: str,
) -> dict[str, Any]:
    """
    Run Docling PDF conversion in a child process so a stuck conversion cannot
    hold the Flask worker and global parse lock forever.
    """
    ctx = multiprocessing.get_context("spawn")
    result_queue = ctx.Queue(maxsize=1)
    process = ctx.Process(
        target=_docling_pdf_convert_worker,
        args=(result_queue, file_bytes, filename, use_text_extraction, output_format),
        daemon=True,
    )
    process.start()
    process.join(DOCLING_CONVERT_TIMEOUT_SECONDS)

    if process.is_alive():
        process.terminate()
        process.join(10)
        if process.is_alive():
            process.kill()
            process.join(5)
        raise TimeoutError(
            f"Docling conversion timed out after {DOCLING_CONVERT_TIMEOUT_SECONDS}s: {filename}"
        )

    try:
        result = result_queue.get_nowait()
    except queue_module.Empty:
        raise RuntimeError(f"Docling conversion exited without result: {filename}")

    if result.get("error"):
        detail = result.get("traceback") or result.get("error")
        raise RuntimeError(detail)

    return result


def _parse_pdf_text_locally(
    file_bytes: bytes,
    filename: str,
    output_format: str,
    parse_mode: str,
) -> dict[str, Any]:
    """
    用 PDF 文本层做本地兜底解析。

    Docling 的标准 PDF pipeline 会按需下载 layout/OCR 模型；内网或模型未缓存时，
    数字 PDF 不应因此解析失败。这里保留页码标记，供后续分片和引用页码使用。
    """
    try:
        import pypdfium2 as pdfium

        pdf = pdfium.PdfDocument(file_bytes)
        pages = len(pdf)
        parts: list[str] = []

        for idx in range(pages):
            page = pdf[idx]
            text = ""
            try:
                textpage = page.get_textpage()
                try:
                    text = textpage.get_text_range()
                except TypeError:
                    text = textpage.get_text_range(0, textpage.count_chars())
            except Exception as e:
                log.warning("本地 PDF 文本提取失败: %s page=%d error=%s", filename, idx + 1, e)

            parts.append(PAGE_MARKER % (idx + 1))
            parts.append((text or "").strip())

        text = "".join(parts).strip()
        if not text:
            return {
                "error": "PDF 文本层为空，需使用 OCR 解析",
                "text": "",
                "pages": pages,
                "filename": filename,
                "parse_mode": parse_mode,
            }

        if output_format == "html":
            import html
            text_out = "<pre>" + html.escape(text) + "</pre>"
        else:
            text_out = text

        result = {
            "text": text_out,
            "pages": pages,
            "format": output_format,
            "tables": 0,
            "filename": filename,
            "elapsed_seconds": 0,
            "parse_mode": parse_mode,
        }
        log.info("本地 PDF 文本提取成功: %s pages=%d chars=%d mode=%s",
                 filename, pages, len(text_out), parse_mode)
        return result
    except Exception as e:
        log.error("本地 PDF 文本提取异常 [%s]: %s", filename, e, exc_info=True)
        return {
            "error": f"PDF text extraction failed: {e}",
            "text": "",
            "pages": 0,
            "filename": filename,
            "parse_mode": parse_mode,
        }


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
