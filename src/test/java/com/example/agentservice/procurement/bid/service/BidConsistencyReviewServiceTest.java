package com.example.agentservice.procurement.bid.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import com.example.agentservice.config.ModelConfig;
import com.example.agentservice.procurement.bid.prompt.BidDocumentPrompts;
import org.junit.jupiter.api.Test;

class BidConsistencyReviewServiceTest {

    private final BidConsistencyReviewService service =
            new BidConsistencyReviewService(mock(ModelConfig.class));

    @Test
    void shouldParseReviewJson() {
        String response = """
                {
                  "conclusion":"NEEDS_REVISION",
                  "summary":"交付响应不完整",
                  "items":[{
                    "requirement":"30日内交付",
                    "status":"PARTIAL",
                    "tenderEvidence":"合同签订后30日内",
                    "documentEvidence":"制定交付计划",
                    "issue":"未明确承诺期限",
                    "suggestion":"补充30日内完成交付的承诺"
                  }]
                }
                """;

        var review = service.parse(response);

        assertEquals("NEEDS_REVISION", review.conclusion());
        assertEquals("PARTIAL", review.items().get(0).status());
    }

    @Test
    void shouldIncludeAllReviewInputs() {
        String input = service.reviewInput("招标要求", "企业资料", "技术方案正文");

        assertTrue(input.contains("招标要求"));
        assertTrue(input.contains("企业资料"));
        assertTrue(input.contains("技术方案正文"));
        assertTrue(BidDocumentPrompts.consistencyReview().contains("CONFLICT"));
    }
}
