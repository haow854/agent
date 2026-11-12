package com.javaee.agentservice.orchestration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.javaee.agentservice.event.AgentProgressBroadcaster;
import com.javaee.agentservice.event.AgentProgressEvent;
import com.javaee.agentservice.model.AgentState;
import com.javaee.agentservice.model.AgentToolResult;
import com.javaee.agentservice.model.Checkpoint;
import com.javaee.agentservice.prompt.PromptEngineeringService;
import com.javaee.agentservice.session.ToolCall;
import com.javaee.agentservice.session.AutoCompactor;
import com.javaee.agentservice.session.ContextManager;
import com.javaee.agentservice.session.SessionManager;
import com.javaee.agentservice.task.CheckpointStore;
import com.javaee.agentservice.tool.AgentToolRegistry;
import com.javaee.agentservice.llm.ModelToolDefinition;
import com.javaee.agentservice.llm.LlmClient;
import com.javaee.agentservice.llm.TokenUsage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

/**
 * Drives one task as a single native assistant/tool message loop.
 *
 * <p>Every model turn and tool response is appended to the session stream and
 * then recorded in the checkpoint, in that order: a checkpoint states what has
 * happened and never what is about to. The stream is authoritative, so a
 * checkpoint that lags a crash costs a re-derivation, not correctness. What it
 * cannot recover is the tool call that ran without its response being written —
 * resume replays that call, which is why tools carry the idempotency.</p>
 *
 * <p>The runner holds a lease for the whole run. Losing it — to a recovery sweep
 * that judged this run dead, or to a cancellation — makes the next checkpoint
 * write fail, and the run stops there rather than continuing to append into a
 * stream another writer now owns.</p>
 */
@Component
public class ReactRunner {

    private static final Logger log = LoggerFactory.getLogger(ReactRunner.class);

    @Autowired
    private LlmClient modelClient;
    @Autowired
    private AgentToolRegistry toolRegistry;
    @Autowired
    private AgentToolGateway toolGateway;
    @Autowired
    private ToolBatchExecutor toolBatchExecutor;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private SessionManager sessionManager;
    @Autowired
    private ContextManager contextManager;
    @Autowired
    private AutoCompactor autoCompactor;
    @Autowired
    private PromptEngineeringService promptEngineeringService;
    @Autowired
    private CheckpointStore checkpointStore;
    @Autowired
    private AgentProgressBroadcaster progressBroadcaster;

    @Value("${ai.agent.context-window-tokens:1000000}")
    private long contextWindowTokens = 1_000_000L;

    @Value("${ai.agent.auto-compact-at-ratio:0.9}")
    private double autoCompactAtRatio = 0.9;

    /**
     * Runs the task until it finishes or needs something from the user.
     *
     * @param leased    checkpoint whose lease this caller holds
     * @param answers   user answers, keyed by the tool call id they reply to
     * @param approved  ids of the paused calls the user granted
     * @return the last checkpoint written for this task
     */
    public Checkpoint run(Checkpoint leased,
                          Map<String, String> answers,
                          List<String> approved) {
        AgentState state = leased.state().copy();
        Checkpoint checkpoint = leased;
        try {
            Map<String, String> approvedByCall = approvedByCall(state, approved);
            appendAnswers(state, answers);

            // A call with no tool response next to it on the chain still has to
            // run, in the order the chain gives it back — which is the model's.
            List<Message> messages = contextManager.buildMessages(state);
            List<AssistantMessage.ToolCall> pending =
                    new ArrayList<>(contextManager.pendingToolCalls(messages));

            if (state.getPhase() == AgentState.Phase.PAUSED) {
                AgentState.Phase nextPhase = pending.isEmpty()
                        ? AgentState.Phase.MODEL_PENDING
                        : AgentState.Phase.TOOL_PENDING;
                state.resumeFromPause(nextPhase);
                checkpoint = commit(checkpoint, state);
            } else if (!pending.isEmpty()
                    && state.getPhase() == AgentState.Phase.MODEL_PENDING) {
                // The checkpoint lags the stream: an assistant turn landed and
                // the commit that would have recorded it did not. The chain is
                // the truth, so the phase is corrected to what it says rather
                // than the run failing on a stale one.
                state.enterPhase(AgentState.Phase.TOOL_PENDING);
                checkpoint = commit(checkpoint, state);
            }

            while (true) {
                if (pending.isEmpty()) {
                    // Measured on the chain about to be sent, which is the only
                    // moment its size is known. A run's cumulative spend says
                    // nothing about whether this one request fits.
                    if (contextManager.estimateTokens(messages)
                            > contextWindowTokens * autoCompactAtRatio) {
                        if (!autoCompactor.compact(state)) {
                            return terminal(checkpoint, state,
                                    AgentState.TaskStatus.FAILED, "context_exhausted");
                        }
                        messages = contextManager.buildMessages(state);
                    }
                    checkpoint = modelTurn(checkpoint, state, messages, pending);
                } else {
                    checkpoint = toolBatch(
                            checkpoint, state, messages, pending, approvedByCall);
                }
                if (state.getPhase() == AgentState.Phase.FINISHED
                        || state.getPhase() == AgentState.Phase.PAUSED) {
                    return checkpoint;
                }
                // The grants covered this batch only. The next model turn asks
                // for new calls, and those need approvals of their own.
                approvedByCall = Map.of();
            }
        } catch (LeaseLostException e) {
            log.info("Run {} stopped: {}", state.getTaskId(), e.getMessage());
            Checkpoint current = checkpointStore.load(state.getTaskId());
            return current == null ? checkpoint : current;
        } catch (Exception e) {
            log.warn("Run {} failed", state.getTaskId(), e);
            state.recordError(e.getMessage());
            try {
                return terminal(checkpoint, state,
                        AgentState.TaskStatus.FAILED, "run_failed");
            } catch (LeaseLostException lost) {
                Checkpoint current = checkpointStore.load(state.getTaskId());
                return current == null ? checkpoint : current;
            }
        }
    }

