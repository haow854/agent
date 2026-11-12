package com.javaee.agentservice.session;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface SessionMapper extends BaseMapper<Session> {

    int updateLastMessage(@Param("id") String id,
                          @Param("lastMessageId") Long lastMessageId,
                          @Param("lastTaskId") String lastTaskId);

    int setTitleIfAbsent(@Param("id") String id, @Param("title") String title);

    int setDocumentIdIfAbsent(@Param("id") String id,
                              @Param("documentId") String documentId);
}
