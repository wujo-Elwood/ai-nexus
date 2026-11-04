package com.rag.agent.entity;

/**
 * 智能体步骤定义：写了步骤的智能体按 step_no 顺序执行，
 * 没写步骤的智能体仍然走模型自主工具循环（保持原有行为）
 */
public class AgentStep {

    /** 步骤类型：调用技能 */
    public static final String TYPE_TOOL = "TOOL";
    /** 步骤类型：调用模型加工中间结果 */
    public static final String TYPE_LLM = "LLM";
    /** 步骤类型：指定最终回答的收尾指令 */
    public static final String TYPE_ANSWER = "ANSWER";

    private Long id;
    private String agentCode;
    private Integer stepNo;
    private String stepName;
    private String stepType;
    private String toolName;
    private String argsTemplate;
    private String instruction;
    private Integer enabled;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getAgentCode() {
        return agentCode;
    }

    public void setAgentCode(String agentCode) {
        this.agentCode = agentCode;
    }

    public Integer getStepNo() {
        return stepNo;
    }

    public void setStepNo(Integer stepNo) {
        this.stepNo = stepNo;
    }

    public String getStepName() {
        return stepName;
    }

    public void setStepName(String stepName) {
        this.stepName = stepName;
    }

    public String getStepType() {
        return stepType;
    }

    public void setStepType(String stepType) {
        this.stepType = stepType;
    }

    public String getToolName() {
        return toolName;
    }

    public void setToolName(String toolName) {
        this.toolName = toolName;
    }

    public String getArgsTemplate() {
        return argsTemplate;
    }

    public void setArgsTemplate(String argsTemplate) {
        this.argsTemplate = argsTemplate;
    }

    public String getInstruction() {
        return instruction;
    }

    public void setInstruction(String instruction) {
        this.instruction = instruction;
    }

    public Integer getEnabled() {
        return enabled;
    }

    public void setEnabled(Integer enabled) {
        this.enabled = enabled;
    }

    /** 当前步骤是否参与执行（未设置时按启用处理） */
    public boolean isActive() {
        return enabled == null || enabled == 1;
    }

    /** 步骤展示名：没配置名称时按类型给出可读名称 */
    public String displayName() {
        if (stepName != null && !stepName.isBlank()) {
            return stepName.trim();
        }
        String type = stepType == null ? TYPE_TOOL : stepType.toUpperCase();
        return switch (type) {
            case TYPE_LLM -> "模型处理";
            case TYPE_ANSWER -> "生成回答";
            default -> toolName == null || toolName.isBlank() ? "调用技能" : toolName;
        };
    }
}
