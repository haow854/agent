package com.javaee.agentservice.tool;

import lombok.Getter;

import java.util.List;

/**
 * One tool parameter: what it is, and what the gateway enforces about it.
 *
 * <p>Declared at the tool that owns it, as
 * {@code Param.integer("检索条数").def(5).range(1, 50)}, because a type, a
 * default and a range are facts about one tool rather than about a parameter
 * name.</p>
 *
 * <p>Name and requiredness are not set here: they come from {@code required(..)}
 * or {@code optional(..)} at the call site, which reads better than a boolean
 * and cannot be forgotten. {@link AgentToolDefinition.Builder} stamps them into
 * a copy, so a declaration is safe to reuse.</p>
 */
@Getter
public final class Param {

    private String name;
    private final String type;
    private final String description;
    private boolean required;
    private Object defaultValue;
    private List<Object> allowedValues;
    private Number minValue;
    private Number maxValue;

    private Param(String type, String description) {
        this.type = type;
        this.description = description;
    }

    public static Param string(String description) {
        return new Param("string", description);
    }

    public static Param integer(String description) {
        return new Param("integer", description);
    }

    /** Filled in by the gateway before the tool runs, and shown to the model. */
    public Param def(Object defaultValue) {
        this.defaultValue = defaultValue;
        return this;
    }

    public Param oneOf(Object... values) {
        this.allowedValues = List.of(values);
        return this;
    }

    public Param range(Number min, Number max) {
        this.minValue = min;
        this.maxValue = max;
        return this;
    }

    /** This declaration bound to the name and requiredness it was added under. */
    Param named(String name, boolean required) {
        Param bound = new Param(type, description);
        bound.name = name;
        bound.required = required;
        bound.defaultValue = defaultValue;
        bound.allowedValues = allowedValues;
        bound.minValue = minValue;
        bound.maxValue = maxValue;
        return bound;
    }
}
