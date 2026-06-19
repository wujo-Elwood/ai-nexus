# Document Extraction Module Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a new traceable document extraction module to the RAG platform with upload, contract-template extraction, result review, and JSON/Excel export.

**Architecture:** Add a focused `com.rag.extract` backend module with its own tables, entities, mappers, service, and controller. Add a Vue `/extract` workbench that calls the new APIs and lets users upload documents, create extraction tasks, review field results, save corrections, and export data.

**Tech Stack:** Spring Boot 3.2.5, MyBatis XML mappers, MySQL, Apache Tika via existing `DocumentParser`, Spring AI/OpenAI-compatible HTTP calls via existing model provider configuration, Vue 3, Element Plus, Axios.

---

## File Structure

Create backend files:

```text
rag-server/src/main/java/com/rag/extract/entity/ExtractDocument.java
rag-server/src/main/java/com/rag/extract/entity/ExtractTemplate.java
rag-server/src/main/java/com/rag/extract/entity/ExtractField.java
rag-server/src/main/java/com/rag/extract/entity/ExtractTask.java
rag-server/src/main/java/com/rag/extract/entity/ExtractResult.java
rag-server/src/main/java/com/rag/extract/entity/ExtractReviewRecord.java
rag-server/src/main/java/com/rag/extract/entity/ExtractExportRecord.java
rag-server/src/main/java/com/rag/extract/dto/CreateExtractTaskRequest.java
rag-server/src/main/java/com/rag/extract/dto/UpdateExtractResultRequest.java
rag-server/src/main/java/com/rag/extract/dto/ExportExtractTaskRequest.java
rag-server/src/main/java/com/rag/extract/mapper/ExtractDocumentMapper.java
rag-server/src/main/java/com/rag/extract/mapper/ExtractTemplateMapper.java
rag-server/src/main/java/com/rag/extract/mapper/ExtractFieldMapper.java
rag-server/src/main/java/com/rag/extract/mapper/ExtractTaskMapper.java
rag-server/src/main/java/com/rag/extract/mapper/ExtractResultMapper.java
rag-server/src/main/java/com/rag/extract/mapper/ExtractReviewRecordMapper.java
rag-server/src/main/java/com/rag/extract/mapper/ExtractExportRecordMapper.java
rag-server/src/main/java/com/rag/extract/service/ExtractService.java
rag-server/src/main/java/com/rag/extract/controller/ExtractController.java
rag-server/src/main/resources/mapper/ExtractDocumentMapper.xml
rag-server/src/main/resources/mapper/ExtractTemplateMapper.xml
rag-server/src/main/resources/mapper/ExtractFieldMapper.xml
rag-server/src/main/resources/mapper/ExtractTaskMapper.xml
rag-server/src/main/resources/mapper/ExtractResultMapper.xml
rag-server/src/main/resources/mapper/ExtractReviewRecordMapper.xml
rag-server/src/main/resources/mapper/ExtractExportRecordMapper.xml
```

Modify backend files:

```text
database/init.sql
rag-server/pom.xml
```

Create frontend files:

```text
rag-web/src/api/extract.js
rag-web/src/views/extract/ExtractView.vue
```

Modify frontend files:

```text
rag-web/src/router/index.js
rag-web/src/layouts/MainLayout.vue
```

Do not modify unrelated existing dirty files unless the task requires it. At the time of writing, `rag-server/src/main/java/com/rag/config/WebConfig.java` and `rag-web/vite.config.js` are already modified by prior work and must not be reverted.

---

### Task 1: Database Schema And Seed Template

**Files:**
- Modify: `database/init.sql`

- [ ] **Step 1: Add extraction tables to `database/init.sql`**

Append these statements after the existing tables:

