# Enterprise RAG Upload, Retrieval, Trust, and Citations Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add resumable local-disk uploads, stronger retrieval filtering, trusted knowledge-base answers, and clickable citations without breaking existing upload or chat behavior.

**Architecture:** Keep `POST /api/file/upload` and the current non-knowledge-base chat path unchanged. Add a persisted multipart-upload domain backed by MySQL and local temporary files; after merge it delegates to the existing file-processing pipeline. Refactor retrieval output into a top-level context object carrying scores and citations, then apply threshold/diversity selection and a trusted-answer guard only when `kbId` is present. Extend the existing SSE protocol with additive `citation` and `answer-meta` events.

**Tech Stack:** Spring Boot 3.2, Java 17, MyBatis, MySQL 5.7-compatible SQL, local `java.nio.file` storage, Qdrant, JUnit 5/Mockito, Vue 3, Element Plus, native Fetch/SSE.

---

### Task 1: Add persisted multipart-upload schema and configuration

**Files:**
- Create: `database/kb_multipart_upload_upgrade.sql`
- Modify: `rag-server/src/main/resources/application.yml:12-52`
- Test: `rag-server/src/test/java/com/rag/service/MultipartUploadServiceTest.java`

- [ ] **Step 1: Write the failing schema/config contract test**

Add a test that loads the configured multipart values through a plain service instance and asserts the defaults are `2GB`, `10MB`, and `24h`. Add a SQL text test that checks both new table names and their unique keys are present.

```java
@Test
void multipartDefaultsShouldUseTwoGbAndTenMb() {
    MultipartUploadService service = new MultipartUploadService();
    ReflectionTestUtils.setField(service, "maxFileSize", 2L * 1024 * 1024 * 1024);
    ReflectionTestUtils.setField(service, "chunkSize", 10L * 1024 * 1024);
    assertEquals(2L * 1024 * 1024 * 1024, ReflectionTestUtils.getField(service, "maxFileSize"));
    assertEquals(10L * 1024 * 1024, ReflectionTestUtils.getField(service, "chunkSize"));
}
```

- [ ] **Step 2: Run the focused test and verify it fails because the service does not exist**

Run: `mvn -q -Dtest=MultipartUploadServiceTest test`

Expected: compilation failure because `MultipartUploadService` has not been created yet.

- [ ] **Step 3: Add the idempotent MySQL 5.7 upgrade script and configuration keys**

Create `kb_upload_session` keyed by `upload_id`, `kb_upload_chunk` keyed by `(upload_id, chunk_index)`, and indexes for `(create_user, status)` and `expire_time`. Use the same dynamic `information_schema`/`PREPARE` style as `database/kb_file_process_upgrade.sql` so running the script twice succeeds. Add:

```yaml
file:
  multipart:
    max-file-size: ${FILE_MULTIPART_MAX_SIZE:2147483648}
    chunk-size: ${FILE_MULTIPART_CHUNK_SIZE:10485760}
    expire-hours: ${FILE_MULTIPART_EXPIRE_HOURS:24}
    cleanup-interval-ms: ${FILE_MULTIPART_CLEANUP_INTERVAL_MS:3600000}
```

- [ ] **Step 4: Run the focused test and SQL twice**

Run: `mvn -q -Dtest=MultipartUploadServiceTest test`

Run the SQL script twice against the local `rag_db`; expected result is no duplicate-column/table/key error.

### Task 2: Implement multipart-upload persistence and service behavior

**Files:**
- Create: `rag-server/src/main/java/com/rag/entity/MultipartUploadSession.java`
- Create: `rag-server/src/main/java/com/rag/entity/MultipartUploadChunk.java`
- Create: `rag-server/src/main/java/com/rag/mapper/MultipartUploadSessionMapper.java`
- Create: `rag-server/src/main/java/com/rag/mapper/MultipartUploadChunkMapper.java`
- Create: `rag-server/src/main/resources/mapper/MultipartUploadSessionMapper.xml`
- Create: `rag-server/src/main/resources/mapper/MultipartUploadChunkMapper.xml`
- Create: `rag-server/src/main/java/com/rag/dto/MultipartUploadInitRequest.java`
- Create: `rag-server/src/main/java/com/rag/dto/MultipartUploadInitResponse.java`
- Create: `rag-server/src/main/java/com/rag/dto/MultipartUploadStatusResponse.java`
- Create: `rag-server/src/main/java/com/rag/service/MultipartUploadService.java`
- Modify: `rag-server/src/main/java/com/rag/service/FileService.java:80-110`
- Modify: `rag-server/src/main/java/com/rag/RagApplication.java:1-20`
- Test: `rag-server/src/test/java/com/rag/service/MultipartUploadServiceTest.java`

