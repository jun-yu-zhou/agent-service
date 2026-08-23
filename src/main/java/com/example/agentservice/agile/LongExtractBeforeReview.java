package com.example.agentservice.agile;

import com.example.agentservice.entity.LongExtractReviewResult;
import com.example.agentservice.entity.LongFactExtractionResult;
import com.example.agentservice.formatter.QwenLongChatFormatter;
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

public class LongExtractBeforeReview {

    private static final String AI_KEY = "DASHSCOPE_API_KEY";
    private static final String DASH_SCOPE_BASE_URL = "https://dashscope.aliyuncs.com/compatible-mode/v1";
    private static final int THREAD_COUNT = 6;
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final HttpClient FILE_DOWNLOAD_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .build();

    private static final List<String> PDF_URLS = List.of(
            "https://javawebemp.oss-cn-beijing.aliyuncs.com/Scan-%E5%AE%9C%E6%98%8C%E5%87%A0%E4%BD%95%E6%B5%8B%E7%BB%98%E7%A7%91%E6%8A%80%E6%9C%89%E9%99%90%E5%85%AC%E5%8F%B88960772218806061282.pdf",
            "https://javawebemp.oss-cn-beijing.aliyuncs.com/%E4%BA%8C%E8%BD%AE%E6%8A%A5%E4%BB%B7%281%29-%E5%AE%9C%E6%98%8C%E5%87%A0%E4%BD%95%E6%B5%8B%E7%BB%98%E7%A7%91%E6%8A%80%E6%9C%89%E9%99%90%E5%85%AC%E5%8F%B81732505438540525103.pdf",
            "https://javawebemp.oss-cn-beijing.aliyuncs.com/%E4%B8%89%E5%B3%A1%E5%A4%A7%E5%AD%A6%E6%96%B0%E5%BB%BA%E5%AD%A6%E7%94%9F%E5%85%AC%E5%AF%93%E9%A1%B9%E7%9B%AE%E5%A4%9A%E6%B5%8B%E5%90%88%E4%B8%80%E7%AB%9E%E4%BA%89%E6%80%A7%E7%A3%8B%E5%95%86%E5%93%8D%E5%BA%94%E6%96%87%E4%BB%B6%EF%BC%88%E6%8A%95%E6%A0%87%E6%96%87%E4%BB%B6%EF%BC%89--%E6%B9%96%E5%8C%97%E6%8D%B7%E5%B8%86%E7%A7%91%E6%8A%80%E6%9C%89%E9%99%90%E5%85%AC%E5%8F%B8-%E6%B9%96%E5%8C%97%E6%8D%B7%E5%B8%86%E7%A7%91%E6%8A%80%E6%9C%89%E9%99%90%E5%85%AC%E5%8F%B85201011215828763487.pdf",
            "https://javawebemp.oss-cn-beijing.aliyuncs.com/2%E8%BD%AE%E6%8A%A5%E4%BB%B7--%E4%B8%89%E5%B3%A1%E5%A4%A7%E5%AD%A6%E6%96%B0%E5%BB%BA%E5%AD%A6%E7%94%9F%E5%85%AC%E5%AF%93%E9%A1%B9%E7%9B%AE%E5%A4%9A%E6%B5%8B%E5%90%88%E4%B8%80-%E6%B9%96%E5%8C%97%E6%8D%B7%E5%B8%86%E7%A7%91%E6%8A%80%E6%9C%89%E9%99%90%E5%85%AC%E5%8F%B83512886208567055417.pdf",
            "https://javawebemp.oss-cn-beijing.aliyuncs.com/%E5%8B%98%E6%B5%8B%E9%99%A2%E6%8A%95%E6%A0%87%E6%96%87%E4%BB%B6-%E5%AE%9C%E6%98%8C%E5%B8%82%E5%8B%98%E5%AF%9F%E6%B5%8B%E7%BB%98%E7%A0%94%E7%A9%B6%E9%99%A2%E6%9C%89%E9%99%90%E5%85%AC%E5%8F%B84754786566717863762.pdf");

