package com.example.agentservice.procurement.bid.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.agentservice.procurement.bid.domain.BidTechnicalOutline;
import java.util.List;
import org.junit.jupiter.api.Test;

class BidContentGenerationServiceTest {

    @Test
    void shouldGenerateLeavesAndKeepOutlineOrder() {
        BidSectionGenerationService sectionService = mock(BidSectionGenerationService.class);
        BidTechnicalOutline.Section schedule = leaf("schedule", "实施进度计划");
        BidTechnicalOutline.Section quality = leaf("quality", "质量保障措施");
        BidTechnicalOutline.Section implementation = new BidTechnicalOutline.Section(
                "implementation", "项目实施方案", null, List.of(), null, List.of(schedule, quality));
        BidTechnicalOutline outline = new BidTechnicalOutline("家具采购技术方案", List.of(implementation));
        when(sectionService.generate(outline, schedule, "招标要求", "企业资料"))
                .thenReturn("进度计划正文");
        when(sectionService.generate(outline, quality, "招标要求", "企业资料"))
                .thenReturn("质量保障正文");

        String markdown = new BidContentGenerationService(sectionService)
                .generate(outline, "招标要求", "企业资料");

        assertEquals("""
                # 家具采购技术方案

                ## 项目实施方案

                ### 实施进度计划

                进度计划正文

                ### 质量保障措施

                质量保障正文""", markdown);
        var order = inOrder(sectionService);
        order.verify(sectionService).generate(outline, schedule, "招标要求", "企业资料");
        order.verify(sectionService).generate(outline, quality, "招标要求", "企业资料");
        verify(sectionService, never()).generate(outline, implementation, "招标要求", "企业资料");
    }

    @Test
    void shouldKeepManualLeafWithoutCallingModel() {
        BidSectionGenerationService sectionService = mock(BidSectionGenerationService.class);
        BidTechnicalOutline.Section manual = new BidTechnicalOutline.Section(
                "authorization", "授权委托书", null, List.of(),
                BidTechnicalOutline.ContentMode.MANUAL, List.of());
        BidTechnicalOutline outline = new BidTechnicalOutline("投标文件", List.of(manual));
        BidContentGenerationService service = new BidContentGenerationService(sectionService);

        String markdown = service.generate(outline, "招标要求", "企业资料");

        assertEquals("# 投标文件\n\n## 授权委托书\n\n" + BidContentGenerationService.MANUAL_PLACEHOLDER,
                markdown);
        org.junit.jupiter.api.Assertions.assertTrue(service.requiresManualCompletion(outline));
        verify(sectionService, never()).generate(outline, manual, "招标要求", "企业资料");
    }

    private BidTechnicalOutline.Section leaf(String id, String title) {
        return new BidTechnicalOutline.Section(id, title, null, List.of(),
                BidTechnicalOutline.ContentMode.AI, List.of());
    }
}
