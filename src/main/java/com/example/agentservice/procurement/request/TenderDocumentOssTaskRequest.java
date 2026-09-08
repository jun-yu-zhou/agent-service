package com.example.agentservice.procurement.request;

import com.fasterxml.jackson.databind.JsonNode;
import io.swagger.v3.oas.annotations.media.Schema;

/** 根据 IMM 可访问的 OSS 文档地址生成招标文件初稿的请求。 */
public record TenderDocumentOssTaskRequest(
        @Schema(description = "IMM 可访问的招标文件 OSS URL", example = "https://example.oss-cn-beijing.aliyuncs.com/tender.docx")
        String documentOssUrl,
        @Schema(description = "旧版项目表单提交的完整结构化 JSON；与大纲冲突时优先使用")
        JsonNode projectData
) {
    public TenderDocumentOssTaskRequest {
        if (documentOssUrl == null || documentOssUrl.isBlank()) {
            throw new IllegalArgumentException("documentOssUrl 不能为空");
        }
    }
}
