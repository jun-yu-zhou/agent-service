package com.example.agentservice;

import com.example.agentservice.imm.gateway.ImmGateway;
import com.example.agentservice.imm.request.DetectImageTextsRequest;
import com.example.agentservice.imm.request.DocumentToImgRequest;
import com.example.agentservice.imm.request.ExtractDocumentTextRequest;
import com.example.agentservice.imm.request.SpliceImagesRequest;
import com.example.agentservice.imm.task.DocumentToImgTask;
import com.example.agentservice.service.DocumentToImgService;
import com.example.agentservice.service.detectImageTextsService;
import com.example.agentservice.service.extractDocumentTextService;
import com.example.agentservice.service.spliceImagesService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link ImmGateway} 的离线单元测试：四个下游 service 全部 mock，
 * 验证 URL 到 oss:// URI 的装配与入参校验逻辑。
 */
class ImmGatewayTest {

    private static final String BUCKET = "unit-test-bucket";

    private static String previousBucket;

    private extractDocumentTextService extractDocumentTextService;
    private spliceImagesService spliceImagesService;
    private detectImageTextsService detectImageTextsService;
    private DocumentToImgService documentToImgService;
    private ImmGateway gateway;

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

    @BeforeEach
    void setUp() {
        extractDocumentTextService = mock(extractDocumentTextService.class);
        spliceImagesService = mock(spliceImagesService.class);
        detectImageTextsService = mock(detectImageTextsService.class);
        documentToImgService = mock(DocumentToImgService.class);
        gateway = new ImmGateway(
                extractDocumentTextService, spliceImagesService,
                detectImageTextsService, documentToImgService);
    }

    // ---------- convertDocumentToImages ----------

    @Test
    void shouldAssembleOssUrisFromHttpUrl() throws Exception {
        DocumentToImgTask expected = new DocumentToImgTask("task-1", "招标文件.docx", "imm-review/招标文件.docx");
        when(documentToImgService.convertDocumentToImages(any(DocumentToImgRequest.class)))
                .thenReturn(expected);

        DocumentToImgTask task = gateway.convertDocumentToImages(
                "https://" + BUCKET + ".oss-cn-beijing.aliyuncs.com/docs/%E6%8B%9B%E6%A0%87%E6%96%87%E4%BB%B6.docx");

        ArgumentCaptor<DocumentToImgRequest> captor = ArgumentCaptor.forClass(DocumentToImgRequest.class);
        verify(documentToImgService).convertDocumentToImages(captor.capture());
        DocumentToImgRequest request = captor.getValue();
        assertThat(request.sourceUri()).isEqualTo("oss://" + BUCKET + "/docs/招标文件.docx");
        assertThat(request.documentName()).isEqualTo("招标文件.docx");
        assertThat(request.targetUriPrefix()).isEqualTo("oss://" + BUCKET + "/imm-review/招标文件.docx/");
        assertThat(task).isSameAs(expected);
    }

    @Test
    void shouldPreservePlusSignInObjectKey() throws Exception {
        when(documentToImgService.convertDocumentToImages(any(DocumentToImgRequest.class)))
                .thenReturn(new DocumentToImgTask("task-1", "a+b.docx", "imm-review/a+b.docx"));

        gateway.convertDocumentToImages("https://" + BUCKET + ".oss.example/a+b.docx");

        ArgumentCaptor<DocumentToImgRequest> captor = ArgumentCaptor.forClass(DocumentToImgRequest.class);
        verify(documentToImgService).convertDocumentToImages(captor.capture());
        assertThat(captor.getValue().sourceUri()).isEqualTo("oss://" + BUCKET + "/a+b.docx");
    }

    @Test
    void shouldRejectInvalidDocumentUrl() {
        String prefix = "https://" + BUCKET + ".oss.example";
        List<String> invalidUrls = Arrays.asList(
                "ftp://" + BUCKET + "/a.docx",
                prefix,
                prefix + "/");
        for (String url : invalidUrls) {
            assertThatThrownBy(() -> gateway.convertDocumentToImages(url))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("文件地址必须是带对象路径的 HTTP 或 HTTPS URL");
        }
        for (String blankUrl : Arrays.asList(null, "  ")) {
            assertThatThrownBy(() -> gateway.convertDocumentToImages(blankUrl))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("OSS 文件地址不能为空");
        }
    }

