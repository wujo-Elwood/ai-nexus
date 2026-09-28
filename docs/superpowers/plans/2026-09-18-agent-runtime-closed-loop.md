# Agent 运行时闭环 Implementation Plan

**Goal:** 把 `agent` 包从"规则引擎 + 报告模板"升级为真正的 Agent 运行时——能规划、能调用工具、能被审批闸门拦截、能在挂起后从断点恢复、能被观测和评测。

**Architecture:** 新增独立于现有质检链路的运行时层 `agent/runtime`，其中 `LlmPlanner` 负责单轮决策（同步调用，产出文本或 tool_calls），`ToolRegistry` 负责工具的注册、参数校验、权限与风险分级，`AgentLoop` 负责循环、预算与终止，`AgentRunStore` 负责把消息快照和每步轨迹持久化到 MySQL 以支持挂起恢复。现有 `KnowledgeQualityAgentService` 不改，作为"质检工具"被注册进注册表；现有 `TaskCenterService`、`chatStreamExecutor`、`ai_usage_log` 全部复用，不另起炉灶。

**Tech Stack:** Spring Boot 3.2.5、MyBatis XML、MySQL 5.7+、LangChain4j 0.36.2（OpenAI 兼容）、Vue 3、Element Plus。

---

## 现状体检（动手前必须承认的事实）

| 检查项 | 实际情况 | 影响 |
| --- | --- | --- |
| `KnowledgeQualityAgentService` | 733 行纯规则代码，阈值扣分 + 模板文案，**全程不调用大模型**；注入的 `ChatService` 只用于 `recallTest` | "智能体"目前没有决策层，闭环要从零建，不是改造 |
| `agent_run` 表 | 质检专用字段（`score`/`risk_level`/`check_mode`/`report_json`） | 不能扩成通用运行表，必须新建表 |
| LangChain4j 用法 | 仅 `OpenAiChatModel.generate(messages)` 与流式；每次调用现场 new 模型实例；未使用 tool / memory / AiServices | 工具调用能力要自己搭 |
| SSE 协议 | 前端 `ChatView.vue` 已按事件名分发（`status`/`citation`/`answer-meta`/`done`/`error`），后端 `safeSendJson` 已就绪 | 扩事件是**加分支**，不是破坏性改造；`status` 事件可直接承载工具中间态 |
| 模型调用并发 | 已有 `@Qualifier("chatStreamExecutor")` 线程池 | Agent 循环必须另起池，不能抢占聊天与文件处理 |
| 用量归因 | `ai_usage_log` 已记录 provider/token/耗时/状态 | 加 `session_id` 维度即可归因到 Agent 运行，无需新表 |
| 可信回答护栏 | `TrustedAnswerGuard` + `answer_quality` 已落地 | Agent 的最终回答可直接复用同一套引用校验与拒答 |

**结论：** 阶段 0 到阶段 4 是"从无到有"的建设，阶段 5、6 是"接进现有骨架"。

## 闭环的六个必要条件

缺任何一个都不算闭环：

1. **契约**——模型用什么格式表达"我要调工具"，以及工具的参数 schema。
2. **执行**——谁执行、在哪执行、超时和异常怎么收敛。
3. **状态**——消息怎么累积，以及上下文不能无限膨胀。
4. **闸门**——写操作怎么被拦下、由人批准、然后继续。
5. **终止**——正常完成、轮次耗尽、预算耗尽、用户取消，四条出口都要有。
6. **观测**——每步入参出参落库，行为可回放、成本可归因。

---

## 阶段 0：拆分模型调用（前置重构，先做）

**目标：** 让"一次 LLM 调用"变成可复用的纯函数，与 SSE、会话落库解耦。

**Files:**
- Create: `rag-server/src/main/java/com/rag/ai/LlmClient.java`、`LlmRequest.java`、`LlmResponse.java`
- Modify: `rag-server/src/main/java/com/rag/ai/ChatService.java`（改为 `LlmClient` 的调用方）

**关键设计：** `LlmClient.chat(LlmRequest)` 接收 `messages + tools + temperature + timeout`，返回 `LlmResponse{content, toolCalls, promptTokens, completionTokens}`。`ChatService` 只保留"检索 → 编排 prompt → 调 LlmClient → 校验引用 → 推送 SSE"。模型实例按 `providerId + model + 用途`缓存在 `ConcurrentHashMap`，避免每轮重建。

**坑与解法：**
- 直接改 `ChatService` 有回归风险 → 这是纯重构，**行为必须零变化**，改完立刻跑 `mvn test` 并用真实会话回归一次流式问答。
- `StreamingResponseHandler` 与 `CountDownLatch` 的同步等待写法在循环里会反复阻塞 → 循环阶段一律用同步接口，流式只留给最终回答。

**验收：** 聊天同步与流式行为完全不变，`mvn -B test` 全绿。

## 阶段 1：契约与循环骨架（mock 工具）

**目标：** 用假工具把循环骨架跑通，与业务解耦。

**Files:**
- Create: `agent/runtime/ToolRegistry.java`、`ToolSpec.java`、`AgentLoop.java`、`AgentContext.java`
- Create: `agent/runtime/impl/EchoTool.java`（压测用）
- Test: `rag-server/src/test/java/com/rag/agent/runtime/AgentLoopTest.java`

