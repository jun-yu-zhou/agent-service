package com.example.agentservice.docextract;

import com.example.agentservice.config.DocextractProperties;
import io.majiaoyu.docextract.DocumentTextExtractor;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.util.Units;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.junit.jupiter.api.BeforeAll;
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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * docextract-sdk 集成测试，兼作 API 使用教程（反向 review 看这里就够了）。
 *
 * <h3>接入三步（宿主项目照抄）</h3>
 * <ol>
 *   <li>引依赖：io.majiaoyu:docextract-sdk（本仓多模块直接依赖，改 SDK 源码即改即生效）；</li>
 *   <li>注入配置：实现 {@link io.majiaoyu.docextract.OcrConfigProvider}（baseUrl/apiKey/model 三项），
 *       业务工程用 {@code @Component + @PostConstruct} 调
 *       {@code QwenOcrClient.setConfigProvider(...)}——本工程已封装为
 *       {@link DocextractProperties#install()}；纯 JUnit 无 Spring 容器，故在 {@code @BeforeAll} 手动调；</li>
 *   <li>调用：{@code DocumentTextExtractor.extract(Path)}，支持 pdf/doc/docx/txt/md/图片，
 *       扫描页/混排页/图片自动走视觉 OCR，印章自动识读并追加"---- 印章文字（OCR）----"段。</li>
 * </ol>
 *
 * <h3>三种调用姿势（重载）</h3>
 * <pre>{@code
 * DocumentTextExtractor.extract(path);                              // ① 全默认
 * DocumentTextExtractor.extract(path, ocrPrompt);                   // ② 覆盖整页 OCR 提示词
 * DocumentTextExtractor.extract(path, ocrPrompt, sealPrompt);       // ③ 再覆盖印章识读提示词
 * }</pre>
 * 提示词传 null 或空白即回落 SDK 默认；②③只影响 OCR 类页，文本层直读（txt/md/文本页）不受影响。
 *
 * <h3>成本标注</h3>
 * 免费 = 不触发任何 OCR 调用；轻付费 ≈ 1~2 次调用（约 0.02 元）；重付费 = 全文档逐页 OCR（约 0.01 元/页）。
 * 所有重付费用例均 {@code @Disabled}，需要时手动放开或用
 * {@code -Djunit.jupiter.conditions.deactivate=org.junit.*DisabledCondition} 强制执行。
 */
@Slf4j
class DocumentTextExtractorIntegrationTest {

    @BeforeAll
    static void installSdkConfig() {
        // 教学点①：配置注入。业务工程由 DocextractProperties 的 @PostConstruct 自动完成；
        // 测试无 Spring 容器，手动调用一次即可，等价于把 baseUrl/apiKey/model 三项交给 SDK。
        DocextractProperties.installNow();
    }

    // ------------------------------------------------------------------
    // 免费用例：文本层直读，不触发 OCR
    // ------------------------------------------------------------------

    /** ① 最小用法：txt 直读。调用即用，无任何配置负担。 */
    @Test
    void extract_txt_全默认_零OCR成本(@TempDir Path directory) throws Exception {
        Path file = directory.resolve("document.txt");
        Files.writeString(file, "通用文档提取");

        assertTrue(DocumentTextExtractor.extract(file).contains("通用文档提取"));
    }

    /** ① 提示词入参对非 OCR 格式是安全的：传了也只是被忽略，不影响直读结果。 */
    @Test
    void extract_txt_传提示词入参也安全(@TempDir Path directory) throws Exception {
        Path file = directory.resolve("document.txt");
        Files.writeString(file, "通用文档提取");

        assertTrue(DocumentTextExtractor.extract(file, "自定义提示词", "自定义印章提示词")
                .contains("通用文档提取"));
    }

    /** ① Markdown：渲染为纯文本（标记去除、文字保留），同样零成本。 */
    @Test
    void extract_markdown_渲染为纯文本(@TempDir Path directory) throws Exception {
        Path file = directory.resolve("document.md");
        Files.writeString(file, "# 标题\n\n**粗体**内容\n");

        String text = DocumentTextExtractor.extract(file);

        assertTrue(text.contains("标题"));
        assertTrue(text.contains("粗体"));
        assertFalse(text.contains("**"), "Markdown 应渲染为纯文本，去掉标记符号");
    }

    /** API 契约：不支持的格式抛 IllegalArgumentException，报错里带支持格式清单。 */
    @Test
    void extract_不支持的格式_报错带支持清单(@TempDir Path directory) {
        Path file = directory.resolve("archive.zip");
        assertThrows(IllegalArgumentException.class, () -> DocumentTextExtractor.extract(file));
    }

    // ------------------------------------------------------------------
    // 轻付费：PDF 文本层页为主，混排/含章页可能触发 1~2 次 OCR（约 0.02 元）
    // ------------------------------------------------------------------

    /**
     * ① 混合 PDF：文本层页直读；含扫描图/印章的页自动 OCR 并追加"印章文字"段。
     * 该合同含嵌入图页，会触发少量 OCR 调用。
     */
    @Test
    void extract_pdf_文本层与混排页混合处理() throws Exception {
        String text = DocumentTextExtractor.extract(
                Path.of("src/main/resources/doc/等离子清洗机-国内合同CB10056SX2026000004-148.pdf"));

        log.info("Extracted text: {}", text);
        assertFalse(text.isBlank());
    }

    // ------------------------------------------------------------------
    // 重付费（@Disabled）：整页 OCR 与印章链路实测，手动放开
    // ------------------------------------------------------------------

    /** ② 提示词覆盖演示：同一份扫描合同，换用"禁 LaTeX 公式"的自定义提示词。约 0.3 元。 */
    @Test
    @Disabled("调用百炼 OCR 模型会产生费用；需要时手动执行")
    void extract_scanPdf_自定义整页OCR提示词() throws Exception {
        String customPrompt = """
                请识别图片中的全部文字，并以 Markdown 返回。
                表格保留为 Markdown 表格；公式、化学式、单位一律用普通文本表示，禁止输出 LaTeX。
                不要描述图片，不要输出扫描页码。
                """;
        String text = DocumentTextExtractor.extract(
                Path.of("src/main/resources/doc/等离子清洗机-国内合同CB10056SX2026000004-148.pdf"),
                customPrompt);

        log.info("Extracted text: {}", text);
        assertFalse(text.isBlank());
    }

    /**
     * ① 全默认兜底演示：IMM 提不出的纯扫描件（27 页零文本层），
     * 本 SDK 逐页 OCR 全文提出。约 0.3 元、2 分钟。对照结论见 2026-10-31 实验。
     */
    @Test
    @Disabled("调用百炼 OCR 模型会产生费用；需要时手动执行")
    void extract_scanPdf_IMM失效件兜底() throws Exception {
        String text = DocumentTextExtractor.extract(
                Path.of("src/test/resources/doc/3355e2688a354a48a7f2d79e21e11e371859673662510620952.pdf"));

        log.info("Extracted text: {}", text);
        assertFalse(text.isBlank());
    }

    /**
     * ① 印章链路：投标文件含多枚公章，输出应带"---- 印章文字（OCR）----"段；
     * 章文截留痕图在 %TEMP%/seal-audit/<文档名>/。约 1.5 元、6 分钟。
     */
    @Test
    @Disabled("调用百炼 OCR 模型会产生费用；需要时手动执行")
    void extract_bidPdf_印章链路_全默认() throws Exception {
        String text = DocumentTextExtractor.extract(
                Path.of("src/test/resources/doc/投标文件-扬州苏中专利事务所（普通合伙）1028992084228050159.pdf"));

        log.info("Extracted text: {}", text);
        assertTrue(text.contains("---- 印章文字（OCR）----"), "应检测到印章并追加印章 OCR 段");
    }

    /**
     * ③ 印章提示词覆盖演示：第三个入参替换默认印章识读提示词（约 1.5 元、6 分钟）。
     * 自定义示例强调"逐字辨认环形文字"，用于压公司章城市名漂移（扬州/苏州/上海/郑州）。
     */
    @Test
    @Disabled("调用百炼 OCR 模型会产生费用；需要时手动执行")
    void extract_bidPdf_自定义印章识读提示词() throws Exception {
        String customSealPrompt = """
                图中可能有多枚印章。请逐枚识别印章环形排列的全部文字，每个字可能带旋转角度，
                务必逐字仔细辨认形近字（如扬/苏/杭、专利/律师），按印章阅读顺序输出。
                每枚印章单独一行；不要输出非印章内容。
                """;
        String text = DocumentTextExtractor.extract(
                Path.of("src/test/resources/doc/投标文件-扬州苏中专利事务所（普通合伙）1028992084228050159.pdf"),
                null,
                customSealPrompt);

        log.info("Extracted text: {}", text);
        assertTrue(text.contains("---- 印章文字（OCR）----"), "应检测到印章并追加印章 OCR 段");
    }

    /**
     * ① 前端 OSS URL 直接入参：下载 → 格式识别（Content-Type）→ 全能力解析 → 自动清理临时文件。
     * 该签名 URL 有效期至 2070；约 0.3 元、2 分钟。
     */
    @Test
    @Disabled("调用百炼 OCR 模型会产生费用；需要时手动执行")
    void extract_ossUrl_下载后全能力解析() throws Exception {
        String text = DocumentTextExtractor.extract(
                "http://oss.easyjcx.com/co-order/2026/10/29/3355e2688a354a48a7f2d79e21e11e371859673662510620952.pdf"
                        + "?Expires=317121699172&OSSAccessKeyId=LTAI5t7BU2scZhLpbwGCw7nZ"
                        + "&Signature=Ala6TVVa2HOwvCIhLnWBd5y%2BT6E%3D");

        log.info("Extracted text: {}", text);
        assertFalse(text.isBlank());
    }

    /** ① legacy .doc：HWPF 文字直提 + 内嵌图 OCR（该文件无内嵌图，实际零成本）。 */
    @Test
    @Disabled("调用百炼 OCR 模型会产生费用；需要时手动执行")
    void extract_doc_内嵌图OCR_全默认() throws Exception {
        String text = DocumentTextExtractor.extract(
                Path.of("src/test/resources/doc/定稿  扬州工业职业技术学院图书馆风冷热泵机组采购项目.doc"));

        log.info("Extracted text: {}", text);
        assertFalse(text.isBlank());
    }

    /**
     * ① docx 内嵌签章：正文走文本层，600px 合成签章图触发内嵌图 OCR，
     * 结果追加"---- 内嵌图片文字（OCR）----"段。约 0.01 元。
     */
    @Test
    @Disabled("调用百炼 OCR 模型会产生费用；需要时手动执行")
    void extract_docx_内嵌签章OCR_全默认(@TempDir Path directory) throws Exception {
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
