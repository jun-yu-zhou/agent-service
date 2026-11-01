package com.example.agentservice.config;

import io.majiaoyu.docextract.DocumentTextExtractor;
import io.majiaoyu.docextract.OcrConfigProvider;
import io.majiaoyu.docextract.QwenOcrClient;
import jakarta.annotation.PostConstruct;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * docextract-sdk 的宿主配置与注入点合一（yml 的 docextract.* 段）：
 * baseUrl/apiKey/model 实现 SDK 的 OcrConfigProvider，maxFileBytes 为提取防护上限；
 * 启动时经 @PostConstruct 注入 SDK，必要项缺失即快速失败
 * （模型可能下架、端点可能变更，不设隐式默认）。
 */
@Component
@ConfigurationProperties(prefix = "docextract")
public class DocextractProperties implements OcrConfigProvider {

    private String baseUrl;
    private String apiKey;
    private String model;
    /** 单文件大小上限（字节），超限直接拒绝提取；≤0 表示不限制。 */
    private long maxFileBytes = 100L * 1024 * 1024;

    /** Spring 生命周期入口；测试等无容器场景请直接调 {@link #installNow()}。 */
    @PostConstruct
    public void install() {
        List<String> missing = new ArrayList<>();
        if (isBlank(baseUrl)) {
            missing.add("docextract.base-url");
        }
        if (isBlank(apiKey)) {
            missing.add("docextract.api-key");
        }
        if (isBlank(model)) {
            missing.add("docextract.model");
        }
        if (!missing.isEmpty()) {
            throw new IllegalStateException("docextract-sdk 缺少必要配置 " + missing
                    + "（模型可能下架、端点可能变更，须在 yml 显式声明）");
        }
        QwenOcrClient.setConfigProvider(this);
        DocumentTextExtractor.setMaxFileBytes(maxFileBytes);
    }

    /** 无 Spring 容器（如纯 JUnit）时的装配入口：读 application.yml 与 application-local.yml 的既有键。 */
    public static void installNow() {
        DocextractProperties properties = new DocextractProperties();
        properties.setBaseUrl(AgentServiceConfig.docextractOcrBaseUrl());
        properties.setApiKey(AgentServiceConfig.dashScopeApiKey());
        properties.setModel(AgentServiceConfig.docextractOcrModel());
        properties.install();
    }

    @Override public String baseUrl() { return baseUrl; }
    @Override public String apiKey()  { return apiKey; }
    @Override public String model()   { return model; }

    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
    public void setApiKey(String apiKey)   { this.apiKey = apiKey; }
    public void setModel(String model)     { this.model = model; }
    public long getMaxFileBytes()          { return maxFileBytes; }
    public void setMaxFileBytes(long maxFileBytes) { this.maxFileBytes = maxFileBytes; }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
