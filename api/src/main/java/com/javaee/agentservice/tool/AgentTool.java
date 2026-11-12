package com.javaee.agentservice.tool;

import com.javaee.agentservice.model.AgentState;
import com.javaee.agentservice.model.AgentToolResult;

import java.util.Map;

/**
 * One tool the Agent can call.
 *
 * <p>Every implementation is a Spring bean that {@link AgentToolRegistry}
 * discovers at startup, and it carries its own schema and its own policy flags.
 * Adding a tool is therefore a new class and nothing else.</p>
 */
public interface AgentTool {

    /** Schema and policy for this tool. Called per tool call, so keep it cheap. */
    AgentToolDefinition definition();

    /**
     * Runs one call. The gateway has already validated {@code params} against
     * {@link #definition()} and applied its defaults, so this sees values that
     * match the declared schema.
     *
     * @param idempotencyKey stable identity of this call, reused verbatim when a
     *                       recovered run replays it. A tool with side effects
     *                       derives its target identity from it so a replay
     *                       overwrites instead of creating a second artifact.
     */
    AgentToolResult execute(String idempotencyKey,
                            Map<String, Object> params,
                            AgentState state);
}
