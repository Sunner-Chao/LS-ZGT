package com.luanshuai.agent.service;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.util.LinkedHashMap;
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
}
