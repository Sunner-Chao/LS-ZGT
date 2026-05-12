package com.luanshuai.agent.service.search;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.*;
import java.util.regex.Pattern;

/**
 * 文档解析服务 (Docling 风格)
 *
 * 支持从 PDF/HTML/Markdown 等格式中提取结构化内容：
 * - 标题层级 (H1, H2, H3 ...)
 * - 表格结构
 * - 图片引用
 * - 代码块
 * - 段落文本
 *
 * 使用 PDF Extract Kit (阿里开源) 进行底层解析
 */
@Service
public class DocumentParserService {

    private static final Logger log = LoggerFactory.getLogger(DocumentParserService.class);

    @Autowired
    private WebClient.Builder webClientBuilder;

    @Value("${app.pdf-extract-kit-url:http://localhost:8081}")
    private String pdfExtractKitUrl;

    // 标题层级对应的 Markdown 符号
    private static final Map<Integer, String> HEADING_PREFIXES = Map.of(
            1, "#",
            2, "##",
            3, "###",
            4, "####",
            5, "#####"
    );

    // 每 N 个字符切分一个 chunk
    private static final int DEFAULT_CHUNK_SIZE = 500;
    private static final int DEFAULT_CHUNK_OVERLAP = 50;

    // 标题模式
    private static final Pattern HEADING_PATTERN = Pattern.compile(
            "^#{1,6}\\s+(.+)$",
            Pattern.MULTILINE
    );

    // 表格行分隔符
    private static final Pattern TABLE_ROW_PATTERN = Pattern.compile(
            "\\|[^\\n]+\\|",
            Pattern.MULTILINE
    );

