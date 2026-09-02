package com.example.agentservice.agile;


import com.example.agentservice.AgentServiceApplication;
import com.example.agentservice.config.AgentServiceConfig;
import com.example.agentservice.config.ModelConfig;
import com.example.agentservice.entity.CibBasicInfoFacts;
import com.example.agentservice.entity.CibCollusionReviewData;
import com.example.agentservice.entity.CibCollusionReviewResult;
import com.example.agentservice.entity.ImmImagePage;
import com.example.agentservice.prompts.ImmBatchBasicInfoReviewPrompts;
import com.example.agentservice.utils.PdfUtils;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.EventType;
import io.agentscope.core.agent.StreamOptions;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.ImageBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.URLSource;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.core.rag.Knowledge;
import io.agentscope.core.rag.integration.bailian.BailianConfig;
import io.agentscope.core.rag.integration.bailian.BailianKnowledge;
import io.agentscope.core.rag.model.Document;
import io.agentscope.core.rag.model.RetrieveConfig;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.stream.IntStream;

/** IMM 页面图片分批抽取、归并事实、专项审查后再生成围标串标报告。 */
public class ImmBatchBasicInfoReviewBy37Plus {

    private static final int IMAGE_BATCH_SIZE = 256;
    private static final int EXTRACTION_THREAD_COUNT = 6;
    private static final String BAILIAN_WORKSPACE_ID = "llm-2c213fyvomyxzuc9";
    private static final String BAILIAN_KNOWLEDGE_INDEX_ID = "94kvimrfoy";
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final List<String> PDF_URLS = List.of();
    private final PdfUtils pdfUtils;
    private final ModelConfig modelConfig;
    private final Knowledge reportKnowledge;

    public ImmBatchBasicInfoReviewBy37Plus(PdfUtils pdfUtils, ModelConfig modelConfig) {
        this.pdfUtils = pdfUtils;
        this.modelConfig = modelConfig;
        BailianConfig config = BailianConfig.builder()
                .accessKeyId(AgentServiceConfig.ossAccessKeyId())
                .accessKeySecret(AgentServiceConfig.ossAccessKeySecret())
                .workspaceId(BAILIAN_WORKSPACE_ID)
                .indexId(BAILIAN_KNOWLEDGE_INDEX_ID)
                .endpoint("bailian.cn-beijing.aliyuncs.com")
                .denseSimilarityTopK(10)
                .sparseSimilarityTopK(10)
                .enableReranking(true)
                .build();
        this.reportKnowledge = BailianKnowledge.builder()
                .config(config)
                .indexId(BAILIAN_KNOWLEDGE_INDEX_ID)
                .build();
    }

    public static void main(String[] args) {
        SpringApplicationBuilder application = new SpringApplicationBuilder(AgentServiceApplication.class)
                .web(WebApplicationType.NONE);
        try (ConfigurableApplicationContext context = application.run()) {
            ImmBatchBasicInfoReviewBy37Plus review = new ImmBatchBasicInfoReviewBy37Plus(
                    context.getBean(PdfUtils.class),
                    context.getBean(ModelConfig.class));
            review.execute(args.length == 0 ? PDF_URLS : Arrays.asList(args));
        } catch (Exception exception) {
            throw new IllegalStateException("IMM分批基础信息雷同审查失败", exception);
        }
    }

    /** 按“转图片、分批抽取、归并审查、生成报告”四个阶段执行基础信息审查。 */
    public void execute(List<String> pdfUrls) throws Exception {
        validateUrls(pdfUrls);
        long startNanos = System.nanoTime();
        System.out.println("阶段1/4：将PDF转为页面图片...");
        ExecutorService conversionExecutor = Executors.newFixedThreadPool(Math.max(1, pdfUrls.size()));
        List<DocumentMaterial> materials;
        try {
            materials = IntStream.range(0, pdfUrls.size())
                    .mapToObj(index -> CompletableFuture.supplyAsync(() -> {
                        try {
                            return new DocumentMaterial("文件" + (index + 1),
                                    pdfUtils.pdfToImage(pdfUrls.get(index)));
                        } catch (Exception exception) {
                            throw new CompletionException("PDF转图片失败: " + pdfUrls.get(index), exception);
                        }
                    }, conversionExecutor))
                    .toList().stream()
                    .map(CompletableFuture::join)
                    .toList();
        } finally {
            conversionExecutor.shutdown();
        }
        int imageCount = materials.stream().mapToInt(item -> item.pages().size()).sum();
        System.out.println("文件数=" + materials.size() + "，总图片数=" + imageCount);

        System.out.println("阶段2/4：按每批最多" + IMAGE_BATCH_SIZE + "张图片抽取基础、报价与版式事实...");
        List<ExtractionOutput> outputs = extractFacts(materials);
        long parsedCount = outputs.stream().filter(output -> output.facts() != null).count();
        System.out.println("完成抽取批次数=" + outputs.size() + "，成功解析JSON=" + parsedCount);
        if (outputs.isEmpty()) {
            throw new IllegalStateException("所有基础信息抽取批次均未返回内容，终止报告生成");
        }

        CibCollusionReviewData reviewData = aggregateFacts(outputs);
        System.out.println("阶段3/4：归并为" + reviewData.bidders().size() + "个投标人、"
                + reviewData.unassignedDocuments().size() + "个待归属文件，并执行围标串标专项审查...");
        CibCollusionReviewResult reviewResult = reviewCollusion(reviewData);

        System.out.println("阶段4/4：根据专项审查结论生成报告...");
        System.out.println(generateReport(reviewData, reviewResult));
        System.out.printf("总耗时: %.3f 秒%n", (System.nanoTime() - startNanos) / 1_000_000_000D);
    }

