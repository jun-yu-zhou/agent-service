package com.example.agentservice.service.impl;

import com.aliyun.imm20200930.Client;
import com.aliyun.imm20200930.models.CreateOfficeConversionTaskRequest;
import com.aliyun.imm20200930.models.CreateOfficeConversionTaskResponse;
import com.aliyun.oss.OSS;
import com.aliyun.oss.model.ListObjectsRequest;
import com.aliyun.oss.model.OSSObjectSummary;
import com.aliyun.teautil.models.RuntimeOptions;
import com.example.agentservice.config.AgentServiceConfig;
import com.example.agentservice.imm.support.AbstractImmServiceSupport;
import com.example.agentservice.imm.task.DocumentToImgTask;
import com.example.agentservice.entity.ImmImagePage;
import com.example.agentservice.imm.request.DocumentToImgRequest;
import com.example.agentservice.service.DocumentToImgService;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class DocumentToImgServiceImpl extends AbstractImmServiceSupport
        implements DocumentToImgService {

    private static final Pattern PAGE_NUMBER_PATTERN = Pattern.compile("(\\d+)$");

    @Override
    public DocumentToImgTask convertDocumentToImages(DocumentToImgRequest command) throws Exception {
        validate(command);
        Client immClient = createImmClient();
        CreateOfficeConversionTaskRequest request = new CreateOfficeConversionTaskRequest()
                .setProjectName(AgentServiceConfig.immProjectName())
                .setSourceURI(command.sourceUri())
                .setTargetType("png")
                .setTargetURIPrefix(command.targetUriPrefix());
        CreateOfficeConversionTaskResponse response = immClient.createOfficeConversionTaskWithOptions(
                request, new RuntimeOptions());
        if (response.getBody() == null || response.getBody().getTaskId() == null
                || response.getBody().getTaskId().isBlank()) {
            throw new IllegalStateException("IMM文档转图片未返回任务ID");
        }
        String taskId = response.getBody().getTaskId();
        System.out.println("已提交IMM转换任务: " + taskId + ", source=" + command.sourceUri());
        return new DocumentToImgTask(taskId, command.documentName(),
                objectKeyFromOssUri(command.targetUriPrefix()));
    }

    @Override
    public List<ImmImagePage> getDocumentToImagesResult(DocumentToImgTask task) throws Exception {
        if (task == null || task.taskId() == null || task.taskId().isBlank()
                || task.outputPrefix() == null || task.outputPrefix().isBlank()) {
            throw new IllegalArgumentException("IMM文档转图片任务信息不能为空");
        }
        OSS ossClient = createOssClient();
        try {
            waitForTask(createImmClient(), task.taskId(), "OfficeConversion");
            List<OSSObjectSummary> outputs = waitForOutputs(ossClient, task.outputPrefix());
            if (outputs.isEmpty()) {
                throw new IllegalStateException("OSS/IMM转换未生成图片: " + task.documentName());
            }
            outputs.sort(Comparator.comparingInt((OSSObjectSummary item) -> pageNumber(item.getKey()))
                    .thenComparing(OSSObjectSummary::getKey));
            List<ImmImagePage> pages = new ArrayList<>();
            for (int index = 0; index < outputs.size(); index++) {
                OSSObjectSummary output = outputs.get(index);
                String imageUrl = generatePresignedUrl(ossClient, output.getKey());
                int page = pageNumber(output.getKey());
                if (page == Integer.MAX_VALUE) {
                    page = index + 1;
                }
                pages.add(new ImmImagePage(task.documentName(), page, imageUrl));
            }
            return pages;
        } finally {
            ossClient.shutdown();
        }
    }

    private List<OSSObjectSummary> waitForOutputs(OSS ossClient, String prefix)
            throws InterruptedException {
        long deadline = System.nanoTime()
                + Duration.ofSeconds(OUTPUT_WAIT_SECONDS).toNanos();
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
            var page = ossClient.listObjects(new ListObjectsRequest(ossBucket())
                    .withPrefix(prefix).withMarker(marker));
            result.addAll(page.getObjectSummaries().stream()
                    .filter(item -> item.getKey().endsWith(".png"))
                    .toList());
            marker = page.isTruncated() ? page.getNextMarker() : null;
        } while (marker != null && !marker.isBlank());
        return result;
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

    private void validate(DocumentToImgRequest command) {
        if (command == null || isBlank(command.sourceUri()) || isBlank(command.targetUriPrefix())
                || isBlank(command.documentName())) {
            throw new IllegalArgumentException("PDF转图片请求不能为空");
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
