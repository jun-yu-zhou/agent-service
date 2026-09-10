package com.example.agentservice.procurement.persistence;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/** 招标项目主表中的生成入口定位字段。 */
@Data
@TableName("zb_project")
public class TenderProjectEntity {

    @TableId("ZB_PROJECT_ID")
    private String zbProjectId;

    private String collegeId;

    /** 1 为货物、2 为工程、3 为服务。 */
    private String projectType;

    private String delFlag;
}
