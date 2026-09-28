# Retrieval, Qdrant, Cleanup and Compose Corrections Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 保证聊天只读取当前版本，提供可执行的全局向量维度重建，完整清理知识库关联数据，并稳定 Compose 首次启动顺序。

**Architecture:** 保留共享 Qdrant collection，通过 QdrantService 的全局重建状态阻断检索，通过 FileService 的公平读写锁隔离普通文件处理与全局重建。知识库删除使用 JVM 内删除标记和数据库存活校验阻止异步任务回写，并显式清理所有无外键关联表和磁盘文件。

**Tech Stack:** Java 17、Spring Boot 3.2、MyBatis、JUnit 5、Mockito、Qdrant REST API、Docker Compose

---

### Task 1: 当前版本检索约束

**Files:**
- Modify: `rag-server/src/main/resources/mapper/ChunkMapper.xml`
- Test: `rag-server/src/test/java/com/rag/mapper/ChunkMapperSqlTest.java`

- [ ] **Step 1: 写失败测试**

读取 `mapper/ChunkMapper.xml`，定位 `findByIdAndKbId` SQL，断言同时包含 `f.is_current` 和 `f.status = 'COMPLETED'`。

- [ ] **Step 2: 验证测试按预期失败**

Run: `mvn -B -Dtest=ChunkMapperSqlTest test`

Expected: FAIL，缺少当前版本和完成状态条件。

- [ ] **Step 3: 实现最小修复**

在 `findByIdAndKbId` 中增加：

```sql
AND (f.is_current = 1 OR f.is_current IS NULL)
AND f.status = 'COMPLETED'
```

- [ ] **Step 4: 验证定向测试通过**

Run: `mvn -B -Dtest=ChunkMapperSqlTest test`

Expected: PASS。

### Task 2: Qdrant 状态识别、幂等删除和全局重建

**Files:**
- Modify: `rag-server/src/main/java/com/rag/rag/QdrantService.java`
- Modify: `rag-server/src/test/java/com/rag/rag/QdrantServiceTest.java`

- [ ] **Step 1: 写 collection 访问失败和非法维度失败测试**

使用 `MockRestServiceServer` 验证：404 表示不存在；503 不得触发创建；已存在但缺少 `vectors.size` 时抛出明确业务异常。

- [ ] **Step 2: 验证测试按预期失败**

Run: `mvn -B -Dtest=QdrantServiceTest test`

Expected: FAIL，当前实现会吞掉访问异常或跳过未知维度。

- [ ] **Step 3: 实现 collection 状态识别**

仅捕获 `HttpClientErrorException.NotFound` 并返回不存在；其他 REST 异常转为 503。集合信息存在但无法解析维度时抛出 500 业务异常，且不设置 `collectionReady`。

- [ ] **Step 4: 写幂等删除失败测试并验证失败**

增加 `deleteByFileId`、`deleteByKbId` 的 404 成功测试和 500 失败测试。

Run: `mvn -B -Dtest=QdrantServiceTest test`

Expected: FAIL，当前 404 会抛异常。

- [ ] **Step 5: 实现幂等删除和全局重建状态**

增加以下公开行为：

```java
public boolean isRebuilding()
public void startRebuild()
public void recreateCollection(int vectorSize)
public void finishRebuild()
```

搜索在重建期间抛出 409；重建方法删除旧 collection 后按新维度创建，并更新内存中的真实维度。collection 删除 404 视为成功，其他错误继续抛出。

- [ ] **Step 6: 验证 Qdrant 测试通过**

Run: `mvn -B -Dtest=QdrantServiceTest test`

Expected: PASS。

### Task 3: 管理员全局重建与异步隔离

**Files:**
- Modify: `rag-server/src/main/java/com/rag/rbac/service/RbacService.java`
- Modify: `rag-server/src/main/java/com/rag/mapper/FileMapper.java`
- Modify: `rag-server/src/main/resources/mapper/FileMapper.xml`
- Modify: `rag-server/src/main/java/com/rag/service/FileService.java`
- Modify: `rag-server/src/main/java/com/rag/controller/KnowledgeBaseController.java`
- Modify: `rag-server/src/test/java/com/rag/rbac/service/RbacServiceTest.java`
- Modify: `rag-server/src/test/java/com/rag/service/FileServiceTest.java`
- Modify: `rag-server/src/test/java/com/rag/controller/KnowledgeBaseControllerTest.java`

- [ ] **Step 1: 写管理员角色和全局重建失败测试**

验证只有启用的 `admin` 角色返回 true；全局重建拒绝普通用户、探测新向量维度、重建 collection、清空全部切片并只重新处理当前文件。

- [ ] **Step 2: 验证测试按预期失败**

Run: `mvn -B '-Dtest=RbacServiceTest,FileServiceTest,KnowledgeBaseControllerTest' test`

Expected: FAIL，缺少管理员判断和全局重建入口。

- [ ] **Step 3: 实现管理员判断和全局重建**

