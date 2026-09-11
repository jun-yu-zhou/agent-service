package com.example.agentservice.procurement.domain;

/** 文档导出产物类型。 */
public enum ArtifactType {
    /** 文档结构化数据。 */
    STRUCTURED_JSON,

    /** Word 文档。 */
    DOCX,

    /** PDF 文档。 */
    PDF,

    /** 文档一致性审核报告。 */
    CONSISTENCY_REPORT
}
