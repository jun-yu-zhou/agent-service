package com.example.agentservice.docextract;

import lombok.extern.slf4j.Slf4j;
import org.apache.poi.util.Units;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 该测试会调用收费的百炼模型，配置 API Key 后手动取消 Disabled 再运行。 */
@Slf4j
class DocumentTextExtractorIntegrationTest {

    @Test
    void extractsTextWithoutVisualOcr(@TempDir Path directory) throws Exception {
        Path file = directory.resolve("document.txt");
        Files.writeString(file, "通用文档提取");

        assertTrue(DocumentTextExtractor.extract(file).contains("通用文档提取"));
    }

    @Test
    void extractsTextFromPdf() throws Exception {
        String text = DocumentTextExtractor.extract(
                Path.of("src/main/resources/doc/等离子清洗机-国内合同CB10056SX2026000004-148.pdf"));

        log.info("Extracted text: {}", text);
        assertFalse(text.isBlank());
    }

    @Test
    void extractsTextFromMarkdown(@TempDir Path directory) throws Exception {
        Path file = directory.resolve("document.md");
        Files.writeString(file, "# 标题\n\n**粗体**内容\n");

        String text = DocumentTextExtractor.extract(file);

        assertTrue(text.contains("标题"));
        assertTrue(text.contains("粗体"));
        assertFalse(text.contains("**"), "Markdown 应渲染为纯文本，去掉标记符号");
    }

    @Test
    @Disabled("调用百炼 OCR 模型会产生费用；需要时手动执行")
    void extractsTextFromScanPdf() throws Exception {
        String text = DocumentTextExtractor.extract(
                Path.of("src/main/resources/doc/等离子清洗机-国内合同CB10056SX2026000004-148.pdf"));

        log.info("Extracted text: {}", text);
        assertFalse(text.isBlank());
    }

    /**
     * IMM ExtractDocumentText 对该文件提取不到任何内容（2026-10-29 co-order 上传件，
     * 原始地址 oss.easyjcx.com/co-order/2026/10/29/3355e268...952.pdf），用于验证
     * qwen3.5-ocr 视觉 OCR 路线能否兜住 IMM 失效的扫描件。
     */
    @Test
    @Disabled("调用百炼 OCR 模型会产生费用；需要时手动执行")
    void extractsTextFromImmFailedPdf() throws Exception {
        String text = DocumentTextExtractor.extract(
                Path.of("src/test/resources/doc/3355e2688a354a48a7f2d79e21e11e371859673662510620952.pdf"));

        log.info("Extracted text: {}", text);
        assertFalse(text.isBlank());
    }

    /** 投标文件（扬州苏中专利事务所），含多枚清晰公章，验证印章定位与章文提取链路。 */
    @Test
    @Disabled("调用百炼 OCR 模型会产生费用；需要时手动执行")
    void extractsSealTextFromBidPdf() throws Exception {
        String text = DocumentTextExtractor.extract(
                Path.of("src/test/resources/doc/投标文件-扬州苏中专利事务所（普通合伙）1028992084228050159.pdf"));

        log.info("Extracted text: {}", text);
        assertTrue(text.contains("---- 印章文字（OCR）----"), "应检测到印章并追加印章 OCR 段");
    }

    @Test
    @Disabled("调用百炼 OCR 模型会产生费用；需要时手动执行")
    void extractsTextAndEmbeddedImagesFromDoc() throws Exception {
        String text = DocumentTextExtractor.extract(
                Path.of("src/test/resources/doc/定稿  扬州工业职业技术学院图书馆风冷热泵机组采购项目.doc"));

        log.info("Extracted text: {}", text);
        assertFalse(text.isBlank());
    }

    @Test
    @Disabled("调用百炼 OCR 模型会产生费用；需要时手动执行")
    void extractsEmbeddedImageOcrFromDocx(@TempDir Path directory) throws Exception {
        BufferedImage image = new BufferedImage(600, 600, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        graphics.setColor(Color.WHITE);
        graphics.fillRect(0, 0, 600, 600);
        graphics.setColor(Color.RED);
        graphics.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 48));
        graphics.drawString("测试签章文字", 100, 300);
        graphics.dispose();
        ByteArrayOutputStream png = new ByteArrayOutputStream();
        ImageIO.write(image, "png", png);

        Path docx = directory.resolve("embedded.docx");
        try (XWPFDocument document = new XWPFDocument()) {
            XWPFRun run = document.createParagraph().createRun();
            run.setText("正文内容");
            run.addPicture(new ByteArrayInputStream(png.toByteArray()), XWPFDocument.PICTURE_TYPE_PNG,
                    "seal.png", Units.toEMU(300), Units.toEMU(300));
            try (OutputStream output = Files.newOutputStream(docx)) {
                document.write(output);
            }
        }

        String text = DocumentTextExtractor.extract(docx);

        log.info("Extracted text: {}", text);
        assertTrue(text.contains("正文内容"), "正文文字应来自文本层提取");
        assertTrue(text.contains("内嵌图片文字"), "嵌入图应触发 OCR 并追加图片段落");
    }
}
