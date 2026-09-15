package com.example.agentservice.procurement.bid.service;

import com.example.agentservice.agile.QwenDocResponseParser;
import com.example.agentservice.config.ModelConfig;
import com.example.agentservice.formatter.QwenDocDashScopeChatFormatter;
import com.example.agentservice.procurement.bid.domain.TenderEssentialFacts;
import com.example.agentservice.procurement.bid.prompt.BidDocumentPrompts;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/** 使用文档模型抽取招标文件中的核心要求。 */
@Slf4j
@Service
public class TenderFactsExtractionService {

    private final ModelConfig modelConfig;

    public TenderFactsExtractionService(ModelConfig modelConfig) {
        this.modelConfig = modelConfig;
    }

    public TenderEssentialFacts extract(String fileName, String sourceUrl) {
        long startNanos = System.nanoTime();
        ReActAgent agent = ReActAgent.builder()
                .name("tender-facts-extractor")
                .sysPrompt(BidDocumentPrompts.tenderFactsExtraction())
                // 事实抽取阶段使用 doc 模型
                .model(modelConfig.qwenDocTenderFactsModel())
                .build();
        Msg response = agent.call(Msg.builder()
                .role(MsgRole.USER)
                .textContent("请读取《" + fileName + "》并抽取核心事实。")
                .metadata(Map.of(
                        QwenDocDashScopeChatFormatter.DOC_URLS_METADATA_KEY, List.of(sourceUrl),
                        QwenDocDashScopeChatFormatter.FILE_PARSING_STRATEGY_METADATA_KEY, "auto"))
                .build()).block();
        if (response == null || response.getTextContent() == null || response.getTextContent().isBlank()) {
            throw new IllegalStateException("招标文件事实抽取未返回有效内容");
        }
        // 记录耗时
        log.info("招标事实抽取耗时毫秒: {}", (System.nanoTime() - startNanos) / 1_000_000);
        // 记录Token使用情况
        if (response.getChatUsage() != null) {
            log.info("招标事实抽取输入Token: {}，输出Token: {}",
                    response.getChatUsage().getInputTokens(), response.getChatUsage().getOutputTokens());
        }
        return parse(response.getTextContent());
    }

    TenderEssentialFacts parse(String response) {
        return QwenDocResponseParser.parse(response, TenderEssentialFacts.class);
    }
}
