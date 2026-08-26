<template>
  <div class="image-page page-shell">
    <section class="page-hero image-hero">
      <div>
        <span class="eyebrow">AI Image</span>
        <h1 class="section-title">AI 生图</h1>
        <p class="section-desc">
          每次生图都会创建后台任务，最多同时运行 6 个任务。切换页面后再回来，也可以继续查看进度和结果。
        </p>
      </div>
    </section>

    <section class="image-layout">
      <article class="prompt-panel glass-panel motion-card">
        <div class="panel-head">
          <div>
            <h2>生成参数</h2>
            <p>使用模型设置中当前启用供应商的生图模型配置。</p>
          </div>
        </div>

        <el-form label-position="top" class="image-form" @submit.prevent>
          <el-form-item label="提示词">
            <el-input
              v-model="form.prompt"
              type="textarea"
              :autosize="{ minRows: 7, maxRows: 12 }"
              maxlength="2000"
              show-word-limit
              placeholder="例如：未来城市夜景，玻璃幕墙建筑，星空背景，电影感光影，细节丰富"
            />
          </el-form-item>

          <el-form-item label="图片尺寸">
            <button type="button" class="size-setting-trigger" @click="openSizeDialog">
              <span>
                <strong>{{ sizeDisplayText }}</strong>
                <small>{{ sizeModeText }}</small>
              </span>
              <em>设置</em>
            </button>
          </el-form-item>

          <el-form-item label="参考图">
            <div class="reference-upload-block">
              <el-upload
                v-model:file-list="referenceImageList"
                class="reference-upload"
                action="#"
                list-type="picture-card"
                :auto-upload="false"
                :multiple="true"
                :limit="MAX_REFERENCE_IMAGES"
                accept="image/png,image/jpeg,image/webp"
                :on-change="handleReferenceImageChange"
                :on-remove="handleReferenceImageRemove"
                :on-exceed="handleReferenceImageExceed"
              >
                <div class="reference-upload-add">
                  <span>+</span>
                </div>
              </el-upload>
              <p>最多 6 张，支持 PNG、JPG、WebP。上传后会作为本次生图参考。</p>
            </div>
          </el-form-item>

          <el-form-item label="生成数量">
            <el-input-number v-model="form.n" :min="1" :max="MAX_CONCURRENT_IMAGE_TASKS" />
          </el-form-item>
        </el-form>

        <div class="form-actions">
          <el-button @click="clearForm">清空</el-button>
          <el-button type="primary" :loading="submitting" :disabled="startButtonDisabled" @click="handleGenerate">
            {{ availableTaskSlots <= 0 ? '任务已满' : '开始生成' }}
          </el-button>
        </div>
        <p v-if="runningTaskCount > 0" class="task-limit-tip">
          当前运行中 {{ runningTaskCount }} / {{ MAX_CONCURRENT_IMAGE_TASKS }}，还可提交 {{ availableTaskSlots }} 个任务
        </p>
      </article>

      <article class="result-panel glass-panel motion-card">
        <div class="panel-head">
          <div>
            <h2>生成结果</h2>
            <p>{{ resultText }}</p>
          </div>
          <el-tag v-if="modelText">{{ modelText }}</el-tag>
        </div>

        <div class="task-card-grid">
          <article
            v-for="slot in displayTaskSlots"
            :key="slot.key"
            class="generate-task-card"
            :class="[slot.task?.taskStatus?.toLowerCase(), { empty: !slot.task }]"
          >
            <div class="task-card-media">
              <img
                v-if="slot.task && getTaskImage(slot.task)"
                :src="getImageSrc(getTaskImage(slot.task))"
                :alt="slot.task.prompt"
              />
              <div v-else-if="slot.task && isRunningImageTask(slot.task)" class="task-running">
                <div class="loading-orbit"></div>
                <strong>正在生成</strong>
                <span>{{ slot.task.progress || 10 }}%</span>
              </div>
              <div v-else-if="slot.task?.taskStatus === 'FAILED'" class="task-failed">
                <strong>生成失败</strong>
                <span>{{ slot.task.errorMessage || slot.task.taskMessage || '生图模型调用失败' }}</span>
              </div>
              <div v-else class="task-empty">
                <strong>任务 {{ slot.index }}</strong>
                <span>空闲中，等待提交生成任务</span>
              </div>
            </div>

            <div v-if="slot.task" class="task-card-body">
              <div class="task-card-head">
                <el-tag size="small" :type="getTaskTagType(slot.task)">
                  {{ getTaskStatusText(slot.task) }}
                </el-tag>
                <small>{{ formatTime(slot.task.createTime) }}</small>
              </div>
              <div class="task-duration-line">
                <span>{{ isRunningImageTask(slot.task) ? '已用' : '耗时' }}</span>
                <strong>{{ getTaskDurationText(slot.task) }}</strong>
              </div>
              <p>{{ slot.task.prompt }}</p>
              <el-progress
                v-if="isRunningImageTask(slot.task)"
                :percentage="slot.task.progress || 10"
                :stroke-width="6"
                :show-text="false"
              />
              <div v-if="getTaskImage(slot.task)" class="image-actions">
                <el-button size="small" @click="openImage(getTaskImage(slot.task))">查看</el-button>
                <el-button size="small" type="primary" @click="downloadImage(getTaskImage(slot.task), slot.task.id)">下载</el-button>
              </div>
            </div>
          </article>
        </div>
      </article>
    </section>

    <section class="history-panel glass-panel motion-card">
      <div class="panel-head">
        <div>
          <h2>生成历史</h2>
          <p>生成成功的图片会保存在服务器上，可以随时查看和下载。</p>
        </div>
        <el-button :loading="historyLoading" @click="loadHistory">刷新</el-button>
      </div>

      <el-empty v-if="!historyLoading && historyList.length === 0" description="暂无生图历史" />

      <div v-else class="history-grid">
        <article v-for="item in historyList" :key="item.id" class="history-card">
          <img :src="buildHistoryImageUrl(item)" :alt="item.prompt" />
          <div class="history-info">
            <strong>{{ item.prompt }}</strong>
            <span>{{ item.providerName || '供应商' }} / {{ item.modelName || '模型' }}</span>
            <span class="history-duration">耗时 {{ getHistoryDurationText(item) }}</span>
            <small>{{ formatTime(item.createTime) }}</small>
          </div>
          <div class="history-actions">
            <el-button size="small" @click="openHistoryImage(item)">查看</el-button>
            <el-button size="small" type="primary" @click="downloadHistoryImage(item)">下载</el-button>
            <el-button size="small" type="danger" text @click="handleDeleteHistory(item)">删除</el-button>
          </div>
        </article>
      </div>
    </section>

    <el-dialog
      v-model="sizeDialogVisible"
      width="640px"
      class="size-setting-dialog"
      :show-close="false"
      :close-on-click-modal="false"
    >
      <div class="size-dialog-shell">
        <header class="size-dialog-head">
          <div>
            <h2>设置图片尺寸</h2>
            <p>当前：{{ form.size }}</p>
          </div>
          <button type="button" class="size-dialog-close" @click="sizeDialogVisible = false">x</button>
        </header>

        <div class="size-mode-tabs">
          <button
            v-for="mode in sizeModeOptions"
            :key="mode.value"
            type="button"
            :class="{ active: sizeDraft.mode === mode.value }"
            @click="setSizeMode(mode.value)"
          >
            {{ mode.label }}
          </button>
        </div>

        <section v-if="sizeDraft.mode === 'auto'" class="auto-size-panel">
          <div class="auto-size-icon">AI</div>
          <h3>自动尺寸</h3>
          <p>由模型根据提示词决定合适的生成尺寸。</p>
        </section>

        <section v-else-if="sizeDraft.mode === 'ratio'" class="ratio-size-panel">
          <div class="size-field-block">
            <label>基准分辨率</label>
            <div class="base-size-options">
              <button
                v-for="item in baseSizeOptions"
                :key="item.label"
                type="button"
                :class="{ active: sizeDraft.base === item.label }"
                @click="sizeDraft.base = item.label"
              >
                {{ item.label }}
              </button>
            </div>
          </div>

          <div class="size-field-block">
            <label>图片比例</label>
            <div class="ratio-options">
              <button
                v-for="item in ratioOptions"
                :key="item.label"
                type="button"
                :class="{ active: sizeDraft.ratio === item.label }"
                @click="sizeDraft.ratio = item.label"
              >
                <span class="ratio-icon" :style="ratioIconStyle(item)"></span>
                {{ item.label }}
              </button>
            </div>
            <button
              type="button"
              class="custom-ratio-button"
              :class="{ active: sizeDraft.ratio === 'custom' }"
              @click="selectCustomRatio"
            >
              自定义比例
            </button>
          </div>

          <div v-if="sizeDraft.ratio === 'custom'" class="size-field-block">
            <label>输入自定义比例</label>
            <el-input v-model="sizeDraft.customRatio" placeholder="例如：16:9" />
          </div>
        </section>

        <section v-else class="custom-size-panel">
          <div class="size-field-block">
            <label>输入自定义像素值</label>
            <div class="custom-pixel-grid">
              <el-form-item label="宽度">
                <el-input-number v-model="sizeDraft.width" :min="16" :max="99999" :step="16" controls-position="right" />
              </el-form-item>
              <span class="pixel-times">x</span>
              <el-form-item label="高度">
                <el-input-number v-model="sizeDraft.height" :min="16" :max="99999" :step="16" controls-position="right" />
              </el-form-item>
            </div>
          </div>
        </section>

        <footer class="size-dialog-foot">
          <div>
            <span>将使用</span>
            <strong>{{ sizePreviewText }}</strong>
          </div>
          <div class="size-dialog-actions">
            <el-button @click="sizeDialogVisible = false">取消</el-button>
            <el-button type="primary" @click="applyImageSize">确定</el-button>
          </div>
        </footer>
      </div>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { deleteImageHistory, generateImage, getImageHistory, getImageTasks } from '../../api/image'