`RbacService.hasRole` 使用 `roleMapper.findByUserId` 判断角色编码和启用状态。`FileMapper.findAllCurrent` 查询所有当前版本文件，`markAllVectorsPending` 将全部文件向量状态改为 `PENDING`。

`FileService.rebuildAllVectors` 先验证管理员并取得当前文件快照，再设置 Qdrant 重建状态并提交一个后台任务。后台任务持有公平写锁，探测当前 Embedding 维度、重建 collection、删除全部 chunk、标记全部向量待处理，逐文件执行完整处理，最后释放重建状态。普通 `processFile` 持有读锁；单库重建在全局重建期间返回 409。

- [ ] **Step 4: 暴露管理员接口**

增加：

```java
@PostMapping("/rebuild-vectors")
public Result<Integer> rebuildAllVectors(HttpServletRequest request)
```

保留 `/{id}/rebuild-vectors` 作为同维度的单库重建。

- [ ] **Step 5: 验证定向测试通过**

Run: `mvn -B '-Dtest=RbacServiceTest,FileServiceTest,KnowledgeBaseControllerTest' test`

Expected: PASS。

### Task 4: 删除竞态、关联表和磁盘清理

**Files:**
- Modify: `rag-server/src/main/java/com/rag/eval/EvalMapper.java`
- Modify: `rag-server/src/main/resources/mapper/EvalMapper.xml`
- Modify: `rag-server/src/main/java/com/rag/agent/mapper/AgentRunMapper.java`
- Modify: `rag-server/src/main/resources/mapper/AgentRunMapper.xml`
- Modify: `rag-server/src/main/java/com/rag/mapper/MultipartUploadSessionMapper.java`
- Modify: `rag-server/src/main/resources/mapper/MultipartUploadSessionMapper.xml`
- Modify: `rag-server/src/main/java/com/rag/service/FileService.java`
- Modify: `rag-server/src/test/java/com/rag/service/FileServiceTest.java`

- [ ] **Step 1: 写删除竞态和完整清理失败测试**

验证知识库进入删除状态后文件处理不会写 chunk 或 Qdrant；删除会清理评测明细、评测运行、评测用例、智能体运行、分片临时目录和备份文件；磁盘删除失败时不继续删除数据库记录。

- [ ] **Step 2: 验证测试按预期失败**

Run: `mvn -B -Dtest=FileServiceTest test`

Expected: FAIL，当前缺少删除标记、评测/智能体清理和临时目录删除。

- [ ] **Step 3: 实现异步存活校验**

FileService 维护正在删除的知识库编号集合。处理任务在解析后、写 chunk 前、写向量前调用同一个存活校验，确认知识库、文件仍存在且知识库不在删除集合；失败后直接退出，不写失败状态。

- [ ] **Step 4: 实现完整清理**

新增 mapper 删除方法并按子表到父表顺序执行：评测运行明细、评测运行、评测用例、智能体运行、切片、版本、标签关联、标签、目录、文件、分片记录、分片会话。删除数据库记录前严格删除原始文件、`.parts/{uploadId}` 和 `backups/kb-{id}-*.zip`；任一磁盘删除失败抛出业务异常。

- [ ] **Step 5: 验证删除测试通过**

Run: `mvn -B -Dtest=FileServiceTest,KnowledgeBaseServiceTest test`

Expected: PASS。

### Task 5: Docker Compose 启动顺序

**Files:**
- Modify: `docker-compose.yml`
- Modify: `README.md`

- [ ] **Step 1: 更新 Compose 配置**

固定 Qdrant 和 Ollama 镜像版本；为 Ollama 添加 `ollama list` 健康检查；`ollama-init` 等待健康并循环拉取 `bge-m3`；后端使用 `service_completed_successfully` 等待模型初始化完成。

- [ ] **Step 2: 更新运维说明**

README 明确单库重建仅适用于同维度，全局维度变化调用管理员全局重建接口，并说明首次启动会等待默认模型拉取完成。

- [ ] **Step 3: 验证 YAML 和关键依赖关系**

Run: `python -c "import yaml; data=yaml.safe_load(open('docker-compose.yml',encoding='utf-8')); assert data['services']['rag-server']['depends_on']['ollama-init']['condition']=='service_completed_successfully'; print('compose yaml: OK')"`

Expected: `compose yaml: OK`。

### Task 6: 全量验证

**Files:**
- Verify only

- [ ] **Step 1: 后端全量测试**

Run: `mvn -B test`

Expected: BUILD SUCCESS，0 failures，0 errors。

- [ ] **Step 2: 前端生产构建**

Run: `npm run build`

Expected: Vite build success。

- [ ] **Step 3: 静态质量检查**

Run: `git diff --check`

Expected: 无空白错误。

- [ ] **Step 4: Docker 可用时执行 Compose 验证**

Run: `docker compose config` 和 `docker compose build rag-server rag-web`

Expected: 配置和镜像构建成功；若本机没有 Docker，明确记录为环境限制，不宣称已完成容器联调。
