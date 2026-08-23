package com.example.agentservice.agile;

import com.example.document2entity.entity.Doc;
import com.example.document2entity.entity.LongFactExtractionResult;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;

public final class QwenDocResponseParser {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final ObjectReader JSON_READER = OBJECT_MAPPER.readerFor(JsonNode.class)
            .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    private QwenDocResponseParser() {
    }

    public static Doc parse(String responseText) {
        return parse(responseText, Doc.class);
    }

    public static <T> T parse(String responseText, Class<T> targetType) {
        try {
            JsonNode root = readExpectedRoot(unwrapJsonCodeFence(responseText), targetType);
            if (root == null || !root.isObject()) {
                throw new IllegalArgumentException("模型响应必须是JSON对象");
            }
            return OBJECT_MAPPER.treeToValue(root, targetType);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("模型返回的JSON无效，原始响应未纳入后续阶段", exception);
        }
    }

    private static String unwrapJsonCodeFence(String responseText) {
        if (responseText == null || responseText.isBlank()) {
            throw new IllegalArgumentException("模型返回了空响应");
        }

        String text = responseText.trim();
        if (!text.startsWith("```")) {
            return text;
        }

        int contentStart = text.indexOf('\n');
        int contentEnd = text.lastIndexOf("```");
        if (contentStart < 0 || contentEnd <= contentStart) {
            throw new IllegalArgumentException("模型返回了不完整的JSON代码围栏");
        }
        return text.substring(contentStart + 1, contentEnd).trim();
    }

    private static JsonNode readExpectedRoot(String text, Class<?> targetType)
            throws JsonProcessingException {
        JsonProcessingException firstError = null;
        String firstObject = extractFirstJsonObject(text, 0);
        try {
            JsonNode root = JSON_READER.readValue(firstObject);
            if (matchesExpectedRoot(root, targetType)) {
                return root;
            }
            if (!requiresExpectedRoot(targetType)) {
                return root;
            }
        } catch (JsonProcessingException exception) {
            firstError = exception;
        }

        for (int start = 1; start < text.length(); start++) {
            if (text.charAt(start) != '{') {
                continue;
            }
            try {
                JsonNode root = JSON_READER.readValue(extractFirstJsonObject(text, start));
                if (matchesExpectedRoot(root, targetType)) {
                    return root;
                }
            } catch (JsonProcessingException exception) {
                if (firstError == null) {
                    firstError = exception;
                }
            }
        }
        if (firstError != null) {
            throw firstError;
        }
        throw new IllegalArgumentException("未找到符合目标类型的JSON根对象");
    }

    private static boolean requiresExpectedRoot(Class<?> targetType) {
        return targetType == Doc.class
                || targetType == LongFactExtractionResult.class;
    }

    private static boolean matchesExpectedRoot(JsonNode root, Class<?> targetType) {
        if (root == null || !root.isObject()) {
            return false;
        }
        if (targetType == Doc.class) {
            return root.has("order");
        }
        if (targetType == LongFactExtractionResult.class) {
            return root.has("dimension") && root.has("candidates");
        }
        return root.has("dimension");
    }

    private static String extractFirstJsonObject(String text, int startIndex) {
        int start = text.indexOf('{', startIndex);
        if (start < 0) {
            return text;
        }

        int depth = 0;
        boolean inString = false;
        boolean escaped = false;

        for (int index = start; index < text.length(); index++) {
            char current = text.charAt(index);
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (current == '\\') {
                    escaped = true;
                } else if (current == '"') {
                    inString = false;
                }
                continue;
            }

            if (current == '"') {
                inString = true;
            } else if (current == '{') {
                depth++;
            } else if (current == '}') {
                depth--;
                if (depth == 0) {
                    // Qwen-Long occasionally appends a second JSON object after a valid result.
                    return text.substring(start, index + 1);
                }
            }
        }
        return text.substring(start);
    }
}
