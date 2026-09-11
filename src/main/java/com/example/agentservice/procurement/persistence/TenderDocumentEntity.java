package com.example.agentservice.procurement.persistence;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

/** AI 招标文件当前正文及审核状态。 */
@Data
@TableName("ai_tender_document")
public class TenderDocumentEntity {

    /** 16 位业务主键。 */
    @TableId(value = "id", type = IdType.INPUT)
    private String id;

    /** 对外暴露的异步任务 ID。 */
    private String taskId;

    /** 对应的招标项目 ID。 */
    private String projectId;

    /** 生成和审核使用的招标文件模板 ID。 */
    private String templateId;

    /** 招标单位确认的结构化项目数据 JSON。 */
    private String projectData;

    /** 用户当前编辑的最新 Markdown 正文。 */
    private String documentMarkdown;

    /** 初稿生成状态。 */
    private String generationStatus;

    /** 初稿生成阶段说明。 */
    private String generationStage;

    /** 初稿生成失败原因。 */
    private String generationError;

    /** 是否已经确认定稿。 */
    private Boolean finalized;

    /** 定稿时间。 */
    private LocalDateTime finalizedAt;

    /** 正文每次保存后递增的修订序号。 */
    private Integer contentRevision;

    /** 当前审核报告对应的正文修订序号。 */
    private Integer reviewRevision;

    /** 审核报告生成状态。 */
    private String reviewStatus;

    /** 审核报告生成阶段说明。 */
    private String reviewStage;

    /** 当前定稿对应的 Markdown 审核报告。 */
    private String reviewReport;

    /** 审核报告生成失败原因。 */
    private String reviewError;

    /** 审核完成时间。 */
    private LocalDateTime reviewedAt;

    /** 记录创建时间。 */
    private LocalDateTime createdAt;

    /** 记录最后更新时间。 */
    private LocalDateTime updatedAt;
}
