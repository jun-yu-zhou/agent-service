package com.example.agentservice.procurement.bid.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import com.example.agentservice.config.ModelConfig;
import com.example.agentservice.procurement.bid.prompt.BidDocumentPrompts;
import org.junit.jupiter.api.Test;

class TenderFactsExtractionServiceTest {

    @Test
    void shouldParseModelJsonCodeFence() {
        String response = """
                ```json
                {
                  "project":{"projectName":"家具采购","projectCode":"ZB-01","purchaser":"某学校","budget":"55万元","procurementScope":"宿舍家具"},
                  "technicalRequirements":[],"deliveryRequirements":[],"technicalScoring":[],"responseRequirements":[],"rejectionRisks":[]
                }
                ```
                """;

        var facts = new TenderFactsExtractionService(mock(ModelConfig.class)).parse(response);

        assertEquals("家具采购", facts.project().projectName());
    }

    @Test
    void shouldGenerateSchemaFromFactsType() {
        String prompt = BidDocumentPrompts.tenderFactsExtraction();

        assertTrue(prompt.contains("technicalScoring"));
        assertTrue(prompt.contains("rejectionRisks"));
    }
}
