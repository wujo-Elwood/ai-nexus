# AI Nexus 企业级 AI 平台

AI Nexus 是一个基于 Spring Boot、LangChain4j、Vue 3、MySQL 和 Qdrant 的企业级 AI 应用平台，整合智能对话、知识库 RAG 问答、AI 生图、能力展示和企业权限管理。系统覆盖文件入库、混合检索、可信问答、引用溯源、知识治理、RAG 评测、任务管理、系统健康监控、文档抽取、智能体和 RBAC 权限管理。

![Java](https://img.shields.io/badge/Java-17-blue)
![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.2.5-brightgreen)
![LangChain4j](https://img.shields.io/badge/LangChain4j-0.36.2-orange)
![Vue](https://img.shields.io/badge/Vue-3.4-brightgreen)
![MySQL](https://img.shields.io/badge/MySQL-5.7%2B-blue)
![Qdrant](https://img.shields.io/badge/Qdrant-1.x-purple)
![License](https://img.shields.io/badge/License-MIT-yellow)

## 功能全览

### 知识库与文件管理

- 创建、编辑、删除知识库，支持私有和公开可见范围。
- 本地磁盘存储，上传文件、预览、下载、删除和重新处理。
- 普通上传和大文件分片上传；支持断点续传、进度查询、暂停后继续、取消和过期分片清理。
- 文件处理阶段、百分比进度、失败原因、累计尝试次数和定时自动重试。
- 同名文件自动形成新版本，支持历史版本查询和回滚。
- SHA-256 文件哈希去重，避免同一知识库重复入库相同文件。
- 文件夹、标签、分类和文档目录管理。
- 知识库处理策略：切片大小、重叠长度、标题层级切片和表格保留策略。
- 知识库检索策略：Top K、相似度阈值、向量权重和关键词权重。
- 知识库统计面板和健康评分，统计空文件、低质量切片、重复文件、重复内容、向量缺失和处理失败。
- 知识库 ZIP 导出、备份恢复和重复文件幂等恢复。

### 文档解析与入库

- 支持 PDF、DOC、DOCX、XLSX、TXT 和 Markdown。
- Apache Tika 负责 PDF、Office 和文本内容提取。
- Apache POI 将 XLSX 工作表转换为保留行列结构的 Markdown 表格。
- PDF 表格提取文字内容，但不保证恢复原始行列结构。
- 按段落、句子和固定长度自适应切片，支持知识库级切片参数。
- Embedding 批量向量化并写入 Qdrant，切片原文同步保存到 MySQL。

### 检索与可信问答

- 向量检索与关键词检索混合召回。
- 查询改写、候选集扩展、融合打分、重排序和结果去重。
- 检索缓存、上下文压缩和多样性控制。
- 召回测试和检索诊断台，可查看改写问题、候选结果、分数和来源。
- SSE 流式问答、会话历史、回答反馈和用量统计。
- 可信回答控制：没有有效知识证据时拒答，避免脱离知识库自由编造。
- 回答引用校验和来源展示，可追溯文件、切片、匹配方式及分数。
- 回答置信度：结合引用分数、引用数量和证据覆盖率输出 HIGH、MEDIUM、LOW 或 NONE。
- 证据覆盖率：统计回答句子与有效知识引用的覆盖比例，并在聊天界面展示。
- 知识摘要：在知识库洞察页按当前版本已完成切片生成、查看和刷新知识库摘要。

### 企业管理能力

- 统一任务中心：聚合文件处理、分片上传、文档抽取、智能体和生图任务；支持文件任务重试和分片上传取消。
- RAG 评测中心：维护测试集，执行召回评测，统计命中、引用命中和平均耗时。
- 系统健康面板：检查 MySQL、Qdrant、Embedding、LLM、磁盘空间和线程池状态。
- RBAC：菜单、角色、角色菜单、用户角色和用户管理。
- 模型供应商按创建人隔离：普通用户只能查看和操作自己创建的供应商及其 API Key，管理员可查看全部；他人创建且正在使用的供应商对普通用户只展示名称和模型，不下发密钥。
- 用户资料和密码修改；注册时校验两次密码一致。
- 允许通过 `localhost`、`127.0.0.1` 和实际局域网 IP 访问前端开发服务。

### 扩展工作台

- 文档抽取：文档上传、抽取模板、字段配置、异步任务、人工修正和结果导出。
- AI 生图：OpenAI 兼容生图供应商、异步生成任务、历史记录、查看、下载和删除。
- 智能体管理：知识库质检智能体、运行记录、风险项、质量报告和历史报告。
- 通用工具智能体：模型自主编排工具调用（首个工具为 Open-Meteo 天气查询，无需 API Key），每个工具调用步骤实时推送到前端并落库 `agent_tool_step`，支持事后回放。
- 能力展示：粒子文字、赛博城市、贾维斯 HUD、分形隧道、黑洞、水墨等多套 WebGL 视觉展示页。

> 当前不包含图片 OCR、图片语义理解或流程图理解。AI 生图是独立功能，不参与知识库文档检索。
> 自动生成业务报告不在当前范围；智能体质量报告属于既有质检功能。

## 技术栈

### 后端

| 技术 | 用途 |
| --- | --- |
| Java 17 / Spring Boot 3.2.5 | Web 服务、任务调度和业务编排 |
| LangChain4j 0.36.2 | OpenAI 兼容聊天模型调用 |
| MyBatis / MySQL 5.7+ | 业务数据、权限、任务和知识元数据 |
| Qdrant 1.x | 文本切片向量存储与相似度检索 |
| Apache Tika 2.9.1 | PDF、DOC、DOCX 和文本解析 |
| Apache POI 5.2.3 | XLSX 表格结构化解析 |
| JWT / BCrypt | 登录认证和密码散列 |
| SSE | AI 回答流式推送 |

### 前端

| 技术 | 用途 |
| --- | --- |
| Vue 3 / Vite 5 | 前端框架和开发构建 |
| Vue Router / Pinia | 路由和用户状态 |
| Axios | HTTP 请求和代理访问 |
| Element Plus | UI 组件 |
| Markdown-It | 回答 Markdown 渲染 |
| GSAP | 页面动效 |

## 项目结构

```text
ai-nexus
├── docker-compose.yml                # MySQL/Qdrant/Ollama/前后端一键部署编排
├── database/                         # 初始化和历史数据库升级脚本
├── docs/                             # 设计、计划和模块说明
├── rag-server/
│   └── src/main/java/com/rag/
│       ├── agent/                    # 智能体和知识库质检
│       ├── ai/                       # 聊天、Embedding 和模型调用
│       ├── backup/                   # 知识库导入导出与恢复
│       ├── catalog/                  # 文件夹和标签
│       ├── controller/               # 基础、文件、聊天和用户接口
│       ├── eval/                     # RAG 评测
│       ├── extract/                  # 文档抽取工作台
│       ├── health/                   # 系统健康检查
│       ├── image/                    # AI 生图
│       ├── kb/                       # 知识库策略和统计
│       ├── quality/                  # 知识库健康评分
│       ├── rag/                      # 解析、切片、检索、引用和可信控制
│       ├── rbac/                     # 菜单、角色和用户权限
│       ├── service/                  # 文件、知识库和分片上传服务
│       └── task/                     # 统一任务中心
├── rag-web/
│   ├── public/                       # 前端静态资源
│   ├── scripts/                      # 前端辅助脚本
│   └── src/
│       ├── api/                      # 后端 API 封装
│       ├── layouts/                  # 统一主布局
│       ├── router/                   # 页面路由
│       ├── styles/                   # 全局 UI 样式
│       └── views/                    # 业务页面
├── scripts/                          # 测试文档生成脚本
├── test-documents/                   # 文档抽取测试样本
└── README.md
```

## 环境要求

| 环境 | 版本 |
| --- | --- |
| JDK | 17+ |
| Maven | 3.8+ |
| Node.js | 18+ |
| MySQL | 5.7+ 或 8.x |
| Qdrant | 1.x |
| Ollama | 可选，默认用于本地 Embedding |

## 快速启动

### Docker Compose 一键部署（推荐）

前置要求：已安装 Docker 和 Docker Compose v2。一条命令拉起 MySQL、Qdrant、Ollama（自动拉取 `bge-m3` 模型）、后端和前端：

```bash
docker compose up -d --build
```

- 首次构建需下载镜像、Maven/npm 依赖和约 1.2GB 的 `bge-m3` 模型，耗时较长。
- 启动完成后访问 `http://localhost:8080`，注册账号，然后在"模型设置"页面配置聊天模型供应商（聊天模型的 base_url、api_key 需自备，Embedding 已由内置 Ollama 提供）。
- 后端 API 调试地址：`http://localhost:8888`；Qdrant 控制台：`http://localhost:6333/dashboard`。
- 数据持久化：MySQL、Qdrant、Ollama 模型使用命名卷；上传文件保存在项目 `./uploads` 目录。
- 数据库建表由首次启动时自动挂载执行的 `database/init.sql` 完成，无需手动初始化。

生产部署请在项目根目录创建 `.env` 覆盖默认密码和密钥；国内网络可同时配置构建加速：

```dotenv
MYSQL_ROOT_PASSWORD=your-mysql-password
JWT_SECRET=your-long-random-secret
MAVEN_MIRROR=https://maven.aliyun.com/repository/public
NPM_REGISTRY=https://registry.npmmirror.com
```

常用命令：

```bash
docker compose logs -f rag-server   # 查看后端日志
docker compose ps                   # 查看各服务状态
docker compose down                 # 停止（数据卷保留）
docker compose down -v              # 停止并清空全部数据
```

#### Linux 服务器部署

Compose 文件和全部镜像本身就是 Linux 容器，Linux 服务器上开箱即用，无需平台适配。步骤如下：

1. 安装 Docker Engine 和 Compose v2 插件（Ubuntu / Debian 为例）：

```bash
curl -fsSL https://get.docker.com | sh
sudo systemctl enable --now docker

# 让当前用户免 sudo 使用 docker，重新登录后生效
sudo usermod -aG docker $USER

docker compose version   # 验证输出 v2.x
```

CentOS / RHEL / openEuler 先配置 docker-ce 软件源，再执行
`sudo yum install -y docker-ce docker-ce-cli containerd.io docker-compose-plugin`。

2. 服务器建议配置：2 核 4G 内存起步（MySQL、Java 后端、Ollama 同机运行），磁盘预留 10G 以上（镜像、模型和数据卷）。

3. 上传代码到服务器并配置 `.env`（内容见上方示例，至少修改密码和 JWT 密钥）。

4. 放行 8080 端口，云服务器还需在控制台安全组放行：

```bash
sudo ufw allow 8080/tcp                                                    # Ubuntu / Debian
sudo firewall-cmd --permanent --add-port=8080/tcp && sudo firewall-cmd --reload   # CentOS / RHEL
```

5. 在项目根目录执行 `docker compose up -d --build`，完成后访问 `http://<服务器IP>:8080`。
   前端由 nginx 同源反代后端，Linux 上无需配置 CORS。

6. 所有服务已配置 `restart: unless-stopped`，配合 Docker 开机自启，服务器重启后自动恢复，无需人工干预。

可选 GPU 加速：服务器有 NVIDIA 显卡时，安装驱动和
[nvidia-container-toolkit](https://docs.nvidia.com/datacenter/cloud-native/container-toolkit/latest/install-guide.html)
后为 compose 中的 `ollama` 服务添加 GPU 配置即可加速 Embedding；纯 CPU 同样可以运行。

### 手动部署

### 1. 克隆项目

```bash
git clone https://github.com/wujo-Elwood/ai-nexus.git
cd ai-nexus
```

### 2. 初始化全新数据库

`database/init.sql` 包含当前版本需要的全部表结构（仅建表和字段，不含业务数据）。MySQL 客户端应显式使用 `utf8mb4`，避免执行脚本时出现 `ERROR 1366 Incorrect string value`。

普通终端：

```bash
mysql --default-character-set=utf8mb4 -u root -p < database/init.sql
```

Windows PowerShell：

```powershell
Get-Content -Raw -Encoding UTF8 database/init.sql |
  mysql --default-character-set=utf8mb4 -u root -p
```

### 3. 启动 Qdrant

```bash
docker run --name ai-nexus-qdrant -p 6333:6333 -p 6334:6334 -v ./qdrant_storage:/qdrant/storage qdrant/qdrant
```

### 4. 启动 Embedding 服务

默认配置使用 Ollama `bge-m3`：

```bash
ollama pull bge-m3
ollama serve
```

默认接口为 `http://localhost:11434/api/embeddings`。

### 5. 配置后端

编辑 `rag-server/src/main/resources/application.yml`，至少检查数据库账号、上传目录和 JWT 密钥。不要把生产密码或 API Key 提交到仓库。

```yaml
server:
  port: 8888

spring:
  datasource:
    url: jdbc:mysql://localhost:3306/rag_db?useUnicode=true&characterEncoding=utf-8&serverTimezone=Asia/Shanghai
    username: your_mysql_username
    password: your_mysql_password

file:
  upload-dir: D:/data/ai-nexus/uploads

jwt:
  secret: replace-with-a-long-random-secret
```

常用环境变量：

| 环境变量 | 默认值 | 说明 |
| --- | --- | --- |
| `CORS_ALLOWED_ORIGIN_PATTERNS` | `http://*:5173` | 前端来源规则，多个规则用逗号分隔 |
| `EMBEDDING_API_URL` | `http://localhost:11434/api/embeddings` | Embedding 接口 |
| `EMBEDDING_HEALTH_URL` | 空 | 独立健康检查地址 |
| `EMBEDDING_MODEL` | `bge-m3` | Embedding 模型 |
| `FILE_UPLOAD_DIR` | 项目本地 `uploads` 目录 | 原始文件、备份、抽取结果和分片存储 |
| `FILE_PROCESS_MAX_ATTEMPTS` | `3` | 文件处理最大尝试次数 |
| `FILE_PROCESS_RETRY_DELAY_SECONDS` | `60` | 首次自动重试延迟 |
| `FILE_MULTIPART_MAX_SIZE` | `2147483648` | 分片上传最大文件字节数，默认 2 GiB |
| `FILE_MULTIPART_CHUNK_SIZE` | `10485760` | 默认分片大小，10 MiB |
| `FILE_MULTIPART_EXPIRE_HOURS` | `24` | 未完成上传保留时长 |
| `QDRANT_BASE_URL` | `http://localhost:6333` | Qdrant HTTP 地址 |
| `QDRANT_COLLECTION` | `rag_chunks` | 向量集合名 |
| `RAG_CANDIDATE_K` | `10` | 候选召回数 |
| `RAG_SIMILARITY_THRESHOLD` | `0.25` | 默认相似度阈值 |
| `RAG_VECTOR_WEIGHT` | `0.6` | 向量分数权重 |
| `RAG_KEYWORD_WEIGHT` | `0.4` | 关键词分数权重 |
| `HEALTH_TIMEOUT_MS` | `3000` | 依赖健康检查超时 |
| `HEALTH_DISK_WARNING_PERCENT` | `85` | 磁盘使用率告警阈值 |

### 6. 配置模型供应商

启动后在“模型设置”页面新增 OpenAI 兼容供应商并激活。聊天需要 `base_url`、`api_key` 和 `model`；AI 生图可单独配置 `image_base_url`、`image_api_key` 和 `image_model`，留空时沿用聊天配置。

供应商会记录创建人：普通用户只看到并只能修改、删除、激活自己创建的供应商，管理员可以看到并操作全部，列表额外展示创建人。`is_active` 仍是全局单值，全系统共用同一个激活供应商，因此普通用户列表中会出现一项只读的“平台供应商”用于展示当前正在使用的模型，其密钥不下发。

### 7. 启动后端

Windows：

```powershell
cd rag-server
mvn.cmd spring-boot:run
```

macOS / Linux：

```bash
cd rag-server
mvn spring-boot:run
```

后端地址：`http://localhost:8888`

### 8. 启动前端

```bash
cd rag-web
npm install
npm run dev
```

前端监听 `0.0.0.0:5173`，可使用以下地址：

- `http://localhost:5173`
- `http://127.0.0.1:5173`
- `http://<局域网IP>:5173`

## 历史数据库升级

已有数据库不要重新执行整库初始化。先备份 `rag_db`，再从仓库根目录按以下顺序执行升级脚本。脚本按 MySQL 5.7 编写，支持重复执行。

| 顺序 | 脚本 | 作用 |
| --- | --- | --- |
| 1 | `extract_v2_upgrade.sql` | 抽取模板创建人、任务进度、失败原因和结果修正字段 |
| 2 | `rbac_management.sql` | 菜单、角色、用户角色和角色菜单表结构 |
| 3 | `agent_management.sql` | 智能体运行与质检报告记录 |
| 4 | `image_model_provider_upgrade.sql` | 模型供应商生图配置字段 |
| 5 | `image_history.sql` | 生图异步任务和历史记录 |
| 6 | `kb_file_process_upgrade.sql` | 文件处理阶段、进度和失败原因 |
| 7 | `kb_file_process_reliability_upgrade.sql` | 文件处理重试次数和下次重试时间 |
| 8 | `kb_multipart_upload_upgrade.sql` | 大文件分片上传会话和分片记录 |
| 9 | `kb_capabilities_upgrade.sql` | 文件版本、哈希、目录、标签、分类和质量字段 |
| 10 | `system_health_upgrade.sql` | 系统健康面板相关表结构 |
| 11 | `kb_second_priority_upgrade.sql` | 知识库策略、任务中心和 RAG 评测表结构 |
| 12 | `kb_summary_upgrade.sql` | 为历史数据库增加知识库摘要内容和更新时间字段 |
| 13 | `model_provider_owner_upgrade.sql` | 为模型供应商增加创建人字段和归属索引 |

普通终端示例：

```bash
mysql --default-character-set=utf8mb4 -u root -p rag_db < database/extract_v2_upgrade.sql
mysql --default-character-set=utf8mb4 -u root -p rag_db < database/rbac_management.sql
# 按上表顺序继续执行其余脚本
```

Windows PowerShell 单个脚本示例：

```powershell
Get-Content -Raw -Encoding UTF8 database/kb_capabilities_upgrade.sql |
  mysql --default-character-set=utf8mb4 -u root -p
```

Windows PowerShell 批量升级：

```powershell
$scripts = @(
  'extract_v2_upgrade.sql',
  'rbac_management.sql',
  'agent_management.sql',
  'image_model_provider_upgrade.sql',
  'image_history.sql',
  'kb_file_process_upgrade.sql',
  'kb_file_process_reliability_upgrade.sql',
  'kb_multipart_upload_upgrade.sql',
  'kb_capabilities_upgrade.sql',
  'system_health_upgrade.sql',
  'kb_second_priority_upgrade.sql',
  'kb_summary_upgrade.sql',
  'model_provider_owner_upgrade.sql'
)

foreach ($script in $scripts) {
  Get-Content -Raw -Encoding UTF8 "database/$script" |
    mysql --default-character-set=utf8mb4 -u root -p
}
```

> 批量脚本会为每个文件分别询问密码。需要无人值守执行时，应使用 MySQL 客户端安全凭据配置，不要把密码写进仓库脚本。

## 前端页面

| 路由 | 页面 | 主要功能 |
| --- | --- | --- |
| `/login` | 登录/注册 | 登录、注册、双密码一致性校验 |
| `/kb` | 知识库 | 创建、编辑、删除和进入知识库 |
| `/file/:kbId` | 文件管理 | 上传、分片续传、版本、目录、标签、分类、备份恢复和健康评分 |
| `/kb/:kbId/insights` | 知识库洞察 | 策略配置、统计、检索诊断和知识摘要 |
| `/chat` | AI 聊天 | RAG 流式问答、引用和历史记录 |
| `/tasks` | 任务中心 | 任务状态、重试和取消 |
| `/eval` | RAG 评测 | 测试集和评测运行 |
| `/health` | 系统健康 | MySQL、Qdrant、Embedding、LLM、磁盘和线程池 |
| `/extract` | 文档抽取 | 模板、字段、任务、审核和导出 |
| `/image` | AI 生图 | 异步生成、历史、查看和下载 |
| `/agents` | 智能体管理 | 智能体入口和运行记录 |
| `/agents/kb-quality` | 知识库质检智能体 | 执行质检并查看质量报告 |
| `/agent-tools` | 工具智能体 | 通用工具调用对话，执行过程逐步可见 |
| `/knowledge-gaps` | 知识缺口分析 | 查看最近 7 天或 30 天的拒答、低质量问题聚类 |
| `/rbac` | 权限管理 | 菜单、角色、授权和用户管理 |
| `/settings` | 模型设置 | 聊天和生图供应商配置 |
| `/profile` | 个人资料 | 昵称和头像信息 |
| `/change-password` | 修改密码 | 校验旧密码并更新密码 |
| `/stats` | 用量统计 | 调用量、Token、耗时和趋势 |
| `/showcase` | 能力展示 | 粒子文字、赛博城市、贾维斯 HUD、分形隧道、黑洞等 WebGL 展示页 |

## 主要 API

所有业务接口以 `/api` 开头。除注册和登录外，接口需要携带登录 Token。

| 模块 | 主要接口 |
| --- | --- |
| 认证与用户 | `POST /auth/register`、`POST /auth/login`、`GET/PUT /user/profile`、`PUT /user/password` |
| 知识库 | `GET/POST /kb`、`GET/PUT/DELETE /kb/{id}`、`GET/PUT /kb/{id}/strategy`、`GET/POST /kb/{id}/summary`、`POST /kb/{id}/rebuild-vectors`、`POST /kb/rebuild-vectors`（管理员全局重建） |
| 文件 | `POST /file/upload`、`GET /file/list/{kbId}`、`GET /file/{id}`、`POST /file/{id}/reprocess`、`DELETE /file/{id}`、预览和下载 |
| 分片上传 | `POST /file/multipart/init`、`PUT /file/multipart/{uploadId}/chunks/{chunkIndex}`、状态、完成和取消 |
| 版本与分类 | `GET /file/{id}/versions`、`POST /file/{id}/rollback`、`PUT /file/{id}/catalog`、文件标签查询和更新 |
| 目录与标签 | `GET /kb/{kbId}/catalog`、文件夹新增/修改/删除、标签新增/删除 |
| 备份恢复 | `GET /kb/{kbId}/backup/export`、`POST /kb/{kbId}/backup/restore` |
| 健康与统计 | `GET /kb/{kbId}/health`、`GET /kb/{kbId}/stats` |
| 聊天检索 | `POST /chat/send`、`POST /chat/stream`、会话历史、召回测试、`POST /chat/diagnose` 和反馈 |
| 任务中心 | `GET /tasks`、`POST /tasks/{taskType}/{taskId}/retry`、取消任务 |
| 知识缺口 | `GET /knowledge-gaps/report?days=7|30`、`POST /knowledge-gaps/analyze?days=7|30`（管理员） |
| RAG 评测 | 评测用例新增/查询/删除、评测运行和运行列表 |
| 系统健康 | `GET /system-health/overview` |
| 文档抽取 | 模板、文档上传、任务、结果修正和导出接口 |
| AI 生图 | 生成、任务、历史、查看、下载和删除接口 |
| 智能体 | 智能体列表、知识库质检运行、报告详情和删除 |
| 工具智能体 | `POST /agent-tools/chat/stream`（SSE：open/step_start/step_result/answer/done/error）、`GET /agent-tools/tools` |
| RBAC | 当前菜单、菜单管理、角色管理、角色授权、用户和用户角色 |
| 模型与统计 | 模型供应商增删改查、激活供应商（均按创建人隔离，管理员可见全部）、`GET /usage/stats` |

## 主要数据库表

| 领域 | 数据表 |
| --- | --- |
| 用户与权限 | `sys_user`、`sys_menu`、`sys_role`、`sys_user_role`、`sys_role_menu` |
| 知识库 | `kb_knowledge_base`、`kb_file`、`kb_chunk` |
| 上传与版本 | `kb_upload_session`、`kb_upload_chunk`、`kb_file_version` |
| 目录与标签 | `kb_file_folder`、`kb_file_tag`、`kb_file_tag_rel` |
| 聊天与模型 | `ai_chat_message`、`ai_model_provider`、`ai_usage_log`、`ai_feedback` |
| RAG 评测 | `kb_eval_case`、`kb_eval_run`、`kb_eval_run_item` |
| 文档抽取 | `extract_document`、`extract_template`、`extract_field`、`extract_task`、`extract_result`、`extract_review_record`、`extract_export_record` |
| AI 生图 | `ai_image_task`、`ai_image_history` |
| 智能体 | `agent_run`、`agent_tool_step` |
| 知识缺口分析 | `ai_answer_quality`、`kb_gap_report` |

## 核心流程

### 文件入库

1. 前端根据文件大小选择普通上传或分片上传。
2. 后端将原始文件保存到本地磁盘并计算 SHA-256。
3. 系统执行重复文件检查和同名文件版本处理。
4. 后台任务解析文档、切片并保存 MySQL。
5. Embedding 服务生成向量，Qdrant 批量写入向量点。
6. 文件状态更新为完成；失败时记录阶段和原因，并按策略重试。

### RAG 问答

1. 系统改写用户问题并查询检索缓存。
2. 同时执行 Qdrant 向量召回和 MySQL 关键词召回。
3. 融合分数、重排序、去重并压缩上下文。
4. LLM 根据带来源标识的证据生成回答。
5. 可信控制校验回答引用；证据不足时返回拒答结果。
6. 前端通过 SSE 展示回答，并显示文件与切片引用。

## 测试与构建

后端测试：

```powershell
cd rag-server
mvn.cmd test
```

前端生产构建：

```powershell
cd rag-web
npm.cmd run build
```

后端打包：

```powershell
cd rag-server
mvn.cmd clean package
```

## 常见问题

### 更换 Embedding 模型

向量库中的存量向量按旧模型生成，直接切换模型会导致维度不匹配报错或检索失准。正确顺序：

1. 修改 `EMBEDDING_MODEL` 环境变量（Docker Compose 中修改对应环境变量）并重启后端。
2. 如果只是同维度重建，可对单个知识库调用重建接口，系统会逐文件自动清空旧向量、重新解析和向量化：

```bash
curl -X POST http://localhost:8888/api/kb/{kbId}/rebuild-vectors -H "Authorization: Bearer <登录Token>"
```

3. 如果新模型维度变化，必须由管理员调用全局重建接口。共享 collection 会被删除并按新维度重建，然后重新处理全部知识库当前版本文件：

```bash
curl -X POST http://localhost:8888/api/kb/rebuild-vectors -H "Authorization: Bearer <管理员Token>"
```

4. 重建是异步的，可在任务中心查看各文件进度；全局重建期间检索会返回“正在重建”，不会读取半成品。
5. 也可以不调接口，在文件管理页对文件逐个“重新处理”，但仅适用于 collection 维度未变化的情况。
6. 维度不匹配时文件处理和聊天会直接返回明确的错误提示，按提示处理即可。

### 登录返回 403

- 确认后端运行在 `8888`，前端运行在 `5173`。
- 确认从 `localhost`、`127.0.0.1` 或局域网 IP 访问时，`CORS_ALLOWED_ORIGIN_PATTERNS` 包含对应来源。
- 开发环境优先通过 Vite 的 `/api` 代理访问，不要在前端写死某个主机地址。

### 文件停留在处理中或处理失败

- 在文件管理或任务中心查看 `process_stage`、进度和失败原因。
- 检查上传目录是否可写，文件格式是否受支持。
- 检查 Embedding 与 Qdrant 健康状态。
- 手动重试前先处理失败依赖；系统也会在最大次数内自动重试。

### 能调用模型但没有知识库召回

- 确认文件状态为 `COMPLETED`，MySQL 中存在切片。
- 确认 Qdrant 集合中存在对应向量，并且入库和检索使用相同 Embedding 模型。
- 在知识库洞察页调整 Top K、阈值和混合检索权重，并使用检索诊断查看候选结果。

### 系统健康显示 Qdrant 或 Embedding 异常

- 健康面板展示真实依赖状态，不会用模拟结果替代。
- Qdrant 默认检查 `http://localhost:6333`。
- Embedding 默认检查 Ollama 服务；模型未拉取或服务未启动时会显示异常。

### SQL 中文写入失败

执行 SQL 时增加 `--default-character-set=utf8mb4`。PowerShell 必须使用 `Get-Content -Raw -Encoding UTF8` 读取脚本。

## 安全说明

- 生产环境必须修改 JWT 密钥、数据库密码和模型 API Key。
- 建议通过外部配置或密钥管理服务注入敏感信息。
- 上传目录只应授予应用进程必要权限，并定期备份 MySQL、Qdrant 和本地文件目录。
- 对外部署时应增加 HTTPS、反向代理、请求限流、文件安全扫描和日志脱敏。

## License

本项目使用 MIT License，详见 [LICENSE](./LICENSE)。
