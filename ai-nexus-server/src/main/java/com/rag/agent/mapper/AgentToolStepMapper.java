package com.rag.agent.mapper;

import com.rag.agent.entity.AgentToolStep;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 通用智能体工具调用步骤 Mapper
 * 提供步骤记录的新增和按运行查询能力
 */
@Mapper
public interface AgentToolStepMapper {

    /** 新增一条工具调用步骤记录 */
    int insert(AgentToolStep step);

    /** 按运行ID查询全部步骤，按步骤序号升序 */
    List<AgentToolStep> findByRunId(@Param("runId") String runId);
}
