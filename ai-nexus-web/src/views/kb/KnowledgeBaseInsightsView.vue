<template>
  <div class="insights-page page-shell">
    <section class="page-hero">
      <div>
        <span class="eyebrow">Knowledge Base Insights</span>
        <h1 class="section-title">知识库策略与统计</h1>
        <p class="section-desc">调整当前知识库的处理策略，查看质量、索引和文档活动数据。</p>
      </div>
      <div class="page-hero-actions">
        <el-button class="page-back-button" plain @click="goBack">
          <el-icon><ArrowLeft /></el-icon>
          返回知识库
        </el-button>
      </div>
    </section>

    <section class="insights-grid">
      <article class="glass-panel strategy-panel">
        <div class="panel-head"><h2>处理与检索策略</h2><el-button type="primary" :loading="saving" @click="saveStrategy">保存策略</el-button></div>
        <el-form label-position="top" class="strategy-form">
          <el-form-item label="切片大小"><el-input-number v-model="form.chunkSize" :min="100" :max="5000" controls-position="right" /></el-form-item>
          <el-form-item label="重叠长度"><el-input-number v-model="form.chunkOverlap" :min="0" :max="2000" controls-position="right" /></el-form-item>
          <el-form-item label="Top-K"><el-input-number v-model="form.topK" :min="1" :max="50" controls-position="right" /></el-form-item>
          <el-form-item label="相似度阈值"><el-input-number v-model="form.similarityThreshold" :min="0" :max="1" :step="0.05" :precision="2" controls-position="right" /></el-form-item>
          <el-form-item label="向量权重"><el-input-number v-model="form.vectorWeight" :min="0" :max="1" :step="0.05" :precision="2" controls-position="right" /></el-form-item>
          <el-form-item label="关键词权重"><el-input-number v-model="form.keywordWeight" :min="0" :max="1" :step="0.05" :precision="2" controls-position="right" /></el-form-item>
          <el-form-item label="表格保留"><el-select v-model="form.tableKeepStrategy"><el-option label="完整表格" value="FULL" /><el-option label="摘要表格" value="SUMMARY" /></el-select></el-form-item>
          <el-form-item label="标题层级切片"><el-switch v-model="headingEnabled" /></el-form-item>
          <el-form-item label="图片视觉解析"><el-switch v-model="visionEnabled" /></el-form-item>
        </el-form>
        <div class="strategy-note">留空策略会沿用系统默认值；保存后重新处理文件才会应用新的切片参数。</div>
      </article>

      <article class="glass-panel score-panel">
        <div class="panel-head"><h2>知识库健康</h2><el-button :loading="loading" @click="loadData">刷新</el-button></div>
        <div class="score-value">{{ stats.healthScore ?? '--' }}</div><div class="score-label">健康评分 / 100</div>
        <div class="metric-grid">
          <div v-for="item in metrics" :key="item.key" class="metric"><span>{{ item.label }}</span><strong>{{ stats[item.key] ?? 0 }}</strong></div>
        </div>
      </article>
    </section>

    <section class="glass-panel summary-panel">
      <div class="panel-head">
        <div><h2>知识摘要</h2><p class="summary-note">基于当前知识库已完成文档生成，内容由当前激活模型整理。</p></div>
        <el-button type="primary" :loading="summaryLoading" @click="generateSummary">刷新摘要</el-button>
      </div>
      <div v-if="summary" class="summary-content">{{ summary }}</div>
      <div v-else class="summary-empty">暂无摘要，点击“刷新摘要”生成。</div>
      <div v-if="summaryUpdatedAt" class="summary-time">最后生成：{{ formatSummaryTime(summaryUpdatedAt) }}</div>
    </section>

    <section class="glass-panel conflict-panel">
      <div class="panel-head">
        <div>
          <h2>口径冲突</h2>
          <p class="summary-note">同一知识库里两段内容在说同一件事、数值却对不上时会被列出，例如「年假 5 天」与「年假 10 天」。检测只看同话题片段的数值差异，不调用大模型。</p>
        </div>
        <el-button type="primary" :loading="detecting" @click="runDetection">开始检测</el-button>
      </div>
      <div class="conflict-stats">
        <span>待确认 <strong>{{ conflictStats.pendingCount ?? 0 }}</strong></span>
        <span>已确认 <strong>{{ conflictStats.confirmedCount ?? 0 }}</strong></span>
        <span>已忽略 <strong>{{ conflictStats.dismissedCount ?? 0 }}</strong></span>
      </div>
      <div v-if="conflicts.length" class="conflict-list">
        <article v-for="item in conflicts" :key="item.id" class="conflict-card">
          <div class="conflict-head">
            <span class="conflict-sim">{{ Math.round((item.similarity || 0) * 100) }}% 相似</span>
            <span class="conflict-reason">{{ item.reason }}</span>
            <el-tag size="small" :type="statusTagType(item.conflictStatus)">{{ statusLabel(item.conflictStatus) }}</el-tag>
          </div>
          <div class="conflict-body">
            <div class="conflict-side">
              <div class="conflict-side-head">{{ item.fileAName }} · 第 {{ (item.chunkAIndex ?? 0) + 1 }} 段</div>
              <p>{{ item.contentA }}</p>
            </div>
            <div class="conflict-side">
              <div class="conflict-side-head">{{ item.fileBName }} · 第 {{ (item.chunkBIndex ?? 0) + 1 }} 段</div>
              <p>{{ item.contentB }}</p>
            </div>
          </div>
          <div v-if="item.conflictStatus === 'PENDING'" class="conflict-ops">
            <el-button size="small" type="primary" plain @click="markConflict(item, 'CONFIRMED')">确认冲突</el-button>
            <el-button size="small" @click="markConflict(item, 'DISMISSED')">忽略</el-button>
          </div>
        </article>
      </div>
      <div v-else class="summary-empty">暂无冲突记录，点击“开始检测”扫描当前知识库。</div>
    </section>

    <section class="activity-grid">
      <article class="glass-panel activity-panel"><h2>最近文件</h2><el-table :data="stats.recentFiles || []"><el-table-column prop="fileName" label="文件" min-width="180" /><el-table-column prop="status" label="状态" width="110" /><el-table-column prop="progress" label="进度" width="90" /></el-table></article>
      <article class="glass-panel activity-panel"><h2>文档规模</h2><el-table :data="stats.popularFiles || []"><el-table-column prop="fileName" label="文件" min-width="180" /><el-table-column prop="chunkCount" label="切片数" width="100" /></el-table></article>
    </section>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { ArrowLeft } from '@element-plus/icons-vue'
