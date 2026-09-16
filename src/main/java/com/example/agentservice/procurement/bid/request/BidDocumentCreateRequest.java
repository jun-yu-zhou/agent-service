package com.example.agentservice.procurement.bid.request;

/** 前端完成 OSS 直传后提交的技术方案生成参数。 */
public record BidDocumentCreateRequest(
        String sourceFileName,
        String sourceUrl,
        String zbProjectId,
        String companyId) {
}
