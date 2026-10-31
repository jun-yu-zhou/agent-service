package com.example.agentservice.procurement.bid.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.agentservice.procurement.bid.persistence.BidDocumentEntity;
import com.example.agentservice.procurement.bid.request.BidDocumentCreateRequest;
import com.example.agentservice.procurement.bid.domain.TenderEssentialFacts;
import com.example.agentservice.procurement.bid.domain.BidTechnicalOutline;
import com.example.agentservice.procurement.bid.domain.BidConsistencyReview;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class BidDocumentTaskServiceTest {

    private final BidDocumentStore store = mock(BidDocumentStore.class);
    private final TenderFactsExtractionService extractionService = mock(TenderFactsExtractionService.class);
    private final BidOutlineGenerationService outlineService = mock(BidOutlineGenerationService.class);
    private final BidContentGenerationService contentService = mock(BidContentGenerationService.class);
    private final BidConsistencyReviewService reviewService = mock(BidConsistencyReviewService.class);
    private final BidSupplierFactsService supplierFactsService = mock(BidSupplierFactsService.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ExecutorService executor = mock(ExecutorService.class);
    private final BidDocumentTaskService service =
            new BidDocumentTaskService(
                    store, extractionService, outlineService, contentService, reviewService, supplierFactsService,
                    true,
                    objectMapper, executor);

    @Test
    void shouldCreateTaskFromOssUrl() throws Exception {
        BidDocumentEntity saved = new BidDocumentEntity();
        saved.setTaskId("task-1");
        when(supplierFactsService.load("project-1", "company-1"))
                .thenReturn(objectMapper.readTree("{\"companyName\":\"测试公司\"}"));
        when(store.create("招标文件.pdf", "https://bucket.oss-cn-beijing.aliyuncs.com/file.pdf",
                "{\"companyName\":\"测试公司\"}"))
                .thenReturn(saved);

        BidDocumentEntity result = service.create(new BidDocumentCreateRequest(
                "招标文件.pdf",
                "https://bucket.oss-cn-beijing.aliyuncs.com/file.pdf",
                "project-1", "company-1"));

        assertEquals("task-1", result.getTaskId());
        verify(store).create("招标文件.pdf", "https://bucket.oss-cn-beijing.aliyuncs.com/file.pdf",
                "{\"companyName\":\"测试公司\"}");
        verify(executor).execute(org.mockito.ArgumentMatchers.any(Runnable.class));
    }

    @Test
    void shouldRejectUnsupportedFile() {
        BidDocumentCreateRequest request = new BidDocumentCreateRequest(
                "招标文件.txt", "https://example.com/file.txt", "project-1", "company-1");

        assertThrows(IllegalArgumentException.class, () -> service.create(request));
    }

    @Test
    void shouldLoadSupplierFactsFromProjectAndCompany() throws Exception {
        BidDocumentEntity saved = new BidDocumentEntity();
        saved.setTaskId("task-2");
        when(supplierFactsService.load("project-1", "company-1"))
                .thenReturn(objectMapper.readTree("{\"companyName\":\"测试企业\"}"));
        when(store.create("招标文件.pdf", "https://example.com/file.pdf",
                "{\"companyName\":\"测试企业\"}"))
                .thenReturn(saved);

        service.create(new BidDocumentCreateRequest("招标文件.pdf", "https://example.com/file.pdf",
                "project-1", "company-1"));

        verify(supplierFactsService).load("project-1", "company-1");
        verify(store).create("招标文件.pdf", "https://example.com/file.pdf",
                "{\"companyName\":\"测试企业\"}");
    }

    @Test
    void shouldRejectSupplierLookupWhenDisabled() {
        BidDocumentTaskService disabled = new BidDocumentTaskService(
                store, extractionService, outlineService, contentService, reviewService,
                supplierFactsService, false, objectMapper, executor);
        assertThrows(IllegalStateException.class, () -> disabled.create(
                new BidDocumentCreateRequest("招标文件.pdf", "https://example.com/file.pdf",
                        "project-1", "company-1")));
    }

    @Test
    void shouldSaveExtractedFacts() {
        BidDocumentEntity document = new BidDocumentEntity();
        document.setTaskId("task-1");
        document.setSourceFileName("招标文件.pdf");
        document.setSourceUrl("https://example.com/file.pdf");
        document.setSupplierFacts("{}");
        when(supplierFactsService.load("project-1", "company-1"))
                .thenReturn(objectMapper.createObjectNode());
        when(store.create("招标文件.pdf", "https://example.com/file.pdf", "{}"))
                .thenReturn(document);
        TenderEssentialFacts facts = new TenderEssentialFacts(
                new TenderEssentialFacts.Project("家具采购", null, null, null, null),
                List.of(), List.of(), List.of(), List.of(), List.of());
        when(extractionService.extract(document.getSourceFileName(), document.getSourceUrl()))
                .thenReturn(facts);
        BidTechnicalOutline outline = new BidTechnicalOutline(
                "家具采购技术方案", List.of(new BidTechnicalOutline.Section(
                        "implementation", "实施方案", "说明实施安排", List.of("实施要求"),
                        BidTechnicalOutline.ContentMode.AI, List.of())));
        when(outlineService.generate(objectMapper.valueToTree(facts).toString(), "{}"))
                .thenReturn(outline);

        service.create(new BidDocumentCreateRequest(
                "招标文件.pdf", "https://example.com/file.pdf", "project-1", "company-1"));
        ArgumentCaptor<Runnable> task = ArgumentCaptor.forClass(Runnable.class);
        verify(executor).execute(task.capture());
        task.getValue().run();

        verify(store).completeExtraction("task-1", objectMapper.valueToTree(facts).toString());
        verify(store).completeOutline("task-1", objectMapper.valueToTree(outline).toString());
    }

    @Test
    void shouldSaveEditableOutline() throws Exception {
        BidDocumentEntity document = editableDocument();
        BidTechnicalOutline outline = outline();
        when(store.findByTaskId("task-1")).thenReturn(Optional.of(document));
        when(store.saveOutline("task-1", objectMapper.writeValueAsString(outline))).thenReturn(true);

        BidTechnicalOutline saved = service.saveOutline("task-1", outline).orElseThrow();

        assertEquals("技术方案", saved.title());
        verify(store).saveOutline("task-1", objectMapper.writeValueAsString(outline));
    }

    @Test
    void shouldRejectDuplicateSectionIds() {
        BidDocumentEntity document = editableDocument();
        when(store.findByTaskId("task-1")).thenReturn(Optional.of(document));
        BidTechnicalOutline outline = new BidTechnicalOutline("技术方案", List.of(
                new BidTechnicalOutline.Section("same", "实施方案", null, List.of(), null, List.of()),
                new BidTechnicalOutline.Section("same", "售后方案", null, List.of(), null, List.of())));

        assertThrows(IllegalArgumentException.class, () -> service.saveOutline("task-1", outline));
    }

    @Test
    void shouldConfirmOutlineOnce() throws Exception {
        BidDocumentEntity document = editableDocument();
        document.setOutlineJson(objectMapper.writeValueAsString(outline()));
        when(store.findByTaskId("task-1")).thenReturn(Optional.of(document));
        when(store.confirmOutline("task-1")).thenReturn(true);

        BidDocumentEntity confirmed = service.confirmOutline("task-1").orElseThrow();

        assertEquals("CONTENT_GENERATING", confirmed.getStage());
        verify(store).confirmOutline("task-1");
        verify(executor).execute(org.mockito.ArgumentMatchers.any(Runnable.class));
    }

    @Test
    void shouldSaveGeneratedDocument() throws Exception {
        BidDocumentEntity document = editableDocument();
        BidTechnicalOutline outline = outline();
        document.setOutlineJson(objectMapper.writeValueAsString(outline));
        document.setTenderFacts("招标要求");
        document.setSupplierFacts("企业资料");
        when(store.findByTaskId("task-1")).thenReturn(Optional.of(document));
        when(store.confirmOutline("task-1")).thenReturn(true);
        when(contentService.generate(outline, "招标要求", "企业资料"))
                .thenReturn("# 技术方案\n\n正文");
        BidConsistencyReview review = new BidConsistencyReview("PASS", "响应完整", List.of());
        when(reviewService.review("招标要求", "企业资料", "# 技术方案\n\n正文"))
                .thenReturn(review);

        service.confirmOutline("task-1");
        ArgumentCaptor<Runnable> task = ArgumentCaptor.forClass(Runnable.class);
        verify(executor).execute(task.capture());
        task.getValue().run();

        verify(store).completeContent("task-1", "# 技术方案\n\n正文", false);
        verify(store).completeReview("task-1", objectMapper.valueToTree(review).toString());
    }

    @Test
    void shouldWaitForManualChapterBeforeReview() throws Exception {
        BidDocumentEntity document = editableDocument();
        BidTechnicalOutline outline = new BidTechnicalOutline("技术方案", List.of(
                new BidTechnicalOutline.Section("manual", "签章材料", null, List.of(),
                        BidTechnicalOutline.ContentMode.MANUAL, List.of())));
        document.setOutlineJson(objectMapper.writeValueAsString(outline));
        document.setTenderFacts("招标要求");
        document.setSupplierFacts("企业资料");
        when(store.findByTaskId("task-1")).thenReturn(Optional.of(document));
        when(store.confirmOutline("task-1")).thenReturn(true);
        when(contentService.generate(outline, "招标要求", "企业资料"))
                .thenReturn("# 技术方案\n\n" + BidContentGenerationService.MANUAL_PLACEHOLDER);
        when(contentService.requiresManualCompletion(outline)).thenReturn(true);

        service.confirmOutline("task-1");
        ArgumentCaptor<Runnable> task = ArgumentCaptor.forClass(Runnable.class);
        verify(executor).execute(task.capture());
        task.getValue().run();

        verify(store).completeContent("task-1",
                "# 技术方案\n\n" + BidContentGenerationService.MANUAL_PLACEHOLDER, true);
        verify(reviewService, never()).review(
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void shouldRejectUnfilledManualChapter() {
        BidDocumentEntity document = new BidDocumentEntity();
        document.setStage("WAITING_MANUAL_COMPLETION");
        when(store.findByTaskId("task-1")).thenReturn(Optional.of(document));

        assertThrows(IllegalArgumentException.class, () -> service.saveDocument(
                "task-1", "# 技术方案\n\n" + BidContentGenerationService.MANUAL_PLACEHOLDER));
    }

    @Test
    void shouldSaveManualDocumentAndReviewAgain() {
        BidDocumentEntity document = new BidDocumentEntity();
        document.setTaskId("task-1");
        document.setStage("COMPLETED");
        document.setTenderFacts("招标要求");
        document.setSupplierFacts("企业资料");
        when(store.findByTaskId("task-1")).thenReturn(Optional.of(document));
        when(store.saveDocumentForReview("task-1", "# 修改后的技术方案"))
                .thenReturn(true);
        BidConsistencyReview review = new BidConsistencyReview("PASS", "响应完整", List.of());
        when(reviewService.review("招标要求", "企业资料", "# 修改后的技术方案"))
                .thenReturn(review);

        BidDocumentEntity saved = service.saveDocument(
                "task-1", "  # 修改后的技术方案  ").orElseThrow();
        ArgumentCaptor<Runnable> task = ArgumentCaptor.forClass(Runnable.class);
        verify(executor).execute(task.capture());
        task.getValue().run();

        assertEquals("CONSISTENCY_REVIEWING", saved.getStage());
        verify(store).completeReview("task-1", objectMapper.valueToTree(review).toString());
    }

    private BidDocumentEntity editableDocument() {
        BidDocumentEntity document = new BidDocumentEntity();
        document.setTaskId("task-1");
        document.setStage("WAITING_OUTLINE_CONFIRMATION");
        document.setOutlineConfirmed(false);
        return document;
    }

    private BidTechnicalOutline outline() {
        return new BidTechnicalOutline("技术方案", List.of(new BidTechnicalOutline.Section(
                "implementation", "实施方案", "说明实施安排", List.of("实施要求"),
                BidTechnicalOutline.ContentMode.AI, List.of())));
    }
}