```sql
-- Document Extraction Module
CREATE TABLE IF NOT EXISTS extract_document (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    file_name VARCHAR(255) NOT NULL,
    file_type VARCHAR(100),
    file_size BIGINT,
    file_path VARCHAR(500),
    parse_status VARCHAR(20) DEFAULT 'UPLOADED',
    page_count INT DEFAULT 1,
    full_text LONGTEXT,
    uploaded_by BIGINT NOT NULL,
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_uploaded_by (uploaded_by),
    INDEX idx_parse_status (parse_status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS extract_template (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    template_name VARCHAR(100) NOT NULL,
    template_code VARCHAR(80) NOT NULL UNIQUE,
    document_type VARCHAR(50),
    description VARCHAR(500),
    enabled TINYINT DEFAULT 1,
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_enabled (enabled)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS extract_field (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    template_id BIGINT NOT NULL,
    field_code VARCHAR(80) NOT NULL,
    field_name VARCHAR(100) NOT NULL,
    field_type VARCHAR(30) DEFAULT 'TEXT',
    required TINYINT DEFAULT 0,
    multiple TINYINT DEFAULT 0,
    field_prompt VARCHAR(500),
    example_value VARCHAR(255),
    regex_rule VARCHAR(255),
    confidence_threshold DECIMAL(5,2) DEFAULT 0.70,
    sort_no INT DEFAULT 0,
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_template_field (template_id, field_code),
    INDEX idx_template_id (template_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS extract_task (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    document_id BIGINT NOT NULL,
    template_id BIGINT NOT NULL,
    task_status VARCHAR(20) DEFAULT 'PENDING',
    task_message VARCHAR(1000),
    started_at DATETIME,
    finished_at DATETIME,
    created_by BIGINT NOT NULL,
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_created_by (created_by),
    INDEX idx_document_id (document_id),
    INDEX idx_task_status (task_status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS extract_result (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    task_id BIGINT NOT NULL,
    document_id BIGINT NOT NULL,
    field_id BIGINT NOT NULL,
    field_code VARCHAR(80) NOT NULL,
    field_name VARCHAR(100) NOT NULL,
    field_value TEXT,
    raw_text TEXT,
    page_no INT DEFAULT 1,
    confidence DECIMAL(5,2) DEFAULT 0.00,
    result_status VARCHAR(20) DEFAULT 'MISSING',
    is_modified TINYINT DEFAULT 0,
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_task_id (task_id),
    INDEX idx_document_id (document_id),
    INDEX idx_field_code (field_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS extract_review_record (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    result_id BIGINT NOT NULL,
    old_value TEXT,
    new_value TEXT,
    review_by BIGINT NOT NULL,
    review_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    remark VARCHAR(500),
    INDEX idx_result_id (result_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS extract_export_record (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    task_id BIGINT NOT NULL,
    export_type VARCHAR(20) NOT NULL,
    export_path VARCHAR(500),
    exported_by BIGINT NOT NULL,
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_task_id (task_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

INSERT INTO extract_template (template_name, template_code, document_type, description, enabled)
SELECT '合同基础信息模板', 'contract_basic', 'contract', '抽取合同名称、编号、甲乙方、金额、日期和联系人信息', 1
WHERE NOT EXISTS (SELECT 1 FROM extract_template WHERE template_code = 'contract_basic');

INSERT INTO extract_field (template_id, field_code, field_name, field_type, required, multiple, field_prompt, example_value, regex_rule, confidence_threshold, sort_no)
SELECT t.id, 'contractName', '合同名称', 'TEXT', 1, 0, '抽取合同或协议的正式名称', '某某项目采购合同', NULL, 0.70, 10 FROM extract_template t WHERE t.template_code = 'contract_basic'
AND NOT EXISTS (SELECT 1 FROM extract_field f WHERE f.template_id = t.id AND f.field_code = 'contractName');

INSERT INTO extract_field (template_id, field_code, field_name, field_type, required, multiple, field_prompt, example_value, regex_rule, confidence_threshold, sort_no)
SELECT t.id, 'contractNo', '合同编号', 'TEXT', 1, 0, '抽取合同编号、协议编号或项目编号', 'HT-2026-001', NULL, 0.70, 20 FROM extract_template t WHERE t.template_code = 'contract_basic'
AND NOT EXISTS (SELECT 1 FROM extract_field f WHERE f.template_id = t.id AND f.field_code = 'contractNo');

INSERT INTO extract_field (template_id, field_code, field_name, field_type, required, multiple, field_prompt, example_value, regex_rule, confidence_threshold, sort_no)
SELECT t.id, 'partyA', '甲方', 'TEXT', 1, 0, '抽取合同甲方名称', '某某有限公司', NULL, 0.70, 30 FROM extract_template t WHERE t.template_code = 'contract_basic'
AND NOT EXISTS (SELECT 1 FROM extract_field f WHERE f.template_id = t.id AND f.field_code = 'partyA');

INSERT INTO extract_field (template_id, field_code, field_name, field_type, required, multiple, field_prompt, example_value, regex_rule, confidence_threshold, sort_no)
SELECT t.id, 'partyB', '乙方', 'TEXT', 1, 0, '抽取合同乙方名称', '某某科技有限公司', NULL, 0.70, 40 FROM extract_template t WHERE t.template_code = 'contract_basic'
AND NOT EXISTS (SELECT 1 FROM extract_field f WHERE f.template_id = t.id AND f.field_code = 'partyB');

INSERT INTO extract_field (template_id, field_code, field_name, field_type, required, multiple, field_prompt, example_value, regex_rule, confidence_threshold, sort_no)
SELECT t.id, 'amount', '合同金额', 'AMOUNT', 1, 0, '抽取合同金额，保留币种和原文数字', '120000.00 元', NULL, 0.70, 50 FROM extract_template t WHERE t.template_code = 'contract_basic'
AND NOT EXISTS (SELECT 1 FROM extract_field f WHERE f.template_id = t.id AND f.field_code = 'amount');

INSERT INTO extract_field (template_id, field_code, field_name, field_type, required, multiple, field_prompt, example_value, regex_rule, confidence_threshold, sort_no)
SELECT t.id, 'signDate', '签订日期', 'DATE', 0, 0, '抽取合同签订日期', '2026-06-19', NULL, 0.70, 60 FROM extract_template t WHERE t.template_code = 'contract_basic'
AND NOT EXISTS (SELECT 1 FROM extract_field f WHERE f.template_id = t.id AND f.field_code = 'signDate');

INSERT INTO extract_field (template_id, field_code, field_name, field_type, required, multiple, field_prompt, example_value, regex_rule, confidence_threshold, sort_no)
SELECT t.id, 'startDate', '开始日期', 'DATE', 0, 0, '抽取合同开始日期、生效日期或服务开始日期', '2026-07-01', NULL, 0.70, 70 FROM extract_template t WHERE t.template_code = 'contract_basic'
AND NOT EXISTS (SELECT 1 FROM extract_field f WHERE f.template_id = t.id AND f.field_code = 'startDate');

INSERT INTO extract_field (template_id, field_code, field_name, field_type, required, multiple, field_prompt, example_value, regex_rule, confidence_threshold, sort_no)
SELECT t.id, 'endDate', '结束日期', 'DATE', 0, 0, '抽取合同结束日期、到期日期或服务结束日期', '2027-06-30', NULL, 0.70, 80 FROM extract_template t WHERE t.template_code = 'contract_basic'
AND NOT EXISTS (SELECT 1 FROM extract_field f WHERE f.template_id = t.id AND f.field_code = 'endDate');

INSERT INTO extract_field (template_id, field_code, field_name, field_type, required, multiple, field_prompt, example_value, regex_rule, confidence_threshold, sort_no)
SELECT t.id, 'contactPerson', '联系人', 'TEXT', 0, 0, '抽取合同中的联系人姓名', '张三', NULL, 0.70, 90 FROM extract_template t WHERE t.template_code = 'contract_basic'
AND NOT EXISTS (SELECT 1 FROM extract_field f WHERE f.template_id = t.id AND f.field_code = 'contactPerson');

INSERT INTO extract_field (template_id, field_code, field_name, field_type, required, multiple, field_prompt, example_value, regex_rule, confidence_threshold, sort_no)
SELECT t.id, 'contactPhone', '联系电话', 'PHONE', 0, 0, '抽取联系人电话或手机号码', '13800000000', NULL, 0.70, 100 FROM extract_template t WHERE t.template_code = 'contract_basic'
AND NOT EXISTS (SELECT 1 FROM extract_field f WHERE f.template_id = t.id AND f.field_code = 'contactPhone');
```

