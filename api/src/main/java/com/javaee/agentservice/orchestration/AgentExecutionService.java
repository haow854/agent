package com.javaee.agentservice.orchestration;

import com.javaee.agentservice.event.AgentProgressBroadcaster;
import com.javaee.agentservice.event.AgentProgressEvent;
import com.javaee.agentservice.model.AgentExecutionRequest;
import com.javaee.agentservice.model.AgentState;
import com.javaee.agentservice.model.AgentTaskResponse;
import com.javaee.agentservice.model.Checkpoint;
import com.javaee.agentservice.memory.AgentMemoryStore;
import com.javaee.agentservice.session.SessionManager;
import com.javaee.agentservice.task.CheckpointStore;
import com.javaee.agentservice.tool.AgentToolDefinition;
import com.javaee.agentservice.tool.AgentToolRegistry;
import com.javaee.agentservice.prompt.PromptEngineeringService;
import com.javaee.common.security.RequestUserContext;
import com.javaee.documentservice.service.DocumentService;
import com.javaee.documentservice.vo.DocumentVO;
import com.javaee.fileservice.entity.FileMetadata;
import com.javaee.fileservice.service.FileMetadataService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Entry point for running a task.
 *
 * <p>Starting and resuming differ only in where the run picks up. Both take the
 * task's lease first: it is what stops two writers from interleaving entries
 * into one session stream, which would rebuild into a chain with duplicated
 * turns. A run that cannot take the lease does not wait for it — another writer
 * holds the task, and the caller is told so.</p>
 */
@Service
public class AgentExecutionService {

    @Autowired
    private ReactRunner runner;
    @Autowired
    private AgentToolRegistry toolRegistry;
    @Autowired
    private PromptEngineeringService promptEngineeringService;
    @Autowired
    private CheckpointStore checkpointStore;
    @Autowired
    private SessionManager sessionManager;
    @Autowired
    private DocumentService documentService;
    @Autowired
    private FileMetadataService fileMetadataService;
    @Autowired
    private AgentMemoryStore memoryStore;
    @Autowired
    private RequestUserContext requestUserContext;
    @Autowired
    private AgentProgressBroadcaster progressBroadcaster;
    @Autowired
    private AgentTaskResponseAssembler responseAssembler;

    @Value("${ai.agent.default-model:deepseek-v4-pro}")
    private String defaultModel;

    @Value("${ai.agent.max-iterations:30}")
    private int maxIterations = 30;

    @Value("${ai.agent.max-tool-calls:100}")
    private int maxToolCalls = 100;

    public AgentTaskResponse execute(AgentExecutionRequest request) {
        Long userId = requestUserContext.getRequiredUserId();
        request.setUserId(userId);
        if (request.getTaskId() != null && !request.getTaskId().isBlank()) {
            return continueTask(request, userId);
        }
        if (request.getTask() == null || request.getTask().isBlank()) {
            throw new IllegalArgumentException("task must not be blank");
        }
        return start(request, userId);
    }

    public List<AgentToolDefinition> listTools() {
        return toolRegistry.list();
    }

    public AgentTaskResponse getTask(String taskId, boolean detailed) {
        Checkpoint checkpoint = checkpointStore.load(taskId);
        if (checkpoint == null) {
            return responseAssembler.status("not_found", taskId, null);
        }
        assertOwner(checkpoint, requestUserContext.getRequiredUserId());
        return detailed
                ? responseAssembler.toDetailedResponse(checkpoint)
                : responseAssembler.toResponse(checkpoint);
    }

    public List<AgentTaskResponse> listTasks(Long userId) {
        List<Checkpoint> checkpoints = userId == null
                ? checkpointStore.listAll()
                : checkpointStore.listByUser(userId);
        return checkpoints.stream().map(responseAssembler::toResponse).toList();
    }

    public boolean deleteTask(String taskId) {
        Checkpoint checkpoint = checkpointStore.load(taskId);
        if (checkpoint == null) {
            return false;
        }
        assertOwner(checkpoint, requestUserContext.getRequiredUserId());
        return checkpointStore.delete(taskId);
    }

    /** 批准就是续接的一种：用户回答了任务停下来的那几次调用之一。 */
    public AgentTaskResponse confirmApproval(AgentExecutionRequest request) {
        if (request.getTaskId() == null || request.getTaskId().isBlank()) {
            throw new IllegalArgumentException("taskId must not be blank");
        }
        return continueTask(request, requestUserContext.getRequiredUserId());
    }