const MIN_IMAGE_PIXELS = 655360
const MAX_IMAGE_PIXELS = 8294400
const MAX_IMAGE_EDGE = 3840
const MAX_IMAGE_RATIO = 3
const IMAGE_SIZE_UNIT = 16
const MAX_REFERENCE_IMAGES = 6
const MAX_REFERENCE_IMAGE_SIZE = 10 * 1024 * 1024
const ACCEPTED_REFERENCE_IMAGE_TYPES = ['image/png', 'image/jpeg', 'image/webp']
const MAX_CONCURRENT_IMAGE_TASKS = 6
const CURRENT_IMAGE_TASK_IDS_KEY = 'currentImageTaskIds'
const MAX_TASK_POLLING_FAILURES = 3
const IMAGE_TASK_TIMEOUT_MS = 20 * 60 * 1000
const FINISHED_TASK_VISIBLE_MS = 60 * 1000

const sizeModeOptions = [
  { label: '自动', value: 'auto' },
  { label: '按比例', value: 'ratio' },
  { label: '自定义像素', value: 'custom' }
]
const baseSizeOptions = [
  { label: '1K', pixels: 1024 * 1024 },
  { label: '2K', pixels: 2048 * 2048 },
  { label: '4K', pixels: 3840 * 2160 }
]
const ratioOptions = [
  { label: '1:1', width: 1, height: 1 },
  { label: '3:2', width: 3, height: 2 },
  { label: '2:3', width: 2, height: 3 },
  { label: '16:9', width: 16, height: 9 },
  { label: '9:16', width: 9, height: 16 },
  { label: '4:3', width: 4, height: 3 },
  { label: '3:4', width: 3, height: 4 },
  { label: '21:9', width: 21, height: 9 }
]

const submitting = ref(false)
const historyLoading = ref(false)
const sizeDialogVisible = ref(false)
const historyList = ref([])
const taskList = ref([])
const currentTaskIds = ref([])
const referenceImageList = ref([])
const providerName = ref('')
const modelName = ref('')
let taskPollingFailureCount = 0
let taskPollingTimer = null
let finishedSlotReleaseTimer = null

const form = reactive({
  prompt: '',
  size: 'auto',
  n: 1
})
const sizeDraft = reactive({
  mode: 'auto',
  base: '4K',
  ratio: '16:9',
  customRatio: '',
  width: 1024,
  height: 1024
})

// 当前正在执行的生图任务数量
const runningTaskCount = computed(() => taskList.value.filter(item => isRunningImageTask(item)).length)

// 当前页面固定任务位中还空闲的数量
const emptyTaskSlotCount = computed(() => normalizeTaskSlots(currentTaskIds.value).filter(id => !id).length)

