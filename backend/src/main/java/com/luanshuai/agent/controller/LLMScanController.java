package com.luanshuai.agent.controller;

import java.io.File;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Scanner;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.reactive.function.client.WebClient;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.luanshuai.agent.model.ApiResponse;

/**
 * LLM 服务扫描控制器
 * 提供扫描本地 LLM 服务和模型文件的功能
 */
@RestController
@RequestMapping("/api/llm")
@CrossOrigin(origins = "*")
public class LLMScanController {

 private static final Logger log = LoggerFactory.getLogger(LLMScanController.class);
 private final ObjectMapper objectMapper = new ObjectMapper();

 @Autowired
 private WebClient.Builder webClientBuilder;

 /**
 * 扫描局域网内可用的 llama.cpp/Ollama 服务
 */
 @GetMapping("/scan-services")
 public ResponseEntity<?> scanLlmServices() {
 List<Map<String, Object>> foundServices = new ArrayList<>();

 // 双容器架构：对话模型服务和向量模型服务分离
 String[][] services = {
 {"http://llama-chat:8080", "llama.cpp Chat (Docker)", "/v1/models", "chat"},
 {"http://llama-embedding:8080", "llama.cpp Embedding (Docker)", "/v1/models", "embedding"},
 {"http://host.docker.internal:11434", "Ollama (宿主机)", "/api/tags", "ollama"},
 {"http://host.docker.internal:1234", "LM Studio (宿主机)", "/v1/models", "lmstudio"},
 {"http://localhost:8080", "llama.cpp (alt)", "/v1/models", "chat"},
 {"http://localhost:8082", "llama.cpp (alt2)", "/v1/models", "embedding"},
 };

 for (String[] service : services) {
 String baseUrl = service[0];
 String type = service[1];
 String modelPath = service[2];
 String serviceType = service[3];

 try {
 long startTime = System.currentTimeMillis();
 String response = sendGetRequest(baseUrl + modelPath, 3000);
 long responseTime = System.currentTimeMillis() - startTime;

 if (response != null && !response.isEmpty()) {
 Map<String, Object> serviceInfo = new HashMap<>();
 serviceInfo.put("url", baseUrl);
 serviceInfo.put("type", type);
 serviceInfo.put("serviceType", serviceType);
 serviceInfo.put("status", "online");
 serviceInfo.put("responseTime", responseTime);

 // 解析模型列表
 List<String> models = parseModels(response, type);
 serviceInfo.put("models", models);
 serviceInfo.put("modelCount", models.size());
 foundServices.add(serviceInfo);
 log.info("[LLM Scan] 发现服务: {} ({}), 模型数: {}", baseUrl, type, models.size());
 }
 } catch (Exception e) {
 log.debug("[LLM Scan] 服务不可用: {} - {}", baseUrl, e.getMessage());
 }
 }

 return ResponseEntity.ok(ApiResponse.success("扫描完成", foundServices));
 }

 private String sendGetRequest(String urlStr, int timeout) {
 HttpURLConnection conn = null;
 try {
 URL url = new URL(urlStr);
 conn = (HttpURLConnection) url.openConnection();
 conn.setRequestMethod("GET");
 conn.setConnectTimeout(timeout);
 conn.setReadTimeout(timeout);
 conn.setRequestProperty("User-Agent", "LS-ZGT-LLM-Scanner/1.0");

 int responseCode = conn.getResponseCode();
 if (responseCode == 200) {
 try (Scanner scanner = new Scanner(conn.getInputStream())) {
 scanner.useDelimiter("\\A");
 return scanner.hasNext() ? scanner.next() : "";
 }
 }
 } catch (Exception e) {
 log.debug("[LLM Scan] 请求失败: {} - {}", urlStr, e.getMessage());
 } finally {
 if (conn != null) {
 conn.disconnect();
 }
 }
 return null;
 }

 private List<String> parseModels(String response, String type) {
 List<String> models = new ArrayList<>();
 try {
 JsonNode root = objectMapper.readTree(response);

 if ("Ollama".equals(type)) {
 // Ollama 使用 /api/tags 格式
 if (root.has("models")) {
 for (JsonNode model : root.get("models")) {
 if (model.has("name")) {
 models.add(model.get("name").asText());
 }
 }
 }
 } else {
 // llama.cpp 和兼容 API 使用 /v1/models 格式
 if (root.has("data")) {
 for (JsonNode item : root.get("data")) {
 if (item.has("id")) {
 models.add(item.get("id").asText());
 }
 }
 } else if (root.has("models")) {
 for (JsonNode model : root.get("models")) {
 if (model.has("name")) {
 models.add(model.get("name").asText());
 }
 }
 }
 }
 } catch (Exception e) {
 log.debug("解析模型列表失败: {}", e.getMessage());
 }
 return models;
 }

