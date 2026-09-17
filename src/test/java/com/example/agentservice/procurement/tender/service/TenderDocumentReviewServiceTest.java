package com.example.agentservice.procurement.tender.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class TenderDocumentReviewServiceTest {

    @Test
    void convertsInternalPropertyNamesToBusinessChinese() {
        assertEquals("项目资料采购清单中核心产品标识均为未明确/否",
                TenderDocumentReviewService.sanitizeBusinessTerms("项目资料items中isCore均为null/否"));
    }
}
