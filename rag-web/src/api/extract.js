import request from '../utils/request'

// 查询文档抽取模板列表
export function getExtractTemplates() {
  // 第1步：请求后端启用的抽取模板
  return request.get('/api/extract/templates')
}

// 保存文档抽取模板
export function saveExtractTemplate(data) {
  // 第1步：提交模板基础信息和字段列表
  return request.post('/api/extract/templates', data)
}

// 删除文档抽取模板
export function deleteExtractTemplate(templateId) {
  // 第1步：按模板编号删除当前用户创建的模板
  return request.delete(`/api/extract/templates/${templateId}`)
}

// 上传待抽取文档
export function uploadExtractDocument(file) {
  // 第1步：创建文件上传表单
  const formData = new FormData()
  // 第2步：把文件放入后端约定的 file 字段
  formData.append('file', file)
  // 第3步：提交 multipart 上传请求
  return request.post('/api/extract/documents/upload', formData, {
    headers: { 'Content-Type': 'multipart/form-data' }
  })
}

// 创建文档抽取任务
export function createExtractTask(data) {
  // 第1步：提交文档编号和模板编号
  // 第2步：抽取会同步调用模型，单独放宽超时时间
  return request.post('/api/extract/tasks', data, { timeout: 180000 })
}

// 查询当前用户的抽取任务
export function getExtractTasks() {
  // 第1步：读取任务列表
  return request.get('/api/extract/tasks')
}

// 查询抽取任务详情
export function getExtractTask(taskId) {
  // 第1步：按任务编号读取任务详情
  return request.get(`/api/extract/tasks/${taskId}`)
}

// 查询抽取任务结果
export function getExtractResults(taskId) {
  // 第1步：按任务编号读取字段结果
  return request.get(`/api/extract/tasks/${taskId}/results`)
}

// 更新人工校正后的字段值
export function updateExtractResult(resultId, data) {
  // 第1步：提交字段新值和备注
  return request.put(`/api/extract/results/${resultId}`, data)
}

// 导出抽取任务结果
export function exportExtractTask(taskId, exportType) {
  // 第1步：按导出类型请求二进制文件
  return request.post(`/api/extract/tasks/${taskId}/export`, { exportType }, { responseType: 'blob' })
}
