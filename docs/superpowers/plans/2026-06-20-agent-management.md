# Agent Management Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add the 智能体管理 module and the first 知识库质检智能体 with saved quality reports.

**Architecture:** Add a small backend agent package that stores agent runs and builds deterministic knowledge-base quality reports from existing knowledge-base, file, chunk, and recall services. Add one Vue page under `/agents` that lists the quality agent, starts a run, and displays historical reports.

**Tech Stack:** Spring Boot 3.2, MyBatis, MySQL, Vue 3, Element Plus, Axios, Vite.

---

## File Structure

Create backend files:

- `rag-server/src/main/java/com/rag/agent/entity/AgentRun.java`
- `rag-server/src/main/java/com/rag/agent/dto/RunKnowledgeQualityRequest.java`
- `rag-server/src/main/java/com/rag/agent/mapper/AgentRunMapper.java`
- `rag-server/src/main/resources/mapper/AgentRunMapper.xml`
- `rag-server/src/main/java/com/rag/agent/service/KnowledgeQualityAgentService.java`
- `rag-server/src/main/java/com/rag/agent/service/AgentService.java`
- `rag-server/src/main/java/com/rag/agent/controller/AgentController.java`
- `database/agent_management.sql`

Modify backend files:

- `database/init.sql`
- `rag-server/src/main/java/com/rag/mapper/ChunkMapper.java`
- `rag-server/src/main/resources/mapper/ChunkMapper.xml`

Create frontend files:

- `rag-web/src/api/agent.js`
- `rag-web/src/views/agent/AgentView.vue`

Modify frontend files:

- `rag-web/src/router/index.js`
- `rag-web/src/layouts/MainLayout.vue`

Create docs:

- `docs/智能体管理模块使用说明.md`

## Coding Rules For This Project

All new code must follow the user's AGENTS instructions:

- Function and variable names use English camelCase.
- Every function has a Chinese comment above it.
- Key logic comments use `第1步`, `第2步`, and so on.
- Comments are on their own line.
- Avoid excessive abstraction. Keep the quality-check logic readable in one main service with small common helpers only.

## Task 1: Database Table And Mapper

**Files:**

- Create: `database/agent_management.sql`
- Modify: `database/init.sql`
- Create: `rag-server/src/main/java/com/rag/agent/entity/AgentRun.java`
- Create: `rag-server/src/main/java/com/rag/agent/mapper/AgentRunMapper.java`
- Create: `rag-server/src/main/resources/mapper/AgentRunMapper.xml`

- [ ] **Step 1: Add upgrade SQL**

Create `database/agent_management.sql` with this table:

```sql
USE rag_db;

CREATE TABLE IF NOT EXISTS agent_run (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    agent_code VARCHAR(80) NOT NULL COMMENT '智能体编码',
    agent_name VARCHAR(100) NOT NULL COMMENT '智能体名称',
    run_status VARCHAR(20) NOT NULL DEFAULT 'SUCCESS' COMMENT '运行状态',
    kb_id BIGINT COMMENT '知识库ID',
    kb_name VARCHAR(100) COMMENT '知识库名称快照',
    check_mode VARCHAR(20) COMMENT '检查模式',
    score INT DEFAULT 0 COMMENT '质量评分',
    risk_level VARCHAR(20) COMMENT '风险等级',
    summary VARCHAR(1000) COMMENT '报告摘要',
    request_json LONGTEXT COMMENT '运行参数JSON',
    report_json LONGTEXT COMMENT '完整报告JSON',
    error_message TEXT COMMENT '错误信息',
    created_by BIGINT COMMENT '运行用户',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_agent_code (agent_code),
    INDEX idx_kb_id (kb_id),
    INDEX idx_created_by (created_by),
    INDEX idx_create_time (create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

- [ ] **Step 2: Append the same table to init SQL**

Append the same `CREATE TABLE IF NOT EXISTS agent_run` block to `database/init.sql` near the other module tables so fresh deployments include the agent table.

- [ ] **Step 3: Create AgentRun entity**

Create `AgentRun.java`:

```java
package com.rag.agent.entity;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 智能体运行记录实体
 * 保存每次智能体运行的参数、评分和完整报告
 */
