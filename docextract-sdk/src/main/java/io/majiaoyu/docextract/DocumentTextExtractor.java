package io.majiaoyu.docextract;

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
 * <p>使用方式与原 Tika 门面一致。支持格式：pdf、doc、docx、txt、md、常见图片（jpg/png/bmp/tiff）。
 * 引擎为 PDFBox（PDF）+ POI（Office）+ 百炼 qwen3.5-ocr（视觉 OCR），不依赖 Tika 与 Spring。
 * 端点/模型/密钥经 {@link OcrConfigProvider} 注入，默认读系统属性与环境变量。</p>
 *
 * <p>两个提示词均可按调用覆盖：整页 OCR（扫描页/混排页/图片/内嵌图）与印章识读，
 * 传 null 或空白即回落默认值。</p>
 */
public final class DocumentTextExtractor {

    private static final Set<String> IMAGE_FORMATS = Set.of("jpg", "jpeg", "png", "bmp", "tif", "tiff");

    private DocumentTextExtractor() {
    }

    /** 全默认提示词提取全文。 */
    public static String extract(Path file) throws Exception {
        return extract(file, null, null);
    }

    /** 覆盖整页 OCR 提示词（扫描页/混排页/图片/内嵌图生效），印章识读仍用默认。 */
    public static String extract(Path file, String ocrPrompt) throws Exception {
        return extract(file, ocrPrompt, null);
    }

    /** 同时覆盖印章识读提示词；两者传 null 或空白均回落默认。 */
    public static String extract(Path file, String ocrPrompt, String sealPrompt) throws Exception {
        if (!Files.isRegularFile(file)) {
            throw new IllegalArgumentException("文档文件不存在：" + file.toAbsolutePath());
        }
        String effectiveOcrPrompt = effective(ocrPrompt, QwenOcrClient.DEFAULT_OCR_PROMPT);
        String format = extension(file);
        if (IMAGE_FORMATS.contains(format)) {
            String mime = format.equals("jpg") ? "jpeg" : format;
            return QwenOcrClient.recognize(Files.readAllBytes(file), mime, effectiveOcrPrompt);
        }
        return switch (format) {
            case "pdf" -> PdfTextExtractor.extract(file, effectiveOcrPrompt,
                    effective(sealPrompt, PdfTextExtractor.SEAL_READ_PROMPT));
            case "doc", "docx" -> OfficeTextExtractor.extract(file, effectiveOcrPrompt);
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

    private static String effective(String custom, String defaultPrompt) {
        return custom == null || custom.isBlank() ? defaultPrompt : custom;
    }

    /** Markdown 用 commonmark 渲染为纯文本，去掉标记符号但保留文字与结构。 */
    private static String readMarkdown(Path file) throws Exception {
        Node document = Parser.builder().build().parse(Files.readString(file));
        return TextContentRenderer.builder().build().render(document).strip();
    }
}