- [ ] **Step 2: Verify SQL syntax is at least parseable by inspection**

Run:

```powershell
rg -n "CREATE TABLE IF NOT EXISTS extract_|contract_basic|extract_field" database\init.sql
```

Expected: output includes all seven `extract_*` tables and the `contract_basic` seed data.

- [ ] **Step 3: Commit database schema**

```powershell
git add database/init.sql
git commit -m "feat: add extraction database schema"
```

---

### Task 2: Backend Entity, DTO, And Mapper Layer

**Files:**
- Create: backend entity, DTO, mapper Java files, and mapper XML files listed in File Structure.

- [ ] **Step 1: Create entity classes**

Use Lombok `@Data`. Follow this shape for each class:

```java
package com.rag.extract.entity;

import lombok.Data;
import java.time.LocalDateTime;

/**
 * 文档抽取文件实体
 * 记录上传到抽取模块的原始文件和解析文本
 */
@Data
public class ExtractDocument {
    /** 文件编号 */
    private Long id;
    /** 原始文件名 */
    private String fileName;
    /** 文件类型 */
    private String fileType;
    /** 文件大小 */
    private Long fileSize;
    /** 文件保存路径 */
    private String filePath;
    /** 解析状态 */
    private String parseStatus;
    /** 页数 */
    private Integer pageCount;
    /** 解析后的全文 */
    private String fullText;
    /** 上传用户编号 */
    private Long uploadedBy;
    /** 创建时间 */
    private LocalDateTime createTime;
}
```

