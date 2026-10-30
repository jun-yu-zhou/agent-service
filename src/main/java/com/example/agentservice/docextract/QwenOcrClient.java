package com.example.agentservice.docextract;

import com.example.agentservice.config.AgentServiceConfig;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Base64;

/**
 * 通过 OpenAI 兼容协议调用百炼视觉 OCR 模型（默认 qwen3.5-ocr），输入单张图片，输出 Markdown 全文。
 *
 * <p>端点、模型与密钥复用 app.dashscope.ocr-base-url / ocr-model / api-key 配置，
 * 语义与原 Tika tika-vlm 的 VLMOCRConfig 对齐：maxTokens 16384、超时 5 分钟。
 * 传输层显式使用 HTTP/1.1：DashScope 兼容端点对 HTTP/2 大请求体会重置流（StreamResetException）。</p>
 */
public final class QwenOcrClient {

    private static final String OCR_PROMPT = """
            请识别图片中的全部文字，并以 Markdown 返回。
            保留原有阅读顺序、标题、段落、列表和表格；不要描述图片，不要补充或猜测未显示的内容。
            """;
    private static final long MAX_COMPLETION_TOKENS = 16_384L;
    private static final Duration REQUEST_TIMEOUT = Duration.ofMinutes(5);

    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(60))
            .build();

    private QwenOcrClient() {
    }

    /** 识别图片中的全部文字；imageFormat 为数据 URI 的 MIME 子类型（png、jpeg 等）。 */
    public static String recognize(byte[] imageBytes, String imageFormat) {
        return recognize(imageBytes, imageFormat, OCR_PROMPT);
    }

    /** 同上，允许调用方按场景定制提示词（如印章环形文字）。 */
    public static String recognize(byte[] imageBytes, String imageFormat, String prompt) {
        JsonObject response = send(request(imageBytes, imageFormat, prompt));
        return contentOf(response);
    }

    private static byte[] request(byte[] imageBytes, String imageFormat, String prompt) {
        String dataUri = "data:image/" + imageFormat + ";base64,"
                + Base64.getEncoder().encodeToString(imageBytes);
        JsonObject imageUrl = new JsonObject();
        imageUrl.addProperty("url", dataUri);
        JsonObject imagePart = new JsonObject();
        imagePart.addProperty("type", "image_url");
        imagePart.add("image_url", imageUrl);

        JsonObject textPart = new JsonObject();
        textPart.addProperty("type", "text");
        textPart.addProperty("text", prompt);

        JsonArray content = new JsonArray();
        content.add(imagePart);
        content.add(textPart);

        JsonObject userMessage = new JsonObject();
        userMessage.addProperty("role", "user");
        userMessage.add("content", content);

        JsonArray messages = new JsonArray();
        messages.add(userMessage);

        JsonObject body = new JsonObject();
        body.addProperty("model", AgentServiceConfig.tikaOcrModel());
        body.addProperty("max_tokens", MAX_COMPLETION_TOKENS);
        body.add("messages", messages);
        return body.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    private static JsonObject send(byte[] bodyBytes) {
        String baseUrl = AgentServiceConfig.tikaOcrBaseUrl();
        // 兼容端点完整路径为 {base}/v1/chat/completions；base 以 /v1 结尾时不再重复拼接。
        String chatCompletionsUrl = baseUrl.replaceAll("/$", "")
                + (baseUrl.endsWith("/v1") ? "/chat/completions" : "/v1/chat/completions");
        HttpRequest request = HttpRequest.newBuilder(URI.create(chatCompletionsUrl))
                .timeout(REQUEST_TIMEOUT)
                .header("Authorization", "Bearer " + AgentServiceConfig.dashScopeApiKey())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(bodyBytes))
                .build();
        HttpResponse<String> response;
        try {
            response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException exception) {
            throw new IllegalStateException("百炼 OCR 请求失败（模型 " + AgentServiceConfig.tikaOcrModel() + "）", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("百炼 OCR 请求被中断", exception);
        }
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("百炼 OCR 返回 HTTP " + response.statusCode()
                    + "：" + snippet(response.body()));
        }
        return JsonParser.parseString(response.body()).getAsJsonObject();
    }

    private static String contentOf(JsonObject response) {
        JsonArray choices = response.getAsJsonArray("choices");
        if (choices == null || choices.isEmpty()) {
            throw new IllegalStateException("百炼 OCR 未返回 choices：" + snippet(response.toString()));
        }
        String content = choices.get(0).getAsJsonObject()
                .getAsJsonObject("message")
                .get("content").getAsString();
        if (content == null || content.isBlank()) {
            throw new IllegalStateException("百炼 OCR 未返回文字内容：" + snippet(response.toString()));
        }
        return content;
    }

    private static String snippet(String text) {
        String trimmed = text == null ? "" : text.strip();
        return trimmed.length() <= 500 ? trimmed : trimmed.substring(0, 500) + "...";
    }
}
