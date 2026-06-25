package com.luanshuai.agent.service.search;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * BM25 关键词检索服务
 *
 * 实现经典的 BM25 (Best Matching 25) 排序算法，用于稀疏检索
 * 支持中英文分词，对中文使用简单的字符级 N-gram
 */
@Service
public class Bm25Service {

    private static final Logger log = LoggerFactory.getLogger(Bm25Service.class);

    private static final double DEFAULT_K1 = 1.5;
    private static final double DEFAULT_B = 0.75;
    private static final int MIN_TERM_LENGTH = 2;

    private final Map<String, Map<String, Integer>> invertedIndex = new HashMap<>();
    private final Map<String, String> docTexts = new HashMap<>();
    private final Map<String, String> docMetadatas = new HashMap<>();
    private final Map<String, String> docCollections = new HashMap<>();
    private final Map<String, String> docSources = new HashMap<>();
    private final Map<String, Integer> docLengths = new HashMap<>();
    private volatile double avgDocLength = 0.0;
    private volatile int totalDocs = 0;
    private volatile long indexVersion = 0;

    /**
     * 为文档集合建立索引
     */
    public void indexDocuments(List<Map<String, Object>> documents) {
        if (documents == null || documents.isEmpty()) {
            return;
        }

        synchronized (this) {
            int accepted = 0;

            for (Map<String, Object> doc : documents) {
                String id = String.valueOf(doc.getOrDefault("id", UUID.randomUUID().toString()));
                String text = String.valueOf(doc.getOrDefault("text", ""));
                String metadata = asString(doc.get("metadata"));
                String collection = asString(doc.get("collection"));
                String source = asString(doc.get("source"));
                if ((source == null || source.isEmpty()) && metadata != null) {
                    source = extractJsonStringField(metadata, "source");
                }

                if (id.trim().isEmpty() || text.isEmpty()) continue;

                docTexts.put(id, text);
                putOrRemove(docMetadatas, id, metadata);
                putOrRemove(docCollections, id, collection);
                putOrRemove(docSources, id, source);
                accepted++;
            }

            rebuildIndexLocked();
            indexVersion++;
            log.info("[BM25] Indexed {} new/updated documents, total={}, avg length={}, vocab size={}",
                    accepted, totalDocs, avgDocLength, invertedIndex.size());
        }
    }

    /**
     * 移除同一集合中同一来源文件的旧 BM25 片段，避免文件重入库后关键词检索命中过期页码。
     */
    public void removeDocumentsBySource(String collectionName, String source) {
        if (source == null || source.trim().isEmpty()) {
            return;
        }

        synchronized (this) {
            List<String> idsToRemove = docSources.entrySet().stream()
                    .filter(entry -> source.equals(entry.getValue()))
                    .filter(entry -> collectionName == null || collectionName.trim().isEmpty()
                            || collectionName.equals(docCollections.get(entry.getKey())))
                    .map(Map.Entry::getKey)
                    .collect(Collectors.toList());

            if (idsToRemove.isEmpty()) {
                return;
            }

            for (String id : idsToRemove) {
                docTexts.remove(id);
                docMetadatas.remove(id);
                docCollections.remove(id);
                docSources.remove(id);
                docLengths.remove(id);
            }
            rebuildIndexLocked();
            indexVersion++;
            log.info("[BM25] Removed {} stale documents for collection={}, source={}",
                    idsToRemove.size(), collectionName, source);
        }
    }

    /**
     * 搜索文档
     */
    public List<Map<String, Object>> search(String query, int topK) {
        return search(query, null, topK, DEFAULT_K1, DEFAULT_B);
    }

    public List<Map<String, Object>> search(String query, String collectionName, int topK) {
        return search(query, collectionName, topK, DEFAULT_K1, DEFAULT_B);
    }

    public List<Map<String, Object>> search(String query, int topK, double k1, double b) {
        return search(query, null, topK, k1, b);
    }

    public List<Map<String, Object>> search(String query, String collectionName, int topK, double k1, double b) {
        if (query == null || query.trim().isEmpty()) {
            return Collections.emptyList();
        }
        if (totalDocs == 0) {
            return Collections.emptyList();
        }

        long start = System.currentTimeMillis();
        List<String> queryTerms = tokenize(query);
        Map<String, Double> docScores = new HashMap<>();

        Map<String, Integer> docFreq = new HashMap<>();
        for (String term : invertedIndex.keySet()) {
            docFreq.put(term, invertedIndex.get(term).size());
        }

        int n = totalDocs;
        Map<String, Double> idfCache = new HashMap<>();
        for (Map.Entry<String, Integer> entry : docFreq.entrySet()) {
            String term = entry.getKey();
            int df = entry.getValue();
            double idf = Math.log((n - df + 0.5) / (df + 0.5) + 1);
            idfCache.put(term, idf);
        }

        for (String term : queryTerms) {
            if (term.length() < MIN_TERM_LENGTH) continue;

            double idf = idfCache.getOrDefault(term, Math.log((n + 0.5) / 0.5 + 1));
            Map<String, Integer> postings = invertedIndex.get(term);

            if (postings == null || postings.isEmpty()) continue;

            for (Map.Entry<String, Integer> posting : postings.entrySet()) {
                String docId = posting.getKey();
                int tf = posting.getValue();
                int docLen = docLengths.getOrDefault(docId, (int) avgDocLength);
                double safeAvgDocLength = avgDocLength > 0 ? avgDocLength : 1.0;

                double numerator = tf * (k1 + 1);
                double denominator = tf + k1 * (1 - b + b * docLen / safeAvgDocLength);
                double score = idf * numerator / denominator;

                docScores.merge(docId, score, Double::sum);
            }
        }

        List<Map.Entry<String, Double>> sorted = docScores.entrySet().stream()
                .filter(entry -> collectionName == null || collectionName.trim().isEmpty()
                        || collectionName.equals(docCollections.get(entry.getKey())))
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .limit(topK)
                .collect(Collectors.toList());

        List<Map<String, Object>> results = new ArrayList<>();
        for (int rank = 0; rank < sorted.size(); rank++) {
            Map.Entry<String, Double> entry = sorted.get(rank);
            String docId = entry.getKey();
            Double score = entry.getValue();

            Map<String, Object> doc = new HashMap<>();
            doc.put("id", docId);
            doc.put("text", docTexts.getOrDefault(docId, ""));
            doc.put("score", score);
            String metadata = docMetadatas.get(docId);
            if (metadata != null) doc.put("metadata", metadata);
            String collection = docCollections.get(docId);
            if (collection != null) doc.put("collection", collection);
            String source = docSources.get(docId);
            if (source != null) doc.put("source", source);
            doc.put("bm25_rank", rank + 1);
            results.add(doc);
        }

        log.debug("[BM25] search '{}' collection={} -> {} results in {}ms",
                query, collectionName, results.size(), System.currentTimeMillis() - start);
        return results;
    }

