package com.example.agentservice.service.impl;

import com.aliyun.imm20200930.Client;
import com.aliyun.imm20200930.models.CreateOfficeConversionTaskRequest;
import com.aliyun.imm20200930.models.CreateOfficeConversionTaskResponse;
import com.aliyun.imm20200930.models.ExtractDocumentTextRequest;
import com.aliyun.imm20200930.models.ExtractDocumentTextResponse;
import com.aliyun.imm20200930.models.GetTaskRequest;
import com.aliyun.imm20200930.models.GetTaskResponse;
import com.aliyun.oss.ClientBuilderConfiguration;
import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSClientBuilder;
import com.aliyun.oss.common.auth.DefaultCredentialProvider;
import com.aliyun.oss.common.comm.SignVersion;
import com.aliyun.oss.model.ListObjectsRequest;
import com.aliyun.oss.model.OSSObjectSummary;
import com.aliyun.teaopenapi.models.Config;
import com.aliyun.teautil.models.RuntimeOptions;
import com.example.agentservice.config.AgentServiceConfig;
import com.example.agentservice.entity.ImmImagePage;
import com.example.agentservice.service.ImmService;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class ImmServiceImpl implements ImmService {

    private static final String OUTPUT_PREFIX = "imm-review";
    private static final int OUTPUT_WAIT_SECONDS = 600;
    private static final Pattern PAGE_NUMBER_PATTERN = Pattern.compile("(\\d+)$");

    @Override
    public List<ImmImagePage> convertPdfsToImages(List<String> pdfUrls) throws Exception {
        validatePdfUrls(pdfUrls);
        OSS ossClient = createOssClient();
        Client immClient = createImmClient();
        try {
            List<ImmImagePage> pages = new ArrayList<>();
            for (String pdfUrl : pdfUrls) {
                pages.addAll(convertOnePdf(ossClient, immClient, pdfUrl));
            }
            return pages;
        } finally {
            ossClient.shutdown();
        }
    }

    @Override
    public String extractDocumentText(String wordOssUrl, String fileExtension) throws Exception {
        String sourceType = validateWordSource(wordOssUrl, fileExtension);
        String sourceUri = "oss://" + AgentServiceConfig.ossBucket() + "/" + objectKey(wordOssUrl);
        ExtractDocumentTextRequest request = new ExtractDocumentTextRequest()
                .setProjectName(AgentServiceConfig.immProjectName())
                .setSourceURI(sourceUri)
                .setSourceType(sourceType);
        ExtractDocumentTextResponse response = createImmClient().extractDocumentTextWithOptions(
                request, new RuntimeOptions());
        if (response.getBody() == null) {
            throw new IllegalStateException("IMM文档正文提取未返回响应内容: " + sourceUri);
        }
        return response.getBody().getDocumentText();
    }

    private List<ImmImagePage> convertOnePdf(OSS ossClient, Client immClient, String pdfUrl)
            throws Exception {
        String sourceKey = objectKey(pdfUrl);
        String documentName = documentName(sourceKey);
        String outputPrefix = OUTPUT_PREFIX + "/" + documentName + "/";
        String bucket = AgentServiceConfig.ossBucket();
        CreateOfficeConversionTaskRequest request = new CreateOfficeConversionTaskRequest()
                .setProjectName(AgentServiceConfig.immProjectName())
                .setSourceURI("oss://" + bucket + "/" + sourceKey)
                .setTargetType("png")
                .setTargetURIPrefix("oss://" + bucket + "/" + outputPrefix);
        CreateOfficeConversionTaskResponse task = immClient.createOfficeConversionTaskWithOptions(
                request, new RuntimeOptions());
        String taskId = task.getBody().getTaskId();
        System.out.println("已提交IMM转换任务: " + taskId + ", project="
                + AgentServiceConfig.immProjectName() + ", object=" + sourceKey);
        waitForTask(immClient, taskId);

        List<OSSObjectSummary> outputs = waitForOutputs(ossClient, outputPrefix);
        if (outputs.isEmpty()) {
            throw new IllegalStateException("OSS/IMM转换未生成图片: " + sourceKey);
        }
        outputs.sort(Comparator.comparingInt((OSSObjectSummary item) -> pageNumber(item.getKey()))
                .thenComparing(OSSObjectSummary::getKey));

        List<ImmImagePage> pages = new ArrayList<>();
        for (int index = 0; index < outputs.size(); index++) {
            OSSObjectSummary output = outputs.get(index);
            String imageUrl = ossClient.generatePresignedUrl(
                    bucket, output.getKey(), Date.from(Instant.now().plus(Duration.ofHours(2)))).toString();
            int page = pageNumber(output.getKey());
            if (page == Integer.MAX_VALUE) {
                page = index + 1;
            }
            pages.add(new ImmImagePage(documentName, page, imageUrl));
            System.out.println("图片OSS URL: " + imageUrl);
        }
        return pages;
    }

    private OSS createOssClient() {
        DefaultCredentialProvider credentialsProvider = new DefaultCredentialProvider(
                AgentServiceConfig.ossAccessKeyId(), AgentServiceConfig.ossAccessKeySecret());
        ClientBuilderConfiguration configuration = new ClientBuilderConfiguration();
        configuration.setSignatureVersion(SignVersion.V4);
        return OSSClientBuilder.create()
                .endpoint(AgentServiceConfig.ossEndpoint())
                .region(AgentServiceConfig.ossRegion())
                .credentialsProvider(credentialsProvider)
                .clientConfiguration(configuration)
                .build();
    }

    private Client createImmClient() throws Exception {
        Config config = new Config()
                .setAccessKeyId(AgentServiceConfig.ossAccessKeyId())
                .setAccessKeySecret(AgentServiceConfig.ossAccessKeySecret())
                .setRegionId(AgentServiceConfig.ossRegion())
                .setEndpoint(AgentServiceConfig.immEndpoint());
        return new Client(config);
    }

    private void waitForTask(Client immClient, String taskId) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(OUTPUT_WAIT_SECONDS).toNanos();
        while (System.nanoTime() < deadline) {
            GetTaskResponse response = immClient.getTaskWithOptions(
                    new GetTaskRequest()
                            .setProjectName(AgentServiceConfig.immProjectName())
                            .setTaskType("OfficeConversion")
                            .setTaskId(taskId),
                    new RuntimeOptions());
            var body = response.getBody();
            String status = body == null ? null : body.getStatus();
            System.out.println("IMM任务状态: " + status + ", progress="
                    + (body == null ? null : body.getProgress()));
            if ("Succeeded".equalsIgnoreCase(status) || "Success".equalsIgnoreCase(status)) {
                return;
            }
            if ("Failed".equalsIgnoreCase(status) || "Error".equalsIgnoreCase(status)) {
                throw new IllegalStateException("IMM转换失败: "
                        + (body == null ? "unknown" : body.getMessage()));
            }
            Thread.sleep(3000L);
        }
        throw new IllegalStateException("IMM转换任务超时: " + taskId);
    }

    private List<OSSObjectSummary> waitForOutputs(OSS ossClient, String prefix)
            throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(OUTPUT_WAIT_SECONDS).toNanos();
        int previousCount = 0;
        int stableRounds = 0;
        List<OSSObjectSummary> outputs = List.of();
        while (System.nanoTime() < deadline) {
            outputs = listOutputs(ossClient, prefix);
            if (!outputs.isEmpty() && outputs.size() == previousCount) {
                stableRounds++;
                if (stableRounds >= 3) {
                    return outputs;
                }
            } else {
                stableRounds = 0;
            }
            previousCount = outputs.size();
            Thread.sleep(2000L);
        }
        return outputs;
    }

    private List<OSSObjectSummary> listOutputs(OSS ossClient, String prefix) {
        List<OSSObjectSummary> result = new ArrayList<>();
        String marker = null;
        do {
            var page = ossClient.listObjects(new ListObjectsRequest(AgentServiceConfig.ossBucket())
                    .withPrefix(prefix).withMarker(marker));
            result.addAll(page.getObjectSummaries().stream()
                    .filter(item -> item.getKey().endsWith(".png"))
                    .toList());
            marker = page.isTruncated() ? page.getNextMarker() : null;
        } while (marker != null && !marker.isBlank());
        return result;
    }

    private void validatePdfUrls(List<String> urls) {
        if (urls == null || urls.isEmpty()) {
            throw new IllegalArgumentException("至少需要传入一个 PDF 文件地址");
        }
        for (String url : urls) {
            if (url == null || url.isBlank()) {
                throw new IllegalArgumentException("PDF 文件地址不能为空");
            }
            URI uri = URI.create(url);
            String scheme = uri.getScheme();
            String path = uri.getPath();
            boolean httpUrl = "http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme);
            if (!httpUrl || path == null || !path.toLowerCase().endsWith(".pdf")) {
                throw new IllegalArgumentException("仅支持 PDF 文件，非法文件地址: " + url);
            }
        }
    }

    private String validateWordSource(String url, String fileExtension) {
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("Word 文件地址不能为空");
        }
        URI uri = URI.create(url);
        String scheme = uri.getScheme();
        if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
            throw new IllegalArgumentException("Word 文件地址必须是 HTTP 或 HTTPS OSS URL: " + url);
        }
        if (fileExtension == null || fileExtension.isBlank()) {
            throw new IllegalArgumentException("Word 文件后缀不能为空");
        }
        String sourceType = fileExtension.startsWith(".")
                ? fileExtension.substring(1)
                : fileExtension;
        sourceType = sourceType.toLowerCase(Locale.ROOT);
        if (!"doc".equals(sourceType) && !"docx".equals(sourceType)) {
            throw new IllegalArgumentException("仅支持 doc 或 docx 文件后缀: " + fileExtension);
        }
        return sourceType;
    }

    private String objectKey(String url) {
        String path = URI.create(url).getRawPath();
        if (path == null || path.length() <= 1) {
            throw new IllegalArgumentException("OSS URL没有对象路径: " + url);
        }
        return URLDecoder.decode(path.substring(1).replace("+", "%2B"), StandardCharsets.UTF_8);
    }

    private int pageNumber(String key) {
        String fileName = key.substring(key.lastIndexOf('/') + 1);
        String stem = fileName.substring(0, fileName.lastIndexOf('.'));
        try {
            return Integer.parseInt(stem);
        } catch (RuntimeException ignored) {
            Matcher matcher = PAGE_NUMBER_PATTERN.matcher(stem);
            if (matcher.find()) {
                try {
                    return Integer.parseInt(matcher.group(1));
                } catch (NumberFormatException ignoredNumber) {
                    // Fall through to the stable OSS key order.
                }
            }
            return Integer.MAX_VALUE;
        }
    }

    private String documentName(String sourceKey) {
        return sourceKey.substring(sourceKey.lastIndexOf('/') + 1);
    }
}
