package com.example.agentservice.procurement.service;

import com.deepoove.poi.XWPFTemplate;
import com.deepoove.poi.config.Configure;
import com.deepoove.poi.plugin.markdown.MarkdownRenderData;
import com.deepoove.poi.plugin.markdown.MarkdownRenderPolicy;
import com.deepoove.poi.plugin.markdown.MarkdownStyle;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/** 使用 poi-tl Markdown 插件生成并排版招标文件 Word。 */
@Service
public class Docx4jMarkdownDocxRenderer {

    private static final String MARKDOWN_TAG = "md";
    private static final Configure CONFIGURE = Configure.builder()
            .bind(MARKDOWN_TAG, new MarkdownRenderPolicy())
            .build();

    /** 在内存中生成 Word，供 HTTP 接口直接返回。 */
    public byte[] render(String markdown) throws IOException {
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            render(markdown, output);
            return output.toByteArray();
        }
    }

    /** 生成 Word 并写入指定路径，主要供测试和离线任务使用。 */
    public void render(String markdown, Path output) throws IOException {
        if (output == null) {
            throw new IllegalArgumentException("DOCX 输出路径不能为空");
        }
        Path parent = output.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        try (OutputStream stream = Files.newOutputStream(output)) {
            render(markdown, stream);
        }
    }

    private void render(String markdown, OutputStream output) throws IOException {
        if (markdown == null || markdown.isBlank()) {
            throw new IllegalArgumentException("定稿 Markdown 不能为空");
        }
        MarkdownRenderData data = new MarkdownRenderData();
        data.setMarkdown(markdown);
        data.setStyle(MarkdownStyle.newStyle());
        try (XWPFDocument document = template();
                XWPFTemplate rendered = XWPFTemplate.compile(document, CONFIGURE)
                        .render(Map.of(MARKDOWN_TAG, data))) {
            DocxFormatter.of(rendered.getXWPFDocument())
                    .a4()
                    .pageNumber()
                    .tableLayout()
                    .majorChapterPageBreak()
                    .apply();
            rendered.write(output);
        } catch (Exception exception) {
            throw new IOException("生成 Word 文件失败", exception);
        }
    }

    private XWPFDocument template() {
        XWPFDocument document = new XWPFDocument();
        document.createParagraph().createRun().setText("{{" + MARKDOWN_TAG + "}}");
        return document;
    }
}
