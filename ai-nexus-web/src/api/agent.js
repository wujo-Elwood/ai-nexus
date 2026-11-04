import request from '../utils/request'

// 获取智能体列表
export function getAgentList() {
  return request.get('/api/agents')
}

// 管理列表：全部智能体（含停用）
export function getAgentManageList() {
  return request.get('/api/agents/manage')
}

// 新建自定义智能体
export function createAgent(data) {
  return request.post('/api/agents/manage', data)
}

// 编辑智能体基础信息
export function updateAgent(code, data) {
  return request.put(`/api/agents/manage/${code}`, data)
}

// 启用或停用智能体
export function updateAgentEnabled(code, enabled) {
  return request.put(`/api/agents/manage/${code}/enabled`, { enabled })
}

// 删除自定义智能体
export function deleteAgent(code) {
  return request.delete(`/api/agents/manage/${code}`)
}

// ================ 自定义智能体（按创建人隔离，登录即可用） ================

// 当前用户可绑定的技能清单（启用中的技能）
export function listAvailableSkills() {
  return request.get('/api/agents/available-skills')
}

// 自定义智能体详情（编排对话框回显）
export function getCustomAgent(code) {
  return request.get(`/api/agents/custom/${code}`)
}

// 创建自定义智能体
export function createCustomAgent(data) {
  return request.post('/api/agents/custom', data)
}

// 编辑自定义智能体
export function updateCustomAgent(code, data) {
  return request.put(`/api/agents/custom/${code}`, data)
}

// 删除自定义智能体
export function deleteCustomAgent(code) {
  return request.delete(`/api/agents/custom/${code}`)
}
