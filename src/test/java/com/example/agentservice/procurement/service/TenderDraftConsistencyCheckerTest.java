package com.example.agentservice.procurement.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TenderDraftConsistencyCheckerTest {

    private final TenderDraftConsistencyChecker checker = new TenderDraftConsistencyChecker();

    @Test
    void shouldReportOnlyExplicitFactsMissingFromDraft() {
        String source = """
                项目预算：120,480元。
                开标日期：2026年9月10日。
                ★投标人须提供有效营业执照。
                ▲服务期限不少于一年。
                """;
        String draft = """
                ## 项目概况
                项目预算：120,480.00元，开标日期为 2026年9月10日。

                ★投标人须提供有效营业执照。
                [待补充：采购人联系人]
                """;

        TenderDraftConsistencyChecker.ConsistencyResult result = checker.check(source, draft);

        assertEquals(1, result.missingCriticalClauses().size());
        assertEquals("▲服务期限不少于一年。", result.missingCriticalClauses().get(0));
        assertTrue(result.missingAmounts().isEmpty());
        assertTrue(result.missingDates().isEmpty());
        assertEquals(1, result.pendingItems().size());
        assertTrue(result.hasIssues());
    }
}
