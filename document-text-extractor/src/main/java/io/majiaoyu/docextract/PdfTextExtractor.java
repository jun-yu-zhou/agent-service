package io.majiaoyu.docextract;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
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
 * 失败只告警跳过。检出框的裁剪图写入系统临时目录 seal-audit/ 便于人工核对漏检误检。
 * 混排页整页 OCR 失败时兜底回退文本层；稀疏页没有文本可兜底，OCR 失败直接抛出。
 * 两个提示词均可在门面入参覆盖，null/空白回落默认。</p>
 *
 * <p>不可信文档防护：渲染前按 MediaBox 换算像素面积，超上限自动降 DPI、降无可降的畸形超大页
 * 直接拒绝——恶意 PDF 一页即可申请数 GB 渲染内存打挂 JVM。审计裁剪图落盘的目录名取自外部
 * 文件名，必须净化防路径穿越；落盘行为提供开关与保留期清理，避免敏感章图无界滞留共享临时目录。</p>
 */
final class PdfTextExtractor {

    private static final Logger log = LoggerFactory.getLogger(PdfTextExtractor.class);

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
    /** 整页印章识读默认提示词：只读章文、逐枚一行；环形文字在整页视野下读得最准。 */
    static final String SEAL_READ_PROMPT = """
            图中可能有一枚或多枚印章。请逐枚识别印章内的全部文字（环形排列的文字请按印章阅读习惯读出，
            注意每个字带旋转角度，仔细辨认形近字），每枚印章单独一行。
            不要描述图片，不要输出非印章内容。
            """;

    /** 整页渲染的像素面积上限，防恶意超大 MediaBox 撑爆内存；系统属性可覆盖，0 表示不限制。 */
    static final long MAX_RENDER_PIXELS = Long.getLong("docextract.render.max-pixels", 16_000_000L);
    /** 渲染 DPI 降档下限：低于该分辨率的页面 OCR 已无意义，直接拒绝而不是渲染出一张废图。 */
    private static final float MIN_RENDER_DPI = 24f;
    /** 审计图写盘开关（默认开，保持既有行为）；系统属性可关闭。 */
    private static final String AUDIT_ENABLED_PROPERTY = "docextract.seal.audit.enabled";
    /** 审计图保留天数（默认 7，0 表示永不清理）。 */
    private static final String AUDIT_RETENTION_PROPERTY = "docextract.seal.audit.retention-days";
    /** 审计目录名最大长度，防超长文件名触发文件系统限制。 */
    static final int AUDIT_NAME_MAX_LENGTH = 80;

    private PdfTextExtractor() {
    }

    static String extract(Path file) throws Exception {
        return extract(file, null, null);
    }

    static String extract(Path file, String ocrPrompt, String sealPrompt) throws Exception {
        String effectiveOcrPrompt = effective(ocrPrompt, QwenOcrClient.DEFAULT_OCR_PROMPT);
        String effectiveSealPrompt = effective(sealPrompt, SEAL_READ_PROMPT);
        try (PDDocument document = Loader.loadPDF(file.toFile())) {
            PDFRenderer renderer = new PDFRenderer(document);
            String docName = stripExtension(file.getFileName().toString());
            Set<String> knownSealTexts = new HashSet<>();
            StringBuilder text = new StringBuilder();
            for (int pageIndex = 0; pageIndex < document.getNumberOfPages(); pageIndex++) {
                if (pageIndex > 0) {
                    text.append("\n\n");
                }
                text.append(pageText(document, renderer, pageIndex, docName, knownSealTexts,
                        effectiveOcrPrompt, effectiveSealPrompt));
            }
            return text.toString();
        }
    }