- [ ] **Step 1: Write failing service tests for initialization, idempotent chunks, missing chunks, and successful merge**

Cover these independent behaviors:

```java
@Test
void completeShouldRejectMissingChunk() { /* assert BusinessException code 409 */ }

@Test
void sameChunkShouldBeIdempotent() { /* upload index 0 twice and assert one mapper row */ }

@Test
void completeShouldCreateFileOnlyAfterSha256Matches() { /* assert FileService registration after successful merge */ }
```

Use `@TempDir`, real temporary chunk files, mocked mappers, and a mocked `FileService`; do not mock `Path` or `MessageDigest`.

- [ ] **Step 2: Run tests and confirm each fails for the missing service behavior**

Run: `mvn -q -Dtest=MultipartUploadServiceTest test`

Expected: failing assertions or missing classes, not a test setup error.

- [ ] **Step 3: Implement entities, MyBatis mappers, and service state transitions**

Every new class and public method gets a Chinese function comment. `MultipartUploadService` must:

1. Validate `kbId`, filename extension/MIME, size `<= maxFileSize`, and owner management permission before creating a session.
2. Generate a UUID `uploadId`, create `${uploadDir}/.parts/{uploadId}`, and persist `UPLOADING`.
3. Write each raw request body to `{chunkIndex}.part` through a bounded stream, calculate SHA-256, and upsert the chunk row.
4. Treat an already recorded identical index as idempotent; reject an index outside `[0,totalChunks)` with 400.
5. In `complete`, atomically change state to `MERGING`, verify every index and expected size, merge in index order into a UUID temporary file, verify the whole-file SHA-256 when supplied, then call a new `FileService.registerSavedFile(...)` method.
6. Mark `COMPLETED` only after the `kb_file` insert succeeds; on merge failure mark `FAILED` and retain chunks for retry.
7. Delete only cancelled/expired session directories.

Add a scheduled cleanup method to this service and `@EnableScheduling` to `RagApplication` using the configured interval. Do not expose absolute file paths in returned errors.

- [ ] **Step 4: Add the minimal FileService registration boundary**

Add `registerSavedFile(Long kbId, String originalName, String contentType, long size, Path savedPath)` to `FileService`. It creates the same `KbFile` fields as the existing `upload` method and calls the existing post-commit processing submission. Refactor the existing upload method to call this boundary only after it has saved the ordinary multipart file, preserving its endpoint behavior.

- [ ] **Step 5: Run focused tests and the existing file tests**

Run: `mvn -q -Dtest=MultipartUploadServiceTest,FileServiceTest test`

Expected: all focused tests pass.

### Task 3: Expose multipart endpoints and protected file preview/download

**Files:**
- Create: `rag-server/src/main/java/com/rag/controller/MultipartUploadController.java`
- Modify: `rag-server/src/main/java/com/rag/controller/FileController.java:1-90`
- Create: `rag-server/src/test/java/com/rag/controller/MultipartUploadControllerTest.java`
- Modify: `rag-web/src/api/file.js:1-30`

- [ ] **Step 1: Write failing controller tests**

Test authenticated owner initialization, missing-session `404`, invalid completion `409`, and preview denial for a user who can neither read the knowledge base nor manage it.

- [ ] **Step 2: Run the controller tests and verify expected failures**

Run: `mvn -q -Dtest=MultipartUploadControllerTest test`

- [ ] **Step 3: Implement additive endpoints**

Add `POST /api/file/multipart/init`, `PUT /api/file/multipart/{uploadId}/chunks/{chunkIndex}`, `GET /api/file/multipart/{uploadId}`, `POST /api/file/multipart/{uploadId}/complete`, and `DELETE /api/file/multipart/{uploadId}`. The chunk endpoint consumes `application/octet-stream` from `HttpServletRequest.getInputStream()` so the existing 50MB ordinary multipart limit is not changed.

Add `GET /api/file/{id}/preview` and `GET /api/file/{id}/download`. Both load the file, call `knowledgeBaseService.checkAccess`, verify the resolved path stays under `file.upload-dir`, and return a `FileSystemResource` with a safe content type and RFC 5987 filename. Preview uses `Content-Disposition: inline`; download uses `attachment`.

Add frontend API functions for initialization, chunk upload, status, completion, cancellation, preview, and download. Keep `uploadFile` unchanged.