@Data
public class AgentRun {
    /** 运行记录ID */
    private Long id;
    /** 智能体编码 */
    private String agentCode;
    /** 智能体名称 */
    private String agentName;
    /** 运行状态 */
    private String runStatus;
    /** 知识库ID */
    private Long kbId;
    /** 知识库名称快照 */
    private String kbName;
    /** 检查模式 */
    private String checkMode;
    /** 质量评分 */
    private Integer score;
    /** 风险等级 */
    private String riskLevel;
    /** 报告摘要 */
    private String summary;
    /** 运行参数JSON */
    private String requestJson;
    /** 完整报告JSON */
    private String reportJson;
    /** 错误信息 */
    private String errorMessage;
    /** 运行用户 */
    private Long createdBy;
    /** 创建时间 */
    private LocalDateTime createTime;
    /** 更新时间 */
    private LocalDateTime updateTime;
}
```

- [ ] **Step 4: Create AgentRunMapper interface**

Create `AgentRunMapper.java`:

```java
package com.rag.agent.mapper;

import com.rag.agent.entity.AgentRun;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 智能体运行记录 Mapper
 * 提供运行记录的新增、查询和删除能力
 */
@Mapper
public interface AgentRunMapper {

    /**
     * 新增智能体运行记录
     */
    int insert(AgentRun agentRun);

    /**
     * 查询智能体运行记录列表
     */
    List<AgentRun> findRecent(@Param("createdBy") Long createdBy, @Param("limit") int limit);

    /**
     * 根据ID查询运行记录
     */
    AgentRun findById(@Param("id") Long id);

    /**
     * 删除运行记录
     */
    int deleteById(@Param("id") Long id, @Param("createdBy") Long createdBy);
}
```

- [ ] **Step 5: Create AgentRunMapper XML**

Create `AgentRunMapper.xml`:

```xml
<?xml version="1.0" encoding="UTF-8" ?>
<!DOCTYPE mapper
        PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN"
        "https://mybatis.org/dtd/mybatis-3-mapper.dtd">
<mapper namespace="com.rag.agent.mapper.AgentRunMapper">

    <insert id="insert" parameterType="com.rag.agent.entity.AgentRun" useGeneratedKeys="true" keyProperty="id">
        INSERT INTO agent_run (
            agent_code, agent_name, run_status, kb_id, kb_name, check_mode,
            score, risk_level, summary, request_json, report_json, error_message, created_by,
            create_time, update_time
        )
        VALUES (
            #{agentCode}, #{agentName}, #{runStatus}, #{kbId}, #{kbName}, #{checkMode},
            #{score}, #{riskLevel}, #{summary}, #{requestJson}, #{reportJson}, #{errorMessage}, #{createdBy},
            NOW(), NOW()
        )
    </insert>

    <select id="findRecent" resultType="com.rag.agent.entity.AgentRun">
        SELECT *
        FROM agent_run
        WHERE created_by = #{createdBy}
        ORDER BY create_time DESC
        LIMIT #{limit}
    </select>

    <select id="findById" resultType="com.rag.agent.entity.AgentRun">
        SELECT *
        FROM agent_run
        WHERE id = #{id}
    </select>

    <delete id="deleteById">
        DELETE FROM agent_run
        WHERE id = #{id}
          AND created_by = #{createdBy}
    </delete>

</mapper>
```

- [ ] **Step 6: Verify mapper resources are detected**

Run:

```powershell
mvn -f rag-server\pom.xml -DskipTests compile
```

Expected: compile reaches Java compilation. It may fail later because service/controller are not created yet if this task is run alone. If this task is committed independently, run the command after Task 2 instead.

## Task 2: Backend DTO And Chunk Queries

**Files:**

- Create: `rag-server/src/main/java/com/rag/agent/dto/RunKnowledgeQualityRequest.java`
- Modify: `rag-server/src/main/java/com/rag/mapper/ChunkMapper.java`
- Modify: `rag-server/src/main/resources/mapper/ChunkMapper.xml`

- [ ] **Step 1: Create run request DTO**

Create `RunKnowledgeQualityRequest.java`:

```java
package com.rag.agent.dto;

import lombok.Data;

import java.util.List;

/**
 * 知识库质检运行请求
 * 接收前端选择的知识库、检查模式和测试问题
 */
