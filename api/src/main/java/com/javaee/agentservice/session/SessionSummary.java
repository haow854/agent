package com.javaee.agentservice.session;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("agent_session_summary")
public class SessionSummary {

    @TableId(value = "session_id", type = IdType.INPUT)
    private String sessionId;

    private String summaryText;

    private Long coveredTo;

    private LocalDateTime updateTime;
}
