package com.javaee.documentservice.util;

import net.sourceforge.tess4j.ITesseract;
import net.sourceforge.tess4j.Tesseract;
import net.sourceforge.tess4j.TesseractException;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.hwpf.HWPFDocument;
import org.apache.poi.hwpf.extractor.WordExtractor;
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

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 文档解析工具类
 * 支持多种文档格式：Word、PDF、HTML、Markdown、图片OCR等
 */
public class DocumentParserUtil {

    private static final Logger log = LoggerFactory.getLogger(DocumentParserUtil.class);

    // 支持的图片格式
    private static final Set<String> IMAGE_EXTENSIONS = new HashSet<>(
            Arrays.asList("jpg", "jpeg", "png", "bmp", "gif", "tiff", "tif")
    );

    // 支持的所有文档格式
    private static final Set<String> SUPPORTED_EXTENSIONS = new HashSet<>(
            Arrays.asList("docx", "doc", "txt", "pdf", "md", "markdown", "html", "htm", "xml",
                    "jpg", "jpeg", "png", "bmp", "gif", "tiff", "tif")
    );

    // 可解析格式不一定可编辑：PDF 和图片可提取文本，但不支持导入为可编辑文档。
    private static final Set<String> EDITABLE_EXTENSIONS = new HashSet<>(
            Arrays.asList("docx", "txt", "md", "markdown", "html", "htm")
    );

    // markdownToNodes 的标题正则只到 #{1,3}，再深的层级还原不回来
    private static final int MAX_HEADING_LEVEL = 3;

    private static final Pattern HEADING_STYLE = Pattern.compile("(?i)heading\\s*([1-9])");

    /**
     * 解析文档内容
     * @param fileContent 文件内容字节数组
     * @param fileName 文件名（用于判断格式）
     * @return 解析后的文本内容
     */
    public static String parseDocument(byte[] fileContent, String fileName) {
        if (fileContent == null || fileContent.length == 0) {
            return "";
        }

        String extension = getFileExtension(fileName);
        log.info("开始解析文档: fileName={}, extension={}, size={}", fileName, extension, fileContent.length);

        try {
            return switch (extension.toLowerCase()) {
                case "docx" -> docxToMarkdown(fileContent);
                case "doc" -> parseDoc(fileContent);
                case "txt" -> textToMarkdown(decodeText(fileContent));
                case "pdf" -> parsePdf(fileContent);
                case "md", "markdown" -> normalizeNewlines(decodeText(fileContent));
                case "html", "htm" -> htmlToMarkdown(decodeText(fileContent));
                case "xml" -> parseXml(fileContent);
                case "jpg", "jpeg", "png", "bmp", "gif", "tiff", "tif" -> parseImageOcr(fileContent);
                default -> {
                    log.warn("不支持的文件格式: {}, 尝试作为纯文本处理", extension);
                    yield parseTxt(fileContent);
                }
            };
        } catch (Exception e) {
            log.error("解析文档失败: {}", fileName, e);
            // 如果解析失败，尝试作为纯文本处理
            try {
                return new String(fileContent, StandardCharsets.UTF_8);
            } catch (Exception ex) {
                return "";
            }
        }
    }

    /**
     * 获取文件扩展名
     */
    private static String getFileExtension(String fileName) {
        if (fileName == null || !fileName.contains(".")) {
            return "txt";
        }
        return fileName.substring(fileName.lastIndexOf(".") + 1);
    }

    /**
     * 判断文件是否为支持的图片格式
     */
    public static boolean isImageFile(String fileName) {
        String extension = getFileExtension(fileName);
        return IMAGE_EXTENSIONS.contains(extension.toLowerCase());
    }

    /**
     * 判断文件是否为支持的文档格式
     */
    public static boolean isSupportedDocument(String fileName) {
        String extension = getFileExtension(fileName);
        return SUPPORTED_EXTENSIONS.contains(extension.toLowerCase());
    }

    public static boolean isEditableDocument(String fileName) {
        String extension = getFileExtension(fileName);
        return EDITABLE_EXTENSIONS.contains(extension.toLowerCase());
    }

    /**
     * 解析DOCX文件
     */
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

    /**
     * 表格降级成一行一段，单元格用 {@code |} 分隔。
     *
     * <p>editor 的 extensions 里没有 Table，产 markdown 表格也会被 generateJSON 整个丢掉。
     * 降级至少保住内容。</p>
     */
    private static List<String> tableToMarkdown(XWPFTable table) {
        List<String> rows = new ArrayList<>();
        for (XWPFTableRow row : table.getRows()) {
            String line = row.getTableCells().stream()
                    .map(cell -> cell.getText() == null ? "" : cell.getText().trim())
                    .collect(Collectors.joining(" | "));
            if (!line.replace("|", "").trim().isEmpty()) {
                rows.add(line);
            }
        }
        return rows;
    }

