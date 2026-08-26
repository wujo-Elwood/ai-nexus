# Knowledge Base Capabilities Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在不破坏现有普通上传、分片上传和 RAG 检索的前提下，补齐文件版本、目录标签、去重、表格结构化、备份恢复和知识库健康评分能力，并让图片通过上下文引用参与检索。

**Architecture:** 继续使用 MySQL 保存知识库元数据、文件版本和质量指标，本地磁盘保存原始文件与 ZIP 备份；现有 `FileService` 仍是唯一文件处理入口，普通上传和分片上传都先完成摘要校验再登记文件。解析器输出带来源上下文的文本，图片不做 OCR/视觉理解，只写入图片引用上下文；健康评分作为独立服务按知识库聚合统计，不改变聊天检索协议。

**Tech Stack:** Spring Boot 3.2、MyBatis、MySQL、Apache Tika、Apache POI、Java NIO、Vue 3、Element Plus、Qdrant。

---

### Task 1: 文件生命周期数据库升级

**Files:**
- Create: `database/kb_capabilities_upgrade.sql`
- Modify: `rag-server/src/main/java/com/rag/entity/KbFile.java`
- Modify: `rag-server/src/main/resources/mapper/FileMapper.xml`
- Modify: `rag-server/src/main/java/com/rag/mapper/FileMapper.java`

- [ ] **Step 1: Add migration tables and columns**

  在 `kb_file` 增加 `version_no`、`version_group_id`、`parent_version_id`、`file_sha256`、`folder_id`、`category`、`is_current`、`quality_status`、`quality_score`、`vector_status`、`image_context` 字段；新增 `kb_file_folder`、`kb_file_tag`、`kb_file_tag_rel`、`kb_file_version` 表，使用唯一索引保证同一知识库同一哈希不重复、同一版本组只有一个当前版本。

- [ ] **Step 2: Run migration against a disposable schema and existing `rag_db`**

  Run:

  ```powershell
  Get-Content -Raw database\kb_capabilities_upgrade.sql | & 'C:\Program Files\MySQL\MySQL Server 5.7\bin\mysql.exe' -uroot --password='Root2022!@#'
  ```

  Expected: exit code `0`; rerunning the same script also exits `0`.

- [ ] **Step 3: Extend `KbFile` and mapper result/insert statements**

  Map every new column with nullable-safe Java types. Existing rows default to version `1`, current `1`, vector status `UNKNOWN`; existing queries must continue returning all old fields.

- [ ] **Step 4: Add mapper tests for current-version and hash lookups**

  Test SQL mapping for `findCurrentByVersionGroup`, `findBySha256`, `listVersions` and `markCurrentVersion`, including the duplicate unique-key path.

### Task 2: Upload deduplication and version management

**Files:**
- Modify: `rag-server/src/main/java/com/rag/service/FileService.java`
- Modify: `rag-server/src/main/java/com/rag/service/MultipartUploadService.java`
- Modify: `rag-server/src/main/java/com/rag/controller/FileController.java`
- Create: `rag-server/src/main/java/com/rag/dto/FileVersionRequest.java`
- Create: `rag-server/src/main/java/com/rag/vo/FileVersionResponse.java`
- Test: `rag-server/src/test/java/com/rag/service/FileVersionServiceTest.java`

- [ ] **Step 1: Add a red test for duplicate SHA-256**

  Given an existing current file with the same `kbId` and `fileSha256`, both normal and multipart completion must return business code `409`, must not create a second `kb_file` row, and must not enqueue parsing.

- [ ] **Step 2: Calculate the full-file SHA-256 before registration**

  Normal upload calculates the digest from the temporary local file. Multipart completion reuses its already calculated merged digest. Pass the digest into a single `registerSavedFile` path so both upload modes share deduplication and version logic.

- [ ] **Step 3: Implement same-name version creation**

  If the same knowledge base already has a current file with the same normalized filename, create a new version group member, mark the old member non-current, preserve the old path and chunks, and enqueue processing only for the new member. The first upload creates version `1` and points `versionGroupId` to itself.

- [ ] **Step 4: Implement version list and rollback endpoints**

  Add `GET /api/file/{id}/versions` and `POST /api/file/{id}/versions/{versionId}/rollback`. Rollback changes the current marker transactionally, deletes/rebuilds the selected version's Qdrant points only when needed, and never deletes historical source files.

