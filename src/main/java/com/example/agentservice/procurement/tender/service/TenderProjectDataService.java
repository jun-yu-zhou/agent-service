package com.example.agentservice.procurement.tender.service;

import com.baomidou.dynamic.datasource.annotation.DS;
import com.example.agentservice.procurement.tender.persistence.TenderProjectEntity;
import com.example.agentservice.procurement.tender.persistence.TenderProjectMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;

/** 从旧招标业务库汇总 AI 生成初稿需要的模板和项目数据。 */
@DS("master")
@Service
public class TenderProjectDataService {

    private static final Map<String, String> TEMPLATE_TYPES = Map.of("1", "30", "2", "31", "3", "32");

    private final TenderProjectMapper mapper;
    private final ObjectMapper objectMapper;
    private final TenderScoreRuleNormalizer scoreRuleNormalizer;
    private final TenderProjectBusinessNormalizer businessNormalizer;
    private final TenderRequirementNormalizer requirementNormalizer;

    public TenderProjectDataService(TenderProjectMapper mapper, ObjectMapper objectMapper,
            TenderScoreRuleNormalizer scoreRuleNormalizer,
            TenderProjectBusinessNormalizer businessNormalizer,
            TenderRequirementNormalizer requirementNormalizer) {
        this.mapper = mapper;
        this.objectMapper = objectMapper;
        this.scoreRuleNormalizer = scoreRuleNormalizer;
        this.businessNormalizer = businessNormalizer;
        this.requirementNormalizer = requirementNormalizer;
    }

    /**
     * 加载初稿生成所需的模板与项目数据：按项目类型定位模板并清理其中的 FreeMarker 指令，
     * 再把基础信息、日期、批次、标的物、资格、评分规则、评论及要求等归并为结构化 JSON。
     * 项目不存在、项目类型不支持或未设置模板时抛出异常。
     *
     * @param projectId 招标项目 ID
     * @return 含模板 ID、清理后模板 HTML 与汇总项目数据的生成输入
     */
    public GenerationInput load(String projectId) {
        TenderProjectEntity project = mapper.selectById(projectId);
        if (project == null || !"1".equals(project.getDelFlag())) {
            throw new IllegalArgumentException("招标项目不存在：" + projectId);
        }
        String templateType = TEMPLATE_TYPES.get(project.getProjectType());
        if (templateType == null) {
            throw new IllegalArgumentException("不支持的招标项目类型：" + project.getProjectType());
        }
        Map<String, Object> template = mapper.selectTemplate(project.getCollegeId(), templateType);
        if (template == null || template.get("templateId") == null
                || template.get("templateHtml") == null
                || String.valueOf(template.get("templateHtml")).isBlank()) {
            throw new IllegalArgumentException("未设置招标文件模板");
        }

        Map<String, Object> projectData = normalize(mapper.selectProject(projectId));
        ObjectNode data = objectMapper.valueToTree(projectData);
        // 项目时间（公告、报名、投标、开标等）
        List<Map<String, Object>> projectDates = normalize(mapper.selectDates(projectId));
        data.set("projectDates", objectMapper.valueToTree(projectDates));
        // 项目分包/批次
        data.set("projectBatches", tree(mapper.selectBatches(projectId)));
        List<Map<String, Object>> parameters = normalize(mapper.selectItemParameters(projectId));
        data.set("technicalRequirementFacts", requirementNormalizer.normalize(parameters));
        List<Map<String, Object>> attachments = normalize(mapper.selectAttachments(projectId));
        List<Map<String, Object>> items = items(projectId, parameters, attachments);
        // 采购品目（含技术参数与品目附件）
        data.set("items", objectMapper.valueToTree(items));
        // 未归属具体品目的工程、服务参数
        data.set("projectParameters", objectMapper.valueToTree(unrelated(parameters, itemIds(items))));
        // 项目级附件及无法归属具体品目的附件
        data.set("projectAttachments", objectMapper.valueToTree(
                projectAttachments(attachments, itemIds(items))));
        // 投标人资格条件
        data.set("qualifications", tree(mapper.selectQualifications(projectId)));
        // 评分项、分值与评分规则（补充可读的规则说明）
        List<Map<String, Object>> scoreRules = scoreRuleNormalizer.normalize(
                normalize(mapper.selectScoreRules(projectId)));
        data.set("scoreRules", objectMapper.valueToTree(scoreRules));
        data.set("scoreRuleFacts", scoreRuleNormalizer.summarize(scoreRules));
        // 各业务页签保存的补充内容
        List<Map<String, Object>> comments = projectComments(projectId);
        data.set("projectComments", objectMapper.valueToTree(comments));
        // 可直接写入正文的中文业务事实
        data.set("documentFacts", businessNormalizer.normalize(projectData, comments,
                normalize(mapper.selectCapitalSources(project.getCollegeId())), projectDates, items));
        // 商务和履约要求
        data.set("requirements", tree(mapper.selectRequirements(projectId)));
        // 商务和履约要求明细条目
        List<Map<String, Object>> requirementDetails =
                normalize(mapper.selectRequirementDetails(projectId));
        data.set("commercialRequirementFacts",
                requirementNormalizer.normalize(requirementDetails));
        data.set("requirementDetails", objectMapper.valueToTree(requirementDetails));
        return new GenerationInput(
                String.valueOf(template.get("templateId")),
                cleanupTemplate(String.valueOf(template.get("templateHtml"))),
                data);
    }

