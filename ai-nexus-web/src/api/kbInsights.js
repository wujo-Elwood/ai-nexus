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
