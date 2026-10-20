package com.example.agentservice.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AgentServiceConfigTest {

    @Test
    void readsTikaOcrEndpointAndModelFromYaml() {
        assertEquals("https://dashscope.aliyuncs.com/compatible-mode", AgentServiceConfig.tikaOcrBaseUrl());
        assertEquals("qwen3.5-ocr", AgentServiceConfig.tikaOcrModel());
    }
}
