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
import com.example.agentservice.utils.PdfUtils;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** IMM图片转换后的多模态审查流程。PDF转换编排通过PdfUtils完成。 */
public class ImmBeforeReview {

    private static final int REVIEW_THREAD_COUNT = 6;
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final List<String> PDF_URLS = List.of(
            "https://javawebemp.oss-cn-beijing.aliyuncs.com/Scan-%E5%AE%9C%E6%98%8C%E5%87%A0%E4%BD%95%E6%B5%8B%E7%BB%98%E7%A7%91%E6%8A%80%E6%9C%89%E9%99%90%E5%85%AC%E5%8F%B88960772218806061282.pdf",
            "https://javawebemp.oss-cn-beijing.aliyuncs.com/2%E8%BD%AE%E6%8A%A5%E4%BB%B7--%E4%B8%89%E5%B3%A1%E5%A4%A7%E5%AD%A6%E6%96%B0%E5%BB%BA%E5%AD%A6%E7%94%9F%E5%85%AC%E5%AF%93%E9%A1%B9%E7%9B%AE%E5%A4%9A%E6%B5%8B%E5%90%88%E4%B8%80-%E6%B9%96%E5%8C%97%E6%8D%B7%E5%B8%86%E7%A7%91%E6%8A%80%E6%9C%89%E9%99%90%E5%85%AC%E5%8F%B83512886208567055417.pdf",
            "https://javawebemp.oss-cn-beijing.aliyuncs.com/%E4%B8%89%E5%B3%A1%E5%A4%A7%E5%AD%A6%E6%96%B0%E5%BB%BA%E5%AD%A6%E7%94%9F%E5%85%AC%E5%AF%93%E9%A1%B9%E7%9B%AE%E5%A4%9A%E6%B5%8B%E5%90%88%E4%B8%80%E7%AB%9E%E4%BA%89%E6%80%A7%E7%A3%8B%E5%95%86%E5%93%8D%E5%BA%94%E6%96%87%E4%BB%B6%EF%BC%88%E6%8A%95%E6%A0%87%E6%96%87%E4%BB%B6%EF%BC%89--%E6%B9%96%E5%8C%97%E6%8D%B7%E5%B8%86%E7%A7%91%E6%8A%80%E6%9C%89%E9%99%90%E5%85%AC%E5%8F%B8-%E6%B9%96%E5%8C%97%E6%8D%B7%E5%B8%86%E7%A7%91%E6%8A%80%E6%9C%89%E9%99%90%E5%85%AC%E5%8F%B85201011215828763487.pdf",
            "https://javawebemp.oss-cn-beijing.aliyuncs.com/%E4%BA%8C%E8%BD%AE%E6%8A%A5%E4%BB%B7%281%29-%E5%AE%9C%E6%98%8C%E5%87%A0%E4%BD%95%E6%B5%8B%E7%BB%98%E7%A7%91%E6%8A%80%E6%9C%89%E9%99%90%E5%85%AC%E5%8F%B81732505438540525103.pdf");

    private final PdfUtils pdfUtils;
    private final ModelConfig modelConfig;

    public ImmBeforeReview(PdfUtils pdfUtils) {
        this(pdfUtils, ModelConfig.standalone());
    }

    public ImmBeforeReview(PdfUtils pdfUtils, ModelConfig modelConfig) {
        this.pdfUtils = pdfUtils;
        this.modelConfig = modelConfig;
    }

    public static void main(String[] args) {
        SpringApplicationBuilder application = new SpringApplicationBuilder(AgentServiceApplication.class)
                .web(WebApplicationType.NONE);
        try (ConfigurableApplicationContext context = application.run()) {
            ImmBeforeReview review = new ImmBeforeReview(
                    context.getBean(PdfUtils.class), context.getBean(ModelConfig.class));
            review.execute(args.length == 0 ? PDF_URLS : Arrays.asList(args));
        } catch (Exception exception) {
            throw new IllegalStateException("IMM审查流程执行失败", exception);
        }
    }

