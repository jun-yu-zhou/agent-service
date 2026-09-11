package com.example.agentservice.procurement.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.agentservice.procurement.persistence.TenderDocumentEntity;
import com.example.agentservice.procurement.persistence.TenderDocumentMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class TenderDocumentStoreTest {

    @BeforeAll
    static void initializeTableMetadata() {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), TenderDocumentEntity.class);
    }

    @Test
    void shouldOverwriteMarkdownAndInvalidateReview() {
        TenderDocumentMapper mapper = mock(TenderDocumentMapper.class);
        TenderDocumentEntity document = new TenderDocumentEntity();
        document.setTaskId("task-1");
        document.setContentRevision(2);
        document.setFinalized(true);
        document.setReviewRevision(2);
        document.setReviewStatus("COMPLETED");
        document.setReviewReport("旧审核报告");
        when(mapper.selectOne(any())).thenReturn(document);

        TenderDocumentEntity saved = new TenderDocumentStore(mapper)
                .saveMarkdown("task-1", "# 修改后的招标文件").orElseThrow();

        assertEquals("# 修改后的招标文件", saved.getDocumentMarkdown());
        assertEquals(3, saved.getContentRevision());
        assertFalse(saved.getFinalized());
        assertEquals("NOT_STARTED", saved.getReviewStatus());
        assertNull(saved.getReviewRevision());
        assertNull(saved.getReviewReport());
        verify(mapper).update(any(), any());
    }

    @Test
    void shouldCreatePendingDocument() throws Exception {
        TenderDocumentMapper mapper = mock(TenderDocumentMapper.class);
        TenderDocumentStore store = new TenderDocumentStore(mapper);

        store.create("task-1", "project-1", "template-1",
                new ObjectMapper().readTree("{\"projectName\":\"测试项目\"}"));

        ArgumentCaptor<TenderDocumentEntity> captor = ArgumentCaptor.forClass(TenderDocumentEntity.class);
        verify(mapper).insert(captor.capture());
        TenderDocumentEntity document = captor.getValue();
        assertEquals(16, document.getId().length());
        assertEquals("task-1", document.getTaskId());
        assertEquals("project-1", document.getProjectId());
        assertEquals("template-1", document.getTemplateId());
        assertEquals("{\"projectName\":\"测试项目\"}", document.getProjectData());
        assertEquals("PENDING", document.getGenerationStatus());
        assertEquals("NOT_STARTED", document.getReviewStatus());
        assertFalse(document.getFinalized());
    }
}
