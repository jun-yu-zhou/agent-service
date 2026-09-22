package com.example.agentservice.managedagent;

import com.alibaba.dashscope.agentstudio.message.ContentBlock;
import com.alibaba.dashscope.agentstudio.message.Message;
import com.alibaba.dashscope.agentstudio.model.Session;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.util.StringUtils;

/** 解析一轮 Managed Agent SSE 事件。 */
final class ManagedAgentEventParser {

    private final Instant turnStartedAt;
    private final List<ManagedAgentArtifact> artifacts = new ArrayList<>();

    ManagedAgentEventParser(Instant turnStartedAt) {
        this.turnStartedAt = turnStartedAt;
    }

    /** 收集产物并返回本轮是否正常结束。 */
    boolean accept(Message event) {
        String error = runtimeError(event);
        if (error != null) {
            throw new IllegalStateException("Managed Agent 执行失败: " + error);
        }
        collectArtifacts(event);
        return isTurnFinished(event);
    }

    List<ManagedAgentArtifact> artifacts() {
        return List.copyOf(artifacts);
    }

    /** 系统提示词约定所有交付文件都通过 mark_artifacts 返回。 */
    private void collectArtifacts(Message event) {
        if (!"tool_call_output".equals(event.getType()) || event.getContent() == null) return;
        for (ContentBlock block : event.getContent()) {
            if (!(block instanceof ContentBlock.DataContent content)) continue;
            JsonObject data = content.getData();
            if (data == null || !"mark_artifacts".equals(text(data, "name"))) continue;
            String output = text(data, "output");
            if (!StringUtils.hasText(output)) continue;
            JsonArray marked = JsonParser.parseString(output).getAsJsonObject().getAsJsonArray("marked");
            if (marked == null) continue;
            for (var value : marked) {
                JsonObject artifact = value.getAsJsonObject();
                artifacts.add(new ManagedAgentArtifact(
                        text(artifact, "file_id"), fileName(text(artifact, "path"))));
            }
        }
    }

    /** 只接受本轮产生的 idle 状态，避免复用会话时读到上一轮结束事件。 */
    private boolean isTurnFinished(Message event) {
        if (!"session_status".equals(event.getType()) || !belongsToTurn(event)) return false;
        String status = sessionStatus(event);
        if ("terminated".equals(status)) {
            throw new IllegalStateException("Managed Agent 会话已终止");
        }
        if (!"idle".equals(status)) return false;
        Session.StopReason reason = event.getStopReason();
        if (reason == null) return false;
        if ("requires_action".equals(reason.getType())) {
            throw new IllegalStateException("Managed Agent 等待工具审批，无法继续执行");
        }
        if ("retries_exhausted".equals(reason.getType())) {
            throw new IllegalStateException("Managed Agent 本轮重试次数已耗尽");
        }
        return "end_turn".equals(reason.getType());
    }

    private boolean belongsToTurn(Message event) {
        if (!StringUtils.hasText(event.getCreatedAt())) return true;
        try {
            String createdAt = event.getCreatedAt();
            Instant eventTime = createdAt.chars().allMatch(Character::isDigit)
                    ? Instant.ofEpochMilli(Long.parseLong(createdAt)) : Instant.parse(createdAt);
            return !eventTime.isBefore(turnStartedAt);
        }
        catch (RuntimeException ignored) {
            return true;
        }
    }

    /** 工具局部失败交给 Agent 处理，仅 error 事件结束本轮。 */
    private static String runtimeError(Message event) {
        if (!"error".equals(event.getType())) return null;
        if (StringUtils.hasText(event.getMessage())) return event.getMessage();
        if (StringUtils.hasText(event.getCode())) return event.getCode();
        if (event.getContent() != null) {
            for (ContentBlock block : event.getContent()) {
                if (block instanceof ContentBlock.DataContent content) {
                    String detail = errorDetail(content.getData());
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
        if (StringUtils.hasText(message) && StringUtils.hasText(code)) return code + ": " + message;
        return StringUtils.hasText(message) ? message : code;
    }

    private static String sessionStatus(Message event) {
        if (event.getContent() == null) return null;
        for (ContentBlock block : event.getContent()) {
            if (block instanceof ContentBlock.DataContent content
                    && content.getData() != null && content.getData().has("session_status")) {
                return content.getData().get("session_status").getAsString();
            }
        }
        return null;
    }

    private static String text(JsonObject value, String name) {
        return value.has(name) && !value.get(name).isJsonNull()
                ? value.get(name).getAsString() : null;
    }

    private static String fileName(String path) {
        if (!StringUtils.hasText(path)) return "artifact";
        int separator = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        return separator < 0 ? path : path.substring(separator + 1);
    }
}
