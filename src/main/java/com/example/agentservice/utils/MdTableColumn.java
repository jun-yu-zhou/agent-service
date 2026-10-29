package com.example.agentservice.utils;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Markdown 表格列定义注解，配合 {@link MarkdownTables#render(List)} 使用。
 *
 * <p>解决的问题：把"对象列表"渲染成 Markdown 表格时，Java 属性名（如 {@code riskType}）
 * 和表格列名（如"风险类型"）不是一回事——英文驼峰直接当表头可读性差，手工在渲染处维护
 * "属性名 → 列名"的映射又容易和实体定义脱节。
 *
 * <p>做法是把列名直接标在属性上（record 组件或字段均可）：渲染器反射读取对象属性时，若属性标注了本注解，
 * 则用 {@link #value()} 作为表头列名；否则回退用属性名本身。列的先后顺序默认跟随
 * 属性声明顺序（record 组件顺序即表格列顺序），保证"改实体 = 改表头"一处生效。
 *
 * <p>相对的还有"渲染处传 Map.of("riskType", "风险类型", ...) 传映射"的做法，
 * 两者二选一即可，本项目选用注解以便列定义与数据结构强绑定、不易漏改。
 */
@Target({ElementType.RECORD_COMPONENT, ElementType.FIELD})
@Retention(RetentionPolicy.RUNTIME)
public @interface MdTableColumn {

    /** 表头显示名，如"风险类型"。 */
    String value();
}
