package com.javaee.agentservice.model;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.javaee.agentservice.llm.TokenUsage;
import lombok.Getter;

import java.util.ArrayList;
import java.util.List;

/**
 * The working state of one Agent run.
 *
 * <p>Mutable on purpose: a run advances this object many times, and a value type
 * would turn every advance into a full positional rebuild. Its frozen form is
 * {@link Checkpoint}, which copies this object rather than sharing it.</p>
 *
 * <p>Nothing here grows with the length of the run. The message chain lives in
 * the session history stream and is never copied in; what the run produced is
 * kept as references, not content. That bound is what makes it safe to rewrite
 * the whole snapshot on every step.</p>
 *
 * <p>Jackson reads and writes the fields directly, so no setter has to exist for
 * persistence alone. Every mutation goes through a method that leaves the state
 * machine consistent.</p>
 */
// @Getter rather than @Data: setters would let a caller move the state machine
// without going through the method that keeps it consistent, and generated
// equals would decide when two checkpoints are the same run.
@Getter
@JsonAutoDetect(
        fieldVisibility = JsonAutoDetect.Visibility.ANY,
        getterVisibility = JsonAutoDetect.Visibility.NONE,
        isGetterVisibility = JsonAutoDetect.Visibility.NONE,
        setterVisibility = JsonAutoDetect.Visibility.NONE)
public class AgentState {

    private String taskId;
    private String sessionId;
    private Long userId;

    private RunConfig config;

    private TaskStatus status;
    private Phase phase;
    private String stoppedReason;

    private Budget budget;

    private String answer;
    private List<Artifact> artifacts = new ArrayList<>();

    private List<Pending> pendingInteractions = new ArrayList<>();
    private String lastError;

    private long startedAt;
    private Long cancelledAt;

    /** For Jackson, which populates the fields directly. Application code goes
     * through {@link #create}. */
    public AgentState() {
    }

    public static AgentState create(String taskId,
                                    String sessionId,
                                    Long userId,
                                    RunConfig config,
                                    long startedAt) {
        AgentState state = new AgentState();
        state.taskId = taskId;
        state.sessionId = sessionId;
        state.userId = userId;
        state.config = config;
        state.status = TaskStatus.RUNNING;
        state.phase = Phase.MODEL_PENDING;
        state.budget = Budget.empty();
        state.startedAt = startedAt;
        return state;
    }

    /** Handed out as a copy, so a reader cannot append to the run's own list. */
    public List<Artifact> getArtifacts() {
        return List.copyOf(artifacts);
    }

    /**
     * Everything in the current batch that needs the user, collected so one
     * batch costs one interruption. Empty unless {@link Phase#PAUSED}.
     */
    public List<Pending> getPendingInteractions() {
        return List.copyOf(pendingInteractions);
    }

    public void enterPhase(Phase next) {
        if (next == Phase.PAUSED || next == Phase.FINISHED) {
            throw new IllegalArgumentException(
                    "use pause() or finish() to reach " + next);
        }
        requireTransition(next);
        status = TaskStatus.RUNNING;
        phase = next;
        pendingInteractions = new ArrayList<>();
        stoppedReason = null;
    }

    /**
     * Stops for the user, naming every call in the batch that needs one. The
     * pause is the only wait that can last hours, so a batch spends at most one.
     */
    public void pause(List<Pending> interactions) {
        if (interactions == null || interactions.isEmpty()) {
            throw new IllegalArgumentException(
                    "pause requires at least one pending interaction");
        }
        requireTransition(Phase.PAUSED);
        status = TaskStatus.WAITING_USER;
        phase = Phase.PAUSED;
        pendingInteractions = new ArrayList<>(interactions);
        stoppedReason = null;
    }

    /** Resumes a paused run; the caller has already consumed what unblocked it. */
    public void resumeFromPause(Phase next) {
        if (phase != Phase.PAUSED) {
            throw new IllegalStateException(
                    "task is not paused: " + taskId + " (" + phase + ")");
        }
        enterPhase(next);
    }

    public void finish(TaskStatus terminal, String stoppedReason, String answer) {
        if (terminal == null || !terminal.terminal()) {
            throw new IllegalArgumentException(
                    "not a terminal status: " + terminal);
        }
        requireTransition(Phase.FINISHED);
        status = terminal;
        phase = Phase.FINISHED;
        pendingInteractions = new ArrayList<>();
        this.stoppedReason = stoppedReason;
        if (answer != null && !answer.isBlank()) {
            this.answer = answer;
        }
        if (terminal == TaskStatus.CANCELLED && cancelledAt == null) {
            cancelledAt = System.currentTimeMillis();
        }
    }

    public void chargeModelTurn(TokenUsage turn) {
        budget = budget.withModelTurn(turn);
    }

    public void chargeToolCall() {
        budget = budget.withToolCall();
    }

    public void setAnswer(String answer) {
        this.answer = answer;
    }

