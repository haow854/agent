package com.javaee.agentservice.tool;

import com.javaee.agentservice.model.AgentState;
import com.javaee.agentservice.model.AgentToolResult;
import com.javaee.documentservice.service.DocumentService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class DocumentDeleteToolTest {

    private final DocumentService documentService = mock(DocumentService.class);
    private final DocumentDeleteTool tool = new DocumentDeleteTool();

    @BeforeEach
    void inject() {
        ReflectionTestUtils.setField(tool, "documentService", documentService);
    }

    /**
     * 以发起这次 run 的用户身份删，而不是当前线程认证成谁就是谁——两者一致，
     * 但只有前者能活着传到批量执行的工作线程上。
     */
    @Test
    void deletesTheDocumentAsTheUserWhoStartedTheRun() {
        AgentToolResult result = tool.execute(
                "task-1:call-a", Map.of("documentId", "doc-1"), state());

        verify(documentService).delete("doc-1", 7L);
        assertThat(result.getStatus()).isEqualTo("success");
        assertThat(result.getData()).containsEntry("documentId", "doc-1");
    }

    /** 重放落在这次调用要的那个状态上，所以跑两遍没关系。 */
    @Test
    void deletingTwiceLeavesTheSameStateAsDeletingOnce() {
        tool.execute("task-1:call-a", Map.of("documentId", "doc-1"), state());
        tool.execute("task-1:call-a", Map.of("documentId", "doc-1"), state());

        verify(documentService, times(2)).delete("doc-1", 7L);
    }

    /**
     * 审批签在参数上，所以一个没带文档ID的调用还能走到执行就是 bug，
     * 该当场报错而不是去删别的东西。
     */
    @Test
    void refusesACallThatNamesNoDocument() {
        assertThat(catchThrowable(() ->
                tool.execute("task-1:call-a", Map.of(), state())))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private AgentState state() {
        return AgentState.create("task-1", "session-1", 7L,
                new AgentState.RunConfig(
                        "删掉这篇", "test-model", "system prompt",
                        List.of("document_delete"), "doc-1", "all", "<workspace>", "", 6, 8),
                0L);
    }
}