    /**
     * Rejecting any one of a batch's approvals cancels the task: the model asked
     * for those calls as one move, and there is no partial version of it to run.
     */
    public boolean cancelApproval(String taskId, Long userId) {
        return cancel(taskId, userId, "approval_cancelled");
    }

    public boolean cancelTask(String taskId) {
        return cancel(taskId, requestUserContext.getRequiredUserId(), "cancelled");
    }

    /**
     * Takes over a task whose writer stopped without finishing it. The lease is
     * the liveness signal: if it has not lapsed, the run is still someone's.
     */
    public AgentTaskResponse recoverTask(String taskId) {
        Long userId = requestUserContext.getRequiredUserId();
        Checkpoint current = checkpointStore.load(taskId);
        if (current == null) {
            return responseAssembler.status("not_found", taskId, null);
        }
        assertOwner(current, userId);
        if (current.state().getStatus() != AgentState.TaskStatus.RUNNING) {
            AgentTaskResponse response =
                    responseAssembler.status("not_recoverable", taskId, null);
            response.setCurrentStatus(
                    current.state().getStatus().name().toLowerCase());
            return response;
        }
        long now = System.currentTimeMillis();
        if (!current.leaseExpired(now)) {
            AgentTaskResponse response =
                    responseAssembler.status("still_running", taskId, null);
            response.setRetryAfterMs(Math.max(0L, current.leaseExpiresAt() - now));
            return response;
        }
        return resume(taskId, userId, Map.of(), List.of(), "task_recovered");
    }

    private AgentTaskResponse start(AgentExecutionRequest request, Long userId) {
        String documentId = blankTo(request.getDocumentId(), null);
        String workspace = blankTo(request.getWorkspace(), "all");
        String sessionId = sessionManager.ensureSession(
                request.getSessionId(), userId, documentId, request.getTask());
        String taskId = taskIdFor(request.getIdempotencyKey());

        // The opening question has to be stored before the checkpoint claims
        // it: the checkpoint records a run over messages that already exist.
        sessionManager.appendUser(sessionId, taskId, request.getTask());

        AgentState.RunConfig config = new AgentState.RunConfig(
                request.getTask(),
                blankTo(request.getModel(), defaultModel),
                promptEngineeringService.agentSystemPrompt(),
                toolRegistry.selectableToolNames(),
                documentId,
                workspace,
                // 各读一次冻进配置：resume 的 run 用的还是当初那份，而且贴在当前
                // 问题上，不动前面已经缓存住的前缀
                workspaceBlock(userId, documentId, workspace),
                promptEngineeringService.memoryBrief(
                        memoryStore.forRun(userId, documentId)),
                maxIterations,
                maxToolCalls);

        long now = System.currentTimeMillis();
        AgentState state = AgentState.create(taskId, sessionId, userId, config, now);

        String leaseOwner = checkpointStore.newLeaseOwner();
        long leaseExpiresAt = checkpointStore.leaseDeadline();
        Checkpoint checkpoint =
                Checkpoint.initial(state, leaseOwner, leaseExpiresAt, now);
        if (!checkpointStore.create(checkpoint)) {
            return responseAssembler.status("duplicate", taskId,
                    "This submission already started a task; read it by its id");
        }

        publish("task_started", taskId, userId, "running", 0,
                "Agent task started", Map.of("task", request.getTask()));
        Checkpoint finished = runner.run(checkpoint, Map.of(), List.of());
        return finish(finished, userId);
    }

    private AgentTaskResponse continueTask(AgentExecutionRequest request, Long userId) {
        String taskId = request.getTaskId();
        Checkpoint current = checkpointStore.load(taskId);
        if (current == null) {
            throw new IllegalArgumentException(
                    "Continuation task does not exist: " + taskId);
        }
        assertOwner(current, userId);
        if (current.state().getStatus() != AgentState.TaskStatus.WAITING_USER) {
            throw new IllegalStateException(
                    "Task is not waiting for approval or user input: " + taskId);
        }
        return resume(taskId, userId, request.getUserInputs(),
                request.getApprovedToolCallIds(), "task_continued");
    }

