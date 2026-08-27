package com.example.agentservice.imm.support;

import com.aliyun.imm20200930.Client;
import com.aliyun.imm20200930.models.GetTaskRequest;
import com.aliyun.imm20200930.models.GetTaskResponse;
import com.aliyun.oss.ClientBuilderConfiguration;
import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSClientBuilder;
import com.aliyun.oss.common.auth.DefaultCredentialProvider;
import com.aliyun.oss.common.comm.SignVersion;
import com.example.agentservice.config.AgentServiceConfig;
import com.aliyun.teaopenapi.models.Config;
import com.aliyun.teautil.models.RuntimeOptions;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** IMM 服务实现共享的客户端、对象路径和任务处理逻辑。 */
public abstract class AbstractImmServiceSupport {

    protected static final int OUTPUT_WAIT_SECONDS = 600;

    protected OSS createOssClient() {
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

    protected Client createImmClient() throws Exception {
        Config config = new Config()
                .setAccessKeyId(AgentServiceConfig.ossAccessKeyId())
                .setAccessKeySecret(AgentServiceConfig.ossAccessKeySecret())
                .setRegionId(AgentServiceConfig.ossRegion())
                .setEndpoint(AgentServiceConfig.immEndpoint());
        return new Client(config);
    }

    protected void waitForTask(Client immClient, String taskId, String taskType) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(OUTPUT_WAIT_SECONDS).toNanos();
        while (System.nanoTime() < deadline) {
            GetTaskResponse response = immClient.getTaskWithOptions(
                    new GetTaskRequest()
                            .setProjectName(AgentServiceConfig.immProjectName())
                            .setTaskType(taskType)
                            .setTaskId(taskId),
                    new RuntimeOptions());
            var body = response.getBody();
            String status = body == null ? null : body.getStatus();
            System.out.println("IMM任务状态: " + status + ", type=" + taskType + ", progress="
                    + (body == null ? null : body.getProgress()));
            if ("Succeeded".equalsIgnoreCase(status) || "Success".equalsIgnoreCase(status)) {
                return;
            }
            if ("Failed".equalsIgnoreCase(status) || "Error".equalsIgnoreCase(status)) {
                throw new IllegalStateException("IMM任务失败: "
                        + (body == null ? "unknown" : body.getMessage()));
            }
            Thread.sleep(3000L);
        }
        throw new IllegalStateException("IMM转换任务超时: " + taskId);
    }

    protected String validateDocumentSource(String url, String fileExtension) {
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("文档地址不能为空");
        }
        if (!url.startsWith("oss://")) {
            throw new IllegalArgumentException("文档SourceURI必须是 OSS URI: " + url);
        }
        if (fileExtension == null || fileExtension.isBlank()) {
            throw new IllegalArgumentException("文档后缀不能为空");
        }
        String sourceType = fileExtension.startsWith(".")
                ? fileExtension.substring(1)
                : fileExtension;
        sourceType = sourceType.toLowerCase(Locale.ROOT);
        if (!Set.of("doc", "docx", "ppt", "pptx", "xls", "xlsx", "pdf", "txt")
                .contains(sourceType)) {
            throw new IllegalArgumentException("不支持的文档后缀: " + fileExtension);
        }
        return sourceType;
    }

    protected void validateSourceUris(List<String> sourceUris) {
        if (sourceUris == null || sourceUris.isEmpty()) {
            throw new IllegalArgumentException("至少需要传入一张图片地址");
        }
        if (sourceUris.size() > 10) {
            throw new IllegalArgumentException("图片拼接最多支持 10 张图片");
        }
        if (sourceUris.stream().anyMatch(uri -> uri == null || !uri.startsWith("oss://"))) {
            throw new IllegalArgumentException("图片SourceURI必须是 OSS URI");
        }
    }

}
