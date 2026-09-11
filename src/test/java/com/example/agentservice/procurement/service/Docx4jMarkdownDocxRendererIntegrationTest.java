package com.example.agentservice.procurement.service;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** 使用 Redis 中已有初稿验证 docx4j 的 Markdown 渲染与排版效果。 */
@SpringBootTest
class Docx4jMarkdownDocxRendererIntegrationTest {

    private static final String TASK_ID = "c74be838-935d-4b5d-832d-29d1c76ff022";
    private static final Path OUTPUT = Path.of("target", "docx4j-markdown-preview.docx");

    @Autowired
    private ProcurementTaskRedisStore taskStore;

    @Autowired
    private Docx4jMarkdownDocxRenderer renderer;

    @Test
    void rendersDraftFromRedis() throws Exception {
        String draft = taskStore.findTender(TASK_ID)
                .map(ProcurementTaskRedisStore.TenderTaskState::draft)
                .filter(value -> !value.isBlank())
                .orElseThrow(() -> new IllegalStateException("Redis 中未找到测试初稿：" + TASK_ID));

        renderer.render(draft, OUTPUT);

        assertTrue(Files.size(OUTPUT) > 0);
        try (ZipFile archive = new ZipFile(OUTPUT.toFile())) {
            assertTrue(xml(archive, "word/document.xml").contains("TOC"));
            assertTrue(xml(archive, archive.stream()
                    .map(ZipEntry::getName)
                    .filter(name -> name.startsWith("word/footer"))
                    .findFirst()
                    .orElseThrow()).contains("PAGE"));
        }
        System.out.println("docx4j Markdown 导出文件：" + OUTPUT.toAbsolutePath());
    }

    private String xml(ZipFile archive, String entry) throws Exception {
        return new String(archive.getInputStream(archive.getEntry(entry)).readAllBytes(), StandardCharsets.UTF_8);
    }
}
