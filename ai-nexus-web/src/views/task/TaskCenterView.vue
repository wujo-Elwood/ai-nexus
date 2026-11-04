<template>
  <div class="task-page page-shell">
    <!-- 页头 -->
    <header class="console-head">
      <div class="head-row">
        <div>
          <h1 class="serif">任务中心</h1>
          <p class="desc">集中查看文件处理、上传、抽取和生图任务；向量重建状态每 2 秒自动刷新。</p>
        </div>
        <el-button :loading="loading" @click="loadTasks">⟳ 刷新</el-button>
      </div>
      <div class="aurora-band"></div>
    </header>

    <!-- 读数列：当前任务分布 -->
    <div class="ledger">
      <div class="cell">
        <div class="k">进行中</div>
        <div class="v">{{ counts.processing }}</div>
      </div>
      <div class="cell">
        <div class="k">排队</div>
        <div class="v">{{ counts.pending }}</div>
      </div>
      <div class="cell">
        <div class="k">失败</div>
        <div class="v" :style="counts.failed > 0 ? 'color: var(--signal)' : ''">{{ counts.failed }}</div>
      </div>
      <div class="cell">
        <div class="k">已完成</div>
        <div class="v">{{ counts.done }}</div>
      </div>
    </div>

    <!-- 全局向量重建：一行状态条 -->
    <div class="rebuild-strip">
      <span class="rs-name mono">向量重建</span>
      <span class="st" :class="rebuildKind"><span class="dot" :class="rebuildKind"></span>{{ rebuildWord }}</span>
      <span class="linebar" :class="{ sig: rebuildStatus.status === 'FAILED' }"><i :style="{ width: rebuildPct + '%' }"></i></span>
      <span class="mono rs-meta">{{ rebuildStatus.completedFiles || 0 }} / {{ rebuildStatus.totalFiles || 0 }} 文件</span>
      <span v-if="rebuildStatus.failedFiles" class="mono rs-meta" style="color: var(--signal)">失败 {{ rebuildStatus.failedFiles }}</span>
      <span v-if="rebuildStatus.startedBy" class="mono rs-meta">发起 {{ rebuildStatus.startedBy }}</span>
    </div>
    <p v-if="rebuildStatus.error" class="rebuild-error">{{ rebuildStatus.error }}</p>

    <!-- 文字筛选 -->
    <div class="filter-row">
      <div class="filters">
        <a :class="{ on: filters.status === '' }" href="#" @click.prevent="setStatus('')">全部状态</a>
        <a :class="{ on: filters.status === 'PROCESSING' }" href="#" @click.prevent="setStatus('PROCESSING')">处理中</a>
        <a :class="{ on: filters.status === 'PENDING' }" href="#" @click.prevent="setStatus('PENDING')">排队</a>
        <a :class="{ on: filters.status === 'FAILED' }" href="#" @click.prevent="setStatus('FAILED')">失败</a>
        <a :class="{ on: filters.status === 'SUCCESS' }" href="#" @click.prevent="setStatus('SUCCESS')">成功</a>
        <a :class="{ on: filters.status === 'COMPLETED' }" href="#" @click.prevent="setStatus('COMPLETED')">完成</a>
      </div>
      <div class="filters">
        <a :class="{ on: filters.taskType === '' }" href="#" @click.prevent="setType('')">全部类型</a>
        <a
          v-for="item in types"
          :key="item.value"
          :class="{ on: filters.taskType === item.value }"
          href="#"
          @click.prevent="setType(item.value)"
        >{{ item.label }}</a>
      </div>
    </div>

    <!-- 任务目录表 -->
    <table class="task-table">
      <thead>
        <tr>
          <th class="col-no">NO.</th>
          <th style="width:110px">类型</th>
          <th>任务</th>
          <th style="width:110px">状态</th>
          <th style="width:190px">进度</th>
          <th style="width:220px">说明</th>
          <th style="width:110px; text-align:right">操作</th>
        </tr>
      </thead>
      <tbody>
        <tr v-for="(row, index) in tasks" :key="row.taskType + '-' + row.taskId">
          <td class="e-no mono">{{ String(index + 1).padStart(2, '0') }}</td>
          <td><span class="type-word mono">{{ typeLabel(row.taskType) }}</span></td>
          <td class="cell-name" :title="row.taskName">{{ row.taskName }}</td>
          <td>
            <span class="st" :class="statusKind(row.status)"><span class="dot" :class="statusKind(row.status)"></span>{{ statusText(row.status) }}</span>
          </td>
          <td>
            <div v-if="isDone(row)" class="mono pct-done">—</div>
            <div v-else class="progress-line">
              <span class="linebar"><i :style="{ width: progressOf(row) + '%' }"></i></span>
              <span class="mono pct">{{ progressOf(row) }}%</span>
            </div>
          </td>
          <td class="cell-desc" :title="row.message">{{ row.message }}</td>
          <td class="cell-op">
            <a v-if="row.retryable" class="op-link" href="#" @click.prevent="retry(row)">重试</a>
            <a v-if="row.cancellable" class="op-link danger" href="#" @click.prevent="cancel(row)">取消</a>
            <span v-if="!row.retryable && !row.cancellable" class="muted">—</span>
          </td>
        </tr>
      </tbody>
    </table>
    <div v-if="!loading && tasks.length === 0" class="empty">暂无任务</div>
  </div>
