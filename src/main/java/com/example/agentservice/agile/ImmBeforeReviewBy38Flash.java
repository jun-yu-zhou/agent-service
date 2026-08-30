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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** 使用 qwen3.8-flash 审查 IMM 转换图片，并沿用原有汇总模型的流程。 */
public class ImmBeforeReviewBy38Flash {

    private static final int REVIEW_THREAD_COUNT = 6;
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final List<String> PDF_URLS = List.of(
            "http://oss1.easyjcx.com/co-order/2026/08/31/0e0abeab14294f35afc315230a16cee58458345289706990841.pdf",
            "http://oss1.easyjcx.com/co-order/2026/08/31/ac7fa88168994d84a379f6cc91223d473481530127675841490.pdf",
            "http://oss1.easyjcx.com/co-order/2026/08/31/6687c758aa79464cbe8920e1090045c71760585613030349238.pdf",
            "http://oss1.easyjcx.com/co-order/2026/08/31/a8974b5bc67f4abd86102b1fd6b9fdad691476349652533877.pdf");

    private final PdfUtils pdfUtils;
    private final ModelConfig modelConfig;

    public ImmBeforeReviewBy38Flash(PdfUtils pdfUtils, ModelConfig modelConfig) {
        this.pdfUtils = pdfUtils;
        this.modelConfig = modelConfig;
    }

    public static void main(String[] args) {
        SpringApplicationBuilder application = new SpringApplicationBuilder(AgentServiceApplication.class)
                .web(WebApplicationType.NONE);
        try (ConfigurableApplicationContext context = application.run()) {
            ImmBeforeReviewBy38Flash review = new ImmBeforeReviewBy38Flash(
                    context.getBean(PdfUtils.class), context.getBean(ModelConfig.class));
            review.execute(args.length == 0 ? PDF_URLS : Arrays.asList(args));
        } catch (Exception exception) {
            throw new IllegalStateException("qwen3.8-flash IMM审查流程执行失败", exception);
        }
    }

    public void execute(List<String> pdfUrls) throws Exception {
        long startNanos = System.nanoTime();
        List<ImmImagePage> imagePages = pdfUtils.pdfToImage(pdfUrls);
        System.out.println("总图片数: " + imagePages.size());
        System.out.println("阶段2/3：开始并发执行6个 qwen3.8-flash 多模态专项审查...");
        List<CibDimensionResult> dimensionResults = reviewDimensions(imagePages);
        System.out.println("阶段3/3：专项审查完成，开始生成汇总报告...");
        System.out.println(generateReport(dimensionResults));
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
            printImageReviewInputTokens(dimension.code(), response);
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
                .model(ModelConfig.QWEN38_FLASH_MODEL_NAME)
                .messages(Arrays.asList(MultiModalMessage.builder()
                        .role(Role.SYSTEM.getValue())
                        .content(List.of(Map.of("text", CibReviewPrompts.promptFor(dimension))))
                        .build(), userMessage))
                .maxLength(4096)
                .temperature(0.2F)
                .build();
        return modelConfig.qwen38FlashMultimodalModel().call(param);
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

    private String generateReport(List<CibDimensionResult> dimensionResults) {
        ReActAgent reportAgent = ReActAgent.builder()
                .name("cib-report")
                .sysPrompt(CibReviewPrompts.REPORT_PROMPT)
                .model(modelConfig.qwen37PlusReportModel())
                .build();
        Msg request = Msg.builder()
                .role(MsgRole.USER)
                .textContent("以下是六个多模态专项Agent返回的JSON结果，请严格汇总：\n"
                        + toJson(dimensionResults))
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