// 当前可以继续提交的任务数量
const availableTaskSlots = computed(() =>
  Math.max(0, Math.min(MAX_CONCURRENT_IMAGE_TASKS - runningTaskCount.value, emptyTaskSlotCount.value))
)

// 开始生成按钮是否禁用
const startButtonDisabled = computed(() => submitting.value || availableTaskSlots.value <= 0)

// 当前页面正在展示的任务
const activeDisplayTasks = computed(() => {
  return normalizeTaskSlots(currentTaskIds.value)
    .map(id => id ? taskList.value.find(task => String(task.id) === String(id)) : null)
    .filter(Boolean)
})

// 当前展示任务中已经结束的数量
const finishedDisplayTaskCount = computed(() =>
  activeDisplayTasks.value.filter(item => ['SUCCESS', 'FAILED'].includes(item.taskStatus)).length
)

// 固定 6 个任务卡片槽位
const displayTaskSlots = computed(() =>
  normalizeTaskSlots(currentTaskIds.value).map((id, index) => ({
    key: id || `empty-${index + 1}`,
    index: index + 1,
    task: id ? taskList.value.find(task => String(task.id) === String(id)) || null : null
  }))
)

// 生成结果说明文案
const resultText = computed(() => {
  if (runningTaskCount.value > 0) {
    return `正在生成中，运行任务 ${runningTaskCount.value} / ${MAX_CONCURRENT_IMAGE_TASKS}`
  }
  if (activeDisplayTasks.value.length > 0) {
    return `当前任务完成 ${finishedDisplayTaskCount.value} / ${activeDisplayTasks.value.length}`
  }
  return `最多同时生成 ${MAX_CONCURRENT_IMAGE_TASKS} 个任务`
})

// 当前模型展示文案
const modelText = computed(() => {
  if (!providerName.value && !modelName.value) {
    return ''
  }
  return `${providerName.value || '模型供应商'} / ${modelName.value || '未返回模型'}`
})

// 尺寸展示文案
const sizeDisplayText = computed(() => form.size === 'auto' ? '自动' : form.size)

// 尺寸模式说明
const sizeModeText = computed(() => form.size === 'auto' ? '由模型决定尺寸' : '已指定生成尺寸')

// 尺寸预览文案
const sizePreviewText = computed(() => {
  if (sizeDraft.mode === 'auto') {
    return 'auto'
  }
  if (sizeDraft.mode === 'ratio') {
    const ratio = getSelectedRatio()
    if (!ratio) {
      return '比例格式不正确'
    }
    const base = baseSizeOptions.find(item => item.label === sizeDraft.base) || baseSizeOptions[2]
    const pixels = calculateSizeByRatio(base.pixels, ratio.width, ratio.height)
    return `${pixels.width}x${pixels.height}`
  }
  const pixels = normalizePixels(sizeDraft.width || 1024, sizeDraft.height || 1024)
  return `${pixels.width}x${pixels.height}`
})

onMounted(() => {
  restoreCurrentTaskIds()
  loadHistory()
  loadImageTasks()
})

onBeforeUnmount(() => {
  stopTaskPolling()
  stopFinishedSlotReleaseTimer()
})

// 执行生图
async function handleGenerate() {
  const prompt = form.prompt.trim()
  if (!prompt) {
    ElMessage.warning('请输入生图提示词')
    return
  }
  if (availableTaskSlots.value <= 0) {
    ElMessage.warning('当前 6 个任务位已满，请等待任务完成后再提交')
    return
  }
  const submitCount = Math.min(form.n, availableTaskSlots.value)
  if (submitCount < form.n) {
    ElMessage.warning(`当前只剩 ${availableTaskSlots.value} 个任务位，本次将提交 ${submitCount} 个任务`)
  }
  try {
    submitting.value = true
    const referenceImages = await buildReferenceImagePayload()
    const requests = Array.from({ length: submitCount }, () => generateImage({
      prompt,
      size: form.size,
      n: 1,
      referenceImages
    }))
    const responses = await Promise.all(requests)
    appendCurrentTaskIds(responses.map(item => item.data?.id).filter(Boolean))
    ElMessage.success(`已提交 ${submitCount} 个生图任务`)
    await loadImageTasks()
    startTaskPolling()
  } catch (error) {
    console.error(error)
    ElMessage.error(error?.message || '生图任务提交失败')
  } finally {
    submitting.value = false
  }
}

// 加载生图任务
async function loadImageTasks() {
  try {
    const res = await getImageTasks(50)
    taskPollingFailureCount = 0
    taskList.value = res.data || []
    markTimeoutTasksFailed()
    releaseExpiredFinishedSlots()
    keepCurrentTaskIdsInTaskList()
    updateModelTextFromTasks()
    updateTaskPollingState()
  } catch (error) {
    console.error(error)
    handleTaskPollingFailure()
  }
}

// 读取当前页面关注的生图任务
function restoreCurrentTaskIds() {
  try {
    const ids = JSON.parse(sessionStorage.getItem(CURRENT_IMAGE_TASK_IDS_KEY) || '[]')
    currentTaskIds.value = normalizeTaskSlots(ids)
  } catch {
    currentTaskIds.value = buildEmptyTaskSlots()
  }
}

// 记录刚提交的生图任务，固定填充到第一个空闲任务位
function appendCurrentTaskIds(ids) {
  const slots = normalizeTaskSlots(currentTaskIds.value)
  ids.map(String).forEach(id => {
    const emptyIndex = slots.findIndex(item => !item)
    if (emptyIndex >= 0) {
      slots[emptyIndex] = id
    }
  })
  currentTaskIds.value = slots
  saveCurrentTaskIds()
}

// 清理任务列表里已经不存在的当前任务
function keepCurrentTaskIdsInTaskList() {
  if (currentTaskIds.value.length === 0) {
    return
  }
  const taskIds = new Set(taskList.value.map(item => String(item.id)))
  currentTaskIds.value = normalizeTaskSlots(currentTaskIds.value).map(id => id && taskIds.has(id) ? id : '')
  saveCurrentTaskIds()
}

// 创建 6 个固定任务空位
function buildEmptyTaskSlots() {
  return Array.from({ length: MAX_CONCURRENT_IMAGE_TASKS }, () => '')
}

