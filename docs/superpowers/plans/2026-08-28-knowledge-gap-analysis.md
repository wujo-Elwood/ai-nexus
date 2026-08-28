# 知识缺口分析 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 持久化知识库回答质量，按最近 7 天和 30 天聚类拒答及低质量问题，生成可供运营查看的知识缺口报告。

**Architecture:** 在聊天完成链路写入 `ai_answer_quality` 事实表；分析服务按固定窗口读取事实和最新负反馈，先去重再调用当前激活模型生成 JSON 报告，失败时回退规则聚类。报告写入 `kb_gap_report`，由每日凌晨任务或管理员手动触发更新；前端增加独立报告页和侧栏入口。

**Tech Stack:** Spring Boot 3.2、MyBatis XML、MySQL、LangChain4j OpenAI、Vue 3、Element Plus、Vue Router。

---

### Task 1: 新增回答质量与报告表

**Files:**
- Create: `database/knowledge_gap_analysis.sql`
- Modify: `database/init.sql`

- [ ] **Step 1: 写数据库验收检查脚本**

在 `database/knowledge_gap_analysis.sql` 中先写幂等 DDL，包含：`ai_answer_quality` 的 `kb_id/create_time` 索引、`kb_gap_report` 的 `window_days` 唯一键，以及报告统计字段和 JSON 长文本字段。

- [ ] **Step 2: 运行数据库语法检查**

运行：`mysql --protocol=tcp -uroot -p"Root2022!@#" -e "source database/knowledge_gap_analysis.sql" rag_db`

预期：命令退出码为 0，重复执行不报错。

- [ ] **Step 3: 将同一幂等 DDL 纳入初始化脚本**

在 `database/init.sql` 的聊天/反馈表附近加入两张表，保证全新部署自动创建；不得修改已有表字段。

- [ ] **Step 4: 检查索引与唯一约束**

运行：`mysql --protocol=tcp -uroot -p"Root2022!@#" -e "SHOW CREATE TABLE ai_answer_quality; SHOW CREATE TABLE kb_gap_report" rag_db`

预期：质量表存在窗口查询索引，报告表存在 `uk_window_days` 唯一约束。

### Task 2: 回答质量事实 Mapper 与实体

**Files:**
- Create: `rag-server/src/main/java/com/rag/entity/AnswerQuality.java`
- Create: `rag-server/src/main/java/com/rag/mapper/AnswerQualityMapper.java`
- Create: `rag-server/src/main/resources/mapper/AnswerQualityMapper.xml`
- Test: `rag-server/src/test/java/com/rag/mapper/AnswerQualityMapperTest.java`

- [ ] **Step 1: 写 Mapper 失败测试**

测试 `insert` 能保存事实，`findSamples` 只返回窗口内且满足拒答、低置信度、低覆盖率或无帮助反馈的记录；使用项目现有 Spring Boot/MyBatis 测试配置和真实临时数据库连接方式。

- [ ] **Step 2: 运行测试确认失败**

运行：`mvn -B -Dtest=AnswerQualityMapperTest test`

预期：因实体/Mapper/表或查询尚未实现而失败。

- [ ] **Step 3: 实现实体和 XML 查询**

实体字段使用 `Long id, Long answerMessageId, Long sessionId, Long kbId, String question, Integer confidence, Integer evidenceCoverage, Boolean grounded, Boolean refusal, String reason, LocalDateTime createTime`。Mapper 提供 `insert(AnswerQuality)` 和 `findSamples(@Param("from") LocalDateTime from)`；SQL 用 `LEFT JOIN ai_feedback` 并按回答消息取最新反馈，过滤 `refusal = 1 OR confidence < 50 OR evidence_coverage < 50 OR helpful = 0`。

- [ ] **Step 4: 运行 Mapper 测试确认通过**

运行同一命令，预期测试通过且查询不返回窗口外样本。

### Task 3: 报告实体、Mapper 与报告快照读写

**Files:**
- Create: `rag-server/src/main/java/com/rag/entity/KnowledgeGapReport.java`
- Create: `rag-server/src/main/java/com/rag/mapper/KnowledgeGapReportMapper.java`
- Create: `rag-server/src/main/resources/mapper/KnowledgeGapReportMapper.xml`
- Test: `rag-server/src/test/java/com/rag/mapper/KnowledgeGapReportMapperTest.java`

