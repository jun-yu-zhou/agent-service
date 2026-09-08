package com.example.agentservice.procurement.service;

import org.junit.jupiter.api.Test;
import org.apache.poi.xwpf.usermodel.XWPFDocument;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;

class TenderDocumentExportTest {

    @Test
    void shouldRenderMarkdownToDocx() throws Exception {
        Path directory = Files.createTempDirectory("tender-export-");
        Path docx = directory.resolve("tender.docx");
        try {
            new TenderMarkdownDocxRenderer().render("# 招标文件\n\n项目编号：VCCGDLGK-2026090\n\n"
                    + "采购代理机构：江苏唯诚建设咨询有限公司\n\n# 目录\n\n第一章 投标邀请\n\n"
                    + "# 第一章 投标邀请\n\n## 一、项目概况\n\n★采购需求\n\n"
                    + "### （一）采购范围\n\n#### 1. 服务边界\n\n"
                    + "# 第二部分 投标人须知\n\n## 一、总则\n\n"
                    + "| 项目 | 数量 |\n| --- | --- |\n| 示例服务 | 1 |", docx);
            try (XWPFDocument document = new XWPFDocument(Files.newInputStream(docx))) {
                assertEquals(11, document.getTables().get(0).getRow(1).getCell(0)
                        .getParagraphs().get(0).getRuns().get(0).getFontSize());
                assertTrue(document.getTables().get(0).getCellMarginLeft() >= 120);
                assertTrue(document.getTables().get(0).getRow(1).getHeight() >= 420);
                assertTrue(document.getHeaderList().isEmpty());
                assertTrue(document.getFooterList().get(0).getParagraphs().get(0).getCTP().xmlText()
                        .contains("PAGE"));
            }
            try (ZipFile archive = new ZipFile(docx.toFile())) {
                String documentXml = new String(
                        archive.getInputStream(archive.getEntry("word/document.xml")).readAllBytes(),
                        StandardCharsets.UTF_8);
                assertTrue(documentXml.contains("TOC"));
                assertTrue(documentXml.contains("第一章 投标邀请"));
                assertTrue(documentXml.contains("第二部分 投标人须知"));
                assertTrue(documentXml.contains("一、项目概况"));
                assertTrue(documentXml.contains("（一）采购范围"));
                assertTrue(documentXml.contains("1. 服务边界"));
                assertTrue(documentXml.contains("1-4"));
                assertTrue(documentXml.split("pageBreakBefore", -1).length - 1 >= 3);
            }
            assertTrue(Files.size(docx) > 0);
        } finally {
            Files.deleteIfExists(docx);
            Files.deleteIfExists(directory);
        }
    }
}
