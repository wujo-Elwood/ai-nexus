import request from '../utils/request'

// 获取智能体列表
export function getAgentList() {
  return request.get('/api/agents')
}

// 运行知识库质检智能体
export function runKnowledgeQuality(data) {
  return request.post('/api/agents/knowledge-quality/run', data)
}

// 获取智能体运行记录
export function getAgentRuns(params) {
  return request.get('/api/agents/runs', { params })
}

// 获取智能体运行详情
export function getAgentRunDetail(id) {
  return request.get(`/api/agents/runs/${id}`)
}

// 删除智能体运行记录
export function deleteAgentRun(id) {
  return request.delete(`/api/agents/runs/${id}`)
}
