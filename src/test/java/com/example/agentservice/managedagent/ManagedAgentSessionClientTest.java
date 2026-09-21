package com.example.agentservice.managedagent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.alibaba.dashscope.agentstudio.message.ContentBlock;
import com.alibaba.dashscope.agentstudio.message.Message;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class ManagedAgentSessionClientTest {

    @Test
    void shouldCollectMarkdownCreatedByMarkArtifacts() {
        JsonObject artifact = new JsonObject();
        artifact.addProperty("path", "/mnt/session/outputs/招标文件初稿.md");
        artifact.addProperty("file_id", "file-1");
        JsonArray marked = new JsonArray();
        marked.add(artifact);
        JsonObject output = new JsonObject();
        output.add("marked", marked);
        output.add("failed", new JsonArray());
        JsonObject data = new JsonObject();
        data.addProperty("name", "mark_artifacts");
        data.addProperty("output", output.toString());
        ContentBlock.DataContent content = new ContentBlock.DataContent();
        content.setData(data);
        Message event = new Message();
        event.setRole("tool");
        event.setType("tool_call_output");
        event.setContent(List.of(content));
        List<ManagedAgentTurn.ManagedAgentFile> files = new ArrayList<>();

        ManagedAgentSessionClient.collectResult(event, new StringBuilder(), files);

        assertEquals(1, files.size());
        assertEquals("file-1", files.get(0).fileId());
        assertEquals("招标文件初稿.md", files.get(0).fileName());
        assertNull(ManagedAgentSessionClient.runtimeError(event));
    }

    @Test
    void shouldAllowAgentToRecoverFromToolError() {
        Message event = new Message();
        event.setType("tool_call_output");
        event.setIsError(true);
        event.setMessage("工具执行失败");

        assertNull(ManagedAgentSessionClient.runtimeError(event));
    }

    @Test
    void shouldReadNestedRuntimeError() {
        JsonObject error = new JsonObject();
        error.addProperty("code", "agent_error");
        error.addProperty("message", "执行失败");
        JsonObject data = new JsonObject();
        data.add("error", error);
        ContentBlock.DataContent content = new ContentBlock.DataContent();
        content.setData(data);
        Message event = new Message();
        event.setType("error");
        event.setContent(List.of(content));

        assertEquals("agent_error: 执行失败", ManagedAgentSessionClient.runtimeError(event));
    }
}
