package com.example.agentservice.service;

import com.example.agentservice.imm.request.ExtractDocumentTextRequest;

public interface extractDocumentTextService {

    String extractDocumentText(ExtractDocumentTextRequest request) throws Exception;
}
