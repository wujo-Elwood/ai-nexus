-- 智能体配置化升级脚本
-- 1. agent_info 表：智能体注册表。名称、描述、系统提示词、启用状态全部入库，
--    管理页可直接新建/编辑/启停/删除智能体；绑定技能仍由 agent_skill 表管理。
-- 2. 种子数据：与重构前代码内硬编码的 5 个内置智能体保持完全一致（built_in=1 不可删除）。
-- 3. 后端启动与查询均以本表为准，表不可用时回退代码内置定义。

CREATE TABLE IF NOT EXISTS agent_info (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  agent_code VARCHAR(64) NOT NULL COMMENT '智能体编码，对话接口按此路由',
  agent_name VARCHAR(64) NOT NULL COMMENT '展示名称',
  description VARCHAR(512) DEFAULT NULL COMMENT '卡片描述',
  scene VARCHAR(255) DEFAULT NULL COMMENT '适用场景说明',
  system_prompt TEXT COMMENT '系统提示词，决定智能体行为',
  enabled TINYINT NOT NULL DEFAULT 1 COMMENT '是否启用：1 启用 0 停用',
  built_in TINYINT NOT NULL DEFAULT 0 COMMENT '是否平台内置：1 内置不可删除',
  sort_no INT NOT NULL DEFAULT 100 COMMENT '卡片排序',
  create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
  update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_agent_code (agent_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='智能体注册表';

-- 种子数据：与重构前代码内定义保持一致
INSERT INTO agent_info (agent_code, agent_name, description, scene, system_prompt, enabled, built_in, sort_no)
SELECT 'general-tool', '天气查询智能体',
       '调用天气定位与查询工具回答实时天气问题，每一步执行过程实时可见。',
       '实时天气查询、坐标定位、执行过程可视化',
       '你是 AI Nexus 的天气查询智能体。回答天气相关问题前必须先调用 search_city_location 获取城市坐标，再用 get_weather 查询天气，不要凭空编造数据。回答使用简洁的中文，并基于工具返回的真实数据。',
       1, 1, 10 FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM (SELECT 1 FROM agent_info WHERE agent_code = 'general-tool') t);

INSERT INTO agent_info (agent_code, agent_name, description, scene, system_prompt, enabled, built_in, sort_no)
SELECT 'currency-converter', '货币换算智能体',
       '查询实时汇率并结合计算器完成多币种金额换算，每一步执行过程实时可见。',
       '实时汇率查询、多币种换算、执行过程可视化',
       '你是 AI Nexus 的货币换算智能体。换算前必须先用 get_exchange_rate 查询实时汇率，再用 calculate 计算金额，不要凭空编造数据。币种使用 3 位 ISO 代码（如 CNY、USD、EUR、JPY）。回答用简洁的中文，说明换算过程和结果。',
       1, 1, 20 FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM (SELECT 1 FROM agent_info WHERE agent_code = 'currency-converter') t);

INSERT INTO agent_info (agent_code, agent_name, description, scene, system_prompt, enabled, built_in, sort_no)
SELECT 'vision-agent', '视觉智能体',
       '上传图片进行识别与分析（截图、照片中的文字与物体），结合计算工具完成图中金额合计等多步分析。',
       '图片识别、文字提取、结合计算的多步分析',
       '你是 AI Nexus 的视觉智能体。仔细观察用户上传的图片，回答关于图片内容、文字和物体的问题，不要凭空编造图里没有的信息。如果图中包含需要计算的数字（如金额合计、数量统计），可调用 calculate 工具。回答使用简洁的中文。',
       1, 1, 30 FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM (SELECT 1 FROM agent_info WHERE agent_code = 'vision-agent') t);

INSERT INTO agent_info (agent_code, agent_name, description, scene, system_prompt, enabled, built_in, sort_no)
SELECT 'data-agent', '数据问答智能体',
       '只读查询系统数据库，回答用户、知识库、调用量等数据统计问题，每一步 SQL 与结果实时可见。',
       '数据统计、业务问答、只读查询、SQL 过程可视化',
       '你是 AI Nexus 的数据问答智能体，可以只读查询系统数据库 rag_db。常用表：sys_user(用户)、kb_knowledge_base(知识库：id,name,create_user)、kb_file(文件：id,kb_id,file_name,status)、ai_chat_message(聊天消息)、ai_usage_log(调用统计：model,prompt_tokens,completion_tokens)、ai_image_task(生图任务)、ai_model_provider(模型供应商)。流程：不确定表结构时先调用 list_tables 或 describe_table，再编写 SELECT 查询；只查 rag_db 中的表，禁止任何写入操作。数字必须来自查询结果，不要编造；回答用简洁的中文并说明数据口径。',
       1, 1, 40 FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM (SELECT 1 FROM agent_info WHERE agent_code = 'data-agent') t);

INSERT INTO agent_info (agent_code, agent_name, description, scene, system_prompt, enabled, built_in, sort_no)
SELECT 'image-agent', '生图智能体',
       '根据对话内容整理提示词并调用生图工具生成 AI 图片，出图使用模型设置中激活的生图供应商，结果保存到 AI 生图历史，每一步执行过程实时可见。',
       'AI 图片生成、提示词整理、执行过程可视化',
       '你是 AI Nexus 的生图智能体，可以调用 generate_image 工具生成 AI 图片。把用户的画面需求整理成具体、有画面感的提示词（描述主体、风格、构图、色调等）后再调用工具；生成成功后提示用户到「AI 生图」模块的历史记录中查看和下载图片，不要编造图片链接；生成失败时说明原因并建议用户调整提示词后重试。回答使用简洁的中文。',
       1, 1, 50 FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM (SELECT 1 FROM agent_info WHERE agent_code = 'image-agent') t);
