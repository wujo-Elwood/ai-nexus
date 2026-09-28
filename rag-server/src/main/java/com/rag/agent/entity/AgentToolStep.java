package com.rag.agent.entity;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 通用智能体工具调用步骤实体类，对应数据库 agent_tool_step 表
 * 每次对话运行中每调用一次工具就记录一行，用于前端回放和事后排查
 */
@Data
public class AgentToolStep {
    /** 主键 */
    private Long id;
    /** 运行ID，一次对话请求一个 */
    private String runId;
    /** 步骤序号，从1开始 */
    private Integer stepNo;
    /** 工具名称 */
    private String toolName;
    /** 模型给出的参数JSON */
    private String arguments;
    /** 工具返回结果 */
    private String result;
    /** 执行状态：SUCCESS/FAILED */
    private String status;
    /** 失败原因 */
    private String errorMessage;
    /** 耗时毫秒 */
    private Long costMs;
    /** 创建时间 */
    private LocalDateTime createTime;
}
