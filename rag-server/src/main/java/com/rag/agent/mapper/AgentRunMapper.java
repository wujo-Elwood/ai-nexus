package com.rag.agent.mapper;

import com.rag.agent.entity.AgentRun;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 智能体运行记录 Mapper
 * 提供运行记录的新增、查询和删除能力
 */
@Mapper
public interface AgentRunMapper {

    /**
     * 新增智能体运行记录
     */
    int insert(AgentRun agentRun);

    /**
     * 查询当前用户最近的智能体运行记录
     */
    List<AgentRun> findRecent(@Param("createdBy") Long createdBy, @Param("limit") int limit);

    /**
     * 根据ID查询运行记录
     */
    AgentRun findById(@Param("id") Long id);

    /**
     * 删除当前用户自己的运行记录
     */
    int deleteById(@Param("id") Long id, @Param("createdBy") Long createdBy);

    /** 删除知识库下全部智能体运行记录 */
    int deleteByKbId(@Param("kbId") Long kbId);
}
