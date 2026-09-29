<template>
  <div
    class="agent-tool-page"
    :class="{ 'vision-split': agentCode === 'vision-agent' }"
    :style="agentCode === 'vision-agent' ? { gridTemplateColumns: leftWidth + 'px 1fr' } : null"
    @dragover="onDragOver"
    @dragleave="onDragLeave"
    @drop="onDrop"
  >
    <div v-if="dragOver" class="drop-overlay">松开鼠标上传图片</div>
    <div class="page-header">
      <div>
        <div class="header-back">
          <el-button @click="backToAgentList">
            <el-icon><ArrowLeft /></el-icon>
            返回智能体管理
          </el-button>
        </div>
        <h2 class="page-title">{{ agentName }}</h2>
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
        {{ agentCode === 'currency-converter'
          ? '试试输入："1000 人民币能换多少美元和日元？"'
          : agentCode === 'vision-agent'
            ? '把图片拖到左侧面板或点击上传，我来分析内容'
            : '试试输入："北京今天天气怎么样？" 或 "对比北京和上海的气温"' }}
      </div>
      <div v-for="(msg, index) in messages" :key="index" class="msg-row" :class="msg.role">
        <div class="msg-bubble">
          <template v-if="msg.role === 'user'">
            {{ msg.content }}
            <div v-if="msg.images && msg.images.length" class="msg-images">
              <img v-for="(src, index) in msg.images" :key="index" :src="src" alt="" />
            </div>
          </template>
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

      <!-- 视觉智能体：左侧大块上传区（点击/拖拽均可），位于左栏顶部 -->
      <div
        v-if="agentCode === 'vision-agent'"
        class="image-panel"
        :class="{ 'has-images': pendingImages.length }"
        title="点击选择图片，也可以把图片拖拽到页面任意位置"
        @click="triggerPickImage"
      >
        <template v-if="pendingImages.length">
          <div v-for="(img, index) in pendingImages" :key="index" class="image-thumb">
            <img :src="img.dataUrl" alt="" />
            <span class="thumb-remove" @click.stop="removeImage(index)">×</span>
          </div>
        </template>
        <template v-else>
          <el-icon><Picture /></el-icon>
          <span>点击选择图片<br />或把图片拖拽到这里</span>
        </template>
      </div>
      <div v-if="pendingImages.length && agentCode !== 'vision-agent'" class="image-previews">
        <div v-for="(img, index) in pendingImages" :key="index" class="image-thumb">
          <img :src="img.dataUrl" alt="" />
          <span class="thumb-remove" @click="removeImage(index)">×</span>
        </div>
      </div>
      <div class="input-bar" :style="{ height: inputHeight + 'px' }">
        <div class="resize-handle" :title="agentCode === 'vision-agent' ? '拖动调整左栏宽度' : '拖动调整输入框高度'" @mousedown="startResize"><span></span></div>
      <div class="input-row">
        <el-input
          v-model="input"
          type="textarea"
          resize="none"
          placeholder="输入问题，自动调用工具查询（Enter 发送，Shift+Enter 换行）"
          :disabled="streaming"
          @keydown.enter.exact="onEnterKey"
        />
        <div class="input-actions">
          <el-button v-if="!streaming" type="primary" :disabled="!input.trim() && !pendingImages.length" @click="send">发送</el-button>
          <el-button v-else type="warning" @click="stop">停止</el-button>
        </div>
      </div>
      <input
        ref="fileInputRef"
        type="file"
        accept="image/*"
        multiple
        style="display: none"
        @change="onPickImage"
      />
    </div>
  </div>
</template>

