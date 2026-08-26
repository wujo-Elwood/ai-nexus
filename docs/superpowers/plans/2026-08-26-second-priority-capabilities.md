# 第二优先级企业能力实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 为现有知识库系统补齐任务中心、检索调试、RAG 评测、策略配置和统计面板，并保持现有功能兼容。

**Architecture:** 继续使用 MySQL/MyBatis 保存配置和评测数据；任务中心读取现有任务表并统一转换为前端视图；检索调试复用 ChatService 的诊断召回；统计接口聚合已有文件、切片、质量和聊天数据；前端新增页面并在知识库、聊天和主菜单中提供入口。

**Tech Stack:** Spring Boot 3.2、MyBatis、MySQL、Vue 3、Element Plus、Vite。

### Task 1: 知识库策略与统计数据层

**Files:** `database/kb_second_priority_upgrade.sql`, `KnowledgeBase.java`, `KnowledgeBaseMapper.java`, `KnowledgeBaseMapper.xml`, `KnowledgeBaseService.java`, new `KnowledgeBaseStatsService.java` and tests.

- [ ] 增加可重复执行的策略字段和评测表。
- [ ] 增加策略查询、更新、范围校验和重索引提交方法。
- [ ] 聚合文件、切片、向量、质量、重复和最近活动统计。
- [ ] 为服务和 SQL 聚合补充测试。

### Task 2: 后端任务中心

**Files:** new `TaskCenterController`, `TaskCenterService`, `TaskCenterItem`, `TaskCenterMapper`; frontend API and view.

- [ ] 按当前用户聚合现有文件处理、上传会话、抽取、智能体和生图任务。
- [ ] 提供状态筛选、分页、重试和取消接口；无法取消的已完成任务返回业务冲突。
- [ ] 新增任务中心页面、筛选、进度、错误和操作按钮。

### Task 3: 检索诊断

**Files:** `ChatService`, `ChatController`, new diagnostic VO/API; `ChatView.vue` and API module.

- [ ] 暴露不调用 LLM 的诊断召回方法，返回改写查询、候选和最终结果。
- [ ] 支持 topK、阈值和权重临时覆盖，限制参数范围。
- [ ] 在聊天页增加调试抽屉和结果表格。

### Task 4: RAG 评测中心

**Files:** new eval entities/mappers/services/controllers, migration, frontend API/view/router/menu.

- [ ] 实现评测集 CRUD、单题/批量运行、运行记录和指标计算。
- [ ] 保存模型和检索参数快照，运行失败可追踪。
- [ ] 接入评测页面和结果详情。

### Task 5: 集成与验证

- [ ] 更新菜单初始化和前端路由。
- [ ] 执行后端测试、前端构建、差异检查。
- [ ] 启动服务验证页面和新增接口返回，记录外部 Embedding/Qdrant/LLM 依赖状态。
