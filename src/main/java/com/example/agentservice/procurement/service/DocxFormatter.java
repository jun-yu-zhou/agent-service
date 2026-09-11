package com.example.agentservice.procurement.service;

import org.docx4j.TextUtils;
import org.docx4j.TraversalUtil;
import org.docx4j.finders.ClassFinder;
import org.docx4j.openpackaging.packages.WordprocessingMLPackage;
import org.docx4j.openpackaging.parts.WordprocessingML.FooterPart;
import org.docx4j.relationships.Relationship;
import org.docx4j.toc.Toc;
import org.docx4j.toc.TocGenerator;
import org.docx4j.wml.CTSimpleField;
import org.docx4j.wml.CTVerticalJc;
import org.docx4j.wml.FooterReference;
import org.docx4j.wml.Ftr;
import org.docx4j.wml.HdrFtrRef;
import org.docx4j.wml.Jc;
import org.docx4j.wml.JcEnumeration;
import org.docx4j.wml.ObjectFactory;
import org.docx4j.wml.P;
import org.docx4j.wml.PPr;
import org.docx4j.wml.R;
import org.docx4j.wml.STVerticalJc;
import org.docx4j.wml.SectPr;
import org.docx4j.wml.Tbl;
import org.docx4j.wml.TblPr;
import org.docx4j.wml.TblWidth;
import org.docx4j.wml.Tc;
import org.docx4j.wml.TcMar;
import org.docx4j.wml.TcPr;
import org.docx4j.wml.Text;

import java.math.BigInteger;
import java.util.List;

/**
 * 使用 docx4j 统一处理招标文件目录、页码和页面版式。
 *
 * <p>Markdown 转换只负责生成 Word 内容结构，本类在转换完成后集中补齐正式文档所需的分页、目录、
 * 页脚和表格版式，避免这些规则分散在生成流程中。</p>
 */
public final class DocxFormatter {

    private static final ObjectFactory FACTORY = new ObjectFactory();

    /** Word 页面尺寸使用 twip，1440 twip 等于 1 英寸。 */
    private static final BigInteger A4_WIDTH = BigInteger.valueOf(11906);
    private static final BigInteger A4_HEIGHT = BigInteger.valueOf(16838);
    private static final BigInteger PAGE_MARGIN = BigInteger.valueOf(1440);
    private static final BigInteger CONTENT_WIDTH = BigInteger.valueOf(9026);

    private final WordprocessingMLPackage document;

    /** 链式方法只登记待执行能力，最终由 apply 按固定顺序统一处理。 */
    private boolean a4;
    private boolean toc;
    private boolean pageNumber;
    private boolean tableLayout;
    private boolean majorChapterPageBreak;

    private DocxFormatter(WordprocessingMLPackage document) {
        if (document == null) throw new IllegalArgumentException("DOCX 文档不能为空");
        this.document = document;
    }

    public static DocxFormatter of(WordprocessingMLPackage document) {
        return new DocxFormatter(document);
    }

    /** 将所有正文分节统一设置为纵向 A4 页面。 */
    public DocxFormatter a4() {
        a4 = true;
        return this;
    }

    /** 根据 Heading 标题样式生成最多六级目录。 */
    public DocxFormatter toc() {
        toc = true;
        return this;
    }

    /** 在正文节的页脚中加入居中的 PAGE 域。 */
    public DocxFormatter pageNumber() {
        pageNumber = true;
        return this;
    }

    /** 调整表格宽度、单元格留白和垂直对齐。 */
    public DocxFormatter tableLayout() {
        tableLayout = true;
        return this;
    }

    /** 让目录后的最高级标题分别从新页开始。 */
    public DocxFormatter majorChapterPageBreak() {
        majorChapterPageBreak = true;
        return this;
    }

    /**
     * 按链式调用选中的能力统一修改文档。
     *
     * <p>目录依赖最终标题与分页信息，因此先完成封面和章节分页，再调用目录分页引擎。</p>
     */
    public void apply() throws Exception {
        SectPr section = section();
        if (a4) configureA4(section);
        List<Object> content = body();
        int directoryIndex = directoryIndex(content);
        formatCover(content, directoryIndex);
        if (majorChapterPageBreak) breakMajorChapters(content, directoryIndex + 1);
        if (toc) insertToc(content, directoryIndex);
        if (pageNumber) insertPageNumber(section);
        if (tableLayout) formatTables();
    }

