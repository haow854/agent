package com.javaee.agentservice.orchestration;

import com.javaee.agentservice.event.AgentProgressBroadcaster;
import com.javaee.agentservice.model.AgentExecutionRequest;
import com.javaee.agentservice.model.AgentState;
import com.javaee.agentservice.model.AgentTaskResponse;
import com.javaee.agentservice.model.Checkpoint;
import com.javaee.agentservice.memory.AgentMemoryStore;
import com.javaee.agentservice.session.SessionManager;
import com.javaee.agentservice.task.CheckpointStore;
import com.javaee.agentservice.tool.AgentToolRegistry;
import com.javaee.common.security.RequestUserContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentExecutionServiceStartTest {

    private AgentExecutionService service;
    private CheckpointStore checkpointStore;
    private SessionManager sessionManager;

    @BeforeEach
    void setUp() {
        service = new AgentExecutionService();
        checkpointStore = mock(CheckpointStore.class);
        sessionManager = mock(SessionManager.class);
        ReactRunner runner = mock(ReactRunner.class);
        AgentToolRegistry toolRegistry = mock(AgentToolRegistry.class);
        RequestUserContext requestUserContext = mock(RequestUserContext.class);
        AgentTaskResponseAssembler responseAssembler =
                mock(AgentTaskResponseAssembler.class);

        when(requestUserContext.getRequiredUserId()).thenReturn(1L);
        when(sessionManager.ensureSession(any(), any(), any(), any())).thenReturn("session-1");
        when(toolRegistry.selectableToolNames())
                .thenReturn(List.of("web_search"));
        when(checkpointStore.newLeaseOwner()).thenReturn("owner-1");
        when(checkpointStore.leaseDeadline()).thenReturn(Long.MAX_VALUE);
        when(checkpointStore.create(any())).thenReturn(true);
        when(runner.run(any(), any(), any())).thenAnswer(call -> call.getArgument(0));
        when(responseAssembler.toDetailedResponse(any()))
                .thenReturn(new AgentTaskResponse());

        ReflectionTestUtils.setField(service, "runner", runner);
        ReflectionTestUtils.setField(service, "checkpointStore", checkpointStore);
        ReflectionTestUtils.setField(service, "sessionManager", sessionManager);
        ReflectionTestUtils.setField(service, "toolRegistry", toolRegistry);
        ReflectionTestUtils.setField(service, "memoryStore", mock(AgentMemoryStore.class));
        ReflectionTestUtils.setField(service, "promptEngineeringService",
                new com.javaee.agentservice.prompt.PromptEngineeringService());
        ReflectionTestUtils.setField(service, "requestUserContext", requestUserContext);
        ReflectionTestUtils.setField(service, "responseAssembler", responseAssembler);
        ReflectionTestUtils.setField(service, "progressBroadcaster",
                mock(AgentProgressBroadcaster.class));
        ReflectionTestUtils.setField(service, "defaultModel", "test-model");
    }

    /**
     * A task opens its context on the session, not on its own first message.
     * Opening it on itself is what made every task blind to the ones before it:
     * the chain is built from that id onward, so there was never anything
     * earlier inside the window to reduce.
     */
    @Test
    void opensTheContextWindowOnTheSessionRatherThanOnTheNewTask() {
        ArgumentCaptor<Checkpoint> created =
                ArgumentCaptor.forClass(Checkpoint.class);

        service.execute(request("总结这个文档"));

        verify(checkpointStore).create(created.capture());
    }

    /**
     * The opening question is stored before the checkpoint claims it, so a
     * checkpoint only ever names a run over messages that already exist.
     */
    @Test
    void writesTheQuestionToTheStreamBeforeCheckpointingIt() {
        service.execute(request("总结这个文档"));

        var order = org.mockito.Mockito.inOrder(sessionManager, checkpointStore);
        order.verify(sessionManager).appendUser(anyString(), anyString(), anyString());
        order.verify(checkpointStore).create(any());
    }

    private AgentExecutionRequest request(String task) {
        AgentExecutionRequest request = new AgentExecutionRequest();
        request.setTask(task);
        request.setIdempotencyKey("key-1");
        return request;
    }
}