    /**
     * 去掉包裹 FreeMarker 指令的 HTML 注释，保留条件和循环语义供模型展开。
     *
     * <p>模板将 {@code <#list>/<#if>/<#else>/</#if>} 写在 HTML 注释中。仅移除注释定界符，
     * 可以避免互斥分支和循环范围丢失。</p>
     */
    static String cleanupTemplate(String template) {
        if (template == null) {
            return null;
        }
        return template.replace("<!--", "").replace("-->", "");
    }

    /** 组装采购品目，并为每个品目挂载技术参数与关联附件。 */
    private List<Map<String, Object>> items(String projectId, List<Map<String, Object>> parameters,
            List<Map<String, Object>> attachments) {
        // 采购品目基础信息
        List<Map<String, Object>> items = normalize(mapper.selectItems(projectId));
        items.forEach(item -> {
            String itemId = String.valueOf(item.get("itemId"));
            item.put("parameters", related(parameters, itemId));
            item.put("attachments", related(attachments, itemId).stream()
                    .filter(this::isItemAttachment).toList());
        });
        return items;
    }

    /** 将补充资料中的 JSON 正文展开为对象或数组，普通文本保持原样。 */
    private List<Map<String, Object>> projectComments(String projectId) {
        return normalize(mapper.selectComments(projectId)).stream().map(comment -> {
            Object content = comment.get("comments");
            if (content instanceof String text && !text.isBlank()) {
                try {
                    comment.put("comments", objectMapper.readTree(text));
                }
                catch (JsonProcessingException ignored) {
                    // 部分历史数据直接保存富文本或普通文字，保留原文交给模型处理。
                }
            }
            return comment;
        }).toList();
    }

    private List<Map<String, Object>> projectAttachments(
            List<Map<String, Object>> attachments, Set<String> itemIds) {
        return attachments.stream()
                .filter(attachment -> !isItemAttachment(attachment)
                        || !itemIds.contains(text(attachment.get("itemId"))))
                .toList();
    }

    private List<Map<String, Object>> unrelated(
            List<Map<String, Object>> rows, Set<String> itemIds) {
        return rows.stream()
                .filter(row -> !itemIds.contains(text(row.get("itemId"))))
                .toList();
    }

    private Set<String> itemIds(List<Map<String, Object>> items) {
        Set<String> itemIds = new HashSet<>();
        items.forEach(item -> itemIds.add(text(item.get("itemId"))));
        itemIds.remove("");
        return itemIds;
    }

    private boolean isItemAttachment(Map<String, Object> attachment) {
        return "1".equals(text(attachment.get("attachmentType")));
    }

    private String text(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private List<Map<String, Object>> related(List<Map<String, Object>> rows, String itemId) {
        return rows.stream().filter(row -> itemId.equals(String.valueOf(row.get("itemId")))).toList();
    }

    private JsonNode tree(List<Map<String, Object>> rows) {
        return objectMapper.valueToTree(normalize(rows));
    }

    /**
     * 批量归一化查询结果，逐行将数据库列名转为 camelCase 字段。
     *
     * @param rows 数据库查询结果行集合
     * @return 列名归一化后的行集合
     */
    private List<Map<String, Object>> normalize(List<Map<String, Object>> rows) {
        return rows.stream().map(this::normalize).toList();
    }

    /** 数据库列名统一转成现有提示词使用的 camelCase 字段。 */
    private Map<String, Object> normalize(Map<String, Object> row) {
        Map<String, Object> normalized = new LinkedHashMap<>();
        row.forEach((key, value) -> normalized.put(camelCase(key), value));
        return normalized;
    }

    private String camelCase(String value) {
        StringBuilder result = new StringBuilder(value.length());
        boolean upper = false;
        for (char character : value.toLowerCase().toCharArray()) {
            if (character == '_') {
                upper = true;
            } else {
                result.append(upper ? Character.toUpperCase(character) : character);
                upper = false;
            }
        }
        return result.toString();
    }

    public record GenerationInput(
            /** 本次生成使用的数据库模板 ID。 */
            String templateId,

            /** 清理 FreeMarker 指令后的 HTML 模板正文。 */
            String templateHtml,

            /** 从旧业务表汇总出的结构化项目数据。 */
            JsonNode projectData) {
    }
}
