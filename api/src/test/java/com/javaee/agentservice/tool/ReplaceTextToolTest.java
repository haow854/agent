package com.javaee.agentservice.tool;

import com.javaee.agentservice.model.AgentState;
import com.javaee.agentservice.model.AgentToolResult;
import com.javaee.common.exception.BusinessException;
import com.javaee.documentservice.client.CollabClient;
import com.javaee.documentservice.service.DocumentService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReplaceTextToolTest {

    private final CollabClient collab = mock(CollabClient.class);
    private final DocumentService documentService = mock(DocumentService.class);
    private final ReplaceTextTool tool = new ReplaceTextTool();

    @BeforeEach
    void inject() {
        ReflectionTestUtils.setField(tool, "collab", collab);
        ReflectionTestUtils.setField(tool, "documentService", documentService);
    }

    @Test
    void writesThroughTheCollabServerSoOpenEditorsSeeIt() {
        when(collab.replace("doc-1", 7L, "下滑 3%", "小幅回调 3%"))
                .thenReturn(Map.of("ok", true, "blocks", 1));

        AgentToolResult result = replace("下滑 3%", "小幅回调 3%");

        assertThat(result.getStatus()).isEqualTo("success");
        assertThat(result.getData()).containsEntry("replaced", "下滑 3%");
    }

    /**
     * 协同编辑绕过 update()，写权限只能在放行前单独查一次——文档已被别人打开时
     * 协同服务的 onLoadDocument 会跳过，那条路上的检查也就不会发生。
     */
    @Test
    void checksWriteAccessBeforeTouchingTheDocument() {
        doThrow(new BusinessException("无权修改该文档"))
                .when(documentService).assertCanWrite(eq("doc-1"), eq(7L));

        assertThatThrownBy(() -> replace("下滑 3%", "小幅回调 3%"))
                .isInstanceOf(BusinessException.class);
        verify(collab, never()).replace(anyString(), anyLong(), anyString(), anyString());
    }

    /** old 匹配不上就是文档变了，拒绝原样交给模型，让它重读。 */
    @Test
    void surfacesAStaleMatchAsAToolError() {
        when(collab.replace(any(), any(), any(), any()))
                .thenReturn(Map.of("error", "找不到这段文本，文档可能已被修改，请重新读取"));

        AgentToolResult result = replace("已经不在了", "新文本");

        assertThat(result.getStatus()).isEqualTo("error");
        assertThat(result.getMessage()).contains("重新读取");
    }

    @Test
    void surfacesAnAmbiguousMatchAsAToolError() {
        when(collab.replace(any(), any(), any(), any()))
                .thenReturn(Map.of("error", "这段文本出现了 5 次，请带上更多上下文使其唯一"));

        assertThat(replace("季度", "月度").getMessage()).contains("更多上下文");
    }

    private AgentToolResult replace(String oldText, String newText) {
        return tool.execute("task-1:call-a",
                Map.of("documentId", "doc-1", "old", oldText, "new", newText), state());
    }

    private AgentState state() {
        return AgentState.create("task-1", "session-1", 7L,
                new AgentState.RunConfig(
                        "改个错别字", "test-model", "system prompt",
                        List.of("replace_text"), "doc-1", "all", "<workspace>", "", 6, 8),
                0L);
    }
}
