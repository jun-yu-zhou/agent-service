package io.majiaoyu.docextract;

/** 默认配置实现：系统属性优先，其次环境变量；baseUrl/apiKey/model 三项均为必填（SDK 不设隐式默认）。 */
public class SystemEnvOcrConfigProvider implements OcrConfigProvider {

    @Override
    public String baseUrl() {
        // 必填：模型可能下架、端点可能变更，SDK 不设隐式默认，须显式声明。
        return required("DASHSCOPE_OCR_BASE_URL");
    }

    @Override
    public String apiKey() {
        return required("DASHSCOPE_API_KEY");
    }

    @Override
    public String model() {
        return required("DASHSCOPE_OCR_MODEL");
    }

    private static String required(String key) {
        String value = value(key, "");
        if (value.isBlank()) {
            throw new IllegalStateException("缺少 OCR 端点配置 " + key
                    + "（系统属性或环境变量）；模型可能下架、端点可能变更，须显式声明");
        }
        return value;
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
