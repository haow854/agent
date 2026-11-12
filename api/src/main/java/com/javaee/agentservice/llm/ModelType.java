package com.javaee.agentservice.llm;

public enum ModelType {
    DEEPSEEK_V4_PRO("deepseek-v4-pro", "DeepSeek V4 Pro"),
    GPT_5_5("gpt-5.5", "GPT-5.5");

    private final String code;
    private final String name;

    ModelType(String code, String name) {
        this.code = code;
        this.name = name;
    }

    public String getCode() {
        return code;
    }

    public String getName() {
        return name;
    }

    /** An unknown code falls back to the default. */
    public static ModelType fromCode(String code) {
        for (ModelType type : values()) {
            if (type.code.equalsIgnoreCase(code)) {
                return type;
            }
        }
        return DEEPSEEK_V4_PRO;
    }
}
