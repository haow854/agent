package com.javaee.agentservice.model;

import com.javaee.agentservice.llm.TokenUsage;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AgentStateTest {

    @Test
    void statusAndPhaseCannotDisagree() {
        AgentState state = running();

        state.pause(List.of(AgentState.Pending.askUser("call-1", "ask_user")));
        assertThat(state.getStatus()).isEqualTo(AgentState.TaskStatus.WAITING_USER);
        assertThat(state.getPhase()).isEqualTo(AgentState.Phase.PAUSED);
        state.validate();

        state.resumeFromPause(AgentState.Phase.TOOL_PENDING);
        assertThat(state.getStatus()).isEqualTo(AgentState.TaskStatus.RUNNING);
        assertThat(state.getPendingInteractions()).isEmpty();
        state.validate();
    }

    @Test
    void pausingWithoutAReasonIsRejected() {
        assertThatThrownBy(() -> running().pause(null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> running().pause(List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** One batch costs one interruption, however many calls needed a user. */
    @Test
    void aPauseCarriesEveryCallOfTheBatchThatNeededTheUser() {
        AgentState state = running();
        state.pause(List.of(
                AgentState.Pending.approval(
                        "call-1", "delete_text", "fp-1", 999L),
                AgentState.Pending.approval(
                        "call-2", "delete_text", "fp-2", 999L)));

        assertThat(state.getPendingInteractions())
                .extracting(AgentState.Pending::toolCallId)
                .containsExactly("call-1", "call-2");
        state.validate();
    }

    @Test
    void aRunningStateMayNotCarryPendingInteractions() {
        AgentState state = running();
        state.pause(List.of(AgentState.Pending.askUser("call-1", "ask_user")));
        state.resumeFromPause(AgentState.Phase.MODEL_PENDING);

        assertThat(state.getPendingInteractions()).isEmpty();
        state.validate();
    }

    @Test
    void aFinishedRunCannotBeAdvancedAgain() {
        AgentState state = running();
        state.finish(AgentState.TaskStatus.SUCCEEDED, "completed", "done");

        assertThatThrownBy(() -> state.enterPhase(AgentState.Phase.TOOL_PENDING))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("FINISHED");
    }

    @Test
    void terminalStatusMustBeTerminal() {
        assertThatThrownBy(() ->
                running().finish(AgentState.TaskStatus.RUNNING, "nope", null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** Resume replays tool calls, so the same artifact can be reported twice. */
    @Test
    void artifactsAreDeduplicatedByReference() {
        AgentState state = running();
        state.recordArtifact(new AgentState.Artifact("documentId", "doc-1", "A"));
        state.recordArtifact(new AgentState.Artifact("documentId", "doc-1", "A"));
        state.recordArtifact(new AgentState.Artifact("documentId", "doc-2", "B"));

        assertThat(state.getArtifacts()).extracting(AgentState.Artifact::ref)
                .containsExactly("doc-1", "doc-2");
    }

    @Test
    void everyLimitCanExhaustTheBudgetOnItsOwn() {
        AgentState iterations = running(config(2, 99));
        iterations.chargeModelTurn(TokenUsage.zero());
        iterations.chargeModelTurn(TokenUsage.zero());
        assertThat(iterations.budgetExhausted()).isTrue();

        AgentState toolCalls = running(config(99, 1));
        toolCalls.chargeToolCall();
        assertThat(toolCalls.budgetExhausted()).isTrue();
    }

    /**
     * Tokens are counted and reported, not spent against a ceiling. A cumulative
     * total says nothing about whether the next request fits — ten turns of a
     * chain the model reads happily add up to more than one it would refuse —
     * so the limit that mattered was never the one this measured.
     */
    @Test
    void countsTokensWithoutStoppingTheRunOverThem() {
        AgentState state = running(config(99, 99));
        state.chargeModelTurn(new TokenUsage(1_000_000L, 1_000_000L, 1));

        assertThat(state.budgetExhausted()).isFalse();
        assertThat(state.getBudget().usage().promptTokens()).isEqualTo(1_000_000L);
    }

    @Test
    void aCopyDoesNotShareMutableState() {
        AgentState state = running();
        AgentState copy = state.copy();
        copy.recordArtifact(new AgentState.Artifact("documentId", "doc-1", "A"));
        copy.chargeToolCall();

        assertThat(state.getArtifacts()).isEmpty();
        assertThat(state.getBudget().toolCallsUsed()).isZero();
    }

    private AgentState running() {
        return running(config(6, 8));
    }

    private AgentState running(AgentState.RunConfig config) {
        return AgentState.create("task-1", "session-1", 1L, config, 0L);
    }

    private AgentState.RunConfig config(
            int maxIterations, int maxToolCalls) {
        return new AgentState.RunConfig(
                "summarize", "test-model", "system prompt",
                List.of("text_analyze"), null, "all", "<workspace>", "",
                maxIterations, maxToolCalls);
    }
}
