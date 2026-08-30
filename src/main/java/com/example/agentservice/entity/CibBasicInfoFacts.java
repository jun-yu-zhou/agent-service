package com.example.agentservice.entity;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.util.List;

/** 从单份投标文件的正文和页面图片中提取的基础信息事实。 */
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

    public record EvidenceValue(
            String value,
            String location,
            String excerpt) {
    }

    public record PersonFact(
            String name,
            String role,
            String certificateNumber,
            String phone,
            String location,
            String excerpt) {
    }

    public record ContactFact(
            String name,
            String phone,
            String email,
            String location,
            String excerpt) {
    }

    public record CertificateFact(
            String certificateName,
            String certificateNumber,
            String holder,
            String location,
            String excerpt) {
    }

    public record BankAccountFact(
            String bankName,
            String accountName,
            String accountNumber,
            String location,
            String excerpt) {
    }

    public record QuoteFact(
            String quoteType,
            String amount,
            String currency,
            String location,
            String excerpt) {
    }

    public record PriceItemFact(
            String itemName,
            String specification,
            String unit,
            String quantity,
            String unitPrice,
            String totalPrice,
            String location,
            String excerpt) {
    }

    public record AchievementFact(
            String projectName,
            String client,
            String amount,
            String date,
            String location,
            String excerpt) {
    }

    public record TextAnomalyFact(
            String text,
            String type,
            String location,
            String excerpt) {
    }

    /** 从审查事实中归纳的法律知识库检索条件，不代表已经作出违法认定。 */
    public record LegalRetrievalClue(
            String riskCategory,
            String observedIssue,
            String applicableScenario,
            @JsonFormat(with = JsonFormat.Feature.ACCEPT_SINGLE_VALUE_AS_ARRAY)
            List<String> keywords) {
    }

    public record LegalBasisFact(
            String lawName,
            String article,
            String contentSummary,
            String applicableScenario,
            String location,
            String excerpt) {
    }
}
