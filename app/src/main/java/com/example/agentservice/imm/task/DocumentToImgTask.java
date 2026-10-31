package com.example.agentservice.imm.task;

/** IMM PDF 转图片任务的最小上下文，用于后续查询任务结果。 */
public record DocumentToImgTask(
        String taskId,
        String documentName,
        String outputPrefix) {
}
