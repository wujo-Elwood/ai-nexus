-- 数据分析（Excel）升级脚本
-- 1. analysis_file 表：上传的表格文件登记（表结构在上传时解析入库，行数据按需从文件读取）
-- 2. 内置"数据分析智能体"：agent_info 种子，绑定 describe_spreadsheet / analyze_spreadsheet 两个技能
--    （技能目录由启动同步自动注册，绑定由 SkillRegistry 启动补齐）
-- 3. 菜单：数据分析（所有登录用户可见）

CREATE TABLE IF NOT EXISTS analysis_file (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  user_id BIGINT NOT NULL COMMENT '所属用户',
  file_name VARCHAR(255) NOT NULL COMMENT '原始文件名',
  file_path VARCHAR(512) NOT NULL COMMENT '服务器存储路径',
  sheets_json TEXT COMMENT '表结构 JSON：[{name, columns:[{name,type}], rowCount}]',
  total_rows INT NOT NULL DEFAULT 0 COMMENT '总数据行数',
  create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
  KEY idx_user_id (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='数据分析-上传表格';

-- 内置智能体：数据分析智能体
INSERT INTO agent_info (agent_code, agent_name, description, scene, system_prompt, enabled, built_in, sort_no)
SELECT 'data-analysis', '数据分析智能体',
       '上传 Excel 表格后用自然语言查数、统计和分组汇总。',
       'Excel 数据分析、查数、统计汇总',
       '你是 AI Nexus 的数据分析智能体，负责分析用户上传的 Excel 表格。用户消息会带有【当前分析文件 ID: N】标记。回答数据问题前必须先调用 describe_spreadsheet 了解列结构，再用 analyze_spreadsheet 做统计（支持 count/sum/avg/min/max/distinct，可分组和筛选）；回答必须基于工具返回的真实数据并说明统计口径，列不存在或无匹配数据时如实告知，不要编造数字。',
       1, 1, 45 FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM (SELECT 1 FROM agent_info WHERE agent_code = 'data-analysis') t);

-- 菜单：数据分析（挂在文档抽取之前）
INSERT INTO sys_menu (parent_id, menu_name, path, permission_code, sort_no)
SELECT 0, '数据分析', '/analysis', 'analysis:view', 35 FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM (SELECT 1 FROM sys_menu WHERE permission_code = 'analysis:view') t);

-- 授权给管理员和普通用户（全员可用的数据工具）
INSERT INTO sys_role_menu (role_id, menu_id)
SELECT r.id, m.id FROM sys_menu m, sys_role r
WHERE m.permission_code = 'analysis:view'
  AND NOT EXISTS (SELECT 1 FROM sys_role_menu rm WHERE rm.menu_id = m.id AND rm.role_id = r.id);