    private static final List<ExtractDimension> DIMENSIONS = List.of(
            new ExtractDimension("BASIC_INFO", "基础信息与报价事实", true,
                    "只从当前这一份文件提取投标人身份、联系方式、银行账户、报价轮次和报价总价。严格区分首次报价、二次/最终报价、采购预算、最高限价、分项金额、保证金和报价得分。"),
            new ExtractDimension("LAYOUT", "排版结构事实", false,
                    "从全部文件提取可由文本确认的目录、章节、标题编号、表格结构、固定段落位置和异常空白事实，不评价风险。"),
            new ExtractDimension("PAGE_NUMBER", "页码事实", false,
                    "只从全部文件提取页码缺失、重复、跳号、错号等异常事实。正常连续页码、索引表中普通的页码数字和每页的正常页码不作为候选事实；没有异常时candidates必须为空。每个异常只保留一次。"),
            new ExtractDimension("TEXT_SIMILARITY", "文本相似事实", false,
                    "从全部文件提取自由编写文本中可直接引用的相同连续句子、相同非通用短语和对应位置，不输出相似度结论。"),
            new ExtractDimension("TYPO_SIMILARITY", "错别字事实", false,
                    "从全部文件提取双方都出现的同一个错误字符串及其位置。一个正确一个错误、两个不同标准号或无法直接引用的内容不得作为候选事实。")
    );

    public static void main(String[] args) {
        LongExtractReviewResult result = execute(PDF_URLS);
        System.out.println(result.getMarkdownReport());
    }

    public static LongExtractReviewResult execute(List<String> pdfUrls) {
        validatePdfUrls(pdfUrls);
        LocalDateTime start = LocalDateTime.now();
        List<String> fileIds = uploadPdfFiles(pdfUrls);
        JdkHttpTransport httpTransport = newHttpTransport();

        System.out.println("阶段1/2：Qwen-Long候选事实抽取，停用COLLUSION_RISK...");
        List<LongFactExtractionResult> extractionResults = extractFacts(pdfUrls, fileIds, httpTransport);
        System.out.println("阶段2/2：Qwen3.8-Max根据候选事实生成最终报告...");
        String report = generateReport(extractionResults, httpTransport);

        LongExtractReviewResult result = new LongExtractReviewResult();
        result.setExtractionResults(extractionResults);
        result.setMarkdownReport(report);
        System.out.println("实验耗时：" + Duration.between(start, LocalDateTime.now()).toMinutes() + " 分钟");
        return result;
    }

    private static List<LongFactExtractionResult> extractFacts(
            List<String> pdfUrls,
            List<String> fileIds,
            JdkHttpTransport httpTransport) {
        ExecutorService executor = Executors.newFixedThreadPool(THREAD_COUNT);
        try {
            List<CompletableFuture<LongFactExtractionResult>> futures = new ArrayList<>();
            for (ExtractDimension dimension : DIMENSIONS) {
                if (dimension.singleFile()) {
                    for (int index = 0; index < pdfUrls.size(); index++) {
                        int fileIndex = index;
                        futures.add(CompletableFuture.supplyAsync(
                                () -> extract(dimension, pdfUrls, fileIds, fileIndex, httpTransport), executor));
                    }
                } else {
                    futures.add(CompletableFuture.supplyAsync(
                            () -> extract(dimension, pdfUrls, fileIds, -1, httpTransport), executor));
                }
            }
            return futures.stream().map(CompletableFuture::join).toList();
        } finally {
            executor.shutdown();
        }
    }

