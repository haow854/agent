package com.javaee.agentservice.session;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.javaee.agentservice.model.AgentState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ContextManagerTest {

    private static final String BODY = "x".repeat(2000);
    private static final LocalDateTime T0 = LocalDateTime.of(2026, 1, 1, 9, 0);
    private static final Duration A_DAY = Duration.ofDays(1).plusMillis(1);
    private static final Duration TEN_MINUTES = Duration.ofMinutes(10);

    private ContextManager contextManager;
    private SessionManager sessionManager;
    private SessionSummaryMapper summaryMapper;

    @BeforeEach
    void setUp() {
        contextManager = new ContextManager();
        sessionManager = mock(SessionManager.class);
        summaryMapper = mock(SessionSummaryMapper.class);

        ReflectionTestUtils.setField(contextManager, "objectMapper", new ObjectMapper());
        ReflectionTestUtils.setField(contextManager, "sessionManager", sessionManager);
        ReflectionTestUtils.setField(contextManager, "summaryMapper", summaryMapper);
        ReflectionTestUtils.setField(contextManager, "charsPerToken", 1.5);
    }

    /**
     * An earlier run's tool traffic stays on the chain. It used to be dropped
     * whole, which meant every new task started blind: what the last one read
     * had to be read again to be used.
     */
    @Test
    void keepsEarlierRunsToolTrafficRatherThanReducingThemToQuestionAndAnswer() {
        history(
                user(1, "task-1", "总结这个文档"),
                assistant(2, "task-1", "", call("call-a", "document_read")),
                toolResult(3, "task-1", "call-a", "document_read", "{\"ok\":1}"),
                assistant(4, "task-1", "总结如下"),
                user(5, "task-2", "再润色一下"),
                assistant(6, "task-2", "", call("call-b", "text_analyze")),
                toolResult(7, "task-2", "call-b", "text_analyze", "{\"ok\":2}")
        );

        List<Message> messages = contextManager.buildMessages(state("task-2"));

        assertThat(messages).hasSize(8);
        assertThat(messages.get(0)).isInstanceOf(SystemMessage.class);
        assertThat(messages.get(1).getContent()).isEqualTo("总结这个文档");
        assertThat(((AssistantMessage) messages.get(2)).getToolCalls())
                .extracting(AssistantMessage.ToolCall::id).containsExactly("call-a");
        assertThat(messages.get(3)).isInstanceOf(ToolResponseMessage.class);
        assertThat(messages.get(4).getContent()).isEqualTo("总结如下");
        assertThat(messages.get(5).getContent())
                .endsWith("<user_request>\n再润色一下\n</user_request>");
        assertThat(((AssistantMessage) messages.get(6)).getToolCalls())
                .extracting(AssistantMessage.ToolCall::id).containsExactly("call-b");
        assertThat(messages.get(7)).isInstanceOf(ToolResponseMessage.class);
    }

    /**
     * The body goes, the envelope stays. Which tool ran, on what, and how far it
     * got costs a few dozen characters and is exactly what the model needs to
     * fetch the rest again; dropping it would force a blind re-read.
     */
    @Test
    void emptiesTheBodyOfAnOldLargeResultAndKeepsTheEnvelope() {
        ReflectionTestUtils.setField(contextManager, "keepRecentResults", 0);
        history(finishedReadThenAsk("document_read", A_DAY));

        String payload = onlyToolResponse(contextManager.buildMessages(state("task-2")));

        assertThat(payload)
                .doesNotContain(BODY)
                .contains("旧的工具结果已清理")
                .contains("\"toolName\":\"document_read\"")
                .contains("\"status\":\"success\"");
    }

    /**
     * Age alone empties nothing. Somebody four hours into one sitting is still
     * on the same problem, and the result they read first is still the evidence.
     */
    @Test
    void leavesEverythingWholeInASessionWorkedStraightThrough() {
        ReflectionTestUtils.setField(contextManager, "keepRecentResults", 0);
        List<SessionMessage> rows =
                new ArrayList<>(List.of(finishedReadThenAsk("document_read", TEN_MINUTES)));
        LocalDateTime at = rows.get(rows.size() - 1).getCreateTime();
        for (int i = 0; i < 24; i++) {
            at = at.plus(TEN_MINUTES);
            rows.add(at(user(6 + i, "task-2", "接着改"), at));
        }
        history(rows.toArray(new SessionMessage[0]));

        assertThat(onlyToolResponse(contextManager.buildMessages(state("task-2"))))
                .contains(BODY);
    }

    /** Whatever else is true of it, a recent result is left alone. */
    @Test
    void leavesRecentResultsWholeHoweverOldTheirRunIs() {
        history(finishedReadThenAsk("document_read", A_DAY));

        assertThat(onlyToolResponse(contextManager.buildMessages(state("task-2"))))
                .contains(BODY);
    }

    /**
     * Times are compared between two recorded values — adjacent rows' create
     * times — so the same history always builds the same chain, whenever it is
     * built.
     */
    @Test
    void leavesAFinishedRunsResultsWholeUntilTheUserHasBeenAway() {
        ReflectionTestUtils.setField(contextManager, "keepRecentResults", 0);
        history(finishedReadThenAsk("document_read", TEN_MINUTES));

        assertThat(onlyToolResponse(contextManager.buildMessages(state("task-2"))))
                .contains(BODY);
    }

    /** What the user said cannot be fetched again; asking twice is not a retry. */
    @Test
    void neverEmptiesAnAnswerTheUserGave() {
        ReflectionTestUtils.setField(contextManager, "keepRecentResults", 0);
        history(finishedReadThenAsk("ask_user", A_DAY));

        assertThat(onlyToolResponse(contextManager.buildMessages(state("task-2"))))
                .contains(BODY);
    }

    /**
     * The fold stands in for the messages it covers rather than joining them.
     * What it covers is never read back: the summary's own bound is where the
     * query starts.
     */
    @Test
    void putsTheFoldInPlaceOfTheTurnsItCovers() {
        fold("以下是更早对话的摘要。用户问过 A。", 4L);
        history(
                user(1, "task-1", "问题 A"),
                assistant(4, "task-1", "回答 A"),
                user(5, "task-2", "问题 B")
        );

        List<Message> messages = contextManager.buildMessages(state("task-2"));

        assertThat(messages).extracting(Message::getContent)
                .anyMatch(content -> content.contains("用户问过 A"));
        assertThat(messages).extracting(Message::getContent)
                .noneMatch(content -> content.equals("问题 A"))
                .noneMatch(content -> content.equals("回答 A"));
    }

    @Test
    void systemPromptComesFromTheRunAndAppearsOnceHoweverManyRunsReplay() {
        history(
                user(1, "task-1", "第一个问题"),
                assistant(2, "task-1", "第一个回答"),
                user(3, "task-2", "第二个问题")
        );

        List<Message> messages = contextManager.buildMessages(state("task-2"));

        assertThat(messages).filteredOn(SystemMessage.class::isInstance)
                .singleElement()
                .satisfies(message ->
                        assertThat(message.getContent()).isEqualTo("frozen system prompt"));
    }

    /** Ambient state is attached to the current question, not made a turn. */
    @Test
    void attachesBlocksToCurrentQuestionOnly() {
        history(
                user(1, "task-1", "旧问题"),
                assistant(2, "task-1", "旧回答"),
                user(3, "task-2", "新问题")
        );

        List<Message> messages = contextManager.buildMessages(state("task-2"));

        assertThat(messages.get(1).getContent())
                .isEqualTo("旧问题")
                .doesNotContain("workspace");
        // 逐字取自配置，不在这里现算——现算意味着 run 改一次文档就重写一次链头。
        assertThat(messages.get(3).getContent())
                .startsWith("<workspace>\nscope: all\n</workspace>")
                .doesNotContain("<memory>")
                .endsWith("<user_request>\n新问题\n</user_request>");
    }

    /**
     * A crash between storing a response and recording it lets replay store the
     * same one twice. One call is answered exactly once.
     */
    @Test
    void answersEachToolCallOnceEvenWhenReplayAppendedItTwice() {
        history(
                user(1, "task-1", "问题"),
                assistant(2, "task-1", "", call("call-a", "document_read")),
                toolResult(3, "task-1", "call-a", "document_read", "{\"n\":1}"),
                toolResult(4, "task-1", "call-a", "document_read", "{\"n\":2}")
        );

        List<Message> messages = contextManager.buildMessages(state("task-1"));

        assertThat(messages).filteredOn(ToolResponseMessage.class::isInstance)
                .singleElement()
                .satisfies(message -> assertThat(
                        ((ToolResponseMessage) message).getResponses().get(0).responseData())
                        .isEqualTo("{\"n\":1}"));
    }

    @Test
    void pendingCallsAreTheLastTurnsCallsThatNoResponseAnswered() {
        List<Message> messages = new ArrayList<>(List.of(
                new UserMessage("问题"),
                new AssistantMessage("", Map.of(), List.of(
                        new AssistantMessage.ToolCall("call-a", "function", "t1", "{}"),
                        new AssistantMessage.ToolCall("call-b", "function", "t2", "{}"),
                        new AssistantMessage.ToolCall("call-c", "function", "t3", "{}"))),
                new ToolResponseMessage(List.of(
                        new ToolResponseMessage.ToolResponse("call-a", "t1", "{}")))
        ));

        assertThat(contextManager.pendingToolCalls(messages))
                .extracting(AssistantMessage.ToolCall::id)
                .containsExactly("call-b", "call-c");
    }

    @Test
    void aFullyAnsweredTurnLeavesNothingPending() {
        List<Message> messages = new ArrayList<>(List.of(
                new AssistantMessage("", Map.of(), List.of(
                        new AssistantMessage.ToolCall("call-a", "function", "t1", "{}"))),
                new ToolResponseMessage(List.of(
                        new ToolResponseMessage.ToolResponse("call-a", "t1", "{}")))
        ));

        assertThat(contextManager.pendingToolCalls(messages)).isEmpty();
    }

    /**
     * A response with no assistant call to own it is dropped, which leaves that
     * call outstanding — exactly what a resume re-runs.
     */
    @Test
    void dropsAnOrphanToolResponseRatherThanBuildingAnInvalidChain() {
        history(
                user(1, "task-1", "问题"),
                toolResult(2, "task-1", "call-ghost", "document_read", "{}")
        );

        List<Message> messages = contextManager.buildMessages(state("task-1"));

        assertThat(messages).noneMatch(ToolResponseMessage.class::isInstance);
    }

    private void fold(String text, long coveredTo) {
        SessionSummary summary = new SessionSummary();
        summary.setSessionId("session-1");
        summary.setSummaryText(text);
        summary.setCoveredTo(coveredTo);
        when(summaryMapper.selectById("session-1")).thenReturn(summary);
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

    /**
     * task-1 reads something big and answers; task-2 opens {@code gap} later.
     * The gap the rule measures is between two recorded create times.
     */
    private SessionMessage[] finishedReadThenAsk(String tool, Duration gap) {
        SessionMessage answered = assistant(4, "task-1", "总结如下");
        return new SessionMessage[]{
                user(1, "task-1", "总结这个文档"),
                assistant(2, "task-1", "", call("call-a", tool)),
                toolResult(3, "task-1", "call-a", tool,
                        "{\"toolName\":\"" + tool + "\",\"status\":\"success\",\"data\":{"
                                + "\"markdown\":\"" + BODY + "\","
                                + "\"documentId\":\"doc-1\",\"to\":50}}"),
                answered,
                at(user(5, "task-2", "再润色一下"), answered.getCreateTime().plus(gap))
        };
    }

    private String onlyToolResponse(List<Message> messages) {
        return messages.stream()
                .filter(ToolResponseMessage.class::isInstance)
                .map(message -> ((ToolResponseMessage) message).getResponses().get(0))
                .map(ToolResponseMessage.ToolResponse::responseData)
                .reduce((first, second) -> {
                    throw new IllegalStateException("expected exactly one tool response");
                })
                .orElseThrow();
    }

    private SessionMessage user(long id, String taskId, String content) {
        SessionMessage message = row(id, taskId, "user");
        message.setContent(content);
        return message;
    }

    private SessionMessage assistant(long id, String taskId, String content,
                                          ToolCall... calls) {
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
        message.setCreateTime(T0.plusSeconds(id));
        return message;
    }

    private SessionMessage at(SessionMessage message, LocalDateTime createTime) {
        message.setCreateTime(createTime);
        return message;
    }

    private ToolCall call(String id, String name) {
        return new ToolCall(id, "function", name, "{}");
    }

    private AgentState state(String taskId) {
        AgentState.RunConfig config = new AgentState.RunConfig(
                "当前问题", "test-model", "frozen system prompt",
                List.of("document_read"), null, "all",
                "<workspace>\nscope: all\n</workspace>", "", 6, 8);
        return AgentState.create(taskId, "session-1", 1L, config, 0L);
    }
}
