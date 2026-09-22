package com.example.agentservice.managedagent;

import com.alibaba.dashscope.agentstudio.AgentStudioClient;
import com.alibaba.dashscope.agentstudio.message.ClientEvents;
import com.alibaba.dashscope.agentstudio.message.ContentBlock;
import com.alibaba.dashscope.agentstudio.message.Message;
import com.alibaba.dashscope.agentstudio.model.AgentStudioFile;
import com.alibaba.dashscope.agentstudio.model.Session;
import com.alibaba.dashscope.agentstudio.param.SessionCreateParam;
import com.alibaba.dashscope.agentstudio.resource.AgentStudioEventStream;
import com.example.agentservice.config.AgentServiceConfig;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.ByteArrayInputStream;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestTemplate;

/** 百炼 Managed Agent 的业务调用封装。 */
@Component
@Slf4j
public class ManagedAgentSessionClient implements AutoCloseable {

    private static final Duration FILE_READY_TIMEOUT = Duration.ofMinutes(30);
    private static final Duration TURN_TIMEOUT = Duration.ofMinutes(30);

    private final String apiKey = AgentServiceConfig.dashScopeApiKey();

    private final AgentStudioClient client = AgentStudioClient.builder()
            .apiKey(apiKey)
            .workspace(ManagedAgentConstants.WORKSPACE_ID)
            .region(ManagedAgentConstants.REGION)
            .build();

    private final RestTemplate restTemplate = new RestTemplate();

