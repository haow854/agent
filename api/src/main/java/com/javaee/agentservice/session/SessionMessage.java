package com.javaee.agentservice.session;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
@TableName(value = "agent_session_message", autoResultMap = true)
public class SessionMessage {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String sessionId;

    private String taskId;

    private String role;

    private String content;

    private String toolCallId;

    private String toolName;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private List<ToolCall> toolCalls;

    private LocalDateTime createTime;
}
