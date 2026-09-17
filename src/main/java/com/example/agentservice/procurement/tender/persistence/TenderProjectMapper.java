package com.example.agentservice.procurement.tender.persistence;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** 查询旧招标业务库中生成招标文件所需的项目数据。 */
@Mapper
public interface TenderProjectMapper extends BaseMapper<TenderProjectEntity> {

    /** 查询有效的招标项目基本信息。 */
    @Select("SELECT * FROM zb_project WHERE ZB_PROJECT_ID = #{id} AND DEL_FLAG = '1' LIMIT 1")
    Map<String, Object> selectProject(@Param("id") String projectId);

    /** 查询公告、报名、投标和开标等项目时间。 */
    @Select("SELECT * FROM zb_project_date WHERE ZB_PROJECT_ID = #{id} AND DEL_FLAG = '1'")
    List<Map<String, Object>> selectDates(@Param("id") String projectId);

    /** 查询项目分包或批次信息，并按批次顺序返回。 */
    @Select("SELECT * FROM zb_project_batch WHERE ZB_PROJECT_ID = #{id} ORDER BY BATCH")
    List<Map<String, Object>> selectBatches(@Param("id") String projectId);

    /** 查询采购品目及其基础信息。 */
    @Select("SELECT * FROM zb_item WHERE ZB_PROJECT_ID = #{id} AND DEL_FLAG = '1'")
    List<Map<String, Object>> selectItems(@Param("id") String projectId);

    /** 查询采购品目的技术参数，并按展示顺序返回。 */
    @Select("SELECT * FROM zb_item_param WHERE PROJECT_ID = #{id} AND IS_DELETE = '1' ORDER BY SHOW_ORDER")
    List<Map<String, Object>> selectItemParameters(@Param("id") String projectId);

    /** 查询项目和采购品目关联的附件信息。 */
    @Select("SELECT * FROM zb_project_attachment WHERE ZB_PROJECT_ID = #{id} AND IS_DELETE = '1' ORDER BY CREATE_TIME")
    List<Map<String, Object>> selectAttachments(@Param("id") String projectId);

    /** 查询投标人的资格条件。 */
    @Select("SELECT * FROM zb_qualification WHERE ZB_PROJECT_ID = #{id} AND DEL_FLAG = '1' ORDER BY SHOW_ORDER")
    List<Map<String, Object>> selectQualifications(@Param("id") String projectId);

    /** 查询评标方法下配置的评分项、分值和评分规则。 */
    @Select("SELECT * FROM zb_score_rule WHERE ZB_PROJECT_ID = #{id} AND DEL_FLAG = '1' ORDER BY SHOW_ORDER")
    List<Map<String, Object>> selectScoreRules(@Param("id") String projectId);

    /** 查询项目各业务页签保存的补充内容。 */
    @Select("SELECT * FROM zb_project_comments WHERE ZB_PROJECT_ID = #{id} AND DEL_FLAG = '1' ORDER BY SHOW_ORDER")
    List<Map<String, Object>> selectComments(@Param("id") String projectId);

    /** 查询项目商务和履约要求。 */
    @Select("SELECT * FROM zb_requirement WHERE ZB_PROJECT_ID = #{id} AND DEL_FLAG = '1' ORDER BY SHOW_ORDER")
    List<Map<String, Object>> selectRequirements(@Param("id") String projectId);

    /** 查询商务和履约要求包含的明细条目。 */
    @Select("SELECT * FROM zb_requirement_detail WHERE ZB_PROJECT_ID = #{id} AND DEL_FLAG = '1' ORDER BY SHOW_ORDER")
    List<Map<String, Object>> selectRequirementDetails(@Param("id") String projectId);

    /** 查询采购单位配置的有效资金来源，用于把项目中的资金来源 ID 转成中文名称。 */
    @Select("SELECT CS_ID, CS_NAME FROM bg_capital_source WHERE COLLEGE_ID = #{collegeId} AND DEL_FLAG = '1'")
    List<Map<String, Object>> selectCapitalSources(@Param("collegeId") String collegeId);

    /** 查询高校对应类型的招标文件模板；高校未配置时回退到通用模板。 */
    @Select("""
            SELECT TEMPLATE_ID AS templateId, COMMENTS AS templateHtml FROM zb_project_template
            WHERE COLLEGE_ID IN (#{collegeId}, 'ALL') AND TEMPLATE_TYPE = #{templateType}
              AND DEL_FLAG = '1' AND COMMENTS IS NOT NULL AND TRIM(COMMENTS) <> ''
            ORDER BY CASE WHEN COLLEGE_ID = #{collegeId} THEN 0 ELSE 1 END,
              COALESCE(LAST_UPDATE_TIME, CREATE_TIME) DESC, TEMPLATE_ID DESC
            LIMIT 1
            """)
    Map<String, Object> selectTemplate(
            @Param("collegeId") String collegeId, @Param("templateType") String templateType);

    /** 根据模板 ID 查询审核阶段使用的原始 HTML 模板正文。 */
    @Select("""
            SELECT COMMENTS FROM zb_project_template
            WHERE TEMPLATE_ID = #{id} AND DEL_FLAG = '1'
              AND COMMENTS IS NOT NULL AND TRIM(COMMENTS) <> ''
            LIMIT 1
            """)
    String selectTemplateHtml(@Param("id") String templateId);
}