    /**
     * 将“目录”标题之前的内容识别为封面。
     *
     * <p>在封面末段挂载 nextPage 分节符，使封面独立垂直居中，同时保证目录从下一页开始。</p>
     */
    private void formatCover(List<Object> content, int directoryIndex) {
        if (directoryIndex <= 0) return;
        P last = null;
        for (int index = 0; index < directoryIndex; index++) {
            Object value = org.docx4j.XmlUtils.unwrap(content.get(index));
            if (value instanceof P paragraph) {
                properties(paragraph).setJc(alignment(JcEnumeration.CENTER));
                last = paragraph;
            }
        }
        if (last == null) return;
        SectPr cover = FACTORY.createSectPr();
        SectPr.Type type = FACTORY.createSectPrType();
        type.setVal("nextPage");
        cover.setType(type);
        CTVerticalJc vertical = FACTORY.createCTVerticalJc();
        vertical.setVal(STVerticalJc.CENTER);
        cover.setVAlign(vertical);
        configureA4(cover);
        properties(last).setSectPr(cover);
    }

    /**
     * 以目录后的最高标题层级作为大章节级别。
     *
     * <p>这里只读取 Markdown 转换器生成的 Heading 样式，不依赖中文标题文本或正则规则。</p>
     */
    private void breakMajorChapters(List<Object> content, int startIndex) {
        int majorLevel = content.stream().skip(Math.max(0, startIndex))
                .map(org.docx4j.XmlUtils::unwrap).filter(P.class::isInstance).map(P.class::cast)
                .mapToInt(this::headingLevel).filter(level -> level > 0).min().orElse(0);
        if (majorLevel == 0) return;
        content.stream().skip(Math.max(0, startIndex))
                .map(org.docx4j.XmlUtils::unwrap).filter(P.class::isInstance).map(P.class::cast)
                .filter(paragraph -> headingLevel(paragraph) == majorLevel)
                .forEach(paragraph -> properties(paragraph).setPageBreakBefore(FACTORY.createBooleanDefaultTrue()));
    }

    /**
     * 根据 Heading 1—6 生成目录，并通过 docx4j-export-fo 的分页结果写入页码。
     *
     * <p>最后一个参数传 false，表示不能跳过页码计算；否则目录只有标题而没有实际页码。</p>
     */
    private void insertToc(List<Object> content, int directoryIndex) throws Exception {
        if (directoryIndex < 0) return;
        removeExistingDirectoryEntries(content, directoryIndex);
        Toc.setTocHeadingText(null);
        new TocGenerator(document).generateToc(directoryIndex + 1, " TOC \\o \"1-6\" \\h \\z \\u ", false);
    }

    /** 删除模型生成的静态目录内容，保留“目录”标题，避免导出后出现两份目录。 */
    private void removeExistingDirectoryEntries(List<Object> content, int directoryIndex) {
        int directoryLevel = headingLevel((P) org.docx4j.XmlUtils.unwrap(content.get(directoryIndex)));
        int end = directoryIndex + 1;
        while (end < content.size()) {
            Object value = org.docx4j.XmlUtils.unwrap(content.get(end));
            if (value instanceof P paragraph && headingLevel(paragraph) > 0
                    && (directoryLevel == 0 || headingLevel(paragraph) <= directoryLevel)) break;
            end++;
        }
        content.subList(directoryIndex + 1, end).clear();
    }

    /** 创建独立页脚部件，并将其关系绑定到正文节。 */
    private void insertPageNumber(SectPr section) throws Exception {
        FooterPart footer = new FooterPart();
        footer.setJaxbElement(pageNumberFooter());
        Relationship relationship = document.getMainDocumentPart().addTargetPart(footer);
        FooterReference reference = FACTORY.createFooterReference();
        reference.setId(relationship.getId());
        reference.setType(HdrFtrRef.DEFAULT);
        section.getEGHdrFtrReferences().add(reference);
    }

    /** PAGE 是动态域而非固定数字，Word 会按照实际页面显示页码。 */
    private Ftr pageNumberFooter() {
        Ftr footer = FACTORY.createFtr();
        P paragraph = FACTORY.createP();
        properties(paragraph).setJc(alignment(JcEnumeration.CENTER));
        CTSimpleField field = FACTORY.createCTSimpleField();
        field.setInstr(" PAGE ");
        R run = FACTORY.createR();
        Text value = FACTORY.createText();
        value.setValue("1");
        run.getContent().add(value);
        field.getContent().add(run);
        paragraph.getContent().add(FACTORY.createPFldSimple(field));
        footer.getContent().add(paragraph);
        return footer;
    }

