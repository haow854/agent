package com.javaee.documentservice.util;

import org.apache.poi.xwpf.usermodel.IBodyElement;
import org.apache.poi.xwpf.usermodel.UnderlinePatterns;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFHyperlink;
import org.apache.poi.xwpf.usermodel.XWPFHyperlinkRun;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.apache.poi.xwpf.usermodel.XWPFStyle;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTStyle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 导入的文件转 Markdown（docx、txt、Markdown、HTML），以及 Markdown 正文转纯文本、摘要。
 */
public class DocumentParserUtil {

    private static final Logger log = LoggerFactory.getLogger(DocumentParserUtil.class);

    // 能导入成可编辑文档的格式
    private static final Set<String> EDITABLE_EXTENSIONS = Set.of("docx", "txt", "md", "markdown", "html", "htm");

    // Markdown 只有六级标题，Word 的七到九级大纲并到六级
    private static final int MAX_HEADING_LEVEL = 6;

    private static final Pattern HEADING_STYLE = Pattern.compile("(?i)heading\\s*([1-9])");

    /**
     * 文件内容转 Markdown，格式按扩展名判断。
     *
     * <p>解析失败直接抛出，由调用方决定怎么提示用户；不要把解析不了的二进制当文本存进文档。</p>
     */
    public static String parseDocument(byte[] fileContent, String fileName) throws IOException {
        String extension = getFileExtension(fileName);
        log.info("开始解析文档: fileName={}, extension={}, size={}", fileName, extension, fileContent.length);

        return switch (extension) {
            case "docx" -> docxToMarkdown(fileContent);
            case "txt" -> textToMarkdown(decodeText(fileContent));
            case "md", "markdown" -> normalizeNewlines(decodeText(fileContent));
            case "html", "htm" -> htmlToMarkdown(decodeText(fileContent));
            default -> throw new IOException("不支持导入这种格式: " + fileName);
        };
    }

    /** 小写扩展名；没有扩展名时按纯文本处理。 */
    private static String getFileExtension(String fileName) {
        if (fileName == null || !fileName.contains(".")) {
            return "txt";
        }
        return fileName.substring(fileName.lastIndexOf('.') + 1).toLowerCase();
    }

    public static boolean isEditableDocument(String fileName) {
        return EDITABLE_EXTENSIONS.contains(getFileExtension(fileName));
    }

    /**
     * DOCX 转 markdown。
     *
     * <p>按 {@code getBodyElements()} 走而不是先段落后表格：那样表格会全被甩到文末，
     * 位置信息全丢。标题层级取样式的 outlineLvl 而不是样式名——中文 Word 叫「标题 1」，
     * 英文叫「Heading 1」，模板改过名就都认不出，而 outlineLvl 是 Word 自己给
     * 导航窗格和目录用的那个数，与语言和命名无关。</p>
     */
    private static String docxToMarkdown(byte[] content) throws IOException {
        try (XWPFDocument document = new XWPFDocument(new ByteArrayInputStream(content))) {
            List<String> blocks = new ArrayList<>();
            List<String> pendingList = new ArrayList<>();

            for (IBodyElement element : document.getBodyElements()) {
                if (element instanceof XWPFParagraph paragraph) {
                    String inline = runsToMarkdown(document, paragraph);
                    if (inline.isEmpty()) {
                        continue;
                    }
                    if (paragraph.getNumID() != null) {
                        pendingList.add(orderedList(paragraph) ? "1. " + inline : "- " + inline);
                        continue;
                    }
                    flushList(blocks, pendingList);
                    int level = headingLevel(document, paragraph);
                    blocks.add(level > 0 ? "#".repeat(level) + " " + inline : inline);
                } else if (element instanceof XWPFTable table) {
                    flushList(blocks, pendingList);
                    blocks.addAll(tableToMarkdown(table));
                }
            }
            flushList(blocks, pendingList);

            return String.join("\n\n", blocks);
        }
    }

    private static void flushList(List<String> blocks, List<String> pendingList) {
        if (!pendingList.isEmpty()) {
            blocks.add(String.join("\n", pendingList));
            pendingList.clear();
        }
    }

