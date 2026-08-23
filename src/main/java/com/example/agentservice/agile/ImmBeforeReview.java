package com.example.agentservice.agile;

import com.alibaba.dashscope.aigc.multimodalconversation.MultiModalConversation;
import com.alibaba.dashscope.aigc.multimodalconversation.MultiModalConversationParam;
import com.alibaba.dashscope.aigc.multimodalconversation.MultiModalConversationResult;
import com.alibaba.dashscope.common.MultiModalMessage;
import com.alibaba.dashscope.common.Role;
import com.aliyun.imm20200930.Client;
import com.aliyun.imm20200930.models.CreateOfficeConversionTaskRequest;
import com.aliyun.imm20200930.models.CreateOfficeConversionTaskResponse;
import com.aliyun.imm20200930.models.GetTaskRequest;
import com.aliyun.imm20200930.models.GetTaskResponse;
import com.aliyun.oss.ClientBuilderConfiguration;
import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSClientBuilder;
import com.aliyun.oss.common.auth.DefaultCredentialProvider;
import com.aliyun.oss.common.comm.SignVersion;
import com.aliyun.oss.model.ListObjectsRequest;
import com.aliyun.oss.model.OSSObjectSummary;
import com.aliyun.teaopenapi.models.Config;
import com.aliyun.teautil.models.RuntimeOptions;
import com.example.agentservice.entity.CibDimensionResult;
import com.example.agentservice.config.AgentServiceConfig;
import com.example.agentservice.prompts.CibReviewPrompts;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.model.ExecutionConfig;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.OpenAIChatModel;
import io.agentscope.core.model.transport.HttpTransportConfig;
import io.agentscope.core.model.transport.JdkHttpTransport;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ImmBeforeReview {

    private static final String MODEL_NAME = "qwen3.7-plus";
    private static final String DASH_SCOPE_BASE_URL = "https://dashscope.aliyuncs.com/compatible-mode/v1";
    private static final int REVIEW_THREAD_COUNT = 6;

    // 自定义域名无法从 URL 推断真实 Bucket，请按 OSS 控制台实际配置修改。
    private static final String OUTPUT_PREFIX = "imm-review";
    private static final int OUTPUT_WAIT_SECONDS = 600;

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final List<String> PDF_URLS = List.of(
            "https://javawebemp.oss-cn-beijing.aliyuncs.com/Scan-%E5%AE%9C%E6%98%8C%E5%87%A0%E4%BD%95%E6%B5%8B%E7%BB%98%E7%A7%91%E6%8A%80%E6%9C%89%E9%99%90%E5%85%AC%E5%8F%B88960772218806061282.pdf",
            "https://javawebemp.oss-cn-beijing.aliyuncs.com/2%E8%BD%AE%E6%8A%A5%E4%BB%B7--%E4%B8%89%E5%B3%A1%E5%A4%A7%E5%AD%A6%E6%96%B0%E5%BB%BA%E5%AD%A6%E7%94%9F%E5%85%AC%E5%AF%93%E9%A1%B9%E7%9B%AE%E5%A4%9A%E6%B5%8B%E5%90%88%E4%B8%80-%E6%B9%96%E5%8C%97%E6%8D%B7%E5%B8%86%E7%A7%91%E6%8A%80%E6%9C%89%E9%99%90%E5%85%AC%E5%8F%B83512886208567055417.pdf",
            "https://javawebemp.oss-cn-beijing.aliyuncs.com/%E4%B8%89%E5%B3%A1%E5%A4%A7%E5%AD%A6%E6%96%B0%E5%BB%BA%E5%AD%A6%E7%94%9F%E5%85%AC%E5%AF%93%E9%A1%B9%E7%9B%AE%E5%A4%9A%E6%B5%8B%E5%90%88%E4%B8%80%E7%AB%9E%E4%BA%89%E6%80%A7%E7%A3%8B%E5%95%86%E5%93%8D%E5%BA%94%E6%96%87%E4%BB%B6%EF%BC%88%E6%8A%95%E6%A0%87%E6%96%87%E4%BB%B6%EF%BC%89--%E6%B9%96%E5%8C%97%E6%8D%B7%E5%B8%86%E7%A7%91%E6%8A%80%E6%9C%89%E9%99%90%E5%85%AC%E5%8F%B8-%E6%B9%96%E5%8C%97%E6%8D%B7%E5%B8%86%E7%A7%91%E6%8A%80%E6%9C%89%E9%99%90%E5%85%AC%E5%8F%B85201011215828763487.pdf",
            "https://javawebemp.oss-cn-beijing.aliyuncs.com/%E4%BA%8C%E8%BD%AE%E6%8A%A5%E4%BB%B7%281%29-%E5%AE%9C%E6%98%8C%E5%87%A0%E4%BD%95%E6%B5%8B%E7%BB%98%E7%A7%91%E6%8A%80%E6%9C%89%E9%99%90%E5%85%AC%E5%8F%B81732505438540525103.pdf");

    public static void main(String[] args) throws Exception {
        long startNanos = System.nanoTime();
        OSS ossClient = createOssClient();
        Client immClient = createImmClient();
        JdkHttpTransport httpTransport = newHttpTransport();
        try {
            List<ImagePage> imagePages = convertPdfsToImages(ossClient, immClient, PDF_URLS);
            System.out.println("总图片数: " + imagePages.size());
            System.out.println("阶段2/3：开始并发执行6个多模态专项审查...");
            List<CibDimensionResult> dimensionResults = reviewDimensions(imagePages);
            System.out.println("阶段3/3：专项审查完成，开始生成汇总报告...");
            String report = generateReport(dimensionResults, httpTransport);
            System.out.println(report);
        } finally {
            ossClient.shutdown();
        }
        System.out.printf("总耗时: %.3f 秒%n", (System.nanoTime() - startNanos) / 1_000_000_000D);
    }

    private static OSS createOssClient() throws Exception {
        DefaultCredentialProvider credentialsProvider =
                new DefaultCredentialProvider(AgentServiceConfig.ossAccessKeyId(),
                        AgentServiceConfig.ossAccessKeySecret());
        ClientBuilderConfiguration configuration = new ClientBuilderConfiguration();
        configuration.setSignatureVersion(SignVersion.V4);
        return OSSClientBuilder.create()
                .endpoint(AgentServiceConfig.ossEndpoint())
                .region(AgentServiceConfig.ossRegion())
                .credentialsProvider(credentialsProvider)
                .clientConfiguration(configuration)
                .build();
    }

    private static Client createImmClient() throws Exception {
        Config config = new Config()
                .setAccessKeyId(AgentServiceConfig.ossAccessKeyId())
                .setAccessKeySecret(AgentServiceConfig.ossAccessKeySecret())
                .setRegionId(AgentServiceConfig.ossRegion())
                .setEndpoint(AgentServiceConfig.immEndpoint());
        return new Client(config);
    }

    private static List<ImagePage> convertPdfsToImages(OSS ossClient, Client immClient, List<String> pdfUrls)
            throws Exception {
        List<ImagePage> allImagePages = new ArrayList<>();
        for (String pdfUrl : pdfUrls) {
            if (!pdfUrl.toLowerCase().contains(".pdf")) {
                throw new IllegalArgumentException("仅支持PDF文件: " + pdfUrl);
            }
            String sourceKey = objectKey(pdfUrl);
            String outputPrefix = OUTPUT_PREFIX + "/" + UUID.randomUUID() + "/";
            CreateOfficeConversionTaskRequest request = new CreateOfficeConversionTaskRequest()
                    .setProjectName(AgentServiceConfig.immProjectName())
                    .setSourceURI("oss://" + AgentServiceConfig.ossBucket() + "/" + sourceKey)
                    .setSourceType("pdf")
                    .setTargetType("png")
                    .setTargetURIPrefix("oss://" + AgentServiceConfig.ossBucket() + "/" + outputPrefix)
                    .setStartPage(1L)
                    .setEndPage(-1L);
            CreateOfficeConversionTaskResponse task = immClient.createOfficeConversionTaskWithOptions(
                    request, new RuntimeOptions());
            String taskId = task.getBody().getTaskId();
            System.out.println("已提交IMM转换任务: " + taskId + ", project=" + AgentServiceConfig.immProjectName()
                    + ", object=" + sourceKey);
            waitForImmTask(immClient, taskId);

            List<OSSObjectSummary> outputs = waitForOutputs(ossClient, outputPrefix);
            if (outputs.isEmpty()) {
                throw new IllegalStateException("OSS/IMM转换未生成图片: " + sourceKey);
            }
            outputs.sort(Comparator.comparingInt((OSSObjectSummary item) -> pageNumber(item.getKey()))
                    .thenComparing(OSSObjectSummary::getKey));
            for (int outputIndex = 0; outputIndex < outputs.size(); outputIndex++) {
                OSSObjectSummary output = outputs.get(outputIndex);
                String imageUrl = ossClient.generatePresignedUrl(
                        AgentServiceConfig.ossBucket(), output.getKey(), Date.from(Instant.now().plus(Duration.ofHours(2)))).toString();
                int page = pageNumber(output.getKey());
                if (page == Integer.MAX_VALUE) {
                    page = outputIndex + 1;
                }
                allImagePages.add(new ImagePage(documentName(sourceKey), page, imageUrl));
                System.out.println("图片OSS URL: " + imageUrl);
            }
        }
        return allImagePages;
    }

    private static void waitForImmTask(Client immClient, String taskId) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(OUTPUT_WAIT_SECONDS).toNanos();
        while (System.nanoTime() < deadline) {
            GetTaskResponse response = immClient.getTaskWithOptions(
                    new GetTaskRequest()
                            .setProjectName(AgentServiceConfig.immProjectName())
                            .setTaskType("OfficeConversion")
                            .setTaskId(taskId),
                    new RuntimeOptions());
            var body = response.getBody();
            String status = body == null ? null : body.getStatus();
            System.out.println("IMM任务状态: " + status + ", progress="
                    + (body == null ? null : body.getProgress()));
            if ("Succeeded".equalsIgnoreCase(status) || "Success".equalsIgnoreCase(status)) {
                return;
            }
            if ("Failed".equalsIgnoreCase(status) || "Error".equalsIgnoreCase(status)) {
                throw new IllegalStateException("IMM转换失败: " + (body == null ? "unknown" : body.getMessage()));
            }
            Thread.sleep(3000L);
        }
        throw new IllegalStateException("IMM转换任务超时: " + taskId);
    }

    private static List<OSSObjectSummary> waitForOutputs(OSS ossClient, String prefix) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(OUTPUT_WAIT_SECONDS).toNanos();
        int previousCount = 0;
        int stableRounds = 0;
        List<OSSObjectSummary> outputs = List.of();
        while (System.nanoTime() < deadline) {
            outputs = listOutputs(ossClient, prefix);
            if (!outputs.isEmpty() && outputs.size() == previousCount) {
                stableRounds++;
                if (stableRounds >= 3) {
                    return outputs;
                }
            } else {
                stableRounds = 0;
            }
            previousCount = outputs.size();
            Thread.sleep(2000L);
        }
        return outputs;
    }

    private static List<OSSObjectSummary> listOutputs(OSS ossClient, String prefix) {
        List<OSSObjectSummary> result = new ArrayList<>();
        String marker = null;
        do {
            var page = ossClient.listObjects(new ListObjectsRequest(AgentServiceConfig.ossBucket()).withPrefix(prefix).withMarker(marker));
            result.addAll(page.getObjectSummaries().stream()
                    .filter(item -> item.getKey().endsWith(".png"))
                    .toList());
            marker = page.isTruncated() ? page.getNextMarker() : null;
        } while (marker != null && !marker.isBlank());
        return result;
    }

    private static JdkHttpTransport newHttpTransport() {
        return JdkHttpTransport.builder()
                .config(HttpTransportConfig.builder()
                        .connectTimeout(Duration.ofSeconds(30))
                        .readTimeout(Duration.ofMinutes(20))
                        .writeTimeout(Duration.ofMinutes(2))
                        .build())
                .build();
    }

    private static List<CibDimensionResult> reviewDimensions(List<ImagePage> imagePages) {
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

    private static CibDimensionResult reviewDimension(
            CibReviewPrompts.Dimension dimension, List<ImagePage> imagePages) {
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

    private static MultiModalConversationResult callMultimodal(
            CibReviewPrompts.Dimension dimension, List<ImagePage> imagePages) throws Exception {
        List<Map<String, Object>> content = new ArrayList<>();
        for (ImagePage imagePage : imagePages) {
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
                .model(MODEL_NAME)
                .messages(Arrays.asList(MultiModalMessage.builder()
                        .role(Role.SYSTEM.getValue())
                        .content(List.of(Map.of("text", CibReviewPrompts.promptFor(dimension))))
                        .build(), userMessage))
                .maxLength(4096)
                .temperature(0.2F)
                .build();
        return new MultiModalConversation().call(param);
    }

    private static String imageMapping(List<ImagePage> imagePages) {
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

    private static String extractText(MultiModalConversationResult response) {
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

    private static void printUsage(String dimension, MultiModalConversationResult response) {
        int input = response.getUsage() == null || response.getUsage().getInputTokens() == null
                ? 0 : response.getUsage().getInputTokens();
        int output = response.getUsage() == null || response.getUsage().getOutputTokens() == null
                ? 0 : response.getUsage().getOutputTokens();
        System.out.println("专项Token[" + dimension + "]: input=" + input
                + ", output=" + output + ", total=" + (input + output));
    }

    private static String generateReport(List<CibDimensionResult> dimensionResults,
                                         JdkHttpTransport httpTransport) {
        ReActAgent reportAgent = ReActAgent.builder()
                .name("cib-report")
                .sysPrompt(CibReviewPrompts.REPORT_PROMPT)
                .model(OpenAIChatModel.builder()
                        .apiKey(AgentServiceConfig.dashScopeApiKey())
                        .modelName("qwen3.8-max")
                        .baseUrl(DASH_SCOPE_BASE_URL)
                        .endpointPath("/chat/completions")
                        .httpTransport(httpTransport)
                        .stream(true)
                        .generateOptions(GenerateOptions.builder()
                                .maxTokens(8192)
                                .temperature(0.15D)
                                .executionConfig(ExecutionConfig.builder()
                                        .timeout(Duration.ofMinutes(20))
                                        .maxAttempts(1)
                                        .build())
                                .build())
                        .build())
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

    private static String toJson(Object value) {
        try {
            return OBJECT_MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("序列化审查结果失败", exception);
        }
    }

    private static String objectKey(String url) {
        String path = URI.create(url).getRawPath();
        if (path == null || path.length() <= 1) {
            throw new IllegalArgumentException("OSS URL没有对象路径: " + url);
        }
        return URLDecoder.decode(path.substring(1).replace("+", "%2B"), StandardCharsets.UTF_8);
    }

    private static int pageNumber(String key) {
        String fileName = key.substring(key.lastIndexOf('/') + 1);
        String stem = fileName.substring(0, fileName.lastIndexOf('.'));
        try {
            return Integer.parseInt(stem);
        } catch (RuntimeException ignored) {
            Matcher matcher = Pattern.compile("(\\d+)$").matcher(stem);
            if (matcher.find()) {
                try {
                    return Integer.parseInt(matcher.group(1));
                } catch (NumberFormatException ignoredNumber) {
                    // Fall through to the stable OSS key order.
                }
            }
            return Integer.MAX_VALUE;
        }
    }

    private static String documentName(String sourceKey) {
        return sourceKey.substring(sourceKey.lastIndexOf('/') + 1);
    }

    private record ImagePage(String document, int page, String url) {
    }
}
