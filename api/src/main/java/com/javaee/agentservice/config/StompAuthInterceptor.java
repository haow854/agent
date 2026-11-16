package com.javaee.agentservice.config;

import com.javaee.common.constant.CommonConstant;
import com.javaee.common.utils.JwtUtils;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessageDeliveryException;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;

import java.security.Principal;

/**
 * Agent 进度通道的门禁。
 *
 * <p>WebSocket 握手是浏览器发起的 HTTP 升级，带不了 Authorization 头，所以鉴权放在
 * STOMP 的 CONNECT 帧上：前端在 CONNECT 头里带访问令牌，校验通过才认出用户，之后只能
 * 订阅自己的进度队列。通道只读，客户端不能往里发消息。</p>
 */
public class StompAuthInterceptor implements ChannelInterceptor {

    /** 进度按用户投递：订阅这个地址，收到的只有当前连接所属用户的事件。 */
    public static final String PROGRESS_DESTINATION = "/user/queue/agent/progress";

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (accessor == null || accessor.getCommand() == null) {
            return message;
        }
        StompCommand command = accessor.getCommand();
        if (command == StompCommand.CONNECT) {
            accessor.setUser(authenticate(accessor.getFirstNativeHeader(CommonConstant.TOKEN_HEADER)));
        } else if (command == StompCommand.SUBSCRIBE
                && !PROGRESS_DESTINATION.equals(accessor.getDestination())) {
            throw new MessageDeliveryException("只能订阅 " + PROGRESS_DESTINATION);
        } else if (command == StompCommand.SEND) {
            throw new MessageDeliveryException("进度通道只读");
        }
        return message;
    }

    private Principal authenticate(String authorization) {
        Long userId;
        try {
            String token = JwtUtils.extractToken(authorization);
            userId = JwtUtils.parseAccessToken(token).get(CommonConstant.TOKEN_CLAIM_USER_ID, Long.class);
        } catch (RuntimeException e) {
            throw new MessageDeliveryException("未登录或登录已过期");
        }
        // 用户名就是用户 id：推送时按 id 找人（AgentProgressBroadcaster）
        String name = String.valueOf(userId);
        return () -> name;
    }
}
