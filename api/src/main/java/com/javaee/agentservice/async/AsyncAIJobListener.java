package com.javaee.agentservice.async;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.javaee.agentservice.model.AgentExecutionRequest;
import com.javaee.agentservice.model.AgentTaskResponse;
import com.javaee.agentservice.orchestration.AgentExecutionService;
import com.javaee.agentservice.config.AiRabbitMQConfig;
import com.javaee.common.security.SecurityContexts;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Consumes queued model jobs and writes results back to Redis.
 */
@Component
public class AsyncAIJobListener {

    private static final Logger log = LoggerFactory.getLogger(AsyncAIJobListener.class);

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    private AsyncAIJobService asyncAIJobService;

    @Autowired
    private AgentExecutionService agentExecutionService;

    @RabbitListener(queues = AiRabbitMQConfig.AI_MODEL_REQUEST_QUEUE)
    public void handleModelJob(AsyncAIJobMessage message) {
        log.info("收到异步AI任务: jobId={}, type={}, model={}",
                message.getJobId(), message.getType(), message.getModel());

        asyncAIJobService.markRunning(message.getJobId());
        try {
            Object result = execute(message);
            // An Agent run reports failure by returning, not by throwing: the
            // runner catches, writes FAILED to the checkpoint and hands back a
            // response. Judging the job by whether an exception escaped would
            // mark every one of those a success.
            String failure = failureOf(result);
            if (failure != null) {
                log.warn("异步AI任务以失败状态结束: jobId={}, reason={}",
                        message.getJobId(), failure);
                asyncAIJobService.markFailed(message.getJobId(), failure);
                return;
            }
            asyncAIJobService.markSuccess(message.getJobId(), result);
            log.info("异步AI任务执行成功: jobId={}", message.getJobId());
        } catch (Exception e) {
            log.error("异步AI任务执行失败: jobId={}", message.getJobId(), e);
            asyncAIJobService.markFailed(message.getJobId(), e.getMessage());
        }
    }

    /** @return why the run failed, or null if it did not */
    private String failureOf(Object result) {
        if (!(result instanceof AgentTaskResponse response)
                || !"error".equals(response.getStatus())) {
            return null;
        }
        String reason = response.getStoppedReason();
        return reason == null || reason.isBlank() ? "run_failed" : reason;
    }

    private Object execute(AsyncAIJobMessage message) throws Exception {
        String type = message.getType();
        return switch (type) {
            // Both land on the same entry point. The type records which endpoint
            // the caller used, and continue guarantees the task id is set.
            case "agent", "agent-continue" -> SecurityContexts.callAs(
                    SecurityContexts.userContext(message.getUserId()),
                    () -> agentExecutionService.execute(requestOf(message)));
            case "agent-approval" -> SecurityContexts.callAs(
                    SecurityContexts.userContext(message.getUserId()),
                    () -> agentExecutionService.confirmApproval(requestOf(message)));
            default -> throw new IllegalArgumentException("不支持的异步AI任务类型: " + type);
        };
    }

    /** Payload read back as a request, carrying the job's own user and model. */
    private AgentExecutionRequest requestOf(AsyncAIJobMessage message) {
        AgentExecutionRequest request =
                objectMapper.convertValue(message.getPayload(), AgentExecutionRequest.class);
        request.setUserId(message.getUserId());
        if (message.getModel() != null && !message.getModel().isBlank()) {
            request.setModel(message.getModel());
        }
        return request;
    }

}
