package com.example.agentservice.procurement.service;

import com.example.agentservice.procurement.persistence.TenderProjectEntity;
import com.example.agentservice.procurement.persistence.TenderProjectMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TenderProjectDataServiceTest {

    private final TenderProjectMapper mapper = mock(TenderProjectMapper.class);
    private final TenderProjectDataService service = new TenderProjectDataService(mapper, new ObjectMapper());

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
        when(mapper.selectProject("project-1"))
                .thenReturn(Map.of("ZB_PROJECT_ID", "project-1", "PROJECT_NAME", "家具采购"));
        when(mapper.selectItems("project-1")).thenReturn(List.of(Map.of("ITEM_ID", "item-1")));
        when(mapper.selectItemParameters("project-1"))
                .thenReturn(List.of(Map.of("ITEM_ID", "item-1", "PARAM_NAME", "尺寸")));
        when(mapper.selectAttachments("project-1"))
                .thenReturn(List.of(Map.of("ITEM_ID", "item-1", "FILE_NAME", "图纸.pdf")));

        TenderProjectDataService.GenerationInput input = service.load("project-1");

        assertEquals("template-1", input.templateId());
        assertEquals("${ZbProject.projectName}", input.templateHtml());
        assertEquals("家具采购", input.projectData().get("projectName").asText());
        assertEquals("尺寸", input.projectData().path("items").get(0)
                .path("parameters").get(0).path("paramName").asText());
        assertEquals("图纸.pdf", input.projectData().path("items").get(0)
                .path("attachments").get(0).path("fileName").asText());
        verify(mapper).selectTemplate("college-1", "30");
    }

    @Test
    void rejectsMissingProject() {
        assertThrows(IllegalArgumentException.class, () -> service.load("missing"));
    }

    @Test
    void removesCommentedDirectivesAndKeepsBranchText() {
        String html = "<td><!-- <#if zbItem.isCore?has_content && zbItem.isCore == '1'> -->是<!-- <#else> -->否<!-- </#if> --></td>";

        String cleaned = TenderProjectDataService.cleanupTemplate(html);

        assertFalse(cleaned.contains("<#"));
        assertTrue(cleaned.contains("是"));
        assertTrue(cleaned.contains("否"));
    }

    @Test
    void removesLoopDirectives() {
        String html = "<!--<#list ZbProject.zbItems as zbItem>--><p>${zbItem.itemName}</p><!--</#list>-->";

        String cleaned = TenderProjectDataService.cleanupTemplate(html);

        assertEquals("<p>${zbItem.itemName}</p>", cleaned);
    }
}
