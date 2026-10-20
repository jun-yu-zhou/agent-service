package com.example.agentservice.tika;

import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 该测试会调用收费的百炼模型，配置 API Key 后手动取消 Disabled 再运行。 */
@Slf4j
class TikaDocumentTextExtractorIntegrationTest {

    @Test
    void extractsTextWithoutVisualOcr(@TempDir Path directory) throws Exception {
        Path file = directory.resolve("document.txt");
        Files.writeString(file, "通用文档提取");

        assertTrue(TikaDocumentTextExtractor.extract(file).contains("通用文档提取"));
    }

    @Test
    void extractsTextFromLegacyDoc() throws Exception {
        String text = TikaDocumentTextExtractor.extract(
                Path.of("src/main/resources/doc/等离子清洗机-国内合同CB10056SX2026000004-148.pdf"));

        log.info("Extracted text: {}", text);
        assertFalse(text.isBlank());
    }

    @Test
    void extractsTextFromMarkdown(@TempDir Path directory) throws Exception {
        Path file = directory.resolve("document.md");
        Files.writeString(file, "# 标题\n\n**粗体**内容\n");

        String text = TikaDocumentTextExtractor.extract(file);

        assertTrue(text.contains("标题"));
        assertTrue(text.contains("粗体"));
        assertFalse(text.contains("**"), "MarkdownParser 应解析掉粗体标记");
    }

    @Test
    @Disabled("调用百炼 OCR 模型会产生费用；需要时手动执行")
    void extractsTextFromScanPdf() throws Exception {
        String text = TikaDocumentTextExtractor.extract(
                Path.of("src/main/resources/doc/等离子清洗机-国内合同CB10056SX2026000004-148.pdf"));

        log.info("Extracted text: {}", text);
        assertFalse(text.isBlank());
    }
}
