# 筑规通桌面客户端 - 阶段3：文档处理实现计划 ⭐ 更新 Docling 集成

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development

**Goal:** 实现基于 Docling 的文档解析器（PDF、DOCX、PPTX、XLSX、图片等）和知识库管理功能。

**Architecture:** 文档上传 → 格式检测 → Docling 解析 → 分块 → 嵌入 → 存储

**核心组件:** Docling (IBM 开源，57K+ stars) + Rust 适配层

---

## 核心设计：为什么选择 Docling？

| 特性 | 传统方案 (lopdf) | Docling |
|------|-----------------|---------|
| PDF 解析 | 文字提取 | 页面布局 + 阅读顺序 |
| 表格处理 | 纯文本 | 结构化表格（行列、多级表头） |
| 公式支持 | 无 | 行内/块级公式检测 |
| 图片理解 | 无 | 图片分类、图表理解 |
| OCR | 需额外集成 | EasyOCR/Tesseract 内置 |
| 多格式 | 仅 PDF | PDF/DOCX/PPTX/XLSX/HTML/LaTeX... |
| AI 集成 | 无 | LangChain/LlamaIndex/MCP 内置 |
| 本地执行 | ✅ | ✅ (气隙环境支持) |

---

## 文件结构规划

```
core/src/
├── parser/
│   ├── mod.rs                  # 解析器统一入口
│   ├── docling/
│   │   ├── mod.rs             # Docling 适配层
│   │   ├── bridge.rs          # Python subprocess 调用
│   │   ├── types.rs           # DoclingDocument 类型映射
│   │   └── chunker.rs         # 智能分块器
│   ├── cad.rs                 # CAD 解析 (补充)
│   └── bim.rs                 # BIM 解析 (补充)
│
└── knowledge/
    ├── mod.rs
    └── manager.rs             # 知识库管理器
```

---

## Task 1: Docling 适配层

**Files:**
- Create: `core/src/parser/docling/mod.rs`
- Create: `core/src/parser/docling/bridge.rs`
- Create: `core/src/parser/docling/types.rs`
- Create: `core/src/parser/docling/chunker.rs`

- [ ] **Step 1: 创建 Docling 桥接层类型**

```rust
// core/src/parser/docling/types.rs

use serde::{Deserialize, Serialize};

/// Docling 导出的文档结构
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct DoclingDocument {
    pub metadata: DocMetadata,
    pub elements: Vec<DocElement>,
    pub exports: DocExports,
}

/// 文档元数据
#[derive(Debug, Clone, Serialize, Deserialize, Default)]
pub struct DocMetadata {
    #[serde(rename = "source")]
    pub source_file: Option<String>,
    #[serde(rename = "page_count")]
    pub page_count: Option<u32>,
    #[serde(rename = "file_size")]
    pub file_size: Option<u64>,
    #[serde(rename = "document_type")]
    pub document_type: Option<String>,
}

/// 文档元素
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(tag = "type")]
pub enum DocElement {
    #[serde(rename = "text")]
    Text {
        text: String,
        page: Option<u32>,
        bbox: Option<BoundingBox>,
    },
    #[serde(rename = "table")]
    Table {
        data: TableData,
        page: Option<u32>,
        bbox: Option<BoundingBox>,
    },
    #[serde(rename = "formula")]
    Formula {
        latex: Option<String>,
        image: Option<String>,
        page: Option<u32>,
        bbox: Option<BoundingBox>,
    },
    #[serde(rename = "image")]
    Image {
        caption: Option<String>,
        image_data: Option<String>,
        page: Option<u32>,
        bbox: Option<BoundingBox>,
    },
}

/// 边界框
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct BoundingBox {
    pub l: f32,
    pub t: f32,
    pub r: f32,
    pub b: f32,
}

/// 表格数据
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct TableData {
    pub headers: Vec<Vec<String>>,
    pub rows: Vec<Vec<String>>,
    pub table_type: Option<String>,
}

/// 导出格式
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct DocExports {
    #[serde(rename = "markdown")]
    pub markdown: Option<String>,
    #[serde(rename = "json")]
    pub json_doc: Option<serde_json::Value>,
}

/// 分块后的文档片段
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct DocChunk {
    pub id: String,
    pub content: String,
    pub chunk_type: ChunkType,
    pub page: Option<u32>,
    pub metadata: ChunkMetadata,
}

/// 分块类型
#[derive(Debug, Clone, Serialize, Deserialize)]
pub enum ChunkType {
    Text,
    Table,
    Formula,
    Image,
}

/// 分块元数据
#[derive(Debug, Clone, Serialize, Deserialize, Default)]
pub struct ChunkMetadata {
    pub document_id: Option<String>,
    pub chunk_index: u32,
    pub bbox: Option<BoundingBox>,
    pub table_structure: Option<TableData>,
}
```

