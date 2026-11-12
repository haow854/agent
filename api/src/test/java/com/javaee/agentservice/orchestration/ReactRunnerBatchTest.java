package com.javaee.agentservice.orchestration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.javaee.agentservice.event.AgentProgressBroadcaster;
import com.javaee.agentservice.model.AgentState;
import com.javaee.agentservice.model.AgentToolResult;
import com.javaee.agentservice.model.Checkpoint;
import com.javaee.agentservice.prompt.PromptEngineeringService;
import com.javaee.agentservice.session.ContextManager;
import com.javaee.agentservice.session.SessionManager;
import com.javaee.agentservice.task.CheckpointStore;
import com.javaee.agentservice.tool.AgentToolRegistry;
import com.javaee.agentservice.llm.LlmClient;
import com.javaee.agentservice.llm.ModelToolDefinition;
import com.javaee.agentservice.llm.TokenUsage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The batch is the unit a run stops on: a call needing the user is collected
 * rather than ending the batch, so one batch costs one interruption.
 */
class ReactRunnerBatchTest {

    private ReactRunner runner;
    private AgentToolGateway toolGateway;
    private ContextManager contextManager;
    private SessionManager sessionManager;
    private CheckpointStore checkpointStore;
    private LlmClient modelClient;
    private AgentToolRegistry toolRegistry;

    @BeforeEach
    void setUp() {
        runner = new ReactRunner();
        toolGateway = mock(AgentToolGateway.class);
        contextManager = mock(ContextManager.class);
        sessionManager = mock(SessionManager.class);
        checkpointStore = mock(CheckpointStore.class);
        modelClient = mock(LlmClient.class);
        toolRegistry = mock(AgentToolRegistry.class);

        when(checkpointStore.save(any())).thenReturn(true);
        when(contextManager.buildMessages(any())).thenReturn(new ArrayList<>());
        when(toolRegistry.modelTools(any())).thenReturn(List.of(
                new ModelToolDefinition("web_search", "search", Map.of(), null)));

        ReflectionTestUtils.setField(runner, "modelClient", modelClient);
        ReflectionTestUtils.setField(runner, "toolRegistry", toolRegistry);
        ReflectionTestUtils.setField(runner, "toolGateway", toolGateway);
        ReflectionTestUtils.setField(runner, "toolBatchExecutor", new ToolBatchExecutor(4));
        ReflectionTestUtils.setField(runner, "objectMapper", new ObjectMapper());
        ReflectionTestUtils.setField(runner, "sessionManager", sessionManager);
        ReflectionTestUtils.setField(runner, "contextManager", contextManager);
        ReflectionTestUtils.setField(runner, "checkpointStore", checkpointStore);
        ReflectionTestUtils.setField(
                runner, "promptEngineeringService", new PromptEngineeringService());
        ReflectionTestUtils.setField(
                runner, "progressBroadcaster", mock(AgentProgressBroadcaster.class));
    }

    /**
     * A call needing the user is collected rather than ending the batch, so the
     * rest is still recorded and the user is asked once. Nothing is written for
     * the collected call, which is what leaves it outstanding for a resume.
     */
    @Test
    void recordsTheRestOfTheBatchWhenOneCallNeedsTheUser() {
        pending(call("call-ask", "ask_user"), call("call-search", "web_search"));
        when(toolGateway.execute(any(), any(), any()))
                .thenAnswer(invocation -> {
                    AssistantMessage.ToolCall call = invocation.getArgument(0);
                    return "call-ask".equals(call.id())
                            ? AgentToolResult.actionRequired(
                                    "ask_user", "which one?", new LinkedHashMap<>())
                            : AgentToolResult.success("web_search", "ok", Map.of());
                });

        Checkpoint result = runner.run(checkpoint(running()), Map.of(), List.of());

        ArgumentCaptor<AssistantMessage.ToolCall> executed =
                ArgumentCaptor.forClass(AssistantMessage.ToolCall.class);
        verify(toolGateway, org.mockito.Mockito.times(2))
                .execute(executed.capture(), any(), any());
        assertThat(executed.getAllValues())
                .extracting(AssistantMessage.ToolCall::id)
                .containsExactlyInAnyOrder("call-ask", "call-search");

        ArgumentCaptor<String> appended = ArgumentCaptor.forClass(String.class);
        verify(sessionManager).appendTool(any(), any(), appended.capture(), any(), any());
        assertThat(appended.getValue()).isEqualTo("call-search");
        assertThat(result.state().getPendingInteractions())
                .extracting(AgentState.Pending::toolCallId)
                .containsExactly("call-ask");
    }

