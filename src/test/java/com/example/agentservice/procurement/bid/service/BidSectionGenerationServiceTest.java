package com.example.agentservice.procurement.bid.service;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import com.example.agentservice.config.ModelConfig;
import com.example.agentservice.procurement.bid.domain.BidTechnicalOutline;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;

class BidSectionGenerationServiceTest {

    private final BidSectionGenerationService service =
            new BidSectionGenerationService(mock(ModelConfig.class), new ObjectMapper());

    @Test
    void shouldBuildFocusedSectionInput() {
        BidTechnicalOutline.Section section = new BidTechnicalOutline.Section(
                "schedule", "实施进度计划", "说明里程碑", List.of("交付期限"), List.of());
        BidTechnicalOutline outline = new BidTechnicalOutline("技术方案", List.of(section));

        String input = service.generationInput(outline, section,
                "{\"deliveryRequirements\":[\"30日内交付\"]}",
                "{\"companyName\":\"测试公司\"}");

        assertTrue(input.contains("实施进度计划"));
        assertTrue(input.contains("交付期限"));
        assertTrue(input.contains("30日内交付"));
        assertTrue(input.contains("测试公司"));
    }

    @Test
    void shouldRejectNonLeafSection() {
        BidTechnicalOutline.Section child = new BidTechnicalOutline.Section(
                "child", "子章节", null, List.of(), List.of());
        BidTechnicalOutline.Section parent = new BidTechnicalOutline.Section(
                "parent", "父章节", null, List.of(), List.of(child));

        assertThrows(IllegalArgumentException.class,
                () -> service.generationInput(new BidTechnicalOutline("技术方案", List.of(parent)),
                        parent, "{}", "{}"));
    }
}