- [ ] **Step 2: 创建 Docling Subprocess 桥接**

```rust
// core/src/parser/docling/bridge.rs

use super::types::*;
use std::process::{Command, Stdio};
use std::io::{BufRead, BufReader};
use serde_json;
use tokio::io::{AsyncBufReadExt, AsyncWriteExt};
use tokio::process::{Command as AsyncCommand};
use tokio::io::AsyncBufReadExt as TokioAsyncBufReadExt;

/// Docling Python 脚本路径（嵌入或从资源加载）
const DOCLING_SCRIPT: &str = r#"
import sys
import json
import asyncio
from docling.document_converter import DocumentConverter

async def main():
    import asyncio
    converter = DocumentConverter()

    for line in sys.stdin:
        line = line.strip()
        if not line:
            continue

        try:
            data = json.loads(line)
            file_path = data.get("file_path")
            format = data.get("format", "json")

            if not file_path:
                print(json.dumps({"error": "No file_path provided"}))
                continue

            result = converter.convert(file_path)

            if format == "markdown":
                output = result.document.export_to_markdown()
            elif format == "doctags":
                output = result.document.export_to_doctags()
            else:
                # Full JSON export
                output = result.document.export_to_dict()

            print(json.dumps({"success": True, "data": output}))
        except Exception as e:
            print(json.dumps({"error": str(e)}), flush=True)

        sys.stdout.flush()

if __name__ == "__main__":
    asyncio.run(main())
"#;

/// Docling 桥接器
pub struct DoclingBridge {
    python_path: String,
    script_path: std::path::PathBuf,
}

impl DoclingBridge {
    /// 创建桥接器
    pub fn new() -> Result<Self, std::io::Error> {
        // 方案1: 写入临时脚本
        let temp_dir = std::env::temp_dir();
        let script_path = temp_dir.join("docling_bridge.py");

        std::fs::write(&script_path, DOCLING_SCRIPT)?;

        // 查找 Python 解释器
        let python_path = Self::find_python();

        Ok(Self {
            python_path,
            script_path,
        })
    }

    fn find_python() -> String {
        // 优先使用虚拟环境中的 Python
        if let Ok(venv) = std::env::var("VIRTUAL_ENV") {
            let python = std::path::Path::new(&venv).join("bin/python");
            if python.exists() {
                return python.to_string_lossy().to_string();
            }
        }

        // 回退到系统 Python
        "python3".to_string()
    }

    /// 解析文档
    pub async fn parse(
        &self,
        file_path: &str,
        format: &str,
    ) -> Result<DoclingDocument, DoclingError> {
        // 构建请求
        let request = serde_json::json!({
            "file_path": file_path,
            "format": format,
        });

        // 启动 Python 子进程
        let mut child = AsyncCommand::new(&self.python_path)
            .arg(&self.script_path)
            .stdin(Stdio::piped())
            .stdout(Stdio::piped())
            .stderr(Stdio::piped())
            .spawn()
            .map_err(|e| DoclingError::ProcessStartFailed(e.to_string()))?;

        // 发送请求
        let mut stdin = child.stdin.take().unwrap();
        stdin
            .write_all(format!("{}\n", request.to_string()).as_bytes())
            .await
            .map_err(|e| DoclingError::WriteFailed(e.to_string()))?;

        drop(stdin);

        // 读取响应
        let mut stdout = child.stdout.take().unwrap();
        let mut line = String::new();
        stdout
            .read_line(&mut line)
            .await
            .map_err(|e| DoclingError::ReadFailed(e.to_string()))?;

        // 等待进程结束
        child
            .wait()
            .await
            .map_err(|e| DoclingError::ProcessFailed(e.to_string()))?;

        // 解析响应
        let response: serde_json::Value = serde_json::from_str(&line)
            .map_err(|e| DoclingError::ParseFailed(e.to_string()))?;

        if let Some(error) = response.get("error") {
            return Err(DoclingError::DoclingFailed(
                error.as_str().unwrap_or("Unknown error").to_string(),
            ));
        }

        let data = response
            .get("data")
            .ok_or_else(|| DoclingError::ParseFailed("No data in response".to_string()))?;

        let doc: DoclingDocument = serde_json::from_value(data.clone())
            .map_err(|e| DoclingError::ParseFailed(e.to_string()))?;

        Ok(doc)
    }

    /// 解析为 Markdown（轻量级）
    pub async fn parse_markdown(&self, file_path: &str) -> Result<String, DoclingError> {
        let request = serde_json::json!({
            "file_path": file_path,
            "format": "markdown",
        });

        // 简化实现：使用同步 subprocess
        let output = Command::new(&self.python_path)
            .arg(&self.script_path)
            .stdin(Stdio::piped())
            .stdout(Stdio::piped())
            .output()
            .map_err(|e| DoclingError::ProcessStartFailed(e.to_string()))?;

        let stdout = String::from_utf8_lossy(&output.stdout);
        let line = stdout.lines().next().unwrap_or("{}");

        let response: serde_json::Value = serde_json::from_str(line)
            .map_err(|e| DoclingError::ParseFailed(e.to_string()))?;

        if let Some(error) = response.get("error") {
            return Err(DoclingError::DoclingFailed(
                error.as_str().unwrap_or("Unknown error").to_string(),
            ));
        }

        let data = response
            .get("data")
            .ok_or_else(|| DoclingError::ParseFailed("No data".to_string()))?;

        Ok(data.as_str().unwrap_or("").to_string())
    }
}

#[derive(Debug, thiserror::Error)]
pub enum DoclingError {
    #[error("进程启动失败: {0}")]
    ProcessStartFailed(String),

    #[error("写入失败: {0}")]
    WriteFailed(String),

    #[error("读取失败: {0}")]
    ReadFailed(String),

    #[error("进程执行失败: {0}")]
    ProcessFailed(String),

    #[error("解析失败: {0}")]
    ParseFailed(String),

    #[error("Docling 错误: {0}")]
    DoclingFailed(String),
}
```

