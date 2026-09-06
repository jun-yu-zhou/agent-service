package com.example.agentservice.procurement.service;

import com.example.agentservice.service.ImmService;
import org.springframework.stereotype.Service;

import java.util.List;

/** Builds a bid draft from one tender document and supplier material documents in OSS. */
@Service
public class BidDocumentDraftWorkflow {

    private final ImmService immService;
    private final BidDocumentGenerationService generationService;

    public BidDocumentDraftWorkflow(ImmService immService, BidDocumentGenerationService generationService) {
        this.immService = immService;
        this.generationService = generationService;
    }

    public String generateDraft(String tenderDocumentUrl, List<String> supplierDocumentUrls) throws Exception {
        if (tenderDocumentUrl == null || tenderDocumentUrl.isBlank()) {
            throw new IllegalArgumentException("招标文件地址不能为空");
        }
        if (supplierDocumentUrls == null || supplierDocumentUrls.isEmpty()) {
            throw new IllegalArgumentException("供应商资料文件不能为空");
        }
        String tenderText = requireText(immService.extractDocumentText(tenderDocumentUrl), "招标文件");
        String supplierText = extractSupplierText(supplierDocumentUrls);
        return generationService.generateDraft(tenderText, supplierText);
    }

    /** Extracts all inputs and reviews a caller-provided bid draft without changing it. */
    public String reviewDraft(String tenderDocumentUrl, List<String> supplierDocumentUrls, String draftText)
            throws Exception {
        if (tenderDocumentUrl == null || tenderDocumentUrl.isBlank()) {
            throw new IllegalArgumentException("招标文件地址不能为空");
        }
        if (supplierDocumentUrls == null || supplierDocumentUrls.isEmpty()) {
            throw new IllegalArgumentException("供应商资料文件不能为空");
        }
        String tenderText = requireText(immService.extractDocumentText(tenderDocumentUrl), "招标文件");
        return generationService.reviewConsistency(tenderText, extractSupplierText(supplierDocumentUrls), draftText);
    }

    /** Extracts all inputs and revises a caller-provided bid draft once. */
    public String reviseDraft(
            String tenderDocumentUrl, List<String> supplierDocumentUrls, String draftText, String reviewText)
            throws Exception {
        if (tenderDocumentUrl == null || tenderDocumentUrl.isBlank()) {
            throw new IllegalArgumentException("招标文件地址不能为空");
        }
        if (supplierDocumentUrls == null || supplierDocumentUrls.isEmpty()) {
            throw new IllegalArgumentException("供应商资料文件不能为空");
        }
        String tenderText = requireText(immService.extractDocumentText(tenderDocumentUrl), "招标文件");
        return generationService.reviseDraft(tenderText, extractSupplierText(supplierDocumentUrls), draftText, reviewText);
    }

    /** Generates, reviews, revises once, then performs a final review for a bid draft. */
    public BidReviewWorkflowResult generateReviewAndRevise(
            String tenderDocumentUrl, List<String> supplierDocumentUrls) throws Exception {
        if (tenderDocumentUrl == null || tenderDocumentUrl.isBlank()) {
            throw new IllegalArgumentException("招标文件地址不能为空");
        }
        if (supplierDocumentUrls == null || supplierDocumentUrls.isEmpty()) {
            throw new IllegalArgumentException("供应商资料文件不能为空");
        }
        String tenderText = requireText(immService.extractDocumentText(tenderDocumentUrl), "招标文件");
        String supplierText = extractSupplierText(supplierDocumentUrls);
        String initialDraft = generationService.generateDraft(tenderText, supplierText);
        String initialReview = generationService.reviewConsistency(tenderText, supplierText, initialDraft);
        String revisedDraft = generationService.reviseDraft(tenderText, supplierText, initialDraft, initialReview);
        String secondReview = generationService.reviewConsistency(tenderText, supplierText, revisedDraft);
        return new BidReviewWorkflowResult(initialDraft, initialReview, revisedDraft, secondReview);
    }

    private String extractSupplierText(List<String> supplierDocumentUrls) throws Exception {
        StringBuilder materials = new StringBuilder();
        for (int index = 0; index < supplierDocumentUrls.size(); index++) {
            String documentUrl = supplierDocumentUrls.get(index);
            if (documentUrl == null || documentUrl.isBlank()) {
                throw new IllegalArgumentException("供应商资料文件地址不能为空");
            }
            String text = requireText(immService.extractDocumentText(documentUrl), "供应商资料第" + (index + 1) + "份");
            if (!materials.isEmpty()) {
                materials.append("\n\n");
            }
            materials.append("## 供应商资料第").append(index + 1).append("份\n\n").append(text);
        }
        return materials.toString();
    }

    private String requireText(String text, String materialName) {
        if (text == null || text.isBlank()) {
            throw new IllegalStateException(materialName + "未提取到正文，不能静默忽略");
        }
        return text;
    }

    public record BidReviewWorkflowResult(
            String initialDraft,
            String initialReview,
            String revisedDraft,
            String secondReview
    ) {
    }
}
