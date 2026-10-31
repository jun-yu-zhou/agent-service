package com.example.agentservice.config;

import io.majiaoyu.docextract.OcrConfigProvider;
import io.majiaoyu.docextract.QwenOcrClient;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

/** 把 yml 里的 docextract.ocr.* 配置注入 docextract-sdk；应用启动即装配。 */
@Component
public class DocextractOcrConfigAdapter {

    private final DocextractOcrProperties properties;

    public DocextractOcrConfigAdapter(DocextractOcrProperties properties) {
        this.properties = properties;
    }

    /** Spring 生命周期入口；测试等无容器场景请直接调 {@link #installNow()}。 */
    @PostConstruct
    public void install() {
        QwenOcrClient.setConfigProvider(properties);
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
    }
}
