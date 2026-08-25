package com.example.agentservice.service;

import com.example.agentservice.imm.request.DetectImageTextsRequest;

public interface detectImageTextsService {

    String detectImageTexts(DetectImageTextsRequest request) throws Exception;
}
