package com.example.agentservice.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 验证 docextract.* 配置段能绑定（ocr 供视觉 OCR，extract 供提取防护；纯 JUnit 下用 Spring Binder 模拟）。 */
class DocextractPropertiesTest {

    @Test
    void bindsOcrEndpointKeyAndModelFromDocextractPrefix() {
        MapConfigurationPropertySource source = new MapConfigurationPropertySource(Map.of(
                "docextract.ocr.base-url", "https://dashscope.aliyuncs.com/api/v1",
                "docextract.ocr.api-key", "test-key",
                "docextract.ocr.model", "qwen3.5-ocr"));

        DocextractOcrProperties properties = new Binder(source)
                .bind("docextract.ocr", Bindable.of(DocextractOcrProperties.class)).get();

        assertEquals("https://dashscope.aliyuncs.com/api/v1", properties.baseUrl());
        assertEquals("test-key", properties.apiKey());
        assertEquals("qwen3.5-ocr", properties.model());
    }

    @Test
    void bindsMaxFileBytesFromDocextractPrefix() {
        MapConfigurationPropertySource source = new MapConfigurationPropertySource(Map.of(
                "docextract.extract.max-file-bytes", "52428800"));

        DocextractExtractProperties properties = new Binder(source)
                .bind("docextract.extract", Bindable.of(DocextractExtractProperties.class)).get();

        assertEquals(52_428_800L, properties.getMaxFileBytes());
    }

    @Test
    void extractPropertiesDefaultsToHundredMbWhenAbsent() {
        MapConfigurationPropertySource source = new MapConfigurationPropertySource(Map.of());

        DocextractExtractProperties properties = new Binder(source)
                .bind("docextract.extract", Bindable.of(DocextractExtractProperties.class))
                .orElseGet(DocextractExtractProperties::new);

        assertEquals(100L * 1024 * 1024, properties.getMaxFileBytes());
    }
}
