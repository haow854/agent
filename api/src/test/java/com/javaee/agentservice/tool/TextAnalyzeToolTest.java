package com.javaee.agentservice.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.javaee.agentservice.model.AgentState;
import com.javaee.agentservice.model.AgentToolResult;
import com.javaee.documentservice.service.DocumentService;
import com.javaee.documentservice.vo.DocumentStatsVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TextAnalyzeToolTest {

    private final DocumentService documentService = mock(DocumentService.class);
    private final TextAnalyzeTool tool = new TextAnalyzeTool();

    @BeforeEach
    void inject() {
        ReflectionTestUtils.setField(tool, "documentService", documentService);
        ReflectionTestUtils.setField(tool, "objectMapper", new ObjectMapper());
    }

    /** 统计的是已保存的正文，且以发起 run 的用户身份去读。 */
    @Test
    void countsTheDocumentAsTheUserWhoStartedTheRun() {
        DocumentStatsVO counts = new DocumentStatsVO();
        counts.setTotalCharacters(5);
        counts.setLines(1);
        when(documentService.getStats(eq("doc-1"), eq(7L))).thenReturn(counts);

        AgentToolResult result = tool.execute(
                "task-1:call-1", Map.of("documentId", "doc-1"), state());

        assertThat(result.getStatus()).isEqualTo("success");
        assertThat(result.getToolName()).isEqualTo("text_analyze");
        assertThat(result.getData())
                .containsEntry("totalCharacters", 5)
                .containsEntry("lines", 1);
    }

    @Test
    void refusesACallWithoutDocumentId() {
        assertThatThrownBy(() -> tool.execute("task-1:call-1", new HashMap<>(), state()))
                .isInstanceOf(IllegalArgumentException.class);
        verify(documentService, never()).getStats(anyString(), anyLong());
    }

    @Test
    void declaresDocumentIdAsItsOnlyParameterAndCarriesNoRisk() {
        AgentToolDefinition definition = tool.definition();

        assertThat(definition.getName()).isEqualTo("text_analyze");
        assertThat(definition.getParameterSchema()).containsOnlyKeys("documentId");

        Param documentId = definition.getParameterSchema().get("documentId");
        assertThat(documentId.getType()).isEqualTo("string");
        assertThat(documentId.isRequired()).isTrue();

        assertThat(definition.isRequiresApproval()).isFalse();
    }

    private AgentState state() {
        return AgentState.create("task-1", "session-1", 7L,
                new AgentState.RunConfig("goal", "test-model", "system prompt",
                        List.of("text_analyze"), "doc-1", "all", "<workspace>", "", 6, 8),
                0L);
    }
}
