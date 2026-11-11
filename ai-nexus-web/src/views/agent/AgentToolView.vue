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
        <template v-if="agentCode === 'image-agent'">
          <span class="tool-chip" :class="{ 'image-model-warn': !imageProvider }" :title="imageModelTip">
            {{ imageProvider ? '生图模型：' + imageProviderLabel : '未激活生图供应商，将回退用对话模型' }}
          </span>
          <span v-if="chatModelLabel" class="tool-chip" title="对话模型：负责理解需求、整理提示词和决策，推理强度对它生效">
            对话模型：{{ chatModelLabel }}
          </span>
        </template>
        <span v-for="tool in tools" :key="tool.name" class="tool-chip" :title="tool.description">
          {{ tool.name }}
        </span>
      </div>
      <div class="history-bar">
        <el-select
          v-model="selectedHistoryId"
          class="history-select"
          placeholder="历史对话"
          size="small"
          filterable
          clearable
          @change="loadHistoryConversation"
        >
          <el-option
            v-for="item in historyList"
            :key="item.id"
            :label="item.title"
            :value="item.id"
          />
        </el-select>
        <el-button size="small" @click="startNewConversation">新对话</el-button>
        <el-button
          v-if="conversationId"
          size="small"
          type="danger"
          plain
          @click="deleteCurrentConversation"
        >删除当前</el-button>
      </div>
    </div>

    <div ref="chatWindowRef" class="chat-window">
      <div v-if="messages.length === 0" class="empty-hint">
        {{ agentCode === 'currency-converter'
          ? '试试输入："1000 人民币能换多少美元和日元？"'
          : agentCode === 'vision-agent'
            ? '把图片拖到左侧面板或点击上传，我来分析内容'
            : agentCode === 'data-agent'
              ? '试试输入："系统里有多少个用户和知识库？"'
              : agentCode === 'image-agent'
                ? '试试输入："画一张赛博朋克风格的未来城市夜景"'
                : agentCode === 'general-tool'
                  ? '试试输入："北京今天天气怎么样？" 或 "对比北京和上海的气温"'
                  : '描述你的问题，我会自动调用绑定的技能完成' }}
      </div>
      <div v-for="(msg, index) in messages" :key="index" class="msg-row" :class="msg.role">
        <div class="log-tag">LOG <b>//</b> {{ msg.role === 'user' ? 'INPUT' : 'OUTPUT' }}</div>
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
                {{ msg.stepsOpen ? '▾' : '▸' }} 执行过程 · {{ msg.steps.length }} 步{{ costText(msg) }}{{ failText(msg) }}
              </div>
              <div v-show="msg.stepsOpen" class="step-timeline">
                <div v-for="(step, idx) in msg.steps" :key="step.stepNo" class="step-row" :class="step.status.toLowerCase()">
                  <div class="step-rail">
                    <span class="step-node">{{ step.status === 'FAILED' ? '!' : step.stepNo }}</span>
                    <span v-if="idx < msg.steps.length - 1" class="step-line"></span>
                  </div>
                  <div class="step-body">
                    <div class="step-head" @click="toggleStepDetail(step)">
                      <span class="step-tool">{{ step.tool }}</span>
                      <span class="step-status">{{ statusText(step) }}</span>
                    </div>
                    <code v-if="argsPreview(step)" class="step-args-preview" @click="toggleStepDetail(step)">{{ argsPreview(step) }}</code>
                    <div v-if="step.detailOpen" class="step-detail">
                      <div v-if="step.arguments" class="detail-block">
                        <span class="detail-label">参数</span>
                        <pre class="detail-pre">{{ prettyArgs(step) }}</pre>
                      </div>
                      <div v-if="step.result" class="detail-block">
                        <span class="detail-label">结果</span>
                        <pre class="detail-pre">{{ step.result }}</pre>
                      </div>
                      <div v-if="step.errorMessage" class="detail-block">
                        <span class="detail-label">失败原因</span>
                        <pre class="detail-pre detail-err">{{ step.errorMessage }}</pre>
                      </div>
                    </div>
                  </div>
                </div>
              </div>
            </div>
            <div class="answer-text">
              <span v-html="renderAnswer(msg.content)"></span><el-icon v-if="msg.pending" class="loading-spinner"><Loading /></el-icon>
            </div>
            <div v-if="(!msg.pending && msg.content) || msg._failed" class="answer-actions">
              <template v-if="ttsSupported && msg.content && !msg.pending">
                <el-button
                  size="small"
                  text
                  class="speak-btn"
                  :class="{ 'speak-active': speakingMessage === msg }"
                  :title="speakingMessage === msg ? '停止朗读' : '朗读回答'"
                  @click="toggleSpeak(msg)"
                >
                  <svg v-if="speakingMessage !== msg" viewBox="0 0 24 24" width="15" height="15" fill="currentColor" aria-hidden="true">
                    <path d="M3 9v6h4l5 5V4L7 9H3zm13.5 3c0-1.77-1.02-3.29-2.5-4.03v8.05c1.48-.73 2.5-2.25 2.5-4.02zM14 3.23v2.06c2.89.86 5 3.54 5 6.71s-2.11 5.85-5 6.71v2.06c4.01-.91 7-4.49 7-8.77s-2.99-7.86-7-8.77z" />
                  </svg>
                  <svg v-else viewBox="0 0 24 24" width="15" height="15" fill="currentColor" aria-hidden="true">
                    <path d="M6 6h12v12H6z" />
                  </svg>
                </el-button>
              </template>
              <template v-if="(msg.serverId || msg.id) && !msg.pending">
                <el-button
                  size="small"
                  text
                  class="feedback-btn"
                  :class="{ 'feedback-active-good': msg._feedback === 1 }"
                  title="有帮助"
                  @click="handleAgentFeedback(msg, 1)"
                >
                  <svg viewBox="0 0 24 24" width="15" height="15" fill="currentColor" aria-hidden="true">
                    <path d="M1 21h4V9H1v12zm22-11c0-1.1-.9-2-2-2h-6.31l.95-4.57.03-.32c0-.41-.17-.79-.44-1.06L14.17 1 7.59 7.59C7.22 7.95 7 8.45 7 9v10c0 1.1.9 2 2 2h9c.83 0 1.54-.5 1.84-1.22l3.02-7.05c.09-.23.14-.47.14-.73v-2z" />
                  </svg>
                </el-button>
                <el-button
                  size="small"
                  text
                  class="feedback-btn"
                  :class="{ 'feedback-active-bad': msg._feedback === 0 }"
                  title="无帮助"
                  @click="handleAgentFeedback(msg, 0)"
                >
                  <svg viewBox="0 0 24 24" width="15" height="15" fill="currentColor" aria-hidden="true">
                    <path d="M15 3H6c-.83 0-1.54.5-1.84 1.22l-3.02 7.05c-.09.23-.14.47-.14.73v2c0 1.1.9 2 2 2h6.31l-.95 4.57-.03.32c0 .41.17.79.44 1.06L9.83 23l6.59-6.59c.36-.36.58-.86.58-1.41V5c0-1.1-.9-2-2-2zm4 0v12h4V3h-4z" />
                  </svg>
                </el-button>
              </template>
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
          <el-select
            v-if="agentCode !== 'image-agent'"
            v-model="reasoningEffort"
            class="effort-chip"
            title="推理强度：越高回答越深入，耗时越长"
            @change="saveReasoningEffort"
          >
            <el-option v-for="opt in reasoningOptions" :key="opt.value" :label="'推理 ' + opt.label" :value="opt.value" />
          </el-select>
          <el-select
            v-if="agentCode === 'image-agent'"
            v-model="imageProviderId"
            class="model-chip"
            placeholder="选择生图模型"
            filterable
            title="选择生图模型：实际出图使用的模型，与对话模型相互独立"
            @change="handleSwitchImageProvider"
          >
            <el-option
              v-for="provider in providerList"
              :key="provider.id"
              :label="formatImageProviderLabel(provider)"
              :value="provider.id"
              :disabled="!provider.apiKey"
            />
          </el-select>
          <el-select
            v-else
            v-model="activeProviderId"
            class="model-chip"
            placeholder="选择模型"
            filterable
            @change="handleSwitchProvider"
          >
            <el-option
              v-for="provider in providerList"
              :key="provider.id"
              :label="formatProviderLabel(provider)"
              :value="provider.id"
              :disabled="!provider.apiKey"
            />
          </el-select>
          <el-button
            class="mic-button"
            :class="{ 'mic-active': dictating }"
            :title="sttSupported ? (dictating ? '停止聆听' : '语音输入') : '当前浏览器不支持语音识别（可用 Chrome / Edge）'"
            :disabled="!sttSupported || streaming"
            @click="toggleDictation"
          >
            <svg viewBox="0 0 24 24" width="16" height="16" fill="currentColor" aria-hidden="true">
              <path d="M12 14c1.66 0 3-1.34 3-3V5c0-1.66-1.34-3-3-3S9 3.34 9 5v6c0 1.66 1.34 3 3 3zm5.91-3c-.49 0-.9.36-.98.85C16.52 14.2 14.47 16 12 16s-4.52-1.8-4.93-4.15c-.08-.49-.49-.85-.98-.85-.61 0-1.09.54-1 1.14.49 3 2.89 5.35 5.91 5.78V20c0 .55.45 1 1 1s1-.45 1-1v-2.08c3.02-.43 5.42-2.78 5.91-5.78.1-.6-.39-1.14-1-1.14z" />
            </svg>
          </el-button>
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
// 组件名用于 keep-alive 缓存：切换菜单时保留对话与流式状态
defineOptions({ name: 'AgentToolView' })
import { computed, nextTick, onActivated, onBeforeUnmount, onDeactivated, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { ArrowLeft, Loading, Picture } from '@element-plus/icons-vue'
import MarkdownIt from 'markdown-it'
import { activateImageModelProvider, activateModelProvider, getModelProviderList } from '../../api/modelProvider'
import { listAgentTools, streamAgentChat } from '../../api/agentTool'
import { createConversation, listConversations, getConversationDetail, appendConversationMessage, deleteConversation } from '../../api/agentConversation'
import { submitFeedback } from '../../api/chat'
import { createDictation, isSTTSupported, isTTSSupported, speak, stopSpeaking, stripMarkdownForSpeech } from '../../utils/speech'
import { rememberAgentVisit } from '../../stores/agentRecent'

const md = new MarkdownIt({ breaks: true, linkify: true })

// ---------- 语音输入与朗读（与 AI 聊天页同一套工具） ----------
const sttSupported = isSTTSupported()
const ttsSupported = isTTSSupported()
const dictating = ref(false)
// 智能体消息没有稳定 id，直接用对象引用跟踪朗读目标
const speakingMessage = ref(null)
let dictation = null
let dictationBase = ''

function toggleDictation() {
  if (dictating.value) {
    dictation.stop()
    return
  }
  if (!dictation) {
    dictation = createDictation({
      onText: (text) => {
        input.value = (dictationBase + ' ' + text).trim()
      },
      onError: (message) => {
        ElMessage.warning(message)
      },
      onStop: () => {
        dictating.value = false
      }
    })
  }
  dictationBase = input.value ? input.value + ' ' : ''
  dictating.value = true
  dictation.start()
}

// 提交/取消智能体回答的反馈（再点一次已选项即取消）
async function handleAgentFeedback(msg, helpful) {
  const messageId = msg.serverId || msg.id
  if (!messageId) {
    return
  }
  try {
    const cancelling = msg._feedback === helpful
    await submitFeedback(messageId, cancelling ? null : helpful, 'AGENT')
    msg._feedback = cancelling ? null : helpful
    ElMessage.success(cancelling ? '已取消反馈' : (helpful === 1 ? '感谢反馈' : '感谢反馈，我们会改进'))
  } catch (error) {
    console.error(error)
    ElMessage.error('反馈提交失败')
  }
}

async function toggleSpeak(msg) {
  if (speakingMessage.value === msg) {
    stopSpeaking()
    speakingMessage.value = null
    return
  }
  stopSpeaking()
  speakingMessage.value = msg
  await speak(stripMarkdownForSpeech(msg.content))
  if (speakingMessage.value === msg) {
    speakingMessage.value = null
  }
}

function renderAnswer(content) {
  return md.render(content || '')
}

const route = useRoute()
const router = useRouter()

// 当前进入的智能体：决定可用工具子集与页面标题
const AGENT_NAMES = {
  'general-tool': '天气查询智能体',
  'currency-converter': '货币换算智能体',
  'vision-agent': '视觉智能体',
  'data-agent': '数据问答智能体',
  'image-agent': '生图智能体'
}

// ---------- 最近访问记忆 ----------
// agent-recent：最近进入的智能体（侧边栏入口不带参数时回退到这里）；
// agent-last-conversation:{code}：每个智能体最近一次会话（切走再回来自动接上）
const RECENT_AGENT_KEY = 'agent-recent'
const lastConversationKey = (code) => `agent-last-conversation:${code}`

function readRecentAgent() {
  try {
    const raw = localStorage.getItem(RECENT_AGENT_KEY)
    return raw ? JSON.parse(raw) : null
  } catch (error) {
    return null
  }
}

const recentAgent = readRecentAgent()
// URL 参数优先（智能体卡片入口），其次回退到最近访问的智能体，最后才是内置默认
const agentCode = String(route.query.agent || recentAgent?.agentCode || 'general-tool')
// 优先使用路由携带的名称（自定义智能体无内置映射），其次查内置映射和最近访问记录
const agentName = String(route.query.name || '')
  || AGENT_NAMES[agentCode]
  || (recentAgent && recentAgent.agentCode === agentCode ? recentAgent.agentName : '')
  || agentCode

function rememberRecentAgent() {
  try {
    // fullPath 是 keep-alive 的缓存键：悬浮按钮用它原样跳回，命中缓存瞬间恢复整个页面状态
    localStorage.setItem(RECENT_AGENT_KEY, JSON.stringify({ agentCode, agentName, fullPath: route.fullPath }))
  } catch (error) {
    // 隐私模式等存储不可用场景忽略，仅影响下次回退
  }
}

function rememberConversation(id) {
  if (!id) return
  try {
    localStorage.setItem(lastConversationKey(agentCode), String(id))
  } catch (error) {
    // 存储不可用仅影响自动恢复
  }
}

function forgetConversation() {
  try {
    localStorage.removeItem(lastConversationKey(agentCode))
  } catch (error) {
    // 同上
  }
}

function readLastConversationId() {
  try {
    return localStorage.getItem(lastConversationKey(agentCode))
  } catch (error) {
    return null
  }
}

rememberRecentAgent()

// 会话级「返回智能体」入口：首次进入与从 keep-alive 缓存恢复时都刷新内存记录，
// 使右下角链接的时间戳跟随"最近一次进入"重新计时（10 分钟未再进入自动消失）
onActivated(() => {
  rememberAgentVisit(agentCode, agentName, route.fullPath)
})

// 生图智能体：右下角选择器直接选生图模型（切换即激活生图供应商），与对话模型相互独立
const imageProviderId = ref(null)
const imageProvider = computed(() => providerList.value.find((p) => p.imageActive === 1) || null)
const imageProviderLabel = computed(() => (imageProvider.value ? formatImageProviderLabel(imageProvider.value) : ''))
const chatModelLabel = computed(() => {
  const provider = providerList.value.find((item) => item.id === activeProviderId.value)
  return provider ? formatProviderLabel(provider) : ''
})
const imageModelTip = computed(() => imageProvider.value
  ? '出图使用该生图供应商，可在下方选择器或「模型设置」中更换'
  : '模型设置中未单独激活生图供应商，出图将回退使用当前对话模型，可能生成失败')
const tools = ref([])
const messages = ref([])
const input = ref('')
// 对话历史持久化：当前会话 ID 与历史列表
const conversationId = ref(null)
const historyList = ref([])
const selectedHistoryId = ref(null)
const streaming = ref(false)
const chatWindowRef = ref(null)
const inputHeight = ref(110)
const fileInputRef = ref(null)
const pendingImages = ref([])
const dragOver = ref(false)
const reasoningOptions = [
  { value: 'low', label: '低' },
  { value: 'medium', label: '中' },
  { value: 'high', label: '高' }
]
const reasoningEffort = ref(localStorage.getItem('agent-reasoning-effort') || 'high')
let abortController = null
let typewriterTimer = null

function saveReasoningEffort(value) {
  reasoningEffort.value = value
  localStorage.setItem('agent-reasoning-effort', value)
}

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

// 模型供应商选择：切换即全局激活，智能体对话随即使用新模型
const providerList = ref([])
const activeProviderId = ref(null)

const activeProviderName = computed(() => {
  const provider = providerList.value.find((item) => item.id === activeProviderId.value)
  return provider ? formatProviderLabel(provider) : ''
})

function formatProviderLabel(provider) {
  const label = `${provider.name} / ${provider.model}`
  return provider.apiKey ? label : `${label}（平台默认，不可切换）`
}

// 生图供应商选项标签：展示专用生图模型，未配置时回退其对话模型
function formatImageProviderLabel(provider) {
  const label = `${provider.name} / ${provider.imageModel || provider.model}`
  return provider.apiKey ? label : `${label}（平台默认，不可切换）`
}

async function loadProviders() {
  try {
    const res = await getModelProviderList()
    providerList.value = res.data || []
    const active = providerList.value.find((provider) => provider.isActive === 1)
    if (active) activeProviderId.value = active.id
    const imageActive = providerList.value.find((provider) => provider.imageActive === 1)
    if (imageActive) imageProviderId.value = imageActive.id
  } catch (error) {
    console.error(error)
  }
}

async function handleSwitchProvider(providerId) {
  try {
    await activateModelProvider(providerId)
    activeProviderId.value = providerId
    const provider = providerList.value.find((item) => item.id === providerId)
    ElMessage.success(`已切换到 ${provider?.name || '当前模型'}`)
  } catch (error) {
    ElMessage.error('切换失败')
  }
}

// 切换生图供应商：同时影响生图智能体与 AI 生图模块的出图模型
async function handleSwitchImageProvider(providerId) {
  try {
    await activateImageModelProvider(providerId)
    providerList.value.forEach((item) => { item.imageActive = item.id === providerId ? 1 : 0 })
    const provider = providerList.value.find((item) => item.id === providerId)
    ElMessage.success(`生图模型已切换为 ${provider?.name || '当前供应商'}`)
  } catch (error) {
    ElMessage.error('切换生图模型失败')
  }
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
  loadProviders()
  try {
    const res = await listAgentTools(agentCode)
    tools.value = Array.isArray(res) ? res : res.data || []
  } catch (e) {
    // 工具清单加载失败不阻塞聊天
  }
  // 自动接上该智能体最近一次会话；会话已删除时静默按新会话进入
  const lastId = readLastConversationId()
  if (lastId) {
    await loadHistoryConversation(lastId, { silent: true })
  }
  refreshHistory()
})

