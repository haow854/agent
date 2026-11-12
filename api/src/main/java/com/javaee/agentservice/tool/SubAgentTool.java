package com.javaee.agentservice.tool;

import com.javaee.agentservice.model.AgentState;
import com.javaee.agentservice.model.AgentToolResult;
import com.javaee.agentservice.orchestration.SubAgentLoop;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Semaphore;

@Component
public class SubAgentTool implements AgentTool {

    private static final List<String> SUB_TOOLS =
            List.of("document_list", "document_read", "web_fetch");

    @Autowired
    private ObjectProvider<SubAgentLoop> subAgentLoop;

    @Value("${ai.agent.subagent.max-concurrent:3}")
    private int maxConcurrent = 3;

    @Value("${ai.agent.subagent.max-tool-calls:15}")
    private int maxToolCalls = 15;

    private Semaphore slots;

    @PostConstruct
    void initialize() {
        slots = new Semaphore(Math.max(1, maxConcurrent));
    }

    @Override
    public AgentToolDefinition definition() {
        return AgentToolDefinition.tool("subagent")
                .desc("将一项子任务委派给独立运行的 agent 执行。"
                        + "该 agent 拥有独立上下文，其执行过程不进入本次对话，仅返回结论与依据。"
                        + "可在同一轮中发起多个，彼此并行执行，同时最多 " + maxConcurrent + " 个；"
                        + "任务可按文档或按子问题拆分后分别委派。"
                        + "待处理的内容越多，委派的收益越高。"
                        + "该 agent 可列出并读取当前用户的业务文档和公开网页，无法修改任何内容。"
                        + "task 需完整描述任务目标、判断标准，以及结论中必须包含的内容。")
                .required("task", Param.string(
                        "子任务的完整描述：目标、判断标准、结论要求"))
                .optional("documentIds", Param.string(
                        "限定其可读取的文档范围，业务文档ID以英文逗号分隔；"
                                + "缺省时由该 agent 自行检索需要读取的文档"))
                .build();
    }

    @Override
    public AgentToolResult execute(String idempotencyKey,
                                   Map<String, Object> params,
                                   AgentState state) {
        String task = ToolArgs.require(params, "task");
        List<String> documentIds = documentIds(params, state);
        if (!slots.tryAcquire()) {
            return AgentToolResult.error("subagent",
                    "同时进行的子任务过多，请改成一次交一件");
        }
        try {
            SubAgentLoop.Result result = subAgentLoop.getObject().run(
                    state,
                    UUID.randomUUID().toString(),
                    instruction(task, documentIds),
                    SUB_TOOLS,
                    budget(state));

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("task", task);
            data.put("documentIds", documentIds);
            data.put("result", result.answer());
            data.put("rounds", result.rounds());
            data.put("toolCallsUsed", result.toolCallsUsed());
            if (result.truncated()) {
                data.put("budgetExhausted", true);
            }
            return AgentToolResult.success("subagent",
                    result.answer().isBlank() ? "子任务未得出结论" : "子任务完成", data);
        } finally {
            slots.release();
        }
    }

    private List<String> documentIds(Map<String, Object> params, AgentState state) {
        String raw = ToolArgs.asString(params.get("documentIds"));
        if (!ToolArgs.isBlank(raw)) {
            return Arrays.stream(raw.split(","))
                    .map(String::trim)
                    .filter(id -> !id.isEmpty())
                    .distinct()
                    .toList();
        }
        String current = state.getConfig().documentId();
        return ToolArgs.isBlank(current) ? List.of() : List.of(current);
    }

    private int budget(AgentState state) {
        int remaining = state.getConfig().maxToolCalls()
                - state.getBudget().toolCallsUsed();
        return Math.max(1, Math.min(maxToolCalls, remaining / 2));
    }

    private String instruction(String task, List<String> documentIds) {
        return documentIds.isEmpty()
                ? task + "\n先用 document_list 找出相关文档，再读它们。"
                : task + "\n可读的文档ID：" + String.join("、", documentIds);
    }
}
