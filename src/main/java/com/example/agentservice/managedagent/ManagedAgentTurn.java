package com.example.agentservice.managedagent;

import java.util.List;

/** 一轮 Managed Agent 执行完成后的文本和文件结果。 */
public record ManagedAgentTurn(String text, List<ManagedAgentFile> files) {

    /** Agent 返回的可下载文件。 */
    public record ManagedAgentFile(String fileId, String fileName, String fileUrl, String fileData) {
    }
}
