// 包声明：模型层
package com.luanshuai.agent.model;

// Jackson 注解，用于在序列化时忽略 null 字段
import com.fasterxml.jackson.annotation.JsonInclude;

// 控制序列化规则：不包含 null 的字段
@JsonInclude(JsonInclude.Include.NON_NULL)
// 通用 API 响应对象，泛型 T 表示 data 字段类型
public class ApiResponse<T> {
    // 是否成功（true 表示成功，false 表示失败）
    private boolean success;
    // 响应消息（成功或失败的描述）
    private String message;
    // 可选的响应数据，类型由泛型 T 指定
    private T data;
    
    // 无参构造（Jackson 反序列化或手动构造时使用）
    public ApiResponse() {}
    
    // 构造函数：成功/失败和消息（无数据）
    public ApiResponse(boolean success, String message) {
        this.success = success; // 设置是否成功
        this.message = message; // 设置消息文本
    }
    
    // 构造函数：成功/失败、消息与数据
    public ApiResponse(boolean success, String message, T data) {
        this.success = success; // 设置是否成功
        this.message = message; // 设置消息文本
        this.data = data;         // 设置返回的数据
    }
    
    // 静态工厂方法：只包含消息的成功响应
    public static <T> ApiResponse<T> success(String message) {
        return new ApiResponse<>(true, message); // success=true
    }
    
    // 静态工厂方法：包含消息和数据的成功响应
    public static <T> ApiResponse<T> success(String message, T data) {
        return new ApiResponse<>(true, message, data); // success=true 且包含 data
    }
    
    // 静态工厂方法：错误响应，只有消息
    public static <T> ApiResponse<T> error(String message) {
        return new ApiResponse<>(false, message); // success=false
    }
    
    // Getter：是否成功
    public boolean isSuccess() { return success; }
    // Setter：设置是否成功
    public void setSuccess(boolean success) { this.success = success; }
    
    // Getter：消息字符串
    public String getMessage() { return message; }
    // Setter：设置消息字符串
    public void setMessage(String message) { this.message = message; }
    
    // Getter：返回的数据
    public T getData() { return data; }
    // Setter：设置返回的数据
    public void setData(T data) { this.data = data; }
} 