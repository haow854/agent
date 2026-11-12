package com.javaee.agentservice.controller;

import com.javaee.agentservice.memory.AgentMemory;
import com.javaee.agentservice.memory.AgentMemoryStore;
import com.javaee.agentservice.model.AgentExecutionRequest;
import com.javaee.agentservice.model.AgentTaskResponse;
import com.javaee.agentservice.orchestration.AgentExecutionService;
import com.javaee.agentservice.tool.AgentToolDefinition;
import com.javaee.agentservice.session.SessionManager;
import com.javaee.common.security.RequestUserContext;
import com.javaee.common.model.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Agent控制器
 * 提供AI Agent相关的REST API接口
 */
@RestController
@RequestMapping("/api/ai/agent")
@Tag(name = "AI Agent", description = "AI Agent相关接口")
public class AgentController {

    @Autowired
    private AgentExecutionService agentExecutionService;

    @Autowired
    private SessionManager sessionManager;

    @Autowired
    private AgentMemoryStore memoryStore;

    @Autowired
    private RequestUserContext requestUserContext;

    /**
     * 统一Agent链路 - 获取工具列表
     */
    @GetMapping("/tools")
    @Operation(summary = "获取Agent工具列表", description = "获取统一Agent可调用的全部工具能力")
    public Result<List<AgentToolDefinition>> listTools() {
        return Result.success(agentExecutionService.listTools());
    }

    /**
     * 统一Agent链路 - 确认危险操作并继续执行
     */
    @PostMapping("/approvals/confirm")
    @Operation(summary = "确认危险操作", description = "携带 taskId 与 approvedToolCallIds 继续执行原挂起任务")
    public Result<AgentTaskResponse> confirmApproval(@RequestBody AgentExecutionRequest request) {
        request.setUserId(requestUserContext.getRequiredUserId());
        AgentTaskResponse result = agentExecutionService.confirmApproval(request);
        return Result.success(result);
    }

    /**
     * 统一Agent链路 - 取消挂起的危险操作
     */
    @PostMapping("/tasks/{taskId}/approvals/cancel")
    @Operation(summary = "取消危险操作", description = "用户拒绝危险操作时取消挂起的任务")
    public Result<Boolean> cancelApproval(@PathVariable String taskId) {
        return Result.success(agentExecutionService.cancelApproval(taskId, requestUserContext.getRequiredUserId()));
    }

    /**
     * 任务运行 - 查询单个任务快照。
     */
    @GetMapping("/tasks/{taskId}")
    @Operation(summary = "查询Agent任务快照", description = "返回指定 taskId 的任务执行计划、工具结果与时间线")
    public Result<AgentTaskResponse> getTask(@PathVariable String taskId) {
        return Result.success(agentExecutionService.getTask(taskId, true));
    }

    /**
     * 任务运行 - 列出当前用户最近的任务。
     */
    @GetMapping("/tasks")
    @Operation(summary = "列出Agent任务历史", description = "按当前用户列出最近的任务快照，管理员可查看全部")
    public Result<List<AgentTaskResponse>> listTasks(
            @Parameter(description = "管理员可指定 userId，留空表示当前用户") @RequestParam(required = false) Long userId) {
        if (requestUserContext.isAdmin() && userId == null) {
            return Result.success(agentExecutionService.listTasks(null));
        }
        Long target = requestUserContext.isAdmin() && userId != null
                ? userId : requestUserContext.getRequiredUserId();
        return Result.success(agentExecutionService.listTasks(target));
    }

    /**
     * 任务运行 - 取消挂起的 Agent 任务。
     */
    @PostMapping("/tasks/{taskId}/cancel")
    @Operation(summary = "取消Agent任务", description = "在主循环下一次迭代入口生效；终态任务不会被覆盖")
    public Result<Boolean> cancelTask(@PathVariable String taskId) {
        return Result.success(agentExecutionService.cancelTask(taskId));
    }

    // No recover endpoint. Recovering an abandoned task is not a decision a
    // user should have to make — nobody is running it — so it belongs to a
    // sweeper, and AgentExecutionService.recoverTask is waiting for one.
    // Exposed today it would mostly be fired at healthy runs: the only signal
    // it has is a lapsed lease, which a long run also has.

