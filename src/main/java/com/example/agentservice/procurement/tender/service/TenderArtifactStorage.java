package com.example.agentservice.procurement.tender.service;

import com.aliyun.oss.ClientBuilderConfiguration;
import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSClientBuilder;
import com.aliyun.oss.common.auth.DefaultCredentialProvider;
import com.aliyun.oss.common.comm.SignVersion;
import com.example.agentservice.config.AgentServiceConfig;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Locale;
import org.springframework.stereotype.Service;

/** 保存并读取 Managed Agent 生成的招标文件产物。 */
@Service
public class TenderArtifactStorage {

    private static final String PREFIX = "ai-tender/artifacts";

    /** 将同一正文版本的定稿和审核报告写入固定对象路径。 */
    public StoredArtifacts store(
            String taskId, int revision, TenderDocumentAiService.FinalizedArtifacts artifacts) {
        String directory = PREFIX + "/" + taskId + "/" + revision + "/";
        String documentKey = directory + "tender-document.docx";
        String reviewKey = directory + "review-report.docx";
        OSS client = createClient();
        try {
            put(client, documentKey, artifacts.documentContent());
            put(client, reviewKey, artifacts.reviewContent());
        }
        finally {
            client.shutdown();
        }
        return new StoredArtifacts(documentKey, reviewKey);
    }

    /** 按数据库中保存的对象路径读取文件内容。 */
    public byte[] read(String objectKey) throws IOException {
        OSS client = createClient();
        try (var object = client.getObject(AgentServiceConfig.ossBucket(), objectKey);
                var input = object.getObjectContent()) {
            return input.readAllBytes();
        }
        finally {
            client.shutdown();
        }
    }

    private void put(OSS client, String objectKey, byte[] content) {
        client.putObject(AgentServiceConfig.ossBucket(), objectKey, new ByteArrayInputStream(content));
    }

    private OSS createClient() {
        var credentials = new DefaultCredentialProvider(
                AgentServiceConfig.ossAccessKeyId(), AgentServiceConfig.ossAccessKeySecret());
        var configuration = new ClientBuilderConfiguration();
        configuration.setSignatureVersion(SignVersion.V4);
        if (!isAliyunEndpoint(AgentServiceConfig.ossEndpoint())) {
            // 自定义域名已经绑定 bucket，不能再由 SDK 拼接 bucket 子域名。
            configuration.setSupportCname(true);
        }
        return OSSClientBuilder.create()
                .endpoint(AgentServiceConfig.ossEndpoint())
                .region(AgentServiceConfig.ossRegion())
                .credentialsProvider(credentials)
                .clientConfiguration(configuration)
                .build();
    }

    private boolean isAliyunEndpoint(String endpoint) {
        String value = endpoint == null ? "" : endpoint.toLowerCase(Locale.ROOT);
        return value.contains(".aliyuncs.com") || value.contains(".aliyun-inc.com");
    }

    /** 当前审核阶段写入 OSS 的两个对象路径。 */
    public record StoredArtifacts(String documentObjectKey, String reviewObjectKey) {
    }
}
