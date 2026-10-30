package com.example.agentservice.docextract;

import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * PDF 全文提取，逐页处理策略：
 *
 * <ul>
 *   <li>纯文本页：直接取文本层，不调 OCR；</li>
 *   <li>稀疏页（文本 &lt;10 字符或未映射字符 ≥10，阈值与原 Tika AUTO 策略一致）：整页渲染后视觉 OCR；</li>
 *   <li>文本+大幅嵌入图混排页：整页渲染后视觉 OCR，由模型按阅读顺序合并图文输出（原 Tika 方案会丢弃图片内容）。</li>
 * </ul>
 *
 * <p>所有页面（含纯文本页，矢量章不体现在嵌入图里）都会先做本地印章框检测（SealLocator）：
 * 检出候选的页对整页做一次"印章识读"调用，由模型定位并读出章文，逐行追加到该页文本之后。
 * 实验证明整页识读比逐章裁剪更准（环形文字需要整章视野）且调用更省；跨页同文印章去重，
 * 失败只告警跳过。检出框的裁剪图写入系统临时目录 seal-audit/ 供人工核对漏检误检。
 * 混排页整页 OCR 失败时兜底回退文本层；稀疏页没有文本可兜底，OCR 失败直接抛出。</p>
 */
@Slf4j
final class PdfTextExtractor {

    private static final int SPARSE_CHARS_PER_PAGE = 10;
    private static final int UNMAPPED_CHARS_PER_PAGE = 10;
    /** 整页 OCR 与印章识读的页面渲染分辨率，与原 Tika OcrConfig 的 300 DPI 一致。 */
    private static final float RENDER_DPI = 300f;
    /** 纯文本页仅做印章框检测，低分辨率渲染足够且更快。 */
    private static final float SEAL_DETECT_DPI = 150f;
    /** 嵌入图像素面积至少达到页面面积（72dpi 换算）的该比例才视为内容图，过滤图标、装饰线与水印。 */
    private static final double CONTENT_IMAGE_MIN_AREA_RATIO = 0.15;
    /** 嵌入图单边至少该像素数才参与面积判定，过滤细长线条图。 */
    private static final int CONTENT_IMAGE_MIN_EDGE = 64;
    /** 审计裁剪图最短边低于该值时放大，便于人眼核对。 */
    private static final int SEAL_AUDIT_MIN_SIDE = 700;
    /** 整页印章识读提示词：只读章文、逐枚一行；实验证明环形文字在整页视野下读得最准。 */
    private static final String SEAL_READ_PROMPT = """
            图中可能有一枚或多枚印章。请逐枚识别印章内的全部文字（环形排列的文字请按印章阅读习惯读出，
            注意每个字带旋转角度，仔细辨认形近字），每枚印章单独一行。
            不要描述图片，不要输出非印章内容。
            """;

    private PdfTextExtractor() {
    }

    static String extract(Path file) throws Exception {
        try (PDDocument document = Loader.loadPDF(file.toFile())) {
            PDFRenderer renderer = new PDFRenderer(document);
            String docName = stripExtension(file.getFileName().toString());
            Set<String> knownSealTexts = new HashSet<>();
            StringBuilder text = new StringBuilder();
            for (int pageIndex = 0; pageIndex < document.getNumberOfPages(); pageIndex++) {
                if (pageIndex > 0) {
                    text.append("\n\n");
                }
                text.append(pageText(document, renderer, pageIndex, docName, knownSealTexts));
            }
            return text.toString();
        }
    }

