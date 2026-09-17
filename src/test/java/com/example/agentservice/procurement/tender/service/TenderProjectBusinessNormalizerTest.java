package com.example.agentservice.procurement.tender.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TenderProjectBusinessNormalizerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final TenderProjectBusinessNormalizer normalizer =
            new TenderProjectBusinessNormalizer(objectMapper);

    @Test
    void convertsProjectCodesMoneyDatesAndComments() throws Exception {
        List<Map<String, Object>> comments = List.of(
                comment("4", "{\"observeLetter\":\"1\",\"observeAmount\":\"5\","
                        + "\"observeRemark\":\"银行保函\",\"refundRemark\":\"验收后退还\"}"),
                comment("38", "[{\"qualificationMessage\":\"按时交付\"}]"),
                comment("47", "{\"companyType\":\"中小企业\"}"));

        ObjectNode facts = normalizer.normalize(Map.ofEntries(
                Map.entry("projectType", "1"), Map.entry("classifyCode", "5"),
                Map.entry("evaluateWayCode", "1"), Map.entry("evaluatingBidType", "1"),
                Map.entry("isAcceptInput", "0"), Map.entry("fundingSource", "a,b"),
                Map.entry("purchaseMoney", "550000.00"),
                Map.entry("openDatetime", LocalDateTime.of(2026, 9, 17, 9, 30)),
                Map.entry("collegeName", "采购单位"), Map.entry("tendereeAgent", "代理机构"),
                Map.entry("isAgent", "1")), comments,
                List.of(Map.of("csId", "a", "csName", "财政资金"),
                        Map.of("csId", "b", "csName", "自筹资金")),
                List.of(Map.of("bidRoom", "第一开标室")),
                List.of(Map.of("itemName", "学生公寓家具", "isCore", "1")));

        assertEquals("竞争性磋商", facts.path("procurementMethodName").asText());
        assertEquals("财政资金、自筹资金", facts.path("fundingSourceName").asText());
        assertEquals("2026年09月17日 09:30:00",
                facts.path("formattedDates").path("openDatetime").path("value").asText());
        assertEquals("★按时交付", facts.path("substantiveRequirements").get(0).asText());
        assertTrue(facts.path("performanceGuarantee").path("required").asBoolean());
        assertEquals("中小企业", facts.path("companyType").asText());
        assertTrue(facts.path("purchaseMoneyUppercase").asText().contains("伍拾伍万"));
        assertEquals("代理机构", facts.path("projectParties").path("tenderAgent").asText());
        assertEquals("第一开标室", facts.path("openingArrangement").path("openingPlace").asText());
        assertEquals("学生公寓家具", facts.path("coreProduct").path("productNames").get(0).asText());
    }

    private Map<String, Object> comment(String type, String json) throws Exception {
        return Map.of("commentsType", type, "comments", objectMapper.readTree(json));
    }
}
