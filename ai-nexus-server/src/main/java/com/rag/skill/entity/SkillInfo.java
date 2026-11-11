package com.rag.skill.entity;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 技能注册表实体
 * 技能目录（编码/描述）由后端启动时按代码内实现自动同步，本表持久化启用状态
 */
@Data
public class SkillInfo {

    /** 主键 */
    private Long id;

    /** 技能编码，对应技能实现的 name() */
    private String skillName;

    /** 技能说明 */
    private String description;

    /** 是否启用：1 启用 0 停用 */
    private Integer enabled;

    /** 创建时间 */
    private LocalDateTime createTime;

    /** 更新时间 */
    private LocalDateTime updateTime;
}
