<template>
    <div class="extract-page page-shell">
      <section class="page-hero">
        <div>
          <span class="eyebrow">Document Extraction</span>
          <h1 class="section-title">文档结构化抽取</h1>
          <p class="section-desc">
            上传合同类 PDF 或 Word，选择抽取模板后生成字段结果，并保留原文片段、页码、置信度和人工校正记录。
          </p>
        </div>
        <div class="hero-action">
          <el-button :loading="taskLoading" type="primary" size="large" @click="handleCreateTask">
            <el-icon><Cpu /></el-icon>
            开始抽取
          </el-button>
          <!-- 任务在后台执行时展示轮询到的状态和进度 -->
          <p v-if="pollingTask" class="poll-hint">
            {{ getTaskStatusText(pollingTask.taskStatus) }} · {{ Number(pollingTask.progress || 0) }}%
          </p>
        </div>
      </section>

      <section class="extract-workbench">
        <div class="upload-panel glass-panel">
          <div class="panel-head">
            <div>
              <h2>上传与模板</h2>
              <p>第一版支持 PDF、DOC、DOCX，单个文件不超过 50MB。</p>
            </div>
            <el-button :loading="loading" @click="loadPageData">
              <el-icon><Refresh /></el-icon>
              刷新
            </el-button>
          </div>

          <el-upload
            drag
            :http-request="handleUploadRequest"
            :before-upload="beforeUpload"
            :show-file-list="false"
            accept=".pdf,.doc,.docx"
          >
            <el-icon class="upload-icon"><UploadFilled /></el-icon>
            <div class="upload-title">拖拽文件到这里，或点击上传</div>
            <div class="upload-tip">上传成功后会保存为待抽取文档</div>
          </el-upload>

          <div class="selected-box">
            <span>当前文件</span>
            <strong>{{ uploadedDocument?.fileName || '尚未上传' }}</strong>
          </div>

          <el-form label-position="top">
            <el-form-item label="抽取模板">
              <el-select
                v-model="selectedTemplateId"
                placeholder="请选择抽取模板"
                size="large"
                class="template-select"
              >
                <el-option
                  v-for="template in templates"
                  :key="template.id"
                  :label="template.templateName"
                  :value="template.id"
                />
              </el-select>
            </el-form-item>
          </el-form>

          <div class="template-desc">
            {{ selectedTemplate?.description || '选择模板后，系统会按模板字段调用模型抽取结构化结果。' }}
          </div>
          <div v-if="selectedTemplate" class="template-actions">
            <span>当前模板：{{ selectedTemplate.templateName }}</span>
            <el-button type="danger" text @click="handleDeleteTemplate(selectedTemplate)">
              删除模板
            </el-button>
          </div>
        </div>

        <div class="summary-panel">
          <div class="summary-card document-card">
            <span>抽取任务</span>
            <strong>{{ tasks.length }}</strong>
          </div>
          <div class="summary-card warning-card">
            <span>需关注字段</span>
            <strong>{{ resultStats.needReview }}</strong>
          </div>
          <div class="summary-card success-card">
            <span>已校正字段</span>
            <strong>{{ resultStats.modified }}</strong>
          </div>
        </div>
      </section>

      <section class="task-panel glass-panel">
        <div class="panel-head">
          <div>
            <h2>任务列表</h2>
            <p>任务完成后进入复核状态，可查看字段结果并导出。</p>
          </div>
        </div>

        <el-empty v-if="!loading && tasks.length === 0" description="暂无抽取任务，请先上传文档并开始抽取" />
        <el-table v-else v-loading="loading" :data="tasks" style="width: 100%">
          <el-table-column prop="documentName" label="文件名称" min-width="240">
            <template #default="{ row }">
              <div class="name-cell">
                <el-icon><Document /></el-icon>
                <span>{{ row.documentName || '未知文件' }}</span>
              </div>
            </template>
          </el-table-column>
          <el-table-column prop="templateName" label="模板" min-width="180" />
          <el-table-column prop="taskStatus" label="状态" width="130">
            <template #default="{ row }">
              <el-tag :type="getTaskStatusType(row.taskStatus)" round>
                {{ getTaskStatusText(row.taskStatus) }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="createTime" label="创建时间" width="180">
            <template #default="{ row }">
              {{ formatDate(row.createTime) }}
            </template>
          </el-table-column>
          <el-table-column label="操作" width="300" fixed="right">
            <template #default="{ row }">
              <el-button type="primary" text @click="handleViewResults(row)">
                查看结果
              </el-button>
              <el-button text @click="handleExport(row, 'json')">
                JSON
              </el-button>
              <el-button text @click="handleExport(row, 'excel')">
                Excel
              </el-button>
            </template>
          </el-table-column>
        </el-table>
      </section>

      <section class="review-layout">
        <div class="result-panel glass-panel">
          <div class="panel-head">
            <div>
              <h2>字段校正</h2>
              <p>{{ selectedTask ? `当前任务：${selectedTask.documentName || selectedTask.id}` : '请选择一个任务查看结果。' }}</p>
            </div>
          </div>

          <el-empty v-if="!selectedTask" description="请选择任务" />
          <el-empty v-else-if="!resultLoading && results.length === 0" description="当前任务暂无结果" />
          <el-table
            v-else
            v-loading="resultLoading"
            :data="results"
            height="440"
            style="width: 100%"
            highlight-current-row
            @row-click="handleSelectResult"
          >
            <el-table-column prop="fieldName" label="字段" width="130" />
            <el-table-column label="字段值" min-width="260">
              <template #default="{ row }">
                <el-input v-model="row.fieldValue" placeholder="请输入校正后的字段值" />
              </template>
            </el-table-column>
            <el-table-column prop="confidence" label="置信度" width="100">
              <template #default="{ row }">
                {{ formatConfidence(row.confidence) }}
              </template>
            </el-table-column>
            <el-table-column prop="resultStatus" label="状态" width="110">
              <template #default="{ row }">
                <el-tag :type="getResultStatusType(row.resultStatus)" round>
                  {{ getResultStatusText(row.resultStatus) }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="100" fixed="right">
              <template #default="{ row }">
                <el-button type="primary" text @click.stop="handleSaveResult(row)">
                  保存
                </el-button>
              </template>
            </el-table-column>
          </el-table>
        </div>

        <aside class="evidence-panel glass-panel">
          <div class="panel-head compact-head">
            <div>
              <h2>原文证据</h2>
              <p>查看模型给出的字段依据。</p>
            </div>
          </div>

          <div v-if="selectedResult" class="evidence-content">
            <div class="evidence-meta">
              <span>字段</span>
              <strong>{{ selectedResult.fieldName }}</strong>
            </div>
            <div class="evidence-tags">
              <el-tag>{{ `第 ${selectedResult.pageNo || 1} 页` }}</el-tag>
              <el-tag :type="getResultStatusType(selectedResult.resultStatus)">
                {{ getResultStatusText(selectedResult.resultStatus) }}
              </el-tag>
              <el-tag type="info">{{ formatConfidence(selectedResult.confidence) }}</el-tag>
            </div>
            <div class="raw-text-box">
              {{ selectedResult.rawText || '模型没有返回原文片段，需要人工核对。' }}
            </div>
          </div>

          <el-empty v-else description="点击字段行查看证据" />
        </aside>
      </section>
    </div>
</template>

<script setup>
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  Cpu,
  Document,
  Refresh,
  UploadFilled
} from '@element-plus/icons-vue'
import {
  createExtractTask,
  deleteExtractTemplate,
  exportExtractTask,
  getExtractResults,
  getExtractTask,
  getExtractTasks,
  getExtractTemplates,
  updateExtractResult,
  uploadExtractDocument
} from '../../api/extract'
import { mascot } from '../../composables/useMascot'

// 后端已改为后台线程执行任务，前端只需轮询这几种进行中状态
const TASK_RUNNING_STATUS = ['PENDING', 'PARSING', 'EXTRACTING', 'SAVING']
// REVIEWING / SUCCESS 都是后端认定的完成态，进入后就不再轮询
const TASK_DONE_STATUS = ['REVIEWING', 'SUCCESS']
// 轮询间隔，2 秒一次足够跟上后端进度变化
const TASK_POLL_INTERVAL = 2000

const templates = ref([])
const selectedTemplateId = ref(null)
const uploadedDocument = ref(null)
const tasks = ref([])
const results = ref([])
const selectedTask = ref(null)
const selectedResult = ref(null)
const loading = ref(false)
const taskLoading = ref(false)
const resultLoading = ref(false)
// 正在轮询的任务编号和最近一次拿到的任务详情
const pollingTaskId = ref(null)
const pollingTask = ref(null)
let taskTimer = null

const selectedTemplate = computed(() => {
  // 第1步：按选中的模板编号查找模板
  return templates.value.find(template => template.id === selectedTemplateId.value)
})

const resultStats = computed(() => {
  // 第1步：统计当前结果里需要关注和已经修改的字段
  return results.value.reduce((stats, result) => {
    if (['WARNING', 'ERROR', 'MISSING'].includes(result.resultStatus)) {
      stats.needReview += 1
    }
    if (result.isModified || result.resultStatus === 'MODIFIED') {
      stats.modified += 1
    }
    return stats
  }, { needReview: 0, modified: 0 })
})

onMounted(() => {
  loadPageData()
})

onBeforeUnmount(() => {
  // 离开抽取页面时停止轮询，避免后台一直请求
  stopTaskPolling()
})

// 加载页面基础数据
async function loadPageData() {
  try {
    // 第1步：打开页面加载状态
    loading.value = true
    // 第2步：并行读取模板和任务
    await Promise.all([loadTemplates(), loadTasks()])
  } catch (error) {
    // 第3步：加载失败时记录错误
    console.error(error)
  } finally {
    // 第4步：关闭页面加载状态
    loading.value = false
  }
}

// 加载抽取模板列表
async function loadTemplates() {
  // 第1步：请求模板接口
  const res = await getExtractTemplates()
  // 第2步：保存模板列表
  templates.value = res.data || []
  // 第3步：没有选中模板时默认选第一个
  if (!selectedTemplateId.value && templates.value.length > 0) {
    selectedTemplateId.value = templates.value[0].id
  }
}

// 删除当前抽取模板
async function handleDeleteTemplate(template) {
  try {
    await ElMessageBox.confirm(`确定删除模板「${template.templateName}」吗？`, '删除模板确认', {
      confirmButtonText: '删除',
      cancelButtonText: '取消',
      type: 'warning'
    })
    await deleteExtractTemplate(template.id)
    ElMessage.success('模板已删除')
    selectedTemplateId.value = null
    await loadTemplates()
  } catch (error) {
    if (error !== 'cancel') console.error(error)
  }
}

// 加载抽取任务列表
async function loadTasks() {
  // 第1步：请求任务接口
  const res = await getExtractTasks()
  // 第2步：保存任务列表
  tasks.value = res.data || []
  // 第3步：刷新后如果还有任务在后台跑，自动接着轮询它的进度
  if (!pollingTaskId.value) {
    const runningTask = tasks.value.find(item => TASK_RUNNING_STATUS.includes(item.taskStatus))
    if (runningTask) {
      startTaskPolling(runningTask.id)
    }
  }
}

// 开始轮询任务状态
function startTaskPolling(taskId) {
  // 第1步：先清掉旧定时器，保证同时只轮询一个任务
  stopTaskPolling()
  // 第2步：记下任务编号并立即查一次当前状态
  pollingTaskId.value = taskId
  pollTaskOnce()
  // 第3步：启动定时器，2 秒一次跟进进度
  taskTimer = window.setInterval(pollTaskOnce, TASK_POLL_INTERVAL)
}

// 停止轮询（任务进入终态或组件卸载时调用）
function stopTaskPolling() {
  if (taskTimer) {
    window.clearInterval(taskTimer)
    taskTimer = null
  }
  pollingTaskId.value = null
  pollingTask.value = null
}

// 查询一次任务详情，推进轮询状态
async function pollTaskOnce() {
  const taskId = pollingTaskId.value
  if (!taskId) {
    return
  }
  // 页面不可见时跳过本次请求，回到前台后下一轮继续
  if (document.hidden) {
    return
  }
  try {
    // 第1步：读取任务最新状态和进度
    const res = await getExtractTask(taskId)
    const task = res.data
    if (!task) {
      return
    }
    pollingTask.value = task
    // 第2步：任务失败，展示后端返回的错误信息并收尾
    if (task.taskStatus === 'FAILED') {
      stopTaskPolling()
      taskLoading.value = false
      ElMessage.error(task.errorMessage || task.taskMessage || '抽取任务失败')
      await loadTasks()
      return
    }
    // 第3步：任务已完成，拉取结果并渲染，同时收尾
    if (TASK_DONE_STATUS.includes(task.taskStatus)) {
      stopTaskPolling()
      taskLoading.value = false
      ElMessage.success('抽取完成，等待复核')
      await loadTasks()
      const row = tasks.value.find(item => item.id === task.id) || task
      await handleViewResults(row)
    }
  } catch (error) {
    // 第4步：单次轮询失败不终止流程，等下一轮重试
    console.error(error)
  }
}

// 上传前校验文件
function beforeUpload(file) {
  // 第1步：定义允许的扩展名
  const allowedExts = ['.pdf', '.doc', '.docx']
  // 第2步：读取当前文件扩展名
  const ext = file.name ? file.name.substring(file.name.lastIndexOf('.')).toLowerCase() : ''
  // 第3步：校验文件格式
  if (!allowedExts.includes(ext)) {
    ElMessage.error('只支持 PDF、DOC、DOCX 文件')
    return false
  }
  // 第4步：校验文件大小
  if (file.size > 50 * 1024 * 1024) {
    ElMessage.error('文件大小不能超过 50MB')
    return false
  }
  // 第5步：校验通过允许上传
  return true
}

// 执行自定义上传请求
async function handleUploadRequest(options) {
  // 第0步：吉祥物上岗扛文件
  const panel = document.querySelector('.upload-panel')
  if (panel) mascot.workAt(panel, 'right', 'carry')
  try {
    // 第1步：调用上传接口
    const res = await uploadExtractDocument(options.file)
    // 第2步：保存上传后的文档
    handleUploadSuccess(res)
    // 第3步：通知上传组件成功
    options.onSuccess(res)
  } catch (error) {
    // 第4步：通知上传组件失败
    options.onError(error)
    mascot.playAction('slip')   // 扛不住摔了一跤
    mascot.speak('都怪网络！又不是我的错…')
  } finally {
    // 第5步：无论成败，下氘回侧栏
    mascot.leavePost()
  }
}

// 处理上传成功
function handleUploadSuccess(response) {
  // 第1步：保存后端返回的文档信息
  uploadedDocument.value = response.data
  // 第2步：提示用户上传成功
  ElMessage.success('上传成功，可以开始抽取')
}

// 创建抽取任务
async function handleCreateTask() {
  // 第1步：校验是否已经上传文档
  if (!uploadedDocument.value?.id) {
    ElMessage.warning('请先上传文档')
    return
  }
  // 第2步：校验是否已经选择模板
  if (!selectedTemplateId.value) {
    ElMessage.warning('请选择抽取模板')
    return
  }
  try {
    // 第3步：提交抽取任务，后端立刻返回待处理任务，不再同步等待抽取完成
    taskLoading.value = true
    const res = await createExtractTask({
      documentId: uploadedDocument.value.id,
      templateId: selectedTemplateId.value
    })
    // 第4步：提示已提交并刷新任务列表
    ElMessage.success('抽取任务已提交，正在后台处理')
    await loadTasks()
    // 第5步：改为轮询任务状态，结束后（成功/失败）才关闭 loading
    if (res.data?.id) {
      startTaskPolling(res.data.id)
      return
    }
    taskLoading.value = false
  } catch (error) {
    // 第6步：提交失败时记录错误并关闭提交状态
    console.error(error)
    taskLoading.value = false
  }
}

// 查看任务结果
async function handleViewResults(row) {
  try {
    // 第1步：保存当前选中的任务
    selectedTask.value = row
    // 第2步：打开结果加载状态
    resultLoading.value = true
    // 第3步：读取任务字段结果
    const res = await getExtractResults(row.id)
    // 第4步：保存字段结果并默认选中第一行
    results.value = res.data || []
    selectedResult.value = results.value[0] || null
  } catch (error) {
    // 第5步：加载失败时记录错误
    console.error(error)
  } finally {
    // 第6步：关闭结果加载状态
    resultLoading.value = false
  }
}

// 选择字段结果
function handleSelectResult(row) {
  // 第1步：保存当前字段作为证据面板来源
  selectedResult.value = row
}

// 保存人工校正结果
async function handleSaveResult(row) {
  try {
    // 第1步：提交人工校正值
    await updateExtractResult(row.id, {
      newValue: row.fieldValue || '',
      remark: '前端人工校正'
    })
    // 第2步：更新当前行状态
    row.resultStatus = 'MODIFIED'
    row.isModified = true
    // 第3步：刷新证据面板引用
    selectedResult.value = row
    // 第4步：提示保存成功
    ElMessage.success('校正已保存')
  } catch (error) {
    // 第5步：保存失败时记录错误
    console.error(error)
  }
}

// 导出任务结果
async function handleExport(row, exportType) {
  try {
    // 第1步：请求后端生成导出文件
    const response = await exportExtractTask(row.id, exportType)
    // 第2步：根据导出类型生成文件名
    const fileName = exportType === 'excel'
      ? `extract-task-${row.id}.xlsx`
      : `extract-task-${row.id}.json`
    // 第3步：下载二进制内容
    downloadBlob(response.data, fileName)
  } catch (error) {
    // 第4步：导出失败时记录错误
    console.error(error)
  }
}

// 下载二进制文件
function downloadBlob(blob, fileName) {
  // 第1步：创建临时下载地址
  const url = window.URL.createObjectURL(blob)
  // 第2步：创建临时链接并触发点击
  const link = document.createElement('a')
  link.href = url
  link.download = fileName
  document.body.appendChild(link)
  link.click()
  // 第3步：清理临时链接和地址
  document.body.removeChild(link)
  window.URL.revokeObjectURL(url)
}

// 获取任务状态标签类型
function getTaskStatusType(status) {
  // 第1步：按任务状态映射标签类型
  const types = {
    PENDING: 'info',
    PARSING: 'warning',
    EXTRACTING: 'warning',
    SAVING: 'warning',
    REVIEWING: 'primary',
    SUCCESS: 'success',
    FAILED: 'danger'
  }
  // 第2步：未知状态默认显示 info
  return types[status] || 'info'
}

// 获取任务状态中文文案
function getTaskStatusText(status) {
  // 第1步：按任务状态映射中文名称
  const texts = {
    PENDING: '待处理',
    PARSING: '解析中',
    EXTRACTING: '抽取中',
    SAVING: '保存中',
    REVIEWING: '待复核',
    SUCCESS: '已完成',
    FAILED: '失败'
  }
  // 第2步：未知状态显示原始值
  return texts[status] || status
}

// 获取结果状态标签类型
function getResultStatusType(status) {
  // 第1步：按字段结果状态映射标签类型
  const types = {
    SUCCESS: 'success',
    WARNING: 'warning',
    ERROR: 'danger',
    MISSING: 'danger',
    MODIFIED: 'primary',
    IGNORED: 'info'
  }
  // 第2步：未知状态默认显示 info
  return types[status] || 'info'
}

// 获取结果状态中文文案
function getResultStatusText(status) {
  // 第1步：按字段结果状态映射中文名称
  const texts = {
    SUCCESS: '正常',
    WARNING: '低置信',
    ERROR: '校验失败',
    MISSING: '缺失',
    MODIFIED: '已校正',
    IGNORED: '已忽略'
  }
  // 第2步：未知状态显示原始值
  return texts[status] || status
}

// 格式化置信度
function formatConfidence(value) {
  // 第1步：空值显示 0%
  if (value === null || value === undefined || value === '') {
    return '0%'
  }
  // 第2步：转换为百分比
  return `${Math.round(Number(value) * 100)}%`
}

// 格式化日期
function formatDate(dateStr) {
  // 第1步：空日期直接返回空字符串
  if (!dateStr) {
    return ''
  }
  // 第2步：按中文日期时间显示
  return new Date(dateStr).toLocaleString('zh-CN')
}
</script>

<style scoped>
.extract-workbench {
  display: grid;
  grid-template-columns: minmax(0, 1.7fr) minmax(280px, 0.8fr);
  gap: 18px;
  margin-bottom: 20px;
}

.upload-panel,
.task-panel,
.result-panel,
.evidence-panel {
  padding: 22px;
}

.panel-head {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: 12px;
  margin-bottom: 18px;
}

.panel-head h2 {
  color: var(--ink-color);
  font-size: 22px;
  font-weight: 900;
}

.panel-head p {
  margin-top: 6px;
  color: var(--muted-color);
  line-height: 1.6;
}

.compact-head {
  margin-bottom: 14px;
}

.upload-panel :deep(.el-upload-dragger) {
  padding: 38px 18px;
  border: 1px dashed rgba(236, 233, 225, 0.48);
  border-radius: 0;
  background:
    radial-gradient(circle at 50% 0%, rgba(236, 233, 225, 0.14), transparent 34%),
    rgba(8, 12, 22, 0.34);
}

.upload-icon {
  color: var(--primary-color);
  font-size: 48px;
}

/* 开始抽取按钮 + 轮询进度提示 */
.hero-action {
  display: flex;
  flex-direction: column;
  align-items: flex-end;
  gap: 8px;
}

.poll-hint {
  color: var(--muted-color);
  font-size: 12.5px;
}

.upload-title {
  margin-top: 14px;
  color: var(--ink-color);
  font-size: 18px;
  font-weight: 900;
}

.upload-tip {
  margin-top: 8px;
  color: var(--muted-color);
}

.selected-box {
  display: grid;
  gap: 6px;
  margin: 16px 0;
  padding: 16px;
  border: 1px solid rgba(236, 233, 225, 0.18);
  border-radius: 0;
  background: rgba(8, 12, 22, 0.34);
}

.selected-box span,
.template-desc {
  color: var(--muted-color);
}

.selected-box strong {
  overflow: hidden;
  color: var(--ink-color);
  text-overflow: ellipsis;
  white-space: nowrap;
}

.template-select {
  width: 100%;
}

.template-desc {
  min-height: 46px;
  padding: 14px 16px;
  border-radius: 0;
  background: rgba(21, 94, 239, 0.07);
  line-height: 1.7;
}

.template-actions {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-top: 12px;
  padding-top: 12px;
  border-top: 1px solid var(--line-color);
  color: var(--muted-color);
  font-size: 13px;
}

.summary-panel {
  display: grid;
  gap: 14px;
}

.summary-card {
  min-height: 96px;
  padding: 16px 20px;
  border: 1px solid var(--amber-line-dim);
  border-radius: 0;
  color: var(--text-1);
  background: var(--panel);
  box-shadow: none;
}

.document-card,
.warning-card,
.success-card {
  background: var(--panel);
}

.summary-card span {
  color: var(--text-3);
  font-size: 11.5px;
}

.summary-card strong {
  display: block;
  margin-top: 6px;
  color: var(--amber-hi);
  font-family: var(--font-mono);
  font-feature-settings: "zero" off;
  font-size: 26px;
  line-height: 1.15;
  font-weight: 600;
  text-shadow: 0 0 14px rgba(236, 233, 225, 0.35);
}

.task-panel {
  margin-bottom: 20px;
}

.name-cell {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
  font-weight: 800;
}

.name-cell span {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.review-layout {
  display: grid;
  grid-template-columns: minmax(0, 1.8fr) minmax(300px, 0.8fr);
  gap: 18px;
}

.evidence-content {
  display: grid;
  gap: 16px;
}

.evidence-meta {
  display: grid;
  gap: 8px;
}

.evidence-meta span {
  color: var(--muted-color);
}

.evidence-meta strong {
  font-size: 20px;
  color: var(--ink-color);
}

.evidence-tags {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.raw-text-box {
  min-height: 260px;
  padding: 18px;
  border: 1px solid var(--line-color);
  border-radius: 0;
  color: var(--ink-color);
  background: rgba(248, 250, 252, 0.9);
  line-height: 1.8;
  white-space: pre-wrap;
}

@media (max-width: 980px) {
  .extract-workbench,
  .review-layout {
    grid-template-columns: 1fr;
  }

  .page-hero,
  .panel-head {
    flex-direction: column;
    align-items: stretch;
  }
}
</style>