    /** 文件审核通过后，按照官方示例在创建会话时一次性挂载全部输入文件。 */
    public String createSession(SessionFile... files) {
        waitForAvailable(List.of(files));
        List<Map<String, Object>> resources = List.of(files).stream()
                .map(file -> Map.<String, Object>of(
                        "type", "file",
                        "file_id", file.fileId(),
                        "mount_path", file.mountPath()))
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

    /** 上传用户模板，不在服务端限制模板格式。 */
    public String uploadFile(byte[] content, String fileName, String contentType) {
        AgentStudioFile file = client.files().upload(
                fileName(fileName), new ByteArrayInputStream(content), contentType);
        if (!StringUtils.hasText(file.getId())) {
            throw new IllegalStateException("Managed Agent 模板上传未返回文件标识");
        }
        log.info("Managed Agent 文件已上传，fileId={}，fileName={}，mimeType={}，size={}，status={}，requestId={}",
                file.getId(), file.getFilename(), file.getMimeType(), file.getSizeBytes(),
                file.getStatus(), file.getRequestId());
        return file.getId();
    }

    /** 发送一轮消息并等待 Agent 本轮结束。 */
    public ManagedAgentTurn sendMessage(String sessionId, String message) {
        Instant turnStartedAt = Instant.now().minusSeconds(5);
        StringBuilder text = new StringBuilder();
        List<ManagedAgentTurn.ManagedAgentFile> files = new ArrayList<>();
        int receivedEventCount = 0;
        // SDK 创建事件流时会立即发起 SSE 请求，并在内部队列缓存后续事件。
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
                String error = runtimeError(event);
                if (error != null) {
                    throw new IllegalStateException("Managed Agent 执行失败: " + error);
                }
                collectResult(event, text, files);
                if (isTurnFinished(event, turnStartedAt)) {
                    List<ManagedAgentTurn.ManagedAgentFile> resultFiles = distinctFiles(files);
                    log.info("Managed Agent 本轮执行完成，sessionId={}，receivedEventCount={}，fileCount={}",
                            sessionId, receivedEventCount, resultFiles.size());
                    return new ManagedAgentTurn(text.toString().trim(), resultFiles);
                }
            }
        }
        throw new IllegalStateException("Managed Agent 事件流在本轮完成前结束，sessionId="
                + sessionId + "，receivedEventCount=" + receivedEventCount);
    }

    private static List<ManagedAgentTurn.ManagedAgentFile> distinctFiles(
            List<ManagedAgentTurn.ManagedAgentFile> files) {
        Map<String, ManagedAgentTurn.ManagedAgentFile> distinct = new LinkedHashMap<>();
        for (ManagedAgentTurn.ManagedAgentFile file : files) {
            String key = StringUtils.hasText(file.fileId()) ? file.fileId() : file.fileName();
            distinct.putIfAbsent(key, file);
        }
        return List.copyOf(distinct.values());
    }

    /** 下载 Agent 输出文件，优先读取返回的内嵌内容。 */
    public byte[] downloadFile(ManagedAgentTurn.ManagedAgentFile file) {
        if (StringUtils.hasText(file.fileData())) {
            return Base64.getDecoder().decode(file.fileData());
        }
        if (!StringUtils.hasText(file.fileUrl())) {
            return downloadFile(file.fileId(), file.fileName());
        }
        ResponseEntity<byte[]> response = restTemplate.exchange(
                file.fileUrl(), HttpMethod.GET, null, byte[].class);
        if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
            throw new IllegalStateException("下载 Managed Agent 输出文件失败: " + file.fileName());
        }
        return response.getBody();
    }

    /** mark_artifacts 只返回文件标识时，通过文件内容接口下载二进制产物。 */
    private byte[] downloadFile(String fileId, String fileName) {
        if (!StringUtils.hasText(fileId)) {
            throw new IllegalStateException("Managed Agent 返回文件缺少下载信息: " + fileName);
        }
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(apiKey);
        ResponseEntity<byte[]> response = restTemplate.exchange(
                client.getBaseUrl() + "/files/" + fileId + "/content",
                HttpMethod.GET, new HttpEntity<>(headers), byte[].class);
        if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
            throw new IllegalStateException("下载 Managed Agent 输出文件失败: " + fileName);
        }
        return response.getBody();
    }

    @Override
    public void close() {
        client.close();
    }

    /** 所有文件共用一个截止时间，避免逐个等待造成超时时间叠加。 */
    private void waitForAvailable(List<SessionFile> files) {
        long deadline = System.nanoTime() + FILE_READY_TIMEOUT.toNanos();
        Set<String> available = new HashSet<>();
        Map<String, AgentStudioFile> latest = new java.util.HashMap<>();
        while (System.nanoTime() < deadline) {
            for (SessionFile file : files) {
                if (available.contains(file.fileId())) continue;
                AgentStudioFile metadata = client.files().retrieve(file.fileId());
                latest.put(file.fileId(), metadata);
                if ("available".equals(metadata.getStatus())) {
                    available.add(file.fileId());
                    log.info("Managed Agent 文件审核通过，fileId={}，purpose={}",
                            file.fileId(), file.purpose());
                }
                else if ("rejected".equals(metadata.getStatus())
                        || "type_rejected".equals(metadata.getStatus())) {
                    throw fileReviewException("未通过审核", file, metadata);
                }
            }
            if (available.size() == files.size()) return;
            waitOneSecond();
        }
        String details = files.stream()
                .filter(file -> !available.contains(file.fileId()))
                .map(file -> fileDescription(file, latest.get(file.fileId())))
                .collect(java.util.stream.Collectors.joining("；"));
        throw new IllegalStateException("等待 Managed Agent 文件审核超时：" + details);
    }

    private static IllegalStateException fileReviewException(
            String reason, SessionFile file, AgentStudioFile metadata) {
        return new IllegalStateException("Managed Agent 文件" + reason + "："
                + fileDescription(file, metadata));
    }

    private static String fileDescription(SessionFile file, AgentStudioFile metadata) {
        if (metadata == null) return file.purpose() + "，fileId=" + file.fileId();
        return file.purpose() + "，fileId=" + file.fileId()
                + "，fileName=" + metadata.getFilename()
                + "，mimeType=" + metadata.getMimeType()
                + "，size=" + metadata.getSizeBytes()
                + "，status=" + metadata.getStatus()
                + "，requestId=" + metadata.getRequestId();
    }

    static void collectResult(Message event, StringBuilder text,
            List<ManagedAgentTurn.ManagedAgentFile> files) {
        if (event.getContent() == null) return;
        for (ContentBlock block : event.getContent()) {
            if ("assistant".equals(event.getRole()) && block instanceof ContentBlock.Text value) {
                text.setLength(0);
                text.append(value.getText());
            }
            if ("assistant".equals(event.getRole()) && block instanceof ContentBlock.File value) {
                files.add(new ManagedAgentTurn.ManagedAgentFile(value.getFileId(), value.getFilename(),
                        value.getFileUrl(), value.getFileData()));
            }
            if ("tool_call_output".equals(event.getType())
                    && block instanceof ContentBlock.DataContent value) {
                collectMarkedArtifacts(value.getData(), files);
            }
        }
    }

    /** 解析 mark_artifacts 工具返回的可下载产物清单。 */
    private static void collectMarkedArtifacts(JsonObject data,
            List<ManagedAgentTurn.ManagedAgentFile> files) {
        if (data == null || !"mark_artifacts".equals(text(data, "name"))) return;
        String output = text(data, "output");
        if (!StringUtils.hasText(output)) return;
        JsonArray marked = JsonParser.parseString(output).getAsJsonObject().getAsJsonArray("marked");
        if (marked == null) return;
        for (var value : marked) {
            JsonObject artifact = value.getAsJsonObject();
            String path = text(artifact, "path");
            files.add(new ManagedAgentTurn.ManagedAgentFile(
                    text(artifact, "file_id"), outputFileName(path), null, null));
        }
    }

    /** 只接受本轮产生的 idle 状态，避免复用会话时把上一轮 end_turn 当成本轮结束。 */
    static boolean isTurnFinished(Message event, Instant turnStartedAt) {
        if (!"session_status".equals(event.getType()) || !belongsToTurn(event, turnStartedAt)) {
            return false;
        }
        String sessionStatus = sessionStatus(event);
        if ("terminated".equals(sessionStatus)) {
            throw new IllegalStateException("Managed Agent 会话已终止");
        }
        if (!"idle".equals(sessionStatus)) return false;
        Session.StopReason stopReason = event.getStopReason();
        if (stopReason == null) return false;
        if ("requires_action".equals(stopReason.getType())) {
            throw new IllegalStateException("Managed Agent 等待工具审批，无法继续执行");
        }
        if ("retries_exhausted".equals(stopReason.getType())) {
            throw new IllegalStateException("Managed Agent 本轮重试次数已耗尽");
        }
        return "end_turn".equals(stopReason.getType());
    }

    private static boolean belongsToTurn(Message event, Instant turnStartedAt) {
        if (!StringUtils.hasText(event.getCreatedAt())) return true;
        try {
            String createdAt = event.getCreatedAt();
            Instant eventTime = createdAt.chars().allMatch(Character::isDigit)
                    ? Instant.ofEpochMilli(Long.parseLong(createdAt))
                    : Instant.parse(createdAt);
            return !eventTime.isBefore(turnStartedAt);
        }
        catch (RuntimeException ignored) {
            return true;
        }
    }

    /** 仅 error 事件代表会话运行失败；工具局部失败会交给 Agent 自行恢复。 */
    static String runtimeError(Message event) {
        if (!"error".equals(event.getType())) return null;
        if (StringUtils.hasText(event.getMessage())) return event.getMessage();
        if (StringUtils.hasText(event.getCode())) return event.getCode();
        if (event.getContent() != null) {
            for (ContentBlock block : event.getContent()) {
                if (block instanceof ContentBlock.DataContent value) {
                    String detail = errorDetail(value.getData());
                    if (detail != null) return detail;
                }
            }
        }
        return "未知运行期错误";
    }

    private static String errorDetail(JsonObject data) {
        if (data == null) return null;
        JsonObject error = data.has("error") && data.get("error").isJsonObject()
                ? data.getAsJsonObject("error") : data;
        String message = text(error, "message");
        String code = text(error, "code");
        if (StringUtils.hasText(message) && StringUtils.hasText(code)) {
            return code + ": " + message;
        }
        return StringUtils.hasText(message) ? message : code;
    }

    /** session_status 的实际状态位于 data 内容块中，而不是 Message.status。 */
    private static String sessionStatus(Message event) {
        if (event.getContent() == null) return null;
        for (ContentBlock block : event.getContent()) {
            if (block instanceof ContentBlock.DataContent value
                    && value.getData() != null
                    && value.getData().has("session_status")) {
                return value.getData().get("session_status").getAsString();
            }
        }
        return null;
    }

    private static String text(JsonObject value, String name) {
        return value.has(name) && !value.get(name).isJsonNull()
                ? value.get(name).getAsString() : null;
    }

    private static String fileName(String value) {
        if (!StringUtils.hasText(value)) {
            return "template";
        }
        return value.replace('/', '_').replace('\\', '_');
    }

    private static String outputFileName(String value) {
        if (!StringUtils.hasText(value)) return "artifact";
        int separator = Math.max(value.lastIndexOf('/'), value.lastIndexOf('\\'));
        return separator < 0 ? value : value.substring(separator + 1);
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

    /** 创建会话时挂载的输入文件。mountPath 必须以 /uploads/ 开头。 */
    public record SessionFile(String fileId, String mountPath, String purpose) {
    }
}
