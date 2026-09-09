package com.example.agentservice.procurement.service;

import org.apache.poi.xwpf.model.XWPFHeaderFooterPolicy;
import org.apache.poi.xwpf.usermodel.BreakType;
import org.apache.poi.xwpf.usermodel.ParagraphAlignment;
import org.apache.poi.xwpf.usermodel.TableRowHeightRule;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFFooter;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.docx4j.openpackaging.packages.WordprocessingMLPackage;
import org.docx4j.openpackaging.parts.WordprocessingML.StyleDefinitionsPart;
import org.docx4j.toc.Toc;
import org.docx4j.toc.TocGenerator;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTPageMar;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTPPr;
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

    private static final int PAGE_WIDTH = 11906;
    private static final int PAGE_HEIGHT = 16838;
    private static final int PAGE_MARGIN = 1440;
    private static final int TABLE_WIDTH = 9026;
    private static final int TABLE_FONT_SIZE = 11;
    /**
     * Markdown 是编辑器保存的唯一正文格式；本方法负责其最终 Word 排版。
     * 封面、目录和一级章节的分页规则在这里统一处理，避免下游再修改文档结构。
     */
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
        int tocPosition = -1;
        int tocDepth = 1;
        int chapterLevel = 0;
        boolean cover = true;
        boolean coverSectionClosed = false;
        XWPFParagraph lastCoverParagraph = null;
        List<String> lines = markdown.replace("\r\n", "\n").replace('\r', '\n').lines().toList();
        try (XWPFDocument document = new XWPFDocument(); OutputStream stream = Files.newOutputStream(output)) {
            configurePage(document.getDocument().getBody().isSetSectPr()
                    ? document.getDocument().getBody().getSectPr()
                    : document.getDocument().getBody().addNewSectPr(), false);
            configureFooter(document);
            for (int index = 0; index < lines.size();) {
                String line = lines.get(index).trim();
                if (line.isBlank() || line.equals("---") || isBreakTagLine(line)) {
                    index++;
                    continue;
                }
                if (line.startsWith("|") && line.endsWith("|")) {
                    List<String> tableLines = new ArrayList<>();
                    while (index < lines.size() && lines.get(index).trim().startsWith("|")
                            && lines.get(index).trim().endsWith("|")) {
                        tableLines.add(lines.get(index++).trim());
                    }
                    appendTable(document, tableLines);
                    continue;
                }

                int headingLevel = headingLevel(line);
                if (headingLevel > 0 && headingText(line, headingLevel).equals("目录")) {
                    if (cover && lastCoverParagraph != null) {
                        closeCoverSection(lastCoverParagraph);
                        coverSectionClosed = true;
                    }
                    cover = false;
                    XWPFParagraph directory = document.createParagraph();
                    if (!coverSectionClosed) {
                        breakPageBefore(directory);
                    }
                    XWPFRun directoryRun = directory.createRun();
                    directoryRun.setText("目录");
                    directoryRun.setBold(true);
                    directoryRun.setFontFamily("宋体");
                    directoryRun.setFontSize(18);
                    directory.setAlignment(ParagraphAlignment.CENTER);
                    directory.setSpacingBefore(240);
                    directory.setSpacingAfter(240);
                    tocPosition = document.getBodyElements().size();
                    index++;
                    while (index < lines.size() && !isChapterHeading(lines.get(index).trim(),
                            headingLevel(lines.get(index).trim()))) {
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
                            document.createParagraph().createRun().addBreak(BreakType.PAGE);
                        }
                        tocPosition = document.getBodyElements().size();
                    }
                    if (chapterLevel == 0) {
                        chapterLevel = headingLevel;
                    }
                }
                if (!cover && headingLevel > 0) {
                    tocDepth = Math.max(tocDepth, Math.max(1, headingLevel - chapterLevel + 1));
                }
                XWPFParagraph paragraph = appendParagraph(document, line, cover, chapterHeading, chapterLevel);
                if (cover) {
                    lastCoverParagraph = paragraph;
                }
                index++;
            }
            document.write(stream);
        }
        if (tocPosition >= 0) {
            addTableOfContents(output, tocPosition, tocDepth);
        }
    }

    private void configureFooter(XWPFDocument document) {
        XWPFFooter footer = document.createHeaderFooterPolicy().createFooter(XWPFHeaderFooterPolicy.DEFAULT);
        XWPFParagraph paragraph = footer.createParagraph();
        paragraph.setAlignment(ParagraphAlignment.CENTER);
        var page = paragraph.getCTP().addNewFldSimple();
        page.setInstr("PAGE");
        var run = page.addNewR();
        run.addNewRPr().addNewRFonts().setEastAsia("宋体");
        run.getRPr().addNewSz().setVal(BigInteger.valueOf(20));
        run.addNewT().setStringValue("1");
    }

    /** 封面以独立节垂直居中，后续正文仍沿用普通 A4 页面。 */
    private void closeCoverSection(XWPFParagraph paragraph) {
        CTSectPr section = properties(paragraph).addNewSectPr();
        configurePage(section, true);
    }

    private void configurePage(CTSectPr section, boolean verticalCenter) {
        section.addNewPgSz().setW(BigInteger.valueOf(PAGE_WIDTH));
        section.getPgSz().setH(BigInteger.valueOf(PAGE_HEIGHT));
        CTPageMar margin = section.addNewPgMar();
        margin.setTop(BigInteger.valueOf(PAGE_MARGIN));
        margin.setBottom(BigInteger.valueOf(PAGE_MARGIN));
        margin.setLeft(BigInteger.valueOf(PAGE_MARGIN));
        margin.setRight(BigInteger.valueOf(PAGE_MARGIN));
        margin.setFooter(BigInteger.valueOf(360));
        if (verticalCenter) {
            section.addNewVAlign().setVal(STVerticalJc.CENTER);
        }
    }

    private void breakPageBefore(XWPFParagraph paragraph) {
        properties(paragraph).addNewPageBreakBefore();
    }

    private XWPFParagraph appendParagraph(
            XWPFDocument document, String line, boolean cover, boolean chapterHeading, int chapterLevel) {
        XWPFParagraph paragraph = document.createParagraph();
        int headingLevel = headingLevel(line);
        String text = headingLevel > 0 ? headingText(line, headingLevel)
                : line.replaceFirst("^(?:- |\\d+\\.\\s+)", "");
        XWPFRun run = paragraph.createRun();
        run.setText(stripInlineMarkdown(text));
        run.setFontFamily("宋体");
        if (headingLevel > 0) {
            if (cover) {
                paragraph.setAlignment(ParagraphAlignment.CENTER);
            } else {
                int outlineLevel = Math.max(1, headingLevel - chapterLevel + 1);
                paragraph.setStyle("Heading" + outlineLevel);
                properties(paragraph).addNewOutlineLvl().setVal(BigInteger.valueOf(outlineLevel - 1L));
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
            if (line.startsWith("- ") || line.matches("^\\d+\\.\\s+.*")) {
                paragraph.setIndentationLeft(420);
            } else if (!cover) {
                paragraph.setFirstLineIndent(480);
            }
        }
        return paragraph;
    }

    /** Markdown 表格按内容长度分配列宽，保持现有导出表格的可读性。 */
    private void appendTable(XWPFDocument document, List<String> lines) {
        List<List<String>> rows = lines.stream().filter(line -> !line.matches("^\\|[ :|\\-]+\\|$")).map(this::tableCells)
                .filter(cells -> !cells.isEmpty()).toList();
        if (rows.isEmpty()) {
            return;
        }
        int columns = rows.stream().mapToInt(List::size).max().orElse(1);
        int[] widths = columnWidths(rows, columns);
        XWPFTable table = document.createTable(rows.size(), columns);
        table.setWidth(TABLE_WIDTH);
        table.setCellMargins(100, 120, 100, 120);
        for (int rowIndex = 0; rowIndex < rows.size(); rowIndex++) {
            XWPFTableRow row = table.getRow(rowIndex);
            row.setHeight(rowIndex == 0 ? 500 : 420);
            row.setHeightRule(TableRowHeightRule.AT_LEAST);
            row.setCantSplitRow(true);
            for (int columnIndex = 0; columnIndex < columns; columnIndex++) {
                XWPFTableCell cell = row.getCell(columnIndex);
                cell.setWidth(Integer.toString(widths[columnIndex]));
                cell.setVerticalAlignment(XWPFTableCell.XWPFVertAlign.CENTER);
                cell.removeParagraph(0);
                XWPFParagraph paragraph = cell.addParagraph();
                paragraph.setAlignment(ParagraphAlignment.LEFT);
                paragraph.setSpacingBetween(1.15D);
                paragraph.setSpacingAfter(0);
                XWPFRun run = paragraph.createRun();
                List<String> values = rows.get(rowIndex);
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
        int[] widths = new int[columns];
        int totalWeight = 0;
        for (int column = 0; column < columns; column++) {
            int maxLength = 0;
            for (List<String> row : rows) {
                if (column < row.size()) {
                    String value = row.get(column);
                    maxLength = Math.max(maxLength, value.codePointCount(0, value.length()));
                }
            }
            widths[column] = Math.max(5, Math.min(maxLength, 30));
            totalWeight += widths[column];
        }
        int assignedWidth = 0;
        for (int column = 0; column < columns; column++) {
            int width = column == columns - 1 ? TABLE_WIDTH - assignedWidth
                    : TABLE_WIDTH * widths[column] / totalWeight;
            widths[column] = width;
            assignedWidth += width;
        }
        return widths;
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

    private CTPPr properties(XWPFParagraph paragraph) {
        return paragraph.getCTP().isSetPPr() ? paragraph.getCTP().getPPr() : paragraph.getCTP().addNewPPr();
    }

    private List<String> tableCells(String line) {
        String[] values = line.substring(1, line.length() - 1).split("\\|", -1);
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

    private boolean isChapterHeading(String line, int headingLevel) {
        return headingLevel > 0 && headingText(line, headingLevel)
                .matches("^第[一二三四五六七八九十百千万零〇0-9]+(?:章|部分|篇|编).*|^[一二三四五六七八九十]+、.*部分.*");
    }

    private String headingText(String line, int headingLevel) {
        return line.substring(headingLevel + 1).trim();
    }

    private String stripInlineMarkdown(String text) {
        return text.replaceAll("(?i)<br\\s*/?>", "").replace("**", "").replace("`", "");
    }

    /** 模板分页/留白的 br 标签被模型带进初稿时，整行只由 br 标签组成的行直接丢弃。 */
    private boolean isBreakTagLine(String line) {
        return line.replaceAll("(?i)<br\\s*/?>", "").isBlank();
    }

}