Create analogous fields for:

```text
ExtractTemplate: id, templateName, templateCode, documentType, description, enabled, createTime
ExtractField: id, templateId, fieldCode, fieldName, fieldType, required, multiple, fieldPrompt, exampleValue, regexRule, confidenceThreshold, sortNo, createTime
ExtractTask: id, documentId, templateId, taskStatus, taskMessage, startedAt, finishedAt, createdBy, createTime
ExtractResult: id, taskId, documentId, fieldId, fieldCode, fieldName, fieldValue, rawText, pageNo, confidence, resultStatus, isModified, createTime, updateTime
ExtractReviewRecord: id, resultId, oldValue, newValue, reviewBy, reviewAt, remark
ExtractExportRecord: id, taskId, exportType, exportPath, exportedBy, createTime
```

- [ ] **Step 2: Create DTO classes**

Create `CreateExtractTaskRequest`:

```java
package com.rag.extract.dto;

import lombok.Data;

/**
 * 创建抽取任务请求
 * 接收文档编号和模板编号
 */
@Data
public class CreateExtractTaskRequest {
    /** 文档编号 */
    private Long documentId;
    /** 模板编号 */
    private Long templateId;
}
```

Create `UpdateExtractResultRequest`:

```java
package com.rag.extract.dto;

import lombok.Data;

/**
 * 修改抽取结果请求
 * 保存人工校正后的字段值
 */
@Data
public class UpdateExtractResultRequest {
    /** 新字段值 */
    private String newValue;
    /** 修改备注 */
    private String remark;
}
```

Create `ExportExtractTaskRequest`:

```java
package com.rag.extract.dto;

import lombok.Data;

/**
 * 导出抽取任务请求
 * 指定导出文件类型
 */
@Data
public class ExportExtractTaskRequest {
    /** 导出类型：json 或 excel */
    private String exportType;
}
```

- [ ] **Step 3: Create mapper interfaces**

Create methods with exact signatures:

