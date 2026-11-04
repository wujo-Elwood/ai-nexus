-- ============================================================
-- 智能体步骤编排（agent_step）
-- 目标：让自定义智能体可以「写步骤 → 按步骤执行」，
--       而不是完全依赖模型每轮自主决定调用哪个工具
-- 影响：新增一张表，不改动已有表结构，老智能体（无步骤）行为不变
-- 说明：step_type 取 TOOL / LLM / ANSWER
--       TOOL   —— 调用指定技能，args_template 支持 ${input} 与 ${step1} 占位符
--       LLM    —— 用 instruction 指令调用模型，产出中间结果
--       ANSWER —— 用 instruction 作为最终回答的收尾指令
-- ============================================================

CREATE TABLE IF NOT EXISTS agent_step (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    agent_code VARCHAR(64) NOT NULL COMMENT '所属智能体编码',
    step_no INT NOT NULL COMMENT '执行顺序，从 1 开始',
    step_name VARCHAR(128) DEFAULT NULL COMMENT '步骤显示名称',
    step_type VARCHAR(16) NOT NULL DEFAULT 'TOOL' COMMENT '步骤类型：TOOL/LLM/ANSWER',
    tool_name VARCHAR(64) DEFAULT NULL COMMENT 'step_type=TOOL 时调用的技能编码',
    args_template TEXT COMMENT '工具参数模板 JSON，支持 ${input} 与 ${stepN} 占位符',
    instruction TEXT COMMENT 'step_type=LLM/ANSWER 时的指令文本',
    enabled TINYINT NOT NULL DEFAULT 1 COMMENT '是否启用：1 启用 0 跳过',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_agent_step (agent_code, step_no),
    KEY idx_agent_code (agent_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='智能体步骤编排定义';
