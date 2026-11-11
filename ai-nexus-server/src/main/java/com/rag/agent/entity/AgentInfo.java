package com.rag.agent.entity;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 智能体注册表实体
 * 名称、描述、系统提示词、启用状态入库管理，管理页可直接新建/编辑
 */
@Data
public class AgentInfo {

    /** 主键 */
    private Long id;

    /** 智能体编码，对话接口按此路由 */
    private String agentCode;

    /** 展示名称 */
    private String agentName;

    /** 卡片描述 */
    private String description;

    /** 适用场景说明 */
    private String scene;

    /** 系统提示词，决定智能体行为 */
    private String systemPrompt;

    /** 是否启用：1 启用 0 停用 */
    private Integer enabled;

    /** 是否平台内置：1 内置不可删除 */
    private Integer builtIn;

    /** 创建人用户ID：NULL=平台内置，非空=用户自定义智能体（按创建人可见） */
    private Long createUser;

    /** 卡片排序 */
    private Integer sortNo;

    /** 创建时间 */
    private LocalDateTime createTime;

    /** 更新时间 */
    private LocalDateTime updateTime;
}
