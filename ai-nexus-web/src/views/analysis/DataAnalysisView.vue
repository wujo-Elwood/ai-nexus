<template>
  <div class="analysis-page page-shell">
    <section class="page-hero analysis-hero">
      <div>
        <span class="eyebrow">数据洞察</span>
        <h1 class="section-title">数据分析</h1>
        <p class="section-desc">
          上传 Excel 表格，用自然语言查数、统计和分组汇总；分析由智能体基于服务端确定性计算完成，结果不做任何编造。
        </p>
      </div>
    </section>

    <div class="analysis-layout">
      <aside class="analysis-side">
        <section class="glass-panel side-block">
          <h2 class="side-title">上传表格</h2>
          <div
            class="upload-zone"
            :class="{ uploading }"
            @click="triggerUpload"
            @dragover.prevent
            @drop.prevent="onDrop"
          >
            <input
              ref="fileInputRef"
              type="file"
              accept=".xlsx"
              style="display: none"
              @change="onPickFile"
            />
            <p v-if="!uploading" class="upload-hint">
              点击选择或拖入 <b>.xlsx</b> 文件<br />
              <small>首行为列名，最多 10 万行</small>
            </p>
            <p v-else class="upload-hint">正在解析…</p>
          </div>
          <div v-if="uploadResult" class="upload-result">
            <b>{{ uploadResult.fileName }}</b>
            <span>{{ uploadResult.totalRows }} 行 · {{ (uploadResult.sheets || []).length }} 个 Sheet，已就绪</span>
          </div>
          <div v-if="files.length" class="file-list">
            <button
              v-for="file in files"
              :key="file.id"
              class="file-item"
              :class="{ active: selectedId === file.id }"
              @click="selectFile(file)"
            >
              <span class="file-name">{{ file.fileName }}</span>
              <span class="file-meta">{{ file.totalRows }} 行</span>
              <span class="file-delete" @click.stop="onDeleteFile(file)">×</span>
            </button>
          </div>
        </section>

        <section v-if="selectedFile" class="glass-panel side-block">
          <h2 class="side-title">表结构预览</h2>
          <div v-for="sheet in selectedSheets" :key="sheet.name" class="sheet-block">
            <div class="sheet-name">{{ sheet.name }} <small>{{ sheet.rowCount }} 行</small></div>
            <div class="sheet-columns">
              <code
                v-for="column in sheet.columns"
                :key="column.name"
                :title="'类型：' + column.type"
              >{{ column.name }}</code>
            </div>
          </div>
        </section>
      </aside>

      <main class="analysis-chat glass-panel">
        <div class="chat-history-bar">
          <el-select
            v-model="selectedHistoryId"
            class="history-select"
            placeholder="历史对话"
            size="small"
            filterable
            clearable
            @change="loadHistoryConversation"
          >
            <el-option v-for="item in historyList" :key="item.id" :label="item.title" :value="item.id" />
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
        <div ref="chatWindowRef" class="chat-window">
          <div v-if="messages.length === 0" class="empty-hint">
            {{ selectedFile
              ? `已选中「${selectedFile.fileName}」，试试问："销售额最高的是哪个产品？"`
              : '先在左侧上传并选中一个表格，然后就能用自然语言查数了' }}
          </div>
          <div v-for="(msg, index) in messages" :key="index" class="msg-row" :class="msg.role">
            <div class="msg-bubble">
              <template v-if="msg.role === 'user'">{{ msg.content }}</template>
              <template v-else>
                <div v-if="msg.steps && msg.steps.length" class="msg-steps">
                  <div v-for="(step, sIndex) in msg.steps" :key="sIndex" class="msg-step">
                    ⚙ {{ step.tool }} · {{ step.status }} · {{ step.costMs }}ms
                  </div>
                </div>
                <div class="msg-answer" v-html="renderAnswer(msg)"></div>
                <div
                  v-for="(chart, cIndex) in getCharts(msg)"
                  :key="'chart-' + index + '-' + cIndex"
                  class="msg-chart"
                  :ref="el => initChart(el, chart)"
                ></div>
                <div v-if="hasExportableStep(msg)" class="msg-export">
                  <el-button size="small" @click="downloadResult(msg, 'xlsx')">导出 Excel</el-button>
                  <el-button size="small" @click="downloadResult(msg, 'csv')">导出 CSV</el-button>
                </div>
              </template>
            </div>
          </div>
          <div v-if="streaming" class="streaming-hint">智能体分析中…</div>
        </div>
        <div class="input-row">
          <el-input
            v-model="input"
            type="textarea"
            resize="none"
            :disabled="streaming || !selectedFile"
            :placeholder="selectedFile ? '输入数据问题（Enter 发送）' : '请先在左侧上传并选中表格'"
            @keydown.enter.exact="send"
          />
          <el-button type="primary" :disabled="streaming || !selectedFile || !input.trim()" @click="send">发送</el-button>
        </div>
      </main>
    </div>
  </div>
