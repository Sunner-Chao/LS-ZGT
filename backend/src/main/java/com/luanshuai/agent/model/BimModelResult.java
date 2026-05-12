// 包声明：模型层
package com.luanshuai.agent.model;

// 导入集合相关类型
import java.util.List;
import java.util.Map;

/**
 * BIM 模型解析结果对象
 * - 包含文件信息、模型/项目/元素/材质/视图/族/系统/空间等解析后的数据
 */
public class BimModelResult {
    // 文件名
    private String fileName;
    // 文件路径
    private String filePath;
    // 文件类型（如 rvt, ifc 等）
    private String fileType;
    // 文件大小（字节）
    private long fileSize;
    // 解析状态（processing/success/failed）
    private String parseStatus;
    // 解析异常信息
    private String errorMessage;
    
    // 模型基本信息（键值对）
    private Map<String, Object> modelInfo;
    
    // 项目信息（项目级元数据）
    private Map<String, Object> projectInfo;
    
    // 元素信息列表（部件、构件等）
    private List<Map<String, Object>> elements;
    
    // 材质信息
    private List<Map<String, Object>> materials;
    
    // 视图信息（平面图、剖面等）
    private List<Map<String, Object>> views;
    
    // 族信息（构件族）
    private List<Map<String, Object>> families;
    
    // 系统信息（例如暖通、电气系统）
    private List<Map<String, Object>> systems;
    
    // 空间信息（房间、区域等）
    private List<Map<String, Object>> spaces;
    
    // 解析统计信息（数量、耗时等）
    private Map<String, Object> statistics;
    
    // 元数据
    private Map<String, Object> metadata;

    // 默认构造
    public BimModelResult() {}

    // 带参构造：初始化文件名与路径并标记状态为 processing
    public BimModelResult(String fileName, String filePath) {
        this.fileName = fileName;
        this.filePath = filePath;
        this.parseStatus = "processing";
    }

    // Getter 和 Setter（以下省略注释以保持简洁）
    public String getFileName() { return fileName; }
    public void setFileName(String fileName) { this.fileName = fileName; }

    public String getFilePath() { return filePath; }
    public void setFilePath(String filePath) { this.filePath = filePath; }

    public String getFileType() { return fileType; }
    public void setFileType(String fileType) { this.fileType = fileType; }

    public long getFileSize() { return fileSize; }
    public void setFileSize(long fileSize) { this.fileSize = fileSize; }

    public String getParseStatus() { return parseStatus; }
    public void setParseStatus(String parseStatus) { this.parseStatus = parseStatus; }

    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }

    public Map<String, Object> getModelInfo() { return modelInfo; }
    public void setModelInfo(Map<String, Object> modelInfo) { this.modelInfo = modelInfo; }

    public Map<String, Object> getProjectInfo() { return projectInfo; }
    public void setProjectInfo(Map<String, Object> projectInfo) { this.projectInfo = projectInfo; }

    public List<Map<String, Object>> getElements() { return elements; }
    public void setElements(List<Map<String, Object>> elements) { this.elements = elements; }

    public List<Map<String, Object>> getMaterials() { return materials; }
    public void setMaterials(List<Map<String, Object>> materials) { this.materials = materials; }

    public List<Map<String, Object>> getViews() { return views; }
    public void setViews(List<Map<String, Object>> views) { this.views = views; }

    public List<Map<String, Object>> getFamilies() { return families; }
    public void setFamilies(List<Map<String, Object>> families) { this.families = families; }

    public List<Map<String, Object>> getSystems() { return systems; }
    public void setSystems(List<Map<String, Object>> systems) { this.systems = systems; }

    public List<Map<String, Object>> getSpaces() { return spaces; }
    public void setSpaces(List<Map<String, Object>> spaces) { this.spaces = spaces; }

    public Map<String, Object> getStatistics() { return statistics; }
    public void setStatistics(Map<String, Object> statistics) { this.statistics = statistics; }

    public Map<String, Object> getMetadata() { return metadata; }
    public void setMetadata(Map<String, Object> metadata) { this.metadata = metadata; }
}
