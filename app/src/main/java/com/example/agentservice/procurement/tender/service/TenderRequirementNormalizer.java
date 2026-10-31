package com.example.agentservice.procurement.tender.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Component;

/** 统一技术参数和商务要求中的重要性、展示标记及证明材料要求。 */
@Component
public class TenderRequirementNormalizer {

    private final ObjectMapper objectMapper;

    public TenderRequirementNormalizer(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * 增强原始明细并按实质性、重要和一般要求生成分组视图。
     * 传入集合中的每条明细会同步获得展示字段，供品目明细和分组视图共同使用。
     *
     * @param details 技术参数或商务要求明细
     * @return 包含三类分组及证明材料数量的业务事实
     */
    public ObjectNode normalize(List<Map<String, Object>> details) {
        details.forEach(this::enrich);
        ObjectNode facts = objectMapper.createObjectNode();
        facts.set("substantive", group(details, "Y"));
        facts.set("important", group(details, "Z"));
        facts.set("general", objectMapper.valueToTree(details.stream()
                .filter(detail -> !"Y".equals(code(detail)) && !"Z".equals(code(detail)))
                .toList()));
        facts.put("evidenceRequiredCount", details.stream()
                .filter(detail -> Boolean.TRUE.equals(detail.get("requiresEvidence"))).count());
        return facts;
    }

    /** 将 Y/Z/N 转换为中文等级、★/▲ 标记和证明材料布尔值。 */
    private void enrich(Map<String, Object> detail) {
        String code = code(detail);
        String mark = switch (code) {
            case "Y" -> "★";
            case "Z" -> "▲";
            default -> "";
        };
        detail.put("importanceName", switch (code) {
            case "Y" -> "实质性要求";
            case "Z" -> "重要要求";
            default -> "一般要求";
        });
        detail.put("importanceMark", mark);
        detail.put("requiresEvidence", "Y".equalsIgnoreCase(text(detail.get("isNeedFile"))));
        String content = text(detail.get("orderParamContent"));
        if (!content.isBlank()) {
            detail.put("displayContent", mark + content);
        }
    }

    /** 按重要性编码筛选明细，并保持原有字段和排序。 */
    private ArrayNode group(List<Map<String, Object>> details, String importance) {
        return objectMapper.valueToTree(details.stream()
                .filter(detail -> importance.equals(code(detail))).toList());
    }

    /** 空值和未知编码按一般要求处理。 */
    private String code(Map<String, Object> detail) {
        return text(detail.get("isImportant")).toUpperCase();
    }

    private String text(Object value) {
        return Objects.toString(value, "").trim();
    }
}
