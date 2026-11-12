package com.javaee.agentservice.tool;

import com.javaee.agentservice.memory.AgentMemoryStore;
import com.javaee.agentservice.model.AgentState;
import com.javaee.agentservice.model.AgentToolResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 删掉一条记忆。过时的记忆比没有记忆更糟——它会污染此后每一个会话。
 */
@Component
public class ForgetTool implements AgentTool {

    @Autowired
    private AgentMemoryStore memoryStore;

    @Override
    public AgentToolDefinition definition() {
        return AgentToolDefinition.tool("forget")
                .desc("删掉一条不再成立的记忆。记忆过时或被用户推翻时用它，"
                        + "name 取自系统提示里列出的记忆。")
                .required("name", Param.string("要删除的记忆的 name"))
                .build();
    }

    @Override
    public AgentToolResult execute(String idempotencyKey,
                                   Map<String, Object> params,
                                   AgentState state) {
        String name = ToolArgs.require(params, "name");
        boolean removed = memoryStore.forget(state.getUserId(), name);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("name", name);
        data.put("removed", removed);
        return AgentToolResult.success("forget",
                removed ? "已忘记: " + name : "没有这条记忆: " + name, data);
    }
}
