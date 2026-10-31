package com.example.agentservice.procurement.tender.request;

import com.fasterxml.jackson.databind.JsonNode;

/** 旧业务系统已整理好的模板与项目数据。 */
public record TenderExternalTaskRequest(
        String projectId,
        String templateId,
        String templateHtml,
        JsonNode projectData) {

    public TenderExternalTaskRequest {
        if (projectId == null || projectId.isBlank()
                || templateId == null || templateId.isBlank()
                || templateHtml == null || templateHtml.isBlank()
                || projectData == null || !projectData.isObject()) {
            throw new IllegalArgumentException("项目 ID、模板 ID、HTML 模板和项目数据不能为空");
        }
    }
}
