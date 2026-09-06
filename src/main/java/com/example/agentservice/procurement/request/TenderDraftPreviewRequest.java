package com.example.agentservice.procurement.request;

import io.swagger.v3.oas.annotations.media.Schema;

/** Request for generating a tender draft from caller-provided source text. */
public record TenderDraftPreviewRequest(
        @Schema(
                description = "招标大纲、结构化数据转换后的正文或人工整理文本",
                example = "项目名称：示例采购项目\n预算：100万元\n★投标人须提供有效营业执照。"
        ) String sourceText
) {
    public TenderDraftPreviewRequest {
        if (sourceText == null || sourceText.isBlank()) {
            throw new IllegalArgumentException("sourceText 不能为空");
        }
    }
}