</template>

<script setup>
// 组件名用于 keep-alive 缓存：切换菜单时保留对话与流式状态
defineOptions({ name: 'DataAnalysisView' })
import { computed, nextTick, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import MarkdownIt from 'markdown-it'
import * as echarts from 'echarts/core'
import { BarChart, LineChart, PieChart } from 'echarts/charts'
import { GridComponent, TooltipComponent } from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'
import { uploadAnalysis, listAnalysis, getAnalysisDetail, deleteAnalysis } from '../../api/analysis'
import { streamAgentChat } from '../../api/agentTool'
import { createConversation, listConversations, getConversationDetail, appendConversationMessage, deleteConversation } from '../../api/agentConversation'

// ECharts 按需注册，控制打包体积
echarts.use([BarChart, LineChart, PieChart, GridComponent, TooltipComponent, CanvasRenderer])

const md = new MarkdownIt({ breaks: true, linkify: true })

const fileInputRef = ref(null)
const files = ref([])
const selectedId = ref(null)
const selectedFile = ref(null)
const uploading = ref(false)
const uploadResult = ref(null)
const messages = ref([])
const input = ref('')
const streaming = ref(false)
const chatWindowRef = ref(null)
// 对话历史持久化：当前会话 ID 与历史列表
const conversationId = ref(null)
const historyList = ref([])
const selectedHistoryId = ref(null)

const selectedSheets = computed(() => {
  try {
    return selectedFile.value?.sheetsJson ? JSON.parse(selectedFile.value.sheetsJson) : []
  } catch (error) {
    return []
  }
})

onMounted(() => {
  loadFiles()
  refreshHistory()
})

function renderAnswer(msg) {
  // 图表指令块不进 Markdown，交给 ECharts 渲染
  const clean = (msg.content || '').replace(/```chart[\s\S]*?```/g, '').trim()
  return md.render(clean)
}

// 从工具结果中提取图表指令（确定性生成，不依赖模型复述）
function getCharts(msg) {
  const charts = []
  for (const step of msg.steps || []) {
    if (step.tool === 'analyze_spreadsheet' && step.result) {
      const match = step.result.match(/```chart\n([\s\S]*?)```/)
      if (match) {
        try {
          charts.push(JSON.parse(match[1]))
        } catch (error) {
          // 图表 JSON 解析失败则跳过
        }
      }
    }
  }
  return charts
}

// 初始化页面上待渲染的图表
function initCharts() {
  nextTick(() => {
    document.querySelectorAll('.msg-chart[data-option]:not([data-done])').forEach(el => {
      try {
        const option = JSON.parse(el.dataset.option)
        const instance = echarts.init(el)
        instance.setOption(buildOption(option))
        el.dataset.done = '1'
      } catch (error) {
        // 单个图表渲染失败不影响回答
      }
    })
  })
}

// ref 回调：图表容器挂载时立即初始化，不依赖事件时序
function initChart(el, chart) {
  if (!el || el.dataset.done || !chart) {
    return
  }
  try {
    const instance = echarts.init(el)
    instance.setOption(buildOption(chart))
    el.dataset.done = '1'
  } catch (error) {
    el.dataset.error = String(error)
  }
}

function buildOption(chart) {
  return {
    title: { text: chart.title, left: 'center', textStyle: { color: '#e8eaf0', fontSize: 13 } },
    tooltip: { trigger: 'axis' },
    grid: { left: 10, right: 20, bottom: 10, top: 44, containLabel: true },
    xAxis: {
      type: 'category',
      data: chart.categories || [],
      axisLabel: { color: '#9aa0ac' },
      axisLine: { lineStyle: { color: '#3a3f4a' } }
    },
    yAxis: {
      type: 'value',
      axisLabel: { color: '#9aa0ac' },
      splitLine: { lineStyle: { color: '#23262e' } }
    },
    series: [{
      type: chart.type || 'bar',
      data: chart.values || [],
      itemStyle: { color: '#ece9e1' },
      barMaxWidth: 42
    }]
  }
}

function hasExportableStep(msg) {
  return (msg.steps || []).some(step => step.tool === 'analyze_spreadsheet' && step.status === 'SUCCESS' && step.arguments)
}

// 导出分析结果：按回答中记录的聚合参数，让后端重算并产出文件
async function downloadResult(msg, format) {
  try {
    const step = (msg.steps || []).find(s => s.tool === 'analyze_spreadsheet' && s.arguments)
    let args = {}
    try {
      args = JSON.parse(step.arguments || '{}')
    } catch (error) {
      args = {}
    }
    const query = new URLSearchParams({ format, operation: args.operation || 'count' })
    if (args.column) query.set('column', args.column)
    if (args.sheet) query.set('sheet', args.sheet)
    if (args.groupBy) query.set('groupBy', args.groupBy)
    if (args.filterColumn) query.set('filterColumn', args.filterColumn)
    if (args.filterValue) query.set('filterValue', args.filterValue)
    const res = await fetch(`/api/analysis/${args.fileId}/export?${query.toString()}`, {
      headers: { Authorization: 'Bearer ' + localStorage.getItem('token') }
    })
    if (!res.ok) {
      throw new Error('HTTP ' + res.status)
    }
    const blob = await res.blob()
    const url = URL.createObjectURL(blob)
    const link = document.createElement('a')
    link.href = url
    link.download = `分析结果.${format}`
    link.click()
    URL.revokeObjectURL(url)
    ElMessage.success('已导出')
  } catch (error) {
    ElMessage.error('导出失败')
  }
}

async function loadFiles() {
  try {
    const res = await listAnalysis()
    files.value = res.data || []
    if (selectedId.value && !files.value.some(f => f.id === selectedId.value)) {
      clearSelection()
    }
  } catch (error) {
    console.error(error)
  }
}

function triggerUpload() {
  if (!uploading.value) {
    fileInputRef.value?.click()
  }
}

async function onPickFile(event) {
  const file = event.target.files?.[0]
  event.target.value = ''
  if (file) {
    await doUpload(file)
  }
}

async function onDrop(event) {
  const file = event.dataTransfer?.files?.[0]
  if (file) {
    await doUpload(file)
  }
}

async function doUpload(file) {
  if (!file.name.toLowerCase().endsWith('.xlsx')) {
    ElMessage.warning('目前仅支持 .xlsx 文件')
    return
  }
  try {
    uploading.value = true
    const res = await uploadAnalysis(file)
    uploadResult.value = res.data
    await loadFiles()
    await selectFile({ id: res.data.id })
    ElMessage.success('解析完成，可以开始提问了')
  } catch (error) {
    ElMessage.error('上传解析失败')
  } finally {
    uploading.value = false
  }
}

async function selectFile(file) {
  try {
    const res = await getAnalysisDetail(file.id)
    selectedFile.value = res.data
    selectedId.value = file.id
    await nextTick(scrollToBottom)
  } catch (error) {
    ElMessage.error('加载表格详情失败')
  }
}

async function onDeleteFile(file) {
  try {
    await deleteAnalysis(file.id)
    if (selectedId.value === file.id) {
      clearSelection()
    }
    await loadFiles()
    ElMessage.success('已删除')
  } catch (error) {
    ElMessage.error('删除失败')
  }
}

function clearSelection() {
  selectedId.value = null
  selectedFile.value = null
  uploadResult.value = null
}

// 发送数据问题：消息携带当前文件 ID，智能体据此调用表格技能
async function send() {
  const question = input.value.trim()
  if (!question || streaming.value || !selectedFile.value) {
    return
  }
  const messageText = `【当前分析文件 ID: ${selectedFile.value.id}】${question}`
  messages.value.push({ role: 'user', content: question })
  input.value = ''
  const answer = { role: 'assistant', content: '', steps: [] }
  messages.value.push(answer)
  streaming.value = true
  await nextTick(scrollToBottom)
  // 持久化：首条消息时创建会话，随后存档用户消息
  await ensureConversation(question)
  await saveMessage('user', question)

  try {
    await streamAgentChat(
      { message: messageText, agentCode: 'data-analysis', reasoningEffort: 'medium' },
      {
        onEvent(type, data) {
          if (type === 'step_start') {
            answer.steps.push({ tool: data.tool || data.toolName, status: '执行中', costMs: 0, arguments: data.arguments || '' })
          } else if (type === 'step_result') {
            const last = answer.steps[answer.steps.length - 1]
            if (last) {
              last.status = data.status || 'SUCCESS'
              last.costMs = data.costMs ?? last.costMs
              last.result = data.result || ''
            }
          } else if (type === 'answer_delta') {
            answer.content += data.delta ?? ''
          } else if (type === 'answer_reset') {
            answer.content = ''
          } else if (type === 'answer') {
            answer.content = data.content || answer.content
          } else if (type === 'error') {
            answer.content += `\n\n执行出错：${data.message || '未知错误'}`
          }
          initCharts()
          nextTick(scrollToBottom)
        }
      }
    )
  } catch (error) {
    answer.content += '\n\n连接中断，请重试。'
  } finally {
    streaming.value = false
    // 持久化：存档助手回答（含工具步骤，恢复后可重建图表）
    if (answer.content) {
      const steps = (answer.steps || []).filter(s => s.tool)
      await saveMessage('assistant', answer.content, steps)
    }
    nextTick(scrollToBottom)
  }
}

// ---------- 对话历史持久化 ----------

// 确保存在当前会话：首条消息发出时创建，标题取首条问题
async function ensureConversation(firstMessage) {
  if (conversationId.value) return
  try {
    const res = await createConversation('data-analysis', firstMessage.slice(0, 80))
    conversationId.value = res.data.id
    await refreshHistory()
  } catch (error) {
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

// 加载历史会话列表
async function refreshHistory() {
  try {
    const res = await listConversations('data-analysis')
    historyList.value = res.data || []
  } catch (error) {
    console.error(error)
  }
}

// 从历史加载会话：重建消息与工具步骤（图表随之恢复）
async function loadHistoryConversation(id) {
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
        restored.push({ role: 'assistant', content: item.content, steps, pending: false })
      }
    }
    conversationId.value = data.id
    selectedHistoryId.value = data.id
    messages.value = restored
    nextTick(() => { initCharts(); scrollToBottom() })
  } catch (error) {
    ElMessage.error('加载历史对话失败')
  }
}