// 把任意任务编号数组整理成固定 6 位
function normalizeTaskSlots(ids) {
  const slots = buildEmptyTaskSlots()
  if (!Array.isArray(ids)) {
    return slots
  }
  ids.slice(0, MAX_CONCURRENT_IMAGE_TASKS).forEach((id, index) => {
    slots[index] = id ? String(id) : ''
  })
  return slots
}

// 保存当前页面展示的固定任务位
function saveCurrentTaskIds() {
  sessionStorage.setItem(CURRENT_IMAGE_TASK_IDS_KEY, JSON.stringify(currentTaskIds.value))
}

// 释放已经结束一段时间的任务位
function releaseExpiredFinishedSlots() {
  let changed = false
  const now = Date.now()
  const nextSlots = normalizeTaskSlots(currentTaskIds.value).map(id => {
    if (!id) {
      return ''
    }
    const task = taskList.value.find(item => String(item.id) === String(id))
    if (!task || isRunningImageTask(task)) {
      return id
    }
    const finishedAt = new Date(task.finishedAt || 0).getTime()
    if (Number.isFinite(finishedAt) && finishedAt > 0 && now - finishedAt >= FINISHED_TASK_VISIBLE_MS) {
      changed = true
      return ''
    }
    return id
  })
  if (changed) {
    currentTaskIds.value = nextSlots
    saveCurrentTaskIds()
  }
}

// 根据任务列表更新模型名称
function updateModelTextFromTasks() {
  const successTask = taskList.value.find(item => item.result)
  if (!successTask?.result) {
    return
  }
  providerName.value = successTask.result.providerName || ''
  modelName.value = successTask.result.modelName || ''
}

// 根据任务状态刷新轮询
function updateTaskPollingState() {
  if (runningTaskCount.value > 0) {
    stopFinishedSlotReleaseTimer()
    startTaskPolling()
    return
  }
  stopTaskPolling()
  if (activeDisplayTasks.value.some(item => ['SUCCESS', 'FAILED'].includes(item.taskStatus))) {
    scheduleFinishedSlotRelease()
  }
  if (taskList.value.some(item => item.taskStatus === 'SUCCESS')) {
    loadHistory()
  }
}

// 处理任务轮询失败
function handleTaskPollingFailure() {
  taskPollingFailureCount += 1
  if (taskPollingFailureCount < MAX_TASK_POLLING_FAILURES) {
    return
  }
  markRunningTasksFailed('服务器连接中断，请刷新页面后重新提交任务')
  stopTaskPolling()
  ElMessage.error('生图任务连接中断，已停止等待')
}

// 超时任务本地标记为失败
function markTimeoutTasksFailed() {
  const now = Date.now()
  let hasTimeout = false
  taskList.value = taskList.value.map(task => {
    if (!isRunningImageTask(task)) {
      return task
    }
    const startTime = new Date(task.startedAt || task.createTime || 0).getTime()
    if (!Number.isFinite(startTime) || startTime <= 0 || now - startTime <= IMAGE_TASK_TIMEOUT_MS) {
      return task
    }
    hasTimeout = true
    return buildLocalFailedTask(task, '生成超时，请重新提交任务')
  })
  if (hasTimeout) {
    ElMessage.warning('有生图任务等待超时，已停止转圈')
  }
}

// 把运行中的任务标记为本地失败
function markRunningTasksFailed(message) {
  taskList.value = taskList.value.map(task => isRunningImageTask(task) ? buildLocalFailedTask(task, message) : task)
}

// 构造本地失败任务
function buildLocalFailedTask(task, message) {
  return {
    ...task,
    taskStatus: 'FAILED',
    taskMessage: message,
    errorMessage: message,
    progress: 100,
    finishedAt: new Date().toISOString()
  }
}

// 判断任务是否还在执行中
function isRunningImageTask(task) {
  return ['PENDING', 'RUNNING'].includes(task?.taskStatus)
}

// 获取任务中的第一张图片
function getTaskImage(task) {
  return task?.result?.images?.[0] || null
}

// 获取任务状态标签类型
function getTaskTagType(task) {
  if (task.taskStatus === 'SUCCESS') {
    return 'success'
  }
  if (task.taskStatus === 'FAILED') {
    return 'danger'
  }
  if (task.taskStatus === 'RUNNING') {
    return 'warning'
  }
  return 'info'
}

// 获取任务状态文案
function getTaskStatusText(task) {
  const textMap = {
    PENDING: '等待中',
    RUNNING: '生成中',
    SUCCESS: '已完成',
    FAILED: '失败'
  }
  return textMap[task.taskStatus] || task.taskStatus || '未知'
}

// 获取生图任务耗时展示文案
function getTaskDurationText(task) {
  const durationMs = calculateTaskDurationMs(task)
  return durationMs == null ? '--' : formatDuration(durationMs)
}

// 获取历史图片耗时展示文案
function getHistoryDurationText(history) {
  const task = findTaskByHistory(history)
  if (!task) {
    return '--'
  }
  return getTaskDurationText(task)
}

// 根据历史图片编号反查对应生图任务
function findTaskByHistory(history) {
  if (!history?.id) {
    return null
  }
  const historyId = String(history.id)
  return taskList.value.find(task =>
    Array.isArray(task?.result?.images)
    && task.result.images.some(image => String(image.historyId || '') === historyId)
  ) || null
}

// 计算任务耗时毫秒数
function calculateTaskDurationMs(task) {
  if (!task) {
    return null
  }
  const startTime = parseTimeMs(task.startedAt || task.createTime)
  if (!startTime) {
    return null
  }
  const endTime = isRunningImageTask(task) ? Date.now() : parseTimeMs(task.finishedAt)
  if (!endTime || endTime < startTime) {
    return null
  }
  return endTime - startTime
}

// 解析时间毫秒数
function parseTimeMs(value) {
  const time = new Date(value || 0).getTime()
  return Number.isFinite(time) && time > 0 ? time : null
}

// 格式化耗时
function formatDuration(durationMs) {
  const totalSeconds = Math.max(0, Math.round(durationMs / 1000))
  const minutes = Math.floor(totalSeconds / 60)
  const seconds = totalSeconds % 60
  if (minutes <= 0) {
    return `${seconds} 秒`
  }
  return `${minutes} 分 ${String(seconds).padStart(2, '0')} 秒`
}

