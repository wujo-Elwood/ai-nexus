package com.rag.agent.mapper;

import com.rag.agent.entity.AgentStep;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 智能体步骤编排 Mapper
 */
public interface AgentStepMapper {

    /** 查询某智能体的步骤定义，按 step_no 升序 */
    List<AgentStep> findByAgent(@Param("agentCode") String agentCode);

    /** 删除智能体的全部步骤（保存前调用，全量替换） */
    int deleteByAgent(@Param("agentCode") String agentCode);

    /** 批量插入步骤 */
    int insertBatch(@Param("steps") List<AgentStep> steps);
}