```java
@Mapper
public interface ExtractDocumentMapper {
    int insert(ExtractDocument document);
    ExtractDocument findById(@Param("id") Long id);
    ExtractDocument findByIdAndUser(@Param("id") Long id, @Param("userId") Long userId);
    int updateParsed(@Param("id") Long id, @Param("fullText") String fullText, @Param("pageCount") Integer pageCount);
    int updateStatus(@Param("id") Long id, @Param("parseStatus") String parseStatus);
}
```

```java
@Mapper
public interface ExtractTemplateMapper {
    List<ExtractTemplate> findEnabled();
    ExtractTemplate findById(@Param("id") Long id);
}
```

```java
@Mapper
public interface ExtractFieldMapper {
    List<ExtractField> findByTemplateId(@Param("templateId") Long templateId);
}
```

```java
@Mapper
public interface ExtractTaskMapper {
    int insert(ExtractTask task);
    ExtractTask findById(@Param("id") Long id);
    ExtractTask findByIdAndUser(@Param("id") Long id, @Param("userId") Long userId);
    List<ExtractTask> findByUser(@Param("userId") Long userId);
    int updateStatus(@Param("id") Long id, @Param("status") String status, @Param("message") String message);
    int markStarted(@Param("id") Long id, @Param("status") String status);
    int markFinished(@Param("id") Long id, @Param("status") String status, @Param("message") String message);
}
```

```java
@Mapper
public interface ExtractResultMapper {
    int insertBatch(@Param("results") List<ExtractResult> results);
    ExtractResult findById(@Param("id") Long id);
    ExtractResult findByIdAndUser(@Param("id") Long id, @Param("userId") Long userId);
    List<ExtractResult> findByTaskId(@Param("taskId") Long taskId);
    int updateManualValue(@Param("id") Long id, @Param("newValue") String newValue);
}
```

```java
@Mapper
public interface ExtractReviewRecordMapper {
    int insert(ExtractReviewRecord record);
}
```

```java
@Mapper
public interface ExtractExportRecordMapper {
    int insert(ExtractExportRecord record);
}
```

- [ ] **Step 4: Create XML mappers**

Each XML mapper must use `namespace` matching its Java interface and rely on MyBatis camel-case mapping already enabled in `application.yml`.

Example for `ExtractDocumentMapper.xml`:

```xml
<?xml version="1.0" encoding="UTF-8" ?>
<!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN"
        "https://mybatis.org/dtd/mybatis-3-mapper.dtd">
<mapper namespace="com.rag.extract.mapper.ExtractDocumentMapper">
    <insert id="insert" parameterType="com.rag.extract.entity.ExtractDocument" useGeneratedKeys="true" keyProperty="id">
        INSERT INTO extract_document (file_name, file_type, file_size, file_path, parse_status, page_count, full_text, uploaded_by, create_time)
        VALUES (#{fileName}, #{fileType}, #{fileSize}, #{filePath}, #{parseStatus}, #{pageCount}, #{fullText}, #{uploadedBy}, NOW())
    </insert>
    <select id="findById" resultType="com.rag.extract.entity.ExtractDocument">
        SELECT * FROM extract_document WHERE id = #{id}
    </select>
    <select id="findByIdAndUser" resultType="com.rag.extract.entity.ExtractDocument">
        SELECT * FROM extract_document WHERE id = #{id} AND uploaded_by = #{userId}
    </select>
    <update id="updateParsed">
        UPDATE extract_document SET full_text = #{fullText}, page_count = #{pageCount}, parse_status = 'PARSED' WHERE id = #{id}
    </update>
    <update id="updateStatus">
        UPDATE extract_document SET parse_status = #{parseStatus} WHERE id = #{id}
    </update>
</mapper>
```

Implement the remaining XML files with direct SQL corresponding to mapper methods. `findByIdAndUser` for results should join `extract_task t ON r.task_id = t.id` and filter `t.created_by = #{userId}`.

- [ ] **Step 5: Compile mapper layer**

Run:

```powershell
cd rag-server
mvn -DskipTests compile
```

Expected: compile succeeds or only fails because service/controller references are not yet created if mapper package scanning has no issue.

