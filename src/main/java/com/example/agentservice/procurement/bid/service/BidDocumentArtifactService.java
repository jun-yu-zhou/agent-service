package com.example.agentservice.procurement.bid.service;

import com.example.agentservice.procurement.bid.domain.BidDocumentStage;
import com.example.agentservice.procurement.common.docx.Docx4jMarkdownDocxRenderer;
import java.io.IOException;
import java.util.Optional;
import org.springframework.stereotype.Service;

/** 将已生成的投标技术方案直接生成为可下载的 Word 内容。 */
@Service
public class BidDocumentArtifactService {

    private final BidDocumentStore store;
    private final Docx4jMarkdownDocxRenderer renderer;

    public BidDocumentArtifactService(BidDocumentStore store, Docx4jMarkdownDocxRenderer renderer) {
        this.store = store;
        this.renderer = renderer;
    }

    public Optional<byte[]> export(String taskId) throws IOException {
        var document = store.findByTaskId(taskId);
        if (document.isEmpty()) {
            return Optional.empty();
        }
        String stage = document.get().getStage();
        if (!BidDocumentStage.COMPLETED.name().equals(stage)
                && !BidDocumentStage.WAITING_MANUAL_COMPLETION.name().equals(stage)) {
            throw new IllegalStateException("投标技术方案尚未生成，暂不能导出 Word");
        }
        return Optional.of(renderer.renderBid(document.get().getDocumentMarkdown()));
    }
}
