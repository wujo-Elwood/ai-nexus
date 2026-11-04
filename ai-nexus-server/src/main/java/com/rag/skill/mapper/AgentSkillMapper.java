package com.rag.skill.mapper;

import com.rag.skill.entity.AgentSkillBinding;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 智能体-技能绑定关系 Mapper
 */
public interface AgentSkillMapper {

    /** 查询智能体绑定的技能编码列表 */
    List<String> findSkillNamesByAgent(@Param("agentCode") String agentCode);

    /** 查询全部绑定关系 */
    List<AgentSkillBinding> findAll();

    /** 删除智能体的全部绑定（保存前调用，全量替换） */
    int deleteByAgent(@Param("agentCode") String agentCode);

    /** 批量插入绑定关系 */
    int insertBatch(@Param("agentCode") String agentCode, @Param("skillNames") List<String> skillNames);
}
