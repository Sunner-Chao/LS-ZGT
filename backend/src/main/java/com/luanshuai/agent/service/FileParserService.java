package com.luanshuai.agent.service; // 包：服务层组件

// 导入常用的文件与图像处理库
import java.awt.image.BufferedImage; // BufferedImage 用于图片处理
import java.io.File; // 文件表示
import java.io.FileInputStream; // 文件输入流
import java.io.IOException; // IO异常
import java.io.InputStream; // 通用输入流
import java.time.Duration;

import javax.imageio.ImageIO; // Java Image IO

// PDF 处理库（Apache PDFBox）
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
// Apache POI 用于Office文档解析
import org.apache.poi.hslf.usermodel.HSLFSlideShow;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.hwpf.HWPFDocument;
import org.apache.poi.hwpf.extractor.WordExtractor;
import org.apache.poi.sl.extractor.SlideShowExtractor;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.slf4j.Logger; // 日志接口
import org.slf4j.LoggerFactory; // 日志工厂
import org.springframework.beans.factory.annotation.Autowired; // Spring 注入
import org.springframework.stereotype.Service; // 标注Service层组件

// Tesseract OCR 已在 TesseractOcrService 中封装

@Service // 声明为 Spring 管理的服务组件
public class FileParserService {
    private static final Logger log = LoggerFactory.getLogger(FileParserService.class); // 日志记录器

    // ==================== 服务注入 ====================

    /** Docling 文档解析服务（主力解析器）：擅长原生数字文档、高精度表格 */
    @Autowired
    private DoclingService doclingService;

    /** VLM 增强 OCR 服务：擅长扫描版 PDF、手写体、印章、复杂图表 */
    @Autowired
    private VlmOcrService vlmOcrService;

    /** Tesseract OCR 兜底服务（VLM OCR 失败时的最后降级方案） */
    @Autowired
    private TesseractOcrService tesseractOcrService;

    // ==================== 文件解析主入口 ====================

    /**
     * 根据文件扩展名解析不同类型的文件（同步版本）
     *
     * 智能路由策略：
     * ┌──────────────────┬──────────────────────────────────────────────┐
     * │ 文件类型          │ 解析顺序                                        │
     * ├──────────────────┼──────────────────────────────────────────────┤
     * │ PDF              │ Docling → VLM OCR（扫描版检测）→ PDFBox     │
     * │ Word (docx/doc)  │ Docling → Apache POI                         │
     * │ Excel (xlsx/xls) │ Docling → Apache POI                         │
     * │ PPT (pptx/ppt)   │ Docling → Apache POI                         │
     * │ 图片 (JPG/PNG)    │ VLM OCR → Tesseract OCR                     │
     * │ TXT/MD/CSV       │ 标准 IO                                       │
     * └──────────────────┴──────────────────────────────────────────────┘
     *
     * @param filePath 待解析文件路径
     * @return 返回解析后的文本内容（可能为空）
     * @throws IOException 文件不存在或解析失败时抛出
     */
    public String parseFileSync(String filePath) throws IOException {
        File file = new File(filePath);
        if (!file.exists()) {
            log.error("[解析] 文件不存在: {}", filePath);
            throw new IOException("文件不存在: " + filePath);
        }
        String fileName = file.getName().toLowerCase();
        log.info("[解析] 开始解析文件: {}", fileName);

        try {
            String result = parseByType(file, fileName);

            if (result != null) {
                log.info("[解析] 文件解析成功，内容长度: {}", result.length());
            } else {
                log.warn("[解析] 文件解析后内容为空: {}", fileName);
            }
            return result != null ? result : "";
        } catch (Exception e) {
            log.error("[解析] 文件解析异常: {}，错误: {}", filePath, e.getMessage());
            throw e;
        }
    }

