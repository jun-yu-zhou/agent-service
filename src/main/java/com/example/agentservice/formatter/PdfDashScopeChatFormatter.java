package com.example.agentservice.formatter;

import io.agentscope.core.formatter.dashscope.DashScopeChatFormatter;
import io.agentscope.core.formatter.dashscope.dto.DashScopeMessage;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;

import java.util.*;
import java.util.stream.Collectors;

/** Formats PDF URLs according to the Qwen PDF understanding protocol. */
public class PdfDashScopeChatFormatter extends DashScopeChatFormatter {

    public static final String PDF_URLS_METADATA_KEY = "dashscope_pdf_urls";

    @Override
    protected List<DashScopeMessage> doFormat(List<Msg> messages) {
        return formatPdfMessages(messages, false);
    }

    @Override
    public List<DashScopeMessage> formatMultiModal(List<Msg> messages) {
        return formatPdfMessages(messages, true);
    }

    private List<DashScopeMessage> formatPdfMessages(List<Msg> messages, boolean multiModal) {
        return messages.stream()
                .map(message -> formatMessage(message, multiModal))
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }

    private DashScopeMessage formatMessage(Msg message, boolean multiModal) {
        List<String> pdfUrls = getPdfUrls(message);
        if (pdfUrls.isEmpty()) {
            List<DashScopeMessage> formatted = multiModal
                    ? super.formatMultiModal(List.of(message))
                    : super.doFormat(List.of(message));
            return formatted.isEmpty() ? null : formatted.get(0);
        }

        DashScopeMessage formatted = new DashScopeMessage();
        formatted.setRole(message.getRole().name().toLowerCase());
        formatted.setContent(toContent(message, pdfUrls));
        return formatted;
    }

    private List<Map<String, Object>> toContent(Msg message, List<String> pdfUrls) {
        List<Map<String, Object>> parts = new ArrayList<>();
        pdfUrls.forEach(url -> parts.add(part("file_url", url)));
        message.getContentBlocks(TextBlock.class)
                .forEach(block -> parts.add(part("text", block.getText())));
        return parts;
    }

    private List<String> getPdfUrls(Msg message) {
        if (message.getMetadata() == null) {
            return List.of();
        }
        Object value = message.getMetadata().get(PDF_URLS_METADATA_KEY);
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

    private Map<String, Object> part(String key, String value) {
        Map<String, Object> part = new LinkedHashMap<>();
        part.put(key, value);
        return part;
    }
}
