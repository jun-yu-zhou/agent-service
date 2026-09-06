package com.example.agentservice.procurement.service;

import com.example.agentservice.config.ModelConfig;
import com.example.agentservice.procurement.prompt.BidGenerationPrompts;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import org.springframework.stereotype.Service;

/** Generates a bid draft from arbitrary tender and supplier source texts. */
@Service
public class BidDocumentGenerationService {

    private final ModelConfig modelConfig;

    public BidDocumentGenerationService(ModelConfig modelConfig) {
        this.modelConfig = modelConfig;
    }

    public String generateDraft(String tenderText, String supplierText) {
        requireSourceTexts(tenderText, supplierText);
        long startNanos = System.nanoTime();
        ReActAgent agent = ReActAgent.builder()
                .name("bid-document-draft-generator")
                .sysPrompt(BidGenerationPrompts.BID_DRAFT_SYSTEM_PROMPT)
                .model(modelConfig.qwen37PlusReportModel())
                .build();
        Msg response = agent.call(Msg.builder()
                .role(MsgRole.USER)
                .textContent("已确认招标文件正文：\n\n" + tenderText
                        + "\n\n供应商资料与证明材料摘要：\n\n" + supplierText)
                .build()).block();
        if (response == null || response.getTextContent() == null || response.getTextContent().isBlank()) {
            throw new IllegalStateException("投标文件生成模型未返回有效内容");
        }
        printMetrics("投标初稿生成", response, startNanos);
        return response.getTextContent().trim();
    }

    /** Reviews a bid draft without changing it. */
    public String reviewConsistency(String tenderText, String supplierText, String draftText) {
        requireSourceTexts(tenderText, supplierText);
        if (draftText == null || draftText.isBlank()) {
            throw new IllegalArgumentException("投标文件初稿不能为空");
        }
        long startNanos = System.nanoTime();
        ReActAgent agent = ReActAgent.builder()
                .name("bid-document-consistency-reviewer")
                .sysPrompt(BidGenerationPrompts.BID_CONSISTENCY_REVIEW_SYSTEM_PROMPT)
                .model(modelConfig.qwen37PlusReportModel())
                .build();
        Msg response = agent.call(Msg.builder()
                .role(MsgRole.USER)
                .textContent("已确认招标文件正文：\n\n" + tenderText
                        + "\n\n供应商资料与证明材料摘要：\n\n" + supplierText
                        + "\n\n投标文件初稿：\n\n" + draftText)
                .build()).block();
        if (response == null || response.getTextContent() == null || response.getTextContent().isBlank()) {
            throw new IllegalStateException("投标文件一致性审查模型未返回有效内容");
        }
        printMetrics("投标初稿语义审查", response, startNanos);
        return response.getTextContent().trim();
    }

    /** Revises a bid draft once using only source-grounded review findings. */
    public String reviseDraft(String tenderText, String supplierText, String draftText, String reviewText) {
        requireSourceTexts(tenderText, supplierText);
        if (draftText == null || draftText.isBlank()) {
            throw new IllegalArgumentException("投标文件初稿不能为空");
        }
        if (reviewText == null || reviewText.isBlank()) {
            throw new IllegalArgumentException("一致性审查结果不能为空");
        }
        long startNanos = System.nanoTime();
        ReActAgent agent = ReActAgent.builder()
                .name("bid-document-draft-reviser")
                .sysPrompt(BidGenerationPrompts.BID_DRAFT_REVISION_SYSTEM_PROMPT)
                .model(modelConfig.qwen37PlusReportModel())
                .build();
        Msg response = agent.call(Msg.builder()
                .role(MsgRole.USER)
                .textContent("已确认招标文件正文：\n\n" + tenderText
                        + "\n\n供应商资料与证明材料摘要：\n\n" + supplierText
                        + "\n\n投标文件初稿：\n\n" + draftText
                        + "\n\n一致性审查问题：\n\n" + reviewText)
                .build()).block();
        if (response == null || response.getTextContent() == null || response.getTextContent().isBlank()) {
            throw new IllegalStateException("投标文件修订模型未返回有效内容");
        }
        printMetrics("投标初稿自动修订", response, startNanos);
        return response.getTextContent().trim();
    }

    private void requireSourceTexts(String tenderText, String supplierText) {
        if (tenderText == null || tenderText.isBlank()) {
            throw new IllegalArgumentException("招标文件正文不能为空");
        }
        if (supplierText == null || supplierText.isBlank()) {
            throw new IllegalArgumentException("供应商资料不能为空");
        }
    }

    private void printMetrics(String stage, Msg response, long startNanos) {
        System.out.println(stage + "耗时毫秒: " + (System.nanoTime() - startNanos) / 1_000_000);
        if (response.getChatUsage() == null) {
            System.out.println(stage + "Token使用量：模型未返回usage");
            return;
        }
        System.out.println(stage + "输入Token: " + response.getChatUsage().getInputTokens());
        System.out.println(stage + "输出Token: " + response.getChatUsage().getOutputTokens());
    }
}
