package com.example.agentservice.procurement.service;

import org.docx4j.markdown.MarkdownImporter;
import org.docx4j.markdown.MarkdownImportOptions;
import org.docx4j.openpackaging.packages.WordprocessingMLPackage;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;

/** 使用 docx4j 将 Markdown 转换并排版为招标文件 Word。 */
@Service
public class Docx4jMarkdownDocxRenderer {

    private static final MarkdownImportOptions MARKDOWN_OPTIONS = new MarkdownImportOptions()
            .setExtensions(EnumSet.of(
                    MarkdownImportOptions.Extension.TABLES,
                    MarkdownImportOptions.Extension.STRIKETHROUGH));

    /** 在内存中生成 Word，供 HTTP 接口直接返回。 */
    public byte[] render(String markdown) throws IOException {
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            render(markdown, output);
            return output.toByteArray();
        }
    }

    /** 写入指定路径，主要供测试和离线任务使用。 */
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
        try {
            WordprocessingMLPackage document = new MarkdownImporter(MARKDOWN_OPTIONS).createPackage(markdown);
            DocxFormatter.of(document)
                    .a4()
                    .toc()
                    .pageNumber()
                    .tableLayout()
                    .majorChapterPageBreak()
                    .apply();
            document.save(output);
        } catch (Exception exception) {
            throw new IOException("生成 Word 文件失败", exception);
        }
    }
}
