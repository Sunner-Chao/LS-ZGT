package com.luanshuai.agent.service; // 包声明：该类属于 com.luanshuai.agent.service 包

import com.luanshuai.agent.model.BimModelResult; // 导入BIM解析结果模型类
import org.slf4j.Logger; // 导入日志接口
import org.slf4j.LoggerFactory; // 导入日志工厂，用于创建Logger
import org.springframework.beans.factory.annotation.Autowired; // 导入自动注入注解
import org.springframework.beans.factory.annotation.Value; // 导入用于注入配置属性的注解
import org.springframework.stereotype.Service; // 导入Service注解，标注此类为服务组件
import org.springframework.web.reactive.function.client.WebClient; // 导入WebClient用于非阻塞HTTP调用
import reactor.core.publisher.Mono; // 导入Reactor的Mono，用于返回异步单值
import org.springframework.core.ParameterizedTypeReference; // 导入用于处理带泛型的响应类型

import java.nio.file.Files; // 导入文件工具类
import java.nio.file.Path; // 导入路径类
import java.nio.file.Paths; // 导入Paths工具类
import java.util.HashMap; // 导入HashMap
import java.util.List; // 导入List集合接口
import java.util.Map; // 导入Map接口

/**
 * BIM文件解析服务
 * 负责与外部BIM解析器进行通信：发送解析请求并封装返回结果
 */
@Service // 声明为Spring管理的服务组件
public class BimParserService {
    
    private static final Logger log = LoggerFactory.getLogger(BimParserService.class); // 日志对象，用于记录运行信息和错误
    
    @Value("${app.bim-parser.url:http://localhost:5002}") // 从配置中读取BIM解析服务的URL，若未配置则使用默认值
    private String bimParserUrl; // BIM解析服务的地址
    
    @Autowired // 注入WebClient.Builder实例以便构建HTTP客户端
    private WebClient.Builder webClientBuilder; // 用于发起HTTP请求的客户端构建器
    
