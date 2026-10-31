package com.example.agentservice.config;

import io.majiaoyu.docextract.OcrConfigProvider;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/** 验证 docextract.ocr.* 配置段能绑定为 OcrConfigProvider（绑定在纯 JUnit 下用 Spring Binder 模拟）。 */
class DocextractOcrPropertiesTest {

    @Test
    void bindsOcrEndpointKeyAndModelFromDocextractPrefix() {
        MapConfigurationPropertySource source = new MapConfigurationPropertySource(Map.of(
                "docextract.ocr.base-url", "https://dashscope.aliyuncs.com/compatible-mode",
                "docextract.ocr.api-key", "test-key",
                "docextract.ocr.model", "qwen3.5-ocr"));

        DocextractOcrProperties properties = new Binder(source)
                .bind("docextract.ocr", Bindable.of(DocextractOcrProperties.class)).get();

        assertEquals("https://dashscope.aliyuncs.com/compatible-mode", properties.baseUrl());
        assertEquals("test-key", properties.apiKey());
        assertEquals("qwen3.5-ocr", properties.model());
        assertInstanceOf(OcrConfigProvider.class, properties, "配置类本身即 SDK 的配置 SPI 实现");
    }
}
