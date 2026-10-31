package com.example.agentservice.procurement.tender.prompt;

/** 招标文件 Managed Agent 各阶段的用户指令。 */
public final class TenderDocumentPrompts {

    public static final String DRAFT_REQUEST = """
            请开始首次编制。模板文件位于 %s，项目招标数据位于 \
            /mnt/session/uploads/data/project-data.json。请生成完整的 Markdown 初稿并标记为产出物。
            """;

    public static final String FINALIZE_REQUEST = """
            请确认定稿并审核。附件是人工确认后的完整招标文件，请按既定要求生成并交付本阶段产物。
            """;

    private TenderDocumentPrompts() {
    }
}
