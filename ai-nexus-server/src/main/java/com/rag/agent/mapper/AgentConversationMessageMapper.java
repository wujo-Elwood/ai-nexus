package com.rag.agent.mapper;

import com.rag.agent.entity.AgentConversationMessage;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 智能体对话消息 Mapper
 */
public interface AgentConversationMessageMapper {

    int insert(AgentConversationMessage message);

    /** 按会话查询全部消息（按时间正序） */
    List<AgentConversationMessage> findByConversationId(@Param("conversationId") Long conversationId);

    int deleteByConversationId(@Param("conversationId") Long conversationId);
}
