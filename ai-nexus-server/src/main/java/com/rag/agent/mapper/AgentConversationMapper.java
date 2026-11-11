package com.rag.agent.mapper;

import com.rag.agent.entity.AgentConversation;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 智能体对话会话 Mapper
 */
public interface AgentConversationMapper {

    int insert(AgentConversation conversation);

    /** 查询用户在某智能体下的会话列表（最近更新的在前） */
    List<AgentConversation> findByUserAndAgent(@Param("userId") Long userId, @Param("agentCode") String agentCode);

    /** 按 ID 与用户查询（用户隔离） */
    AgentConversation findByIdAndUserId(@Param("id") Long id, @Param("userId") Long userId);

    /** 触碰会话的更新时间（新消息追加时调用） */
    int touch(@Param("id") Long id);

    int deleteByIdAndUserId(@Param("id") Long id, @Param("userId") Long userId);
}
