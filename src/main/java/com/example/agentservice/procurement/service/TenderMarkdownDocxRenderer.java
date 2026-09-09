package com.example.agentservice.procurement.service;

import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.BreakType;
import org.apache.poi.xwpf.usermodel.XWPFFooter;
import org.apache.poi.xwpf.usermodel.ParagraphAlignment;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.apache.poi.xwpf.usermodel.TableRowHeightRule;
import org.apache.poi.xwpf.model.XWPFHeaderFooterPolicy;
import org.docx4j.openpackaging.packages.WordprocessingMLPackage;
import org.docx4j.openpackaging.parts.WordprocessingML.StyleDefinitionsPart;
import org.docx4j.toc.Toc;
import org.docx4j.toc.TocGenerator;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTPageMar;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTSectPr;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.STVerticalJc;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.OutputStream;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** 将采购文档生成的 Markdown 内容渲染为通用 A4 DOCX。 */
@Service
public class TenderMarkdownDocxRenderer {

    private static final int TABLE_WIDTH = 9026;
    private static final int TABLE_FONT_SIZE = 11;

    private static final String JDK_TRANSFORMER_FACTORY =
            "com.sun.org.apache.xalan.internal.xsltc.trax.TransformerFactoryImpl";

    public void render(String markdown, Path output) throws IOException {
        if (markdown == null || markdown.isBlank()) {
            throw new IllegalArgumentException("定稿 Markdown 不能为空");
        }
        if (output == null) {
            throw new IllegalArgumentException("DOCX 输出路径不能为空");
        }
        Path parent = output.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        System.setProperty("javax.xml.transform.TransformerFactory", JDK_TRANSFORMER_FACTORY);
        int tocPosition = -1;
        int tocDepth = 1;
        int chapterHeadingLevel = 0;
        try (XWPFDocument document = new XWPFDocument(); OutputStream stream = Files.newOutputStream(output)) {
            configureA4(document);
            configureFooter(document);
            List<String> lines = markdown.replace("\r\n", "\n").replace('\r', '\n').lines().toList();
            boolean cover = true;
            boolean coverSectionClosed = false;
            XWPFParagraph lastCoverParagraph = null;
            for (int index = 0; index < lines.size();) {
                String line = lines.get(index).trim();
                if (line.isBlank() || line.equals("---")) {
                    index++;
                    continue;
                }
                if (isTableLine(line)) {
                    List<String> tableLines = new ArrayList<>();
                    while (index < lines.size() && isTableLine(lines.get(index).trim())) {
                        tableLines.add(lines.get(index).trim());
                        index++;
                    }
                    appendTable(document, tableLines);
                    continue;
                }
                int headingLevel = headingLevel(line);
                if (isDirectoryHeading(line, headingLevel)) {
                    if (cover && lastCoverParagraph != null) {
                        closeCoverSection(lastCoverParagraph);
                        coverSectionClosed = true;
                    }
                    cover = false;
                    XWPFParagraph directoryHeading = document.createParagraph();
                    XWPFRun headingRun = directoryHeading.createRun();
                    headingRun.setText("目录");
                    headingRun.setBold(true);
                    headingRun.setFontFamily("宋体");
                    headingRun.setFontSize(18);
                    directoryHeading.setAlignment(ParagraphAlignment.CENTER);
                    directoryHeading.setSpacingBefore(240);
                    directoryHeading.setSpacingAfter(240);
                    if (!coverSectionClosed) {
                        breakPageBefore(directoryHeading);
                    }
                    tocPosition = document.getBodyElements().size();
                    index++;
                    while (index < lines.size() && !isChapterHeading(
                            lines.get(index).trim(), headingLevel(lines.get(index).trim()))) {
                        index++;
                    }
                    continue;
                }
                boolean chapterHeading = isChapterHeading(line, headingLevel);
                if (chapterHeading) {
                    if (cover && lastCoverParagraph != null) {
                        closeCoverSection(lastCoverParagraph);
                        coverSectionClosed = true;
                    }
                    cover = false;
                    if (tocPosition < 0) {
                        if (!coverSectionClosed) {
                            appendPageBreak(document);
                        }
                        tocPosition = document.getBodyElements().size();
                    }
                    if (chapterHeadingLevel == 0) {
                        chapterHeadingLevel = headingLevel;
                    }
                }
                if (!cover && headingLevel > 0) {
                    tocDepth = Math.max(tocDepth, outlineLevel(headingLevel, chapterHeadingLevel));
                }
                XWPFParagraph appended = appendParagraph(document, line, cover, chapterHeading, chapterHeadingLevel);
                if (cover) {
                    lastCoverParagraph = appended;
                }
                index++;
            }
            document.write(stream);
        }
        if (tocPosition >= 0) {
            addTableOfContents(output, tocPosition, tocDepth);
        }
    }

