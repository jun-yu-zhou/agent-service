package com.example.agentservice.procurement.tender.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/** 将评分组件保存的规则转换为适合编入招标文件的中文说明。 */
@Component
public class TenderScoreRuleNormalizer {

    private static final Map<String, String> SCORE_TYPES = Map.ofEntries(
            Map.entry("0", "资格条件"), Map.entry("1", "价格评分"),
            Map.entry("2", "技术评分"), Map.entry("3", "商务评分"),
            Map.entry("4", "售后服务"), Map.entry("5", "人员资质"),
            Map.entry("6", "技术参数"), Map.entry("7", "信用评价"),
            Map.entry("8", "质量评价"), Map.entry("9", "施工方案"),
            Map.entry("10", "培训方案"), Map.entry("11", "品牌性能"),
            Map.entry("13", "商务条款"), Map.entry("14", "诚信评价"));

    private static final Map<String, String> PRICE_TITLES = Map.ofEntries(
            Map.entry("0", "基准价"), Map.entry("1", "平均价"),
            Map.entry("2", "最低价"), Map.entry("3", "市场均价"),
            Map.entry("4", "偏差率法"), Map.entry("5", "权重法"),
            Map.entry("6", "投标价"), Map.entry("7", "当量价"),
            Map.entry("8", "最高价"), Map.entry("9", "投标折扣"),
            Map.entry("10", "让利幅度"), Map.entry("11", "合理低价"));

    private final ObjectMapper objectMapper;

    public TenderScoreRuleNormalizer(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /** 保留原始规则，并补充中文分类、分类总分和可直接编入招标文件的规则说明。 */
    public List<Map<String, Object>> normalize(List<Map<String, Object>> scoreRules) {
        Map<String, BigDecimal> totals = scoreRules.stream().collect(Collectors.groupingBy(
                rule -> text(rule.get("scoreType")),
                Collectors.reducing(BigDecimal.ZERO,
                        rule -> decimal(rule.get("totalScore")), BigDecimal::add)));
        return scoreRules.stream().map(rule -> normalize(rule, totals)).toList();
    }

    private Map<String, Object> normalize(
            Map<String, Object> scoreRule, Map<String, BigDecimal> totals) {
        Map<String, Object> result = new LinkedHashMap<>(scoreRule);
        String scoreType = text(scoreRule.get("scoreType"));
        if (SCORE_TYPES.containsKey(scoreType)) {
            result.put("scoreTypeName", SCORE_TYPES.get(scoreType));
            result.put("scoreTypeTotal", totals.get(scoreType));
        }
        if ("1".equals(scoreType)) {
            String title = PRICE_TITLES.get(text(scoreRule.get("scoreTitle")));
            if (title != null) {
                result.put("displayScoreTitle", title);
            }
        }
        String displayRule = displayRule(scoreRule);
        if (!displayRule.isBlank()) {
            result.put("displayScoreRule", displayRule);
        }
        return result;
    }

    private String displayRule(Map<String, Object> scoreRule) {
        String componentType = text(scoreRule.get("componentType"));
        String rawRule = text(scoreRule.get("scoreRule"));
        try {
            return switch (componentType) {
                case "deviationRule" -> deviationRule(rawRule);
                case "multiAccordRule" -> multiAccordRule(rawRule);
                case "customRule" -> customRule(rawRule);
                case "textRule" -> textRule(rawRule);
                case "priceRule" -> priceRule(text(scoreRule.get("scoreTitle")), rawRule);
                case "Y" -> "实质性参数不允许负偏离，负偏离时按无效响应处理";
                case "Z" -> parameterRule("重要参数", rawRule);
                case "N" -> parameterRule("一般参数", rawRule);
                case "deviceRule", "accordRule", "companyLevelRule", "qualityRule", "pjAuto" ->
                    readableJsonRule(rawRule);
                default -> rawRule;
            };
        }
        catch (Exception exception) {
            return rawRule;
        }
    }

    private String priceRule(String priceType, String rawRule) {
        return switch (priceType) {
            case "2" -> "价格分=最低投标价/投标报价×价格分权重";
            case "4" -> "偏差率=(投标报价-(平均报价+预算价)/2)/((平均报价+预算价)/2)×100%；正偏差每增加3%扣6分，负偏差每减少3%扣3分";
            case "5" -> "评标基准价=(投标报价平均价+预算价)/2；价格分=(1-|投标报价-评标基准价|/评标基准价)×价格分权重";
            case "7" -> "价格分=最低当量价/投标报价相应当量价×价格分权重";
            case "8" -> "价格分=投标报价/最高投标价×价格分权重";
            default -> rawRule;
        };
    }

    private String parameterRule(String level, String rawRule) throws JsonProcessingException {
        String rule = readableJsonRule(rawRule);
        return rule.isBlank() ? level : level + "：" + rule;
    }

    /** 对没有专用公式的组件仅展开已有明细，不推测数据库未保存的计算口径。 */
    private String readableJsonRule(String rawRule) throws JsonProcessingException {
        if (rawRule.isBlank()) {
            return "";
        }
        JsonNode root = objectMapper.readTree(rawRule);
        JsonNode children = root.path("child");
        if (!children.isArray()) {
            return textRule(rawRule);
        }
        List<String> rules = new ArrayList<>();
        for (JsonNode child : children) {
            String label = child.path("label").asText();
            String value = child.path("value").asText();
            String score = child.path("score").asText();
            if (!label.isBlank()) {
                rules.add(label + (!value.isBlank() ? "：" + value + "分" : ""));
            }
            else if (!value.isBlank() || !score.isBlank()) {
                rules.add(!score.isBlank() ? value + "，得" + score + "分" : value + "分");
            }
        }
        return rules.isEmpty() ? rawRule : String.join("；", rules);
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

    private BigDecimal decimal(Object value) {
        try {
            return value == null ? BigDecimal.ZERO : new BigDecimal(String.valueOf(value));
        }
        catch (NumberFormatException ignored) {
            return BigDecimal.ZERO;
        }
    }
}