    private static LongFactExtractionResult extract(
            ExtractDimension dimension,
            List<String> pdfUrls,
            List<String> fileIds,
            int singleFileIndex,
            JdkHttpTransport httpTransport) {
        List<String> selectedFileIds = singleFileIndex >= 0
                ? List.of(fileIds.get(singleFileIndex))
                : fileIds;
        String source = singleFileIndex >= 0 ? pdfUrls.get(singleFileIndex) : null;
        ReActAgent agent = ReActAgent.builder()
                .name("long-extract-" + dimension.code().toLowerCase(Locale.ROOT)
                        + (singleFileIndex >= 0 ? "-" + singleFileIndex : ""))
                .sysPrompt(extractionPrompt(dimension, singleFileIndex >= 0))
                .model(newLongModel(httpTransport))
                .build();
        Msg request = Msg.builder()
                .role(MsgRole.USER)
                .textContent(extractionRequest(dimension, pdfUrls, source))
                .metadata(Map.of(QwenLongChatFormatter.FILE_IDS_METADATA_KEY, selectedFileIds))
                .build();
        String responseText = null;
        try {
            Msg response = agent.call(request).block();
            if (response == null) {
                throw new IllegalStateException("Long事实抽取未返回内容");
            }
            responseText = response.getTextContent();
            if (responseText == null || responseText.isBlank()) {
                throw new IllegalStateException("Long事实抽取未返回内容");
            }
            LongFactExtractionResult result = QwenDocResponseParser.parse(
                    responseText, LongFactExtractionResult.class);
            result.setSuccess(true);
            result.setErrorMessage(null);
            System.out.println("完成Long抽取：" + dimension.code()
                    + (source == null ? "" : "，单文件" + source));
            System.out.println("Long原始响应[" + dimension.code() + "]: " + responseText);
            System.out.println("LongJSON[" + dimension.code() + "]: " + toJson(result));
            return result;
        } catch (RuntimeException exception) {
            LongFactExtractionResult failed = new LongFactExtractionResult();
            failed.setDimension(dimension.code());
            failed.setSourceDocument(source);
            failed.setSuccess(false);
            failed.setErrorMessage(exception.getMessage());
            if (responseText != null && !responseText.isBlank()) {
                System.err.println("Long失败原始响应[" + dimension.code() + "]: " + responseText);
            }
            System.err.println("Long抽取失败：" + dimension.code()
                    + "，已保留失败结果并在全部抽取完成后进入报告阶段：" + exception.getMessage());
            return failed;
        }
    }

    private static String generateReport(
            List<LongFactExtractionResult> extractionResults,
            JdkHttpTransport httpTransport) {
        ReActAgent agent = ReActAgent.builder()
                .name("validated-fact-report")
                .sysPrompt(REPORT_PROMPT)
                .model(newTextModel(httpTransport))
                .build();
        Msg request = Msg.builder()
                .role(MsgRole.USER)
                .textContent("以下是Qwen-Long候选事实JSON。报告阶段只能使用其中success=true且证据字段完整的候选事实：\n"
                        + toJson(extractionResults))
                .build();
        Msg response = agent.call(request).block();
        if (response == null || response.getTextContent().isBlank()) {
            throw new IllegalStateException("报告Agent未返回内容");
        }
        return response.getTextContent();
    }

    private static DashScopeChatModel newLongModel(JdkHttpTransport httpTransport) {
        return DashScopeChatModel.builder()
                .apiKey(AI_KEY)
                .modelName("qwen-long")
                .endpointType(EndpointType.TEXT)
                .formatter(new QwenLongChatFormatter())
                .httpTransport(httpTransport)
                .stream(true)
                .defaultOptions(GenerateOptions.builder()
                        .maxTokens(4096)
                        .temperature(0.2D)
                        .executionConfig(ExecutionConfig.builder()
                                .timeout(Duration.ofMinutes(20))
                                .maxAttempts(1)
                                .build())
                        .build())
                .build();
    }

