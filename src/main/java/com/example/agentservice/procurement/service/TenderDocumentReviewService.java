package com.example.agentservice.procurement.service;

import com.example.agentservice.config.ModelConfig;
import com.example.agentservice.procurement.prompt.TenderGenerationPrompts;
import com.fasterxml.jackson.databind.JsonNode;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/** 对照原始模板和项目数据生成招标文件定稿审核报告。 */
@Service
@Slf4j
public class TenderDocumentReviewService {

    private final ModelConfig modelConfig;

    public TenderDocumentReviewService(ModelConfig modelConfig) {
        this.modelConfig = modelConfig;
    }

    public String review(String templateHtml, JsonNode projectData, String finalizedMarkdown) {
        if (templateHtml == null || templateHtml.isBlank()) {
            throw new IllegalArgumentException("招标文件原始 HTML 模板不能为空");
        }
        if (finalizedMarkdown == null || finalizedMarkdown.isBlank()) {
            throw new IllegalArgumentException("招标文件定稿正文不能为空");
        }

        long startNanos = System.nanoTime();
        ReActAgent agent = ReActAgent.builder()
                .name("tender-document-reviewer")
                .sysPrompt(TenderGenerationPrompts.TENDER_REVIEW_SYSTEM_PROMPT)
                .model(modelConfig.qwen37PlusTenderReviewModel())
                .build();
        Msg response = agent.call(Msg.builder()
                .role(MsgRole.USER)
                .textContent(reviewInput(templateHtml, projectData, finalizedMarkdown))
                .build()).block();
        if (response == null || response.getTextContent() == null || response.getTextContent().isBlank()) {
            throw new IllegalStateException("招标文件审核模型未返回有效报告");
        }
        printModelMetrics(response, startNanos);
        return response.getTextContent().trim();
    }

    /** 使用清晰边界隔离三类材料，避免模型混淆审核基准与待审核正文。 */
    private String reviewInput(String templateHtml, JsonNode projectData, String finalizedMarkdown) {
        String projectJson = projectData == null || projectData.isNull()
                ? "未提供结构化项目数据"
                : projectData.toString();
        return "【原始 HTML 模板开始】\n" + templateHtml
                + "\n【原始 HTML 模板结束】\n\n【结构化项目数据开始】\n" + projectJson
                + "\n【结构化项目数据结束】\n\n【招标文件定稿开始】\n" + finalizedMarkdown
                + "\n【招标文件定稿结束】";
    }

    private void printModelMetrics(Msg response, long startNanos) {
        log.info("招标定稿审核耗时毫秒: {}", (System.nanoTime() - startNanos) / 1_000_000);
        if (response.getChatUsage() == null) {
            log.info("招标定稿审核Token使用量：模型未返回usage");
            return;
        }
        log.info("招标定稿审核输入Token: {}", response.getChatUsage().getInputTokens());
        log.info("招标定稿审核输出Token: {}", response.getChatUsage().getOutputTokens());
    }
}
