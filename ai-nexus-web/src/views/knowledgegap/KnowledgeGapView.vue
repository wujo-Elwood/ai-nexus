<template>
  <div class="knowledge-gap-page page-shell">
    <section class="page-hero">
      <div>
        <span class="eyebrow">Knowledge Gap Analysis</span>
        <h1 class="section-title">知识缺口分析</h1>
        <p class="section-desc">聚合拒答、低置信度和负反馈问题，帮助运营决定下一批需要补充的资料。</p>
      </div>
      <div class="hero-actions">
        <el-segmented v-model="selectedDays" :options="windowOptions" @change="loadReport" />
        <el-button v-if="isAdmin" type="primary" :loading="submitting" @click="submitAnalysis">立即重新分析</el-button>
      </div>
    </section>

    <section class="report-meta glass-panel">
      <div><span class="eyebrow">{{ selectedDays }} DAY WINDOW</span><h2>{{ reportStatusLabel }}</h2></div>
      <div class="meta-right"><el-tag :type="statusTagType">{{ report.status || 'NOT_GENERATED' }}</el-tag><span v-if="report.generatedAt">生成于 {{ formatDate(report.generatedAt) }}</span><span v-if="report.error" class="error-text">{{ report.error }}</span></div>
    </section>
    <el-alert v-if="loadError" class="load-alert" type="warning" :closable="false" :title="loadError" />

    <section class="stats-grid">
      <article v-for="item in statItems" :key="item.key" class="stat-card glass-panel"><span>{{ item.label }}</span><strong>{{ report[item.key] || 0 }}</strong></article>
    </section>

    <section class="topics-panel glass-panel">
      <div class="panel-head"><div><span class="eyebrow">Clustered Topics</span><h2>反复出现的知识缺口</h2></div><span class="topic-count">{{ topics.length }} 个主题</span></div>
      <el-table v-if="topics.length" :data="topics" table-layout="fixed">
        <el-table-column prop="topic" label="主题" min-width="150" />
        <el-table-column prop="count" label="问题数" width="90" sortable />
        <el-table-column label="代表问题" min-width="260"><template #default="{ row }"><div class="question-list"><span v-for="question in row.representativeQuestions || []" :key="question">{{ question }}</span></div></template></el-table-column>
        <el-table-column prop="gap" label="缺少的知识" min-width="220" />
        <el-table-column label="建议补充" min-width="220"><template #default="{ row }"><div class="document-list"><span v-for="document in row.suggestedDocuments || []" :key="document">{{ document }}</span></div></template></el-table-column>
        <el-table-column prop="priority" label="优先级" width="100"><template #default="{ row }"><el-tag :type="priorityType(row.priority)">{{ row.priority || 'MEDIUM' }}</el-tag></template></el-table-column>
      </el-table>
      <el-empty v-else description="当前窗口还没有可分析的问题" />
    </section>
  </div>
</template>

<script setup>
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { analyzeKnowledgeGap, getKnowledgeGapReport } from '../../api/knowledgeGaps'
import { useUserStore } from '../../stores/user'

const userStore = useUserStore()
const selectedDays = ref(7)
const report = ref(emptyReport(7))
const loading = ref(false)
const submitting = ref(false)
const loadError = ref('')
let pollTimer = null
const windowOptions = [{ label: '最近 7 天', value: 7 }, { label: '最近 30 天', value: 30 }]
const statItems = [{ key: 'sampleCount', label: '问题样本' }, { key: 'refusalCount', label: '拒答次数' }, { key: 'lowConfidenceCount', label: '低置信度' }, { key: 'negativeFeedbackCount', label: '无帮助反馈' }]
const topics = computed(() => Array.isArray(report.value.topics) ? report.value.topics : [])
const isAdmin = computed(() => String(userStore.username || '').toLowerCase() === 'admin')
const reportStatusLabel = computed(() => report.value.status === 'RUNNING' ? '分析正在进行' : report.value.status === 'COMPLETED' ? '报告已生成' : report.value.status === 'FAILED' ? '最近一次分析失败' : '尚未生成报告')
const statusTagType = computed(() => report.value.status === 'RUNNING' ? 'warning' : report.value.status === 'FAILED' ? 'danger' : report.value.status === 'COMPLETED' ? 'success' : 'info')

