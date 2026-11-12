package com.javaee.agentservice.session;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Owns the session and the messages under it.
 *
 * <p>A message is durable the moment it is appended: each append is its own
 * transaction, never one spanning a run. A run holds no connection while it
 * waits on the model, and a crash keeps every message written before it.</p>
 */
@Component
public class SessionManager {

    private static final Logger log = LoggerFactory.getLogger(SessionManager.class);
    private static final int LIST_LIMIT = 200;
    private static final int TITLE_LIMIT = 40;

    @Autowired
    private SessionMapper sessionMapper;

    @Autowired
    private SessionMessageMapper messageMapper;

    @Autowired
    private SessionSummaryMapper summaryMapper;

    public String createSession(Long userId, String documentId) {
        return insertSession(UUID.randomUUID().toString(), userId, documentId, null);
    }

    /**
     * 空→新建；存在→验主；不存在→同 id 建出来（前端存着的 id 比会话活得久，
     * 报错会把 resume 和隔了很久回来的第一条消息打死）。
     */
    public String ensureSession(String sessionId, Long userId, String documentId, String title) {
        if (sessionId == null || sessionId.isBlank()) {
            return insertSession(UUID.randomUUID().toString(), userId, documentId, title);
        }
        Session session = sessionMapper.selectById(sessionId);
        if (session == null) {
            return insertSession(sessionId, userId, documentId, title);
        }
        assertOwner(session, userId);
        if (documentId != null && !documentId.isBlank()) {
            sessionMapper.setDocumentIdIfAbsent(sessionId, documentId);
        }
        if (title != null && !title.isBlank()) {
            sessionMapper.setTitleIfAbsent(sessionId, title(title));
        }
        return sessionId;
    }

    private String insertSession(String sessionId, Long userId, String documentId, String title) {
        Session session = new Session();
        session.setId(sessionId);
        session.setUserId(userId);
        session.setDocumentId(documentId == null || documentId.isBlank() ? null : documentId);
        session.setTitle(title == null || title.isBlank() ? null : title(title));
        session.setLastMessageId(0L);
        sessionMapper.insert(session);
        log.info("Created session: userId={}, sessionId={}", userId, sessionId);
        return sessionId;
    }

    public Long appendUser(String sessionId, String taskId, String content) {
        SessionMessage message = new SessionMessage();
        message.setSessionId(sessionId);
        message.setTaskId(taskId);
        message.setRole("user");
        message.setContent(content);
        return append(message);
    }

    public Long appendAssistant(String sessionId,
                                String taskId,
                                String content,
                                List<ToolCall> toolCalls) {
        SessionMessage message = new SessionMessage();
        message.setSessionId(sessionId);
        message.setTaskId(taskId);
        message.setRole("assistant");
        message.setContent(content);
        message.setToolCalls(toolCalls == null || toolCalls.isEmpty() ? null : toolCalls);
        return append(message);
    }

    public Long appendTool(String sessionId,
                           String taskId,
                           String toolCallId,
                           String toolName,
                           String content) {
        SessionMessage message = new SessionMessage();
        message.setSessionId(sessionId);
        message.setTaskId(taskId);
        message.setRole("tool");
        message.setToolCallId(toolCallId);
        message.setToolName(toolName);
        message.setContent(content);
        return append(message);
    }

    /**
     * @return the id of the appended message, which is also the cursor a later
     *         read starts from
     */
    @Transactional
    public Long append(SessionMessage message) {
        messageMapper.insert(message);
        int updated = sessionMapper.updateLastMessage(
                message.getSessionId(), message.getId(), message.getTaskId());
        if (updated == 0) {
            throw new IllegalArgumentException(
                    "Session does not exist: " + message.getSessionId());
        }
        return message.getId();
    }

    /**
     * Messages from {@code fromId} to the end, oldest first.
     *
     * @param fromId inclusive lower bound, or null for the whole session
     */
    public List<SessionMessage> getHistoryFrom(String sessionId, Long fromId) {
        return messageMapper.selectList(new LambdaQueryWrapper<SessionMessage>()
                .eq(SessionMessage::getSessionId, sessionId)
                .ge(fromId != null, SessionMessage::getId, fromId)
                .orderByAsc(SessionMessage::getId));
    }

    public List<Map<String, Object>> getHistoryForUser(String sessionId, Long userId) {
        assertOwner(sessionId, userId);
        return getHistoryFrom(sessionId, null).stream().map(this::toChatItem).toList();
    }

    @Transactional
    public void deleteSession(String sessionId) {
        messageMapper.delete(new LambdaQueryWrapper<SessionMessage>()
                .eq(SessionMessage::getSessionId, sessionId));
        summaryMapper.deleteById(sessionId);
        sessionMapper.deleteById(sessionId);
        log.info("Deleted session: sessionId={}", sessionId);
    }

    public void deleteSessionForUser(String sessionId, Long userId) {
        assertOwner(sessionId, userId);
        deleteSession(sessionId);
    }

    /** Most recently used first. */
    public List<String> getUserSessions(Long userId) {
        return userSessions(userId).stream().map(Session::getId).toList();
    }

    public List<Map<String, Object>> listUserSessions(Long userId) {
        return userSessions(userId).stream().map(this::toSessionInfo).toList();
    }

    private List<Session> userSessions(Long userId) {
        if (userId == null) {
            return List.of();
        }
        return sessionMapper.selectList(new LambdaQueryWrapper<Session>()
                .eq(Session::getUserId, userId)
                .orderByDesc(Session::getUpdateTime)
                .last("LIMIT " + LIST_LIMIT));
    }

    private void assertOwner(String sessionId, Long userId) {
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("Session ID cannot be blank");
        }
        assertOwner(sessionMapper.selectById(sessionId), userId);
    }

    private void assertOwner(Session session, Long userId) {
        if (session == null) {
            throw new IllegalArgumentException("Session does not exist");
        }
        if (!String.valueOf(session.getUserId()).equals(String.valueOf(userId))) {
            throw new SecurityException("No permission to access this session");
        }
    }

    private Map<String, Object> toSessionInfo(Session session) {
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("sessionId", session.getId());
        info.put("userId", session.getUserId());
        info.put("createdAt", millisOf(session.getCreateTime()));
        info.put("updatedAt", millisOf(session.getUpdateTime()));
        putIfPresent(info, "documentId", session.getDocumentId());
        putIfPresent(info, "lastTaskId", session.getLastTaskId());
        putIfPresent(info, "title", session.getTitle());
        return info;
    }

    private Map<String, Object> toChatItem(SessionMessage message) {
        Map<String, Object> item = new LinkedHashMap<>();
        putIfPresent(item, "taskId", message.getTaskId());
        item.put("role", message.getRole());
        item.put("content", message.getContent());
        List<ToolCall> toolCalls = message.getToolCalls();
        if (toolCalls != null && !toolCalls.isEmpty()) {
            item.put("toolCalls", new ArrayList<>(toolCalls));
        }
        putIfPresent(item, "toolCallId", message.getToolCallId());
        return item;
    }

    private void putIfPresent(Map<String, Object> target, String field, String value) {
        if (value != null && !value.isBlank()) {
            target.put(field, value);
        }
    }

    private Long millisOf(LocalDateTime time) {
        return time == null
                ? null
                : time.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
    }

    private String title(String content) {
        String oneLine = content.strip().replaceAll("\\s+", " ");
        return oneLine.length() <= TITLE_LIMIT
                ? oneLine
                : oneLine.substring(0, TITLE_LIMIT) + "…";
    }
}
