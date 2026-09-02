package com.example.agentservice.entity;

import java.util.List;

/** 围标串标审查 Agent 的五个专项结论，供报告阶段直接引用。 */
public record CibCollusionReviewResult(
        List<DimensionReview> dimensionReviews,
        List<CibBasicInfoFacts.LegalRetrievalClue> legalRetrievalClues) {

    /** dimension 固定为 LAYOUT、PAGE_NUMBER、QUOTE、PRICE_ITEM、TEXT_SIMILARITY。 */
    public record DimensionReview(
            String dimension,
            String riskLevel,
            String summary,
            List<Finding> findings) {
    }

    /** 一条结论必须具备可回溯到文件和页码的证据。 */
    public record Finding(
            String riskType,
            String riskLevel,
            String summary,
            String confidence,
            List<String> supplierNames,
            List<Evidence> evidences) {
    }

    public record Evidence(
            String documentId,
            String supplierName,
            String location,
            String excerpt) {
    }
}
