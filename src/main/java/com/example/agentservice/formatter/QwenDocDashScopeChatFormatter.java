package com.example.agentservice.formatter;

import io.agentscope.core.formatter.dashscope.DashScopeChatFormatter;
import io.agentscope.core.formatter.dashscope.dto.DashScopeMessage;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;

import java.util.*;
import java.util.stream.Collectors;

public class QwenDocDashScopeChatFormatter extends DashScopeChatFormatter {

    public static final String DOC_URLS_METADATA_KEY = "dashscope_doc_urls";
    public static final String FILE_PARSING_STRATEGY_METADATA_KEY = "dashscope_file_parsing_strategy";

    @Override
    protected List<DashScopeMessage> doFormat(List<Msg> messages) {
        if (messages.stream().noneMatch(this::hasDocUrls)) {
            return super.doFormat(messages);
        }
        return formatDocUrlMessages(messages, false);
    }

    @Override
    public List<DashScopeMessage> formatMultiModal(List<Msg> messages) {
        if (messages.stream().noneMatch(this::hasDocUrls)) {
            return super.formatMultiModal(messages);
        }
        return formatDocUrlMessages(messages, true);
    }

    private List<DashScopeMessage> formatDocUrlMessages(List<Msg> messages, boolean multiModal) {
        return messages.stream()
                .map(msg -> formatMessage(msg, multiModal))
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }

    private DashScopeMessage formatMessage(Msg msg, boolean multiModal) {
        if (hasDocUrls(msg)) {
            return toDashScopeMessage(msg);
        }

        List<DashScopeMessage> formatted = multiModal
                ? super.formatMultiModal(List.of(msg))
                : super.doFormat(List.of(msg));
        return formatted.isEmpty() ? null : formatted.get(0);
    }

    private DashScopeMessage toDashScopeMessage(Msg msg) {
        DashScopeMessage message = new DashScopeMessage();
        message.setRole(msg.getRole().name().toLowerCase());
        message.setContent(toContent(msg));
        return message;
    }

    private Object toContent(Msg msg) {
        if (!hasDocUrls(msg)) {
            return textContent(msg);
        }

        List<Map<String, Object>> parts = new ArrayList<>();
        msg.getContentBlocks(TextBlock.class)
                .forEach(textBlock -> parts.add(textPart(textBlock.getText())));
        parts.add(docUrlPart(getDocUrls(msg), getFileParsingStrategy(msg)));
        return parts.isEmpty() ? textContent(msg) : parts;
    }

    private boolean hasDocUrls(Msg msg) {
        return !getDocUrls(msg).isEmpty();
    }

    private List<String> getDocUrls(Msg msg) {
        if (msg.getMetadata() == null) {
            return List.of();
        }
        Object value = msg.getMetadata().get(DOC_URLS_METADATA_KEY);
        if (value instanceof String url && !url.isBlank()) {
            return List.of(url);
        }
        if (value instanceof List<?> urls) {
            return urls.stream()
                    .filter(String.class::isInstance)
                    .map(String.class::cast)
                    .filter(url -> !url.isBlank())
                    .collect(Collectors.toList());
        }
        return List.of();
    }

    private String getFileParsingStrategy(Msg msg) {
        if (msg.getMetadata() == null) {
            return "auto";
        }
        Object value = msg.getMetadata().get(FILE_PARSING_STRATEGY_METADATA_KEY);
        return value instanceof String strategy && !strategy.isBlank() ? strategy : "auto";
    }

    private String textContent(Msg msg) {
        return msg.getContentBlocks(TextBlock.class).stream()
                .map(TextBlock::getText)
                .collect(Collectors.joining("\n"));
    }

    private Map<String, Object> textPart(String text) {
        Map<String, Object> part = new LinkedHashMap<>();
        part.put("type", "text");
        part.put("text", text);
        return part;
    }

    private Map<String, Object> docUrlPart(List<String> docUrls, String fileParsingStrategy) {
        Map<String, Object> part = new LinkedHashMap<>();
        part.put("type", "doc_url");
        part.put("doc_url", docUrls);
        part.put("file_parsing_strategy", fileParsingStrategy);
        return part;
    }
}
