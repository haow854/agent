package com.javaee.agentservice.config;

import com.javaee.common.utils.JwtUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessageDeliveryException;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.messaging.support.MessageHeaderAccessor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class StompAuthInterceptorTest {

    private final StompAuthInterceptor interceptor = new StompAuthInterceptor();
    private final MessageChannel channel = mock(MessageChannel.class);

    @BeforeEach
    void signingKey() {
        new JwtUtils("jwt-unit-tests-only-signing-key-0123456789");
    }

    /** 连接时带有效的访问令牌：认出用户，用户名就是用户 id，推送按它找人。 */
    @Test
    void connectWithAnAccessTokenIdentifiesTheUser() {
        Message<byte[]> connect = frame(StompCommand.CONNECT, "Bearer " + JwtUtils.generateToken(42L, "alice", "user"), null);

        Message<?> passed = interceptor.preSend(connect, channel);

        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(passed, StompHeaderAccessor.class);
        assertThat(accessor.getUser()).isNotNull();
        assertThat(accessor.getUser().getName()).isEqualTo("42");
    }

    /** 不带令牌、带伪造令牌、拿刷新令牌来连，都连不上。 */
    @Test
    void connectWithoutAValidAccessTokenIsRefused() {
        assertThatThrownBy(() -> interceptor.preSend(frame(StompCommand.CONNECT, null, null), channel))
                .isInstanceOf(MessageDeliveryException.class);
        assertThatThrownBy(() -> interceptor.preSend(frame(StompCommand.CONNECT, "Bearer forged.token.value", null), channel))
                .isInstanceOf(MessageDeliveryException.class);
        String refresh = JwtUtils.generateRefreshToken(42L);
        assertThatThrownBy(() -> interceptor.preSend(frame(StompCommand.CONNECT, "Bearer " + refresh, null), channel))
                .isInstanceOf(MessageDeliveryException.class);
    }

    /** 只能订阅自己的进度队列；以前的全局频道、按用户或任务分的频道都订阅不了。 */
    @Test
    void onlyTheOwnProgressQueueCanBeSubscribed() {
        interceptor.preSend(frame(StompCommand.SUBSCRIBE, null, StompAuthInterceptor.PROGRESS_DESTINATION), channel);

        for (String other : new String[]{"/topic/agent/progress", "/topic/agent/users/7", "/queue/agent/progress"}) {
            assertThatThrownBy(() -> interceptor.preSend(frame(StompCommand.SUBSCRIBE, null, other), channel))
                    .isInstanceOf(MessageDeliveryException.class);
        }
    }

    @Test
    void clientsCannotSendIntoTheChannel() {
        assertThatThrownBy(() -> interceptor.preSend(frame(StompCommand.SEND, null, "/queue/agent/progress"), channel))
                .isInstanceOf(MessageDeliveryException.class);
    }

    private Message<byte[]> frame(StompCommand command, String authorization, String destination) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(command);
        if (authorization != null) {
            accessor.setNativeHeader("Authorization", authorization);
        }
        if (destination != null) {
            accessor.setDestination(destination);
        }
        accessor.setLeaveMutable(true);
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }
}
