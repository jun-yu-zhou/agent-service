package com.example.agentservice.procurement.tender.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.agentservice.managedagent.ManagedAgentArtifact;
import com.example.agentservice.managedagent.ManagedAgentClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

class TenderDocumentAiServiceTest {

    @Test
    void shouldDownloadMarkedMarkdownArtifact() {
        ManagedAgentClient client = mock(ManagedAgentClient.class);
        ManagedAgentArtifact artifact = new ManagedAgentArtifact("file-1", "项目-招标文件初稿.md");
        when(client.sendMessage(anyString(), anyString())).thenReturn(List.of(artifact));
        when(client.downloadFile(artifact)).thenReturn("# 招标文件".getBytes(StandardCharsets.UTF_8));
        TenderDocumentAiService service = new TenderDocumentAiService(client, new ObjectMapper());

        String markdown = service.generateDraft(
                new TenderDocumentAiService.DraftSession("session-1", "/mnt/session/template.html"));

        assertEquals("# 招标文件", markdown);
    }
}
