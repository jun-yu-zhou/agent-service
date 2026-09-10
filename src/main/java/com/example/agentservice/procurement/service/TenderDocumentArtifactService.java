package com.example.agentservice.procurement.service;

import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.Optional;

/** 将招标文件定稿版本直接生成为可下载的 Word 内容。 */
@Service
public class TenderDocumentArtifactService {

    private static final String DOCX_CONTENT_TYPE =
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document";

    private final ProcurementTaskRedisStore taskStore;
    private final PoiMarkdownDocxRenderer docxRenderer;

    public TenderDocumentArtifactService(
            ProcurementTaskRedisStore taskStore, PoiMarkdownDocxRenderer docxRenderer) {
        this.taskStore = taskStore;
        this.docxRenderer = docxRenderer;
    }

    public Optional<ExportedDocument> export(String taskId, String versionId) throws IOException {
        Optional<ProcurementTaskRedisStore.DocumentVersionSnapshot> optional = taskStore.findVersion(taskId, versionId);
        if (optional.isEmpty()) return Optional.empty();
        ProcurementTaskRedisStore.DocumentVersionSnapshot snapshot = optional.get();
        if (!snapshot.version().finalized()) throw new IllegalStateException("仅已确认定稿版本可导出产物");
        byte[] content = docxRenderer.render(snapshot.markdown());
        return Optional.of(new ExportedDocument(
                "招标文件_V" + snapshot.version().versionNumber() + ".docx",
                DOCX_CONTENT_TYPE, content));
    }

    public record ExportedDocument(String filename, String contentType, byte[] content) {
    }
}
