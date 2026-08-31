package com.example.agentservice.agile;

import com.example.agentservice.AgentServiceApplication;
import com.example.agentservice.config.AgentServiceConfig;
import com.example.agentservice.config.ModelConfig;
import com.example.agentservice.entity.CibBasicInfoFacts;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;

/** IMM 页面图片分批抽取基础信息，再由 qwen3.7-plus 执行纯文本雷同比对。 */
public class ImmBatchBasicInfoReviewBy37Plus {

    private static final int IMAGE_BATCH_SIZE = 180;
    private static final int EXTRACTION_THREAD_COUNT = 6;
    private static final String BAILIAN_WORKSPACE_ID = "llm-2c213fyvomyxzuc9";
    private static final String BAILIAN_KNOWLEDGE_INDEX_ID = "94kvimrfoy";
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final List<String> PDF_URLS = List.of(
            "http://oss1.easyjcx.com/co-order/2026/08/31/0e0abeab14294f35afc315230a16cee58458345289706990841.pdf",
            "http://oss1.easyjcx.com/co-order/2026/08/31/ac7fa88168994d84a379f6cc91223d473481530127675841490.pdf",
            "http://oss1.easyjcx.com/co-order/2026/08/31/6687c758aa79464cbe8920e1090045c71760585613030349238.pdf",
            "http://oss1.easyjcx.com/co-order/2026/08/31/a8974b5bc67f4abd86102b1fd6b9fdad691476349652533877.pdf",
            "http://oss1.easyjcx.com/co-order/2026/08/31/7c7dd732604144e6bf1876911b07180e7242434849769595335.pdf",
            "http://oss1.easyjcx.com/co-order/2026/08/31/9827b7322c344a81ab6ff2ec7cce25675852779963543401812.pdf");

    private final PdfUtils pdfUtils;
    private final ModelConfig modelConfig;
    private final Knowledge reportKnowledge;

    public ImmBatchBasicInfoReviewBy37Plus(PdfUtils pdfUtils, ModelConfig modelConfig) {
        this.pdfUtils = pdfUtils;
        this.modelConfig = modelConfig;
        this.reportKnowledge = createReportKnowledge();
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

    /** 按“转图片、分批抽取、跨文件汇总”三个阶段执行基础信息审查。 */
    public void execute(List<String> pdfUrls) throws Exception {
        validateUrls(pdfUrls);
        long startNanos = System.nanoTime();
        System.out.println("阶段1/3：将PDF转为页面图片...");
        List<DocumentMaterial> materials = loadMaterials(pdfUrls);
        int imageCount = materials.stream().mapToInt(item -> item.pages().size()).sum();
        System.out.println("文件数=" + materials.size() + "，总图片数=" + imageCount);

        System.out.println("阶段2/3：按每批最多" + IMAGE_BATCH_SIZE + "张图片抽取结构化事实...");
        List<ExtractionOutput> outputs = extractFacts(materials);
        long parsedCount = outputs.stream().filter(output -> output.facts() != null).count();
        System.out.println("完成抽取批次数=" + outputs.size() + "，成功解析JSON=" + parsedCount);
        if (outputs.isEmpty()) {
            throw new IllegalStateException("所有基础信息抽取批次均未返回内容，终止报告生成");
        }

        System.out.println("阶段3/3：使用 qwen3.7-plus 对全部供应商事实执行纯文本比对...");
        System.out.println(compareFacts(outputs));
        System.out.printf("总耗时: %.3f 秒%n", elapsedSeconds(startNanos));
    }

    /** 为每个输入文件分配稳定 documentId，后续模型不得改变该文件归属。 */
    private List<DocumentMaterial> loadMaterials(List<String> pdfUrls) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(Math.max(1, pdfUrls.size()));
        try {
            List<CompletableFuture<DocumentMaterial>> futures = IntStream.range(0, pdfUrls.size())
                    .mapToObj(index -> CompletableFuture.supplyAsync(() -> {
                        String url = pdfUrls.get(index);
                        List<ImmImagePage> pages = convertPages(url);
                        return new DocumentMaterial("文件" + (index + 1), pages);
                    }, executor))
                    .toList();
            List<DocumentMaterial> result = new ArrayList<>();
            for (CompletableFuture<DocumentMaterial> future : futures) {
                result.add(await(future));
            }
            return result;
        } finally {
            executor.shutdown();
        }
    }

