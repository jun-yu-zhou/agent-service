package com.example.agentservice.procurement.service;

import com.example.agentservice.config.ModelConfig;
import com.example.agentservice.procurement.prompt.TenderGenerationPrompts;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import org.springframework.stereotype.Service;

/** Generates a tender draft from normalized text without binding to a source file format. */
@Service
public class TenderDocumentGenerationService {

    private final ModelConfig modelConfig;
    private final TenderDraftConsistencyChecker consistencyChecker;

    public TenderDocumentGenerationService(
            ModelConfig modelConfig, TenderDraftConsistencyChecker consistencyChecker) {
        this.modelConfig = modelConfig;
        this.consistencyChecker = consistencyChecker;
    }

    public String generateDraft(String sourceText) {
        if (sourceText == null || sourceText.isBlank()) {
            throw new IllegalArgumentException("招标来源正文不能为空");
        }
        long startNanos = System.nanoTime();
        ReActAgent agent = ReActAgent.builder()
                .name("tender-document-draft-generator")
                .sysPrompt(TenderGenerationPrompts.TENDER_DRAFT_SYSTEM_PROMPT)
                .model(modelConfig.qwen37PlusReportModel())
                .build();
        Msg response = agent.call(Msg.builder()
                .role(MsgRole.USER)
                .textContent("请根据以下来源正文生成招标文件初稿：\n\n" + sourceText)
                .build()).block();
        if (response == null || response.getTextContent() == null || response.getTextContent().isBlank()) {
            throw new IllegalStateException("招标文件生成模型未返回有效内容");
        }
        printModelMetrics("招标初稿生成", response, startNanos);
        return response.getTextContent().trim();
    }

    /** Generates a draft and immediately returns deterministic source-to-draft checks. */
    public DraftGenerationResult generateDraftWithCheck(String sourceText) {
        String draft = generateDraft(sourceText);
        return new DraftGenerationResult(draft, consistencyChecker.check(sourceText, draft));
    }

    /** Reviews semantic consistency without modifying the draft. */
    public String reviewConsistency(String sourceText, String draftText) {
        if (sourceText == null || sourceText.isBlank()) {
            throw new IllegalArgumentException("招标来源正文不能为空");
        }
        if (draftText == null || draftText.isBlank()) {
            throw new IllegalArgumentException("招标文件初稿不能为空");
        }
        long startNanos = System.nanoTime();
        ReActAgent agent = ReActAgent.builder()
                .name("tender-document-consistency-reviewer")
                .sysPrompt(TenderGenerationPrompts.TENDER_CONSISTENCY_REVIEW_SYSTEM_PROMPT)
                .model(modelConfig.qwen37PlusReportModel())
                .build();
        Msg response = agent.call(Msg.builder()
                .role(MsgRole.USER)
                .textContent("来源正文：\n\n" + sourceText + "\n\n招标文件初稿：\n\n" + draftText)
                .build()).block();
        if (response == null || response.getTextContent() == null || response.getTextContent().isBlank()) {
            throw new IllegalStateException("招标文件一致性审查模型未返回有效内容");
        }
        printModelMetrics("招标初稿语义审查", response, startNanos);
        return response.getTextContent().trim();
    }

    /** Revises a draft once using review findings that are grounded in the source text. */
    public String reviseDraft(String sourceText, String draftText, String reviewText) {
        if (sourceText == null || sourceText.isBlank()) {
            throw new IllegalArgumentException("招标来源正文不能为空");
        }
        if (draftText == null || draftText.isBlank()) {
            throw new IllegalArgumentException("招标文件初稿不能为空");
        }
        if (reviewText == null || reviewText.isBlank()) {
            throw new IllegalArgumentException("一致性审查结果不能为空");
        }
        long startNanos = System.nanoTime();
        ReActAgent agent = ReActAgent.builder()
                .name("tender-document-draft-reviser")
                .sysPrompt(TenderGenerationPrompts.TENDER_DRAFT_REVISION_SYSTEM_PROMPT)
                .model(modelConfig.qwen37PlusReportModel())
                .build();
        Msg response = agent.call(Msg.builder()
                .role(MsgRole.USER)
                .textContent("来源正文：\n\n" + sourceText
                        + "\n\n招标文件初稿：\n\n" + draftText
                        + "\n\n一致性审查问题：\n\n" + reviewText)
                .build()).block();
        if (response == null || response.getTextContent() == null || response.getTextContent().isBlank()) {
            throw new IllegalStateException("招标文件修订模型未返回有效内容");
        }
        printModelMetrics("招标初稿自动修订", response, startNanos);
        return response.getTextContent().trim();
    }

    public record DraftGenerationResult(
            String draft,
            TenderDraftConsistencyChecker.ConsistencyResult consistency
    ) {
    }

    private void printModelMetrics(String stage, Msg response, long startNanos) {
        System.out.println(stage + "耗时毫秒: " + (System.nanoTime() - startNanos) / 1_000_000);
        if (response.getChatUsage() == null) {
            System.out.println(stage + "Token使用量：模型未返回usage");
            return;
        }
        System.out.println(stage + "输入Token: " + response.getChatUsage().getInputTokens());
        System.out.println(stage + "输出Token: " + response.getChatUsage().getOutputTokens());
    }
}