    public void execute(List<String> pdfUrls) throws Exception {
        long startNanos = System.nanoTime();
        List<ImmImagePage> imagePages = pdfUtils.pdfToImage(pdfUrls);
        System.out.println("总图片数: " + imagePages.size());
        System.out.println("阶段2/3：开始并发执行6个多模态专项审查...");
        List<CibDimensionResult> dimensionResults = reviewDimensions(imagePages);
        System.out.println("阶段3/3：专项审查完成，开始生成汇总报告...");
        String report = generateReport(dimensionResults);
        System.out.println(report);
        System.out.printf("总耗时: %.3f 秒%n", (System.nanoTime() - startNanos) / 1_000_000_000D);
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
        System.out.println("开始专项审查：" + dimension.name());
        try {
            MultiModalConversationResult response = callMultimodal(dimension, imagePages);
            String rawText = extractText(response);
            CibDimensionResult result = QwenDocResponseParser.parse(rawText, CibDimensionResult.class);
            if (!dimension.code().equals(result.getDimension())) {
                throw new IllegalArgumentException("专项结果dimension不匹配: " + result.getDimension());
            }
            result.setSuccess(true);
            result.setErrorMessage(null);
            System.out.println("完成专项审查：" + dimension.name());
            System.out.println("专项JSON[" + dimension.code() + "]: " + toJson(result));
            printUsage(dimension.code(), response);
            return result;
        } catch (Exception exception) {
            System.err.println("专项审查失败：" + dimension.name() + "，" + exception.getMessage());
            CibDimensionResult failed = new CibDimensionResult();
            failed.setDimension(dimension.code());
            failed.setSummary("专项审查失败，未获得有效结果");
            failed.setRiskLevel("UNKNOWN");
            failed.setSuccess(false);
            failed.setErrorMessage(exception.getMessage());
            failed.setRecommendations(List.of("重新执行" + dimension.name() + "并人工复核"));
            return failed;
        }
    }

    private MultiModalConversationResult callMultimodal(
            CibReviewPrompts.Dimension dimension, List<ImmImagePage> imagePages) throws Exception {
        List<Map<String, Object>> content = new ArrayList<>();
        for (ImmImagePage imagePage : imagePages) {
            content.add(Map.of("image", imagePage.url()));
        }
        content.add(Map.of("text", "图片顺序与证据来源映射：\n" + imageMapping(imagePages)
                + "\n\n请完整查看以上全部PDF页面，只执行“" + dimension.name()
                + "”专项审查。输出必须严格符合系统提示词中的JSON Schema。"
                + "证据document必须使用映射中的原始PDF文件名，location必须写图片对应的PDF页码或可见章节。"));
        MultiModalMessage userMessage = MultiModalMessage.builder()
                .role(Role.USER.getValue())
                .content(content)
                .build();
        MultiModalConversationParam param = MultiModalConversationParam.builder()
                .apiKey(AgentServiceConfig.dashScopeApiKey())
                .model(ModelConfig.QWEN37_PLUS_MODEL_NAME)
                .messages(Arrays.asList(MultiModalMessage.builder()
                        .role(Role.SYSTEM.getValue())
                        .content(List.of(Map.of("text", CibReviewPrompts.promptFor(dimension))))
                        .build(), userMessage))
                .maxLength(4096)
                .temperature(0.2F)
                .build();
        return modelConfig.qwen37PlusMultimodalModel().call(param);
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

    private void printUsage(String dimension, MultiModalConversationResult response) {
        int input = response.getUsage() == null || response.getUsage().getInputTokens() == null
                ? 0 : response.getUsage().getInputTokens();
        int output = response.getUsage() == null || response.getUsage().getOutputTokens() == null
                ? 0 : response.getUsage().getOutputTokens();
        System.out.println("专项Token[" + dimension + "]: input=" + input
                + ", output=" + output + ", total=" + (input + output));
    }

    String generateReport(List<CibDimensionResult> dimensionResults) {
        ReActAgent reportAgent = ReActAgent.builder()
                .name("cib-report")
                .sysPrompt(CibReviewPrompts.REPORT_PROMPT)
                .model(modelConfig.qwen37PlusReportModel())
                .build();
        Msg request = Msg.builder()
                .role(MsgRole.USER)
                .textContent("以下是六个多模态专项Agent返回的JSON结果，请严格汇总：\n" + toJson(dimensionResults))
                .build();
        Msg response = reportAgent.call(request).block();
        if (response == null || response.getTextContent().isBlank()) {
            throw new IllegalStateException("报告Agent未返回内容");
        }
        return response.getTextContent();
    }

    private String toJson(Object value) {
        try {
            return OBJECT_MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("序列化审查结果失败", exception);
        }
    }

}
