package com.luanshuai.agent.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;

/**
 * Tesseract OCR 兜底服务
 *
 * 当 VLM OCR 不可用或失败时，使用传统 Tesseract 进行文字识别。
 * Tesseract 特别适合：清晰的印刷体、截图、简单排版的图片。
 */
@Service
public class TesseractOcrService {

    private static final Logger log = LoggerFactory.getLogger(TesseractOcrService.class);

    private final net.sourceforge.tess4j.Tesseract tesseract;

    public TesseractOcrService(
            @Value("${app.tesseract.datapath:/usr/local/share/tessdata}") String datapath,
            @Value("${app.tesseract.language:chi_sim+eng}") String language) {

        this.tesseract = new net.sourceforge.tess4j.Tesseract();
        this.tesseract.setDatapath(datapath);
        this.tesseract.setLanguage(language);
        // 使用 Tesseract 默认的分页模式（自动检测）
        log.info("[Tesseract] 初始化完成: datapath={}, language={}", datapath, language);
    }

    /**
     * OCR 图片（BufferedImage）
     */
    public String doOCR(BufferedImage image) throws IOException {
        try {
            return tesseract.doOCR(image);
        } catch (Exception e) {
            throw new IOException("Tesseract OCR 失败: " + e.getMessage(), e);
        }
    }

    /**
     * OCR 图片文件
     */
    public String ocrImage(File imageFile) throws IOException {
        log.info("[Tesseract] 开始 OCR: {}", imageFile.getName());
        try {
            BufferedImage image = ImageIO.read(imageFile);
            if (image == null) {
                throw new IOException("无法读取图片: " + imageFile.getAbsolutePath());
            }
            String text = tesseract.doOCR(image);
            log.info("[Tesseract] OCR 完成: {} ({} chars)", imageFile.getName(), text.length());
            return text;
        } catch (Exception e) {
            log.error("[Tesseract] OCR 失败: {}", e.getMessage());
            throw new IOException("Tesseract OCR 失败: " + e.getMessage(), e);
        }
    }

    /**
     * OCR 扫描版 PDF（逐页渲染后调用 Tesseract）
     */
    public String ocrPdf(File pdfFile) throws IOException {
        log.info("[Tesseract] 开始 PDF OCR: {}", pdfFile.getName());
        StringBuilder result = new StringBuilder();

        try (org.apache.pdfbox.pdmodel.PDDocument document = org.apache.pdfbox.Loader.loadPDF(pdfFile)) {
            int pageCount = document.getNumberOfPages();
            org.apache.pdfbox.rendering.PDFRenderer renderer = new org.apache.pdfbox.rendering.PDFRenderer(document);
            org.apache.pdfbox.text.PDFTextStripper stripper = new org.apache.pdfbox.text.PDFTextStripper();

            for (int i = 1; i <= pageCount; i++) {
                stripper.setStartPage(i);
                stripper.setEndPage(i);
                String text = stripper.getText(document);

                if (text == null || text.trim().isEmpty()) {
                    // 文本为空 → 渲染图片 → OCR
                    try {
                        BufferedImage image = renderer.renderImageWithDPI(i - 1, 300);
                        String ocrText = tesseract.doOCR(image);
                        result.append(ocrText).append("\n");
                        log.debug("[Tesseract] PDF 第 {} 页 OCR 完成", i);
                    } catch (Exception e) {
                        log.warn("[Tesseract] PDF 第 {} 页 OCR 失败: {}", i, e.getMessage());
                    }
                } else {
                    result.append(text).append("\n");
                }
            }
        }

        String output = result.toString();
        log.info("[Tesseract] PDF OCR 完成: {} ({} chars)", pdfFile.getName(), output.length());
        return output;
    }
}