// 启动任务轮询
function startTaskPolling() {
  if (taskPollingTimer) {
    return
  }
  taskPollingTimer = window.setInterval(() => {
    loadImageTasks()
  }, 3000)
}

// 停止任务轮询
function stopTaskPolling() {
  if (taskPollingTimer) {
    window.clearInterval(taskPollingTimer)
    taskPollingTimer = null
  }
}

// 安排完成或失败任务的卡位自动释放
function scheduleFinishedSlotRelease() {
  if (finishedSlotReleaseTimer) {
    return
  }
  finishedSlotReleaseTimer = window.setTimeout(() => {
    finishedSlotReleaseTimer = null
    releaseExpiredFinishedSlots()
  }, FINISHED_TASK_VISIBLE_MS + 500)
}

// 停止任务位自动释放定时器
function stopFinishedSlotReleaseTimer() {
  if (finishedSlotReleaseTimer) {
    window.clearTimeout(finishedSlotReleaseTimer)
    finishedSlotReleaseTimer = null
  }
}

// 加载生图历史
async function loadHistory() {
  try {
    historyLoading.value = true
    const res = await getImageHistory(50)
    historyList.value = res.data || []
  } catch (error) {
    console.error(error)
  } finally {
    historyLoading.value = false
  }
}

// 清空生图表单
function clearForm() {
  form.prompt = ''
  form.size = 'auto'
  form.n = 1
  referenceImageList.value = []
}

// 处理参考图选择
async function handleReferenceImageChange(file, fileList) {
  if (fileList.length > MAX_REFERENCE_IMAGES) {
    referenceImageList.value = fileList.slice(0, MAX_REFERENCE_IMAGES)
    ElMessage.warning('参考图最多只能上传 6 张')
    return
  }
  if (!validateReferenceFile(file.raw)) {
    referenceImageList.value = fileList.filter(item => item.uid !== file.uid)
    return
  }
  try {
    file.dataUrl = await readReferenceImageAsDataUrl(file.raw)
  } catch (error) {
    console.error(error)
    referenceImageList.value = fileList.filter(item => item.uid !== file.uid)
    ElMessage.error('参考图读取失败')
  }
}

// 处理参考图删除
function handleReferenceImageRemove(file, fileList) {
  referenceImageList.value = fileList
}

// 处理参考图超出数量
function handleReferenceImageExceed() {
  ElMessage.warning('参考图最多只能上传 6 张')
}

// 校验参考图文件
function validateReferenceFile(file) {
  if (!file) {
    ElMessage.warning('参考图文件无效')
    return false
  }
  if (!ACCEPTED_REFERENCE_IMAGE_TYPES.includes(file.type)) {
    ElMessage.warning('参考图只支持 PNG、JPG、WebP 格式')
    return false
  }
  if (file.size > MAX_REFERENCE_IMAGE_SIZE) {
    ElMessage.warning('单张参考图不能超过 10MB')
    return false
  }
  return true
}

// 读取参考图为 data URL
function readReferenceImageAsDataUrl(file) {
  return new Promise((resolve, reject) => {
    const reader = new FileReader()
    reader.onload = () => resolve(reader.result)
    reader.onerror = () => reject(reader.error)
    reader.readAsDataURL(file)
  })
}

// 构造参考图请求参数
async function buildReferenceImagePayload() {
  if (referenceImageList.value.length === 0) {
    return []
  }
  const images = []
  for (const file of referenceImageList.value) {
    if (file.dataUrl) {
      images.push(file.dataUrl)
    } else if (file.raw) {
      images.push(await readReferenceImageAsDataUrl(file.raw))
    }
  }
  return images
}

// 打开尺寸弹窗
function openSizeDialog() {
  if (form.size === 'auto') {
    sizeDraft.mode = 'auto'
  } else {
    const match = String(form.size).match(/^(\d+)x(\d+)$/)
    sizeDraft.mode = 'custom'
    if (match) {
      sizeDraft.width = Number(match[1])
      sizeDraft.height = Number(match[2])
    }
  }
  sizeDialogVisible.value = true
}

// 设置尺寸模式
function setSizeMode(mode) {
  sizeDraft.mode = mode
  if (mode === 'ratio' && sizeDraft.ratio === 'custom' && !sizeDraft.customRatio) {
    sizeDraft.ratio = '16:9'
  }
}

// 选择自定义比例
function selectCustomRatio() {
  sizeDraft.ratio = 'custom'
}

// 应用图片尺寸
function applyImageSize() {
  if (sizeDraft.mode === 'auto') {
    form.size = 'auto'
    sizeDialogVisible.value = false
    return
  }
  if (sizeDraft.mode === 'ratio') {
    const ratio = getSelectedRatio()
    if (!ratio) {
      ElMessage.warning('比例格式不正确，请使用 16:9 这类格式')
      return
    }
    const base = baseSizeOptions.find(item => item.label === sizeDraft.base) || baseSizeOptions[2]
    const pixels = calculateSizeByRatio(base.pixels, ratio.width, ratio.height)
    form.size = `${pixels.width}x${pixels.height}`
    sizeDialogVisible.value = false
    return
  }
  const pixels = normalizePixels(sizeDraft.width || 1024, sizeDraft.height || 1024)
  form.size = `${pixels.width}x${pixels.height}`
  sizeDialogVisible.value = false
}

// 获取当前选择的比例
function getSelectedRatio() {
  if (sizeDraft.ratio !== 'custom') {
    return ratioOptions.find(item => item.label === sizeDraft.ratio)
  }
  const match = String(sizeDraft.customRatio || '').trim().match(/^(\d+(?:\.\d+)?)\s*[:：x]\s*(\d+(?:\.\d+)?)$/)
  if (!match) {
    return null
  }
  const width = Number(match[1])
  const height = Number(match[2])
  if (!Number.isFinite(width) || !Number.isFinite(height) || width <= 0 || height <= 0) {
    return null
  }
  return { width, height }
}

// 根据比例和基准像素计算图片尺寸
function calculateSizeByRatio(targetPixels, ratioWidth, ratioHeight) {
  const width = Math.sqrt(targetPixels * ratioWidth / ratioHeight)
  const height = width * ratioHeight / ratioWidth
  return normalizePixels(width, height)
}

