package com.example.agentservice.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** docextract-sdk 的提取防护配置（application.yml 的 docextract.extract.* 段）。 */
@Component
@ConfigurationProperties(prefix = "docextract.extract")
public class DocextractExtractProperties {

    /** 单文件大小上限（字节），超限直接拒绝提取；≤0 表示不限制。 */
    private long maxFileBytes = 100L * 1024 * 1024;

    public long getMaxFileBytes() {
        return maxFileBytes;
    }

    public void setMaxFileBytes(long maxFileBytes) {
        this.maxFileBytes = maxFileBytes;
    }
}
