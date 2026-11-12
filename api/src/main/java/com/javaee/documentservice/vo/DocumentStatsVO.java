package com.javaee.documentservice.vo;

import lombok.Data;

/** 文档正文的字符统计，标签已剥离。 */
@Data
public class DocumentStatsVO {
    private Integer totalCharacters;
    private Integer chineseCharacters;
    private Integer englishCharacters;
    private Integer digits;
    private Integer spaces;
    private Integer punctuations;
    private Integer lines;
}
