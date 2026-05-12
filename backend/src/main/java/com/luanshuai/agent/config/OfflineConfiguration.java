package com.luanshuai.agent.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 离线模式配置与文档缓存
 *
 * 功能：
 * 1. 检测网络离线状态
 * 2. 缓存检索到的文档供离线使用
 * 3. 预加载常用文档到缓存
 * 4. LRU 缓存淘汰（超过容量时）
 */
@Component
public class OfflineConfiguration {

    private static final Logger log = LoggerFactory.getLogger(OfflineConfiguration.class);

    /** 最大缓存文档数量 */
    private static final int MAX_CACHE_SIZE = 1000;

    /** 文档缓存（LRU） */
    private final Map<String, OfflineDocument> documentCache = new LinkedHashMap<>(MAX_CACHE_SIZE) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, OfflineDocument> eldest) {
            if (size() > MAX_CACHE_SIZE) {
                log.info("[Offline] Cache full, evicting: {}", eldest.getKey());
                return true;
            }
            return false;
        }
    };

    /** 离线模式标志 */
    private volatile boolean offlineMode = false;

    /** 活跃文档 ID 集合（用于快速判断） */
    private final ConcurrentHashMap<String, Boolean> activeDocIds = new ConcurrentHashMap<>();

    /**
     * 离线文档数据类
     */
    public static class OfflineDocument {
        private final String docId;
        private final String text;
        private final Map<String, Object> metadata;
        private final long cachedAt;
        private int accessCount;

        public OfflineDocument(String docId, String text, Map<String, Object> metadata) {
            this.docId = docId;
            this.text = text;
            this.metadata = metadata != null ? metadata : new java.util.HashMap<>();
            this.cachedAt = System.currentTimeMillis();
            this.accessCount = 0;
        }

        public String getDocId() { return docId; }
        public String getText() { return text; }
        public Map<String, Object> getMetadata() { return metadata; }
        public long getCachedAt() { return cachedAt; }
        public int getAccessCount() { return accessCount; }
        public void incrementAccess() { this.accessCount++; }
    }

    /**
     * 检测网络离线状态
     * 通过尝试访问向量模型服务来判断
     */
    public boolean isOfflineMode() {
        return offlineMode;
    }

    /**
     * 手动设置离线模式
     */
    public void setOfflineMode(boolean offline) {
        this.offlineMode = offline;
        log.info("[Offline] Offline mode set to: {}", offline);
    }

    /**
     * 检查网络连通性并更新离线状态
     */
    public boolean checkConnectivity(String url) {
        try {
            java.net.HttpURLConnection conn = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
            conn.setConnectTimeout(3000);
            conn.setReadTimeout(3000);
            conn.setRequestMethod("GET");
            int responseCode = conn.getResponseCode();
            boolean reachable = (responseCode == 200 || responseCode == 404); // 404 也算可达
            conn.disconnect();

            if (!reachable) {
                this.offlineMode = true;
                log.warn("[Offline] Network unreachable, offline mode enabled");
            }
            return reachable;
        } catch (Exception e) {
            this.offlineMode = true;
            log.warn("[Offline] Network check failed: {}, offline mode enabled", e.getMessage());
            return false;
        }
    }

    /**
     * 缓存文档
     *
     * @param docId 文档 ID
     * @param text 文档文本
     * @param metadata 元数据
     */
    public void cacheDocument(String docId, String text, Map<String, Object> metadata) {
        if (docId == null || text == null) return;

        synchronized (documentCache) {
            OfflineDocument doc = new OfflineDocument(docId, text, metadata);
            documentCache.put(docId, doc);
            activeDocIds.put(docId, true);
        }
        log.debug("[Offline] Cached document: {}", docId);
    }

    /**
     * 获取缓存的文档
     */
    public OfflineDocument getCachedDocument(String docId) {
        synchronized (documentCache) {
            OfflineDocument doc = documentCache.get(docId);
            if (doc != null) {
                doc.incrementAccess();
            }
            return doc;
        }
    }

    /**
     * 获取缓存的文档列表
     */
    public List<OfflineDocument> getCachedDocuments(List<String> docIds) {
        return docIds.stream()
                .map(this::getCachedDocument)
                .filter(d -> d != null)
                .toList();
    }

    /**
     * 检查文档是否已缓存
     */
    public boolean isDocumentCached(String docId) {
        return documentCache.containsKey(docId);
    }

    /**
     * 批量检查文档缓存状态
     */
    public Map<String, Boolean> checkDocumentsCached(List<String> docIds) {
        Map<String, Boolean> result = new java.util.HashMap<>();
        for (String docId : docIds) {
            result.put(docId, documentCache.containsKey(docId));
        }
        return result;
    }

    /**
     * 获取缓存统计信息
     */
    public Map<String, Object> getCacheStats() {
        synchronized (documentCache) {
            Map<String, Object> stats = new java.util.HashMap<>();
            stats.put("cacheSize", documentCache.size());
            stats.put("maxCacheSize", MAX_CACHE_SIZE);
            stats.put("activeDocCount", activeDocIds.size());
            stats.put("offlineMode", offlineMode);

            // 计算平均访问次数
            int totalAccess = documentCache.values().stream()
                    .mapToInt(OfflineDocument::getAccessCount)
                    .sum();
            stats.put("avgAccessCount", documentCache.isEmpty() ? 0 :
                    (double) totalAccess / documentCache.size());

            return stats;
        }
    }

    /**
     * 获取缓存文档数量
     */
    public int getCacheSize() {
        synchronized (documentCache) {
            return documentCache.size();
        }
    }

    /**
     * 预加载集合中的所有文档到缓存（从 MilvusDbService 拉取）
     *
     * @param collectionName 集合名称
     * @return 预加载的文档数量
     */
    public int preloadDocuments(String collectionName) {
        // 占位实现：实际预加载需通过 MilvusDbService 查询后缓存
        log.info("[Offline] preloadDocuments called for collection: {} (placeholder)", collectionName);
        return 0;
    }

    /**
     * 预加载文档到缓存
     *
     * @param docIds 文档 ID 列表
     * @param texts 文档文本列表
     * @param metadatas 元数据列表
     */
    public void preloadDocuments(List<String> docIds, List<String> texts, List<Map<String, Object>> metadatas) {
        if (docIds == null || texts == null || metadatas == null) return;
        if (docIds.size() != texts.size() || texts.size() != metadatas.size()) {
            log.error("[Offline] Preload size mismatch: ids={}, texts={}, metadatas={}",
                    docIds.size(), texts.size(), metadatas.size());
            return;
        }

        int count = 0;
        for (int i = 0; i < docIds.size(); i++) {
            cacheDocument(docIds.get(i), texts.get(i), metadatas.get(i));
            count++;
        }
        log.info("[Offline] Preloaded {} documents into cache", count);
    }

    /**
     * 清除所有缓存
     */
    public void clearCache() {
        synchronized (documentCache) {
            documentCache.clear();
            activeDocIds.clear();
        }
        log.info("[Offline] Cache cleared");
    }

    /**
     * 获取缓存中的所有文档
     */
    public List<OfflineDocument> getAllCachedDocuments() {
        synchronized (documentCache) {
            return new java.util.ArrayList<>(documentCache.values());
        }
    }

    /**
     * 获取活跃文档 ID 列表
     */
    public List<String> getActiveDocIds() {
        return new java.util.ArrayList<>(activeDocIds.keySet());
    }

    /**
     * 移除指定的文档
     */
    public void removeDocument(String docId) {
        synchronized (documentCache) {
            documentCache.remove(docId);
            activeDocIds.remove(docId);
        }
        log.info("[Offline] Removed document from cache: {}", docId);
    }

    /**
     * 获取缓存总大小（字节数）
     */
    public long getCacheSizeBytes() {
        synchronized (documentCache) {
            return documentCache.values().stream()
                    .mapToLong(d -> d.getText().getBytes().length)
                    .sum();
        }
    }

    /**
     * 获取最常访问的文档
     */
    public List<OfflineDocument> getMostAccessedDocuments(int limit) {
        synchronized (documentCache) {
            return documentCache.values().stream()
                    .sorted((a, b) -> Integer.compare(b.getAccessCount(), a.getAccessCount()))
                    .limit(limit)
                    .toList();
        }
    }
}
