package com.example.agentservice.procurement.service;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 使用 Redis 中已有初稿验证 poi-tl 渲染与 POI 排版的真实导出效果。 */
@SpringBootTest
class PoiMarkdownDocxRendererIntegrationTest {

    private static final String TASK_ID = "c74be838-935d-4b5d-832d-29d1c76ff022";
    private static final Path OUTPUT = Path.of("target", "poi-markdown-preview.docx");

    @Autowired
    private ProcurementTaskRedisStore taskStore;

    @Autowired
    private PoiMarkdownDocxRenderer renderer;

    @Test
    void rendersDraftFromRedis() throws Exception {
        String draft = taskStore.findTender(TASK_ID)
                .map(ProcurementTaskRedisStore.TenderTaskState::draft)
                .filter(value -> !value.isBlank())
                .orElseThrow(() -> new IllegalStateException("Redis 中未找到测试初稿：" + TASK_ID));

        renderer.render(draft, OUTPUT);

        assertTrue(Files.size(OUTPUT) > 0);
        try (ZipFile archive = new ZipFile(OUTPUT.toFile())) {
            String documentXml = xml(archive, "word/document.xml");
            String footerXml = xml(archive, archive.stream()
                    .map(ZipEntry::getName)
                    .filter(name -> name.startsWith("word/footer"))
                    .findFirst()
                    .orElseThrow());
            String settingsXml = xml(archive, "word/settings.xml");
            assertFalse(documentXml.contains("TOC"));
            assertTrue(footerXml.contains("PAGE"));
            assertFalse(settingsXml.contains("updateFields"));
        }
        System.out.println("poi-tl Markdown 导出文件：" + OUTPUT.toAbsolutePath());
    }

    private String xml(ZipFile archive, String entry) throws Exception {
        return new String(archive.getInputStream(archive.getEntry(entry)).readAllBytes(), StandardCharsets.UTF_8);
    }
}
