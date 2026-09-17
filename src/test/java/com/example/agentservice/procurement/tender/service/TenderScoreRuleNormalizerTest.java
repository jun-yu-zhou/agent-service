package com.example.agentservice.procurement.tender.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
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

    @Test
    void addsPriceFormulaChineseCategoryAndCategoryTotal() {
        List<Map<String, Object>> result = normalizer.normalize(List.of(
                rule("priceRule", "", "1", "2", 30),
                rule("multiAccordRule", "{\"child\":[{\"label\":\"业绩\",\"value\":\"10\"}]}",
                        "3", "业绩", 10),
                rule("textRule", "服务方案", "3", "方案", 20)));

        assertEquals("价格评分", result.get(0).get("scoreTypeName"));
        assertEquals("最低价", result.get(0).get("displayScoreTitle"));
        assertEquals("价格分=最低投标价/投标报价×价格分权重",
                result.get(0).get("displayScoreRule"));
        assertEquals(new java.math.BigDecimal("30"), result.get(0).get("scoreTypeTotal"));
        assertEquals(new java.math.BigDecimal("30"), result.get(1).get("scoreTypeTotal"));
    }

    @Test
    void convertsParameterLevelsAndKeepsUnknownPriceFormula() {
        List<Map<String, Object>> result = normalizer.normalize(List.of(
                rule("Y", "", "6", "关键参数", 5),
                rule("priceRule", "按项目约定公式计算", "1", "10", 20)));

        assertEquals("实质性参数不允许负偏离，负偏离时按无效响应处理",
                result.get(0).get("displayScoreRule"));
        assertEquals("让利幅度", result.get(1).get("displayScoreTitle"));
        assertEquals("按项目约定公式计算", result.get(1).get("displayScoreRule"));
    }

    @Test
    void summarizesScoreGroupsAndConvertsTechnicalParameterRule() {
        List<Map<String, Object>> normalized = normalizer.normalize(List.of(
                rule("customRule", "{\"important\":\"Z\",\"child\":[{\"mode\":\"0\",\"value\":\"2\"}]}",
                        "6", "重要参数", 10),
                rule("textRule", "服务方案完整", "3", "服务方案", 20)));

        ObjectNode facts = normalizer.summarize(normalized);

        assertEquals("带▲的重要参数每负偏离一项扣2分，扣完为止",
                normalized.get(0).get("displayScoreRule"));
        assertEquals(30, facts.path("totalScore").decimalValue().intValue());
        assertEquals("技术参数", facts.path("groups").get(0).path("name").asText());
        assertEquals(1, facts.path("groups").get(0).path("itemCount").asInt());
    }

    private Map<String, Object> rule(String componentType, String scoreRule) {
        return Map.of("componentType", componentType, "scoreRule", scoreRule);
    }

    private Map<String, Object> rule(String componentType, String scoreRule,
            String scoreType, String scoreTitle, Number totalScore) {
        return Map.of("componentType", componentType, "scoreRule", scoreRule,
                "scoreType", scoreType, "scoreTitle", scoreTitle, "totalScore", totalScore);
    }
}
