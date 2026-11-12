package com.javaee.agentservice.tool;

import com.javaee.agentservice.model.AgentState;
import com.javaee.agentservice.model.AgentToolResult;
import com.javaee.documentservice.client.CollabClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DocumentReadToolTest {

    private final CollabClient collab = mock(CollabClient.class);
    private final DocumentReadTool tool = new DocumentReadTool();

    @BeforeEach
    void inject() {
        ReflectionTestUtils.setField(tool, "collab", collab);
        ReflectionTestUtils.setField(tool, "maxChars", 100);
    }

    @Test
    void handsBackMarkdown() {
        stored("# 标题\n\n正文。");

        AgentToolResult result = read(Map.of("documentId", "doc-1"));

        assertThat(result.getData())
                .containsEntry("markdown", "# 标题\n\n正文。")
                .containsEntry("documentId", "doc-1")
                .containsEntry("truncated", false)
                .containsEntry("total", 9);
    }

    @Test
    void windowsByCharacterSoLaterPagesAreReachable() {
        stored("A".repeat(250));

        AgentToolResult first = read(Map.of("documentId", "doc-1"));
        assertThat(first.getData())
                .containsEntry("from", 1).containsEntry("to", 100)
                .containsEntry("total", 250).containsEntry("truncated", true);

        AgentToolResult second = read(Map.of("documentId", "doc-1", "offset", 101));
        assertThat(second.getData())
                .containsEntry("from", 101).containsEntry("to", 200)
                .containsEntry("truncated", true);
        assertThat(second.getData().get("markdown").toString()).hasSize(100);

        AgentToolResult last = read(Map.of("documentId", "doc-1", "offset", 201));
        assertThat(last.getData())
                .containsEntry("to", 250).containsEntry("truncated", false);
    }

    @Test
    void putsTheCursorAheadOfTheBody() {
        stored("A".repeat(250));

        assertThat(read(Map.of("documentId", "doc-1")).getData().keySet())
                .containsSubsequence("from", "to", "total", "truncated", "markdown");
    }

    @Test
    void capsTheWindowAtItsOwnLimit() {
        stored("A".repeat(250));

        assertThat(read(Map.of("documentId", "doc-1", "limit", 9999)).getData())
                .containsEntry("to", 100);
    }

    @Test
    void neverCutsASurrogatePairInHalf() {
        stored("A".repeat(99) + "𝑎" + "B".repeat(99));

        AgentToolResult result = read(Map.of("documentId", "doc-1"));

        assertThat(result.getData()).containsEntry("to", 99);
        assertThat(result.getData().get("markdown").toString()).doesNotContain("\ud835");
    }

    @Test
    void passesTheCollabServersRefusalStraightThrough() {
        when(collab.read(eq("doc-2"), eq(7L), anyInt(), anyInt()))
                .thenReturn(Map.of("error", "无权访问该文档，或文档不存在"));

        AgentToolResult result = read(Map.of("documentId", "doc-2"));

        assertThat(result.getStatus()).isEqualTo("error");
        assertThat(result.getMessage()).contains("无权访问");
    }

    private void stored(String markdown) {
        when(collab.read(eq("doc-1"), eq(7L), anyInt(), anyInt()))
                .thenReturn(Map.of("markdown", markdown, "title", "文档"));
    }

    private AgentToolResult read(Map<String, Object> params) {
        return tool.execute("task-1:call-a", params, state());
    }

    private AgentState state() {
        return AgentState.create("task-1", "session-1", 7L,
                new AgentState.RunConfig(
                        "读一下文档", "test-model", "system prompt",
                        List.of("document_read"), "doc-1", "all",
                        "<workspace>", "", 6, 8),
                0L);
    }
}
