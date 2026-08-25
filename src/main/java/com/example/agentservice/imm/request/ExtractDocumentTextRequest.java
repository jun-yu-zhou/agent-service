package com.example.agentservice.imm.request;

/** 已转换为 IMM SourceURI 的文档正文提取请求。 */
public record ExtractDocumentTextRequest(String sourceUri, String fileExtension) {
}
