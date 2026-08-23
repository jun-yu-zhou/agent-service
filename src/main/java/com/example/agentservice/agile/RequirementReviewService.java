package com.example.agentservice.agile;

import cn.hutool.json.JSONUtil;
import com.example.document2entity.entity.Doc;
import com.example.document2entity.entity.IndicatorReviewResult;
import com.example.document2entity.entity.RequirementReviewResult;
import com.example.document2entity.entity.ReviewSummaryReport;
import com.example.document2entity.formatter.QwenDocDashScopeChatFormatter;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.DashScopeChatModel;
import io.agentscope.core.model.GenerateOptions;
import lombok.extern.slf4j.Slf4j;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Slf4j
public class RequirementReviewService {

    private static final int REVIEW_THREAD_COUNT = 6;

    private static final String REVIEW_PROMPT = """
            # 角色
            你是采购需求文件的单指标评审专家。

            # 任务
            根据用户消息中提供的一个最小层级指标规则，评审随消息上传的需求文件。

            # 评审规则
            1. 本次只评审用户指定的一个最小层级指标，不评审或计算上级指标。
            2. roles 是该指标要求，rules 是评分规则，maxScore 是最高分。
            3. 评分必须严格依据需求文件中的明确内容和 rules，不得使用常识补充材料。
            4. 找不到明确证据时，不得认定该要求已满足。
            5. score 必须在 0 到 maxScore 之间。
            6. evidence 只填写需求文件中能够支持结论的简短事实；没有证据时返回空数组。
            7. 只输出 JSON，不得输出 Markdown 或解释文字。

            # 输出 JSON Schema
            {
              "$schema": "https://json-schema.org/draft/2020-12/schema",
              "type": "object",
              "properties": {
                "serialNumber": { "type": "integer" },
                "name": { "type": "string" },
                "maxScore": { "type": "number" },
                "score": { "type": "number" },
                "conclusion": { "type": "string" },
                "evidence": { "type": "array", "items": { "type": "string" } },
                "deductions": { "type": "array", "items": { "type": "string" } },
                "suggestions": { "type": "array", "items": { "type": "string" } }
              },
              "required": ["serialNumber", "name", "maxScore", "score", "conclusion", "evidence", "deductions", "suggestions"],
              "additionalProperties": false
            }
            """;

    private static final String SUMMARY_PROMPT = """
            # 角色
            你是采购需求指标评审的汇总专家。

            # 任务
            根据用户提供的全部主指标评审结果，形成简洁、客观、可执行的汇总结论。

            # 要求
            1. 不重新评分，不修改任何单项得分。
            2. overallConclusion 概括整体质量、主要优势和主要风险。
            3. keyIssues 按影响程度列出跨指标或高优先级问题。
            4. recommendations 必须对应已有问题，不增加评审结果中没有依据的事实。
            5. 只输出 JSON，不得输出 Markdown 或解释文字。

            # 输出 JSON Schema
            {
              "$schema": "https://json-schema.org/draft/2020-12/schema",
              "type": "object",
              "properties": {
                "overallConclusion": { "type": "string" },
                "keyIssues": { "type": "array", "items": { "type": "string" } },
                "recommendations": { "type": "array", "items": { "type": "string" } }
              },
              "required": ["overallConclusion", "keyIssues", "recommendations"],
              "additionalProperties": false
            }
            """;

    private final String apiKey;