import { generateKbSummary, getKbStats, getKbStrategy, getKbSummary, updateKbStrategy, getKbConflicts, detectKbConflicts, updateKbConflict } from '../../api/kbInsights'

const route = useRoute()
const router = useRouter()
const kbId = Number(route.params.kbId)
const loading = ref(false)
const saving = ref(false)
const summaryLoading = ref(false)
const stats = ref({})
const summary = ref('')
const summaryUpdatedAt = ref(null)
const conflicts = ref([])
const conflictStats = ref({})
const detecting = ref(false)
const form = reactive({ chunkSize: null, chunkOverlap: null, topK: null, similarityThreshold: null, vectorWeight: null, keywordWeight: null, headingSplitEnabled: 0, tableKeepStrategy: 'FULL', visionParseEnabled: 0 })
const headingEnabled = computed({ get: () => form.headingSplitEnabled === 1, set: value => { form.headingSplitEnabled = value ? 1 : 0 } })
// 视觉解析开关：开启后图片内容（扫描页、大图页、内嵌图、独立图片）走视觉模型入库
const visionEnabled = computed({ get: () => form.visionParseEnabled === 1, set: value => { form.visionParseEnabled = value ? 1 : 0 } })
const metrics = [
  { key: 'fileCount', label: '当前文件' }, { key: 'totalBytes', label: '总大小' }, { key: 'chunkCount', label: '切片数' },
  { key: 'vectorReadyCount', label: '向量就绪' }, { key: 'processingFailureCount', label: '处理失败' }, { key: 'duplicateFileCount', label: '重复文件' }, { key: 'lowQualityChunkCount', label: '低质量切片' }
]

onMounted(() => { loadData(); loadConflicts() })

// 加载口径冲突列表（单独容错：表未建或接口异常不影响其它面板）
async function loadConflicts() {
  try {
    const response = await getKbConflicts(kbId)
    conflicts.value = response.data?.items || []
    conflictStats.value = response.data || {}
  } catch (error) { console.warn(error) }
}

// 触发一次冲突检测
async function runDetection() {
  detecting.value = true
  try {
    const response = await detectKbConflicts(kbId)
    const count = response.data?.conflictCount ?? 0
    ElMessage.success(count > 0 ? `检测完成，发现 ${count} 处口径冲突` : '检测完成，未发现口径冲突')
    await loadConflicts()
  } catch (error) { console.error(error) } finally { detecting.value = false }
}

// 确认冲突或忽略
async function markConflict(item, status) {
  try {
    await updateKbConflict(kbId, item.id, status)
    ElMessage.success(status === 'CONFIRMED' ? '已标记为确认冲突' : '已忽略该条冲突')
    await loadConflicts()
  } catch (error) { console.error(error) }
}

// 冲突状态文案
function statusLabel(status) {
  if (status === 'CONFIRMED') return '确认冲突'
  if (status === 'DISMISSED') return '已忽略'
  return '待确认'
}

// 冲突状态标签配色
function statusTagType(status) {
  if (status === 'CONFIRMED') return 'danger'
  if (status === 'DISMISSED') return 'info'
  return 'warning'
}

