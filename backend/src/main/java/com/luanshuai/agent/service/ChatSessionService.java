package com.luanshuai.agent.service;

import com.luanshuai.agent.model.ChatRequest.ChatMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ChatSessionService - 后端会话历史存储服务
 *
 * 职责：基于 tenantId 在内存中维护会话历史（ConcurrentHashMap），TTL 30 分钟，
 * 每轮对话自动追加用户消息和 AI 回复。
 *
 * 设计原则：
 * - 不变性：每次追加返回新列表，不修改原列表
 * - 线程安全：ConcurrentHashMap + 同步操作
 * - 自动清理：每分钟清理过期会话
 */
@Service
public class ChatSessionService {

    private static final Logger log = LoggerFactory.getLogger(ChatSessionService.class);

    /** tenantId → 消息列表（user/assistant 交替） */
    private final ConcurrentHashMap<String, SessionData> sessions = new ConcurrentHashMap<>();

    /** 会话最大保留轮数（默认 10 轮 = 10 对话） */
    @Value("${app.chat-session.max-history-turns:10}")
    private int maxHistoryTurns;

    /** 会话 TTL（毫秒，默认 30 分钟） */
    @Value("${app.chat-session.ttl-minutes:30}")
    private int ttlMinutes;

    /** 定时清理间隔（毫秒） */
    private static final long CLEANUP_INTERVAL_MS = 60_000L;

    /**
     * 获取当前会话历史
     * @param tenantId 租户 ID
     * @return 历史消息列表（从未请求过则返回空列表，不返回 null）
     */
    public List<ChatMessage> getHistory(String tenantId) {
        if (tenantId == null || tenantId.trim().isEmpty()) {
            tenantId = "default";
        }
        SessionData data = sessions.get(tenantId);
        if (data == null) {
            return List.of();
        }
        // 每次访问刷新 TTL
        data.refresh();
        List<ChatMessage> result = new ArrayList<>(data.messages);
        log.debug("[Session] 获取历史 tenantId={} count={}", tenantId, result.size());
        return result;
    }

    /**
     * 追加用户消息
     * @param tenantId 租户 ID
     * @param content 用户消息内容
     */
    public void appendUserMessage(String tenantId, String content) {
        if (tenantId == null || tenantId.trim().isEmpty()) {
            tenantId = "default";
        }
        appendMessage(tenantId, content, true);
    }

    /**
     * 追加 AI 回复
     * @param tenantId 租户 ID
     * @param content AI 回复内容
     */
    public void appendAiMessage(String tenantId, String content) {
        if (tenantId == null || tenantId.trim().isEmpty()) {
            tenantId = "default";
        }
        appendMessage(tenantId, content, false);
    }

    private void appendMessage(String tenantId, String content, boolean isUser) {
        SessionData data = sessions.compute(tenantId, (key, existing) -> {
            if (existing == null) {
                return new SessionData(maxHistoryTurns, ttlMinutes);
            }
            existing.refresh();
            return existing;
        });
        synchronized (data) {
            data.messages.add(new ChatMessage(content, isUser));
            // 超过最大轮数，移除最老的一对（user + assistant）
            while (data.messages.size() > maxHistoryTurns * 2) {
                data.messages.remove(0);
            }
        }
        log.debug("[Session] 追加消息 tenantId={} isUser={} totalCount={}",
                  tenantId, isUser, data.messages.size());
    }

    /**
     * 手动清理指定租户的会话
     * @param tenantId 租户 ID
     */
    public void clearSession(String tenantId) {
        sessions.remove(tenantId);
        log.info("[Session] 清理会话 tenantId={}", tenantId);
    }

    /**
     * 定时任务：每分钟清理过期会话
     */
    @Scheduled(fixedRate = CLEANUP_INTERVAL_MS)
    public void cleanupExpired() {
        long now = System.currentTimeMillis();
        int before = sessions.size();
        Iterator<Map.Entry<String, SessionData>> it = sessions.entrySet().iterator();
        int cleaned = 0;
        while (it.hasNext()) {
            Map.Entry<String, SessionData> entry = it.next();
            synchronized (entry.getValue()) {
                if (now - entry.getValue().lastAccessTime > entry.getValue().ttlMs) {
                    it.remove();
                    cleaned++;
                }
            }
        }
        if (cleaned > 0) {
            log.info("[Session] 清理过期会话: 清理={} 剩余={}", cleaned, sessions.size());
        }
    }

    /**
     * 内部类：封装会话数据和元信息
     */
    private static class SessionData {
        /** 消息列表 */
        final List<ChatMessage> messages;
        /** 最大保留消息数 */
        final int maxMessages;
        /** TTL（毫秒） */
        final long ttlMs;
        /** 最后访问时间 */
        volatile long lastAccessTime;

        SessionData(int maxMessages, int ttlMinutes) {
            this.messages = new ArrayList<>();
            this.maxMessages = maxMessages;
            this.ttlMs = ttlMinutes * 60_000L;
            this.lastAccessTime = System.currentTimeMillis();
        }

        void refresh() {
            this.lastAccessTime = System.currentTimeMillis();
        }
    }
}
