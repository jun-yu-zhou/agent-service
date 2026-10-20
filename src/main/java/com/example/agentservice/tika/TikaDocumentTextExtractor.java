package com.example.agentservice.tika;

import com.example.agentservice.config.AgentServiceConfig;
import org.apache.tika.io.TikaInputStream;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.TikaCoreProperties;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.parser.Parser;
import org.apache.tika.parser.pdf.OcrConfig;
import org.apache.tika.parser.pdf.PDFParserConfig;
import org.apache.tika.parser.vlm.OpenAIVLMParser;
import org.apache.tika.parser.vlm.VLMOCRConfig;
import org.apache.tika.sax.BodyContentHandler;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * 使用 Apache Tika 4.x 自动识别并提取常见文档格式；PDF 文本稀少页可由百炼视觉 OCR 模型补充 OCR。
 *
 * <p>支持格式取决于 Tika standard parser package。PDF 使用 AUTO 策略：当页面文本少于 10 个字符，
 * 或未映射字符达到 10 个时，才渲染页面并调用视觉 OCR。其他格式直接由 Tika 对应解析器提取。</p>
 */
public final class TikaDocumentTextExtractor {

    private TikaDocumentTextExtractor() {
    }

    public static String extract(Path file) throws Exception {
        if (!Files.isRegularFile(file)) {
            throw new IllegalArgumentException("文档文件不存在：" + file.toAbsolutePath());
        }

        AutoDetectParser parser = new AutoDetectParser();
        ParseContext context = new ParseContext();
        context.set(PDFParserConfig.class, pdfParserConfig());
        if (isPdf(file)) {
            OpenAIVLMParser visualOcrParser = new OpenAIVLMParser(qwenOcrConfig());
            visualOcrParser.initialize();
            if (!visualOcrParser.isServerAvailable()) {
                throw new IllegalStateException(
                        "百炼 OCR 端点不可用（模型 " + AgentServiceConfig.tikaOcrModel()
                                + "），无法执行 PDF 视觉 OCR；请检查 DASHSCOPE_API_KEY 和网络配置");
            }
            context.set(Parser.class, visualOcrParser);
        }

        BodyContentHandler contentHandler = new BodyContentHandler(-1);
        Metadata metadata = new Metadata();
        metadata.set(TikaCoreProperties.RESOURCE_NAME_KEY, file.getFileName().toString());
        try (TikaInputStream inputStream = TikaInputStream.get(file)) {
            parser.parse(inputStream, contentHandler, metadata, context);
        }
        return contentHandler.toString();
    }

    private static boolean isPdf(Path file) {
        return file.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".pdf");
    }

    private static VLMOCRConfig qwenOcrConfig() throws Exception {
        VLMOCRConfig config = new VLMOCRConfig();
        config.setBaseUrl(AgentServiceConfig.tikaOcrBaseUrl());
        config.setApiKey(AgentServiceConfig.dashScopeApiKey());
        config.setModel(AgentServiceConfig.tikaOcrModel());
        config.setPrompt("""
                请识别图片中的全部文字，并以 Markdown 返回。
                保留原有阅读顺序、标题、段落、列表和表格；不要描述图片，不要补充或猜测未显示的内容。
                """);
        config.setMaxTokens(16_384);
        config.setTimeoutMillis(300_000);
        config.setMaxImagePixels(100_000_000);
        return config;
    }

    private static PDFParserConfig pdfParserConfig() {
        OcrConfig.StrategyAuto auto = new OcrConfig.StrategyAuto();
        auto.setTotalCharsPerPage(10);
        auto.setUnmappedUnicodeCharsPerPage(10);

        OcrConfig ocr = new OcrConfig();
        ocr.setStrategy(OcrConfig.Strategy.AUTO);
        ocr.setStrategyAuto(auto);
        ocr.setDpi(300);
        ocr.setImageFormat(OcrConfig.ImageFormat.PNG);
        ocr.setImageType(OcrConfig.ImageType.GRAY);
        ocr.setRenderingStrategy(OcrConfig.RenderingStrategy.ALL);
        ocr.setMaxPagesToOcr(-1);

        PDFParserConfig config = new PDFParserConfig();
        config.setOcr(ocr);
        config.setSortByPosition(true);
        return config;
    }
}
