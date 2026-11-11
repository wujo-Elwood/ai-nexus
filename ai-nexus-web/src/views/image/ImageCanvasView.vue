<template>
  <div class="canvas-page page-shell">
    <section class="page-hero">
      <div>
        <span class="eyebrow">AI Image Canvas</span>
        <h1 class="section-title">生图画布</h1>
        <p class="section-desc">
          把生成的图片铺在无限画布上自由排版对比：拖动空白平移，滚轮缩放，拖动卡片摆位，位置自动记忆。
        </p>
      </div>
      <div class="page-hero-actions">
        <el-button class="page-back-button" plain @click="router.push('/image')">去生图</el-button>
        <el-button :loading="refreshing" @click="refreshAll">刷新</el-button>
      </div>
    </section>

    <section class="generate-bar glass-panel">
      <el-input
        v-model="prompt"
        class="bar-prompt"
        type="textarea"
        :autosize="{ minRows: 1, maxRows: 3 }"
        maxlength="2000"
        placeholder="输入提示词直接生成，新图会实时出现在画布上"
      />
      <el-input-number v-model="count" :min="1" :max="6" />
      <el-button
        type="primary"
        :loading="submitting"
        :disabled="submitting || !prompt.trim() || runningTaskCount >= MAX_TASKS"
        @click="submitGeneration"
      >
        生成
      </el-button>
      <span class="bar-tip">运行中 {{ runningTaskCount }} / {{ MAX_TASKS }}</span>
    </section>

    <div class="canvas-shell">
      <div class="canvas-toolbar">
        <el-button size="small" @click="resetCanvasView">复位视图</el-button>
        <span class="canvas-zoom-label">{{ Math.round(canvasZoom * 100) }}%</span>
        <span class="canvas-tip">拖动空白平移 · 滚轮缩放 · 拖动卡片摆位 · 双击卡片查看大图</span>
        <el-button size="small" text type="danger" @click="clearLayout">清空摆位</el-button>
      </div>
      <div
        class="canvas-viewport"
        @pointerdown="startCanvasPan"
        @pointermove="onCanvasPointerMove"
        @pointerup="endCanvasInteraction"
        @pointercancel="endCanvasInteraction"
        @wheel.prevent="onCanvasWheel"
      >
        <div class="canvas-world" :style="canvasWorldStyle">
          <div
            v-for="node in imageNodes"
            :key="node.key"
            class="canvas-node"
            :style="{ left: node.x + 'px', top: node.y + 'px', width: CANVAS_NODE_WIDTH + 'px' }"
            @pointerdown.stop="startNodeDrag($event, node.key)"
            @pointermove="onCanvasPointerMove"
            @pointerup="endCanvasInteraction"
            @pointercancel="endCanvasInteraction"
            @dblclick="openHistoryImage(node.item)"
          >
            <img :src="authedSrcOf(`/api/image/history/${node.item.id}/view`)" :alt="node.item.prompt" />
            <div class="canvas-node-body">
              <el-tooltip :content="node.item.prompt" placement="top" popper-class="prompt-tooltip" :show-after="200">
                <p class="canvas-node-prompt">{{ node.item.prompt }}</p>
              </el-tooltip>
              <div class="canvas-node-meta">
                <span class="node-model">{{ node.item.providerName || '供应商' }} / {{ node.item.modelName || '模型' }}</span>
                <small>{{ formatTime(node.item.createTime) }}</small>
              </div>
              <div class="canvas-node-actions">
                <el-button size="small" @click="openHistoryImage(node.item)">查看</el-button>
                <el-button size="small" type="primary" @click="downloadHistoryImage(node.item)">下载</el-button>
              </div>
            </div>
          </div>
          <div
            v-for="node in taskNodes"
            :key="node.key"
            class="canvas-node"
            :class="{ 'canvas-node-failed': node.task.taskStatus === 'FAILED' }"
            :style="{ left: node.x + 'px', top: node.y + 'px', width: CANVAS_NODE_WIDTH + 'px' }"
            @pointerdown.stop="startNodeDrag($event, node.key)"
            @pointermove="onCanvasPointerMove"
            @pointerup="endCanvasInteraction"
            @pointercancel="endCanvasInteraction"
          >
            <div class="canvas-node-media">
              <img v-if="getTaskImage(node.task)" :src="getTaskImageUrl(node.task)" :alt="node.task.prompt" />
              <div v-else-if="isRunningTask(node.task)" class="node-running">
                <div class="loading-orbit"></div>
                <strong>正在生成</strong>
                <span>{{ node.task.progress || 10 }}%</span>
              </div>
              <div v-else class="node-failed-face">
                <strong>生成失败</strong>
              </div>
            </div>
            <div class="canvas-node-body">
              <el-tooltip :content="node.task.prompt" placement="top" popper-class="prompt-tooltip" :show-after="200">
                <p class="canvas-node-prompt">{{ node.task.prompt }}</p>
              </el-tooltip>
              <div class="canvas-node-meta">
                <el-tag size="small" :type="node.task.taskStatus === 'FAILED' ? 'danger' : 'warning'">
                  {{ node.task.taskStatus === 'FAILED' ? '失败' : '生成中' }}
                </el-tag>
                <small>{{ formatTime(node.task.createTime) }}</small>
              </div>
              <p v-if="node.task.taskStatus === 'FAILED'" class="node-error">{{ node.task.errorMessage || '生图模型调用失败' }}</p>
            </div>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { computed, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { generateImage, getImageHistory, getImageTasks } from '../../api/image'
import { authedSrcOf, openAuthedImage, downloadAuthedFile } from '../../utils/authImage'

const router = useRouter()

const CANVAS_NODE_WIDTH = 260
const CANVAS_NODE_HEIGHT = 380
const CANVAS_PER_ROW = 4
const CANVAS_ZOOM_MIN = 0.15
const CANVAS_ZOOM_MAX = 3
const MAX_TASKS = 6
const CANVAS_POS_KEY = 'genCanvasPos'
const CANVAS_VIEW_KEY = 'genCanvasView'
const CANVAS_DISMISSED_KEY = 'genCanvasDismissedFailed'

const prompt = ref('')
const count = ref(1)
const submitting = ref(false)
const refreshing = ref(false)
const tasks = ref([])
const history = ref([])
const canvasPan = reactive({ x: 40, y: 40 })
const canvasZoom = ref(1)
const nodePositions = ref(readJsonStore(CANVAS_POS_KEY, {}))
const dismissedFailed = ref(readJsonStore(CANVAS_DISMISSED_KEY, []))
let canvasDrag = null
let pollTimer = null

function readJsonStore(key, fallback) {
  try {
    const raw = localStorage.getItem(key)
    const value = raw ? JSON.parse(raw) : null
    return value && typeof value === 'object' ? value : fallback
  } catch {
    return fallback
  }
}

function writeJsonStore(key, value) {
  try { localStorage.setItem(key, JSON.stringify(value)) } catch { /* 本地存储失败不影响使用 */ }
}

// ==================== 数据加载 ====================

async function refreshAll() {
  refreshing.value = true
  try {
    const [taskRes, historyRes] = await Promise.all([
      getImageTasks(50),
      getImageHistory(100)
    ])
    tasks.value = taskRes.data || []
    history.value = historyRes.data || []
    updatePollingState()
  } catch (error) {
    console.error(error)
  } finally {
    refreshing.value = false
  }
}

async function loadHistory() {
  try {
    const res = await getImageHistory(100)
    history.value = res.data || []
  } catch (error) {
    console.error(error)
  }
}

async function pollTasks() {
  try {
    const res = await getImageTasks(50)
    tasks.value = res.data || []
    updatePollingState()
  } catch (error) {
    // 轮询失败不代表任务失败：保持最后状态，下个周期继续重试
    console.error(error)
  }
}

function updatePollingState() {
  if (runningTaskCount.value > 0) {
    startPolling()
  } else {
    stopPolling()
    loadHistory()
  }
}

function startPolling() {
  if (pollTimer) return
  pollTimer = window.setInterval(pollTasks, 3000)
}

function stopPolling() {
  if (pollTimer) {
    window.clearInterval(pollTimer)
    pollTimer = null
  }
}

// ==================== 生成 ====================

async function submitGeneration() {
  const text = prompt.value.trim()
  if (!text) {
    ElMessage.warning('请输入生图提示词')
    return
  }
  submitting.value = true
  try {
    const requests = Array.from({ length: count.value }, () =>
      generateImage({ prompt: text, size: 'auto', n: 1, referenceImages: [] })
    )
    //逐个独立结算：单个提交失败不影响其余任务
    const results = await Promise.allSettled(requests)
    const created = results.filter(item => item.status === 'fulfilled' && item.value?.data?.id).length
    const failed = results.length - created
    if (failed === 0) {
      ElMessage.success(`已提交 ${created} 个生图任务，完成后自动出现在画布上`)
    } else if (created > 0) {
      ElMessage.warning(`成功提交 ${created} 个任务，${failed} 个提交失败；失败的不影响其余任务`)
    } else {
      const firstError = results.find(item => item.status === 'rejected')?.reason
      ElMessage.error(firstError?.message || '生图任务提交失败')
    }
    await pollTasks()
  } catch (error) {
    console.error(error)
    ElMessage.error(error?.message || '生图任务提交失败')
  } finally {
    submitting.value = false
  }
}

// ==================== 画布节点 ====================

const runningTaskCount = computed(() => tasks.value.filter(isRunningTask).length)

function isRunningTask(task) {
  return ['PENDING', 'RUNNING'].includes(task?.taskStatus)
}

// 生成中的任务 + 近期失败未关闭的任务（成功结果由历史节点呈现）
const taskNodes = computed(() => {
  return tasks.value
    .filter(task => {
      if (isRunningTask(task)) return true
      if (task.taskStatus !== 'FAILED') return false
      if (dismissedFailed.value.includes(String(task.id))) return false
      const finished = new Date(task.finishedAt || 0).getTime()
      return Number.isFinite(finished) && Date.now() - finished < 30 * 60 * 1000
    })
    .map((task, index) => {
      const key = 't' + task.id
      const pos = nodePositions.value[key] || defaultNodePosition(history.value.length + index)
      return { key, task, x: pos.x, y: pos.y }
    })
})

// 历史图片：全部自动铺开，从最旧的开始排，新图往后面追加，已拖动过的沿用记忆位置
const imageNodes = computed(() => {
  const ordered = [...history.value].reverse()
  return ordered.map((item, index) => {
    const key = 'h' + item.id
    const pos = nodePositions.value[key] || defaultNodePosition(index)
    return { key, item, x: pos.x, y: pos.y }
  })
})

const canvasWorldStyle = computed(() => ({
  transform: `translate(${canvasPan.x}px, ${canvasPan.y}px) scale(${canvasZoom.value})`
}))

function defaultNodePosition(index) {
  return {
    x: 40 + (index % CANVAS_PER_ROW) * (CANVAS_NODE_WIDTH + 32),
    y: 40 + Math.floor(index / CANVAS_PER_ROW) * (CANVAS_NODE_HEIGHT + 32)
  }
}

// ==================== 画布交互 ====================

function startCanvasPan(event) {
  if (event.button !== 0) return
  canvasDrag = { type: 'pan', startX: event.clientX, startY: event.clientY, originX: canvasPan.x, originY: canvasPan.y }
  event.currentTarget.setPointerCapture?.(event.pointerId)
}

function startNodeDrag(event, key) {
  if (event.button !== 0) return
  const pos = nodePositions.value[key] || { x: 0, y: 0 }
  canvasDrag = { type: 'node', key, startX: event.clientX, startY: event.clientY, originX: pos.x, originY: pos.y }
  event.currentTarget.setPointerCapture?.(event.pointerId)
}

function onCanvasPointerMove(event) {
  if (!canvasDrag) return
  const dx = event.clientX - canvasDrag.startX
  const dy = event.clientY - canvasDrag.startY
  if (canvasDrag.type === 'pan') {
    canvasPan.x = canvasDrag.originX + dx
    canvasPan.y = canvasDrag.originY + dy
    return
  }
  nodePositions.value = {
    ...nodePositions.value,
    [canvasDrag.key]: {
      x: Math.round(canvasDrag.originX + dx / canvasZoom.value),
      y: Math.round(canvasDrag.originY + dy / canvasZoom.value)
    }
  }
}

function endCanvasInteraction() {
  if (canvasDrag && canvasDrag.type === 'node') {
    writeJsonStore(CANVAS_POS_KEY, nodePositions.value)
  }
  canvasDrag = null
}

function onCanvasWheel(event) {
  const factor = event.deltaY < 0 ? 1.1 : 1 / 1.1
  const nextZoom = Math.min(CANVAS_ZOOM_MAX, Math.max(CANVAS_ZOOM_MIN, canvasZoom.value * factor))
  if (nextZoom === canvasZoom.value) return
  const rect = event.currentTarget.getBoundingClientRect()
  const mouseX = event.clientX - rect.left
  const mouseY = event.clientY - rect.top
  // 以光标为锚点缩放，保证指着的点视觉上不动
  canvasPan.x = mouseX - (mouseX - canvasPan.x) * (nextZoom / canvasZoom.value)
  canvasPan.y = mouseY - (mouseY - canvasPan.y) * (nextZoom / canvasZoom.value)
  canvasZoom.value = nextZoom
  writeJsonStore(CANVAS_VIEW_KEY, { x: canvasPan.x, y: canvasPan.y, zoom: canvasZoom.value })
}

function resetCanvasView() {
  canvasPan.x = 40
  canvasPan.y = 40
  canvasZoom.value = 1
  writeJsonStore(CANVAS_VIEW_KEY, { x: canvasPan.x, y: canvasPan.y, zoom: canvasZoom.value })
}

function clearLayout() {
  ElMessageBox.confirm('确定清空全部手动摆位吗？所有卡片会回到默认网格排列。', '清空摆位', {
    confirmButtonText: '清空',
    cancelButtonText: '取消',
    type: 'warning'
  }).then(() => {
    nodePositions.value = {}
    dismissedFailed.value = []
    writeJsonStore(CANVAS_POS_KEY, {})
    writeJsonStore(CANVAS_DISMISSED_KEY, [])
    resetCanvasView()
    ElMessage.success('已恢复默认排列')
  }).catch(() => {})
}

// ==================== 图片访问 ====================
// 受保护图片统一走带鉴权头的 blob 缓存，不把 JWT 拼进 URL

function getTaskImage(task) {
  return task?.result?.images?.[0] || null
}

function getTaskImageUrl(task) {
  const image = getTaskImage(task)
  if (!image) return ''
  return image.viewUrl ? authedSrcOf(image.viewUrl) : ''
}

function openHistoryImage(item) {
  openAuthedImage(`/api/image/history/${item.id}/view`)
}

async function downloadHistoryImage(item) {
  await downloadAuthedFile(`/api/image/history/${item.id}/download`, item.fileName || `ai-image-${item.id}.png`)
}

function formatTime(value) {
  return value ? new Date(value).toLocaleString() : ''
}

onMounted(async () => {
  const view = readJsonStore(CANVAS_VIEW_KEY, null)
  if (view && Number.isFinite(view.x)) {
    canvasPan.x = view.x
    canvasPan.y = view.y
    canvasZoom.value = view.zoom || 1
  }
  await refreshAll()
})

onBeforeUnmount(stopPolling)
</script>

<style scoped>
.page-hero {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
}

.generate-bar {
  display: flex;
  align-items: flex-start;
  gap: 12px;
  padding: 14px 16px;
  margin-bottom: 16px;
  border-radius: 0;
}

.bar-prompt {
  flex: 1;
}

.bar-tip {
  align-self: center;
  color: var(--muted-color);
  font-size: 12px;
  white-space: nowrap;
}

.canvas-shell {
  position: relative;
}

.canvas-toolbar {
  position: absolute;
  top: 12px;
  left: 12px;
  z-index: 5;
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 6px 10px;
  border: 1px solid var(--line-color);
  border-radius: 0;
  background: rgba(8, 10, 16, 0.72);
}

.canvas-zoom-label {
  min-width: 44px;
  color: var(--muted-color);
  font-size: 12px;
  text-align: center;
}

.canvas-tip {
  color: var(--muted-color);
  font-size: 12px;
}

.canvas-viewport {
  position: relative;
  height: calc(100vh - 320px);
  min-height: 480px;
  overflow: hidden;
  border: 1px solid var(--line-color);
  border-radius: 0;
  background:
    radial-gradient(circle, rgba(255, 255, 255, 0.07) 1px, transparent 1px) 0 0 / 24px 24px,
    #0b0e14;
  cursor: grab;
  touch-action: none;
  user-select: none;
}

.canvas-viewport:active {
  cursor: grabbing;
}

.canvas-world {
  position: absolute;
  top: 0;
  left: 0;
  transform-origin: 0 0;
}

.canvas-node {
  position: absolute;
  overflow: hidden;
  border: 1px solid var(--line-color);
  border-radius: 0;
  background: rgba(8, 10, 16, 0.78);
  box-shadow: 0 10px 28px rgba(0, 0, 0, 0.38);
  cursor: grab;
  touch-action: none;
}

.canvas-node:active {
  cursor: grabbing;
}

.canvas-node-failed {
  border-color: rgba(220, 80, 80, 0.45);
}

.canvas-node img {
  width: 100%;
  max-height: 240px;
  display: block;
  object-fit: contain;
  background: #080a10;
  pointer-events: none;
}

.canvas-node-media {
  min-height: 80px;
  display: grid;
  place-items: center;
  background: rgba(8, 10, 16, 0.4);
}

.canvas-node-body {
  display: grid;
  gap: 6px;
  padding: 10px 12px 12px;
}

.canvas-node-prompt {
  margin: 0;
  color: var(--ink-color);
  font-size: 13px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.canvas-node-meta {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}

.canvas-node-meta small {
  color: var(--muted-color);
  font-size: 12px;
}

.node-model {
  color: var(--muted-color);
  font-size: 12px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.node-error {
  margin: 0;
  color: #e08888;
  font-size: 12px;
  line-height: 1.5;
  display: -webkit-box;
  -webkit-line-clamp: 3;
  -webkit-box-orient: vertical;
  overflow: hidden;
}

.node-running,
.node-failed-face {
  display: grid;
  place-items: center;
  gap: 10px;
  padding: 24px 16px;
  text-align: center;
}

.node-running strong,
.node-failed-face strong {
  color: var(--ink-color);
}

.node-running span {
  color: var(--muted-color);
  font-size: 13px;
}

.loading-orbit {
  width: 34px;
  height: 34px;
  border-radius: 50%;
  border: 3px solid rgba(236, 233, 225, 0.25);
  border-top-color: #ece9e1;
  animation: canvas-spin 0.9s linear infinite;
}

@keyframes canvas-spin {
  to { transform: rotate(360deg); }
}
</style>

<style>
/* 提示词悬停浮框：挂在 body 下的 popper，需要全局样式；长提示词自动换行、过高可滚动 */
.prompt-tooltip {
  max-width: 460px;
  max-height: 320px;
  overflow-y: auto;
  white-space: pre-wrap;
  word-break: break-word;
  line-height: 1.6;
  font-size: 13px;
}
</style>