<script setup>
import { nextTick, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { ArrowLeft, Picture } from '@element-plus/icons-vue'
import MarkdownIt from 'markdown-it'
import { listAgentTools, streamAgentChat } from '../../api/agentTool'

const md = new MarkdownIt({ breaks: true, linkify: true })

function renderAnswer(content) {
  return md.render(content || '')
}

const route = useRoute()
const router = useRouter()

// 当前进入的智能体：决定可用工具子集与页面标题
const AGENT_NAMES = {
  'general-tool': '天气查询智能体',
  'currency-converter': '货币换算智能体',
  'vision-agent': '视觉智能体'
}
const agentCode = String(route.query.agent || 'general-tool')
const agentName = AGENT_NAMES[agentCode] || '天气查询智能体'
const tools = ref([])
const messages = ref([])
const input = ref('')
const streaming = ref(false)
const chatWindowRef = ref(null)
const inputHeight = ref(110)
const fileInputRef = ref(null)
const pendingImages = ref([])
const dragOver = ref(false)
let abortController = null
let typewriterTimer = null

function triggerPickImage() {
  if (fileInputRef.value) fileInputRef.value.click()
}

function onPickImage(event) {
  addImageFiles([...(event.target.files || [])])
  event.target.value = ''
}

// 统一处理选择/拖拽进来的图片文件；超大图自动压缩到模型友好的尺寸
function addImageFiles(files) {
  for (const file of files) {
    if (!file.type.startsWith('image/')) continue
    if (file.size > 50 * 1024 * 1024) {
      ElMessage.error('图片不能超过 50MB')
      continue
    }
    compressImage(file)
      .then((dataUrl) => pendingImages.value.push({ name: file.name, dataUrl }))
      .catch(() => ElMessage.error('图片读取失败，请使用 JPG/PNG/WebP 格式'))
  }
}

/**
 * 压缩图片：超过 1.5MB 或长边超过 2048px 的图，等比缩放后转 JPEG（质量 0.85）；
 * 视觉模型对超高分辨率并不受益，压缩可把几十 MB 的原图降到几百 KB
 */
function compressImage(file) {
  return new Promise((resolve, reject) => {
    const reader = new FileReader()
    reader.onload = () => {
      const original = reader.result
      const img = new Image()
      img.onload = () => {
        const maxEdge = Math.max(img.width, img.height)
        if (file.size <= 1.5 * 1024 * 1024 && maxEdge <= 2048) {
          resolve(original)
          return
        }
        const scale = Math.min(1, 2048 / maxEdge)
        const canvas = document.createElement('canvas')
        canvas.width = Math.max(1, Math.round(img.width * scale))
        canvas.height = Math.max(1, Math.round(img.height * scale))
        const ctx = canvas.getContext('2d')
        ctx.fillStyle = '#ffffff'
        ctx.fillRect(0, 0, canvas.width, canvas.height)
        ctx.drawImage(img, 0, 0, canvas.width, canvas.height)
        resolve(canvas.toDataURL('image/jpeg', 0.85))
      }
      img.onerror = () => reject(new Error('decode failed'))
      img.src = original
    }
    reader.onerror = () => reject(new Error('read failed'))
    reader.readAsDataURL(file)
  })
}

// 拖拽上传：仅视觉智能体启用
function onDragOver(event) {
  if (agentCode !== 'vision-agent') return
  event.preventDefault()
  dragOver.value = true
}

function onDragLeave(event) {
  if (agentCode !== 'vision-agent') return
  if (!event.relatedTarget) dragOver.value = false
}

function onDrop(event) {
  if (agentCode !== 'vision-agent') return
  dragOver.value = false
  addImageFiles([...(event.dataTransfer?.files || [])])
}

function removeImage(index) {
  pendingImages.value.splice(index, 1)
}

// 拖动调整尺寸：视觉智能体（左右布局）调左栏宽度，其他智能体调输入框高度
const leftWidth = ref(380)

function startResize(event) {
  event.preventDefault()
  if (agentCode === 'vision-agent') {
    const startX = event.clientX
    const startWidth = leftWidth.value
    const onMoveWidth = (e) => {
      leftWidth.value = Math.min(640, Math.max(300, startWidth - (e.clientX - startX)))
    }
    const onUpWidth = () => {
      window.removeEventListener('mousemove', onMoveWidth)
      window.removeEventListener('mouseup', onUpWidth)
    }
    window.addEventListener('mousemove', onMoveWidth)
    window.addEventListener('mouseup', onUpWidth)
    return
  }
  const startY = event.clientY
  const startHeight = inputHeight.value
  const onMoveHeight = (e) => {
    inputHeight.value = Math.min(420, Math.max(72, startHeight + (startY - e.clientY)))
  }
  const onUpHeight = () => {
    window.removeEventListener('mousemove', onMoveHeight)
    window.removeEventListener('mouseup', onUpHeight)
  }
  window.addEventListener('mousemove', onMoveHeight)
  window.addEventListener('mouseup', onUpHeight)
}

// 中文输入法选词时的回车不触发发送
function onEnterKey(event) {
  if (event.isComposing || event.keyCode === 229) return
  event.preventDefault()
  send()
}

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
    const res = await listAgentTools(agentCode)
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
      // 以本次会话实际下发的工具为准，同步页签展示
      if (Array.isArray(data.tools) && data.tools.length) {
        tools.value = data.tools
      }
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
  if ((!text && !pendingImages.value.length) || streaming.value) return
  input.value = ''
  streaming.value = true
  const images = pendingImages.value.map((item) => item.dataUrl)
  const messageText = text || (images.length ? '请分析这张图片' : '')
  messages.value.push({ role: 'user', content: messageText, images: images.length ? images : undefined })
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
      { message: messageText, history, agentCode, images: images.length ? images : undefined },
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
    pendingImages.value = []
    scrollBottom()
  }
}

