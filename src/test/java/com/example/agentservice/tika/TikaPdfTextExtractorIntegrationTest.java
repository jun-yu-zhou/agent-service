package com.example.agentservice.tika;

import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;

/** 该测试会调用收费的百炼模型，配置 API Key 后手动取消 Disabled 再运行。 */
@Slf4j
class TikaPdfTextExtractorIntegrationTest {

    @Test
    @Disabled("调用 qwen-vl-max 会产生费用；需要时手动执行")
    void extractsTextFromScanPdf() throws Exception {
        String text = TikaPdfTextExtractor.extract(
                Path.of("example.pdf"));

        log.info("Extracted text: {}", text);
        assertFalse(text.isBlank());
    }
}
