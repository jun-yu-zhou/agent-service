package com.example.agentservice;

import com.aliyun.imm20200930.Client;
import com.aliyun.imm20200930.models.CreateOfficeConversionTaskRequest;
import com.aliyun.imm20200930.models.CreateOfficeConversionTaskResponse;
import com.aliyun.imm20200930.models.CreateOfficeConversionTaskResponseBody;
import com.aliyun.imm20200930.models.GetTaskRequest;
import com.aliyun.imm20200930.models.GetTaskResponse;
import com.aliyun.imm20200930.models.GetTaskResponseBody;
import com.aliyun.oss.OSS;
import com.aliyun.oss.model.ListObjectsRequest;
import com.aliyun.oss.model.ObjectListing;
import com.aliyun.oss.model.OSSObjectSummary;
import com.aliyun.teautil.models.RuntimeOptions;
import com.example.agentservice.entity.ImmImagePage;
import com.example.agentservice.imm.request.DocumentToImgRequest;
import com.example.agentservice.imm.task.DocumentToImgTask;
import com.example.agentservice.service.impl.DocumentToImgServiceImpl;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.net.URL;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link DocumentToImgServiceImpl} 的离线单元测试：override 客户端工厂注入 mock，
 * 不访问真实 OSS/IMM。system property 在类级设置，覆盖 application-local.yml 的真实值。
 */
class DocumentToImgServiceImplTest {

    private static final String BUCKET = "unit-test-bucket";
    private static final String PROJECT = "unit-test-project";
    private static final String SIGNED_URL = "https://unit-test-bucket.oss.example/signed";

    private static String previousBucket;
    private static String previousProject;

    private Client immClient;
    private OSS ossClient;
    private DocumentToImgServiceImpl service;

    @BeforeAll
    static void setUpConfig() {
        previousBucket = System.getProperty("ALIYUN_OSS_BUCKET");
        previousProject = System.getProperty("ALIYUN_IMM_PROJECT");
        System.setProperty("ALIYUN_OSS_BUCKET", BUCKET);
        System.setProperty("ALIYUN_IMM_PROJECT", PROJECT);
    }

    @AfterAll
    static void restoreConfig() {
        if (previousBucket == null) {
            System.clearProperty("ALIYUN_OSS_BUCKET");
        } else {
            System.setProperty("ALIYUN_OSS_BUCKET", previousBucket);
        }
        if (previousProject == null) {
            System.clearProperty("ALIYUN_IMM_PROJECT");
        } else {
            System.setProperty("ALIYUN_IMM_PROJECT", previousProject);
        }
    }

    @BeforeEach
    void setUp() {
        immClient = mock(Client.class);
        ossClient = mock(OSS.class);
        service = new DocumentToImgServiceImpl() {
            @Override
            protected Client createImmClient() {
                return immClient;
            }

            @Override
            protected OSS createOssClient() {
                return ossClient;
            }
        };
    }

    private DocumentToImgRequest command() {
        return new DocumentToImgRequest(
                "oss://" + BUCKET + "/docs/招标文件.docx",
                "oss://" + BUCKET + "/imm-review/招标文件.docx/",
                "招标文件.docx");
    }

    // ---------- convertDocumentToImages ----------