    /** 按页分批并发抽取，批次边界同时作为最终证据的页码映射。 */
    private List<ExtractionOutput> extractFacts(List<DocumentMaterial> materials) {
        List<ExtractionTask> tasks = new ArrayList<>();
        for (DocumentMaterial material : materials) {
            for (int start = 0; start < material.pages().size(); start += IMAGE_BATCH_SIZE) {
                int end = Math.min(start + IMAGE_BATCH_SIZE, material.pages().size());
                tasks.add(new ExtractionTask(material, start / IMAGE_BATCH_SIZE + 1,
                        material.pages().subList(start, end)));
            }
            if (material.pages().isEmpty()) {
                tasks.add(new ExtractionTask(material, 1, List.of()));
            }
        }

        ExecutorService executor = Executors.newFixedThreadPool(
                Math.min(EXTRACTION_THREAD_COUNT, Math.max(1, tasks.size())));
        try {
            return tasks.stream()
                    .map(task -> CompletableFuture.supplyAsync(() -> extractBatch(task), executor))
                    .toList().stream()
                    .map(CompletableFuture::join)
                    .filter(Objects::nonNull)
                    .toList();
        } finally {
            executor.shutdown();
        }
    }

    /** 单批图片仅抽取页面事实，解析失败时记录原始响应用于诊断。 */
    private ExtractionOutput extractBatch(ExtractionTask task) {
        String label = task.pages().isEmpty()
                ? task.material().documentId() + "#批次" + task.batchNumber() + "#无图片"
                : task.material().documentId() + "#批次" + task.batchNumber() + "#第"
                        + task.pages().get(0).page() + "-"
                        + task.pages().get(task.pages().size() - 1).page() + "页";
        try {
            ReActAgent agent = ReActAgent.builder()
                    .name("basic-info-extractor-" + task.batchNumber())
                    .sysPrompt(ImmBatchBasicInfoReviewPrompts.extractionPrompt())
                    .model(modelConfig.qwen37PlusStreamingReviewModel())
                    .build();
            StreamResult result = stream(agent, buildExtractionRequest(task), label);
            printTokenUsage("基础信息抽取", label, result.usage());
            try {
                CibBasicInfoFacts facts = QwenDocResponseParser.parse(
                        result.text(), CibBasicInfoFacts.class);
                return new ExtractionOutput(task.material().documentId(), task.batchNumber(),
                        task.pages().isEmpty() ? null : task.pages().get(0).page(),
                        task.pages().isEmpty() ? null : task.pages().get(task.pages().size() - 1).page(), facts);
            } catch (IllegalArgumentException parseException) {
                System.err.println("基础信息JSON解析失败[" + label + "]："
                        + parseException.getMessage());
                System.err.println("原始响应字符数[" + label + "]：" + result.text().length()
                        + "，末尾片段：" + result.text().substring(Math.max(0, result.text().length() - 200))
                                .replaceAll("[\\r\\n]+", " "));
                System.err.println("----- 原始响应开始[" + label + "] -----");
                System.err.println(result.text());
                System.err.println("----- 原始响应结束[" + label + "] -----");
                return new ExtractionOutput(task.material().documentId(), task.batchNumber(),
                        task.pages().isEmpty() ? null : task.pages().get(0).page(),
                        task.pages().isEmpty() ? null : task.pages().get(task.pages().size() - 1).page(), null);
            }
        } catch (Exception exception) {
            System.err.println("基础信息抽取失败[" + label + "]：" + exception.getMessage());
            return null;
        }
    }

