package com.example.agentservice.procurement.tender.service;

import com.example.agentservice.procurement.tender.domain.TenderReviewStatus;
import com.example.agentservice.procurement.tender.persistence.TenderDocumentEntity;
import java.io.IOException;
import java.util.Optional;
import org.springframework.stereotype.Service;

/** 从 OSS 读取 Managed Agent 已生成的招标文件定稿和审核报告。 */
@Service
public class TenderDocumentArtifactService {

    private static final String DOCX_CONTENT_TYPE =
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document";

    private final TenderDocumentStore documentStore;
    private final TenderArtifactStorage artifactStorage;

    public TenderDocumentArtifactService(
            TenderDocumentStore documentStore, TenderArtifactStorage artifactStorage) {
        this.documentStore = documentStore;
        this.artifactStorage = artifactStorage;
    }

    /** 导出已经确认定稿的招标文件。 */
    public Optional<ExportedDocument> exportDocument(String taskId, String versionId) throws IOException {
        Optional<TenderDocumentEntity> document = document(taskId, versionId);
        if (document.isEmpty()) return Optional.empty();
        if (!Boolean.TRUE.equals(document.get().getFinalized())) {
            throw new IllegalStateException("仅已确认定稿版本可导出产物");
        }
        return Optional.of(read(
                "招标文件.docx", document.get().getFinalDocumentObjectKey(), "招标文件定稿尚未生成完成"));
    }

    /** 导出已经生成完成的招标文件审核报告。 */
    public Optional<ExportedDocument> exportReview(String taskId, String versionId) throws IOException {
        Optional<TenderDocumentEntity> document = document(taskId, versionId);
        if (document.isEmpty()) return Optional.empty();
        if (!TenderReviewStatus.COMPLETED.name().equals(document.get().getReviewStatus())
                || document.get().getReviewReportObjectKey() == null
                || document.get().getReviewReportObjectKey().isBlank()) {
            throw new IllegalStateException("审核报告尚未生成完成");
        }
        return Optional.of(read(
                "招标文件审核报告.docx", document.get().getReviewReportObjectKey(), "审核报告尚未生成完成"));
    }

    private Optional<TenderDocumentEntity> document(String taskId, String versionId) {
        return documentStore.findByTaskId(taskId)
                .filter(document -> versionId.equals(document.getId()));
    }

    private ExportedDocument read(String filename, String objectKey, String missingMessage)
            throws IOException {
        if (objectKey == null || objectKey.isBlank()) {
            throw new IllegalStateException(missingMessage);
        }
        return new ExportedDocument(filename, DOCX_CONTENT_TYPE, artifactStorage.read(objectKey));
    }

    public record ExportedDocument(String filename, String contentType, byte[] content) {
    }
}
