<template>
  <div class="chat-page page-shell">
    <aside class="chat-sidebar">
      <section class="sidebar-panel">
        <h2>对话设置</h2>
        <p>选择知识库后，回答会优先引用已入库文档。</p>
        <el-select
          v-model="selectedKbId"
          class="side-control"
          placeholder="选择知识库"
          clearable
          filterable
        >
          <el-option v-for="kb in kbList" :key="kb.id" :label="kb.name" :value="kb.id" />
        </el-select>
        <div class="mode-card">
          <span>当前模式</span>
          <strong>{{ selectedKbId ? '知识库问答' : '通用对话' }}</strong>
          <p>{{ selectedKbId ? '先检索相关文档片段，再组织回答。' : '不检索知识库，直接调用大模型回答。' }}</p>
          <small>模型：{{ activeProviderName }}</small>
        </div>
        <el-button class="side-button" @click="startNewSession">
          <el-icon><Refresh /></el-icon>
          新对话
        </el-button>
        <el-button class="side-button" :disabled="messages.length === 0" @click="exportChat">
          <el-icon><Download /></el-icon>
          导出对话
        </el-button>
      </section>

      <section class="sidebar-panel">
        <h2>召回测试</h2>
        <p>输入问题，查看知识库召回了哪些文本块。</p>
        <el-input
          v-model="recallQuery"
          type="textarea"
          :autosize="{ minRows: 2, maxRows: 4 }"
          placeholder="输入测试问题"
        />
        <el-button
          class="side-button primary"
          :loading="recallLoading"
          :disabled="!recallQuery.trim() || !selectedKbId"
          @click="handleRecallTest"
        >
          测试召回
        </el-button>
        <div v-if="recallResults.length > 0" class="recall-results">
          <div v-for="item in recallResults" :key="item.chunkId || item.rank" class="recall-item">
            <div class="recall-item-header">
              <el-tag size="small" type="info">#{{ item.rank }}</el-tag>
              <el-tag size="small" :type="getMatchTypeTag(item.matchType)">
                {{ getMatchTypeText(item.matchType) }}
              </el-tag>
              <span v-if="item.vectorScore != null">相似度 {{ formatRecallScore(item.vectorScore) }}</span>
            </div>
            <div class="recall-source">{{ item.fileName || item.source }} · 第 {{ (item.chunkIndex ?? 0) + 1 }} 段</div>
            <div v-if="item.rewrittenQuery" class="recall-query">检索词：{{ item.rewrittenQuery }}</div>
            <div class="recall-item-content">{{ item.content }}</div>
          </div>
        </div>
      </section>

      <section class="sidebar-panel history-panel">
        <h2>历史对话</h2>
        <div v-if="sessionList.length === 0" class="empty-history">暂无历史对话</div>
        <div
          v-for="session in sessionList"
          :key="session.id"
          class="history-item"
          :class="{ active: session.id === sessionId }"
        >
          <button class="history-item-main" @click="loadSession(session.id)">
            <span>{{ session.title }}</span>
            <small>{{ formatSessionTime(session.updatedAt) }}</small>
          </button>
          <el-icon class="history-item-delete" title="删除对话" @click.stop="handleDeleteSession(session)">
            <Delete />
          </el-icon>
        </div>
      </section>

      <section class="sidebar-panel">
        <h2>快捷问题</h2>
        <button
          v-for="question in quickQuestions"
          :key="question"
          class="quick-question"
          @click="useQuickQuestion(question)"
        >
          {{ question }}
        </button>
      </section>
    </aside>

    <section class="chat-main">
      <div class="chat-header">
        <div class="chat-head-main">
          <h1>智能问答</h1>
          <span class="meta">知识库：{{ selectedKbName }} · 模型 {{ activeProviderName }}</span>
        </div>
      </div>

      <div ref="messagesRef" class="chat-messages">
        <div v-if="messages.length === 0 && !loading" class="empty-chat">
          <el-icon><ChatDotRound /></el-icon>
          <h2>开始一次清晰的问答</h2>
          <p>可以先选择知识库，也可以直接向大模型提问。</p>
        </div>

        <article v-for="msg in messages" :key="msg.id" class="message-item" :class="msg.role">
          <div class="message-row">
            <div class="message-avatar" :class="msg.role === 'user' ? 'me' : 'ai'">{{ msg.role === 'user' ? '你' : 'AI' }}</div>
            <div class="message-content">
            <div class="message-meta">
              <span class="who">{{ msg.role === 'user' ? '你' : 'AI 助手' }}</span>
              <span v-if="msg._streaming" class="status">{{ msg.statusText || '生成中' }}</span>
            </div>
            <div v-if="msg._streaming && !msg.content" class="message-text loading-card">
              <span class="spinner"></span>
              <span>{{ msg.statusText || '正在思考并生成回答' }}</span>
            </div>
            <div v-else-if="msg._streaming" class="message-text streaming-text">
              {{ msg.content }}
            </div>
            <div v-else class="message-text" v-html="renderMarkdown(msg.content)" />
            <div v-if="msg.role === 'assistant' && !msg._streaming && msg._answerMeta" class="answer-evidence-meta">
              <span :class="['evidence-pill', `confidence-${String(msg._answerMeta.confidenceLevel || 'NONE').toLowerCase()}`]">
                置信度 {{ Number(msg._answerMeta.confidence ?? 0) }}%
              </span>
              <span class="evidence-pill">证据覆盖率 {{ Number(msg._answerMeta.evidenceCoverage ?? 0) }}%</span>
              <span class="evidence-pill">{{ Number(msg._answerMeta.citationCount ?? 0) }} 条引用</span>
            </div>
            <div v-if="msg.role === 'assistant' && !msg._streaming && msg._grounded && msg._citations?.length" class="citation-block">
              <button class="citation-toggle" type="button" @click="toggleCitations(msg)">
                引用 {{ msg._citations.length }} 条 <span class="cite-caret">{{ msg._showCitations ? '▴' : '▾' }}</span>
              </button>
              <template v-if="msg._showCitations">
                <div v-for="citation in msg._citations" :key="`${msg.id}-${citation.chunkId}`" class="citation-item">
                  <div class="citation-info">
                    <strong>{{ citation.fileName }}</strong>
                    <span class="cite-loc">第 {{ Number(citation.chunkIndex ?? 0) + 1 }} 段</span>
                    <span class="cite-type">{{ getMatchTypeText(citation.matchType) }}</span>
                  </div>
                  <p>{{ citation.contentPreview }}</p>
                  <div class="citation-actions">
                    <el-button text size="small" @click="openCitation(citation)">
                      <el-icon><Download /></el-icon>
                      查看原文
                    </el-button>
                    <span v-if="citation.finalScore != null">匹配度 {{ formatRecallScore(citation.finalScore) }}</span>
                  </div>
                </div>
              </template>
            </div>
            <div v-if="msg._failed" class="failed-box">
              <span class="failed-text">回答生成失败：{{ msg._failReason || '未知原因' }}</span>
              <el-button size="small" type="primary" plain class="retry-btn" @click="retryFailed(msg)">重试</el-button>
            </div>
            <div v-if="msg.role === 'assistant' && !msg._streaming" class="feedback-bar">
              <el-button
                v-if="ttsSupported && msg.content"
                size="small"
                class="feedback-btn"
                :class="{ 'feedback-active-good': speakingMessageId === msg.id }"
                :type="speakingMessageId === msg.id ? 'success' : 'default'"
                text
                :title="speakingMessageId === msg.id ? '停止朗读' : '朗读回答'"
                @click="toggleSpeak(msg)"
              >
                <svg v-if="speakingMessageId !== msg.id" viewBox="0 0 24 24" width="15" height="15" fill="currentColor" aria-hidden="true">
                  <path d="M3 9v6h4l5 5V4L7 9H3zm13.5 3c0-1.77-1.02-3.29-2.5-4.03v8.05c1.48-.73 2.5-2.25 2.5-4.02zM14 3.23v2.06c2.89.86 5 3.54 5 6.71s-2.11 5.85-5 6.71v2.06c4.01-.91 7-4.49 7-8.77s-2.99-7.86-7-8.77z" />
                </svg>
                <svg v-else viewBox="0 0 24 24" width="15" height="15" fill="currentColor" aria-hidden="true">
                  <path d="M6 6h12v12H6z" />
                </svg>
              </el-button>
              <template v-if="msg.id">
              <el-button
                size="small"
                class="feedback-btn"
                :class="{ 'feedback-active-good': msg._feedback === 1 }"
                :type="msg._feedback === 1 ? 'success' : 'default'"
                text
                title="有帮助"
                @click="handleFeedback(msg, 1)"
              >
                <svg viewBox="0 0 24 24" width="15" height="15" fill="currentColor" aria-hidden="true">
                  <path d="M1 21h4V9H1v12zm22-11c0-1.1-.9-2-2-2h-6.31l.95-4.57.03-.32c0-.41-.17-.79-.44-1.06L14.17 1 7.59 7.59C7.22 7.95 7 8.45 7 9v10c0 1.1.9 2 2 2h9c.83 0 1.54-.5 1.84-1.22l3.02-7.05c.09-.23.14-.47.14-.73v-2z" />
                </svg>
              </el-button>
              <el-button
                size="small"
                class="feedback-btn"
                :class="{ 'feedback-active-bad': msg._feedback === 0 }"
                :type="msg._feedback === 0 ? 'danger' : 'default'"
                text
                title="无帮助"
                @click="handleFeedback(msg, 0)"
              >
                <svg viewBox="0 0 24 24" width="15" height="15" fill="currentColor" aria-hidden="true">
                  <path d="M15 3H6c-.83 0-1.54.5-1.84 1.22l-3.02 7.05c-.09.23-.14.47-.14.73v2c0 1.1.9 2 2 2h6.31l-.95 4.57-.03.32c0 .41.17.79.44 1.06L9.83 23l6.59-6.59c.36-.36.58-.86.58-1.41V5c0-1.1-.9-2-2-2zm4 0v12h4V3h-4z" />
                </svg>
              </el-button>
              </template>
            </div>
            <div v-if="msg.role === 'assistant' && !msg._streaming && msg.followups && msg.followups.length" class="followup-bar">
              <button
                v-for="(question, index) in msg.followups"
                :key="index"
                type="button"
                class="followup-chip"
                @click="askFollowup(question)"
              >{{ question }}</button>
            </div>
            </div>
          </div>
        </article>

        <article v-if="waitingForResponse" class="message-item assistant">
          <div class="message-row">
            <div class="message-avatar ai">AI</div>
            <div class="message-content">
              <div class="message-meta">
                <span class="who">AI 助手</span>
                <span class="status">生成中</span>
              </div>
              <div class="message-text loading-card">
                <span class="spinner"></span>
                <span>模型正在思考，请稍等...</span>
              </div>
            </div>
          </div>
        </article>
      </div>

      <div
        class="chat-resize-handle"
        title="拖动调整输入框高度"
        @mousedown="startInputResize"
      ></div>

      <div class="chat-input-panel" :style="{ height: inputAreaHeight + 'px' }">
        <el-input
          v-model="inputMessage"
          type="textarea"
          resize="none"
          placeholder="输入你的问题，Enter 发送，Shift + Enter 换行"
          @keydown.enter.exact="handleEnter"
        />
        <div class="input-toolbar">
          <el-select
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
          <div class="toolbar-right">
            <el-button
              class="mic-button"
              :class="{ 'mic-active': dictating }"
              :title="sttSupported ? (dictating ? '停止聆听' : '语音输入') : '当前浏览器不支持语音识别（可用 Chrome / Edge）'"
              :disabled="!sttSupported"
              @click="toggleDictation"
            >
              <svg viewBox="0 0 24 24" width="16" height="16" fill="currentColor" aria-hidden="true">
                <path d="M12 14c1.66 0 3-1.34 3-3V5c0-1.66-1.34-3-3-3S9 3.34 9 5v6c0 1.66 1.34 3 3 3zm5.91-3c-.49 0-.9.36-.98.85C16.52 14.2 14.47 16 12 16s-4.52-1.8-4.93-4.15c-.08-.49-.49-.85-.98-.85-.61 0-1.09.54-1 1.14.49 3 2.89 5.35 5.91 5.78V20c0 .55.45 1 1 1s1-.45 1-1v-2.08c3.02-.43 5.42-2.78 5.91-5.78.1-.6-.39-1.14-1-1.14z" />
              </svg>
            </el-button>
            <el-button
              type="primary"
              class="send-button"
              title="发送（Enter）"
              :loading="loading"
              :disabled="!inputMessage.trim()"
              @click="sendMessage"
            >
              <el-icon><Promotion /></el-icon>
            </el-button>
          </div>
        </div>
      </div>
    </section>
  </div>
