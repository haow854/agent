package com.javaee.documentservice.dto;

import lombok.Data;

import java.util.List;

/**
 * 文档创建DTO。
 *
 * <p>只服务"正文已经在手上"这条路，比如 Agent 写的。上传文件建文档走
 * {@code POST /api/documents/upload}，原件不经过这里。</p>
 */
@Data
public class DocumentCreateDTO {

    private String title;

    /** 正文，markdown。 */
    private String content;

    private String category;

    private List<String> tags;
}
