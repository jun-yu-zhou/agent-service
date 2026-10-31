package io.majiaoyu.docextract;

/** OCR 端点配置 SPI：宿主按自身配置源实现；未注入时 SDK 走 {@link SystemEnvOcrConfigProvider}。 */
public interface OcrConfigProvider {

    /** DashScope OpenAI 兼容端点根地址，如 https://dashscope.aliyuncs.com/compatible-mode。 */
    String baseUrl();

    /** API Key。 */
    String apiKey();

    /** 视觉 OCR 模型名，如 qwen3.5-ocr。 */
    String model();
}
