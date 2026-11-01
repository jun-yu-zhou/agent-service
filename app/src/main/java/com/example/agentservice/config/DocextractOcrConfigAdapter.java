package com.example.agentservice.config;

import io.majiaoyu.docextract.DocumentTextExtractor;
import io.majiaoyu.docextract.OcrConfigProvider;
import io.majiaoyu.docextract.QwenOcrClient;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/** 把 yml 里的 docextract.* 配置注入 docextract-sdk；应用启动即装配。 */
@Component
public class DocextractOcrConfigAdapter {

    private final DocextractOcrProperties ocrProperties;
    private final DocextractExtractProperties extractProperties;

    public DocextractOcrConfigAdapter(DocextractOcrProperties ocrProperties,
                                      DocextractExtractProperties extractProperties) {
        this.ocrProperties = ocrProperties;
        this.extractProperties = extractProperties;
    }

    /** Spring 生命周期入口；测试等无容器场景请直接调 {@link #installNow()}。 */
    @PostConstruct
    public void install() {
        // baseUrl/model/apiKey 三项必填：模型可能下架、端点可能变更，SDK 不设隐式默认，缺失在启动时快速失败。
        List<String> missing = new ArrayList<>();
        if (isBlank(ocrProperties.baseUrl())) {
            missing.add("docextract.ocr.base-url");
        }
        if (isBlank(ocrProperties.apiKey())) {
            missing.add("docextract.ocr.api-key");
        }
        if (isBlank(ocrProperties.model())) {
            missing.add("docextract.ocr.model");
        }
        if (!missing.isEmpty()) {
            throw new IllegalStateException("docextract-sdk 缺少必要配置 " + missing
                    + "（模型可能下架、端点可能变更，须在 yml 显式声明）");
        }
        QwenOcrClient.setConfigProvider(ocrProperties);
        DocumentTextExtractor.setMaxFileBytes(extractProperties.getMaxFileBytes());
    }

    /** 无 Spring 容器（如纯 JUnit）时的装配入口：读 application.yml 与 application-local.yml 的既有键。 */
    public static void installNow() {
        QwenOcrClient.setConfigProvider(new OcrConfigProvider() {
            @Override
            public String baseUrl() {
                return AgentServiceConfig.docextractOcrBaseUrl();
            }

            @Override
            public String apiKey() {
                // 密钥单一来源仍是 application-local.yml 的 app.dashscope.api-key。
                return AgentServiceConfig.dashScopeApiKey();
            }

            @Override
            public String model() {
                return AgentServiceConfig.docextractOcrModel();
            }
        });
        // 文件大小上限走 SDK 默认（100MB）；如需调整请配置 docextract.extract.max-file-bytes 并走 Spring 路径。
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
