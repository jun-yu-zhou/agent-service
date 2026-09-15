package com.example.agentservice.procurement.bid.prompt;

import com.example.agentservice.procurement.bid.domain.TenderEssentialFacts;
import com.example.agentservice.procurement.bid.domain.BidTechnicalOutline;
import com.example.agentservice.procurement.bid.domain.BidConsistencyReview;
import com.example.agentservice.utils.JsonSchemaPromptUtils;

/** 投标技术方案各阶段使用的提示词。 */
public final class BidDocumentPrompts {

    private BidDocumentPrompts() {
    }

    public static String tenderFactsExtraction() {
        return """
                你负责从招标文件中抽取编制投标技术方案所需的客观事实。

                重点读取：项目名称、编号、采购人、预算和采购范围；技术与服务要求；交付、工期、验收和售后要求；技术评分项及得分条件；投标文件的组成、顺序和格式要求；可能导致无效响应或扣分的明确条件。

                只记录文件中明确出现的内容，不推测、不补写。location填写章节、条款或页码，excerpt保留能够支撑事实的简短原文。没有找到的单值字段填null，列表填空数组。相同事实合并，只输出一个符合下列Schema的JSON对象，不输出解释或Markdown代码块：

                %s
                """.formatted(JsonSchemaPromptUtils.schemaFor(TenderEssentialFacts.class));
    }

    public static String outlineGeneration() {
        return """
                你负责规划一份可直接用于编写投标技术方案的目录。

                目录应围绕技术评分标准、采购需求、实施交付、质量保障、验收、售后和风险控制展开，并结合企业已有能力组织章节。评分重点应落实到具体末级章节，避免空泛、重复和与本项目无关的内容。通常使用二至三级目录，章节数量和层级由项目复杂度决定。

                每个章节设置稳定且唯一的id；writingFocus说明该章节正文应回答什么；requirementRefs填写其响应的评分项或招标要求名称。children为空的章节将直接生成正文。

                只输出一个符合下列Schema的JSON对象，不输出解释或Markdown代码块：

                %s
                """.formatted(JsonSchemaPromptUtils.schemaFor(BidTechnicalOutline.class));
    }

    public static final String SECTION_CONTENT_GENERATION = """
            你负责撰写投标技术方案中的一个末级章节。

            以招标要求和评分标准为主线，结合投标企业已经提供的能力与经验，形成具体、可执行且针对本项目的响应内容。根据章节主题说明实施步骤、责任分工、进度控制、质量保障、交付成果或风险措施，不堆砌口号，也不重复其他章节。

            可以基于专业常识设计实施方法，但不得虚构企业资质、人员姓名、证书、业绩、设备数量或承诺数值。资料未提供的企业事实不写占位符。

            使用规范Markdown正文；不要输出章节标题、目录、前言、总结、解释或代码围栏。内容深度与该章节对应的招标要求和评分权重相匹配。
            """;

    public static String consistencyReview() {
        return """
                你负责检查投标技术方案是否准确、完整地响应招标文件。

                逐项核对技术评分、技术与服务要求、交付验收要求、响应文件要求和否决风险。重点识别遗漏、响应不充分、前后矛盾、超出企业资料的虚构陈述，以及可能导致扣分或无效响应的内容。

                status仅使用COVERED、PARTIAL、MISSING或CONFLICT；conclusion仅使用PASS或NEEDS_REVISION。证据应引用输入中已有的要求和技术方案文字，不推测外部事实。suggestion给出可以直接执行的修改方向，不重写整份方案。

                只输出一个符合下列Schema的JSON对象，不输出解释或Markdown代码块：

                %s
                """.formatted(JsonSchemaPromptUtils.schemaFor(BidConsistencyReview.class));
    }
}