    /**
     * 表格占满正文可用宽度，并增加单元格留白和垂直居中。
     *
     * <p>通过遍历嵌套节点同时处理普通表格和表格内部的单元格。</p>
     */
    private void formatTables() {
        for (Object value : elements(Tbl.class)) {
            Tbl table = (Tbl) org.docx4j.XmlUtils.unwrap(value);
            TblPr properties = table.getTblPr() == null ? FACTORY.createTblPr() : table.getTblPr();
            properties.setTblW(width(CONTENT_WIDTH));
            table.setTblPr(properties);
        }
        for (Object value : elements(Tc.class)) {
            Tc cell = (Tc) org.docx4j.XmlUtils.unwrap(value);
            TcPr properties = cell.getTcPr() == null ? FACTORY.createTcPr() : cell.getTcPr();
            TcMar margins = FACTORY.createTcMar();
            margins.setTop(width(BigInteger.valueOf(100)));
            margins.setLeft(width(BigInteger.valueOf(120)));
            margins.setBottom(width(BigInteger.valueOf(100)));
            margins.setRight(width(BigInteger.valueOf(120)));
            properties.setTcMar(margins);
            CTVerticalJc vertical = FACTORY.createCTVerticalJc();
            vertical.setVal(STVerticalJc.CENTER);
            properties.setVAlign(vertical);
            cell.setTcPr(properties);
        }
    }

    /** 使用 docx4j 遍历器查找指定节点，避免手工递归 WordprocessingML 树。 */
    private List<Object> elements(Class<?> type) {
        ClassFinder finder = new ClassFinder(type);
        new TraversalUtil(document.getMainDocumentPart().getJaxbElement(), finder);
        return finder.results;
    }

    /** 设置纵向 A4、四周一英寸页边距及页脚距离。 */
    private void configureA4(SectPr section) {
        SectPr.PgSz size = FACTORY.createSectPrPgSz();
        size.setW(A4_WIDTH);
        size.setH(A4_HEIGHT);
        section.setPgSz(size);
        SectPr.PgMar margin = FACTORY.createSectPrPgMar();
        margin.setTop(PAGE_MARGIN);
        margin.setBottom(PAGE_MARGIN);
        margin.setLeft(PAGE_MARGIN);
        margin.setRight(PAGE_MARGIN);
        margin.setFooter(BigInteger.valueOf(360));
        section.setPgMar(margin);
    }

    /** 返回“目录”标题在主文档顶层节点中的位置，未找到时返回 -1。 */
    private int directoryIndex(List<Object> content) {
        for (int index = 0; index < content.size(); index++) {
            Object value = org.docx4j.XmlUtils.unwrap(content.get(index));
            if (value instanceof P paragraph && "目录".equals(TextUtils.getText(paragraph).trim())) return index;
        }
        return -1;
    }

    /** 从 Heading 样式读取标题级别；普通段落返回 0。 */
    private int headingLevel(P paragraph) {
        PPr properties = paragraph.getPPr();
        if (properties == null || properties.getPStyle() == null) return 0;
        String style = properties.getPStyle().getVal();
        if (style == null || !style.startsWith("Heading")) return 0;
        try {
            return Integer.parseInt(style.substring("Heading".length()));
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private List<Object> body() {
        return document.getMainDocumentPart().getJaxbElement().getBody().getContent();
    }

    /** 获取正文末尾分节属性；Markdown 转换未创建时就地补齐。 */
    private SectPr section() {
        var body = document.getMainDocumentPart().getJaxbElement().getBody();
        if (body.getSectPr() == null) body.setSectPr(FACTORY.createSectPr());
        return body.getSectPr();
    }

    /** 获取段落属性，供对齐、分页和分节设置复用。 */
    private PPr properties(P paragraph) {
        if (paragraph.getPPr() == null) paragraph.setPPr(FACTORY.createPPr());
        return paragraph.getPPr();
    }

    private Jc alignment(JcEnumeration value) {
        Jc alignment = FACTORY.createJc();
        alignment.setVal(value);
        return alignment;
    }

    /** 创建以 twip 为单位的固定宽度定义。 */
    private TblWidth width(BigInteger value) {
        TblWidth width = FACTORY.createTblWidth();
        width.setType(TblWidth.TYPE_DXA);
        width.setW(value);
        return width;
    }
}
