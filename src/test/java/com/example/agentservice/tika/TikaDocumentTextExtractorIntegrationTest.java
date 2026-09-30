package com.example.agentservice.tika;

import lombok.extern.slf4j.Slf4j;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.OutputStream;
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
    void extractsTextFromDocx(@TempDir Path directory) throws Exception {
        Path file = directory.resolve("document.docx");
        try (XWPFDocument document = new XWPFDocument();
             OutputStream output = Files.newOutputStream(file)) {
            document.createParagraph().createRun().setText("DOCX 文档提取");
            document.write(output);
        }

        assertTrue(TikaDocumentTextExtractor.extract(file).contains("DOCX 文档提取"));
    }

    @Test
    @Disabled("调用 qwen-vl-max 会产生费用；需要时手动执行")
    void extractsTextFromScanPdf() throws Exception {
        String text = TikaDocumentTextExtractor.extract(
                Path.of("src/main/resources/doc/等离子清洗机-国内合同CB10056SX2026000004-148.pdf"));

        log.info("Extracted text: {}", text);
        assertFalse(text.isBlank());
    }
}
