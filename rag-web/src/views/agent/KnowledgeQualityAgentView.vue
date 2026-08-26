<template>
  <div class="agent-page page-shell">
    <section class="page-hero agent-hero">
      <div>
        <span class="eyebrow">智能体管理 / 知识库质检</span>
        <h1 class="section-title">知识库质检智能体</h1>
        <p class="section-desc">
          检查知识库文件处理、分片质量、召回效果和问答风险，生成质量评分、问题清单和优化建议。
        </p>
      </div>
      <div class="agent-hero-actions">
        <el-button @click="backToAgentList">
          <el-icon><ArrowLeft /></el-icon>
          返回智能体管理
        </el-button>
        <el-button type="primary" size="large" @click="openRunDialog">
          <el-icon><Cpu /></el-icon>
          运行质检
        </el-button>
      </div>
    </section>

    <section class="agent-stats">
      <article class="stat-card score-card motion-card">
        <span>最近评分</span>
        <strong>{{ latestScoreText }}</strong>
        <small>{{ latestRiskText }}</small>
      </article>
      <article class="stat-card run-card motion-card">
        <span>历史报告</span>
        <strong>{{ runList.length }}</strong>
        <small>当前用户保存的质检记录</small>
      </article>
      <article class="stat-card kb-card motion-card">
        <span>可选知识库</span>
        <strong>{{ kbList.length }}</strong>
        <small>可运行质检的知识库数量</small>
      </article>
    </section>

    <section class="agent-main">
      <section class="history-panel glass-panel">
        <div class="panel-head">
          <div>
            <h2>质检报告历史</h2>
            <p>查看最近保存的智能体运行记录。</p>
          </div>
          <el-button :loading="loading" @click="loadRuns">
            <el-icon><Refresh /></el-icon>
            刷新
          </el-button>
        </div>

        <el-table v-if="runList.length > 0" :data="runList" class="run-table">
          <el-table-column prop="kbName" label="知识库" min-width="150" />
          <el-table-column prop="checkMode" label="模式" width="100">
            <template #default="{ row }">
              <el-tag>{{ getModeText(row.checkMode) }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="score" label="评分" width="90">
            <template #default="{ row }">
              <strong>{{ row.score }}</strong>
            </template>
          </el-table-column>
          <el-table-column prop="riskLevel" label="风险" width="100">
            <template #default="{ row }">
              <el-tag :type="getRiskTagType(row.riskLevel)">{{ row.riskLevel }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="createTime" label="运行时间" min-width="170">
            <template #default="{ row }">
              {{ formatDateTime(row.createTime) }}
            </template>
          </el-table-column>
          <el-table-column label="操作" width="180" fixed="right">
            <template #default="{ row }">
              <el-button link type="primary" @click="openRunDetail(row.id)">详情</el-button>
              <el-button link type="danger" @click="handleDeleteRun(row.id)">删除</el-button>
            </template>
          </el-table-column>
        </el-table>

        <div v-else class="empty-state">
          <div class="empty-icon">
            <el-icon><DocumentChecked /></el-icon>
          </div>
          <h3>还没有质检报告</h3>
          <p>选择一个知识库运行质检后，这里会保存历史报告。</p>
        </div>
      </section>
    </section>

    <el-dialog v-model="runDialogVisible" title="运行知识库质检" width="620px">
      <el-form label-position="top">
        <el-form-item label="选择知识库">
          <el-select v-model="runForm.kbId" placeholder="请选择要质检的知识库" size="large" class="full-width">
            <el-option v-for="kb in kbList" :key="kb.id" :label="kb.name" :value="kb.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="检查模式">
          <el-radio-group v-model="runForm.checkMode" class="mode-group">
            <el-radio-button label="QUICK">快速</el-radio-button>
            <el-radio-button label="STANDARD">标准</el-radio-button>
            <el-radio-button label="DEEP">深度</el-radio-button>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="测试问题">
          <el-input
            v-model="runForm.testQuestionsText"
            type="textarea"
            :rows="5"
            placeholder="一行一个问题。标准模式会用这些问题做召回测试，深度模式不填时会自动生成轻量测试问题。"
          />
        </el-form-item>
        <el-checkbox v-model="runForm.saveReport">保存本次质检报告</el-checkbox>
      </el-form>
      <template #footer>
        <el-button @click="runDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="running" @click="handleRunQuality">开始质检</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="detailDialogVisible" title="知识库质检报告" width="1080px" class="report-dialog">
      <div v-if="selectedReport" class="report-view">
        <section class="report-summary">
          <div>
            <span>评分</span>
            <strong>{{ selectedReport.score }}</strong>
          </div>
          <div>
            <span>风险等级</span>
            <el-tag :type="getRiskTagType(selectedReport.riskLevel)" size="large">{{ selectedReport.riskLevel }}</el-tag>
          </div>
          <div>
            <span>检查模式</span>
            <strong>{{ getModeText(selectedReport.checkMode) }}</strong>
          </div>
        </section>

        <p class="report-text">{{ selectedReport.summary }}</p>

        <section class="metric-grid">
          <div v-for="item in metricItems" :key="item.key" class="metric-item">
            <span>{{ item.label }}</span>
            <strong>{{ selectedReport.metrics?.[item.key] ?? 0 }}</strong>
          </div>
        </section>

        <section class="report-section">
          <div class="section-head">
            <h3>问题清单</h3>
            <span>{{ selectedReport.issues?.length || 0 }} 个问题</span>
          </div>
          <el-table v-if="selectedReport.issues?.length" :data="selectedReport.issues">
            <el-table-column prop="severity" label="级别" width="100">
              <template #default="{ row }">
                <el-tag :type="getSeverityTagType(row.severity)">{{ getSeverityText(row.severity) }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="title" label="问题" min-width="170" />
            <el-table-column prop="evidence" label="证据" min-width="150" />
            <el-table-column prop="suggestion" label="建议" min-width="240" />
          </el-table>
          <div v-else class="mini-empty">本次没有发现明显问题。</div>
        </section>

        <section class="report-section">
          <div class="section-head">
            <h3>召回测试</h3>
            <span>{{ selectedReport.recallResults?.length || 0 }} 个问题</span>
          </div>
          <div v-if="selectedReport.recallResults?.length" class="recall-list">
            <article v-for="item in selectedReport.recallResults" :key="item.question" class="recall-item">
              <div class="recall-head">
                <strong>{{ item.question }}</strong>
                <el-tag :type="item.hit ? 'success' : 'danger'">{{ item.hit ? '有召回' : '召回为空' }}</el-tag>
              </div>
              <p>召回数量：{{ item.resultCount }}</p>
              <p v-if="item.sources?.length">来源：{{ item.sources.join('、') }}</p>
              <ul v-if="item.topContents?.length">
                <li v-for="content in item.topContents" :key="content">{{ content }}</li>
              </ul>
            </article>
          </div>
          <div v-else class="mini-empty">快速模式未执行召回测试，或本次没有测试问题。</div>
        </section>

        <section class="report-section">
          <div class="section-head">
            <h3>优化建议</h3>
            <span>{{ selectedReport.suggestions?.length || 0 }} 条建议</span>
          </div>
          <ol class="suggestion-list">
            <li v-for="item in selectedReport.suggestions" :key="item.priority">
              <strong>{{ item.action }}</strong>
              <span>{{ item.reason }}</span>
            </li>
          </ol>
        </section>
      </div>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { ArrowLeft, Cpu, DocumentChecked, Refresh } from '@element-plus/icons-vue'
import { getKbList } from '../../api/kb'
import { deleteAgentRun, getAgentRunDetail, getAgentRuns, runKnowledgeQuality } from '../../api/agent'

const router = useRouter()
const runList = ref([])
const kbList = ref([])
const selectedReport = ref(null)
const runDialogVisible = ref(false)
const detailDialogVisible = ref(false)
const running = ref(false)
const loading = ref(false)

const runForm = reactive({
  kbId: null,
  checkMode: 'STANDARD',
  testQuestionsText: '',
  saveReport: true
})

const metricItems = [
  { key: 'fileTotal', label: '文件总数' },
  { key: 'completedFileCount', label: '完成文件' },
  { key: 'failedFileCount', label: '失败文件' },
  { key: 'chunkTotal', label: '分片总数' },
  { key: 'emptyChunkCount', label: '空分片' },
  { key: 'shortChunkCount', label: '过短分片' },
  { key: 'longChunkCount', label: '过长分片' },
  { key: 'duplicateChunkCount', label: '重复分片' },
  { key: 'noiseChunkCount', label: '噪声分片' },
  { key: 'recallEmptyCount', label: '召回为空' }
]

// 最近一次质检运行记录
const latestRun = computed(() => runList.value[0] || null)

// 最近一次质检评分文案
const latestScoreText = computed(() => latestRun.value ? latestRun.value.score : '--')

// 最近一次风险等级文案
const latestRiskText = computed(() => latestRun.value ? latestRun.value.riskLevel : '暂无历史报告')

onMounted(() => {
  loadAgentDetailPage()
})

// 加载知识库质检智能体详情页数据
async function loadAgentDetailPage() {
  await Promise.all([loadKnowledgeBases(), loadRuns()])
}

// 返回智能体卡片总览
function backToAgentList() {
  router.push('/agents')
}

// 加载知识库列表
async function loadKnowledgeBases() {
  try {
    const res = await getKbList()
    kbList.value = res.data || []
  } catch (error) {
    console.error(error)
  }
}

// 加载运行记录
async function loadRuns() {
  try {
    loading.value = true
    const res = await getAgentRuns({ limit: 30 })
    runList.value = res.data || []
  } catch (error) {
    console.error(error)
  } finally {
    loading.value = false
  }
}

// 打开运行质检弹窗
function openRunDialog() {
  if (!runForm.kbId && kbList.value.length > 0) {
    runForm.kbId = kbList.value[0].id
  }
  runDialogVisible.value = true
}

// 运行知识库质检
async function handleRunQuality() {
  if (!runForm.kbId) {
    ElMessage.warning('请选择要质检的知识库')
    return
  }
  try {
    running.value = true
    const testQuestions = runForm.testQuestionsText
      .split('\n')
      .map(item => item.trim())
      .filter(Boolean)
    const res = await runKnowledgeQuality({
      kbId: runForm.kbId,
      checkMode: runForm.checkMode,
      testQuestions,
      saveReport: runForm.saveReport
    })
    selectedReport.value = res.data
    detailDialogVisible.value = true
    runDialogVisible.value = false
    await loadRuns()
    ElMessage.success('质检完成')
  } catch (error) {
    console.error(error)
  } finally {
    running.value = false
  }
}

// 打开历史报告详情
async function openRunDetail(id) {
  try {
    const res = await getAgentRunDetail(id)
    selectedReport.value = res.data?.report || null
    detailDialogVisible.value = true
  } catch (error) {
    console.error(error)
  }
}

// 删除运行记录
async function handleDeleteRun(id) {
  try {
    await ElMessageBox.confirm('确定删除这条质检报告吗？', '提示', {
      confirmButtonText: '确定',
      cancelButtonText: '取消',
      type: 'warning'
    })
    await deleteAgentRun(id)
    await loadRuns()
    ElMessage.success('删除成功')
  } catch (error) {
    if (error !== 'cancel') {
      console.error(error)
    }
  }
}

// 获取模式中文
function getModeText(mode) {
  const modeMap = {
    QUICK: '快速',
    STANDARD: '标准',
    DEEP: '深度'
  }
  return modeMap[mode] || mode || '--'
}

// 获取风险标签类型
function getRiskTagType(riskLevel) {
  if (riskLevel === '健康') {
    return 'success'
  }
  if (riskLevel === '需关注') {
    return 'warning'
  }
  return 'danger'
}

// 获取问题级别标签类型
function getSeverityTagType(severity) {
  if (severity === 'HIGH') {
    return 'danger'
  }
  if (severity === 'MEDIUM') {
    return 'warning'
  }
  return 'info'
}

// 获取问题级别中文
function getSeverityText(severity) {
  const severityMap = {
    HIGH: '高',
    MEDIUM: '中',
    LOW: '低'
  }
  return severityMap[severity] || severity || '--'
}

// 格式化日期时间
function formatDateTime(dateStr) {
  if (!dateStr) {
    return '--'
  }
  return new Date(dateStr).toLocaleString('zh-CN')
}
</script>

<style scoped>
.agent-hero .el-button {
  min-width: 128px;
  height: 40px;
}

.agent-page {
  width: min(1120px, calc(100% - 64px));
}

.agent-hero {
  padding-top: 0;
  padding-bottom: 24px;
}

.agent-hero-actions {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 10px;
}

.agent-stats {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 12px;
  margin-top: 0;
  margin-bottom: 18px;
}

.stat-card {
  min-height: 86px;
  padding: 16px;
  border: 1px solid var(--border);
  border-radius: 12px;
  color: var(--ink-color);
  background: var(--surface-elevated);
}

.stat-card span,
.stat-card small {
  display: block;
  color: var(--muted-color);
}

.stat-card strong {
  display: block;
  margin: 8px 0 4px;
  color: var(--primary-color);
  font-family: "Consolas", "SFMono-Regular", monospace;
  font-size: 26px;
  line-height: 1;
  font-weight: 850;
  font-variant-numeric: tabular-nums;
}

.agent-main {
  display: block;
}

.panel-head,
.report-summary,
.section-head,
.recall-head {
  display: flex;
  align-items: center;
}

.panel-head,
.section-head,
.recall-head {
  justify-content: space-between;
  gap: 14px;
}

.panel-head p,
.report-text {
  color: var(--muted-color);
  line-height: 1.7;
}

.history-panel {
  min-width: 0;
  padding: 18px;
}

.panel-head {
  margin-bottom: 18px;
}

.panel-head h2 {
  font-size: 18px;
  font-weight: 850;
}

.run-table {
  width: 100%;
}

.empty-state {
  display: grid;
  place-items: center;
  min-height: 260px;
  text-align: center;
}

.empty-icon {
  width: 68px;
  height: 68px;
  display: grid;
  place-items: center;
  margin-bottom: 16px;
  border-radius: 18px;
  color: var(--primary-color);
  font-size: 32px;
  background: rgba(239, 164, 58, 0.12);
}

.full-width {
  width: 100%;
}

.mode-group {
  display: flex;
  flex-wrap: wrap;
}

.report-view {
  display: grid;
  gap: 22px;
}

.report-summary {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 14px;
}

.report-summary > div,
.metric-item,
.recall-item {
  padding: 16px;
  border: 1px solid var(--line-color);
  border-radius: 12px;
  background: #17191d;
}

.report-summary span,
.metric-item span {
  display: block;
  margin-bottom: 8px;
  color: var(--muted-color);
  font-weight: 700;
}

.report-summary strong,
.metric-item strong {
  font-size: 26px;
  font-weight: 850;
  font-variant-numeric: tabular-nums;
}

.metric-grid {
  display: grid;
  grid-template-columns: repeat(5, minmax(0, 1fr));
  gap: 12px;
}

.report-section {
  display: grid;
  gap: 14px;
}

.section-head h3 {
  font-size: 20px;
  font-weight: 850;
}

.section-head span,
.mini-empty {
  color: var(--muted-color);
}

.recall-list {
  display: grid;
  gap: 12px;
}

.recall-item p {
  margin-top: 8px;
  color: var(--muted-color);
}

.recall-item ul {
  margin-top: 10px;
  padding-left: 18px;
  color: var(--ink-color);
  line-height: 1.7;
}

.suggestion-list {
  display: grid;
  gap: 12px;
  padding-left: 22px;
}

.suggestion-list li {
  padding: 14px;
  border: 1px solid var(--line-color);
  border-radius: 12px;
  background: #17191d;
}

.suggestion-list strong,
.suggestion-list span {
  display: block;
}

.suggestion-list span {
  margin-top: 6px;
  color: var(--muted-color);
  line-height: 1.6;
}

@media (max-width: 1080px) {
  .agent-stats {
    grid-template-columns: 1fr;
  }

  .metric-grid,
  .report-summary {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}

@media (max-width: 760px) {
  .agent-hero {
    display: grid;
  }

  .agent-hero-actions {
    justify-content: flex-start;
    flex-wrap: wrap;
  }

  .stat-card {
    grid-column: span 1;
  }

  .metric-grid,
  .report-summary {
    grid-template-columns: 1fr;
  }
}
</style>
