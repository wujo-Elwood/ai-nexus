import { reactive } from 'vue'

// 「返回智能体」右下角入口的会话级记录：
// - 只活在当前页面生命周期内，刷新/重开页面即清空；
// - 进入智能体时写入并刷新时间戳，10 分钟未再次进入自动过期；
// - 与 localStorage 的 agent-recent 互不相干：后者继续驱动侧边栏动态菜单
//   和 /agent-tools 免参数进入时的回退导航（这两个场景需要跨刷新持久）。
const TTL_MS = 10 * 60 * 1000

const state = reactive({
  agentCode: '',
  agentName: '',
  fullPath: '',
  enteredAt: 0
})

// 进入（或从缓存恢复）智能体时调用：写入本次会话的返回入口
export function rememberAgentVisit(agentCode, agentName, fullPath) {
  state.agentCode = agentCode || ''
  state.agentName = agentName || ''
  state.fullPath = fullPath || ''
  state.enteredAt = Date.now()
}

// 读取有效记录：从未进入过、或距上次进入超过 10 分钟时返回 null
export function readAgentVisit() {
  if (!state.agentCode) {
    return null
  }
  if (Date.now() - state.enteredAt > TTL_MS) {
    return null
  }
  return state
}
