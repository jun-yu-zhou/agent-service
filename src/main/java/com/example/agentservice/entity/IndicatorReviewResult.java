package com.example.agentservice.entity;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

@Data
@NoArgsConstructor
public class IndicatorReviewResult {

    private Integer metricSerialNumber;
    private String metricName;
    private Double maxScore;
    private Double score;
    private String conclusion;
    private List<String> evidence = new ArrayList<>();
    private List<String> issues = new ArrayList<>();
    private List<String> suggestions = new ArrayList<>();
    private List<SubIndicatorReviewResult> subIndicatorResults = new ArrayList<>();
    private boolean success;
    private String errorMessage;

    public static IndicatorReviewResult failed(Doc.KeyMetric metric, Throwable throwable) {
        IndicatorReviewResult result = new IndicatorReviewResult();
        result.setMetricSerialNumber(metric.getSerialNumber());
        result.setMetricName(metric.getName());
        result.setMaxScore(metric.getMaxScore());
        result.setScore(0D);
        result.setConclusion("评审执行失败");
        result.setSuccess(false);
        result.setErrorMessage(throwable.getMessage());
        return result;
    }

    @Data
    @NoArgsConstructor
    public static class SubIndicatorReviewResult {
        private boolean success;
        private String errorMessage;
        private Integer serialNumber;
        private String name;
        private Double maxScore;
        private Double score;
        private String conclusion;
        private List<String> evidence = new ArrayList<>();
        private List<String> deductions = new ArrayList<>();
        private List<String> suggestions = new ArrayList<>();

        public static SubIndicatorReviewResult failed(Doc.SubIndicator indicator, Throwable throwable) {
            SubIndicatorReviewResult result = new SubIndicatorReviewResult();
            result.setSerialNumber(indicator.getSerialNumber());
            result.setName(indicator.getName());
            result.setMaxScore(indicator.getMaxScore());
            result.setScore(0D);
            result.setConclusion("评审执行失败");
            result.setSuccess(false);
            result.setErrorMessage(throwable.getMessage());
            return result;
        }
    }
}
