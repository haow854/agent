package com.javaee.documentservice.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 文档版本的元数据。正文存在文档所在的桶里，按版本 id 读写（DocumentContentService）。
 */
@Data
@TableName("document_version")
public class DocumentVersion {

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    private String documentId;

    private Integer versionNumber;

    private String title;

    private String summary;

    private String keywords;

    private String changeLog;

    private String createdBy;

    private LocalDateTime createTime;
}
