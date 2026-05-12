// 包声明：控制器相关的全局异常处理器
package com.luanshuai.agent.controller;

// 导入 Spring WebFlux 的异常处理注解与响应封装类
import org.springframework.web.bind.annotation.ControllerAdvice; // 标注为 Controller Advice 的注解
import org.springframework.web.bind.annotation.ExceptionHandler; // 标注异常处理方法的注解
import org.springframework.web.server.ServerWebInputException; // 请求绑定/解析异常
import org.springframework.http.ResponseEntity; // HTTP 响应封装
import reactor.core.publisher.Mono; // Reactor 单值响应类型

// ControllerAdvice：将该类注册为全局异常处理器，捕获控制器层抛出的异常并统一处理
@ControllerAdvice
public class GlobalExceptionHandler {
    
    // 处理请求体/参数绑定相关的异常（例如 JSON 结构不匹配、缺失必需字段等）
    @ExceptionHandler(ServerWebInputException.class)
    public Mono<ResponseEntity<String>> handleWebInputException(ServerWebInputException ex) {
        // 打印错误信息到 STDERR，便于日志或调试时查看请求入参问题
        System.err.println("=== 全局异常处理器：ServerWebInputException ===");
        System.err.println("异常信息: " + ex.getMessage());
        System.err.println("异常类型: " + ex.getClass().getName());
        ex.printStackTrace(); // 输出堆栈信息以便定位问题
        // 返回 400 Bad Request 并带上简要错误信息给客户端
        return Mono.just(ResponseEntity.badRequest().body("参数绑定失败: " + ex.getMessage()));
    }
    
    // 通用异常处理：兜底处理其他未显式捕获的异常
    @ExceptionHandler(Exception.class)
    public Mono<ResponseEntity<String>> handleOtherException(Exception ex) {
        // 输出异常信息用于运维/调试
        System.err.println("=== 全局异常处理器：通用异常 ===");
        System.err.println("异常信息: " + ex.getMessage());
        System.err.println("异常类型: " + ex.getClass().getName());
        ex.printStackTrace();
        // 返回 500 Internal Server Error 给调用方，包含简要信息
        return Mono.just(ResponseEntity.status(500).body("服务器异常: " + ex.getMessage()));
    }
}