    /**
     * Asks the model for the next move. A turn with no tool calls is the run's
     * answer.
     */
    private Checkpoint modelTurn(Checkpoint checkpoint,
                                 AgentState state,
                                 List<Message> messages,
                                 List<AssistantMessage.ToolCall> pending) {
        boolean outOfBudget = state.budgetExhausted();
        List<ModelToolDefinition> tools = outOfBudget
                ? List.of()
                : toolRegistry.modelTools(state.getConfig().availableTools());
        if (outOfBudget) {
            messages.add(new UserMessage(
                    promptEngineeringService.budgetExhaustedInstruction()));
        }
        LlmClient.ModelTurn turn = modelClient.complete(
                state.getConfig().model(), messages, tools, true);
        AssistantMessage assistant = turn.message();
        state.chargeModelTurn(turn.usage());
        logTurnUsage(state, turn.usage(), messages);

        List<ToolCall> calls = assistant.getToolCalls().stream()
                .map(call -> new ToolCall(
                        call.id(), call.type(), call.name(), call.arguments()))
                .toList();
        sessionManager.appendAssistant(
                state.getSessionId(), state.getTaskId(), assistant.getContent(), calls);
        messages.add(assistant);
        publishStep(state, "step_model", "Model turn completed", Map.of(
                "iteration", state.getBudget().iterationsUsed(),
                "text", assistant.getContent() == null ? "" : assistant.getContent(),
                "toolCalls", assistant.getToolCalls().stream()
                        .map(call -> Map.of(
                                "id", call.id(),
                                "name", call.name(),
                                "arguments", call.arguments() == null ? "" : call.arguments()))
                        .toList()));

        String content = assistant.getContent();
        if (content != null && !content.isBlank()) {
            state.setAnswer(content);
        }
        if (!assistant.hasToolCalls()) {
            if (content == null || content.isBlank()) {
                return terminal(checkpoint, state,
                        AgentState.TaskStatus.FAILED, "empty_model_response");
            }
            return terminal(checkpoint, state, AgentState.TaskStatus.SUCCEEDED,
                    outOfBudget ? "budget_exhausted" : "completed");
        }

        pending.addAll(assistant.getToolCalls());
        state.enterPhase(AgentState.Phase.TOOL_PENDING);
        return commit(checkpoint, state);
    }

