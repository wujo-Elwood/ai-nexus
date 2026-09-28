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
        <el-select
          v-model="activeProviderId"
          class="side-control"
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
        <div>
          <span class="eyebrow">AI Chat</span>
          <h1>智能问答</h1>
          <p>支持流式输出、知识库召回、历史对话和答案反馈。</p>
        </div>
        <el-tag :type="selectedKbId ? 'success' : 'info'" round>
          {{ selectedKbName }}
        </el-tag>
      </div>

      <div ref="messagesRef" class="chat-messages">
        <div v-if="messages.length === 0 && !loading" class="empty-chat">
          <el-icon><ChatDotRound /></el-icon>
          <h2>开始一次清晰的问答</h2>
          <p>可以先选择知识库，也可以直接向大模型提问。</p>
        </div>

        <article v-for="msg in messages" :key="msg.id" class="message-item" :class="msg.role">
          <div class="message-avatar">
            <el-icon v-if="msg.role === 'user'"><User /></el-icon>
            <el-icon v-else><Monitor /></el-icon>
          </div>
          <div class="message-content">
            <div class="message-meta">
              <span>{{ msg.role === 'user' ? '你' : 'AI 助手' }}</span>
              <span v-if="msg._streaming">{{ msg.statusText || '生成中' }}</span>
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
            <div v-if="msg.role === 'assistant' && !msg._streaming && msg._grounded && msg._citations?.length" class="citation-list">
              <div class="citation-title">参考资料</div>
              <div v-for="citation in msg._citations" :key="`${msg.id}-${citation.chunkId}`" class="citation-item">
                <div class="citation-info">
                  <strong>{{ citation.fileName }}</strong>
                  <span>第 {{ Number(citation.chunkIndex ?? 0) + 1 }} 段</span>
                  <el-tag size="small" effect="plain">{{ getMatchTypeText(citation.matchType) }}</el-tag>
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
            </div>
            <div v-if="msg.role === 'assistant' && !msg._streaming && msg.id" class="feedback-bar">
              <el-button size="small" :type="msg._feedback === 1 ? 'success' : 'default'" text @click="handleFeedback(msg, 1)">
                有帮助
              </el-button>
              <el-button size="small" :type="msg._feedback === 0 ? 'danger' : 'default'" text @click="handleFeedback(msg, 0)">
                无帮助
              </el-button>
            </div>
          </div>
        </article>

        <article v-if="waitingForResponse" class="message-item assistant">
          <div class="message-avatar">
            <el-icon><Monitor /></el-icon>
          </div>
          <div class="message-content">
            <div class="message-meta">
              <span>AI 助手</span>
              <span>生成中</span>
            </div>
            <div class="message-text loading-card">
              <span class="spinner"></span>
              <span>模型正在思考，请稍等...</span>
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
    </section>
  </div>
</template>

<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { ChatDotRound, Delete, Download, Monitor, Promotion, Refresh, User } from '@element-plus/icons-vue'
import MarkdownIt from 'markdown-it'
import { getKbList } from '../../api/kb'
import { deleteSession, getChatHistory, recallTest, submitFeedback } from '../../api/chat'
import { getFilePreviewUrl } from '../../api/file'
import { activateModelProvider, getModelProviderList } from '../../api/modelProvider'

const route = useRoute()
const md = new MarkdownIt({ breaks: true, linkify: true })

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

const sessionStorageKey = 'rag_chat_sessions'
const pendingChatSessionKey = 'rag_chat_pending_session'
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
    localStorage.setItem('rag_current_session', String(sessionId.value))
  }
  if (abortController.value) {
    abortController.value.abort()
    abortController.value = null
  }
  clearPendingChatSession()
  loading.value = false
  waitingForResponse.value = false
  stopPolling()
  stopInputResize()
})

