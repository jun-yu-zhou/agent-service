package com.example.agentservice.service.impl;

import com.aliyun.imm20200930.Client;
import com.aliyun.imm20200930.models.DetectImageTextsRequest;
import com.aliyun.imm20200930.models.DetectImageTextsResponse;
import com.aliyun.teautil.models.RuntimeOptions;
import com.example.agentservice.config.AgentServiceConfig;
import com.example.agentservice.imm.support.AbstractImmServiceSupport;
import com.example.agentservice.service.detectImageTextsService;
import org.springframework.stereotype.Service;

@Service
public class detectImageTextsServiceImpl extends AbstractImmServiceSupport
        implements detectImageTextsService {

    @Override
    public String detectImageTexts(
            com.example.agentservice.imm.request.DetectImageTextsRequest command) throws Exception {
        if (command == null) {
            throw new IllegalArgumentException("图片文字识别请求不能为空");
        }
        if (command.sourceUri() == null || command.sourceUri().isBlank()) {
            throw new IllegalArgumentException("图片SourceURI不能为空");
        }
        String sourceUri = command.sourceUri();
        DetectImageTextsRequest request = new DetectImageTextsRequest()
                .setProjectName(AgentServiceConfig.immProjectName())
                .setSourceURI(sourceUri);
        Client immClient = createImmClient();
        DetectImageTextsResponse response = immClient.detectImageTextsWithOptions(
                request, new RuntimeOptions());
        if (response.getBody() == null) {
            throw new IllegalStateException("IMM图片正文提取未返回响应内容: " + sourceUri);
        }
        return response.getBody().getOCRTexts();
    }
}
