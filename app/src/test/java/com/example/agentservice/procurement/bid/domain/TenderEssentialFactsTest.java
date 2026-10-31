package com.example.agentservice.procurement.bid.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;

class TenderEssentialFactsTest {

    @Test
    void shouldRoundTripJson() throws Exception {
        TenderEssentialFacts facts = new TenderEssentialFacts(
                new TenderEssentialFacts.Project("家具采购", "ZB-01", "某学校", "55万元", "学生宿舍家具"),
                List.of(new TenderEssentialFacts.Requirement(
                        "产品质量", "满足技术参数", "第三章第2节", "产品应满足技术参数")),
                List.of(),
                List.of(new TenderEssentialFacts.ScoringItem(
                        "实施方案", "10", "内容完整且可行得10分", "给出进度与人员安排", "第五章第3项")),
                List.of(),
                List.of());

        ObjectMapper mapper = new ObjectMapper();
        TenderEssentialFacts restored = mapper.readValue(
                mapper.writeValueAsString(facts), TenderEssentialFacts.class);

        assertEquals("家具采购", restored.project().projectName());
        assertEquals("实施方案", restored.technicalScoring().get(0).name());
    }
}
