package com.example.agentservice.procurement.tender.service;

import com.example.agentservice.config.ModelConfig;
import com.example.agentservice.procurement.tender.prompt.TenderGenerationPrompts;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.model.DashScopeChatModel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/** 统一执行招标文件初稿生成、一致性修订和定稿审核。 */
@Service
@Slf4j
public class TenderDocumentAiService {

    private final ModelConfig modelConfig;
    private final ObjectMapper objectMapper;

    public TenderDocumentAiService(ModelConfig modelConfig, ObjectMapper objectMapper) {
        this.modelConfig = modelConfig;
        this.objectMapper = objectMapper;
    }

    /** 根据 HTML 模板和项目资料生成招标文件初稿。 */
    public String generateDraft(String templateHtml, JsonNode projectData) {
        if (templateHtml == null || templateHtml.isBlank()) {
            throw new IllegalArgumentException("招标文件 HTML 模板不能为空");
        }
        Msg response = invoke(
                "招标初稿生成",
                "tender-document-draft-generator",
                TenderGenerationPrompts.TENDER_DRAFT_SYSTEM_PROMPT,
                modelConfig.qwen37PlusTenderGenerationModel(),
                generationInput(templateHtml, projectData));
        String draft = requiredText(response, "招标文件生成模型未返回有效内容");
        return reviseConsistency(sanitizeHtmlTags(draft), projectData);
    }

    /** 对照模板、项目资料和定稿正文生成审核报告。 */
    public String review(String templateHtml, JsonNode projectData, String finalizedMarkdown) {
        if (templateHtml == null || templateHtml.isBlank()) {
            throw new IllegalArgumentException("招标文件原始 HTML 模板不能为空");
        }
        if (finalizedMarkdown == null || finalizedMarkdown.isBlank()) {
            throw new IllegalArgumentException("招标文件定稿正文不能为空");
        }
        Msg response = invoke(
                "招标定稿审核",
                "tender-document-reviewer",
                TenderGenerationPrompts.TENDER_REVIEW_SYSTEM_PROMPT,
                modelConfig.qwen37PlusTenderReviewModel(),
                reviewInput(templateHtml, projectData, finalizedMarkdown));
        return requiredText(response, "招标文件审核模型未返回有效报告");
    }

    /** 再次通读完整初稿，统一跨章节事实并清理残留的模板示例。 */
    private String reviseConsistency(String draft, JsonNode projectData) {
        Msg response = invoke(
                "招标初稿一致性修订",
                "tender-document-consistency-reviser",
                TenderGenerationPrompts.TENDER_CONSISTENCY_REVISION_SYSTEM_PROMPT,
                modelConfig.qwen37FlashTenderRevisionModel(),
                revisionInput(draft, projectData));
        if (text(response).isBlank()) {
            log.warn("招标初稿一致性修订未返回有效内容，保留原初稿");
            return draft;
        }
        String revised = sanitizeHtmlTags(text(response));
        if (!isSubstantiallyComplete(draft, revised)) {
            log.warn("招标初稿一致性修订结果明显短于原文，保留原初稿：原文字符数={}，修订字符数={}", draft.length(), revised.length());
            return draft;
        }
        return revised;
    }

    private Msg invoke(String stage, String agentName, String prompt,
            DashScopeChatModel model, String input) {
        long startNanos = System.nanoTime();
        Msg response = ReActAgent.builder()
                .name(agentName)
                .sysPrompt(prompt)
                .model(model)
                .build()
                .call(Msg.builder().role(MsgRole.USER).textContent(input).build())
                .block();
        printModelMetrics(stage, response, startNanos);
        return response;
    }

    String generationInput(String templateHtml, JsonNode projectData) {
        ObjectNode input = request("生成招标文件初稿");
        input.put("templateHtml", templateHtml);
        putProjectData(input, projectData);
        return input.toString();
    }

    String revisionInput(String draft, JsonNode projectData) {
        ObjectNode input = request("修订招标文件初稿");
        input.put("draftMarkdown", draft);
        putProjectData(input, projectData);
        return input.toString();
    }

    String reviewInput(String templateHtml, JsonNode projectData, String finalizedMarkdown) {
        ObjectNode input = request("审核招标文件定稿");
        input.put("templateHtml", templateHtml);
        input.put("finalizedMarkdown", finalizedMarkdown);
        putProjectData(input, projectData);
        return input.toString();
    }

    /**
     * 所有外部内容都作为 JSON 字段值传入，避免模板或正文伪造文本边界并越界改写任务。
     */
    private ObjectNode request(String task) {
        ObjectNode input = objectMapper.createObjectNode();
        input.put("task", task);
        return input;
    }

    private void putProjectData(ObjectNode input, JsonNode projectData) {
        if (hasData(projectData)) input.set("projectData", projectData);
        else input.putNull("projectData");
    }

    private boolean hasData(JsonNode projectData) {
        return projectData != null && !projectData.isNull() && !projectData.isEmpty();
    }

    private String requiredText(Msg response, String errorMessage) {
        String content = text(response);
        if (content.isBlank()) throw new IllegalStateException(errorMessage);
        return content;
    }

    private String text(Msg response) {
        return response == null || response.getTextContent() == null
                ? "" : response.getTextContent().trim();
    }

    /** 清理模型照抄到 Markdown 中的 HTML 标签。 */
    static String sanitizeHtmlTags(String markdown) {
        return markdown
                .replaceAll("(?i)</?(strong|b)>", "**")
                .replaceAll("(?i)</?(em|i)>", "*")
                .replaceAll("(?i)<br\\s*/?>", " ")
                .replaceAll("</?[a-zA-Z][a-zA-Z0-9]*(?:\\s[^<>]*)?/?>", "")
                .trim();
    }

    static boolean isSubstantiallyComplete(String draft, String revised) {
        return revised != null && revised.length() >= draft.length() * 0.8D;
    }

    private void printModelMetrics(String stage, Msg response, long startNanos) {
        log.info("{}耗时毫秒: {}", stage, (System.nanoTime() - startNanos) / 1_000_000);
        if (response == null || response.getChatUsage() == null) {
            log.info("{}Token使用量：模型未返回usage", stage);
            return;
        }
        log.info("{}输入Token: {}", stage, response.getChatUsage().getInputTokens());
        log.info("{}输出Token: {}", stage, response.getChatUsage().getOutputTokens());
    }
}
