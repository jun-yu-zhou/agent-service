package com.example.agentservice.service.impl;

import com.aliyun.imm20200930.Client;
import com.aliyun.imm20200930.models.CreateImageSplicingTaskRequest;
import com.aliyun.imm20200930.models.CreateImageSplicingTaskResponse;
import com.aliyun.teautil.models.RuntimeOptions;
import com.aliyun.oss.OSS;
import com.example.agentservice.config.AgentServiceConfig;
import com.example.agentservice.imm.request.SpliceImagesRequest;
import com.example.agentservice.imm.support.AbstractImmServiceSupport;
import com.example.agentservice.service.spliceImagesService;
import org.springframework.stereotype.Service;

@Service
public class spliceImagesServiceImpl extends AbstractImmServiceSupport
        implements spliceImagesService {

    private static final String IMAGE_SPLICING_PREFIX = "imm/image-splicing";

    @Override
    public String spliceImagesHorizontally(SpliceImagesRequest command) throws Exception {
        return spliceImages(command, "horizontal");
    }

    @Override
    public String spliceImagesVertically(SpliceImagesRequest command) throws Exception {
        return spliceImages(command, "vertical");
    }

    private String spliceImages(SpliceImagesRequest command, String direction) throws Exception {
        if (command == null) {
            throw new IllegalArgumentException("图片拼接请求不能为空");
        }
        validateSourceUris(command.sourceUris());
        OSS ossClient = createOssClient();
        try {
            Client immClient = createImmClient();
            String targetKey = IMAGE_SPLICING_PREFIX + "/" + java.util.UUID.randomUUID() + ".png";
            var sources = command.sourceUris().stream()
                    .map(sourceUri -> new CreateImageSplicingTaskRequest.CreateImageSplicingTaskRequestSources()
                            .setURI(sourceUri))
                    .toList();
            CreateImageSplicingTaskRequest request = new CreateImageSplicingTaskRequest()
                    .setProjectName(AgentServiceConfig.immProjectName())
                    .setSources(sources)
                    .setTargetURI(ossUri(targetKey))
                    .setImageFormat("png")
                    .setDirection(direction)
                    .setScaleType("fit")
                    .setPadding(0L)
                    .setMargin(0L)
                    .setBackgroundColor("#FFFFFF");
            CreateImageSplicingTaskResponse task = immClient.createImageSplicingTaskWithOptions(
                    request, new RuntimeOptions());
            if (task.getBody() == null || task.getBody().getTaskId() == null
                    || task.getBody().getTaskId().isBlank()) {
                throw new IllegalStateException("IMM图片拼接未返回任务ID");
            }
            String taskId = task.getBody().getTaskId();
            System.out.println("已提交IMM图片" + direction + "拼接任务: " + taskId
                    + ", target=" + targetKey);
            waitForTask(immClient, taskId, "ImageSplicing");
            return generatePresignedUrl(ossClient, targetKey);
        } finally {
            ossClient.shutdown();
        }
    }
}