- [ ] **Step 5: Run focused service/controller tests**

  Run:

  ```powershell
  mvn -q "-Dtest=FileVersionServiceTest,MultipartUploadServiceTest,MultipartUploadControllerTest" test
  ```

  Expected: all tests pass and no duplicate processing task is submitted.

### Task 3: Folder, tag, category and document catalog

**Files:**
- Create: `rag-server/src/main/java/com/rag/catalog/*`
- Create: `rag-server/src/main/resources/mapper/KbCatalogMapper.xml`
- Create: `rag-server/src/main/java/com/rag/controller/KbCatalogController.java`
- Create: `rag-web/src/api/catalog.js`
- Modify: `rag-web/src/views/file/FileView.vue`

- [ ] **Step 1: Add catalog API tests**

  Cover folder creation/rename/delete, tag upsert, file move, category update, and rejection when the current user cannot manage the knowledge base. Deleting a non-empty folder must return `409` unless `recursive=true` is explicitly requested.

- [ ] **Step 2: Implement catalog mapper and service**

  Use `kb_file_folder` for a per-knowledge-base tree, `kb_file_tag` for normalized tag names, and `kb_file_tag_rel` for many-to-many file labels. Validate parent folder ownership through `kbId` and prevent cycles by walking parent IDs before update.

- [ ] **Step 3: Add catalog endpoints**

  Provide:

  - `GET/POST/PUT/DELETE /api/kb/{kbId}/folders`
  - `GET/POST/DELETE /api/kb/{kbId}/tags`
  - `PUT /api/file/{fileId}/catalog`
  - `GET /api/kb/{kbId}/catalog`

- [ ] **Step 4: Add file-page controls**

  Add a folder tree, category selector, tag editor and current-version filter while retaining existing upload, reprocess and delete actions. All new controls use existing dark `glass-panel`/`motion-card` styles.

### Task 4: Structured table parsing and lightweight image context

**Files:**
- Modify: `rag-server/src/main/java/com/rag/rag/DocumentParser.java`
- Modify: `rag-server/src/main/java/com/rag/service/FileService.java`
- Modify: `rag-server/src/main/java/com/rag/entity/KbChunk.java`
- Modify: `rag-server/src/main/resources/mapper/ChunkMapper.xml`
- Create: `rag-server/src/test/java/com/rag/rag/DocumentParserTableTest.java`

- [ ] **Step 1: Add parser tests for workbook sheets and PDF page context**

  Assert that Excel output includes sheet name, header, row/column labels and cell values. Assert that PDF output keeps page markers when available. Add an image fixture test that records filename and nearby document context only; it must not invoke OCR or a vision model.

- [ ] **Step 2: Normalize table output into retrieval-safe blocks**

  Keep the existing Markdown table output for compatibility, but add explicit `sheet`, `rowRange` and `columnRange` markers before each block so retrieval results retain table location. Escape pipe characters and collapse empty rows before splitting.

- [ ] **Step 3: Add image-reference context without image extraction**

  For document formats where Tika exposes embedded resource metadata, append a textual marker such as `[图片引用: filename, 文档上下文: ...]`. Store the marker as normal chunk content and `sourceInfo`; no binary image decoding, OCR, caption generation or external model call is allowed.

- [ ] **Step 4: Reprocess existing files with the same pipeline**

  Ensure the new parser metadata is written through the existing asynchronous `processFile` path and vector payloads remain compatible with Qdrant.

### Task 5: Knowledge-base export, import and backup recovery

**Files:**
- Create: `rag-server/src/main/java/com/rag/backup/*`
- Create: `rag-server/src/main/java/com/rag/controller/KnowledgeBaseBackupController.java`
- Create: `rag-server/src/main/java/com/rag/backup/BackupManifest.java`
- Create: `rag-server/src/test/java/com/rag/backup/KnowledgeBaseBackupServiceTest.java`
- Create: `rag-web/src/api/backup.js`
- Modify: `rag-web/src/views/kb/KbView.vue`

- [ ] **Step 1: Define a versioned ZIP manifest**

  Manifest fields are `formatVersion`, `kb`, `files`, `folders`, `tags`, `chunks`, `createdAt` and `sha256`. Paths inside the archive are fixed to `manifest.json`, `metadata/*.json`, `files/<fileId>/<version>/<safeName>`, and `chunks/<fileId>.jsonl`; no absolute local paths are stored.