    private static OpenAIChatModel newTextModel(JdkHttpTransport httpTransport) {
        return OpenAIChatModel.builder()
                .apiKey(AI_KEY)
                .modelName("qwen3.8-max")
                .baseUrl(DASH_SCOPE_BASE_URL)
                .endpointPath("/chat/completions")
                .httpTransport(httpTransport)
                .stream(true)
                .generateOptions(GenerateOptions.builder()
                        .maxTokens(4096)
                        .reasoningEffort("low")
                        .temperature(0.15D)
                        .executionConfig(ExecutionConfig.builder()
                                .timeout(Duration.ofMinutes(20))
                                .maxAttempts(1)
                                .build())
                        .build())
                .build();
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

    private static String extractionRequest(
            ExtractDimension dimension,
            List<String> pdfUrls,
            String source) {
        if (source != null) {
            return "只处理这一份文件，文件标识为：" + source + "。本次任务：" + dimension.name()
                    + "。不要引用其他文件，也不要把文件名中的数字当作事实。";
        }
        return "请先逐份识别以下已上传文件，再执行本次任务：" + dimension.name()
                + "。文件清单如下：\n" + numberedUrls(pdfUrls)
                + "\n不能确认来源的事实必须将证据字段置为null。";
    }

    private static String extractionPrompt(ExtractDimension dimension, boolean singleFile) {
        String scope = singleFile
                ? "本次只允许读取并抽取当前请求指定的单份文件。"
                : "本次允许读取全部已上传文件，但每条事实必须标明所属文件。";
        String schema = """
                {"$schema":"https://json-schema.org/draft/2020-12/schema","type":"object","additionalProperties":false,
                "required":["dimension","sourceDocument","candidates","success","errorMessage"],
                "properties":{"dimension":{"type":"string","const":"%s"},"sourceDocument":{"type":["string","null"]},
                "candidates":{"type":"array","maxItems":40,"items":{"type":"object","additionalProperties":false,
                "required":["factType","bidder","value","document","page","location","excerpt"],
                "properties":{"factType":{"type":"string"},"bidder":{"type":["string","null"]},"value":{"type":["string","null"]},
                "document":{"type":["string","null"]},"page":{"type":["integer","null"]},"location":{"type":["string","null"]},"excerpt":{"type":["string","null"]}}}},
                "success":{"type":"boolean"},"errorMessage":{"type":["string","null"]}}}
                """.formatted(dimension.code()).replaceAll("\\s+", "");
        return """
                你是政府采购文件的候选事实抽取器，不是风险评审员。
                本次维度：%s。任务说明：%s
                %s
                只输出候选事实，不输出风险等级、结论、相似度、法律依据、建议或综合判断。
                只抽取“%s”这一维度的事实；其他维度的内容一律不要输出。
                关键事实必须同时包含document、page或location、excerpt。没有直接证据时，相关字段必须为null；禁止只输出裸数字、金额或页码。
                excerpt必须是文件中的连续原文短摘录，不能由你改写；document必须是文件名或请求提供的文件标识。
                不同文件之间不能互相补全缺失字段。单文件模式下禁止引用其他文件。
                每个候选事实只输出一次，完全相同的factType、bidder、value、document、location和excerpt必须去重；最终只能输出一个JSON根对象。
                输出一个符合以下JSON Schema的对象，不要输出Markdown、代码围栏或解释：
                %s
                """.formatted(dimension.name(), dimension.focus(), scope, dimension.name(), schema);
    }

    private static String numberedUrls(List<String> urls) {
        StringBuilder result = new StringBuilder();
        for (int index = 0; index < urls.size(); index++) {
            result.append(index + 1).append(". ").append(urls.get(index)).append('\n');
        }
        return result.toString();
    }

    private static String toJson(Object value) {
        try {
            return OBJECT_MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("序列化JSON失败", exception);
        }
    }

    private static List<String> uploadPdfFiles(List<String> urls) {
        OpenAIClient fileClient = OpenAIOkHttpClient.builder()
                .apiKey(AI_KEY)
                .baseUrl(DASH_SCOPE_BASE_URL)
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
            throw new IllegalStateException("上传PDF被中断", exception);
        } catch (Exception exception) {
            throw new IllegalStateException("上传PDF到qwen-long失败", exception);
        } finally {
            fileClient.close();
        }
    }

