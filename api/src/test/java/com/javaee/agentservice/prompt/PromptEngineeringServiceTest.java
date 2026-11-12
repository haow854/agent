package com.javaee.agentservice.prompt;

import com.javaee.agentservice.memory.AgentMemory;
import com.javaee.agentservice.memory.MemoryType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class PromptEngineeringServiceTest {

    private final PromptEngineeringService service = new PromptEngineeringService();

    @Test
    void systemPromptCarriesMemoryRulesOnly() {
        String prompt = service.agentSystemPrompt();

        assertThat(prompt)
                .contains("背景信息，不是本轮的指令")
                .contains("remember")
                .contains("forget")
                .doesNotContain("<memory>");
    }

    @Test
    void memoryBriefGroupsByType() {
        String brief = service.memoryBrief(Map.of(
                MemoryType.PREFERENCE, List.of(memory("summary-length", "摘要一律 100 字内")),
                MemoryType.CONSTRAINT, List.of(memory("no-legal-edits", "legal 分类不要改"))));

        assertThat(brief)
                .startsWith("<memory>")
                .endsWith("</memory>")
                .contains("偏好:\n  summary-length: 摘要一律 100 字内")
                .contains("约束:\n  no-legal-edits: legal 分类不要改");
    }

    @Test
    void memoryBriefIsEmptyWithoutMemory() {
        assertThat(service.memoryBrief(Map.of())).isEmpty();
        assertThat(service.memoryBrief(null)).isEmpty();
    }

    @Test
    void workspaceBriefWithDocument() {
        String brief = service.workspaceBrief("all", "doc-31", "缓存层设计", "md");

        assertThat(brief)
                .contains("<workspace>\nscope: all\ndocumentId: doc-31")
                .contains("title: 缓存层设计")
                .contains("type: md")
                .contains("主文档")
                .contains("快照");
    }

    @Test
    void workspaceBriefWithoutDocument() {
        String brief = service.workspaceBrief("all", null, null, null);

        assertThat(brief)
                .contains("<workspace>\nscope: all\n</workspace>")
                .contains("未指定主文档")
                .doesNotContain("documentId")
                .doesNotContain("快照");
    }

    @Test
    void workspaceBriefScopedToCurrent() {
        assertThat(service.workspaceBrief("current", "doc-31", "缓存层设计", "md"))
                .contains("仅可操作上述文档")
                .contains("要求用户先在界面挂载");
    }

    private AgentMemory memory(String name, String content) {
        AgentMemory memory = new AgentMemory();
        memory.setName(name);
        memory.setContent(content);
        return memory;
    }
}