    /**
     * Execution is concurrent, recording is not. The latch only clears because
     * the second call is already running while the first waits on it — serial
     * execution would never reach it — and the entries still land in the order
     * the model declared them.
     */
    @Test
    void recordsInTheOrderTheModelDeclaredHoweverTheCallsInterleave() {
        pending(call("call-slow", "web_search"), call("call-fast", "text_analyze"));
        CountDownLatch fastStarted = new CountDownLatch(1);
        AtomicBoolean ranTogether = new AtomicBoolean();
        when(toolGateway.execute(any(), any(), any())).thenAnswer(invocation -> {
            AssistantMessage.ToolCall call = invocation.getArgument(0);
            if ("call-slow".equals(call.id())) {
                ranTogether.set(fastStarted.await(5, TimeUnit.SECONDS));
            } else {
                fastStarted.countDown();
            }
            return AgentToolResult.success(call.name(), "ok", Map.of());
        });
        when(modelClient.complete(any(), any(), any(), any(Boolean.class)))
                .thenReturn(new LlmClient.ModelTurn(
                        new AssistantMessage("done"), TokenUsage.zero()));

        runner.run(checkpoint(running()), Map.of(), List.of());

        assertThat(ranTogether).isTrue();
        ArgumentCaptor<String> appended = ArgumentCaptor.forClass(String.class);
        verify(sessionManager, org.mockito.Mockito.atLeast(2))
                .appendTool(any(), any(), appended.capture(), any(), any());
        assertThat(appended.getAllValues()).containsExactly("call-slow", "call-fast");
    }

    @Test
    void collectsEveryApprovalOfTheBatchIntoASinglePause() {
        pending(call("call-1", "delete_text"), call("call-2", "delete_text"));
        when(toolGateway.execute(any(), any(), any())).thenAnswer(invocation -> {
            AssistantMessage.ToolCall call = invocation.getArgument(0);
            Map<String, Object> data = new LinkedHashMap<>();
            data.put(AgentToolGateway.APPROVAL_FINGERPRINT, "fp-" + call.id());
            data.put(AgentToolGateway.APPROVAL_EXPIRES_AT, 999L);
            return AgentToolResult.actionRequired("delete_text", "confirm", data);
        });

        Checkpoint result = runner.run(checkpoint(running()), Map.of(), List.of());

        assertThat(result.state().getPhase()).isEqualTo(AgentState.Phase.PAUSED);
        assertThat(result.state().getPendingInteractions())
                .extracting(AgentState.Pending::toolCallId)
                .containsExactly("call-1", "call-2");
        assertThat(result.state().getPendingInteractions())
                .extracting(AgentState.Pending::paramsFingerprint)
                .containsExactly("fp-call-1", "fp-call-2");
        // Neither call was answered, so both stay outstanding on the chain —
        // and neither is charged, because the resume that runs them will be.
        verify(sessionManager, never()).appendTool(any(), any(), any(), any(), any());
        assertThat(result.state().getBudget().toolCallsUsed()).isZero();
    }

    /**
     * A crash between the assistant turn reaching the stream and the commit
     * that would have recorded it leaves the phase behind the chain. The chain
     * is the truth, so the run picks up from what it says.
     */
    @Test
    void resumesFromTheChainWhenTheCheckpointLagsTheAssistantTurn() {
        pending(call("call-a", "web_search"));
        when(toolGateway.execute(any(), any(), any()))
                .thenReturn(AgentToolResult.success("web_search", "ok", Map.of()));
        when(modelClient.complete(any(), any(), any(), any(Boolean.class)))
                .thenReturn(new LlmClient.ModelTurn(
                        new AssistantMessage("done"), TokenUsage.zero()));

        // MODEL_PENDING, yet the chain already has a call nobody answered.
        Checkpoint result = runner.run(checkpoint(running()), Map.of(), List.of());

        assertThat(result.state().getStatus())
                .isEqualTo(AgentState.TaskStatus.SUCCEEDED);
        verify(toolGateway).execute(any(), any(), any());
    }

    /** A grant authorises the one call it names, under the params it named. */
    @Test
    void matchesGrantsBackToTheCallsTheyWereGivenFor() {
        pending(call("call-1", "delete_text"), call("call-2", "delete_text"));
        when(toolGateway.execute(any(), any(), any()))
                .thenReturn(AgentToolResult.success("delete_text", "deleted", Map.of()));
        when(modelClient.complete(any(), any(), any(), any(Boolean.class)))
                .thenReturn(new LlmClient.ModelTurn(
                        new AssistantMessage("done"), TokenUsage.zero()));

        long later = System.currentTimeMillis() + 60_000L;
        AgentState paused = running();
        paused.pause(List.of(
                AgentState.Pending.approval("call-1", "delete_text", "fp-1", later),
                AgentState.Pending.approval("call-2", "delete_text", "fp-2", later)));

        runner.run(checkpoint(paused), Map.of(), List.of("call-2"));

        ArgumentCaptor<String> granted = ArgumentCaptor.forClass(String.class);
        verify(toolGateway, org.mockito.Mockito.times(2))
                .execute(any(), any(), granted.capture());
        // call-1 was not granted, so it goes in unauthorised and is challenged
        // again. The batch runs at once, so which one ran first says nothing.
        assertThat(granted.getAllValues()).containsExactlyInAnyOrder(null, "fp-2");
    }