watch(messages, () => {
  scrollToBottom()
}, { deep: true })

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
  const savedSessionId = localStorage.getItem('rag_current_session')
  if (savedSessionId) {
    localStorage.removeItem('rag_current_session')
    await loadSessionAndPollIfNeeded(Number(savedSessionId))
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
      content: cleanSourceMarkers(item.content || '')
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
    try {
      const res = await getChatHistory(sessionId.value)
      const serverMessages = (res.data || []).map(item => ({
        id: item.id || createMessageId(),
        role: item.role,
        // 清理轮询返回消息中已经保存的内部来源标记
        content: cleanSourceMarkers(item.content || '')
      }))
      if (serverMessages.length > messages.value.length) {
        messages.value = serverMessages
        waitingForResponse.value = false
        loading.value = false
        stopPolling()
        scrollToBottom()
      }
    } catch (error) {
      console.error('Polling error:', error)
    }
  }, 2000)
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
    updateAssistantMessage(assistantMessage.id, { content: '发送失败，请稍后重试。' })
    ElMessage.error(error.message || '发送失败，请重试')
  } finally {
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
  if (!pending || !sessionId.value || Number(pending.sessionId) === Number(sessionId.value)) {
    localStorage.removeItem(pendingChatSessionKey)
  }
}

// 判断当前会话是否仍然处于有效等待期
function isCurrentSessionPending() {
  const pending = readPendingChatSession()
  if (!pending || Number(pending.sessionId) !== Number(sessionId.value)) {
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
    } catch {
      updateAssistantMessage(assistantMessageId, { _grounded: false })
    }
    return
  }
  if (eventName === 'open' || eventName === 'done' || text === '[DONE]') {
    return
  }
  if (eventName === 'error') {
    appendAssistantContent(assistantMessageId, text || '聊天服务异常')
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

// 更新助手消息
function updateAssistantMessage(messageId, patchData) {
  messages.value = messages.value.map(item => item.id === messageId ? { ...item, ...patchData } : item)
}

// 追加助手消息内容
function appendAssistantContent(messageId, text) {
  if (!text) {
    return
  }
  messages.value = messages.value.map(item =>
    item.id === messageId ? { ...item, content: `${item.content || ''}${text}`, statusText: '生成中' } : item
  )
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

// 滚动到底部
function scrollToBottom() {
  nextTick(() => {
    if (messagesRef.value) {
      messagesRef.value.scrollTop = messagesRef.value.scrollHeight
    }
  })
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
    await submitFeedback(msg.id, helpful)
    msg._feedback = helpful
    ElMessage.success(helpful === 1 ? '感谢反馈' : '感谢反馈，我们会改进')
  } catch (error) {
    console.error(error)
    ElMessage.error('反馈提交失败')
  }
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
.chat-page {
  display: grid;
  grid-template-columns: 360px minmax(0, 1fr);
  gap: 24px;
  height: calc(100vh - 94px);
  min-height: 760px;
  overflow: hidden;
}

.chat-sidebar {
  display: flex;
  flex-direction: column;
  gap: 16px;
  overflow-y: auto;
  padding-right: 4px;
}

.sidebar-panel,
.chat-main {
  border: 1px solid var(--line-color);
  border-radius: 12px;
  background: rgba(30, 31, 35, 0.76);
  backdrop-filter: blur(18px);
}

.sidebar-panel {
  padding: 20px;
}

.sidebar-panel h2 {
  color: var(--ink-color);
  font-size: 18px;
  font-weight: 850;
}

.sidebar-panel p {
  margin-top: 8px;
  color: var(--muted-color);
  line-height: 1.7;
}

.side-control {
  width: 100%;
  margin-top: 14px;
}

.mode-card {
  margin-top: 14px;
  padding: 16px;
  border: 1px solid rgba(229, 160, 68, 0.22);
  border-radius: 12px;
  background: rgba(229, 160, 68, 0.08);
}

.mode-card span,
.mode-card small {
  color: var(--muted-color);
}

.mode-card strong {
  display: block;
  margin-top: 8px;
  color: var(--ink-color);
  font-size: 18px;
}

.side-button {
  width: 100%;
  margin-top: 12px;
}

.side-button.primary {
  color: #0b0c0e;
  border: none;
  background: var(--primary-color);
}

.recall-results {
  max-height: 320px;
  margin-top: 12px;
  overflow-y: auto;
}

.recall-item,
.history-item,
.quick-question {
  border: 1px solid var(--line-color);
  border-radius: 10px;
  background: rgba(247, 245, 242, 0.04);
}

.recall-item {
  padding: 12px;
  margin-bottom: 10px;
}

.recall-item-header {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 8px;
  color: var(--muted-color);
  font-size: 12px;
}

.recall-source {
  margin-bottom: 6px;
  color: var(--ink-color);
  font-size: 13px;
  font-weight: 700;
}

.recall-query {
  margin-bottom: 8px;
  overflow: hidden;
  color: var(--muted-color);
  font-size: 12px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.recall-item-content {
  max-height: 110px;
  overflow: hidden;
  color: var(--ink-color);
  line-height: 1.7;
}

.empty-history {
  margin-top: 12px;
  color: var(--muted-color);
}

.history-item {
  display: flex;
  align-items: center;
  margin-top: 10px;
  overflow: hidden;
}

.history-item.active,
.history-item:hover {
  border-color: rgba(229, 160, 68, 0.46);
  background: rgba(229, 160, 68, 0.08);
}

.history-item-main {
  flex: 1;
  min-width: 0;
  padding: 12px 14px;
  border: none;
  color: var(--ink-color);
  background: transparent;
  text-align: left;
}

.history-item-main span,
.history-item-main small {
  display: block;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.history-item-main small {
  margin-top: 4px;
  color: var(--muted-color);
  font-size: 12px;
}

.history-item-delete {
  width: 42px;
  display: grid;
  place-items: center;
  color: var(--muted-color);
  cursor: pointer;
}

.history-item-delete:hover {
  color: var(--danger-color);
}

.quick-question {
  width: 100%;
  margin-top: 10px;
  padding: 12px 14px;
  color: var(--ink-color);
  text-align: left;
  font-weight: 700;
}

.quick-question:hover {
  color: var(--primary-dark);
  border-color: rgba(229, 160, 68, 0.46);
}

.chat-main {
  display: flex;
  flex-direction: column;
  min-width: 0;
  /* 父级 grid 固定高度；overflow 改为 visible 后必须显式 min-height:0，
     否则 grid item 的 min-height:auto 会被内容撑高，把底部输入框顶出视口 */
  min-height: 0;
  /* 不能裁切，否则全局"星云流光边框"的外扩云雾层（::after）会被裁掉底边一圈 */
  overflow: visible;
}

.chat-header {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: 18px;
  padding: 26px;
  border-bottom: 1px solid var(--line-color);
}

.chat-header h1 {
  color: var(--ink-color);
  font-size: 34px;
  line-height: 1.1;
  font-weight: 900;
}

.chat-header p {
  margin-top: 10px;
  color: var(--muted-color);
}

.chat-messages {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  padding: 24px;
  background: radial-gradient(circle at 8% 0%, rgba(229, 160, 68, 0.08), transparent 24%);
}

.empty-chat {
  min-height: 360px;
  display: grid;
  place-items: center;
  align-content: center;
  text-align: center;
  color: var(--muted-color);
}

.empty-chat .el-icon {
  margin-bottom: 18px;
  color: var(--primary-color);
  font-size: 58px;
}

.empty-chat h2 {
  margin-bottom: 8px;
  color: var(--ink-color);
  font-size: 26px;
}

.message-item {
  display: flex;
  gap: 12px;
  margin-bottom: 20px;
}

.message-item.user {
  flex-direction: row-reverse;
}

.message-avatar {
  width: 42px;
  height: 42px;
  display: grid;
  place-items: center;
  flex-shrink: 0;
  border: 1px solid rgba(229, 160, 68, 0.28);
  border-radius: 12px;
  color: var(--primary-dark);
  background: rgba(229, 160, 68, 0.1);
}

.message-content {
  max-width: min(76%, 840px);
}

.message-meta {
  display: flex;
  gap: 10px;
  margin-bottom: 7px;
  color: var(--muted-color);
  font-size: 12px;
  font-weight: 800;
}

.user .message-meta {
  justify-content: flex-end;
}

.message-text {
  padding: 15px 18px;
  border: 1px solid var(--line-color);
  border-radius: 14px;
  color: var(--ink-color);
  background: rgba(247, 245, 242, 0.05);
  line-height: 1.8;
  word-break: break-word;
}

.user .message-text {
  color: #0b0c0e;
  border-color: transparent;
  background: var(--primary-color);
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
  border-radius: 8px;
  background: rgba(229, 160, 68, 0.12);
}

.message-text :deep(.source-tag) {
  display: inline-block;
  padding: 2px 8px;
  border-radius: 999px;
  color: var(--primary-dark);
  background: rgba(229, 160, 68, 0.12);
  font-size: 12px;
  font-weight: 800;
}

.loading-card {
  display: inline-flex;
  align-items: center;
  gap: 10px;
  color: var(--muted-color);
}

.spinner {
  width: 16px;
  height: 16px;
  border: 2px solid rgba(229, 160, 68, 0.18);
  border-top-color: var(--primary-color);
  border-radius: 50%;
  animation: spin 0.8s linear infinite;
}

@keyframes spin {
  to {
    transform: rotate(360deg);
  }
}

.citation-list {
  display: grid;
  gap: 8px;
  margin-top: 10px;
}

.answer-evidence-meta {
  display: flex;
  flex-wrap: wrap;
  gap: 7px;
  margin-top: 10px;
}

.evidence-pill {
  display: inline-flex;
  align-items: center;
  min-height: 24px;
  padding: 0 9px;
  border: 1px solid var(--line-color);
  border-radius: 999px;
  color: var(--muted-color);
  font-size: 12px;
}

.confidence-high { color: #72d7a3; border-color: rgba(114, 215, 163, 0.4); }
.confidence-medium { color: #e5b56a; border-color: rgba(229, 181, 106, 0.4); }
.confidence-low { color: #ee8b78; border-color: rgba(238, 139, 120, 0.4); }
.confidence-none { color: var(--muted-color); }

.citation-title {
  color: var(--muted-color);
  font-size: 12px;
  font-weight: 800;
}

.citation-item {
  padding: 10px 12px;
  border: 1px solid var(--line-color);
  border-radius: 6px;
  background: rgba(255, 255, 255, 0.04);
}

.citation-info,
.citation-actions {
  display: flex;
  align-items: center;
  gap: 8px;
}

.citation-info {
  flex-wrap: wrap;
}

.citation-info strong {
  color: var(--ink-color);
}

.citation-info span,
.citation-actions span,
.citation-item p {
  color: var(--muted-color);
  font-size: 12px;
}

.citation-item p {
  margin: 7px 0;
  line-height: 1.6;
}

.citation-actions {
  justify-content: space-between;
}

.feedback-bar {
  display: flex;
  gap: 4px;
  margin-top: 8px;
}

.chat-input-panel {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 44px;
  gap: 14px;
  align-items: stretch;
  padding: 18px;
  border-top: 1px solid var(--line-color);
  background: rgba(8, 10, 16, 0.42);
  /* 父级不再裁切，底部两角自己收圆，避免盖住容器的圆角 */
  border-radius: 0 0 12px 12px;
  min-height: 110px;
}

/* 输入框跟随面板高度（拖动分隔条时同步变高/变矮） */
.chat-input-panel :deep(.el-textarea) {
  height: 100%;
}

.chat-input-panel :deep(.el-textarea__inner) {
  height: 100%;
  min-height: 0;
  resize: none;
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
  border-radius: 3px;
  background: rgba(247, 245, 242, 0.14);
  transition:
    width 0.2s var(--motion-curve),
    background 0.2s var(--motion-curve);
}

.chat-resize-handle:hover::after,
.chat-resize-handle:active::after {
  width: 84px;
  background: rgba(157, 107, 255, 0.72);
}

.send-button {
  width: 40px;
  min-height: 40px;
  padding: 0;
  align-self: end;
  justify-self: end;
  border-radius: 10px;
}

.send-button :deep(.el-icon) {
  font-size: 18px;
}

/* 方形图标按钮：平时低调中性灰，与页面其它主按钮共用主色点亮 */
.chat-input-panel .send-button:not(.is-disabled) {
  color: rgba(247, 245, 242, 0.88);
  border: none;
  background: rgba(247, 245, 242, 0.1);
}

.chat-input-panel .send-button:not(.is-disabled):hover,
.chat-input-panel .send-button:not(.is-disabled):focus {
  color: #0b0c0e;
  background: var(--primary-color);
  box-shadow: 0 0 18px -6px rgba(229, 160, 68, 0.6);
}

/* 未输入内容时按钮是禁用的：走低调的灰，不要 Element Plus 那套浅蓝 */
.chat-input-panel .send-button.is-disabled {
  color: rgba(247, 245, 242, 0.26);
  border: 1px solid rgba(247, 245, 242, 0.08);
  background: rgba(247, 245, 242, 0.04);
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
    grid-template-columns: 1fr;
    flex-direction: column;
  }

  .message-content {
    max-width: calc(100% - 54px);
  }
}
</style>