    private static boolean orderedList(XWPFParagraph paragraph) {
        String format = paragraph.getNumFmt();
        return format != null && !"bullet".equalsIgnoreCase(format);
    }

    /** Word 表格 → Markdown 表格，第一行作表头。 */
    private static List<String> tableToMarkdown(XWPFTable table) {
        List<List<String>> rows = new ArrayList<>();
        for (XWPFTableRow row : table.getRows()) {
            rows.add(row.getTableCells().stream()
                    .map(cell -> cell.getText() == null ? "" : cell.getText())
                    .toList());
        }
        String markdown = markdownTable(rows);
        return markdown.isEmpty() ? List.of() : List.of(markdown);
    }

    /**
     * 单元格文字 → Markdown 表格，第一行作表头。
     *
     * <p>列数按最宽的一行补齐；单元格里的换行并成空格，{@code |} 加反斜杠转义，
     * 否则会被当成列分隔。整张表没有文字时返回空串。</p>
     */
    static String markdownTable(List<List<String>> rows) {
        List<List<String>> cleaned = rows.stream()
                .map(row -> row.stream()
                        .map(cell -> cell.replaceAll("\\s+", " ").trim().replace("|", "\\|"))
                        .toList())
                .filter(row -> row.stream().anyMatch(cell -> !cell.isEmpty()))
                .toList();
        if (cleaned.isEmpty()) {
            return "";
        }
        int columns = cleaned.stream().mapToInt(List::size).max().orElse(0);
        List<String> lines = new ArrayList<>();
        for (int i = 0; i < cleaned.size(); i++) {
            List<String> cells = new ArrayList<>(cleaned.get(i));
            while (cells.size() < columns) {
                cells.add("");
            }
            lines.add("| " + String.join(" | ", cells) + " |");
            if (i == 0) {
                lines.add("|" + " --- |".repeat(columns));
            }
        }
        return String.join("\n", lines);
    }

    /** run 的样式转 Markdown 标记，加粗、斜体、删除线、下划线可以叠加。 */
    private static String runsToMarkdown(XWPFDocument document, XWPFParagraph paragraph) {
        StringBuilder text = new StringBuilder();
        for (XWPFRun run : paragraph.getRuns()) {
            String piece = run.text();
            if (piece == null || piece.isEmpty()) {
                continue;
            }
            piece = piece.replace("\n", " ").replace("\r", " ");
            if (piece.isBlank()) {
                text.append(piece);
                continue;
            }
            if (run.getUnderline() != UnderlinePatterns.NONE) {
                piece = "<u>" + piece + "</u>";
            }
            if (run.isStrikeThrough()) {
                piece = "~~" + piece + "~~";
            }
            if (run.isItalic()) {
                piece = "*" + piece + "*";
            }
            if (run.isBold()) {
                piece = "**" + piece + "**";
            }
            if (run instanceof XWPFHyperlinkRun link) {
                XWPFHyperlink target = link.getHyperlink(document);
                if (target != null && target.getURL() != null) {
                    piece = "[" + piece + "](" + target.getURL() + ")";
                }
            }
            text.append(piece);
        }
        return text.toString().trim();
    }

    private static int headingLevel(XWPFDocument document, XWPFParagraph paragraph) {
        String styleId = paragraph.getStyleID();
        if (styleId == null || styleId.isBlank()) {
            return 0;
        }
        XWPFStyle style = document.getStyles() == null ? null : document.getStyles().getStyle(styleId);
        if (style != null) {
            CTStyle ct = style.getCTStyle();
            if (ct != null && ct.getPPr() != null && ct.getPPr().getOutlineLvl() != null) {
                // outlineLvl 从 0 起算，正文是 9
                int level = ct.getPPr().getOutlineLvl().getVal().intValue() + 1;
                return level >= 1 && level <= 9 ? Math.min(level, MAX_HEADING_LEVEL) : 0;
            }
        }
        Matcher matcher = HEADING_STYLE.matcher(styleId);
        return matcher.find() ? Math.min(Integer.parseInt(matcher.group(1)), MAX_HEADING_LEVEL) : 0;
    }

