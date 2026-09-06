package com.example.agentservice.procurement.request;

import io.swagger.v3.oas.annotations.media.Schema;

/** Request for generating a bid draft from tender and supplier texts. */
public record BidDraftPreviewRequest(
        @Schema(description = "已确认的招标文件正文", example = "项目名称：示例采购项目\n★投标人须提供有效营业执照。")
        String tenderText,
        @Schema(description = "供应商已知资料与证明材料摘要", example = "供应商名称：示例科技有限公司\n已提供营业执照。")
        String supplierText
) {
    public BidDraftPreviewRequest {
        if (tenderText == null || tenderText.isBlank()) {
            throw new IllegalArgumentException("tenderText 不能为空");
        }
        if (supplierText == null || supplierText.isBlank()) {
            throw new IllegalArgumentException("supplierText 不能为空");
        }
    }
}
