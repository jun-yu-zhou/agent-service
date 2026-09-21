package com.example.agentservice.procurement.tender.service;

import com.example.agentservice.procurement.common.docx.Docx4jMarkdownDocxRenderer;
import com.example.agentservice.procurement.tender.domain.TenderReviewStatus;
import com.example.agentservice.procurement.tender.persistence.TenderDocumentEntity;
import java.io.IOException;
import java.util.Optional;
import org.springframework.stereotype.Service;

/** 将人工定稿和 Managed Agent 审核报告转换为 Word 文件。 */
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

    /** 导出已经确认定稿的招标文件。 */
    public Optional<ExportedDocument> exportDocument(String taskId, String versionId) throws IOException {
        Optional<TenderDocumentEntity> document = document(taskId, versionId);
        if (document.isEmpty()) return Optional.empty();
        if (!Boolean.TRUE.equals(document.get().getFinalized())) {
            throw new IllegalStateException("仅已确认定稿版本可导出产物");
        }
        return Optional.of(render("招标文件.docx", document.get().getDocumentMarkdown()));
    }

    /** 导出已经生成完成的招标文件审核报告。 */
    public Optional<ExportedDocument> exportReview(String taskId, String versionId) throws IOException {
        Optional<TenderDocumentEntity> document = document(taskId, versionId);
        if (document.isEmpty()) return Optional.empty();
        if (!TenderReviewStatus.COMPLETED.name().equals(document.get().getReviewStatus())
                || document.get().getReviewReport() == null
                || document.get().getReviewReport().isBlank()) {
            throw new IllegalStateException("审核报告尚未生成完成");
        }
        return Optional.of(render("招标文件审核报告.docx", document.get().getReviewReport()));
    }

    private Optional<TenderDocumentEntity> document(String taskId, String versionId) {
        return documentStore.findByTaskId(taskId)
                .filter(document -> versionId.equals(document.getId()));
    }

    private ExportedDocument render(String filename, String markdown) throws IOException {
        return new ExportedDocument(filename, DOCX_CONTENT_TYPE, docxRenderer.render(markdown));
    }

    public record ExportedDocument(String filename, String contentType, byte[] content) {
    }
}