    private void configureA4(XWPFDocument document) {
        CTSectPr section = document.getDocument().getBody().isSetSectPr()
                ? document.getDocument().getBody().getSectPr()
                : document.getDocument().getBody().addNewSectPr();
        section.addNewPgSz().setW(BigInteger.valueOf(11906));
        section.getPgSz().setH(BigInteger.valueOf(16838));
        CTPageMar margin = section.addNewPgMar();
        margin.setTop(BigInteger.valueOf(1440));
        margin.setBottom(BigInteger.valueOf(1440));
        margin.setLeft(BigInteger.valueOf(1440));
        margin.setRight(BigInteger.valueOf(1440));
        margin.setFooter(BigInteger.valueOf(360));
    }

    private void configureFooter(XWPFDocument document) {
        XWPFHeaderFooterPolicy policy = document.createHeaderFooterPolicy();
        XWPFFooter footer = policy.createFooter(XWPFHeaderFooterPolicy.DEFAULT);
        XWPFParagraph footerParagraph = footer.createParagraph();
        footerParagraph.setAlignment(ParagraphAlignment.CENTER);
        var page = footerParagraph.getCTP().addNewFldSimple();
        page.setInstr("PAGE");
        var run = page.addNewR();
        run.addNewRPr().addNewRFonts().setEastAsia("宋体");
        run.getRPr().addNewSz().setVal(BigInteger.valueOf(20));
        run.addNewT().setStringValue("1");
    }

    private void appendPageBreak(XWPFDocument document) {
        document.createParagraph().createRun().addBreak(BreakType.PAGE);
    }

    private void breakPageBefore(XWPFParagraph paragraph) {
        var properties = paragraph.getCTP().isSetPPr()
                ? paragraph.getCTP().getPPr()
                : paragraph.getCTP().addNewPPr();
        properties.addNewPageBreakBefore();
    }

    private XWPFParagraph appendParagraph(
            XWPFDocument document, String line, boolean cover, boolean chapterHeading, int chapterHeadingLevel) {
        XWPFParagraph paragraph = document.createParagraph();
        int headingLevel = headingLevel(line);
        String text = headingLevel > 0 ? line.substring(headingLevel + 1).trim() : stripListPrefix(line);
        XWPFRun run = paragraph.createRun();
        run.setText(stripInlineMarkdown(text));
        run.setFontFamily("宋体");
        if (headingLevel > 0) {
            if (cover) {
                paragraph.setAlignment(ParagraphAlignment.CENTER);
            } else {
                int outlineLevel = outlineLevel(headingLevel, chapterHeadingLevel);
                paragraph.setStyle("Heading" + outlineLevel);
                var properties = paragraph.getCTP().isSetPPr()
                        ? paragraph.getCTP().getPPr()
                        : paragraph.getCTP().addNewPPr();
                properties.addNewOutlineLvl().setVal(BigInteger.valueOf(outlineLevel - 1L));
            }
            run.setBold(true);
            run.setFontSize(switch (headingLevel) {
                case 1 -> 22;
                case 2 -> 18;
                case 3 -> 15;
                default -> 13;
            });
            paragraph.setSpacingBefore(headingLevel <= 2 ? 240 : 160);
            paragraph.setSpacingAfter(headingLevel <= 2 ? 240 : 100);
            if (chapterHeading) {
                breakPageBefore(paragraph);
            }
        } else {
            run.setFontSize(12);
            paragraph.setSpacingBetween(1.5D);
            paragraph.setSpacingAfter(80);
            paragraph.setAlignment(cover ? ParagraphAlignment.CENTER : ParagraphAlignment.BOTH);
            if (isListItem(line)) {
                paragraph.setIndentationLeft(420);
            } else if (!cover) {
                paragraph.setFirstLineIndent(480);
            }
        }
        return paragraph;
    }

    /** 封面单独成节并垂直水平居中：在封面最后一段落下分节符，该节版式设置为垂直居中。 */
    private void closeCoverSection(XWPFParagraph lastCoverParagraph) {
        var properties = lastCoverParagraph.getCTP().isSetPPr()
                ? lastCoverParagraph.getCTP().getPPr()
                : lastCoverParagraph.getCTP().addNewPPr();
        CTSectPr section = properties.addNewSectPr();
        section.addNewPgSz().setW(BigInteger.valueOf(11906));
        section.getPgSz().setH(BigInteger.valueOf(16838));
        CTPageMar margin = section.addNewPgMar();
        margin.setTop(BigInteger.valueOf(1440));
        margin.setBottom(BigInteger.valueOf(1440));
        margin.setLeft(BigInteger.valueOf(1440));
        margin.setRight(BigInteger.valueOf(1440));
        margin.setFooter(BigInteger.valueOf(360));
        section.addNewVAlign().setVal(STVerticalJc.CENTER);
    }

