package com.example.agentservice.procurement.bid.domain;

import java.util.List;

/** 从招标文件中抽取、供技术方案编制使用的核心事实。 */
public record TenderEssentialFacts(
        Project project,
        List<Requirement> technicalRequirements,
        List<Requirement> deliveryRequirements,
        List<ScoringItem> technicalScoring,
        List<Requirement> responseRequirements,
        List<Risk> rejectionRisks) {

    /** 项目身份及采购范围。 */
    public record Project(
            String projectName,
            String projectCode,
            String purchaser,
            String budget,
            String procurementScope) {
    }

    /** 带原文位置的客观要求。 */
    public record Requirement(
            String name,
            String content,
            String location,
            String excerpt) {
    }

    /** 与技术方案编制直接相关的评分项。 */
    public record ScoringItem(
            String name,
            String score,
            String scoringRule,
            String responseFocus,
            String location) {
    }

    /** 可能导致无效响应或扣分的明确约束。 */
    public record Risk(
            String name,
            String consequence,
            String requirement,
            String location,
            String excerpt) {
    }
}