// 加载策略和统计
async function loadData() {
  loading.value = true
  try {
    const [strategy, statsResponse, summaryResponse] = await Promise.all([getKbStrategy(kbId), getKbStats(kbId), getKbSummary(kbId)])
    Object.assign(form, strategy.data || {})
    stats.value = statsResponse.data || {}
    summary.value = summaryResponse.data?.summary || ''
    summaryUpdatedAt.value = summaryResponse.data?.summaryUpdatedAt || null
  } catch (error) { console.error(error) } finally { loading.value = false }
}

// 生成知识库摘要并保留已有内容直到新摘要成功返回
async function generateSummary() {
  summaryLoading.value = true
  try {
    const response = await generateKbSummary(kbId)
    summary.value = response.data?.summary || ''
    summaryUpdatedAt.value = response.data?.summaryUpdatedAt || null
    ElMessage.success('知识摘要已更新')
  } catch (error) { console.error(error) } finally { summaryLoading.value = false }
}

// 格式化摘要生成时间
function formatSummaryTime(value) {
  return value ? String(value).replace('T', ' ').slice(0, 19) : ''
}

// 保存知识库策略
async function saveStrategy() {
  saving.value = true
  try { await updateKbStrategy(kbId, form); ElMessage.success('策略已保存'); await loadData() } catch (error) { console.error(error) } finally { saving.value = false }
}

// 返回知识库列表
function goBack() { router.push('/kb') }
</script>

<style scoped>
.insights-grid,.activity-grid{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:18px}.strategy-panel,.score-panel,.activity-panel{padding:20px}.panel-head{display:flex;align-items:center;justify-content:space-between;gap:12px;margin-bottom:18px;padding-bottom:12px;border-bottom:1px solid var(--line)}.panel-head h2,.activity-panel h2{font-size:14px;font-weight:600}.strategy-form{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:0 16px}.strategy-form :deep(.el-input-number),.strategy-form :deep(.el-select){width:100%}.strategy-note,.score-label,.summary-note,.summary-time{color:var(--text-3);font-size:12px}.score-value{text-align:center;color:var(--amber-hi);font-family:var(--font-mono);font-feature-settings:"zero" off;font-size:56px;font-weight:600;line-height:1.1;text-shadow:0 0 14px rgba(236,233,225,.35)}.score-label{text-align:center;margin-bottom:20px}.metric-grid{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:10px}.metric{padding:12px;border:1px solid var(--line);border-radius:2px;background:rgba(242,239,233,.02)}.metric span,.metric strong{display:block}.metric span{color:var(--text-3);font-size:12px}.metric strong{margin-top:6px;color:var(--text-1);font-family:var(--font-mono);font-size:20px;font-weight:600}.summary-panel{margin-top:18px;padding:20px}.summary-panel .panel-head{margin-bottom:14px}.summary-content{white-space:pre-wrap;line-height:1.8;color:var(--text-1);font-size:14px}.summary-empty{padding:28px 0;color:var(--text-3);font-size:13px}.summary-time{margin-top:14px}.activity-grid{margin-top:18px}.activity-panel h2{margin-bottom:16px}.conflict-panel{margin-top:18px;padding:20px}.conflict-panel .panel-head{align-items:flex-start;margin-bottom:14px}.conflict-stats{display:flex;gap:24px;margin-bottom:16px;color:var(--text-3);font-size:12px}.conflict-stats strong{margin-left:6px;color:var(--text-1);font-family:var(--font-mono);font-size:16px;font-weight:600}.conflict-list{display:flex;flex-direction:column;gap:12px}.conflict-card{padding:14px;border:1px solid var(--line);border-radius:2px;background:rgba(242,239,233,.02)}.conflict-head{display:flex;align-items:center;gap:12px;margin-bottom:10px}.conflict-sim{flex:0 0 auto;color:var(--signal);font-family:var(--font-mono);font-size:12px}.conflict-reason{flex:1 1 auto;color:var(--text-2);font-size:12px;line-height:1.6}.conflict-body{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:12px}.conflict-side{padding:10px 12px;border-left:2px solid var(--rule-strong);background:rgba(0,0,0,.16)}.conflict-side-head{margin-bottom:6px;color:var(--text-3);font-size:11px}.conflict-side p{margin:0;color:var(--text-1);font-size:12px;line-height:1.7;white-space:pre-wrap;word-break:break-word}.conflict-ops{display:flex;justify-content:flex-end;gap:8px;margin-top:10px}@media(max-width:760px){.insights-grid,.activity-grid{grid-template-columns:1fr}.strategy-form{grid-template-columns:1fr}.conflict-body{grid-template-columns:1fr}.metric-grid{grid-template-columns:repeat(2,minmax(0,1fr))}}
</style>
