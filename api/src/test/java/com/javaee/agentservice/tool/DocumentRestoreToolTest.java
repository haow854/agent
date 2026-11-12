package com.javaee.agentservice.tool;

import com.javaee.agentservice.model.AgentState;
import com.javaee.agentservice.model.AgentToolResult;
import com.javaee.documentservice.service.DocumentService;
import com.javaee.documentservice.vo.DocumentVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DocumentRestoreToolTest {

    private final DocumentService documentService = mock(DocumentService.class);
    private final DocumentRestoreTool tool = new DocumentRestoreTool();

    @BeforeEach
    void inject() {
        ReflectionTestUtils.setField(tool, "documentService", documentService);
    }

    @Test
    void restoresTheVersionTheModelNamedAsTheUserWhoStartedTheRun() {
        when(documentService.restoreVersion(eq("doc-1"), eq(3), eq(7L)))
                .thenReturn(document("第三稿正文"));

        AgentToolResult result = tool.execute(
                "task-1:call-a", Map.of("documentId", "doc-1", "versionNumber", 3), state());

        assertThat(result.getStatus()).isEqualTo("success");
        assertThat(result.getData())
                .containsEntry("restoredFrom", 3)
                .containsEntry("contentLength", "第三稿正文".length());
    }

    /**
     * The target version decides the result, so a replayed call lands on the text
     * it landed on the first time. This is what lets the gateway retry it: the
     * run recovers to the state the call asked for, not to one more of something.
     *
     * <p>What it does not undo is the history: each restore saves the current
     * text as a new version first, so running twice leaves two entries. The
     * content converges; the version list grows.</p>
     */
    @Test
    void restoringTwiceLandsOnTheSameTextAsRestoringOnce() {
        when(documentService.restoreVersion(eq("doc-1"), eq(3), eq(7L)))
                .thenReturn(document("第三稿正文"));

        Map<String, Object> params = Map.of("documentId", "doc-1", "versionNumber", 3);
        AgentToolResult first = tool.execute("task-1:call-a", params, state());
        AgentToolResult second = tool.execute("task-1:call-a", params, state());

        assertThat(second.getData()).isEqualTo(first.getData());
        verify(documentService, times(2)).restoreVersion("doc-1", 3, 7L);
    }

    /** Approval is signed over the parameters, so arriving without one is a bug. */
    @Test
    void refusesACallThatNamesNoDocument() {
        assertThat(org.assertj.core.api.Assertions.catchThrowable(() ->
                tool.execute("task-1:call-a", Map.of("versionNumber", 3), state())))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private DocumentVO document(String content) {
        DocumentVO vo = new DocumentVO();
        vo.setId("doc-1");
        vo.setTitle("标题");
        vo.setContent(content);
        vo.setVersion(4);
        return vo;
    }

    private AgentState state() {
        return AgentState.create("task-1", "session-1", 7L,
                new AgentState.RunConfig(
                        "回到第三版", "test-model", "system prompt",
                        List.of("document_restore"), "doc-1", "all", "<workspace>", "", 6, 8),
                0L);
    }
}
