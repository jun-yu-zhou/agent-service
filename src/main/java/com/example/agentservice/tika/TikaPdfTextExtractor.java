package com.example.agentservice.tika;

import com.example.agentservice.config.AgentServiceConfig;
import org.apache.tika.io.TikaInputStream;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.parser.Parser;
import org.apache.tika.parser.pdf.OcrConfig;
import org.apache.tika.parser.pdf.PDFParser;
import org.apache.tika.parser.pdf.PDFParserConfig;
import org.apache.tika.parser.vlm.OpenAIVLMParser;
import org.apache.tika.parser.vlm.VLMOCRConfig;
import org.apache.tika.sax.BodyContentHandler;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 使用 Apache Tika 4.x 提取 PDF 文本，并由百炼 qwen-vl-max 补充识别文本稀少的扫描页。
 *
 * <p>PDFParser 先提取每一页的文本；当文本少于 10 个字符或未映射字符达到 10 个时，
 * {@link OcrConfig.Strategy#AUTO} 才会渲染该页并调用视觉 OCR。</p>
 */
public final class TikaPdfTextExtractor {

    private static final String QWEN_COMPATIBLE_BASE_URL =
            "https://dashscope.aliyuncs.com/compatible-mode";
    private static final String QWEN_VL_MAX_MODEL = "qwen-vl-max";

    private TikaPdfTextExtractor() {
    }

    public static String extract(Path pdf) throws Exception {
        if (!Files.isRegularFile(pdf)) {
            throw new IllegalArgumentException("PDF 文件不存在：" + pdf.toAbsolutePath());
        }

        OpenAIVLMParser visualOcrParser = new OpenAIVLMParser(qwenVlmConfig());
        visualOcrParser.initialize();
        if (!visualOcrParser.isServerAvailable()) {
            throw new IllegalStateException(
                    "百炼 qwen-vl-max 端点不可用，无法执行视觉 OCR；请检查 DASHSCOPE_API_KEY 和网络配置");
        }

        ParseContext context = new ParseContext();
        context.set(Parser.class, visualOcrParser);
        BodyContentHandler contentHandler = new BodyContentHandler(-1);
        try (TikaInputStream inputStream = TikaInputStream.get(pdf)) {
            new PDFParser(pdfParserConfig()).parse(inputStream, contentHandler, new Metadata(), context);
        }
        return contentHandler.toString();
    }

    private static VLMOCRConfig qwenVlmConfig() throws Exception {
        VLMOCRConfig config = new VLMOCRConfig();
        config.setBaseUrl(QWEN_COMPATIBLE_BASE_URL);
        config.setApiKey(AgentServiceConfig.dashScopeApiKey());
        config.setModel(QWEN_VL_MAX_MODEL);
        config.setPrompt("""
                请识别图片中的全部文字，并以 Markdown 返回。
                保留原有阅读顺序、标题、段落、列表和表格；不要描述图片，不要补充或猜测未显示的内容。
                """);
        config.setMaxTokens(8192);
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
