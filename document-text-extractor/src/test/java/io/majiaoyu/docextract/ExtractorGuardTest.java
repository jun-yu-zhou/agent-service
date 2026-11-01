package io.majiaoyu.docextract;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.CRC32;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 入口与 Office 提取的本地防护单测：文件大小上限、内嵌图解码像素预检，均不调 OCR。 */
class ExtractorGuardTest {

    @Test
    void fileSizeOverLimitIsRejected(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("big.txt");
        Files.write(file, new byte[2048]);

        assertThrows(IllegalArgumentException.class,
                () -> DocumentTextExtractor.checkFileSize(file, 1024));
    }

    @Test
    void fileSizeWithinLimitPasses(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("small.txt");
        Files.writeString(file, "内容");

        DocumentTextExtractor.checkFileSize(file, 1024);
        DocumentTextExtractor.checkFileSize(file, 0);
    }

    @Test
    void decodableSmallImagePassesDecodeLimit() {
        // 10x10 的 1x1 白点 PNG（真实可解码）
        byte[] png = pngWithSize(10, 10);
        assertFalse(OfficeTextExtractor.exceedsDecodeLimit(png, 1000));
    }

    @Test
    void headerOversizedImageIsRejectedWithoutDecoding() {
        // header 声明 500x500，实际数据缺失也能读出尺寸：像素上限 1000 时应直接拒绝
        byte[] png = pngWithSize(500, 500);
        assertTrue(OfficeTextExtractor.exceedsDecodeLimit(png, 1000));
        assertFalse(OfficeTextExtractor.exceedsDecodeLimit(png, 500L * 500));
    }

    @Test
    void nonImageBytesAreNotRejectedByDecodeLimit() {
        assertFalse(OfficeTextExtractor.exceedsDecodeLimit("不是图片".getBytes(StandardCharsets.UTF_8), 1000));
        assertFalse(OfficeTextExtractor.exceedsDecodeLimit(new byte[0], 1000));
        assertFalse(OfficeTextExtractor.exceedsDecodeLimit(null, 1000));
    }

    /** 手工构造仅含签名与 IHDR 的 PNG：尺寸声明写在 header，无需真实像素数据。 */
    private static byte[] pngWithSize(int width, int height) {
        byte[] signature = {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A};
        byte[] type = "IHDR".getBytes(StandardCharsets.US_ASCII);
        byte[] data = new byte[13];
        data[0] = (byte) (width >>> 24);
        data[1] = (byte) (width >>> 16);
        data[2] = (byte) (width >>> 8);
        data[3] = (byte) width;
        data[4] = (byte) (height >>> 24);
        data[5] = (byte) (height >>> 16);
        data[6] = (byte) (height >>> 8);
        data[7] = (byte) height;
        data[8] = 8;   // bit depth
        data[9] = 6;   // color type RGBA

        CRC32 crc = new CRC32();
        crc.update(type);
        crc.update(data);

        byte[] chunk = new byte[4 + 4 + 13 + 4];
        putInt(chunk, 0, 13);
        System.arraycopy(type, 0, chunk, 4, 4);
        System.arraycopy(data, 0, chunk, 8, 13);
        putInt(chunk, 21, (int) crc.getValue());

        byte[] png = new byte[signature.length + chunk.length];
        System.arraycopy(signature, 0, png, 0, signature.length);
        System.arraycopy(chunk, 0, png, signature.length, chunk.length);
        return png;
    }

    private static void putInt(byte[] target, int offset, int value) {
        target[offset] = (byte) (value >>> 24);
        target[offset + 1] = (byte) (value >>> 16);
        target[offset + 2] = (byte) (value >>> 8);
        target[offset + 3] = (byte) value;
    }
}
