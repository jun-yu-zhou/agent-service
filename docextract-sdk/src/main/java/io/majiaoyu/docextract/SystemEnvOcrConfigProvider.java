package io.majiaoyu.docextract;

/** 默认配置实现：系统属性优先，其次环境变量；键名沿用 agent-service 既有约定。 */
public class SystemEnvOcrConfigProvider implements OcrConfigProvider {

    @Override
    public String baseUrl() {
        return value("DASHSCOPE_OCR_BASE_URL", "https://dashscope.aliyuncs.com/compatible-mode");
    }

    @Override
    public String apiKey() {
        String key = value("DASHSCOPE_API_KEY", "");
        if (key.isBlank()) {
            throw new IllegalStateException("缺少 DASHSCOPE_API_KEY（系统属性或环境变量），无法执行视觉 OCR");
        }
        return key;
    }

    @Override
    public String model() {
        return value("DASHSCOPE_OCR_MODEL", "qwen3.5-ocr");
    }

    private static String value(String key, String defaultValue) {
        String systemValue = System.getProperty(key);
        if (systemValue != null && !systemValue.isBlank()) {
            return systemValue;
        }
        String environmentValue = System.getenv(key);
        return environmentValue == null || environmentValue.isBlank() ? defaultValue : environmentValue;
    }
}