    /**
     * 任务运行 - 删除任务快照。
     */
    @DeleteMapping("/tasks/{taskId}")
    @Operation(summary = "删除Agent任务", description = "从注册表中彻底删除任务快照")
    public Result<Boolean> deleteTask(@PathVariable String taskId) {
        return Result.success(agentExecutionService.deleteTask(taskId));
    }

    /**
     * 任务运行 - 续接 action_required 任务。
     */
    @PostMapping("/tasks/{taskId}/continue")
    @Operation(summary = "续接Agent任务", description = "对处于 action_required 状态的任务补充信息后继续执行")
    public Result<AgentTaskResponse> continueTask(
            @PathVariable String taskId,
            @RequestBody AgentExecutionRequest request) {
        request.setTaskId(taskId);
        request.setUserId(requestUserContext.getRequiredUserId());
        return Result.success(agentExecutionService.execute(request));
    }

    /**
     * 对话Agent - 开始对话
     */
    @PostMapping("/session/start")
    @Operation(summary = "开始对话", description = "创建新的对话会话")
    public Result<String> startSession(
            @Parameter(description = "用户ID") @RequestParam(required = false, defaultValue = "default") String userId) {
        String sessionId = sessionManager.createSession(
                requestUserContext.getRequiredUserId(), null);
        return Result.success(sessionId);
    }

    /**
     * 对话Agent - 结束对话
     */
    @PostMapping("/session/{sessionId}/end")
    @Operation(summary = "结束对话", description = "结束指定对话")
    public Result<Boolean> endSession(
            @Parameter(description = "对话ID") @PathVariable String sessionId) {
        sessionManager.deleteSessionForUser(sessionId, requestUserContext.getRequiredUserId());
        return Result.success(true);
    }

    /**
     * 对话Agent - 获取对话历史
     */
    /**
     * 对话Agent - 列出当前用户的会话，最近使用的在前。
     */
    @GetMapping("/sessions")
    @Operation(summary = "列出会话", description = "按最近使用排序，含标题与最后一个任务ID")
    public Result<List<Map<String, Object>>> listSessions() {
        return Result.success(sessionManager.listUserSessions(
                requestUserContext.getRequiredUserId()));
    }

    @GetMapping("/session/{sessionId}/history")
    @Operation(summary = "获取对话历史", description = "获取对话的消息历史")
    public Result<List<Map<String, Object>>> getSessionHistory(
            @Parameter(description = "对话ID") @PathVariable String sessionId) {
        List<Map<String, Object>> history =
                sessionManager.getHistoryForUser(sessionId, requestUserContext.getRequiredUserId());
        return Result.success(history);
    }

    @GetMapping("/memory")
    @Operation(summary = "列出我的记忆", description = "Agent 跨会话记住的用户事实")
    public Result<List<AgentMemory>> listMemory() {
        return Result.success(
                memoryStore.listAll(requestUserContext.getRequiredUserId()));
    }

    @DeleteMapping("/memory/{name}")
    @Operation(summary = "删除一条记忆", description = "按 name 删除")
    public Result<Boolean> deleteMemory(
            @Parameter(description = "记忆的 name") @PathVariable String name) {
        return Result.success(
                memoryStore.forget(requestUserContext.getRequiredUserId(), name));
    }

    /**
     * Agent 工作台 - 聚合接口，返回工具/对话/任务等综合数据。
     */
    @GetMapping("/workbench/overview")
    @Operation(summary = "Agent工作台总览", description = "聚合工具列表、用户对话和最近Agent任务用于前端展示")
    public Result<Map<String, Object>> workbenchOverview() {
        Long userId = requestUserContext.getRequiredUserId();
        Map<String, Object> overview = new java.util.LinkedHashMap<>();
        overview.put("tools", agentExecutionService.listTools());
        overview.put("sessions", sessionManager.getUserSessions(userId));
        overview.put("tasks", agentExecutionService.listTasks(userId));
        overview.put("user", Map.of(
                "userId", userId,
                "role", requestUserContext.getCurrentRole()
        ));
        return Result.success(overview);
    }

}
