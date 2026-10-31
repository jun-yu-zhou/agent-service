package com.example.agentservice.agile;

import com.alibaba.dashscope.aigc.multimodalconversation.MultiModalConversation;
import com.alibaba.dashscope.aigc.multimodalconversation.MultiModalConversationParam;
import com.alibaba.dashscope.aigc.multimodalconversation.MultiModalConversationResult;
import com.alibaba.dashscope.common.MultiModalMessage;
import com.alibaba.dashscope.common.Role;
import com.example.agentservice.AgentServiceApplication;
import com.example.agentservice.config.AgentServiceConfig;
import com.example.agentservice.config.ModelConfig;
import com.example.agentservice.entity.CibDimensionResult;
import com.example.agentservice.entity.ImmImagePage;
import com.example.agentservice.prompts.CibReviewPrompts;
import com.example.agentservice.service.ImmService;
import com.example.agentservice.utils.PdfUtils;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** 同时基于 IMM 图片和文档正文执行围标串标审查。 */
public class ImmBeforeTextAndImgReview {

    private static final int REVIEW_THREAD_COUNT = 6;
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final List<String> PDF_URLS = List.of(
            "https://javawebemp.oss-cn-beijing.aliyuncs.com/Scan-%E5%AE%9C%E6%98%8C%E5%87%A0%E4%BD%95%E6%B5%8B%E7%BB%98%E7%A7%91%E6%8A%80%E6%9C%89%E9%99%90%E5%85%AC%E5%8F%B88960772218806061282.pdf",
            "https://javawebemp.oss-cn-beijing.aliyuncs.com/2%E8%BD%AE%E6%8A%A5%E4%BB%B7--%E4%B8%89%E5%B3%A1%E5%A4%A7%E5%AD%A6%E6%96%B0%E5%BB%BA%E5%AD%A6%E7%94%9F%E5%85%AC%E5%AF%93%E9%A1%B9%E7%9B%AE%E5%A4%9A%E6%B5%8B%E5%90%88%E4%B8%80-%E6%B9%96%E5%8C%97%E6%8D%B7%E5%B8%86%E7%A7%91%E6%8A%80%E6%9C%89%E9%99%90%E5%85%AC%E5%8F%B83512886208567055417.pdf",
            "https://javawebemp.oss-cn-beijing.aliyuncs.com/%E4%B8%89%E5%B3%A1%E5%A4%A7%E5%AD%A6%E6%96%B0%E5%BB%BA%E5%AD%A6%E7%94%9F%E5%85%AC%E5%AF%93%E9%A1%B9%E7%9B%AE%E5%A4%9A%E6%B5%8B%E5%90%88%E4%B8%80%E7%AB%9E%E4%BA%89%E6%80%A7%E7%A3%8B%E5%95%86%E5%93%8D%E5%BA%94%E6%96%87%E4%BB%B6%EF%BC%88%E6%8A%95%E6%A0%87%E6%96%87%E4%BB%B6%EF%BC%89--%E6%B9%96%E5%8C%97%E6%8D%B7%E5%B8%86%E7%A7%91%E6%8A%80%E6%9C%89%E9%99%90%E5%85%AC%E5%8F%B8-%E6%B9%96%E5%8C%97%E6%8D%B7%E5%B8%86%E7%A7%91%E6%8A%80%E6%9C%89%E9%99%90%E5%85%AC%E5%8F%B85201011215828763487.pdf",
            "https://javawebemp.oss-cn-beijing.aliyuncs.com/%E4%BA%8C%E8%BD%AE%E6%8A%A5%E4%BB%B7%281%29-%E5%AE%9C%E6%98%8C%E5%87%A0%E4%BD%95%E6%B5%8B%E7%BB%98%E7%A7%91%E6%8A%80%E6%9C%89%E9%99%90%E5%85%AC%E5%8F%B81732505438540525103.pdf");

    private final PdfUtils pdfUtils;
    private final ImmService immService;
    private final ModelConfig modelConfig;

