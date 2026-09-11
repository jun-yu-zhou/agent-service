package com.example.agentservice.procurement.service;

import com.example.agentservice.config.ModelConfig;
import com.example.agentservice.procurement.prompt.TenderGenerationPrompts;
import com.fasterxml.jackson.databind.JsonNode;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/** 根据招标文件 HTML 模板和项目数据生成招标文件初稿。 */
@Service
@Slf4j
public class TenderDocumentGenerationService {

    private final ModelConfig modelConfig;
    public TenderDocumentGenerationService(ModelConfig modelConfig) {
        this.modelConfig = modelConfig;
    }

    public String generateDraft(String templateHtml, JsonNode projectData) {
        if (templateHtml == null || templateHtml.isBlank()) {
            throw new IllegalArgumentException("招标文件 HTML 模板不能为空");
        }
        long startNanos = System.nanoTime();
        ReActAgent agent = ReActAgent.builder()
                .name("tender-document-draft-generator")
                .sysPrompt(TenderGenerationPrompts.TENDER_DRAFT_SYSTEM_PROMPT)
                .model(modelConfig.qwen37PlusTenderGenerationModel())
                .build();
        Msg response = agent.call(Msg.builder()
                .role(MsgRole.USER)
                .textContent(generationInput(templateHtml, projectData))
                .build()).block();
        if (response == null || response.getTextContent() == null || response.getTextContent().isBlank()) {
            throw new IllegalStateException("招标文件生成模型未返回有效内容");
        }
        printModelMetrics("招标初稿生成", response, startNanos);
        return sanitizeHtmlTags(response.getTextContent().trim());
    }

    /**
     * 模板与数据值可能携带 HTML 标签并被模型照抄进 Markdown。
     *
     * <p>strong/em 转成 Markdown 加粗斜体，br 转成空格（避免打断表格行），其余标签去除只留文字。</p>
     */
    static String sanitizeHtmlTags(String markdown) {
        return markdown
                .replaceAll("(?i)</?(strong|b)>", "**")
                .replaceAll("(?i)</?(em|i)>", "*")
                .replaceAll("(?i)<br\\s*/?>", " ")
                .replaceAll("</?[a-zA-Z][a-zA-Z0-9]*(?:\\s[^<>]*)?/?>", "");
    }

    private String generationInput(String templateHtml, JsonNode projectData) {
        StringBuilder input = new StringBuilder("招标文件 HTML 模板：\n\n").append(templateHtml);
        if (projectData != null && !projectData.isNull() && !projectData.isEmpty()) {
            input.append("\n\n招标单位确认的完整结构化项目数据（同一字段冲突时以此处为准）：\n\n")
                    .append(projectData);
        }
        return input.toString();
    }

    private void printModelMetrics(String stage, Msg response, long startNanos) {
        log.info("{}耗时毫秒: {}", stage, (System.nanoTime() - startNanos) / 1_000_000);
        if (response.getChatUsage() == null) {
            log.info("{}Token使用量：模型未返回usage", stage);
            return;
        }
        log.info("{}输入Token: {}", stage, response.getChatUsage().getInputTokens());
        log.info("{}输出Token: {}", stage, response.getChatUsage().getOutputTokens());
    }
}
