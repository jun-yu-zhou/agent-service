package com.example.agentservice.procurement.service;

import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

class TenderDocxRendererTest {

    @Test
    void shouldRenderCallerProvidedTemplate() throws Exception {
        Path directory = Files.createTempDirectory("tender-docx-render-");
        Path template = directory.resolve("template.docx");
        Path output = directory.resolve("output.docx");
        try (XWPFDocument document = new XWPFDocument(); OutputStream stream = Files.newOutputStream(template)) {
            document.createParagraph().createRun().setText("项目名称：{{projectName}}");
            document.write(stream);
        }

        new TenderDocxRenderer().render(template, Map.of("projectName", "通用采购项目"), output);

        try (InputStream stream = Files.newInputStream(output); XWPFDocument document = new XWPFDocument(stream)) {
            assertTrue(document.getParagraphs().get(0).getText().contains("通用采购项目"));
        }
    }
}
