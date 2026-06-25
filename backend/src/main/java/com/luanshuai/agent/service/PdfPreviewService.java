package com.luanshuai.agent.service;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * PDF 页面预览渲染服务
 *
 * 使用 Apache PDFBox 将 PDF 指定页面渲染为 PNG 图片。
 * 内置 LRU 缓存避免重复渲染。
 */
@Service
public class PdfPreviewService {

    private static final Logger log = LoggerFactory.getLogger(PdfPreviewService.class);

    /** 渲染 DPI（150 平衡清晰度和大小） */
    private static final float RENDER_DPI = 150;

    /** LRU 缓存最大条目数 */
    private static final int CACHE_MAX = 100;

    @Autowired(required = false)
    private TesseractOcrService tesseractOcrService;

    /** LRU 缓存：key = "filePath:page"，value = PNG bytes */
    private final LinkedHashMap<String, CacheEntry> cache = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, CacheEntry> eldest) {
            return size() > CACHE_MAX;
        }
    };

    /** 缓存条目 */
    private record CacheEntry(byte[] pngBytes, long createdAt) {}

    /**
     * 渲染 PDF 指定页面为 PNG 图片
     *
     * @param pdfPath PDF 文件绝对路径
     * @param page    页码（1-based）
     * @return PNG 图片字节数组
     * @throws IOException 文件不存在或渲染失败
     */
    public byte[] renderPage(String pdfPath, int page) throws IOException {
        String cacheKey = pdfPath + ":" + page;

        // 检查缓存
        synchronized (cache) {
            CacheEntry entry = cache.get(cacheKey);
            if (entry != null) {
                log.debug("[PdfPreview] 缓存命中: {}", cacheKey);
                return entry.pngBytes();
            }
        }

        // 渲染
        File pdfFile = new File(pdfPath);
        if (!pdfFile.exists()) {
            throw new IOException("PDF 文件不存在: " + pdfPath);
        }

        byte[] pngBytes;
        try (PDDocument document = Loader.loadPDF(pdfFile)) {
            int totalPages = document.getNumberOfPages();
            if (page < 1 || page > totalPages) {
                throw new IOException(
                    String.format("页码超出范围: page=%d, totalPages=%d, file=%s", page, totalPages, pdfPath)
                );
            }

            PDFRenderer renderer = new PDFRenderer(document);
            // PDFBox 页码是 0-based
            BufferedImage image = renderer.renderImageWithDPI(page - 1, RENDER_DPI);

            pngBytes = toPngBytes(image);
            log.info("[PdfPreview] 渲染完成: {} page={} size={}KB", pdfPath, page, pngBytes.length / 1024);
        }

        // 写入缓存
        synchronized (cache) {
            cache.put(cacheKey, new CacheEntry(pngBytes, System.currentTimeMillis()));
        }

        return pngBytes;
    }

    /**
     * 渲染 PDF 指定页面，并尽量用红框标出命中的原文片段。
     *
     * 优先使用 PDF 文本层精确定位；若扫描件没有文本层，则用 markdown 同页文本顺序做近似定位。
     */
    public byte[] renderPageWithHighlight(
            String pdfPath,
            int page,
            String highlightText,
            String markdownPageText) throws IOException {
        if (isBlank(highlightText)) {
            return renderPage(pdfPath, page);
        }

        File pdfFile = new File(pdfPath);
        if (!pdfFile.exists()) {
            throw new IOException("PDF 文件不存在: " + pdfPath);
        }

        try (PDDocument document = Loader.loadPDF(pdfFile)) {
            int totalPages = document.getNumberOfPages();
            if (page < 1 || page > totalPages) {
                throw new IOException(
                    String.format("页码超出范围: page=%d, totalPages=%d, file=%s", page, totalPages, pdfPath)
                );
            }

            PDFRenderer renderer = new PDFRenderer(document);
            BufferedImage image = renderer.renderImageWithDPI(page - 1, RENDER_DPI);

            List<HighlightBox> boxes = findTextLayerHighlightBoxes(document, page, highlightText, image);
            if (boxes.isEmpty()) {
                boxes = findOcrHighlightBoxes(image, highlightText);
            }
            if (boxes.isEmpty()) {
                boxes = estimateHighlightBoxesFromMarkdown(highlightText, markdownPageText, image);
            }
            drawHighlightBoxes(image, boxes);

            log.info("[PdfPreview] 高亮渲染完成: {} page={} boxes={} size={}KB",
                    pdfPath, page, boxes.size(), image.getWidth() * image.getHeight() / 1024);
            return toPngBytes(image);
        }
    }

    private List<HighlightBox> findOcrHighlightBoxes(BufferedImage image, String highlightText) {
        if (tesseractOcrService == null || isBlank(highlightText)) {
            return List.of();
        }
        try {
            List<TesseractOcrService.OcrWord> words = tesseractOcrService.getWords(image);
            if (words.isEmpty()) {
                return List.of();
            }

            NormalizedOcrText pageText = normalizeOcrWords(words);
            String target = normalizeForSearch(highlightText);
            MatchRange match = findBestMatch(pageText.text(), target);
            if (match == null) {
                return List.of();
            }

            int startWord = pageText.normalizedToWordIndex().get(match.start());
            int endWord = pageText.normalizedToWordIndex().get(match.end() - 1);
            if (endWord < startWord) {
                return List.of();
            }

            List<TesseractOcrService.OcrWord> matched = new ArrayList<>();
            for (int i = startWord; i <= endWord && i < words.size(); i++) {
                TesseractOcrService.OcrWord word = words.get(i);
                if (!isBlank(word.text()) && word.confidence() > 15) {
                    matched.add(word);
                }
            }
            List<HighlightBox> boxes = mergeOcrWordsIntoLineBoxes(matched, image.getWidth(), image.getHeight());
            log.info("[PdfPreview] OCR 高亮定位: words={}, matchedWords={}, boxes={}",
                    words.size(), matched.size(), boxes.size());
            return boxes;
        } catch (Exception e) {
            log.debug("[PdfPreview] OCR 高亮定位失败: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * 获取 PDF 总页数
     */
    public int getTotalPages(String pdfPath) throws IOException {
        File pdfFile = new File(pdfPath);
        if (!pdfFile.exists()) {
            throw new IOException("PDF 文件不存在: " + pdfPath);
        }
        try (PDDocument document = Loader.loadPDF(pdfFile)) {
            return document.getNumberOfPages();
        }
    }

    /**
     * BufferedImage 转 PNG 字节数组
     */
    private byte[] toPngBytes(BufferedImage image) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(image, "PNG", baos);
        return baos.toByteArray();
    }

    private List<HighlightBox> findTextLayerHighlightBoxes(
            PDDocument document,
            int page,
            String highlightText,
            BufferedImage image) {
        try {
            PositionedTextStripper stripper = new PositionedTextStripper();
            stripper.setSortByPosition(true);
            stripper.setStartPage(page);
            stripper.setEndPage(page);
            stripper.getText(document);

            List<PositionedChar> chars = stripper.getChars();
            if (chars.isEmpty()) {
                return List.of();
            }

            NormalizedText pageText = normalizePositionedChars(chars);
            String target = normalizeForSearch(highlightText);
            MatchRange match = findBestMatch(pageText.text(), target);
            if (match == null) {
                return List.of();
            }

            int startCharIndex = pageText.normalizedToCharIndex().get(match.start());
            int endCharIndex = pageText.normalizedToCharIndex().get(match.end() - 1);
            if (endCharIndex < startCharIndex) {
                return List.of();
            }

            PDPage pdPage = document.getPage(page - 1);
            float pageWidth = pdPage.getCropBox().getWidth();
            float pageHeight = pdPage.getCropBox().getHeight();
            double scaleX = image.getWidth() / Math.max(1.0, pageWidth);
            double scaleY = image.getHeight() / Math.max(1.0, pageHeight);

            List<PositionedChar> matched = new ArrayList<>();
            for (int i = startCharIndex; i <= endCharIndex && i < chars.size(); i++) {
                PositionedChar ch = chars.get(i);
                if (!isBlank(ch.value())) {
                    matched.add(ch);
                }
            }
            return mergeCharsIntoLineBoxes(matched, scaleX, scaleY, image.getWidth(), image.getHeight());
        } catch (Exception e) {
            log.debug("[PdfPreview] 文本层高亮定位失败: {}", e.getMessage());
            return List.of();
        }
    }

    private List<HighlightBox> estimateHighlightBoxesFromMarkdown(
            String highlightText,
            String markdownPageText,
            BufferedImage image) {
        if (isBlank(highlightText) || isBlank(markdownPageText)) {
            return List.of();
        }
        String pageText = normalizeForSearch(markdownPageText);
        String target = normalizeForSearch(highlightText);
        MatchRange match = findBestMatch(pageText, target);
        if (match == null || pageText.isEmpty()) {
            return List.of();
        }

        double ratio = Math.max(0.0, Math.min(1.0, match.start() / (double) Math.max(1, pageText.length())));
        int marginX = Math.max(24, (int) Math.round(image.getWidth() * 0.08));
        int usableHeight = Math.max(1, (int) Math.round(image.getHeight() * 0.82));
        int topMargin = Math.max(24, (int) Math.round(image.getHeight() * 0.08));
        int height = Math.max(54, (int) Math.round(image.getHeight() * 0.14));
        int y = topMargin + (int) Math.round(ratio * usableHeight) - height / 2;
        y = Math.max(8, Math.min(image.getHeight() - height - 8, y));
        return List.of(new HighlightBox(marginX, y, image.getWidth() - marginX * 2, height));
    }

    private List<HighlightBox> mergeCharsIntoLineBoxes(
            List<PositionedChar> chars,
            double scaleX,
            double scaleY,
            int imageWidth,
            int imageHeight) {
        if (chars.isEmpty()) {
            return List.of();
        }
        chars.sort(Comparator
                .comparingDouble(PositionedChar::y)
                .thenComparingDouble(PositionedChar::x));

        List<List<PositionedChar>> lines = new ArrayList<>();
        for (PositionedChar ch : chars) {
            List<PositionedChar> line = lines.isEmpty() ? null : lines.get(lines.size() - 1);
            if (line == null || Math.abs(line.get(0).y() - ch.y()) > 4.0) {
                line = new ArrayList<>();
                lines.add(line);
            }
            line.add(ch);
        }

        List<HighlightBox> boxes = new ArrayList<>();
        for (List<PositionedChar> line : lines) {
            double minX = Double.MAX_VALUE;
            double minY = Double.MAX_VALUE;
            double maxX = 0;
            double maxY = 0;
            for (PositionedChar ch : line) {
                double x1 = ch.x() * scaleX;
                double x2 = (ch.x() + Math.max(1.0, ch.width())) * scaleX;
                double y1 = (ch.y() - Math.max(1.0, ch.height())) * scaleY;
                double y2 = (ch.y() + Math.max(1.0, ch.height()) * 0.25) * scaleY;
                minX = Math.min(minX, x1);
                maxX = Math.max(maxX, x2);
                minY = Math.min(minY, y1);
                maxY = Math.max(maxY, y2);
            }
            int pad = 6;
            int x = clamp((int) Math.floor(minX) - pad, 0, imageWidth - 1);
            int y = clamp((int) Math.floor(minY) - pad, 0, imageHeight - 1);
            int w = clamp((int) Math.ceil(maxX - minX) + pad * 2, 1, imageWidth - x);
            int h = clamp((int) Math.ceil(maxY - minY) + pad * 2, 1, imageHeight - y);
            boxes.add(new HighlightBox(x, y, w, h));
        }
        return boxes;
    }

    private void drawHighlightBoxes(BufferedImage image, List<HighlightBox> boxes) {
        if (boxes == null || boxes.isEmpty()) {
            return;
        }
        Graphics2D g = image.createGraphics();
        try {
            g.setColor(new Color(220, 38, 38, 72));
            for (HighlightBox box : boxes) {
                g.fillRect(box.x(), box.y(), box.width(), box.height());
            }
            g.setColor(new Color(220, 38, 38));
            g.setStroke(new BasicStroke(Math.max(3.0f, image.getWidth() / 360.0f)));
            for (HighlightBox box : boxes) {
                g.drawRect(box.x(), box.y(), Math.max(1, box.width()), Math.max(1, box.height()));
            }
        } finally {
            g.dispose();
        }
    }

    private NormalizedText normalizePositionedChars(List<PositionedChar> chars) {
        StringBuilder normalized = new StringBuilder();
        List<Integer> map = new ArrayList<>();
        for (int i = 0; i < chars.size(); i++) {
            String value = normalizeForSearch(chars.get(i).value());
            if (!value.isEmpty()) {
                normalized.append(value);
                for (int j = 0; j < value.length(); j++) {
                    map.add(i);
                }
            }
        }
        return new NormalizedText(normalized.toString(), map);
    }

    private NormalizedOcrText normalizeOcrWords(List<TesseractOcrService.OcrWord> words) {
        StringBuilder normalized = new StringBuilder();
        List<Integer> map = new ArrayList<>();
        for (int i = 0; i < words.size(); i++) {
            String value = normalizeForSearch(words.get(i).text());
            if (!value.isEmpty()) {
                normalized.append(value);
                for (int j = 0; j < value.length(); j++) {
                    map.add(i);
                }
            }
        }
        return new NormalizedOcrText(normalized.toString(), map);
    }

    private List<HighlightBox> mergeOcrWordsIntoLineBoxes(
            List<TesseractOcrService.OcrWord> words,
            int imageWidth,
            int imageHeight) {
        if (words.isEmpty()) {
            return List.of();
        }
        words.sort(Comparator
                .comparingInt(TesseractOcrService.OcrWord::y)
                .thenComparingInt(TesseractOcrService.OcrWord::x));

        List<List<TesseractOcrService.OcrWord>> lines = new ArrayList<>();
        for (TesseractOcrService.OcrWord word : words) {
            List<TesseractOcrService.OcrWord> line = lines.isEmpty() ? null : lines.get(lines.size() - 1);
            int threshold = Math.max(8, word.height() / 2);
            if (line == null || Math.abs(lineCenterY(line) - wordCenterY(word)) > threshold) {
                line = new ArrayList<>();
                lines.add(line);
            }
            line.add(word);
        }

        List<HighlightBox> boxes = new ArrayList<>();
        for (List<TesseractOcrService.OcrWord> line : lines) {
            int minX = Integer.MAX_VALUE;
            int minY = Integer.MAX_VALUE;
            int maxX = 0;
            int maxY = 0;
            for (TesseractOcrService.OcrWord word : line) {
                minX = Math.min(minX, word.x());
                minY = Math.min(minY, word.y());
                maxX = Math.max(maxX, word.x() + word.width());
                maxY = Math.max(maxY, word.y() + word.height());
            }
            int pad = Math.max(8, imageWidth / 220);
            int x = clamp(minX - pad, 0, imageWidth - 1);
            int y = clamp(minY - pad, 0, imageHeight - 1);
            int w = clamp(maxX - minX + pad * 2, 1, imageWidth - x);
            int h = clamp(maxY - minY + pad * 2, 1, imageHeight - y);
            boxes.add(new HighlightBox(x, y, w, h));
        }
        return boxes;
    }

    private int lineCenterY(List<TesseractOcrService.OcrWord> line) {
        int minY = Integer.MAX_VALUE;
        int maxY = 0;
        for (TesseractOcrService.OcrWord word : line) {
            minY = Math.min(minY, word.y());
            maxY = Math.max(maxY, word.y() + word.height());
        }
        return (minY + maxY) / 2;
    }

    private int wordCenterY(TesseractOcrService.OcrWord word) {
        return word.y() + word.height() / 2;
    }

    private MatchRange findBestMatch(String pageText, String target) {
        if (isBlank(pageText) || isBlank(target)) {
            return null;
        }

        String clippedTarget = target.length() > 260 ? target.substring(0, 260) : target;
        int exact = pageText.indexOf(clippedTarget);
        if (exact >= 0) {
            return new MatchRange(exact, exact + clippedTarget.length());
        }

        int[] windowSizes = {120, 90, 64, 42, 28, 18};
        for (int window : windowSizes) {
            if (clippedTarget.length() < window) {
                continue;
            }
            int step = Math.max(1, window / 3);
            for (int start = 0; start + window <= clippedTarget.length(); start += step) {
                String anchor = clippedTarget.substring(start, start + window);
                int pos = pageText.indexOf(anchor);
                if (pos >= 0) {
                    return new MatchRange(pos, pos + window);
                }
            }
        }

        return null;
    }

    private String normalizeForSearch(String text) {
        if (text == null) {
            return "";
        }
        String cleaned = text
                .replaceAll("\\[PAGE:\\s*\\d+\\]", "")
                .replace("...", "")
                .replace("…", "");
        StringBuilder sb = new StringBuilder();
        cleaned.codePoints().forEach(cp -> {
            if (Character.isWhitespace(cp)) {
                return;
            }
            int type = Character.getType(cp);
            if (type == Character.CONNECTOR_PUNCTUATION
                    || type == Character.DASH_PUNCTUATION
                    || type == Character.START_PUNCTUATION
                    || type == Character.END_PUNCTUATION
                    || type == Character.OTHER_PUNCTUATION
                    || cp == '#' || cp == '*' || cp == '`' || cp == '|' || cp == '>') {
                return;
            }
            sb.appendCodePoint(Character.toLowerCase(cp));
        });
        return sb.toString();
    }

    private boolean isBlank(String text) {
        return text == null || text.trim().isEmpty();
    }

    private int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private record PositionedChar(String value, double x, double y, double width, double height) {}
    private record HighlightBox(int x, int y, int width, int height) {}
    private record NormalizedText(String text, List<Integer> normalizedToCharIndex) {}
    private record NormalizedOcrText(String text, List<Integer> normalizedToWordIndex) {}
    private record MatchRange(int start, int end) {}

    private static class PositionedTextStripper extends PDFTextStripper {
        private final List<PositionedChar> chars = new ArrayList<>();

        PositionedTextStripper() throws IOException {
            super();
        }

        List<PositionedChar> getChars() {
            return chars;
        }

        @Override
        protected void processTextPosition(TextPosition text) {
            String unicode = text.getUnicode();
            if (unicode == null || unicode.isEmpty()) {
                return;
            }
            int charCount = Math.max(1, unicode.codePointCount(0, unicode.length()));
            double charWidth = text.getWidthDirAdj() / charCount;
            double x = text.getXDirAdj();
            double y = text.getYDirAdj();
            double height = text.getHeightDir();

            int offset = 0;
            for (int i = 0; i < charCount; i++) {
                int cp = unicode.codePointAt(offset);
                String value = new String(Character.toChars(cp));
                chars.add(new PositionedChar(value, x + i * charWidth, y, charWidth, height));
                offset += Character.charCount(cp);
            }
        }
    }
}
