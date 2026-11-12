package com.javaee.agentservice.async;

import com.javaee.agentservice.model.AgentExecutionRequest;
import com.javaee.agentservice.model.AgentTaskResponse;
import com.javaee.agentservice.orchestration.AgentExecutionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AsyncAIJobListenerTest {

    private AsyncAIJobListener listener;
    private AsyncAIJobService asyncAIJobService;
    private AgentExecutionService agentExecutionService;

    @BeforeEach
    void setUp() {
        listener = new AsyncAIJobListener();
        asyncAIJobService = mock(AsyncAIJobService.class);
        agentExecutionService = mock(AgentExecutionService.class);

        ReflectionTestUtils.setField(listener, "asyncAIJobService", asyncAIJobService);
        ReflectionTestUtils.setField(listener, "agentExecutionService", agentExecutionService);
    }

    @Test
    void handleModelJobExecutesAgentRequestAndStoresResult() {
        doAnswer(invocation -> {
            assertThat(SecurityContextHolder.getContext().getAuthentication().getPrincipal())
                    .isEqualTo("1");
            AgentTaskResponse response = new AgentTaskResponse();
            response.setStatus("success");
            response.setAnswer("done");
            return response;
        }).when(agentExecutionService).execute(any(AgentExecutionRequest.class));

        AsyncAIJobMessage message = new AsyncAIJobMessage();
        message.setJobId("job-1");
        message.setType("agent");
        message.setModel("gpt-5.5");
        message.setUserId(1L);
        message.setPayload(Map.of(
                "task", "总结当前文档",
                "sessionId", "conv-1",
                "documentId", "doc-1"
        ));

        listener.handleModelJob(message);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        ArgumentCaptor<AgentExecutionRequest> requestCaptor =
                ArgumentCaptor.forClass(AgentExecutionRequest.class);
        verify(agentExecutionService).execute(requestCaptor.capture());
        AgentExecutionRequest request = requestCaptor.getValue();
        assertThat(request.getTask()).isEqualTo("总结当前文档");
        assertThat(request.getModel()).isEqualTo("gpt-5.5");
        assertThat(request.getUserId()).isEqualTo(1L);
        assertThat(request.getSessionId()).isEqualTo("conv-1");
        assertThat(request.getDocumentId()).isEqualTo("doc-1");
        verify(asyncAIJobService).markRunning("job-1");
        verify(asyncAIJobService).markSuccess(
                eq("job-1"),
                argThat(result -> result instanceof AgentTaskResponse response
                        && "success".equals(response.getStatus())
                        && "done".equals(response.getAnswer())));
    }

    /**
     * An approval names a task that is already running, so it must not reach the
     * entry point that starts one.
     */
    @Test
    void handleModelJobSendsAnApprovalJobToConfirm() {
        doAnswer(invocation -> {
            assertThat(SecurityContextHolder.getContext().getAuthentication().getPrincipal())
                    .isEqualTo("2");
            AgentTaskResponse response = new AgentTaskResponse();
            response.setStatus("success");
            return response;
        }).when(agentExecutionService).confirmApproval(any(AgentExecutionRequest.class));

        AsyncAIJobMessage message = new AsyncAIJobMessage();
        message.setJobId("job-2");
        message.setType("agent-approval");
        message.setUserId(2L);
        message.setPayload(Map.of("taskId", "task-7", "approvedToolCallIds", List.of("call-1")));

        listener.handleModelJob(message);

        ArgumentCaptor<AgentExecutionRequest> requestCaptor =
                ArgumentCaptor.forClass(AgentExecutionRequest.class);
        verify(agentExecutionService).confirmApproval(requestCaptor.capture());
        assertThat(requestCaptor.getValue().getApprovedToolCallIds()).containsExactly("call-1");
        assertThat(requestCaptor.getValue().getUserId()).isEqualTo(2L);
        verify(agentExecutionService, never()).execute(any());
        verify(asyncAIJobService).markSuccess(eq("job-2"), any());
    }

    @Test
    void handleModelJobSendsAContinueJobToExecuteWithItsTaskId() {
        when(agentExecutionService.execute(any())).thenReturn(new AgentTaskResponse());

        AsyncAIJobMessage message = new AsyncAIJobMessage();
        message.setJobId("job-3");
        message.setType("agent-continue");
        message.setUserId(3L);
        message.setPayload(Map.of("taskId", "task-9"));

        listener.handleModelJob(message);

        ArgumentCaptor<AgentExecutionRequest> requestCaptor =
                ArgumentCaptor.forClass(AgentExecutionRequest.class);
        verify(agentExecutionService).execute(requestCaptor.capture());
        assertThat(requestCaptor.getValue().getTaskId()).isEqualTo("task-9");
        verify(asyncAIJobService).markSuccess(eq("job-3"), any());
    }

    /**
     * The runner catches a failed run, writes FAILED to the checkpoint and
     * returns. Judging the job by whether an exception escaped would mark that
     * a success, and the caller would render a run that never reached the model
     * as a finished one.
     */
    @Test
    void handleModelJobFailsAJobWhoseRunFailed() {
        AgentTaskResponse failed = new AgentTaskResponse();
        failed.setStatus("error");
        failed.setStoppedReason("run_failed");
        when(agentExecutionService.execute(any())).thenReturn(failed);

        AsyncAIJobMessage message = new AsyncAIJobMessage();
        message.setJobId("job-6");
        message.setType("agent");
        message.setUserId(6L);
        message.setPayload(Map.of("task", "你好"));

        listener.handleModelJob(message);

        verify(asyncAIJobService).markFailed("job-6", "run_failed");
        verify(asyncAIJobService, never()).markSuccess(eq("job-6"), any());
    }

    /** An unknown job type is a programming error, not something to run. */
    @Test
    void handleModelJobRefusesAJobTypeItDoesNotKnow() {
        AsyncAIJobMessage message = new AsyncAIJobMessage();
        message.setJobId("job-4");
        message.setType("agent-rewind");
        message.setUserId(4L);
        message.setPayload(Map.of("taskId", "task-9", "task", "换个问法"));

        listener.handleModelJob(message);

        verify(agentExecutionService, never()).execute(any());
        verify(asyncAIJobService).markFailed(eq("job-4"), any());
    }

}
