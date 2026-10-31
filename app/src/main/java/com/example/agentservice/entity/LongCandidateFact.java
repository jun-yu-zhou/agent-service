package com.example.agentservice.entity;

import lombok.Data;

@Data
public class LongCandidateFact {
    private String factType;
    private String bidder;
    private String value;
    private String document;
    private Integer page;
    private String location;
    private String excerpt;
}
