package com.rag.skill.mapper;

import com.rag.skill.entity.AgentUsageStat;
import com.rag.skill.entity.SkillInfo;
import com.rag.skill.entity.SkillUsageStat;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 技能注册表 Mapper
 */
public interface SkillMapper {

    /** 查询全部技能配置 */
    List<SkillInfo> findAll();

    /** 同步技能目录：不存在则插入，存在则更新描述 */
    int upsertSkill(@Param("skillName") String skillName, @Param("description") String description);

    /** 更新技能启用状态 */
    int updateEnabled(@Param("skillName") String skillName, @Param("enabled") Integer enabled);

    /** 按技能聚合指定时间之后的调用统计 */
    List<SkillUsageStat> summarizeBySkill(@Param("since") LocalDateTime since);

    /** 按智能体聚合指定时间之后的调用统计 */
    List<AgentUsageStat> summarizeByAgent(@Param("since") LocalDateTime since);
}
