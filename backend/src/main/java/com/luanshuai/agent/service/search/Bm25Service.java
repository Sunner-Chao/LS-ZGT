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
            invertedIndex.clear();
            docTexts.clear();
            docLengths.clear();
            totalDocs = 0;
            long totalLength = 0;

            for (Map<String, Object> doc : documents) {
                String id = String.valueOf(doc.getOrDefault("id", UUID.randomUUID().toString()));
                String text = String.valueOf(doc.getOrDefault("text", ""));

                if (text.isEmpty()) continue;

                docTexts.put(id, text);
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
            indexVersion++;
            log.info("[BM25] Indexed {} documents, avg length={}, vocab size={}", totalDocs, avgDocLength, invertedIndex.size());
        }
    }

    /**
     * 搜索文档
     */
    public List<Map<String, Object>> search(String query, int topK) {
        return search(query, topK, DEFAULT_K1, DEFAULT_B);
    }

    public List<Map<String, Object>> search(String query, int topK, double k1, double b) {
        if (query == null || query.trim().isEmpty()) {
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

                double numerator = tf * (k1 + 1);
                double denominator = tf + k1 * (1 - b + b * docLen / avgDocLength);
                double score = idf * numerator / denominator;

                docScores.merge(docId, score, Double::sum);
            }
        }

        List<Map.Entry<String, Double>> sorted = docScores.entrySet().stream()
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
            doc.put("bm25_rank", rank + 1);
            results.add(doc);
        }

        log.debug("[BM25] search '{}' -> {} results in {}ms", query, results.size(), System.currentTimeMillis() - start);
        return results;
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
            if (token.length() >= MIN_TERM_LENGTH) {
                terms.add(token);
            }
        }

        if (containsChinese(lowerText)) {
            for (int i = 0; i < lowerText.length() - 1; i++) {
                char c = lowerText.charAt(i);
                if (isChinese(c) || isChinesePunctuation(c)) continue;
                char next = lowerText.charAt(i + 1);
                if (isChinese(next) || isChinesePunctuation(next)) continue;
                String bigram = String.valueOf(c) + next;
                if (!containsChinese(bigram)) {
                    terms.add(bigram);
                }
            }
        }

        return terms;
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
            docLengths.clear();
            totalDocs = 0;
            avgDocLength = 0.0;
            indexVersion++;
            log.info("[BM25] Index cleared");
        }
    }
}