function stop() {
  if (abortController) abortController.abort()
  flushTypewriter()
}
</script>

<style scoped>
.drop-overlay {
  position: absolute;
  inset: 0;
  z-index: 20;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 16px;
  font-weight: 600;
  color: var(--accent-color);
  background: rgba(11, 12, 14, 0.72);
  border: 2px dashed var(--accent-color);
  border-radius: 12px;
  pointer-events: none;
}

.agent-tool-page {
  position: relative;
  display: flex;
  flex-direction: column;
  height: 100%;
  padding: 20px 24px;
  box-sizing: border-box;
}

/* 视觉智能体左右布局：左栏=上传图片+描述，右栏=执行结果 */
.agent-tool-page.vision-split {
  display: grid;
  grid-template-rows: auto minmax(0, auto) minmax(0, 1fr);
  grid-template-areas:
    "header chat"
    "panel chat"
    "bar chat";
  column-gap: 16px;
}

.vision-split .page-header {
  grid-area: header;
}

.vision-split .image-panel {
  grid-area: panel;
  width: 100%;
  height: 100%;
  min-height: 280px;
  align-content: center;
}

.vision-split .image-panel.has-images {
  align-content: flex-start;
}

.vision-split .image-thumb {
  width: 72px;
  height: 72px;
}

.vision-split .input-bar {
  grid-area: bar;
  height: auto !important;
}

.vision-split .input-row {
  flex: 1;
  min-height: 140px;
  align-items: stretch;
}

.vision-split .chat-window {
  grid-area: chat;
}

.vision-split .resize-handle {
  cursor: ew-resize;
}

.vision-split .resize-handle span {
  width: 4px;
  height: 46px;
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

.image-panel {
  width: 168px;
  flex-shrink: 0;
  align-self: stretch;
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  align-items: center;
  align-content: center;
  justify-content: center;
  padding: 8px;
  border: 1px dashed #3a3b40;
  border-radius: 10px;
  background: rgba(255, 255, 255, 0.02);
  cursor: pointer;
  color: var(--text-secondary);
  font-size: 12px;
  line-height: 1.5;
  text-align: center;
  transition: border-color 0.2s, color 0.2s;
}

.image-panel:hover {
  border-color: var(--accent-color);
  color: var(--accent-color);
}

.image-panel .el-icon {
  font-size: 24px;
}

.image-panel.has-images {
  align-content: flex-start;
  justify-content: flex-start;
}

.image-previews {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-bottom: 8px;
}

.image-thumb {
  position: relative;
  width: 56px;
  height: 56px;
  border: 1px solid #2a2b30;
  border-radius: 8px;
  overflow: hidden;
}

.image-thumb img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.thumb-remove {
  position: absolute;
  top: 0;
  right: 0;
  width: 16px;
  height: 16px;
  line-height: 14px;
  text-align: center;
  font-size: 12px;
  color: #fff;
  background: rgba(0, 0, 0, 0.6);
  cursor: pointer;
  border-bottom-left-radius: 6px;
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
  position: relative;
  display: flex;
  flex-direction: column;
  margin-top: 18px;
  min-height: 72px;
}

.resize-handle {
  position: absolute;
  top: -9px;
  left: 0;
  right: 0;
  height: 12px;
  display: flex;
  align-items: center;
  justify-content: center;
  cursor: ns-resize;
  z-index: 5;
}

.resize-handle span {
  width: 46px;
  height: 4px;
  border-radius: 999px;
  background: #2a2b30;
  transition: background 0.2s;
}

.resize-handle:hover span {
  background: var(--accent-color);
}

.input-row {
  flex: 1;
  min-height: 0;
  display: flex;
  align-items: flex-end;
  gap: 10px;
}

.input-row :deep(.el-textarea),
.input-row :deep(.el-input) {
  flex: 1;
  height: 100%;
}

.input-row :deep(.el-textarea__inner) {
  height: 100%;
  min-height: 56px;
  padding: 10px 12px;
  background: #15161a;
  border-color: #232428;
  color: var(--text-primary);
  resize: none;
  line-height: 1.5;
}

.input-row :deep(.el-textarea__inner:focus) {
  border-color: rgba(229, 160, 68, 0.55);
}

.input-actions {
  padding-bottom: 2px;
}
</style>
