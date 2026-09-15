package com.example.agentservice.procurement.bid.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import com.example.agentservice.config.ModelConfig;
import com.example.agentservice.procurement.bid.prompt.BidDocumentPrompts;
import org.junit.jupiter.api.Test;

class BidOutlineGenerationServiceTest {

    @Test
    void shouldParseTreeOutline() {
        String response = """
                {
                  "title":"家具采购项目技术方案",
                  "sections":[{
                    "id":"implementation",
                    "title":"项目实施方案",
                    "writingFocus":"说明进度、人员和交付安排",
                    "requirementRefs":["实施方案评分项"],
                    "children":[{
                      "id":"schedule",
                      "title":"实施进度计划",
                      "writingFocus":"明确里程碑",
                      "requirementRefs":["交付期限"],
                      "children":[]
                    }]
                  }]
                }
                """;

        var outline = new BidOutlineGenerationService(mock(ModelConfig.class)).parse(response);

        assertEquals("实施进度计划", outline.sections().get(0).children().get(0).title());
    }

    @Test
    void shouldIncludeRecursiveOutlineSchema() {
        String prompt = BidDocumentPrompts.outlineGeneration();

        assertTrue(prompt.contains("writingFocus"));
        assertTrue(prompt.contains("requirementRefs"));
    }
}
