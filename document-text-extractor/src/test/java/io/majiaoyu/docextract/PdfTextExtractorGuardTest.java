package io.majiaoyu.docextract;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 提取器本地防护逻辑单测：审计目录名净化、渲染 DPI 降档、审计图过期清理，均不调 OCR。 */
class PdfTextExtractorGuardTest {

    @Test
    void sanitizesPathTraversalNames() {
        String backslash = PdfTextExtractor.sanitizeAuditDirName("..\\..\\x");
        String slash = PdfTextExtractor.sanitizeAuditDirName("../../etc");
        // 分隔符一律替换，结果不可能构成穿越路径
        assertFalse(backslash.contains("\\"));
        assertFalse(backslash.contains("/"));
        assertFalse(slash.contains("\\"));
        assertFalse(slash.contains("/"));
        assertFalse(Path.of("audit", backslash).normalize().startsWith(".."));
        assertFalse(Path.of("audit", slash).normalize().startsWith(".."));
    }

    @Test
    void pureDotNamesFallBackToUnknown() {
        assertEquals("unknown", PdfTextExtractor.sanitizeAuditDirName(".."));
        assertEquals("unknown", PdfTextExtractor.sanitizeAuditDirName("..."));
        assertEquals("unknown", PdfTextExtractor.sanitizeAuditDirName(""));
        // ".pdf" 这类无名文件 stripExtension 后是空串
        assertEquals("unknown", PdfTextExtractor.sanitizeAuditDirName("."));
    }

    /** Windows 对设备名（含带扩展名形式）保留，命中时前置下划线化解。 */
    @Test
    void windowsReservedDeviceNamesAreNeutralized() {
        assertEquals("_con", PdfTextExtractor.sanitizeAuditDirName("con"));
        assertEquals("_con.txt", PdfTextExtractor.sanitizeAuditDirName("con.txt"));
        assertEquals("_NUL", PdfTextExtractor.sanitizeAuditDirName("NUL"));
        assertEquals("_COM1", PdfTextExtractor.sanitizeAuditDirName("COM1"));
        // 普通名字不应被误伤
        assertEquals("constant", PdfTextExtractor.sanitizeAuditDirName("constant"));
    }

    @Test
    void keepsReadableNamesAndTruncatesLongOnes() {
        assertEquals("投标文件_v1.2", PdfTextExtractor.sanitizeAuditDirName("投标文件 v1.2"));
        assertEquals("a-b_c.d", PdfTextExtractor.sanitizeAuditDirName("a-b_c.d"));
        String longName = "长".repeat(200);
        assertEquals(PdfTextExtractor.AUDIT_NAME_MAX_LENGTH,
                PdfTextExtractor.sanitizeAuditDirName(longName).length());
    }

    @Test
    void normalPageKeepsRequestedDpi() throws Exception {
        try (PDDocument document = new PDDocument()) {
            // A4：612x792pt，300dpi 下约 840 万像素，在上限内不降档
            PDPage page = new PDPage(new PDRectangle(612, 792));
            document.addPage(page);
            assertEquals(300f, PdfTextExtractor.renderDpi(page, 300f));
            assertEquals(150f, PdfTextExtractor.renderDpi(page, 150f));
        }
    }

    @Test
    void oversizedPageScalesDpiDownToPixelCap() throws Exception {
        try (PDDocument document = new PDDocument()) {
            // 12000x12000pt：300dpi 需约 25 亿像素，应降档到下限 24dpi（此时恰在上限内）
            PDPage page = new PDPage(new PDRectangle(12000, 12000));
            document.addPage(page);
            assertEquals(24f, PdfTextExtractor.renderDpi(page, 300f));
        }
    }

    @Test
    void absurdPageIsRejected() throws Exception {
        try (PDDocument document = new PDDocument()) {
            // 20000x20000pt：降到下限以下仍超限，直接拒绝而不是渲染废图
            PDPage page = new PDPage(new PDRectangle(20000, 20000));
            document.addPage(page);
            assertThrows(IllegalArgumentException.class, () -> PdfTextExtractor.renderDpi(page, 300f));
        }
    }

    @Test
    void purgesOnlyExpiredAuditImages(@TempDir Path dir) throws IOException {
        Path fresh = dir.resolve("p1-s1.png");
        Path stale = dir.resolve("p2-s1.png");
        Files.writeString(fresh, "fresh");
        Files.writeString(stale, "stale");
        Files.setLastModifiedTime(stale, FileTime.fromMillis(System.currentTimeMillis() - 8L * 24 * 3600_000L));

        PdfTextExtractor.purgeExpiredAuditImages(dir, 7L * 24 * 3600_000L);

        assertFalse(Files.exists(stale));
        assertTrue(Files.exists(fresh));
    }

    @Test
    void zeroRetentionDisablesPurge(@TempDir Path dir) throws IOException {
        Path stale = dir.resolve("p1-s1.png");
        Files.writeString(stale, "stale");
        Files.setLastModifiedTime(stale, FileTime.fromMillis(0L));

        PdfTextExtractor.purgeExpiredAuditImages(dir, 0L);

        assertTrue(Files.exists(stale));
    }

    /** 印章行保守化：防伪码与 (N) 序号剥离，纯数字行丢弃，章名主体保留（宁可少做也别做错）。 */
    @Test
    void stripsUnreliableFragmentsFromSealLines() {
        // 真实误读样本：章名 + 序号 + 防伪码混排 → 只留章名
        assertEquals("山东农业大学 项目管理有限公司 合同专用章",
                PdfTextExtractor.stripUnreliableSealFragments(
                        "山东农业大学 项目管理有限公司 合同专用章 (2) 3701207662353"));
        // 纯防伪码行 → 剥离后为空，调用方按 <3 字符丢弃
        assertEquals("", PdfTextExtractor.stripUnreliableSealFragments("(2) 3701207662353"));
        // 8 位以下数字可能是机构名的一部分，不剥离；8 位以上按防伪码剥离
        assertEquals("编号360123", PdfTextExtractor.stripUnreliableSealFragments("编号360123"));
        assertEquals("证书编号", PdfTextExtractor.stripUnreliableSealFragments("证书编号12345678"));
        // 无数字的章名主体不变
        assertEquals("中华人民共和国国家知识产权局",
                PdfTextExtractor.stripUnreliableSealFragments("中华人民共和国国家知识产权局"));
    }
}
