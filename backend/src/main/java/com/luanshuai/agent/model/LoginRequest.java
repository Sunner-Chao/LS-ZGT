// 包声明：模型层 - 登录请求
package com.luanshuai.agent.model;

// 引入校验注解
import jakarta.validation.constraints.NotBlank;

// LoginRequest：封装登录接口的请求体（username, password）
public class LoginRequest {
    // 用户名必须非空，校验失败会返回指定 message
    @NotBlank(message = "用户名不能为空")
    private String username;
    
    // 密码必须非空
    @NotBlank(message = "密码不能为空")
    private String password;
    
    // 默认构造（用于反序列化）
    public LoginRequest() {}
    
    // 带参构造
    public LoginRequest(String username, String password) {
        this.username = username; // 设置用户名
        this.password = password; // 设置密码
    }
    
    // Getter/Setter
    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }
    
    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }
} 