// 规范图片尺寸
function normalizePixels(rawWidth, rawHeight) {
  let width = roundToUnit(Number(rawWidth) || 1024)
  let height = roundToUnit(Number(rawHeight) || 1024)
  for (let i = 0; i < 8; i += 1) {
    const oldWidth = width
    const oldHeight = height
    const maxEdge = Math.max(width, height)
    if (maxEdge > MAX_IMAGE_EDGE) {
      const scale = MAX_IMAGE_EDGE / maxEdge
      width = floorToUnit(width * scale)
      height = floorToUnit(height * scale)
    }
    const ratio = width >= height ? width / height : height / width
    if (ratio > MAX_IMAGE_RATIO) {
      if (width >= height) {
        height = ceilToUnit(width / MAX_IMAGE_RATIO)
      } else {
        width = ceilToUnit(height / MAX_IMAGE_RATIO)
      }
    }
    const pixels = width * height
    if (pixels > MAX_IMAGE_PIXELS) {
      const scale = Math.sqrt(MAX_IMAGE_PIXELS / pixels)
      width = floorToUnit(width * scale)
      height = floorToUnit(height * scale)
    } else if (pixels < MIN_IMAGE_PIXELS) {
      const scale = Math.sqrt(MIN_IMAGE_PIXELS / pixels)
      width = ceilToUnit(width * scale)
      height = ceilToUnit(height * scale)
    }
    if (oldWidth === width && oldHeight === height) {
      break
    }
  }
  return { width, height }
}

// 四舍五入到模型尺寸单位
function roundToUnit(value) {
  return Math.max(IMAGE_SIZE_UNIT, Math.round(value / IMAGE_SIZE_UNIT) * IMAGE_SIZE_UNIT)
}

// 向下取整到模型尺寸单位
function floorToUnit(value) {
  return Math.max(IMAGE_SIZE_UNIT, Math.floor(value / IMAGE_SIZE_UNIT) * IMAGE_SIZE_UNIT)
}

// 向上取整到模型尺寸单位
function ceilToUnit(value) {
  return Math.max(IMAGE_SIZE_UNIT, Math.ceil(value / IMAGE_SIZE_UNIT) * IMAGE_SIZE_UNIT)
}

// 生成比例图标样式
function ratioIconStyle(item) {
  const longSide = 28
  if (item.width >= item.height) {
    return {
      width: `${longSide}px`,
      height: `${Math.max(10, longSide * item.height / item.width)}px`
    }
  }
  return {
    width: `${Math.max(10, longSide * item.width / item.height)}px`,
    height: `${longSide}px`
  }
}

// 获取图片展示地址
function getImageSrc(item) {
  if (item.viewUrl) {
    return buildAuthedImageUrl(item.viewUrl)
  }
  if (item.b64Json) {
    return buildBase64ImageSrc(item)
  }
  return item.url || ''
}

// 拼接带 token 的图片访问地址
function buildAuthedImageUrl(url) {
  const token = localStorage.getItem('token') || ''
  const joiner = url.includes('?') ? '&' : '?'
  return token ? `${url}${joiner}token=${encodeURIComponent(token)}` : url
}

// 拼接 base64 图片地址
function buildBase64ImageSrc(item) {
  return `data:${item.mimeType || 'image/png'};base64,${item.b64Json}`
}

// 获取图片下载扩展名
function getImageExtension(item) {
  const extensionMap = {
    'image/jpeg': 'jpg',
    'image/jpg': 'jpg',
    'image/png': 'png',
    'image/webp': 'webp',
    'image/gif': 'gif'
  }
  return extensionMap[item.mimeType || 'image/png'] || 'png'
}

// 打开图片
function openImage(item) {
  if (item.viewUrl) {
    window.open(buildAuthedImageUrl(item.viewUrl), '_blank')
    return
  }
  if (item.b64Json) {
    const blobUrl = buildBlobUrl(item)
    if (blobUrl) {
      window.open(blobUrl, '_blank')
    }
    return
  }
  const src = getImageSrc(item)
  if (src) {
    window.open(src, '_blank')
  }
}

// 下载图片
async function downloadImage(item, index) {
  if (item.downloadUrl) {
    window.open(buildAuthedImageUrl(item.downloadUrl), '_blank')
    return
  }
  const src = item.b64Json ? buildBase64ImageSrc(item) : getImageSrc(item)
  if (!src) {
    ElMessage.warning('图片地址为空')
    return
  }
  const link = document.createElement('a')
  link.href = src
  link.download = `ai-image-${index}.${getImageExtension(item)}`
  document.body.appendChild(link)
  link.click()
  document.body.removeChild(link)
}

// 创建 Blob 图片地址
function buildBlobUrl(item) {
  try {
    const byteCharacters = atob(item.b64Json)
    const byteNumbers = new Array(byteCharacters.length)
    for (let i = 0; i < byteCharacters.length; i += 1) {
      byteNumbers[i] = byteCharacters.charCodeAt(i)
    }
    const blob = new Blob([new Uint8Array(byteNumbers)], { type: item.mimeType || 'image/png' })
    return URL.createObjectURL(blob)
  } catch (error) {
    console.error(error)
    ElMessage.error('图片打开失败')
    return ''
  }
}

// 构造历史图片查看地址
function buildHistoryImageUrl(item) {
  return buildAuthedImageUrl(`/api/image/history/${item.id}/view`)
}

// 打开历史图片
function openHistoryImage(item) {
  window.open(buildHistoryImageUrl(item), '_blank')
}

// 下载历史图片
function downloadHistoryImage(item) {
  window.open(buildAuthedImageUrl(`/api/image/history/${item.id}/download`), '_blank')
}

// 删除历史图片
async function handleDeleteHistory(item) {
  try {
    await ElMessageBox.confirm('确定要删除这张生成图片吗？', '删除确认', {
      confirmButtonText: '删除',
      cancelButtonText: '取消',
      type: 'warning'
    })
  } catch {
    return
  }
  try {
    await deleteImageHistory(item.id)
    ElMessage.success('已删除')
    loadHistory()
  } catch (error) {
    console.error(error)
  }
}

// 格式化时间
function formatTime(value) {
  return value ? new Date(value).toLocaleString() : ''
}
</script>

<style scoped>
.image-hero {
  padding-bottom: 24px;
}

.image-layout {
  display: grid;
  grid-template-columns: 360px minmax(0, 1fr);
  align-items: start;
  gap: 16px;
}

