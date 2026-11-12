package com.javaee.agentservice.tool;

import com.javaee.agentservice.llm.ModelToolDefinition;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Central catalog for every tool the Agent planner is allowed to use, and the
 * one place that catalog is rendered into model-facing schemas.
 *
 * <p>Holds no knowledge of any particular tool: Spring hands it every
 * {@link AgentTool} bean and it indexes them. Adding a tool is a new class, and
 * this file never changes.</p>
 *
 * <p>Sorted by name so the catalog — and therefore the tool list the model is
 * given — is the same on every start. Bean discovery order is not.</p>
 */
@Component
public class AgentToolRegistry {

    private final Map<String, AgentToolDefinition> definitions = new LinkedHashMap<>();
    private final Map<String, AgentTool> tools = new LinkedHashMap<>();

    public AgentToolRegistry(List<AgentTool> discovered) {
        List<AgentTool> ordered = new ArrayList<>(discovered);
        ordered.sort(Comparator.comparing(tool -> tool.definition().getName()));
        for (AgentTool tool : ordered) {
            AgentToolDefinition definition = tool.definition();
            String name = definition.getName();
            if (definitions.put(name, definition) != null) {
                throw new IllegalStateException("duplicate agent tool: " + name);
            }
            tools.put(name, tool);
        }
    }

    public AgentToolDefinition get(String name) {
        return definitions.get(name);
    }

    public List<AgentToolDefinition> list() {
        return new ArrayList<>(definitions.values());
    }

    /** The bean that runs this tool, or null if no tool goes by that name. */
    public AgentTool tool(String name) {
        return tools.get(name);
    }

    public List<ModelToolDefinition> modelTools() {
        return modelTools(null);
    }

    /**
     * Builds the model-facing schemas for a named subset of the catalog.
     *
     * <p>A resumed run passes the set frozen when it started. Offering it
     * whatever the catalog holds today would let a tool disappear mid-run, and
     * the chain already references calls the model would then have no schema
     * for.</p>
     *
     * <p>Only what the model has to act on crosses over. Whether the server will
     * demand approval is not sent: it enforces that either way, so on the
     * model's side it is a paragraph of tokens per tool that can only invite
     * second-guessing.</p>
     *
     * @param names tools to expose, or null for the whole catalog
     */
    public List<ModelToolDefinition> modelTools(Collection<String> names) {
        List<ModelToolDefinition> modelTools = new ArrayList<>();
        for (AgentToolDefinition definition : definitions.values()) {
            if (names != null && !names.contains(definition.getName())) {
                continue;
            }
            modelTools.add(new ModelToolDefinition(
                    definition.getName(),
                    definition.getDescription(),
                    parametersSchema(definition),
                    null
            ));
        }
        return modelTools;
    }

    /** Names of every tool a run may be given, in catalog order. */
    public List<String> selectableToolNames() {
        return definitions.values().stream()
                .map(AgentToolDefinition::getName)
                .toList();
    }

    private Map<String, Object> parametersSchema(AgentToolDefinition definition) {
        Map<String, Object> properties = new LinkedHashMap<>();
        List<String> required = new ArrayList<>();

        for (Param parameter : definition.getParameterSchema().values()) {
            Map<String, Object> property = new LinkedHashMap<>();
            property.put("type", parameter.getType());
            property.put("description", parameter.getDescription());
            if (parameter.getAllowedValues() != null && !parameter.getAllowedValues().isEmpty()) {
                property.put("enum", parameter.getAllowedValues());
            }
            if (parameter.getDefaultValue() != null) {
                property.put("default", parameter.getDefaultValue());
            }
            if (parameter.getMinValue() != null) {
                property.put("minimum", parameter.getMinValue());
            }
            if (parameter.getMaxValue() != null) {
                property.put("maximum", parameter.getMaxValue());
            }
            properties.put(parameter.getName(), property);
            if (parameter.isRequired()) {
                required.add(parameter.getName());
            }
        }

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", required);
        schema.put("additionalProperties", false);
        return schema;
    }
}
