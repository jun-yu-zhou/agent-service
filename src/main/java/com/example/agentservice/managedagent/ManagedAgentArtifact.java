package com.example.agentservice.managedagent;

/** Managed Agent 通过 mark_artifacts 交付的文件。 */
public record ManagedAgentArtifact(String fileId, String fileName) {
}