- [ ] **Step 2: Add export and backup tests**

  Verify that export includes only the requested knowledge base, rejects files outside the upload root, and fails closed when a manifest checksum does not match. Verify that restoring the same archive twice is idempotent by file hash.

- [ ] **Step 3: Implement streaming ZIP export**

  Stream the archive through `StreamingResponseBody`, calculate each entry checksum while writing, and never load the complete file into memory. Include historical versions and catalog metadata.

- [ ] **Step 4: Implement import/restore with quarantine validation**

  Upload the archive to a temporary local directory, validate manifest schema, entry paths, file sizes and checksums, then create a new knowledge base or merge into an existing one. Restore source files first, insert metadata second, and enqueue processing only for files whose chunks/vectors are absent.

- [ ] **Step 5: Add knowledge-base UI actions**

  Add export, backup and restore actions with progress, checksum failure messages and a confirmation dialog for merge/replace behavior.

### Task 6: Knowledge-base health scoring

**Files:**
- Create: `rag-server/src/main/java/com/rag/quality/KnowledgeBaseHealthService.java`
- Create: `rag-server/src/main/java/com/rag/quality/KnowledgeBaseHealthController.java`
- Create: `rag-server/src/main/java/com/rag/quality/KnowledgeBaseHealthResponse.java`
- Create: `rag-server/src/main/java/com/rag/quality/KnowledgeBaseHealthServiceTest.java`
- Create: `rag-web/src/api/kbHealth.js`
- Modify: `rag-web/src/views/kb/KbView.vue`

- [ ] **Step 1: Add deterministic scoring tests**

  Use fixed counts to assert the score and every issue count. The score starts at `100` and subtracts weighted ratios for empty files, low-quality chunks, duplicate hashes, missing vectors and failed processing; clamp to `0..100` and return the formula version.

- [ ] **Step 2: Implement SQL aggregation**

  Aggregate only the selected knowledge base and current versions. Low-quality chunks are empty/short content, duplicate content is repeated normalized chunk hash, missing vectors are chunks without a successful vector marker, and processing failures are current files with `FAILED` status.

- [ ] **Step 3: Add health endpoints and UI**

  Provide `GET /api/kb/{kbId}/health` and show score, grade, issue counts, last calculated time and a “重新计算” action on the knowledge-base page. Keep the existing agent quality page separate; this score is deterministic and does not call an LLM.

### Task 7: Regression verification and rollout

**Files:**
- Modify: `database/kb_capabilities_upgrade.sql`
- Modify: `docs/superpowers/plans/2026-08-24-knowledge-base-capabilities.md`

- [ ] **Step 1: Run backend full test suite**

  ```powershell
  cd D:\workspace\wujo_rag\rag-server
  mvn test
  ```

  Expected: zero failures and no new warnings beyond existing test fixtures.

- [ ] **Step 2: Build the frontend**

  ```powershell
  cd D:\workspace\wujo_rag\rag-web
  npm.cmd run build
  ```

  Expected: exit code `0`.

- [ ] **Step 3: Apply and verify migrations twice**

  ```powershell
  cd D:\workspace\wujo_rag
  Get-Content -Raw database\kb_capabilities_upgrade.sql | & 'C:\Program Files\MySQL\MySQL Server 5.7\bin\mysql.exe' -uroot --password='Root2022!@#'
  Get-Content -Raw database\kb_capabilities_upgrade.sql | & 'C:\Program Files\MySQL\MySQL Server 5.7\bin\mysql.exe' -uroot --password='Root2022!@#'
  ```

  Verify duplicate indexes, current-version markers and backup tables exist exactly once.

- [ ] **Step 4: Run API smoke checks**

  Verify normal upload, multipart completion, duplicate rejection, version list, rollback, folder/tag operations, export, restore, and health score with an authenticated admin token. Confirm no response contains API keys, passwords or absolute disk paths.

- [ ] **Step 5: Run `git diff --check` and record residual limits**

  Record that image content semantics are intentionally unsupported; only filename and document context are indexed. Record that PDF table structure depends on extractable text unless a future table-specific parser is added.
