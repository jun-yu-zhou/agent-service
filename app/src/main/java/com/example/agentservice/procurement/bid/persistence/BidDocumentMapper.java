package com.example.agentservice.procurement.bid.persistence;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/** AI 投标文件任务数据库访问入口。 */
@Mapper
public interface BidDocumentMapper extends BaseMapper<BidDocumentEntity> {
}
