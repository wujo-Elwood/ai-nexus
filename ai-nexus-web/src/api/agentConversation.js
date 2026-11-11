import request from '../utils/request'

// 创建会话（首条消息发出时调用）
export function createConversation(agentCode, title) {
  return request.post('/api/agent-conversations', { agentCode, title })
}

// 当前用户在某智能体下的会话列表
export function listConversations(agentCode) {
  return request.get('/api/agent-conversations', { params: { agentCode } })
}

// 加载会话详情与全部消息
export function getConversationDetail(id) {
  return request.get(`/api/agent-conversations/${id}`)
}

// 追加一条消息
export function appendConversationMessage(id, payload) {
  return request.post(`/api/agent-conversations/${id}/messages`, payload)
}

// 删除会话及其全部消息
export function deleteConversation(id) {
  return request.delete(`/api/agent-conversations/${id}`)
}