    private AgentTaskResponse resume(String taskId,
                                     Long userId,
                                     Map<String, String> answers,
                                     List<String> approved,
                                     String eventType) {
        Checkpoint current = checkpointStore.load(taskId);
        if (current == null) {
            return responseAssembler.status("not_found", taskId, null);
        }
        assertOwner(current, userId);
        if (current.state().getPhase() == AgentState.Phase.FINISHED) {
            return responseAssembler.status("not_resumable", taskId,
                    "Task already finished: " + current.state().getStoppedReason());
        }

        sessionManager.ensureSession(current.state().getSessionId(), userId, null, null);
        String leaseOwner = checkpointStore.newLeaseOwner();
        Checkpoint leased = checkpointStore.acquireLease(taskId, leaseOwner);
        if (leased == null) {
            AgentTaskResponse response =
                    responseAssembler.status("still_running", taskId,
                            "Another worker holds this task");
            response.setRetryAfterMs(Math.max(0L,
                    current.leaseExpiresAt() - System.currentTimeMillis()));
            return response;
        }

        publish(eventType, taskId, userId, "running", 0,
                "Agent task resumed", Map.of("taskId", taskId));
        Checkpoint finished = runner.run(leased, answers, approved);
        return finish(finished, userId);
    }

    private boolean cancel(String taskId, Long userId, String reason) {
        Checkpoint current = checkpointStore.load(taskId);
        if (current == null) {
            return false;
        }
        assertOwner(current, userId);
        if (current.state().getPhase() == AgentState.Phase.FINISHED) {
            return false;
        }
        AgentState state = current.state().copy();
        state.finish(AgentState.TaskStatus.CANCELLED, reason, state.getAnswer());
        // Cancelling claims no time: it is not going to run anything.
        Checkpoint next = current.next(
                state, System.currentTimeMillis(), current.leaseExpiresAt());
        // Deliberately without the lease: cancellation comes from outside the
        // run, and the store refuses to move a cancelled task back to running.
        boolean cancelled = checkpointStore.saveWithoutLease(next);
        if (cancelled) {
            publish("task_cancelled", taskId, userId, "cancelled", 100,
                    "Agent task cancelled", Map.of("taskId", taskId));
        }
        return cancelled;
    }

    private AgentTaskResponse finish(Checkpoint checkpoint, Long userId) {
        AgentState state = checkpoint.state();
        AgentTaskResponse response = responseAssembler.toDetailedResponse(checkpoint);
        Map<String, Object> payload = responseAssembler.toPayload(response);
        if (state.getStatus() == AgentState.TaskStatus.WAITING_USER) {
            publish("task_waiting_user", state.getTaskId(), userId,
                    response.getStatus(), 95,
                    "Task is waiting for approval or user input", payload);
        } else {
            publish("task_finished", state.getTaskId(), userId,
                    response.getStatus(), 100,
                    "Agent task finished: " + state.getStoppedReason(), payload);
        }
        return response;
    }

    /**
     * The task id a submission claims, derived from its idempotency key so a
     * resubmission lands on the same task and the store refuses to create it
     * twice. The key is pinned at the enqueue boundary and is never blank here.
     *
     * <p>Derived rather than used directly, to keep the caller's key out of the
     * task id space: a task id is ours to hand out.</p>
     */
    private String taskIdFor(String idempotencyKey) {
        byte[] seed = ("agent-task:" + idempotencyKey).getBytes(StandardCharsets.UTF_8);
        return UUID.nameUUIDFromBytes(seed).toString();
    }

    private void publish(String eventType,
                         String taskId,
                         Long userId,
                         String status,
                         int progress,
                         String message,
                         Map<String, Object> payload) {
        AgentProgressEvent event =
                AgentProgressEvent.of(eventType, String.valueOf(userId), status, message);
        event.setTaskId(taskId);
        event.setProgress(progress);
        event.setPayload(payload);
        progressBroadcaster.publish(event);
    }

    private void assertOwner(Checkpoint checkpoint, Long userId) {
        if (!requestUserContext.isAdmin()
                && !userId.equals(checkpoint.state().getUserId())) {
            throw new SecurityException("No permission to access this Agent task");
        }
    }

    private String workspaceBlock(Long userId, String documentId, String workspace) {
        if (documentId == null) {
            return promptEngineeringService.workspaceBrief(workspace, null, null, null);
        }
        DocumentVO document = documentService.getById(documentId, userId);
        FileMetadata file = blankTo(document.getFileId(), null) == null
                ? null : fileMetadataService.getMetadata(document.getFileId());
        return promptEngineeringService.workspaceBrief(workspace, document.getId(),
                document.getTitle(), file == null ? null : file.getFileType());
    }

    private String blankTo(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