    public RequirementReviewService(String apiKey) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalArgumentException("DashScope API key must not be blank");
        }
        this.apiKey = apiKey;
    }

    public RequirementReviewResult review(Doc rules, String requirementFileUrl) {
        List<Doc.KeyMetric> metrics = validateAndGetMetrics(rules, requirementFileUrl);
        ExecutorService executor = Executors.newFixedThreadPool(REVIEW_THREAD_COUNT);
        try {
            Map<Doc.KeyMetric, List<CompletableFuture<IndicatorReviewResult.SubIndicatorReviewResult>>>
                    futuresByMetric = new LinkedHashMap<>();
            for (Doc.KeyMetric metric : metrics) {
                List<CompletableFuture<IndicatorReviewResult.SubIndicatorReviewResult>> futures =
                        metric.getSubIndicators().stream()
                                .map(indicator -> CompletableFuture.supplyAsync(
                                        () -> reviewLeafIndicator(metric, indicator, requirementFileUrl),
                                        executor))
                                .toList();
                futuresByMetric.put(metric, futures);
            }

            List<IndicatorReviewResult> indicatorResults = futuresByMetric.entrySet().stream()
                    .map(entry -> aggregateMetric(
                            entry.getKey(),
                            entry.getValue().stream().map(CompletableFuture::join).toList()))
                    .toList();
            int leafIndicatorCount = indicatorResults.stream()
                    .mapToInt(item -> item.getSubIndicatorResults().size())
                    .sum();
            int successfulLeafIndicatorCount = (int) indicatorResults.stream()
                    .flatMap(item -> item.getSubIndicatorResults().stream())
                    .filter(IndicatorReviewResult.SubIndicatorReviewResult::isSuccess)
                    .count();

            RequirementReviewResult result = new RequirementReviewResult();
            result.setMetricCount(indicatorResults.size());
            result.setSuccessfulMetricCount((int) indicatorResults.stream()
                    .filter(IndicatorReviewResult::isSuccess)
                    .count());
            result.setFailedMetricCount(result.getMetricCount() - result.getSuccessfulMetricCount());
            result.setLeafIndicatorCount(leafIndicatorCount);
            result.setSuccessfulLeafIndicatorCount(successfulLeafIndicatorCount);
            result.setFailedLeafIndicatorCount(leafIndicatorCount - successfulLeafIndicatorCount);
            result.setIndicatorResults(indicatorResults);
            result.setSummaryReport(summarize(indicatorResults));
            return result;
        } finally {
            executor.shutdown();
        }
    }

    private IndicatorReviewResult.SubIndicatorReviewResult reviewLeafIndicator(
            Doc.KeyMetric metric,
            Doc.SubIndicator indicator,
            String requirementFileUrl) {
        log.info("开始评审最小指标 [{}] {}（上级：{}）",
                indicator.getSerialNumber(), indicator.getName(), metric.getName());
        try {
            ReActAgent agent = newAgent(
                    "leaf-review-" + indicator.getSerialNumber(),
                    "qwen-doc-turbo",
                    REVIEW_PROMPT,
                    true);
            Msg request = Msg.builder()
                    .role(MsgRole.USER)
                    .content(TextBlock.builder()
                            .text("所属上级指标：" + metric.getName()
                                    + "\n请评审以下最小指标：\n"
                                    + JSONUtil.toJsonStr(indicator))
                            .build())
                    .metadata(Map.of(
                            QwenDocDashScopeChatFormatter.DOC_URLS_METADATA_KEY,
                            List.of(requirementFileUrl),
                            QwenDocDashScopeChatFormatter.FILE_PARSING_STRATEGY_METADATA_KEY,
                            "auto"))
                    .build();

            Msg response = Objects.requireNonNull(agent.call(request).block(), "最小指标评审未返回结果");
            IndicatorReviewResult.SubIndicatorReviewResult result = QwenDocResponseParser.parse(
                    response.getTextContent(), IndicatorReviewResult.SubIndicatorReviewResult.class);
            normalizeAndValidate(indicator, result);
            result.setSuccess(true);
            result.setErrorMessage(null);
            log.info("最小指标评审完成 [{}] {}，得分 {}/{}",
                    indicator.getSerialNumber(), indicator.getName(), result.getScore(), result.getMaxScore());
            return result;
        } catch (Exception exception) {
            log.warn("最小指标评审失败 [{}] {}：{}",
                    indicator.getSerialNumber(), indicator.getName(), exception.getMessage());
            return IndicatorReviewResult.SubIndicatorReviewResult.failed(indicator, exception);
        }
    }

    private ReviewSummaryReport summarize(List<IndicatorReviewResult> indicatorResults) {
        double totalMaxScore = indicatorResults.stream()
                .map(IndicatorReviewResult::getMaxScore)
                .filter(Objects::nonNull)
                .mapToDouble(Double::doubleValue)
                .sum();
        double totalScore = indicatorResults.stream()
                .map(IndicatorReviewResult::getScore)
                .filter(Objects::nonNull)
                .mapToDouble(Double::doubleValue)
                .sum();

        ReviewSummaryReport report;
        try {
            log.info("开始生成汇总报告");
            ReActAgent agent = newAgent("review-summary", "qwen-plus", SUMMARY_PROMPT, false);
            Msg request = Msg.builder()
                    .role(MsgRole.USER)
                    .content(TextBlock.builder()
                            .text("全部指标评审结果：\n" + JSONUtil.toJsonStr(indicatorResults))
                            .build())
                    .build();
            Msg response = Objects.requireNonNull(agent.call(request).block(), "汇总智能体未返回结果");
            report = QwenDocResponseParser.parse(
                    response.getTextContent(), ReviewSummaryReport.class);
            report.setSuccess(true);
            log.info("汇总报告生成完成");
        } catch (Exception exception) {
            log.warn("汇总报告生成失败：{}", exception.getMessage());
            report = new ReviewSummaryReport();
            report.setSuccess(false);
            report.setErrorMessage(exception.getMessage());
            report.setOverallConclusion("汇总报告生成失败，请查看各指标评审结果");
        }

        report.setTotalMaxScore(round(totalMaxScore));
        report.setTotalScore(round(totalScore));
        report.setScoreRate(totalMaxScore == 0D ? 0D : round(totalScore / totalMaxScore * 100D));
        report.setMetricSummaries(buildMetricSummaries(indicatorResults));
        return report;
    }

    private ReActAgent newAgent(String name, String modelName, String prompt, boolean documentModel) {
        DashScopeChatModel.Builder modelBuilder = DashScopeChatModel.builder()
                .apiKey(apiKey)
                .modelName(modelName)
                .defaultOptions(GenerateOptions.builder()
                        .temperature(0.1)
                        .build());
        if (documentModel) {
            modelBuilder.formatter(new QwenDocDashScopeChatFormatter());
        }
        return ReActAgent.builder()
                .name(name)
                .sysPrompt(prompt)
                .model(modelBuilder.build())
                .build();
    }

    private void normalizeAndValidate(
            Doc.SubIndicator indicator,
            IndicatorReviewResult.SubIndicatorReviewResult result) {
        double maxScore = requireNonNegative(indicator.getMaxScore(), "最小指标满分");
        double score = requireScore(result.getScore(), maxScore, indicator.getName());
        result.setSerialNumber(indicator.getSerialNumber());
        result.setName(indicator.getName());
        result.setMaxScore(round(maxScore));
        result.setScore(round(score));
    }

    IndicatorReviewResult aggregateMetric(
            Doc.KeyMetric metric,
            List<IndicatorReviewResult.SubIndicatorReviewResult> children) {
        IndicatorReviewResult result = new IndicatorReviewResult();
        double maxScore = children.stream()
                .map(IndicatorReviewResult.SubIndicatorReviewResult::getMaxScore)
                .filter(Objects::nonNull)
                .mapToDouble(Double::doubleValue)
                .sum();
        double score = children.stream()
                .map(IndicatorReviewResult.SubIndicatorReviewResult::getScore)
                .filter(Objects::nonNull)
                .mapToDouble(Double::doubleValue)
                .sum();
        long successfulChildren = children.stream()
                .filter(IndicatorReviewResult.SubIndicatorReviewResult::isSuccess)
                .count();

        result.setMetricSerialNumber(metric.getSerialNumber());
        result.setMetricName(metric.getName());
        result.setMaxScore(round(maxScore));
        result.setScore(round(score));
        result.setSuccess(successfulChildren == children.size());
        result.setConclusion("完成 " + successfulChildren + "/" + children.size()
                + " 个最小指标评审，得分 " + round(score) + "/" + round(maxScore));
        result.setSubIndicatorResults(children);
        result.setEvidence(distinctStrings(children.stream()
                .flatMap(item -> safeList(item.getEvidence()).stream())
                .toList()));

        List<String> issues = new ArrayList<>(children.stream()
                .flatMap(item -> safeList(item.getDeductions()).stream())
                .toList());
        children.stream()
                .filter(item -> !item.isSuccess() && item.getErrorMessage() != null)
                .map(item -> item.getName() + "：" + item.getErrorMessage())
                .forEach(issues::add);
        result.setIssues(distinctStrings(issues));
        result.setSuggestions(distinctStrings(children.stream()
                .flatMap(item -> safeList(item.getSuggestions()).stream())
                .toList()));
        if (!result.isSuccess()) {
            result.setErrorMessage("部分最小指标评审失败");
        }
        return result;
    }

    private List<ReviewSummaryReport.MetricSummary> buildMetricSummaries(
            List<IndicatorReviewResult> indicatorResults) {
        return indicatorResults.stream().map(item -> {
            ReviewSummaryReport.MetricSummary summary = new ReviewSummaryReport.MetricSummary();
            summary.setMetricSerialNumber(item.getMetricSerialNumber());
            summary.setMetricName(item.getMetricName());
            summary.setMaxScore(item.getMaxScore());
            summary.setScore(item.getScore());
            summary.setConclusion(item.getConclusion());
            return summary;
        }).toList();
    }

    private List<Doc.KeyMetric> validateAndGetMetrics(Doc rules, String requirementFileUrl) {
        if (requirementFileUrl == null || requirementFileUrl.isBlank()) {
            throw new IllegalArgumentException("Requirement file URL must not be blank");
        }
        if (rules == null || rules.getOrder() == null || rules.getOrder().isEmpty()) {
            throw new IllegalArgumentException("Rule document contains no valid metrics");
        }
        List<Doc.KeyMetric> metrics = rules.getOrder().values().stream()
                .filter(Objects::nonNull)
                .toList();
        boolean missingLeafIndicators = metrics.stream()
                .anyMatch(metric -> metric.getSubIndicators() == null
                        || metric.getSubIndicators().isEmpty());
        if (missingLeafIndicators) {
            throw new IllegalArgumentException("Each parent metric must contain leaf indicators");
        }
        return metrics;
    }

    private double requireNonNegative(Double value, String fieldName) {
        if (value == null || value < 0D) {
            throw new IllegalArgumentException(fieldName + "无效");
        }
        return value;
    }

    private double requireScore(Double value, double maxScore, String indicatorName) {
        if (value == null || value < 0D || value > maxScore + 0.000001D) {
            throw new IllegalArgumentException("子指标得分越界: " + indicatorName);
        }
        return value;
    }

    private <T> List<T> safeList(List<T> values) {
        return values == null ? List.of() : values;
    }

    private List<String> distinctStrings(List<String> values) {
        return values.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .distinct()
                .toList();
    }

    private double round(double value) {
        return Math.round(value * 100D) / 100D;
    }
}
