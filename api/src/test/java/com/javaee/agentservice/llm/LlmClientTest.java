package com.javaee.agentservice.llm;

import com.javaee.agentservice.config.MultiModelConfig;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.HashMap;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class LlmClientTest {

    @Test
    void modelListingKeepsAllKnownModelsAndEnabledState() {
        MultiModelConfig config = new MultiModelConfig();
        MultiModelConfig.ModelConfig disabled = new MultiModelConfig.ModelConfig();
        disabled.setEnabled(false);
        HashMap<String, MultiModelConfig.ModelConfig> models = new HashMap<>();
        models.put(ModelType.GPT_5_5.getCode(), disabled);
        config.setOpenai(models);

        LlmClient modelClient = new LlmClient();
        ReflectionTestUtils.setField(modelClient, "multiModelConfig", config);

        List<LlmClient.ModelInfo> availableModels = modelClient.availableModels();

        assertThat(availableModels).hasSize(ModelType.values().length);
        assertThat(availableModels)
                .filteredOn(model -> model.code().equals(ModelType.GPT_5_5.getCode()))
                .singleElement()
                .extracting(LlmClient.ModelInfo::available)
                .isEqualTo(false);
        // 没有配置项的也报不可用：createModel 会拒绝这个 code，说可用就是在骗调用方。
        assertThat(availableModels)
                .filteredOn(model -> model.code().equals(ModelType.DEEPSEEK_V4_PRO.getCode()))
                .singleElement()
                .extracting(LlmClient.ModelInfo::available)
                .isEqualTo(false);
        assertThat(availableModels).allMatch(model -> "openai".equals(model.provider()));
    }
}