@Data
public class RunKnowledgeQualityRequest {
    /** 知识库ID */
    private Long kbId;
    /** 检查模式 */
    private String checkMode;
    /** 测试问题列表 */
    private List<String> testQuestions;
    /** 是否保存报告 */
    private Boolean saveReport;
}
```

- [ ] **Step 2: Extend ChunkMapper interface**

Add these methods to `ChunkMapper.java`:

```java
    /**
     * 根据知识库ID查询所有分片
     */
    List<KbChunk> findByKbId(@Param("kbId") Long kbId);

    /**
     * 根据知识库ID统计分片数量
     */
    int countByKbId(@Param("kbId") Long kbId);
```

- [ ] **Step 3: Extend ChunkMapper XML**

Add these selects to `ChunkMapper.xml`:

```xml
    <select id="findByKbId" resultType="com.rag.entity.KbChunk">
        SELECT c.*
        FROM kb_chunk c
        INNER JOIN kb_file f ON c.file_id = f.id
        WHERE f.kb_id = #{kbId}
        ORDER BY c.file_id, c.chunk_index
    </select>

    <select id="countByKbId" resultType="int">
        SELECT COUNT(*)
        FROM kb_chunk c
        INNER JOIN kb_file f ON c.file_id = f.id
        WHERE f.kb_id = #{kbId}
    </select>
```

- [ ] **Step 4: Verify compile after mapper changes**

Run:

```powershell
mvn -f rag-server\pom.xml -DskipTests compile
```

Expected: compile succeeds if Task 1 did not introduce syntax errors.

## Task 3: Knowledge Quality Agent Service

**Files:**

- Create: `rag-server/src/main/java/com/rag/agent/service/KnowledgeQualityAgentService.java`

- [ ] **Step 1: Create the service skeleton**

Create a service that injects:

```java
private final KnowledgeBaseService knowledgeBaseService;
private final FileMapper fileMapper;
private final ChunkMapper chunkMapper;
private final ChatService chatService;
```

Use constructor injection.

- [ ] **Step 2: Implement runQualityCheck**

Add one public method:

```java
/**
 * 运行知识库质检
 */
