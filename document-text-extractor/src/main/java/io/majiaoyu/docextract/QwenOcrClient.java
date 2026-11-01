package io.majiaoyu.docextract;

import com.alibaba.dashscope.aigc.multimodalconversation.MultiModalConversation;
import com.alibaba.dashscope.aigc.multimodalconversation.MultiModalConversationParam;
import com.alibaba.dashscope.aigc.multimodalconversation.MultiModalConversationResult;
import com.alibaba.dashscope.common.MultiModalMessage;
import com.alibaba.dashscope.protocol.ConnectionOptions;

import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 经 DashScope 官方 SDK 的多模态对话接口调用百炼视觉 OCR 模型（默认 qwen3.5-ocr）。
 *
 * <p>配置经 {@link OcrConfigProvider} 注入（默认读系统属性/环境变量）；
 * maxTokens 16384、读超时 5 分钟，语义与原 Tika tika-vlm 的 VLMOCRConfig 对齐。
 * 传输层使用 HTTP/1.1 协议（与 app 内 ModelConfig 的多模态客户端同款）：
 * DashScope 对 HTTP/2 大请求体会重置流。base-url 需为 DashScope 主站地址
 * （原生 multimodal-generation API，非 OpenAI 兼容端点）。</p>
 */
public final class QwenOcrClient {

    /** 整页/图片 OCR 默认提示词；宿主可经 extract 入参覆盖。 */
    public static final String DEFAULT_OCR_PROMPT = """
            请识别图片中的全部文字，并以 Markdown 返回。
            保留原有阅读顺序、标题、段落、列表和表格；不要描述图片，不要补充或猜测未显示的内容。
            """;
    private static final int MAX_COMPLETION_TOKENS = 16_384;
    private static final String PROTOCOL_HTTP_1_1 = "http";

    /**
     * 客户端按 baseUrl 缓存复用：底层 OkHttp 连接池与调度线程池随实例创建且不随调用释放，
     * 每次请求 new 一个会在多页文档/高并发下耗尽文件描述符。apiKey/model 走 param 不在实例上，换配置源无需重建。
     */
    private static final ConcurrentHashMap<String, MultiModalConversation> CLIENTS = new ConcurrentHashMap<>();

    private static volatile OcrConfigProvider configProvider = new SystemEnvOcrConfigProvider();

    private QwenOcrClient() {
    }

    /** 宿主在启动时注入自己的配置源；注入后 SDK 内所有 OCR 调用即时生效。 */
    public static void setConfigProvider(OcrConfigProvider provider) {
        QwenOcrClient.configProvider = Objects.requireNonNull(provider, "provider");
    }

    /** 识别图片中的全部文字；imageFormat 为数据 URI 的 MIME 子类型（png、jpeg 等），
     *  prompt 可按场景定制（整页提取、印章识读各有默认值）。 */
    public static String recognize(byte[] imageBytes, String imageFormat, String prompt) {
        OcrConfigProvider config = configProvider;
        String dataUri = "data:image/" + imageFormat + ";base64,"
                + Base64.getEncoder().encodeToString(imageBytes);
        MultiModalMessage userMessage = MultiModalMessage.builder()
                .role("user")
                .content(List.of(Map.of("image", dataUri), Map.of("text", prompt)))
                .build();
        MultiModalConversationParam param = MultiModalConversationParam.builder()
                .apiKey(config.apiKey())
                .model(config.model())
                .messages(List.of(userMessage))
                .maxLength(MAX_COMPLETION_TOKENS)
                .build();
        MultiModalConversation conversation = clientFor(trimTrailingSlash(config.baseUrl()));
        MultiModalConversationResult result;
        try {
            result = conversation.call(param);
        } catch (Exception exception) {
            throw new IllegalStateException("百炼 OCR 调用失败（模型 " + config.model() + "）"
                    + exception.getMessage(), exception);
        }
        String content = contentText(result);
        if (content.isBlank()) {
            throw new IllegalStateException("百炼 OCR 未返回文字内容（模型 " + config.model() + "）");
        }
        return content;
    }

    /** 同一 baseUrl 复用同一客户端实例；超时参数固定，与既有语义一致。 */
    private static MultiModalConversation clientFor(String baseUrl) {
        return CLIENTS.computeIfAbsent(baseUrl, url -> new MultiModalConversation(
                PROTOCOL_HTTP_1_1,
                url,
                ConnectionOptions.builder()
                        .connectTimeout(Duration.ofSeconds(30))
                        .writeTimeout(Duration.ofMinutes(5))
                        .readTimeout(Duration.ofMinutes(5))
                        .build()));
    }

    /** content 形态兼容：String 直接用；List 取各分段的 text 字段拼接。 */
    private static String contentText(MultiModalConversationResult result) {
        if (result.getOutput() == null || result.getOutput().getChoices() == null
                || result.getOutput().getChoices().isEmpty()
                || result.getOutput().getChoices().get(0).getMessage() == null) {
            return "";
        }
        Object content = result.getOutput().getChoices().get(0).getMessage().getContent();
        if (content == null) {
            return "";
        }
        if (content instanceof String text) {
            return text;
        }
        if (content instanceof List<?> parts) {
            StringBuilder text = new StringBuilder();
            for (Object part : parts) {
                if (part instanceof Map<?, ?> map && map.get("text") != null) {
                    text.append(map.get("text"));
                }
            }
            return text.toString();
        }
        return String.valueOf(content);
    }

    private static String trimTrailingSlash(String baseUrl) {
        return baseUrl.replaceAll("/$", "");
    }
}
