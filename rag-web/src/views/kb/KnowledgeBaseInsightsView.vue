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
import { getKbStrategy, getKbStats, updateKbStrategy } from '../../api/kbInsights'

const route = useRoute()
const router = useRouter()
const kbId = Number(route.params.kbId)
const loading = ref(false)
const saving = ref(false)
const stats = ref({})
const form = reactive({ chunkSize: null, chunkOverlap: null, topK: null, similarityThreshold: null, vectorWeight: null, keywordWeight: null, headingSplitEnabled: 0, tableKeepStrategy: 'FULL' })
const headingEnabled = computed({ get: () => form.headingSplitEnabled === 1, set: value => { form.headingSplitEnabled = value ? 1 : 0 } })
const metrics = [
  { key: 'fileCount', label: '当前文件' }, { key: 'totalBytes', label: '总大小' }, { key: 'chunkCount', label: '切片数' },
  { key: 'vectorReadyCount', label: '向量就绪' }, { key: 'processingFailureCount', label: '处理失败' }, { key: 'duplicateFileCount', label: '重复文件' }, { key: 'lowQualityChunkCount', label: '低质量切片' }
]

onMounted(loadData)

// 加载策略和统计
async function loadData() {
  loading.value = true
  try {
    const [strategy, summary] = await Promise.all([getKbStrategy(kbId), getKbStats(kbId)])
    Object.assign(form, strategy.data || {})
    stats.value = summary.data || {}
  } catch (error) { console.error(error) } finally { loading.value = false }
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
.insights-grid,.activity-grid{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:18px}.strategy-panel,.score-panel,.activity-panel{padding:22px}.panel-head{display:flex;align-items:center;justify-content:space-between;gap:12px;margin-bottom:20px}.panel-head h2,.activity-panel h2{font-size:20px}.strategy-form{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:0 16px}.strategy-form :deep(.el-input-number),.strategy-form :deep(.el-select){width:100%}.strategy-note,.score-label{color:var(--muted-color);font-size:12px}.score-value{text-align:center;color:var(--primary-color);font-size:72px;font-weight:900;line-height:1.1}.score-label{text-align:center;margin-bottom:20px}.metric-grid{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:10px}.metric{padding:12px;border:1px solid var(--line-color);border-radius:10px}.metric span,.metric strong{display:block}.metric span{color:var(--muted-color);font-size:12px}.metric strong{margin-top:6px;font-size:22px}.activity-grid{margin-top:18px}.activity-panel h2{margin-bottom:16px}@media(max-width:760px){.insights-grid,.activity-grid{grid-template-columns:1fr}.strategy-form{grid-template-columns:1fr}.metric-grid{grid-template-columns:repeat(2,minmax(0,1fr))}}
</style>