- [ ] **Step 3: 创建智能分块器**

```rust
// core/src/parser/docling/chunker.rs

use super::types::*;
use uuid::Uuid;

/// 分块策略
#[derive(Debug, Clone)]
pub enum ChunkStrategy {
    /// 按段落分块
    ByParagraph { max_size: usize, overlap: usize },
    /// 按语义单元分块（保留表格/公式）
    BySemanticUnit { preserve_tables: bool },
    /// 按页面分块
    ByPage,
}

/// Docling 文档分块器
pub struct DoclingChunker {
    strategy: ChunkStrategy,
}

impl DoclingChunker {
    pub fn new(strategy: ChunkStrategy) -> Self {
        Self { strategy }
    }

    /// 默认策略：语义分块
    pub fn default() -> Self {
        Self::new(ChunkStrategy::BySemanticUnit {
            preserve_tables: true,
        })
    }

    /// 将 DoclingDocument 转换为分块
    pub fn chunk(&self, doc: &DoclingDocument) -> Vec<DocChunk> {
        match &self.strategy {
            ChunkStrategy::ByParagraph { max_size, overlap } => {
                self.chunk_by_paragraph(doc, *max_size, *overlap)
            }
            ChunkStrategy::BySemanticUnit { preserve_tables } => {
                self.chunk_by_semantic(doc, *preserve_tables)
            }
            ChunkStrategy::ByPage => self.chunk_by_page(doc),
        }
    }

    /// 按语义单元分块（保留表格、公式作为独立块）
    fn chunk_by_semantic(&self, doc: &DoclingDocument, preserve_tables: bool) -> Vec<DocChunk> {
        let mut chunks = Vec::new();
        let doc_id = Uuid::new_v4().to_string();

        for element in &doc.elements {
            let chunk = match element {
                DocElement::Text { text, page, bbox } => {
                    // 文本按段落进一步分块
                    self.chunk_text_element(&doc_id, text, *page, bbox.clone())
                }
                DocElement::Table { data, page, bbox } => {
                    if preserve_tables {
                        // 表格作为独立块
                        vec![DocChunk {
                            id: Uuid::new_v4().to_string(),
                            content: self.table_to_markdown(data),
                            chunk_type: ChunkType::Table,
                            page: *page,
                            metadata: ChunkMetadata {
                                document_id: Some(doc_id.clone()),
                                chunk_index: chunks.len() as u32,
                                bbox: bbox.clone(),
                                table_structure: Some(data.clone()),
                            },
                        }]
                    } else {
                        // 表格转文本
                        self.chunk_text_element(
                            &doc_id,
                            &self.table_to_markdown(data),
                            *page,
                            bbox.clone(),
                        )
                    }
                }
                DocElement::Formula { latex, page, bbox, .. } => {
                    // 公式作为独立块
                    vec![DocChunk {
                        id: Uuid::new_v4().to_string(),
                        content: latex.clone().unwrap_or_default(),
                        chunk_type: ChunkType::Formula,
                        page: *page,
                        metadata: ChunkMetadata {
                            document_id: Some(doc_id.clone()),
                            chunk_index: chunks.len() as u32,
                            bbox: bbox.clone(),
                            table_structure: None,
                        },
                    }]
                }
                DocElement::Image { caption, page, bbox, .. } => {
                    // 图片及其描述作为独立块
                    vec![DocChunk {
                        id: Uuid::new_v4().to_string(),
                        content: caption.clone().unwrap_or_default(),
                        chunk_type: ChunkType::Image,
                        page: *page,
                        metadata: ChunkMetadata {
                            document_id: Some(doc_id.clone()),
                            chunk_index: chunks.len() as u32,
                            bbox: bbox.clone(),
                            table_structure: None,
                        },
                    }]
                }
            };

            chunks.extend(chunk);
        }

        // 更新 chunk_index
        for (i, chunk) in chunks.iter_mut().enumerate() {
            chunk.metadata.chunk_index = i as u32;
        }

        chunks
    }

    /// 将文本元素按段落分块
    fn chunk_text_element(
        &self,
        doc_id: &str,
        text: &str,
        page: Option<u32>,
        bbox: Option<BoundingBox>,
    ) -> Vec<DocChunk> {
        let paragraphs: Vec<&str> = text.split("\n\n").filter(|s| !s.trim().is_empty()).collect();

        paragraphs
            .iter()
            .enumerate()
            .map(|(i, para)| DocChunk {
                id: Uuid::new_v4().to_string(),
                content: para.trim().to_string(),
                chunk_type: ChunkType::Text,
                page,
                metadata: ChunkMetadata {
                    document_id: Some(doc_id.to_string()),
                    chunk_index: i as u32,
                    bbox: bbox.clone(),
                    table_structure: None,
                },
            })
            .collect()
    }

    /// 按页面分块
    fn chunk_by_page(&self, doc: &DoclingDocument) -> Vec<DocChunk> {
        let mut page_chunks: std::collections::HashMap<u32, Vec<&DocElement>> =
            std::collections::HashMap::new();

        for element in &doc.elements {
            let page = match element {
                DocElement::Text { page, .. } => page.unwrap_or(1),
                DocElement::Table { page, .. } => page.unwrap_or(1),
                DocElement::Formula { page, .. } => page.unwrap_or(1),
                DocElement::Image { page, .. } => page.unwrap_or(1),
            };
            page_chunks.entry(page).or_default().push(element);
        }

        page_chunks
            .into_iter()
            .map(|(page, elements)| {
                let content = elements
                    .iter()
                    .map(|e| match e {
                        DocElement::Text { text, .. } => text.clone(),
                        DocElement::Table { data, .. } => self.table_to_markdown(data),
                        DocElement::Formula { latex, .. } => {
                            format!("[公式] {}", latex.as_deref().unwrap_or(""))
                        }
                        DocElement::Image { caption, .. } => {
                            caption.as_deref().unwrap_or("[图片]").to_string()
                        }
                    })
                    .collect::<Vec<_>>()
                    .join("\n\n");

                DocChunk {
                    id: Uuid::new_v4().to_string(),
                    content,
                    chunk_type: ChunkType::Text,
                    page: Some(page),
                    metadata: ChunkMetadata {
                        document_id: None,
                        chunk_index: page,
                        bbox: None,
                        table_structure: None,
                    },
                }
            })
            .collect()
    }

    /// 表格转 Markdown
    fn table_to_markdown(&self, data: &TableData) -> String {
        let mut lines = Vec::new();

        // 表头
        if !data.headers.is_empty() {
            let header = data.headers[0]
                .iter()
                .map(|h| format!("| {}", h))
                .collect::<Vec<_>>()
                .join("");
            lines.push(format!("{} |", header));
            lines.push(
                data.headers[0]
                    .iter()
                    .map(|_| "|---")
                    .collect::<Vec<_>>()
                    .join(""),
            );
        }

        // 数据行
        for row in &data.rows {
            lines.push(
                row.iter()
                    .map(|cell| format!("| {}", cell))
                    .collect::<Vec<_>>()
                    .join(""),
            );
        }

        lines.join("\n")
    }
}
```

