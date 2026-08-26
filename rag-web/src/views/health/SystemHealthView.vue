<template>
  <div class="health-page page-shell">
    <section class="page-hero health-hero">
      <div>
        <span class="eyebrow">System Health</span>
        <h1 class="section-title">系统健康</h1>
        <p class="section-desc">查看基础服务、存储空间和后台线程池的实时状态。</p>
      </div>
      <div class="health-actions">
        <span v-if="checkedAt" class="checked-time">最后检查：{{ formatDateTime(checkedAt) }}</span>
        <el-button :loading="loading" :icon="Refresh" @click="loadHealth">刷新检查</el-button>
      </div>
    </section>

    <section class="health-summary glass-panel">
      <div class="summary-status" :class="statusClass(overallStatus)">
        <el-icon :size="28"><component :is="statusIcon(overallStatus)" /></el-icon>
        <div>
          <span>系统总体状态</span>
          <strong>{{ statusLabel(overallStatus) }}</strong>
        </div>
      </div>
      <div class="summary-note">页面每 30 秒自动检查一次，异常组件不会暴露敏感配置。</div>
    </section>

    <section class="component-grid">
      <article v-for="component in componentList" :key="component.key" class="health-card">
        <div class="health-card-head">
          <div>
            <span class="health-card-label">{{ component.label }}</span>
            <strong>{{ statusLabel(component.status) }}</strong>
          </div>
          <el-icon :class="['status-icon', statusClass(component.status)]" :size="24">
            <component :is="statusIcon(component.status)" />
          </el-icon>
        </div>
        <p class="health-message">{{ component.message || '暂无状态说明' }}</p>
        <div class="health-card-foot">
          <span>响应时间</span>
          <strong>{{ component.latencyMs ?? '-' }} ms</strong>
        </div>
        <div v-if="component.key === 'disk' && component.details" class="disk-meter">
          <div class="disk-meter-head">
            <span>磁盘使用率</span>
            <strong>{{ component.details.usagePercent ?? 0 }}%</strong>
          </div>
          <el-progress :percentage="Number(component.details.usagePercent || 0)" :status="diskProgressStatus(component)" :show-text="false" />
          <span class="disk-capacity">可用 {{ formatBytes(component.details.freeBytes) }} / {{ formatBytes(component.details.totalBytes) }}</span>
        </div>
        <div v-if="component.key === 'llm' && component.details?.model" class="provider-line">
          {{ component.details.provider }} · {{ component.details.model }}
        </div>
      </article>
      <div v-if="componentList.length === 0 && !loading" class="empty-health glass-panel">暂无健康检查数据</div>
    </section>

    <section class="thread-section glass-panel">
      <div class="section-heading">
        <div>
          <h2>线程池状态</h2>
          <p>展示后台任务和流式响应的实时处理能力。</p>
        </div>
      </div>
      <el-table v-if="threadPoolList.length > 0" :data="threadPoolList" stripe>
        <el-table-column prop="name" label="线程池" min-width="190" />
        <el-table-column label="状态" width="120">
          <template #default="scope">
            <span :class="['table-status', statusClass(scope.row.status)]">{{ statusLabel(scope.row.status) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="活动 / 当前 / 最大" min-width="180">
          <template #default="scope">{{ scope.row.activeCount }} / {{ scope.row.poolSize }} / {{ scope.row.maximumPoolSize }}</template>
        </el-table-column>
        <el-table-column prop="queueSize" label="等待队列" width="120" />
        <el-table-column prop="completedTaskCount" label="已完成任务" width="140" />
      </el-table>
      <div v-else class="empty-table">暂无线程池指标</div>
    </section>
  </div>
</template>

<script setup>
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { CircleCheckFilled, CircleCloseFilled, QuestionFilled, Refresh, WarningFilled } from '@element-plus/icons-vue'
import { getSystemHealth } from '../../api/health'

const loading = ref(false)
const overallStatus = ref('NOT_CONFIGURED')
const checkedAt = ref('')
const components = ref({})
const threadPools = ref({})
let refreshTimer = null

const componentList = computed(() => Object.values(components.value))
const threadPoolList = computed(() => Object.values(threadPools.value))

onMounted(() => {
  loadHealth()
  refreshTimer = window.setInterval(loadHealth, 30000)
})

onBeforeUnmount(() => {
  if (refreshTimer) {
    window.clearInterval(refreshTimer)
  }
})

// 加载系统健康概览
async function loadHealth() {
  if (loading.value) {
    return
  }
  loading.value = true
  try {
    const response = await getSystemHealth()
    const data = response.data || {}
    overallStatus.value = data.status || 'NOT_CONFIGURED'
    checkedAt.value = data.checkedAt || ''
    components.value = data.components || {}
    threadPools.value = data.threadPools || {}
  } catch (error) {
    overallStatus.value = 'DOWN'
    console.error(error)
  } finally {
    loading.value = false
  }
}

// 获取状态显示文本
function statusLabel(status) {
  return { UP: '正常', DEGRADED: '有告警', DOWN: '异常', NOT_CONFIGURED: '未配置' }[status] || '未知'
}

// 获取状态样式
function statusClass(status) {
  return `status-${String(status || 'unknown').toLowerCase()}`
}

// 获取状态图标
function statusIcon(status) {
  if (status === 'UP') return CircleCheckFilled
  if (status === 'DEGRADED') return WarningFilled
  if (status === 'DOWN') return CircleCloseFilled
  return QuestionFilled
}

// 获取磁盘进度条状态
function diskProgressStatus(component) {
  if (component.status === 'DOWN') return 'exception'
  if (component.status === 'DEGRADED') return 'warning'
  return undefined
}

// 格式化磁盘容量
function formatBytes(value) {
  const bytes = Number(value || 0)
  if (bytes < 1024) return `${bytes} B`
  const units = ['KB', 'MB', 'GB', 'TB']
  let current = bytes
  let index = -1
  do {
    current /= 1024
    index += 1
  } while (current >= 1024 && index < units.length - 1)
  return `${current.toFixed(current >= 10 ? 0 : 1)} ${units[index]}`
}

// 格式化检查时间
function formatDateTime(value) {
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return value
  return date.toLocaleString()
}
</script>

<style scoped>
.health-hero,
.health-summary,
.section-heading,
.health-card-head,
.health-card-foot,
.disk-meter-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 18px;
}

.health-actions {
  display: flex;
  align-items: center;
  gap: 14px;
}

.checked-time,
.summary-note,
.health-message,
.health-card-foot,
.disk-capacity,
.provider-line,
.section-heading p {
  color: var(--muted-color);
}

.health-summary {
  margin-bottom: 20px;
  padding: 20px 24px;
}

.summary-status {
  display: flex;
  align-items: center;
  gap: 12px;
}

.summary-status span,
.health-card-label {
  display: block;
  color: var(--muted-color);
  font-size: 13px;
}

.summary-status strong {
  display: block;
  margin-top: 4px;
  font-size: 22px;
}

.component-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 16px;
  margin-bottom: 20px;
}

