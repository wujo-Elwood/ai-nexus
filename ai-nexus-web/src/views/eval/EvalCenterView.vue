<template>
  <div class="eval-page page-shell">
    <section class="page-hero">
      <div>
        <span class="eyebrow">RAG Evaluation</span>
        <h1 class="section-title">评测中心</h1>
        <p class="section-desc">用固定的评测问题跑真实检索链路，把「改完是不是更准了」变成可复现的数字。</p>
      </div>
      <div class="hero-actions">
        <el-select v-model="selectedKbId" class="kb-select" placeholder="选择知识库" @change="handleKbChange">
          <el-option v-for="kb in kbList" :key="kb.id" :label="kb.name" :value="kb.id" />
        </el-select>
        <el-button type="primary" :loading="running" :disabled="!selectedKbId || !cases.length" @click="submitRun">
          运行评测
        </el-button>
      </div>
    </section>

    <section class="readout-panel glass-panel">
      <span class="eyebrow">口径说明</span>
      <p>
        评测调用与线上问答完全相同的知识库策略与检索规则（向量召回 + 关键词召回 → 融合 → 阈值与去重），
        只跳过「调用大模型生成答案」这一步，因此衡量的是召回与排序质量。
        标注了期望来源的用例按「首个命中期望来源的排名」计分，未标注的只要召回到内容即算命中。
      </p>
    </section>

    <el-alert v-if="loadError" class="load-alert" type="warning" :closable="false" :title="loadError" />

    <section class="cases-panel glass-panel">
      <div class="panel-head">
        <div><span class="eyebrow">Test Cases</span><h2>评测问题</h2></div>
        <span class="panel-count">{{ cases.length }} 条</span>
      </div>

      <div class="case-form">
        <el-input v-model="draft.question" placeholder="评测问题，例如：知识库单个上传文件的大小上限是多少？" clearable />
        <el-input v-model="draft.expectedSources" placeholder="期望来源（文件名关键词，多个用逗号分隔，可不填）" clearable />
        <el-button :loading="adding" :disabled="!selectedKbId" @click="submitCase">添加</el-button>
      </div>

      <el-table v-if="cases.length" :data="cases" table-layout="fixed">
        <el-table-column prop="question" label="评测问题" min-width="280" />
        <el-table-column label="期望来源" min-width="200">
          <template #default="{ row }">
            <span v-if="row.expectedSources" class="source-text">{{ row.expectedSources }}</span>
            <span v-else class="muted">未标注（仅校验能否召回）</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="90">
          <template #default="{ row }">
            <el-button link type="danger" @click="removeCase(row.id)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
      <el-empty v-else description="还没有评测问题，先添加几条再运行评测" />
    </section>

    <section v-if="result" class="result-panel glass-panel">
      <div class="panel-head">
        <div><span class="eyebrow">Latest Run</span><h2>本次结果</h2></div>
        <span class="panel-count">{{ result.run?.runStatus }} · {{ formatDate(result.run?.finishedAt) }}</span>
      </div>

      <div class="stats-grid">
        <article v-for="item in statItems" :key="item.label" class="stat-card">
          <span>{{ item.label }}</span><strong>{{ item.value }}</strong>
        </article>
      </div>

      <el-table :data="result.items || []" table-layout="fixed">
        <el-table-column prop="question" label="问题" min-width="240" />
        <el-table-column label="结果" width="110">
          <template #default="{ row }"><el-tag :type="statusType(row.resultStatus)">{{ row.resultStatus }}</el-tag></template>
        </el-table-column>
        <el-table-column label="命中排名" width="100">
          <template #default="{ row }">
            <span :class="{ muted: !row.matchedRank }">{{ row.matchedRank ? `第 ${row.matchedRank} 位` : '未命中' }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="latencyMs" label="耗时(ms)" width="100" sortable />
        <el-table-column label="召回首位" min-width="220">
          <template #default="{ row }">
            <span v-if="firstSource(row.sources)" class="source-text">{{ firstSource(row.sources) }}</span>
            <span v-else class="muted">{{ row.errorMessage || '无召回' }}</span>
          </template>
        </el-table-column>
      </el-table>
    </section>

    <section class="history-panel glass-panel">
      <div class="panel-head">
        <div><span class="eyebrow">Run History</span><h2>历史运行</h2></div>
        <span class="panel-count">最近 50 次</span>
      </div>
      <el-table v-if="runs.length" :data="runs" table-layout="fixed" @row-click="viewRun">
        <el-table-column prop="id" label="#" width="70" />
        <el-table-column label="时间" min-width="170">
          <template #default="{ row }">{{ formatDate(row.createTime) }}</template>
        </el-table-column>
        <el-table-column label="命中" width="120">
          <template #default="{ row }">{{ row.hitCount || 0 }} / {{ row.totalCount || 0 }}</template>
        </el-table-column>
        <el-table-column label="MRR" width="100">
          <template #default="{ row }">{{ formatNumber(row.mrr, 4) }}</template>
        </el-table-column>
        <el-table-column label="平均耗时(ms)" width="130">
          <template #default="{ row }">{{ row.avgLatencyMs || 0 }}</template>
        </el-table-column>
        <el-table-column label="状态" width="110">
          <template #default="{ row }"><el-tag :type="row.runStatus === 'SUCCESS' ? 'success' : 'warning'">{{ row.runStatus }}</el-tag></template>
        </el-table-column>
      </el-table>
      <el-empty v-else description="还没有运行记录" />
    </section>
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { getKbList } from '../../api/kb'
import { addEvalCase, deleteEvalCase, getEvalCases, getEvalRunDetail, getEvalRuns, runEval } from '../../api/eval'

const kbList = ref([])
const selectedKbId = ref(null)
const cases = ref([])
const runs = ref([])
const result = ref(null)
const draft = ref({ question: '', expectedSources: '' })
const adding = ref(false)
const running = ref(false)
const loadError = ref('')

const statItems = computed(() => {
  const payload = result.value || {}
  const run = payload.run || {}
  return [
    { label: '命中率', value: `${formatNumber(payload.hitRate, 2)}%` },
    { label: 'MRR', value: formatNumber(payload.mrr, 4) },
    { label: '拒答率', value: `${formatNumber(payload.refusalRate, 2)}%` },
    { label: '平均耗时', value: `${run.avgLatencyMs || 0} ms` }
  ]
})

onMounted(loadKbList)

/** 加载可见知识库，默认选中第一个。 */
async function loadKbList() {
  try {
    const response = await getKbList()
    kbList.value = response.data || []
    if (kbList.value.length) {
      selectedKbId.value = kbList.value[0].id
      await Promise.all([loadCases(), loadRuns()])
    }
  } catch (error) {
    loadError.value = error?.message || '知识库加载失败'
  }
}

/** 切换知识库后重新加载用例与历史。 */
async function handleKbChange() {
  result.value = null
  await Promise.all([loadCases(), loadRuns()])
}

/** 查询当前知识库的评测问题。 */
async function loadCases() {
  if (!selectedKbId.value) return
  loadError.value = ''
  try {
    const response = await getEvalCases(selectedKbId.value)
    cases.value = response.data || []
  } catch (error) {
    loadError.value = error?.message || '评测问题加载失败'
  }
}

/** 查询当前知识库的历史运行。 */
async function loadRuns() {
  if (!selectedKbId.value) return
  try {
    const response = await getEvalRuns(selectedKbId.value)
    runs.value = response.data || []
  } catch (error) {
    loadError.value = error?.message || '历史运行加载失败'
  }
}

/** 新增一条评测问题。 */
async function submitCase() {
  const question = String(draft.value.question || '').trim()
  if (!question) {
    ElMessage.warning('请先填写评测问题')
    return
  }
  adding.value = true
  try {
    await addEvalCase({ kbId: selectedKbId.value, question, expectedSources: String(draft.value.expectedSources || '').trim() })
    draft.value = { question: '', expectedSources: '' }
    await loadCases()
    ElMessage.success('已添加')
  } catch (error) {
    loadError.value = error?.message || '添加失败'
  } finally {
    adding.value = false
  }
}

/** 删除一条评测问题。 */
async function removeCase(id) {
  try {
    await deleteEvalCase(id)
    await loadCases()
  } catch (error) {
    loadError.value = error?.message || '删除失败'
  }
}

/** 运行评测：逐条跑真实检索，结果直接落库。 */
async function submitRun() {
  if (!selectedKbId.value || !cases.value.length) return
  running.value = true
  loadError.value = ''
  try {
    const response = await runEval(selectedKbId.value)
    result.value = response.data || null
    await loadRuns()
    ElMessage.success('评测完成')
  } catch (error) {
    loadError.value = error?.message || '评测执行失败'
  } finally {
    running.value = false
  }
}

/** 点击历史行查看该次运行明细。 */
async function viewRun(row) {
  try {
    const response = await getEvalRunDetail(row.id)
    result.value = response.data || null
  } catch (error) {
    loadError.value = error?.message || '明细加载失败'
  }
}

/** 从明细的来源快照里取第一条，便于快速看出召回的是什么。 */
function firstSource(sources) {
  if (!sources) return ''
  try {
    const list = JSON.parse(sources)
    if (!Array.isArray(list) || !list.length) return ''
    const first = list[0]
    return `${first.fileName || '未知文件'} · 第 ${(first.chunkIndex ?? 0) + 1} 段`
  } catch (error) {
    return ''
  }
}

/** 结果状态映射到标签色。 */
function statusType(status) {
  if (status === 'HIT') return 'success'
  if (status === 'MISS') return 'warning'
  if (status === 'ERROR') return 'danger'
  return 'info'
}

/** 数值格式化，避免面板里出现一长串小数。 */
function formatNumber(value, digits = 2) {
  const number = Number(value)
  return Number.isFinite(number) ? number.toFixed(digits) : '0'
}

/** 格式化后端时间文本。 */
function formatDate(value) {
  return value ? String(value).replace('T', ' ').slice(0, 19) : '—'
}
</script>

<style scoped>
.hero-actions,
.panel-head,
.case-form {
  display: flex;
  align-items: center;
  gap: 12px;
}
.hero-actions {
  flex-wrap: wrap;
  justify-content: flex-end;
}
.kb-select {
  width: 220px;
}
.readout-panel {
  padding: 16px 22px;
  margin-bottom: 16px;
  color: var(--muted-color);
  font-size: 13px;
  line-height: 1.7;
}
.readout-panel p {
  margin-top: 6px;
}
.load-alert {
  margin-bottom: 16px;
}
.cases-panel,
.result-panel,
.history-panel {
  padding: 22px;
  margin-bottom: 18px;
}
.panel-head {
  justify-content: space-between;
  margin-bottom: 16px;
}
.panel-head h2 {
  margin-top: 5px;
  font-size: 21px;
}
.panel-count {
  color: var(--muted-color);
  font-size: 13px;
}
.case-form {
  margin-bottom: 14px;
}
.case-form .el-input {
  flex: 1;
}
.stats-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 16px;
  margin-bottom: 18px;
}
.stat-card span {
  color: var(--muted-color);
  font-size: 13px;
}
.stat-card strong {
  display: block;
  margin-top: 10px;
  color: var(--ink-color);
  font-size: 30px;
  line-height: 1;
}
.source-text {
  color: var(--ink-color);
  font-size: 13px;
}
.muted {
  color: var(--muted-color);
  font-size: 13px;
}
@media (max-width: 900px) {
  .stats-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
  .case-form {
    flex-wrap: wrap;
  }
}
@media (max-width: 560px) {
  .hero-actions {
    justify-content: flex-start;
  }
  .stats-grid {
    grid-template-columns: 1fr;
  }
  .cases-panel,
  .result-panel,
  .history-panel {
    padding: 14px;
  }
}
</style>