- [ ] **Step 4: 创建 Docling 解析器入口**

```rust
// core/src/parser/docling/mod.rs

pub mod bridge;
pub mod types;
pub mod chunker;

pub use bridge::{DoclingBridge, DoclingError};
pub use types::*;
pub use chunker::{DoclingChunker, ChunkStrategy};

use crate::parser::{DocumentParser, ParsedDocument, ParsedChunk, DocumentMetadata, FileType, ParserError};

/// Docling 文档解析器
pub struct DoclingParser {
    bridge: DoclingBridge,
    chunker: DoclingChunker,
}

impl DoclingParser {
    pub fn new() -> Result<Self, DoclingError> {
        Ok(Self {
            bridge: DoclingBridge::new()?,
            chunker: DoclingChunker::default(),
        })
    }

    pub fn with_chunker(chunker: DoclingChunker) -> Result<Self, DoclingError> {
        Ok(Self {
            bridge: DoclingBridge::new()?,
            chunker,
        })
    }

    /// 解析文件
    pub async fn parse_file(&self, file_path: &str) -> Result<DoclingDocument, DoclingError> {
        self.bridge.parse(file_path, "json").await
    }

    /// 解析为 Markdown
    pub async fn parse_markdown(&self, file_path: &str) -> Result<String, DoclingError> {
        self.bridge.parse_markdown(file_path).await
    }

    /// 获取分块
    pub fn chunk_document(&self, doc: &DoclingDocument) -> Vec<DocChunk> {
        self.chunker.chunk(doc)
    }
}

/// 支持的文件类型
pub const DOCLING_SUPPORTED: &[(&str, FileType)] = &[
    ("pdf", FileType::Pdf),
    ("docx", FileType::Docx),
    ("pptx", FileType::Pptx),
    ("xlsx", FileType::Xlsx),
    ("html", FileType::Html),
    ("htm", FileType::Html),
    ("png", FileType::Image),
    ("jpg", FileType::Image),
    ("jpeg", FileType::Image),
    ("tiff", FileType::Image),
    ("bmp", FileType::Image),
    ("latex", FileType::Latex),
    ("tex", FileType::Latex),
];

/// 检测是否被 Docling 支持
pub fn is_docling_supported(ext: &str) -> bool {
    DOCLING_SUPPORTED.iter().any(|(e, _)| *e == ext.to_lowercase())
}
```