.health-card {
  min-height: 190px;
  padding: 24px;
  border: 1px solid var(--line-color);
  border-radius: 26px;
  color: var(--text-primary);
  background: rgba(30, 31, 35, 0.76);
  box-shadow: none;
  backdrop-filter: blur(18px);
}

.health-card-head strong {
  display: block;
  margin-top: 6px;
  font-size: 20px;
}

.health-message {
  min-height: 22px;
  margin: 18px 0;
  font-size: 14px;
}

.health-card-foot {
  padding-top: 12px;
  border-top: 1px solid var(--line-color);
  font-size: 13px;
}

.health-card-foot strong {
  color: var(--text-primary);
}

.disk-meter {
  margin-top: 16px;
}

.disk-meter-head {
  margin-bottom: 8px;
  font-size: 13px;
}

.disk-capacity,
.provider-line {
  display: block;
  margin-top: 8px;
  font-size: 12px;
}

.thread-section {
  padding: 24px;
}

.section-heading {
  margin-bottom: 18px;
}

.section-heading h2 {
  font-size: 22px;
}

.section-heading p {
  margin-top: 5px;
}

.empty-health,
.empty-table {
  padding: 48px 20px;
  color: var(--muted-color);
  text-align: center;
}

.table-status {
  font-weight: 800;
}

.status-up { color: var(--success-color); }
.status-degraded { color: var(--warning-color); }
.status-down { color: var(--danger-color); }
.status-not_configured,
.status-unknown { color: var(--muted-color); }

@media (max-width: 1000px) {
  .component-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); }
}

@media (max-width: 680px) {
  .health-hero,
  .health-summary { align-items: flex-start; flex-direction: column; }
  .health-actions { width: 100%; justify-content: space-between; }
  .component-grid { grid-template-columns: 1fr; }
  .thread-section { padding: 16px; overflow-x: auto; }
}
</style>
