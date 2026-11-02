package com.example.agentservice.utils;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/** 将对象列表转换为 Markdown 表格，列名可用 MdTableColumn 指定。 */
public final class MarkdownTables {

    private MarkdownTables() {
    }

    public static String render(List<?> rows) {
        if (rows == null || rows.isEmpty()) {
            return "| 无数据 |\n| --- |";
        }
        List<Field> fields = Arrays.stream(rows.get(0).getClass().getDeclaredFields())
                .filter(field -> !Modifier.isStatic(field.getModifiers()) && !field.isSynthetic())
                .toList();
        fields.forEach(field -> field.setAccessible(true));
        String headers = fields.stream().map(field -> {
            MdTableColumn column = field.getAnnotation(MdTableColumn.class);
            return column == null ? field.getName() : column.value();
        }).collect(Collectors.joining(" | ", "| ", " |"));
        StringBuilder table = new StringBuilder(headers).append('\n')
                .append(fields.stream().map(field -> "---")
                        .collect(Collectors.joining(" | ", "| ", " |")));
        for (Object row : rows) {
            table.append('\n').append(fields.stream().map(field -> cell(field, row))
                    .collect(Collectors.joining(" | ", "| ", " |")));
        }
        return table.toString();
    }

    private static String cell(Field field, Object row) {
        try {
            Object value = field.get(row);
            String text = value == null ? "" : value.toString();
            return text.isBlank() ? "" : text.replace("|", "｜")
                    .replace("\r\n", " ").replace('\n', ' ').replace('\r', ' ');
        } catch (IllegalAccessException exception) {
            throw new IllegalStateException("读取表格字段失败: " + field.getName(), exception);
        }
    }
}
