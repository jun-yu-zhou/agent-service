package com.example.agentservice;

import com.example.agentservice.entity.BidControlRiskRow;
import com.example.agentservice.utils.MarkdownTables;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** {@link MarkdownTables} 的离线单元测试。 */
class MarkdownTablesTest {

    @Test
    void shouldRenderAnnotatedColumnsInDeclarationOrder() {
        List<BidControlRiskRow> rows = List.of(new BidControlRiskRow(
                "参数精确", "高", "第3页：UPS要求特定品牌型号",
                "风险逻辑：……；法律依据：《政府采购法》第X条，条文要点：……；适用分析：……",
                "改为性能参数", "高"));

        String table = MarkdownTables.render(rows);

        String[] lines = table.split("\n");
        assertThat(lines).hasSize(3);
        assertThat(lines[0]).isEqualTo("| 风险类型 | 风险程度 | 风险内容 | 风险分析 | 修改建议 | 置信度 |");
        assertThat(lines[1]).isEqualTo("| --- | --- | --- | --- | --- | --- |");
        assertThat(lines[2]).contains("参数精确").contains("第3页：UPS要求特定品牌型号").contains("改为性能参数");
    }

    @Test
    void shouldEscapePipesAndNewlinesInCells() {
        List<BidControlRiskRow> rows = List.of(new BidControlRiskRow(
                "含|竖线", "中", "内容\n第二行\r\n第三行\r", "分析", "建议", "低"));

        String table = MarkdownTables.render(rows);

        String[] lines = table.split("\n");
        assertThat(lines).hasSize(3);
        assertThat(lines[2]).contains("含｜竖线").doesNotContain("含|竖线");
        assertThat(lines[2]).doesNotContain("\n第二行");
    }

    @Test
    void shouldRenderNullAndBlankCellsAsEmpty() {
        List<BidControlRiskRow> rows = List.of(new BidControlRiskRow(null, "  ", "内容", "", null, "低"));

        String table = MarkdownTables.render(rows);

        String dataLine = table.split("\n")[2];
        assertThat(dataLine).isEqualTo("| 风险类型 | 风险程度 | 风险内容 | 风险分析 | 修改建议 | 置信度 |"
                .replace("风险类型", "").replace("风险程度", "").replace("风险内容", "内容")
                .replace("风险分析", "").replace("修改建议", "").replace("置信度", "低"));
    }

    @Test
    void shouldFallbackToPropertyNameWithoutAnnotation() {
        record Plain(String name, int score) {
        }

        String table = MarkdownTables.render(List.of(new Plain("张三", 90)));

        String[] lines = table.split("\n");
        assertThat(lines[0]).isEqualTo("| name | score |");
        assertThat(lines[2]).isEqualTo("| 张三 | 90 |");
    }

    @Test
    void shouldRenderPlaceholderTableForNullOrEmptyRows() {
        assertThat(MarkdownTables.render(null)).isEqualTo("| 无数据 |\n| --- |");
        assertThat(MarkdownTables.render(List.of())).isEqualTo("| 无数据 |\n| --- |");
    }
}
