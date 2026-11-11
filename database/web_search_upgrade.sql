-- Web 搜索技能升级脚本
-- 1. web_search_config 表：web_search 技能的搜索源配置，按用户维度存储 API Key。
--    user_id = 0 为平台默认配置（仅管理员可写）；其他用户可保存自己的专属配置。
--    技能执行时优先读取当前用户的配置，没有则回退平台默认配置。
-- 2. 智能体种子：新增内置「联网搜索智能体」并绑定 web_search 技能，已有数据不覆盖。

CREATE TABLE IF NOT EXISTS web_search_config (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  user_id BIGINT NOT NULL DEFAULT 0 COMMENT '所属用户：0=平台默认，其余为用户专属配置',
  provider VARCHAR(32) NOT NULL COMMENT '搜索源：tavily/serper/bocha/searxng',
  api_key VARCHAR(512) DEFAULT NULL COMMENT '搜索源 API Key；searxng 免 Key 可为空',
  base_url VARCHAR(512) DEFAULT NULL COMMENT '自建搜索源地址（searxng 必填），如 http://searxng:8080',
  max_results INT NOT NULL DEFAULT 5 COMMENT '单次搜索返回结果条数上限（1-10）',
  create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
  update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_user_id (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='web_search 技能搜索源配置';

-- 内置联网搜索智能体（已有数据不覆盖）
INSERT INTO agent_info (agent_code, agent_name, description, scene, system_prompt, enabled, built_in, sort_no)
SELECT 'web-search', '联网搜索智能体',
       '调用 web_search 技能实时搜索互联网并基于结果回答，执行过程实时可见。',
       '实时资讯、时事热点、知识库外的事实信息查询',
       '你是 AI Nexus 的联网搜索智能体。回答事实类问题前必须先调用 web_search 搜索最新信息，再基于搜索结果回答；优先综合多条结果交叉验证，不要只依据单条来源下结论。回答用简洁的中文，并在结尾以列表形式注明所引用信息的来源标题和链接。搜索没有结果或结果不可信时如实说明，不要编造。',
       1, 1, 60 FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM (SELECT 1 FROM agent_info WHERE agent_code = 'web-search') t);

-- 默认技能绑定（已有数据不覆盖）
INSERT IGNORE INTO agent_skill (agent_code, skill_name) VALUES
  ('web-search', 'web_search');
