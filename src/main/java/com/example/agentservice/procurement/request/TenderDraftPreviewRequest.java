package com.example.agentservice.procurement.request;

import com.fasterxml.jackson.databind.JsonNode;
import io.swagger.v3.oas.annotations.media.Schema;

/** 根据调用方提供的来源正文生成招标文件初稿的请求。 */
public record TenderDraftPreviewRequest(
        @Schema(
                description = "招标大纲、结构化数据转换后的正文或人工整理文本",
                example = "项目名称：示例采购项目\n预算：100万元\n★投标人须提供有效营业执照。"
        ) String sourceText,
        @Schema(description = "旧版项目表单提交的完整结构化 JSON；与大纲冲突时优先使用")
        JsonNode projectData
) {
    public TenderDraftPreviewRequest {
        if (sourceText == null || sourceText.isBlank()) {
            throw new IllegalArgumentException("sourceText 不能为空");
        }
    }
}