- [ ] **Step 1: 写报告读写失败测试**

覆盖按 `windowDays` 查询、首次插入、成功更新和失败更新保留旧 `reportJson` 的行为。

- [ ] **Step 2: 运行测试确认失败**

运行：`mvn -B -Dtest=KnowledgeGapReportMapperTest test`

预期：因 Mapper 未定义或 SQL 未实现而失败。

- [ ] **Step 3: 实现报告 Mapper**

提供 `findByWindowDays(int)`, `insert(KnowledgeGapReport)`, `updateSuccess(...)`, `updateFailure(...)`；失败更新只更新 `status/errorMessage/generatedAt`，不得覆盖最近成功的 `reportJson`。

- [ ] **Step 4: 运行测试确认通过**

运行同一命令，预期全部通过。

### Task 4: 在聊天链路写入回答质量事实

**Files:**
- Modify: `rag-server/src/main/java/com/rag/ai/ChatService.java`
- Test: `rag-server/src/test/java/com/rag/ai/ChatServiceAnswerQualityTest.java`

- [ ] **Step 1: 写同步和流式拒答记录失败测试**

验证知识库无证据拒答写入 `refusal=true, reason=NO_EVIDENCE, confidence=0, evidenceCoverage=0`；验证有证据流式回答使用 `AnswerEvidenceMetrics` 的 confidence/evidenceCoverage，并传入回答消息 ID。

- [ ] **Step 2: 运行测试确认失败**

运行：`mvn -B -Dtest=ChatServiceAnswerQualityTest test`

预期：因 ChatService 没有质量写入调用而失败。

- [ ] **Step 3: 实现最小写入调用**

新增 `AnswerQualityMapper` 构造注入；保存助手消息时返回生成的消息 ID，随后写入质量事实。同步拒答、同步正常回答、流式拒答、流式正常回答四条路径都要写入；非知识库聊天跳过。质量写入异常只记录日志，不影响回答返回或 SSE 完成。

- [ ] **Step 4: 运行测试确认通过并回归聊天测试**

运行：`mvn -B -Dtest=ChatServiceAnswerQualityTest,ChatServiceTest test`

预期：新增测试和既有聊天测试全部通过。

### Task 5: 实现规则聚类与 LLM 报告生成服务

**Files:**
- Create: `rag-server/src/main/java/com/rag/knowledgegap/KnowledgeGapAnalysisService.java`
- Create: `rag-server/src/main/java/com/rag/knowledgegap/KnowledgeGapReportData.java`
- Test: `rag-server/src/test/java/com/rag/knowledgegap/KnowledgeGapAnalysisServiceTest.java`

- [ ] **Step 1: 写样本筛选、去重和回退测试**

覆盖：只接受 7/30 窗口；同一问题忽略空白和大小写后只保留一条；空样本返回空主题报告；LLM 返回非法 JSON 时返回规则聚类且保留错误信息；并发调用第二次抛出 409 `知识缺口分析正在进行`。

- [ ] **Step 2: 运行测试确认失败**

运行：`mvn -B -Dtest=KnowledgeGapAnalysisServiceTest test`

预期：因服务未实现而失败。

- [ ] **Step 3: 实现服务核心流程**

服务提供 `getReport(int days)`, `triggerAnalysis(int days, Long userId)`, `analyzeWindow(int days)`；用 `ReentrantLock` 防止定时任务与手动触发并发。样本 DTO 只发送问题、指标和拒答原因；LLM 要求严格 JSON 数组，每个主题包含 `topic`, `count`, `representativeQuestions`, `gap`, `suggestedDocuments`, `priority`。使用 Jackson 解析，失败时按问题中的中文分词/前缀做确定性分组，保证接口始终有可展示结构。报告 JSON 额外保存 `generatedAt` 与 `analysisMethod`。

- [ ] **Step 4: 运行测试确认通过**

运行同一命令，预期服务测试全部通过。

### Task 6: 定时任务、控制器和权限

**Files:**
- Create: `rag-server/src/main/java/com/rag/knowledgegap/KnowledgeGapController.java`
- Create: `rag-server/src/main/java/com/rag/knowledgegap/KnowledgeGapScheduler.java`
- Create: `rag-server/src/main/java/com/rag/rbac/annotation/RequireRole.java`
- Modify: `rag-server/src/main/java/com/rag/config/WebConfig.java`
- Modify: `database/rbac_management.sql`
- Test: `rag-server/src/test/java/com/rag/knowledgegap/KnowledgeGapControllerTest.java`

