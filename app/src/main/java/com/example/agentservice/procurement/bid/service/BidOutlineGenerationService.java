package com.example.agentservice.procurement.bid.service;

import com.example.agentservice.agile.QwenDocResponseParser;
import com.example.agentservice.config.ModelConfig;
import com.example.agentservice.procurement.bid.domain.BidTechnicalOutline;
import com.example.agentservice.procurement.bid.prompt.BidDocumentPrompts;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/** 根据招标核心事实和企业资料生成技术方案目录。 */
@Slf4j
@Service
public class BidOutlineGenerationService {

    private final ModelConfig modelConfig;

    public BidOutlineGenerationService(ModelConfig modelConfig) {
        this.modelConfig = modelConfig;
    }

    /**
     *
     * @param tenderFacts 招标文件核心要求（招标文件事实提炼结果）
     * @param supplierFacts 投标企业资料（供应商事实提炼结果）
     * @return
     */
    public BidTechnicalOutline generate(String tenderFacts, String supplierFacts) {
        long startNanos = System.nanoTime();
        ReActAgent agent = ReActAgent.builder()
                .name("bid-technical-outline-generator")
                .sysPrompt(BidDocumentPrompts.outlineGeneration())
                // 技术方案目录生成阶段使用 qwen3.7-flash 模型
                .model(modelConfig.qwen37FlashBidOutlineModel())
                .build();
        Msg response = agent.call(Msg.builder()
                .role(MsgRole.USER)
                .textContent("招标文件核心要求：\n" + tenderFacts
                        + "\n\n投标企业资料：\n" + supplierFacts)
                .build()).block();
        if (response == null || response.getTextContent() == null || response.getTextContent().isBlank()) {
            throw new IllegalStateException("技术方案目录生成未返回有效内容");
        }
        log.info("技术方案目录生成耗时毫秒: {}", (System.nanoTime() - startNanos) / 1_000_000);
        if (response.getChatUsage() != null) {
            log.info("技术方案目录生成输入Token: {}，输出Token: {}",
                    response.getChatUsage().getInputTokens(), response.getChatUsage().getOutputTokens());
        }
        return parse(response.getTextContent());
    }

    BidTechnicalOutline parse(String response) {
        return QwenDocResponseParser.parse(response, BidTechnicalOutline.class);
    }
}
