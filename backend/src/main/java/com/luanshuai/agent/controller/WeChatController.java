// 包声明：公众号相关的控制器
package com.luanshuai.agent.controller;

// 引入服务与 Spring 注解
import com.luanshuai.agent.service.WeChatService; // 公众号业务逻辑服务
import org.springframework.beans.factory.annotation.Autowired; // 自动注入
import org.springframework.http.ResponseEntity; // 响应封装
import org.springframework.web.bind.annotation.*; // 常用的映射注解

import javax.servlet.http.HttpServletRequest; // 接收原始 HTTP 请求
import javax.servlet.http.HttpServletResponse; // HTTP 响应
import java.io.IOException; // IO 异常
import java.util.Map; // Map 类型

/**
 * 微信公众号控制器：包含公众号服务的验证、消息处理、菜单管理与接口调用
 */
@RestController
@RequestMapping("/api/wechat") // 路由前缀
public class WeChatController {

    // 注入 WeChatService，封装了具体与微信平台交互的逻辑
    @Autowired
    private WeChatService weChatService;

    /**
     * 微信公众号服务器验证（用于初次接入微信时的签名校验）
     * @param signature 微信传入的签名
     * @param timestamp 时间戳
     * @param nonce 随机字符串
     * @param echostr 验证字符串，校验通过后返回给微信
     */
    @GetMapping("/official-account")
    public String verifyServer(@RequestParam("signature") String signature,
                              @RequestParam("timestamp") String timestamp,
                              @RequestParam("nonce") String nonce,
                              @RequestParam("echostr") String echostr) {
        // 委托到 Service 层进行签名校验并返回 echostr（或空字符串）
        return weChatService.verifyServer(signature, timestamp, nonce, echostr);
    }

    /**
     * 处理微信公众号消息（接收被动推送的消息与事件）
     * 该方法使用 HttpServletRequest 以便直接处理微信的原始 XML/流
     */
    @PostMapping("/official-account")
    public String handleMessage(HttpServletRequest request, HttpServletResponse response) {
        try {
            // 委托到服务层进行消息解析与回复构造；若异常则返回 "success" 表示已接收，避免微信重试
            return weChatService.handleMessage(request);
        } catch (Exception e) {
            e.printStackTrace();
            return "success"; // 发生异常时返回 success 以避免微信端重复推送
        }
    }

    /**
     * 获取公众号的全局 access token（通常带缓存，避免频繁调用微信接口）
     */
    @GetMapping("/access-token")
    public ResponseEntity<Map<String, Object>> getAccessToken() {
        try {
            String accessToken = weChatService.getAccessToken();
            // 返回 success=true 以及 accessToken
            return ResponseEntity.ok(Map.of("success", true, "accessToken", accessToken));
        } catch (Exception e) {
            // 出错时返回 success=false 并带上错误信息
            return ResponseEntity.ok(Map.of("success", false, "message", e.getMessage()));
        }
    }

    /**
     * 发送客服消息（通过 weChatService 调用微信客服发送接口）
     */
    @PostMapping("/send-message")
    public ResponseEntity<Map<String, Object>> sendMessage(@RequestBody Map<String, Object> request) {
        try {
            // 从请求体中提取 openId 与消息内容
            String openId = (String) request.get("openId");
            String content = (String) request.get("content");
            String result = weChatService.sendTextMessage(openId, content);
            return ResponseEntity.ok(Map.of("success", true, "result", result));
        } catch (Exception e) {
            return ResponseEntity.ok(Map.of("success", false, "message", e.getMessage()));
        }
    }

    /**
     * 创建自定义菜单（将 menuData JSON 传给服务层）
     */
    @PostMapping("/create-menu")
    public ResponseEntity<Map<String, Object>> createMenu(@RequestBody String menuData) {
        try {
            String result = weChatService.createMenu(menuData);
            return ResponseEntity.ok(Map.of("success", true, "result", result));
        } catch (Exception e) {
            return ResponseEntity.ok(Map.of("success", false, "message", e.getMessage()));
        }
    }

    /**
     * 获取自定义菜单
     */
    @GetMapping("/get-menu")
    public ResponseEntity<Map<String, Object>> getMenu() {
        try {
            String result = weChatService.getMenu();
            return ResponseEntity.ok(Map.of("success", true, "result", result));
        } catch (Exception e) {
            return ResponseEntity.ok(Map.of("success", false, "message", e.getMessage()));
        }
    }

    /**
     * 删除自定义菜单
     */
    @DeleteMapping("/delete-menu")
    public ResponseEntity<Map<String, Object>> deleteMenu() {
        try {
            String result = weChatService.deleteMenu();
            return ResponseEntity.ok(Map.of("success", true, "result", result));
        } catch (Exception e) {
            return ResponseEntity.ok(Map.of("success", false, "message", e.getMessage()));
        }
    }

    /**
     * 获取用户基本信息（通过 openId 查询）
     */
    @GetMapping("/user-info/{openId}")
    public ResponseEntity<Map<String, Object>> getUserInfo(@PathVariable String openId) {
        try {
            String userInfo = weChatService.getUserInfo(openId);
            return ResponseEntity.ok(Map.of("success", true, "userInfo", userInfo));
        } catch (Exception e) {
            return ResponseEntity.ok(Map.of("success", false, "message", e.getMessage()));
        }
    }
}