- [ ] **Step 6: Commit mapper layer**

```powershell
git add rag-server/src/main/java/com/rag/extract rag-server/src/main/resources/mapper
git commit -m "feat: add extraction mapper layer"
```

---

### Task 3: Backend Service And Model Extraction Logic

**Files:**
- Create: `rag-server/src/main/java/com/rag/extract/service/ExtractService.java`
- Modify: `rag-server/pom.xml`

- [ ] **Step 1: Add Apache POI OOXML dependency for Excel export if missing**

Add to `rag-server/pom.xml` dependencies:

```xml
<dependency>
    <groupId>org.apache.poi</groupId>
    <artifactId>poi-ooxml</artifactId>
    <version>5.2.5</version>
</dependency>
```

- [ ] **Step 2: Implement `ExtractService` public methods**

Create service with these methods:

```java
public List<Map<String, Object>> getTemplates()
public ExtractDocument uploadDocument(MultipartFile file, Long userId)
public ExtractTask createTask(Long documentId, Long templateId, Long userId)
public List<Map<String, Object>> listTasks(Long userId)
public ExtractTask getTask(Long taskId, Long userId)
public List<ExtractResult> getTaskResults(Long taskId, Long userId)
public void updateResult(Long resultId, String newValue, String remark, Long userId)
public ResponseEntity<?> exportTask(Long taskId, String exportType, Long userId)
```

Use existing `DocumentParser` to parse uploaded files. Use existing `ModelProviderService.getActive()` to get model configuration. Use `HttpURLConnection` or `RestTemplate` to call the provider's `/chat/completions` endpoint, matching the style in `QueryRewriter`.

- [ ] **Step 3: Implement upload validation**

Allow MIME types:

```java
Set.of(
    "application/pdf",
    "application/msword",
    "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
)
```

Also allow extensions `.pdf`, `.doc`, `.docx` for browsers with missing content type.

- [ ] **Step 4: Implement extraction prompt**

Prompt must instruct the model to return JSON only:

```text
你是文档结构化抽取助手。请根据合同文本抽取字段。
只输出 JSON，不要输出 Markdown，不要解释。
输出格式：
{"fields":[{"fieldCode":"字段编码","value":"字段值","rawText":"最能证明字段值的原文片段","pageNo":1,"confidence":0.0,"reason":"简短理由"}]}
如果字段找不到，value 和 rawText 为空，confidence 为 0。
```

Include every `ExtractField` as field code, field name, type, required, prompt, example.

- [ ] **Step 5: Implement JSON cleanup**

Add private method:

```java
private String extractJsonText(String responseText)
```

Rules:

- If response contains ```json code fence, return content inside it.
- Else if response contains first `{` and last `}`, return that substring.
- Else return original trimmed text.

- [ ] **Step 6: Implement result normalization**

For every template field, produce exactly one `ExtractResult`. If the model omits a field, create a `MISSING` result. Apply status rules:

```text
MISSING when required and value blank
WARNING when confidence < confidenceThreshold
ERROR when regexRule exists and value does not match regex
SUCCESS otherwise
```

Default `pageNo` to 1 and `confidence` to 0 when absent.

- [ ] **Step 7: Implement exports**

JSON export:

- Return `ResponseEntity<byte[]>`.
- Content type: `application/json;charset=UTF-8`.
- Header `Content-Disposition`: `attachment; filename=extract-task-{taskId}.json`.

Excel export:

- Use `XSSFWorkbook`.
- Sheet name: `抽取结果`.
- Header columns: `字段编码, 字段名称, 字段值, 原文片段, 页码, 置信度, 状态`.
- Return as `application/vnd.openxmlformats-officedocument.spreadsheetml.sheet`.

- [ ] **Step 8: Compile service**

Run:

```powershell
cd rag-server
mvn -DskipTests compile
```

Expected: compile succeeds after controller task is added, or fails only because controller references are absent.

- [ ] **Step 9: Commit service**

```powershell
git add rag-server/pom.xml rag-server/src/main/java/com/rag/extract/service/ExtractService.java
git commit -m "feat: add extraction service"
```

---

### Task 4: Backend Controller

**Files:**
- Create: `rag-server/src/main/java/com/rag/extract/controller/ExtractController.java`

- [ ] **Step 1: Implement controller endpoints**

Create endpoints:

```java
@GetMapping("/templates")
public Result<List<Map<String, Object>>> getTemplates()

