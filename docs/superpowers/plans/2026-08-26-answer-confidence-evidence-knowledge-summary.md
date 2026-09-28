# 回答置信度、证据覆盖率与知识摘要 Implementation Plan

> **For agentic workers:** Execute this plan inline in the current session. Automatic report generation is explicitly excluded.

**Goal:** 为现有 RAG 问答增加可解释的置信度和证据覆盖率，并在知识库洞察页提供可刷新、可持久化的知识摘要。

**Architecture:** 复用现有 `KnowledgeContext`、`KnowledgeCitation`、`TrustedAnswerGuard` 和激活模型配置。回答指标在 `ChatService` 完成可信校验后计算；摘要由独立 `KnowledgeSummaryService` 读取当前版本切片，使用分组摘要和合并摘要，持久化到知识库表。

**Tech Stack:** Spring Boot 3.2.5, MyBatis, MySQL, LangChain4j/OpenAI-compatible API, Vue 3, Element Plus, JUnit 5.

---

### Task 1: 添加摘要字段和升级脚本

**Files:**
- Modify: `database/init.sql`
- Create: `database/kb_summary_upgrade.sql`

- [ ] Add nullable `summary LONGTEXT` and `summary_updated_at DATETIME` to the knowledge-base table in the full initialization script.
- [ ] Add an idempotent MySQL 5.7 upgrade script using `information_schema.COLUMNS` and prepared `ALTER TABLE` statements.
- [ ] Execute initialization and upgrade script twice against a temporary database.

### Task 2: 扩展知识库实体、Mapper 和接口

**Files:**
- Modify: `rag-server/src/main/java/com/rag/entity/KnowledgeBase.java`
- Modify: `rag-server/src/main/java/com/rag/mapper/KnowledgeBaseMapper.java`
- Modify: `rag-server/src/main/resources/mapper/KnowledgeBaseMapper.xml`
- Modify: `rag-server/src/main/java/com/rag/controller/KnowledgeBaseController.java`
- Create: `rag-server/src/main/java/com/rag/kb/KnowledgeSummaryService.java`

- [ ] Write failing tests for summary query and successful persistence.
- [ ] Add `summary` and `summaryUpdatedAt` properties and mapper methods for query/update.
- [ ] Implement `GET /api/kb/{id}/summary` with access validation.
- [ ] Implement `POST /api/kb/{id}/summary` with access validation and service delegation.

### Task 3: Implement summary generation

**Files:**
- Modify: `rag-server/src/main/java/com/rag/mapper/ChunkMapper.java`
- Modify: `rag-server/src/main/resources/mapper/ChunkMapper.xml`
- Create or modify tests under `rag-server/src/test/java/com/rag/kb/`

- [ ] Write failing tests for no completed chunks, successful group/merge generation, and LLM failure preserving the old summary.
- [ ] Query chunks only from current, completed files owned by the knowledge base.
- [ ] Group content within a bounded prompt size, generate partial summaries, then merge partial summaries with the active model.
- [ ] Save the final summary and timestamp only after the complete generation succeeds.
- [ ] Return a clear business error when no completed document exists.

### Task 4: Add answer confidence and evidence coverage

**Files:**
- Modify: `rag-server/src/main/java/com/rag/ai/ChatService.java`
- Modify: `rag-web/src/views/chat/ChatView.vue`
- Modify or create tests under `rag-server/src/test/java/com/rag/ai/`

- [ ] Write failing tests for high-confidence cited answers, low-confidence answers, ordinary chat without evidence, and no-evidence refusal.
- [ ] Calculate confidence from grounded state, valid citation count, and final citation scores, clamped to 0-100.
- [ ] Calculate evidence coverage as the ratio of answer sentences containing valid source markers, clamped to 0-100.
- [ ] Add `confidence`, `confidenceLevel`, and `evidenceCoverage` to existing `answer-meta` SSE payloads without changing existing fields.
- [ ] Render the metrics beneath assistant messages while preserving existing citation rendering and refusal behavior.

### Task 5: Add knowledge summary UI

**Files:**
- Modify: `rag-web/src/api/kbInsights.js`
- Modify: `rag-web/src/views/kb/KnowledgeBaseInsightsView.vue`

- [ ] Add API wrappers for summary query and generation.
- [ ] Add summary panel with content, timestamp, loading state, empty state, and refresh action.
- [ ] Keep the previous summary visible while a refresh is running or fails.
- [ ] Match existing dark UI, responsive layout, and loading overlay conventions.

### Task 6: Verify and document

**Files:**
- Modify: `README.md`

- [ ] Document the three features and the two summary endpoints; explicitly state automatic reports are not included.
- [ ] Run `mvn.cmd test` and confirm zero failures/errors.
- [ ] Run `npm.cmd run build` and confirm exit code 0.
- [ ] Run `git diff --check` and inspect the final diff for unrelated changes.
