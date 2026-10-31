package com.example.agentservice.entity;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class CibReviewResult {

    private List<CibDimensionResult> dimensionResults = new ArrayList<>();
    private String markdownReport;
}
