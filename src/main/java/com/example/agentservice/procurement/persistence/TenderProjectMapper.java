package com.example.agentservice.procurement.persistence;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** 查询旧招标业务库中生成招标文件所需的项目数据。 */
@Mapper
public interface TenderProjectMapper extends BaseMapper<TenderProjectEntity> {

    @Select("SELECT * FROM zb_project WHERE ZB_PROJECT_ID = #{id} AND DEL_FLAG = '1' LIMIT 1")
    Map<String, Object> selectProject(@Param("id") String projectId);

    @Select("SELECT * FROM zb_project_date WHERE ZB_PROJECT_ID = #{id} AND DEL_FLAG = '1'")
    List<Map<String, Object>> selectDates(@Param("id") String projectId);

    @Select("SELECT * FROM zb_project_batch WHERE ZB_PROJECT_ID = #{id} ORDER BY BATCH")
    List<Map<String, Object>> selectBatches(@Param("id") String projectId);

    @Select("SELECT * FROM zb_item WHERE ZB_PROJECT_ID = #{id} AND DEL_FLAG = '1'")
    List<Map<String, Object>> selectItems(@Param("id") String projectId);

    @Select("SELECT * FROM zb_item_param WHERE PROJECT_ID = #{id} AND IS_DELETE = '1' ORDER BY SHOW_ORDER")
    List<Map<String, Object>> selectItemParameters(@Param("id") String projectId);

    @Select("SELECT * FROM zb_project_attachment WHERE ZB_PROJECT_ID = #{id} AND IS_DELETE = '1' ORDER BY CREATE_TIME")
    List<Map<String, Object>> selectAttachments(@Param("id") String projectId);

    @Select("SELECT * FROM zb_qualification WHERE ZB_PROJECT_ID = #{id} AND DEL_FLAG = '1'")
    List<Map<String, Object>> selectQualifications(@Param("id") String projectId);

    @Select("SELECT * FROM zb_score_rule WHERE ZB_PROJECT_ID = #{id} AND DEL_FLAG = '1'")
    List<Map<String, Object>> selectScoreRules(@Param("id") String projectId);

    @Select("SELECT * FROM zb_project_comments WHERE ZB_PROJECT_ID = #{id} AND DEL_FLAG = '1'")
    List<Map<String, Object>> selectComments(@Param("id") String projectId);

    @Select("SELECT * FROM zb_requirement WHERE ZB_PROJECT_ID = #{id} AND DEL_FLAG = '1'")
    List<Map<String, Object>> selectRequirements(@Param("id") String projectId);

    @Select("SELECT * FROM zb_requirement_detail WHERE ZB_PROJECT_ID = #{id} AND DEL_FLAG = '1'")
    List<Map<String, Object>> selectRequirementDetails(@Param("id") String projectId);

    @Select("""
            SELECT TEMPLATE_ID AS templateId, COMMENTS AS templateHtml FROM zb_project_template
            WHERE COLLEGE_ID IN (#{collegeId}, 'ALL') AND TEMPLATE_TYPE = #{templateType}
            ORDER BY CASE WHEN COLLEGE_ID = #{collegeId} THEN 0 ELSE 1 END
            LIMIT 1
            """)
    Map<String, Object> selectTemplate(
            @Param("collegeId") String collegeId, @Param("templateType") String templateType);

    @Select("SELECT COMMENTS FROM zb_project_template WHERE TEMPLATE_ID = #{id} LIMIT 1")
    String selectTemplateHtml(@Param("id") String templateId);
}
