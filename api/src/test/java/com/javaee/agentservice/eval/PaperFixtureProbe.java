package com.javaee.agentservice.eval;

import com.javaee.documentservice.util.DocumentParserUtil;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What survives PDF extraction, measured before any task is written against it.
 *
 * <p>Assertions have to be read off the extracted text, not off the paper: a
 * table that arrived as one flat line has no answer in it, and grading the agent
 * against the original would score PDFBox instead.</p>
 */
class PaperFixtureProbe {

    private static final Logger log = LoggerFactory.getLogger(PaperFixtureProbe.class);
    private static final Path PAPERS = Path.of("src/test/resources/eval/papers");
    private static final Path EXTRACTED = Path.of("target/eval/extracted");
    private static final int RESULT_CAP = 24_000;

    private static final Pattern SECTION =
            Pattern.compile("(?m)^\\s*(\\d{1,2}(\\.\\d{1,2})?)\\s+[A-Z][A-Za-z].{2,60}$");
    private static final Pattern TABLE = Pattern.compile("Table\\s+\\d+");
    private static final Pattern FIGURE = Pattern.compile("Figure\\s+\\d+");

    @Test
    void reportsWhatSurvivesExtraction() throws Exception {
        for (Path pdf : Files.list(PAPERS).sorted().toList()) {
            byte[] bytes = Files.readAllBytes(pdf);
            int pages;
            try (PDDocument document = Loader.loadPDF(bytes)) {
                pages = document.getNumberOfPages();
            }
            String text = DocumentParserUtil.parseDocument(bytes, pdf.getFileName().toString());
            Files.createDirectories(EXTRACTED);
            Files.write(EXTRACTED.resolve(
                            pdf.getFileName().toString().replace(".pdf", ".txt")),
                    text.getBytes(StandardCharsets.UTF_8));

            log.info("FIXTURE {} | {} pages | {} MB pdf | {} chars | {} reads at {}k cap "
                            + "| sections={} tables={} figures={} badChars={}",
                    pdf.getFileName(),
                    pages,
                    String.format("%.1f", bytes.length / 1024.0 / 1024.0),
                    text.length(),
                    (int) Math.ceil(text.length() / (double) RESULT_CAP),
                    RESULT_CAP / 1000,
                    count(SECTION, text),
                    count(TABLE, text),
                    count(FIGURE, text),
                    unmappable(text));
            log.info("FIXTURE {} first 400 chars >>> {}", pdf.getFileName(),
                    text.substring(0, Math.min(400, text.length())).replace('\n', '·'));
            log.info("FIXTURE {} around first table >>> {}",
                    pdf.getFileName(), around(TABLE, text, 300));
        }
    }

    /** Lone surrogates: a PDF font mapped a glyph to half a code point. */
    private int unmappable(String text) {
        int bad = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isHighSurrogate(c)) {
                if (i + 1 >= text.length() || !Character.isLowSurrogate(text.charAt(i + 1))) {
                    bad++;
                } else {
                    i++;
                }
            } else if (Character.isLowSurrogate(c)) {
                bad++;
            }
        }
        return bad;
    }

    private int count(Pattern pattern, String text) {
        Matcher matcher = pattern.matcher(text);
        int found = 0;
        while (matcher.find()) {
            found++;
        }
        return found;
    }

    private String around(Pattern pattern, String text, int chars) {
        Matcher matcher = pattern.matcher(text);
        if (!matcher.find()) {
            return "(none)";
        }
        int from = matcher.start();
        return text.substring(from, Math.min(from + chars, text.length()))
                .replace('\n', '·');
    }
}