</template>

<script setup>
// 组件名用于 keep-alive 缓存：切换菜单时保留对话与流式状态
defineOptions({ name: 'ChatView' })
import { computed, nextTick, onActivated, onBeforeUnmount, onDeactivated, onMounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { ChatDotRound, Delete, Download, Promotion, Refresh } from '@element-plus/icons-vue'
import MarkdownIt from 'markdown-it'
import { getKbList } from '../../api/kb'
import { deleteSession, getChatHistory, recallTest, submitFeedback } from '../../api/chat'
import { getFilePreviewUrl } from '../../api/file'
import { activateModelProvider, getModelProviderList } from '../../api/modelProvider'
import { createDictation, isSTTSupported, isTTSSupported, speak, stopSpeaking, stripMarkdownForSpeech } from '../../utils/speech'
import { mascot } from '../../composables/useMascot'

const route = useRoute()
const md = new MarkdownIt({ breaks: true, linkify: true })

// ---------- 语音输入与朗读 ----------
// 能力在加载时检测一次：不支持的浏览器直接隐藏/禁用入口，避免点击后才发现不可用
const sttSupported = isSTTSupported()
const ttsSupported = isTTSSupported()
const dictating = ref(false)
const speakingMessageId = ref(null)
let dictation = null
let dictationBase = ''

// 开始/停止听写：中间结果实时并入输入框，最终文本沉淀避免抖动
function toggleDictation() {
  if (dictating.value) {
    dictation.stop()
    return
  }
  if (!dictation) {
    dictation = createDictation({
      onText: (text) => {
        inputMessage.value = (dictationBase + ' ' + text).trim()
      },
      onError: (message) => {
        ElMessage.warning(message)
      },
      onStop: () => {
        dictating.value = false
      }
    })
  }
  dictationBase = inputMessage.value ? inputMessage.value + ' ' : ''
  dictating.value = true
  dictation.start()
}

// 朗读/停止某条回答；开始新朗读会打断上一段
async function toggleSpeak(msg) {
  if (speakingMessageId.value === msg.id) {
    stopSpeaking()
    speakingMessageId.value = null
    return
  }
  stopSpeaking()
  speakingMessageId.value = msg.id
  await speak(stripMarkdownForSpeech(msg.content))
  // 自然结束或被打断后复位；若期间用户已点别的朗读，保持新的状态
  if (speakingMessageId.value === msg.id) {
    speakingMessageId.value = null
  }
}

const messages = ref([])
const inputMessage = ref('')
const loading = ref(false)
const kbList = ref([])
const selectedKbId = ref(null)
const sessionId = ref(null)
const sessionList = ref([])
const messagesRef = ref(null)
const providerList = ref([])
const activeProviderId = ref(null)
const abortController = ref(null)
const waitingForResponse = ref(false)
const recallQuery = ref('')
const recallLoading = ref(false)
const recallResults = ref([])
let pollingTimer = null
let pollingInFlight = false   // 轮询 in-flight 标志：上一轮请求未返回时不再发下一轮
// 自动滚动节流：流式 token 高频到达时不逐个 token 同步滚动
let scrollThrottleTimer = null
let lastScrollAt = 0
const SCROLL_THROTTLE_MS = 80

// 输入区高度：可用鼠标拖动上方的分隔条上下调整
const inputAreaHeight = ref(110)
const INPUT_MIN_HEIGHT = 110
const INPUT_MAX_HEIGHT = 480
let resizingInput = false
let resizeStartY = 0
let resizeStartHeight = 0

function handleInputResizeMove(event) {
  if (!resizingInput) return
  const next = resizeStartHeight - (event.clientY - resizeStartY)
  inputAreaHeight.value = Math.min(Math.max(next, INPUT_MIN_HEIGHT), INPUT_MAX_HEIGHT)
}

function stopInputResize() {
  if (!resizingInput) return
  resizingInput = false
  document.removeEventListener('mousemove', handleInputResizeMove)
  document.removeEventListener('mouseup', stopInputResize)
  document.body.style.userSelect = ''
  document.body.style.cursor = ''
}

function startInputResize(event) {
  resizingInput = true
  resizeStartY = event.clientY
  resizeStartHeight = inputAreaHeight.value
  document.addEventListener('mousemove', handleInputResizeMove)
  document.addEventListener('mouseup', stopInputResize)
  document.body.style.userSelect = 'none'
  document.body.style.cursor = 'ns-resize'
  event.preventDefault()
}

// 本地聊天状态按登录用户分区，避免同一浏览器切换账号后看到其他账号的历史会话。
const chatStorageUserId = localStorage.getItem('userId') || 'anonymous'
const sessionStorageKey = `rag_chat_sessions:${chatStorageUserId}`
const currentSessionStorageKey = `rag_current_session:${chatStorageUserId}`
const pendingChatSessionKey = `rag_chat_pending_session:${chatStorageUserId}`
const pendingChatTimeoutMs = 2 * 60 * 1000

const quickQuestions = [
  '请总结这个知识库的核心内容',
  '有哪些需要注意的规则或流程？',
  '把相关内容整理成三条要点'
]

// 当前选中知识库名称
const selectedKbName = computed(() => {
  const kb = kbList.value.find(item => item.id === selectedKbId.value)
  return kb ? kb.name : '通用对话'
})

// 当前启用模型名称
const activeProviderName = computed(() => {
  const provider = providerList.value.find(item => item.id === activeProviderId.value)
  return provider ? formatProviderLabel(provider) : '未选择模型'
})

// 拼接模型展示名称，他人创建的平台供应商标记为不可切换
function formatProviderLabel(provider) {
  const label = `${provider.name} / ${provider.model}`
  return provider.apiKey ? label : `${label}（平台默认，不可切换）`
}

onMounted(() => {
  loadSessionList()
  loadKbList()
  restoreKbFromQuery()
  loadProviders()
  restoreCurrentSession()
})

onBeforeUnmount(() => {
  if (sessionId.value) {
    localStorage.setItem(currentSessionStorageKey, String(sessionId.value))
  }
  if (abortController.value) {
    abortController.value.abort()
    abortController.value = null
  }
  // 离开页面时停止听写与朗读，避免后台继续占用麦克风/喇叭
  if (dictation) {
    dictation.stop()
  }
  stopSpeaking()
  clearPendingChatSession()
  loading.value = false
  waitingForResponse.value = false
  stopPolling()
  stopScrollThrottle()
  stopInputResize()
  mascot.stopReading()   // 保险：离开页面必回位
})

// keep-alive 页面切走时触发 onDeactivated（onBeforeUnmount 不会执行）：
// 停止听写与朗读，回来时按钮为初始状态
onDeactivated(() => {
  if (dictation) dictation.stop()
  stopSpeaking()
  dictating.value = false
  speakingMessageId.value = null
  mascot.stopReading()   // 页面切走（keep-alive）：回位
})


// 只盯最后一条消息的内容与消息条数：既保证新消息、流式增量时滚到底部，
// 又避免对 messages 做 deep watch（每个 token 都全量遍历整个消息数组）
watch(
  () => {
    const list = messages.value
    const last = list[list.length - 1]
    return `${list.length}:${last ? last.content : ''}`
  },
  () => {
    scheduleScrollToBottom()
  }
)

// 加载知识库列表
async function loadKbList() {
  try {
    const res = await getKbList()
    kbList.value = res.data || []
    restoreKbFromQuery()
  } catch (error) {
    console.error(error)
  }
}

// 加载模型供应商列表
async function loadProviders() {
  try {
    const res = await getModelProviderList()
    providerList.value = res.data || []
    const active = providerList.value.find(provider => provider.isActive === 1)
    if (active) {
      activeProviderId.value = active.id
    }
  } catch (error) {
    console.error(error)
  }
}

// 切换当前模型供应商
async function handleSwitchProvider(providerId) {
  try {
    await activateModelProvider(providerId)
    activeProviderId.value = providerId
    const provider = providerList.value.find(item => item.id === providerId)
    ElMessage.success(`已切换到 ${provider?.name || '当前模型'}`)
  } catch (error) {
    console.error(error)
    ElMessage.error('切换失败')
  }
}

// 根据地址参数恢复知识库
function restoreKbFromQuery() {
  const queryKbId = route.query.kbId
  if (queryKbId) {
    selectedKbId.value = Number(queryKbId)
  }
}

// 加载本地会话列表
function loadSessionList() {
  try {
    const rawSessions = localStorage.getItem(sessionStorageKey)
    sessionList.value = rawSessions ? JSON.parse(rawSessions) : []
  } catch {
    sessionList.value = []
  }
}

// 保存本地会话列表
function saveSessionList() {
  localStorage.setItem(sessionStorageKey, JSON.stringify(sessionList.value))
}

// 确保当前存在会话
function createSessionIfNeeded() {
  if (sessionId.value) {
    return
  }
  if (sessionList.value.length > 0) {
    loadSession(sessionList.value[0].id)
    return
  }
  startNewSession()
}

// 恢复当前会话
async function restoreCurrentSession() {
  const savedSessionId = localStorage.getItem(currentSessionStorageKey)
  if (savedSessionId) {
    localStorage.removeItem(currentSessionStorageKey)
    await loadSessionAndPollIfNeeded(savedSessionId)
    return
  }
  createSessionIfNeeded()
}

// 加载会话并检查是否需要轮询
async function loadSessionAndPollIfNeeded(targetSessionId) {
  sessionId.value = targetSessionId
  await reloadMessages(targetSessionId)
  scrollToBottom()
  checkAndStartPolling()
}

// 重新加载会话消息
async function reloadMessages(targetSessionId) {
  try {
    const res = await getChatHistory(targetSessionId)
    messages.value = (res.data || []).map(item => ({
      id: item.id || createMessageId(),
      role: item.role,
      // 清理历史消息中已经保存的内部来源标记
      content: cleanSourceMarkers(item.content || ''),
      // 反馈状态回显：历史接口附带每条消息的点赞状态
      _feedback: item.helpful === 0 || item.helpful === 1 ? item.helpful : null
    }))
  } catch (error) {
    console.error(error)
  }
}

// 检查是否需要轮询等待回答
function checkAndStartPolling() {
  if (messages.value.length === 0) {
    waitingForResponse.value = false
    loading.value = false
    return
  }
  const lastMsg = messages.value[messages.value.length - 1]
  if (lastMsg.role === 'user' && isCurrentSessionPending()) {
    waitingForResponse.value = true
    loading.value = true
    startPolling()
    return
  }
  waitingForResponse.value = false
  loading.value = false
  stopPolling()
}

// 启动历史消息轮询
function startPolling() {
  stopPolling()
  pollingTimer = setInterval(async () => {
    if (!sessionId.value) {
      stopPolling()
      return
    }
    // 第1步：上一轮请求还没返回就跳过这一轮，避免请求堆叠和结果乱序
    if (pollingInFlight) {
      return
    }
    pollingInFlight = true
    try {
      const res = await getChatHistory(sessionId.value)
      const serverMessages = (res.data || []).map(item => ({
        id: item.id || createMessageId(),
        role: item.role,
        // 清理轮询返回消息中已经保存的内部来源标记
        content: cleanSourceMarkers(item.content || '')
      }))
      // 第2步：按 id 合并，不用轮询结果整体覆盖本地消息（否则引用、置信度、点赞状态全丢）
      const changed = mergePolledMessages(serverMessages)
      if (changed) {
        waitingForResponse.value = false
        loading.value = false
        stopPolling()
        scheduleScrollToBottom()
        mascot.playAction('idea')   // 回答送达：灵光一闪
      }
    } catch (error) {
      console.error('Polling error:', error)
    } finally {
      // 第3步：无论成功失败都要复位，否则一次异常会让轮询永久停摆
      pollingInFlight = false
    }
  }, 2000)
}

// 合并轮询结果：已有本地消息只补缺失的 content，本地的 _citations / _answerMeta / _feedback
// 等附加字段一律保留；本地没有的消息才追加。返回 true 表示本轮确有新内容
// （语义等价于原来的「服务端消息比本地多」）
function mergePolledMessages(serverMessages) {
  let changed = false
  const matched = new Set()   // 本轮已对齐过的本地消息，避免两条服务端消息配到同一条
  const localById = new Map(messages.value.map(item => [item.id, item]))
  serverMessages.forEach((serverMsg, index) => {
    // 第1步：优先按消息 id 对齐
    let local = localById.get(serverMsg.id)
    if (local && matched.has(local)) {
      local = null
    }
    // 第2步：本次会话里前端生成的消息 id 与服务端不一致，按相同下标 + 相同角色兜底对齐
    if (!local) {
      const candidate = messages.value[index]
      if (candidate && !matched.has(candidate) && candidate.role === serverMsg.role) {
        local = candidate
      }
    }
    if (local) {
      matched.add(local)
      // 第3步：id 不一致（本地临时 id）时改用服务端 id，后续点赞/反馈才能对上后端记录；
      // 仍在流式输出的消息不改 id，避免正在按 id 找目标的增量写入找不到消息
      if (local.id !== serverMsg.id && !local._streaming) {
        local.id = serverMsg.id
      }
      // 第4步：只补本地还空着的内容，已有的流式增量不覆盖；附加字段保持本地值不动
      if (!local.content && serverMsg.content) {
        local.content = serverMsg.content
        local._streaming = false
        changed = true
      }
      return
    }
    // 第5步：本地没有这条消息才追加
    messages.value.push(serverMsg)
    matched.add(serverMsg)
    changed = true
  })
  return changed
}

// 停止历史消息轮询
function stopPolling() {
  if (pollingTimer) {
    clearInterval(pollingTimer)
    pollingTimer = null
  }
}

// 新建会话
function startNewSession() {
  sessionId.value = Date.now()
  messages.value = []
  loading.value = false
  waitingForResponse.value = false
  clearPendingChatSession()
  stopPolling()
  upsertSession('新对话')
  scrollToBottom()
}

// 新增或更新会话摘要
function upsertSession(title) {
  const existSession = sessionList.value.find(item => item.id === sessionId.value)
  if (existSession) {
    existSession.title = title || existSession.title
    existSession.updatedAt = Date.now()
  } else {
    sessionList.value.unshift({ id: sessionId.value, title: title || '新对话', updatedAt: Date.now() })
  }
  sessionList.value = sessionList.value.slice(0, 30)
  saveSessionList()
}

// 加载指定历史会话
async function loadSession(targetSessionId) {
  if (loading.value) {
    ElMessage.warning('当前回答还在生成中，请稍后再切换对话')
    return
  }
  sessionId.value = targetSessionId
  messages.value = []
  waitingForResponse.value = false
  stopPolling()
  try {
    await reloadMessages(targetSessionId)
    checkAndStartPolling()
  } catch (error) {
    console.error(error)
    ElMessage.error('历史对话加载失败')
  }
}

// 删除历史会话
async function handleDeleteSession(session) {
  if (loading.value) {
    ElMessage.warning('当前回答还在生成中，请稍后再操作')
    return
  }
  try {
    await ElMessageBox.confirm('确定要删除这个对话吗？', '删除确认', {
      confirmButtonText: '删除',
      cancelButtonText: '取消',
      type: 'warning'
    })
  } catch {
    return
  }
  sessionList.value = sessionList.value.filter(item => item.id !== session.id)
  saveSessionList()
  if (session.id === sessionId.value) {
    sessionId.value = null
    messages.value = []
    createSessionIfNeeded()
  }
  try {
    await deleteSession(session.id)
  } catch (error) {
    console.error(error)
  }
  ElMessage.success('对话已删除')
}

// 使用快捷问题
function useQuickQuestion(question) {
  inputMessage.value = question
}

// 处理 Enter 发送
function handleEnter(e) {
  e.preventDefault()
  sendMessage()
}

// 发送聊天消息
async function sendMessage() {
  if (!inputMessage.value.trim() || loading.value) {
    return
  }
  createSessionIfNeeded()
  const userMessage = inputMessage.value.trim()
  inputMessage.value = ''
  messages.value.push({ id: createMessageId(), role: 'user', content: userMessage })
  upsertSession(userMessage.slice(0, 24))
  loading.value = true
  waitingForResponse.value = false
  savePendingChatSession()
  const assistantMessage = {
    id: createMessageId(),
    role: 'assistant',
    content: '',
    statusText: '正在连接大模型',
    _streaming: true
  }
  messages.value.push(assistantMessage)
  mascot.startReading()   // 回答开始生成：它凑过来盯着内容区读
  try {
    const response = await createStreamRequest(userMessage)
    if (!response.ok || !response.body) {
      throw new Error('聊天服务暂时不可用')
    }
    await readStream(response, assistantMessage.id)
  } catch (error) {
    if (error.name === 'AbortError') {
      return
    }
    console.error(error)
    updateAssistantMessage(assistantMessage.id, { content: '', _failed: true, _failReason: error.message || '发送失败' })
    ElMessage.error(error.message || '发送失败，请重试')
  } finally {
    mascot.stopReading()   // 读完/中断：回位
    updateAssistantMessage(assistantMessage.id, { _streaming: false, statusText: '' })
    loading.value = false
    abortController.value = null
    clearPendingChatSession()
  }
}

// 记录当前会话正在等待模型回答
function savePendingChatSession() {
  if (!sessionId.value) {
    return
  }
  localStorage.setItem(pendingChatSessionKey, JSON.stringify({
    sessionId: sessionId.value,
    startedAt: Date.now()
  }))
}

// 清理当前会话的等待回答标记
function clearPendingChatSession() {
  const pending = readPendingChatSession()
  if (!pending || !sessionId.value || String(pending.sessionId) === String(sessionId.value)) {
    localStorage.removeItem(pendingChatSessionKey)
  }
}

// 判断当前会话是否仍然处于有效等待期
function isCurrentSessionPending() {
  const pending = readPendingChatSession()
  if (!pending || String(pending.sessionId) !== String(sessionId.value)) {
    return false
  }
  if (Date.now() - Number(pending.startedAt || 0) > pendingChatTimeoutMs) {
    localStorage.removeItem(pendingChatSessionKey)
    return false
  }
  return true
}

// 读取等待回答标记
function readPendingChatSession() {
  try {
    const raw = localStorage.getItem(pendingChatSessionKey)
    return raw ? JSON.parse(raw) : null
  } catch {
    localStorage.removeItem(pendingChatSessionKey)
    return null
  }
}

// 创建流式聊天请求
function createStreamRequest(userMessage) {
  const controller = new AbortController()
  abortController.value = controller
  const token = localStorage.getItem('token')
  return fetch('/api/chat/stream', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${token}` },
    body: JSON.stringify({ message: userMessage, kbId: selectedKbId.value, sessionId: sessionId.value }),
    signal: controller.signal
  })
}

// 读取流式响应
async function readStream(response, assistantMessageId) {
  const reader = response.body.getReader()
  const decoder = new TextDecoder('utf-8')
  let buffer = ''
  while (true) {
    const { done, value } = await reader.read()
    if (done) {
      break
    }
    buffer += decoder.decode(value, { stream: true })
    buffer = await consumeSseBuffer(buffer, assistantMessageId)
  }
  if (buffer.trim()) {
    await appendSsePart(buffer, assistantMessageId)
  }
}

// 消费 SSE 缓存
async function consumeSseBuffer(buffer, assistantMessageId) {
  const parts = buffer.replace(/\r\n/g, '\n').split('\n\n')
  const rest = parts.pop() || ''
  for (const part of parts) {
    await appendSsePart(part, assistantMessageId)
  }
  return rest
}

// 追加单个 SSE 事件
async function appendSsePart(part, assistantMessageId) {
  const eventName = getSseEventName(part)
  const text = getSseDataText(part)
  if (eventName === 'status') {
    updateAssistantMessage(assistantMessageId, { statusText: text || '正在处理' })
    await waitForRenderFrame()
    return
  }
  if (eventName === 'citation') {
    try {
      updateAssistantMessage(assistantMessageId, { _citations: JSON.parse(text) || [] })
    } catch {
      updateAssistantMessage(assistantMessageId, { _citations: [] })
    }
    return
  }
  if (eventName === 'answer-meta') {
    try {
      const meta = JSON.parse(text)
      updateAssistantMessage(assistantMessageId, {
        _answerMeta: meta,
        _grounded: meta.grounded === true
      })
      if (meta && meta.grounded === false) {
        mascot.playAction('shrug')   // 拒答：耸肩——知识库里没有依据，不瞎编
        mascot.speak('知识库里没有依据，我不瞎编～')
      }
    } catch {
      updateAssistantMessage(assistantMessageId, { _grounded: false })
    }
    return
  }
  if (eventName === 'open' || eventName === 'done' || text === '[DONE]') {
    return
  }
  if (eventName === 'followups') {
    try {
      const list = JSON.parse(text)
      updateAssistantMessage(assistantMessageId, { followups: Array.isArray(list) ? list : [] })
    } catch (error) {
      // 建议解析失败不影响回答展示
    }
    return
  }
  if (eventName === 'error') {
    updateAssistantMessage(assistantMessageId, { content: '', _failed: true, _failReason: text || '聊天服务异常' })
    await waitForRenderFrame()
    return
  }
  if (text) {
    appendAssistantContent(assistantMessageId, text)
    await waitForRenderFrame()
  }
}

// 打开引用对应的原文文件
async function openCitation(citation) {
  if (!citation?.fileId) {
    return
  }
  try {
    const response = await fetch(getFilePreviewUrl(citation.fileId), {
      headers: { Authorization: `Bearer ${localStorage.getItem('token')}` }
    })
    if (!response.ok) {
      throw new Error('文件预览失败')
    }
    const blob = await response.blob()
    const url = URL.createObjectURL(blob)
    window.open(url, '_blank', 'noopener,noreferrer')
    window.setTimeout(() => URL.revokeObjectURL(url), 60 * 1000)
  } catch (error) {
    console.error(error)
    ElMessage.error('文件预览失败，请尝试下载原文')
  }
}

// 按消息 id 找下标：从后往前找，流式回答永远在末尾，命中更快
function findMessageIndex(messageId) {
  for (let i = messages.value.length - 1; i >= 0; i -= 1) {
    if (messages.value[i].id === messageId) {
      return i
    }
  }
  return -1
}

// 更新助手消息
// 原实现用 messages.value.map(...) 重建整个数组，每收一个 token 就复制一遍全部消息（O(n²)）；
// 现在改为原地改写最后一条 assistant 消息对象的字段，数组引用不变
function updateAssistantMessage(messageId, patchData) {
  const index = findMessageIndex(messageId)
  if (index < 0) {
    return
  }
  // 第1步：只有需要整段替换的字段（如 answer_reset / error 的 content）才走这里，
  // 直接改对象字段即可保持响应式，引用、置信度、反馈等其它字段不受影响
  Object.assign(messages.value[index], patchData)
}

// 追加助手消息内容
function appendAssistantContent(messageId, text) {
  if (!text) {
    return
  }
  const index = findMessageIndex(messageId)
  if (index < 0) {
    return
  }
  // 第1步：原地累加增量，触发的是 content 的 setter，而不是整个数组被替换
  const target = messages.value[index]
  target.content = `${target.content || ''}${text}`
  // 第2步：状态文案同步回「生成中」（与原来的整条替换行为保持一致）
  target.statusText = '生成中'
}

// 等待浏览器完成一帧渲染
async function waitForRenderFrame() {
  await nextTick()
  await new Promise(resolve => requestAnimationFrame(resolve))
  await new Promise(resolve => window.setTimeout(resolve, 0))
}

// 获取 SSE 事件名
function getSseEventName(part) {
  const line = part.split('\n').find(item => item.startsWith('event:'))
  return line ? line.substring(6).trim() : 'message'
}

// 获取 SSE 数据文本
function getSseDataText(part) {
  return part
    .split('\n')
    .filter(item => item.startsWith('data:'))
    .map(item => item.substring(5).replace(/^ /, ''))
    .join('\n')
}

// 渲染 Markdown 内容
function renderMarkdown(content) {
  if (!content) {
    return ''
  }
  return md.render(cleanSourceMarkers(content))
}

// 清理内部校验用的来源标记，避免历史消息和导出内容继续展示
function cleanSourceMarkers(content) {
  if (!content) {
    return ''
  }
  // 同时兼容旧消息中的方括号标记和已经生成的 source-tag HTML 文本
  return content
    .replace(/\[来源:\s*[^\]]+\]/g, '')
    .replace(/<span\s+class=["']source-tag["'][^>]*>来源:\s*[\s\S]*?<\/span>/gi, '')
    .trim()
}

// 滚动到底部（真正执行滚动的一步）
function scrollToBottom() {
  nextTick(() => {
    if (messagesRef.value) {
      messagesRef.value.scrollTop = messagesRef.value.scrollHeight
    }
  })
}

// 节流版滚动：流式回答每个 token 都会触发一次，这里限制为最多 80ms 滚一次
// （首帧立即滚，尾随再补一次），避免逐 token 同步滚动引起的强制布局与卡顿
function scheduleScrollToBottom() {
  const now = Date.now()
  const wait = Math.max(0, SCROLL_THROTTLE_MS - (now - lastScrollAt))
  if (wait === 0) {
    lastScrollAt = now
    scrollToBottom()
    return
  }
  if (scrollThrottleTimer) {
    return
  }
  scrollThrottleTimer = window.setTimeout(() => {
    scrollThrottleTimer = null
    lastScrollAt = Date.now()
    scrollToBottom()
  }, wait)
}

// 清理滚动节流的尾随定时器（页面卸载时调用，避免卸载后仍触发滚动）
function stopScrollThrottle() {
  if (scrollThrottleTimer) {
    window.clearTimeout(scrollThrottleTimer)
    scrollThrottleTimer = null
  }
}

// 创建前端消息编号
function createMessageId() {
  return `${Date.now()}-${Math.random().toString(16).slice(2)}`
}

// 格式化会话时间
function formatSessionTime(value) {
  if (!value) {
    return ''
  }
  return new Date(value).toLocaleString()
}

// 执行召回测试
async function handleRecallTest() {
  if (!recallQuery.value.trim() || !selectedKbId.value) {
    return
  }
  recallLoading.value = true
  recallResults.value = []
  try {
    const res = await recallTest(recallQuery.value, selectedKbId.value)
    recallResults.value = res.data || []
    if (recallResults.value.length === 0) {
      ElMessage.info('未召回任何文本块')
    }
  } catch (error) {
    console.error(error)
    ElMessage.error('召回测试失败')
  } finally {
    recallLoading.value = false
  }
}

// 获取召回命中方式文案
function getMatchTypeText(matchType) {
  // 第1步：按后端诊断类型显示中文文案
  const texts = { VECTOR: '向量', KEYWORD: '关键词', HYBRID: '混合命中' }
  return texts[matchType] || '召回'
}

// 获取召回命中方式标签样式
function getMatchTypeTag(matchType) {
  // 第1步：混合命中强调成功色，关键词使用警告色
  const types = { VECTOR: 'info', KEYWORD: 'warning', HYBRID: 'success' }
  return types[matchType] || 'info'
}

// 格式化向量相似度
function formatRecallScore(score) {
  // 第1步：将0到1的相似度显示为百分比
  return `${(Number(score) * 100).toFixed(1)}%`
}

// 提交答案反馈
async function handleFeedback(msg, helpful) {
  try {
    // 再点一次已选中的大拇指 = 取消反馈（helpful 传 null，后端删除记录）
    const cancelling = msg._feedback === helpful
    await submitFeedback(msg.id, cancelling ? null : helpful, 'CHAT')
    msg._feedback = cancelling ? null : helpful
    ElMessage.success(cancelling ? '已取消反馈' : (helpful === 1 ? '感谢反馈' : '感谢反馈，我们会改进'))
    // 吉祥物反应：点赞开心鼓掌（心情+），点踩蹲角落画圈圈（心情-）
    if (!cancelling) {
      if (helpful === 1) {
        mascot.playAction('clap')
        mascot.applyMood(8)
        mascot.speak('谢谢夸奖，我会继续努力的～')
      } else {
        mascot.playAction('sulk')
        mascot.applyMood(-12)
        mascot.speak('呜…我会改进的')
      }
    }
  } catch (error) {
    console.error(error)
    ElMessage.error('反馈提交失败')
  }
}

// 展开或收起回答的引用切片列表
function toggleCitations(msg) {
  msg._showCitations = !msg._showCitations
}

// 点击追问建议：作为新的用户消息发送
function askFollowup(question) {
  if (loading.value || !question) {
    return
  }
  inputMessage.value = question
  sendMessage()
}

// 重试失败回答：撤掉失败气泡和对应的用户消息，按原问题重新发送
function retryFailed(msg) {
  if (loading.value) {
    return
  }
  const index = messages.value.indexOf(msg)
  if (index < 0) {
    return
  }
  let question = ''
  for (let i = index - 1; i >= 0; i--) {
    if (messages.value[i].role === 'user') {
      question = messages.value[i].content
      break
    }
  }
  if (!question) {
    return
  }
  // 同时移除失败回答与其用户消息，重发后视觉顺序不变
  messages.value.splice(index, 1)
  for (let i = index - 1; i >= 0; i--) {
    if (messages.value[i].role === 'user' && messages.value[i].content === question) {
      messages.value.splice(i, 1)
      break
    }
  }
  inputMessage.value = question
  sendMessage()
}

// 导出当前对话
function exportChat() {
  if (messages.value.length === 0) {
    return
  }
  let text = '# 对话导出\n\n'
  for (const msg of messages.value) {
    const role = msg.role === 'user' ? '用户' : 'AI 助手'
    // 导出前清理内部来源标记，避免把实现细节写入文件
    text += `## ${role}\n\n${cleanSourceMarkers(msg.content)}\n\n---\n\n`
  }
  const blob = new Blob([text], { type: 'text/markdown;charset=utf-8' })
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = `对话导出_${new Date().toLocaleDateString()}.md`
  a.click()
  URL.revokeObjectURL(url)
  ElMessage.success('导出成功')
}
</script>

<style scoped>
/* 聊天页：左历史窗格（平面、细线分隔）│ 对话流（日志条目）│ 底部输入 */
.chat-page {
  display: grid;
  grid-template-columns: 300px minmax(0, 1fr);
  height: calc(100vh - 53px);
  min-height: 720px;
  overflow: hidden;
}

.chat-sidebar {
  width: 264px;
  flex: 0 0 auto;
  display: flex;
  flex-direction: column;
  gap: 14px;
  padding: 24px 24px 24px 0;
  border-right: 1px solid var(--rule);
  overflow-y: auto;
  background: transparent;
  border-radius: 0;
  box-shadow: none;
}

.mode-card {
  border-radius: 0;
  padding: 12px 14px;
  background: transparent;
  border-left: 2px solid var(--signal);
  box-shadow: none;
}

.mode-card span,
.mode-card small {
  color: var(--text-3);
  font-size: 11.5px;
}

.mode-card strong {
  display: block;
  margin: 2px 0 4px;
  color: var(--amber-hi);
  font-size: 13.5px;
  font-weight: 600;
}

.side-button {
  width: 100%;
  margin-top: 12px;
}

.side-button.primary {
  color: #140503;
  border: none;
  background: var(--signal);
}

.recall-results {
  max-height: 320px;
  margin-top: 12px;
  overflow-y: auto;
}

.recall-item {
  padding: 10px 12px;
  margin-bottom: 8px;
  border: 1px solid var(--line);
  border-radius: 0;
  background: rgba(242, 239, 233, 0.02);
}

.recall-item-header {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 6px;
  color: var(--text-3);
  font-family: var(--font-mono);
  font-size: 11px;
}

.recall-source {
  margin-bottom: 6px;
  color: var(--amber-hi);
  font-size: 12.5px;
  font-weight: 600;
}

.recall-query {
  margin-bottom: 6px;
  overflow: hidden;
  color: var(--text-3);
  font-size: 12px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.recall-item-content {
  max-height: 110px;
  overflow: hidden;
  color: var(--text-2);
  font-size: 12.5px;
  line-height: 1.6;
}

.empty-history {
  margin-top: 12px;
  color: var(--text-3);
  font-size: 12.5px;
}

.history-item {
  display: flex;
  align-items: center;
  margin-top: 2px;
  border-left: 2px solid transparent;
  border-radius: 0;
}

.history-item.active,
.history-item:hover {
  background: var(--hover);
}

.history-item.active {
  border-left-color: var(--amber);
}

.history-item.active .history-item-main span {
  color: var(--amber-hi);
}

.history-item-main {
  flex: 1;
  min-width: 0;
  padding: 8px 10px;
  border: none;
  color: var(--text-2);
  background: transparent;
  text-align: left;
  font-size: 13px;
}

.history-item-main span,
.history-item-main small {
  display: block;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.history-item-main small {
  margin-top: 2px;
  color: var(--text-3);
  font-family: var(--font-mono);
  font-size: 11px;
}

.history-item-delete {
  width: 34px;
  display: grid;
  place-items: center;
  color: var(--text-3);
  cursor: pointer;
}

.history-item-delete:hover {
  color: var(--bad);
}

.quick-question {
  display: block;
  width: 100%;
  margin-top: 6px;
  padding: 2px 0;
  border: none;
  color: var(--amber-hi);
  background: none;
  text-align: left;
  font-size: 12.5px;
}

.quick-question:hover {
  text-decoration: underline;
  text-underline-offset: 4px;
}

.chat-main {
  display: flex;
  flex-direction: column;
  min-width: 0;
  min-height: 0;
  overflow: hidden;
}

.chat-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 18px;
  padding: 14px 28px;
  border-bottom: 1px solid var(--line);
  background: rgba(10, 12, 18, 0.4);
}

.chat-head-main {
  display: flex;
  align-items: baseline;
  gap: 14px;
  min-width: 0;
}

.chat-header h1 {
  color: var(--text-1);
  font-size: 15px;
  font-weight: 600;
  white-space: nowrap;
}

.chat-header .meta {
  overflow: hidden;
  color: var(--text-3);
  font-family: var(--font-mono);
  font-size: 11px;
  letter-spacing: 0.06em;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.chat-messages {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  padding: 20px 0 26px;
}

.empty-chat {
  min-height: 360px;
  display: grid;
  place-items: center;
  align-content: center;
  gap: 6px;
  text-align: center;
  color: var(--text-3);
  font-size: 13px;
}

.empty-chat .el-icon {
  color: var(--amber);
  font-size: 40px;
}

.empty-chat h2 {
  color: var(--text-2);
  font-size: 16px;
  font-weight: 600;
}

/* 消息：手稿式直排 —— 用户带左缘朱砂短线，AI 直排无容器 */
.message-item {
  margin: 0 32px 26px;
}

.message-row {
  position: relative;
  display: flex;
  gap: 10px;
}

.message-avatar {
  display: none;
}

.message-content {
  flex: 1;
  min-width: 0;
  max-width: 820px;
}

/* 说话人标注：mono 小字（Q / A 账目行） */
.message-meta {
  display: flex;
  gap: 12px;
  margin-bottom: 5px;
}

.message-meta .who {
  color: var(--text-3);
  font-family: var(--font-mono);
  font-size: 10px;
  letter-spacing: 0.2em;
}

.message-meta .status {
  color: var(--text-3);
  font-family: var(--font-mono);
  font-size: 10px;
  letter-spacing: 0.06em;
}

/* 用户条目：左缘朱砂短线 */
.message-item.user {
  display: block;
}

.message-item.user .message-row {
  border-left: 2px solid var(--signal);
  padding-left: 14px;
}

.message-item.user .message-content {
  max-width: none;
}

.message-item.user .message-text {
  border-left: none;
  padding-left: 0;
}

.message-text {
  border-left: none;
  padding-left: 0;
}

.message-text {
  color: var(--text-1);
  font-size: 14px;
  line-height: 1.7;
  word-break: break-word;
}

.streaming-text {
  white-space: pre-wrap;
}

.message-text :deep(p) {
  margin: 0 0 10px;
}

.message-text :deep(p:last-child) {
  margin-bottom: 0;
}

.message-text :deep(ul),
.message-text :deep(ol) {
  padding-left: 20px;
}

.message-text :deep(code) {
  padding: 2px 6px;
  border-radius: 0;
  background: rgba(242, 239, 233, 0.06);
  font-family: var(--font-mono);
  font-size: 13px;
}

.message-text :deep(pre) {
  padding: 12px 14px;
  border: 1px solid var(--line);
  border-radius: 0;
  background: rgba(242, 239, 233, 0.04);
  overflow-x: auto;
}

.message-text :deep(.source-tag) {
  display: inline-block;
  padding: 1px 7px;
  border: 1px solid var(--amber-line-dim);
  border-radius: 0;
  color: var(--amber-hi);
  background: rgba(236, 233, 225, 0.05);
  font-family: var(--font-mono);
  font-size: 11.5px;
  font-weight: 500;
}

.loading-card {
  display: inline-flex;
  align-items: center;
  gap: 10px;
  color: var(--text-3);
}

.spinner {
  width: 14px;
  height: 14px;
  border: 2px solid rgba(236, 233, 225, 0.18);
  border-top-color: var(--amber);
  border-radius: 50%;
  animation: spin 0.8s linear infinite;
}

@keyframes spin {
  to {
    transform: rotate(360deg);
  }
}

.answer-evidence-meta {
  display: flex;
  gap: 18px;
  flex-wrap: wrap;
  margin-top: 12px;
  padding-top: 10px;
  border-top: 1px solid var(--rule);
  font-family: var(--font-mono);
  font-size: 11px;
  color: var(--text-2);
  letter-spacing: 0.06em;
}

.evidence-pill::before {
  content: "·";
  margin-right: 18px;
  color: var(--text-3);
}

.evidence-pill:first-child::before {
  content: none;
}

.evidence-pill.confidence-high {
  color: var(--text-1);
}

.citation-block {
  margin-top: 10px;
  border-top: 1px solid var(--rule);
}

.citation-toggle {
  display: inline-block;
  padding: 9px 0 7px;
  border: 0;
  background: none;
  color: var(--text-2);
  font-size: 12px;
  font-family: var(--font-mono);
  letter-spacing: 0.14em;
}

.citation-toggle:hover {
  color: var(--text-1);
}

.citation-toggle .cite-caret {
  color: var(--text-3);
}

.citation-item {
  display: flex;
  gap: 14px;
  align-items: baseline;
  padding: 8px 0;
  border-top: 1px solid rgba(236, 233, 225, 0.06);
  background: none;
  border-radius: 0;
}

.followup-chip {
  padding: 2px 0;
  border: 0;
  border-bottom: 1px solid var(--rule-strong);
  border-radius: 0;
  background: none;
  color: var(--text-2);
  font-size: 12.5px;
}

.followup-chip::before {
  content: none;
}

.followup-chip:hover {
  color: var(--signal);
  border-bottom-color: var(--signal);
  background: none;
}

.chat-input-panel {
  position: relative;
  display: flex;
  flex-direction: column;
  padding: 14px 28px 12px;
  border-top: 1px solid var(--line);
  background: rgba(10, 12, 18, 0.4);
  min-height: 110px;
}

.chat-input-panel :deep(.el-textarea) {
  flex: 1;
  min-height: 0;
}

.chat-input-panel :deep(.el-textarea__inner) {
  height: 100%;
  min-height: 0;
  padding: 4px 0 46px;
  border: none;
  resize: none;
  color: var(--text-1);
  background: transparent;
  box-shadow: none;
  font-size: 14px;
  line-height: 1.6;
}

.chat-input-panel :deep(.el-textarea__inner:focus) {
  box-shadow: none;
}

.chat-input-panel :deep(.el-textarea__inner::placeholder) {
  color: var(--text-3);
}

.input-toolbar {
  position: absolute;
  left: 28px;
  right: 28px;
  bottom: 14px;
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 10px;
  z-index: 5;
}

.toolbar-right {
  display: flex;
  align-items: center;
  gap: 8px;
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

.model-chip {
  width: auto;
  min-width: 150px;
  max-width: 300px;
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

/* 分隔条：按住上下拖动可调整输入区高度 */
.chat-resize-handle {
  flex: 0 0 auto;
  height: 12px;
  margin-top: -6px;
  display: grid;
  place-items: center;
  cursor: ns-resize;
  position: relative;
  z-index: 2;
}

.chat-resize-handle::after {
  content: "";
  width: 44px;
  height: 3px;
  border-radius: 0;
  background: rgba(242, 239, 233, 0.14);
  transition:
    width 0.2s var(--motion-curve),
    background 0.2s var(--motion-curve);
}

.chat-resize-handle:hover::after,
.chat-resize-handle:active::after {
  width: 84px;
  background: rgba(236, 233, 225, 0.72);
}

/* 发送按钮：mono 文字钮「发送 ↵」 */
.send-button {
  height: 30px;
  min-height: 30px;
  padding: 0 12px;
  border-radius: 0;
  font-family: var(--font-mono);
  font-size: 12.5px;
  font-weight: 600;
  letter-spacing: 0.08em;
}

/* 发送按钮：平时低调中性灰，悬停点亮为琥珀 */
.chat-input-panel .send-button:not(.is-disabled) {
  color: var(--amber-hi);
  border: 1px solid var(--amber-line-dim);
  background: transparent;
}

.chat-input-panel .send-button:not(.is-disabled):hover,
.chat-input-panel .send-button:not(.is-disabled):focus {
  color: #140503;
  border-color: transparent;
  background: var(--signal);
}

.chat-input-panel .send-button.is-disabled {
  color: rgba(242, 239, 233, 0.26);
  border: 1px solid var(--line);
  background: transparent;
}

@media (max-width: 1020px) {
  .chat-page {
    grid-template-columns: 1fr;
    height: auto;
    min-height: 0;
    overflow: visible;
  }

  .chat-main {
    min-height: 720px;
  }
}

@media (max-width: 640px) {
  .chat-header,
  .chat-input-panel {
    flex-direction: column;
  }

  .message-item {
    margin: 0 16px 4px;
  }
}
</style>
