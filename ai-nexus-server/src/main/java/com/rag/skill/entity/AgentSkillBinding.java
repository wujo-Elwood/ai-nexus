package com.rag.skill.entity;

import lombok.Data;

/**
 * 智能体-技能绑定关系实体
 */
@Data
public class AgentSkillBinding {

    /** 主键 */
    private Long id;

    /** 智能体编码 */
    private String agentCode;

    /** 技能编码 */
    private String skillName;
}
