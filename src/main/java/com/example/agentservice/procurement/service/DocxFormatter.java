package com.example.agentservice.procurement.service;

import org.apache.poi.wp.usermodel.HeaderFooterType;
import org.apache.poi.xwpf.usermodel.ParagraphAlignment;
import org.apache.poi.xwpf.usermodel.TableRowHeightRule;
import org.apache.poi.xwpf.usermodel.XWPFAbstractNum;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFFooter;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.apache.xmlbeans.impl.xb.xmlschema.SpaceAttribute;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTBody;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTPageMar;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTPageSz;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTPPr;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTR;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTSectPr;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTTblWidth;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTTcMar;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTTcPr;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.STFldCharType;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.STNumberFormat;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.STSectionMark;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.STVerticalJc;

import java.math.BigInteger;
import java.util.List;

/** 使用 Apache POI 统一处理招标文件的页面、目录、页码和表格版式。 */
public final class DocxFormatter {

    private static final int A4_WIDTH = 11906;
    private static final int A4_HEIGHT = 16838;
    private static final int PAGE_MARGIN = 1440;
    private static final int CONTENT_WIDTH = A4_WIDTH - PAGE_MARGIN * 2;

    private final XWPFDocument document;
    private boolean a4;
    private boolean toc;
    private boolean pageNumber;
    private boolean tableLayout;
    private boolean majorChapterPageBreak;

    private DocxFormatter(XWPFDocument document) {
        if (document == null) {
            throw new IllegalArgumentException("DOCX 文档不能为空");
        }
        this.document = document;
    }

    public static DocxFormatter of(XWPFDocument document) {
        return new DocxFormatter(document);
    }

    public DocxFormatter a4() {
        a4 = true;
        return this;
    }

    public DocxFormatter toc() {
        toc = true;
        return this;
    }

    public DocxFormatter pageNumber() {
        pageNumber = true;
        return this;
    }

    public DocxFormatter tableLayout() {
        tableLayout = true;
        return this;
    }

    public DocxFormatter majorChapterPageBreak() {
        majorChapterPageBreak = true;
        return this;
    }

    /** 按链式调用选中的能力统一修改文档。 */
    public void apply() {
        if (a4) {
            configureA4(section());
        }
        List<XWPFParagraph> paragraphs = document.getParagraphs();
        int directoryIndex = directoryTitleIndex(paragraphs);
        formatCover(paragraphs, directoryIndex);
        if (toc) {
            insertToc(paragraphs, directoryIndex);
        }
        if (majorChapterPageBreak) {
            breakMajorChapters(paragraphs, directoryIndex + 1);
        }
        if (pageNumber) {
            insertPageNumber();
        }
        if (tableLayout) {
            formatTables();
        }
        normalizeNumbering();
    }

    /** 目录前的正文作为封面，并通过分节实现垂直居中。 */
    private void formatCover(List<XWPFParagraph> paragraphs, int directoryIndex) {
        if (directoryIndex <= 0) {
            return;
        }
        for (int index = 0; index < directoryIndex; index++) {
            paragraphs.get(index).setAlignment(ParagraphAlignment.CENTER);
        }
        CTPPr properties = properties(paragraphs.get(directoryIndex - 1));
        CTSectPr cover = properties.isSetSectPr() ? properties.getSectPr() : properties.addNewSectPr();
        configureA4(cover);
        cover.addNewType().setVal(STSectionMark.NEXT_PAGE);
        cover.addNewVAlign().setVal(STVerticalJc.CENTER);
    }

    /** 写入标准目录域，由 Word/WPS 根据最终分页生成准确页码。 */
    private void insertToc(List<XWPFParagraph> paragraphs, int directoryIndex) {
        if (directoryIndex < 0) {
            return;
        }
        XWPFParagraph title = paragraphs.get(directoryIndex);
        title.setPageBreak(true);
        for (int index = directoryIndex + 1; index < paragraphs.size(); index++) {
            XWPFParagraph paragraph = paragraphs.get(index);
            int level = headingLevel(paragraph);
            if (level > 0) {
                paragraph.setStyle("Heading" + level);
            }
        }
        title.createRun().addBreak();
        addField(title, "TOC \\o \"1-6\" \\h \\z \\u", "目录将在打开文档时更新");
        document.getSettings().setUpdateFields();
    }

    /** 目录后的最高标题层级视为大章节，每个大章节从新页开始。 */
    private void breakMajorChapters(List<XWPFParagraph> paragraphs, int startIndex) {
        int majorLevel = paragraphs.stream()
                .skip(Math.max(0, startIndex))
                .mapToInt(this::headingLevel)
                .filter(level -> level > 0)
                .min()
                .orElse(0);
        if (majorLevel == 0) {
            return;
        }
        for (int index = Math.max(0, startIndex); index < paragraphs.size(); index++) {
            XWPFParagraph paragraph = paragraphs.get(index);
            if (headingLevel(paragraph) == majorLevel) {
                paragraph.setPageBreak(true);
            }
        }
    }

