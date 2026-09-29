import request from '../utils/request'

// 获取智能体列表
export function getAgentList() {
  return request.get('/api/agents')
}
