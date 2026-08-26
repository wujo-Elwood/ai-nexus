import request from '../utils/request'

// 调用 AI 生图接口，后端会创建异步任务并立即返回任务信息
export function generateImage(data) {
  return request.post('/api/image/generate', data, { timeout: 30000 })
}

// 查询 AI 生图任务列表
export function getImageTasks(limit = 20) {
  return request.get('/api/image/tasks', { params: { limit } })
}

// 查询单个 AI 生图任务
export function getImageTask(id) {
  return request.get(`/api/image/tasks/${id}`)
}

// 查询 AI 生图历史
export function getImageHistory(limit = 30) {
  return request.get('/api/image/history', { params: { limit } })
}

// 删除 AI 生图历史
export function deleteImageHistory(id) {
  return request.delete(`/api/image/history/${id}`)
}
