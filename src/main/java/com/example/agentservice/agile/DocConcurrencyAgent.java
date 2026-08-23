package com.example.agentservice.agile;

import com.example.document2entity.entity.CibDimensionResult;
import com.example.document2entity.entity.CibReviewResult;
import com.example.document2entity.formatter.QwenDocDashScopeChatFormatter;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.model.*;
import io.agentscope.core.model.transport.HttpTransportConfig;
import io.agentscope.core.model.transport.JdkHttpTransport;

import java.net.URI;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class DocConcurrencyAgent {

    private static final String ai_key = "DASHSCOPE_API_KEY";
    private static final String DASH_SCOPE_BASE_URL = "https://dashscope.aliyuncs.com/compatible-mode/v1";
    private static final int REVIEW_THREAD_COUNT = 6;
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final List<String> pdfList = List.of("https://javawebemp.oss-cn-beijing.aliyuncs.com/Scan-%E5%AE%9C%E6%98%8C%E5%87%A0%E4%BD%95%E6%B5%8B%E7%BB%98%E7%A7%91%E6%8A%80%E6%9C%89%E9%99%90%E5%85%AC%E5%8F%B88960772218806061282.pdf",
            "https://javawebemp.oss-cn-beijing.aliyuncs.com/%E4%BA%8C%E8%BD%AE%E6%8A%A5%E4%BB%B7%281%29-%E5%AE%9C%E6%98%8C%E5%87%A0%E4%BD%95%E6%B5%8B%E7%BB%98%E7%A7%91%E6%8A%80%E6%9C%89%E9%99%90%E5%85%AC%E5%8F%B81732505438540525103.pdf",
            "https://javawebemp.oss-cn-beijing.aliyuncs.com/%E4%B8%89%E5%B3%A1%E5%A4%A7%E5%AD%A6%E6%96%B0%E5%BB%BA%E5%AD%A6%E7%94%9F%E5%85%AC%E5%AF%93%E9%A1%B9%E7%9B%AE%E5%A4%9A%E6%B5%8B%E5%90%88%E4%B8%80%E7%AB%9E%E4%BA%89%E6%80%A7%E7%A3%8B%E5%95%86%E5%93%8D%E5%BA%94%E6%96%87%E4%BB%B6%EF%BC%88%E6%8A%95%E6%A0%87%E6%96%87%E4%BB%B6%EF%BC%89--%E6%B9%96%E5%8C%97%E6%8D%B7%E5%B8%86%E7%A7%91%E6%8A%80%E6%9C%89%E9%99%90%E5%85%AC%E5%8F%B8-%E6%B9%96%E5%8C%97%E6%8D%B7%E5%B8%86%E7%A7%91%E6%8A%80%E6%9C%89%E9%99%90%E5%85%AC%E5%8F%B85201011215828763487.pdf",
            "https://javawebemp.oss-cn-beijing.aliyuncs.com/2%E8%BD%AE%E6%8A%A5%E4%BB%B7--%E4%B8%89%E5%B3%A1%E5%A4%A7%E5%AD%A6%E6%96%B0%E5%BB%BA%E5%AD%A6%E7%94%9F%E5%85%AC%E5%AF%93%E9%A1%B9%E7%9B%AE%E5%A4%9A%E6%B5%8B%E5%90%88%E4%B8%80-%E6%B9%96%E5%8C%97%E6%8D%B7%E5%B8%86%E7%A7%91%E6%8A%80%E6%9C%89%E9%99%90%E5%85%AC%E5%8F%B83512886208567055417.pdf",
            "https://javawebemp.oss-cn-beijing.aliyuncs.com/%E5%8B%98%E6%B5%8B%E9%99%A2%E6%8A%95%E6%A0%87%E6%96%87%E4%BB%B6-%E5%AE%9C%E6%98%8C%E5%B8%82%E5%8B%98%E5%AF%9F%E6%B5%8B%E7%BB%98%E7%A0%94%E7%A9%B6%E9%99%A2%E6%9C%89%E9%99%90%E5%85%AC%E5%8F%B84754786566717863762.pdf");

    public static void main(String[] args) {
        CibReviewResult result = execute(pdfList);
        System.out.println(result.getMarkdownReport());
    }

    public static CibReviewResult execute(List<String> pdfUrls) {
        validatePdfUrls(pdfUrls);
        LocalDateTime start = LocalDateTime.now();
        JdkHttpTransport httpTransport = JdkHttpTransport.builder()
                .config(HttpTransportConfig.builder()
                        .connectTimeout(Duration.ofSeconds(30))
                        .readTimeout(Duration.ofMinutes(20))
                        .writeTimeout(Duration.ofMinutes(2))
                        .build())
                .build();

        System.out.println("已准备 " + pdfUrls.size() + " 份PDF URL，开始并发执行6个 qwen-doc 专项评审...");
        List<CibDimensionResult> dimensionResults = reviewDimensions(pdfUrls, httpTransport);
        System.out.println("专项评审完成，开始生成汇总报告...");
        String report = generateReport(dimensionResults, httpTransport);

        CibReviewResult result = new CibReviewResult();
        result.setDimensionResults(dimensionResults);
        result.setMarkdownReport(report);
        System.out.println("审查耗时：" + Duration.between(start, LocalDateTime.now()).toMinutes() + " 分钟");
        return result;
    }

    private static DashScopeChatModel newReviewModel(JdkHttpTransport httpTransport, int maxTokens) {
        return DashScopeChatModel.builder()
                .apiKey(ai_key)
                .modelName("qwen-doc-turbo")
                .endpointType(EndpointType.TEXT)
                .formatter(new QwenDocDashScopeChatFormatter())
                .httpTransport(httpTransport)
                .stream(true)
                .defaultOptions(GenerateOptions.builder()
                        .maxTokens(maxTokens)
                        .executionConfig(ExecutionConfig.builder()
                                .timeout(Duration.ofMinutes(20))
                                .maxAttempts(1)
                                .build())
                        .build())
                .build();
    }

    private static OpenAIChatModel newReportModel(JdkHttpTransport httpTransport) {
        return OpenAIChatModel.builder()
                .apiKey(ai_key)
                .modelName("qwen3.8-max")
                .baseUrl(DASH_SCOPE_BASE_URL)
                .endpointPath("/chat/completions")
                .httpTransport(httpTransport)
                .stream(true)
                .generateOptions(GenerateOptions.builder()
                        .maxTokens(8192)
                        .executionConfig(ExecutionConfig.builder()
                                .timeout(Duration.ofMinutes(20))
                                .maxAttempts(1)
                                .build())
                        .build())
                .build();
    }

    private static List<CibDimensionResult> reviewDimensions(
            List<String> pdfUrls,
            JdkHttpTransport httpTransport) {
        ExecutorService executor = Executors.newFixedThreadPool(REVIEW_THREAD_COUNT);
        try {
            List<CompletableFuture<CibDimensionResult>> futures = CibReviewPrompts.DIMENSIONS.stream()
                    .map(dimension -> CompletableFuture.supplyAsync(
                            () -> reviewDimension(dimension, pdfUrls, httpTransport), executor))
                    .toList();
            return futures.stream().map(CompletableFuture::join).toList();
        } finally {
            executor.shutdown();
        }
    }

    private static CibDimensionResult reviewDimension(
            CibReviewPrompts.Dimension dimension,
            List<String> pdfUrls,
            JdkHttpTransport httpTransport) {
        System.out.println("开始专项评审：" + dimension.name());
        ReActAgent agent = ReActAgent.builder()
                .name("cib-" + dimension.code().toLowerCase(Locale.ROOT))
                .sysPrompt(CibReviewPrompts.promptFor(dimension))
                .model(newReviewModel(httpTransport, 4096))
                .build();
        Msg request = Msg.builder()
                .role(MsgRole.USER)
                .textContent("请审查提供的全部投标文件，本次只执行" + dimension.name() + "。")
                .metadata(Map.of(
                        QwenDocDashScopeChatFormatter.DOC_URLS_METADATA_KEY, pdfUrls,
                        QwenDocDashScopeChatFormatter.FILE_PARSING_STRATEGY_METADATA_KEY, "auto"
                ))
                .build();
        try {
            Msg response = agent.call(request).block();
            if (response == null || response.getTextContent().isBlank()) {
                throw new IllegalStateException("专项评审未返回内容");
            }
            CibDimensionResult result = QwenDocResponseParser.parse(
                    response.getTextContent(), CibDimensionResult.class);
            if (!dimension.code().equals(result.getDimension())) {
                throw new IllegalArgumentException("专项结果dimension不匹配: " + result.getDimension());
            }
            result.setSuccess(true);
            result.setErrorMessage(null);
            System.out.println("完成专项评审：" + dimension.name());
            System.out.println("专项JSON[" + dimension.code() + "]: " + toJson(result));
            return result;
        } catch (RuntimeException exception) {
            System.err.println("专项评审失败：" + dimension.name() + "，" + exception.getMessage());
            CibDimensionResult failed = new CibDimensionResult();
            failed.setDimension(dimension.code());
            failed.setSummary("专项评审失败，未获得有效结果");
            failed.setRiskLevel("UNKNOWN");
            failed.setSuccess(false);
            failed.setErrorMessage(exception.getMessage());
            failed.setRecommendations(List.of("重新执行" + dimension.name() + "并人工复核"));
            return failed;
        }
    }

    private static String generateReport(
            List<CibDimensionResult> dimensionResults,
            JdkHttpTransport httpTransport) {
        ReActAgent reportAgent = ReActAgent.builder()
                .name("cib-report")
                .sysPrompt(CibReviewPrompts.REPORT_PROMPT)
                .model(newReportModel(httpTransport))
                .build();
        Msg request = Msg.builder()
                .role(MsgRole.USER)
                .textContent("以下是六个专项Agent的JSON结果，请严格汇总：\n"
                        + toJson(dimensionResults))
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
            throw new IllegalStateException("序列化专项评审结果失败", exception);
        }
    }

    private static void validatePdfUrls(List<String> urls) {
        if (urls == null || urls.isEmpty()) {
            throw new IllegalArgumentException("至少需要传入一个 PDF 文件地址");
        }
        for (String url : urls) {
            if (url == null || url.isBlank()) {
                throw new IllegalArgumentException("PDF 文件地址不能为空");
            }
            URI uri;
            try {
                uri = URI.create(url);
            } catch (IllegalArgumentException exception) {
                throw new IllegalArgumentException("文件地址格式不正确: " + url, exception);
            }
            String scheme = uri.getScheme();
            String path = uri.getPath();
            boolean httpUrl = "http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme);
            if (!httpUrl || path == null || !path.toLowerCase(Locale.ROOT).endsWith(".pdf")) {
                throw new IllegalArgumentException("仅支持 PDF 文件，非法文件地址: " + url);
            }
        }
    }
}