- [ ] **Step 1: 写控制器失败测试**

覆盖 `GET /api/knowledge-gaps/report?days=7|30` 登录可读；`days=14` 返回 400；`POST /api/knowledge-gaps/analyze?days=7` 普通用户返回 403，管理员返回 200/任务已提交；重复分析返回 409。

- [ ] **Step 2: 运行测试确认失败**

运行：`mvn -B -Dtest=KnowledgeGapControllerTest test`

预期：因路由和权限未实现而失败。

- [ ] **Step 3: 实现接口和每日任务**

控制器使用现有 JWT request userId 和 `@RequirePermission("knowledge-gap:view")`；分析接口使用新建的 `@RequireRole("admin")`。在 `WebConfig` 的认证拦截器中先校验方法/类上的 `@RequireRole`，再校验 `@RequirePermission`，拒绝时沿用 HTTP 200 + `Result{code:403}`。调度器使用 `@Scheduled(cron = "0 0 3 * * *")`，依次调用 7 和 30 天分析并记录异常，不阻断下一窗口。

- [ ] **Step 4: 增加 RBAC 菜单与授权初始化**

在 `database/rbac_management.sql` 增加 `/knowledge-gaps` 菜单、`knowledge-gap:view` 权限；管理员授权该菜单，普通用户只读授权该菜单。确保重复执行脚本不产生重复菜单。

- [ ] **Step 5: 运行控制器和权限回归测试**

运行：`mvn -B -Dtest=KnowledgeGapControllerTest,WebConfigPermissionTest,RbacServiceTest test`

预期：全部通过。

### Task 7: 前端知识缺口报告页面与入口

**Files:**
- Create: `rag-web/src/api/knowledgeGaps.js`
- Create: `rag-web/src/views/knowledgegap/KnowledgeGapView.vue`
- Modify: `rag-web/src/router/index.js`
- Modify: `rag-web/src/layouts/MainLayout.vue`
- Modify: `rag-web/src/views/kb/KbView.vue`

- [ ] **Step 1: 写页面契约检查**

先在页面源码中固定两个窗口值 `7`、`30`、报告接口调用、管理员刷新按钮、分析中轮询和 `onBeforeUnmount` 清理；运行 `rg` 检查应因页面尚不存在而失败。

- [ ] **Step 2: 实现 API、路由和菜单**

API 提供 `getKnowledgeGapReport(days)` 和 `analyzeKnowledgeGap(days)`；路由为 `/knowledge-gaps`；侧栏菜单符号映射加入 `knowledge-gap:view`。知识库首页增加“知识缺口分析”按钮跳转该页。

- [ ] **Step 3: 实现报告页面**

页面顶部用 Element Plus 分段按钮切换 7/30 天；展示四个统计数、状态/生成时间、主题表格或主题卡片；管理员显示“立即重新分析”，提交后每 2 秒轮询报告直到不再 `RUNNING`；请求错误保留已有报告并提示。

- [ ] **Step 4: 构建验证**

运行：`npm run build`（工作目录 `rag-web`）。

预期：退出码 0，产物包含 `KnowledgeGapView`，无 Vue 编译错误。

### Task 8: 端到端与完整回归

**Files:**
- Modify: `README.md`（补充知识缺口接口和页面入口说明）
- Test: `rag-server/src/test/java/com/rag/knowledgegap/*`

- [ ] **Step 1: 运行后端全量测试**

运行：`mvn -B test`（工作目录 `rag-server`）。预期：0 failures。

- [ ] **Step 2: 启动后端并检查报告接口**

运行：`mvn.cmd spring-boot:run`，调用 `GET /api/knowledge-gaps/report?days=7` 和 `?days=30`，验证统一 `Result` 包装、未生成时 `NOT_GENERATED` 和空主题结构。

- [ ] **Step 3: 启动前端并验证路由源码**

运行：`npm run dev -- --host 127.0.0.1`，登录后打开 `/knowledge-gaps`，验证两个窗口切换、统计展示、手动分析按钮和轮询停止。

- [ ] **Step 4: 做最终差异检查**

运行：`git diff --check` 和 `git status --short`。不得覆盖用户已有未提交修改；只报告本功能相关新增/修改文件。
