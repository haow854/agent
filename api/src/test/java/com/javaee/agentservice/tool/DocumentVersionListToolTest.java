package com.javaee.agentservice.tool;

import com.javaee.agentservice.model.AgentState;
import com.javaee.agentservice.model.AgentToolResult;
import com.javaee.documentservice.entity.DocumentVersion;
import com.javaee.documentservice.service.DocumentService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DocumentVersionListToolTest {

    private final DocumentService documentService = mock(DocumentService.class);
    private final DocumentVersionListTool tool = new DocumentVersionListTool();

    @BeforeEach
    void inject() {
        ReflectionTestUtils.setField(tool, "documentService", documentService);
    }

    /**
     * Every version holds a whole copy of the document. A listing that returned
     * them verbatim would put one document per version onto the message chain,
     * which the current run cannot trim — and the model picks a version number,
     * for which a line of preview is enough.
     */
    @Test
    @SuppressWarnings("unchecked")
    void describesEachVersionWithoutPuttingItsWholeBodyOnTheChain() {
        when(documentService.getVersions(eq("doc-1"), eq(7L)))
                .thenReturn(List.of(version(1, "第一稿", "x".repeat(5000))));

        AgentToolResult result = tool.execute("task-1:call-a",
                Map.of("documentId", "doc-1"), state());

        List<Map<String, Object>> versions =
                (List<Map<String, Object>>) result.getData().get("versions");
        assertThat(versions).singleElement().satisfies(described -> {
            assertThat(described).doesNotContainKey("content");
            assertThat(described).containsEntry("versionNumber", 1);
            assertThat(described).containsEntry("changeLog", "第一稿");
            assertThat(described).containsEntry("contentLength", 5000);
            assertThat((String) described.get("contentPreview")).hasSizeLessThan(200);
        });
    }

    /** Identity comes off the run, so this works on any thread. */
    @Test
    void readsTheVersionsAsTheUserWhoStartedTheRun() {
        when(documentService.getVersions(eq("doc-1"), eq(7L))).thenReturn(List.of());

        AgentToolResult result = tool.execute("task-1:call-a",
                Map.of("documentId", "doc-1"), state());

        assertThat(result.getStatus()).isEqualTo("success");
        assertThat(result.getData()).containsEntry("versionCount", 0);
    }

    private DocumentVersion version(int number, String changeLog, String content) {
        DocumentVersion version = new DocumentVersion();
        version.setVersionNumber(number);
        version.setChangeLog(changeLog);
        version.setContent(content);
        version.setTitle("标题");
        return version;
    }

    private AgentState state() {
        return AgentState.create("task-1", "session-1", 7L,
                new AgentState.RunConfig(
                        "回到上一版", "test-model", "system prompt",
                        List.of("document_version_list"), "doc-1", "all", "<workspace>", "", 6, 8),
                0L);
    }
}
