# 文档结构化抽取模块设计

## 1. 背景和目标

当前平台已经具备用户登录、模型供应商配置、文件上传、文档解析、RAG 召回和流式问答能力。新增的文档结构化抽取模块用于把 PDF、Word 等非结构化文档转换为可校正、可追溯、可导出的结构化字段数据。

第一版采用增强可追溯范围，目标是跑通合同基础信息抽取闭环：

```text
上传文档 -> 选择模板 -> 创建抽取任务 -> AI 抽取字段 -> 查看原文证据 -> 人工校正 -> 导出 JSON / Excel
```

第一版不做 OCR、PDF 坐标框高亮、批量上传、Webhook、复杂模板设计器和多用户协同校正。

## 2. 产品范围

### 2.1 第一版包含

- 独立的“文档抽取”顶部菜单和页面。
- PDF / Word 文档上传。
- 内置“合同基础信息模板”。
- 抽取任务创建和状态查看。
- 字段抽取结果保存。
- 字段结果包含字段值、原文片段、页码、置信度和结果状态。
- 结果校正页面支持人工修改字段值。
- 人工修改写入校正记录。
- 支持导出 JSON 和 Excel。

### 2.2 第一版不包含

- 扫描件 OCR。
- PDF.js 原文坐标框高亮。
- 自定义模板可视化编辑器。
- 批量上传和批量抽取。
- 外部业务系统 API 推送。
- Webhook 回调。
- 复杂审批流。

## 3. 推荐架构

文档抽取作为独立模块接入平台，不复用知识库文件表作为主数据。原因是 RAG 的核心对象是文本块和向量，文档抽取的核心对象是抽取任务、字段结果和原文证据。

后端新增包：

```text
com.rag.extract.controller
com.rag.extract.service
com.rag.extract.mapper
com.rag.extract.entity
```

前端新增：

```text
rag-web/src/api/extract.js
rag-web/src/views/extract/ExtractView.vue
```

路由新增：

```text
/extract
```

顶部菜单新增：

```text
文档抽取
```

## 4. 数据模型

### 4.1 extract_document

保存抽取模块自己的文档文件记录。

字段：

```text
id
file_name
file_type
file_size
file_path
parse_status
page_count
full_text
uploaded_by
create_time
```

说明：

- `full_text` 第一版保存解析后的全文，便于任务重复抽取。
- `parse_status` 使用 `UPLOADED / PARSED / FAILED`。
- 第一版页码可通过解析策略尽量估算；无法识别页码时默认第 1 页。

### 4.2 extract_template

保存抽取模板。

字段：

```text
id
template_name
template_code
document_type
description
enabled
create_time
```

第一版内置一条合同模板：

```text
template_code = contract_basic
template_name = 合同基础信息模板
```

### 4.3 extract_field

保存模板字段定义。

字段：

```text
id
template_id
field_code
field_name
field_type
required
multiple
field_prompt
example_value
regex_rule
confidence_threshold
sort_no
```

第一版内置字段：

```text
contractName
contractNo
partyA
partyB
amount
signDate
startDate
endDate
contactPerson
contactPhone
```

### 4.4 extract_task

保存一次抽取任务。

字段：

```text
id
document_id
template_id
task_status
task_message
started_at
finished_at
created_by
create_time
```

任务状态：

```text
PENDING
PARSING
EXTRACTING
REVIEWING
SUCCESS
FAILED
```

第一版任务完成后进入 `REVIEWING`，用户导出不改变任务状态。

### 4.5 extract_result

保存字段抽取结果。

字段：

```text
id
task_id
document_id
field_id
field_code
field_name
field_value
raw_text
page_no
confidence
result_status
is_modified
create_time
update_time
```

结果状态：

```text
SUCCESS
WARNING
ERROR
MISSING
MODIFIED
IGNORED
```

状态规则：

- 必填字段无值时为 `MISSING`。
- 置信度低于字段阈值时为 `WARNING`。
- 正则校验失败时为 `ERROR`。
- 用户修改后为 `MODIFIED`。
- 其他成功结果为 `SUCCESS`。

### 4.6 extract_review_record

保存人工校正记录。

字段：

```text
id
result_id
old_value
new_value
review_by
review_at
remark
```

### 4.7 extract_export_record

保存导出记录。

字段：

```text
id
task_id
export_type
export_path
exported_by
create_time
```

第一版 JSON 可以直接返回下载内容；Excel 可以生成本地文件并记录路径。

## 5. 后端接口

### 5.1 查询模板列表

```text
GET /api/extract/templates
```

返回启用模板和字段列表。

### 5.2 上传文档

```text
POST /api/extract/documents/upload
```

请求：

```text
multipart/form-data file
```

返回：

```json
{
  "documentId": 1001,
  "fileName": "contract.pdf",
  "parseStatus": "UPLOADED"
}
```

### 5.3 创建抽取任务

```text
POST /api/extract/tasks
```

请求：

