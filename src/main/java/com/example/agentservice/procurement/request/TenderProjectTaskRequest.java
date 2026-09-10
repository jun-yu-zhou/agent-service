package com.example.agentservice.procurement.request;

import io.swagger.v3.oas.annotations.media.Schema;

/** 使用旧业务系统的招标项目 ID 创建初稿生成任务。 */
public record TenderProjectTaskRequest(
        @Schema(description = "招标项目 ID", requiredMode = Schema.RequiredMode.REQUIRED)
        String id
) {
    public TenderProjectTaskRequest {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("招标项目 ID 不能为空");
        }
        id = id.trim();
    }
}
