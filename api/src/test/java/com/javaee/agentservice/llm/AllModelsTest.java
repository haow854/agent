package com.javaee.agentservice.llm;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@SpringBootTest
@EnabledIfEnvironmentVariable(named = "RUN_LIVE_MODEL_TESTS", matches = "true")
public class AllModelsTest {

    private static final Logger log = LoggerFactory.getLogger(AllModelsTest.class);

    @Autowired
    private LlmClient modelClient;

    private static final String TEST_PROMPT = "请用100字以内介绍一下你自己";

    @Test
    public void testAllModels() {
        log.info("========================================");
        log.info("开始测试所有可用模型");
        log.info("========================================");

        Map<ModelType, String> results = new HashMap<>();
        List<ModelType> failedModels = new ArrayList<>();

        // 获取所有可用模型
        List<LlmClient.ModelInfo> services = modelClient.availableModels();

        for (LlmClient.ModelInfo service : services) {
            ModelType modelType = ModelType.fromCode(service.code());
            
            if (!service.available()) {
                log.warn("模型 {} 已禁用，跳过测试", modelType.getName());
                continue;
            }
            
            log.info("----------------------------------------");
            log.info("测试模型: {} ({})", modelType.getName(), modelType.getCode());
            
            try {
                String response = modelClient.completeText(modelType.getCode(), TEST_PROMPT);
                
                log.info("✓ 模型 {} 调用成功!", modelType.getName());
                log.info("响应: {}", response);
                
                results.put(modelType, response);
                
            } catch (Exception e) {
                log.error("✗ 模型 {} 调用失败!", modelType.getName(), e);
                failedModels.add(modelType);
            }
        }

        log.info("========================================");
        log.info("测试总结");
        log.info("========================================");
        log.info("成功模型: {}/{}", results.size(), services.size());
        
        if (!results.isEmpty()) {
            log.info("成功的模型:");
            results.keySet().forEach(model -> log.info("  - {} ({})", model.getName(), model.getCode()));
        }
        
        if (!failedModels.isEmpty()) {
            log.warn("失败的模型:");
            failedModels.forEach(model -> log.warn("  - {} ({})", model.getName(), model.getCode()));
        }
        
        log.info("========================================");
    }
}
