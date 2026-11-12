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

class DeleteTextToolTest {

    private final CollabClient collab = mock(CollabClient.class);
    private final DocumentService documentService = mock(DocumentService.class);
    private final DeleteTextTool tool = new DeleteTextTool();

    @BeforeEach
    void inject() {
        ReflectionTestUtils.setField(tool, "collab", collab);
        ReflectionTestUtils.setField(tool, "documentService", documentService);
    }

    /** 这是这套工具里唯一让内容消失的操作，审批闸门留给它。 */
    @Test
    void isTheOnlyDocumentToolTheServerStopsAndAsksAbout() {
        assertThat(tool.definition().isRequiresApproval()).isTrue();
    }

    @Test
    void reportsWhetherTheWholeParagraphWentWithIt() {
        when(collab.delete("doc-1", 7L, "- 华南区下滑 3%"))
                .thenReturn(Map.of("ok", true, "removed", true));

        AgentToolResult result = delete("- 华南区下滑 3%");

        assertThat(result.getStatus()).isEqualTo("success");
        assertThat(result.getData()).containsEntry("blockRemoved", true);
    }

    @Test
    void leavesTheParagraphWhenOnlyPartOfItGoes() {
        when(collab.delete("doc-1", 7L, "，主要来自新签约的三家客户"))
                .thenReturn(Map.of("ok", true, "blocks", 1));

        assertThat(delete("，主要来自新签约的三家客户").getData())
                .containsEntry("blockRemoved", false);
    }

    @Test
    void checksWriteAccessBeforeTouchingTheDocument() {
        doThrow(new BusinessException("无权修改该文档"))
                .when(documentService).assertCanWrite(eq("doc-1"), eq(7L));

        assertThatThrownBy(() -> delete("随便什么"))
                .isInstanceOf(BusinessException.class);
        verify(collab, never()).delete(anyString(), anyLong(), anyString());
    }

    /** 重放安全：已删掉的文本匹配不上，第二次直接被拒，不会连带删掉别的。 */
    @Test
    void refusesAReplayBecauseTheTextIsAlreadyGone() {
        when(collab.delete(any(), any(), any()))
                .thenReturn(Map.of("error", "找不到这段文本，文档可能已被修改，请重新读取"));

        assertThat(delete("- 华南区下滑 3%").getStatus()).isEqualTo("error");
    }

    private AgentToolResult delete(String oldText) {
        return tool.execute("task-1:call-a",
                Map.of("documentId", "doc-1", "old", oldText), state());
    }

    private AgentState state() {
        return AgentState.create("task-1", "session-1", 7L,
                new AgentState.RunConfig(
                        "删掉那一段", "test-model", "system prompt",
                        List.of("delete_text"), "doc-1", "all", "<workspace>", "", 6, 8),
                0L);
    }
}
