import request from '../utils/request'

// 获取当前注册的工具清单
export function listAgentTools() {
  return request.get('/api/agent-tools/tools')
}

// 工具智能体流式对话：通过 onEvent(event, data) 回调每一步
// SSE 事件：open / step_start / step_result / answer / done / error
export async function streamAgentChat(data, { onEvent, signal } = {}) {
  const token = localStorage.getItem('token')
  const response = await fetch('/api/agent-tools/chat/stream', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${token}` },
    body: JSON.stringify(data),
    signal
  })
  if (!response.ok || !response.body) {
    throw new Error(`请求失败（${response.status}）`)
  }
  const reader = response.body.getReader()
  const decoder = new TextDecoder('utf-8')
  let buffer = ''
  while (true) {
    const { done, value } = await reader.read()
    if (done) break
    buffer += decoder.decode(value, { stream: true })
    let idx
    while ((idx = buffer.indexOf('\n\n')) >= 0) {
      const block = buffer.slice(0, idx)
      buffer = buffer.slice(idx + 2)
      let event = 'message'
      const dataLines = []
      for (const line of block.split('\n')) {
        if (line.startsWith('event:')) {
          event = line.slice(6).trim()
        } else if (line.startsWith('data:')) {
          dataLines.push(line.slice(5).trim())
        }
      }
      if (!dataLines.length) continue
      const raw = dataLines.join('\n')
      let payload = raw
      try {
        payload = JSON.parse(raw)
      } catch (e) {
        // 非 JSON 数据按原文透传
      }
      if (onEvent) onEvent(event, payload)
    }
  }
}