- [ ] **Step 4: Run controller tests and compile**

Run: `mvn -q -Dtest=MultipartUploadControllerTest test`

### Task 4: Implement frontend resumable upload without changing small-file behavior

**Files:**
- Modify: `rag-web/src/views/file/FileView.vue:42-70,180-310`
- Modify: `rag-web/src/api/file.js:1-80`
- Create: `rag-web/src/utils/multipartUpload.js`

- [ ] **Step 1: Write the frontend behavior tests or executable helper checks**

For the upload helper, assert that a 25MB mock `File` produces three 10MB/10MB/5MB slices and that a resumed status skips indexes already returned by the server. Keep the helper free of Vue state so it can be checked with the existing Node/Vite toolchain.

- [ ] **Step 2: Run the helper check before implementation**

Run: `npm.cmd run build` after adding the test harness; expected failure is the missing helper export.

- [ ] **Step 3: Implement the helper and file-page integration**

Use `crypto.subtle.digest('SHA-256', ...)` for whole-file and chunk digests. For files `<= 10MB`, call the existing `uploadFile`. For larger files:

1. initialize or restore an `uploadId` keyed by `kbId + file.name + file.size + file.lastModified` in local storage;
2. fetch server status;
3. upload missing chunks sequentially with a pause flag;
4. refresh progress after each successful chunk;
5. complete only after all chunks are present;
6. remove the local resume key after completion or cancellation.

Replace the current 50MB client check with the confirmed 2GB limit, show a progress row for active uploads, and leave the existing file-processing polling and retry controls unchanged.

- [ ] **Step 4: Build the frontend**

Run: `npm.cmd run build`

Expected: Vite build succeeds.

### Task 5: Add retrieval candidate filtering and diversity selection

**Files:**
- Create: `rag-server/src/main/java/com/rag/rag/RetrievalCandidate.java`
- Create: `rag-server/src/main/java/com/rag/rag/RetrievalSelector.java`
- Create: `rag-server/src/test/java/com/rag/rag/RetrievalSelectorTest.java`
- Modify: `rag-server/src/main/java/com/rag/rag/KeywordSearchService.java:25-75`
- Modify: `rag-server/src/main/java/com/rag/rag/Reranker.java:20-75`
- Modify: `rag-server/src/main/java/com/rag/ai/ChatService.java:370-560`
- Modify: `rag-server/src/main/resources/application.yml:49-54`

- [ ] **Step 1: Write failing selector tests**

Test that a vector-only candidate below the threshold is removed, a keyword candidate survives with a low vector score, and near-identical content is reduced while distinct content remains.

```java
@Test
void selectorShouldKeepKeywordHitBelowVectorThreshold() { /* assert one KEYWORD result remains */ }

@Test
void selectorShouldRemoveNearDuplicateContent() { /* assert only the strongest duplicate remains */ }
```

- [ ] **Step 2: Run selector tests and verify the expected failures**

Run: `mvn -q -Dtest=RetrievalSelectorTest test`

- [ ] **Step 3: Implement candidate metadata and selector**

`RetrievalCandidate` is a top-level class containing chunk, vector score, keyword score, match type, final score, and filter reason. `RetrievalSelector` applies the configured threshold, fusion weights, and token-set similarity diversity filter. It must not use a new external model or change Qdrant payload shape.

- [ ] **Step 4: Upgrade keyword retrieval and ChatService integration**

Change `KeywordSearchService` to query each non-stopword keyword, merge by chunk ID, and cap the result count. In `ChatService`, call Qdrant with `candidate-k`, preserve vector score by chunk ID, merge keyword hits, select final candidates, and cache only final chunk IDs. Keep the old `top-k` property and set compatibility defaults in `application.yml`.

- [ ] **Step 5: Expand recall diagnostics and run tests**

Return original query, rewritten query, vector/keyword/final scores, match type, selected flag, and filter reason. Update `ChatServiceTest` and add selector tests. Run:

`mvn -q -Dtest=RetrievalSelectorTest,ChatServiceTest test`

### Task 6: Add trusted-answer guard and structured citation context

**Files:**
- Create: `rag-server/src/main/java/com/rag/rag/KnowledgeCitation.java`
- Create: `rag-server/src/main/java/com/rag/rag/KnowledgeContext.java`
- Create: `rag-server/src/main/java/com/rag/rag/TrustedAnswerGuard.java`
- Create: `rag-server/src/main/java/com/rag/rag/TrustedAnswerResult.java`
- Create: `rag-server/src/test/java/com/rag/rag/TrustedAnswerGuardTest.java`
- Modify: `rag-server/src/main/java/com/rag/ai/ChatService.java:60-290,330-510`

