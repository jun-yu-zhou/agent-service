package com.example.agentservice.procurement.bid.persistence;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

/** 投标文件生成任务、目录和正文的当前状态。 */
@Data
@TableName("ai_bid_document")
public class BidDocumentEntity {

    /** 16 位业务主键。 */
    @TableId(value = "id", type = IdType.INPUT)
    private String id;

    /** 对外暴露的异步任务 ID。 */
    private String taskId;

    /** 用户上传的招标文件名。 */
    private String sourceFileName;

    /** OSS 对象路径；前端仅提供访问地址时可以为空。 */
    private String sourceObjectKey;

    /** 招标文件的可访问地址，供文档模型解析。 */
    private String sourceUrl;

    /** 企业、人员、资质、业绩和实施能力等事实 JSON。 */
    private String supplierFacts;

    /** 从招标文件抽取的项目要求 JSON。 */
    private String tenderFacts;

    /** 用户可编辑并确认的树形目录及章节正文 JSON。 */
    private String outlineJson;

    /** 合并后的 Markdown 投标技术方案正文。 */
    private String documentMarkdown;

    /** 技术方案与招标要求的一致性检查结果 JSON。 */
    private String consistencyReview;

    /** 当前任务阶段。 */
    private String stage;

    /** 目录是否已由用户确认。 */
    private Boolean outlineConfirmed;

    /** 当前阶段失败原因。 */
    private String errorMessage;

    /** 记录创建时间。 */
    private LocalDateTime createdAt;

    /** 记录最后更新时间。 */
    private LocalDateTime updatedAt;
}