    private List<ImmImagePage> convertPages(String url) {
        try {
            return pdfUtils.pdfToImage(url);
        } catch (Exception exception) {
            throw new CompletionException("PDF转图片失败: " + url, exception);
        }
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
                    .flatMap(List::stream)
                    .toList();
        } finally {
            executor.shutdown();
        }
    }

    /** 单批图片仅抽取页面事实；解析失败时保留原始响应供最终阶段参考。 */
    private List<ExtractionOutput> extractBatch(ExtractionTask task) {
        String label = extractionLabel(task);
        try {
            ReActAgent agent = ReActAgent.builder()
                    .name("basic-info-extractor-" + task.batchNumber())
                    .sysPrompt(ImmBatchBasicInfoReviewPrompts.extractionPrompt())
                    .model(modelConfig.qwen37PlusStreamingReviewModel())
                    .build();
            StreamResult result = stream(agent, buildExtractionRequest(task), label);
            if (result.usage() != null) {
                System.out.println("基础信息抽取输入Token[" + label + "]: "
                        + result.usage().getInputTokens());
            }
            try {
                CibBasicInfoFacts facts = QwenDocResponseParser.parse(
                        result.text(), CibBasicInfoFacts.class);
                return List.of(extractionOutput(task, facts, result.text()));
            } catch (IllegalArgumentException parseException) {
                System.err.println("基础信息JSON解析失败[" + label + "]："
                        + parseException.getMessage());
                System.err.println("原始响应字符数[" + label + "]：" + result.text().length()
                        + "，末尾片段：" + responseTail(result.text()));
                System.err.println("----- 原始响应开始[" + label + "] -----");
                System.err.println(result.text());
                System.err.println("----- 原始响应结束[" + label + "] -----");
                return List.of(extractionOutput(task, null, result.text()));
            }
        } catch (Exception exception) {
            System.err.println("基础信息抽取失败[" + label + "]：" + exception.getMessage());
            return List.of();
        }
    }

    private String extractionLabel(ExtractionTask task) {
        if (task.pages().isEmpty()) {
            return task.material().documentId() + "#批次" + task.batchNumber() + "#无图片";
        }
        return task.material().documentId() + "#批次" + task.batchNumber() + "#第"
                + task.pages().get(0).page() + "-"
                + task.pages().get(task.pages().size() - 1).page() + "页";
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

    /** 将带不可变来源元数据的批次事实交由 3.7 Plus 生成最终报告。 */
    private String compareFacts(List<ExtractionOutput> outputs) {
        String legalReferences = retrieveLegalReferences(outputs);
        ReActAgent agent = ReActAgent.builder()
                .name("basic-info-similarity-report")
                .sysPrompt(ImmBatchBasicInfoReviewPrompts.REPORT_PROMPT)
                .model(modelConfig.qwen37PlusDefaultReportModel())
                .build();
        Msg response = agent.call(buildComparisonRequest(outputs, legalReferences)).block();
        String report = requireReportContent(response);
        if (response.getChatUsage() != null) {
            System.out.println("基础信息雷同比对输入Token: "
                    + response.getChatUsage().getInputTokens());
        }
        return report;
    }

    private Msg buildComparisonRequest(List<ExtractionOutput> outputs, String legalReferences) {
        String content = """
                以下是所有文件批次的事实材料。JSON解析失败的批次保留原始响应：
                %s

                以下是法律文件知识库检索结果，仅用于报告中的法律条款依据：
                %s
                """.formatted(comparisonMaterials(outputs), legalReferences);
        return Msg.builder().role(MsgRole.USER).textContent(content).build();
    }

    private String requireReportContent(Msg response) {
        if (response == null || response.getTextContent().isBlank()) {
            throw new IllegalStateException("基础信息雷同比对未返回报告");
        }
        return response.getTextContent();
    }

    /** 仅对页面事实给出的法律线索检索知识库，不进行通用兜底检索。 */
    private String retrieveLegalReferences(List<ExtractionOutput> outputs) {
        List<String> clues = buildLegalRetrievalClues(outputs);
        StringBuilder references = new StringBuilder();
        Set<String> referencedContents = new LinkedHashSet<>();
        for (String clue : clues) {
            String query = "投标采购围标串标审查法律依据、认定规则与法律责任：" + clue;
            appendLegalReferences(references, referencedContents, "检索线索：" + clue, query);
        }
        return references.isEmpty() ? "法律文件知识库未检索到相关条款。"
                : references.toString();
    }

    private void appendLegalReferences(
            StringBuilder references, Set<String> referencedContents, String label, String query) {
        System.out.println("法律知识库检索：" + label);
        List<Document> documents = reportKnowledge.retrieve(
                query,
                RetrieveConfig.builder()
                        .limit(3)
                        .build())
                .block();
        if (documents == null || documents.isEmpty()) {
            return;
        }
        references.append('\n').append('[').append(label).append("]\n");
        for (Document document : documents) {
            String content = document.getMetadata().getContentText();
            if (content != null && referencedContents.add(content)) {
                references.append("[法律资料]\n").append(content).append('\n');
            }
        }
    }

    private List<String> buildLegalRetrievalClues(List<ExtractionOutput> outputs) {
        Set<String> clues = new LinkedHashSet<>();
        for (ExtractionOutput output : outputs) {
            if (output.facts() == null || output.facts().legalRetrievalClues() == null) {
                continue;
            }
            for (CibBasicInfoFacts.LegalRetrievalClue clue : output.facts().legalRetrievalClues()) {
                String text = String.join(" ", nonBlankParts(
                        clue.riskCategory(), clue.observedIssue(), clue.applicableScenario(),
                        clue.keywords() == null ? null : String.join(" ", clue.keywords())));
                if (!text.isBlank()) {
                    clues.add(text);
                }
            }
        }
        return new ArrayList<>(clues);
    }

    private List<String> nonBlankParts(String... values) {
        return Arrays.stream(values)
                .filter(value -> value != null && !value.isBlank())
                .toList();
    }

    private Knowledge createReportKnowledge() {
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
        return BailianKnowledge.builder()
                .config(config)
                .indexId(BAILIAN_KNOWLEDGE_INDEX_ID)
                .build();
    }

    /** 将文件、批次和页码与抽取事实一并序列化，防止最终模型发生归属漂移。 */
    private String comparisonMaterials(List<ExtractionOutput> outputs) {
        StringBuilder materials = new StringBuilder();
        for (ExtractionOutput output : outputs) {
            materials.append('\n').append(toJson(new ComparisonMaterial(
                    output.documentId(),
                    output.batchNumber(),
                    output.pageStart(),
                    output.pageEnd(),
                    output.facts(),
                    output.facts() == null ? output.rawText() : null)));
        }
        return materials.toString();
    }

    /** 从代码侧任务生成来源元数据，不采信模型返回的文件名或供应商名。 */
    private ExtractionOutput extractionOutput(
            ExtractionTask task, CibBasicInfoFacts facts, String rawText) {
        Integer pageStart = task.pages().isEmpty() ? null : task.pages().get(0).page();
        Integer pageEnd = task.pages().isEmpty() ? null
                : task.pages().get(task.pages().size() - 1).page();
        return new ExtractionOutput(task.material().documentId(), task.batchNumber(),
                pageStart, pageEnd, facts, rawText);
    }

    private void validateUrls(List<String> urls) {
        if (urls == null || urls.isEmpty()
                || urls.stream().anyMatch(url -> url == null || url.isBlank())) {
            throw new IllegalArgumentException("至少需要传入一个有效PDF URL");
        }
    }

    private <T> T await(CompletableFuture<T> future) throws Exception {
        try {
            return future.join();
        } catch (CompletionException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof Exception typedCause) {
                throw typedCause;
            }
            throw new IllegalStateException("异步任务失败", cause);
        }
    }

    private String toJson(Object value) {
        try {
            return OBJECT_MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("结构化事实序列化失败", exception);
        }
    }

    private double elapsedSeconds(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000_000D;
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
            CibBasicInfoFacts facts,
            String rawText) {
    }

    private String responseTail(String response) {
        int start = Math.max(0, response.length() - 200);
        return response.substring(start).replaceAll("[\\r\\n]+", " ");
    }

    /** 汇总模型的批次输入，来源映射仅以这里的流程元数据为准。 */
    private record ComparisonMaterial(
            String documentId,
            int batchNumber,
            Integer pageStart,
            Integer pageEnd,
            CibBasicInfoFacts facts,
            String rawText) {
    }
}