    public ImmBeforeTextAndImgReview(
            PdfUtils pdfUtils, ImmService immService, ModelConfig modelConfig) {
        this.pdfUtils = pdfUtils;
        this.immService = immService;
        this.modelConfig = modelConfig;
    }

    public static void main(String[] args) {
        SpringApplicationBuilder application = new SpringApplicationBuilder(AgentServiceApplication.class)
                .web(WebApplicationType.NONE);
        try (ConfigurableApplicationContext context = application.run()) {
            ImmBeforeTextAndImgReview review = new ImmBeforeTextAndImgReview(
                    context.getBean(PdfUtils.class),
                    context.getBean(ImmService.class),
                    context.getBean(ModelConfig.class));
            review.execute(args.length == 0 ? PDF_URLS : Arrays.asList(args));
        } catch (Exception exception) {
            throw new IllegalStateException("IMM图文联合审查流程执行失败", exception);
        }
    }

    public void execute(List<String> documentUrls) throws Exception {
        long startNanos = System.nanoTime();
        ExecutorService stageExecutor = Executors.newFixedThreadPool(2);
        try {
            CompletableFuture<List<ImmImagePage>> imageFuture = CompletableFuture.supplyAsync(
                    () -> getImagePages(documentUrls), stageExecutor);
            CompletableFuture<String> textFuture = CompletableFuture.supplyAsync(
                    () -> extractAllDocumentText(documentUrls), stageExecutor);

            List<ImmImagePage> imagePages = getFuture(imageFuture);
            String documentText = getFuture(textFuture);
            System.out.println("IMM图片总数: " + imagePages.size());
            System.out.println("已提取全部文档正文，字符数: " + documentText.length());

            CompletableFuture<List<CibDimensionResult>> visualReviewFuture = CompletableFuture.supplyAsync(
                    () -> reviewVisualDimensions(imagePages), stageExecutor);
            CompletableFuture<String> textReviewFuture = CompletableFuture.supplyAsync(
                    () -> reviewTextDocuments(documentText), stageExecutor);

            List<CibDimensionResult> visualResults = getFuture(visualReviewFuture);
            String textReview = getFuture(textReviewFuture);
            System.out.println("图文专项审查完成，开始生成汇总报告...");
            System.out.println(generateReport(visualResults, textReview));
            System.out.printf("总耗时: %.3f 秒%n", (System.nanoTime() - startNanos) / 1_000_000_000D);
        } finally {
            stageExecutor.shutdown();
        }
    }

    private List<ImmImagePage> getImagePages(List<String> documentUrls) {
        try {
            return pdfUtils.pdfToImage(documentUrls);
        } catch (Exception exception) {
            throw new IllegalStateException("IMM图片转换失败", exception);
        }
    }

    private String extractAllDocumentText(List<String> documentUrls) {
        StringBuilder result = new StringBuilder();
        for (int index = 0; index < documentUrls.size(); index++) {
            String documentUrl = documentUrls.get(index);
            try {
                result.append("\n\n===== 文件 ").append(index + 1).append(" =====\n")
                        .append("来源URL: ").append(documentUrl).append("\n")
                        .append(immService.extractDocumentText(documentUrl));
            } catch (Exception exception) {
                throw new IllegalStateException("文档正文提取失败: " + documentUrl, exception);
            }
        }
        return result.toString();
    }

    private List<CibDimensionResult> reviewVisualDimensions(List<ImmImagePage> imagePages) {
        System.out.println("开始并发执行6个 qwen3.8-flash 图片专项审查...");
        ExecutorService executor = Executors.newFixedThreadPool(REVIEW_THREAD_COUNT);
        try {
            List<CompletableFuture<CibDimensionResult>> futures = CibReviewPrompts.DIMENSIONS.stream()
                    .map(dimension -> CompletableFuture.supplyAsync(
                            () -> reviewVisualDimension(dimension, imagePages), executor))
                    .toList();
            return futures.stream().map(CompletableFuture::join).toList();
        } finally {
            executor.shutdown();
        }
    }

