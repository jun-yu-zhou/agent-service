package com.example.agentservice.procurement.bid.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.example.agentservice.procurement.bid.persistence.BidDocumentEntity;
import com.example.agentservice.procurement.bid.persistence.BidDocumentMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class BidDocumentStoreTest {

    @BeforeAll
    static void initializeTableMetadata() {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), BidDocumentEntity.class);
    }

    @Test
    void shouldCreateExtractingTask() {
        BidDocumentMapper mapper = mock(BidDocumentMapper.class);

        new BidDocumentStore(mapper).create(
                "招标文件.pdf", "https://example/file.pdf", "{\"companyName\":\"测试公司\"}");

        ArgumentCaptor<BidDocumentEntity> captor = ArgumentCaptor.forClass(BidDocumentEntity.class);
        verify(mapper).insert(captor.capture());
        BidDocumentEntity document = captor.getValue();
        assertEquals(16, document.getId().length());
        assertEquals(36, document.getTaskId().length());
        assertEquals("招标文件.pdf", document.getSourceFileName());
        assertNull(document.getSourceObjectKey());
        assertEquals("EXTRACTING", document.getStage());
        assertFalse(document.getOutlineConfirmed());
    }
}
