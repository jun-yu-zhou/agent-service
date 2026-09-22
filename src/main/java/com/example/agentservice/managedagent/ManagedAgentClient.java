package com.example.agentservice.managedagent;

import com.alibaba.dashscope.agentstudio.AgentStudioClient;
import com.alibaba.dashscope.agentstudio.message.ClientEvents;
import com.alibaba.dashscope.agentstudio.message.Message;
import com.alibaba.dashscope.agentstudio.model.AgentStudioFile;
import com.alibaba.dashscope.agentstudio.model.Session;
import com.alibaba.dashscope.agentstudio.param.SessionCreateParam;
import com.alibaba.dashscope.agentstudio.resource.AgentStudioEventStream;
import com.example.agentservice.config.AgentServiceConfig;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.io.ByteArrayInputStream;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestTemplate;

/** 百炼 Managed Agent 的文件、会话和事件流调用入口。 */
@Component
@Slf4j
public class ManagedAgentClient implements AutoCloseable {

    private static final Duration FILE_READY_TIMEOUT = Duration.ofMinutes(30);
    private static final Duration TURN_TIMEOUT = Duration.ofMinutes(30);

    private final String apiKey = AgentServiceConfig.dashScopeApiKey();
    private final AgentStudioClient client = AgentStudioClient.builder()
            .apiKey(apiKey)
            .workspace(ManagedAgentConstants.WORKSPACE_ID)
            .region(ManagedAgentConstants.REGION)
            .build();
    private final RestTemplate restTemplate = new RestTemplate();

    /** 等待输入文件可用后创建会话并完成挂载。 */
    public String createSession(SessionFile... files) {
        waitForAvailable(List.of(files));
        List<Map<String, Object>> resources = List.of(files).stream()
                .map(file -> Map.<String, Object>of(
                        "type", "file", "file_id", file.fileId(), "mount_path", file.mountPath()))
                .toList();
        Session session = client.sessions().create(SessionCreateParam.builder()
                .agent(ManagedAgentConstants.TENDER_AGENT_ID)
                .environmentId(ManagedAgentConstants.ENVIRONMENT_ID)
                .resources(resources)
                .build());
        if (!StringUtils.hasText(session.getId())) {
            throw new IllegalStateException("Managed Agent 创建会话未返回会话标识");
        }
        log.info("Managed Agent 会话已创建，sessionId={}，environmentId={}",
                session.getId(), ManagedAgentConstants.ENVIRONMENT_ID);
        return session.getId();
    }

    /** 上传文件，格式是否可用交由 Managed Agent 文件服务判断。 */
    public String uploadFile(byte[] content, String fileName, String contentType) {
        AgentStudioFile file = client.files().upload(
                safeFileName(fileName), new ByteArrayInputStream(content), contentType);
        if (!StringUtils.hasText(file.getId())) {
            throw new IllegalStateException("Managed Agent 文件上传未返回文件标识");
        }
        log.info("Managed Agent 文件已上传，fileId={}，fileName={}，status={}，requestId={}",
                file.getId(), file.getFilename(), file.getStatus(), file.getRequestId());
        return file.getId();
    }

    /** 先订阅 SSE，再发送消息并等待本轮正常结束。 */
    public List<ManagedAgentArtifact> sendMessage(String sessionId, String message) {
        ManagedAgentEventParser parser = new ManagedAgentEventParser(Instant.now().minusSeconds(5));
        int receivedEventCount = 0;
        try (AgentStudioEventStream stream = client.sessions().events()
                .stream(sessionId, TURN_TIMEOUT.toMillis())) {
            JsonObject accepted = client.sessions().events()
                    .send(sessionId, List.of(ClientEvents.userMessage(message)));
            JsonArray events = accepted.getAsJsonArray("data");
            if (events == null || events.isEmpty()) {
                throw new IllegalStateException("Managed Agent 未返回事件受理记录: " + accepted);
            }
            log.info("Managed Agent 事件已受理，sessionId={}，requestId={}，eventCount={}",
                    sessionId, text(accepted, "request_id"), events.size());
            for (Message event : stream) {
                receivedEventCount++;
                if (parser.accept(event)) {
                    List<ManagedAgentArtifact> artifacts = parser.artifacts();
                    log.info("Managed Agent 本轮执行完成，sessionId={}，receivedEventCount={}，fileCount={}",
                            sessionId, receivedEventCount, artifacts.size());
                    return artifacts;
                }
            }
        }
        throw new IllegalStateException("Managed Agent 事件流在本轮完成前结束，sessionId="
                + sessionId + "，receivedEventCount=" + receivedEventCount);
    }

    /** 官方 SDK 暂未提供文件正文接口，按文件标识下载产物。 */
    public byte[] downloadFile(ManagedAgentArtifact artifact) {
        if (!StringUtils.hasText(artifact.fileId())) {
            throw new IllegalStateException("Managed Agent 返回文件缺少文件标识: " + artifact.fileName());
        }
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(apiKey);
        ResponseEntity<byte[]> response = restTemplate.exchange(
                client.getBaseUrl() + "/files/" + artifact.fileId() + "/content",
                HttpMethod.GET, new HttpEntity<>(headers), byte[].class);
        if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
            throw new IllegalStateException("下载 Managed Agent 输出文件失败: " + artifact.fileName());
        }
        return response.getBody();
    }

    /** 所有输入文件共用一个截止时间，避免逐个等待叠加超时。 */
    private void waitForAvailable(List<SessionFile> files) {
        long deadline = System.nanoTime() + FILE_READY_TIMEOUT.toNanos();
        Set<String> pending = new HashSet<>();
        files.forEach(file -> pending.add(file.fileId()));
        while (!pending.isEmpty() && System.nanoTime() < deadline) {
            for (SessionFile file : files) {
                if (!pending.contains(file.fileId())) continue;
                AgentStudioFile metadata = client.files().retrieve(file.fileId());
                if ("available".equals(metadata.getStatus())) {
                    pending.remove(file.fileId());
                }
                else if ("rejected".equals(metadata.getStatus())
                        || "type_rejected".equals(metadata.getStatus())) {
                    throw new IllegalStateException("Managed Agent 文件未通过审核，fileId="
                            + file.fileId() + "，fileName=" + metadata.getFilename()
                            + "，status=" + metadata.getStatus());
                }
            }
            if (!pending.isEmpty()) waitOneSecond();
        }
        if (!pending.isEmpty()) {
            throw new IllegalStateException("等待 Managed Agent 文件审核超时，fileIds=" + pending);
        }
    }

    private static String safeFileName(String value) {
        return !StringUtils.hasText(value)
                ? "file" : value.replace('/', '_').replace('\\', '_');
    }

    private static String text(JsonObject value, String name) {
        return value.has(name) && !value.get(name).isJsonNull()
                ? value.get(name).getAsString() : null;
    }

    private static void waitOneSecond() {
        try {
            Thread.sleep(1000);
        }
        catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("等待 Managed Agent 文件审核被中断", exception);
        }
    }

    @Override
    public void close() {
        client.close();
    }

    /** 创建会话时挂载的输入文件，路径必须位于 uploads 目录。 */
    public record SessionFile(String fileId, String mountPath) {
    }
}
