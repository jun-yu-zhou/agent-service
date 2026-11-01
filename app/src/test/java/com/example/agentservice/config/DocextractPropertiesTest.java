package com.example.agentservice.config;

import io.majiaoyu.docextract.OcrConfigProvider;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/** 验证 docextract.* 配置段能绑定为 SDK 配置类（纯 JUnit 下用 Spring Binder 模拟，不启容器）。 */
class DocextractPropertiesTest {

    @Test
    void bindsAllFourKeysFromDocextractPrefix() {
        MapConfigurationPropertySource source = new MapConfigurationPropertySource(Map.of(
                "docextract.base-url", "https://dashscope.aliyuncs.com/api/v1",
                "docextract.api-key", "test-key",
                "docextract.model", "qwen3.5-ocr",
                "docextract.max-file-bytes", "52428800"));

        DocextractProperties properties = new Binder(source)
                .bind("docextract", Bindable.of(DocextractProperties.class)).get();

        assertEquals("https://dashscope.aliyuncs.com/api/v1", properties.baseUrl());
        assertEquals("test-key", properties.apiKey());
        assertEquals("qwen3.5-ocr", properties.model());
        assertEquals(52_428_800L, properties.getMaxFileBytes());
        assertInstanceOf(OcrConfigProvider.class, properties, "配置类本身即 SDK 的配置 SPI 实现");
    }

    @Test
    void maxFileBytesDefaultsToHundredMbWhenAbsent() {
        MapConfigurationPropertySource source = new MapConfigurationPropertySource(Map.of());

        DocextractProperties properties = new Binder(source)
                .bind("docextract", Bindable.of(DocextractProperties.class))
                .orElseGet(DocextractProperties::new);

        assertEquals(100L * 1024 * 1024, properties.getMaxFileBytes());
    }
}
