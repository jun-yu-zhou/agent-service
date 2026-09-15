package com.example.agentservice.procurement.bid.request;

import com.fasterxml.jackson.databind.JsonNode;

/** 前端完成 OSS 直传后提交的技术方案生成参数。 */
public record BidDocumentCreateRequest(
        String sourceFileName,
        String sourceUrl,
        JsonNode supplierFacts,
        String zbProjectId,
        String companyId) {

    /** 保留手填企业资料的本地对照测试入口。 */
    public BidDocumentCreateRequest(String sourceFileName, String sourceUrl, JsonNode supplierFacts) {
        this(sourceFileName, sourceUrl, supplierFacts, null, null);
    }
}
