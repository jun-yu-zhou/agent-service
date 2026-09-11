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

    private final ProcurementTaskRedisStore taskStore;
    private final Docx4jMarkdownDocxRenderer docxRenderer;

    public TenderReviewArtifactService(
            ProcurementTaskRedisStore taskStore, Docx4jMarkdownDocxRenderer docxRenderer) {
        this.taskStore = taskStore;
        this.docxRenderer = docxRenderer;
    }

    public Optional<ExportedReview> export(String taskId, String versionId) throws IOException {
        var review = taskStore.findReview(taskId, versionId);
        var version = taskStore.findVersion(taskId, versionId);
        if (review.isEmpty() || version.isEmpty()) return Optional.empty();
        if (review.get().status() != TenderReviewStatus.COMPLETED
                || review.get().reportMarkdown() == null
                || review.get().reportMarkdown().isBlank()) {
            throw new IllegalStateException("审核报告尚未生成完成");
        }

        byte[] content = docxRenderer.render(review.get().reportMarkdown());
        return Optional.of(new ExportedReview(
                "招标文件审核报告_V" + version.get().version().versionNumber() + ".docx",
                DOCX_CONTENT_TYPE, content));
    }

    public record ExportedReview(String filename, String contentType, byte[] content) {
    }
}
