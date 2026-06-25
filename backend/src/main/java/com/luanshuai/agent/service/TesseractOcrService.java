package com.luanshuai.agent.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

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
     * OCR 图片并返回文字框坐标。坐标基于传入 BufferedImage 的像素坐标系。
     */
    public List<OcrWord> getWords(BufferedImage image) throws IOException {
        if (image == null) {
            return List.of();
        }
        try {
            List<net.sourceforge.tess4j.Word> words = tesseract.getWords(
                    image,
                    net.sourceforge.tess4j.ITessAPI.TessPageIteratorLevel.RIL_WORD
            );
            List<OcrWord> result = new ArrayList<>();
            for (net.sourceforge.tess4j.Word word : words) {
                if (word == null || word.getText() == null || word.getText().trim().isEmpty()) {
                    continue;
                }
                Rectangle box = word.getBoundingBox();
                if (box == null || box.width <= 0 || box.height <= 0) {
                    continue;
                }
                result.add(new OcrWord(
                        word.getText(),
                        box.x,
                        box.y,
                        box.width,
                        box.height,
                        word.getConfidence()
                ));
            }
            log.debug("[Tesseract] OCR words extracted: {}", result.size());
            return result;
        } catch (Exception e) {
            throw new IOException("Tesseract OCR 文字框提取失败: " + e.getMessage(), e);
        }
    }

    public record OcrWord(String text, int x, int y, int width, int height, float confidence) {}

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

            for (int i = 1; i <= pageCount; i++) {
                try {
                    result.append(String.format("%n%n[PAGE: %d]%n%n", i));
                    BufferedImage image = renderer.renderImageWithDPI(i - 1, 300);
                    String ocrText = tesseract.doOCR(image);
                    result.append(ocrText == null ? "" : ocrText.trim()).append("\n");
                    log.debug("[Tesseract] PDF 第 {} 页 OCR 完成", i);
                } catch (Exception e) {
                    log.warn("[Tesseract] PDF 第 {} 页 OCR 失败: {}", i, e.getMessage());
                }
            }
        }

        String output = result.toString();
        log.info("[Tesseract] PDF OCR 完成: {} ({} chars)", pdfFile.getName(), output.length());
        return output;
    }
}
