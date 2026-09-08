package com.example.agentservice.entity;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.util.List;

/**
 * 围标串标基础信息审查的精简事实摘要。
 *
 * <p>该模型输出专门面向最终八张表和法律知识库检索，避免逐页回传设备、业绩、人员等
 * 与汇总无直接关系的完整明细。</p>
 */
public record CibBasicInfoFacts(
        String supplierName,
        String documentRole,
        QuoteSummary quoteSummary,
        List<QuoteRoundFact> quoteRounds,
        List<ReferencePriceFact> referencePrices,
        List<AssociationFact> associationFacts,
        List<DocumentFeatureFact> documentFeatures,
        List<PriceItemFact> keyPriceItems,
        List<DocumentLayoutFact> documentLayoutFacts,
        List<TextSimilarityFact> textSimilarityFacts,
        List<LegalRetrievalClue> legalRetrievalClues) {

    /** 首次和最终报价及其可回溯证据，直接服务于报告第一、三张表。 */
    public record QuoteSummary(
            String firstQuoteAmount,
            String firstQuoteLocation,
            String firstQuoteExcerpt,
            String finalQuoteAmount,
            String finalQuoteLocation,
            String finalQuoteExcerpt) {
    }

    /** 同一文件中每一轮可确认归属当前投标人的投标总价。 */
    public record QuoteRoundFact(
            String roundType,
            String amount,
            String currency,
            String quoteDate,
            String location,
            String excerpt) {
    }

    /** 项目预算、最高限价或控制价，用于计算投标总价偏差率。 */
    public record ReferencePriceFact(
            String referenceType,
            String amount,
            String location,
            String excerpt) {
    }

    /**
     * 仅保留可用于跨投标人关联核验的主体信息。
     * category 只能为 ENTERPRISE_NAME、LEGAL_REPRESENTATIVE、AUTHORIZED_REPRESENTATIVE、
     * CONTACT、ADDRESS、BANK_ACCOUNT、QUALIFICATION。
     */
    public record AssociationFact(
            String category,
            String value,
            String details,
            String location,
            String excerpt,
            String contextType) {
    }

    /** 仅保留投标总价之外，可能影响报价结构对比的关键分项。 */
    public record PriceItemFact(
            String itemName,
            String itemCode,
            String specification,
            String unit,
            String quantity,
            String unitPrice,
            String totalPrice,
            String quoteRound,
            String taxIncluded,
            String pricingBasis,
            String tableName,
            String location,
            String excerpt,
            String contextType) {
    }

    /** 非报价文件中可能用于后续核验的声明、授权、资质、业绩或签章等客观事实。 */
    public record DocumentFeatureFact(
            String documentCategory,
            String factType,
            String value,
            String location,
            String excerpt,
            String contextType) {
    }

    /** 页面中可跨投标人比对的页眉页脚、字体、段落、表格和版式事实。 */
    public record DocumentLayoutFact(
            String pageHeader,
            String pageFooter,
            String pageNumber,
            String fontAndStyle,
            String paragraphAndIndentation,
            String tableLayout,
            String location,
            String excerpt) {
    }

    /** 可用于跨文件核验的非通用表述、共同错误或疑似模板残留。 */
    public record TextSimilarityFact(
            String signalType,
            String text,
            String normalizedText,
            String location,
            String excerpt) {
    }

    /** 从页面事实归纳的法律知识库检索条件，不代表已经作出违法认定。 */
    public record LegalRetrievalClue(
            String riskCategory,
            String observedIssue,
            String applicableScenario,
            @JsonFormat(with = JsonFormat.Feature.ACCEPT_SINGLE_VALUE_AS_ARRAY)
            List<String> keywords) {
    }
}
