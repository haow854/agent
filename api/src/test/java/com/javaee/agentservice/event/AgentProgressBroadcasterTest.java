package com.javaee.agentservice.event;

import org.junit.jupiter.api.Test;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class AgentProgressBroadcasterTest {

    private final SimpMessagingTemplate template = mock(SimpMessagingTemplate.class);
    private final AgentProgressBroadcaster broadcaster = new AgentProgressBroadcaster();

    {
        ReflectionTestUtils.setField(broadcaster, "messagingTemplate", template);
    }

    /** 进度里有任务原文和工具参数，只发给任务所属的用户，不再广播。 */
    @Test
    void progressGoesOnlyToTheTaskOwner() {
        AgentProgressEvent event = AgentProgressEvent.of("step_tool", "7", "running", "读取文档");

        broadcaster.publish(event);

        verify(template).convertAndSendToUser("7", "/queue/agent/progress", event);
        verify(template, never()).convertAndSend(anyString(), any(Object.class));
    }

    @Test
    void eventsWithoutAnOwnerAreNotSentAnywhere() {
        broadcaster.publish(AgentProgressEvent.of("step_tool", null, "running", "读取文档"));

        verifyNoInteractions(template);
    }
}
