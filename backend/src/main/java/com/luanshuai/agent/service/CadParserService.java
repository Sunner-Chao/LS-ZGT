package com.luanshuai.agent.service; // 包声明：该类属于 com.luanshuai.agent.service

import com.luanshuai.agent.model.CadDrawingResult; // CAD解析结果模型
import org.slf4j.Logger; // 日志接口
import org.slf4j.LoggerFactory; // 日志工厂
import org.springframework.beans.factory.annotation.Autowired; // 自动注入注解
import org.springframework.beans.factory.annotation.Value; // 注入配置属性注解
import org.springframework.stereotype.Service; // Service 注解，表示Spring服务组件
import org.springframework.web.reactive.function.client.WebClient; // 非阻塞HTTP客户端
import reactor.core.publisher.Mono; // Reactor Mono，用于异步返回单一结果
import org.springframework.core.ParameterizedTypeReference; // 用于带泛型的响应类型解析

import java.io.File; // 文件相关类
import java.nio.file.Files; // 文件工具类
import java.nio.file.Path; // 路径表示
import java.nio.file.Paths; // 路径工具
import java.util.HashMap; // HashMap实现
import java.util.List; // 列表接口
import java.util.Map; // Map接口

/**
 * CAD文件解析服务
 * 负责与外部 CAD 解析器（通常为 Python 服务）通信，提交文件解析请求并封装返回结果
 */
@Service // 标注为Spring管理的服务组件
public class CadParserService {
    
    private static final Logger log = LoggerFactory.getLogger(CadParserService.class); // 日志记录器
    
    @Value("${app.cad-parser.url:http://localhost:5001}") // 从配置读取CAD解析服务的URL，默认 http://localhost:5001
    private String cadParserUrl; // CAD解析服务基地址
    
    @Autowired // 注入用于创建WebClient的Builder
    private WebClient.Builder webClientBuilder; // WebClient构建器，用于发起HTTP请求
    
    /**
     * 解析CAD文件
     * @param filePath 文件路径
     * @return CAD解析结果
     */
    public Mono<CadDrawingResult> parseCadFile(String filePath) {
        log.info("开始解析CAD文件: {}", filePath);
        
        return Mono.fromCallable(() -> {
            // 检查文件是否存在
            Path path = Paths.get(filePath);
            if (!Files.exists(path)) {
                throw new RuntimeException("文件不存在: " + filePath);
            }
            
            // 检查文件类型
            String fileName = path.getFileName().toString().toLowerCase();
            if (!isSupportedCadFormat(fileName)) {
                throw new RuntimeException("不支持的CAD文件格式: " + fileName);
            }
            
            return path;
        })
        .flatMap(path -> {
            // 调用Python CAD解析服务
                    return webClientBuilder.build()
            .post()
            .uri(cadParserUrl + "/parse-cad")
            .bodyValue(createParseRequest(path.toString()))
            .retrieve()
            .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>(){})
                .map(result -> {
                    log.info("CAD文件解析成功: {}", filePath);
                    CadDrawingResult cadResult = new CadDrawingResult(
                        Paths.get(filePath).getFileName().toString(), 
                        filePath
                    );
                    cadResult.setParseStatus("success");
                    // 将Map结果转换为CadDrawingResult
                    if (result != null) {
                        cadResult.setDrawingInfo((Map<String, Object>) result.get("drawing_info"));
                        cadResult.setLayers((List<Map<String, Object>>) result.get("layers"));
                        cadResult.setEntities((List<Map<String, Object>>) result.get("entities"));
                        cadResult.setTexts((List<Map<String, Object>>) result.get("texts"));
                        cadResult.setAnnotations((List<Map<String, Object>>) result.get("annotations"));
                        cadResult.setStatistics((Map<String, Object>) result.get("statistics"));
                        cadResult.setMetadata((Map<String, Object>) result.get("metadata"));
                    }
                    return cadResult;
                })
                .doOnError(error -> {
                    log.error("CAD文件解析失败: {}", filePath, error);
                });
        })
        .onErrorResume(error -> {
            log.error("CAD解析服务调用失败: {}", filePath, error);
            CadDrawingResult errorResult = new CadDrawingResult(
                Paths.get(filePath).getFileName().toString(), 
                filePath
            );
            errorResult.setParseStatus("failed");
            errorResult.setErrorMessage(error.getMessage());
            return Mono.just(errorResult);
        });
    }
    
    /**
     * 检查是否为支持的CAD格式
     * @param fileName 文件名（小写）
     * @return 如果扩展名在支持列表中返回true，否则返回false
     */
    private boolean isSupportedCadFormat(String fileName) {
        String[] supportedFormats = {".dwg", ".dxf", ".dgn", ".dwf"}; // 支持的CAD扩展名
        for (String format : supportedFormats) { // 遍历支持列表
            if (fileName.endsWith(format)) { // 匹配扩展名
                return true; // 支持该格式
            }
        }
        return false; // 非支持格式
    }
    
    /**
     * 创建解析请求
     * @param filePath 文件路径
     * @return 返回封装的请求Map
     */
    private Map<String, Object> createParseRequest(String filePath) {
        Map<String, Object> request = new HashMap<>(); // 构建请求体
        request.put("file_path", filePath); // 指定文件路径
        request.put("extract_text", true); // 请求提取文本
        request.put("extract_dimensions", true); // 请求提取尺寸/标注
        request.put("extract_layers", true); // 请求提取图层信息
        request.put("extract_entities", true); // 请求提取实体/元素
        return request; // 返回请求Map
    }
    
    /**
     * 获取CAD文件信息
     */
    public Mono<Map<String, Object>> getCadFileInfo(String filePath) {
        log.info("获取CAD文件信息: {}", filePath);
        
        return webClientBuilder.build()
            .get()
            .uri(cadParserUrl + "/file-info?file_path=" + filePath)
            .retrieve()
            .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>(){})
            .doOnSuccess(info -> log.info("获取CAD文件信息成功: {}", filePath))
            .doOnError(error -> log.error("获取CAD文件信息失败: {}", filePath, error))
            .onErrorResume(error -> {
                Map<String, Object> errorInfo = new HashMap<>();
                errorInfo.put("error", error.getMessage());
                return Mono.just(errorInfo);
            });
    }
    
    /**
     * 提取CAD文件中的文本
     */
    public Mono<Map<String, Object>> extractTextFromCad(String filePath) {
        log.info("提取CAD文件文本: {}", filePath);
        
        Map<String, Object> request = new HashMap<>();
        request.put("file_path", filePath);
        
        return webClientBuilder.build()
            .post()
            .uri(cadParserUrl + "/extract-text")
            .bodyValue(request)
            .retrieve()
            .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>(){})
            .doOnSuccess(result -> log.info("CAD文本提取成功: {}", filePath))
            .doOnError(error -> log.error("CAD文本提取失败: {}", filePath, error))
            .onErrorResume(error -> {
                Map<String, Object> errorResult = new HashMap<>();
                errorResult.put("error", error.getMessage());
                return Mono.just(errorResult);
            });
    }
    
    /**
     * 检查CAD解析服务状态
     */
    public Mono<Boolean> checkServiceHealth() {
        return webClientBuilder.build()
            .get()
            .uri(cadParserUrl + "/health")
            .retrieve()
            .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>(){})
            .map(response -> {
                String status = (String) response.get("status");
                return "ok".equals(status) || "healthy".equals(status);
            })
            .onErrorReturn(false);
    }
}
