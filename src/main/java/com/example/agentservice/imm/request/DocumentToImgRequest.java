package com.example.agentservice.imm.request;

/** PDF 转图片任务请求，字段均为 IMM 使用的 OSS URI。 */
public record DocumentToImgRequest(
        String sourceUri,
        String targetUriPrefix,
        String documentName) {
}
