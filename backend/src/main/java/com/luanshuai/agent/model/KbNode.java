// 包声明：模型层 - 知识库节点
package com.luanshuai.agent.model;

import java.util.List; // 列表类型
import com.fasterxml.jackson.annotation.JsonIdentityInfo; // 防止循环引用的注解
import com.fasterxml.jackson.annotation.ObjectIdGenerators; // id 生成器

// 使用 path 属性作为 JSON 序列化的标识，避免循环引用导致的问题
@JsonIdentityInfo(generator = ObjectIdGenerators.PropertyGenerator.class, property = "path")
public class KbNode {
    // 节点类型："folder" 表示文件夹，"file" 表示文件
    private String type; // "folder" or "file"
    // 显示名称
    private String name;
    // 相对路径（作为唯一 id，在 JsonIdentityInfo 中被引用）
    private String path; // 相对路径
    // 子节点列表（仅当 type="folder" 时存在）
    private List<KbNode> children;
    // 文件大小（字节），仅文件类型有效
    private Long size;
    // 最后修改时间（ISO 8601 格式字符串），仅文件类型有效
    private String lastModified;

    // 无参构造
    public KbNode() {}

    // 构造：type 和 name
    public KbNode(String type, String name) {
        this.type = type;
        this.name = name;
    }

    // 构造：包含子节点
    public KbNode(String type, String name, List<KbNode> children) {
        this.type = type;
        this.name = name;
        this.children = children;
    }

    // 构造：包含路径
    public KbNode(String type, String name, String path) {
        this.type = type;
        this.name = name;
        this.path = path;
    }

    // 构造：包含路径和子节点
    public KbNode(String type, String name, String path, List<KbNode> children) {
        this.type = type;
        this.name = name;
        this.path = path;
        this.children = children;
    }

    // Getter/Setter
    public String getType() { return type; }
    public void setType(String type) { this.type = type; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public List<KbNode> getChildren() { return children; }
    public void setChildren(List<KbNode> children) { this.children = children; }

    public String getPath() { return path; }
    public void setPath(String path) { this.path = path; }

    public Long getSize() { return size; }
    public void setSize(Long size) { this.size = size; }

    public String getLastModified() { return lastModified; }
    public void setLastModified(String lastModified) { this.lastModified = lastModified; }
}