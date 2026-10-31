package com.example.agentservice.config;

import io.majiaoyu.docextract.OcrConfigProvider;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** docextract-sdk 的视觉 OCR 配置（application.yml 的 docextract.ocr.* 段），本身即 OcrConfigProvider 实现。 */
@Component
@ConfigurationProperties(prefix = "docextract.ocr")
public class DocextractOcrProperties implements OcrConfigProvider {

    private String baseUrl;
    private String apiKey;
    private String model;

    @Override
    public String baseUrl() {
        return baseUrl;
    }

    @Override
    public String apiKey() {
        return apiKey;
    }

    @Override
    public String model() {
        return model;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public void setModel(String model) {
        this.model = model;
    }
}
