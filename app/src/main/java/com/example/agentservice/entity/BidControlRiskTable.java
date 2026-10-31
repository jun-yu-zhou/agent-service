package com.example.agentservice.entity;

import java.util.List;

/**
 * 控标审查结构化输出的顶层载体：把所有风险行包进一个 JSON 对象。
 *
 * <p>解决的问题：AgentScope 的结构化输出入口 {@code agent.call(msg, Class)} 需要一个
 * 顶层 Java 类来生成 JSON Schema，直接传 {@code List.class} 生成不出有意义的字段约束。
 * 所以用本类包一层——模型实际输出的是 {@code {"risks": [ {...行对象...}, ... ]}}，
 * 程序侧经 {@code risks()} 拿到行列表后再交给 {@code MarkdownTables.render} 拼表。
 *
 * <p>后续若报告要增加"总结""免责声明"等非表格字段，直接在本类加组件即可，
 * 模型会随 Schema 一起输出，无需改调用链路。
 */
public record BidControlRiskTable(List<BidControlRiskRow> risks) {

    public BidControlRiskTable {
        risks = risks == null ? List.of() : List.copyOf(risks);
    }
}