    private void appendTable(XWPFDocument document, List<String> lines) {
        List<List<String>> rows = lines.stream()
                .filter(line -> !isTableSeparator(line))
                .map(this::tableCells)
                .filter(cells -> !cells.isEmpty())
                .toList();
        if (rows.isEmpty()) {
            return;
        }
        int columns = rows.stream().mapToInt(List::size).max().orElse(1);
        XWPFTable table = document.createTable(rows.size(), columns);
        table.setWidth(TABLE_WIDTH);
        table.setCellMargins(100, 120, 100, 120);
        int[] columnWidths = columnWidths(rows, columns);
        for (int rowIndex = 0; rowIndex < rows.size(); rowIndex++) {
            XWPFTableRow row = table.getRow(rowIndex);
            row.setHeight(rowIndex == 0 ? 500 : 420);
            row.setHeightRule(TableRowHeightRule.AT_LEAST);
            row.setCantSplitRow(true);
            List<String> values = rows.get(rowIndex);
            for (int columnIndex = 0; columnIndex < columns; columnIndex++) {
                XWPFTableCell cell = row.getCell(columnIndex);
                cell.setWidth(Integer.toString(columnWidths[columnIndex]));
                cell.setVerticalAlignment(XWPFTableCell.XWPFVertAlign.CENTER);
                cell.removeParagraph(0);
                XWPFParagraph paragraph = cell.addParagraph();
                paragraph.setAlignment(ParagraphAlignment.LEFT);
                paragraph.setSpacingBetween(1.15D);
                paragraph.setSpacingAfter(0);
                XWPFRun run = paragraph.createRun();
                appendText(run, stripInlineMarkdown(columnIndex < values.size() ? values.get(columnIndex) : ""));
                run.setFontFamily("宋体");
                run.setFontSize(TABLE_FONT_SIZE);
                run.setBold(rowIndex == 0);
            }
        }
    }

    private void addTableOfContents(Path output, int position, int depth) throws IOException {
        try {
            WordprocessingMLPackage document = WordprocessingMLPackage.load(output.toFile());
            if (document.getMainDocumentPart().getStyleDefinitionsPart() == null) {
                StyleDefinitionsPart styles = new StyleDefinitionsPart();
                styles.unmarshalDefaultStyles();
                document.getMainDocumentPart().addTargetPart(styles);
            }
            Toc.setTocHeadingText("目录");
            TocGenerator generator = new TocGenerator(document);
            generator.pageNumbersViaXSLT(true);
            generator.generateToc(position, " TOC \\o \"1-" + depth + "\" \\h \\u ", false);
            document.save(output.toFile());
        } catch (Exception exception) {
            throw new IOException("生成 Word 目录失败", exception);
        }
    }

    private int[] columnWidths(List<List<String>> rows, int columns) {
        int[] weights = new int[columns];
        int totalWeight = 0;
        for (int column = 0; column < columns; column++) {
            int maxLength = 0;
            for (List<String> row : rows) {
                if (column < row.size()) {
                    maxLength = Math.max(maxLength, row.get(column).codePointCount(0, row.get(column).length()));
                }
            }
            weights[column] = Math.max(5, Math.min(maxLength, 30));
            totalWeight += weights[column];
        }
        int assignedWidth = 0;
        for (int column = 0; column < columns; column++) {
            int width = column == columns - 1
                    ? TABLE_WIDTH - assignedWidth
                    : TABLE_WIDTH * weights[column] / totalWeight;
            weights[column] = width;
            assignedWidth += width;
        }
        return weights;
    }

    private void appendText(XWPFRun run, String text) {
        String[] lines = text.split("\\n", -1);
        for (int index = 0; index < lines.length; index++) {
            if (index > 0) {
                run.addBreak();
            }
            run.setText(lines[index]);
        }
    }

    private boolean isTableLine(String line) {
        return line.startsWith("|") && line.endsWith("|");
    }

    private boolean isTableSeparator(String line) {
        return line.matches("^\\|[ :|\\-]+\\|$");
    }

    private List<String> tableCells(String line) {
        String content = line.substring(1, line.length() - 1);
        String[] values = content.split("\\|", -1);
        List<String> cells = new ArrayList<>(values.length);
        for (String value : values) {
            cells.add(value.trim().replace("<br>", "\n"));
        }
        return cells;
    }

    private int headingLevel(String line) {
        int level = 0;
        while (level < line.length() && line.charAt(level) == '#') {
            level++;
        }
        return level > 0 && level < line.length() && line.charAt(level) == ' ' ? level : 0;
    }

    private boolean isDirectoryHeading(String line, int headingLevel) {
        return headingLevel > 0 && headingText(line, headingLevel).equals("目录");
    }

    private boolean isChapterHeading(String line, int headingLevel) {
        return headingLevel > 0 && headingText(line, headingLevel)
                .matches("^第[一二三四五六七八九十百千万零〇0-9]+(?:章|部分|篇|编).*|^[一二三四五六七八九十]+、.*部分.*");
    }

    private String headingText(String line, int headingLevel) {
        return line.substring(headingLevel + 1).trim();
    }

    private int outlineLevel(int headingLevel, int chapterHeadingLevel) {
        return Math.max(1, headingLevel - chapterHeadingLevel + 1);
    }

    private boolean isListItem(String line) {
        return line.startsWith("- ") || line.matches("^\\d+\\.\\s+.*");
    }

    private String stripListPrefix(String line) {
        return line.replaceFirst("^(?:- |\\d+\\.\\s+)", "");
    }

    private String stripInlineMarkdown(String text) {
        return text.replace("**", "").replace("`", "");
    }
}
