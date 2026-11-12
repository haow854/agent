package com.javaee.agentservice.tool;

import com.javaee.agentservice.model.AgentState;
import com.javaee.agentservice.model.AgentToolResult;
import com.javaee.documentservice.service.DocumentService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 永久删除一篇业务文档。
 *
 * <p>危险操作，所以走到这里时 run 已经为审批令牌停过一次。审批是 agent 自己的
 * ——签在工具名和规范化参数上——所以这里不需要再让文档服务跑一遍确认流程。</p>
 *
 * <p>直接调文档服务。以前中间隔着一层 AI 侧的删除服务，负责解析目标、查桶权限、
 * 走确认流程；等这个工具只针对文档之后，那几步全被跳过，那一层只是原样转发。</p>
 */
@Component
public class DocumentDeleteTool implements AgentTool {

    @Autowired
    private DocumentService documentService;

    @Override
    public AgentToolDefinition definition() {
        return AgentToolDefinition.tool("document_delete")
                .desc("永久删除指定业务文档及其文件。")
                .requiresApproval()
                .required("documentId", Param.string("业务文档ID，必填"))
                .build();
    }

    /**
     * 删掉一个已经不在的东西，落点还是这次调用要的那个状态，所以重放无害
     * ——这正是恢复出来的 run 敢再跑一次的原因。
     */
    @Override
    public AgentToolResult execute(String idempotencyKey,
                                   Map<String, Object> params,
                                   AgentState state) {
        String documentId = ToolArgs.require(params, "documentId");
        documentService.delete(documentId, state.getUserId());

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("documentId", documentId);
        data.put("status", "deleted");
        return AgentToolResult.success(
                "document_delete", "业务文档已永久删除: " + documentId, data);
    }
}
