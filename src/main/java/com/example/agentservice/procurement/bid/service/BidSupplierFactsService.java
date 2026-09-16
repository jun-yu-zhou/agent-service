package com.example.agentservice.procurement.bid.service;

import com.baomidou.dynamic.datasource.annotation.DS;
import com.example.agentservice.procurement.bid.persistence.BidSupplierFactsMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/** 按项目当前批次归并某家投标企业已经提交的响应事实。 */
@DS("master")
@Service
public class BidSupplierFactsService {

    private final BidSupplierFactsMapper mapper;
    private final ObjectMapper objectMapper;

    public BidSupplierFactsService(BidSupplierFactsMapper mapper, ObjectMapper objectMapper) {
        this.mapper = mapper;
        this.objectMapper = objectMapper;
    }

    /**
     * 加载指定投标企业在招标项目当前批次已提交的响应事实，聚合为一份 JSON 供技术方案生成与一致性检查使用。
     * 项目批次不存在、已流标或终止、或该企业缺少基础响应资料时抛出异常。
     *
     * @param projectId 招标项目 ID
     * @param companyId 投标企业 ID
     * @return 含项目、企业与批次标识及基础响应、标的物及参数响应、商务响应、要求响应、附件清单的聚合事实 JSON
     */
    public JsonNode load(String projectId, String companyId) {
        if (projectId == null || projectId.isBlank() || companyId == null || companyId.isBlank()) {
            throw new IllegalArgumentException("招标项目 ID 和投标企业 ID 不能为空");
        }
        Map<String, Object> batch = mapper.latestBatch(projectId.trim());
        if (batch == null || batch.get("batchId") == null) {
            throw new IllegalArgumentException("招标项目批次不存在");
        }
        if ("3".equals(String.valueOf(batch.get("batchStatus")))
                || "4".equals(String.valueOf(batch.get("batchStatus")))) {
            throw new IllegalArgumentException("招标项目已流标或终止");
        }
        String batchId = String.valueOf(batch.get("batchId"));
        String id = projectId.trim();
        String supplierId = companyId.trim();
        Map<String, Object> base = mapper.baseResponse(id, batchId, supplierId);
        if (base == null || base.isEmpty()) {
            throw new IllegalArgumentException("当前批次没有该投标企业的基础响应资料");
        }

        ObjectNode facts = objectMapper.createObjectNode();
        facts.put("projectId", id);
        facts.put("companyId", supplierId);
        facts.put("projectBatchId", batchId);
        facts.set("baseResponse", objectMapper.valueToTree(base));
        facts.set("itemResponses", rows(mapper.itemResponses(id, batchId, supplierId)));
        facts.set("itemParameterResponses", rows(mapper.itemParameterResponses(id, batchId, supplierId)));
        facts.set("businessResponses", rows(mapper.commentResponses(id, batchId, supplierId)));
        facts.set("requirementResponses", rows(mapper.requirementResponses(id, batchId, supplierId)));
        facts.set("attachments", rows(mapper.attachments(id, batchId, supplierId)));
        return facts;
    }

    private JsonNode rows(List<Map<String, Object>> values) {
        return objectMapper.valueToTree(values == null ? List.of() : values);
    }
}
