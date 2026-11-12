package com.javaee.agentservice.session;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface SessionSummaryMapper extends BaseMapper<SessionSummary> {

    int upsert(@Param("summary") SessionSummary summary);
}