    private Msg buildExtractionRequest(ExtractionTask task) {
        List<ContentBlock> content = new ArrayList<>(task.pages().size() + 1);
        for (ImmImagePage page : task.pages()) {
            content.add(ImageBlock.builder()
                    .source(URLSource.builder().url(page.url()).build())
                    .build());
        }
        String pageRange = task.pages().isEmpty() ? "无页面图片"
                : "PDF第" + task.pages().get(0).page() + "页至第"
                        + task.pages().get(task.pages().size() - 1).page() + "页";
        content.add(TextBlock.builder().text("""
                文件编号：%s
                当前图片范围：%s
                当前为该文件第%d个图片批次。图片顺序与PDF页码一致。

                请仅依据当前批次页面图片抽取基础信息事实。
                """.formatted(task.material().documentId(), pageRange,
                task.batchNumber())).build());
        return Msg.builder().role(MsgRole.USER).content(content).build();
    }

    /** 聚合流式文本和响应使用量，避免遗漏最后一条 ChatUsage。 */
    private StreamResult stream(ReActAgent agent, Msg request, String label) {
        StringBuilder text = new StringBuilder();
        AtomicReference<ChatUsage> usage = new AtomicReference<>();
        StreamOptions options = StreamOptions.builder()
                .eventTypes(EventType.AGENT_RESULT)
                .incremental(true)
                .build();
        agent.stream(request, options).doOnNext(event -> {
            Msg message = event.getMessage();
            if (message == null) {
                return;
            }
            String chunk = message.getTextContent();
            if (chunk != null && !chunk.isEmpty()) {
                text.append(chunk);
            }
            if (message.getChatUsage() != null) {
                usage.set(message.getChatUsage());
            }
        }).blockLast();
        if (text.isEmpty()) {
            throw new IllegalStateException("流式抽取未返回内容: " + label);
        }
        return new StreamResult(text.toString(), usage.get());
    }

    /** 先将同一文件的批次合并，再将可识别的同一投标人文件归到同一组。 */
    private CibCollusionReviewData aggregateFacts(List<ExtractionOutput> outputs) {
        LinkedHashMap<String, List<ExtractionOutput>> documentOutputs = new LinkedHashMap<>();
        for (ExtractionOutput output : outputs) {
            documentOutputs.computeIfAbsent(output.documentId(), ignored -> new ArrayList<>()).add(output);
        }

        LinkedHashMap<String, List<CibCollusionReviewData.DocumentFacts>> bidderDocuments = new LinkedHashMap<>();
        List<CibCollusionReviewData.DocumentFacts> unassignedDocuments = new ArrayList<>();
        for (var entry : documentOutputs.entrySet()) {
            CibCollusionReviewData.DocumentFacts documentFacts = mergeDocumentFacts(entry.getKey(), entry.getValue());
            if (isKnownSupplier(documentFacts.supplierName())) {
                bidderDocuments.computeIfAbsent(documentFacts.supplierName(), ignored -> new ArrayList<>())
                        .add(documentFacts);
            } else {
                unassignedDocuments.add(documentFacts);
            }
        }
        List<CibCollusionReviewData.BidderFacts> bidders = bidderDocuments.entrySet().stream()
                .map(entry -> new CibCollusionReviewData.BidderFacts(entry.getKey(), entry.getValue()))
                .toList();
        return new CibCollusionReviewData(bidders, unassignedDocuments);
    }

    private CibCollusionReviewData.DocumentFacts mergeDocumentFacts(
            String documentId, List<ExtractionOutput> documentOutputs) {
        List<ExtractionOutput> sortedOutputs = documentOutputs.stream()
                .sorted(java.util.Comparator.comparingInt(ExtractionOutput::batchNumber))
                .toList();
        String supplierName = sortedOutputs.stream()
                .map(ExtractionOutput::facts)
                .filter(Objects::nonNull)
                .map(CibBasicInfoFacts::supplierName)
                .filter(this::isKnownSupplier)
                .findFirst()
                .orElse("UNKNOWN");
        List<CibCollusionReviewData.BatchFacts> batches = sortedOutputs.stream()
                .map(output -> new CibCollusionReviewData.BatchFacts(output.batchNumber(), output.pageStart(),
                        output.pageEnd(), output.facts()))
                .toList();
        return new CibCollusionReviewData.DocumentFacts(documentId, supplierName, batches,
                mergeFacts(supplierName, sortedOutputs));
    }

