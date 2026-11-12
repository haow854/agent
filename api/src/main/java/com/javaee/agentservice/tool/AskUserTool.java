package com.javaee.agentservice.tool;

import com.javaee.agentservice.model.AgentState;
import com.javaee.agentservice.model.AgentToolResult;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Stops the run to ask the user something.
 *
 * <p>Returns {@code actionRequired} rather than a result: the runner records the
 * call as pending, pauses, and writes the user's reply back as this call's tool
 * response when the task is continued.</p>
 */
@Component
public class AskUserTool implements AgentTool {

    @Override
    public AgentToolDefinition definition() {
        return AgentToolDefinition.tool("ask_user")
                .desc("当任务参数缺失或存在歧义时，向用户提出澄清问题。不要在已经掌握所需信息时使用。")
                .required("question", Param.string("需要向用户澄清的问题"))
                .optional("missingFields", Param.string("本次缺失的字段名，逗号分隔，可选"))
                .optional("options", Param.string("可供用户选择的候选项，分号分隔，可选"))
                .build();
    }

    @Override
    public AgentToolResult execute(String idempotencyKey,
                                   Map<String, Object> params,
                                   AgentState state) {
        String question = ToolArgs.require(params, "question");
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("question", question);
        String missing = ToolArgs.asString(params.get("missingFields"));
        if (!ToolArgs.isBlank(missing)) {
            data.put("missingFields", List.of(missing.split("[,，;；]")));
        }
        String options = ToolArgs.asString(params.get("options"));
        if (!ToolArgs.isBlank(options)) {
            data.put("options", List.of(options.split("[;；]")));
        }
        data.put("interactionType", "user_input");
        data.put("resumeMode", "continue_task");
        data.put("resumeEndpointTemplate", "/api/ai/agent/tasks/{taskId}/continue");
        return AgentToolResult.actionRequired("ask_user", question, data);
    }
}
