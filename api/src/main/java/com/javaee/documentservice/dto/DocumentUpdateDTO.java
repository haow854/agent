package com.javaee.documentservice.dto;

import lombok.Data;

import java.util.List;

/**
 * 文档更新DTO
 */
@Data
public class DocumentUpdateDTO {

    private String id;

    private String title;

    private String content;

    private String category;

    private List<String> tags;

    private String changeLog;

    /**
     * 协同编辑的自动回存，不建历史版本。
     *
     * <p>协同服务停止编辑 2 秒后就回存一次，每次都建版本的话版本表会被
     * 打字速度撑爆。手动保存仍然建版本。</p>
     */
    private boolean autosave;
}
