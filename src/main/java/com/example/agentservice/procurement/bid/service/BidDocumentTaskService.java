package com.example.agentservice.procurement.bid.service;

import com.example.agentservice.procurement.bid.persistence.BidDocumentEntity;
import com.example.agentservice.procurement.bid.request.BidDocumentCreateRequest;
import com.example.agentservice.procurement.bid.domain.BidDocumentStage;
import com.example.agentservice.procurement.bid.domain.BidTechnicalOutline;
import com.example.agentservice.procurement.bid.domain.BidConsistencyReview;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.util.Locale;
import java.util.Optional;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** 创建和推进投标技术方案生成任务。 */
@Service
public class BidDocumentTaskService {

    private static final Set<String> SUPPORTED_SUFFIXES = Set.of("pdf", "doc", "docx");

    private final BidDocumentStore store;
    private final TenderFactsExtractionService extractionService;
    private final BidOutlineGenerationService outlineService;
    private final BidContentGenerationService contentService;
    private final BidConsistencyReviewService reviewService;
    private final BidSupplierFactsService supplierFactsService;
    private final boolean supplierLookupEnabled;
    private final ObjectMapper objectMapper;
    private final ExecutorService executor;

    public BidDocumentTaskService(
            BidDocumentStore store,
            TenderFactsExtractionService extractionService,
            BidOutlineGenerationService outlineService,
            BidContentGenerationService contentService,
            BidConsistencyReviewService reviewService,
            BidSupplierFactsService supplierFactsService,
            @Value("${app.bid.supplier-lookup-enabled:false}") boolean supplierLookupEnabled,
            ObjectMapper objectMapper,
            @Qualifier("procurementDocumentExecutor") ExecutorService executor) {
        this.store = store;
        this.extractionService = extractionService;
        this.outlineService = outlineService;
        this.contentService = contentService;
        this.reviewService = reviewService;
        this.supplierFactsService = supplierFactsService;
        this.supplierLookupEnabled = supplierLookupEnabled;
        this.objectMapper = objectMapper;
        this.executor = executor;
    }

