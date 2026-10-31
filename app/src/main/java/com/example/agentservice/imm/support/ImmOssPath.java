package com.example.agentservice.imm.support;

import com.example.agentservice.config.AgentServiceConfig;

/** Builds and validates OSS URIs used by IMM requests. */
public final class ImmOssPath {

    private ImmOssPath() {
    }

    public static String prefix() {
        return "oss://" + AgentServiceConfig.ossBucket() + "/";
    }

    public static String uri(String objectKey) {
        if (objectKey == null || objectKey.isBlank()) {
            throw new IllegalArgumentException("OSS 对象路径不能为空");
        }
        return prefix() + objectKey;
    }

    public static String objectKey(String uri) {
        String prefix = prefix();
        if (uri == null || uri.isBlank() || !uri.startsWith(prefix)
                || uri.length() <= prefix.length()) {
            throw new IllegalArgumentException("OSS URI 不合法: " + uri);
        }
        return uri.substring(prefix.length());
    }
}
