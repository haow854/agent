package com.javaee.agentservice.tool;

import com.javaee.agentservice.memory.AgentMemory;
import com.javaee.agentservice.memory.AgentMemoryStore;
import com.javaee.agentservice.memory.MemoryType;
import com.javaee.agentservice.model.AgentState;
import com.javaee.agentservice.model.AgentToolResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 记住一条跨会话的用户事实。
 *
 * <p>不走审批：写记忆不销毁任何东西，工具日志里可见，写错了有 forget。</p>
 */
@Component
public class RememberTool implements AgentTool {

    @Autowired
    private AgentMemoryStore memoryStore;

    @Override
    public AgentToolDefinition definition() {
        return AgentToolDefinition.tool("remember")
                .desc("记住一条会改变以后行为的用户事实，跨会话有效。"
                        + "判据：删掉它下次行为会不会变？不会变就别记。"
                        + "不要记发生过什么、任务结果、文档正文——这些别处已有。"
                        + "name 用稳定的 kebab-case，同名会覆盖：改一条偏好请沿用原 name，不要新造。")
                .required("name", Param.string("kebab-case 标识，如 summary-length"))
                .required("type", Param.string("记忆类型")
                        .oneOf("preference", "constraint", "project", "reference"))
                .required("content", Param.string("一句祈使句，如“摘要一律控制在 100 字内”"))
                .optional("documentId", Param.string(
                        "只对某篇文档成立时填它；对所有文档都成立就留空"))
                .build();
    }

    @Override
    public AgentToolResult execute(String idempotencyKey,
                                   Map<String, Object> params,
                                   AgentState state) {
        AgentMemory memory = new AgentMemory();
        memory.setUserId(state.getUserId());
        memory.setName(ToolArgs.require(params, "name"));
        memory.setType(MemoryType.of(ToolArgs.require(params, "type")).name());
        memory.setContent(ToolArgs.require(params, "content"));
        memory.setDocumentId(ToolArgs.asString(params.get("documentId")));
        memory.setSourceTaskId(state.getTaskId());

        AgentMemory saved = memoryStore.remember(memory);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("name", saved.getName());
        data.put("type", saved.getType());
        data.put("content", saved.getContent());
        return AgentToolResult.success("remember", "已记住: " + saved.getName(), data);
    }
}