    /** 合并列表事实而不删除 location，后续任一结论都可以继续回溯PDF页码。 */
    private CibBasicInfoFacts mergeFacts(String supplierName, List<ExtractionOutput> outputs) {
        List<CibBasicInfoFacts> facts = outputs.stream()
                .map(ExtractionOutput::facts)
                .filter(Objects::nonNull)
                .toList();
        return new CibBasicInfoFacts(
                supplierName,
                firstNonBlank(facts.stream().map(CibBasicInfoFacts::documentRole).toList()),
                mergeQuoteSummary(facts),
                flatten(facts, CibBasicInfoFacts::referencePrices),
                flatten(facts, CibBasicInfoFacts::associationFacts),
                flatten(facts, CibBasicInfoFacts::keyPriceItems),
                flatten(facts, CibBasicInfoFacts::documentLayoutFacts),
                flatten(facts, CibBasicInfoFacts::textSimilarityFacts),
                flatten(facts, CibBasicInfoFacts::legalRetrievalClues));
    }

    private CibBasicInfoFacts.QuoteSummary mergeQuoteSummary(List<CibBasicInfoFacts> facts) {
        List<CibBasicInfoFacts.QuoteSummary> summaries = facts.stream()
                .map(CibBasicInfoFacts::quoteSummary)
                .filter(Objects::nonNull)
                .toList();
        if (summaries.isEmpty()) {
            return null;
        }
        return new CibBasicInfoFacts.QuoteSummary(
                firstNonBlank(summaries.stream().map(CibBasicInfoFacts.QuoteSummary::firstQuoteAmount).toList()),
                firstNonBlank(summaries.stream().map(CibBasicInfoFacts.QuoteSummary::firstQuoteLocation).toList()),
                firstNonBlank(summaries.stream().map(CibBasicInfoFacts.QuoteSummary::firstQuoteExcerpt).toList()),
                firstNonBlank(summaries.stream().map(CibBasicInfoFacts.QuoteSummary::finalQuoteAmount).toList()),
                firstNonBlank(summaries.stream().map(CibBasicInfoFacts.QuoteSummary::finalQuoteLocation).toList()),
                firstNonBlank(summaries.stream().map(CibBasicInfoFacts.QuoteSummary::finalQuoteExcerpt).toList()));
    }

    private <T> List<T> flatten(List<CibBasicInfoFacts> facts,
            Function<CibBasicInfoFacts, List<T>> extractor) {
        return facts.stream()
                .map(extractor)
                .filter(Objects::nonNull)
                .flatMap(Collection::stream)
                .toList();
    }

    private String firstNonBlank(List<String> values) {
        return values.stream().filter(value -> value != null && !value.isBlank()).findFirst().orElse(null);
    }

    private boolean isKnownSupplier(String supplierName) {
        return supplierName != null && !supplierName.isBlank() && !"UNKNOWN".equalsIgnoreCase(supplierName);
    }

    /** 对归并后的事实进行五维围标串标审查，报告阶段不再重复判断风险。 */
    private CibCollusionReviewResult reviewCollusion(CibCollusionReviewData reviewData) {
        ReActAgent agent = ReActAgent.builder()
                .name("collusion-review")
                .sysPrompt(ImmBatchBasicInfoReviewPrompts.collusionReviewPrompt())
                .model(modelConfig.qwen37PlusDefaultReportModel())
                .build();
        Msg response = agent.call(Msg.builder().role(MsgRole.USER)
                .textContent("以下是按投标人和文件归并的结构化审查材料：\n" + toJson(reviewData))
                .build()).block();
        String text = requireContent(response, "围标串标专项审查");
        printTokenUsage("围标串标专项审查", "五维审查", response.getChatUsage());
        try {
            return QwenDocResponseParser.parse(text, CibCollusionReviewResult.class);
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("围标串标专项审查JSON解析失败：" + exception.getMessage(), exception);
        }
    }

    /** 报告仅整理已审查结论、归并事实和与结论对应的法律资料。 */
    private String generateReport(CibCollusionReviewData reviewData, CibCollusionReviewResult reviewResult) {
        String legalReferences = retrieveLegalReferences(reviewResult);
        ReActAgent agent = ReActAgent.builder()
                .name("basic-info-similarity-report")
                .sysPrompt(ImmBatchBasicInfoReviewPrompts.REPORT_PROMPT)
                .model(modelConfig.qwen37PlusDefaultReportModel())
                .build();
        Msg response = agent.call(buildReportRequest(reviewData, reviewResult, legalReferences)).block();
        String report = requireContent(response, "基础信息雷同比对报告");
        printTokenUsage("基础信息雷同比对", "最终汇总", response.getChatUsage());
        return report;
    }

