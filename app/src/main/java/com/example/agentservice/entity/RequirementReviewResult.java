package com.example.agentservice.entity;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

@Data
@NoArgsConstructor
public class RequirementReviewResult {

    private int metricCount;
    private int successfulMetricCount;
    private int failedMetricCount;
    private int leafIndicatorCount;
    private int successfulLeafIndicatorCount;
    private int failedLeafIndicatorCount;
    private List<IndicatorReviewResult> indicatorResults = new ArrayList<>();
    private ReviewSummaryReport summaryReport;
}
