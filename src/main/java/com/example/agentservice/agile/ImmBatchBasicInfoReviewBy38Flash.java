package com.example.agentservice.agile;

import com.example.agentservice.AgentServiceApplication;
import com.example.agentservice.config.AgentServiceConfig;
import com.example.agentservice.config.ModelConfig;
import com.example.agentservice.entity.CibBasicInfoFacts;
import com.example.agentservice.entity.ImmImagePage;
import com.example.agentservice.prompts.ImmBatchBasicInfoReviewPrompts;
import com.example.agentservice.service.ImmService;
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
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

/** IMM 正文与页面图片分批抽取基础信息，再由 qwen3.8-flash 执行纯文本雷同比对。 */
public class ImmBatchBasicInfoReviewBy38Flash {

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
    private final ImmService immService;
    private final ModelConfig modelConfig;
    private final Knowledge reportKnowledge;

    public ImmBatchBasicInfoReviewBy38Flash(
            PdfUtils pdfUtils, ImmService immService, ModelConfig modelConfig) {
        this.pdfUtils = pdfUtils;
        this.immService = immService;
        this.modelConfig = modelConfig;
        this.reportKnowledge = createReportKnowledge();
    }

    public static void main(String[] args) {
        SpringApplicationBuilder application = new SpringApplicationBuilder(AgentServiceApplication.class)
                .web(WebApplicationType.NONE);
        try (ConfigurableApplicationContext context = application.run()) {
            ImmBatchBasicInfoReviewBy38Flash review = new ImmBatchBasicInfoReviewBy38Flash(
                    context.getBean(PdfUtils.class),
                    context.getBean(ImmService.class),
                    context.getBean(ModelConfig.class));
            review.execute(args.length == 0 ? PDF_URLS : Arrays.asList(args));
        } catch (Exception exception) {
            throw new IllegalStateException("IMM分批基础信息雷同审查失败", exception);
        }
    }

    public void execute(List<String> pdfUrls) throws Exception {
        validateUrls(pdfUrls);
        long startNanos = System.nanoTime();
        System.out.println("阶段1/3：并发提取正文并将PDF转为页面图片...");
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

        System.out.println("阶段3/3：使用 qwen3.8-flash 对全部供应商事实执行纯文本比对...");
        System.out.println(compareFacts(outputs));
        System.out.printf("总耗时: %.3f 秒%n", elapsedSeconds(startNanos));
    }

