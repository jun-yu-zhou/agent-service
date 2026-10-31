package com.example.agentservice.procurement.bid.domain;

import java.util.List;

/** 技术方案与招标要求的一致性检查结果。 */
public record BidConsistencyReview(
        String conclusion,
        String summary,
        List<ReviewItem> items) {

    /** 一项招标要求在技术方案中的响应情况。 */
    public record ReviewItem(
            String requirement,
            String status,
            String tenderEvidence,
            String documentEvidence,
            String issue,
            String suggestion) {
    }
}