- [ ] **Step 1: Write failing guard tests**

Cover empty evidence, a valid `[来源: 文件名, 第N段]` citation, an unknown file citation, and an answer without citations.

```java
@Test
void noEvidenceShouldReturnNoEvidenceResult() { /* assert grounded false and NO_EVIDENCE */ }

@Test
void unknownCitationShouldBeRejected() { /* assert grounded false and INVALID_CITATION */ }
```

- [ ] **Step 2: Run guard tests and verify they fail**

Run: `mvn -q -Dtest=TrustedAnswerGuardTest test`

- [ ] **Step 3: Implement top-level context, citation, and guard classes**

`KnowledgeContext` carries compressed prompt text plus the exact citation list. `TrustedAnswerGuard` extracts the model citation marker, matches it to the context source and chunk index, and returns either the answer with valid citations or one of the two fixed refusal messages. It never accepts a source that was not in the current context.

- [ ] **Step 4: Apply strict mode only to knowledge-base chat**

Refactor `ChatService` retrieval methods to return `KnowledgeContext`. When `kbId` is null, retain the current path. When `kbId` is non-null and context is empty, skip the LLM call and return the no-evidence response. For synchronous chat, validate the completed answer before saving it. For streaming chat, buffer model tokens while knowledge-base strict mode is active, validate on completion, then send the validated answer once as a `message` event followed by `answer-meta` and `done`.

- [ ] **Step 5: Run guard, chat, and regression tests**

Run: `mvn -q -Dtest=TrustedAnswerGuardTest,ChatServiceTest test`

### Task 7: Add citation SSE events and frontend source cards

**Files:**
- Modify: `rag-server/src/main/java/com/rag/ai/ChatService.java:170-290`
- Modify: `rag-web/src/views/chat/ChatView.vue:130-165,600-780`
- Modify: `rag-web/src/api/file.js:1-100`

- [ ] **Step 1: Write the frontend parser test/check for additive SSE events**

Feed a `citation` event and an `answer-meta` event to the parser and assert they update only the assistant message metadata while ordinary `message` events still append content.

- [ ] **Step 2: Run the parser check before implementation**

Run: `npm.cmd run build`; expected failure is the missing citation metadata handling.

- [ ] **Step 3: Emit citation metadata from ChatService**

Before generating a trusted knowledge-base answer, send `citation` with JSON-serialized `KnowledgeCitation` data. After guard validation, send `answer-meta` with `grounded`, `reason`, and citation count. Do not remove or rename existing SSE event names.

- [ ] **Step 4: Render source cards and protected file actions**

Store `_citations` and `_answerMeta` on assistant messages. Render a compact “参考资料” section below trusted answers, with file name, chunk number, match type, and score. Open `/api/file/{fileId}/preview` in a new tab with the existing bearer token strategy; offer download when inline preview fails. Hide the section for empty or untrusted answers.

- [ ] **Step 5: Build the frontend and run the existing backend tests**

Run: `npm.cmd run build`

Run: `mvn -q test`

### Task 8: End-to-end verification and cleanup

**Files:**
- Modify only files already listed above if verification exposes a feature-specific defect.
- Create: `scripts/verify-multipart-rag.ps1`

- [ ] **Step 1: Run the complete automated suite**

Run: `mvn test`, `npm.cmd run build`, and `git diff --check`. Record test counts, build exit code, and any dependency warnings separately.

- [ ] **Step 2: Run the real multipart HTTP flow**

Use a temporary authenticated user and knowledge base. Split a test TXT/PDF into three files, initialize, upload only the first two, query status, restart the script, upload the missing chunk, complete, and compare the merged SHA-256 with the source. Confirm a `kb_file` row appears and processing status changes.

- [ ] **Step 3: Run the real trusted-answer flow when dependencies are available**

With Embedding, Qdrant, and the configured LLM running, upload a small document, ask one answerable and one unrelated question, verify citation/answer-meta SSE events, verify unrelated-question refusal, and open the citation preview URL. If an external dependency is unavailable, report that exact boundary instead of claiming full-chain success.

- [ ] **Step 4: Verify compatibility and workspace scope**

Confirm `POST /api/file/upload` still accepts a small file, ordinary chat without `kbId` still streams, and `git status` shows only files belonging to this feature changed in addition to the pre-existing dirty worktree changes.
