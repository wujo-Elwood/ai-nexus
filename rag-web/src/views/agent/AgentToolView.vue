<template>
  <div class="agent-tool-page">
    <div class="page-header">
      <div>
        <div class="header-back">
          <el-button @click="backToAgentList">
            <el-icon><ArrowLeft /></el-icon>
            返回智能体管理
          </el-button>
        </div>
        <h2 class="page-title">工具智能体</h2>
        <p class="page-desc">模型自主调用工具完成任务，每一步调用过程实时可见</p>
      </div>
      <div class="tool-chips">
        <span v-for="tool in tools" :key="tool.name" class="tool-chip" :title="tool.description">
          {{ tool.name }}
        </span>
      </div>
    </div>

    <div ref="chatWindowRef" class="chat-window">
      <div v-if="messages.length === 0" class="empty-hint">
        试试输入："北京今天天气怎么样？" 或 "对比北京和上海的气温"
      </div>
      <div v-for="(msg, index) in messages" :key="index" class="msg-row" :class="msg.role">
        <div class="msg-bubble">
          <template v-if="msg.role === 'user'">{{ msg.content }}</template>
          <template v-else>
            <div v-if="msg.statusText" class="stream-status">{{ msg.statusText }}</div>
            <div v-if="msg.steps.length" class="steps-panel">
              <div class="steps-title" @click="msg.stepsOpen = !msg.stepsOpen">
                {{ msg.stepsOpen ? '▾' : '▸' }} 执行过程（{{ msg.steps.length }} 步{{ costText(msg) }}）
              </div>
              <div v-show="msg.stepsOpen" class="step-list">
                <div v-for="step in msg.steps" :key="step.stepNo" class="step-item" :class="step.status.toLowerCase()">
                  <div class="step-head">
                    <span class="step-index">{{ step.stepNo }}</span>
                    <span class="step-tool">{{ step.tool }}</span>
                    <code class="step-args">{{ step.arguments }}</code>
                    <span class="step-status">
                      {{ step.status === 'RUNNING' ? '执行中…' : (step.status === 'FAILED' ? '失败' : step.costMs + 'ms') }}
                    </span>
                  </div>
                  <pre v-if="step.result" class="step-result">{{ step.result }}</pre>
                </div>
              </div>
            </div>
            <div class="answer-text">
              <span v-html="renderAnswer(msg.content)"></span><span v-if="msg.pending" class="cursor">▍</span>
            </div>
            <div v-if="msg.errorMsg" class="error-text">{{ msg.errorMsg }}</div>
          </template>
        </div>
      </div>
    </div>

    <div class="input-bar">
      <el-input
        v-model="input"
        placeholder="输入问题，需要实时数据时会自动调用工具"
        :disabled="streaming"
        @keyup.enter="send"
      />
      <el-button v-if="!streaming" type="primary" :disabled="!input.trim()" @click="send">发送</el-button>
      <el-button v-else type="warning" @click="stop">停止</el-button>
    </div>
  </div>
</template>