```json
{
  "documentId": 1001,
  "templateId": 1
}
```

返回：

```json
{
  "taskId": 2001,
  "taskStatus": "PENDING"
}
```

### 5.4 查询任务列表

```text
GET /api/extract/tasks
```

返回当前用户创建的任务列表。

### 5.5 查询任务详情

```text
GET /api/extract/tasks/{taskId}
```

返回任务、文档和模板摘要。

### 5.6 查询任务结果

```text
GET /api/extract/tasks/{taskId}/results
```

返回字段抽取结果列表。

### 5.7 保存人工校正

```text
PUT /api/extract/results/{resultId}
```

请求：

```json
{
  "newValue": "某某科技有限公司",
  "remark": "人工确认修改"
}
```

行为：

- 更新 `extract_result.field_value`。
- 设置 `is_modified = 1`。
- 设置 `result_status = MODIFIED`。
- 插入 `extract_review_record`。

### 5.8 导出结果

```text
POST /api/extract/tasks/{taskId}/export
```

请求：

```json
{
  "exportType": "json"
}
```

支持：

```text
json
excel
```

## 6. 抽取流程

### 6.1 文档解析

第一版复用现有 `DocumentParser` 的能力，将文件解析为全文。PDF / Word 中无法稳定拿到页码时，先默认页码为 1。

后续如果要增强页码和坐标，可新增专门的 PDFBox 解析器，将文档拆成页、段落和文本块。

### 6.2 AI 字段抽取

后端根据模板字段和文档全文构建 Prompt，调用当前激活的大模型供应商。

模型输出必须是 JSON：

```json
{
  "fields": [
    {
      "fieldCode": "partyA",
      "value": "某某有限公司",
      "rawText": "甲方：某某有限公司",
      "pageNo": 1,
      "confidence": 0.95,
      "reason": "原文明确出现甲方字段"
    }
  ]
}
```

服务端必须做 JSON 解析和容错：

- 模型返回 Markdown 代码块时，提取代码块中的 JSON。
- 模型返回字段缺失时，保存 `MISSING` 结果。
- 模型返回非法 JSON 时，任务标记为 `FAILED` 并保存错误信息。

### 6.3 规则校验

第一版内置基础校验：

- 必填校验。
- 日期格式宽松校验。
- 金额格式宽松校验。
- 手机号格式校验。
- 字段自带正则校验。

校验不会阻止保存结果，只影响 `result_status`。

## 7. 前端设计

### 7.1 页面结构

新增 `文档抽取` 页面，采用工作台布局：

```text
顶部：模块标题、上传按钮、模板选择、开始抽取按钮
中部：任务列表
底部/右侧：任务结果校正区
```

### 7.2 任务列表

展示：

```text
文件名
模板名称
任务状态
创建时间
操作
```

操作：

```text
查看结果
导出 JSON
导出 Excel
```

### 7.3 结果校正区

左右布局：

```text
左侧：当前选中字段的原文片段、页码、置信度、状态说明
右侧：字段表单列表
```

字段表单展示：

```text
字段名称
字段值输入框
置信度
状态标签
原文片段
保存按钮
```

低置信度结果用 warning 标记，缺失和校验失败结果用 error 标记，人工修改结果用 success 或 primary 标记。

## 8. 错误处理

### 8.1 模型未配置

创建任务时提示：

```text
请先在模型设置中配置并激活模型
```

任务不进入执行。

### 8.2 文档解析失败

任务状态设置为 `FAILED`，`task_message` 保存解析失败原因。

### 8.3 模型调用失败

任务状态设置为 `FAILED`，`task_message` 保存模型调用失败摘要。

### 8.4 模型 JSON 解析失败

任务状态设置为 `FAILED`，`task_message` 保存“模型返回格式不正确”。

### 8.5 导出失败

接口返回失败消息，不改变抽取任务状态。

## 9. 权限规则

第一版沿用当前登录用户体系：

- 文档记录保存上传人。
- 任务记录保存创建人。
- 查询任务列表只返回当前用户任务。
- 查询任务详情、结果、导出和校正时校验任务创建人。

## 10. 验收标准

完成后需要验证：

- 顶部菜单存在“文档抽取”。
- `/extract` 页面可以进入。
- 可以上传 PDF / Word。
- 可以选择“合同基础信息模板”。
- 可以创建抽取任务。
- 任务完成后可以看到字段结果。
- 字段结果包含字段值、原文片段、页码、置信度和状态。
- 可以修改字段值并保存校正记录。
- 可以导出 JSON。
- 可以导出 Excel。
- 后端 Maven 构建通过。
- 前端 Vite 构建通过。

## 11. 后续扩展

后续版本可以增加：

- PDF.js 预览和坐标高亮。
- PDFBox 按页解析和 bbox 定位。
- OCR 支持扫描件和图片。
- 模板管理页面。
- 字段配置页面。
- 批量上传和批量抽取。
- 业务系统 API 推送。
- Webhook 回调。
- 抽取准确率统计。