    @Test
    void answersEveryQuestionOfAPausedBatchBeforeRebuildingTheChain() {
        pending();
        when(modelClient.complete(any(), any(), any(), any(Boolean.class)))
                .thenReturn(new LlmClient.ModelTurn(
                        new AssistantMessage("done"), TokenUsage.zero()));

        AgentState paused = running();
        paused.pause(List.of(
                AgentState.Pending.askUser("call-1", "ask_user"),
                AgentState.Pending.askUser("call-2", "ask_user")));

        runner.run(checkpoint(paused),
                Map.of("call-1", "第一个答案", "call-2", "第二个答案"), List.of());

        ArgumentCaptor<String> appended = ArgumentCaptor.forClass(String.class);
        verify(sessionManager, org.mockito.Mockito.atLeast(2))
                .appendTool(any(), any(), appended.capture(), any(), any());
        assertThat(appended.getAllValues()).contains("call-1", "call-2");
    }

    /**
     * The batch that takes the run past its tool ceiling still runs whole —
     * half a batch would leave calls on the chain with no response, and the
     * overrun is at most one turn's worth. What the ceiling then buys is the
     * next turn: it goes out with no tools, and says so, so the run ends with
     * the answer it can already give rather than with an error.
     */
    @Test
    void overrunsBudgetThenAnswersWithoutTools() {
        pending(call("call-1", "web_search"), call("call-2", "web_search"));
        when(toolGateway.execute(any(), any(), any()))
                .thenReturn(AgentToolResult.success("web_search", "ok", Map.of()));
        when(modelClient.complete(any(), any(), any(), any(Boolean.class)))
                .thenReturn(new LlmClient.ModelTurn(
                        new AssistantMessage("目前只能说到这里"), TokenUsage.zero()));

        Checkpoint result = runner.run(checkpoint(running(6, 1)), Map.of(), List.of());

        verify(toolGateway, org.mockito.Mockito.times(2)).execute(any(), any(), any());
        assertThat(result.state().getBudget().toolCallsUsed()).isEqualTo(2);

        ArgumentCaptor<List<ModelToolDefinition>> tools =
                ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<List<Message>> sent = ArgumentCaptor.forClass(List.class);
        verify(modelClient).complete(
                any(), sent.capture(), tools.capture(), any(Boolean.class));
        assertThat(tools.getValue()).isEmpty();
        // Silently withdrawing the tools the system prompt promised reads as a
        // malfunction, so the turn that loses them says why.
        assertThat(sent.getValue()).extracting(Message::getContent)
                .anyMatch(text -> text != null && text.contains("额度已用尽"));

        assertThat(result.state().getStatus())
                .isEqualTo(AgentState.TaskStatus.SUCCEEDED);
        assertThat(result.state().getStoppedReason()).isEqualTo("budget_exhausted");
        assertThat(result.state().getAnswer()).isEqualTo("目前只能说到这里");
    }

    /** With budget to spare the next turn still gets tools, so nothing is cut short. */
    @Test
    void keepsToolsWhileBudgetRemains() {
        pending(call("call-1", "web_search"), call("call-2", "web_search"));
        when(toolGateway.execute(any(), any(), any()))
                .thenReturn(AgentToolResult.success("web_search", "ok", Map.of()));
        when(modelClient.complete(any(), any(), any(), any(Boolean.class)))
                .thenReturn(new LlmClient.ModelTurn(
                        new AssistantMessage("done"), TokenUsage.zero()));

        Checkpoint result = runner.run(checkpoint(running(6, 4)), Map.of(), List.of());

        verify(toolGateway, org.mockito.Mockito.times(2)).execute(any(), any(), any());
        assertThat(result.state().getBudget().toolCallsUsed()).isEqualTo(2);
        assertThat(result.state().getStoppedReason()).isEqualTo("completed");
    }

    private void pending(AssistantMessage.ToolCall... calls) {
        when(contextManager.pendingToolCalls(any(List.class)))
                .thenReturn(List.of(calls));
    }

    private AssistantMessage.ToolCall call(String id, String name) {
        return new AssistantMessage.ToolCall(id, "function", name, "{}");
    }

    private Checkpoint checkpoint(AgentState state) {
        return Checkpoint.initial(state, "owner-1", Long.MAX_VALUE, 1_000L);
    }

    private AgentState running() {
        return running(6, 8);
    }

    private AgentState running(int maxIterations, int maxToolCalls) {
        AgentState.RunConfig config = new AgentState.RunConfig(
                "delete them", "test-model", "system prompt",
                List.of("web_search", "ask_user", "delete_text"),
                null, "all", "<workspace>", "", maxIterations, maxToolCalls);
        AgentState state = AgentState.create(
                "task-1", "session-1", 1L, config, 0L);
        return state;
    }
}