    /**
     * 解析 PDF 文件 (通过 PDF Extract Kit API)
     *
     * @param filePath PDF 文件路径
     * @return 解析后的结构化文档列表
     */
    public List<StructuredDocument> parsePdf(String filePath) {
        log.info("[Parser] Parsing PDF: {}", filePath);

        try {
            Map<String, Object> payload = Map.of(
                    "file_path", filePath,
                    "strategy", "layout"
            );

            List<?> response = webClientBuilder.build()
                    .post()
                    .uri(pdfExtractKitUrl + "/parse")
                    .bodyValue(payload)
                    .retrieve()
                    .bodyToFlux(Object.class)
                    .collectList()
                    .block();

            List<StructuredDocument> docs = new ArrayList<>();
            if (response != null) {
                for (Object item : response) {
                    if (item instanceof Map) {
                        Map<String, Object> block = (Map<String, Object>) item;
                        docs.addAll(convertToStructuredDocs(block));
                    }
                }
            }

            log.info("[Parser] PDF parsed: {} blocks from {}", docs.size(), filePath);
            return docs;

        } catch (Exception e) {
            log.error("[Parser] Failed to parse PDF {}: {}", filePath, e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * 解析 HTML 内容
     */
    public List<StructuredDocument> parseHtml(String htmlContent, String sourceUrl) {
        List<StructuredDocument> docs = new ArrayList<>();

        try {
            // 提取标题
            List<String[]> headings = new ArrayList<>();
            java.util.regex.Matcher headingMatcher = HEADING_PATTERN.matcher(htmlContent);
            while (headingMatcher.find()) {
                int level = headingMatcher.group().indexOf(' ');
                String text = headingMatcher.group(1);
                headings.add(new String[]{String.valueOf(level), text});
            }

            // 提取表格
            List<String> tables = new ArrayList<>();
            java.util.regex.Matcher tableMatcher = TABLE_ROW_PATTERN.matcher(htmlContent);
            StringBuilder currentTable = new StringBuilder();
            while (tableMatcher.find()) {
                if (currentTable.length() > 0 && !tableMatcher.group().trim().startsWith("|")) {
                    // 新表格开始
                    if (currentTable.length() > 0) {
                        tables.add(currentTable.toString());
                        currentTable = new StringBuilder();
                    }
                }
                currentTable.append(tableMatcher.group()).append("\n");
            }
            if (currentTable.length() > 0) {
                tables.add(currentTable.toString());
            }

            // 提取正文 (移除 HTML 标签)
            String plainText = htmlContent.replaceAll("<[^>]+>", " ")
                    .replaceAll("\\s+", " ")
                    .trim();

            // 清理 Markdown 格式
            plainText = plainText.replaceAll("\\*\\*(.+?)\\*\\*", "$1");
            plainText = plainText.replaceAll("__(.+?)__", "$1");
            plainText = plainText.replaceAll("\\*(.+?)\\*", "$1");
            plainText = plainText.replaceAll("_(.+?)_", "$1");

            // 构建结构化文档
            int order = 0;
            for (String[] heading : headings) {
                int level = Integer.parseInt(heading[0]);
                String headingText = heading[1];
                docs.add(new StructuredDocument(
                        "heading_" + (order++),
                        headingText,
                        "heading",
                        level,
                        sourceUrl,
                        null,
                        Map.of("source_url", sourceUrl)
                ));
            }

            for (String table : tables) {
                docs.add(new StructuredDocument(
                        "table_" + (order++),
                        table,
                        "table",
                        0,
                        sourceUrl,
                        null,
                        Map.of("source_url", sourceUrl)
                ));
            }

            if (!plainText.isEmpty()) {
                docs.add(new StructuredDocument(
                        "content_" + (order++),
                        plainText,
                        "text",
                        0,
                        sourceUrl,
                        null,
                        Map.of("source_url", sourceUrl)
                ));
            }

        } catch (Exception e) {
            log.error("[Parser] Failed to parse HTML: {}", e.getMessage());
        }

        return docs;
    }

    /**
     * 解析 Markdown 内容
     */
    public List<StructuredDocument> parseMarkdown(String markdownContent, String sourceUrl) {
        List<StructuredDocument> docs = new ArrayList<>();
        String[] lines = markdownContent.split("\n");

        String currentSection = "";
        int currentHeadingLevel = 0;
        StringBuilder currentContent = new StringBuilder();
        int order = 0;

        for (String line : lines) {
            if (HEADING_PATTERN.matcher(line).matches()) {
                // 保存上一个段落
                if (currentContent.length() > 0) {
                    String text = currentContent.toString().trim();
                    if (!text.isEmpty()) {
                        docs.add(new StructuredDocument(
                                "chunk_" + (order++),
                                text,
                                currentSection.isEmpty() ? "text" : currentSection,
                                currentHeadingLevel,
                                sourceUrl,
                                null,
                                Map.of("section", currentSection)
                        ));
                    }
                    currentContent = new StringBuilder();
                }

                // 解析标题
                int level = line.indexOf(' ');
                currentHeadingLevel = level;
                currentSection = line.substring(level + 1).trim();

                docs.add(new StructuredDocument(
                        "heading_" + (order++),
                        currentSection,
                        "heading",
                        level,
                        sourceUrl,
                        null,
                        Map.of()
                ));

            } else if (line.startsWith("|")) {
                // 表格行
                currentContent.append(line).append("\n");
            } else {
                // 普通文本
                currentContent.append(line).append(" ");
            }
        }

        // 保存最后一段
        if (currentContent.length() > 0) {
            String text = currentContent.toString().trim();
            if (!text.isEmpty()) {
                docs.add(new StructuredDocument(
                        "chunk_" + (order++),
                        text,
                        currentSection.isEmpty() ? "text" : currentSection,
                        currentHeadingLevel,
                        sourceUrl,
                        null,
                        Map.of("section", currentSection)
                ));
            }
        }

        return docs;
    }

    /**
     * 文本分块 (Chunking)
     *
     * 将长文本按语义边界切分为小块，支持：
     * - 按段落分割
     * - 按固定长度 + 重叠切分
     * - 保留上下文信息
     *
     * @param text 输入文本
     * @param chunkSize 每块字符数
     * @param overlap 重叠字符数
     * @param metadata 额外元数据
     * @return 分块后的文档列表
     */
    public List<Map<String, Object>> chunkText(String text, int chunkSize, int overlap,
                                               Map<String, Object> metadata) {
        if (text == null || text.trim().isEmpty()) {
            return Collections.emptyList();
        }

        List<Map<String, Object>> chunks = new ArrayList<>();
        int textLen = text.length();

        // 按段落分割
        List<String> paragraphs = Arrays.asList(text.split("\n\n+"));

        if (paragraphs.size() <= 2) {
            // 段落太少，直接按固定长度切分
            chunks.addAll(chunkByLength(text, chunkSize, overlap, metadata));
        } else {
            // 按段落组合
            StringBuilder currentChunk = new StringBuilder();
            int currentLen = 0;

            for (String para : paragraphs) {
                int paraLen = para.length();

                if (currentLen + paraLen + 2 <= chunkSize) {
                    if (currentChunk.length() > 0) {
                        currentChunk.append("\n\n");
                    }
                    currentChunk.append(para);
                    currentLen += paraLen + 2;
                } else {
                    // 当前块已满，保存
                    if (currentChunk.length() > 0) {
                        chunks.add(buildChunk(currentChunk.toString(), metadata));
                    }

                    // 重叠处理
                    String overlapText = currentChunk.length() > overlap
                            ? currentChunk.substring(currentChunk.length() - overlap)
                            : currentChunk.toString();

                    currentChunk = new StringBuilder(overlapText);
                    if (currentChunk.length() > 0) {
                        currentChunk.append("\n\n");
                    }
                    currentChunk.append(para);
                    currentLen = currentChunk.length();
                }
            }

            // 保存最后一块
            if (currentChunk.length() > 0) {
                chunks.add(buildChunk(currentChunk.toString(), metadata));
            }
        }

        log.debug("[Parser] chunked text ({} chars) into {} chunks", textLen, chunks.size());
        return chunks;
    }

    /**
     * 默认分块参数
     */
    public List<Map<String, Object>> chunkText(String text, Map<String, Object> metadata) {
        return chunkText(text, DEFAULT_CHUNK_SIZE, DEFAULT_CHUNK_OVERLAP, metadata);
    }

    /**
     * 按固定长度切分
     */
    private List<Map<String, Object>> chunkByLength(String text, int chunkSize, int overlap,
                                                    Map<String, Object> metadata) {
        List<Map<String, Object>> chunks = new ArrayList<>();

        for (int i = 0; i < text.length(); i += chunkSize - overlap) {
            int end = Math.min(i + chunkSize, text.length());
            String chunkText = text.substring(i, end);
            chunks.add(buildChunk(chunkText, metadata));
            if (end >= text.length()) break;
        }

        return chunks;
    }

    /**
     * 构建分块结果
     */
    private Map<String, Object> buildChunk(String text, Map<String, Object> metadata) {
        Map<String, Object> chunk = new HashMap<>(metadata);
        chunk.put("text", text.trim());
        chunk.put("chunk_length", text.length());
        return chunk;
    }

    /**
     * 将 PDF Extract Kit 返回转换为结构化文档
     */
    private List<StructuredDocument> convertToStructuredDocs(Map<String, Object> block) {
        List<StructuredDocument> docs = new ArrayList<>();

        String type = String.valueOf(block.getOrDefault("type", "text"));
        Object content = block.get("content");

        if (content == null) {
            return docs;
        }

        String text;
        if (content instanceof String) {
            text = (String) content;
        } else if (content instanceof List) {
            text = String.join("\n", (List<String>) content);
        } else {
            text = content.toString();
        }

        if (text.trim().isEmpty()) {
            return docs;
        }

        String docId = "block_" + block.getOrDefault("index", docs.size());
        int level = 0;

        if ("title".equals(type) || "heading".equals(type)) {
            level = ((Number) block.getOrDefault("level", 1)).intValue();
        }

        docs.add(new StructuredDocument(
                docId,
                text,
                type,
                level,
                String.valueOf(block.getOrDefault("source", "")),
                block.get("bbox"),
                Map.of("page", block.getOrDefault("page", 0))
        ));

        return docs;
    }

    /**
     * 结构化文档数据类
     */
    public static class StructuredDocument {
        private final String id;
        private final String text;
        private final String type;
        private final int headingLevel;
        private final String sourceUrl;
        private final Object bbox;
        private final Map<String, Object> metadata;

        public StructuredDocument(String id, String text, String type, int headingLevel,
                                  String sourceUrl, Object bbox, Map<String, Object> metadata) {
            this.id = id;
            this.text = text;
            this.type = type;
            this.headingLevel = headingLevel;
            this.sourceUrl = sourceUrl;
            this.bbox = bbox;
            this.metadata = metadata;
        }

        public String getId() { return id; }
        public String getText() { return text; }
        public String getType() { return type; }
        public int getHeadingLevel() { return headingLevel; }
        public String getSourceUrl() { return sourceUrl; }
        public Object getBbox() { return bbox; }
        public Map<String, Object> getMetadata() { return metadata; }

        public Map<String, Object> toMap() {
            Map<String, Object> map = new HashMap<>(metadata);
            map.put("id", id);
            map.put("text", text);
            map.put("type", type);
            map.put("heading_level", headingLevel);
            map.put("source_url", sourceUrl);
            return map;
        }
    }
}
