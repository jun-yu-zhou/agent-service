package com.example.agentservice.procurement.common.docx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.List;
import org.docx4j.TextUtils;
import org.docx4j.openpackaging.packages.WordprocessingMLPackage;
import org.docx4j.wml.P;
import org.docx4j.wml.Tbl;
import org.junit.jupiter.api.Test;

class DocxFormatterTest {

    @Test
    void shouldFormatCloudGeneratedTenderDocument() throws Exception {
        WordprocessingMLPackage source = WordprocessingMLPackage.createPackage();
        var main = source.getMainDocumentPart();
        main.addStyledParagraphOfText("Heading1", "项目招标文件");
        main.addParagraphOfText("项目编号：TEST-001");
        main.addStyledParagraphOfText("Heading1", "第一章 投标邀请");
        main.addParagraphOfText("正文");
        main.addObject(new org.docx4j.wml.ObjectFactory().createTbl());
        main.addStyledParagraphOfText("Heading1", "第二章 投标须知");
        DocxFormatter.of(source)
                .autoTocHeading()
                .toc()
                .pageNumber()
                .tableLayout()
                .majorChapterPageBreak()
                .apply();
        ByteArrayOutputStream formatted = new ByteArrayOutputStream();
        source.save(formatted);
        WordprocessingMLPackage result = WordprocessingMLPackage.load(
                new ByteArrayInputStream(formatted.toByteArray()));
        List<Object> body = result.getMainDocumentPart().getJaxbElement().getBody().getContent();

        int directory = paragraphIndex(body, "目录");
        int projectCode = paragraphIndex(body, "项目编号：TEST-001");
        int firstChapter = paragraphIndex(body, "第一章 投标邀请");
        assertTrue(projectCode < directory && directory < firstChapter);
        assertTrue(paragraph(body, "第一章 投标邀请").getPPr().getPageBreakBefore().isVal());
        assertTrue(paragraph(body, "第二章 投标须知").getPPr().getPageBreakBefore().isVal());
        assertFalse(result.getMainDocumentPart().getJaxbElement().getBody()
                .getSectPr().getEGHdrFtrReferences().isEmpty());

        Tbl table = body.stream().map(org.docx4j.XmlUtils::unwrap)
                .filter(Tbl.class::isInstance).map(Tbl.class::cast).findFirst().orElseThrow();
        assertEquals("9026", table.getTblPr().getTblW().getW().toString());
    }

    private int paragraphIndex(List<Object> content, String text) {
        for (int index = 0; index < content.size(); index++) {
            Object value = org.docx4j.XmlUtils.unwrap(content.get(index));
            if (value instanceof P paragraph && text.equals(TextUtils.getText(paragraph))) return index;
        }
        return -1;
    }

    private P paragraph(List<Object> content, String text) {
        return content.stream().map(org.docx4j.XmlUtils::unwrap)
                .filter(P.class::isInstance).map(P.class::cast)
                .filter(value -> text.equals(TextUtils.getText(value)))
                .findFirst().orElseThrow();
    }
}