    /**
     * 根据文件类型路由到最优解析器（同步）
     */
    private String parseByType(File file, String fileName) throws IOException {
        String result;

        if (fileName.endsWith(".pdf")) {
            result = parsePdfSmart(file);
        } else if (fileName.endsWith(".docx")) {
            result = parseWithDoclingOrFallback(file, "docx", () -> parseDocx(file));
        } else if (fileName.endsWith(".doc")) {
            result = parseWithDoclingOrFallback(file, "doc", () -> parseDoc(file));
        } else if (fileName.endsWith(".pptx")) {
            result = parseWithDoclingOrFallback(file, "pptx", () -> parsePptx(file));
        } else if (fileName.endsWith(".ppt")) {
            result = parseWithDoclingOrFallback(file, "ppt", () -> parsePpt(file));
        } else if (fileName.endsWith(".xlsx") || fileName.endsWith(".xls")) {
            result = parseWithDoclingOrFallback(file, "xlsx", () -> parseExcel(file));
        } else if (isImageFile(fileName)) {
            // 图片文件：VLM OCR（主力）→ Tesseract（兜底）
            result = parseWithVlmOrFallback(file);
        } else if (fileName.endsWith(".txt") || fileName.endsWith(".md") || fileName.endsWith(".csv")) {
            result = parseTextFile(file);
        } else {
            log.error("[解析] 不支持的文件类型: {}", fileName);
            throw new IOException("不支持的文件类型: " + fileName);
        }

        return result;
    }

    // ==================== 智能解析方法 ====================

    /**
     * 智能 PDF 解析
     *
     * 策略：
     * 1. Docling 解析（主力）→ 若成功直接返回
     * 2. 若失败，检测是否为扫描版 PDF（文本层贫瘠）→ VLM OCR
     * 3. 最终兜底：Apache PDFBox 纯文本提取
     */
    private String parsePdfSmart(File file) throws IOException {
        // Step 1: 尝试 Docling（擅长原生数字 PDF 和表格）
        try {
            log.info("[解析] 尝试使用 Docling 解析 PDF: {}", file.getName());
            String result = doclingService.parseDocument(file, "markdown").block(Duration.ofSeconds(180));
            if (result != null && result.trim().length() > 50) {
                log.info("[解析] Docling 解析成功，内容长度: {}", result.length());
                return result;
            }
        } catch (Exception e) {
            log.warn("[解析] Docling 解析失败: {}", e.getMessage());
        }

        // Step 2: 检测是否为扫描版 PDF（文本层贫瘠）→ VLM OCR
        try {
            String textLayer = extractTextLayerFast(file);
            if (textLayer == null || textLayer.trim().length() < 50) {
                log.info("[解析] PDF 文本层贫瘠，判定为扫描版，尝试 VLM OCR: {}", file.getName());
                String vlmResult = vlmOcrService.ocrPdf(file, VlmOcrService.OcrMode.TEXT)
                        .block(Duration.ofSeconds(300));
                if (vlmResult != null && vlmResult.trim().length() > 20) {
                    log.info("[解析] VLM OCR 成功，内容长度: {}", vlmResult.length());
                    return vlmResult;
                }
            } else {
                log.info("[解析] PDF 文本层提取成功（{} 字符），使用文本层内容: {}", textLayer.length(), file.getName());
                return textLayer;
            }
        } catch (Exception e) {
            log.warn("[解析] VLM OCR 失败: {}", e.getMessage());
        }

        // Step 3: 最终兜底：Apache PDFBox
        log.warn("[解析] 所有高级解析器失败，回退到 Apache PDFBox: {}", file.getName());
        return parsePdf(file);
    }