    // ---------- extractDocumentText ----------

    @Test
    void shouldInferFileExtensionFromUrl() throws Exception {
        when(extractDocumentTextService.extractDocumentText(any(ExtractDocumentTextRequest.class)))
                .thenReturn("正文");

        gateway.extractDocumentText("https://" + BUCKET + ".oss.example/docs/公告.docx");

        ArgumentCaptor<ExtractDocumentTextRequest> captor =
                ArgumentCaptor.forClass(ExtractDocumentTextRequest.class);
        verify(extractDocumentTextService).extractDocumentText(captor.capture());
        assertThat(captor.getValue().sourceUri()).isEqualTo("oss://" + BUCKET + "/docs/公告.docx");
        assertThat(captor.getValue().fileExtension()).isEqualTo("docx");
    }

    @Test
    void shouldUseExplicitFileExtension() throws Exception {
        when(extractDocumentTextService.extractDocumentText(any(ExtractDocumentTextRequest.class)))
                .thenReturn("正文");

        gateway.extractDocumentText("https://" + BUCKET + ".oss.example/docs/文件", "pdf");

        ArgumentCaptor<ExtractDocumentTextRequest> captor =
                ArgumentCaptor.forClass(ExtractDocumentTextRequest.class);
        verify(extractDocumentTextService).extractDocumentText(captor.capture());
        assertThat(captor.getValue().fileExtension()).isEqualTo("pdf");
    }

    @Test
    void shouldRejectUrlWithoutFileExtension() {
        String prefix = "https://" + BUCKET + ".oss.example/docs/";
        List<String> invalidUrls = List.of(prefix + "无后缀", prefix + "以点结尾.");
        for (String url : invalidUrls) {
            assertThatThrownBy(() -> gateway.extractDocumentText(url))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("无法从OSS URL解析文件后缀");
        }
    }

    // ---------- splice & detect ----------

    @Test
    void shouldMapImageUrlsToSourceUrisForSplicing() throws Exception {
        when(spliceImagesService.spliceImagesHorizontally(any(SpliceImagesRequest.class)))
                .thenReturn("https://signed/horizontal.png");
        when(spliceImagesService.spliceImagesVertically(any(SpliceImagesRequest.class)))
                .thenReturn("https://signed/vertical.png");
        List<String> urls = List.of(
                "https://" + BUCKET + ".oss.example/1.png",
                "https://" + BUCKET + ".oss.example/2.png");

        assertThat(gateway.spliceImagesHorizontally(urls)).isEqualTo("https://signed/horizontal.png");
        assertThat(gateway.spliceImagesVertically(urls)).isEqualTo("https://signed/vertical.png");

        ArgumentCaptor<SpliceImagesRequest> captor = ArgumentCaptor.forClass(SpliceImagesRequest.class);
        verify(spliceImagesService).spliceImagesHorizontally(captor.capture());
        assertThat(captor.getValue().sourceUris()).containsExactly(
                "oss://" + BUCKET + "/1.png", "oss://" + BUCKET + "/2.png");
        verify(spliceImagesService).spliceImagesVertically(captor.capture());
        assertThat(captor.getValue().sourceUris()).containsExactly(
                "oss://" + BUCKET + "/1.png", "oss://" + BUCKET + "/2.png");
    }

    @Test
    void shouldMapImageUrlToSourceUriForDetection() throws Exception {
        when(detectImageTextsService.detectImageTexts(any(DetectImageTextsRequest.class)))
                .thenReturn("识别文本");

        gateway.detectImageTexts("https://" + BUCKET + ".oss.example/page-1.png");

        ArgumentCaptor<DetectImageTextsRequest> captor =
                ArgumentCaptor.forClass(DetectImageTextsRequest.class);
        verify(detectImageTextsService).detectImageTexts(captor.capture());
        assertThat(captor.getValue().sourceUri()).isEqualTo("oss://" + BUCKET + "/page-1.png");
    }
}
