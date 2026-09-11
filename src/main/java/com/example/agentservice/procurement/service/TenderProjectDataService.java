package com.example.agentservice.procurement.service;

import com.baomidou.dynamic.datasource.annotation.DS;
import com.example.agentservice.procurement.persistence.TenderProjectEntity;
import com.example.agentservice.procurement.persistence.TenderProjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/** 从旧招标业务库汇总 AI 生成初稿需要的模板和项目数据。 */
@DS("master")
@Service
public class TenderProjectDataService {

    private static final Map<String, String> TEMPLATE_TYPES = Map.of("1", "30", "2", "31", "3", "32");

    private final TenderProjectMapper mapper;
    private final ObjectMapper objectMapper;

    public TenderProjectDataService(TenderProjectMapper mapper, ObjectMapper objectMapper) {
        this.mapper = mapper;
        this.objectMapper = objectMapper;
    }

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
        data.set("projectDates", tree(mapper.selectDates(projectId)));
        data.set("projectBatches", tree(mapper.selectBatches(projectId)));
        data.set("items", tree(items(projectId)));
        data.set("qualifications", tree(mapper.selectQualifications(projectId)));
        data.set("scoreRules", tree(mapper.selectScoreRules(projectId)));
        data.set("projectComments", tree(mapper.selectComments(projectId)));
        data.set("requirements", tree(mapper.selectRequirements(projectId)));
        data.set("requirementDetails", tree(mapper.selectRequirementDetails(projectId)));
        return new GenerationInput(
                String.valueOf(template.get("templateId")),
                cleanupTemplate(String.valueOf(template.get("templateHtml"))),
                data);
    }

    /**
     * 移除模板中的 FreeMarker 指令，避免指令语法进入模型输入。
     *
     * <p>模板把 {@code <#list>/<#if>/<#else>/</#if>} 写在 HTML 注释里：只删注释定界符会让指令原样裸露，
     * 整块删注释又会连带丢掉注释内的变量；因此先移除指令本身，再清掉剩余的注释标记。</p>
     */
    static String cleanupTemplate(String template) {
        if (template == null) {
            return null;
        }
        return template.replaceAll("(?s)</?#[^>]*>", "").replace("<!--", "").replace("-->", "");
    }

    private List<Map<String, Object>> items(String projectId) {
        List<Map<String, Object>> parameters = normalize(mapper.selectItemParameters(projectId));
        List<Map<String, Object>> attachments = normalize(mapper.selectAttachments(projectId));
        List<Map<String, Object>> items = normalize(mapper.selectItems(projectId));
        items.forEach(item -> {
            String itemId = String.valueOf(item.get("itemId"));
            item.put("parameters", related(parameters, itemId));
            item.put("attachments", related(attachments, itemId));
        });
        return items;
    }

    private List<Map<String, Object>> related(List<Map<String, Object>> rows, String itemId) {
        return rows.stream().filter(row -> itemId.equals(String.valueOf(row.get("itemId")))).toList();
    }

    private JsonNode tree(List<Map<String, Object>> rows) {
        return objectMapper.valueToTree(normalize(rows));
    }

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
