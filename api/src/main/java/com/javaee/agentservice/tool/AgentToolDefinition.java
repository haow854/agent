package com.javaee.agentservice.tool;

import lombok.Getter;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * What one tool is: its schema, and the policy the server enforces around it.
 *
 * <p>Only the description and the parameter schema ever reach the model.
 * {@code requiresApproval} is the server's, and it is the only flag: it stops
 * the call before it happens so the user can confirm it. A flag belongs here
 * when the server has to branch <em>before</em> calling the tool and cannot
 * derive the answer — approval is the one thing that qualifies, because by the
 * time there is a result the document is already gone. A tool that merely needs
 * the user does not need a flag: it says so by returning
 * {@code actionRequired}.</p>
 *
 * <p>There is no retry-safety flag because every tool is retry-safe: one with a
 * side effect derives its outcome from the call's identity or from its
 * parameters, so a replay lands on the state the call asked for. A tool that
 * cannot make that promise needs the flag back.</p>
 *
 * <p>Built only through {@link #tool(String)}, at the tool that owns it, so that
 * a parameter's type, default and range are facts about one tool rather than
 * about every tool that happens to share the parameter's name.</p>
 */
@Getter
public class AgentToolDefinition {

    private final String name;
    private final String description;
    private final Map<String, Param> parameterSchema;
    private final boolean requiresApproval;

    private AgentToolDefinition(Builder builder) {
        this.name = builder.name;
        this.description = builder.description;
        this.parameterSchema = new LinkedHashMap<>(builder.parameterSchema);
        this.requiresApproval = builder.requiresApproval;
    }

    /** Declares a definition at the tool that owns it. */
    public static Builder tool(String name) {
        return new Builder(name);
    }

    /** Assembles a definition from the tool's own declaration. */
    public static final class Builder {

        private final String name;
        private final Map<String, Param> parameterSchema = new LinkedHashMap<>();
        private String description = "";
        private boolean requiresApproval;

        private Builder(String name) {
            this.name = name;
        }

        /** What the model is told this tool does. Nothing else reaches it. */
        public Builder desc(String description) {
            this.description = description;
            return this;
        }

        /**
         * The run stops for an approval token before this tool runs, and resumes
         * only once the user has granted one for these exact parameters.
         *
         * <p>Declared by the tool rather than looked up in a policy table so that
         * adding a tool is one edit. A table would leave a new tool unlisted, and
         * unlisted would have to mean allowed.</p>
         */
        public Builder requiresApproval() {
            this.requiresApproval = true;
            return this;
        }

        public Builder required(String parameterName, Param param) {
            return add(parameterName, param, true);
        }

        public Builder optional(String parameterName, Param param) {
            return add(parameterName, param, false);
        }

        public AgentToolDefinition build() {
            return new AgentToolDefinition(this);
        }

        private Builder add(String parameterName, Param param, boolean required) {
            if (parameterSchema.put(parameterName,
                    param.named(parameterName, required)) != null) {
                throw new IllegalStateException(
                        "duplicate parameter " + parameterName + " on tool " + name);
            }
            return this;
        }
    }
}
