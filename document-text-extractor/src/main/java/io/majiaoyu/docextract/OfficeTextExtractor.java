package io.majiaoyu.docextract;

import org.apache.poi.hwpf.HWPFDocument;
import org.apache.poi.hwpf.extractor.WordExtractor;
import org.apache.poi.hwpf.model.PicturesTable;
import org.apache.poi.hwpf.usermodel.Picture;
import org.apache.poi.poifs.filesystem.POIFSFileSystem;
import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFPictureData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Transparency;
import java.awt.image.BufferedImage;
import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Iterator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Office 文档全文提取：docx 走 XWPF，legacy doc 走 HWPF。
 *
 * <p>文字内容之外，正文中内嵌的图片（扫描章、截图表格等）会按短边 ≥200 像素过滤、
 * 按内容去重后逐张送视觉 OCR（提示词可经门面入参覆盖），结果附在正文之后；
 * 页眉页脚中的图片不处理；单张图片 OCR 失败只记录告警并跳过，不影响正文提取。
 * WMF/EMF 等矢量图无法解码，同样跳过。</p>
 *
 * <p>不可信文档防护：内嵌图解码前先按图片 header 声明的像素面积预检（{@link #MAX_DECODE_PIXELS}），
 * 拦截解压炸弹——几 KB 的恶意 PNG/JPEG 可声明解出数十亿像素，直接 {@code ImageIO.read} 会在
 * 校验内容前就为完整位图分配内存。</p>
 */
final class OfficeTextExtractor {

    private static final Logger log = LoggerFactory.getLogger(OfficeTextExtractor.class);

    /** 内嵌图短边达到该像素数才视为内容图（签章、截图表格），过滤图标、logo 与装饰元素。 */
    private static final int CONTENT_IMAGE_MIN_EDGE = 200;
    private static final String IMAGE_SECTION_HEADER = "\n\n---- 内嵌图片文字（OCR）----";
    /** 内嵌图解码前的像素面积上限，防解压炸弹（小文件解出巨图）撑爆内存；系统属性可覆盖，0 表示不限制。 */
    static final long MAX_DECODE_PIXELS = Long.getLong("docextract.decode.max-pixels", 64_000_000L);

    private OfficeTextExtractor() {
    }

    static String extract(Path file) throws IOException {
        return extract(file, QwenOcrClient.DEFAULT_OCR_PROMPT);
    }

    static String extract(Path file, String ocrPrompt) throws IOException {
        try (InputStream input = new BufferedInputStream(Files.newInputStream(file))) {
            boolean docx = file.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".docx");
            return docx ? extractDocx(input, ocrPrompt) : extractDoc(input, ocrPrompt);
        }
    }

    private static String extractDocx(InputStream input, String ocrPrompt) throws IOException {
        try (XWPFDocument document = new XWPFDocument(input);
             XWPFWordExtractor extractor = new XWPFWordExtractor(document)) {
            String text = extractor.getText().strip();
            List<byte[]> images = document.getAllPictures().stream()
                    .map(XWPFPictureData::getData)
                    .toList();
            return text + ocrImages(images, ocrPrompt);
        }
    }

    private static String extractDoc(InputStream input, String ocrPrompt) throws IOException {
        try (HWPFDocument document = new HWPFDocument(new POIFSFileSystem(input));
             WordExtractor extractor = new WordExtractor(document)) {
            String text = extractor.getText().strip();
            List<byte[]> images = document.getPicturesTable().getAllPictures().stream()
                    .map(Picture::getContent)
                    .toList();
            return text + ocrImages(images, ocrPrompt);
        }
    }

    /** 过滤、去重后逐张 OCR 内嵌图片；返回带分隔标记的追加段落，没有可识别图时返回空串。 */
    private static String ocrImages(List<byte[]> images, String ocrPrompt) {
        // 按图片内容 MD5 指纹去重：骑缝章、同一签章在文档多处出现时只 OCR 一次，省重复调用费。
        Map<String, byte[]> uniqueImages = new LinkedHashMap<>();
        for (byte[] bytes : images) {
            if (bytes == null || bytes.length == 0) {
                continue;
            }
            uniqueImages.putIfAbsent(md5Fingerprint(bytes), bytes);
        }
        StringBuilder section = new StringBuilder();
        int index = 0;
        for (byte[] bytes : uniqueImages.values()) {
            index++;
            String ocrText = ocrSingleImage(bytes, index, ocrPrompt);
            if (ocrText != null) {
                if (section.isEmpty()) {
                    section.append(IMAGE_SECTION_HEADER);
                }
                section.append("\n\n[内嵌图片 ").append(index).append("]\n").append(ocrText);
            }
        }
        return section.toString();
    }

    /** 图片内容指纹（MD5 hex），供去重比对；MD5 由 JCA 规范保证必存在。 */
    private static String md5Fingerprint(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("MD5").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("JDK 缺少 MD5 实现", exception);
        }
    }

    /** 返回该图的 OCR 结果；图过小、超解码上限、无法解码或 OCR 失败时返回 null 表示跳过。 */
    private static String ocrSingleImage(byte[] bytes, int index, String ocrPrompt) {
        if (exceedsDecodeLimit(bytes, MAX_DECODE_PIXELS)) {
            log.warn("文档内嵌图片像素超限，跳过 OCR（第 {} 张，上限 {} 像素）", index, MAX_DECODE_PIXELS);
            return null;
        }
        BufferedImage image;
        try {
            image = ImageIO.read(new ByteArrayInputStream(bytes));
        } catch (IOException exception) {
            image = null;
        }
        if (image == null || Math.min(image.getWidth(), image.getHeight()) < CONTENT_IMAGE_MIN_EDGE) {
            return null;
        }
        try {
            return QwenOcrClient.recognize(toPngOnWhite(image), "png", ocrPrompt);
        } catch (Exception exception) {
            log.warn("文档内嵌图片 OCR 失败，已跳过（第 {} 张）", index, exception);
            return null;
        }
    }

    /** 解码前只读图片 header 拿尺寸做上限判定，避免为恶意图分配解码内存；header 读不出时交还 ImageIO 正常处理。 */
    static boolean exceedsDecodeLimit(byte[] bytes, long maxPixels) {
        if (maxPixels <= 0 || bytes == null || bytes.length == 0) {
            return false;
        }
        try (ImageInputStream input = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) {
                return false;
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(input);
                return (long) reader.getWidth(0) * reader.getHeight(0) > maxPixels;
            } finally {
                reader.dispose();
            }
        } catch (IOException | IndexOutOfBoundsException exception) {
            return false;
        }
    }

    /** 透明底图垫白后统一重编码为 PNG，避免透明区域被 OCR 模型误读为黑色背景。 */
    private static byte[] toPngOnWhite(BufferedImage image) throws IOException {
        BufferedImage flattened = image;
        if (image.getTransparency() != Transparency.OPAQUE) {
            flattened = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_RGB);
            Graphics2D graphics = flattened.createGraphics();
            graphics.drawImage(image, 0, 0, Color.WHITE, null);
            graphics.dispose();
        }
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(flattened, "png", output);
        return output.toByteArray();
    }
}
