package com.example.agentservice.procurement.service;

import com.example.agentservice.procurement.domain.TenderReviewStatus;
import java.io.IOException;
import java.util.Optional;
import org.springframework.stereotype.Service;

/** 将已完成的招标文件审核报告直接生成为可下载的 Word 内容。 */
@Service
public class TenderReviewArtifactService {

    private static final String DOCX_CONTENT_TYPE =
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document";

    private final TenderDocumentStore documentStore;
    private final Docx4jMarkdownDocxRenderer docxRenderer;

    public TenderReviewArtifactService(
            TenderDocumentStore documentStore, Docx4jMarkdownDocxRenderer docxRenderer) {
        this.documentStore = documentStore;
        this.docxRenderer = docxRenderer;
    }

    public Optional<ExportedReview> export(String taskId, String versionId) throws IOException {
        var optional = documentStore.findByTaskId(taskId);
        if (optional.isEmpty() || !versionId.equals(optional.get().getId())) return Optional.empty();
        var document = optional.get();
        if (!TenderReviewStatus.COMPLETED.name().equals(document.getReviewStatus())
                || document.getReviewReport() == null || document.getReviewReport().isBlank()) {
            throw new IllegalStateException("审核报告尚未生成完成");
        }

        byte[] content = docxRenderer.render(document.getReviewReport());
        return Optional.of(new ExportedReview(
                "招标文件审核报告_V" + document.getContentRevision() + ".docx",
                DOCX_CONTENT_TYPE, content));
    }

    public record ExportedReview(String filename, String contentType, byte[] content) {
    }
}