    private List<DocumentMaterial> loadMaterials(List<String> pdfUrls) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(pdfUrls.size() * 2);
        try {
            List<CompletableFuture<DocumentMaterial>> futures = pdfUrls.stream().map(url -> {
                CompletableFuture<String> text = CompletableFuture.supplyAsync(() -> extractText(url), executor);
                CompletableFuture<List<ImmImagePage>> pages = CompletableFuture.supplyAsync(
                        () -> convertPages(url), executor);
                return text.thenCombine(pages, (documentText, imagePages) ->
                        new DocumentMaterial(url, documentName(url, imagePages), documentText, imagePages));
            }).toList();
            List<DocumentMaterial> result = new ArrayList<>();
            for (CompletableFuture<DocumentMaterial> future : futures) {
                result.add(await(future));
            }
            return result;
        } finally {
            executor.shutdown();
        }
    }

    private String extractText(String url) {
        try {
            String documentText = immService.extractDocumentText(url);
            if (documentText == null || documentText.isBlank()) {
                System.out.println("IMM未提取到文档正文，按扫描件处理并以页面图片为准："
                        + documentNameFromUrl(url));
                return "[IMM未提取到正文，该文件可能是扫描件。此提示不是文件内容，请完全依据页面图片抽取事实。]";
            }
            return documentText;
        } catch (Exception exception) {
            throw new CompletionException("文档正文提取失败: " + url, exception);
        }
    }

    private String documentNameFromUrl(String url) {
        String path = url.substring(0, url.indexOf('?') >= 0 ? url.indexOf('?') : url.length());
        return path.substring(path.lastIndexOf('/') + 1);
    }

    private List<ImmImagePage> convertPages(String url) {
        try {
            return pdfUtils.pdfToImage(url);
        } catch (Exception exception) {
            throw new CompletionException("PDF转图片失败: " + url, exception);
        }
    }

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

    private List<ExtractionOutput> extractBatch(ExtractionTask task) {
        String label = extractionLabel(task);
        try {
            ReActAgent agent = ReActAgent.builder()
                    .name("basic-info-extractor-" + task.batchNumber())
                    .sysPrompt(ImmBatchBasicInfoReviewPrompts.EXTRACTION_PROMPT)
                    .model(modelConfig.qwen38FlashStreamingReviewModel())
                    .build();
            StreamResult result = stream(agent, buildExtractionRequest(task), label);
            if (result.usage() != null) {
                System.out.println("基础信息抽取输入Token[" + label + "]: "
                        + result.usage().getInputTokens());
            }
            try {
                CibBasicInfoFacts facts = QwenDocResponseParser.parse(
                        result.text(), CibBasicInfoFacts.class);
                return List.of(new ExtractionOutput(label, facts, result.text()));
            } catch (IllegalArgumentException parseException) {
                System.err.println("基础信息JSON解析失败[" + label + "]："
                        + parseException.getMessage());
                System.err.println("----- 原始响应开始[" + label + "] -----");
                System.err.println(result.text());
                System.err.println("----- 原始响应结束[" + label + "] -----");
                return List.of(new ExtractionOutput(label, null, result.text()));
            }
        } catch (Exception exception) {
            if (isModelTimeout(exception) && task.pages().size() > 1) {
                int middle = task.pages().size() / 2;
                System.err.println("基础信息抽取超时[" + label + "]，自动拆分为"
                        + middle + "页和" + (task.pages().size() - middle) + "页重试");
                List<ExtractionOutput> outputs = new ArrayList<>();
                outputs.addAll(extractBatch(new ExtractionTask(task.material(), task.batchNumber(),
                        task.pages().subList(0, middle))));
                outputs.addAll(extractBatch(new ExtractionTask(task.material(), task.batchNumber(),
                        task.pages().subList(middle, task.pages().size()))));
                return outputs;
            }
            System.err.println("基础信息抽取失败[" + label + "]：" + exception.getMessage());
            return List.of();
        }
    }

    private String extractionLabel(ExtractionTask task) {
        if (task.pages().isEmpty()) {
            return task.material().documentName() + "#批次" + task.batchNumber() + "#无图片";
        }
        return task.material().documentName() + "#批次" + task.batchNumber() + "#第"
                + task.pages().get(0).page() + "-"
                + task.pages().get(task.pages().size() - 1).page() + "页";
    }

    private boolean isModelTimeout(Throwable throwable) {
        for (Throwable current = throwable; current != null; current = current.getCause()) {
            String message = current.getMessage();
            if (message != null && (message.contains("Model request timeout")
                    || message.contains("PT5M"))) {
                return true;
            }
        }
        return false;
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
                文件名：%s
                当前图片范围：%s
                当前为该文件第%d个图片批次。图片顺序与PDF页码一致。
                审查基准日期：%s（早于或等于该日期的落款日期不属于未来日期）。

                文件正文：
                %s

                请综合正文和当前批次页面图片抽取基础信息事实。
                """.formatted(task.material().documentName(), pageRange,
                task.batchNumber(), reviewDate(), task.material().documentText())).build());
        return Msg.builder().role(MsgRole.USER).content(content).build();
    }

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

    private String compareFacts(List<ExtractionOutput> outputs) {
        String legalReferences = retrieveLegalReferences(outputs);
        ReActAgent agent = ReActAgent.builder()
                .name("basic-info-similarity-report")
                .sysPrompt(ImmBatchBasicInfoReviewPrompts.REPORT_PROMPT)
                .model(modelConfig.qwen38FlashDefaultReportModel())
                .build();
        Msg response = agent.call(Msg.builder()
                .role(MsgRole.USER)
                .textContent("审查基准日期：" + reviewDate()
                        + "。早于或等于该日期的落款日期不得认定为未来日期。\n\n"
                        + "以下是所有文件批次的事实材料。JSON解析失败的批次保留原始响应：\n"
                        + comparisonMaterials(outputs)
                        + "\n\n以下是法律文件知识库检索结果，仅用于报告中的法律条款依据：\n"
                        + legalReferences)
                .build()).block();
        if (response == null || response.getTextContent().isBlank()) {
            throw new IllegalStateException("基础信息雷同比对未返回报告");
        }
        return response.getTextContent();
    }

    private String retrieveLegalReferences(List<ExtractionOutput> outputs) {
        String query = buildLegalRetrievalQuery(outputs);
        System.out.println("法律知识库检索条件：" + query);
        List<Document> documents = reportKnowledge.retrieve(
                query,
                RetrieveConfig.builder()
                        .limit(10)
                        .scoreThreshold(0D)
                        .build())
                .block();
        if (documents == null || documents.isEmpty()) {
            return "法律文件知识库未检索到相关条款。";
        }
        StringBuilder references = new StringBuilder();
        for (int index = 0; index < documents.size(); index++) {
            Document document = documents.get(index);
            references.append("\n[法律资料").append(index + 1).append("]\n")
                    .append(document.getMetadata().getContentText()).append('\n');
        }
        return references.toString();
    }

    private String buildLegalRetrievalQuery(List<ExtractionOutput> outputs) {
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
        String prefix = "投标采购围标串标审查法律依据、认定规则与法律责任：";
        if (clues.isEmpty()) {
            return prefix + "基础信息雷同、报价异常、串通投标";
        }
        StringBuilder query = new StringBuilder(prefix);
        for (String clue : clues) {
            if (query.length() + clue.length() + 1 > 500) {
                break;
            }
            query.append(' ').append(clue);
        }
        return query.toString();
    }

    private List<String> nonBlankParts(String... values) {
        return Arrays.stream(values)
                .filter(value -> value != null && !value.isBlank())
                .toList();
    }

    private LocalDate reviewDate() {
        return LocalDate.now(ZoneId.of("Asia/Shanghai"));
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

    private String comparisonMaterials(List<ExtractionOutput> outputs) {
        StringBuilder materials = new StringBuilder();
        for (ExtractionOutput output : outputs) {
            materials.append("\n\n### ").append(output.label()).append('\n');
            if (output.facts() != null) {
                materials.append(toJson(output.facts()));
            } else {
                materials.append(output.rawText());
            }
        }
        return materials.toString();
    }

    private String documentName(String url, List<ImmImagePage> pages) {
        if (!pages.isEmpty() && pages.get(0).document() != null
                && !pages.get(0).document().isBlank()) {
            return pages.get(0).document();
        }
        return documentNameFromUrl(url);
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
            String sourceUrl,
            String documentName,
            String documentText,
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
            String label,
            CibBasicInfoFacts facts,
            String rawText) {
    }
}