    private CibDimensionResult reviewVisualDimension(
            CibReviewPrompts.Dimension dimension, List<ImmImagePage> imagePages) {
        System.out.println("开始图片专项审查：" + dimension.name());
        try {
            MultiModalConversationResult response = callVisualModel(dimension, imagePages);
            CibDimensionResult result = QwenDocResponseParser.parse(extractText(response), CibDimensionResult.class);
            validateDimension(dimension, result);
            result.setSuccess(true);
            result.setErrorMessage(null);
            printImageReviewInputTokens(dimension.code(), response);
            return result;
        } catch (Exception exception) {
            return failedResult(dimension, "图片专项审查失败", exception);
        }
    }

    private String reviewTextDocuments(String documentText) {
        System.out.println("开始使用 qwen3.7-plus 审查全部文档正文...");
        try {
            ReActAgent agent = ReActAgent.builder()
                    .name("cib-text-review")
                    .sysPrompt(textReviewPrompt())
                    .model(modelConfig.qwen37PlusReportModel())
                    .build();
            Msg response = agent.call(Msg.builder()
                    .role(MsgRole.USER)
                    .textContent("以下是全部投标文件的 IMM 提取正文，请完成六个专项文本审查：\n" + documentText)
                    .build()).block();
            if (response == null || response.getTextContent().isBlank()) {
                throw new IllegalStateException("文本审查未返回内容");
            }
            return response.getTextContent();
        } catch (Exception exception) {
            System.err.println("全文文本审查失败：" + exception.getMessage());
            return "";
        }
    }

    private MultiModalConversationResult callVisualModel(
            CibReviewPrompts.Dimension dimension, List<ImmImagePage> imagePages) throws Exception {
        List<Map<String, Object>> content = new ArrayList<>();
        for (ImmImagePage imagePage : imagePages) {
            content.add(Map.of("image", imagePage.url()));
        }
        content.add(Map.of("text", "图片顺序与证据来源映射：\n" + imageMapping(imagePages)
                + "\n\n请完整查看以上全部PDF页面，只执行“" + dimension.name()
                + "”专项审查。输出必须严格符合系统提示词中的JSON Schema。"
                + "证据document必须使用映射中的原始PDF文件名，location必须写图片对应的PDF页码或可见章节。"));
        MultiModalConversationParam param = MultiModalConversationParam.builder()
                .apiKey(AgentServiceConfig.dashScopeApiKey())
                .model(ModelConfig.QWEN38_FLASH_MODEL_NAME)
                .messages(Arrays.asList(MultiModalMessage.builder()
                        .role(Role.SYSTEM.getValue())
                        .content(List.of(Map.of("text", CibReviewPrompts.promptFor(dimension))))
                        .build(), MultiModalMessage.builder()
                        .role(Role.USER.getValue())
                        .content(content)
                        .build()))
                .maxLength(4096)
                .temperature(0.2F)
                .build();
        return modelConfig.qwen38FlashMultimodalModel().call(param);
    }

    private String generateReport(
            List<CibDimensionResult> visualResults, String textReview) {
        String reportMaterials = "审查结果：\n" + toJson(visualResults);
        if (textReview != null && !textReview.isBlank()) {
            reportMaterials += "\n\n补充审查结论：\n" + textReview;
        }
        ReActAgent reportAgent = ReActAgent.builder()
                .name("cib-multisource-report")
                .sysPrompt(CibReviewPrompts.REPORT_PROMPT + """

                        本次输入可能包含多份互补审查材料，汇总时应综合使用其中可验证的事实；证据冲突时采用更保守结论并标记人工复核。
                        报告只聚焦审查结论、原文证据、风险判断和处理建议。不得出现或暗示视觉审查、文本审查、图片、正文、IMM、模型、阶段、专项失败、来源不可用、调用异常等过程信息。
                        某项材料未提供有效证据时，只能在对应结论中写“证据不足”或“未发现明确异常”，不得解释审查过程或缺失原因。
                        """)
                .model(modelConfig.qwen37PlusReportModel())
                .build();
        Msg response = reportAgent.call(Msg.builder()
                .role(MsgRole.USER)
                .textContent(reportMaterials)
                .build()).block();
        if (response == null || response.getTextContent().isBlank()) {
            throw new IllegalStateException("报告Agent未返回内容");
        }
        return response.getTextContent();
    }