    private static String pageText(PDDocument document, PDFRenderer renderer, int pageIndex,
                                   String docName, Set<String> knownSealTexts,
                                   String ocrPrompt, String sealPrompt) throws Exception {
        PDPage page = document.getPage(pageIndex);
        String textLayer = stripText(document, pageIndex).strip();
        boolean sparse = isSparse(textLayer);
        boolean hasContentImage = hasLargeEmbeddedImage(page);
        boolean needsFullOcr = sparse || hasContentImage;
        // 每页都渲染：纯文本页也可能有矢量绘制的章，只有看图才能发现
        BufferedImage render = renderPage(page, renderer, pageIndex,
                needsFullOcr ? RENDER_DPI : SEAL_DETECT_DPI);

        String text = textLayer;
        if (needsFullOcr) {
            try {
                text = QwenOcrClient.recognize(toPng(toGray(render)), "png", ocrPrompt);
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
        BufferedImage readImage = needsFullOcr ? render : renderPage(page, renderer, pageIndex, RENDER_DPI);
        writeSealAudit(docName, pageIndex, render, sealBoxes);
        return text + sealTexts(readImage, pageIndex, knownSealTexts, sealBoxes.size(), sealPrompt);
    }

    /** 对含章页做一次整页印章识读；跨页同文去重，返回以段头标记的逐行追加段，无有效结果返回空串。 */
    private static String sealTexts(BufferedImage page, int pageIndex,
                                    Set<String> knownSealTexts, int detectedCount, String sealPrompt) {
        String ocr;
        try {
            ocr = QwenOcrClient.recognize(toPng(page), "png", sealPrompt).strip();
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

    /** 误检页上模型可能返回 rotate_rect JSON 或无印章声明，视为无效跳过。 */
    private static boolean looksLikeNonSealNoise(String ocr) {
        String lower = ocr.toLowerCase(java.util.Locale.ROOT);
        return lower.contains("rotate_rect") || lower.contains("```");
    }

    private static boolean isNoSealStatement(String normalized) {
        return normalized.contains("无印章") || normalized.contains("没有印章") || normalized.contains("未发现印章");
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

    /** 像素面积超上限时按比例下调 DPI 保证渲染内存受控；降无可降的畸形超大页面直接拒绝。 */
    static float renderDpi(PDPage page, float requestedDpi) {
        if (MAX_RENDER_PIXELS <= 0) {
            return requestedDpi;
        }
        PDRectangle box = page.getMediaBox();
        double pixels = (double) Math.abs(box.getWidth()) * Math.abs(box.getHeight())
                * (requestedDpi / 72f) * (requestedDpi / 72f);
        if (pixels <= MAX_RENDER_PIXELS) {
            return requestedDpi;
        }
        float scaled = (float) (requestedDpi * Math.sqrt(MAX_RENDER_PIXELS / pixels));
        if (scaled < MIN_RENDER_DPI) {
            throw new IllegalArgumentException("PDF 页面尺寸异常，拒绝渲染："
                    + box.getWidth() + "x" + box.getHeight() + "pt");
        }
        return scaled;
    }

    private static BufferedImage renderPage(PDPage page, PDFRenderer renderer, int pageIndex,
                                            float dpi) throws Exception {
        return renderer.renderImage(pageIndex, renderDpi(page, dpi) / 72f);
    }

    /** 审计用：检出框裁剪放大后落盘临时目录，供人工核对漏检/误检。docName 来自外部文件名，落盘前必须净化。 */
    private static void writeSealAudit(String docName, int pageIndex, BufferedImage render,
                                       List<Rectangle> boxes) {
        // 审计图承载文档敏感片段，写盘行为必须可关闭；默认开是保持历史行为不变
        if (!auditEnabled()) {
            return;
        }
        Path dir;
        try {
            // docName 来自外部文件名，可携带 ..\ 与 ../，不净化会目录穿越到临时目录之外
            dir = Path.of(System.getProperty("java.io.tmpdir"), "seal-audit", sanitizeAuditDirName(docName));
            Files.createDirectories(dir);
            // 无后台清理任务，借每次写盘的时机惰性清除超保留期的旧审计图
            purgeExpiredAuditImages(dir, auditRetentionMillis());
        } catch (Exception exception) {
            log.warn("印章审计目录准备失败（doc={}）", docName, exception);
            return;
        }
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
                ImageIO.write(crop, "png", dir.resolve("p" + (pageIndex + 1) + "-s" + (i + 1) + ".png").toFile());
            } catch (Exception exception) {
                log.warn("印章审计图写入失败（page={} seal={}）", pageIndex + 1, i + 1, exception);
            }
        }
    }

    private static boolean auditEnabled() {
        return Boolean.parseBoolean(System.getProperty(AUDIT_ENABLED_PROPERTY, "true"));
    }

    private static long auditRetentionMillis() {
        long days = Long.getLong(AUDIT_RETENTION_PROPERTY, 7);
        return days <= 0 ? 0 : days * 24L * 3_600_000L;
    }

    /**
     * 审计目录名净化：文件名是不可信外部输入，只保留字母/数字/点/横线/下划线（中英文均保留），
     * 其余（含路径分隔符、Windows 保留字符）替换为下划线；去尾部点（Windows 不允许），
     * 化解 ".." 类纯点号名，超长截断，空名回退 unknown。
     */
    static String sanitizeAuditDirName(String docName) {
        String cleaned = docName.replaceAll("[^\\p{L}\\p{N}._-]", "_");
        cleaned = cleaned.replaceAll("\\.+$", "");
        if (cleaned.isEmpty()) {
            return "unknown";
        }
        return cleaned.length() <= AUDIT_NAME_MAX_LENGTH
                ? cleaned : cleaned.substring(0, AUDIT_NAME_MAX_LENGTH);
    }

    /** 清理超过保留期的审计图，防临时目录无界累积；保留期 0 表示跳过。 */
    static void purgeExpiredAuditImages(Path dir, long retentionMillis) throws IOException {
        if (retentionMillis <= 0) {
            return;
        }
        long expiry = System.currentTimeMillis() - retentionMillis;
        try (var files = Files.list(dir)) {
            for (Path file : files.toList()) {
                try {
                    if (Files.getLastModifiedTime(file).toMillis() < expiry) {
                        Files.deleteIfExists(file);
                    }
                } catch (IOException exception) {
                    log.debug("审计图清理跳过：{}", file, exception);
                }
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

    private static String effective(String custom, String defaultPrompt) {
        return custom == null || custom.isBlank() ? defaultPrompt : custom;
    }

    private static String stripExtension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot < 0 ? fileName : fileName.substring(0, dot);
    }
}