**关键设计：** `ToolSpec` = `{name, description, parameterSchema, riskLevel, permissionCode, executor}`。`AgentLoop` 每轮：调 `LlmClient` → 无 tool_calls 则终止 → 有则逐个执行 → 结果转 `tool` 消息追加 → 下一轮。

**坑与解法：**
- **`tool_call_id` 必须严格配对。** assistant 消息带 `tool_calls`，随后每个结果都要以 `role=tool` + 相同 `tool_call_id` 回灌；少一个、顺序错、id 不符，主流 OpenAI 兼容端点会直接 400。这是最常见的翻车点，必须写单测锁死。
- **一次响应可能返回多个 `tool_calls`。** 必须全部执行并全部回灌，不能只处理第一个。
- **LangChain4j 0.36.2 的流式模型对 tool calling 支持不完整。** 先用一个最小用例验证 `OpenAiChatModel + toolSpecifications` 能否拿到 `toolExecutionRequests`；若不适配，就退到手写 `/chat/completions` HTTP 调用自己解析 JSON（项目在别处已有手写 HTTP 的先例，`normalizeOpenAiBaseUrl` 的注释就是为它留的）。
- **不同供应商支持度不一。** 启动时做一次能力探测（发一个带 `tools` 的最小请求），不支持则报明确错误，不要静默降级成"模型胡编工具名"。

**验收：** mock 工具被连续调用 3 轮后正常终止；单测覆盖 id 配对、多 tool_calls、异常回灌。

## 阶段 2：只读工具接入

**目标：** 用真实数据跑通端到端，且零副作用。

**Files:**
- Create: `agent/runtime/tool/SearchKnowledgeTool.java`、`ReadChunkTool.java`、`ListKbFilesTool.java`
- Create: `agent/runtime/ToolResultTrimmer.java`

**关键设计：** `SearchKnowledgeTool` 直接复用 `ChatService.recallTest`（已验证可用），返回统一 `ToolResult{summary, payload, truncated}`。

**坑与解法：**
- **工具返回体积失控。** 检索 10 个切片可能上万 token，一轮就撑爆上下文。解法三层：工具自己限制返回条数、`ToolResultTrimmer` 做字符上限截断、超出部分只回 `summary` 并告知模型"如需更多请用 `readChunk` 精确取"。
- **工具 description 就是 prompt。** 描述含糊模型就不选它或填错参数。按 API 文档标准写，给出参数含义和一条调用示例。
- **参数不合格不能让循环崩。** 模型给出的 JSON 可能字段缺失或类型错，要 catch 后把校验错误作为 `tool` 结果回灌，让模型自己纠正——这是"自愈"的关键，也是 Agent 与脚本的本质差别。
- **工具必须做权限校验。** 不能只信任模型传的 `kbId`，要用请求上下文里的 `userId` 走 `knowledgeBaseService.checkAccess`，否则等于开了越权读的后门。

**验收：** 在真实知识库上，模型自主调用检索工具并产出带引用的回答。

## 阶段 3：终止与预算护栏

**目标：** 让循环一定停得下来。

**Files:**
- Modify: `agent/runtime/AgentLoop.java`
- Create: `agent/runtime/AgentBudget.java`

**关键设计：** 四条出口——模型给出最终答案 / `stepCount >= maxSteps`（默认 8）/ token 预算耗尽 / 用户 `cancel`。另加**重复调用检测**：连续两次相同 `toolName + args` 直接注入纠正提示，第三次终止。

**坑与解法：**
- **没有护栏必然无限循环烧钱。** 护栏不是优化项，是必需项。
- **失败时不能只返回错误。** 要把已执行的步骤和中间结果一并返回，否则用户面对一个失败的任务完全不知道发生过什么。

**验收：** 构造一个"模型反复要调同一工具"的场景，确认在预算内被拦下且轨迹完整。

## 阶段 4：审批闸门与挂起恢复（真闭环的分水岭）

**目标：** 危险操作必须经人批准，且挂起后能从断点继续。

**Files:**
- Create: `agent/runtime/AgentRunStore.java`、`ApprovalService.java`、`AgentRunRecoveryService.java`
- Create: `agent/controller/AgentRuntimeController.java`
- Create: `database/agent_runtime_upgrade.sql`（并同步 `database/init.sql`）

**关键设计：** 遇到 `riskLevel != READ` 的工具 → 不执行，写 `agent_step` 状态 `PENDING_APPROVAL`、run 状态改 `WAITING_APPROVAL`、返回前端 → 人工批准后由 `ApprovalService` 恢复循环。

**坑与解法：**
- **恢复必须从断点继续，不能从头重跑**，否则之前已生效的写操作会重复执行。因此 `agent_session.messages_json`（完整消息快照）和 `step_count` 必须持久化到 MySQL，**不能只放内存**。
- **幂等键。** 恢复与重试都要靠 `uk_session_step(session_id, step_no)` 唯一约束挡住重复副作用。
- **审批不能无限等。** 设 `expire_time`，超时自动置 `EXPIRED` 并按拒绝处理。
- **进程重启后要能捞回。** 照 `ImageTaskRecoveryService` 的范式写扫描任务，启动时和定时把 `WAITING_APPROVAL` 的 run 重新装载。
- **审批人要有权限约束。** 只能由有对应 `permissionCode` 的用户批准，不能任何登录用户都能放行。

