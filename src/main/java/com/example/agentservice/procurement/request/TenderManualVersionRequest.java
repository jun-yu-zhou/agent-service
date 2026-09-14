package com.example.agentservice.procurement.request;

import io.swagger.v3.oas.annotations.media.Schema;

/** 人工编辑后覆盖保存的完整 Markdown 正文。 */
public record TenderManualVersionRequest(
        @Schema(description = "人工编辑后的完整 Markdown 招标文件", requiredMode = Schema.RequiredMode.REQUIRED)
        String markdown
) {
    public TenderManualVersionRequest {
        if (markdown == null || markdown.isBlank()) {
            throw new IllegalArgumentException("markdown 必须是完整且非空的 Markdown 内容");
        }
    }
}