// 开启新对话：清空当前状态（历史仍保留在列表中）
function startNewConversation() {
  conversationId.value = null
  selectedHistoryId.value = null
  messages.value = []
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

function scrollToBottom() {
  const windowEl = chatWindowRef.value
  if (windowEl) {
    windowEl.scrollTop = windowEl.scrollHeight
  }
}
</script>

<style scoped>
.analysis-hero {
  padding-top: 0;
}

.analysis-layout {
  display: grid;
  grid-template-columns: 340px minmax(0, 1fr);
  gap: 0;
  margin-top: 20px;
  align-items: start;
}

/* 左栏与对话区之间立一条分界线 */
.analysis-side {
  border-right: 1px solid var(--rule);
  padding-right: 26px;
}

.analysis-chat {
  padding-left: 30px;
}

.side-block {
  padding: 14px 16px;
  margin-bottom: 14px;
}

.side-title {
  margin: 0 0 10px;
  font-size: 14px;
  color: var(--text-primary, #e8eaf0);
}

.upload-zone {
  padding: 22px 14px;
  text-align: center;
  border: 1px dashed #2f333c;
  border-radius: 0;
  cursor: pointer;
  transition: border-color 0.2s ease, background 0.2s ease;
}

.upload-zone:hover,
.upload-zone.uploading {
  border-color: var(--rule-strong);
  background: var(--row-hover);
}

.upload-hint {
  margin: 0;
  font-size: 13px;
  color: var(--text-secondary, #9aa0ac);
  line-height: 1.8;
}

.upload-hint b {
  color: var(--text-1);
}

.upload-result {
  display: flex;
  flex-direction: column;
  gap: 2px;
  margin-top: 10px;
  padding: 8px 10px;
  border-radius: 0;
  background: var(--row-hover);
  border: 1px solid var(--rule-strong);
  font-size: 12px;
}

.upload-result b {
  color: var(--text-1);
}

.upload-result span {
  color: var(--text-secondary, #9aa0ac);
}

.file-list {
  display: flex;
  flex-direction: column;
  gap: 6px;
  margin-top: 10px;
  max-height: 220px;
  overflow-y: auto;
}

.file-item {
  position: relative;
  display: flex;
  flex-direction: column;
  gap: 2px;
  padding: 8px 28px 8px 10px;
  text-align: left;
  background: rgba(255, 255, 255, 0.02);
  border: 1px solid #23262e;
  border-radius: 0;
  color: inherit;
  cursor: pointer;
}

.file-item:hover {
  border-color: #3a3f4a;
}

.file-item.active {
  border-color: rgba(255, 107, 87, 0.55);
  background: var(--signal-soft);
}

.file-name {
  font-size: 13px;
  color: var(--text-primary, #e8eaf0);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.file-meta {
  font-size: 11px;
  color: var(--muted-color);
}

.file-delete {
  position: absolute;
  right: 8px;
  top: 50%;
  transform: translateY(-50%);
  color: var(--muted-color);
  font-size: 16px;
  cursor: pointer;
}

.file-delete:hover {
  color: #f56c6c;
}

.sheet-block {
  margin-bottom: 10px;
}

.sheet-name {
  font-size: 13px;
  color: var(--text-primary, #e8eaf0);
  margin-bottom: 6px;
}

.sheet-name small {
  color: var(--muted-color);
  margin-left: 6px;
}

.sheet-columns {
  display: flex;
  flex-wrap: wrap;
  gap: 5px;
}

.sheet-columns code {
  padding: 1px 8px;
  font-size: 12px;
  color: #7fb0ff;
  background: rgba(127, 176, 255, 0.08);
  border: 1px solid rgba(127, 176, 255, 0.25);
  border-radius: 0;
}

.analysis-chat {
  display: flex;
  flex-direction: column;
  min-height: 560px;
  padding: 14px;
  border-radius: 0;
}

.chat-history-bar {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 8px;
}

.history-select {
  width: 240px;
}

.chat-window {
  flex: 1;
  min-height: 460px;
  overflow-y: auto;
  padding: 6px 4px;
}

.empty-hint {
  margin-top: 40px;
  text-align: center;
  color: var(--subtle-color);
  font-size: 13px;
}

.msg-row {
  display: flex;
  margin-bottom: 12px;
}

.msg-row.user {
  justify-content: flex-end;
}

.msg-bubble {
  max-width: 82%;
  padding: 9px 13px;
  border-radius: 0;
  font-size: 13.5px;
  line-height: 1.65;
}

.msg-row.user .msg-bubble {
  background: transparent;
  border: 0;
  border-left: 2px solid var(--signal);
  padding-left: 14px;
}

.msg-row.assistant .msg-bubble {
  background: transparent;
  border: 0;
  padding-left: 0;
}

.msg-steps {
  margin-bottom: 6px;
}

.msg-step {
  font-size: 11.5px;
  color: var(--muted-color);
}

.msg-answer :deep(table) {
  border-collapse: collapse;
  margin: 6px 0;
  font-size: 12.5px;
}

.msg-answer :deep(th),
.msg-answer :deep(td) {
  border: 1px solid #2a2d35;
  padding: 4px 8px;
}

.msg-answer :deep(code) {
  color: #7fb0ff;
}

.streaming-hint {
  text-align: center;
  font-size: 12px;
  color: var(--muted-color);
}

.msg-chart {
  width: 100%;
  height: 260px;
  margin-top: 8px;
}

.msg-export {
  display: flex;
  gap: 8px;
  margin-top: 8px;
}

.input-row {
  display: flex;
  gap: 10px;
  margin-top: 10px;
}
</style>
