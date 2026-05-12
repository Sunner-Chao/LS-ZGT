// 包声明：模型层，用于封装 CAD 解析结果
package com.luanshuai.agent.model;

// 导入集合类型
import java.util.List; // 列表
import java.util.Map;  // 键值对映射

/**
 * CAD图纸解析结果对象
 * - 包含文件基础信息、图层、实体、文本、标注、统计与元数据
 */
public class CadDrawingResult {
    // 文件名，例如：drawing.dwg
    private String fileName;
    // 文件路径（相对或绝对）
    private String filePath;
    // 文件类型（例如 dwg/dxf/pdf）
    private String fileType;
    // 文件大小（字节）
    private long fileSize;
    // 解析状态（processing/success/failed）
    private String parseStatus;
    // 解析失败时的错误信息
    private String errorMessage;
    
    // 图纸基本信息（键值对结构）
    private Map<String, Object> drawingInfo;
    
    // 图层信息列表，每一项为包含图层属性的 Map
    private List<Map<String, Object>> layers;
    
    // 实体信息列表（分块/几何信息）
    private List<Map<String, Object>> entities;
    
    // 文本信息列表（提取的文本与位置等）
    private List<Map<String, Object>> texts;
    
    // 标注信息列表（尺寸标注、注释等）
    private List<Map<String, Object>> annotations;
    
    // 解析统计信息（例如实体计数、文本计数等）
    private Map<String, Object> statistics;
    
    // 元数据（如作者、创建时间、来源等）
    private Map<String, Object> metadata;

    // 默认无参构造（用于序列化/反序列化工具）
    public CadDrawingResult() {}

    // 构造并初始化文件名与路径，默认将解析状态设置为 processing
    public CadDrawingResult(String fileName, String filePath) {
        this.fileName = fileName; // 设置文件名
        this.filePath = filePath; // 设置路径
        this.parseStatus = "processing"; // 标记为处理中
    }

    // Getter 和 Setter：文件名
    public String getFileName() { return fileName; }
    public void setFileName(String fileName) { this.fileName = fileName; }

    // 文件路径
    public String getFilePath() { return filePath; }
    public void setFilePath(String filePath) { this.filePath = filePath; }

    // 文件类型
    public String getFileType() { return fileType; }
    public void setFileType(String fileType) { this.fileType = fileType; }

    // 文件大小
    public long getFileSize() { return fileSize; }
    public void setFileSize(long fileSize) { this.fileSize = fileSize; }

    // 解析状态
    public String getParseStatus() { return parseStatus; }
    public void setParseStatus(String parseStatus) { this.parseStatus = parseStatus; }

    // 错误信息（解析失败时填充）
    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }

    // 图纸基本信息
    public Map<String, Object> getDrawingInfo() { return drawingInfo; }
    public void setDrawingInfo(Map<String, Object> drawingInfo) { this.drawingInfo = drawingInfo; }

    // 图层信息
    public List<Map<String, Object>> getLayers() { return layers; }
    public void setLayers(List<Map<String, Object>> layers) { this.layers = layers; }

    // 实体信息
    public List<Map<String, Object>> getEntities() { return entities; }
    public void setEntities(List<Map<String, Object>> entities) { this.entities = entities; }

    // 文本信息
    public List<Map<String, Object>> getTexts() { return texts; }
    public void setTexts(List<Map<String, Object>> texts) { this.texts = texts; }

    // 标注信息
    public List<Map<String, Object>> getAnnotations() { return annotations; }
    public void setAnnotations(List<Map<String, Object>> annotations) { this.annotations = annotations; }

    // 解析统计
    public Map<String, Object> getStatistics() { return statistics; }
    public void setStatistics(Map<String, Object> statistics) { this.statistics = statistics; }

    // 元数据
    public Map<String, Object> getMetadata() { return metadata; }
    public void setMetadata(Map<String, Object> metadata) { this.metadata = metadata; }
}
