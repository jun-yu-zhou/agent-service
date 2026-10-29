package com.example.agentservice.entity;

import com.example.agentservice.utils.MdTableColumn;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 控标审查结果表格中的一行风险记录，同时是给大模型的"结构化输出模板"。
 *
 * <p>解决的问题：过去控标审查报告的 Markdown 表格由模型直接绘制，表头顺序、列名、
 * 单元格转义全靠提示词约束，模型一跑偏整张表就废。现在改为让模型按本类的字段结构
 * 输出 JSON（AgentScope 的 {@code agent.call(msg, BidControlRiskTable.class)} 会把
 * 本类转成 JSON Schema 强制模型遵守），表格"长相"由 {@code MarkdownTables} 代码绘制。
 *
 * <p>6 个组件与审查报告的固定列一一对应，组件名（JSON 字段名/英文）给模型与程序用，
 * {@code @MdTableColumn} 标注的中文名给最终 Markdown 表头用；两套名字一处定义、不会脱节。
 *
 * <p>"字段必填"不靠提示词：{@code @JsonProperty(required = true)} 会被 AgentScope 的
 * schema 生成器（victools JacksonModule 的 RESPECT_JSONPROPERTY_REQUIRED 选项）转成
 * JSON Schema 的 {@code required} 数组；调用方再用 {@code minLength: 1} 硬化
 * （见 BidControlReview#hardenSchema），空字符串在 schema 层就不合法。
 * 注意 AgentScope 不读 jakarta 的 {@code @NotNull/@NotBlank}，别用错注解。
 */
public record


BidControlRiskRow(
        @MdTableColumn("风险类型") @JsonProperty(required = true) String riskType,
        @MdTableColumn("风险程度") @JsonProperty(required = true) String riskLevel,
        @MdTableColumn("风险内容") @JsonProperty(required = true) String riskContent,
        @MdTableColumn("风险分析") @JsonProperty(required = true) String riskAnalysis,
        @MdTableColumn("修改建议") @JsonProperty(required = true) String suggestion,
        @MdTableColumn("置信度") @JsonProperty(required = true) String confidence) {
}
