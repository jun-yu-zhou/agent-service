package com.example.agentservice.procurement.tender.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class TenderDocumentAiServiceTest {

    @Test
    void stripsUnderlineTagsAndKeepsText() {
        String markdown = "根据《中华人民共和国政府采购法》的规定及<u>8号公寓B座学生宿舍家具采购项目</u>"
                + "（项目编号：<u>ZB137542026000159</u>）的采购结果。";

        assertEquals("根据《中华人民共和国政府采购法》的规定及8号公寓B座学生宿舍家具采购项目"
                + "（项目编号：ZB137542026000159）的采购结果。", TenderDocumentAiService.sanitizeHtmlTags(markdown));
    }

    @Test
    void convertsStrongToMarkdownBold() {
        assertEquals("人民币 [待补充：合同金额] 元整（**小写**）",
                TenderDocumentAiService.sanitizeHtmlTags(
                        "人民币 <u>[待补充：合同金额]</u> 元整（<strong>小写</strong>）"));
    }

    @Test
    void convertsLineBreakTagToSpace() {
        assertEquals("（1）新购家具 （床、柜分离） 2组",
                TenderDocumentAiService.sanitizeHtmlTags("（1）新购家具<br>（床、柜分离） 2组"));
        assertEquals("（2）新购家具 （上床下柜） 118组",
                TenderDocumentAiService.sanitizeHtmlTags("（2）新购家具<br/>（上床下柜） 118组"));
    }

    @Test
    void keepsComparisonOperatorsUntouched() {
        assertEquals("温度<50 且压力<0.5MPa",
                TenderDocumentAiService.sanitizeHtmlTags("温度<50 且压力<0.5MPa"));
    }

    @Test
    void rejectsRevisionThatObviouslyDropsDocumentContent() {
        assertEquals(false, TenderDocumentAiService.isSubstantiallyComplete("1234567890", "1234567"));
        assertEquals(true, TenderDocumentAiService.isSubstantiallyComplete("1234567890", "12345678"));
    }
}
