package com.javaee.agentservice.tool;

import com.javaee.agentservice.model.AgentState;
import com.javaee.agentservice.model.AgentToolResult;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AgentToolRegistryTest {

    /**
     * Bean discovery order is not guaranteed, and the catalog order is the order
     * the model is offered its tools. Sorting makes it the same on every start.
     */
    @Test
    void ordersTheCatalogByNameWhateverOrderBeansArriveIn() {
        AgentToolRegistry registry = new AgentToolRegistry(
                List.of(stub("zulu"), stub("alpha"), stub("mike")));

        assertThat(registry.list()).extracting(AgentToolDefinition::getName)
                .containsExactly("alpha", "mike", "zulu");
    }

    @Test
    void resolvesBothTheDefinitionAndTheBeanThatRunsIt() {
        AgentTool alpha = stub("alpha");
        AgentToolRegistry registry = new AgentToolRegistry(List.of(alpha));

        assertThat(registry.get("alpha").getName()).isEqualTo("alpha");
        assertThat(registry.tool("alpha")).isSameAs(alpha);

        assertThat(registry.get("nope")).isNull();
        assertThat(registry.tool("nope")).isNull();
    }

    /**
     * Two beans claiming one name would leave which of them runs depending on
     * discovery order, so it is a startup failure rather than a coin toss.
     */
    @Test
    void aNameDeclaredTwiceFailsStartup() {
        assertThatThrownBy(() -> new AgentToolRegistry(List.of(stub("alpha"), stub("alpha"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("alpha");
    }

    private AgentTool stub(String name) {
        return new AgentTool() {
            @Override
            public AgentToolDefinition definition() {
                return AgentToolDefinition.tool(name).desc("test tool").build();
            }

            @Override
            public AgentToolResult execute(String idempotencyKey,
                                           Map<String, Object> params,
                                           AgentState state) {
                return AgentToolResult.success(name, "done", Map.of());
            }
        };
    }
}