.prompt-panel,
.result-panel {
  min-width: 0;
  padding: 22px;
  border-radius: 12px;
}

.panel-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 20px;
}

.panel-head h2 {
  margin: 0;
  color: var(--ink-color);
  font-size: 18px;
  font-weight: 850;
}

.panel-head p {
  margin-top: 6px;
  color: var(--muted-color);
  line-height: 1.6;
}

.image-form {
  display: grid;
  gap: 4px;
}

.image-form :deep(.el-input__count) {
  color: var(--muted-color);
  background: transparent;
}

.image-form :deep(.el-input-number),
.size-dialog-shell :deep(.el-input-number) {
  --el-input-bg-color: rgba(8, 12, 22, 0.34);
  --el-input-border-color: var(--line-color);
  --el-input-hover-border-color: rgba(245, 174, 70, 0.42);
  --el-input-focus-border-color: rgba(245, 174, 70, 0.72);
  --el-fill-color-light: rgba(8, 12, 22, 0.46);
  --el-border-color: var(--line-color);
  --el-text-color-regular: var(--ink-color);
}

.image-form :deep(.el-input-number__decrease),
.image-form :deep(.el-input-number__increase),
.size-dialog-shell :deep(.el-input-number__decrease),
.size-dialog-shell :deep(.el-input-number__increase) {
  border-color: var(--line-color);
  color: var(--muted-color);
  background: rgba(8, 12, 22, 0.46);
}

.image-form :deep(.el-input-number__decrease:hover),
.image-form :deep(.el-input-number__increase:hover),
.size-dialog-shell :deep(.el-input-number__decrease:hover),
.size-dialog-shell :deep(.el-input-number__increase:hover) {
  color: var(--primary-dark);
  background: rgba(245, 174, 70, 0.12);
}

.image-form :deep(.el-input__wrapper),
.size-dialog-shell :deep(.el-input__wrapper) {
  background: rgba(8, 12, 22, 0.34);
  box-shadow: 0 0 0 1px var(--line-color) inset;
}

.size-setting-trigger {
  width: 100%;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 13px 14px;
  border: 1px solid var(--line-color);
  border-radius: 10px;
  color: var(--ink-color);
  background: rgba(247, 245, 242, 0.04);
  text-align: left;
}

.size-setting-trigger span {
  display: grid;
  gap: 3px;
}

.size-setting-trigger strong {
  font-size: 15px;
  font-weight: 850;
}

.size-setting-trigger small,
.task-limit-tip {
  color: var(--muted-color);
  font-size: 12px;
}

.size-setting-trigger em {
  color: var(--primary-dark);
  font-size: 13px;
  font-style: normal;
  font-weight: 800;
}

.reference-upload-block {
  display: grid;
  gap: 10px;
}

.reference-upload-block p {
  color: var(--muted-color);
  font-size: 12px;
  line-height: 1.6;
}

.reference-upload :deep(.el-upload-list--picture-card) {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 8px;
}

.reference-upload :deep(.el-upload-list--picture-card .el-upload-list__item),
.reference-upload :deep(.el-upload--picture-card) {
  width: 100%;
  height: auto;
  aspect-ratio: 1 / 1;
  margin: 0;
  border-radius: 10px;
  border-color: var(--line-color);
  background: rgba(247, 245, 242, 0.035);
}

.reference-upload :deep(.el-upload-list__item-thumbnail) {
  object-fit: cover;
  background: #080a10;
}

.reference-upload-add {
  width: 100%;
  height: 100%;
  display: grid;
  place-items: center;
  color: var(--primary-dark);
  font-size: 26px;
  font-weight: 700;
}

.form-actions {
  display: flex;
  justify-content: flex-end;
  gap: 10px;
  margin-top: 18px;
}

.task-limit-tip {
  margin-top: 10px;
  text-align: right;
}

.result-panel {
  min-height: 760px;
  display: grid;
  grid-template-rows: auto 1fr;
}

.history-panel {
  margin-top: 16px;
  padding: 22px;
  border-radius: 12px;
}

.task-card-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  grid-template-rows: repeat(2, minmax(0, 1fr));
  gap: 16px;
  align-items: stretch;
}

.generate-task-card {
  overflow: hidden;
  min-height: 360px;
  display: grid;
  grid-template-rows: minmax(230px, 1fr) auto;
  border: 1px solid var(--line-color);
  border-radius: 12px;
  background: rgba(8, 10, 16, 0.42);
}

.generate-task-card.empty {
  min-height: 0;
  grid-template-rows: 1fr;
  border-style: dashed;
  background: rgba(8, 10, 16, 0.2);
}

.generate-task-card.running {
  border-color: rgba(229, 160, 68, 0.45);
}

.generate-task-card.success {
  border-color: rgba(74, 222, 128, 0.35);
}

.generate-task-card.failed {
  border-color: rgba(248, 113, 113, 0.45);
}

.task-card-media {
  position: relative;
  min-height: 230px;
  display: grid;
  place-items: center;
  background: rgba(8, 10, 16, 0.38);
}

.generate-task-card.empty .task-card-media {
  min-height: 0;
  place-items: center;
  padding: 24px;
}

.task-card-media img {
  width: 100%;
  height: 100%;
  min-height: 230px;
  display: block;
  object-fit: contain;
  background: #080a10;
}

.task-running,
.task-failed,
.task-empty {
  display: grid;
  place-items: center;
  gap: 10px;
  padding: 20px;
  text-align: center;
}

.task-running strong,
.task-failed strong,
.task-empty strong {
  color: var(--ink-color);
  font-size: 15px;
}

.task-running span,
.task-failed span,
.task-empty span {
  color: var(--muted-color);
  font-size: 12px;
  line-height: 1.6;
}

.task-card-body {
  display: grid;
  gap: 10px;
  padding: 12px;
}

.task-card-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
}

.task-card-head small {
  color: var(--muted-color);
  font-size: 12px;
}

.task-duration-line {
  display: inline-flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  min-height: 26px;
  padding: 5px 8px;
  border: 1px solid rgba(245, 174, 70, 0.18);
  border-radius: 8px;
  color: var(--muted-color);
  background: rgba(245, 174, 70, 0.06);
  font-size: 12px;
}

.task-duration-line strong {
  color: var(--primary-dark);
  font-weight: 850;
}

