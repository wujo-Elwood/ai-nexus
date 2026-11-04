-- 技能模块升级脚本
-- 1. skill 表：技能注册表。技能目录（编码/描述）由后端启动时按代码内实现自动同步，
--    本表持久化每个技能的启用状态，供管理页开关。
-- 2. agent_skill 表：智能体与技能的绑定关系，决定每个智能体可调用哪些技能，
--    取代原先 ToolRegistry 中硬编码的分配。
-- 3. 菜单与授权：技能管理页仅授予管理员角色。

CREATE TABLE IF NOT EXISTS skill (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  skill_name VARCHAR(64) NOT NULL COMMENT '技能编码，对应技能实现的 name()',
  description VARCHAR(512) DEFAULT NULL COMMENT '技能说明，启动时按代码同步',
  enabled TINYINT NOT NULL DEFAULT 1 COMMENT '是否启用：1 启用 0 停用',
  create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
  update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_skill_name (skill_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='技能注册表';

CREATE TABLE IF NOT EXISTS agent_skill (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  agent_code VARCHAR(64) NOT NULL COMMENT '智能体编码',
  skill_name VARCHAR(64) NOT NULL COMMENT '技能编码',
  create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_agent_skill (agent_code, skill_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='智能体-技能绑定关系';

-- 种子绑定：与重构前硬编码的分配保持一致，已有数据不覆盖
INSERT IGNORE INTO agent_skill (agent_code, skill_name) VALUES
  ('general-tool', 'search_city_location'),
  ('general-tool', 'get_weather'),
  ('currency-converter', 'get_exchange_rate'),
  ('currency-converter', 'calculate'),
  ('vision-agent', 'calculate'),
  ('data-agent', 'list_tables'),
  ('data-agent', 'describe_table'),
  ('data-agent', 'query_database'),
  ('image-agent', 'generate_image');

-- 菜单：技能管理（挂在智能体管理之后）
INSERT INTO sys_menu (parent_id, menu_name, path, permission_code, sort_no)
SELECT 0, '技能管理', '/skills', 'skills:view', 55 FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM (SELECT 1 FROM sys_menu WHERE permission_code = 'skills:view') t);

-- 授权给管理员角色
INSERT INTO sys_role_menu (role_id, menu_id)
SELECT 1, m.id FROM sys_menu m
WHERE m.permission_code = 'skills:view'
  AND NOT EXISTS (SELECT 1 FROM sys_role_menu rm WHERE rm.menu_id = m.id AND rm.role_id = 1);
