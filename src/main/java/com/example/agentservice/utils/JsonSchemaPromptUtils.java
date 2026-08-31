package com.example.agentservice.utils;

import com.github.victools.jsonschema.generator.OptionPreset;
import com.github.victools.jsonschema.generator.SchemaGenerator;
import com.github.victools.jsonschema.generator.SchemaGeneratorConfig;
import com.github.victools.jsonschema.generator.SchemaGeneratorConfigBuilder;
import com.github.victools.jsonschema.generator.SchemaVersion;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** 将 Java 类型转换为可直接附加到大模型提示词中的 JSON Schema。 */
public final class JsonSchemaPromptUtils {

    private static final SchemaGenerator GENERATOR = new SchemaGenerator(createConfig());
    private static final Map<Class<?>, String> SCHEMA_CACHE = new ConcurrentHashMap<>();

    private JsonSchemaPromptUtils() {
    }

    public static String schemaFor(Class<?> type) {
        return SCHEMA_CACHE.computeIfAbsent(type,
                targetType -> GENERATOR.generateSchema(targetType).toString());
    }

    private static SchemaGeneratorConfig createConfig() {
        return new SchemaGeneratorConfigBuilder(SchemaVersion.DRAFT_7, OptionPreset.PLAIN_JSON)
                .build();
    }
}
