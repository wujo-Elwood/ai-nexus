import request from '../utils/request'

export function sendMessage(data) {
  return request.post('/api/chat/send', data)
}

export function getChatHistory(sessionId) {
  return request.get(`/api/chat/history/${sessionId}`)
}

export function deleteSession(sessionId) {
  return request.delete(`/api/chat/session/${sessionId}`)
}

/** 召回测试：输入问题和知识库ID，返回召回的文本块 */
export function recallTest(message, kbId) {
  return request.post('/api/chat/recall-test', { message, kbId })
}

// 按临时参数执行检索诊断
export function diagnoseRecall(data) {
  return request.post('/api/chat/diagnose', data)
}

/** 提交答案质量反馈 */
export function submitFeedback(messageId, helpful) {
  return request.post('/api/chat/feedback', { messageId, helpful })
}
