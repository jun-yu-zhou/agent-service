package com.example.agentservice.config;

import io.majiaoyu.docextract.OcrConfigProvider;
import io.majiaoyu.docextract.QwenOcrClient;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

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
        QwenOcrClient.setConfigProvider(ocrProperties);
        io.majiaoyu.docextract.DocumentTextExtractor.setMaxFileBytes(extractProperties.getMaxFileBytes());
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
}
