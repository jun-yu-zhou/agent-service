package com.example.agentservice.procurement.tender.service;

import com.example.agentservice.procurement.tender.persistence.TenderProjectEntity;
import com.example.agentservice.procurement.tender.persistence.TenderProjectMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TenderProjectDataServiceTest {

    private final TenderProjectMapper mapper = mock(TenderProjectMapper.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final TenderProjectDataService service = new TenderProjectDataService(
            mapper, objectMapper, new TenderScoreRuleNormalizer(objectMapper),
            new TenderProjectBusinessNormalizer(objectMapper),
            new TenderRequirementNormalizer(objectMapper));

    @Test
    void loadsTemplateAndNestedProjectData() {
        TenderProjectEntity project = new TenderProjectEntity();
        project.setZbProjectId("project-1");
        project.setCollegeId("college-1");
        project.setProjectType("1");
        project.setDelFlag("1");
        when(mapper.selectById("project-1")).thenReturn(project);
        when(mapper.selectTemplate("college-1", "30")).thenReturn(Map.of(
                "templateId", "template-1",
                "templateHtml", "<!--${ZbProject.projectName}-->"));
        when(mapper.selectProject("project-1")).thenReturn(Map.of(
                "ZB_PROJECT_ID", "project-1", "PROJECT_NAME", "家具采购",
                "PROJECT_TYPE", "1", "CLASSIFY_CODE", "1", "FUNDING_SOURCE", "fund-1",
                "PURCHASE_MONEY", "550000.00", "IS_ACCEPT_INPUT", "0"));
        when(mapper.selectCapitalSources("college-1"))
                .thenReturn(List.of(Map.of("CS_ID", "fund-1", "CS_NAME", "财政资金")));
        when(mapper.selectItems("project-1")).thenReturn(List.of(Map.of("ITEM_ID", "item-1")));
        when(mapper.selectItemParameters("project-1"))
                .thenReturn(List.of(Map.of("ITEM_ID", "item-1", "PARAM_NAME", "尺寸",
                        "ORDER_PARAM_CONTENT", "长度不低于2米", "IS_IMPORTANT", "Y",
                        "IS_NEED_FILE", "Y")));
        when(mapper.selectAttachments("project-1"))
                .thenReturn(List.of(
                        Map.of("ITEM_ID", "item-1", "ATTACHMENT_TYPE", "1", "FILE_NAME", "图纸.pdf"),
                        Map.of("ITEM_ID", "", "ATTACHMENT_TYPE", "2", "FILE_NAME", "项目说明.pdf")));
        when(mapper.selectComments("project-1")).thenReturn(List.of(Map.of(
                "COMMENTS_TYPE", "38", "COMMENTS", "[{\"qualificationMessage\":\"按时交付\"}]")));
        when(mapper.selectScoreRules("project-1")).thenReturn(List.of(Map.of(
                "COMPONENT_TYPE", "multiAccordRule",
                "SCORE_RULE", "{\"child\":[{\"label\":\"完全满足\",\"value\":\"5\"}]}")));

        TenderProjectDataService.GenerationInput input = service.load("project-1");

        assertEquals("template-1", input.templateId());
        assertEquals("${ZbProject.projectName}", input.templateHtml());
        assertEquals("家具采购", input.projectData().get("projectName").asText());
        assertEquals("尺寸", input.projectData().path("items").get(0)
                .path("parameters").get(0).path("paramName").asText());
        assertEquals("★长度不低于2米", input.projectData().path("items").get(0)
                .path("parameters").get(0).path("displayContent").asText());
        assertEquals(1, input.projectData().path("technicalRequirementFacts")
                .path("evidenceRequiredCount").asInt());
        assertEquals("图纸.pdf", input.projectData().path("items").get(0)
                .path("attachments").get(0).path("fileName").asText());
        assertEquals("项目说明.pdf", input.projectData().path("projectAttachments").get(0)
                .path("fileName").asText());
        assertEquals("按时交付", input.projectData().path("projectComments").get(0)
                .path("comments").get(0).path("qualificationMessage").asText());
        assertEquals("财政资金", input.projectData().path("documentFacts")
                .path("fundingSourceName").asText());
        assertEquals("不接受", input.projectData().path("documentFacts")
                .path("acceptImportedProducts").asText());
        assertEquals("★按时交付", input.projectData().path("documentFacts")
                .path("substantiveRequirements").get(0).asText());
        assertEquals("完全满足得5分", input.projectData().path("scoreRules").get(0)
                .path("displayScoreRule").asText());
        verify(mapper).selectTemplate("college-1", "30");
    }

    @Test
    void rejectsMissingProject() {
        assertThrows(IllegalArgumentException.class, () -> service.load("missing"));
    }

    @Test
    void unwrapsCommentedDirectivesAndKeepsBranchSemantics() {
        String html = "<td><!-- <#if zbItem.isCore?has_content && zbItem.isCore == '1'> -->是<!-- <#else> -->否<!-- </#if> --></td>";

        String cleaned = TenderProjectDataService.cleanupTemplate(html);

        assertTrue(cleaned.contains("<#if"));
        assertTrue(cleaned.contains("<#else>"));
        assertTrue(cleaned.contains("</#if>"));
        assertTrue(cleaned.contains("是"));
        assertTrue(cleaned.contains("否"));
    }

    @Test
    void unwrapsCommentedLoopDirectives() {
        String html = "<!--<#list ZbProject.zbItems as zbItem>--><p>${zbItem.itemName}</p><!--</#list>-->";

        String cleaned = TenderProjectDataService.cleanupTemplate(html);

        assertEquals("<#list ZbProject.zbItems as zbItem><p>${zbItem.itemName}</p></#list>", cleaned);
    }

    @Test
    void keepsParametersWithoutMatchingItem() {
        TenderProjectEntity project = new TenderProjectEntity();
        project.setZbProjectId("project-2");
        project.setCollegeId("college-1");
        project.setProjectType("2");
        project.setDelFlag("1");
        when(mapper.selectById("project-2")).thenReturn(project);
        when(mapper.selectTemplate("college-1", "31")).thenReturn(Map.of(
                "templateId", "template-2", "templateHtml", "工程参数：${paramName}"));
        when(mapper.selectProject("project-2")).thenReturn(Map.of("ZB_PROJECT_ID", "project-2"));
        when(mapper.selectItemParameters("project-2"))
                .thenReturn(List.of(Map.of("ITEM_ID", "missing", "PARAM_NAME", "施工工期")));

        TenderProjectDataService.GenerationInput input = service.load("project-2");

        assertEquals("施工工期", input.projectData().path("projectParameters").get(0)
                .path("paramName").asText());
    }
}
