package com.example.agentservice.procurement.bid.service;

import com.example.agentservice.agile.QwenDocResponseParser;
import com.example.agentservice.config.ModelConfig;
import com.example.agentservice.procurement.bid.domain.BidConsistencyReview;
import com.example.agentservice.procurement.bid.prompt.BidDocumentPrompts;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/** 检查技术方案对招标要求的响应完整性和准确性。 */
@Slf4j
@Service
public class BidConsistencyReviewService {

    private final ModelConfig modelConfig;

    public BidConsistencyReviewService(ModelConfig modelConfig) {
        this.modelConfig = modelConfig;
    }

    /**
     *
     * @param tenderFacts 招标文件核心要求（招标文件事实提炼结果）
     * @param supplierFacts 投标企业资料（供应商事实提炼结果）
     * @param documentMarkdown 待检查的投标技术方案（Markdown 正文）
     * @return
     */
    public BidConsistencyReview review(
            String tenderFacts, String supplierFacts, String documentMarkdown) {
        long startNanos = System.nanoTime();
        ReActAgent agent = ReActAgent.builder()
                .name("bid-consistency-reviewer")
                .sysPrompt(BidDocumentPrompts.consistencyReview())
                // 一致性检查使用 qwen3.7-plus 模型
                .model(modelConfig.qwen37PlusBidConsistencyModel())
                .build();
        Msg response = agent.call(Msg.builder()
                .role(MsgRole.USER)
                .textContent(reviewInput(tenderFacts, supplierFacts, documentMarkdown))
                .build()).block();
        if (response == null || response.getTextContent() == null || response.getTextContent().isBlank()) {
            throw new IllegalStateException("技术方案一致性检查未返回有效内容");
        }
        log.info("技术方案一致性检查耗时毫秒: {}", (System.nanoTime() - startNanos) / 1_000_000);
        if (response.getChatUsage() != null) {
            log.info("技术方案一致性检查输入Token: {}，输出Token: {}",
                    response.getChatUsage().getInputTokens(), response.getChatUsage().getOutputTokens());
        }
        return parse(response.getTextContent());
    }

    String reviewInput(String tenderFacts, String supplierFacts, String documentMarkdown) {
        return """
                招标文件核心要求：
                %s

                投标企业资料：
                %s

                待检查的投标技术方案：
                %s
                """.formatted(tenderFacts, supplierFacts, documentMarkdown);
    }

    BidConsistencyReview parse(String response) {
        return QwenDocResponseParser.parse(response, BidConsistencyReview.class);
    }
}