- [ ] **Step 5: 提交**

```bash
git add core/src/parser/docling/
git commit -m "feat(parser): add Docling integration

- Add DoclingBridge for Python subprocess communication
- Add DoclingDocument types matching Docling v2 schema
- Add DoclingChunker with semantic/paragraph/page strategies
- Support PDF/DOCX/PPTX/XLSX/HTML/LaTeX/image parsing"
```

---

## Task 2: 统一解析器入口

**Files:**
- Create: `core/src/parser/mod.rs`

- [ ] **Step 1: 创建统一解析器接口**

```rust
// core/src/parser/mod.rs

pub mod docling;
pub mod cad;
pub mod bim;

pub use docling::{DoclingParser, DoclingDocument, DocChunk, ChunkStrategy};
pub use cad::CadParser;
pub use bim::BimParser;

use async_trait::async_trait;
use serde::{Deserialize, Serialize};
use std::path::Path;

/// 解析后的文档
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ParsedDocument {
    pub id: String,
    pub filename: String,
    pub file_type: FileType,
    pub title: Option<String>,
    pub chunks: Vec<ParsedChunk>,
    pub metadata: DocumentMetadata,
    /// Docling 原始导出（如果有）
    pub docling_export: Option<serde_json::Value>,
}

/// 解析后的片段
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ParsedChunk {
    pub content: String,
    pub page: Option<u32>,
    pub line_start: Option<u32>,
    pub line_end: Option<u32>,
    pub chunk_index: u32,
    pub chunk_type: ChunkKind,
    /// 扩展元数据
    pub extra: Option<serde_json::Value>,
}

/// 分块类型
#[derive(Debug, Clone, Copy, Serialize, Deserialize, PartialEq, Eq)]
pub enum ChunkKind {
    Text,
    Table,
    Formula,
    Image,
}

/// 文档元数据
#[derive(Debug, Clone, Serialize, Deserialize, Default)]
pub struct DocumentMetadata {
    pub author: Option<String>,
    pub created_date: Option<String>,
    pub modified_date: Option<String>,
    pub page_count: Option<u32>,
    pub file_size: Option<u64>,
    pub document_type: Option<String>,
}

/// 支持的文件类型
#[derive(Debug, Clone, Copy, Serialize, Deserialize, PartialEq, Eq)]
pub enum FileType {
    Pdf,
    Docx,
    Pptx,
    Xlsx,
    Html,
    Image,
    Latex,
    Cad, // DXF
    Bim, // IFC
    Unknown,
}

impl FileType {
    pub fn from_extension(ext: &str) -> Self {
        match ext.to_lowercase().as_str() {
            "pdf" => FileType::Pdf,
            "docx" => FileType::Docx,
            "pptx" => FileType::Pptx,
            "xlsx" => FileType::Xlsx,
            "html" | "htm" => FileType::Html,
            "png" | "jpg" | "jpeg" | "tiff" | "tif" | "bmp" | "gif" => FileType::Image,
            "latex" | "tex" => FileType::Latex,
            "dxf" => FileType::Cad,
            "ifc" => FileType::Bim,
            _ => FileType::Unknown,
        }
    }

    pub fn is_docling_supported(&self) -> bool {
        matches!(
            self,
            FileType::Pdf
                | FileType::Docx
                | FileType::Pptx
                | FileType::Xlsx
                | FileType::Html
                | FileType::Image
                | FileType::Latex
        )
    }

    pub fn is_cad_supported(&self) -> bool {
        matches!(self, FileType::Cad)
    }

    pub fn is_bim_supported(&self) -> bool {
        matches!(self, FileType::Bim)
    }
}

#[derive(Debug, thiserror::Error)]
pub enum ParserError {
    #[error("文件不存在: {0}")]
    FileNotFound(String),

    #[error("不支持的文件类型: {0}")]
    UnsupportedType(String),

    #[error("解析失败: {0}")]
    ParseFailed(String),

    #[error("Docling 错误: {0}")]
    DoclingError(String),

    #[error("IO 错误: {0}")]
    IoError(String),
}

/// 统一文档解析器
pub struct DocumentParser {
    docling: DoclingParser,
    cad: Option<CadParser>,
    bim: Option<BimParser>,
}

impl DocumentParser {
    pub fn new() -> Result<Self, ParserError> {
        Ok(Self {
            docling: DoclingParser::new().map_err(|e| ParserError::DoclingError(e.to_string()))?,
            cad: Some(CadParser::new()),
            bim: Some(BimParser::new()),
        })
    }

    /// 解析文档
    pub async fn parse(&self, file_path: &str) -> Result<ParsedDocument, ParserError> {
        let path = Path::new(file_path);

        // 检查文件存在
        if !path.exists() {
            return Err(ParserError::FileNotFound(file_path.to_string()));
        }

        // 获取扩展名
        let ext = path
            .extension()
            .and_then(|e| e.to_str())
            .unwrap_or("")
            .to_lowercase();

        let file_type = FileType::from_extension(&ext);

        // 选择解析器
        if file_type.is_docling_supported() {
            self.parse_with_docling(file_path, &file_type, path).await
        } else if file_type.is_cad_supported() {
            self.parse_with_cad(file_path, path)
        } else if file_type.is_bim_supported() {
            self.parse_with_bim(file_path, path)
        } else {
            Err(ParserError::UnsupportedType(ext))
        }
    }

    async fn parse_with_docling(
        &self,
        file_path: &str,
        file_type: &FileType,
        path: &Path,
    ) -> Result<ParsedDocument, ParserError> {
        // 解析文件
        let doc = self.docling.parse_file(file_path).await
            .map_err(|e| ParserError::DoclingError(e.to_string()))?;

        // 分块
        let doc_chunks = self.docling.chunk_document(&doc);

        // 转换为 ParsedChunk
        let chunks: Vec<ParsedChunk> = doc_chunks
            .iter()
            .map(|c| ParsedChunk {
                content: c.content.clone(),
                page: c.page,
                line_start: None,
                line_end: None,
                chunk_index: c.metadata.chunk_index,
                chunk_type: match c.chunk_type {
                    docling::ChunkType::Text => ChunkKind::Text,
                    docling::ChunkType::Table => ChunkKind::Table,
                    docling::ChunkType::Formula => ChunkKind::Formula,
                    docling::ChunkType::Image => ChunkKind::Image,
                },
                extra: c.metadata.table_structure.as_ref().map(|t| serde_json::to_value(t).ok()).flatten(),
            })
            .collect();

        // 元数据
        let metadata = DocumentMetadata {
            page_count: doc.metadata.page_count,
            file_size: doc.metadata.file_size,
            document_type: doc.metadata.document_type,
            ..Default::default()
        };

        // Docling 原始导出
        let docling_export = doc.exports.json_doc.clone();

        Ok(ParsedDocument {
            id: uuid::Uuid::new_v4().to_string(),
            filename: path.file_name().unwrap_or_default().to_string_lossy().to_string(),
            file_type: *file_type,
            title: None,
            chunks,
            metadata,
            docling_export,
        })
    }

    fn parse_with_cad(&self, file_path: &str, path: &Path) -> Result<ParsedDocument, ParserError> {
        // 使用 CAD 解析器
        todo!("CAD parsing implementation")
    }

    fn parse_with_bim(&self, file_path: &str, path: &Path) -> Result<ParsedDocument, ParserError> {
        // 使用 BIM 解析器
        todo!("BIM parsing implementation")
    }
}
```

