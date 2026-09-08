package com.example.agentservice.procurement.service;

import com.example.agentservice.config.ModelConfig;
import com.example.agentservice.procurement.prompt.TenderGenerationPrompts;
import com.fasterxml.jackson.databind.JsonNode;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import org.springframework.stereotype.Service;

/** 根据旧版 HTML 模板和项目数据生成招标文件初稿。 */
@Service
public class TenderDocumentGenerationService {

    private final ModelConfig modelConfig;
    public TenderDocumentGenerationService(ModelConfig modelConfig) {
        this.modelConfig = modelConfig;
    }

    public String generateDraft(String sourceText) {
        return generateDraft(sourceText, null);
    }

    public String generateDraft(String sourceText, JsonNode projectData) {
        if (sourceText == null || sourceText.isBlank()) {
            throw new IllegalArgumentException("招标来源正文不能为空");
        }
        long startNanos = System.nanoTime();
        ReActAgent agent = ReActAgent.builder()
                .name("tender-document-draft-generator")
                .sysPrompt(TenderGenerationPrompts.TENDER_DRAFT_SYSTEM_PROMPT)
                .model(modelConfig.qwen37PlusTenderGenerationModel())
                .build();
        Msg response = agent.call(Msg.builder()
                .role(MsgRole.USER)
                .textContent(generationInput(sourceText, projectData))
                .build()).block();
        if (response == null || response.getTextContent() == null || response.getTextContent().isBlank()) {
            throw new IllegalStateException("招标文件生成模型未返回有效内容");
        }
        printModelMetrics("招标初稿生成", response, startNanos);
        return response.getTextContent().trim();
    }

    private String generationInput(String sourceText, JsonNode projectData) {
        StringBuilder input = new StringBuilder("旧版招标文件 HTML 模板：\n\n").append(sourceText);
        if (projectData != null && !projectData.isNull() && !projectData.isEmpty()) {
            input.append("\n\n招标单位确认的完整结构化项目数据（同一字段冲突时以此处为准）：\n\n")
                    .append(projectData);
        }
        return input.toString();
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
