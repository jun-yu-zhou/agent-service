package com.example.agentservice.procurement.domain;

import java.time.Instant;

/** 某个招标文件定稿版本对应的审核报告快照。 */
public record TenderReviewSnapshot(
        /** 招标文件生成任务 ID。 */
        String taskId,

        /** 本次审核绑定的定稿版本 ID。 */
        String versionId,

        /** 审核任务当前状态。 */
        TenderReviewStatus status,

        /** 供页面展示的当前处理阶段。 */
        String currentStage,

        /** 模型生成的完整 Markdown 审核报告。 */
        String reportMarkdown,

        /** 审核失败原因，成功时为空。 */
        String errorMessage,

        /** 审核任务首次创建时间。 */
        Instant createdAt,

        /** 审核状态最后更新时间。 */
        Instant updatedAt) {
}
