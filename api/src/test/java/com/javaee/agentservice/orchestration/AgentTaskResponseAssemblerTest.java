package com.javaee.agentservice.orchestration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.javaee.agentservice.model.AgentState;
import com.javaee.agentservice.model.AgentTaskResponse;
import com.javaee.agentservice.model.Checkpoint;
import com.javaee.agentservice.session.SessionMessage;
import com.javaee.agentservice.session.SessionManager;
import com.javaee.agentservice.llm.TokenUsage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AgentTaskResponseAssemblerTest {

    private AgentTaskResponseAssembler assembler;
    private SessionManager sessionManager;

    @BeforeEach
    void setUp() {
        assembler = new AgentTaskResponseAssembler();
        sessionManager = mock(SessionManager.class);
        ReflectionTestUtils.setField(assembler, "objectMapper", new ObjectMapper());
        ReflectionTestUtils.setField(assembler, "sessionManager", sessionManager);
    }

    @Test
    void doesNotLeakRecoveryMachineryToCallers() {
        AgentTaskResponse response = assembler.toResponse(checkpoint(running()));
        Map<String, Object> payload = assembler.toPayload(response);

        assertThat(payload).doesNotContainKeys(
                "leaseOwner", "leaseExpiresAt", "seq", "schemaVersion",
                "contextStartId", "contextEndId", "state", "systemPrompt");
    }

    @Test
    void translatesInternalStatusToTheVocabularyCallersAlreadyRead() {
        assertThat(assembler.toResponse(checkpoint(running())).getStatus())
                .isEqualTo("running");
        assertThat(assembler.toResponse(checkpoint(paused())).getStatus())
                .isEqualTo("action_required");
        assertThat(assembler.toResponse(checkpoint(finished(
                AgentState.TaskStatus.SUCCEEDED))).getStatus())
                .isEqualTo("success");
        assertThat(assembler.toResponse(checkpoint(finished(
                AgentState.TaskStatus.FAILED))).getStatus())
                .isEqualTo("error");
        assertThat(assembler.toResponse(checkpoint(finished(
                AgentState.TaskStatus.CANCELLED))).getStatus())
                .isEqualTo("cancelled");
    }

    @Test
    void exposesAPendingApprovalWithoutItsFingerprint() {
        AgentState state = running();
        state.pause(List.of(AgentState.Pending.approval(
                "call-1", "delete_text", "fingerprint-1", 999L)));

        AgentTaskResponse response = assembler.toResponse(checkpoint(state));

        assertThat(response.getPending()).singleElement().satisfies(pending -> {
            assertThat(pending.kind()).isEqualTo("APPROVAL");
            
            assertThat(pending.toolName()).isEqualTo("delete_text");
        });
        // The fingerprint is the server's own check; a caller gains nothing.
        assertThat(assembler.toPayload(response).toString())
                .doesNotContain("fingerprint-1");
    }

    @Test
    void reportsTokensSpentAlongsideTheCallCounts() {
        AgentState state = running();
        state.chargeModelTurn(new TokenUsage(120L, 30L, 1));
        state.chargeToolCall();

        AgentTaskResponse response = assembler.toResponse(checkpoint(state));

        assertThat(response.getIterations()).isEqualTo(1);
        assertThat(response.getToolCallCount()).isEqualTo(1);
        assertThat(response.getPromptTokens()).isEqualTo(120L);
        assertThat(response.getCompletionTokens()).isEqualTo(30L);
    }

    /** The chain is the record of what ran, so steps are read back from it. */
    @Test
    void rebuildsExecutedStepsFromTheToolEntriesOfThisTask() {
        when(sessionManager.getHistoryFrom(eq("session-1"), any())).thenReturn(List.of(
                user(1L, "task-1", "question"),
                toolResult(2L, "task-1", "call-a",
                        "{\"toolName\":\"document_read\",\"status\":\"success\","
                                + "\"message\":\"read\",\"durationMs\":12}"),
                // Another task's rows share the session and must not be counted.
                toolResult(3L, "task-2", "call-b",
                        "{\"toolName\":\"document_create\",\"status\":\"success\"}")
        ));

        AgentTaskResponse response = assembler.toDetailedResponse(checkpoint(running()));

        assertThat(response.getSteps()).singleElement().satisfies(step -> {
            assertThat(step.id()).isEqualTo("step-1");
            assertThat(step.toolName()).isEqualTo("document_read");
            assertThat(step.durationMs()).isEqualTo(12L);
        });
    }

    @Test
    void listingProjectionSkipsTheStreamRead() {
        assertThat(assembler.toResponse(checkpoint(running())).getSteps()).isNull();
    }

    private Checkpoint checkpoint(AgentState state) {
        return Checkpoint.initial(state, "owner-1", 5_000L, 1_000L);
    }

    private AgentState running() {
        AgentState.RunConfig config = new AgentState.RunConfig(
                "summarize", "internal-model", "system prompt",
                List.of("document_read"), "doc-1", "all", "<workspace>", "", 6, 8);
        AgentState state = AgentState.create(
                "task-1", "session-1", 1L, config, 0L);
        return state;
    }

    private AgentState paused() {
        AgentState state = running();
        state.pause(List.of(AgentState.Pending.askUser("call-1", "ask_user")));
        return state;
    }

    private AgentState finished(AgentState.TaskStatus status) {
        AgentState state = running();
        state.finish(status, "done", "answer");
        return state;
    }

    private SessionMessage user(Long id, String taskId, String content) {
        SessionMessage message = row(id, taskId, "user");
        message.setContent(content);
        return message;
    }

    private SessionMessage toolResult(Long id, String taskId, String toolCallId, String content) {
        SessionMessage message = row(id, taskId, "tool");
        message.setToolCallId(toolCallId);
        message.setContent(content);
        return message;
    }

    private SessionMessage row(Long id, String taskId, String role) {
        SessionMessage message = new SessionMessage();
        message.setId(id);
        message.setSessionId("session-1");
        message.setTaskId(taskId);
        message.setRole(role);
        return message;
    }
}