    @Test
    void shouldSubmitPngConversionTaskAndReturnTaskContext() throws Exception {
        CreateOfficeConversionTaskResponse response = new CreateOfficeConversionTaskResponse();
        response.setBody(new CreateOfficeConversionTaskResponseBody().setTaskId("task-1"));
        when(immClient.createOfficeConversionTaskWithOptions(
                any(CreateOfficeConversionTaskRequest.class), any(RuntimeOptions.class)))
                .thenReturn(response);

        DocumentToImgTask task = service.convertDocumentToImages(command());

        ArgumentCaptor<CreateOfficeConversionTaskRequest> captor =
                ArgumentCaptor.forClass(CreateOfficeConversionTaskRequest.class);
        verify(immClient).createOfficeConversionTaskWithOptions(captor.capture(), any(RuntimeOptions.class));
        CreateOfficeConversionTaskRequest request = captor.getValue();
        assertThat(request.getProjectName()).isEqualTo(PROJECT);
        assertThat(request.getSourceURI()).isEqualTo("oss://" + BUCKET + "/docs/招标文件.docx");
        assertThat(request.getTargetType()).isEqualTo("png");
        assertThat(request.getTargetURIPrefix()).isEqualTo("oss://" + BUCKET + "/imm-review/招标文件.docx/");

        assertThat(task.taskId()).isEqualTo("task-1");
        assertThat(task.documentName()).isEqualTo("招标文件.docx");
        assertThat(task.outputPrefix()).isEqualTo("imm-review/招标文件.docx/");
    }