onMounted(() => loadReport(7))
onBeforeUnmount(stopPolling)

/** 查询当前时间窗口报告，保留已有报告避免请求失败时页面闪空。 */
async function loadReport(days = selectedDays.value) {
  selectedDays.value = Number(days); loadError.value = ''; loading.value = true
  try {
    const response = await getKnowledgeGapReport(selectedDays.value)
    if (response.data) report.value = { ...emptyReport(selectedDays.value), ...response.data }
    report.value.status === 'RUNNING' ? startPolling() : stopPolling()
  } catch (error) { loadError.value = error?.message || '报告加载失败，已保留当前内容' } finally { loading.value = false }
}

/** 提交管理员分析任务并开始轮询报告状态。 */
async function submitAnalysis() {
  submitting.value = true; loadError.value = ''
  try { await analyzeKnowledgeGap(selectedDays.value); report.value = { ...report.value, status: 'RUNNING' }; startPolling(); ElMessage.success('分析任务已提交') } catch (error) { loadError.value = error?.message || '分析任务提交失败' } finally { submitting.value = false }
}

/** 每两秒刷新一次运行中的报告，完成或失败后停止。 */
function startPolling() {
  if (pollTimer) return
  pollTimer = window.setInterval(async () => { try { const response = await getKnowledgeGapReport(selectedDays.value); if (response.data) report.value = { ...report.value, ...response.data }; if (report.value.status !== 'RUNNING') stopPolling() } catch (error) { loadError.value = error?.message || '报告刷新失败' } }, 2000)
}

/** 清理报告轮询定时器。 */
function stopPolling() { if (pollTimer) { window.clearInterval(pollTimer); pollTimer = null } }

/** 返回空报告结构，确保首次打开页面也有稳定的统计和主题区域。 */
function emptyReport(days) { return { windowDays: days, sampleCount: 0, refusalCount: 0, lowConfidenceCount: 0, negativeFeedbackCount: 0, topics: [], status: 'NOT_GENERATED' } }

/** 格式化后端时间文本。 */
function formatDate(value) { return String(value || '').replace('T', ' ').slice(0, 19) }

/** 映射主题优先级的视觉类型。 */
function priorityType(priority) { return priority === 'HIGH' ? 'danger' : priority === 'LOW' ? 'info' : 'warning' }
</script>

<style scoped>
.hero-actions,.meta-right,.panel-head{display:flex;align-items:center;gap:12px}.hero-actions{flex-wrap:wrap;justify-content:flex-end}.report-meta{display:flex;align-items:center;justify-content:space-between;gap:18px;padding:18px 22px;margin-bottom:16px}.report-meta h2{margin-top:5px;font-size:22px}.meta-right{flex-wrap:wrap;justify-content:flex-end;color:var(--muted-color);font-size:13px}.error-text{color:var(--el-color-danger)}.load-alert{margin-bottom:16px}.stats-grid{display:grid;grid-template-columns:repeat(4,minmax(0,1fr));gap:16px;margin-bottom:18px}.stat-card{padding:18px 20px}.stat-card span{color:var(--muted-color);font-size:13px}.stat-card strong{display:block;margin-top:10px;color:var(--ink-color);font-size:34px;line-height:1}.topics-panel{padding:22px}.panel-head{justify-content:space-between;margin-bottom:18px}.panel-head h2{margin-top:5px;font-size:21px}.topic-count{color:var(--muted-color);font-size:13px}.question-list,.document-list{display:grid;gap:4px;line-height:1.5}.question-list span,.document-list span{display:block;white-space:normal}.document-list{color:var(--muted-color)}@media(max-width:820px){.stats-grid{grid-template-columns:repeat(2,minmax(0,1fr))}.report-meta{align-items:flex-start;flex-direction:column}.meta-right{justify-content:flex-start}}@media(max-width:560px){.hero-actions{justify-content:flex-start}.stats-grid{grid-template-columns:1fr}.topics-panel{padding:14px}.report-meta{padding:16px}.panel-head{align-items:flex-start;flex-direction:column}}
</style>
