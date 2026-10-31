package com.example.agentservice.entity;


import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.LinkedHashMap;
import java.util.List;

@Data
@NoArgsConstructor
public class Doc {

    private LinkedHashMap<Integer, KeyMetric> order;

    @Data
    @NoArgsConstructor
    public static class KeyMetric {
        private String name;
        private Double proportion;
        private Double maxScore;
        private String rules;
        private Double score;
        private Integer serialNumber;
        private List<SubIndicator> subIndicators;

    }

    @Data
    @NoArgsConstructor
    public static class SubIndicator {
        private String name;
        private Double proportion;
        private Double maxScore;
        private String roles;
        private String rules;
        private Integer serialNumber;
    }
}
