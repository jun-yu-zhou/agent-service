package com.example.agentservice.procurement.service;

import com.deepoove.poi.XWPFTemplate;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/** Renders any caller-provided DOCX template with caller-provided values. */
@Service
public class TenderDocxRenderer {

    public void render(Path template, Map<String, Object> values, Path output) throws IOException {
        if (template == null || !Files.isRegularFile(template)) {
            throw new IllegalArgumentException("DOCX模板文件不存在");
        }
        if (output == null) {
            throw new IllegalArgumentException("DOCX输出路径不能为空");
        }
        Path parent = output.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        try (XWPFTemplate document = XWPFTemplate.compile(template.toFile())) {
            document.render(values == null ? Map.of() : values).writeToFile(output.toString());
        }
    }
}