    /**
     * 字节转文本，UTF-8 解不出来就按 GB18030 再来一次。
     *
     * <p>国内的 txt 大量是 GBK/GB18030。硬编码 UTF-8 的话它们整篇是乱码，
     * 而乱码不会报错，会一路存进文档、进摘要、进向量库。</p>
     */
    private static String decodeText(byte[] content) {
        try {
            String text = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(content))
                    .toString();
            // 记事本等工具保存的 UTF-8 带 BOM，不去掉会变成正文开头的一个不可见字符
            return text.startsWith("\uFEFF") ? text.substring(1) : text;
        } catch (CharacterCodingException notUtf8) {
            log.info("按 UTF-8 解码失败，回退 GB18030");
            return new String(content, Charset.forName("GB18030"));
        }
    }

    private static String normalizeNewlines(String text) {
        return text.replace("\r\n", "\n").replace('\r', '\n').trim();
    }

    /**
     * 纯文本转 markdown：一行一段。
     *
     * <p>markdownToNodes 按空行切块，原样丢进去的话整篇会变成一个段落。
     * 中文 txt 基本是一行一段，按行切是可预期的那个解释。</p>
     */
    private static String textToMarkdown(String text) {
        return normalizeNewlines(text).lines()
                .map(String::trim)
                .filter(line -> !line.isEmpty())
                .collect(Collectors.joining("\n\n"));
    }

    /**
     * HTML 转 Markdown：标题、段落、列表（含嵌套）、引用、代码块、表格、分隔线、图片。
     *
     * <p>按文档顺序逐个取块元素。表格整张转换，表格里的段落不再单独出块。</p>
     */
    public static String htmlToMarkdown(String html) {
        Document doc = Jsoup.parse(html);
        List<String> blocks = new ArrayList<>();
        List<String> pendingList = new ArrayList<>();

        for (Element element : doc.select("h1, h2, h3, h4, h5, h6, p, li, blockquote, pre, hr, img, table")) {
            String tag = element.tagName();
            if (!"table".equals(tag) && element.closest("table") != null) {
                continue;
            }
            if ("table".equals(tag)) {
                flushList(blocks, pendingList);
                String table = markdownTable(element.select("tr").stream()
                        .map(tr -> tr.select("th, td").stream().map(Element::text).toList())
                        .toList());
                if (!table.isEmpty()) {
                    blocks.add(table);
                }
                continue;
            }
            if ("pre".equals(tag)) {
                flushList(blocks, pendingList);
                String code = element.wholeText().replaceAll("^\n+|\n+$", "");
                if (!code.isBlank()) {
                    blocks.add("```\n" + code + "\n```");
                }
                continue;
            }
            if ("hr".equals(tag)) {
                flushList(blocks, pendingList);
                blocks.add("---");
                continue;
            }
            if ("img".equals(tag)) {
                String src = element.attr("src");
                if (!src.isBlank()) {
                    flushList(blocks, pendingList);
                    String alt = element.attr("alt");
                    blocks.add("![" + (alt.isBlank() ? "image" : alt) + "](" + src + ")");
                }
                continue;
            }

            String inline = inlineToMarkdown(element);
            if (inline.isEmpty()) {
                continue;
            }

            if ("li".equals(tag)) {
                Element parent = element.parent();
                boolean ordered = parent != null && "ol".equals(parent.tagName());
                // 外层还有几层列表，就缩进几级
                long depth = element.parents().stream()
                        .filter(p -> "ul".equals(p.tagName()) || "ol".equals(p.tagName()))
                        .count() - 1;
                String indent = "  ".repeat((int) Math.max(depth, 0));
                pendingList.add(indent + (ordered ? "1. " : "- ") + inline);
                continue;
            }

            flushList(blocks, pendingList);
            if (tag.length() == 2 && tag.charAt(0) == 'h') {
                blocks.add("#".repeat(Math.min(tag.charAt(1) - '0', MAX_HEADING_LEVEL)) + " " + inline);
            } else if ("blockquote".equals(tag)) {
                blocks.add(inline.lines().map(line -> "> " + line).collect(Collectors.joining("\n")));
            } else {
                blocks.add(inline);
            }
        }
        flushList(blocks, pendingList);

        return String.join("\n\n", blocks);
    }

    /** 元素的行内内容转 Markdown，标记可以嵌套（{@code <b><i>x</i></b>} 得到 {@code ***x***}）。 */
    private static String inlineToMarkdown(Element element) {
        StringBuilder text = new StringBuilder();
        for (Node node : element.childNodes()) {
            if (node instanceof TextNode textNode) {
                text.append(textNode.text());
            } else if (node instanceof Element child) {
                if ("ul".equals(child.tagName()) || "ol".equals(child.tagName())) {
                    continue;
                }
                String inner = inlineToMarkdown(child);
                if (inner.isBlank()) {
                    continue;
                }
                text.append(switch (child.tagName()) {
                    case "strong", "b" -> "**" + inner + "**";
                    case "em", "i" -> "*" + inner + "*";
                    case "s", "del", "strike" -> "~~" + inner + "~~";
                    case "u", "ins" -> "<u>" + inner + "</u>";
                    case "code" -> "`" + inner + "`";
                    case "a" -> "[" + inner + "](" + child.attr("href") + ")";
                    default -> inner;
                });
            }
        }
        return text.toString().replaceAll("\\s+", " ").trim();
    }

    /**
     * markdown 正文转纯文本，标记不算字数。
     *
     * <p>正文是编辑器 ydoc 派生出的 markdown，统计和摘要要的是人读到的字符，
     * {@code ## } 和 {@code **} 是格式不是内容。</p>
     */
    public static String markdownToText(String markdown) {
        if (markdown == null) {
            return "";
        }
        return markdown
                // 代码块的围栏行
                .replaceAll("(?m)^\\s*```.*$", "")
                // 表格：分隔行整行去掉，单元格之间的竖线换成空格（转义的 \| 是正文，留着）
                .replaceAll("(?m)^\\s*\\|?(\\s*:?-{3,}:?\\s*\\|)+\\s*:?-{0,}:?\\s*$", "")
                .replaceAll("(?m)^\\s*\\|\\s?", "")
                .replaceAll("(?m)\\s?(?<!\\\\)\\|\\s*$", "")
                .replaceAll("\\s*(?<!\\\\)\\|\\s*", " ")
                .replaceAll("(?m)^\\s{0,3}#{1,6}\\s+", "")
                .replaceAll("(?m)^\\s{0,3}>\\s?", "")
                .replaceAll("(?m)^\\s*([-*+]|\\d+\\.)\\s+(\\[[ xX]\\]\\s+)?", "")
                .replaceAll("(?m)^\\s{0,3}(-{3,}|\\*{3,})\\s*$", "")
                .replaceAll("!\\[[^\\]]*\\](\\([^)]*\\))?", "")
                .replaceAll("\\[([^\\]]*)\\]\\([^)]*\\)", "$1")
                .replaceAll("\\*\\*([^*]+)\\*\\*", "$1")
                .replaceAll("~~([^~]+)~~", "$1")
                .replaceAll("\\*([^*]+)\\*", "$1")
                .replaceAll("`([^`]+)`", "$1")
                .replaceAll("</?u>", "")
                // 规范写法里转义过的符号和 HTML 实体还原成字符
                .replaceAll("\\\\([\\\\`*_{}\\[\\]()#+\\-.!|<>~])", "$1")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .replace("&amp;", "&")
                .trim();
    }

    /**
     * 获取文档摘要。
     *
     * <p>先剥标记再截断：正文是编辑器派生的 markdown，直接截前 200 字会截出
     * {@code ## 标题} 这种东西，而且很可能截在标记中间。空白（含换行）并成一个空格。</p>
     */
    public static String getSummary(String text, int maxLength) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        text = markdownToText(text).replaceAll("\\s+", " ").trim();
        if (text.length() <= maxLength) {
            return text;
        }
        return text.substring(0, maxLength) + "...";
    }
}
