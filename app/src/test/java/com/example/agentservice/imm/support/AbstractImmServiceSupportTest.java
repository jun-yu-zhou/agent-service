package com.example.agentservice.imm.support;

import com.aliyun.oss.OSS;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.net.URL;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link AbstractImmServiceSupport} 校验与路径方法的离线单元测试，
 * 通过最小子类暴露 protected 方法。
 */
class AbstractImmServiceSupportTest {

    private static final String BUCKET = "unit-test-bucket";

    private static String previousBucket;

    private final SupportProbe probe = new SupportProbe();

    @BeforeAll
    static void setUpConfig() {
        previousBucket = System.getProperty("ALIYUN_OSS_BUCKET");
        System.setProperty("ALIYUN_OSS_BUCKET", BUCKET);
    }

    @AfterAll
    static void restoreConfig() {
        if (previousBucket == null) {
            System.clearProperty("ALIYUN_OSS_BUCKET");
        } else {
            System.setProperty("ALIYUN_OSS_BUCKET", previousBucket);
        }
    }

    // ---------- validateDocumentSource ----------

    @Test
    void shouldNormalizeSupportedDocumentExtensions() {
        List.of("doc", "docx", "ppt", "pptx", "xls", "xlsx", "pdf", "txt").forEach(extension ->
                assertThat(probe.validateDocumentSourcePublic("oss://" + BUCKET + "/a." + extension, extension))
                        .isEqualTo(extension));
        assertThat(probe.validateDocumentSourcePublic("oss://" + BUCKET + "/a.docx", ".docx"))
                .isEqualTo("docx");
        assertThat(probe.validateDocumentSourcePublic("oss://" + BUCKET + "/a.docx", "DOCX"))
                .isEqualTo("docx");
        assertThat(probe.validateDocumentSourcePublic("oss://" + BUCKET + "/a.pdf", ".PDF"))
                .isEqualTo("pdf");
    }

    @Test
    void shouldRejectBlankDocumentUrl() {
        assertThatThrownBy(() -> probe.validateDocumentSourcePublic(null, "pdf"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("文档地址不能为空");
        assertThatThrownBy(() -> probe.validateDocumentSourcePublic(" ", "pdf"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("文档地址不能为空");
        assertThatThrownBy(() -> probe.validateDocumentSourcePublic("https://" + BUCKET + "/a.pdf", "pdf"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("文档SourceURI必须是 OSS URI");
    }

    @Test
    void shouldRejectBlankOrUnsupportedFileExtension() {
        assertThatThrownBy(() -> probe.validateDocumentSourcePublic("oss://" + BUCKET + "/a.pdf", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("文档后缀不能为空");
        assertThatThrownBy(() -> probe.validateDocumentSourcePublic("oss://" + BUCKET + "/a.pdf", " "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("文档后缀不能为空");
        assertThatThrownBy(() -> probe.validateDocumentSourcePublic("oss://" + BUCKET + "/a.exe", "exe"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不支持的文档后缀: exe");
    }

    // ---------- validateSourceUris ----------

    @Test
    void shouldAcceptUpToTenOssUris() {
        List<String> tenUris = IntStream.rangeClosed(1, 10)
                .mapToObj(index -> "oss://" + BUCKET + "/" + index + ".png")
                .collect(Collectors.toList());
        assertThatCode(() -> probe.validateSourceUrisPublic(tenUris))
                .doesNotThrowAnyException();
    }

    @Test
    void shouldRejectEmptyOrMissingSourceUris() {
        assertThatThrownBy(() -> probe.validateSourceUrisPublic(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("至少需要传入一张图片地址");
        assertThatThrownBy(() -> probe.validateSourceUrisPublic(List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("至少需要传入一张图片地址");
    }

    @Test
    void shouldRejectMoreThanTenSourceUris() {
        List<String> elevenUris = IntStream.rangeClosed(1, 11)
                .mapToObj(index -> "oss://" + BUCKET + "/" + index + ".png")
                .collect(Collectors.toList());
        assertThatThrownBy(() -> probe.validateSourceUrisPublic(elevenUris))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("图片拼接最多支持 10 张图片");
    }

    @Test
    void shouldRejectNonOssSourceUri() {
        assertThatThrownBy(() -> probe.validateSourceUrisPublic(
                List.of("https://" + BUCKET + "/1.png")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("图片SourceURI必须是 OSS URI");
        assertThatThrownBy(() -> probe.validateSourceUrisPublic(
                Arrays.asList("oss://" + BUCKET + "/1.png", null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("图片SourceURI必须是 OSS URI");
    }

    // ---------- 路径与预签名辅助 ----------

    @Test
    void shouldRoundTripOssUriHelpers() {
        String objectKey = "imm-review/a/2.png";
        assertThat(probe.ossUriPublic(objectKey)).isEqualTo("oss://" + BUCKET + "/" + objectKey);
        assertThat(probe.objectKeyFromOssUriPublic("oss://" + BUCKET + "/" + objectKey)).isEqualTo(objectKey);
        assertThat(probe.ossBucketPublic()).isEqualTo(BUCKET);
    }

    @Test
    void shouldGenerateTwoHourPresignedUrl() throws Exception {
        OSS ossClient = mock(OSS.class);
        when(ossClient.generatePresignedUrl(anyString(), anyString(), any(Date.class)))
                .thenReturn(new URL("https://signed.example/a.png"));

        String url = probe.generatePresignedUrlPublic(ossClient, "imm-review/a/1.png");

        assertThat(url).isEqualTo("https://signed.example/a.png");
        ArgumentCaptor<Date> captor = ArgumentCaptor.forClass(Date.class);
        verify(ossClient).generatePresignedUrl(
                eq(BUCKET), eq("imm-review/a/1.png"), captor.capture());
        // 预签名有效期为 2 小时（计算耗时有毫秒级偏差，放宽到 1 分钟）。
        long deltaMillis = captor.getValue().toInstant().toEpochMilli() - System.currentTimeMillis();
        assertThat(deltaMillis).isBetween(2L * 3600_000 - 60_000, 2L * 3600_000 + 60_000);
    }

    /** 暴露被测 protected 方法，不含任何真实客户端构建逻辑。 */
    private static final class SupportProbe extends AbstractImmServiceSupport {

        String validateDocumentSourcePublic(String url, String fileExtension) {
            return validateDocumentSource(url, fileExtension);
        }

        void validateSourceUrisPublic(List<String> sourceUris) {
            validateSourceUris(sourceUris);
        }

        String ossUriPublic(String objectKey) {
            return ossUri(objectKey);
        }

        String objectKeyFromOssUriPublic(String uri) {
            return objectKeyFromOssUri(uri);
        }

        String ossBucketPublic() {
            return ossBucket();
        }

        String generatePresignedUrlPublic(OSS ossClient, String objectKey) {
            return generatePresignedUrl(ossClient, objectKey);
        }
    }
}