    public void recordError(String lastError) {
        this.lastError = lastError;
    }

    /**
     * Adds a reference to something the run produced, ignoring one already
     * recorded. Resume replays tool calls, so an idempotent tool re-run yields
     * the same reference and must not be counted twice.
     */
    public void recordArtifact(Artifact artifact) {
        if (artifact == null) {
            return;
        }
        boolean known = artifacts.stream()
                .anyMatch(existing -> existing.ref().equals(artifact.ref()));
        if (!known) {
            artifacts.add(artifact);
        }
    }

    public boolean budgetExhausted() {
        return budget.exhausted(config);
    }

    /**
     * Checks the coupling between the two levels of the state machine. Status is
     * what a caller is told; phase is where a resume picks up. They describe the
     * same stop from different distances and may never disagree.
     */
    public void validate() {
        if (taskId == null || taskId.isBlank()) {
            throw new IllegalStateException("taskId must not be blank");
        }
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalStateException("sessionId must not be blank");
        }
        if (config == null || status == null || phase == null || budget == null) {
            throw new IllegalStateException("state is incomplete: " + taskId);
        }
        boolean consistent = switch (status) {
            case RUNNING -> phase == Phase.MODEL_PENDING || phase == Phase.TOOL_PENDING;
            case WAITING_USER -> phase == Phase.PAUSED;
            case SUCCEEDED, FAILED, CANCELLED -> phase == Phase.FINISHED;
        };
        if (!consistent) {
            throw new IllegalStateException(
                    "status " + status + " cannot hold phase " + phase
                            + " for task " + taskId);
        }
        if ((phase == Phase.PAUSED) == pendingInteractions.isEmpty()) {
            throw new IllegalStateException(
                    "pending interactions exist exactly while PAUSED, violated by task "
                            + taskId);
        }
    }

    public AgentState copy() {
        AgentState copy = new AgentState();
        copy.taskId = taskId;
        copy.sessionId = sessionId;
        copy.userId = userId;
        copy.config = config;
        copy.status = status;
        copy.phase = phase;
        copy.stoppedReason = stoppedReason;
        copy.budget = budget;
        copy.answer = answer;
        copy.artifacts = new ArrayList<>(artifacts);
        copy.pendingInteractions = new ArrayList<>(pendingInteractions);
        copy.lastError = lastError;
        copy.startedAt = startedAt;
        copy.cancelledAt = cancelledAt;
        return copy;
    }

    private void requireTransition(Phase next) {
        if (!phase.canTransitionTo(next)) {
            throw new IllegalStateException(
                    "illegal phase transition " + phase + " -> " + next
                            + " for task " + taskId);
        }
    }

    /** What a caller is told. Terminal values distinguish how the run ended. */
    public enum TaskStatus {
        RUNNING,
        WAITING_USER,
        SUCCEEDED,
        FAILED,
        CANCELLED;

        public boolean terminal() {
            return this == SUCCEEDED || this == FAILED || this == CANCELLED;
        }
    }

    /**
     * Where the loop stopped. Every value except {@link #PAUSED} is derivable
     * from the stream; the field exists so a resume need not scan to find out,
     * and so that pausing — which the stream cannot express — has a home.
     */
    public enum Phase {
        /** The chain is complete and the model is owed a turn. */
        MODEL_PENDING,
        /** The last assistant turn has tool calls still unanswered. */
        TOOL_PENDING,
        /** Stopped until a user approves or answers. */
        PAUSED,
        /** Terminal. Nothing may advance this run again. */
        FINISHED;

        public boolean canTransitionTo(Phase next) {
            return switch (this) {
                case MODEL_PENDING -> next == TOOL_PENDING
                        || next == PAUSED || next == FINISHED;
                // A batch of calls stays TOOL_PENDING until its last response
                // lands, then the model is owed a turn.
                case TOOL_PENDING -> next == TOOL_PENDING
                        || next == MODEL_PENDING
                        || next == PAUSED || next == FINISHED;
                case PAUSED -> next == MODEL_PENDING
                        || next == TOOL_PENDING || next == FINISHED;
                case FINISHED -> false;
            };
        }
    }

    /**
     * Run settings captured at start.
     *
     * <p>Resolved values only: a resume that re-resolved a default would run
     * under whatever the configuration says today, against a message chain
     * written under what it said then. A tool set that shrank in between would
     * leave the chain referencing calls the model can no longer be told
     * about.</p>
     *
     * <p>The limits live here rather than beside the counters they bound, so
     * that raising one cannot retroactively widen a run already in flight.</p>
     */
    public record RunConfig(
            String goal,
            String model,
            String systemPrompt,
            List<String> availableTools,
            /** The document this run works on, fixed even if the session moves on. */
            String documentId,
            String workspace,
            String workspaceBlock,
            String memoryBlock,
            int maxIterations,
            int maxToolCalls
    ) {
        public RunConfig {
            if (goal == null || goal.isBlank()) {
                throw new IllegalArgumentException("goal must not be blank");
            }
            if (model == null || model.isBlank()) {
                throw new IllegalArgumentException(
                        "model must be resolved before it is frozen");
            }
            if (systemPrompt == null || systemPrompt.isBlank()) {
                throw new IllegalArgumentException(
                        "systemPrompt must be resolved before it is frozen");
            }
            if (!"all".equals(workspace) && !"current".equals(workspace)) {
                throw new IllegalArgumentException(
                        "workspace must be all or current, was " + workspace);
            }
            if ("current".equals(workspace)
                    && (documentId == null || documentId.isBlank())) {
                throw new IllegalArgumentException(
                        "workspace current needs the document it is current on");
            }
            if (maxIterations <= 0) {
                throw new IllegalArgumentException("maxIterations must be positive");
            }
            if (maxToolCalls <= 0) {
                throw new IllegalArgumentException("maxToolCalls must be positive");
            }
            availableTools = availableTools == null
                    ? List.of() : List.copyOf(availableTools);
            workspaceBlock = workspaceBlock == null ? "" : workspaceBlock;
            memoryBlock = memoryBlock == null ? "" : memoryBlock;
        }
    }

    /**
     * What the run has consumed. Every field is a limiter input, which is why
     * token usage sits next to the call counters rather than in a reporting
     * field: a run that stops only on step count treats a six-turn
     * summarisation and a six-turn pass over a large document as equal.
     */
    public record Budget(int iterationsUsed, int toolCallsUsed, TokenUsage usage) {
        public Budget {
            if (iterationsUsed < 0 || toolCallsUsed < 0) {
                throw new IllegalArgumentException("counters must not be negative");
            }
            usage = usage == null ? TokenUsage.zero() : usage;
        }

        public static Budget empty() {
            return new Budget(0, 0, TokenUsage.zero());
        }

        public Budget withModelTurn(TokenUsage turn) {
            return new Budget(iterationsUsed + 1, toolCallsUsed, usage.plus(turn));
        }

        public Budget withToolCall() {
            return new Budget(iterationsUsed, toolCallsUsed + 1, usage);
        }

        public boolean exhausted(RunConfig config) {
            return iterationsUsed >= config.maxIterations()
                    || toolCallsUsed >= config.maxToolCalls();
        }
    }

    /**
     * A reference to something the run produced.
     *
     * <p>Accumulated here rather than read back from the tool results on the
     * chain because it has to survive compaction: once old results are folded
     * into a summary, the ids they carried must not go with them.</p>
     *
     * @param kind what sort of reference {@code ref} is, e.g. {@code documentId}
     * @param ref  the reference itself, never the content
     * @param name label to show a user
     */
    public record Artifact(String kind, String ref, String name) {
        public Artifact {
            if (kind == null || kind.isBlank()) {
                throw new IllegalArgumentException("artifact kind must not be blank");
            }
            if (ref == null || ref.isBlank()) {
                throw new IllegalArgumentException("artifact ref must not be blank");
            }
        }
    }

    /**
     * Why a paused run is waiting and what unblocks it.
     *
     * <p>Holds no question text and no parameters: both are in the assistant
     * tool call the run stopped on, which the stream already has. Only the facts
     * a resume cannot read back from there are kept.</p>
     */
    public record Pending(
            Kind kind,
            /** The call the run stopped on; what the user's answer unblocks. */
            String toolCallId,
            /**
             * Carried so approval can be verified without a stream read: the
             * fingerprint is taken over the tool name together with the
             * parameters.
             */
            String toolName,
            /** What the user's grant covers; compared against the call replayed. */
            String paramsFingerprint,
            /** Deadline for APPROVAL; zero when an answer never expires. */
            long expiresAt
    ) {
        public Pending {
            if (kind == null) {
                throw new IllegalArgumentException("kind must not be null");
            }
            if (toolCallId == null || toolCallId.isBlank()) {
                throw new IllegalArgumentException("toolCallId must not be blank");
            }
            if (kind == Kind.APPROVAL
                    && (paramsFingerprint == null || paramsFingerprint.isBlank())) {
                throw new IllegalArgumentException(
                        "APPROVAL requires a params fingerprint");
            }
        }

        public static Pending approval(String toolCallId,
                                       String toolName,
                                       String paramsFingerprint,
                                       long expiresAt) {
            return new Pending(Kind.APPROVAL, toolCallId, toolName,
                    paramsFingerprint, expiresAt);
        }

        public static Pending askUser(String toolCallId, String toolName) {
            return new Pending(Kind.ASK_USER, toolCallId, toolName, null, 0L);
        }

        public enum Kind {
            /** A destructive tool needs the user to confirm before it runs. */
            APPROVAL,
            /** The run asked the user something; the answer becomes a tool response. */
            ASK_USER
        }
    }
}