@PostMapping("/documents/upload")
public Result<ExtractDocument> upload(@RequestParam("file") MultipartFile file, HttpServletRequest request)

@PostMapping("/tasks")
public Result<ExtractTask> createTask(@RequestBody CreateExtractTaskRequest body, HttpServletRequest request)

@GetMapping("/tasks")
public Result<List<Map<String, Object>>> listTasks(HttpServletRequest request)

@GetMapping("/tasks/{taskId}")
public Result<ExtractTask> getTask(@PathVariable Long taskId, HttpServletRequest request)

@GetMapping("/tasks/{taskId}/results")
public Result<List<ExtractResult>> getResults(@PathVariable Long taskId, HttpServletRequest request)

@PutMapping("/results/{resultId}")
public Result<Void> updateResult(@PathVariable Long resultId, @RequestBody UpdateExtractResultRequest body, HttpServletRequest request)

@PostMapping("/tasks/{taskId}/export")
public ResponseEntity<?> exportTask(@PathVariable Long taskId, @RequestBody ExportExtractTaskRequest body, HttpServletRequest request)
```

All endpoints derive `Long userId = (Long) request.getAttribute("userId");`.

- [ ] **Step 2: Compile backend**

Run:

```powershell
cd rag-server
mvn -DskipTests compile
```

Expected: `BUILD SUCCESS`.

- [ ] **Step 3: Commit controller**

```powershell
git add rag-server/src/main/java/com/rag/extract/controller/ExtractController.java
git commit -m "feat: add extraction api"
```

---

### Task 5: Frontend API And Routing

**Files:**
- Create: `rag-web/src/api/extract.js`
- Modify: `rag-web/src/router/index.js`
- Modify: `rag-web/src/layouts/MainLayout.vue`

- [ ] **Step 1: Create API wrapper**

Create `extract.js`:

```js
import request from '../utils/request'

export function getExtractTemplates() {
  return request.get('/api/extract/templates')
}

export function uploadExtractDocument(file) {
  const formData = new FormData()
  formData.append('file', file)
  return request.post('/api/extract/documents/upload', formData, {
    headers: { 'Content-Type': 'multipart/form-data' }
  })
}

export function createExtractTask(data) {
  return request.post('/api/extract/tasks', data)
}

export function getExtractTasks() {
  return request.get('/api/extract/tasks')
}

export function getExtractResults(taskId) {
  return request.get(`/api/extract/tasks/${taskId}/results`)
}

export function updateExtractResult(resultId, data) {
  return request.put(`/api/extract/results/${resultId}`, data)
}

