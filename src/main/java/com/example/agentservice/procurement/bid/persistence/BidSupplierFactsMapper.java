package com.example.agentservice.procurement.bid.persistence;

import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** 查询旧招标业务库中的投标企业响应，不复制旧页面状态。 */
@Mapper
public interface BidSupplierFactsMapper {

    @Select("SELECT ZB_PROJECT_BATCH_ID AS batchId, BATCH_STATUS AS batchStatus FROM zb_project_batch "
            + "WHERE ZB_PROJECT_ID = #{projectId} ORDER BY LAST_UPDATE_TIME DESC LIMIT 1")
    Map<String, Object> latestBatch(@Param("projectId") String projectId);

    @Select("""
            SELECT COMPANY_NAME AS companyName, PROJECT_NAME AS projectName,
                   TOTAL_PRICE AS totalPrice, BANK_NAME AS bankName, BANK_ACCOUNT AS bankAccount,
                   TENDER AS legalRepresentative, TENDER_ID AS legalRepresentativeId,
                   TENDER_JOB AS legalRepresentativeJob, AUTH_TENDER AS authorizedPerson,
                   AUTHO_TENDER_ID AS authorizedPersonId, AUTH_TENDER_MOBILE AS authorizedPersonMobile,
                   AUTH_TENDER_JOB AS authorizedPersonJob, PROJECT_MANAGER AS projectManager,
                   WORK_MANAGER AS workManager, SAFETY_MANAGER AS safetyManager
            FROM zb_project_base_resp
            WHERE ZB_PROJECT_ID = #{projectId} AND ZB_PROJECT_BATCH_ID = #{batchId}
              AND COMPANY_ID = #{companyId} AND DEL_FLAG = '1'
            ORDER BY LAST_UPDATE_TIME DESC LIMIT 1
            """)
    Map<String, Object> baseResponse(@Param("projectId") String projectId,
            @Param("batchId") String batchId, @Param("companyId") String companyId);

    @Select("""
            SELECT ITEM_ID AS itemId, ITEM_NAME AS itemName, SHOW_ORDER AS showOrder,
                   ORDER_NUM AS quantity, UNIT AS unit, BRAND AS brand, MODELS AS model,
                   MANUFACTURER AS manufacturer, PRODUCING_AREA AS origin,
                   BID_PRICE AS unitPrice, BID_AMOUNT AS amount, BID_REASON AS quoteReason,
                   WARRANTY_PERIOD_RESP AS warrantyPeriod, WARRANTY_DEVIATION AS warrantyDeviation,
                   AFTER_SALE_SERVICE AS afterSalesService, AFTER_SALE_DEVIATION AS afterSalesDeviation,
                   SUPPORTING_DATA AS supportingData
            FROM zb_item_resp
            WHERE ZB_PROJECT_ID = #{projectId} AND ZB_PROJECT_BATCH_ID = #{batchId}
              AND COMPANY_ID = #{companyId} AND DEL_FLAG = '1'
            ORDER BY SHOW_ORDER
            """)
    List<Map<String, Object>> itemResponses(@Param("projectId") String projectId,
            @Param("batchId") String batchId, @Param("companyId") String companyId);

    @Select("""
            SELECT ITEM_ID AS itemId, SHOW_ORDER AS showOrder, ORDER_PARAM_CODE AS requirementCode,
                   ORDER_PARAM_CONTENT AS requirement, DEVIATION AS deviation,
                   SUPPORTING_DATA AS supportingData
            FROM zb_item_param_resp
            WHERE ZB_PROJECT_ID = #{projectId} AND ZB_PROJECT_BATCH_ID = #{batchId}
              AND COMPANY_ID = #{companyId} AND IS_DELETE = '1'
            ORDER BY SHOW_ORDER
            """)
    List<Map<String, Object>> itemParameterResponses(@Param("projectId") String projectId,
            @Param("batchId") String batchId, @Param("companyId") String companyId);

    @Select("""
            SELECT COMMENTS_TYPE AS responseType, ZB_SCORE_RULE_ID AS scoreRuleId,
                   RESP_NAME AS responseName,
                   RESP_GRADE AS responseGrade, RESP_PERSON_ID AS personId,
                   RESP_COMMENTS AS responseContent, PARAM_VALUES AS parameterValues,
                   SHOW_ORDER AS showOrder
            FROM zb_project_comments_resp
            WHERE ZB_PROJECT_ID = #{projectId} AND ZB_PROJECT_BATCH_ID = #{batchId}
              AND COMPANY_ID = #{companyId} AND DEL_FLAG = '1'
            ORDER BY SHOW_ORDER
            """)
    List<Map<String, Object>> commentResponses(@Param("projectId") String projectId,
            @Param("batchId") String batchId, @Param("companyId") String companyId);

    @Select("""
            SELECT REQUIREMENT_NAME AS requirementName, ORDER_PARAM_CONTENT AS requirement,
                   BID_PARAM_CONTENT AS response, DEVIATION AS deviation,
                   SUPPORTING_DATA AS supportingData, FILE_NAME AS fileName,
                   REQUIREMENT_SHOW_ORDER AS requirementOrder,
                   REQUIREMENT_DETAIL_SHOW_ORDER AS detailOrder
            FROM zb_requirement_detail_resp
            WHERE ZB_PROJECT_ID = #{projectId} AND ZB_PROJECT_BATCH_ID = #{batchId}
              AND COMPANY_ID = #{companyId} AND DEL_FLAG = '1'
            ORDER BY REQUIREMENT_SHOW_ORDER, REQUIREMENT_DETAIL_SHOW_ORDER
            """)
    List<Map<String, Object>> requirementResponses(@Param("projectId") String projectId,
            @Param("batchId") String batchId, @Param("companyId") String companyId);

    @Select("""
            SELECT ATTACHMENT_NAME AS attachmentName, ATTACHMENT_TYPE AS attachmentType,
                   SHOW_ORDER AS showOrder
            FROM zb_bid_attachment
            WHERE ZB_PROJECT_ID = #{projectId} AND ZB_PROJECT_BATCH_ID = #{batchId}
              AND COMPANY_ID = #{companyId} AND IS_DELETE = '1'
            ORDER BY SHOW_ORDER
            """)
    List<Map<String, Object>> attachments(@Param("projectId") String projectId,
            @Param("batchId") String batchId, @Param("companyId") String companyId);
}
