-- 智能体对话历史持久化升级脚本
-- 1. agent_conversation：会话表（用户 + 智能体维度，标题取首条用户消息）
-- 2. agent_conversation_message：消息表（用户/助手消息逐条追加，
--    助手消息附带 steps_json 保存工具调用步骤，恢复后可重建图表与导出按钮）

CREATE TABLE IF NOT EXISTS agent_conversation (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  user_id BIGINT NOT NULL COMMENT '所属用户',
  agent_code VARCHAR(64) NOT NULL COMMENT '智能体编码',
  title VARCHAR(255) NOT NULL COMMENT '会话标题（首条用户消息截断）',
  create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
  update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  KEY idx_user_agent (user_id, agent_code, update_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='智能体对话会话';

CREATE TABLE IF NOT EXISTS agent_conversation_message (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  conversation_id BIGINT NOT NULL COMMENT '所属会话',
  role VARCHAR(20) NOT NULL COMMENT '角色：user/assistant',
  content MEDIUMTEXT NOT NULL COMMENT '消息内容',
  steps_json MEDIUMTEXT COMMENT '助手消息的工具步骤 JSON（图表/导出恢复用）',
  create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
  KEY idx_conversation (conversation_id, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='智能体对话消息';
