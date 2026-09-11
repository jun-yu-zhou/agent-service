package com.example.agentservice.procurement.persistence;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/** AI 招标文件生成、编辑和审核记录的数据访问入口。 */
@Mapper
public interface TenderDocumentMapper extends BaseMapper<TenderDocumentEntity> {
}
