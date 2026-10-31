package com.example.agentservice.procurement.tender.persistence;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.util.Arrays;
import org.apache.ibatis.annotations.Select;
import org.junit.jupiter.api.Test;

class TenderProjectMapperTest {

    @Test
    void templateQueryPrefersLatestValidCollegeTemplateThenFallsBackToCommonTemplate()
            throws Exception {
        String sql = sql("selectTemplate", String.class, String.class);

        assertTrue(sql.contains("COLLEGE_ID IN (#{collegeId}, 'ALL')"));
        assertTrue(sql.contains("DEL_FLAG = '1'"));
        assertTrue(sql.contains("TRIM(COMMENTS) <> ''"));
        assertTrue(sql.contains("CASE WHEN COLLEGE_ID = #{collegeId} THEN 0 ELSE 1 END"));
        assertTrue(sql.contains("COALESCE(LAST_UPDATE_TIME, CREATE_TIME) DESC"));
        assertTrue(sql.contains("TEMPLATE_ID DESC"));
    }

    @Test
    void businessDetailsUseConfiguredDisplayOrder() throws Exception {
        for (String method : new String[] { "selectQualifications", "selectScoreRules",
                "selectComments", "selectRequirements", "selectRequirementDetails" }) {
            assertTrue(sql(method, String.class).contains("ORDER BY SHOW_ORDER"));
        }
    }

    /** 读取 Mapper 注解中的 SQL，并折叠空白，避免测试受文本块排版影响。 */
    private String sql(String methodName, Class<?>... parameterTypes) throws Exception {
        Method method = TenderProjectMapper.class.getMethod(methodName, parameterTypes);
        return String.join(" ", Arrays.asList(method.getAnnotation(Select.class).value()))
                .replaceAll("\\s+", " ").trim();
    }
}
