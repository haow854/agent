package com.javaee.agentservice.orchestration;

import com.javaee.agentservice.event.AgentProgressBroadcaster;
import com.javaee.agentservice.model.AgentExecutionRequest;
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
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A submission carrying a key is one piece of work however many times it
 * arrives, which is what makes a redelivery safe.
 */
class AgentExecutionServiceIdempotencyTest {

    private AgentExecutionService service;
    private CheckpointStore checkpointStore;
    private ReactRunner runner;

    @BeforeEach
    void setUp() {
        service = new AgentExecutionService();
        checkpointStore = mock(CheckpointStore.class);
        runner = mock(ReactRunner.class);
        SessionManager sessionManager = mock(SessionManager.class);
        AgentToolRegistry toolRegistry = mock(AgentToolRegistry.class);
        RequestUserContext requestUserContext = mock(RequestUserContext.class);
        AgentTaskResponseAssembler responseAssembler =
                mock(AgentTaskResponseAssembler.class);

        when(requestUserContext.getRequiredUserId()).thenReturn(1L);
        when(sessionManager.ensureSession(any(), anyLong(), any(), any()))
                .thenReturn("session-1");
        when(toolRegistry.selectableToolNames())
                .thenReturn(List.of("web_search"));
        when(checkpointStore.newLeaseOwner()).thenReturn("owner-1");
        when(checkpointStore.leaseDeadline()).thenReturn(Long.MAX_VALUE);
        when(checkpointStore.create(any())).thenReturn(true);
        when(runner.run(any(), any(), any())).thenAnswer(call -> call.getArgument(0));
        when(responseAssembler.toDetailedResponse(any()))
                .thenReturn(new AgentTaskResponse());
        when(responseAssembler.status(anyString(), anyString(), any()))
                .thenAnswer(call -> {
                    AgentTaskResponse response = new AgentTaskResponse();
                    response.setStatus(call.getArgument(0));
                    response.setTaskId(call.getArgument(1));
                    return response;
                });

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

    @Test
    void derivesOneTaskIdFromOneKey() {
        service.execute(request("summarise it", "key-1"));
        service.execute(request("summarise it", "key-1"));

        assertThat(createdTaskIds()).hasSize(2);
        assertThat(createdTaskIds().get(0)).isEqualTo(createdTaskIds().get(1));
    }

    @Test
    void refusesToRunTheSameSubmissionTwice() {
        // The second create loses: the store already holds that task.
        when(checkpointStore.create(any())).thenReturn(true, false);

        service.execute(request("summarise it", "key-1"));
        AgentTaskResponse second = service.execute(request("summarise it", "key-1"));

        assertThat(second.getStatus()).isEqualTo("duplicate");
        assertThat(second.getTaskId()).isEqualTo(createdTaskIds().get(0));
        verify(runner, times(1)).run(any(), any(), any());
    }

    @Test
    void keepsSubmissionsWithDifferentKeysApart() {
        service.execute(request("summarise it", "key-1"));
        service.execute(request("summarise it", "key-2"));

        assertThat(createdTaskIds().get(0)).isNotEqualTo(createdTaskIds().get(1));
    }

    private List<String> createdTaskIds() {
        ArgumentCaptor<Checkpoint> created =
                ArgumentCaptor.forClass(Checkpoint.class);
        verify(checkpointStore, times(2)).create(created.capture());
        return created.getAllValues().stream().map(Checkpoint::taskId).toList();
    }

    private AgentExecutionRequest request(String task, String idempotencyKey) {
        AgentExecutionRequest request = new AgentExecutionRequest();
        request.setTask(task);
        request.setSessionId("session-1");
        request.setIdempotencyKey(idempotencyKey);
        return request;
    }
}