    /** 创建任务；模型处理将在后续阶段接入。 */
    public BidDocumentEntity create(BidDocumentCreateRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("请求不能为空");
        }
        // 校验
        validateFileName(request.sourceFileName());
        validateSourceUrl(request.sourceUrl());
        if (!supplierLookupEnabled) {
            throw new IllegalStateException("当前环境未启用投标企业资料查询");
        }
        JsonNode supplierFacts = supplierFactsService.load(request.zbProjectId(), request.companyId());
        if (supplierFacts == null || !supplierFacts.isObject()) {
            throw new IllegalArgumentException("投标企业资料必须是 JSON 对象");
        }
        BidDocumentEntity document = store.create(request.sourceFileName().trim(), request.sourceUrl().trim(),
                writeJson(supplierFacts));
        executor.execute(() -> extract(document));
        return document;
    }

    public Optional<BidDocumentEntity> find(String taskId) {
        return store.findByTaskId(taskId);
    }

    /** 读取已生成的目录，供前端树形编辑器展示。 */
    public Optional<BidTechnicalOutline> findOutline(String taskId) {
        return store.findByTaskId(taskId).map(document -> {
            if (document.getOutlineJson() == null || document.getOutlineJson().isBlank()) {
                throw new IllegalStateException("技术方案目录尚未生成完成");
            }
            return readOutline(document.getOutlineJson());
        });
    }

    /** 完整覆盖尚未确认的目录。 */
    public Optional<BidTechnicalOutline> saveOutline(String taskId, BidTechnicalOutline outline) {
        Optional<BidDocumentEntity> document = store.findByTaskId(taskId);
        if (document.isEmpty()) {
            return Optional.empty();
        }
        requireEditableOutline(document.get());
        validateOutline(outline);
        if (!store.saveOutline(taskId, writeJson(objectMapper.valueToTree(outline)))) {
            throw new IllegalStateException("目录状态已发生变化，请刷新后重试");
        }
        return Optional.of(outline);
    }

    /** 确认当前目录并进入正文生成阶段。 */
    public Optional<BidDocumentEntity> confirmOutline(String taskId) {
        Optional<BidDocumentEntity> document = store.findByTaskId(taskId);
        if (document.isEmpty()) {
            return Optional.empty();
        }
        requireEditableOutline(document.get());
        validateOutline(readOutline(document.get().getOutlineJson()));
        if (!store.confirmOutline(taskId)) {
            throw new IllegalStateException("目录状态已发生变化，请刷新后重试");
        }
        document.get().setOutlineConfirmed(true);
        document.get().setStage(BidDocumentStage.CONTENT_GENERATING.name());
        executor.execute(() -> generateContent(document.get()));
        return document;
    }

    /** 返回已完成的正文和一致性检查结果。 */
    public Optional<Result> findResult(String taskId) {
        return store.findByTaskId(taskId).map(document -> {
            if (!BidDocumentStage.COMPLETED.name().equals(document.getStage())) {
                throw new IllegalStateException("投标技术方案尚未生成完成");
            }
            return new Result(document.getDocumentMarkdown(), readReview(document.getConsistencyReview()));
        });
    }

    /** 保存用户明确提交的修改，并重新执行一致性检查。 */
    public Optional<BidDocumentEntity> saveDocument(String taskId, String markdown) {
        Optional<BidDocumentEntity> document = store.findByTaskId(taskId);
        if (document.isEmpty()) {
            return Optional.empty();
        }
        if (markdown == null || markdown.isBlank()) {
            throw new IllegalArgumentException("投标技术方案正文不能为空");
        }
        if (!BidDocumentStage.COMPLETED.name().equals(document.get().getStage())) {
            throw new IllegalStateException("当前任务状态不允许保存人工修改");
        }
        String content = markdown.trim();
        if (!store.saveDocumentForReview(taskId, content)) {
            throw new IllegalStateException("文档状态已发生变化，请刷新后重试");
        }
        document.get().setDocumentMarkdown(content);
        document.get().setConsistencyReview(null);
        document.get().setStage(BidDocumentStage.CONSISTENCY_REVIEWING.name());
        executor.execute(() -> reviewContent(document.get(), content));
        return document;
    }

    /** 验证招标文件名、格式 */
    private void validateFileName(String fileName) {
        if (fileName == null || fileName.isBlank()) {
            throw new IllegalArgumentException("招标文件名不能为空");
        }
        int separator = fileName.lastIndexOf('.');
        String suffix = separator < 0 ? "" : fileName.substring(separator + 1).toLowerCase(Locale.ROOT);
        if (!SUPPORTED_SUFFIXES.contains(suffix)) {
            throw new IllegalArgumentException("招标文件仅支持 PDF、DOC、DOCX");
        }
    }

    /** 验证招标文件地址 */
    private void validateSourceUrl(String sourceUrl) {
        try {
            URI uri = URI.create(sourceUrl == null ? "" : sourceUrl.trim());
            if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                    || uri.getHost() == null) {
                throw new IllegalArgumentException("招标文件地址必须是有效的 HTTP 地址");
            }
        }
        catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("招标文件地址必须是有效的 HTTP 地址", exception);
        }
    }

    /** 将 JSON 节点写入 JSON 字符串 */
    private String writeJson(JsonNode value) {
        try {
            return objectMapper.writeValueAsString(value);
        }
        catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("投标企业资料无法序列化", exception);
        }
    }

    private BidTechnicalOutline readOutline(String json) {
        try {
            return objectMapper.readValue(json, BidTechnicalOutline.class);
        }
        catch (JsonProcessingException exception) {
            throw new IllegalStateException("技术方案目录数据无法解析", exception);
        }
    }

    private BidConsistencyReview readReview(String json) {
        try {
            return objectMapper.readValue(json, BidConsistencyReview.class);
        }
        catch (JsonProcessingException | IllegalArgumentException exception) {
            throw new IllegalStateException("一致性检查结果无法解析", exception);
        }
    }

    private void requireEditableOutline(BidDocumentEntity document) {
        if (!BidDocumentStage.WAITING_OUTLINE_CONFIRMATION.name().equals(document.getStage())
                || Boolean.TRUE.equals(document.getOutlineConfirmed())) {
            throw new IllegalStateException("当前任务状态不允许修改或确认目录");
        }
    }

    /** 验证目录结构 */
    private void validateOutline(BidTechnicalOutline outline) {
        if (outline == null || outline.title() == null || outline.title().isBlank()) {
            throw new IllegalArgumentException("技术方案目录标题不能为空");
        }
        if (outline.sections() == null || outline.sections().isEmpty()) {
            throw new IllegalArgumentException("技术方案目录至少需要一个章节");
        }
        validateSections(outline.sections(), new HashSet<>());
    }

    /** 验证目录章节ID是否重复 */
    private void validateSections(Iterable<BidTechnicalOutline.Section> sections, HashSet<String> ids) {
        for (BidTechnicalOutline.Section section : sections) {
            if (section == null || section.id() == null || section.id().isBlank()
                    || section.title() == null || section.title().isBlank()) {
                throw new IllegalArgumentException("每个目录章节都必须包含ID和标题");
            }
            if (!ids.add(section.id())) {
                throw new IllegalArgumentException("目录章节ID不能重复: " + section.id());
            }
            if (section.children() != null) {
                validateSections(section.children(), ids);
            }
        }
    }

    private void extract(BidDocumentEntity document) {
        try {
            // 提取招标文件事实
            var facts = extractionService.extract(document.getSourceFileName(), document.getSourceUrl());
            String tenderFacts = writeJson(objectMapper.valueToTree(facts));
            store.completeExtraction(document.getTaskId(), tenderFacts);
            // 生成投标文件目录
            var outline = outlineService.generate(tenderFacts, document.getSupplierFacts());
            store.completeOutline(document.getTaskId(), writeJson(objectMapper.valueToTree(outline)));
        }
        catch (Exception exception) {
            store.fail(document.getTaskId(), exception.getMessage());
        }
    }

    private void generateContent(BidDocumentEntity document) {
        try {
            String markdown = contentService.generate(
                    readOutline(document.getOutlineJson()),
                    document.getTenderFacts(),
                    document.getSupplierFacts());
            store.completeContent(document.getTaskId(), markdown);
            reviewContent(document, markdown);
        }
        catch (Exception exception) {
            store.fail(document.getTaskId(), exception.getMessage());
        }
    }

    private void reviewContent(BidDocumentEntity document, String markdown) {
        try {
            var review = reviewService.review(
                    document.getTenderFacts(), document.getSupplierFacts(), markdown);
            store.completeReview(document.getTaskId(), writeJson(objectMapper.valueToTree(review)));
        }
        catch (Exception exception) {
            store.fail(document.getTaskId(), exception.getMessage());
        }
    }

    public record Result(String documentMarkdown, BidConsistencyReview consistencyReview) {
    }
}