    private String textReviewPrompt() {
        String dimensions = CibReviewPrompts.DIMENSIONS.stream()
                .map(dimension -> "- " + dimension.code() + "（" + dimension.name() + "）："
                        + dimension.focus())
                .reduce("", (left, right) -> left + "\n" + right);
        return """
                你是政府采购围标串标全文文本审查专家。用户会提供全部投标文件的正文。
                只依据正文中的明确事实完成以下六个专项审查：
                %s

                对每个专项建立跨投标人证据，排除同一投标人的二次报价、招标文件原文、统一模板和行业通用内容。
                每条finding必须有至少两家投标人的位置和原文摘录；无法确认时使用UNKNOWN，不得编造事实。

                请按六个专项分别输出文本审查结论。每个专项列出：结论、涉及投标人、文件位置、双方原文摘录、风险判断和人工复核建议。
                没有明确异常时直接说明“未发现明确异常”或“证据不足”；不要输出JSON，也不要省略专项。
                """.formatted(dimensions);
    }

    private CibDimensionResult failedResult(
            CibReviewPrompts.Dimension dimension, String stage, Exception exception) {
        System.err.println(stage + "[" + dimension.name() + "]：" + exception.getMessage());
        CibDimensionResult failed = new CibDimensionResult();
        failed.setDimension(dimension.code());
        failed.setSummary(stage + "，未获得有效结果");
        failed.setRiskLevel("UNKNOWN");
        failed.setSuccess(false);
        failed.setErrorMessage(exception.getMessage());
        failed.setRecommendations(List.of("重新执行" + dimension.name() + "并人工复核"));
        return failed;
    }

    private <T> T getFuture(CompletableFuture<T> future) throws Exception {
        try {
            return future.get();
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof Exception typedCause) {
                throw typedCause;
            }
            throw new IllegalStateException("异步流程执行失败", cause);
        }
    }

    private void validateDimension(CibReviewPrompts.Dimension dimension, CibDimensionResult result) {
        if (result == null || !dimension.code().equals(result.getDimension())) {
            throw new IllegalArgumentException("专项结果dimension不匹配: "
                    + (result == null ? null : result.getDimension()));
        }
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
                    .append("张图片属于文件《").append(document).append("》；图片页码范围为第")
                    .append(imagePages.get(index).page()).append("页至第")
                    .append(imagePages.get(end).page()).append("页。\n");
            start = end + 2;
            index = end + 1;
        }
        return mapping.toString();
    }

    private String extractText(MultiModalConversationResult response) {
        if (response == null || response.getOutput() == null
                || response.getOutput().getChoices() == null
                || response.getOutput().getChoices().isEmpty()
                || response.getOutput().getChoices().get(0).getMessage() == null) {
            throw new IllegalStateException("多模态模型未返回结果");
        }
        List<Map<String, Object>> content = response.getOutput().getChoices().get(0).getMessage().getContent();
        if (content == null) {
            throw new IllegalStateException("多模态模型返回内容为空");
        }
        return content.stream()
                .map(item -> item.get("text"))
                .filter(value -> value != null)
                .map(Object::toString)
                .reduce("", String::concat);
    }

    private void printImageReviewInputTokens(
            String dimension, MultiModalConversationResult response) {
        if (response != null && response.getUsage() != null
                && response.getUsage().getInputTokens() != null) {
            System.out.println("图片审查输入Token[" + dimension + "]: "
                    + response.getUsage().getInputTokens());
        }
    }

    private String toJson(Object value) {
        try {
            return OBJECT_MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("序列化审查结果失败", exception);
        }
    }
}
