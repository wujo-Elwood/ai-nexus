import request from '../utils/request'

// 上传并解析 Excel 表格
export function uploadAnalysis(file) {
  const form = new FormData()
  form.append('file', file)
  return request.post('/api/analysis/upload', form, {
    headers: { 'Content-Type': 'multipart/form-data' }
  })
}

// 当前用户的表格列表
export function listAnalysis() {
  return request.get('/api/analysis')
}

// 表格详情（含表结构）
export function getAnalysisDetail(id) {
  return request.get(`/api/analysis/${id}`)
}

// 删除表格
export function deleteAnalysis(id) {
  return request.delete(`/api/analysis/${id}`)
}
