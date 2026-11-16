package com.javaee.documentservice.util;

import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DocumentParserUtilTest {

    @Test
    void htmlKeepsHeadingLevelsUpToSix() {
        String md = DocumentParserUtil.htmlToMarkdown("<h1>一</h1><h4>四</h4><h6>六</h6>");
        assertEquals("# 一\n\n#### 四\n\n###### 六", md);
    }

    @Test
    void htmlTableBecomesMarkdownTableWithoutDuplicatingCellParagraphs() {
        String md = DocumentParserUtil.htmlToMarkdown(
                "<p>前文</p><table><tr><th>名称</th><th>数量</th></tr>"
                        + "<tr><td><p>苹果</p></td><td>3</td></tr></table><p>后文</p>");
        assertEquals("前文\n\n| 名称 | 数量 |\n| --- | --- |\n| 苹果 | 3 |\n\n后文", md);
    }

    @Test
    void htmlNestedListsAreIndented() {
        String md = DocumentParserUtil.htmlToMarkdown(
                "<ul><li>水果<ul><li>苹果</li><li>香蕉</li></ul></li><li>蔬菜</li></ul>");
        assertEquals("- 水果\n  - 苹果\n  - 香蕉\n- 蔬菜", md);
    }

    @Test
    void htmlPreKeepsLinesAndBlankLines() {
        String md = DocumentParserUtil.htmlToMarkdown("<pre><code>a = 1\n\nb = 2\n</code></pre>");
        assertEquals("```\na = 1\n\nb = 2\n```", md);
    }

    @Test
    void htmlNestedInlineMarks() {
        assertEquals("***重点***", DocumentParserUtil.htmlToMarkdown("<p><b><i>重点</i></b></p>"));
    }

    @Test
    void markdownTableEscapesPipesAndPadsShortRows() {
        String md = DocumentParserUtil.markdownTable(List.of(
                List.of("名称", "说明"),
                List.of("a|b"),
                List.of("", "")));
        assertEquals("| 名称 | 说明 |\n| --- | --- |\n| a\\|b |  |", md);
    }

    @Test
    void docxTableAndCombinedRunStyles() throws Exception {
        byte[] bytes;
        try (XWPFDocument doc = new XWPFDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            XWPFRun run = doc.createParagraph().createRun();
            run.setText("重点");
            run.setBold(true);
            run.setItalic(true);

            XWPFTable table = doc.createTable(2, 2);
            table.getRow(0).getCell(0).setText("名称");
            table.getRow(0).getCell(1).setText("数量");
            table.getRow(1).getCell(0).setText("苹果");
            table.getRow(1).getCell(1).setText("3");

            doc.write(out);
            bytes = out.toByteArray();
        }

        String md = DocumentParserUtil.parseDocument(bytes, "测试.docx");
        assertEquals("***重点***\n\n| 名称 | 数量 |\n| --- | --- |\n| 苹果 | 3 |", md);
    }

    @Test
    void txtWithUtf8BomHasNoInvisibleLeadingCharacter() throws Exception {
        byte[] bytes = "\uFEFF第一行\n第二行".getBytes(StandardCharsets.UTF_8);
        assertEquals("第一行\n\n第二行", DocumentParserUtil.parseDocument(bytes, "a.txt"));
    }

    @Test
    void markdownToTextDropsTableSyntaxTaskMarkersFencesAndEscapes() {
        String text = DocumentParserUtil.markdownToText(String.join("\n\n",
                "#### 清单",
                "| 名称 | 数量 |\n| --- | --- |\n| 苹果 | 3 |",
                "- [x] 写周报\n- [ ] 买菜",
                "```js\nconst a = 1\n```",
                "价格 3\\*4，路径 a\\_b，R&amp;D 部门，<u>下划线</u>，管道 a\\|b"));

        assertTrue(text.contains("清单"));
        assertTrue(text.contains("名称 数量"));
        assertTrue(text.contains("苹果 3"));
        assertTrue(text.contains("写周报"));
        assertTrue(text.contains("const a = 1"));
        assertTrue(text.contains("价格 3*4，路径 a_b，R&D 部门，下划线，管道 a|b"));
        assertFalse(text.contains("---"));
        assertFalse(text.contains("[x]"));
        assertFalse(text.contains("```"));
        assertFalse(text.contains("####"));
    }
}