    /**
     * Runs the whole batch at once, then pauses for every call that needed the
     * user.
     *
     * <p>Execution is concurrent, recording is not. The calls start together —
     * the model asked for them as one move, so nothing orders them — but their
     * responses are read back positionally and appended on this thread, in the
     * order the model declared. A batch's stream entries therefore do not
     * depend on which call happened to finish first.</p>
     *
     * <p>A call that needs the user is collected rather than ending the batch,
     * so the rest is still recorded and the user is asked about all of them in
     * one go. Nothing is written for such a call, which is exactly what leaves
     * it outstanding on the chain for a resume to run again.</p>
     *
     * <p>One checkpoint closes the batch. A finer one would buy nothing: what a
     * resume needs is the responses, and those are already on the stream — the
     * source a rebuilt chain is derived from.</p>
     */
    private Checkpoint toolBatch(Checkpoint checkpoint,
                                 AgentState state,
                                 List<Message> messages,
                                 List<AssistantMessage.ToolCall> pending,
                                 Map<String, String> approvedByCall) {
        List<AssistantMessage.ToolCall> batch = List.copyOf(pending);
        pending.clear();

        List<Callable<AgentToolResult>> work = batch.stream()
                .map(call -> (Callable<AgentToolResult>) () ->
                        toolGateway.execute(call, state, approvedByCall.get(call.id())))
                .toList();
        List<Future<AgentToolResult>> futures = toolBatchExecutor.submitAll(work);

        List<AgentState.Pending> needsUser = new ArrayList<>();
        for (int i = 0; i < batch.size(); i++) {
            AssistantMessage.ToolCall call = batch.get(i);
            AgentToolResult result = await(futures.get(i), call);
            if (result.isRequiresAction()) {
                needsUser.add(pendingFrom(call, result));
                continue;
            }

            state.chargeToolCall();
            recordArtifacts(state, result);
            String payload = serialize(result);
            sessionManager.appendTool(state.getSessionId(), state.getTaskId(),
                    call.id(), call.name(), payload);

            ToolResponseMessage.ToolResponse response =
                    new ToolResponseMessage.ToolResponse(call.id(), call.name(), payload);
            messages.add(new ToolResponseMessage(List.of(response)));
            publishStep(state, "step_tool", "Tool finished", Map.of(
                    "toolCallId", call.id(),
                    "toolName", call.name(),
                    "status", result.getStatus(),
                    "message", result.getMessage() == null ? "" : result.getMessage()));
        }

        if (!needsUser.isEmpty()) {
            state.pause(needsUser);
        } else {
            state.enterPhase(AgentState.Phase.MODEL_PENDING);
        }
        return commit(checkpoint, state);
    }

    /**
     * Reads one call's result back. A worker that failed to produce one at all
     * becomes that call's error, so the batch still has a response for it.
     */
    private AgentToolResult await(Future<AgentToolResult> future,
                                  AssistantMessage.ToolCall call) {
        try {
            return future.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return AgentToolResult.error(call.name(), "Tool execution was interrupted");
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            return AgentToolResult.error(call.name(), cause.getMessage());
        }
    }

    /**
     * Writes the user's answers to the stream as tool responses, so the calls
     * the run paused on now have their replies.
     *
     * <p>Writes no checkpoint. If the process dies before the caller commits,
     * the answers are on the stream while the checkpoint still says paused, so
     * the next resume writes a second response for the same call. The chain is
     * assembled keeping the first, which is the answer the user gave.</p>
     *
     * <p>Approvals are not handled here. Nothing is written for them, so those
     * calls still have no response and the batch runs them again with their
     * tokens.</p>
     */
    private void appendAnswers(AgentState state, Map<String, String> answers) {
        for (AgentState.Pending pending : state.getPendingInteractions()) {
            if (pending.kind() != AgentState.Pending.Kind.ASK_USER) {
                continue;
            }
            String answer = answers.get(pending.toolCallId());
            AgentToolResult result = AgentToolResult.success(
                    pending.toolName(),
                    "User input received",
                    Map.of("userInput", answer == null ? "" : answer));
            sessionManager.appendTool(state.getSessionId(), state.getTaskId(),
                    pending.toolCallId(), pending.toolName(), serialize(result));
        }
    }

    /**
     * 调用方只说批了哪几次调用，批的是什么参数从 checkpoint 上读，不听调用方的。
     * 没批的、过期的、压根没停在这儿的都没有条目，会被重新挑战。
     */
    private Map<String, String> approvedByCall(AgentState state, List<String> granted) {
        if (granted == null || granted.isEmpty()) {
            return Map.of();
        }
        Set<String> approved = new HashSet<>(granted);
        long now = System.currentTimeMillis();
        Map<String, String> byCall = new HashMap<>();
        for (AgentState.Pending pending : state.getPendingInteractions()) {
            if (pending.kind() == AgentState.Pending.Kind.APPROVAL
                    && approved.contains(pending.toolCallId())
                    && pending.expiresAt() > now) {
                byCall.put(pending.toolCallId(), pending.paramsFingerprint());
            }
        }
        return byCall;
    }