export function exportExtractTask(taskId, exportType) {
  return request.post(`/api/extract/tasks/${taskId}/export`, { exportType }, { responseType: 'blob' })
}
```

- [ ] **Step 2: Add route**

Add to `routes`:

```js
{
  path: '/extract',
  name: 'Extract',
  component: () => import('../views/extract/ExtractView.vue'),
  meta: { requiresAuth: true }
}
```

- [ ] **Step 3: Add menu item**

In `MainLayout.vue`, add a menu item:

```js
{
  path: '/extract',
  label: '文档抽取',
  icon: '<svg viewBox="0 0 24 24" fill="none"><path d="M7 3h7l5 5v13H7a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2Z" stroke="currentColor" stroke-width="2"/><path d="M14 3v5h5M8 13h8M8 17h6" stroke="currentColor" stroke-width="2" stroke-linecap="round"/></svg>'
}
```

- [ ] **Step 4: Commit frontend API and navigation**

```powershell
git add rag-web/src/api/extract.js rag-web/src/router/index.js rag-web/src/layouts/MainLayout.vue
git commit -m "feat: add extraction navigation"
```

---

### Task 6: Frontend Extraction Workbench

**Files:**
- Create: `rag-web/src/views/extract/ExtractView.vue`

- [ ] **Step 1: Create page template**

Use `MainLayout`, Element Plus upload/select/table/form components. Required sections:

```text
page hero
upload and template controls
task table
result review panel
evidence panel
```

- [ ] **Step 2: Create script state**

State variables:

```js
const templates = ref([])
const selectedTemplateId = ref(null)
const uploadedDocument = ref(null)
const tasks = ref([])
const results = ref([])
const selectedTask = ref(null)
const selectedResult = ref(null)
const loading = ref(false)
const taskLoading = ref(false)
const resultLoading = ref(false)
```

- [ ] **Step 3: Implement upload validation**

Allow `.pdf`, `.doc`, `.docx` and 50MB maximum, matching backend.

- [ ] **Step 4: Implement actions**

Functions:

```js
loadTemplates()
loadTasks()
handleUploadSuccess(response)
handleCreateTask()
handleViewResults(row)
handleSaveResult(row)
handleExport(row, exportType)
downloadBlob(blob, fileName)
getTaskStatusType(status)
getResultStatusType(status)
```

- [ ] **Step 5: Implement result review behavior**

When clicking a result row:

- Set `selectedResult`.
- Show raw text, page number, confidence, and status on the evidence panel.
- Field value can be edited directly in the result table.
- Save button calls `updateExtractResult`.

- [ ] **Step 6: Build frontend**

Run:

```powershell
cd rag-web
npm run build
```

Expected: Vite build succeeds.

- [ ] **Step 7: Commit workbench**

```powershell
git add rag-web/src/views/extract/ExtractView.vue
git commit -m "feat: add extraction workbench"
```

---

### Task 7: End-To-End Verification

**Files:**
- No new files unless bug fixes are needed.

- [ ] **Step 1: Verify backend compile**

Run:

```powershell
cd rag-server
mvn -DskipTests compile
```

Expected: `BUILD SUCCESS`.

- [ ] **Step 2: Verify frontend build**

Run:

```powershell
cd rag-web
npm run build
```

Expected: Vite reports successful production build.

- [ ] **Step 3: Verify route and menu by source inspection**

Run:

```powershell
rg -n "文档抽取|/extract|ExtractView|extract/templates|extract/tasks" rag-web\src rag-server\src\main\java
```

Expected:

- `/extract` route exists.
- `文档抽取` menu label exists.
- frontend API calls exist.
- backend extract controller endpoints exist.

- [ ] **Step 4: Verify database schema by source inspection**

Run:

```powershell
rg -n "extract_document|extract_template|extract_result|contract_basic" database\init.sql rag-server\src\main\resources\mapper
```

Expected: all extraction tables and mapper SQL appear.

- [ ] **Step 5: Check dirty worktree**

Run:

```powershell
git status --short
```

Expected: only intentionally uncommitted user changes remain, specifically pre-existing `WebConfig.java` and `vite.config.js` if still present.

---

## Self-Review

Spec coverage:

- New menu and `/extract` page: covered by Tasks 5 and 6.
- PDF / Word upload: covered by Tasks 3, 4, and 6.
- Built-in contract template: covered by Task 1.
- Task creation and status: covered by Tasks 1, 2, 3, and 4.
- Field result with value, raw text, page, confidence, status: covered by Tasks 1, 2, 3, and 6.
- Manual correction and review record: covered by Tasks 1, 2, 3, 4, and 6.
- JSON and Excel export: covered by Task 3 and Task 6.
- User permission checks: covered by mapper methods and service requirements in Tasks 2 and 3.
- Build verification: covered by Task 7.

Placeholder scan:

- No `TODO`, `TBD`, or open-ended placeholder steps are intentionally left.

Type consistency:

- Entity names, mapper method names, DTO names, route paths, and frontend API names are consistent across tasks.
