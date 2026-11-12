package com.javaee.agentservice.llm;

import com.javaee.agentservice.config.MultiModelConfig;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.ai.openai.metadata.OpenAiUsage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * One way to reach a chat model, shared by everything in this service that needs
 * one. Neither the agent loop nor the chat endpoints own it; both are callers.
 *
 * <p>Goes through Spring AI, and preserves typed assistant and tool-response
 * messages on the way.</p>
 */
@Component
public class LlmClient {

    @Autowired
    private MultiModelConfig multiModelConfig;

    @Value("${spring.ai.openai.api-key:}")
    private String defaultApiKey;

    @Value("${spring.ai.openai.base-url:}")
    private String defaultBaseUrl;

    private final Map<String, ConfiguredModel> models = new ConcurrentHashMap<>();

    /**
     * One model turn. Usage comes back with the message rather than through a
     * side channel, because what a run has spent is a limiter input and has to
     * be charged in the same step that produced it.
     */
    public record ModelTurn(AssistantMessage message, TokenUsage usage) {
    }

    public record ModelInfo(String code, String name, String provider, boolean available) {
    }

    public ModelTurn complete(String modelCode,
                              List<Message> messages,
                              List<ModelToolDefinition> tools,
                              boolean parallelToolCalls) {
        ChatResponse response =
                call(modelCode, messages, tools, "auto", parallelToolCalls);
        return new ModelTurn(response.getResult().getOutput(), usageOf(response));
    }

    /** Providers may omit usage; an absent count is zero, never an error. */
    private TokenUsage usageOf(ChatResponse response) {
        Usage usage = response.getMetadata() == null
                ? null : response.getMetadata().getUsage();
        if (usage == null) {
            return new TokenUsage(0L, 0L, 1);
        }
        return new TokenUsage(
                usage.getPromptTokens() == null ? 0L : usage.getPromptTokens(),
                cachedPromptTokens(usage),
                usage.getGenerationTokens() == null ? 0L : usage.getGenerationTokens(),
                1);
    }

    /**
     * How much of the prompt the provider served from its prefix cache.
     *
     * <p>Read off the provider's own type rather than the common interface,
     * which carries three counts and not this one. An endpoint that speaks the
     * OpenAI protocol without caching it reports nothing, and nothing is zero
     * rather than an error: the caller reads this as a ratio, and a provider
     * that caches nothing has truthfully cached nothing.</p>
     */
    private long cachedPromptTokens(Usage usage) {
        if (!(usage instanceof OpenAiUsage openAiUsage)) {
            return 0L;
        }
        Long cached = openAiUsage.getPromptTokensDetailsCachedTokens();
        return cached == null ? 0L : cached;
    }

    /** 一问一答，不给工具。null 或空的 modelCode 选默认模型。 */
    public String completeText(String modelCode, String prompt) {
        ChatResponse response = call(modelCode,
                List.of(new UserMessage(prompt == null ? "" : prompt)), null, null, null);
        String content = response.getResult().getOutput().getContent();
        return content == null ? "" : content;
    }

    public List<ModelInfo> availableModels() {
        List<ModelInfo> result = new ArrayList<>();
        for (ModelType type : ModelType.values()) {
            MultiModelConfig.ModelConfig config = multiModelConfig.getOpenai().get(type.getCode());
            // 没有配置项等于用不了：createModel 现在会直接拒绝这个 code。
            boolean enabled = config != null && config.isEnabled();
            result.add(new ModelInfo(type.getCode(), type.getName(), "openai", enabled));
        }
        return List.copyOf(result);
    }

    private ChatResponse call(String modelCode,
                              List<Message> messages,
                              List<ModelToolDefinition> tools,
                              String toolChoice,
                              Boolean parallelToolCalls) {
        ConfiguredModel configured = models.computeIfAbsent(normalizeModelCode(modelCode), this::createModel);

        OpenAiChatOptions.Builder options = OpenAiChatOptions.builder()
                .withModel(configured.apiModel())
                .withProxyToolCalls(true);
        if (tools != null && !tools.isEmpty()) {
            options.withTools(toSpringTools(tools))
                    .withToolChoice(isBlank(toolChoice) ? "auto" : toolChoice)
                    .withParallelToolCalls(parallelToolCalls);
        }

        ChatResponse response = configured.chatModel().call(new Prompt(messages, options.build()));
        if (response == null || response.getResult() == null || response.getResult().getOutput() == null) {
            throw new IllegalStateException("Model returned an empty response");
        }
        return response;
    }

    private ConfiguredModel createModel(String modelCode) {
        MultiModelConfig.ModelConfig config = multiModelConfig.getOpenai().get(modelCode);
        // 认不出就当场停。旧 checkpoint 里存着已经删掉的 code，落到兜底配置上就会
        // 带着一个不存在的模型名去请求，报错位置离原因很远。
        if (config == null) {
            throw new IllegalStateException("Unknown model: " + modelCode
                    + "; configured models are " + multiModelConfig.getOpenai().keySet());
        }
        if (!config.isEnabled()) {
            throw new IllegalStateException("Model is disabled: " + modelCode);
        }

        String apiKey = isBlank(config.getApiKey()) ? defaultApiKey : config.getApiKey();
        String baseUrl = isBlank(config.getBaseUrl()) ? defaultBaseUrl : config.getBaseUrl();
        if (isBlank(apiKey) || isBlank(baseUrl)) {
            throw new IllegalStateException("Missing API configuration for model: " + modelCode);
        }
        return new ConfiguredModel(new OpenAiChatModel(new OpenAiApi(baseUrl, apiKey)),
                isBlank(config.getModel()) ? modelCode : config.getModel());
    }

    private List<OpenAiApi.FunctionTool> toSpringTools(List<ModelToolDefinition> tools) {
        List<OpenAiApi.FunctionTool> result = new ArrayList<>();
        for (ModelToolDefinition tool : tools) {
            OpenAiApi.FunctionTool.Function function = new OpenAiApi.FunctionTool.Function(
                    tool.description(),
                    tool.name(),
                    tool.parameters()
            );
            result.add(new OpenAiApi.FunctionTool(function));
        }
        return result;
    }

    private String normalizeModelCode(String modelCode) {
        return isBlank(modelCode) ? ModelType.DEEPSEEK_V4_PRO.getCode() : modelCode;
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private record ConfiguredModel(OpenAiChatModel chatModel, String apiModel) {
    }
}
