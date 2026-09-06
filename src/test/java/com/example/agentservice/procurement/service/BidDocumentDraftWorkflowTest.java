package com.example.agentservice.procurement.service;

import com.example.agentservice.service.ImmService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BidDocumentDraftWorkflowTest {

    @Test
    void shouldExtractTenderAndEverySupplierMaterialBeforeGeneration() throws Exception {
        ImmService immService = mock(ImmService.class);
        BidDocumentGenerationService generationService = mock(BidDocumentGenerationService.class);
        when(immService.extractDocumentText("tender-url")).thenReturn("招标正文");
        when(immService.extractDocumentText("supplier-a-url")).thenReturn("供应商基本资料");
        when(immService.extractDocumentText("supplier-b-url")).thenReturn("资质证明材料");
        when(generationService.generateDraft(anyString(), anyString())).thenReturn("投标初稿");

        String draft = new BidDocumentDraftWorkflow(immService, generationService)
                .generateDraft("tender-url", List.of("supplier-a-url", "supplier-b-url"));

        assertEquals("投标初稿", draft);
    }
}
