package com.example.agentservice.procurement.domain;

/** 文档生成、人工编辑和定稿流程使用的版本元数据。 */
public record DocumentVersion(
        /** 文档版本的唯一标识。 */
        String versionId,

        /** 该版本所属的文档生成任务 ID。 */
        String taskId,

        /** 供用户识别和排序的版本序号。 */
        int versionNumber,

        /** 版本来源，例如 AI 生成或人工编辑。 */
        String changeSource,

        /** 是否为当前任务确认的定稿版本。 */
        boolean finalized) {
}