 private static final String HOST_MODELS_DIR = "/host_models";

 /**
 * 获取模型目录（固定为 /host_models）
 */
 private List<String> getHostMountDirs() {
 List<String> dirs = new ArrayList<>();
 dirs.add(HOST_MODELS_DIR);
 return dirs;
 }

 /**
 * 获取可浏览的宿主机映射目录列表
 */
 @GetMapping("/list-host-dirs")
 public ResponseEntity<?> listHostDirectories() {
 List<Map<String, Object>> availableDirs = new ArrayList<>();

 for (String dirPath : getHostMountDirs()) {
 File dir = new File(dirPath);
 if (dir.exists() && dir.isDirectory()) {
 Map<String, Object> info = new HashMap<>();
 info.put("path", dirPath);
 info.put("name", "模型目录");
 info.put("exists", true);
 info.put("hasChildren", dir.listFiles() != null && dir.listFiles().length > 0);
 availableDirs.add(info);
 }
 }

 // 如果没有任何映射目录存在，返回提示
 if (availableDirs.isEmpty()) {
 return ResponseEntity.ok(ApiResponse.success("未发现模型目录", Map.of(
 "dirs", availableDirs,
 "hint", "请在 docker-compose.yml 中添加 volume 映射，例如：../models:/host_models"
 )));
 }

 return ResponseEntity.ok(ApiResponse.success("发现模型目录", Map.of("dirs", availableDirs)));
 }

 /**
 * 列出目录结构（用于前端浏览选择）
 * 支持浏览所有 /host_* 映射目录及其子目录
 */
 @GetMapping("/list-dirs")
 public ResponseEntity<?> listDirectories(@RequestParam(value = "path", required = false) String path) {
 try {
 // 如果没有指定路径，返回可浏览的根目录列表
 if (path == null || path.isEmpty() || path.equals("/")) {
 return listHostDirectories();
 }

 File dir = new File(path);
 String canonicalPath = dir.getCanonicalPath();

 // 安全检查：只允许浏览 /host_* 开头的目录
 boolean isAllowed = false;
 for (String hostDir : getHostMountDirs()) {
 File hostDirFile = new File(hostDir);
 if (hostDirFile.exists()) {
 String hostCanonical = hostDirFile.getCanonicalPath();
 if (canonicalPath.startsWith(hostCanonical) || canonicalPath.equals(hostCanonical)) {
 isAllowed = true;
 break;
 }
 }
 }

 if (!isAllowed) {
 return ResponseEntity.ok(ApiResponse.error("只能浏览宿主机映射目录（/host_*），请从根目录重新选择"));
 }

 if (!dir.exists() || !dir.isDirectory()) {
 return ResponseEntity.ok(ApiResponse.error("目录不存在: " + path));
 }

 List<Map<String, Object>> items = new ArrayList<>();
 File[] files = dir.listFiles();
 if (files != null) {
 for (File f : files) {
 if (f.isDirectory()) {
 Map<String, Object> item = new HashMap<>();
 item.put("name", f.getName());
 item.put("path", f.getAbsolutePath());
 item.put("type", "directory");
 item.put("hasChildren", f.listFiles() != null && f.listFiles().length > 0);
 items.add(item);
 } else if (f.getName().toLowerCase().endsWith(".gguf")) {
 Map<String, Object> item = new HashMap<>();
 item.put("name", f.getName());
 item.put("path", f.getAbsolutePath());
 item.put("type", "file");
 item.put("size", f.length());
 item.put("sizeFormatted", formatFileSize(f.length()));
 items.add(item);
 }
 }
 }

 // 计算父目录（检查是否还在允许范围内）
 String parentPath = dir.getParent() != null ? dir.getParent() : "/";

 // 检查当前目录是否是映射根目录（如 /host_d）
 boolean currentIsHostRoot = isPathInHostDirs(canonicalPath);
 if (currentIsHostRoot) {
 // 如果当前是映射根目录，返回 "/" 回到选择界面
 parentPath = "/";
 } else {
 // 检查父目录是否是映射根目录
 boolean parentIsHostRoot = isPathInHostDirs(parentPath);
 if (parentIsHostRoot) {
 // 父目录是映射根目录，返回 "/" 回到选择界面
 parentPath = "/";
 } else if (!isPathAllowed(parentPath)) {
 // 父目录不在允许范围内，返回根目录列表
 parentPath = "/";
 }
 }

 return ResponseEntity.ok(ApiResponse.success("列出目录成功", Map.of(
 "currentPath", canonicalPath,
 "parentPath", parentPath,
 "items", items
 )));
 } catch (Exception e) {
 return ResponseEntity.ok(ApiResponse.error("列出目录失败: " + e.getMessage()));
 }
 }