public Map<String, Object> runQualityCheck(RunKnowledgeQualityRequest request, Long userId)
```

Required logic:

1. Validate `kbId`.
2. Call `knowledgeBaseService.checkAccess(kbId, userId)`.
3. Load `KnowledgeBase`, `List<KbFile>`, and `List<KbChunk>`.
4. Normalize mode to `QUICK`, `STANDARD`, or `DEEP`.
5. Build metrics map.
6. Build issue list.
7. Run recall checks for STANDARD and DEEP.
8. Compute score.
9. Compute risk level.
10. Return report map.

- [ ] **Step 3: Implement metrics in the same service**

Metrics must include:

```java
fileTotal
completedFileCount
failedFileCount
processingFileCount
chunkTotal
emptyChunkCount
shortChunkCount
longChunkCount
missingSourceCount
duplicateFileNameCount
duplicateChunkCount
noiseChunkCount
averageChunkLength
minChunkLength
maxChunkLength
recallQuestionCount
recallEmptyCount
```

- [ ] **Step 4: Implement issue generation**

Issue maps must use this shape:

```java
Map<String, Object> issue = new LinkedHashMap<>();
issue.put("severity", "HIGH");
issue.put("type", "EMPTY_KB");
issue.put("title", "知识库没有可用文件");
issue.put("description", "当前知识库还没有完成入库的文件，问答时无法稳定召回业务资料。");
issue.put("evidence", "已完成文件数：0");
issue.put("suggestion", "先上传并等待文件处理完成，再运行质检。");
```

Required issue types:

- `EMPTY_KB`
- `FAILED_FILE`
- `PROCESSING_FILE`
- `NO_CHUNK`
- `EMPTY_CHUNK`
- `SHORT_CHUNK`
- `LONG_CHUNK`
- `MISSING_SOURCE`
- `DUPLICATE_FILE`
- `DUPLICATE_CHUNK`
- `NOISE_CHUNK`
- `RECALL_EMPTY`
- `RECALL_WEAK`

- [ ] **Step 5: Implement recall checks**

Use `chatService.recallTest(question, kbId)` for each question.

Question source:

- Use user provided questions first.
- If mode is `DEEP` and no user question exists, generate simple default questions from knowledge-base name and up to three file names.
- In `QUICK`, skip recall.

Each recall item must include:

```java
question
hit
resultCount
sources
topContents
```

Limit `topContents` to short snippets so the report is readable.

- [ ] **Step 6: Implement score calculation**

Start from 100.

Suggested deductions:

- Empty knowledge base: minus 45.
- Failed file ratio: up to minus 20.
- Processing file ratio: up to minus 8.
- No chunks: minus 35.
- Empty chunk ratio: up to minus 15.
- Short chunk ratio: up to minus 10.
- Long chunk ratio: up to minus 10.
- Missing source ratio: up to minus 10.
- Duplicate chunk ratio: up to minus 8.
- Noise chunk ratio: up to minus 8.
- Recall empty ratio: up to minus 25.

Clamp score to 0-100.

- [ ] **Step 7: Verify service compiles**

Run:

```powershell
mvn -f rag-server\pom.xml -DskipTests compile
```

Expected: compile succeeds after Task 4 creates AgentService references if this task uses only existing classes.

## Task 4: Agent Service And Controller

**Files:**

- Create: `rag-server/src/main/java/com/rag/agent/service/AgentService.java`
- Create: `rag-server/src/main/java/com/rag/agent/controller/AgentController.java`

- [ ] **Step 1: Create AgentService**

Responsibilities:

- Return static agent list containing one agent: `knowledge-quality`.
- Run knowledge quality agent.
- Save report to `agent_run` when `saveReport` is not false.
- List recent runs.
- Get run detail.
- Delete own run.

Use Jackson `ObjectMapper` to serialize request/report JSON.

- [ ] **Step 2: Create AgentController**

Expose:

```java
GET /api/agents
POST /api/agents/knowledge-quality/run
GET /api/agents/runs
GET /api/agents/runs/{id}
DELETE /api/agents/runs/{id}
```

Use:

```java
Long userId = (Long) httpRequest.getAttribute("userId");
```

Return `Result.success(...)`.

- [ ] **Step 3: Error behavior**

If service throws `BusinessException`, existing global handler should return the error.

If report generation fails unexpectedly, save a failed run only when enough context exists, then rethrow a user-readable `BusinessException`.

- [ ] **Step 4: Verify backend compile**

Run:

```powershell
mvn -f rag-server\pom.xml -DskipTests compile
```

Expected: `BUILD SUCCESS`.

## Task 5: Frontend API, Route, And Menu

**Files:**

- Create: `rag-web/src/api/agent.js`
- Modify: `rag-web/src/router/index.js`
- Modify: `rag-web/src/layouts/MainLayout.vue`

- [ ] **Step 1: Create frontend API**

Create `agent.js`:

```javascript
import request from '../utils/request'

export function getAgentList() {
  return request.get('/api/agents')
}

export function runKnowledgeQuality(data) {
  return request.post('/api/agents/knowledge-quality/run', data)
}

export function getAgentRuns(params) {
  return request.get('/api/agents/runs', { params })
}

export function getAgentRunDetail(id) {
  return request.get(`/api/agents/runs/${id}`)
}