    private static String pageText(PDDocument document, PDFRenderer renderer, int pageIndex,
                                   String docName, Set<String> knownSealTexts) throws Exception {
        PDPage page = document.getPage(pageIndex);
        String textLayer = stripText(document, pageIndex).strip();
        boolean sparse = isSparse(textLayer);
        boolean hasContentImage = hasLargeEmbeddedImage(page);
        boolean needsFullOcr = sparse || hasContentImage;
        // 每页都渲染：纯文本页也可能有矢量绘制的章，只有看图才能发现
        BufferedImage render = renderPage(renderer, pageIndex,
                needsFullOcr ? RENDER_DPI : SEAL_DETECT_DPI);

        String text = textLayer;
        if (needsFullOcr) {
            try {
                text = QwenOcrClient.recognize(toPng(toGray(render)), "png");
            } catch (Exception exception) {
                if (sparse) {
                    throw exception;
                }
                log.warn("混排页整页 OCR 失败，回退文本层（page={}）", pageIndex + 1, exception);
            }
        }

        List<Rectangle> sealBoxes = SealLocator.locate(render);
        if (sealBoxes.isEmpty()) {
            return text;
        }
        // 检测渲染是 150dpi 的纯文本页，章文识读前重渲为 300dpi
        BufferedImage readImage = needsFullOcr ? render : renderPage(renderer, pageIndex, RENDER_DPI);
        writeSealAudit(docName, pageIndex, render, sealBoxes);
        return text + sealTexts(readImage, pageIndex, knownSealTexts, sealBoxes.size());
    }

    /** 对含章页做一次整页印章识读；跨页同文去重，返回以段头标记的逐行追加段，无有效结果返回空串。 */
    private static String sealTexts(BufferedImage page, int pageIndex,
                                    Set<String> knownSealTexts, int detectedCount) {
        String ocr;
        try {
            ocr = QwenOcrClient.recognize(toPng(page), "png", SEAL_READ_PROMPT).strip();
        } catch (Exception exception) {
            log.warn("整页印章识读失败，已跳过（page={}，检出候选 {} 处）", pageIndex + 1, detectedCount, exception);
            return "";
        }
        if (looksLikeNonSealNoise(ocr)) {
            log.warn("印章识读返回疑似误检内容，已跳过（page={}）：{}", pageIndex + 1, snippet(ocr));
            return "";
        }
        StringBuilder section = new StringBuilder();
        for (String line : ocr.split("\\r?\\n")) {
            String content = line.strip();
            String normalized = content.replaceAll("\\s+", "");
            if (!isPlausibleSealText(normalized) || isNoSealStatement(normalized)
                    || !knownSealTexts.add(normalized)) {
                continue;
            }
            if (section.isEmpty()) {
                section.append("\n\n---- 印章文字（OCR）----");
            }
            section.append("\n").append(content);
        }
        return section.toString();
    }

    /**
     * 行级卫生过滤：整页识读在排版复杂的页上会混入印刷正文与表格词。
     * 章文（含环文、防伪码）长度有限，超长的基本是段落串入，两字以内的是表格词。
     */
    private static boolean isPlausibleSealText(String normalized) {
        if (normalized.length() < 3 || normalized.length() > 60) {
            return false;
        }
        return !normalized.endsWith("：") && !normalized.endsWith(":");
    }

    /** 误检页上模型可能返回 rotate_rect JSON 或无印章声明，视为无效跳过。 */
    private static boolean looksLikeNonSealNoise(String ocr) {
        String lower = ocr.toLowerCase(java.util.Locale.ROOT);
        return lower.contains("rotate_rect") || lower.contains("```");
    }

    private static boolean isNoSealStatement(String normalized) {
        return normalized.contains("无印章") || normalized.contains("没有印章") || normalized.contains("未发现印章");
    }

    private static String snippet(String text) {
        String trimmed = text == null ? "" : text.strip();
        return trimmed.length() <= 200 ? trimmed : trimmed.substring(0, 200) + "...";
    }

    private static String stripText(PDDocument document, int pageIndex) throws Exception {
        PDFTextStripper stripper = new PDFTextStripper();
        stripper.setSortByPosition(true);
        stripper.setStartPage(pageIndex + 1);
        stripper.setEndPage(pageIndex + 1);
        return stripper.getText(document);
    }