 private boolean isPathInHostDirs(String path) {
 for (String hostDir : getHostMountDirs()) {
 if (path.equals(hostDir)) return true;
 }
 return false;
 }

 private boolean isPathAllowed(String path) {
 try {
 String canonical = new File(path).getCanonicalPath();
 for (String hostDir : getHostMountDirs()) {
 File hostDirFile = new File(hostDir);
 if (hostDirFile.exists()) {
 String hostCanonical = hostDirFile.getCanonicalPath();
 if (canonical.startsWith(hostCanonical)) return true;
 }
 }
 } catch (Exception e) {
 log.debug("路径检查失败: {}", e.getMessage());
 }
 return false;
 }

 /**
 * 扫描宿主机目录中的 GGUF 模型文件
 * 通过 Docker volume 映射扫描 /host_models 目录
 */
 @PostMapping("/scan-folder")
 public ResponseEntity<?> scanFolder(@RequestBody Map<String, String> request) {
 String hostPath = request.getOrDefault("path", "");

 // 优先使用用户指定的路径
 String scanPath = hostPath;
 if (scanPath == null || scanPath.isEmpty()) {
 scanPath = "/host_models";
 }

 File dir = new File(scanPath);
 if (!dir.exists() || !dir.isDirectory()) {
 return ResponseEntity.ok(Map.of(
 "success", false,
 "message", "目录不存在或无法访问: " + scanPath,
 "hostPath", hostPath
 ));
 }

 List<Map<String, Object>> models = new ArrayList<>();
 scanGgufFiles(dir, models, 0);

 return ResponseEntity.ok(ApiResponse.success("扫描完成", Map.of(
 "path", scanPath,
 "count", models.size(),
 "models", models
 )));
 }

 private void scanGgufFiles(File dir, List<Map<String, Object>> models, int depth) {
 if (depth > 5) return;
 File[] files = dir.listFiles();
 if (files == null) return;
 for (File f : files) {
 if (f.isDirectory()) {
 scanGgufFiles(f, models, depth + 1);
 } else if (f.getName().toLowerCase().endsWith(".gguf")) {
 Map<String, Object> info = new HashMap<>();
 info.put("name", f.getName());
 info.put("path", f.getAbsolutePath());
 info.put("size", f.length());
 info.put("sizeFormatted", formatFileSize(f.length()));
 models.add(info);
 }
 }
 }

 /**
 * 扫描指定目录下的模型文件
 */
 @GetMapping("/scan-models")
 public ResponseEntity<?> scanModelFiles(
 @RequestParam(value = "path", required = false, defaultValue = "/models") String modelPath) {
 List<Map<String, Object>> foundModels = new ArrayList<>();

 // 常见的模型目录
 String[] searchPaths = {
 modelPath,
 "/app/models",
 "/models",
 "/home/models",
 "/root/models",
 "/host_models",
 System.getProperty("user.home") + "/models",
 "/llama.cpp/models",
 };

 Set<String> scannedDirs = new HashSet<>();

 for (String dir : searchPaths) {
 if (scannedDirs.contains(dir)) continue;

 File modelsDir = new File(dir);
 if (modelsDir.exists() && modelsDir.isDirectory()) {
 scannedDirs.add(dir);
 log.info("[LLM Scan] 扫描目录: {}", dir);
 scanDirectoryForModels(modelsDir, foundModels, 3);
 }
 }

 return ResponseEntity.ok(ApiResponse.success("扫描完成", Map.of(
 "path", modelPath,
 "modelCount", foundModels.size(),
 "models", foundModels
 )));
 }

 private void scanDirectoryForModels(File dir, List<Map<String, Object>> models, int maxDepth) {
 if (maxDepth <= 0 || !dir.exists() || !dir.isDirectory()) return;

 File[] files = dir.listFiles();
 if (files == null) return;

 for (File file : files) {
 if (file.isDirectory()) {
 scanDirectoryForModels(file, models, maxDepth - 1);
 } else {
 String name = file.getName().toLowerCase();
 // 支持的模型文件格式
 if (name.endsWith(".gguf") || name.endsWith(".ggml") ||
 name.endsWith(".bin") || name.endsWith(".pt") ||
 name.endsWith(".safetensors") || name.endsWith(".ckpt")) {

 Map<String, Object> modelInfo = new HashMap<>();
 modelInfo.put("name", file.getName());
 modelInfo.put("path", file.getAbsolutePath());
 modelInfo.put("size", file.length());
 modelInfo.put("sizeFormatted", formatFileSize(file.length()));
 modelInfo.put("type", getModelType(name));

 // 检查是否为量化模型
 if (name.contains("q2_k") || name.contains("q3_") || name.contains("q4_") ||
 name.contains("q5_") || name.contains("q6_") || name.contains("q8_")) {
 modelInfo.put("quantized", true);
 }

 models.add(modelInfo);
 log.info("[LLM Scan] 发现模型: {} ({}), 大小: {}",
 file.getName(), modelInfo.get("type"), modelInfo.get("sizeFormatted"));
 }
 }
 }
 }