    private void printTokenUsage(String stage, String label, ChatUsage usage) {
        if (usage == null) {
            System.out.println(stage + "Token使用量[" + label + "]：模型未返回usage");
            return;
        }
        System.out.println(stage + "输入Token[" + label + "]: " + usage.getInputTokens());
        System.out.println(stage + "输出Token[" + label + "]: " + usage.getOutputTokens());
    }

    private Msg buildReportRequest(CibCollusionReviewData reviewData,
            CibCollusionReviewResult reviewResult, String legalReferences) {
        List<String> suppliers = reviewData.bidders().stream()
                .map(CibCollusionReviewData.BidderFacts::supplierName)
                .toList();
        String content = """
                本次已识别的真实投标人名单（共%d名，顺序固定）：%s
                第一张和第三张表必须完整保留名单中的每一名投标人。第三张表的表头必须为“分项项目”加上述每名投标人的报价列及“报价分布特征”；
                即使某投标人缺少某一分项报价，也不得省略该投标人列，应填写“未发现明确报价”。

                以下是已归并的文件事实：
                %s

                以下是独立围标串标审查Agent的结论，风险判断必须以此为准：
                %s

                以下是法律文件知识库检索结果，仅用于报告中的法律条款依据：
                %s
                """.formatted(suppliers.size(), String.join("、", suppliers),
                toJson(reviewData), toJson(reviewResult), legalReferences);
        return Msg.builder().role(MsgRole.USER).textContent(content).build();
    }

    private String requireContent(Msg response, String stage) {
        if (response == null || response.getTextContent().isBlank()) {
            throw new IllegalStateException(stage + "未返回内容");
        }
        return response.getTextContent();
    }

    /** 仅对页面事实给出的法律线索检索知识库，不进行通用兜底检索。 */
    private String retrieveLegalReferences(CibCollusionReviewResult reviewResult) {
        Set<String> clues = new LinkedHashSet<>();
        if (reviewResult.legalRetrievalClues() != null) {
            for (CibBasicInfoFacts.LegalRetrievalClue clue : reviewResult.legalRetrievalClues()) {
                String text = Arrays.stream(new String[] {clue.riskCategory(), clue.observedIssue(),
                        clue.applicableScenario(), clue.keywords() == null ? null : String.join(" ", clue.keywords())})
                        .filter(value -> value != null && !value.isBlank())
                        .collect(java.util.stream.Collectors.joining(" "));
                if (!text.isBlank()) {
                    clues.add(text);
                }
            }
        }
        StringBuilder references = new StringBuilder();
        Set<String> referencedContents = new LinkedHashSet<>();
        for (String clue : clues) {
            System.out.println("法律知识库检索：检索线索：" + clue);
            List<Document> documents = reportKnowledge.retrieve(
                    "投标采购围标串标审查法律依据、认定规则与法律责任：" + clue,
                    RetrieveConfig.builder().limit(3).build()).block();
            if (documents != null && !documents.isEmpty()) {
                references.append('\n').append("[检索线索：").append(clue).append("]\n");
                for (Document document : documents) {
                    String content = document.getMetadata().getContentText();
                    if (content != null && referencedContents.add(content)) {
                        references.append("[法律资料]\n").append(content).append('\n');
                    }
                }
            }
        }
        return references.isEmpty() ? "法律文件知识库未检索到相关条款。" : references.toString();
    }

    private void validateUrls(List<String> urls) {
        if (urls == null || urls.isEmpty()
                || urls.stream().anyMatch(url -> url == null || url.isBlank())) {
            throw new IllegalArgumentException("至少需要传入一个有效PDF URL");
        }
    }

    private String toJson(Object value) {
        try {
            return OBJECT_MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("结构化事实序列化失败", exception);
        }
    }

    private record DocumentMaterial(
            String documentId,
            List<ImmImagePage> pages) {
    }

    private record ExtractionTask(
            DocumentMaterial material,
            int batchNumber,
            List<ImmImagePage> pages) {
    }

    private record StreamResult(String text, ChatUsage usage) {
    }

    private record ExtractionOutput(
            String documentId,
            int batchNumber,
            Integer pageStart,
            Integer pageEnd,
            CibBasicInfoFacts facts) {
    }

}