    /**
     * 解析BIM文件
     * @param filePath 文件路径
     * @return BIM解析结果
     */
    public Mono<BimModelResult> parseBimFile(String filePath) {
        log.info("开始解析BIM文件: {}", filePath);
        
        return Mono.fromCallable(() -> {
            // 检查文件是否存在
            Path path = Paths.get(filePath);
            if (!Files.exists(path)) {
                throw new RuntimeException("文件不存在: " + filePath);
            }
            
            // 检查文件类型
            String fileName = path.getFileName().toString().toLowerCase();
            if (!isSupportedBimFormat(fileName)) {
                throw new RuntimeException("不支持的BIM文件格式: " + fileName);
            }
            
            return path;
        })
        .flatMap(path -> {
            // 调用Python BIM解析服务
                    return webClientBuilder.build()
            .post()
            .uri(bimParserUrl + "/parse-bim")
            .bodyValue(createParseRequest(path.toString()))
            .retrieve()
            .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>(){})
                .map(result -> {
                    log.info("BIM文件解析成功: {}", filePath);
                    BimModelResult bimResult = new BimModelResult(
                        Paths.get(filePath).getFileName().toString(), 
                        filePath
                    );
                    bimResult.setParseStatus("success");
                    // 将Map结果转换为BimModelResult
                    if (result != null) {
                        bimResult.setModelInfo((Map<String, Object>) result.get("model_info"));
                        bimResult.setProjectInfo((Map<String, Object>) result.get("project_info"));
                        bimResult.setElements((List<Map<String, Object>>) result.get("elements"));
                        bimResult.setMaterials((List<Map<String, Object>>) result.get("materials"));
                        bimResult.setViews((List<Map<String, Object>>) result.get("views"));
                        bimResult.setFamilies((List<Map<String, Object>>) result.get("families"));
                        bimResult.setSystems((List<Map<String, Object>>) result.get("systems"));
                        bimResult.setSpaces((List<Map<String, Object>>) result.get("spaces"));
                        bimResult.setStatistics((Map<String, Object>) result.get("statistics"));
                        bimResult.setMetadata((Map<String, Object>) result.get("metadata"));
                    }
                    return bimResult;
                })
                .doOnError(error -> {
                    log.error("BIM文件解析失败: {}", filePath, error);
                });
        })
        .onErrorResume(error -> {
            log.error("BIM解析服务调用失败: {}", filePath, error);
            BimModelResult errorResult = new BimModelResult(
                Paths.get(filePath).getFileName().toString(), 
                filePath
            );
            errorResult.setParseStatus("failed");
            errorResult.setErrorMessage(error.getMessage());
            return Mono.just(errorResult);
        });
    }
    
    /**
     * 检查是否为支持的BIM格式
     * @param fileName 文件名（小写）
     * @return 如果扩展名在支持列表中返回true，否则返回false
     */
    private boolean isSupportedBimFormat(String fileName) {
        String[] supportedFormats = {".rvt", ".ifc", ".nwd", ".nwc", ".rfa", ".rte"}; // 支持的BIM文件扩展名
        for (String format : supportedFormats) { // 遍历支持列表
            if (fileName.endsWith(format)) { // 如果文件名以某个受支持的后缀结尾
                return true; // 表示支持该格式
            }
        }
        return false; // 未匹配到，表示不支持
    }
    
    /**
     * 创建解析请求
     * @param filePath 目标文件路径
     * @return 返回一个Map表示请求体，包含需要提取的项
     */
    private Map<String, Object> createParseRequest(String filePath) {
        Map<String, Object> request = new HashMap<>(); // 构造请求Map
        request.put("file_path", filePath); // 指定要解析的文件路径
        request.put("extract_elements", true); // 指定提取元素
        request.put("extract_materials", true); // 指定提取材料信息
        request.put("extract_views", true); // 指定提取视图信息
        request.put("extract_families", true); // 指定提取族/系列
        request.put("extract_systems", true); // 指定提取系统信息
        request.put("extract_spaces", true); // 指定提取空间信息
        return request; // 返回构建完成的请求体
    }
    
    /**
     * 获取BIM文件信息
     * @param filePath 文件路径
     * @return 返回Mono<Map>包含文件元信息或错误信息
     */
    public Mono<Map<String, Object>> getBimFileInfo(String filePath) {
        log.info("获取BIM文件信息: {}", filePath); // 记录请求
        
        return webClientBuilder.build() // 创建WebClient实例
            .get() // 使用GET请求
            .uri(bimParserUrl + "/file-info?file_path=" + filePath) // 构建URI并添加查询参数
            .retrieve() // 发起请求
            .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>(){} ) // 解析为Map结构
            .doOnSuccess(info -> log.info("获取BIM文件信息成功: {}", filePath)) // 成功时记录日志
            .doOnError(error -> log.error("获取BIM文件信息失败: {}", filePath, error)) // 失败时记录日志
            .onErrorResume(error -> { // 出错时返回包含错误信息的Map
                Map<String, Object> errorInfo = new HashMap<>();
                errorInfo.put("error", error.getMessage()); // 将异常信息放入Map
                return Mono.just(errorInfo); // 返回Mono<Map>
            });
    }
    
    /**
     * 提取BIM模型中的元素信息
     */
    public Mono<Map<String, Object>> extractElementsFromBim(String filePath) {
        log.info("提取BIM模型元素: {}", filePath); // 记录请求的文件路径
        
        Map<String, Object> request = new HashMap<>(); // 构建请求体的Map实例
        request.put("file_path", filePath); // 将文件路径加入请求体
        
        return webClientBuilder.build() // 创建WebClient实例
            .post() // 使用POST方法
            .uri(bimParserUrl + "/extract-elements") // 指定提取元素接口路径
            .bodyValue(request) // 添加请求体
            .retrieve() // 执行请求并准备解析响应
            .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>(){})
            .doOnSuccess(result -> log.info("BIM元素提取成功: {}", filePath)) // 远程调用成功时记录日志
            .doOnError(error -> log.error("BIM元素提取失败: {}", filePath, error)) // 远程调用失败时记录错误
            .onErrorResume(error -> {
                Map<String, Object> errorResult = new HashMap<>();
                errorResult.put("error", error.getMessage());
                return Mono.just(errorResult);
            });
    }
    
    /**
     * 生成BIM模型碰撞检测报告
     */
    public Mono<Map<String, Object>> generateCollisionReport(String filePath) {
        log.info("生成BIM碰撞检测报告: {}", filePath); // 记录碰撞检测请求的文件路径
        
        Map<String, Object> request = new HashMap<>(); // 构建请求体Map用于发送给碰撞检测服务
        request.put("file_path", filePath); // 设置要检测的文件路径
        request.put("tolerance", 0.01); // 设定检测容差（1cm）
        request.put("include_visualization", true); // 请求包含可视化数据（若支持）
        
        return webClientBuilder.build() // 创建WebClient并调用碰撞检测接口
            .post() // 使用POST请求
            .uri(bimParserUrl + "/collision-detection") // 指定接口路径
            .bodyValue(request) // 设置请求体
            .retrieve() // 执行请求并获取响应
            .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>(){})
            .doOnSuccess(result -> log.info("BIM碰撞检测报告生成成功: {}", filePath)) // 成功时记录日志
            .doOnError(error -> log.error("BIM碰撞检测报告生成失败: {}", filePath, error)) // 出错时记录错误
            .onErrorResume(error -> {
                Map<String, Object> errorResult = new HashMap<>();
                errorResult.put("error", error.getMessage());
                return Mono.just(errorResult);
            });
    }
    
    /**
     * 检查BIM解析服务状态
     * @return 返回Mono<Boolean>表示服务是否可用
     */
    public Mono<Boolean> checkServiceHealth() {
        return webClientBuilder.build() // 构建WebClient并调用health接口
            .get() // GET请求
            .uri(bimParserUrl + "/health") // 健康检查接口
            .retrieve() // 获取响应
            .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>(){} ) // 将响应解析为Map
            .map(response -> { // 从返回的Map中读取status字段判断健康状态
                String status = (String) response.get("status"); // 读取status字段
                return "ok".equals(status) || "healthy".equals(status); // 如果为ok或healthy则返回true
            })
            .onErrorReturn(false); // 出错时默认返回false，表示不可用
    }
}
