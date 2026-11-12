package com.javaee.agentservice.tool;

import com.javaee.agentservice.model.AgentState;
import com.javaee.agentservice.model.AgentToolResult;
import com.javaee.documentservice.dto.DocumentCreateDTO;
import com.javaee.documentservice.service.DocumentService;
import com.javaee.documentservice.vo.DocumentVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DocumentCreateToolTest {

    private final DocumentService documentService = mock(DocumentService.class);
    private final DocumentCreateTool tool = new DocumentCreateTool();

    @BeforeEach
    void inject() {
        ReflectionTestUtils.setField(tool, "documentService", documentService);
    }

    /**
     * 产出的是正式文档，所以给回去的是 documentId——document_read 认的就是它。
     * data 里没有 fileId：这条路上不存在文件仓库里的原件。
     */
    @Test
    void createsARealDocumentAndHandsBackItsId() {
        when(documentService.create(any(), anyLong(), anyString()))
                .thenAnswer(invocation -> vo(invocation.getArgument(2)));

        AgentToolResult result = create(Map.of(
                "title", "周报", "content", "# 本周\n\n做完了。"));

        assertThat(result.getStatus()).isEqualTo("success");
        assertThat(result.getData())
                .containsEntry("title", "周报")
                .containsEntry("persisted", true)
                .containsKey("documentId")
                .doesNotContainKey("fileId");

        ArgumentCaptor<DocumentCreateDTO> dto =
                ArgumentCaptor.forClass(DocumentCreateDTO.class);
        verify(documentService).create(dto.capture(), anyLong(), anyString());
        assertThat(dto.getValue().getContent()).isEqualTo("# 本周\n\n做完了。");
    }

    /** 同一次调用重来一遍要落回同一个ID，否则重投递就多出一篇文档。 */
    @Test
    void derivesTheSameDocumentIdFromTheSameCall() {
        when(documentService.create(any(), anyLong(), anyString()))
                .thenAnswer(invocation -> vo(invocation.getArgument(2)));

        Object first = tool.execute("task-1:call-a", params(), state()).getData().get("documentId");
        Object again = tool.execute("task-1:call-a", params(), state()).getData().get("documentId");
        Object other = tool.execute("task-1:call-b", params(), state()).getData().get("documentId");

        assertThat(first).isEqualTo(again).isNotEqualTo(other);
    }

    /** 撞主键说明上一次已经建好了，这是重放而不是失败。 */
    @Test
    void treatsADuplicateIdAsTheReplayItIs() {
        when(documentService.create(any(), anyLong(), anyString()))
                .thenThrow(new DuplicateKeyException("PRIMARY"));

        AgentToolResult result = create(params());

        assertThat(result.getStatus()).isEqualTo("success");
        assertThat(result.getData().get("documentId")).isNotNull();
    }

    /** 标题落在文档列表和标签页里，换行会把那一行显示撑坏。 */
    @Test
    void flattensTheTitleOntoOneLine() {
        when(documentService.create(any(), anyLong(), anyString()))
                .thenAnswer(invocation -> vo(invocation.getArgument(2)));

        AgentToolResult result = create(Map.of(
                "title", "第一行\n第二行", "content", "正文"));

        assertThat(result.getData()).containsEntry("title", "第一行 第二行");
    }

    private Map<String, Object> params() {
        return Map.of("title", "周报", "content", "正文");
    }

    private AgentToolResult create(Map<String, Object> params) {
        return tool.execute("task-1:call-a", params, state());
    }

    private DocumentVO vo(String id) {
        DocumentVO created = new DocumentVO();
        created.setId(id);
        return created;
    }

    private AgentState state() {
        return AgentState.create("task-1", "session-1", 7L,
                new AgentState.RunConfig(
                        "写份周报", "test-model", "system prompt",
                        List.of("document_create"), null, "all", "<workspace>", "", 6, 8),
                0L);
    }
}
