package com.luanshuai.agent.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;
import javax.servlet.http.HttpServletRequest;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.*;

/**
 * 微信公众号服务（封装常见公众平台操作与消息处理）
 * 
 * 职责：
 * - 验证来自微信的服务器请求合法性（签名校验）
 * - 读取并解析微信推送的 XML 消息，按消息类型分发到相应处理器
 * - 管理 access_token（缓存与续期），调用微信开放接口发送客服消息、管理菜单、获取用户信息等
 * - 提供简易的回复 XML 构造方法，便于在消息回调中返回标准的被动回复
 * 
 * 注意：本类使用同步 RestTemplate 与阻塞方式与微信 API 通信；若在高并发场景需要考虑改为异步/限流。
 */
@Service
public class WeChatService {

    @Value("${wechat.official-account.app-id}")
    private String appId;

    @Value("${wechat.official-account.app-secret}")
    private String appSecret;

    @Value("${wechat.official-account.token}")
    private String token;

    @Value("${wechat.official-account.encoding-aes-key}")
    private String encodingAESKey;

    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper objectMapper = new ObjectMapper();

    private String accessToken;
    private long tokenExpireTime;

    /**
     * 验证服务器请求的合法性（微信接入校验）
     * <p>
     * 流程：
     * 1. 将 token、timestamp、nonce 三个参数按字典序排序并拼接
     * 2. 对拼接字符串做 SHA-1 加密
     * 3. 将加密结果与微信传来的 signature 比对，若相等则返回 echostr 表示校验通过
     *
     * @param signature 微信传入的签名
     * @param timestamp 时间戳
     * @param nonce 随机串
     * @param echostr 校验字符串，校验通过需返回该值
     * @return 通过返回 echostr，否则返回空字符串
     */
    public String verifyServer(String signature, String timestamp, String nonce, String echostr) {
        try {
            // 将token、timestamp、nonce三个参数进行字典序排序
            String[] arr = {token, timestamp, nonce};
            Arrays.sort(arr);

            // 将三个参数字符串拼接成一个字符串进行sha1加密
            StringBuilder content = new StringBuilder();
            for (String s : arr) {
                content.append(s);
            }
            String temp = sha1(content.toString());

            // 开发者获得加密后的字符串可与signature对比，标识该请求来源于微信
            if (temp.equals(signature)) {
                return echostr;
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return "";
    }

    /**
     * 处理微信消息回调入口
     * <p>
     * 1. 从 HttpServletRequest 中读取原始 XML 消息
     * 2. 将 XML 解析为 Map（常见字段：ToUserName/FromUserName/MsgType/Content 等）
     * 3. 根据 MsgType 调用不同的处理函数并返回构造好的回复 XML
     * 4. 出现异常时返回 "success" 以遵守微信的回调容错约定（避免被重复推送）
     */
    public String handleMessage(HttpServletRequest request) {
        try {
            // 读取XML消息
            String xmlMessage = readXmlFromRequest(request);
            
            // 解析XML
            Map<String, String> messageMap = parseXml(xmlMessage);
            
            // 处理消息
            String replyMessage = processMessage(messageMap);
            
            return replyMessage;
        } catch (Exception e) {
            e.printStackTrace();
            return "success";
        }
    }

    /**
     * 获取并缓存 access_token
     * <p>
     * - 使用 AppID 与 AppSecret 调用微信接口获取 access_token
     * - 将 token 缓存到内存并设置过期时间，提前 5 分钟（300 秒）失效以避免临界问题
     * - 若请求失败则抛出异常，上层调用应根据需要重试或降级
     */
    public String getAccessToken() throws Exception {
        // 检查令牌是否过期
        if (accessToken != null && System.currentTimeMillis() < tokenExpireTime) {
            return accessToken;
        }

        String url = "https://api.weixin.qq.com/cgi-bin/token?grant_type=client_credential&appid=" + appId + "&secret=" + appSecret;
        
        ResponseEntity<String> response = restTemplate.getForEntity(url, String.class);
        JsonNode jsonNode = objectMapper.readTree(response.getBody());
        
        if (jsonNode.has("access_token")) {
            accessToken = jsonNode.get("access_token").asText();
            // 设置过期时间（提前5分钟过期）
            tokenExpireTime = System.currentTimeMillis() + (jsonNode.get("expires_in").asLong() - 300) * 1000;
            return accessToken;
        } else {
            throw new Exception("获取access_token失败: " + jsonNode.get("errmsg").asText());
        }
    }

    /**
     * 发送客服文本消息给指定 openId
     * 
     * @param openId 用户的 openId
     * @param content 消息文本
     * @return 微信 API 返回的原始响应字符串（JSON）
     * @throws Exception 若获取 access_token 失败则抛出异常
     */
    public String sendTextMessage(String openId, String content) throws Exception {
        String accessToken = getAccessToken();
        String url = "https://api.weixin.qq.com/cgi-bin/message/custom/send?access_token=" + accessToken;
        
        Map<String, Object> message = new HashMap<>();
        message.put("touser", openId);
        message.put("msgtype", "text");
        message.put("text", Map.of("content", content));
        
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(message, headers);
        
        ResponseEntity<String> response = restTemplate.postForEntity(url, entity, String.class);
        return response.getBody();
    }

    /**
     * 创建自定义菜单（JSON body 需符合微信菜单接口格式）
     * @param menuData 菜单的 JSON 字符串
     * @return 微信 API 原始响应
     */
    public String createMenu(String menuData) throws Exception {
        String accessToken = getAccessToken();
        String url = "https://api.weixin.qq.com/cgi-bin/menu/create?access_token=" + accessToken;
        
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<String> entity = new HttpEntity<>(menuData, headers);
        
        ResponseEntity<String> response = restTemplate.postForEntity(url, entity, String.class);
        return response.getBody();
    }

    /**
     * 获取当前自定义菜单配置
     */
    public String getMenu() throws Exception {
        String accessToken = getAccessToken();
        String url = "https://api.weixin.qq.com/cgi-bin/menu/get?access_token=" + accessToken;
        
        ResponseEntity<String> response = restTemplate.getForEntity(url, String.class);
        return response.getBody();
    }

    /**
     * 删除当前自定义菜单
     */
    public String deleteMenu() throws Exception {
        String accessToken = getAccessToken();
        String url = "https://api.weixin.qq.com/cgi-bin/menu/delete?access_token=" + accessToken;
        
        ResponseEntity<String> response = restTemplate.getForEntity(url, String.class);
        return response.getBody();
    }

    /**
     * 获取用户基本信息（通过 openId）
     * @param openId 用户的 openId
     * @return 微信 API 返回的原始 JSON 字符串
     */
    public String getUserInfo(String openId) throws Exception {
        String accessToken = getAccessToken();
        String url = "https://api.weixin.qq.com/cgi-bin/user/info?access_token=" + accessToken + "&openid=" + openId + "&lang=zh_CN";
        
        ResponseEntity<String> response = restTemplate.getForEntity(url, String.class);
        return response.getBody();
    }

    /**
     * 消息分发器：根据 MsgType 分发到不同处理函数
     * - text: 文本消息（可接入 AI 服务）
     * - event: 事件类（关注/菜单点击等）
     * - image/voice/video/location/link: 简单的默认回复（可扩展）
     * @param messageMap 已解析的消息字段映射
     * @return 构造好的回复 XML（或空字符串），符合微信被动回复规范
     */
    private String processMessage(Map<String, String> messageMap) {
        String msgType = messageMap.get("MsgType");
        String fromUserName = messageMap.get("FromUserName");
        String toUserName = messageMap.get("ToUserName");
        
        switch (msgType) {
            case "text":
                return processTextMessage(messageMap);
            case "event":
                return processEventMessage(messageMap);
            case "image":
                return createReplyMessage(fromUserName, toUserName, "收到您的图片，目前暂不支持图片识别，请发送文字问题。");
            case "voice":
                return createReplyMessage(fromUserName, toUserName, "收到您的语音，目前暂不支持语音识别，请发送文字问题。");
            case "video":
                return createReplyMessage(fromUserName, toUserName, "收到您的视频，目前暂不支持视频处理，请发送文字问题。");
            case "location":
                return createReplyMessage(fromUserName, toUserName, "收到您的位置信息，目前暂不支持位置相关功能，请发送文字问题。");
            case "link":
                return createReplyMessage(fromUserName, toUserName, "收到您分享的链接，请直接发送您的问题。");
            default:
                return createReplyMessage(fromUserName, toUserName, "收到您的消息，请发送文字问题。");
        }
    }

    /**
     * 文本消息处理器（可接入 AI 服务以提供语义回答）
     * - 当前实现为简单的回声/提示
     * - 可在 TODO 处集成 RagService/AI 调用，将结果作为被动回复发送给用户
     */
    private String processTextMessage(Map<String, String> messageMap) {
        String fromUserName = messageMap.get("FromUserName");
        String toUserName = messageMap.get("ToUserName");
        String content = messageMap.get("Content");
        
        // 这里可以集成你的AI聊天功能
        String replyContent = "收到您的消息：" + content + "，正在为您处理...";
        
        // TODO: 调用AI服务处理用户问题
        // replyContent = aiService.processQuestion(content);
        
        return createReplyMessage(fromUserName, toUserName, replyContent);
    }

    /**
     * 事件消息处理器（处理关注/取消关注/菜单点击等）
     * - subscribe: 欢迎语
     * - unsubscribe: 无需回复
     * - CLICK: 菜单点击，委托给 processMenuClick 处理
     * - VIEW: 菜单跳转，不需要被动回复
     */
    private String processEventMessage(Map<String, String> messageMap) {
        String fromUserName = messageMap.get("FromUserName");
        String toUserName = messageMap.get("ToUserName");
        String event = messageMap.get("Event");
        
        switch (event) {
            case "subscribe":
                return createReplyMessage(fromUserName, toUserName, 
                    "欢迎关注孪数光线AI助手！我是您的智能知识库助手，可以为您提供专业的问答服务。");
            case "unsubscribe":
                return ""; // 用户取消关注，不需要回复
            case "CLICK":
                return processMenuClick(messageMap);
            case "VIEW":
                return ""; // 菜单跳转不需要回复
            default:
                return "";
        }
    }

    /**
     * 菜单点击事件处理：根据 eventKey 返回对应的被动回复或触发相应业务
     * - START_CHAT: 引导用户发送问题
     * - KNOWLEDGE_BASE: 引导用户访问网页版知识库
     * - HELP: 返回简要使用说明
     */
    private String processMenuClick(Map<String, String> messageMap) {
        String fromUserName = messageMap.get("FromUserName");
        String toUserName = messageMap.get("ToUserName");
        String eventKey = messageMap.get("EventKey");
        
        switch (eventKey) {
            case "START_CHAT":
                return createReplyMessage(fromUserName, toUserName, "开始智能对话，请直接发送您的问题。");
            case "KNOWLEDGE_BASE":
                return createReplyMessage(fromUserName, toUserName, "知识库功能，请访问我们的网页版：https://your-domain.com");
            case "HELP":
                return createReplyMessage(fromUserName, toUserName, 
                    "使用帮助：\n1. 直接发送问题开始对话\n2. 点击菜单使用特定功能\n3. 访问网页版获得更好体验");
            default:
                return "";
        }
    }

    /**
     * 构建被动回复的 XML（文本消息）
     * 注意：微信要求被动回复在若干秒内返回，且必须使用指定的 XML 字段和 CDATA 包裹文本
     * @param fromUserName 发送方（用户的 openId）
     * @param toUserName 开发者微信号（公众平台帐号）
     * @param content 文本内容
     * @return 符合微信被动回复格式的 XML 字符串
     */
    private String createReplyMessage(String fromUserName, String toUserName, String content) {
        return String.format(
            "<xml>" +
            "<ToUserName><![CDATA[%s]]></ToUserName>" +
            "<FromUserName><![CDATA[%s]]></FromUserName>" +
            "<CreateTime>%d</CreateTime>" +
            "<MsgType><![CDATA[text]]></MsgType>" +
            "<Content><![CDATA[%s]]></Content>" +
            "</xml>",
            fromUserName, toUserName, System.currentTimeMillis() / 1000, content
        );
    }

    /**
     * 从 HttpServletRequest 中按行读取原始 XML 请求体
     * 用于读取微信服务器的 POST 消息体（XML 格式）
     */
    private String readXmlFromRequest(HttpServletRequest request) throws Exception {
        StringBuilder sb = new StringBuilder();
        BufferedReader reader = request.getReader();
        String line;
        while ((line = reader.readLine()) != null) {
            sb.append(line);
        }
        return sb.toString();
    }

    /**
     * 解析XML
     */
    private Map<String, String> parseXml(String xml) throws Exception {
        Map<String, String> map = new HashMap<>();
        
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        DocumentBuilder builder = factory.newDocumentBuilder();
        InputStream is = new ByteArrayInputStream(xml.getBytes("UTF-8"));
        Document document = builder.parse(is);
        
        Element root = document.getDocumentElement();
        NodeList nodeList = root.getChildNodes();
        
        for (int i = 0; i < nodeList.getLength(); i++) {
            if (nodeList.item(i) instanceof Element) {
                Element element = (Element) nodeList.item(i);
                map.put(element.getNodeName(), element.getTextContent());
            }
        }
        
        return map;
    }

    /**
     * 计算字符串的 SHA-1 哈希值并以小写十六进制字符串返回
     */
    private String sha1(String str) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-1");
        byte[] digest = md.digest(str.getBytes("UTF-8"));
        StringBuilder sb = new StringBuilder();
        for (byte b : digest) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}
