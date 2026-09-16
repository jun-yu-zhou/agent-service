package com.example.agentservice.procurement.bid.service;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.agentservice.procurement.bid.persistence.BidDocumentEntity;
import com.example.agentservice.procurement.common.docx.Docx4jMarkdownDocxRenderer;
import org.docx4j.openpackaging.packages.WordprocessingMLPackage;
import org.docx4j.wml.P;
import org.docx4j.TextUtils;
import java.io.ByteArrayInputStream;
import java.util.Optional;
import java.util.zip.ZipInputStream;
import org.junit.jupiter.api.Test;

class BidDocumentArtifactServiceTest {

    private final BidDocumentStore store = mock(BidDocumentStore.class);
    private final Docx4jMarkdownDocxRenderer renderer = mock(Docx4jMarkdownDocxRenderer.class);
    private final BidDocumentArtifactService service = new BidDocumentArtifactService(store, renderer);

    @Test
    void exportsCompletedDocument() throws Exception {
        BidDocumentEntity document = new BidDocumentEntity();
        document.setStage("COMPLETED");
        document.setDocumentMarkdown("# 投标技术方案\n\n正文");
        when(store.findByTaskId("task-1")).thenReturn(Optional.of(document));
        when(renderer.renderBid(document.getDocumentMarkdown())).thenReturn(new byte[] {1, 2});

        assertArrayEquals(new byte[] {1, 2}, service.export("task-1").orElseThrow());
        verify(renderer).renderBid(document.getDocumentMarkdown());
    }

    @Test
    void rejectsIncompleteDocument() {
        BidDocumentEntity document = new BidDocumentEntity();
        document.setStage("CONTENT_GENERATING");
        when(store.findByTaskId("task-1")).thenReturn(Optional.of(document));

        assertThrows(IllegalStateException.class, () -> service.export("task-1"));
    }

    @Test
    void rendersDownloadableDocxWithoutDatabase() throws Exception {
        byte[] content = new Docx4jMarkdownDocxRenderer().renderBid(
                "# 投标技术方案\n\n## 产品技术参数响应方案\n\n### 参数响应\n\n逐项响应。"
                        + "\n\n### 质量保证\n\n提供质量保证。\n\n## 服务方案\n\n独立章节正文。");

        try (ZipInputStream archive = new ZipInputStream(new ByteArrayInputStream(content))) {
            org.junit.jupiter.api.Assertions.assertNotNull(archive.getNextEntry());
        }
        WordprocessingMLPackage word = WordprocessingMLPackage.load(new ByteArrayInputStream(content));
        var paragraphs = word.getMainDocumentPart().getContent().stream()
                .map(org.docx4j.XmlUtils::unwrap).filter(P.class::isInstance).map(P.class::cast)
                .toList();
        int directoryIndex = -1;
        for (int index = 0; index < paragraphs.size(); index++) {
            if ("目录".equals(TextUtils.getText(paragraphs.get(index)).trim())) {
                directoryIndex = index;
                break;
            }
        }
        org.junit.jupiter.api.Assertions.assertTrue(directoryIndex > 0);
        org.junit.jupiter.api.Assertions.assertTrue(paragraphs.size() > directoryIndex + 2,
                "目录标题之后应包含目录条目和正文章节");
        var headings = word.getMainDocumentPart().getContent().stream()
                .map(org.docx4j.XmlUtils::unwrap).filter(P.class::isInstance).map(P.class::cast)
                .filter(paragraph -> paragraph.getPPr() != null && paragraph.getPPr().getPStyle() != null
                        && paragraph.getPPr().getPStyle().getVal().startsWith("Heading"))
                .toList();
        org.junit.jupiter.api.Assertions.assertEquals(5, headings.size());
        org.junit.jupiter.api.Assertions.assertNull(headings.get(0).getPPr().getPageBreakBefore());
        org.junit.jupiter.api.Assertions.assertNull(headings.get(1).getPPr().getPageBreakBefore());
        for (int index = 2; index < headings.size(); index++) {
            org.junit.jupiter.api.Assertions.assertNotNull(headings.get(index).getPPr().getPageBreakBefore());
        }
    }

    @Test
    void ordinaryRendererDoesNotBreakEveryHeading() throws Exception {
        byte[] content = new Docx4jMarkdownDocxRenderer().render(
                "# 招标文件\n\n## 项目说明\n\n正文内容。");
        WordprocessingMLPackage word = WordprocessingMLPackage.load(new ByteArrayInputStream(content));
        P subheading = word.getMainDocumentPart().getContent().stream()
                .map(org.docx4j.XmlUtils::unwrap).filter(P.class::isInstance).map(P.class::cast)
                .filter(paragraph -> paragraph.getPPr() != null && paragraph.getPPr().getPStyle() != null
                        && "Heading2".equals(paragraph.getPPr().getPStyle().getVal()))
                .findFirst().orElseThrow();
        org.junit.jupiter.api.Assertions.assertNull(subheading.getPPr().getPageBreakBefore());
    }
}
