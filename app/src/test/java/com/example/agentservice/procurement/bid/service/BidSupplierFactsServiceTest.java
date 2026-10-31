package com.example.agentservice.procurement.bid.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.agentservice.procurement.bid.persistence.BidSupplierFactsMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class BidSupplierFactsServiceTest {

    private final BidSupplierFactsMapper mapper = mock(BidSupplierFactsMapper.class);
    private final BidSupplierFactsService service = new BidSupplierFactsService(mapper, new ObjectMapper());

    @Test
    void usesLatestBatchAndKeepsBidIdentityFacts() {
        when(mapper.latestBatch("project-1"))
                .thenReturn(Map.of("batchId", "batch-2", "batchStatus", "1"));
        when(mapper.baseResponse("project-1", "batch-2", "company-1"))
                .thenReturn(Map.of("companyName", "测试企业", "legalRepresentativeId", "证件号样例",
                        "bankAccount", "账户样例"));
        when(mapper.itemResponses("project-1", "batch-2", "company-1"))
                .thenReturn(List.of(Map.of("itemName", "家具", "brand", "测试品牌")));

        JsonNode facts = service.load("project-1", "company-1");

        assertEquals("batch-2", facts.path("projectBatchId").asText());
        assertEquals("证件号样例", facts.path("baseResponse").path("legalRepresentativeId").asText());
        assertEquals("账户样例", facts.path("baseResponse").path("bankAccount").asText());
        assertEquals("测试品牌", facts.path("itemResponses").get(0).path("brand").asText());
        verify(mapper).requirementResponses("project-1", "batch-2", "company-1");
        verify(mapper).attachments("project-1", "batch-2", "company-1");
    }

    @Test
    void rejectsMissingCompanyResponse() {
        when(mapper.latestBatch("project-1"))
                .thenReturn(Map.of("batchId", "batch-2", "batchStatus", "1"));

        assertThrows(IllegalArgumentException.class, () -> service.load("project-1", "company-2"));
    }

    @Test
    void rejectsClosedProjectBatch() {
        when(mapper.latestBatch("project-1"))
                .thenReturn(Map.of("batchId", "batch-2", "batchStatus", "3"));

        assertThrows(IllegalArgumentException.class, () -> service.load("project-1", "company-1"));
    }
}
