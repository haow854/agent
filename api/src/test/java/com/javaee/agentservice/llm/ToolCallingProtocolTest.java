package com.javaee.agentservice.llm;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * How a Spring AI response becomes one model turn. The other half of the
 * protocol — how the catalog becomes tool schemas — needs the real tools, so it
 * lives in {@code AgentToolCatalogTest}.
 */
class ToolCallingProtocolTest {

    @Test
    void springAiResponseMapsToOneModelTurn() {
        LlmClient service = new LlmClient();
        AssistantMessage message = new AssistantMessage(
                "",
                Map.of(),
                List.of(new AssistantMessage.ToolCall(
                        "call-1",
                        "function",
                        "web_search",
                        "{\"query\":\"部署方式\",\"topK\":5}"
                ))
        );
        ChatResponse springResponse = new ChatResponse(List.of(new Generation(
                message,
                ChatGenerationMetadata.from("tool_calls", null)
        )));

        LlmClient.ModelTurn turn = new LlmClient.ModelTurn(
                springResponse.getResult().getOutput(),
                ReflectionTestUtils.invokeMethod(service, "usageOf", springResponse));

        assertThat(turn.message().getContent()).isEmpty();
        assertThat(turn.message().getToolCalls()).hasSize(1);
        assertThat(turn.message().getToolCalls().get(0).id()).isEqualTo("call-1");
        assertThat(turn.message().getToolCalls().get(0).name()).isEqualTo("web_search");
        assertThat(turn.message().getToolCalls().get(0).arguments())
                .contains("部署方式", "topK");
        assertThat(turn.usage().modelCalls()).isEqualTo(1);
    }
}
