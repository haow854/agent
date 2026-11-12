package com.javaee.agentservice.memory;

public enum MemoryType {
    /** 该怎么做，含用户角色的推论 */
    PREFERENCE("偏好"),
    /** 不许做。裁记忆时永不裁这类：这个 agent 手里有销毁性工具 */
    CONSTRAINT("约束"),
    /** 在做什么、目标 */
    PROJECT("在做"),
    /** 指路：模板、文档、外部资源 */
    REFERENCE("指路");

    private final String label;

    MemoryType(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }

    public static MemoryType of(String value) {
        for (MemoryType type : values()) {
            if (type.name().equalsIgnoreCase(value)) {
                return type;
            }
        }
        throw new IllegalArgumentException("未知的记忆类型: " + value);
    }
}
