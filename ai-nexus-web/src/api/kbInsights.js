import request from '../utils/request'

// 查询知识库处理和检索策略
export function getKbStrategy(kbId) {
  return request.get(`/api/kb/${kbId}/strategy`)
}

// 保存知识库处理和检索策略
export function updateKbStrategy(kbId, data) {
  return request.put(`/api/kb/${kbId}/strategy`, data)
}

// 查询知识库统计概览
export function getKbStats(kbId) {
  return request.get(`/api/kb/${kbId}/stats`)
}

// 查询知识库已保存摘要
export function getKbSummary(kbId) {
  return request.get(`/api/kb/${kbId}/summary`)
}

// 生成并保存知识库摘要
export function generateKbSummary(kbId) {
  return request.post(`/api/kb/${kbId}/summary`)
}

// 查询知识库口径冲突列表，status 为空返回全部
export function getKbConflicts(kbId, status) {
  return request.get(`/api/kb/${kbId}/conflicts`, { params: status ? { status } : {} })
}

// 触发一次口径冲突检测（全量替换旧结果）
export function detectKbConflicts(kbId) {
  return request.post(`/api/kb/${kbId}/conflicts/detect`)
}

// 确认或忽略一条冲突
export function updateKbConflict(kbId, conflictId, status) {
  return request.put(`/api/kb/${kbId}/conflicts/${conflictId}`, { status })
}