    private void rebuildIndexLocked() {
        invertedIndex.clear();
        docLengths.clear();
        totalDocs = 0;
        long totalLength = 0;

        for (Map.Entry<String, String> docEntry : docTexts.entrySet()) {
            String id = docEntry.getKey();
            String text = docEntry.getValue();
            if (text == null || text.isEmpty()) continue;

            int docLen = text.length();
            docLengths.put(id, docLen);
            totalLength += docLen;
            totalDocs++;

            List<String> terms = tokenize(text);
            Map<String, Integer> termFreq = new HashMap<>();
            for (String term : terms) {
                termFreq.merge(term, 1, Integer::sum);
            }

            for (Map.Entry<String, Integer> entry : termFreq.entrySet()) {
                String term = entry.getKey();
                if (term.length() < MIN_TERM_LENGTH) continue;
                invertedIndex.computeIfAbsent(term, k -> new HashMap<>()).put(id, entry.getValue());
            }
        }

        avgDocLength = totalDocs > 0 ? (double) totalLength / totalDocs : 1.0;
    }

    private String asString(Object value) {
        if (value == null) return null;
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : text;
    }

    private void putOrRemove(Map<String, String> map, String key, String value) {
        if (value == null || value.isEmpty()) {
            map.remove(key);
        } else {
            map.put(key, value);
        }
    }

    private String extractJsonStringField(String json, String fieldName) {
        if (json == null || fieldName == null) return null;
        try {
            java.util.regex.Pattern pattern = java.util.regex.Pattern.compile(
                    "\"" + java.util.regex.Pattern.quote(fieldName) + "\"\\s*:\\s*\"([^\"]*)\"");
            java.util.regex.Matcher matcher = pattern.matcher(json);
            if (matcher.find()) {
                return matcher.group(1);
            }
        } catch (Exception ignore) {}
        return null;
    }

    /**
     * 简单分词器（支持中英文）
     */
    private List<String> tokenize(String text) {
        List<String> terms = new ArrayList<>();
        if (text == null || text.isEmpty()) return terms;

        String lowerText = text.toLowerCase();
        String[] englishTokens = lowerText.split("[\\s\\p{Punct}]{1,}");
        for (String token : englishTokens) {
            if (token.length() >= MIN_TERM_LENGTH && !containsChinese(token)) {
                terms.add(token);
            }
        }

        if (containsChinese(lowerText)) {
            StringBuilder chineseRun = new StringBuilder();
            for (int i = 0; i < lowerText.length(); i++) {
                char c = lowerText.charAt(i);
                if (isChinese(c)) {
                    chineseRun.append(c);
                } else {
                    addChineseNgrams(chineseRun.toString(), terms);
                    chineseRun.setLength(0);
                }
            }
            addChineseNgrams(chineseRun.toString(), terms);
        }

        return terms;
    }

    private void addChineseNgrams(String text, List<String> terms) {
        if (text == null || text.length() < MIN_TERM_LENGTH) return;

        for (int i = 0; i <= text.length() - 2; i++) {
            terms.add(text.substring(i, i + 2));
        }
        for (int i = 0; i <= text.length() - 3; i++) {
            terms.add(text.substring(i, i + 3));
        }
    }

    private boolean containsChinese(String text) {
        for (int i = 0; i < text.length(); i++) {
            if (isChinese(text.charAt(i))) return true;
        }
        return false;
    }

    private boolean isChinese(char c) {
        return (c >= 0x4E00 && c <= 0x9FFF) || (c >= 0x3400 && c <= 0x4DBF) || (c >= 0x20000 && c <= 0x2A6DF);
    }

    private boolean isChinesePunctuation(char c) {
        return (c >= 0x3000 && c <= 0x303F) || (c >= 0xFF00 && c <= 0xFFEF);
    }

    /**
     * 获取索引统计信息
     */
    public Map<String, Object> getStats() {
        Map<String, Object> stats = new HashMap<>();
        stats.put("totalDocs", totalDocs);
        stats.put("avgDocLength", avgDocLength);
        stats.put("vocabSize", invertedIndex.size());
        stats.put("indexVersion", indexVersion);
        return stats;
    }

    /**
     * 清除索引
     */
    public void clear() {
        synchronized (this) {
            invertedIndex.clear();
            docTexts.clear();
            docMetadatas.clear();
            docCollections.clear();
            docSources.clear();
            docLengths.clear();
            totalDocs = 0;
            avgDocLength = 0.0;
            indexVersion++;
            log.info("[BM25] Index cleared");
        }
    }
}