    private void insertPageNumber() {
        XWPFFooter footer = document.createFooter(HeaderFooterType.DEFAULT);
        XWPFParagraph paragraph = footer.createParagraph();
        paragraph.setAlignment(ParagraphAlignment.CENTER);
        addField(paragraph, "PAGE", "1");
    }

    /** 表格占满正文宽度，并统一单元格留白、最小行高与垂直对齐。 */
    private void formatTables() {
        for (XWPFTable table : document.getTables()) {
            table.setWidth(CONTENT_WIDTH);
            for (XWPFTableRow row : table.getRows()) {
                row.setHeight(420);
                row.setHeightRule(TableRowHeightRule.AT_LEAST);
                for (XWPFTableCell cell : row.getTableCells()) {
                    setCellMargins(cell);
                    cell.setVerticalAlignment(XWPFTableCell.XWPFVertAlign.CENTER);
                }
            }
        }
    }

    /** 插件默认使用“1)”列表格式，统一改为不存在括号歧义的“1.”。 */
    private void normalizeNumbering() {
        if (document.getNumbering() == null) {
            return;
        }
        for (XWPFAbstractNum number : document.getNumbering().getAbstractNums()) {
            for (var level : number.getCTAbstractNum().getLvlList()) {
                if (!level.isSetNumFmt() || level.getNumFmt().getVal() != STNumberFormat.DECIMAL
                        || !level.isSetLvlText()) {
                    continue;
                }
                String format = level.getLvlText().getVal();
                if (format != null && (format.endsWith(")") || format.endsWith("）"))) {
                    level.getLvlText().setVal(format.substring(0, format.length() - 1) + ".");
                }
            }
        }
    }

    private void setCellMargins(XWPFTableCell cell) {
        CTTcPr properties = cell.getCTTc().isSetTcPr() ? cell.getCTTc().getTcPr() : cell.getCTTc().addNewTcPr();
        CTTcMar margins = properties.isSetTcMar() ? properties.getTcMar() : properties.addNewTcMar();
        margins.setTop(width(100));
        margins.setLeft(width(120));
        margins.setBottom(width(100));
        margins.setRight(width(120));
    }

    private CTTblWidth width(int value) {
        CTTblWidth width = CTTblWidth.Factory.newInstance();
        width.setW(BigInteger.valueOf(value));
        return width;
    }

    private void configureA4(CTSectPr section) {
        CTPageSz size = section.isSetPgSz() ? section.getPgSz() : section.addNewPgSz();
        size.setW(BigInteger.valueOf(A4_WIDTH));
        size.setH(BigInteger.valueOf(A4_HEIGHT));
        CTPageMar margin = section.isSetPgMar() ? section.getPgMar() : section.addNewPgMar();
        margin.setTop(BigInteger.valueOf(PAGE_MARGIN));
        margin.setBottom(BigInteger.valueOf(PAGE_MARGIN));
        margin.setLeft(BigInteger.valueOf(PAGE_MARGIN));
        margin.setRight(BigInteger.valueOf(PAGE_MARGIN));
        margin.setFooter(BigInteger.valueOf(360));
    }

    private int directoryTitleIndex(List<XWPFParagraph> paragraphs) {
        for (int index = 0; index < paragraphs.size(); index++) {
            if ("目录".equals(paragraphs.get(index).getText().trim())) {
                return index;
            }
        }
        return -1;
    }

    private int headingLevel(XWPFParagraph paragraph) {
        String style = paragraph.getStyle();
        if (style == null) {
            return 0;
        }
        try {
            return Integer.parseInt(style.startsWith("Heading")
                    ? style.substring("Heading".length()) : style);
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private void addField(XWPFParagraph paragraph, String instruction, String placeholder) {
        CTR begin = paragraph.createRun().getCTR();
        begin.addNewFldChar().setFldCharType(STFldCharType.BEGIN);
        XWPFRun code = paragraph.createRun();
        code.getCTR().addNewInstrText().setStringValue(instruction);
        code.getCTR().getInstrTextArray(0).setSpace(SpaceAttribute.Space.PRESERVE);
        CTR separate = paragraph.createRun().getCTR();
        separate.addNewFldChar().setFldCharType(STFldCharType.SEPARATE);
        paragraph.createRun().setText(placeholder);
        CTR end = paragraph.createRun().getCTR();
        end.addNewFldChar().setFldCharType(STFldCharType.END);
    }

    private CTSectPr section() {
        CTBody body = document.getDocument().getBody();
        return body.isSetSectPr() ? body.getSectPr() : body.addNewSectPr();
    }

    private CTPPr properties(XWPFParagraph paragraph) {
        return paragraph.getCTP().isSetPPr() ? paragraph.getCTP().getPPr() : paragraph.getCTP().addNewPPr();
    }
}
