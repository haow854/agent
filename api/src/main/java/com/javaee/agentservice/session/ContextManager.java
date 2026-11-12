package com.javaee.agentservice.session;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.javaee.agentservice.model.AgentState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Assembles the message chain one model call is given.
 *
 * <p>Storage is one table; layering happens here. Which messages a call should
 * see is a policy re-decided every turn, so it is derived from the shape of the
 * rows rather than from a flag written when they were stored.</p>
 *
 * <p>Nothing is ever removed, only emptied: a tool call keeps its matching
 * response, in order, with the same id, because the protocol requires it. What
 * a response loses is its {@code data} — never the envelope, which says which
 * tool ran and whether it worked.</p>
 *
 * <p>A result is emptied once the user has been away since: the history has a
 * gap wide enough to say they stopped, and the result is on the far side of the
 * last one. Times come off the rows, never the clock at assembly — the chain a
 * resume rebuilds has to be the chain the run was using. A session worked
 * straight through empties nothing; size is {@link AutoCompactor}'s job.</p>
 *
 * <p>Prior conversation arrives as real turns rather than as a transcript
 * rendered into one message. What nobody said — the run's workspace and what the
 * user has been remembered to prefer — is pasted verbatim from the run's frozen
 * configuration and never computed here: a run that edits its own document, or
 * remembers something mid-session, would otherwise rewrite the head of its own
 * chain and lose the prefix the provider had cached.</p>
 */
@Component
public class ContextManager {

    private static final Logger log = LoggerFactory.getLogger(ContextManager.class);

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private SessionManager sessionManager;

    @Autowired
    private SessionSummaryMapper summaryMapper;

    @Value("${ai.agent.chars-per-token:1.5}")
    private double charsPerToken = 1.5;

    /** Recent results stay whole whatever their size, so a run still paging
     * through one document never loses the front of it. */
    @Value("${ai.agent.microcompact-keep-recent-results:10}")
    private int keepRecentResults = 10;

    /** How long after a run ends its evidence becomes disposable. Measured
     * between two recorded times, never against the clock at assembly. */
    @Value("${ai.agent.microcompact-after-ms:86400000}")
    private long microcompactAfterMs = 86_400_000L;

    private static final String CLEARED = "[旧的工具结果已清理，需要时重新调用]";

    /**
     * About how many tokens this chain will cost to send.
     *
     * <p>Counted from characters rather than tokenised. A tokeniser would be
     * exact for one model's vocabulary and wrong for the next, and the number is
     * used to decide whether to fold — where being early is cheap and being late
     * is a rejected request. So the divisor is deliberately low: it overstates,
     * which errs towards folding sooner.</p>
     */
    public long estimateTokens(List<Message> messages) {
        long chars = 0;
        for (Message message : messages) {
            chars += message.getContent() == null ? 0 : message.getContent().length();
            if (message instanceof AssistantMessage assistant) {
                for (AssistantMessage.ToolCall call : assistant.getToolCalls()) {
                    chars += call.name().length()
                            + (call.arguments() == null ? 0 : call.arguments().length());
                }
            } else if (message instanceof ToolResponseMessage responses) {
                for (ToolResponseMessage.ToolResponse response : responses.getResponses()) {
                    chars += response.responseData() == null
                            ? 0 : response.responseData().length();
                }
            }
        }
        return (long) Math.ceil(chars / Math.max(0.5, charsPerToken));
    }

