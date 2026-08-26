# 系统健康面板实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans. Steps use checkbox (`- [ ]`) syntax.

**Goal:** 在现有管理端新增受 RBAC 保护的系统健康面板，展示 MySQL、Qdrant、Embedding、LLM、磁盘空间和线程池状态。

**Architecture:** 后端新增独立 `SystemHealthService` 聚合基础设施检查，返回统一状态对象；线程池改为可观测的 `ThreadPoolExecutor` Bean。前端新增 `/health` 页面和 API，沿用现有布局、RBAC 菜单和请求封装。

**Tech Stack:** Spring Boot 3.2、MyBatis、JDBC DataSource、RestTemplate、Java NIO、Vue 3、Element Plus、MySQL。

---

### Task 1: 后端健康检查模型和失败测试

**Files:**
- Create: `rag-server/src/main/java/com/rag/health/HealthStatus.java`
- Create: `rag-server/src/main/java/com/rag/health/HealthComponent.java`
- Create: `rag-server/src/main/java/com/rag/health/SystemHealthResponse.java`
- Create: `rag-server/src/test/java/com/rag/health/SystemHealthServiceTest.java`

- [x] **Step 1: Write failing tests**
  - MySQL、磁盘和线程池检查返回统一字段；
  - 外部服务异常只返回 `DOWN` 和脱敏消息；
  - API Key、数据库密码和绝对路径不出现在响应消息中。

- [x] **Step 2: Run focused test and confirm missing service failure**
  - Run: `mvn -q "-Dtest=SystemHealthServiceTest" test`
  - Expected: compilation failure because `SystemHealthService` has not been created.

### Task 2: 实现后端健康聚合接口

**Files:**
- Create: `rag-server/src/main/java/com/rag/health/SystemHealthService.java`
- Create: `rag-server/src/main/java/com/rag/health/SystemHealthController.java`
- Modify: `rag-server/src/main/resources/application.yml`
- Create: `rag-server/src/test/java/com/rag/health/SystemHealthControllerTest.java`

- [x] **Step 1: Implement checks**
  - MySQL 使用 `DataSource.getConnection()` 执行 `SELECT 1`；
  - Qdrant 请求 collection 地址；
  - Embedding 使用可配置 `health-url`，默认由 `/api/embeddings` 推导 `/api/tags`；
  - LLM 请求当前激活供应商的 `/models`，不发送聊天请求；
  - 磁盘使用 `FileStore` 统计容量；
  - 外部检查设置短连接超时；
  - 所有异常映射为脱敏的 `DOWN`。

- [x] **Step 2: Add authenticated endpoint**
  - `GET /api/system-health/overview`
  - 未登录返回 401；已登录返回统一健康结构。

- [x] **Step 3: Run focused tests**
  - Run: `mvn -q "-Dtest=SystemHealthServiceTest,SystemHealthControllerTest" test`

### Task 3: 线程池指标和 RBAC 菜单

**Files:**
- Modify: `rag-server/src/main/java/com/rag/RagApplication.java`
- Modify: `database/rbac_management.sql`
- Create: `database/system_health_upgrade.sql`
- Modify: `rag-web/src/layouts/MainLayout.vue`
- Modify: `rag-web/src/router/index.js`

- [x] **Step 1: Replace fixed executor return types with observable `ThreadPoolExecutor` Beans**
  - 保持现有 Bean 名称、线程数和调用方式不变；
  - 健康服务按 Bean 名称读取活动线程、池大小、队列长度和完成任务数。

- [x] **Step 2: Add idempotent `health:view` menu upgrade**
  - 新增 `/health` 菜单；
  - 只给 `admin` 角色授权；
  - 旧数据库执行脚本两次不能报错。

### Task 4: 前端健康面板

**Files:**
- Create: `rag-web/src/api/health.js`
- Create: `rag-web/src/views/health/SystemHealthView.vue`
- Modify: `rag-web/src/router/index.js`
- Modify: `rag-web/src/layouts/MainLayout.vue`

- [x] **Step 1: Add API and route**
  - 请求 `/api/system-health/overview`；
  - 路由要求登录。

- [x] **Step 2: Build dashboard UI**
  - 总体状态、最后检查时间；
  - 六类检查卡片；
  - 线程池指标表；
  - 手动刷新和 30 秒自动刷新；
  - 错误状态显示脱敏消息。

- [x] **Step 3: Build frontend**
  - Run: `npm.cmd run build`

### Task 5: 集成验证

- [x] **Step 1: Run backend full tests**
  - Run: `mvn test`

- [x] **Step 2: Execute database upgrade twice**
  - Run `database/system_health_upgrade.sql` twice against `rag_db`.

- [x] **Step 3: Start backend and verify real endpoint**
  - Confirm HTTP 200 for authenticated admin;
  - Confirm MySQL and disk are `UP`;
  - Confirm unavailable Qdrant/Embedding/LLM are `DOWN` without secrets.

- [x] **Step 4: Run `git diff --check` and frontend helper/build checks**