onBeforeUnmount(() => {
  if (abortController) abortController.abort()
  // 离开页面时停止听写与朗读
  if (dictation) dictation.stop()
  stopSpeaking()
  flushTypewriter()
})

// keep-alive 页面切走时触发 onDeactivated（onBeforeUnmount 不会执行）：
// 停止听写与朗读，回来时按钮为初始状态
onDeactivated(() => {
  if (dictation) dictation.stop()
  stopSpeaking()
  dictating.value = false
  speakingMessage.value = null
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
  return total > 0 ? ` · 共 ${total}ms` : ''
}

function failText(msg) {
  const n = msg.steps.filter((step) => step.status === 'FAILED').length
  return n > 0 ? ` · ${n} 失败` : ''
}

function statusText(step) {
  if (step.status === 'RUNNING') return '执行中…'
  if (step.status === 'FAILED') return '失败'
  return `${step.costMs || 0}ms`
}

function argsPreview(step) {
  const a = step.arguments || ''
  return a.length > 96 ? a.slice(0, 96) + '…' : a
}

// 参数尽力格式化：合法 JSON 缩进两格展示，原样文本直接给出
function prettyArgs(step) {
  try { return JSON.stringify(JSON.parse(step.arguments), null, 2) } catch (e) { return step.arguments }
}

function toggleStepDetail(step) {
  step.detailOpen = !step.detailOpen
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
        costMs: 0,
        errorMessage: '',
        detailOpen: false
      })
      msg.stepsOpen = true
      break
    case 'step_result': {
      const step = msg.steps.find((item) => item.stepNo === data.stepNo)
      if (step) {
        step.result = data.result
        step.costMs = data.costMs
        step.status = data.status
        step.errorMessage = data.errorMessage || ''
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
  // 持久化：首条消息时创建会话，随后存档用户消息
  await ensureConversation(messageText)
  await saveMessage('user', messageText)

  const history = messages.value
    .slice(0, -2)
    .filter((m) => m.content)
    .map((m) => ({ role: m.role, content: m.content }))

  abortController = new AbortController()
  try {
    await streamAgentChat(
      { message: messageText, history, agentCode, reasoningEffort: reasoningEffort.value, images: images.length ? images : undefined },
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
    // 持久化：存档助手回答（含工具步骤，恢复后可重建图表与导出）
    const finalContent = assistant.content || (assistant.errorMsg ? '执行出错：' + assistant.errorMsg : '')
    if (finalContent) {
      await saveAssistantMessage(assistant)
    }
  }
}

// ---------- 对话历史持久化 ----------

// 确保存在当前会话：首条消息发出时创建，标题取首条消息
async function ensureConversation(firstMessage) {
  if (conversationId.value) return
  try {
    const res = await createConversation(agentCode, firstMessage.slice(0, 80))
    conversationId.value = res.data.id
    rememberConversation(conversationId.value)
    await refreshHistory()
  } catch (error) {
    // 历史保存失败不阻塞对话
    console.error(error)
  }
}

// 追加保存一条消息
async function saveMessage(role, content, steps) {
  if (!conversationId.value) return
  try {
    await appendConversationMessage(conversationId.value, {
      role,
      content,
      stepsJson: steps ? JSON.stringify(steps) : null
    })
    await refreshHistory()
  } catch (error) {
    console.error(error)
  }
}

// 保存助手消息：内容 + 工具步骤（含参数，恢复后图表/导出可用）
// 落库后的消息编号写回 assistant.serverId，供大拇指反馈关联
async function saveAssistantMessage(assistant) {
  const steps = (assistant.steps || [])
    .filter((s) => s.tool)
    .map((s) => ({ stepNo: s.stepNo, tool: s.tool, arguments: s.arguments, result: s.result, status: s.status, costMs: s.costMs }))
  try {
    const res = await appendConversationMessage(conversationId.value, {
      role: 'assistant',
      content: assistant.content,
      stepsJson: steps.length ? JSON.stringify(steps) : null
    })
    assistant.serverId = res.data || null
    await refreshHistory()
  } catch (error) {
    console.error(error)
  }
}

// 加载历史会话列表
async function refreshHistory() {
  try {
    const res = await listConversations(agentCode)
    historyList.value = res.data || []
  } catch (error) {
    console.error(error)
  }
}

// 从历史加载会话：重建消息与工具步骤（图表/导出按钮随之恢复）
// silent = true 用于进入页面时的自动恢复：会话已被删除等情况静默回退，不弹错误
async function loadHistoryConversation(id, { silent = false } = {}) {
  if (!id) return
  if (streaming.value) {
    ElMessage.warning('当前正在生成回答，请稍后再切换历史')
    selectedHistoryId.value = conversationId.value
    return
  }
  try {
    const res = await getConversationDetail(id)
    const data = res.data || {}
    const restored = []
    for (const item of data.messages || []) {
      if (item.role === 'user') {
        restored.push({ role: 'user', content: item.content })
      } else {
        let steps = []
        try {
          steps = item.stepsJson ? JSON.parse(item.stepsJson) : []
        } catch (error) {
          steps = []
        }
        restored.push({
          id: item.id,
          role: 'assistant',
          content: item.content,
          steps,
          stepsOpen: false,
          pending: false,
          // 反馈状态回显：会话详情附带每条消息的点赞状态
          _feedback: item.feedback === 0 || item.feedback === 1 ? item.feedback : null
        })
      }
    }
    conversationId.value = data.id
    selectedHistoryId.value = data.id
    messages.value = restored
    rememberConversation(data.id)
    nextTick(scrollBottom)
  } catch (error) {
    if (silent) {
      // 记忆的会话可能已在别处删除：清掉记忆按新会话进入，不打扰用户
      forgetConversation()
      startNewConversation()
      return
    }
    ElMessage.error('加载历史对话失败')
  }
}

// 开启新对话：清空当前状态（历史仍保留在列表中）
function startNewConversation() {
  conversationId.value = null
  selectedHistoryId.value = null
  messages.value = []
  forgetConversation()
}

// 删除当前会话
async function deleteCurrentConversation() {
  if (!conversationId.value) return
  try {
    await deleteConversation(conversationId.value)
    ElMessage.success('会话已删除')
    startNewConversation()
    await refreshHistory()
  } catch (error) {
    ElMessage.error('删除失败')
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
  font-size: 15px;
  font-weight: 600;
  color: var(--amber-hi);
  background: rgba(10, 12, 18, 0.78);
  border: 1px dashed var(--amber);
  border-radius: 0;
  pointer-events: none;
}

.agent-tool-page {
  position: relative;
  display: flex;
  flex-direction: column;
  height: 100%;
  padding: 18px 28px 20px;
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
  column-gap: 20px;
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
  padding-bottom: 12px;
  margin-bottom: 14px;
  border-bottom: 1px solid;
  border-image: linear-gradient(90deg, var(--amber-line), transparent 70%) 1;
}

.header-back {
  margin-bottom: 10px;
}

.page-title {
  margin: 0 0 4px;
  font-size: 20px;
  font-weight: 600;
  color: var(--text-1);
}

.page-desc {
  margin: 0;
  color: var(--text-2);
  font-size: 12.5px;
}

.history-bar {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 10px;
  justify-content: flex-end;
}

.history-select {
  width: 260px;
}

.tool-chips {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  justify-content: flex-end;
}

.tool-chip {
  padding: 1px 8px;
  color: var(--amber-hi);
  border: 1px solid var(--amber-line-dim);
  border-radius: 0;
  background: rgba(236, 233, 225, 0.05);
  font-family: var(--font-mono);
  font-size: 11px;
  cursor: default;
}

.tool-chip.image-model-warn {
  color: var(--warn);
  border-color: rgba(236, 233, 225, 0.35);
  background: transparent;
}

.chat-window {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  padding: 18px 0 22px;
}

.empty-hint {
  margin-top: 80px;
  text-align: center;
  color: var(--text-3);
  font-size: 13px;
}

/* 消息 = 日志条目：无气泡直排 */
.msg-row {
  margin: 0 8px 4px;
  padding-bottom: 10px;
  border-bottom: 1px solid var(--line);
}

.msg-row:last-child {
  border-bottom: none;
}

.msg-bubble {
  max-width: 860px;
  padding-left: 14px;
  font-size: 14px;
  line-height: 1.7;
  white-space: pre-wrap;
  color: var(--text-1);
}

/* 用户条目：左侧 2px 琥珀条 */
.msg-row.user .msg-bubble {
  border-left: 2px solid var(--amber);
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
  border: 1px dashed var(--amber-line);
  border-radius: 0;
  background: rgba(236, 233, 225, 0.03);
  cursor: pointer;
  color: var(--text-3);
  font-size: 12px;
  line-height: 1.5;
  text-align: center;
  transition: border-color 0.18s ease, color 0.18s ease;
}

.image-panel:hover {
  border-color: var(--amber);
  color: var(--amber-hi);
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
  border: 1px solid var(--line);
  border-radius: 0;
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
}

.stream-status {
  margin-bottom: 8px;
  color: var(--warn);
  font-family: var(--font-mono);
  font-size: 11px;
  letter-spacing: 0.08em;
}

/* 执行步骤 = 日志流：mono 工具行 01 tool · 320ms ✓ */
.steps-panel {
  margin-bottom: 10px;
  border-left: 2px solid var(--line);
}

.steps-title {
  padding: 4px 10px;
  color: var(--text-3);
  font-family: var(--font-mono);
  font-size: 11px;
  letter-spacing: 0.08em;
  cursor: pointer;
  user-select: none;
}

.steps-title:hover {
  color: var(--amber-hi);
}

/* 执行过程时间线：左侧步骤节点 + 连接线，右侧工具详情（点击头部展开参数/结果/失败原因） */
.step-timeline {
  padding: 2px 0 4px;
}

.step-row {
  display: flex;
  gap: 10px;
}

.step-rail {
  display: flex;
  flex-direction: column;
  align-items: center;
  flex: 0 0 auto;
  width: 22px;
}

.step-node {
  display: grid;
  place-items: center;
  width: 18px;
  height: 18px;
  flex: 0 0 auto;
  border: 1px solid var(--line);
  background: rgba(242, 239, 233, 0.04);
  color: var(--text-3);
  font-family: var(--font-mono);
  font-size: 10px;
}

.step-row.completed .step-node {
  border-color: var(--ok);
  color: var(--ok);
}

.step-row.running .step-node {
  border-color: var(--warn);
  color: var(--warn);
  animation: step-pulse 1.1s ease-in-out infinite;
}

.step-row.failed .step-node {
  border-color: var(--bad);
  color: var(--bad);
  font-weight: 700;
}

@keyframes step-pulse {
  0%, 100% { box-shadow: 0 0 0 0 rgba(247, 181, 56, 0.35); }
  50% { box-shadow: 0 0 0 4px rgba(247, 181, 56, 0); }
}

.step-line {
  flex: 1;
  width: 1px;
  min-height: 12px;
  margin: 2px 0;
  background: var(--line);
}

.step-body {
  flex: 1;
  min-width: 0;
  padding: 1px 0 12px;
  font-family: var(--font-mono);
  font-feature-settings: "zero" off;
  font-size: 12.5px;
  color: var(--text-2);
}

.step-head {
  display: flex;
  align-items: center;
  gap: 10px;
  min-height: 18px;
  cursor: pointer;
}

.step-tool {
  color: var(--amber-hi);
  font-weight: 600;
}

.step-head:hover .step-tool {
  text-decoration: underline;
}

.step-status {
  margin-left: auto;
  color: var(--text-3);
  font-size: 11px;
  white-space: nowrap;
}

.step-row.completed .step-status {
  color: var(--ok);
}

.step-row.running .step-status {
  color: var(--warn);
}

.step-row.failed .step-status {
  color: var(--bad);
}

.step-args-preview {
  display: block;
  margin-top: 2px;
  overflow: hidden;
  color: var(--text-3);
  font-size: 11px;
  text-overflow: ellipsis;
  white-space: nowrap;
  cursor: pointer;
}

.step-detail {
  margin-top: 4px;
}

.detail-label {
  color: var(--text-3);
  font-size: 10px;
  letter-spacing: 0.12em;
}

.detail-pre {
  margin: 2px 0 8px;
  padding: 6px 8px;
  max-height: 220px;
  overflow: auto;
  color: var(--text-2);
  background: rgba(242, 239, 233, 0.04);
  border: 1px solid var(--line);
  font-family: var(--font-mono);
  font-size: 11.5px;
  line-height: 1.55;
  white-space: pre-wrap;
  word-break: break-all;
}

.detail-err {
  color: var(--bad);
  border-color: rgba(255, 107, 87, 0.45);
}

.answer-text {
  color: var(--text-1);
}

.loading-spinner {
  color: var(--amber);
  font-size: 16px;
  vertical-align: -3px;
  animation: loading-spin 0.9s linear infinite;
}

@keyframes loading-spin {
  from {
    transform: rotate(0deg);
  }
  to {
    transform: rotate(360deg);
  }
}

.error-text {
  margin-top: 6px;
  color: var(--bad);
  font-size: 12.5px;
}

.input-bar {
  position: relative;
  display: flex;
  flex-direction: column;
  margin-top: 14px;
  padding-top: 12px;
  border-top: 1px solid var(--line);
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
  height: 3px;
  border-radius: 0;
  background: rgba(242, 239, 233, 0.14);
  transition: background 0.2s;
}

.resize-handle:hover span {
  background: rgba(236, 233, 225, 0.72);
}

.input-row {
  position: relative;
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
  padding: 8px 84px 40px 0;
  border: none;
  color: var(--text-1);
  background: transparent;
  box-shadow: none;
  resize: none;
  line-height: 1.6;
}

.input-row :deep(.el-textarea__inner:focus) {
  box-shadow: none;
}

.input-row :deep(.el-textarea__inner::placeholder) {
  color: var(--text-3);
}

.input-actions {
  position: absolute;
  right: 0;
  bottom: 10px;
  z-index: 5;
  display: flex;
  align-items: center;
  gap: 10px;
}

.mic-button {
  padding: 8px;
  color: var(--text-3);
}

.mic-button.mic-active {
  color: var(--bad);
  animation: mic-pulse 1.2s ease-in-out infinite;
}

@keyframes mic-pulse {
  0%, 100% {
    box-shadow: 0 0 0 0 rgba(255, 138, 138, 0.4);
  }
  50% {
    box-shadow: 0 0 0 8px rgba(255, 138, 138, 0.08);
  }
}

/* 回答朗读与大拇指 */
.answer-actions {
  display: flex;
  align-items: center;
  margin-top: 6px;
}

.speak-btn,
.answer-actions .feedback-btn {
  color: var(--text-3);
}

.speak-btn.speak-active {
  color: var(--amber-hi);
}

.answer-actions .feedback-btn:hover {
  color: var(--amber-hi);
}

.answer-actions .feedback-btn.feedback-active-good {
  color: var(--ok);
}

.answer-actions .feedback-btn.feedback-active-bad {
  color: var(--bad);
}

.effort-chip {
  width: auto;
  min-width: 92px;
  max-width: 120px;
}

.effort-chip :deep(.el-select__wrapper) {
  min-height: 30px;
  padding: 2px 10px;
  border-radius: 0;
  background: transparent;
  box-shadow: 0 0 0 1px var(--line);
  font-family: var(--font-mono);
  font-size: 11.5px;
}

.effort-chip :deep(.el-select__wrapper.is-hovering) {
  box-shadow: 0 0 0 1px var(--amber-line);
}

.model-chip {
  width: auto;
  min-width: 150px;
  max-width: 260px;
}

.model-chip :deep(.el-select__wrapper) {
  min-height: 30px;
  padding: 2px 12px;
  border-radius: 0;
  background: transparent;
  box-shadow: 0 0 0 1px var(--line);
  font-family: var(--font-mono);
  font-size: 11.5px;
}

.model-chip :deep(.el-select__wrapper.is-hovering) {
  box-shadow: 0 0 0 1px var(--amber-line);
}
</style>
