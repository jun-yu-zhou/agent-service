package com.example.agentservice.procurement.service;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 验证正式导出链路使用 poi-tl 和 POI 生成正文并完成统一排版。 */
class TenderDocumentExportTest {

    @Test
    void shouldRenderDocxDirectlyToMemory() throws Exception {
        byte[] content = new Docx4jMarkdownDocxRenderer().render("# 招标文件\n\n## 第一章 投标邀请");

        assertTrue(content.length > 0);
        assertTrue(content[0] == 'P' && content[1] == 'K');
    }

    @Test
    void shouldRenderMarkdownToDocx() throws Exception {
        Path directory = Files.createTempDirectory("tender-export-");
        Path docx = directory.resolve("tender.docx");
        try {
            new Docx4jMarkdownDocxRenderer().render("# 招标文件\n\n项目名称：测试项目\n\n招标编号：TEST-001\n\n"
                    + "招 标 人：测试单位\n\n组织招标：测试机构\n\n发布日期：2026-09-09\n\n"
                    + "## 目录\n\n第一章 投标邀请 ...... 1\n\n## 第一章 投标邀请\n\n### 一、项目概况\n\n"
                    + "## 第二章 投标人须知\n\n| 项目 | 数量 |\n| --- | --- |\n| 示例服务 | 1 |", docx);

            try (ZipFile archive = new ZipFile(docx.toFile())) {
                String documentXml = xml(archive, "word/document.xml");
                String footerXml = xml(archive, archive.stream()
                        .map(ZipEntry::getName)
                        .filter(name -> name.startsWith("word/footer"))
                        .findFirst()
                        .orElseThrow());
                assertFalse(documentXml.contains("TOC"));
                assertTrue(documentXml.contains("第一章 投标邀请 ...... 1"));
                assertTrue(documentXml.contains("第一章 投标邀请"));
                assertTrue(documentXml.contains("第二章 投标人须知"));
                assertFalse(documentXml.contains("测试项目招标编号"));
                assertFalse(documentXml.contains("测试单位组织招标"));
                assertTrue(documentXml.contains("w:tcMar"));
                assertTrue(documentXml.contains("w:trHeight w:val=\"420\" w:hRule=\"atLeast\""));
                assertTrue(documentXml.contains("w:vAlign w:val=\"center\""));
                assertTrue(documentXml.split("w:pageBreakBefore", -1).length - 1 >= 2);
                assertTrue(documentXml.contains("w:type w:val=\"nextPage\""));
                assertTrue(footerXml.contains("PAGE"));
            }
            assertTrue(Files.size(docx) > 0);
        } finally {
            Files.deleteIfExists(docx);
            Files.deleteIfExists(directory);
        }
    }

    private String xml(ZipFile archive, String entry) throws Exception {
        return new String(archive.getInputStream(archive.getEntry(entry)).readAllBytes(), StandardCharsets.UTF_8);
    }
}