- [ ] **Step 2: 提交**

```bash
git add core/src/parser/mod.rs
git commit -m "feat(parser): add unified document parser interface

- Add DocumentParser as facade for all parsers
- Auto-detect file type and route to appropriate parser
- Convert DoclingDocument to ParsedDocument format"
```

---

## Task 3: 知识库管理

**Files:**
- Create: `core/src/knowledge/mod.rs`
- Create: `core/src/knowledge/manager.rs`

- [ ] **Step 1: 创建知识库管理器**

```rust
// core/src/knowledge/manager.rs

use crate::parser::{DocumentParser, ParsedDocument};
use crate::vector::{MilvusVectorStore, DocumentChunk};
use crate::embedding::EmbeddingModel;
use crate::storage::Storage;
use std::sync::Arc;

pub struct KnowledgeBaseManager {
    parser: Arc<DocumentParser>,
    embedding_model: Arc<dyn EmbeddingModel>,
    vector_store: Arc<MilvusVectorStore>,
    storage: Arc<Storage>,
}

impl KnowledgeBaseManager {
    pub fn new(
        parser: Arc<DocumentParser>,
        embedding_model: Arc<dyn EmbeddingModel>,
        vector_store: Arc<MilvusVectorStore>,
        storage: Arc<Storage>,
    ) -> Self {
        Self {
            parser,
            embedding_model,
            vector_store,
            storage,
        }
    }

    /// 上传并处理文档
    pub async fn upload_document(
        &self,
        file_path: &str,
        category: Option<&str>,
    ) -> Result<UploadResult, KnowledgeError> {
        // 1. 解析文档
        let parsed = self.parser.parse(file_path).await
            .map_err(|e| KnowledgeError::ParseFailed(e.to_string()))?;

        // 2. 保存元数据到数据库
        let doc_id = parsed.id.clone();
        self.save_document_metadata(&parsed, category)?;

        // 3. 生成嵌入向量
        let contents: Vec<String> = parsed.chunks.iter().map(|c| c.content.clone()).collect();
        let embeddings = self.embedding_model.embed_batch(&contents).await
            .map_err(|e| KnowledgeError::EmbeddingFailed(e.to_string()))?;

        // 4. 转换为 DocumentChunk
        let chunks: Vec<DocumentChunk> = parsed.chunks.iter().enumerate().map(|(i, c)| {
            DocumentChunk {
                id: format!("{}-{}", doc_id, i),
                document_id: doc_id.clone(),
                content: c.content.clone(),
                metadata: crate::vector::ChunkMetadata {
                    page: c.page,
                    line_start: c.line_start,
                    line_end: c.line_end,
                    chunk_type: Some(c.chunk_type.to_string()),
                },
                chunk_index: c.chunk_index,
            }
        }).collect();

        // 5. 存储到向量数据库
        self.vector_store.insert(chunks, embeddings).await
            .map_err(|e| KnowledgeError::StorageFailed(e.to_string()))?;

        Ok(UploadResult {
            document_id: doc_id,
            chunks_count: parsed.chunks.len() as u32,
            file_type: parsed.file_type.to_string(),
        })
    }

    fn save_document_metadata(&self, doc: &ParsedDocument, category: Option<&str>) -> Result<(), KnowledgeError> {
        // 保存到 SQLite
        // ...
        Ok(())
    }
}

pub struct UploadResult {
    pub document_id: String,
    pub chunks_count: u32,
    pub file_type: String,
}

#[derive(Debug, thiserror::Error)]
pub enum KnowledgeError {
    #[error("解析失败: {0}")]
    ParseFailed(String),

    #[error("嵌入失败: {0}")]
    EmbeddingFailed(String),

    #[error("存储失败: {0}")]
    StorageFailed(String),

    #[error("文档不存在: {0}")]
    DocumentNotFound(String),
}
```

