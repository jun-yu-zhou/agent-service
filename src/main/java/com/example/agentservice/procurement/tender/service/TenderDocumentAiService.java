package com.example.agentservice.procurement.tender.service;

import com.example.agentservice.managedagent.ManagedAgentArtifact;
import com.example.agentservice.managedagent.ManagedAgentClient;
import com.example.agentservice.procurement.tender.prompt.TenderDocumentPrompts;
import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.springframework.stereotype.Service;

/** 通过百炼 Managed Agent 生成招标文件初稿并驱动定稿审核。 */
@Service
public class TenderDocumentAiService {

    private final ManagedAgentClient managedAgentClient;

    public TenderDocumentAiService(ManagedAgentClient managedAgentClient) {
        this.managedAgentClient = managedAgentClient;
    }

    /** 将数据库中的 HTML 模板上传到 Managed Agent 文件服务。 */
    public String uploadTemplateHtml(String templateHtml) {
        if (templateHtml == null || templateHtml.isBlank()) {
            throw new IllegalArgumentException("招标文件 HTML 模板不能为空");
        }
        return uploadText(templateHtml, "template.html", "text/html");
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
                new ManagedAgentClient.SessionFile(
                        templateFileId, templatePath),
                new ManagedAgentClient.SessionFile(
                        projectDataFileId, "/uploads/data/project-data.json"));
        return new DraftSession(sessionId, "/mnt/session" + templatePath);
    }

    /** 让 Agent 从会话挂载文件读取模板和项目资料，避免将大段内容塞入事件消息。 */
    public String generateDraft(DraftSession session) {
        List<ManagedAgentArtifact> artifacts = managedAgentClient.sendMessage(session.sessionId(),
                TenderDocumentPrompts.DRAFT_REQUEST.formatted(session.templatePath()));
        return markdown(artifacts, null);
    }

    /** 将用户上传的模板交由 Managed Agent 文件服务保存。 */
    public String uploadTemplate(byte[] content, String fileName, String contentType) {
        return managedAgentClient.uploadFile(content, fileName, contentType);
    }

    /** 在同一会话中提交人工定稿，并下载 Agent 交付的定稿和审核报告。 */
    public FinalizedArtifacts reviewFinalizedDocument(String sessionId, String finalizedMarkdown) {
        if (finalizedMarkdown == null || finalizedMarkdown.isBlank()) {
            throw new IllegalArgumentException("招标文件定稿正文不能为空");
        }
        String fileName = "招标文件人工定稿.md";
        String fileId = uploadText(finalizedMarkdown, fileName, "text/markdown");
        List<ManagedAgentArtifact> artifacts = managedAgentClient.sendMessage(
                sessionId, TenderDocumentPrompts.FINALIZE_REQUEST,
                new ManagedAgentClient.MessageFile(fileId, fileName));
        ManagedAgentArtifact document = docx(artifacts, "定稿");
        ManagedAgentArtifact report = docx(artifacts, "审核报告");
        return new FinalizedArtifacts(
                document.fileName(), managedAgentClient.downloadFile(document),
                report.fileName(), managedAgentClient.downloadFile(report));
    }

    private String uploadText(String content, String fileName, String contentType) {
        return managedAgentClient.uploadFile(
                content.getBytes(StandardCharsets.UTF_8), fileName, contentType);
    }

    private static String safeFileName(String value) {
        return value == null || value.isBlank()
                ? "template" : value.replace('/', '_').replace('\\', '_');
    }

    /** 正文只读取 Agent 通过 mark_artifacts 明确交付的 Markdown 文件。 */
    private String markdown(List<ManagedAgentArtifact> artifacts, String name) {
        ManagedAgentArtifact file = artifacts.stream()
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

    private ManagedAgentArtifact docx(List<ManagedAgentArtifact> artifacts, String name) {
        return artifacts.stream()
                .filter(value -> value.fileName() != null
                        && value.fileName().toLowerCase().endsWith(".docx"))
                .filter(value -> value.fileName().contains(name))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "Managed Agent 未返回可下载的" + name + " DOCX 文件"));
    }

    /** 已挂载模板和项目资料的初稿会话。 */
    public record DraftSession(String sessionId, String templatePath) {
    }

    /** 人工定稿审核阶段交付的两个 Word 产物。 */
    public record FinalizedArtifacts(
            String documentFileName,
            byte[] documentContent,
            String reviewFileName,
            byte[] reviewContent) {
    }
}
