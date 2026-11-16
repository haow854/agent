package com.javaee.agentservice.orchestration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.javaee.agentservice.event.AgentProgressBroadcaster;
import com.javaee.agentservice.event.AgentProgressEvent;
import com.javaee.agentservice.llm.LlmClient;
import com.javaee.agentservice.llm.ModelToolDefinition;
import com.javaee.agentservice.model.AgentState;
import com.javaee.agentservice.model.AgentToolResult;
import com.javaee.agentservice.tool.AgentToolRegistry;
import com.javaee.agentservice.prompt.PromptEngineeringService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Component
public class SubAgentLoop {

    private static final Logger log = LoggerFactory.getLogger(SubAgentLoop.class);

    private static final String DROPPED = "[已读取，要点见后续回复]";

    @Autowired
    private LlmClient modelClient;
    @Autowired
    private PromptEngineeringService prompts;
    @Autowired
    private AgentToolRegistry toolRegistry;
    @Autowired
    private AgentToolGateway toolGateway;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private AgentProgressBroadcaster progressBroadcaster;

    @Value("${ai.agent.subagent.max-rounds:8}")
    private int maxRounds = 8;

    public record Result(String answer, int rounds, int toolCallsUsed, boolean truncated) {
    }

    public Result run(AgentState parent,
                      String subRunId,
                      String instruction,
                      List<String> tools,
                      int toolCallBudget) {
        AgentState state = subState(parent, subRunId, instruction, tools, toolCallBudget);
        List<Message> messages = new ArrayList<>();
        messages.add(new SystemMessage(prompts.subagentSystemPrompt()));
        messages.add(new UserMessage(instruction));

        int toolCallsUsed = 0;
        boolean forced = false;
        String lastText = "";
        int latestBatchStart = messages.size();

        for (int round = 1; round <= maxRounds; round++) {
            boolean noMoreTools = round == maxRounds || toolCallsUsed >= toolCallBudget;
            forced |= noMoreTools;
            if (noMoreTools) {
                messages.add(new UserMessage(prompts.subagentBudgetSpent()));
            }
            dropConsumedToolResults(messages, latestBatchStart);

            LlmClient.ModelTurn turn = modelClient.complete(
                    state.getConfig().model(), messages,
                    noMoreTools ? List.of() : modelTools(tools), false);
            AssistantMessage assistant = turn.message();
            messages.add(assistant);
            publish(parent, subRunId, round, assistant);

            if (!text(assistant).isBlank()) {
                lastText = text(assistant);
            }
            if (!assistant.hasToolCalls()) {
                return new Result(lastText, round, toolCallsUsed, forced);
            }

            latestBatchStart = messages.size();
            for (AssistantMessage.ToolCall call : assistant.getToolCalls()) {
                AgentToolResult result = toolGateway.execute(call, state, null);
                toolCallsUsed++;
                messages.add(toolResponse(call, result));
                if (toolCallsUsed >= toolCallBudget) {
                    break;
                }
            }
        }

        log.warn("子检索未在 {} 轮内收敛: subRunId={}", maxRounds, subRunId);
        return new Result(lastText, maxRounds, toolCallsUsed, true);
    }

    private List<ModelToolDefinition> modelTools(List<String> tools) {
        return toolRegistry.modelTools(tools);
    }

    private AgentState subState(AgentState parent,
                                String subRunId,
                                String goal,
                                List<String> tools,
                                int toolCallBudget) {
        AgentState.RunConfig parentConfig = parent.getConfig();
        AgentState.RunConfig config = new AgentState.RunConfig(
                goal,
                parentConfig.model(),
                prompts.subagentSystemPrompt(),
                tools,
                parentConfig.documentId(),
                parentConfig.workspace(),
                "",
                "",
                maxRounds,
                Math.max(1, toolCallBudget));
        return AgentState.create(
                parent.getTaskId() + "#" + subRunId,
                parent.getSessionId(),
                parent.getUserId(),
                config,
                System.currentTimeMillis());
    }

    private void dropConsumedToolResults(List<Message> messages, int latestBatchStart) {
        for (int i = 0; i < latestBatchStart; i++) {
            if (messages.get(i) instanceof ToolResponseMessage consumed) {
                messages.set(i, dropped(consumed));
            }
        }
    }

    private ToolResponseMessage dropped(ToolResponseMessage consumed) {
        return new ToolResponseMessage(consumed.getResponses().stream()
                .map(response -> new ToolResponseMessage.ToolResponse(
                        response.id(), response.name(), DROPPED))
                .toList());
    }

    private ToolResponseMessage toolResponse(AssistantMessage.ToolCall call,
                                             AgentToolResult result) {
        return new ToolResponseMessage(List.of(new ToolResponseMessage.ToolResponse(
                call.id(), call.name(), serialize(result))));
    }

    private String serialize(AgentToolResult result) {
        try {
            return objectMapper.writeValueAsString(result);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize tool result", e);
        }
    }

    private String text(AssistantMessage assistant) {
        return assistant.getContent() == null ? "" : assistant.getContent();
    }

    private void publish(AgentState parent,
                         String subRunId,
                         int round,
                         AssistantMessage assistant) {
        AgentProgressEvent event = AgentProgressEvent.of("step_subagent",
                String.valueOf(parent.getUserId()), "running", "子检索进行中");
        event.setTaskId(parent.getTaskId());
        event.setPayload(Map.of(
                "subRunId", subRunId,
                "round", round,
                "text", text(assistant),
                "toolCalls", assistant.getToolCalls().stream()
                        .map(AssistantMessage.ToolCall::name).toList()));
        progressBroadcaster.publish(event);
    }
}