    /**
     * Builds the chain for the next model call of {@code state}.
     *
     * <p>The system prompt is prepended from the run's frozen configuration; it
     * is not stored with the messages, so changing it never rewrites history and
     * a replay of several runs cannot produce several system turns.</p>
     *
     * <p>The read starts where the summary stops. What a fold covers is never
     * fetched and then skipped — the summary stands in for it.</p>
     */
    public List<Message> buildMessages(AgentState state) {
        List<Message> messages = new ArrayList<>();
        messages.add(new SystemMessage(state.getConfig().systemPrompt()));

        SessionSummary summary = summaryMapper.selectById(state.getSessionId());
        if (summary != null) {
            messages.add(new UserMessage(summary.getSummaryText()));
        }

        List<SessionMessage> history = sessionManager.getHistoryFrom(
                state.getSessionId(),
                summary == null ? null : summary.getCoveredTo() + 1);

        String currentTaskId = state.getTaskId();
        LocalDateTime resumedAt = resumedAfterBreak(history);
        Set<Long> emptied = resumedAt == null
                ? Set.of()
                : microcompact(history, currentTaskId, resumedAt);

        String memory = state.getConfig().memoryBlock();
        String workspace = state.getConfig().workspaceBlock();

        Set<String> declaredCalls = new HashSet<>();
        Set<String> answeredCalls = new HashSet<>();
        boolean attached = false;

        for (SessionMessage message : history) {
            boolean current = currentTaskId.equals(message.getTaskId());

            switch (message.getRole()) {
                case "user" -> {
                    String content = message.getContent() == null ? "" : message.getContent();
                    if (current && !attached) {
                        content = workspace
                                + (memory.isEmpty() ? "" : "\n\n" + memory)
                                + "\n\n<user_request>\n" + content + "\n</user_request>";
                        attached = true;
                    }
                    messages.add(new UserMessage(content));
                }
                case "assistant" -> {
                    List<AssistantMessage.ToolCall> calls = new ArrayList<>();
                    List<ToolCall> stored = message.getToolCalls();
                    if (stored != null) {
                        for (ToolCall call : stored) {
                            declaredCalls.add(call.id());
                            calls.add(new AssistantMessage.ToolCall(
                                    call.id(), call.type(), call.name(), call.arguments()));
                        }
                    }
                    if (calls.isEmpty()
                            && (message.getContent() == null || message.getContent().isBlank())) {
                        continue;
                    }
                    messages.add(new AssistantMessage(message.getContent(), Map.of(), calls));
                }
                case "tool" -> {
                    // No assistant call owns it. Dropping it leaves that call
                    // outstanding, which is what a resume will re-run.
                    if (!declaredCalls.contains(message.getToolCallId())) {
                        log.warn("Dropping orphan tool response {} in session {}",
                                message.getToolCallId(), state.getSessionId());
                        continue;
                    }
                    // Replay may append the same response twice; one call is
                    // answered exactly once.
                    if (!answeredCalls.add(message.getToolCallId())) {
                        continue;
                    }
                    String content = emptied.contains(message.getId())
                            ? withoutBody(message.getContent())
                            : message.getContent();
                    messages.add(new ToolResponseMessage(List.of(
                            new ToolResponseMessage.ToolResponse(
                                    message.getToolCallId(), message.getToolName(), content))));
                }
                default -> throw new IllegalStateException(
                        "Unsupported history role: " + message.getRole());
            }
        }
        return messages;
    }

    /** 用户最后一次久别之后第一条记录的时刻；一次都没断过返回 null。 */
    private LocalDateTime resumedAfterBreak(List<SessionMessage> history) {
        LocalDateTime resumedAt = null;
        LocalDateTime previous = null;
        for (SessionMessage message : history) {
            LocalDateTime at = message.getCreateTime();
            if (at == null) {
                continue;
            }
            if (previous != null
                    && Duration.between(previous, at).toMillis() > microcompactAfterMs) {
                resumedAt = at;
            }
            previous = at;
        }
        return resumedAt;
    }

    /** {@code resumedAt} 之前的工具结果只留信封，最后 N 条除外。 */
    private Set<Long> microcompact(List<SessionMessage> history,
                                       String currentTaskId,
                                       LocalDateTime resumedAt) {
        List<SessionMessage> responses = history.stream()
                .filter(message -> "tool".equals(message.getRole()))
                .toList();

        Set<Long> emptiable = new HashSet<>();
        for (int i = 0; i < responses.size() - keepRecentResults; i++) {
            SessionMessage message = responses.get(i);
            if (currentTaskId.equals(message.getTaskId())
                    // 用户原话不能重放，重跑等于再问一次用户。
                    || "ask_user".equals(message.getToolName())
                    || message.getContent() == null
                    || message.getCreateTime() == null
                    || !message.getCreateTime().isBefore(resumedAt)) {
                continue;
            }
            emptiable.add(message.getId());
        }
        return emptiable;
    }

    /**
     * The same payload with its bulky fields cut short.
     *
     * <p>Which field holds the bulk is a per-tool question, so it is not asked:
     * anything under {@code data} long enough to be worth cutting is cut,
     * whatever it is called, and everything else — the ids, offsets and flags a
     * re-read needs — is short and stays.</p>
     */
    private String withoutBody(String payload) {
        try {
            ObjectNode root = (ObjectNode) objectMapper.readTree(payload);
            root.put("data", CLEARED);
            return objectMapper.writeValueAsString(root);
        } catch (Exception e) {
            return payload;
        }
    }

    /**
     * Tool calls of the last assistant turn that have no tool response yet.
     *
     * <p>Read off the chain rather than stored. The chain already says it: the
     * model's turn is not done until every call it made has a response, so a
     * stored copy could only disagree with it.</p>
     *
     * <p>Walks back from the end and stops at that turn, since everything after
     * it is its own responses. Earlier history is never touched.</p>
     */
    public List<AssistantMessage.ToolCall> pendingToolCalls(List<Message> messages) {
        Set<String> answered = new HashSet<>();
        for (int i = messages.size() - 1; i >= 0; i--) {
            Message message = messages.get(i);
            if (message instanceof ToolResponseMessage responses) {
                responses.getResponses()
                        .forEach(response -> answered.add(response.id()));
            } else if (message instanceof AssistantMessage assistant
                    && assistant.hasToolCalls()) {
                return assistant.getToolCalls().stream()
                        .filter(call -> !answered.contains(call.id()))
                        .toList();
            }
        }
        return List.of();
    }

}
