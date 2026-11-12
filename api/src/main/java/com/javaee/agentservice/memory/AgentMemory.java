package com.javaee.agentservice.memory;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 一条跨会话的用户记忆。 */
@Data
@TableName("agent_memory")
public class AgentMemory {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    /** kebab-case slug，(userId, name) 唯一，写入按它覆盖 */
    private String name;

    private String type;

    private String content;

    /** 空=全局注入；有值=只在处理这篇文档的 run 里注入 */
    private String documentId;

    private String sourceTaskId;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
