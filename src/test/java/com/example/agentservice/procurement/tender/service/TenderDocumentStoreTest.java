package com.example.agentservice.procurement.tender.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.agentservice.procurement.tender.persistence.TenderDocumentEntity;
import com.example.agentservice.procurement.tender.persistence.TenderDocumentMapper;
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
        when(mapper.update(any(), any())).thenReturn(1);

        boolean saved = new TenderDocumentStore(mapper)
                .saveMarkdown("task-1", 2, true, "# 修改后的招标文件");

        assertTrue(saved);
        verify(mapper).update(any(), any());
        // 条件更新成功后不再查询，避免读到随后并发请求写入的其他版本。
        verify(mapper, never()).selectOne(any());
    }

    @Test
    void shouldGuardGenerationCompletionWithInitialState() {
        TenderDocumentMapper mapper = mock(TenderDocumentMapper.class);
        when(mapper.update(any(), any())).thenReturn(1);
        ArgumentCaptor<com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<TenderDocumentEntity>>
                update = ArgumentCaptor.forClass(com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper.class);

        assertTrue(new TenderDocumentStore(mapper)
                .completeGeneration("task-1", "session-1", "# 招标文件"));

        verify(mapper).update(any(), update.capture());
        String conditions = update.getValue().getSqlSegment().toLowerCase();
        assertTrue(conditions.contains("task_id"));
        assertTrue(conditions.contains("generation_status"));
        assertTrue(conditions.contains("content_revision"));
        assertTrue(conditions.contains("finalized"));
    }

    @Test
    void shouldSaveSessionOnlyForGeneratingInitialTask() {
        TenderDocumentMapper mapper = mock(TenderDocumentMapper.class);
        when(mapper.update(any(), any())).thenReturn(1);
        ArgumentCaptor<com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<TenderDocumentEntity>>
                update = ArgumentCaptor.forClass(com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper.class);

        assertTrue(new TenderDocumentStore(mapper).saveGenerationSession("task-1", "session-1"));

        verify(mapper).update(any(), update.capture());
        String conditions = update.getValue().getSqlSegment().toLowerCase();
        assertTrue(conditions.contains("task_id"));
        assertTrue(conditions.contains("generation_status"));
        assertTrue(conditions.contains("content_revision"));
        assertTrue(conditions.contains("finalized"));
    }

    @Test
    void shouldCreatePendingDocument() throws Exception {
        TenderDocumentMapper mapper = mock(TenderDocumentMapper.class);
        TenderDocumentStore store = new TenderDocumentStore(mapper);

        store.create("task-1", "project-1", "template-1");

        ArgumentCaptor<TenderDocumentEntity> captor = ArgumentCaptor.forClass(TenderDocumentEntity.class);
        verify(mapper).insert(captor.capture());
        TenderDocumentEntity document = captor.getValue();
        assertEquals(16, document.getId().length());
        assertEquals("task-1", document.getTaskId());
        assertEquals("project-1", document.getProjectId());
        assertEquals("template-1", document.getTemplateId());
        assertEquals("PENDING", document.getGenerationStatus());
        assertEquals("NOT_STARTED", document.getReviewStatus());
        assertFalse(document.getFinalized());
    }

}
