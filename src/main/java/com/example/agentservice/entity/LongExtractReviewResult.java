package com.example.agentservice.entity;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class LongExtractReviewResult {
    private List<LongFactExtractionResult> extractionResults = new ArrayList<>();
    private String markdownReport;
}
