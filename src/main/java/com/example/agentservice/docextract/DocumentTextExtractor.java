package com.example.agentservice.docextract;

import org.commonmark.node.Node;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.text.TextContentRenderer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Set;

/**
 * 轻量文档全文提取入口：丢文件进来返回全文，扫描页与嵌入图混排页自动触发视觉 OCR。
 *
 * <p>使用方式与原 Tika 门面一致：{@code DocumentTextExtractor.extract(path)}。
 * 支持格式：pdf、doc、docx、txt、md、常见图片（jpg/png/bmp/tiff）。
 * 引擎为 PDFBox（PDF）+ POI（Office）+ 百炼 qwen3.5-ocr（视觉 OCR），不依赖 Tika。</p>
 */
public final class DocumentTextExtractor {

    private static final Set<String> IMAGE_FORMATS = Set.of("jpg", "jpeg", "png", "bmp", "tif", "tiff");

    private DocumentTextExtractor() {
    }

    public static String extract(Path file) throws Exception {
        if (!Files.isRegularFile(file)) {
            throw new IllegalArgumentException("文档文件不存在：" + file.toAbsolutePath());
        }
        String format = extension(file);
        if (IMAGE_FORMATS.contains(format)) {
            String mime = format.equals("jpg") ? "jpeg" : format;
            return QwenOcrClient.recognize(Files.readAllBytes(file), mime);
        }
        return switch (format) {
            case "pdf" -> PdfTextExtractor.extract(file);
            case "doc", "docx" -> OfficeTextExtractor.extract(file);
            case "md", "markdown" -> readMarkdown(file);
            case "txt" -> Files.readString(file);
            default -> throw new IllegalArgumentException(
                    "不支持的文档格式：" + file.getFileName() + "（支持 pdf/doc/docx/txt/md/图片）");
        };
    }

    private static String extension(Path file) {
        String name = file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    /** Markdown 用 commonmark 渲染为纯文本，去掉标记符号但保留文字与结构。 */
    private static String readMarkdown(Path file) throws Exception {
        Node document = Parser.builder().build().parse(Files.readString(file));
        return TextContentRenderer.builder().build().render(document).strip();
    }
}
