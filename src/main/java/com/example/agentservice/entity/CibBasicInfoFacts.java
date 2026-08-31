package com.example.agentservice.entity;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.util.List;

/**
 * 从单份投标文件中提取的基础信息事实，用于后续跨投标文件的雷同性、报价和关联风险比对。
 * 每项事实均应通过 location 和 excerpt 回溯到原始材料。
 */
public record CibBasicInfoFacts(
        String sourceDocument,
        String supplierName,
        List<EvidenceValue> enterpriseNames,
        List<PersonFact> legalRepresentatives,
        List<PersonFact> authorizedRepresentatives,
        List<ContactFact> contacts,
        List<EvidenceValue> addresses,
        List<PersonFact> projectPersonnel,
        List<CertificateFact> qualificationCertificates,
        List<BankAccountFact> bankAccounts,
        List<QuoteFact> quotes,
        List<PriceItemFact> priceItems,
        List<EvidenceValue> equipment,
        List<AchievementFact> achievements,
        List<TextAnomalyFact> abnormalExpressions,
        List<TextAnomalyFact> commonErrors,
        List<LegalRetrievalClue> legalRetrievalClues,
        List<LegalBasisFact> legalBases) {

    /**
     * 带定位证据的普通文本事实，如企业名称、地址或设备名称。
     * contextType 用于区分投标人自身、附件合同和项目共用内容，避免将页面出现的名称直接归属投标人。
     */
    public record EvidenceValue(
            String value,
            String location,
            String excerpt,
            String contextType) {
    }

    /** 人员身份、角色和证书信息，用于法定代表人、授权代表人及项目人员比对。 */
    public record PersonFact(
            String name,
            String role,
            String certificateNumber,
            String phone,
            String location,
            String excerpt) {
    }

    /** 对外联络信息，用于电话、邮箱等跨投标人重复核验。 */
    public record ContactFact(
            String name,
            String phone,
            String email,
            String location,
            String excerpt) {
    }

    /** 企业或人员持有的资质、资格和获奖证书信息。 */
    public record CertificateFact(
            String certificateName,
            String certificateNumber,
            String holder,
            String location,
            String excerpt) {
    }

    /**
     * 收款或基本账户信息；只有 contextType 为 BIDDER_SELF 时才能用于跨投标人账户关联审查。
     */
    public record BankAccountFact(
            String bankName,
            String accountName,
            String accountNumber,
            String location,
            String excerpt,
            String contextType) {
    }

    /**
     * 投标报价事实；仅记录文件原文明确出现的报价。
     * contextType 用于排除采购预算、控制价和项目共用清单，只有 BIDDER_SELF 才是投标人自身报价。
     */
    public record QuoteFact(
            String quoteType,
            String amount,
            String currency,
            String location,
            String excerpt,
            String contextType) {
    }

    /**
     * 分项报价表中的一行明细，用于横向比较数量、单价和报价结构。
     * 项目统一清单、控制价或参考价标记为 PROJECT_COMMON，不作为投标人报价比较证据。
     */
    public record PriceItemFact(
            String itemName,
            String specification,
            String unit,
            String quantity,
            String unitPrice,
            String totalPrice,
            String location,
            String excerpt,
            String contextType) {
    }

    /** 业绩项目事实，用于核验项目、业主、金额和实施时间；附件合同中的内容标记为 ATTACHMENT_CONTRACT。 */
    public record AchievementFact(
            String projectName,
            String client,
            String amount,
            String date,
            String location,
            String excerpt,
            String contextType) {
    }

    /**
     * 文本异常事实，供跨文件检索同句、复制残留和共同错别字。
     * 单文件残留仅记录事实；只有多个文件出现同一非共用内容时才可能构成风险证据。
     */
    public record TextAnomalyFact(
            String text,
            String type,
            String location,
            String excerpt,
            String contextType) {
    }

    /** 从审查事实中归纳的法律知识库检索条件，不代表已经作出违法认定。 */
    public record LegalRetrievalClue(
            String riskCategory,
            String observedIssue,
            String applicableScenario,
            @JsonFormat(with = JsonFormat.Feature.ACCEPT_SINGLE_VALUE_AS_ARRAY)
            List<String> keywords) {
    }

    /** 文件原文中直接出现的法律文件或条款引用。 */
    public record LegalBasisFact(
            String lawName,
            String article,
            String contentSummary,
            String applicableScenario,
            String location,
            String excerpt) {
    }
}