    private AgentState.Pending pendingFrom(AssistantMessage.ToolCall call,
                                           AgentToolResult result) {
        Map<String, Object> data = result.getData();
        Object fingerprint = data.get(AgentToolGateway.APPROVAL_FINGERPRINT);
        if (fingerprint == null) {
            return AgentState.Pending.askUser(call.id(), result.getToolName());
        }
        Object expiresAt = data.get(AgentToolGateway.APPROVAL_EXPIRES_AT);
        return AgentState.Pending.approval(
                call.id(),
                result.getToolName(),
                String.valueOf(fingerprint),
                expiresAt instanceof Number number ? number.longValue() : 0L);
    }

    /**
     * Keeps a reference to what the call produced. The chain carries it too, for
     * now: this survives the tool result being compacted away.
     *
     * <p>{@code persisted} is the tool's own claim that it created something
     * durable, not merely touched one — document_read returns a documentId too.</p>
     */
    private void recordArtifacts(AgentState state, AgentToolResult result) {
        Map<String, Object> data = result.getData();
        Object documentId = data.get("documentId");
        if (documentId != null && Boolean.TRUE.equals(data.get("persisted"))) {
            AgentState.Artifact artifact = new AgentState.Artifact(
                    "documentId",
                    String.valueOf(documentId),
                    String.valueOf(data.getOrDefault("title", documentId)));
            state.recordArtifact(artifact);
        }
    }

    /**
     * One line per model turn, because the shape of the curve is the question
     * and the run's totals cannot show it.
     *
     * <p>A run whose prefix survives from one turn to the next shows the cached
     * share climbing towards the whole chain; one that rewrites earlier turns
     * shows it flat near zero however large the chain gets. Those two are the
     * same number of tokens spent and a different thing to fix, so the split is
     * recorded where it happens rather than only summed.</p>
     *
     * <p>The chain length is on the same line: a cached share that drops on the
     * turn the chain shrinks is the assembly rewriting history, and no other
     * pair of numbers says that.</p>
     */
    /**
     * What this turn cost, and what of it was tool results.
     *
     * <p>The result sizes are listed oldest first because that is the order any
     * clearing rule works through them in: what a given "keep the last n" would
     * have saved is the sum of this list minus its last n, and there is no other
     * place that number can be read off after the fact.</p>
     */
    private void logTurnUsage(AgentState state, TokenUsage turn, List<Message> messages) {
        long prompt = turn.promptTokens();
        long cached = turn.cachedPromptTokens();
        log.info("Turn {} of task {}: messages={} est={} prompt={} cached={} ({}%) "
                        + "completion={} resultChars={}",
                state.getBudget().iterationsUsed(),
                state.getTaskId(),
                messages.size(),
                contextManager.estimateTokens(messages),
                prompt,
                cached,
                prompt == 0L ? 0L : Math.round(cached * 100.0 / prompt),
                turn.completionTokens(),
                messages.stream()
                        .filter(ToolResponseMessage.class::isInstance)
                        .map(message -> ((ToolResponseMessage) message).getResponses().stream()
                                .mapToInt(response -> response.responseData() == null
                                        ? 0 : response.responseData().length())
                                .sum())
                        .toList());
    }

    /**
     * 一步一推，让前端不用等整个 run 结束才有东西看。
     *
     * <p>广播失败不抛，掉一条进度不该让任务失败。</p>
     */
    private void publishStep(AgentState state,
                             String eventType,
                             String message,
                             Map<String, Object> payload) {
        AgentProgressEvent event = AgentProgressEvent.of(
                eventType, String.valueOf(state.getUserId()), "running", message);
        event.setTaskId(state.getTaskId());
        event.setPayload(payload);
        progressBroadcaster.publish(event);
    }

    private Checkpoint terminal(Checkpoint checkpoint,
                                AgentState state,
                                AgentState.TaskStatus status,
                                String stoppedReason) {
        state.finish(status, stoppedReason, state.getAnswer());
        return commit(checkpoint, state);
    }

    private Checkpoint commit(Checkpoint previous, AgentState state) {
        Checkpoint next = previous.next(
                state, System.currentTimeMillis(), checkpointStore.leaseDeadline());
        if (!checkpointStore.save(next)) {
            throw new LeaseLostException(
                    "lease lost or task cancelled: " + state.getTaskId());
        }
        return next;
    }

    private String serialize(AgentToolResult result) {
        try {
            return objectMapper.writeValueAsString(result);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize tool result", e);
        }
    }

    /** Another writer owns this run now; this one stops without writing again. */
    private static final class LeaseLostException extends RuntimeException {
        private LeaseLostException(String message) {
            super(message);
        }
    }
}
