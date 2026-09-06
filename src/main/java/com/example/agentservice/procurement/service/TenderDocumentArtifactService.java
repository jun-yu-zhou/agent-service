package com.example.agentservice.procurement.service;

import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSClientBuilder;
import com.aliyun.oss.model.ObjectMetadata;
import com.example.agentservice.config.AgentServiceConfig;
import com.example.agentservice.procurement.config.ProcurementDocumentProperties;
import com.example.agentservice.procurement.domain.ArtifactType;
import com.example.agentservice.procurement.domain.DocumentArtifact;
import com.example.agentservice.procurement.domain.DocumentVersion;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Optional;

/** Exports one finalized tender version and persists its OSS artifact links. */
@Service
public class TenderDocumentArtifactService {

    private final ProcurementTaskRedisStore taskStore;
    private final TenderMarkdownDocxRenderer docxRenderer;
    private final DocxPdfConverter pdfConverter;
    private final ProcurementDocumentProperties properties;

    public TenderDocumentArtifactService(ProcurementTaskRedisStore taskStore, TenderMarkdownDocxRenderer docxRenderer,
                                         DocxPdfConverter pdfConverter, ProcurementDocumentProperties properties) {
        this.taskStore = taskStore;
        this.docxRenderer = docxRenderer;
        this.pdfConverter = pdfConverter;
        this.properties = properties;
    }

    public Optional<List<DocumentArtifact>> export(String taskId, String versionId) throws Exception {
        Optional<ProcurementTaskRedisStore.DocumentVersionSnapshot> optional = taskStore.findVersion(taskId, versionId);
        if (optional.isEmpty()) return Optional.empty();
        ProcurementTaskRedisStore.DocumentVersionSnapshot snapshot = optional.get();
        if (!snapshot.version().finalized()) throw new IllegalStateException("仅已确认定稿版本可导出产物");
        Path directory = Files.createTempDirectory("procurement-export-");
        try {
            Path docx = directory.resolve("tender.docx");
            Path pdf = directory.resolve("tender.pdf");
            docxRenderer.render(snapshot.markdown(), docx);
            pdfConverter.convert(docx, pdf);
            List<DocumentArtifact> artifacts = upload(taskId, versionId, docx, pdf);
            DocumentVersion version = snapshot.version();
            taskStore.saveVersion(new ProcurementTaskRedisStore.DocumentVersionSnapshot(
                    new DocumentVersion(version.versionId(), version.taskId(), version.parentVersionId(), version.versionNumber(),
                            version.changeSource(), version.changedBy(), version.createdAt(), true, artifacts),
                    snapshot.markdown(), snapshot.consistencyResult(), snapshot.review()));
            return Optional.of(artifacts);
        } finally {
            Files.deleteIfExists(directory.resolve("tender.docx"));
            Files.deleteIfExists(directory.resolve("tender.pdf"));
            Files.deleteIfExists(directory);
        }
    }

    private List<DocumentArtifact> upload(String taskId, String versionId, Path docx, Path pdf) throws Exception {
        String prefix = properties.getOssOutputPrefix().replaceAll("/+$", "") + "/" + taskId + "/" + versionId + "/";
        OSS client = new OSSClientBuilder().build(AgentServiceConfig.ossEndpoint(), AgentServiceConfig.ossAccessKeyId(), AgentServiceConfig.ossAccessKeySecret());
        try {
            return List.of(uploadOne(client, prefix + "tender.docx", docx, ArtifactType.DOCX,
                    "application/vnd.openxmlformats-officedocument.wordprocessingml.document"),
                    uploadOne(client, prefix + "tender.pdf", pdf, ArtifactType.PDF, "application/pdf"));
        } finally { client.shutdown(); }
    }

    private DocumentArtifact uploadOne(OSS client, String key, Path file, ArtifactType type, String contentType) throws Exception {
        byte[] bytes = Files.readAllBytes(file);
        ObjectMetadata metadata = new ObjectMetadata(); metadata.setContentType(contentType); metadata.setContentLength(bytes.length);
        client.putObject(AgentServiceConfig.ossBucket(), key, new ByteArrayInputStream(bytes), metadata);
        String url = client.generatePresignedUrl(AgentServiceConfig.ossBucket(), key,
                Date.from(Instant.now().plus(Duration.ofHours(2)))).toString();
        return new DocumentArtifact(type, key, url, bytes.length, Instant.now());
    }
}