 private String getModelType(String filename) {
 if (filename.contains("qwen")) return "Qwen";
 if (filename.contains("llama")) return "LLaMA";
 if (filename.contains("mistral")) return "Mistral";
 if (filename.contains("phi")) return "Phi";
 if (filename.contains("deepseek")) return "DeepSeek";
 if (filename.contains("yi")) return "Yi";
 if (filename.contains("gemma")) return "Gemma";
 if (filename.contains("bge")) return "Embedding";
 if (filename.contains("embedding")) return "Embedding";
 if (filename.contains("chatglm")) return "ChatGLM";
 if (filename.contains("baichuan")) return "Baichuan";
 return "Unknown";
 }

 private String formatFileSize(long size) {
 if (size < 1024) return size + " B";
 if (size < 1024 * 1024) return String.format("%.1f KB", size / 1024.0);
 if (size < 1024 * 1024 * 1024) return String.format("%.1f MB", size / (1024.0 * 1024));
 return String.format("%.2f GB", size / (1024.0 * 1024 * 1024));
 }

 /**
 * 切换 LLM 模型（通过宿主机管理服务）
 * 支持 chat 和 embedding 两种服务类型
 */
 @PostMapping("/switch-model")
 public ResponseEntity<?> switchModel(@RequestBody Map<String, String> request) {
 String serviceType = request.getOrDefault("serviceType", "chat");
 String modelPath = request.get("modelPath");

 if (modelPath == null || modelPath.isEmpty()) {
 return ResponseEntity.ok(ApiResponse.error("模型路径不能为空"));
 }

 if (!serviceType.equals("chat") && !serviceType.equals("embedding")) {
 return ResponseEntity.ok(ApiResponse.error("服务类型必须是 chat 或 embedding"));
 }

 try {
 log.info("[LLM Switch] 切换 {} 服务模型: {}", serviceType, modelPath);

 // 调用宿主机管理服务（同步调用避免 WebFlux 线程问题）
 String managerUrl = "http://host.docker.internal:5000/switch";

 Map<String, String> payload = new HashMap<>();
 payload.put("service", serviceType);
 payload.put("model", modelPath);

 // 使用 RestTemplate 同步调用（避免 WebFlux blocking 错误）
 org.springframework.web.client.RestTemplate restTemplate = new org.springframework.web.client.RestTemplate();
 org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
 headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
 org.springframework.http.HttpEntity<java.util.Map<String, String>> entity =
     new org.springframework.http.HttpEntity<>(payload, headers);

 String response = restTemplate.postForObject(managerUrl, entity, String.class);

 log.info("[LLM Switch] 管理服务响应: {}", response);

 // 解析响应
 if (response != null && response.contains("\"success\":true")) {
   return ResponseEntity.ok(ApiResponse.success("模型切换成功", Map.of(
     "serviceType", serviceType,
     "modelPath", modelPath
   )));
 } else {
   return ResponseEntity.ok(ApiResponse.error("切换失败: " + response));
 }
 } catch (Exception e) {
 log.error("[LLM Switch] 切换失败: {}", e.getMessage(), e);
 return ResponseEntity.ok(ApiResponse.error("切换模型失败: " + e.getMessage()));
 }
 }

 /**
 * 获取当前加载的模型信息
 */
 @GetMapping("/current-models")
 public ResponseEntity<?> getCurrentModels() {
 Map<String, Object> result = new HashMap<>();

 // 检查 chat 服务
 try {
 String chatResponse = sendGetRequest("http://llama-chat:8080/v1/models", 3000);
 if (chatResponse != null) {
 List<String> models = parseModels(chatResponse, "llama.cpp");
 result.put("chat", Map.of(
 "url", "http://llama-chat:8080",
 "models", models,
 "status", "online"
 ));
 } else {
 result.put("chat", Map.of("status", "offline"));
 }
 } catch (Exception e) {
 result.put("chat", Map.of("status", "error", "message", e.getMessage()));
 }

 // 检查 embedding 服务
 try {
 String embResponse = sendGetRequest("http://llama-embedding:8080/v1/models", 3000);
 if (embResponse != null) {
 List<String> models = parseModels(embResponse, "llama.cpp");
 result.put("embedding", Map.of(
 "url", "http://llama-embedding:8080",
 "models", models,
 "status", "online"
 ));
 } else {
 result.put("embedding", Map.of("status", "offline"));
 }
 } catch (Exception e) {
 result.put("embedding", Map.of("status", "error", "message", e.getMessage()));
 }

 return ResponseEntity.ok(ApiResponse.success("获取成功", result));
 }
}