    @Test
    void shouldRejectNullCommand() {
        assertThatThrownBy(() -> service.convertDocumentToImages(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("PDF转图片请求不能为空");
    }

    @Test
    void shouldRejectBlankRequestFields() {
        DocumentToImgRequest blankSource = new DocumentToImgRequest(
                " ", "oss://" + BUCKET + "/out/", "a.docx");
        DocumentToImgRequest blankPrefix = new DocumentToImgRequest(
                "oss://" + BUCKET + "/a.docx", " ", "a.docx");
        DocumentToImgRequest blankName = new DocumentToImgRequest(
                "oss://" + BUCKET + "/a.docx", "oss://" + BUCKET + "/out/", " ");
        for (DocumentToImgRequest request : List.of(blankSource, blankPrefix, blankName)) {
            assertThatThrownBy(() -> service.convertDocumentToImages(request))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void shouldFailWhenResponseBodyMissing() throws Exception {
        when(immClient.createOfficeConversionTaskWithOptions(
                any(CreateOfficeConversionTaskRequest.class), any(RuntimeOptions.class)))
                .thenReturn(new CreateOfficeConversionTaskResponse());

        assertThatThrownBy(() -> service.convertDocumentToImages(command()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("IMM文档转图片未返回任务ID");
    }

    @Test
    void shouldFailWhenTaskIdNullOrBlank() throws Exception {
        CreateOfficeConversionTaskResponse nullId = new CreateOfficeConversionTaskResponse();
        nullId.setBody(new CreateOfficeConversionTaskResponseBody());
        CreateOfficeConversionTaskResponse blankId = new CreateOfficeConversionTaskResponse();
        blankId.setBody(new CreateOfficeConversionTaskResponseBody().setTaskId("  "));
        when(immClient.createOfficeConversionTaskWithOptions(
                any(CreateOfficeConversionTaskRequest.class), any(RuntimeOptions.class)))
                .thenReturn(nullId, blankId);

        for (int i = 0; i < 2; i++) {
            assertThatThrownBy(() -> service.convertDocumentToImages(command()))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("IMM文档转图片未返回任务ID");
        }
    }

    // ---------- getDocumentToImagesResult ----------

    @Test
    void shouldRejectInvalidTaskContext() {
        DocumentToImgTask blankTaskId = new DocumentToImgTask(" ", "a.docx", "imm-review/a.docx");
        DocumentToImgTask blankPrefix = new DocumentToImgTask("task-1", "a.docx", " ");
        for (DocumentToImgTask task : Arrays.asList(null, blankTaskId, blankPrefix)) {
            assertThatThrownBy(() -> service.getDocumentToImagesResult(task))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("IMM文档转图片任务信息不能为空");
        }
    }

    @Test
    void shouldFailWhenImmTaskFailed() throws Exception {
        when(immClient.getTaskWithOptions(any(GetTaskRequest.class), any(RuntimeOptions.class)))
                .thenReturn(taskResponse("Failed", "源文件格式不支持"));

        DocumentToImgTask task = new DocumentToImgTask("task-1", "a.docx", "imm-review/a.docx");
        assertThatThrownBy(() -> service.getDocumentToImagesResult(task))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("IMM任务失败")
                .hasMessageContaining("源文件格式不支持");
    }

    /**
     * 全链路 happy path：任务 Succeeded 后，从 OSS 拉取乱序输出并按页码排序。
     * waitForOutputs 需要连续 3 轮数量稳定（每轮 sleep 2 秒），本用例耗时约 6 秒。
     * waitForOutputs 的"输出为空等满 600 秒超时"分支无法在合理时间内触发，不在单测覆盖范围。
     */
    @Test
    void shouldReturnSortedPagesFromTaskOutputs() throws Exception {
        when(immClient.getTaskWithOptions(any(GetTaskRequest.class), any(RuntimeOptions.class)))
                .thenReturn(taskResponse("Succeeded", null));
        ObjectListing listing = mock(ObjectListing.class);
        when(listing.getObjectSummaries()).thenReturn(List.of(
                summary("imm-review/招标文件.docx/10.png"),
                summary("imm-review/招标文件.docx/1.png"),
                summary("imm-review/招标文件.docx/cover.png"),
                summary("imm-review/招标文件.docx/2.png"),
                summary("imm-review/招标文件.docx/说明.jpg")));
        when(listing.isTruncated()).thenReturn(false);
        when(ossClient.listObjects(any(ListObjectsRequest.class))).thenReturn(listing);
        when(ossClient.generatePresignedUrl(any(), any(), any()))
                .thenReturn(new URL(SIGNED_URL));

        List<ImmImagePage> pages = service.getDocumentToImagesResult(
                new DocumentToImgTask("task-1", "招标文件.docx", "imm-review/招标文件.docx"));

        assertThat(pages).extracting(ImmImagePage::page).containsExactly(1, 2, 10, 4);
        assertThat(pages).extracting(ImmImagePage::document)
                .containsOnly("招标文件.docx");
        assertThat(pages).extracting(ImmImagePage::url).containsOnly(SIGNED_URL);

        ArgumentCaptor<GetTaskRequest> captor = ArgumentCaptor.forClass(GetTaskRequest.class);
        verify(immClient).getTaskWithOptions(captor.capture(), any(RuntimeOptions.class));
        assertThat(captor.getValue().getProjectName()).isEqualTo(PROJECT);
        assertThat(captor.getValue().getTaskType()).isEqualTo("OfficeConversion");
        assertThat(captor.getValue().getTaskId()).isEqualTo("task-1");
    }

    @Test
    void shouldAcceptSuccessStatusVariant() throws Exception {
        when(immClient.getTaskWithOptions(any(GetTaskRequest.class), any(RuntimeOptions.class)))
                .thenReturn(taskResponse("Success", null));
        ObjectListing listing = mock(ObjectListing.class);
        when(listing.getObjectSummaries()).thenReturn(List.of(summary("imm-review/b/1.png")));
        when(listing.isTruncated()).thenReturn(false);
        when(ossClient.listObjects(any(ListObjectsRequest.class))).thenReturn(listing);
        when(ossClient.generatePresignedUrl(any(), any(), any()))
                .thenReturn(new URL(SIGNED_URL));

        List<ImmImagePage> pages = service.getDocumentToImagesResult(
                new DocumentToImgTask("task-2", "b.docx", "imm-review/b"));

        assertThat(pages).hasSize(1);
        assertThat(pages.get(0).page()).isEqualTo(1);
    }

    private GetTaskResponse taskResponse(String status, String message) {
        GetTaskResponse response = new GetTaskResponse();
        GetTaskResponseBody body = new GetTaskResponseBody();
        body.setStatus(status);
        body.setMessage(message);
        response.setBody(body);
        return response;
    }

    private OSSObjectSummary summary(String key) {
        OSSObjectSummary summary = new OSSObjectSummary();
        summary.setKey(key);
        return summary;
    }
}
