package com.example.agentservice.procurement.bid.service;

import com.example.agentservice.config.ModelConfig;
import com.example.agentservice.procurement.bid.domain.BidTechnicalOutline;
import com.example.agentservice.procurement.bid.prompt.BidDocumentPrompts;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/** 为技术方案中的一个末级章节生成正文。 */
@Slf4j
@Service
public class BidSectionGenerationService {

    private final ModelConfig modelConfig;
    private final ObjectMapper objectMapper;

    public BidSectionGenerationService(ModelConfig modelConfig, ObjectMapper objectMapper) {
        this.modelConfig = modelConfig;
        this.objectMapper = objectMapper;
    }

    public String generate(
            // 已确认的完整技术方案目录（含全部章节树）
            BidTechnicalOutline outline,
            // 当前需要生成正文的末级章节
            BidTechnicalOutline.Section section,
            // 招标文件核心要求（招标文件事实提炼结果）
            String tenderFacts,
            // 投标企业资料（供应商事实提炼结果）
            String supplierFacts) {
        validateLeaf(section);
        long startNanos = System.nanoTime();
        ReActAgent agent = ReActAgent.builder()
                .name("bid-section-content-generator")
                .sysPrompt(BidDocumentPrompts.SECTION_CONTENT_GENERATION)
                // 技术方案章节生成阶段使用 qwen3.7-flash 模型
                .model(modelConfig.qwen37FlashBidContentModel())
                .build();
        Msg response = agent.call(Msg.builder()
                .role(MsgRole.USER)
                .textContent(generationInput(outline, section, tenderFacts, supplierFacts))
                .build()).block();
        if (response == null || response.getTextContent() == null || response.getTextContent().isBlank()) {
            throw new IllegalStateException("技术方案章节生成未返回有效内容: " + section.title());
        }
        log.info("技术方案章节[{}]生成耗时毫秒: {}", section.title(),
                (System.nanoTime() - startNanos) / 1_000_000);
        if (response.getChatUsage() != null) {
            log.info("技术方案章节[{}]输入Token: {}，输出Token: {}", section.title(),
                    response.getChatUsage().getInputTokens(), response.getChatUsage().getOutputTokens());
        }
        return response.getTextContent().trim();
    }

    /** 提示词编排 */
    String generationInput(
            BidTechnicalOutline outline,
            BidTechnicalOutline.Section section,
            String tenderFacts,
            String supplierFacts) {
        validateLeaf(section);
        return """
                招标文件核心要求：
                %s

                投标企业资料：
                %s

                已确认的完整目录：
                %s

                当前章节：
                %s
                """.formatted(tenderFacts, supplierFacts, json(outline), json(section));
    }

    private void validateLeaf(BidTechnicalOutline.Section section) {
        if (section == null || section.id() == null || section.id().isBlank()
                || section.title() == null || section.title().isBlank()) {
            throw new IllegalArgumentException("待生成章节必须包含ID和标题");
        }
        if (section.children() != null && !section.children().isEmpty()) {
            throw new IllegalArgumentException("仅末级章节可以直接生成正文");
        }
        if (section.effectiveContentMode() != BidTechnicalOutline.ContentMode.AI) {
            throw new IllegalArgumentException("仅AI生成章节可以调用正文模型");
        }
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        }
        catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("技术方案目录无法序列化", exception);
        }
    }
}
