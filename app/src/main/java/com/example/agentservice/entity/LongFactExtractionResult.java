package com.example.agentservice.entity;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class LongFactExtractionResult {
    private String dimension;
    private String sourceDocument;
    private List<LongCandidateFact> candidates = new ArrayList<>();
    private boolean success = true;
    private String errorMessage;
}