**验收：** 触发一次需审批的写操作，杀掉进程重启，批准后任务仍能走完且副作用只发生一次。

## 阶段 5：流式中间态

**目标：** 用户能看见"正在检索知识库 / 已找到 5 条证据 / 正在创建任务"。

**Files:**
- Modify: `agent/runtime/AgentLoop.java`（推送事件）
- Modify: `rag-web/src/views/chat/ChatView.vue`（`appendSsePart` 增加分支）

**关键设计：** 复用现有事件名。工具开始/结束推 `status`（已有渲染逻辑），新增 `tool` 事件承载结构化轨迹（工具名、参数摘要、结果摘要），`approval_required` 事件触发前端弹审批框。

**坑与解法：**
- 后端 `SseEmitter` 在 Agent 场景下生命周期很长（可能跨审批挂起数分钟）→ **不能把挂起中的 run 绑在 `SseEmitter` 上**，长连接断了任务就丢了。正确做法：SSE 只负责"实时看"，run 的真相在库里，前端重连后拉 `GET /agent/runs/{id}` 补状态。
- 前端 `appendSsePart` 的兜底分支是"未知事件名就当正文追加"，新增事件名必须先加判断，否则会把 JSON 当答案打到聊天框里。

**验收：** 一次需要审批的完整运行，前端能依次看到检索、工具调用、待审批、完成四个阶段。

## 阶段 6：观测与评测

**目标：** 行为可回放、成本可归因、质量可回归。

**Files:**
- Create: `rag-web/src/views/agent/AgentRunDetailView.vue`（轨迹时间线）
- Modify: `agent` 相关 Controller、`ai_usage_log` 增加 `agent_session_id` 列

**关键设计：** 轨迹页按步展示 `思考 / 工具 / 观察 / 审批`；`ai_usage_log` 按 run 聚合 token 与耗时；Agent 评测纳入 `kb_eval_case` 思路——给定任务，断言"应调用哪些工具、不应调用哪些工具"。

**坑与解法：** 没有观测时，调 prompt 就是玄学——行为一变就无法归因。这一步不能省，但可以放在闭环成立之后。

**验收：** 任意历史运行能完整回放出每一步的入参和出参。

---

## 表结构草案

```sql
CREATE TABLE IF NOT EXISTS agent_session (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  agent_code VARCHAR(64) NOT NULL,
  title VARCHAR(255),
  status VARCHAR(32) NOT NULL,
  goal TEXT,
  kb_id BIGINT,
  messages_json LONGTEXT,
  step_count INT DEFAULT 0,
  prompt_tokens INT DEFAULT 0,
  completion_tokens INT DEFAULT 0,
  max_steps INT DEFAULT 8,
  error_message VARCHAR(1000),
  created_by BIGINT NOT NULL,
  create_time DATETIME,
  update_time DATETIME,
  KEY idx_user_time (created_by, create_time),
  KEY idx_status (status)
);

CREATE TABLE IF NOT EXISTS agent_step (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  session_id BIGINT NOT NULL,
  step_no INT NOT NULL,
  step_type VARCHAR(32) NOT NULL,
  tool_name VARCHAR(64),
  tool_args JSON,
  tool_result MEDIUMTEXT,
  tool_status VARCHAR(32),
  duration_ms INT,
  error_message VARCHAR(1000),
  create_time DATETIME,
  UNIQUE KEY uk_session_step (session_id, step_no)
);

CREATE TABLE IF NOT EXISTS agent_approval (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  session_id BIGINT NOT NULL,
  step_no INT NOT NULL,
  tool_name VARCHAR(64) NOT NULL,
  tool_args JSON,
  risk_level VARCHAR(16) NOT NULL,
  status VARCHAR(24) NOT NULL,
  reason VARCHAR(500),
  decided_by BIGINT,
  decide_time DATETIME,
  expire_time DATETIME,
  create_time DATETIME,
  UNIQUE KEY uk_session_step (session_id, step_no)
);
```

`agent_run` 保持不动，质检报告继续写它，两套并存不迁移。

## 风险排序（会推倒重来的三个点）

1. **`tool_call_id` 配对与消息顺序**（阶段 1）——错了每个供应商都报错，且错误信息不直观。
2. **工具返回体积失控**（阶段 2）——上线后才发现，表现为"偶发调用失败"或成本飙升。
3. **挂起上下文的持久化与幂等**（阶段 4）——做错了是数据事故，不是体验问题。

## 明确不做

- 不改 `agent_run` 表结构，不做数据迁移。
- 不在阶段 1-4 动 DAG 编排，先把单 Agent 闭环跑扎实。
- 不引入外部工作流引擎，编排用现有线程池自建。
- 不替换 `TrustedAnswerGuard`，Agent 最终回答继续走同一套引用校验。
