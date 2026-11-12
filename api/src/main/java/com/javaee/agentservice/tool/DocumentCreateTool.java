package com.javaee.agentservice.tool;

import com.javaee.agentservice.model.AgentState;
import com.javaee.agentservice.model.AgentToolResult;
import com.javaee.documentservice.dto.DocumentCreateDTO;
import com.javaee.documentservice.service.DocumentService;
import com.javaee.documentservice.vo.DocumentVO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 新建一篇业务文档。
 *
 * <p>产出的就是正式文档，不是附件：写完之后 document_read 读得回来，replace_text
 * 改得动，版本和协同编辑照常。以前这里存的是文件仓库里的一个 blob，
 * 界面上没有列它的地方，agent 自己的其他工具也一个都看不见它。</p>
 */
@Component
public class DocumentCreateTool implements AgentTool {

    @Autowired
    private DocumentService documentService;

    @Override
    public AgentToolDefinition definition() {
        return AgentToolDefinition.tool("document_create")
                .desc("新建一篇业务文档并写入正文，返回 documentId。"
                        + "要把生成的内容留下来就用它，之后可以用 document_read 读、"
                        + "replace_text 改。改已有文档不要用这个，那会多出一篇。")
                .required("title", Param.string("文档标题，必填"))
                .required("content", Param.string("文档正文，markdown，必填"))
                .optional("category", Param.string("分类").def("default"))
                .build();
    }

    @Override
    public AgentToolResult execute(String idempotencyKey,
                                   Map<String, Object> params,
                                   AgentState state) {
        DocumentCreateDTO dto = new DocumentCreateDTO();
        dto.setTitle(normalizeTitle(ToolArgs.require(params, "title")));
        dto.setContent(ToolArgs.require(params, "content"));
        dto.setCategory(ToolArgs.valueOrDefault(
                ToolArgs.asString(params.get("category")), "default"));

        String documentId = derivedDocumentId(idempotencyKey);
        try {
            DocumentVO created = documentService.create(dto, state.getUserId(), documentId);
            return result(created.getId(), dto.getTitle(), dto.getContent().length());
        } catch (DuplicateKeyException alreadyCreated) {
            // 同一次调用重放到了这里，文档上一次就建好了。
            return result(documentId, dto.getTitle(), dto.getContent().length());
        }
    }

    private AgentToolResult result(String documentId, String title, int contentLength) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("persisted", true);
        data.put("documentId", documentId);
        data.put("title", title);
        data.put("contentLength", contentLength);
        return AgentToolResult.success(
                "document_create", "文档已创建: " + title, data);
    }

    /** 标题进的是一行文本，换行和制表符会让列表和标签页显示错位。 */
    private String normalizeTitle(String title) {
        String normalized = title.replaceAll("[\\r\\n\\t]", " ").trim();
        return normalized.isBlank() ? "未命名文档" : normalized;
    }

    /**
     * 从调用身份推出文档ID，重放撞主键而不是多建一篇。没有键就交给服务端生成。
     */
    private String derivedDocumentId(String idempotencyKey) {
        if (ToolArgs.isBlank(idempotencyKey)) {
            return null;
        }
        return UUID.nameUUIDFromBytes(idempotencyKey.getBytes(StandardCharsets.UTF_8))
                .toString().replace("-", "");
    }
}