- [ ] **Step 2: 提交**

```bash
git add core/src/knowledge/
git commit -m "feat(knowledge): add knowledge base manager with Docling support"
```

---

## Task 4: Docling 环境检测与安装

**Files:**
- Create: `core/src/parser/docling/env.rs`

- [ ] **Step 1: Docling 环境检测**

```rust
// core/src/parser/docling/env.rs

use std::process::Command;

/// Docling 环境状态
#[derive(Debug, Clone)]
pub enum DoclingEnvStatus {
    /// 可用
    Available { version: String },
    /// 不可用
    Unavailable { reason: String },
}

/// 检测 Docling 环境
pub fn check_docling_env() -> DoclingEnvStatus {
    // 检查 Python
    let python = match Command::new("python3").arg("--version").output() {
        Ok(o) if o.status.success() => {
            String::from_utf8_lossy(&o.stdout).trim().to_string()
        }
        _ => return DoclingEnvStatus::Unavailable {
            reason: "Python 3 not found".to_string(),
        },
    };

    // 检查 Docling
    let output = Command::new("python3")
        .args(["-c", "import docling; print(docling.__version__)"])
        .output();

    match output {
        Ok(o) if o.status.success() => {
            let version = String::from_utf8_lossy(&o.stdout).trim().to_string();
            DoclingEnvStatus::Available { version }
        }
        Ok(o) => DoclingEnvStatus::Unavailable {
            reason: format!(
                "Docling import failed: {}",
                String::from_utf8_lossy(&o.stderr)
            ),
        },
        Err(e) => DoclingEnvStatus::Unavailable {
            reason: format!("Failed to run Python: {}", e),
        },
    }
}

/// 安装 Docling
pub fn install_docling() -> Result<(), String> {
    let output = Command::new("pip3")
        .args(["install", "docling"])
        .output()
        .map_err(|e| format!("Failed to run pip: {}", e))?;

    if output.status.success() {
        Ok(())
    } else {
        Err(String::from_utf8_lossy(&output.stderr).to_string())
    }
}
```

