package com.example.agentservice.procurement.tender.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TenderScoreRuleNormalizerTest {

    private final TenderScoreRuleNormalizer normalizer =
            new TenderScoreRuleNormalizer(new ObjectMapper());

    @Test
    void convertsSupportedScoreComponents() {
        List<Map<String, Object>> result = normalizer.normalize(List.of(
                rule("deviationRule", "{\"child\":[{\"label\":\"正偏离\",\"value\":\"2\"}]}"),
                rule("multiAccordRule", "{\"child\":[{\"label\":\"完全满足\",\"value\":\"5\"}]}"),
                rule("customRule", "{\"child\":[{\"mode\":\"0\",\"value\":\"1\",\"score\":\"0\"},"
                        + "{\"mode\":\"1\",\"min\":\"1\",\"max\":\"3\",\"score\":\"3\"},"
                        + "{\"mode\":\"2\",\"value\":\"4\",\"score\":\"5\"}]}"),
                rule("textRule", "方案完整\n措施可行")));

        assertEquals("正偏离：2分", result.get(0).get("displayScoreRule"));
        assertEquals("完全满足得5分", result.get(1).get("displayScoreRule"));
        assertEquals("小于1得0分；区间1-3得3分；大于等于4得5分",
                result.get(2).get("displayScoreRule"));
        assertEquals("方案完整\n措施可行", result.get(3).get("displayScoreRule"));
    }

    @Test
    void preservesOriginalRuleWhenComponentIsUnknownOrJsonIsInvalid() {
        List<Map<String, Object>> result = normalizer.normalize(List.of(
                rule("priceRule", "价格分计算公式"),
                rule("customRule", "不是JSON")));

        assertEquals("价格分计算公式", result.get(0).get("displayScoreRule"));
        assertEquals("不是JSON", result.get(1).get("displayScoreRule"));
        assertEquals("不是JSON", result.get(1).get("scoreRule"));
    }

    private Map<String, Object> rule(String componentType, String scoreRule) {
        return Map.of("componentType", componentType, "scoreRule", scoreRule);
    }
}
