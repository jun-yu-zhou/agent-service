package com.example.agentservice.procurement.service;

import org.springframework.stereotype.Service;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.math.BigDecimal;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Performs deterministic checks between source text and a generated tender draft.
 * Semantic equivalence is deliberately left to the later model-review stage.
 */
@Service
public class TenderDraftConsistencyChecker {

    private static final Pattern AMOUNT_PATTERN = Pattern.compile("(?<!\\d)\\d{1,3}(?:,\\d{3})*(?:\\.\\d+)?\\s*(?:元|万元|亿元)");
    private static final Pattern DATE_PATTERN = Pattern.compile("\\d{4}年\\d{1,2}月\\d{1,2}日|\\d{4}[-/]\\d{1,2}[-/]\\d{1,2}");
    private static final Pattern TODO_PATTERN = Pattern.compile("\\[待补充：[^\\]]+]");

    public ConsistencyResult check(String sourceText, String draftText) {
        if (sourceText == null || sourceText.isBlank()) {
            throw new IllegalArgumentException("招标来源正文不能为空");
        }
        if (draftText == null || draftText.isBlank()) {
            throw new IllegalArgumentException("招标文件初稿不能为空");
        }

        String normalizedDraft = normalize(draftText);
        List<String> missingCriticalClauses = sourceText.lines()
                .map(String::trim)
                .filter(line -> line.contains("★") || line.contains("▲"))
                .map(this::normalize)
                .filter(line -> !line.isBlank() && !normalizedDraft.contains(line))
                .distinct()
                .toList();

        return new ConsistencyResult(
                missingCriticalClauses,
                missingValues(sourceText, normalizedDraft, AMOUNT_PATTERN),
                missingValues(sourceText, normalizedDraft, DATE_PATTERN),
                findAll(draftText, TODO_PATTERN)
        );
    }

    private List<String> missingValues(String sourceText, String normalizedDraft, Pattern pattern) {
        return findAll(sourceText, pattern).stream()
                .filter(value -> !containsValue(normalizedDraft, value, pattern))
                .toList();
    }

    private boolean containsValue(String normalizedDraft, String value, Pattern pattern) {
        if (pattern != AMOUNT_PATTERN) {
            return normalizedDraft.contains(normalize(value));
        }
        Matcher draftAmounts = AMOUNT_PATTERN.matcher(normalizedDraft);
        String normalizedValue = normalizeAmount(value);
        while (draftAmounts.find()) {
            if (normalizeAmount(draftAmounts.group()).equals(normalizedValue)) {
                return true;
            }
        }
        return false;
    }

    private String normalizeAmount(String amount) {
        String unit = amount.endsWith("亿元") ? "亿元" : amount.endsWith("万元") ? "万元" : "元";
        String numeric = amount.substring(0, amount.length() - unit.length()).replace(",", "").trim();
        return new BigDecimal(numeric).stripTrailingZeros().toPlainString() + unit;
    }

    private List<String> findAll(String text, Pattern pattern) {
        Set<String> matches = new LinkedHashSet<>();
        Matcher matcher = pattern.matcher(text);
        while (matcher.find()) {
            matches.add(matcher.group());
        }
        return List.copyOf(matches);
    }

    private String normalize(String text) {
        return text.replaceAll("\\s+", "").replace("**", "").trim();
    }

    public record ConsistencyResult(
            List<String> missingCriticalClauses,
            List<String> missingAmounts,
            List<String> missingDates,
            List<String> pendingItems
    ) {
        public boolean hasIssues() {
            return !(missingCriticalClauses.isEmpty()
                    && missingAmounts.isEmpty()
                    && missingDates.isEmpty());
        }
    }
}