<script setup>
import { nextTick, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { ArrowLeft } from '@element-plus/icons-vue'
import MarkdownIt from 'markdown-it'
import { listAgentTools, streamAgentChat } from '../../api/agentTool'

const md = new MarkdownIt({ breaks: true, linkify: true })

function renderAnswer(content) {
  return md.render(content || '')
}

const router = useRouter()
const tools = ref([])
const messages = ref([])
const input = ref('')
const streaming = ref(false)
const chatWindowRef = ref(null)
let abortController = null
let typewriterTimer = null

// 打字机缓冲：增量先进队列，按固定节奏匀速上屏，保证肉眼可见的流式效果
function queueDelta(msg, delta) {
  msg.deltaQueue += delta
  if (!typewriterTimer) {
    typewriterTimer = setInterval(pumpTypewriter, 24)
  }
}

function pumpTypewriter() {
  let busy = false
  for (const msg of messages.value) {
    if (msg.deltaQueue) {
      busy = true
      const take = Math.max(2, Math.ceil(msg.deltaQueue.length / 12))
      msg.content += msg.deltaQueue.slice(0, take)
      msg.deltaQueue = msg.deltaQueue.slice(take)
    }
  }
  if (busy) {
    scrollBottom()
  } else {
    clearInterval(typewriterTimer)
    typewriterTimer = null
  }
}

function flushTypewriter() {
  if (typewriterTimer) {
    clearInterval(typewriterTimer)
    typewriterTimer = null
  }
  for (const msg of messages.value) {
    if (msg.deltaQueue) {
      msg.content += msg.deltaQueue
      msg.deltaQueue = ''
    }
  }
}

onMounted(async () => {
  try {
    const res = await listAgentTools()
    tools.value = Array.isArray(res) ? res : res.data || []
  } catch (e) {
    // 工具清单加载失败不阻塞聊天
  }
})

onBeforeUnmount(() => {
  if (abortController) abortController.abort()
  flushTypewriter()
})

// 返回智能体卡片总览
function backToAgentList() {
  router.push('/agents')
}

function scrollBottom() {
  nextTick(() => {
    const el = chatWindowRef.value
    if (el) el.scrollTop = el.scrollHeight
  })
}

function costText(msg) {
  const total = msg.steps.reduce((sum, step) => sum + (step.costMs || 0), 0)
  return total > 0 ? `，共 ${total}ms` : ''
}

function handleEvent(msg, event, data) {
  switch (event) {
    case 'open':
      msg.runId = data.runId
      break
    case 'status':
      msg.statusText = data.message || ''
      break
    case 'answer_delta':
      queueDelta(msg, data.delta || '')
      break
    case 'answer_reset':
      msg.deltaQueue = ''
      msg.content = ''
      break
    case 'step_start':
      msg.steps.push({
        stepNo: data.stepNo,
        tool: data.tool,
        arguments: data.arguments,
        result: '',
        status: 'RUNNING',
        costMs: 0
      })
      msg.stepsOpen = true
      break
    case 'step_result': {
      const step = msg.steps.find((item) => item.stepNo === data.stepNo)
      if (step) {
        step.result = data.result
        step.costMs = data.costMs
        step.status = data.status
      }
      break
    }
    case 'answer':
      flushTypewriter()
      msg.content = data.content
      break
    case 'done':
      msg.pending = false
      streaming.value = false
      break
    case 'error':
      msg.errorMsg = data.message
      msg.pending = false
      streaming.value = false
      break
    default:
      break
  }
  scrollBottom()
}

async function send() {
  const text = input.value.trim()
  if (!text || streaming.value) return
  input.value = ''
  streaming.value = true
  messages.value.push({ role: 'user', content: text })
  // 必须用 reactive 包裹：事件回调持有的是这个引用，直接改原始对象不会触发视图更新
  const assistant = reactive({ role: 'assistant', content: '', deltaQueue: '', steps: [], stepsOpen: true, pending: true, statusText: '' })
  messages.value.push(assistant)
  scrollBottom()

  const history = messages.value
    .slice(0, -2)
    .filter((m) => m.content)
    .map((m) => ({ role: m.role, content: m.content }))

  abortController = new AbortController()
  try {
    await streamAgentChat(
      { message: text, history },
      { signal: abortController.signal, onEvent: (event, data) => handleEvent(assistant, event, data) }
    )
  } catch (e) {
    if (e.name !== 'AbortError') {
      assistant.errorMsg = '连接中断，请重试'
      ElMessage.error('连接中断')
    }
  } finally {
    assistant.pending = false
    streaming.value = false
    abortController = null
    scrollBottom()
  }
}

function stop() {
  if (abortController) abortController.abort()
  flushTypewriter()
}
</script>

<style scoped>
.agent-tool-page {
  display: flex;
  flex-direction: column;
  height: 100%;
  padding: 20px 24px;
  box-sizing: border-box;
}

.page-header {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 14px;
}

.header-back {
  margin-bottom: 10px;
}

.page-title {
  margin: 0 0 4px;
  font-size: 20px;
  color: var(--text-primary);
}

.page-desc {
  margin: 0;
  font-size: 12px;
  color: var(--text-secondary);
}

.tool-chips {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  justify-content: flex-end;
}

.tool-chip {
  padding: 3px 10px;
  font-size: 12px;
  color: var(--text-secondary);
  border: 1px solid #2a2b30;
  border-radius: 999px;
  cursor: default;
}

.chat-window {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  padding: 16px;
  border: 1px solid #232428;
  border-radius: 12px;
  background: rgba(255, 255, 255, 0.02);
}

.empty-hint {
  margin-top: 80px;
  text-align: center;
  font-size: 13px;
  color: var(--text-secondary);
}

.msg-row {
  display: flex;
  margin-bottom: 14px;
}

.msg-row.user {
  justify-content: flex-end;
}

.msg-bubble {
  max-width: 82%;
  padding: 10px 14px;
  border-radius: 10px;
  font-size: 14px;
  line-height: 1.6;
  white-space: pre-wrap;
}

.msg-row.user .msg-bubble {
  background: var(--accent-color);
  color: #1a1a1a;
}

.msg-row.assistant .msg-bubble {
  background: #15161a;
  border: 1px solid #232428;
  color: var(--text-primary);
}

.stream-status {
  margin-bottom: 8px;
  font-size: 12px;
  color: var(--accent-color);
}

.steps-panel {
  margin-bottom: 10px;
  border: 1px solid #2a2b30;
  border-radius: 8px;
  overflow: hidden;
}

.steps-title {
  padding: 6px 10px;
  font-size: 12px;
  color: var(--text-secondary);
  cursor: pointer;
  user-select: none;
  background: rgba(255, 255, 255, 0.03);
}

.step-item {
  padding: 6px 10px;
  border-top: 1px solid #232428;
  border-left: 2px solid #4ade80;
}

.step-item.running {
  border-left-color: var(--accent-color);
}

.step-item.failed {
  border-left-color: #f87171;
}

.step-head {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 12px;
}

.step-index {
  min-width: 18px;
  height: 18px;
  line-height: 18px;
  text-align: center;
  border-radius: 50%;
  background: #2a2b30;
  color: var(--text-primary);
  font-size: 11px;
}

.step-tool {
  color: var(--text-primary);
  font-weight: 600;
}

.step-args {
  flex: 1;
  overflow: hidden;
  color: var(--text-secondary);
  text-overflow: ellipsis;
  white-space: nowrap;
}

.step-status {
  color: var(--text-secondary);
}

.step-result {
  margin: 6px 0 2px 26px;
  padding: 6px 8px;
  font-size: 12px;
  line-height: 1.5;
  color: var(--text-secondary);
  background: rgba(255, 255, 255, 0.02);
  border-radius: 6px;
  white-space: pre-wrap;
  word-break: break-all;
}

.answer-text {
  color: var(--text-primary);
}

.cursor {
  animation: blink 1s step-end infinite;
  color: var(--accent-color);
}

@keyframes blink {
  50% {
    opacity: 0;
  }
}

.error-text {
  margin-top: 6px;
  font-size: 12px;
  color: #f87171;
}

.input-bar {
  display: flex;
  gap: 10px;
  margin-top: 14px;
}
</style>
