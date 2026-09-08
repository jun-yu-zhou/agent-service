package com.example.agentservice.procurement.service;

import com.aliyun.oss.OSS;
import com.aliyun.oss.model.OSSObject;
import com.example.agentservice.procurement.config.ProcurementDocumentProperties;
import com.example.agentservice.procurement.domain.ArtifactType;
import com.example.agentservice.procurement.domain.DocumentArtifact;
import com.example.agentservice.procurement.domain.DocumentVersion;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class TenderDocumentArtifactServiceTest {

    private static final String TASK_ID = "task-1";
    private static final String VERSION_ID = "version-1";
    private static final String OBJECT_KEY =
            "procurement-document/task-1/version-1/tender.docx";

    @Test
    void shouldStreamOnlyTheArtifactOwnedByTheRequestedVersion() throws Exception {
        byte[] expected = "docx-content".getBytes();
        ProcurementTaskRedisStore store = mock(ProcurementTaskRedisStore.class);
        OSS client = mock(OSS.class);
        OSSObject object = new OSSObject();
        object.setObjectContent(new ByteArrayInputStream(expected));
        when(client.getObject("test-bucket", OBJECT_KEY)).thenReturn(object);
        when(store.findVersion(TASK_ID, VERSION_ID)).thenReturn(Optional.of(snapshot(OBJECT_KEY)));

        TenderDocumentArtifactService.ArtifactDownload download =
                service(store, client).download(TASK_ID, VERSION_ID, ArtifactType.DOCX).orElseThrow();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        download.body().writeTo(output);

        assertEquals("招标文件_V2.docx", download.filename());
        assertEquals(expected.length, download.contentLength());
        assertArrayEquals(expected, output.toByteArray());
        verify(client).shutdown();
    }

    @Test
    void shouldRejectUnsupportedArtifactTypesBeforeAccessingOss() {
        ProcurementTaskRedisStore store = mock(ProcurementTaskRedisStore.class);
        OSS client = mock(OSS.class);

        assertThrows(IllegalArgumentException.class,
                () -> service(store, client).download(TASK_ID, VERSION_ID, ArtifactType.STRUCTURED_JSON));

        verifyNoInteractions(store, client);
    }

    @Test
    void shouldRejectAnArtifactOutsideTheExpectedObjectKey() {
        ProcurementTaskRedisStore store = mock(ProcurementTaskRedisStore.class);
        OSS client = mock(OSS.class);
        when(store.findVersion(TASK_ID, VERSION_ID))
                .thenReturn(Optional.of(snapshot("procurement-document/other/version-1/tender.docx")));

        IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> service(store, client).download(TASK_ID, VERSION_ID, ArtifactType.DOCX));

        assertTrue(exception.getMessage().contains("路径不合法"));
        verifyNoInteractions(client);
    }

    private TenderDocumentArtifactService service(ProcurementTaskRedisStore store, OSS client) {
        ProcurementDocumentProperties properties = new ProcurementDocumentProperties();
        properties.setOssOutputPrefix("procurement-document/");
        return new TenderDocumentArtifactService(store, mock(TenderMarkdownDocxRenderer.class), properties) {
            @Override
            protected OSS createOssClient() {
                return client;
            }

            @Override
            protected String ossBucket() {
                return "test-bucket";
            }
        };
    }

    private ProcurementTaskRedisStore.DocumentVersionSnapshot snapshot(String objectKey) {
        DocumentArtifact artifact = new DocumentArtifact(
                ArtifactType.DOCX, objectKey, null, "docx-content".getBytes().length, Instant.now());
        DocumentVersion version = new DocumentVersion(
                VERSION_ID, TASK_ID, null, 2, "MANUAL_EDIT", "tester", Instant.now(), true,
                List.of(artifact));
        return new ProcurementTaskRedisStore.DocumentVersionSnapshot(version, "# 招标文件");
    }
}
