package io.majiaoyu.docextract;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** 远程拉取的本地单测：协议闸门与格式识别，不发网络请求。 */
class UrlFetchTest {

    @Test
    void rejectsNonHttpSchemes() {
        assertThrows(IllegalArgumentException.class,
                () -> UrlFetch.downloadToTempFile("ftp://host/file.pdf", 1000));
        assertThrows(IllegalArgumentException.class,
                () -> UrlFetch.downloadToTempFile("file:///etc/passwd", 1000));
    }

    @Test
    void detectsFormatFromUrlSuffix() {
        assertEquals("pdf", UrlFetch.detectFormat(
                "http://oss.example.com/co-order/2026/10/29/abc123.pdf?Expires=1&Signature=x", ""));
        assertEquals("jpg", UrlFetch.detectFormat("https://oss.example.com/img/JPEG-photo.jpeg", ""));
        assertEquals("docx", UrlFetch.detectFormat("https://oss.example.com/a.b/c.docx", ""));
    }

    @Test
    void fallsBackToContentTypeWhenSuffixUnknown() {
        assertEquals("pdf", UrlFetch.detectFormat(
                "http://oss.example.com/download/3355e268", "application/pdf;charset=utf-8"));
        assertEquals("png", UrlFetch.detectFormat("http://oss.example.com/get?id=1", "image/png"));
    }

    @Test
    void unknownSuffixAndContentTypeIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> UrlFetch.detectFormat("http://oss.example.com/file", "application/octet-stream"));
        assertThrows(IllegalArgumentException.class,
                () -> UrlFetch.detectFormat("http://oss.example.com/file", ""));
    }
}
