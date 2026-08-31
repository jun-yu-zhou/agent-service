package com.example.agentservice.agile;

import com.example.agentservice.AgentServiceApplication;
import com.example.agentservice.config.ModelConfig;
import com.example.agentservice.entity.CibDimensionResult;
import com.example.agentservice.entity.ImmImagePage;
import com.example.agentservice.prompts.CibReviewPrompts;
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
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** 使用 AgentScope 流式调用 qwen3.8-flash，对 IMM 全量图片执行围标串标审查。 */
public class ImmConcurrentStreamReviewBy38Flash {

    private static final int REVIEW_THREAD_COUNT = 6;
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

    public ImmConcurrentStreamReviewBy38Flash(PdfUtils pdfUtils, ModelConfig modelConfig) {
        this.pdfUtils = pdfUtils;
        this.modelConfig = modelConfig;
    }

    public static void main(String[] args) {
        SpringApplicationBuilder application = new SpringApplicationBuilder(AgentServiceApplication.class)
                .web(WebApplicationType.NONE);
        try (ConfigurableApplicationContext context = application.run()) {
            ImmConcurrentStreamReviewBy38Flash review = new ImmConcurrentStreamReviewBy38Flash(
                    context.getBean(PdfUtils.class), context.getBean(ModelConfig.class));
            review.execute(args.length == 0 ? PDF_URLS : Arrays.asList(args));
        } catch (Exception exception) {
            throw new IllegalStateException("AgentScope全量图片流式审查流程执行失败", exception);
        }
    }

    public void execute(List<String> pdfUrls) throws Exception {
        validatePdfUrls(pdfUrls);
        long startNanos = System.nanoTime();
        System.out.println("阶段1/3：按文件并发执行 IMM PDF 转图片，并发数=" + pdfUrls.size());
        List<ImmImagePage> imagePages = convertPdfFilesConcurrently(pdfUrls);
        System.out.println("IMM 转图完成，总图片数=" + imagePages.size());

        System.out.println("阶段2/3：开始并发执行6个 qwen3.8-flash 全量图片流式审查...");
        List<CibDimensionResult> results = reviewDimensions(imagePages);

        System.out.println("阶段3/3：开始使用 qwen3.8-flash 汇总报告...");
        System.out.println(generateReport(results));
        System.out.printf("总耗时: %.3f 秒%n", elapsedSeconds(startNanos));
    }

