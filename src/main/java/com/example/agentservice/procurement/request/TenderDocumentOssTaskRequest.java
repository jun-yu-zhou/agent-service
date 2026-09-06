package com.example.agentservice.procurement.request;

import io.swagger.v3.oas.annotations.media.Schema;

/** Request for generating a tender draft from an IMM-accessible OSS document URL. */
public record TenderDocumentOssTaskRequest(
        @Schema(description = "IMM 可访问的招标文件 OSS URL", example = "https://example.oss-cn-beijing.aliyuncs.com/tender.docx")
        String documentOssUrl
) {
    public TenderDocumentOssTaskRequest {
        if (documentOssUrl == null || documentOssUrl.isBlank()) {
            throw new IllegalArgumentException("documentOssUrl 不能为空");
        }
    }
}
