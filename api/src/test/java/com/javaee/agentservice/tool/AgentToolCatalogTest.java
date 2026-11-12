package com.javaee.agentservice.tool;

import com.javaee.agentservice.llm.ModelToolDefinition;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards the catalog against a tool going missing.
 *
 * <p>Every tool is a bean now, so a class that loses its {@code @Component} — or
 * never gets one — simply stops existing, with nothing in the registry left to
 * disagree with. This is the check that notices.</p>
 */
@SpringBootTest
class AgentToolCatalogTest {

    @Autowired
    private AgentToolRegistry registry;

    @Test
    void discoversEveryToolAndGivesEachOneABean() {
        assertThat(registry.list()).extracting(AgentToolDefinition::getName)
                .containsExactly(
                        "ask_user",
                        "delete_text",
                        "document_create",
                        "document_delete",
                        "document_list",
                        "document_read",
                        "document_restore",
                        "document_version_list",
                        "forget",
                        "remember",
                        "replace_text",
                        "subagent",
                        "text_analyze",
                        "web_fetch",
                        "web_search");

        assertThat(registry.list())
                .allSatisfy(definition ->
                        assertThat(registry.tool(definition.getName())).isNotNull());
    }

    /**
     * Pins the whole approval set rather than the two members it has today, so a
     * tool added without {@code requiresApproval()} fails here instead of
     * shipping. Declaring the flag on the tool keeps adding one to a single edit;
     * this is what stops that edit from silently being the wrong one.
     */
    @Test
    void namesEveryToolTheServerWillStopAndAskAbout() {
        assertThat(registry.list())
                .filteredOn(AgentToolDefinition::isRequiresApproval)
                .extracting(AgentToolDefinition::getName)
                .containsExactly("delete_text", "document_delete", "document_restore");
    }

    /**
     * A parameter's default and range are the owning tool's to state. They used
     * to be looked up by parameter name across the whole registry, so every
     * {@code limit} in the catalog had to mean the same thing.
     */
    @Test
    void takesParameterDefaultsFromTheToolThatDeclaresThem() {
        Param limit =
                registry.get("document_list").getParameterSchema().get("limit");

        assertThat(limit.getDefaultValue()).isEqualTo(20);
        assertThat(limit.getMinValue()).isEqualTo(1);
        assertThat(limit.getMaxValue()).isEqualTo(50);
        assertThat(limit.isRequired()).isFalse();
    }

    /**
     * A tool is named the same everywhere. There used to be an internal name
     * and a model-facing one, mapped back and forth on every call because the
     * catalog spelt its names with a character the function-name grammar
     * rejects.
     */
    @Test
    void offersEveryToolUnderTheNameTheCatalogGivesIt() {
        assertThat(registry.modelTools()).extracting(ModelToolDefinition::name)
                .containsExactlyElementsOf(
                        registry.list().stream().map(AgentToolDefinition::getName).toList());
    }

    /**
     * The model is told what a tool does, not how the server classifies it:
     * risk level and approval policy are enforced here whatever the model
     * believes about them.
     */
    @Test
    void keepsTheServersOwnClassificationOutOfWhatTheModelReads() {
        assertThat(registry.modelTools()).extracting(ModelToolDefinition::description)
                .allSatisfy(description -> assertThat(description)
                        .doesNotContain("风险等级")
                        .doesNotContain("危险操作"));
    }

    @SuppressWarnings("unchecked")
    @Test
    void mapsTheCatalogToToolSchemasTheModelCanBeGiven() {
        List<ModelToolDefinition> tools = registry.modelTools();

        assertThat(tools).extracting(ModelToolDefinition::name)
                .contains("replace_text", "document_create");

        ModelToolDefinition replaceText = tools.stream()
                .filter(tool -> "replace_text".equals(tool.name()))
                .findFirst()
                .orElseThrow();
        assertThat(replaceText.strict()).isNull();
        assertThat(replaceText.parameters()).containsEntry("type", "object")
                .containsEntry("additionalProperties", false);
        assertThat((List<String>) replaceText.parameters().get("required"))
                .contains("documentId", "old", "new");
        Map<String, Object> properties =
                (Map<String, Object>) replaceText.parameters().get("properties");
        assertThat(properties).containsKeys("documentId", "old", "new").doesNotContainKey("_plan");
    }
}
