package com.example.agentservice.procurement.service;

import com.example.agentservice.service.ImmService;
import org.springframework.stereotype.Service;

/** Generates a tender draft directly from an OSS document URL supported by IMM. */
@Service
public class TenderDocumentDraftWorkflow {

    private final ImmService immService;
    private final TenderDocumentGenerationService generationService;
    private final TenderDraftConsistencyChecker consistencyChecker;

    public TenderDocumentDraftWorkflow(
            ImmService immService,
            TenderDocumentGenerationService generationService,
            TenderDraftConsistencyChecker consistencyChecker) {
        this.immService = immService;
        this.generationService = generationService;
        this.consistencyChecker = consistencyChecker;
    }

    public String generateDraft(String documentOssUrl) throws Exception {
        return generateDraftWithCheck(documentOssUrl).draft();
    }

    /** Generates a draft from an OSS document and retains its source consistency result. */
    public TenderDocumentGenerationService.DraftGenerationResult generateDraftWithCheck(String documentOssUrl)
            throws Exception {
        return generationService.generateDraftWithCheck(extractSourceText(documentOssUrl));
    }

    /** Extracts the source text again and reviews a caller-provided draft without changing it. */
    public String reviewDraft(String documentOssUrl, String draftText) throws Exception {
        return generationService.reviewConsistency(extractSourceText(documentOssUrl), draftText);
    }

    /** Revises a caller-provided draft once from review findings and the extracted source text. */
    public String reviseDraft(String documentOssUrl, String draftText, String reviewText) throws Exception {
        return generationService.reviseDraft(extractSourceText(documentOssUrl), draftText, reviewText);
    }

    /** Executes the only allowed automatic revision, then returns the second review result. */
    public DraftReviewWorkflowResult generateReviewAndRevise(String documentOssUrl) throws Exception {
        String sourceText = extractSourceText(documentOssUrl);
        TenderDocumentGenerationService.DraftGenerationResult initial =
                generationService.generateDraftWithCheck(sourceText);
        String firstReview = generationService.reviewConsistency(sourceText, initial.draft());
        String revisedDraft = generationService.reviseDraft(sourceText, initial.draft(), firstReview);
        TenderDraftConsistencyChecker.ConsistencyResult revisedConsistency =
                consistencyChecker.check(sourceText, revisedDraft);
        String secondReview = generationService.reviewConsistency(sourceText, revisedDraft);
        return new DraftReviewWorkflowResult(
                initial.draft(), initial.consistency(), firstReview,
                revisedDraft, revisedConsistency, secondReview
        );
    }

    /**
     * Extracts source text once so callers coordinating a multi-step workflow can reuse it.
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

    public record DraftReviewWorkflowResult(
            String initialDraft,
            TenderDraftConsistencyChecker.ConsistencyResult initialConsistency,
            String initialReview,
            String revisedDraft,
            TenderDraftConsistencyChecker.ConsistencyResult revisedConsistency,
            String secondReview
    ) {
    }
}
