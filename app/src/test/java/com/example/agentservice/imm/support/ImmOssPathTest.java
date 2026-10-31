package com.example.agentservice.imm.support;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** {@link ImmOssPath} 的离线单元测试。 */
class ImmOssPathTest {

    private static final String BUCKET = "unit-test-bucket";

    private static String previousBucket;

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

    @Test
    void shouldBuildUriWithBucketPrefix() {
        assertThat(ImmOssPath.prefix()).isEqualTo("oss://" + BUCKET + "/");
        assertThat(ImmOssPath.uri("imm-review/a/1.png"))
                .isEqualTo("oss://" + BUCKET + "/imm-review/a/1.png");
    }

    @Test
    void shouldRejectBlankObjectKey() {
        assertThatThrownBy(() -> ImmOssPath.uri(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("OSS 对象路径不能为空");
        assertThatThrownBy(() -> ImmOssPath.uri("  "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("OSS 对象路径不能为空");
    }

    @Test
    void shouldExtractObjectKeyFromUri() {
        assertThat(ImmOssPath.objectKey("oss://" + BUCKET + "/docs/文件.docx"))
                .isEqualTo("docs/文件.docx");
    }

    @Test
    void shouldRejectInvalidUri() {
        String prefix = "oss://" + BUCKET + "/";
        List<String> invalidUris = Arrays.asList(
                null,
                "",
                "https://" + BUCKET + "/a.png",
                "oss://other-bucket/a.png",
                prefix);
        for (String uri : invalidUris) {
            assertThatThrownBy(() -> ImmOssPath.objectKey(uri))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("OSS URI 不合法");
        }
    }

    @Test
    void shouldRoundTripObjectKeyAndUri() {
        String objectKey = "imm-review/招标文件.docx/10.png";
        assertThat(ImmOssPath.objectKey(ImmOssPath.uri(objectKey))).isEqualTo(objectKey);
    }
}
