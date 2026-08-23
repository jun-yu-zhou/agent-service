package com.example.agentservice.entity;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class CibDimensionResult {

    private String dimension;
    private String summary;
    private String riskLevel;
    private List<Finding> findings = new ArrayList<>();
    private List<String> recommendations = new ArrayList<>();
    private List<BidderProfile> bidderProfiles = new ArrayList<>();
    private List<SimilarityItem> similarityItems = new ArrayList<>();
    private List<PageSummary> pageSummary = new ArrayList<>();
    private List<PageError> pageErrors = new ArrayList<>();
    private List<SimilarityMetric> similarityMetrics = new ArrayList<>();
    private List<TypoMetric> typoMetrics = new ArrayList<>();
    private List<SimilarTypo> similarTypos = new ArrayList<>();
    private List<RiskFactor> riskFactors = new ArrayList<>();

    private boolean success = true;

    private String errorMessage;

    @Data
    public static class Finding {
        private List<String> bidders = new ArrayList<>();
        private String indicator;
        private List<Evidence> evidence = new ArrayList<>();
        private String assessment;
        private String confidence;
    }

    @Data
    public static class Evidence {
        private String bidder;
        private String document;
        private String location;
        private String excerpt;
    }

    @Data
    public static class BidderProfile {
        private String bidder;
        private Double firstQuote;
        private Double finalQuote;
        private Double reductionRate;
        private String firstQuoteLocation;
        private String firstQuoteExcerpt;
        private String finalQuoteLocation;
        private String finalQuoteExcerpt;
    }

    @Data
    public static class SimilarityItem {
        private String field;
        private String format;
        private List<String> bidders = new ArrayList<>();
        private List<String> values = new ArrayList<>();
        private String comparison;
    }

    @Data
    public static class PageSummary {
        private String bidder;
        private Integer totalPages;
    }

    @Data
    public static class PageError {
        private String pattern;
        private List<String> bidders = new ArrayList<>();
        private List<String> locations = new ArrayList<>();
    }

    @Data
    public static class SimilarityMetric {
        private List<String> bidders = new ArrayList<>();
        private Double textRepetitionRate;
        private Double semanticSimilarity;
    }

    @Data
    public static class TypoMetric {
        private String bidder;
        private Integer typoCount;
    }

    @Data
    public static class SimilarTypo {
        private String wrongText;
        private String correctText;
        private List<String> bidders = new ArrayList<>();
    }

    @Data
    public static class RiskFactor {
        private String type;
        private List<String> bidders = new ArrayList<>();
        private String description;
        private String legalBasis;
        private String confidence;
    }
}