.task-card-body p {
  min-height: 42px;
  display: -webkit-box;
  overflow: hidden;
  color: var(--muted-color);
  font-size: 13px;
  line-height: 1.6;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 2;
}

.image-actions {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
}

.history-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 14px;
}

.history-card {
  overflow: hidden;
  display: grid;
  border: 1px solid var(--line-color);
  border-radius: 12px;
  background: rgba(8, 10, 16, 0.42);
}

.history-card img {
  width: 100%;
  aspect-ratio: 1 / 1;
  object-fit: cover;
  background: #080a10;
}

.history-info {
  display: grid;
  gap: 5px;
  padding: 12px 12px 4px;
}

.history-info strong,
.history-info span,
.history-info small {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.history-info strong {
  color: var(--ink-color);
  font-size: 13px;
}

.history-info span,
.history-info small {
  color: var(--muted-color);
  font-size: 12px;
}

.history-info .history-duration {
  color: var(--primary-dark);
  font-weight: 800;
}

.history-actions {
  display: flex;
  flex-wrap: wrap;
  justify-content: flex-end;
  gap: 6px;
  padding: 10px 12px 12px;
}

.loading-orbit {
  width: 58px;
  height: 58px;
  border: 1px solid rgba(229, 160, 68, 0.22);
  border-top-color: var(--primary-color);
  border-radius: 50%;
  animation: imageOrbit 1.1s linear infinite;
}

@keyframes imageOrbit {
  100% {
    transform: rotate(360deg);
  }
}

:global(.size-setting-dialog.el-dialog) {
  border-radius: 18px;
}

:global(.size-setting-dialog .el-dialog__header) {
  display: none;
}

:global(.size-setting-dialog .el-dialog__body) {
  padding: 0;
}

.size-dialog-shell {
  min-height: 640px;
  display: grid;
  grid-template-rows: auto auto 1fr auto;
  padding: 26px 28px 24px;
}

.size-dialog-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 22px;
}

.size-dialog-head h2 {
  margin: 0;
  color: var(--ink-color);
  font-size: 22px;
  font-weight: 900;
}

.size-dialog-close {
  width: 36px;
  height: 36px;
  border: 0;
  border-radius: 10px;
  color: var(--muted-color);
  background: transparent;
  font-size: 20px;
}

.size-mode-tabs {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 4px;
  padding: 4px;
  border-radius: 12px;
  background: rgba(247, 245, 242, 0.05);
}

.size-mode-tabs button {
  height: 46px;
  border: 0;
  border-radius: 9px;
  color: var(--muted-color);
  background: transparent;
  font-size: 15px;
  font-weight: 850;
}

.size-mode-tabs button.active {
  color: var(--ink-color);
  background: rgba(247, 245, 242, 0.1);
  box-shadow: inset 0 0 0 1px var(--line-color);
}

.auto-size-panel,
.ratio-size-panel,
.custom-size-panel {
  padding: 34px 0 18px;
}

.auto-size-panel {
  display: grid;
  place-items: center;
  align-content: center;
  text-align: center;
}

.auto-size-icon {
  width: 82px;
  height: 82px;
  display: grid;
  place-items: center;
  margin-bottom: 22px;
  border-radius: 50%;
  color: var(--primary-dark);
  background: rgba(229, 160, 68, 0.12);
  font-size: 24px;
  font-weight: 900;
}

.size-field-block {
  display: grid;
  gap: 12px;
  margin-bottom: 22px;
}

.size-field-block > label {
  color: var(--muted-color);
  font-size: 14px;
  font-weight: 850;
}

.base-size-options,
.ratio-options {
  display: grid;
  gap: 10px;
}

.base-size-options {
  grid-template-columns: repeat(3, minmax(0, 1fr));
}

.ratio-options {
  grid-template-columns: repeat(4, minmax(0, 1fr));
}

.base-size-options button,
.custom-ratio-button,
.ratio-options button {
  border: 1px solid var(--line-color);
  border-radius: 11px;
  color: var(--ink-color);
  background: rgba(247, 245, 242, 0.035);
  font-weight: 850;
}

.base-size-options button {
  min-height: 48px;
  font-size: 17px;
}

.ratio-options button {
  min-height: 84px;
  display: grid;
  place-items: center;
  align-content: center;
  gap: 9px;
  font-size: 14px;
}

.base-size-options button.active,
.custom-ratio-button.active,
.ratio-options button.active {
  color: var(--primary-dark);
  border-color: rgba(229, 160, 68, 0.76);
  background: rgba(229, 160, 68, 0.12);
}

.ratio-icon {
  display: block;
  border: 2px solid currentColor;
  border-radius: 4px;
  opacity: 0.76;
}

.custom-ratio-button {
  width: 100%;
  min-height: 44px;
  font-size: 15px;
}

.custom-pixel-grid {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 24px minmax(0, 1fr);
  align-items: end;
  gap: 12px;
}

.custom-pixel-grid :deep(.el-form-item) {
  margin-bottom: 0;
}

.custom-pixel-grid :deep(.el-input-number) {
  width: 100%;
}

.pixel-times {
  align-self: center;
  color: var(--subtle-color);
  font-size: 22px;
  text-align: center;
}

.size-dialog-foot {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 18px;
  padding-top: 20px;
}

.size-dialog-foot span {
  display: block;
  color: var(--muted-color);
  font-size: 13px;
  font-weight: 800;
}

.size-dialog-foot strong {
  display: block;
  margin-top: 8px;
  color: var(--ink-color);
  font-family: var(--font-mono);
  font-size: 24px;
  font-weight: 900;
}

.size-dialog-actions {
  display: grid;
  grid-template-columns: 140px 140px;
  gap: 12px;
}

@media (max-width: 1200px) {
  .task-card-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}

@media (max-width: 980px) {
  .image-layout {
    grid-template-columns: 1fr;
  }
}

@media (max-width: 680px) {
  .task-card-grid,
  .history-grid,
  .size-mode-tabs,
  .base-size-options,
  .ratio-options,
  .custom-pixel-grid,
  .size-dialog-actions {
    grid-template-columns: 1fr;
  }

  .panel-head,
  .form-actions,
  .size-dialog-foot {
    align-items: stretch;
    flex-direction: column;
  }

  :global(.size-setting-dialog.el-dialog) {
    width: calc(100% - 28px) !important;
  }

  .pixel-times {
    display: none;
  }
}
</style>
