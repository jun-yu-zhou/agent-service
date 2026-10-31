package com.example.agentservice.utils;

import java.lang.reflect.Field;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 通用 Markdown 表格渲染器：把"对象列表"拼成一张 Markdown 表格字符串。
 *
 * <p>解决的问题：让大模型直接输出 Markdown 表格，列序、列名、转义（竖线、换行）全靠提示词
 * 约束，一旦模型输出跑偏（多一列、单元格里带未转义的 {@code |}）整张表就废了。更稳的做法是
 * 让模型输出结构化数据（JSON 对象数组），表格的"长相"由代码负责——本类就是那块负责"长相"的
 * 积木：输入 {@code List<行对象>}，输出标准 Markdown 表格文本。
 *
 * <p>工作方式：渲染器与业务类型零耦合，靠反射通用读取任意 record/class 列表：
 * <ol>
 *   <li>列集合来自第一行的对象：record 走 record components，普通类走声明字段；</li>
 *   <li>列名优先取属性上的 {@link MdTableColumn} 注解（中文表头），未标注则回退属性名；</li>
 *   <li>单元格值经 {@link #escapeCell} 转义——竖线换成全角、换行换成空格，保证一行数据
 *       永远渲染成表格的一行；</li>
 *   <li>null 与空白值渲染为空单元格，不输出 "null" 字样。</li>
 * </ol>
 *
 * <p>相对的还有"提示词里让模型自己输出表格"的做法，两者二选一即可：前者省一次结构化解析
 * 但格式不可控，本项目选结构化输出 + 本渲染器，格式 100% 确定。
 */
public final class MarkdownTables {

    private MarkdownTables() {
    }

    /**
     * 把对象列表渲染成 Markdown 表格。
     *
     * <p>列表为 null 或空时返回单列占位空表（渲染器从行对象取列定义，空列表拿不到列），
     * 保证输出永远是合法表格；列取自首个元素，后续元素的未知属性忽略、缺失属性渲染空单元格。
     *
     * @param rows 行对象列表，每个对象渲染为表格的一行
     * @return Markdown 表格文本，形如 {@code | 列1 | 列2 |\n| --- | --- |\n| 值 | 值 |}
     */
    public static <T> String render(List<T> rows) {
        if (rows == null || rows.isEmpty()) {
            return "| 无数据 |\n| --- |";
        }
        List<Column> columns = columnsOf(rows.get(0).getClass());
        List<String> lines = new ArrayList<>();
        lines.add(headerRow(columns.stream().map(Column::header).toList()));
        lines.add(separatorRow(columns));
        for (Object row : rows) {
            lines.add(dataRow(row, columns));
        }
        return String.join("\n", lines);
    }

    private static List<Column> columnsOf(Class<?> type) {
        if (type.isRecord()) {
            List<Column> columns = new ArrayList<>();
            for (RecordComponent component : type.getRecordComponents()) {
                columns.add(new Column(component.getName(), headerOf(component)));
            }
            return columns;
        }
        List<Column> columns = new ArrayList<>();
        for (Field field : type.getDeclaredFields()) {
            if (!java.lang.reflect.Modifier.isStatic(field.getModifiers())) {
                columns.add(new Column(field.getName(), headerOf(field)));
            }
        }
        return columns;
    }

    private static String headerOf(RecordComponent component) {
        MdTableColumn annotation = component.getAnnotation(MdTableColumn.class);
        return annotation != null ? annotation.value() : component.getName();
    }

    private static String headerOf(Field field) {
        MdTableColumn annotation = field.getAnnotation(MdTableColumn.class);
        return annotation != null ? annotation.value() : field.getName();
    }

    private static String headerRow(List<String> headers) {
        return headers.stream()
                .map(header -> "| " + header + " ")
                .collect(Collectors.joining("", "", "|"));
    }

    private static String separatorRow(List<Column> columns) {
        return columns.stream()
                .map(column -> "| --- ")
                .collect(Collectors.joining("", "", "|"));
    }

    private static String dataRow(Object row, List<Column> columns) {
        return columns.stream()
                .map(column -> "| " + escapeCell(readProperty(row, column.name())) + " ")
                .collect(Collectors.joining("", "", "|"));
    }

    private static String readProperty(Object row, String name) {
        try {
            if (row.getClass().isRecord()) {
                java.lang.reflect.Method accessor = row.getClass().getMethod(name);
                // 行对象可能是包私有的 record（如测试里的局部 record），需放开跨包访问。
                accessor.setAccessible(true);
                Object value = accessor.invoke(row);
                return value == null ? "" : String.valueOf(value);
            }
            Field field = row.getClass().getDeclaredField(name);
            field.setAccessible(true);
            Object value = field.get(row);
            return value == null ? "" : String.valueOf(value);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("读取表格单元格失败: " + row.getClass().getName() + "." + name, exception);
        }
    }

    private static String escapeCell(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        return value.replace("|", "｜").replace("\r\n", " ").replace('\n', ' ').replace('\r', ' ');
    }

    private record Column(String name, String header) {
    }
}
