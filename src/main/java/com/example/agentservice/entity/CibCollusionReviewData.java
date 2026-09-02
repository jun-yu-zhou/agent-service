package com.example.agentservice.entity;

import java.util.List;

/** 围标串标审查前按文件和投标人归并的结构化输入。 */
public record CibCollusionReviewData(
        List<BidderFacts> bidders,
        List<DocumentFacts> unassignedDocuments) {

    /** 同一投标人的多份文件归入同一组，供跨文件判断时统一使用。 */
    public record BidderFacts(
            String supplierName,
            List<DocumentFacts> documents) {
    }

    /** 单一文件的全部批次及合并事实，页码仍保留在批次和事实证据中。 */
    public record DocumentFacts(
            String documentId,
            String supplierName,
            List<BatchFacts> batches,
            CibBasicInfoFacts mergedFacts) {
    }

    /** 一个图片批次的固定来源范围；解析失败的批次facts为null。 */
    public record BatchFacts(
            int batchNumber,
            Integer pageStart,
            Integer pageEnd,
            CibBasicInfoFacts facts) {
    }
}
