package com.javaee.agentservice.controller;

import com.javaee.agentservice.async.AsyncAIJobService;
import com.javaee.agentservice.async.AsyncAIJobVO;
import com.javaee.agentservice.model.AgentExecutionRequest;
import com.javaee.common.security.RequestUserContext;
import com.javaee.common.model.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Asynchronous AI endpoints backed by RabbitMQ.
 */
@RestController
@RequestMapping("/api/ai/async")
@Tag(name = "异步AI处理", description = "通过RabbitMQ异步执行AI模型请求")
public class AsyncAIController {

    @Autowired
    private AsyncAIJobService asyncAIJobService;

    @Autowired
    private RequestUserContext requestUserContext;

    @PostMapping("/agent/execute")
    @Operation(summary = "异步Agent任务", description = "提交统一Agent任务到RabbitMQ，立即返回jobId")
    public Result<AsyncAIJobVO> submitAgent(@RequestBody AgentExecutionRequest request) {
        Long userId = requestUserContext.getRequiredUserId();
        request.setUserId(userId);
        // 队列 at-least-once 重投递、HTTP 超时重试都靠这个键收敛成同一个任务，必填。
        if (request.getIdempotencyKey() == null || request.getIdempotencyKey().isBlank()) {
            throw new IllegalArgumentException("idempotencyKey must not be blank");
        }
        return Result.success(asyncAIJobService.submit("agent", request, request.getModel(), userId));
    }

    @PostMapping("/agent/approvals/confirm")
    @Operation(summary = "异步确认危险操作", description = "提交审批确认到RabbitMQ，立即返回jobId")
    public Result<AsyncAIJobVO> submitAgentApproval(@RequestBody AgentExecutionRequest request) {
        if (request.getTaskId() == null || request.getTaskId().isBlank()) {
            throw new IllegalArgumentException("taskId must not be blank");
        }
        if (request.getApprovedToolCallIds().isEmpty()) {
            throw new IllegalArgumentException("approvedToolCallIds must not be empty");
        }
        Long userId = requestUserContext.getRequiredUserId();
        request.setUserId(userId);
        return Result.success(asyncAIJobService.submit(
                "agent-approval", request, request.getModel(), userId));
    }

    @PostMapping("/agent/tasks/{taskId}/continue")
    @Operation(summary = "异步续接Agent任务", description = "提交续接到RabbitMQ，立即返回jobId")
    public Result<AsyncAIJobVO> submitAgentContinue(
            @Parameter(description = "任务ID") @PathVariable String taskId,
            @RequestBody AgentExecutionRequest request) {
        Long userId = requestUserContext.getRequiredUserId();
        request.setUserId(userId);
        request.setTaskId(taskId);
        return Result.success(asyncAIJobService.submit(
                "agent-continue", request, request.getModel(), userId));
    }

    @GetMapping("/jobs/{jobId}")
    @Operation(summary = "查询异步任务", description = "根据jobId查询任务状态和执行结果")
    public Result<AsyncAIJobVO> getJob(
            @Parameter(description = "异步任务ID") @PathVariable String jobId) {
        AsyncAIJobVO job = asyncAIJobService.getJob(
                jobId,
                requestUserContext.getRequiredUserId(),
                requestUserContext.isAdmin()
        );
        return Result.success(job);
    }
}
