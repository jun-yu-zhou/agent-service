package com.example.agentservice.service.impl;

import com.aliyun.imm20200930.Client;
import com.aliyun.imm20200930.models.ExtractDocumentTextRequest;
import com.aliyun.imm20200930.models.ExtractDocumentTextResponse;
import com.aliyun.teautil.models.RuntimeOptions;
import com.example.agentservice.config.AgentServiceConfig;
import com.example.agentservice.imm.support.AbstractImmServiceSupport;
import com.example.agentservice.service.extractDocumentTextService;
import org.springframework.stereotype.Service;

@Service
public class extractDocumentTextServiceImpl extends AbstractImmServiceSupport
        implements extractDocumentTextService {

    @Override
    public String extractDocumentText(
            com.example.agentservice.imm.request.ExtractDocumentTextRequest command) throws Exception {
        if (command == null) {
            throw new IllegalArgumentException("文档正文提取请求不能为空");
        }
        String sourceType = validateDocumentSource(command.sourceUri(), command.fileExtension());
        String sourceUri = command.sourceUri();
        ExtractDocumentTextRequest request = new ExtractDocumentTextRequest()
                .setProjectName(AgentServiceConfig.immProjectName())
                .setSourceURI(sourceUri)
                .setSourceType(sourceType);
        Client immClient = createImmClient();
        ExtractDocumentTextResponse response = immClient.extractDocumentTextWithOptions(
                request, new RuntimeOptions());
        if (response.getBody() == null) {
            throw new IllegalStateException("IMM文档正文提取未返回响应内容: " + sourceUri);
        }
        return response.getBody().getDocumentText();
    }
}
