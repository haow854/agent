package com.javaee.agentservice.event;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

/**
 * Agent 实时进度推送。事件只发给任务所属的用户：进度里有任务原文和工具参数，
 * 发给别人就是泄露文档内容。若 WebSocket 消息模板不可用，则静默降级，不影响主流程。
 */
@Component
public class AgentProgressBroadcaster {

    /** 用户的进度队列；客户端订阅的是加上 /user 前缀的地址。 */
    static final String USER_QUEUE = "/queue/agent/progress";

    @Autowired(required = false)
    private SimpMessagingTemplate messagingTemplate;

    public void publish(AgentProgressEvent event) {
        if (messagingTemplate == null || event == null
                || event.getUserId() == null || event.getUserId().isBlank()) {
            return;
        }
        try {
            messagingTemplate.convertAndSendToUser(event.getUserId(), USER_QUEUE, event);
        } catch (Exception ignored) {
            // 推送失败不应影响任务执行。
        }
    }
}
