-- AI 资讯 + 邮件推送升级脚本
-- 1. 内置"AI资讯助手"智能体：fetch_ai_news 检索 Hacker News、send_email 发送简报
-- 2. 技能目录由后端启动同步自动注册，无需建表
-- 3. 邮件发送需在配置文件设置 SMTP（MAIL_HOST/MAIL_USERNAME/MAIL_PASSWORD）
--    与收件人白名单（MAIL_ALLOWED_RECIPIENTS，逗号分隔，支持 *@域名 通配）

-- 内置智能体：AI资讯助手
INSERT INTO agent_info (agent_code, agent_name, description, scene, system_prompt, enabled, built_in, sort_no)
SELECT 'news-assistant', 'AI资讯助手',
       '从 Hacker News 检索头部 AI 公司动态与 AI 圈热点，翻译成中文简报，可发送到白名单邮箱。',
       'AI 资讯、行业动态、邮件推送',
       '你是 AI Nexus 的资讯助手，监控 OpenAI、Anthropic、Google 等头部 AI 公司动态和 AI 圈热点。调用 fetch_ai_news 技能后，把返回的英文内容翻译成中文，按「头部厂商动态」「AI 圈最热榜」两个板块整理简报：每条含中文标题、基于正文的详细摘要和原文链接，厂商动态用【公司名】分组；不要编造列表之外的内容。用户要求把简报发到邮箱时，调用 send_email 技能（to 为用户给出的邮箱，subject 如「AI 资讯早报 YYYY-MM-DD」，body 为整理好的完整简报文本）；若技能提示收件人不在白名单或邮件未配置，如实告知用户并说明需要管理员配置。',
       1, 0, 55 FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM (SELECT 1 FROM agent_info WHERE agent_code = 'news-assistant') t);

-- 技能绑定（已有绑定记录的智能体不会由启动自动补齐，需显式插入）
INSERT IGNORE INTO agent_skill (agent_code, skill_name) VALUES
  ('news-assistant', 'fetch_ai_news'),
  ('news-assistant', 'send_email');
