package com.example.agentservice.procurement.tender.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/** 将评分组件保存的规则转换为适合编入招标文件的中文说明。 */
@Component
public class TenderScoreRuleNormalizer {

    private final ObjectMapper objectMapper;

    public TenderScoreRuleNormalizer(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /** 保留原始规则，并为每条评分项补充可直接阅读的规则说明。 */
    public List<Map<String, Object>> normalize(List<Map<String, Object>> scoreRules) {
        return scoreRules.stream().map(this::normalize).toList();
    }

    private Map<String, Object> normalize(Map<String, Object> scoreRule) {
        Map<String, Object> result = new LinkedHashMap<>(scoreRule);
        String rawRule = text(scoreRule.get("scoreRule"));
        if (!rawRule.isBlank()) {
            result.put("displayScoreRule", displayRule(text(scoreRule.get("componentType")), rawRule));
        }
        return result;
    }

    private String displayRule(String componentType, String rawRule) {
        try {
            return switch (componentType) {
                case "deviationRule" -> deviationRule(rawRule);
                case "multiAccordRule" -> multiAccordRule(rawRule);
                case "customRule" -> customRule(rawRule);
                case "textRule" -> textRule(rawRule);
                default -> rawRule;
            };
        }
        catch (Exception exception) {
            return rawRule;
        }
    }

    private String deviationRule(String rawRule) throws JsonProcessingException {
        List<String> rules = new ArrayList<>();
        for (JsonNode child : children(rawRule)) {
            rules.add("%s：%s分".formatted(child.path("label").asText(), child.path("value").asText()));
        }
        return String.join("；", rules);
    }

    private String multiAccordRule(String rawRule) throws JsonProcessingException {
        List<String> rules = new ArrayList<>();
        for (JsonNode child : children(rawRule)) {
            rules.add("%s得%s分".formatted(child.path("label").asText(), child.path("value").asText()));
        }
        return String.join("；", rules);
    }

    private String customRule(String rawRule) throws JsonProcessingException {
        List<String> rules = new ArrayList<>();
        for (JsonNode child : children(rawRule)) {
            String rule = switch (child.path("mode").asText()) {
                case "0" -> "小于%s得%s分".formatted(
                        child.path("value").asText(), child.path("score").asText());
                case "1" -> "区间%s-%s得%s分".formatted(
                        child.path("min").asText(), child.path("max").asText(),
                        child.path("score").asText());
                case "2" -> "大于等于%s得%s分".formatted(
                        child.path("value").asText(), child.path("score").asText());
                default -> null;
            };
            if (rule != null) {
                rules.add(rule);
            }
        }
        return rules.isEmpty() ? rawRule : String.join("；", rules);
    }

    private JsonNode children(String rawRule) throws JsonProcessingException {
        return objectMapper.readTree(rawRule).path("child");
    }

    private String textRule(String rawRule) {
        return rawRule.replace("<br/>", "\n").replace("&nbsp;", " ").replace("&ldquo;", "\"");
    }

    private String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }
}
