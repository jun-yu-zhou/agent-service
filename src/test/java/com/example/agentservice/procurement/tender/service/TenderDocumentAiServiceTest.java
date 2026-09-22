package com.example.agentservice.procurement.tender.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.agentservice.managedagent.ManagedAgentArtifact;
import com.example.agentservice.managedagent.ManagedAgentClient;
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
        TenderDocumentAiService service = new TenderDocumentAiService(client);

        String markdown = service.generateDraft(
                new TenderDocumentAiService.DraftSession("session-1", "/mnt/session/template.html"));

        assertEquals("# 招标文件", markdown);
    }

    @Test
    void shouldDownloadFinalDocumentAndReviewReport() {
        ManagedAgentClient client = mock(ManagedAgentClient.class);
        ManagedAgentArtifact document = new ManagedAgentArtifact("file-1", "项目-招标文件定稿.docx");
        ManagedAgentArtifact report = new ManagedAgentArtifact("file-2", "项目-招标文件审核报告.docx");
        when(client.uploadFile(any(byte[].class), eq("招标文件人工定稿.md"), eq("text/markdown")))
                .thenReturn("finalized-file");
        when(client.sendMessage(anyString(), anyString(),
                any(ManagedAgentClient.MessageFile.class))).thenReturn(List.of(document, report));
        when(client.downloadFile(document)).thenReturn("document".getBytes(StandardCharsets.UTF_8));
        when(client.downloadFile(report)).thenReturn("report".getBytes(StandardCharsets.UTF_8));
        TenderDocumentAiService service = new TenderDocumentAiService(client);

        TenderDocumentAiService.FinalizedArtifacts result =
                service.reviewFinalizedDocument("session-1", "# 人工定稿");

        assertEquals("项目-招标文件定稿.docx", result.documentFileName());
        assertEquals("项目-招标文件审核报告.docx", result.reviewFileName());
        verify(client).uploadFile(any(byte[].class), eq("招标文件人工定稿.md"), eq("text/markdown"));
        verify(client).sendMessage(eq("session-1"), anyString(),
                any(ManagedAgentClient.MessageFile.class));
        verify(client).downloadFile(document);
        verify(client).downloadFile(report);
    }
}
