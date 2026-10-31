package com.example.agentservice.procurement.tender.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TenderRequirementNormalizerTest {

    private final TenderRequirementNormalizer normalizer =
            new TenderRequirementNormalizer(new ObjectMapper());

    @Test
    void enrichesAndGroupsRequirementDetails() {
        List<Map<String, Object>> details = new ArrayList<>(List.of(
                detail("Y", "Y", "必须满足"),
                detail("Z", "N", "重点响应"),
                detail("N", "N", "一般说明")));

        ObjectNode facts = normalizer.normalize(details);

        assertEquals("★必须满足", details.get(0).get("displayContent"));
        assertEquals("▲重点响应", details.get(1).get("displayContent"));
        assertTrue((Boolean) details.get(0).get("requiresEvidence"));
        assertFalse((Boolean) details.get(1).get("requiresEvidence"));
        assertEquals(1, facts.path("substantive").size());
        assertEquals(1, facts.path("important").size());
        assertEquals(1, facts.path("general").size());
        assertEquals(1, facts.path("evidenceRequiredCount").asInt());
    }

    private Map<String, Object> detail(String importance, String needFile, String content) {
        Map<String, Object> detail = new HashMap<>();
        detail.put("isImportant", importance);
        detail.put("isNeedFile", needFile);
        detail.put("orderParamContent", content);
        return detail;
    }
}
