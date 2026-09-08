package com.example.agentservice.procurement.service;

import com.aliyun.oss.OSS;
import com.aliyun.oss.model.ObjectMetadata;
import com.example.agentservice.imm.support.AbstractImmServiceSupport;
import com.example.agentservice.procurement.config.ProcurementDocumentProperties;
import com.example.agentservice.procurement.domain.ArtifactType;
import com.example.agentservice.procurement.domain.DocumentArtifact;
import com.example.agentservice.procurement.domain.DocumentVersion;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Optional;

/** 导出招标文件定稿版本，并保存对应的 OSS 产物信息。 */
@Service
public class TenderDocumentArtifactService extends AbstractImmServiceSupport {

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
                    snapshot.markdown()));
            return Optional.of(artifacts);
        } finally {
            Files.deleteIfExists(directory.resolve("tender.docx"));
            Files.deleteIfExists(directory.resolve("tender.pdf"));
            Files.deleteIfExists(directory);
        }
    }

    /** 查找已导出的 DOCX/PDF，并在写入 HTTP 响应时再从 OSS 传输文件。 */
    public Optional<ArtifactDownload> download(String taskId, String versionId, ArtifactType type) {
        ArtifactFormat format = ArtifactFormat.from(type);
        Optional<ProcurementTaskRedisStore.DocumentVersionSnapshot> optional = taskStore.findVersion(taskId, versionId);
        if (optional.isEmpty()) return Optional.empty();
        DocumentVersion version = optional.get().version();
        if (!taskId.equals(version.taskId()) || !versionId.equals(version.versionId())) {
            throw new IllegalStateException("文档版本数据与请求不一致");
        }
        DocumentArtifact artifact = version.artifacts().stream()
                .filter(candidate -> candidate.type() == type)
                .findFirst()
                .orElse(null);
        if (artifact == null) return Optional.empty();
        String expectedKey = artifactKey(taskId, versionId, format.extension());
        if (!expectedKey.equals(artifact.objectKey())) {
            throw new IllegalStateException("导出产物路径不合法");
        }
        StreamingResponseBody body = output -> {
            OSS client = createOssClient();
            try (var object = client.getObject(ossBucket(), expectedKey);
                    var input = object.getObjectContent()) {
                input.transferTo(output);
                output.flush();
            } finally {
                client.shutdown();
            }
        };
        return Optional.of(new ArtifactDownload(
                "招标文件_V" + version.versionNumber() + "." + format.extension(),
                format.contentType(), artifact.size(), body));
    }

    private List<DocumentArtifact> upload(String taskId, String versionId, Path docx, Path pdf) throws Exception {
        OSS client = createOssClient();
        try {
            return List.of(uploadOne(client, artifactKey(taskId, versionId, "docx"), docx, ArtifactType.DOCX,
                    "application/vnd.openxmlformats-officedocument.wordprocessingml.document"),
                    uploadOne(client, artifactKey(taskId, versionId, "pdf"), pdf, ArtifactType.PDF, "application/pdf"));
        } finally { client.shutdown(); }
    }

    private DocumentArtifact uploadOne(OSS client, String key, Path file, ArtifactType type, String contentType) throws Exception {
        byte[] bytes = Files.readAllBytes(file);
        ObjectMetadata metadata = new ObjectMetadata(); metadata.setContentType(contentType); metadata.setContentLength(bytes.length);
        client.putObject(ossBucket(), key, new ByteArrayInputStream(bytes), metadata);
        String url = client.generatePresignedUrl(ossBucket(), key,
                Date.from(Instant.now().plus(Duration.ofHours(2)))).toString();
        return new DocumentArtifact(type, key, url, bytes.length, Instant.now());
    }

    private String artifactKey(String taskId, String versionId, String extension) {
        return properties.getOssOutputPrefix().replaceAll("/+$", "")
                + "/" + taskId + "/" + versionId + "/tender." + extension;
    }

    public record ArtifactDownload(
            String filename, String contentType, long contentLength, StreamingResponseBody body) {
    }

    private record ArtifactFormat(String extension, String contentType) {

        private static ArtifactFormat from(ArtifactType type) {
            if (type == null) throw new IllegalArgumentException("导出产物类型不能为空");
            return switch (type) {
                case DOCX -> new ArtifactFormat("docx",
                        "application/vnd.openxmlformats-officedocument.wordprocessingml.document");
                case PDF -> new ArtifactFormat("pdf", "application/pdf");
                default -> throw new IllegalArgumentException("仅支持下载 DOCX 或 PDF 文件");
            };
        }
    }
}
