package com.example.agentservice.imm.request;

import java.util.List;

/** 已转换为 IMM SourceURI 的图片拼接请求。 */
public record SpliceImagesRequest(List<String> sourceUris) {

    public SpliceImagesRequest {
        sourceUris = sourceUris == null ? null : List.copyOf(sourceUris);
    }
}
