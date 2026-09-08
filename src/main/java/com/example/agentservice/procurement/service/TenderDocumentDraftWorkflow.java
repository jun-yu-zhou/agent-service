package com.example.agentservice.procurement.service;

import com.example.agentservice.service.ImmService;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Service;

/** 根据 IMM 支持的 OSS 文档地址生成招标文件初稿。 */
@Service
public class TenderDocumentDraftWorkflow {

    private final ImmService immService;
    private final TenderDocumentGenerationService generationService;

    public TenderDocumentDraftWorkflow(
            ImmService immService,
            TenderDocumentGenerationService generationService) {
        this.immService = immService;
        this.generationService = generationService;
    }

    public String generateDraft(String documentOssUrl) throws Exception {
        return generateDraft(documentOssUrl, null);
    }

    public String generateDraft(String documentOssUrl, JsonNode projectData) throws Exception {
        return generationService.generateDraft(extractSourceText(documentOssUrl), projectData);
    }

    /**
     * 只提取一次来源正文，供编排多阶段流程的调用方复用。
     */
    public String extractSourceText(String documentOssUrl) throws Exception {
        if (documentOssUrl == null || documentOssUrl.isBlank()) {
            throw new IllegalArgumentException("招标来源文件地址不能为空");
        }
        String sourceText = immService.extractDocumentText(documentOssUrl);
        if (sourceText == null || sourceText.isBlank()) {
            throw new IllegalStateException("未从招标来源文件提取到正文");
        }
        return sourceText;
    }

}
