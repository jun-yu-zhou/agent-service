package com.example.agentservice.entity;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

@Data
@NoArgsConstructor
public class ReviewSummaryReport {

    private boolean success;
    private String errorMessage;
    private Double totalMaxScore;
    private Double totalScore;
    private Double scoreRate;
    private String overallConclusion;
    private List<MetricSummary> metricSummaries = new ArrayList<>();
    private List<String> keyIssues = new ArrayList<>();
    private List<String> recommendations = new ArrayList<>();

    @Data
    @NoArgsConstructor
    public static class MetricSummary {
        private Integer metricSerialNumber;
        private String metricName;
        private Double maxScore;
        private Double score;
        private String conclusion;
    }
}