    /**
     * run 的样式转 markdown 标记，一个 run 只取一个。
     *
     * <p>{@code markdownToNodes} 的行内正则是 {@code \*\*([^*]+)\*\*} 这种，标记里不能再有标记，
     * 所以 {@code ***粗斜体***} 会被解析错。嵌套是这套编解码的能力上限，加粗优先。</p>
     */
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
            if (run.isBold()) {
                piece = "**" + piece + "**";
            } else if (run.isItalic()) {
                piece = "*" + piece + "*";
            } else if (run.isStrikeThrough()) {
                piece = "~~" + piece + "~~";
            } else if (run.getUnderline() != UnderlinePatterns.NONE) {
                piece = "<u>" + piece + "</u>";
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
     * 解析DOC文件（旧版Word）
     */
    private static String parseDoc(byte[] content) throws IOException {
        try (HWPFDocument document = new HWPFDocument(new ByteArrayInputStream(content))) {
            WordExtractor extractor = new WordExtractor(document);
            return extractor.getText().trim();
        }
    }

    /**
     * 解析TXT文件
     */
    private static String parseTxt(byte[] content) {
        return decodeText(content).trim();
    }

    /**
     * 字节转文本，UTF-8 解不出来就按 GB18030 再来一次。
     *
     * <p>国内的 txt 大量是 GBK/GB18030。硬编码 UTF-8 的话它们整篇是乱码，
     * 而乱码不会报错，会一路存进文档、进摘要、进向量库。</p>
     */
    private static String decodeText(byte[] content) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(content))
                    .toString();
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
     * 解析PDF文件
     */
    private static String parsePdf(byte[] content) throws IOException {
        try (PDDocument document = Loader.loadPDF(content)) {
            PDFTextStripper stripper = new PDFTextStripper();
            return stripper.getText(document).trim();
        }
    }

    /**
     * HTML 转 markdown。
     *
     * <p>只产 {@code markdownToNodes} 认得的块类型：多出来的还原不回去，会在
     * round-trip 校验那里被拒，或者干脆退化成一段普通文字。</p>
     */
    public static String htmlToMarkdown(String html) {
        Document doc = Jsoup.parse(html);
        List<String> blocks = new ArrayList<>();
        List<String> pendingList = new ArrayList<>();

        for (Element element : doc.select("h1, h2, h3, h4, h5, h6, p, li, blockquote, pre, hr, img, td, th")) {
            String tag = element.tagName();
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
                pendingList.add(ordered ? "1. " + inline : "- " + inline);
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

    /**
     * 元素的行内内容转 markdown。
     *
     * <p>嵌套标记不产：{@code markdownToNodes} 的行内正则解析不了 {@code ***x***}。</p>
     */
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
                .replaceAll("(?m)^\\s{0,3}#{1,6}\\s+", "")
                .replaceAll("(?m)^\\s{0,3}>\\s?", "")
                .replaceAll("(?m)^\\s{0,3}([-*+]|\\d+\\.)\\s+", "")
                .replaceAll("(?m)^\\s{0,3}(-{3,}|\\*{3,})\\s*$", "")
                .replaceAll("!\\[[^\\]]*\\](\\([^)]*\\))?", "")
                .replaceAll("\\[([^\\]]*)\\]\\([^)]*\\)", "$1")
                .replaceAll("\\*\\*([^*]+)\\*\\*", "$1")
                .replaceAll("~~([^~]+)~~", "$1")
                .replaceAll("\\*([^*]+)\\*", "$1")
                .replaceAll("`([^`]+)`", "$1")
                .trim();
    }

    /**
     * 解析XML文件
     */
    private static String parseXml(byte[] content) throws IOException {
        String xml = new String(content, StandardCharsets.UTF_8);
        Document doc = Jsoup.parse(xml);
        
        // 提取所有文本内容
        return doc.text().trim();
    }

    /**
     * 解析图片文件，使用OCR识别文字
     * 注意：需要安装 Tesseract OCR 引擎
     */
    private static String parseImageOcr(byte[] content) throws IOException {
        try (ByteArrayInputStream bais = new ByteArrayInputStream(content)) {
            BufferedImage image = ImageIO.read(bais);
            if (image == null) {
                log.warn("无法解析图片文件");
                return "";
            }
            
            ITesseract tesseract = new Tesseract();
            // 设置Tesseract数据路径（可选，如果Tesseract已安装到系统路径可以不设置）
            // tesseract.setDatapath("path/to/tessdata");
            
            // 设置识别语言（英文+简体中文）
            tesseract.setLanguage("eng+chi_sim");
            
            try {
                String result = tesseract.doOCR(image);
                return result.trim();
            } catch (TesseractException e) {
                log.error("OCR识别失败", e);
                return "";
            }
        }
    }

    /**
     * 清理解析后的文本（去除多余空白）
     */
    public static String cleanText(String text) {
        if (text == null) {
            return "";
        }
        // 去除多余的换行和空格
        return text.replaceAll("\\n+", "\n")
                   .replaceAll("\\s+", " ")
                   .trim();
    }

    /**
     * 获取文档摘要。
     *
     * <p>先剥标记再截断：正文是编辑器派生的 markdown，直接截前 200 字会截出
     * {@code ## 标题} 这种东西，而且很可能截在标记中间。</p>
     */
    public static String getSummary(String text, int maxLength) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        text = cleanText(markdownToText(text));
        if (text.length() <= maxLength) {
            return text;
        }
        return text.substring(0, maxLength) + "...";
    }
}
