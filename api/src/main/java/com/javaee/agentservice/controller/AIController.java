package com.javaee.agentservice.controller;

import com.javaee.agentservice.llm.LlmClient;
import com.javaee.common.model.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * AI控制器
 * 提供AI处理和文件管理相关的REST API接口，支持多模型选择
 */
@RestController
@RequestMapping("/api/ai")
@Tag(name = "AI处理", description = "可用模型列表")
public class AIController {

    @Autowired
    private LlmClient modelClient;

    @GetMapping("/models")
    @Operation(summary = "获取可用模型列表", description = "获取所有可用的AI模型列表及其详细信息")
    public Result<java.util.List<Map<String, Object>>> getModels() {
        java.util.List<Map<String, Object>> models = modelClient.availableModels().stream()
                .map(info -> {
                    Map<String, Object> model = new HashMap<>();
                    model.put("code", info.code());
                    model.put("name", info.name());
                    model.put("provider", info.provider());
                    model.put("available", info.available());
                    return model;
                })
                .collect(Collectors.toList());
        return Result.success(models);
    }

}