- [ ] **Step 2: 提交**

```bash
git add core/src/parser/docling/env.rs
git commit -m "feat(parser): add Docling environment detection and installation"
```

---

## Task 5: 更新 lib.rs

- [ ] **Step 1: 更新 core/src/lib.rs**

```rust
pub mod parser;
pub mod knowledge;
// ... existing modules ...
```

- [ ] **Step 2: 添加依赖**

```toml
# 添加到 Cargo.toml [dependencies]
tokio = { version = "1.38", features = ["full"] }
uuid = { version = "1.8", features = ["v4", "serde"] }
thiserror = "1.0"
```

- [ ] **Step 3: 构建测试**

```bash
cd core && cargo build
```

- [ ] **Step 4: 提交**

```bash
git add core/
git commit -m "feat: integrate parser with Docling and knowledge base modules"
```

---

## 验收检查

- [ ] `cargo build` 成功
- [ ] Docling 桥接层可工作（需 Python 环境）
- [ ] PDF 解析器可工作
- [ ] DOCX/PPTX/XLSX 解析器可工作
- [ ] 智能分块正常
- [ ] 知识库管理接口完整
- [ ] 环境检测提示友好

---

## Docling 安装说明

用户首次使用文档解析功能时，如未检测到 Docling：

```bash
# 自动安装
pip3 install docling

# 或使用 uv（更快）
uv pip install docling
```

**系统需求：**
- Python 3.10+
- 建议 8GB+ RAM（OCR 和 VLM 模型需要）
- 可选：GPU 加速（CUDA）

---

## 参考资料

- [Docling GitHub](https://github.com/docling-project/docling)
- [Docling 文档](https://docling-project.github.io/docling/)
- [Docling v2 变更](https://docling-project.github.io/docling/v2/)
- [arXiv 技术报告](https://arxiv.org/abs/2408.09869)

**计划创建日期**: 2026-04-22
**更新日期**: 2026-04-22
**更新内容**: 新增 Docling 集成，替代原有 Rust 原生 PDF 解析方案