    private List<ImmImagePage> convertPdfFilesConcurrently(List<String> pdfUrls) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(pdfUrls.size());
        try {
            List<CompletableFuture<List<ImmImagePage>>> futures = pdfUrls.stream()
                    .map(url -> CompletableFuture.supplyAsync(() -> convertPdf(url), executor))
                    .toList();
            List<ImmImagePage> pages = new ArrayList<>();
            for (CompletableFuture<List<ImmImagePage>> future : futures) {
                pages.addAll(await(future));
            }
            return pages;
        } finally {
            executor.shutdown();
        }
    }

    private List<ImmImagePage> convertPdf(String pdfUrl) {
        try {
            return pdfUtils.pdfToImage(pdfUrl);
        } catch (Exception exception) {
            throw new CompletionException("PDF转图片失败: " + pdfUrl, exception);
        }
    }

    private List<CibDimensionResult> reviewDimensions(List<ImmImagePage> imagePages) {
        ExecutorService executor = Executors.newFixedThreadPool(REVIEW_THREAD_COUNT);
        try {
            List<CompletableFuture<CibDimensionResult>> futures = CibReviewPrompts.DIMENSIONS.stream()
                    .map(dimension -> CompletableFuture.supplyAsync(
                            () -> reviewDimension(dimension, imagePages), executor))
                    .toList();
            return futures.stream().map(CompletableFuture::join).toList();
        } finally {
            executor.shutdown();
        }
    }

    private CibDimensionResult reviewDimension(
            CibReviewPrompts.Dimension dimension, List<ImmImagePage> imagePages) {
        long startNanos = System.nanoTime();
        System.out.println("开始流式专项审查[" + dimension.code() + "]，图片数=" + imagePages.size());
        try {
            ReActAgent agent = ReActAgent.builder()
                    .name("cib-stream-" + dimension.code().toLowerCase())
                    .sysPrompt(CibReviewPrompts.promptFor(dimension))
                    .model(modelConfig.qwen38FlashStreamingReviewModel())
                    .build();
            StreamResult streamResult = streamAgent(
                    agent, buildImageRequest(dimension, imagePages), dimension);
            CibDimensionResult result = QwenDocResponseParser.parse(
                    streamResult.text(), CibDimensionResult.class);
            validateDimension(dimension, result);
            result.setSuccess(true);
            result.setErrorMessage(null);
            printInputTokens(dimension.code(), streamResult.usage());
            System.out.printf("完成流式专项审查[%s]，耗时=%.3f 秒%n",
                    dimension.code(), elapsedSeconds(startNanos));
            return result;
        } catch (Exception exception) {
            System.err.println("流式专项审查失败[" + dimension.name() + "]：" + exception.getMessage());
            return failedResult(dimension, exception);
        }
    }

    private StreamResult streamAgent(
            ReActAgent agent, Msg request, CibReviewPrompts.Dimension dimension) {
        StringBuilder text = new StringBuilder();
        AtomicReference<ChatUsage> usage = new AtomicReference<>();
        AtomicBoolean firstChunk = new AtomicBoolean(true);
        long requestNanos = System.nanoTime();
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
                if (firstChunk.compareAndSet(true, false)) {
                    System.out.printf("收到首个流式响应[%s]，首包耗时=%.3f 秒%n",
                            dimension.code(), elapsedSeconds(requestNanos));
                }
                text.append(chunk);
            }
            if (message.getChatUsage() != null) {
                usage.set(message.getChatUsage());
            }
        }).blockLast();

        if (text.isEmpty()) {
            throw new IllegalStateException("AgentScope流式调用未返回专项内容");
        }
        return new StreamResult(text.toString(), usage.get());
    }

    private Msg buildImageRequest(
            CibReviewPrompts.Dimension dimension, List<ImmImagePage> imagePages) {
        List<ContentBlock> content = new ArrayList<>(imagePages.size() + 1);
        for (ImmImagePage imagePage : imagePages) {
            content.add(ImageBlock.builder()
                    .source(URLSource.builder().url(imagePage.url()).build())
                    .build());
        }
        content.add(TextBlock.builder()
                .text("图片顺序与证据来源映射：\n" + imageMapping(imagePages)
                        + "\n请完整查看以上全部PDF页面，只执行“" + dimension.name() + "”专项审查。"
                        + "输出必须符合系统提示词中的JSON Schema；document使用原始PDF文件名，"
                        + "location使用图片对应的PDF页码或可见章节。")
                .build());
        return Msg.builder().role(MsgRole.USER).content(content).build();
    }

    private String generateReport(List<CibDimensionResult> results) {
        ReActAgent reportAgent = ReActAgent.builder()
                .name("cib-qwen38-flash-report")
                .sysPrompt(CibReviewPrompts.REPORT_PROMPT)
                .model(modelConfig.qwen38FlashDefaultReportModel())
                .build();
        Msg response = reportAgent.call(Msg.builder()
                .role(MsgRole.USER)
                .textContent("以下是六个专项Agent返回的JSON结果，请严格汇总：\n" + toJson(results))
                .build()).block();
        if (response == null || response.getTextContent().isBlank()) {
            throw new IllegalStateException("qwen3.8-flash报告Agent未返回内容");
        }
        return response.getTextContent();
    }

    private String imageMapping(List<ImmImagePage> imagePages) {
        StringBuilder mapping = new StringBuilder();
        int start = 1;
        int index = 0;
        while (index < imagePages.size()) {
            String document = imagePages.get(index).document();
            int end = index;
            while (end + 1 < imagePages.size()
                    && document.equals(imagePages.get(end + 1).document())) {
                end++;
            }
            mapping.append("第").append(start).append("-").append(end + 1)
                    .append("张图片属于文件《").append(document).append("》；PDF页码范围为第")
                    .append(imagePages.get(index).page()).append("页至第")
                    .append(imagePages.get(end).page()).append("页。\n");
            start = end + 2;
            index = end + 1;
        }
        return mapping.toString();
    }

    private CibDimensionResult failedResult(
            CibReviewPrompts.Dimension dimension, Exception exception) {
        CibDimensionResult failed = new CibDimensionResult();
        failed.setDimension(dimension.code());
        failed.setSummary("专项审查失败，未获得有效结果");
        failed.setRiskLevel("UNKNOWN");
        failed.setSuccess(false);
        failed.setErrorMessage(exception.getMessage());
        failed.setRecommendations(List.of("重新执行" + dimension.name() + "并人工复核"));
        return failed;
    }

    private void validateDimension(
            CibReviewPrompts.Dimension dimension, CibDimensionResult result) {
        if (result == null || !dimension.code().equals(result.getDimension())) {
            throw new IllegalArgumentException("专项结果dimension不匹配: "
                    + (result == null ? null : result.getDimension()));
        }
    }

    private void validatePdfUrls(List<String> pdfUrls) {
        if (pdfUrls == null || pdfUrls.isEmpty()) {
            throw new IllegalArgumentException("至少需要传入一个 PDF OSS URL");
        }
        if (pdfUrls.stream().anyMatch(url -> url == null || url.isBlank())) {
            throw new IllegalArgumentException("PDF OSS URL不能为空");
        }
    }

    private void printInputTokens(String dimension, ChatUsage usage) {
        if (usage != null) {
            System.out.println("图片审查输入Token[" + dimension + "]: " + usage.getInputTokens());
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
            throw new IllegalStateException("IMM并发转图失败", cause);
        }
    }

    private String toJson(Object value) {
        try {
            return OBJECT_MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("序列化审查结果失败", exception);
        }
    }

    private double elapsedSeconds(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000_000D;
    }

    private record StreamResult(String text, ChatUsage usage) {
    }
}
