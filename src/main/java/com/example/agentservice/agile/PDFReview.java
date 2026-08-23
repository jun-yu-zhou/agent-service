package com.example.agentservice.agile;

import com.example.agentservice.entity.CibDimensionResult;
import com.example.agentservice.entity.CibReviewResult;
import com.example.agentservice.formatter.QwenLongChatFormatter;
import com.example.agentservice.prompts.CibReviewPrompts;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.models.files.FileCreateParams;
import com.openai.models.files.FileObject;
import com.openai.models.files.FilePurpose;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.model.*;
import io.agentscope.core.model.transport.HttpTransportConfig;
import io.agentscope.core.model.transport.JdkHttpTransport;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class PDFReview {
    private static final String QWEN_LONG_BASE_URL = "https://dashscope.aliyuncs.com/compatible-mode/v1";
    private static final int MODEL_PARSING_RETRIES = 6;
    private static final int REVIEW_THREAD_COUNT = 6;
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final HttpClient FILE_DOWNLOAD_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .build();

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
        List<String> fileIds = uploadPdfFiles(pdfUrls);

        JdkHttpTransport httpTransport = JdkHttpTransport.builder()
                .config(HttpTransportConfig.builder()
                        .connectTimeout(Duration.ofSeconds(30))
                        .readTimeout(Duration.ofMinutes(20))
                        .writeTimeout(Duration.ofMinutes(2))
                        .build())
                .build();

        System.out.println("已上传并解析 " + pdfUrls.size() + " 份PDF，开始并发执行6个专项评审...");
        List<CibDimensionResult> dimensionResults = reviewDimensions(fileIds, httpTransport);
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
                .apiKey(com.example.agentservice.config.AgentServiceConfig.dashScopeApiKey())
                .modelName("qwen-long")
                .endpointType(EndpointType.TEXT)
                .formatter(new QwenLongChatFormatter())
                .httpTransport(httpTransport)
                .stream(true)
                .defaultOptions(GenerateOptions.builder()
                        .maxTokens(maxTokens)
                        .temperature(0.2D)
                        .executionConfig(ExecutionConfig.builder()
                                .timeout(Duration.ofMinutes(20))
                                .maxAttempts(1)
                                .build())
                        .build())
                .build();
    }

    private static OpenAIChatModel newReportModel(JdkHttpTransport httpTransport) {
        return OpenAIChatModel.builder()
                .apiKey(com.example.agentservice.config.AgentServiceConfig.dashScopeApiKey())
                .modelName("qwen3.8-max")
                .baseUrl(QWEN_LONG_BASE_URL)
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
                .build();
    }

    private static List<CibDimensionResult> reviewDimensions(
            List<String> fileIds,
            JdkHttpTransport httpTransport) {
        ExecutorService executor = Executors.newFixedThreadPool(REVIEW_THREAD_COUNT);
        try {
            List<CompletableFuture<CibDimensionResult>> futures = CibReviewPrompts.DIMENSIONS.stream()
                    .map(dimension -> CompletableFuture.supplyAsync(
                            () -> reviewDimension(dimension, fileIds, httpTransport), executor))
                    .toList();
            return futures.stream().map(CompletableFuture::join).toList();
        } finally {
            executor.shutdown();
        }
    }

    private static CibDimensionResult reviewDimension(
            CibReviewPrompts.Dimension dimension,
            List<String> fileIds,
            JdkHttpTransport httpTransport) {
        System.out.println("开始专项评审：" + dimension.name());
        ReActAgent agent = ReActAgent.builder()
                .name("cib-" + dimension.code().toLowerCase(Locale.ROOT))
                .sysPrompt(CibReviewPrompts.promptFor(dimension))
                .model(newReviewModel(httpTransport, 4096))
                .build();
        Msg request = Msg.builder()
                .role(MsgRole.USER)
                .textContent("请先完整阅读全部已上传投标文件，再执行本次唯一专项："
                        + dimension.name() + "。不要提前结束，也不要用文件名推断未在正文确认的事实。")
                .metadata(Map.of(QwenLongChatFormatter.FILE_IDS_METADATA_KEY, fileIds))
                .build();
        try {
            Msg response = callReview(agent, request);
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

    private static Msg callReview(ReActAgent agent, Msg request) {
        for (int attempt = 1; attempt <= MODEL_PARSING_RETRIES; attempt++) {
            try {
                return agent.call(request).block();
            } catch (RuntimeException exception) {
                if (!containsMessage(exception, "file parsing in progress")
                        || attempt == MODEL_PARSING_RETRIES) {
                    throw exception;
                }
                System.out.println("模型侧尚未同步文件解析结果，10秒后重试...");
                try {
                    Thread.sleep(Duration.ofSeconds(10).toMillis());
                } catch (InterruptedException interruptedException) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("等待文件解析时被中断", interruptedException);
                }
            }
        }
        throw new IllegalStateException("文件解析重试次数已耗尽");
    }

    private static boolean containsMessage(Throwable throwable, String expected) {
        for (Throwable current = throwable; current != null; current = current.getCause()) {
            if (current.getMessage() != null
                    && current.getMessage().toLowerCase(Locale.ROOT).contains(expected)) {
                return true;
            }
        }
        return false;
    }

    private static List<String> uploadPdfFiles(List<String> urls) {
        OpenAIClient fileClient = OpenAIOkHttpClient.builder()
                .apiKey(com.example.agentservice.config.AgentServiceConfig.dashScopeApiKey())
                .baseUrl(QWEN_LONG_BASE_URL)
                .build();
        try {
            List<String> fileIds = new ArrayList<>(urls.size());
            for (String url : urls) {
                Path temporaryPdf = downloadPdf(url);
                try {
                    FileObject file = fileClient.files().create(FileCreateParams.builder()
                            .file(temporaryPdf)
                            .purpose(FilePurpose.of("file-extract"))
                            .build());
                    waitUntilProcessed(fileClient, file.id(), url);
                    fileIds.add(file.id());
                } finally {
                    Files.deleteIfExists(temporaryPdf);
                }
            }
            return fileIds;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("上传 PDF 被中断", exception);
        } catch (Exception exception) {
            throw new IllegalStateException("上传 PDF 到 qwen-long 失败", exception);
        } finally {
            fileClient.close();
        }
    }

    private static Path downloadPdf(String url) throws Exception {
        Path temporaryPdf = Files.createTempFile("pdf-review-", ".pdf");
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofMinutes(10))
                .GET()
                .build();
        HttpResponse<Path> response = FILE_DOWNLOAD_CLIENT.send(
                request,
                HttpResponse.BodyHandlers.ofFile(temporaryPdf));
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            Files.deleteIfExists(temporaryPdf);
            throw new IllegalStateException("下载 PDF 失败，HTTP 状态码: " + response.statusCode());
        }
        return temporaryPdf;
    }

    private static void waitUntilProcessed(OpenAIClient fileClient, String fileId, String url)
            throws InterruptedException {
        for (int attempt = 0; attempt < 120; attempt++) {
            FileObject file = fileClient.files().retrieve(fileId);
            String status = file.status().asString();
            if ("processed".equalsIgnoreCase(status)) {
                return;
            }
            if ("error".equalsIgnoreCase(status)) {
                throw new IllegalStateException("百炼解析 PDF 失败: " + url
                        + file.statusDetails().map(detail -> "，" + detail).orElse(""));
            }
            Thread.sleep(Duration.ofSeconds(2).toMillis());
        }
        throw new IllegalStateException("等待百炼解析 PDF 超时: " + url);
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