    /**
     * 快速提取 PDF 文本层（用于判断是否为扫描版）
     */
    private String extractTextLayerFast(File file) {
        try (org.apache.pdfbox.pdmodel.PDDocument document = org.apache.pdfbox.Loader.loadPDF(file)) {
            org.apache.pdfbox.text.PDFTextStripper stripper = new org.apache.pdfbox.text.PDFTextStripper();
            return stripper.getText(document);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Docling 优先，失败时使用本地解析器兜底
     */
    private String parseWithDoclingOrFallback(File file, String format, IOExceptionSupplier fallback) throws IOException {
        try {
            log.info("[解析] 尝试使用 Docling 解析 {}: {}", format, file.getName());
            String result = doclingService.parseDocument(file, "markdown")
                    .block(Duration.ofSeconds(180));
            if (result != null && result.trim().length() > 20) {
                log.info("[解析] Docling 解析 {} 成功，内容长度: {}", format, result.length());
                return result;
            }
        } catch (Exception e) {
            log.warn("[解析] Docling 解析 {} 失败，回退到 Apache POI: {}", format, e.getMessage());
        }

        // 兜底：Apache POI
        return fallback.get();
    }

    /**
     * VLM OCR 优先，失败时使用 Tesseract 兜底
     */
    private String parseWithVlmOrFallback(File file) throws IOException {
        try {
            log.info("[解析] 尝试使用 VLM OCR 解析图片: {}", file.getName());
            String result = vlmOcrService.ocrImage(file, VlmOcrService.OcrMode.TEXT)
                    .block(Duration.ofSeconds(120));
            if (result != null && result.trim().length() > 5) {
                log.info("[解析] VLM OCR 成功，内容长度: {}", result.length());
                return result;
            }
        } catch (Exception e) {
            log.warn("[解析] VLM OCR 失败: {}", e.getMessage());
        }

        // 兜底：Tesseract OCR
        log.info("[解析] VLM OCR 失败或结果为空，回退到 Tesseract OCR: {}", file.getName());
        return parseImage(file);
    }

    private boolean isImageFile(String fileName) {
        return fileName.endsWith(".jpg") || fileName.endsWith(".jpeg")
                || fileName.endsWith(".png") || fileName.endsWith(".gif")
                || fileName.endsWith(".bmp") || fileName.endsWith(".tiff")
                || fileName.endsWith(".webp") || fileName.endsWith(".tif");
    }

    /**
     * 解析非PDF文件（同步版本）
     * 此方法用于仅解析非PDF文件类型的同步逻辑，供响应式包装或同步调用使用
     */
    public String parseFileSyncNonPdf(String filePath) throws IOException {
        File file = new File(filePath);
        if (!file.exists()) {
            log.error("[解析] 文件不存在: {}", filePath);
            throw new IOException("文件不存在: " + filePath);
        }
        String fileName = file.getName().toLowerCase();
        log.info("[解析] 开始解析非PDF文件: {}", fileName);

        try {
            if (fileName.endsWith(".docx")) {
                try {
                    return parseDocx(file);
                } catch (Exception e) {
                    log.warn("[解析] DOCX文本提取失败，尝试纯文本兜底: {}", e.getMessage());
                    return parseTextFile(file);
                }
            } else if (fileName.endsWith(".doc")) {
                return parseDoc(file);
            } else if (fileName.endsWith(".pptx")) {
                return parsePptx(file);
            } else if (fileName.endsWith(".ppt")) {
                return parsePpt(file);
            } else if (isImageFile(fileName)) {
                return parseWithVlmOrFallback(file);
            } else if (fileName.endsWith(".xlsx") || fileName.endsWith(".xls")) {
                return parseExcel(file);
            } else {
                return parseTextFile(file);
            }
        } catch (Exception e) {
            log.error("[解析] 文件解析异常: {}，错误: {}", filePath, e.getMessage());
            throw e;
        }
    }

    @FunctionalInterface
    private interface IOExceptionSupplier {
        String get() throws IOException;
    }

    // ==================== 响应式版本 ====================

    public String parseFile(String filePath) throws IOException {
        return parseFileSync(filePath);
    }

    /**
     * 响应式版本的文件解析方法
     */
    public reactor.core.publisher.Mono<String> parseFileReactive(String filePath) {
        File file = new File(filePath);
        if (!file.exists()) {
            return reactor.core.publisher.Mono.error(new IOException("文件不存在: " + filePath));
        }

        String fileName = file.getName().toLowerCase();
        log.info("[解析] 开始解析文件: {}", fileName);

        if (fileName.endsWith(".pdf")) {
            return parsePdfSmartReactive(file)
                    .filter(result -> result != null && !result.trim().isEmpty())
                    .switchIfEmpty(reactor.core.publisher.Mono.error(new IOException("PDF解析结果为空")));
        } else if (isImageFile(fileName)) {
            return vlmOcrService.ocrImage(file, VlmOcrService.OcrMode.TEXT)
                    .onErrorResume(e -> {
                        log.warn("[解析] VLM OCR 失败，回退到 Tesseract: {}", e.getMessage());
                        return reactor.core.publisher.Mono.fromCallable(() -> parseImage(file))
                                .subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic());
                    });
        } else if (fileName.endsWith(".docx") || fileName.endsWith(".doc")
                || fileName.endsWith(".pptx") || fileName.endsWith(".ppt")
                || fileName.endsWith(".xlsx") || fileName.endsWith(".xls")) {
            return doclingService.parseDocument(file, "markdown")
                    .onErrorResume(e -> {
                        log.warn("[解析] Docling 解析失败，回退到本地解析: {}", e.getMessage());
                        return reactor.core.publisher.Mono.fromCallable(() -> parseFileSyncNonPdf(filePath))
                                .subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic());
                    });
        } else {
            return reactor.core.publisher.Mono.fromCallable(() -> parseTextFile(file))
                    .subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic());
        }
    }

    private reactor.core.publisher.Mono<String> parsePdfSmartReactive(File file) {
        return doclingService.parseDocument(file, "markdown")
                .flatMap(result -> {
                    if (result != null && result.trim().length() > 50) {
                        return reactor.core.publisher.Mono.just(result);
                    }
                    // Docling 结果贫瘠，尝试 VLM OCR
                    return vlmOcrService.ocrPdf(file, VlmOcrService.OcrMode.TEXT)
                            .onErrorResume(e -> reactor.core.publisher.Mono.just(""));
                })
                .flatMap(result -> {
                    if (result != null && result.trim().length() > 50) {
                        return reactor.core.publisher.Mono.just(result);
                    }
                    // VLM 也失败，兜底 PDFBox
                    return reactor.core.publisher.Mono.fromCallable(() -> parsePdf(file))
                            .subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic());
                })
                .onErrorResume(e -> {
                    log.warn("[解析] PDF 响应式解析异常: {}", e.getMessage());
                    return reactor.core.publisher.Mono.fromCallable(() -> parsePdf(file))
                            .subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic());
                });
    }

    /**
     * 解析PDF文件（逐页提取文本并用特殊分隔符标记页码）
     * 使用 Apache PDFBox 进行文本提取，适合作为 PDF 的本地兜底解析
     * 页码格式: \n\n[PAGE: 1]\n\n 用于后续分片时保留页码信息
     */
    private static final String PAGE_SEPARATOR = "\n\n[PAGE: %d]\n\n";

    private String parsePdf(File file) throws IOException {
        try (PDDocument document = Loader.loadPDF(file)) {
            PDFTextStripper stripper = new PDFTextStripper();
            int totalPages = document.getNumberOfPages();
            StringBuilder result = new StringBuilder();
            for (int i = 1; i <= totalPages; i++) {
                stripper.setStartPage(i);
                stripper.setEndPage(i);
                String pageText = stripper.getText(document).trim();
                result.append(String.format(PAGE_SEPARATOR, i));
                result.append(pageText);
            }
            return result.toString();
        }
    }

    /**
     * 解析Word DOCX文件（基于 Apache POI XWPF）
     */
    private String parseDocx(File file) throws IOException {
        try (InputStream fis = new FileInputStream(file);
             XWPFDocument document = new XWPFDocument(fis)) {
            XWPFWordExtractor extractor = new XWPFWordExtractor(document);
            return extractor.getText(); // 提取DOCX文本
        }
    }

    /**
     * 解析Word DOC文件（基于 Apache POI HWPF）
     */
    private String parseDoc(File file) throws IOException {
        try (InputStream fis = new FileInputStream(file);
             HWPFDocument document = new HWPFDocument(fis)) {
            WordExtractor extractor = new WordExtractor(document);
            return extractor.getText(); // 提取DOC文本
        }
    }

    /**
     * 解析PowerPoint PPTX文件（基于 Apache POI XSLF）
     */
    private String parsePptx(File file) throws IOException {
        try (InputStream fis = new FileInputStream(file);
              XMLSlideShow xmlSlideShow = new XMLSlideShow(fis)) {
              SlideShowExtractor extractor = new SlideShowExtractor(xmlSlideShow);
            return extractor.getText(); // 提取PPTX文本
        }
    }

    /**
     * 解析PowerPoint PPT文件（基于 Apache POI HSLF）
     */
    private String parsePpt(File file) throws IOException {
        try (InputStream fis = new FileInputStream(file);
              HSLFSlideShow hslfSlideShow = new HSLFSlideShow(fis)) {
              SlideShowExtractor extractor = new SlideShowExtractor(hslfSlideShow);
            return extractor.getText(); // 提取PPT文本
        }
    }

    /**
     * 解析图片文件（OCR文字识别）
     * 使用 Tesseract 对图片进行 OCR 识别，适用于扫描图片或截图中的文字
     */
    private String parseImage(File file) throws IOException {
        try {
            BufferedImage image = ImageIO.read(file); // 读取图片为 BufferedImage
            return tesseractOcrService.ocrImage(file); // 调用 Tesseract OCR 识别并返回文本
        } catch (Exception e) {
            log.error("图片OCR识别失败", e);
            throw new IOException("图片识别失败: " + e.getMessage());
        }
    }

    /**
     * 解析文本文件
     * 使用 Scanner 一次性读取整个文件内容为字符串（UTF-8）
     */
    private String parseTextFile(File file) throws IOException {
        try (FileInputStream fis = new FileInputStream(file);
             java.util.Scanner scanner = new java.util.Scanner(fis, java.nio.charset.StandardCharsets.UTF_8)) {
            return scanner.useDelimiter("\\A").next(); // 读取全文
        }
    }

    // 新增：图片型PDF的OCR识别
    // 逐页尝试使用 PDFTextStripper 提取文本，若单页为空则渲染图片并进行 OCR（300 DPI）
    private String ocrPdf(File file) throws IOException {
        StringBuilder sb = new StringBuilder();
        try (PDDocument document = Loader.loadPDF(file)) {
            int pageCount = document.getNumberOfPages();
            PDFTextStripper stripper = new PDFTextStripper();
            for (int i = 1; i <= pageCount; i++) {
                stripper.setStartPage(i);
                stripper.setEndPage(i);
                String text = stripper.getText(document);
                if (text == null || text.trim().isEmpty()) {
                    // 当PDF页文本为空时，尝试将页面渲染为图片并用OCR识别
                    try {
                        org.apache.pdfbox.rendering.PDFRenderer renderer = new org.apache.pdfbox.rendering.PDFRenderer(document);
                        java.awt.image.BufferedImage image = renderer.renderImageWithDPI(i - 1, 300); // 渲染为300 DPI图片
                        String ocrText = tesseractOcrService.doOCR(image);
                        sb.append(ocrText).append("\n");
                    } catch (Exception e) {
                        log.warn("[OCR] PDF第{}页识别失败: {}", i, e.getMessage());
                    }
                } else {
                    sb.append(text).append("\n");
                }
            }
        }
        return sb.toString();
    }

    /**
     * 解析Excel文件（.xlsx和.xls）
     * 说明：根据文件扩展名选用 XSSFWorkbook（.xlsx）或 HSSFWorkbook（.xls），按工作表和行遍历单元格并拼接文本
     */
    private String parseExcel(File file) throws IOException {
        StringBuilder result = new StringBuilder(); // 用于拼接整个Excel解析内容
        try (InputStream fis = new FileInputStream(file)) {
            Workbook workbook; // 根据文件类型选择Workbook实现
            
            if (file.getName().toLowerCase().endsWith(".xlsx")) {
                workbook = new XSSFWorkbook(fis); // Office Open XML
            } else {
                workbook = new HSSFWorkbook(fis); // 兼容旧的二进制格式
            }
            
            int sheetCount = workbook.getNumberOfSheets();
            log.info("[Excel] 文件包含 {} 个工作表", sheetCount);
            
            // 遍历每个工作表
            for (int sheetIndex = 0; sheetIndex < sheetCount; sheetIndex++) {
                org.apache.poi.ss.usermodel.Sheet sheet = workbook.getSheetAt(sheetIndex);
                String sheetName = sheet.getSheetName();
                result.append("工作表: ").append(sheetName).append("\n");
                
                int rowCount = sheet.getPhysicalNumberOfRows();
                log.info("[Excel] 工作表 '{}' 包含 {} 行数据", sheetName, rowCount);
                
                // 遍历每一行并收集非空单元格文本
                for (int rowIndex = 0; rowIndex < rowCount; rowIndex++) {
                    org.apache.poi.ss.usermodel.Row row = sheet.getRow(rowIndex);
                    if (row != null) {
                        StringBuilder rowText = new StringBuilder();
                        for (int cellIndex = 0; cellIndex < row.getLastCellNum(); cellIndex++) {
                            org.apache.poi.ss.usermodel.Cell cell = row.getCell(cellIndex);
                            if (cell != null) {
                                String cellValue = getCellValueAsString(cell); // 将单元格值转换为字符串
                                if (cellValue != null && !cellValue.trim().isEmpty()) {
                                    rowText.append(cellValue).append("\t"); // 单元格以制表符分隔
                                }
                            }
                        }
                        if (rowText.length() > 0) {
                            result.append(rowText.toString().trim()).append("\n"); // 添加行文本
                        }
                    }
                }
                result.append("\n"); // 每个工作表后分割
            }
            
            workbook.close(); // 关闭工作簿释放资源
        } catch (Exception e) {
            log.error("[Excel] 解析Excel文件失败: {}", e.getMessage(), e);
            throw new IOException("Excel文件解析失败: " + e.getMessage());
        }
        
        String content = result.toString();
        log.info("[Excel] Excel文件解析完成，内容长度: {}", content.length());
        return content; // 返回拼接后的Excel文本内容
    }
    
    /**
     * 获取单元格的值作为字符串
     * 说明：考虑日期、数值、公式等多种单元格类型，并尽量避免出现科学计数法表示
     */
    private String getCellValueAsString(org.apache.poi.ss.usermodel.Cell cell) {
        if (cell == null) {
            return null; // 空单元格返回 null
        }
        
        switch (cell.getCellType()) {
            case STRING:
                return cell.getStringCellValue(); // 直接返回字符串
            case NUMERIC:
                if (org.apache.poi.ss.usermodel.DateUtil.isCellDateFormatted(cell)) {
                    return cell.getDateCellValue().toString(); // 日期格式化为字符串
                } else {
                    // 数值类型：避免科学计数法，整数以 long 返回，小数以 double 返回的字符串形式
                    double numericValue = cell.getNumericCellValue();
                    if (numericValue == (long) numericValue) {
                        return String.valueOf((long) numericValue); // 整数处理
                    } else {
                        return String.valueOf(numericValue); // 小数处理
                    }
                }
            case BOOLEAN:
                return String.valueOf(cell.getBooleanCellValue()); // 布尔类型
            case FORMULA:
                // 公式单元格：优先尝试数值计算结果，否则尝试字符串结果，再回退到公式文本
                try {
                    return String.valueOf(cell.getNumericCellValue());
                } catch (Exception e) {
                    try {
                        return cell.getStringCellValue();
                    } catch (Exception e2) {
                        return cell.getCellFormula();
                    }
                }
            case BLANK:
                return ""; // 空单元格返回空字符串
            default:
                return ""; // 其他未知类型返回空字符串
        }
    }
}