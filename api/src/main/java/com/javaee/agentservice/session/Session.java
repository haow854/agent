package com.javaee.agentservice.session;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("agent_session")
public class Session {

    @TableId(type = IdType.INPUT)
    private String id;

    private Long userId;

    private String documentId;

    private String title;

    private String lastTaskId;

    private Long lastMessageId;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
