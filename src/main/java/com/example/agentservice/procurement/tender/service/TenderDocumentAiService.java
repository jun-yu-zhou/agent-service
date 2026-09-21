package com.example.agentservice.procurement.tender.service;

import com.example.agentservice.managedagent.ManagedAgentSessionClient;
import com.example.agentservice.managedagent.ManagedAgentTurn;
import com.example.agentservice.procurement.tender.prompt.TenderDocumentPrompts;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import org.springframework.stereotype.Service;

/** 通过百炼 Managed Agent 生成招标文件初稿并驱动定稿审核。 */
@Service
public class TenderDocumentAiService {

    private final ManagedAgentSessionClient managedAgentClient;
    private final ObjectMapper objectMapper;

    public TenderDocumentAiService(
            ManagedAgentSessionClient managedAgentClient, ObjectMapper objectMapper) {
        this.managedAgentClient = managedAgentClient;
        this.objectMapper = objectMapper;
    }

    /** 上传数据库 HTML 模板和项目资料，并创建会话。 */
    public DraftSession createDraftSession(String templateHtml, JsonNode projectData) {
        if (templateHtml == null || templateHtml.isBlank()) {
            throw new IllegalArgumentException("招标文件 HTML 模板不能为空");
        }
        String templateFileId = uploadText(templateHtml, "template.html", "text/html");
        return createDraftSession(templateFileId, "template.html", projectData);
    }

    /** 上传项目资料，并与用户模板一起挂载到会话。 */
    public DraftSession createDraftSession(
            String templateFileId, String templateFileName, JsonNode projectData) {
        String projectDataFileId = uploadText(
                projectData == null ? "null" : projectData.toString(),
                "project-data.json", "application/json");
        String templateName = safeFileName(templateFileName);
        String templatePath = "/uploads/template/" + templateName;
        String sessionId = managedAgentClient.createSession(
                new ManagedAgentSessionClient.SessionFile(
                        templateFileId, templatePath, "模板文件"),
                new ManagedAgentSessionClient.SessionFile(
                        projectDataFileId, "/uploads/data/project-data.json", "项目资料"));
        return new DraftSession(sessionId, "/mnt/session" + templatePath);
    }

    /** 让 Agent 从会话挂载文件读取模板和项目资料，避免将大段内容塞入事件消息。 */
    public String generateDraft(DraftSession session) {
        ManagedAgentTurn result = managedAgentClient.sendMessage(session.sessionId(),
                TenderDocumentPrompts.DRAFT_REQUEST.formatted(session.templatePath()));
        return markdown(result);
    }

    /** 将用户上传的模板交由 Managed Agent 文件服务保存。 */
    public String uploadTemplate(byte[] content, String fileName, String contentType) {
        return managedAgentClient.uploadFile(content, fileName, contentType);
    }

    /** 在同一会话中提交人工定稿，并下载 Agent 生成的 Markdown 审核报告。 */
    public String reviewFinalizedDocument(String sessionId, String finalizedMarkdown) {
        if (finalizedMarkdown == null || finalizedMarkdown.isBlank()) {
            throw new IllegalArgumentException("招标文件定稿正文不能为空");
        }
        ObjectNode request = objectMapper.createObjectNode();
        request.put("task", TenderDocumentPrompts.FINALIZE_REQUEST);
        request.put("finalizedMarkdown", finalizedMarkdown);
        ManagedAgentTurn result = managedAgentClient.sendMessage(sessionId, request.toString());
        return markdown(result, "审核");
    }

    private String uploadText(String content, String fileName, String contentType) {
        return managedAgentClient.uploadFile(
                content.getBytes(StandardCharsets.UTF_8), fileName, contentType);
    }

    private static String safeFileName(String value) {
        return value == null || value.isBlank()
                ? "template" : value.replace('/', '_').replace('\\', '_');
    }

    /** 初稿必须来自 Agent 返回的可下载 Markdown 文件，避免把说明性文本当成正文。 */
    private String markdown(ManagedAgentTurn result) {
        return markdown(result, null);
    }

    private String markdown(ManagedAgentTurn result, String name) {
        ManagedAgentTurn.ManagedAgentFile file = result.files().stream()
                .filter(value -> value.fileName() != null && value.fileName().toLowerCase().endsWith(".md"))
                .filter(value -> name == null || value.fileName().toLowerCase().contains(name))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Managed Agent 未返回可下载的 Markdown "
                        + (name == null ? "初稿" : name + " 文件")));
        String markdown = new String(managedAgentClient.downloadFile(file), StandardCharsets.UTF_8).trim();
        if (markdown.isBlank()) {
            throw new IllegalStateException("Managed Agent 返回的 Markdown 初稿为空");
        }
        return markdown;
    }

    /** 已挂载模板和项目资料的初稿会话。 */
    public record DraftSession(String sessionId, String templatePath) {
    }
}
