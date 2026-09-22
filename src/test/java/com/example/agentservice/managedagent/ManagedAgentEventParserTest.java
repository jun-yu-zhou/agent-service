package com.example.agentservice.managedagent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.alibaba.dashscope.agentstudio.message.ContentBlock;
import com.alibaba.dashscope.agentstudio.message.Message;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class ManagedAgentEventParserTest {

    private static final Instant TURN_STARTED_AT = Instant.parse("2026-09-21T09:00:00Z");

    @Test
    void shouldCollectArtifactMarkedByAgent() {
        JsonObject artifact = new JsonObject();
        artifact.addProperty("path", "/mnt/session/outputs/招标文件初稿.md");
        artifact.addProperty("file_id", "file-1");
        JsonArray marked = new JsonArray();
        marked.add(artifact);
        JsonObject output = new JsonObject();
        output.add("marked", marked);
        JsonObject data = new JsonObject();
        data.addProperty("name", "mark_artifacts");
        data.addProperty("output", output.toString());
        ManagedAgentEventParser parser = new ManagedAgentEventParser(TURN_STARTED_AT);

        assertFalse(parser.accept(dataEvent("tool_call_output", data)));

        assertEquals(List.of(new ManagedAgentArtifact("file-1", "招标文件初稿.md")), parser.artifacts());
    }

    @Test
    void shouldIgnoreToolFailureAndOldTurnStatus() {
        ManagedAgentEventParser parser = new ManagedAgentEventParser(TURN_STARTED_AT);
        Message toolFailure = new Message();
        toolFailure.setType("tool_call_output");
        toolFailure.setIsError(true);

        assertFalse(parser.accept(toolFailure));
        assertFalse(parser.accept(statusEvent("idle", "end_turn", "2026-09-21T08:59:59Z")));
        assertFalse(new ManagedAgentEventParser(Instant.ofEpochMilli(1789977600000L))
                .accept(statusEvent("idle", "end_turn", "1789977599000")));
        assertTrue(parser.accept(statusEvent("idle", "end_turn", "2026-09-21T09:00:01Z")));
    }

    @Test
    void shouldRejectRuntimeErrorAndIncompleteStopReasons() {
        ManagedAgentEventParser parser = new ManagedAgentEventParser(TURN_STARTED_AT);
        JsonObject error = new JsonObject();
        error.addProperty("code", "agent_error");
        error.addProperty("message", "执行失败");
        JsonObject data = new JsonObject();
        data.add("error", error);

        IllegalStateException runtimeError = assertThrows(
                IllegalStateException.class, () -> parser.accept(dataEvent("error", data)));
        assertTrue(runtimeError.getMessage().contains("agent_error: 执行失败"));
        assertThrows(IllegalStateException.class, () -> parser.accept(
                statusEvent("idle", "requires_action", "2026-09-21T09:00:01Z")));
        assertThrows(IllegalStateException.class, () -> parser.accept(
                statusEvent("idle", "retries_exhausted", "2026-09-21T09:00:01Z")));
    }

    private Message dataEvent(String type, JsonObject data) {
        ContentBlock.DataContent content = new ContentBlock.DataContent();
        content.setData(data);
        Message event = new Message();
        event.setType(type);
        event.setContent(List.of(content));
        return event;
    }

    private Message statusEvent(String status, String stopReason, String createdAt) {
        JsonObject data = new JsonObject();
        data.addProperty("session_status", status);
        JsonObject reason = new JsonObject();
        reason.addProperty("type", stopReason);
        data.add("stop_reason", reason);
        Message event = dataEvent("session_status", data);
        event.setCreatedAt(createdAt);
        return event;
    }
}
