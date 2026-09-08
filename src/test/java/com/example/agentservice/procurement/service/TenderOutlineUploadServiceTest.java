package com.example.agentservice.procurement.service;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TenderOutlineUploadServiceTest {

    private final TenderOutlineUploadService service = new TenderOutlineUploadService();

    @Test
    void readsHtmlTemplate() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "template.html", "text/html", "<h1>招标文件</h1>".getBytes(StandardCharsets.UTF_8));

        assertEquals("<h1>招标文件</h1>", service.readTemplate(file));
    }

    @Test
    void rejectsNonHtmlTemplate() {
        MockMultipartFile file = new MockMultipartFile("file", "outline.doc", "application/msword", new byte[] {1});

        assertThrows(IllegalArgumentException.class, () -> service.readTemplate(file));
    }

    @Test
    void rejectsEmptyHtmlTemplateWithClearMessage() {
        MockMultipartFile file = new MockMultipartFile("file", "template.html", "text/html", new byte[0]);

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class, () -> service.readTemplate(file));
        assertEquals("招标文件 HTML 模板是空文件（0 字节），请先写入模板内容", exception.getMessage());
    }

    @Test
    void recognizesHtmlContentWhenFilenameIsUnexpected() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "legacy-template", "application/octet-stream",
                "<!doctype html><html><body>招标文件</body></html>".getBytes(StandardCharsets.UTF_8));

        assertEquals("<!doctype html><html><body>招标文件</body></html>", service.readTemplate(file));
    }
}
