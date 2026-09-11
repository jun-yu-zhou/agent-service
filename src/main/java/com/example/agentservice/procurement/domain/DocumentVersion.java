package com.example.agentservice.procurement.domain;

import java.time.Instant;
import java.util.List;

/** 文档生成、人工编辑和定稿流程使用的版本元数据。 */
public record DocumentVersion(
        /** 文档版本的唯一标识。 */
        String versionId,

        /** 该版本所属的文档生成任务 ID。 */
        String taskId,

        /** 该版本基于的上一版本 ID，初始版本为空。 */
        String parentVersionId,

        /** 供用户识别和排序的版本序号。 */
        int versionNumber,

        /** 版本来源，例如 AI 生成或人工编辑。 */
        String changeSource,

        /** 创建该版本的用户或系统标识。 */
        String changedBy,

        /** 版本创建时间。 */
        Instant createdAt,

        /** 是否为当前任务确认的定稿版本。 */
        boolean finalized,

        /** 该版本已经生成的导出产物。 */
        List<DocumentArtifact> artifacts) {

    public DocumentVersion {
        artifacts = artifacts == null ? List.of() : List.copyOf(artifacts);
    }
}
