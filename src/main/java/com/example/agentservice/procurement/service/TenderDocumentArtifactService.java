package com.example.agentservice.procurement.service;

import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.Optional;

/** 将招标文件定稿版本直接生成为可下载的 Word 内容。 */
@Service
public class TenderDocumentArtifactService {

    private static final String DOCX_CONTENT_TYPE =
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document";

    private final TenderDocumentStore documentStore;
    private final Docx4jMarkdownDocxRenderer docxRenderer;

    public TenderDocumentArtifactService(
            TenderDocumentStore documentStore, Docx4jMarkdownDocxRenderer docxRenderer) {
        this.documentStore = documentStore;
        this.docxRenderer = docxRenderer;
    }

    public Optional<ExportedDocument> export(String taskId, String versionId) throws IOException {
        var optional = documentStore.findByTaskId(taskId);
        if (optional.isEmpty()) return Optional.empty();
        var document = optional.get();
        if (!versionId.equals(document.getId())) return Optional.empty();
        if (!Boolean.TRUE.equals(document.getFinalized())) throw new IllegalStateException("仅已确认定稿版本可导出产物");
        byte[] content = docxRenderer.render(document.getDocumentMarkdown());
        return Optional.of(new ExportedDocument(
                "招标文件_V" + document.getContentRevision() + ".docx",
                DOCX_CONTENT_TYPE, content));
    }

    public record ExportedDocument(String filename, String contentType, byte[] content) {
    }
}
