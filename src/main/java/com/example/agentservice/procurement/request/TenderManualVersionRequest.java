package com.example.agentservice.procurement.request;

import io.swagger.v3.oas.annotations.media.Schema;

/** Operator-authored Markdown content saved as a child version of a tender draft. */
public record TenderManualVersionRequest(
        @Schema(description = "人工编辑后的完整 Markdown 招标文件", requiredMode = Schema.RequiredMode.REQUIRED)
        String markdown,
        @Schema(description = "修改人标识；未传时记为 operator", example = "zhangsan")
        String changedBy
) {
    public TenderManualVersionRequest {
        if (markdown == null || markdown.isBlank()) {
            throw new IllegalArgumentException("markdown 必须是完整且非空的 Markdown 内容");
        }
    }
}