</template>

<script setup>
import { computed, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { cancelTask, getTasks, retryTask } from '../../api/tasks'
import { getGlobalRebuildStatus } from '../../api/kb'
import { mascot } from '../../composables/useMascot'
import { watch } from 'vue'

const loading = ref(false)
const tasks = ref([])
const rebuildStatus = ref({ status: 'COMPLETED', progress: 100 })
const filters = reactive({ status: '', taskType: '' })
const types = [
  { value: 'FILE', label: '文件处理' },
  { value: 'UPLOAD', label: '分片上传' },
  { value: 'EXTRACT', label: '文档抽取' },
  { value: 'IMAGE', label: 'AI 生图' }
]
let rebuildTimer = null

onMounted(async () => {
  await Promise.all([loadTasks(), loadRebuildStatus()])
  rebuildTimer = window.setInterval(loadRebuildStatus, 2000)
})

onBeforeUnmount(() => {
  if (rebuildTimer) {
    window.clearInterval(rebuildTimer)
    rebuildTimer = null
  }
})

// 当前任务分布（读数列）
const counts = computed(() => {
  const c = { processing: 0, pending: 0, failed: 0, done: 0 }
  for (const row of tasks.value) {
    if (row.status === 'PROCESSING') c.processing++
    else if (row.status === 'PENDING') c.pending++
    else if (row.status === 'FAILED') c.failed++
    else if (row.status === 'SUCCESS' || row.status === 'COMPLETED') c.done++
  }
  return c
})

const rebuildPct = computed(() => Math.min(100, Math.max(0, Number(rebuildStatus.value.progress || 0))))

const rebuildWord = computed(() => {
  if (rebuildStatus.value.rebuilding) return '重建中'
  if (rebuildStatus.value.status === 'FAILED') return '失败'
  return '已完成'
})

const rebuildKind = computed(() => {
  if (rebuildStatus.value.status === 'FAILED') return 'bad'
  if (rebuildStatus.value.rebuilding) return 'warn'
  return 'ok'
})

// 吉祥物：重建进行中才上岗推条，闲时不下班在页面上游荡；
// 重建完成的瞬间 → 彩纸 + 三连翻转庆祝
// 注意：重建状态每 2 秒轮询，watch 会反复触发——只有 rebuilding→false 的那一次才下班回家，
// 否则 leavePost 会反复把吉祥物从菜单栏拽到定居点
let prevRebuilding = null
watch(rebuildStatus, (st) => {
  const wasRebuilding = prevRebuilding === true
  prevRebuilding = !!st.rebuilding
  if (st.rebuilding) {
    const el = document.querySelector('.rebuild-strip .linebar')
    if (el) mascot.workAt(el, 'right', 'push')
  } else if (wasRebuilding) {
    mascot.leavePost()
    if ((st.totalFiles || 0) > 0) mascot.celebrate()
  }
}, { deep: true })

// 设置状态筛选
function setStatus(value) {
  filters.status = value
  loadTasks()
}

// 设置类型筛选
function setType(value) {
  filters.taskType = value
  loadTasks()
}

// 类型中文名
function typeLabel(value) {
  const hit = types.find(item => item.value === value)
  return hit ? hit.label : value
}

// 状态中文名
function statusText(status) {
  const map = {
    PROCESSING: '处理中',
    PENDING: '排队',
    FAILED: '失败',
    SUCCESS: '成功',
    COMPLETED: '完成'
  }
  return map[status] || status
}

// 状态语义（失败=告警朱砂；进行中=呼吸；其余排印色）
function statusKind(status) {
  if (status === 'FAILED') return 'bad'
  if (status === 'PROCESSING') return 'warn'
  if (status === 'SUCCESS' || status === 'COMPLETED') return 'ok'
  return 'idle'
}

function isDone(row) {
  return row.status === 'SUCCESS' || row.status === 'COMPLETED' || Number(row.progress || 0) >= 100
}

function progressOf(row) {
  return Math.min(100, Math.max(0, Number(row.progress || 0)))
}

// 加载统一任务
async function loadTasks() {
  loading.value = true
  try {
    const res = await getTasks(filters)
    tasks.value = res.data || []
  } catch (error) {
    console.error(error)
  } finally {
    loading.value = false
  }
}

// 加载全局向量重建状态
async function loadRebuildStatus() {
  try {
    const res = await getGlobalRebuildStatus()
    if (res.data) rebuildStatus.value = res.data
  } catch (error) {
    console.error(error)
  }
}

// 重试任务
async function retry(row) {
  try {
    await retryTask(row.taskType, row.taskId)
    ElMessage.success('已提交重试')
    await loadTasks()
  } catch (error) {
    console.error(error)
  }
}

// 取消任务
async function cancel(row) {
  try {
    await cancelTask(row.taskType, row.taskId)
    ElMessage.success('已取消')
    await loadTasks()
  } catch (error) {
    console.error(error)
  }
}
</script>

<style scoped>
/* 读数列与目录表之间的重建状态条 */
.rebuild-strip {
  display: flex;
  align-items: center;
  gap: 16px;
  padding: 14px 4px 16px;
  border-bottom: 1px solid var(--rule);
}

.rs-name {
  color: var(--text-2);
  font-size: 11px;
  letter-spacing: 0.18em;
}

.rebuild-strip .linebar {
  flex: 1;
}

.rs-meta {
  color: var(--text-3);
  font-size: 11px;
  letter-spacing: 0.06em;
  white-space: nowrap;
}

.rebuild-error {
  margin-top: 10px;
  color: var(--signal);
  font-size: 12.5px;
}

/* 文字筛选行 */
.filter-row {
  display: flex;
  align-items: baseline;
  gap: 28px;
  flex-wrap: wrap;
  padding: 22px 0 6px;
}

/* 任务目录表 */
.task-table {
  width: 100%;
  border-collapse: collapse;
  margin-top: 12px;
  border-top: 1px solid var(--rule-strong);
}

.task-table th {
  text-align: left;
  padding: 9px 10px;
  border-bottom: 1px solid var(--rule-strong);
  color: var(--text-3);
  font-family: var(--font-mono);
  font-size: 9.5px;
  font-weight: 500;
  letter-spacing: 0.22em;
  white-space: nowrap;
}

.task-table td {
  padding: 14px 10px;
  border-bottom: 1px solid rgba(236, 233, 225, 0.06);
  font-size: 13px;
  vertical-align: baseline;
}

.task-table tbody tr:hover td {
  background: var(--row-hover);
}

.col-no {
  width: 44px;
}

.type-word {
  color: var(--text-2);
  font-size: 11px;
  letter-spacing: 0.08em;
}

/* 任务名单行截断：长提示词不再撑高整表 */
.cell-name {
  max-width: 380px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.cell-desc {
  color: var(--text-3);
  font-size: 12px;
  max-width: 200px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.progress-line {
  display: flex;
  align-items: center;
  gap: 10px;
}

.progress-line .linebar {
  flex: 1;
  min-width: 70px;
}

.pct {
  color: var(--text-2);
  font-size: 11px;
  font-variant-numeric: tabular-nums;
}

.pct-done {
  color: var(--text-3);
  font-size: 12px;
}

.cell-op {
  text-align: right;
  white-space: nowrap;
}

.op-link {
  color: var(--text-1);
  font-size: 12.5px;
}

.op-link:hover {
  color: var(--signal);
  border-bottom: 1px solid var(--signal);
}

.op-link.danger {
  color: var(--text-2);
}

.op-link.danger:hover {
  color: var(--signal);
}

.empty {
  padding: 48px;
  text-align: center;
  color: var(--text-3);
}

@media (max-width: 900px) {
  .rebuild-strip {
    flex-wrap: wrap;
  }
}
</style>