export function deleteAgentRun(id) {
  return request.delete(`/api/agents/runs/${id}`)
}
```

- [ ] **Step 2: Add router entry**

Add:

```javascript
{
  path: '/agents',
  name: 'Agents',
  component: () => import('../views/agent/AgentView.vue'),
  meta: { requiresAuth: true }
}
```

- [ ] **Step 3: Add menu item**

Add one item to `menuItems` in `MainLayout.vue`:

```javascript
{
  path: '/agents',
  label: '智能体管理',
  icon: '<svg viewBox="0 0 24 24" fill="none"><path d="M12 3v4M12 17v4M4.93 4.93l2.83 2.83M16.24 16.24l2.83 2.83M3 12h4M17 12h4M4.93 19.07l2.83-2.83M16.24 7.76l2.83-2.83" stroke="currentColor" stroke-width="2" stroke-linecap="round"/><circle cx="12" cy="12" r="4" stroke="currentColor" stroke-width="2"/></svg>'
}
```

- [ ] **Step 4: Verify frontend imports**

Run:

```powershell
npm --prefix rag-web run build
```

Expected: build may fail because `AgentView.vue` is not created yet if this task is isolated. It should pass after Task 6.

## Task 6: Agent Management Vue Page

**Files:**

- Create: `rag-web/src/views/agent/AgentView.vue`

- [ ] **Step 1: Create page state**

The page must use:

```javascript
const agentList = ref([])
const runList = ref([])
const kbList = ref([])
const selectedRun = ref(null)
const runDialogVisible = ref(false)
const detailDialogVisible = ref(false)
const running = ref(false)
const loading = ref(false)
const runForm = reactive({
  kbId: null,
  checkMode: 'STANDARD',
  testQuestionsText: '',
  saveReport: true
})
```

- [ ] **Step 2: Load data on mount**

Use:

```javascript
onMounted(() => {
  loadAgentPage()
})
```

`loadAgentPage()` must load agent list, run list, and knowledge base list.

- [ ] **Step 3: Implement run dialog**

Dialog fields:

- Knowledge base select.
- Check mode segmented radio: QUICK, STANDARD, DEEP.
- Multiline test questions.
- Save report checkbox.

- [ ] **Step 4: Implement run action**

`handleRunQuality()` must:

1. Validate `kbId`.
2. Split `testQuestionsText` by line.
3. Call `runKnowledgeQuality`.
4. Show success message.
5. Set selected report to returned data.
6. Refresh run history.
7. Open detail dialog.

- [ ] **Step 5: Implement detail display**

Show:

- Score.
- Risk level.
- Summary.
- Metrics.
- Issues.
- Recall tests.
- Suggestions.

Use `el-table`, `el-tag`, and compact cards. Avoid nested UI cards inside cards.

- [ ] **Step 6: Implement empty states**

When no runs exist, show a clear empty state explaining that no quality reports have been generated.

- [ ] **Step 7: Verify frontend build**

Run:

```powershell
npm --prefix rag-web run build
```

Expected: `vite build` succeeds.

## Task 7: Full Documentation

**Files:**

- Create: `docs/智能体管理模块使用说明.md`

- [ ] **Step 1: Write the documentation**

The document must include these sections:

- 模块定位
- 设计思路
- 知识库质检智能体检查什么
- 页面怎么使用
- 报告怎么看
- 评分规则
- 解决了什么问题
- 后续可以扩展哪些智能体
- 数据库脚本说明
- 接口说明

- [ ] **Step 2: Include actual file references**

Mention:

- `database/agent_management.sql`
- `rag-server/src/main/java/com/rag/agent`
- `rag-web/src/views/agent/AgentView.vue`

- [ ] **Step 3: Verify doc has no placeholders**

Run:

```powershell
Select-String -Path 'docs\智能体管理模块使用说明.md' -Pattern 'TODO|TBD|待定|占位'
```

Expected: no output.

## Task 8: End-To-End Verification

**Files:**

- No new files unless fixes are needed.

- [ ] **Step 1: Check changed files**

Run:

```powershell
git status --short
```

Expected: only intended agent module, docs, SQL, router, layout, and mapper files are listed. Existing unrelated dirty files may also appear and must not be reverted.

- [ ] **Step 2: Compile backend**

Run:

```powershell
mvn -f rag-server\pom.xml -DskipTests compile
```

Expected: `BUILD SUCCESS`.

- [ ] **Step 3: Build frontend**

Run:

```powershell
npm --prefix rag-web run build
```

Expected: `vite build` succeeds.

- [ ] **Step 4: Start local services if user wants runtime check**

Use existing approved start command or start backend and frontend separately. Confirm:

- Backend listens on `8080`.
- Frontend listens on `5173` or next free Vite port.
- Login still works with known test account if database is available.

- [ ] **Step 5: Manual API checks**

After applying `database/agent_management.sql`, call:

```http
GET /api/agents
POST /api/agents/knowledge-quality/run
GET /api/agents/runs
```

Expected:

- Agent list contains `knowledge-quality`.
- Run returns a report with `score`, `riskLevel`, `metrics`, `issues`, and `suggestions`.
- Runs list contains saved report when `saveReport=true`.

- [ ] **Step 6: Completion audit**

Check against design spec:

- 智能体管理菜单 exists.
- 知识库质检智能体 appears.
- User can run quality check.
- Report has score, risk, issues, evidence, suggestions.
- Run history can be queried.
- Documentation explains design thinking, usage, and solved problems.

Only mark the objective complete after all required checks pass or any unchecked runtime dependency is clearly reported.