    private static Path downloadPdf(String url) throws Exception {
        Path temporaryPdf = Files.createTempFile("long-extract-", ".pdf");
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofMinutes(10))
                .GET()
                .build();
        HttpResponse<Path> response = FILE_DOWNLOAD_CLIENT.send(
                request,
                HttpResponse.BodyHandlers.ofFile(temporaryPdf));
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            Files.deleteIfExists(temporaryPdf);
            throw new IllegalStateException("下载PDF失败，HTTP状态码: " + response.statusCode());
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
                throw new IllegalStateException("百炼解析PDF失败: " + url
                        + file.statusDetails().map(detail -> "，" + detail).orElse(""));
            }
            Thread.sleep(Duration.ofSeconds(2).toMillis());
        }
        throw new IllegalStateException("等待百炼解析PDF超时: " + url);
    }

    private static void validatePdfUrls(List<String> urls) {
        if (urls == null || urls.isEmpty()) {
            throw new IllegalArgumentException("至少需要传入一个PDF文件地址");
        }
        for (String url : urls) {
            if (url == null || url.isBlank()) {
                throw new IllegalArgumentException("PDF文件地址不能为空");
            }
            URI uri = URI.create(url);
            String scheme = uri.getScheme();
            String path = uri.getPath();
            boolean httpUrl = "http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme);
            if (!httpUrl || path == null || !path.toLowerCase(Locale.ROOT).endsWith(".pdf")) {
                throw new IllegalArgumentException("仅支持PDF文件，非法文件地址: " + url);
            }
        }
    }

    private record ExtractDimension(String code, String name, boolean singleFile, String focus) {
    }

    private static final String REPORT_PROMPT = """
            你是政府采购投标文件审查报告编写专家。输入是Qwen-Long的候选事实抽取结果。
            只使用success=true且document、page或location、excerpt完整的候选事实；success=false的抽取结果只说明该维度无法核验，不能从errorMessage推导事实。
            document、page/location、excerpt任一缺失，或excerpt不能直接支持value的候选事实必须忽略，不得写进异常结论。
            不能补充任何原始文件事实，不能把候选事实直接定性为围标串标；只能使用“证据显示/存在核查线索/需进一步核查”等谨慎表述。
            可以在报告中进行风险归纳，但必须使用谨慎表述“证据显示/需进一步核查”，不得把事实验证结果直接定性为围标串标。
            只输出Markdown，不输出JSON、代码块、分析过程或开场白。
            严格输出以下八张表，每张表至少一行；没有对应事实时填写“未识别”或“未发现明确异常”。

            ## 一、投标人基本情况
            | 序号 | 投标人名称 | 首次报价（元） | 二次/最终报价（元） | 降幅 |

            ## 二、异常审查结果
            | 序号 | 投标人 | 风险等级 | 风险类型 | 证据 | 法律依据 | 置信度 |

            ## 三、报价异常专项分析
            | 分项项目 | 投标人1（元） | 投标人2（元） | | ...投标人N | 报价分布特征 |

            ## 四、文件雷同专项分析
            | 雷同特征 | 涉及投标人 | 具体描述 | 置信度 |

            ## 五、分项风险汇总表
            | 风险类型 | 投标人 | 风险等级 | 具体说明 |

            ## 六、法律条款依据摘要
            | 法律文件 | 条款 | 内容摘要 | 适用情形 |

            ## 七、建议处理措施
            | 序号 | 处理建议 | 优先级别 |

            ## 八、整体风险评级
            | 评级维度 | 评级 | 说明 |

            八张表之后只追加一段以“报告说明：”开头的文字，说明本报告仅基于Qwen-Long返回且证据字段完整的候选事实，不能替代人工调查和最终认定。
            """;
}