    private static boolean isSparse(String textLayer) {
        String visible = textLayer.replaceAll("\\s+", "");
        if (visible.length() < SPARSE_CHARS_PER_PAGE) {
            return true;
        }
        long unmapped = textLayer.chars()
                .filter(c -> c == 0xFFFD || (c < 32 && c != '\n' && c != '\r' && c != '\t'))
                .count();
        return unmapped >= UNMAPPED_CHARS_PER_PAGE;
    }

    private static boolean hasLargeEmbeddedImage(PDPage page) throws Exception {
        if (page.getResources() == null) {
            return false;
        }
        PDRectangle box = page.getMediaBox();
        double pageArea = Math.abs(box.getWidth() * box.getHeight());
        for (var name : page.getResources().getXObjectNames()) {
            if (page.getResources().getXObject(name) instanceof PDImageXObject image) {
                if (isContentImage(image, pageArea)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean isContentImage(PDImageXObject image, double pageArea) {
        if (image.getWidth() < CONTENT_IMAGE_MIN_EDGE || image.getHeight() < CONTENT_IMAGE_MIN_EDGE) {
            return false;
        }
        return (double) image.getWidth() * image.getHeight() >= CONTENT_IMAGE_MIN_AREA_RATIO * pageArea;
    }

    private static BufferedImage renderPage(PDFRenderer renderer, int pageIndex, float dpi) throws Exception {
        return renderer.renderImage(pageIndex, dpi / 72f);
    }

    /** 审计用：检出框裁剪放大后落盘临时目录，供人工核对漏检/误检。 */
    private static void writeSealAudit(String docName, int pageIndex, BufferedImage render,
                                       List<Rectangle> boxes) {
        for (int i = 0; i < boxes.size(); i++) {
            try {
                Rectangle box = boxes.get(i);
                int pad = Math.max(box.width, box.height) / 10;
                int x = Math.max(0, box.x - pad);
                int y = Math.max(0, box.y - pad);
                int width = Math.min(render.getWidth() - x, box.width + pad * 2);
                int height = Math.min(render.getHeight() - y, box.height + pad * 2);
                BufferedImage crop = render.getSubimage(x, y, width, height);
                if (Math.max(width, height) < SEAL_AUDIT_MIN_SIDE) {
                    double scale = (double) SEAL_AUDIT_MIN_SIDE / Math.max(width, height);
                    BufferedImage enlarged = new BufferedImage((int) (width * scale),
                            (int) (height * scale), BufferedImage.TYPE_INT_RGB);
                    Graphics2D graphics = enlarged.createGraphics();
                    graphics.drawImage(crop, 0, 0, enlarged.getWidth(), enlarged.getHeight(), null);
                    graphics.dispose();
                    crop = enlarged;
                }
                Path dir = Path.of(System.getProperty("java.io.tmpdir"), "seal-audit", docName);
                Files.createDirectories(dir);
                ImageIO.write(crop, "png", dir.resolve("p" + (pageIndex + 1) + "-s" + (i + 1) + ".png").toFile());
            } catch (Exception exception) {
                log.warn("印章审计图写入失败（page={} seal={}）", pageIndex + 1, i + 1, exception);
            }
        }
    }

    /** 灰度化压缩整页 OCR 上传体积，章印文字在灰度下仍可读。 */
    private static BufferedImage toGray(BufferedImage rgb) {
        BufferedImage gray = new BufferedImage(rgb.getWidth(), rgb.getHeight(), BufferedImage.TYPE_BYTE_GRAY);
        Graphics2D graphics = gray.createGraphics();
        graphics.drawImage(rgb, 0, 0, null);
        graphics.dispose();
        return gray;
    }

    private static byte[] toPng(BufferedImage image) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(image, "png", output);
        return output.toByteArray();
    }

    private static String stripExtension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot < 0 ? fileName : fileName.substring(0, dot);
    }
}
