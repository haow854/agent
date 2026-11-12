package com.javaee.agentservice.session;

import com.javaee.agentservice.model.AgentState;
import com.javaee.agentservice.llm.LlmClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AutoCompactorTest {

    private AutoCompactor compactor;
    private SessionManager sessionManager;
    private SessionSummaryMapper summaryMapper;
    private LlmClient modelClient;

    @BeforeEach
    void setUp() {
        compactor = new AutoCompactor();
        sessionManager = mock(SessionManager.class);
        summaryMapper = mock(SessionSummaryMapper.class);
        modelClient = mock(LlmClient.class);

        ReflectionTestUtils.setField(compactor, "sessionManager", sessionManager);
        ReflectionTestUtils.setField(compactor, "summaryMapper", summaryMapper);
        ReflectionTestUtils.setField(compactor, "modelClient", modelClient);
        when(modelClient.completeText(anyString(), anyString())).thenReturn("助手做了些事。");
    }

    /**
     * The running task is left whole. It is the reasoning in progress, and a
     * summary of half an argument is not an argument — nor a chain the API will
     * accept, since its tool calls would lose their responses.
     */
    @Test
    void foldsEverythingBeforeTheRunningTaskAndNothingOfIt() {
        history(
                user(1, "task-1", "总结这个文档"),
                assistant(2, "task-1", "总结如下"),
                user(3, "task-2", "再润色一下"),
                assistant(4, "task-2", "好的")
        );

        compactor.compact(state("task-2"));

        assertThat(saved().getCoveredTo()).isEqualTo(2L);
    }

    /**
     * What the user said is copied, not summarised. It is the only record of
     * what was actually asked, and a model told to compress will paraphrase away
     * a constraint sooner than it will drop its own reasoning.
     */
    @Test
    void copiesTheUsersOwnWordsRatherThanSummarisingThem() {
        history(
                user(1, "task-1", "改成正式文风，别动第一段"),
                assistant(2, "task-1", "改好了"),
                user(3, "task-2", "现在呢")
        );

        compactor.compact(state("task-2"));

        assertThat(saved().getSummaryText()).contains("改成正式文风，别动第一段");
    }

    /** Which documents were touched is read off the calls, not inferred. */
    @Test
    void namesTheDocumentsTheFoldedTurnsTouched() {
        history(
                user(1, "task-1", "读一下"),
                assistant(2, "task-1", "", new ToolCall("call-a", "function",
                        "document_read", "{\"documentId\":\"doc-7\"}")),
                toolResult(3, "task-1", "call-a", "document_read", "{}"),
                assistant(4, "task-1", "看完了"),
                user(5, "task-2", "继续")
        );

        compactor.compact(state("task-2"));

        assertThat(saved().getSummaryText()).contains("doc-7");
    }

    /**
     * The parts that matter most need no model, so a model that is down costs
     * detail rather than the fold — which would otherwise leave the run with no
     * way to get smaller and nothing to do but fail.
     */
    @Test
    void stillFoldsWhenTheModelCannotBeReached() {
        when(modelClient.completeText(anyString(), anyString()))
                .thenThrow(new IllegalStateException("model unavailable"));
        history(
                user(1, "task-1", "第一个问题"),
                assistant(2, "task-1", "第一个回答"),
                user(3, "task-2", "第二个问题")
        );

        compactor.compact(state("task-2"));
        assertThat(saved().getSummaryText()).contains("第一个问题");
    }

    /**
     * Nothing left to fold is the signal the run cannot get any smaller. Saying
     * so lets it stop with a reason instead of sending a request it knows the
     * model will refuse.
     */
    @Test
    void reportsThatNothingIsLeftToFold() {
        history(user(1, "task-1", "唯一的问题"));

        assertThat(compactor.compact(state("task-1"))).isFalse();
        verify(summaryMapper, never()).upsert(any());
    }

    /** A second fold carries the first one forward rather than losing it. */
    @Test
    void keepsWhatAnEarlierFoldAlreadyCovered() {
        fold("更早的摘要：用户问过 A", 1L);
        history(
                user(1, "task-1", "问题 A"),
                user(2, "task-2", "问题 B"),
                assistant(3, "task-2", "回答 B"),
                user(4, "task-3", "问题 C")
        );

        compactor.compact(state("task-3"));

        SessionSummary saved = saved();
        assertThat(saved.getCoveredTo()).isEqualTo(3L);
        assertThat(saved.getSummaryText()).contains("更早的摘要：用户问过 A").contains("问题 B");
    }

    private void fold(String text, long coveredTo) {
        SessionSummary summary = new SessionSummary();
        summary.setSessionId("session-1");
        summary.setSummaryText(text);
        summary.setCoveredTo(coveredTo);
        when(summaryMapper.selectById("session-1")).thenReturn(summary);
    }

    private SessionSummary saved() {
        ArgumentCaptor<SessionSummary> captor = ArgumentCaptor.forClass(SessionSummary.class);
        verify(summaryMapper).upsert(captor.capture());
        return captor.getValue();
    }

    /** Honours the lower bound the way the query does. */
    private void history(SessionMessage... rows) {
        List<SessionMessage> all = List.of(rows);
        when(sessionManager.getHistoryFrom(eq("session-1"), any()))
                .thenAnswer(call -> {
                    Long from = call.getArgument(1);
                    return from == null
                            ? all
                            : all.stream().filter(row -> row.getId() >= from).toList();
                });
    }

    private SessionMessage user(long id, String taskId, String content) {
        SessionMessage message = row(id, taskId, "user");
        message.setContent(content);
        return message;
    }

    private SessionMessage assistant(long id, String taskId, String content, ToolCall... calls) {
        SessionMessage message = row(id, taskId, "assistant");
        message.setContent(content);
        message.setToolCalls(calls.length == 0 ? null : List.of(calls));
        return message;
    }

    private SessionMessage toolResult(long id, String taskId, String toolCallId,
                                      String toolName, String content) {
        SessionMessage message = row(id, taskId, "tool");
        message.setToolCallId(toolCallId);
        message.setToolName(toolName);
        message.setContent(content);
        return message;
    }

    private SessionMessage row(long id, String taskId, String role) {
        SessionMessage message = new SessionMessage();
        message.setId(id);
        message.setSessionId("session-1");
        message.setTaskId(taskId);
        message.setRole(role);
        return message;
    }

    private AgentState state(String taskId) {
        return AgentState.create(taskId, "session-1", 1L,
                new AgentState.RunConfig("当前问题", "test-model", "system prompt",
                        List.of("document_read"), null, "all", "<workspace>", "", 6, 8),
                0L);
    }
}
