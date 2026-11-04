<template>
  <div class="agent-page page-shell">
    <header class="console-head">
      <div class="head-row">
        <div>
          <h1>智能体</h1>
          <p class="desc">平台当前可用的智能体入口，运行过程与配置在各智能体对话页内完成。</p>
        </div>
        <div class="head-actions">
          <el-button @click="openComposer()">＋ 创建智能体</el-button>
          <el-button :loading="loading" @click="loadAgents">⟳ 刷新</el-button>
        </div>
      </div>
      <div class="aurora-band"></div>
    </header>

    <template v-if="displayAgentList.length > 0">
      <div class="sys-label list-label">共 {{ displayAgentList.length }} 个智能体</div>
      <div class="agent-rows">
        <article
          v-for="(agent, index) in displayAgentList"
          :key="agent.code"
          class="agent-entry"
          role="button"
          tabindex="0"
          @click="openAgent(agent)"
          @keydown.enter.prevent="openAgent(agent)"
          @keydown.space.prevent="openAgent(agent)"
        >
          <span class="e-no mono">{{ String(index + 1).padStart(2, '0') }}</span>
          <div class="e-main">
            <div class="e-name serif">
              {{ agent.name }}
              <span v-if="agent === displayAgentList[0]" class="recent-word mono">上次使用</span>
              <span v-if="agent.custom" class="recent-word mono mine-word">我的</span>
              <span class="st" :class="statusKind(agent.status)"><span class="dot" :class="statusKind(agent.status)"></span>{{ statusText(agent.status) }}</span>
            </div>
            <p class="e-desc">{{ agent.description }}</p>
            <div v-if="agent.skills && agent.skills.length" class="skills-line mono">
              绑定技能&nbsp;&nbsp;<b v-for="skill in agent.skills" :key="skill" :title="'绑定的技能：' + skill">{{ skill }}</b>
            </div>
          </div>
          <div class="leaders"></div>
          <div class="e-readout mono">
            {{ agentMetaModel(agent) }}<br>
            {{ agent.scene || '平台智能体' }}
          </div>
          <div class="e-act">
            <template v-if="agent.custom">
              <a class="row-cta" @click.prevent.stop="openComposer(agent)" href="#">编辑</a>
              <a class="row-cta danger" @click.prevent.stop="removeCustom(agent)" href="#">删除</a>
            </template>
            <a class="row-cta" @click.prevent.stop="openAgent(agent)" href="#">进入 →</a>
          </div>
        </article>      </div>
    </template>

    <div v-else class="empty-state">
      <p>还没有可展示的智能体</p>
      <a class="empty-link" @click.prevent="loadAgents" href="#">重新加载</a>
    </div>

    <!-- 编排对话框：创建/编辑自定义智能体 -->
    <el-dialog
      v-model="composerVisible"
      :title="editingCode ? '编辑智能体' : '创建智能体'"
      width="620px"
      append-to-body
      destroy-on-close
      class="composer-dialog"
    >
      <div class="composer-form">
        <div class="f-row">
          <label class="f-label mono">名称 *</label>
          <el-input v-model="composerForm.name" maxlength="64" show-word-limit placeholder="例如：出差助手" />
        </div>
        <div class="f-row">
          <label class="f-label mono">描述</label>
          <el-input v-model="composerForm.description" maxlength="512" placeholder="一句话说明它能做什么，会显示在卡片上" />
        </div>
        <div class="f-row">
          <label class="f-label mono">适用场景</label>
          <el-input v-model="composerForm.scene" maxlength="255" placeholder="例如：出差准备、数据整理" />
        </div>
        <div class="f-row">
          <label class="f-label mono">系统提示词</label>
          <el-input
            v-model="composerForm.systemPrompt"
            type="textarea"
            :rows="4"
            maxlength="2000"
            show-word-limit
            placeholder="决定它的行为准则，例如：你是出差助手，回答前先查天气与汇率，用简洁中文作答"
          />
        </div>
        <div class="f-row">
          <label class="f-label mono">
            绑定技能
            <span class="f-tip">勾选它能调用的工具；不选则纯对话</span>
          </label>
          <div v-if="skillOptions.length" class="skill-grid">
            <label
              v-for="s in skillOptions"
              :key="s.name"
              class="skill-item"
              :title="s.description"
            >
              <input
                type="checkbox"
                class="skill-check"
                :checked="composerForm.skills.includes(s.name)"
                @change="toggleSkill(s.name, $event.target.checked)"
              >
              <span class="s-name mono">{{ s.name }}</span>
              <span class="s-desc">{{ s.description }}</span>
            </label>
          </div>
          <div v-else class="skill-loading mono">技能清单加载中…</div>
        </div>
        <div class="f-row">
          <label class="f-label mono">
            执行步骤
            <span class="f-tip">留空则由模型自主调用工具；配置后按顺序执行</span>
          </label>
          <div class="step-editor">
            <div v-for="(step, index) in composerForm.steps" :key="index" class="step-card">
              <div class="step-head">
                <span class="step-no mono">{{ index + 1 }}</span>
                <el-select v-model="step.stepType" size="small" class="step-type">
                  <el-option label="调用技能" value="TOOL" />
                  <el-option label="模型处理" value="LLM" />
                  <el-option label="收尾要求" value="ANSWER" />
                </el-select>
                <el-input
                  v-model="step.stepName"
                  size="small"
                  class="step-name"
                  maxlength="32"
                  placeholder="步骤名称（可留空）"
                />
                <span class="step-ops">
                  <button type="button" class="step-op" :disabled="index === 0" @click="moveStep(index, -1)">↑</button>
                  <button
                    type="button"
                    class="step-op"
                    :disabled="index === composerForm.steps.length - 1"
                    @click="moveStep(index, 1)"
                  >↓</button>
                  <button type="button" class="step-op danger" @click="removeStep(index)">删除</button>
                </span>
              </div>
              <template v-if="step.stepType === 'TOOL'">
                <el-select v-model="step.toolName" size="small" class="step-tool" placeholder="选择要调用的技能">
                  <el-option v-for="s in skillOptions" :key="s.name" :label="s.name" :value="s.name" />
                </el-select>
                <el-input
                  v-model="step.argsTemplate"
                  size="small"
                  type="textarea"
                  :rows="2"
                  placeholder='参数 JSON：可用 ${input} 引用用户问题、${step1} 引用第 1 步结果，例 {"city":"${input}"}'
                />
              </template>
              <el-input
                v-else
                v-model="step.instruction"
                size="small"
                type="textarea"
                :rows="2"
                :placeholder="step.stepType === 'ANSWER'
                  ? '对最终回答的额外要求，例：用表格输出，不超过 5 行'
                  : '这一步让模型做什么，例：从上面的结果中提取所有金额并求和'"
              />
            </div>
            <button type="button" class="step-add" @click="addStep">+ 添加步骤</button>
          </div>
        </div>
      </div>
      <template #footer>
        <el-button @click="composerVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="submitComposer">
          {{ editingCode ? '保存修改' : '创建并启用' }}
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { getAgentList, listAvailableSkills, getCustomAgent, createCustomAgent, updateCustomAgent, deleteCustomAgent } from '../../api/agent'

const router = useRouter()
const agentList = ref([])
const loading = ref(false)

// —— 编排对话框状态 ——
const composerVisible = ref(false)
const saving = ref(false)
const editingCode = ref('')
const skillOptions = ref([])
const composerForm = reactive({
  name: '',
  description: '',
  scene: '',
  systemPrompt: '',
  skills: [],
  steps: []
})

const fallbackAgentList = [
  {
    code: 'general-tool',
    name: '天气查询智能体',
    description: '调用天气定位与查询工具回答实时天气问题，每一步执行过程实时可见。',
    status: 'ENABLED',
    version: '1.0',
    scene: '实时天气查询、坐标定位、执行过程可视化',
    modelDisplayName: '跟随模型设置'
  },
  {
    code: 'currency-converter',
    name: '货币换算智能体',
    description: '查询实时汇率并结合计算器完成多币种金额换算，每一步执行过程实时可见。',
    status: 'ENABLED',
    version: '1.0',
    scene: '实时汇率查询、多币种换算、执行过程可视化',
    modelDisplayName: '跟随模型设置'
  },
  {
    code: 'vision-agent',
    name: '视觉智能体',
    description: '上传图片进行识别与分析（截图、照片中的文字与物体），结合计算工具完成图中金额合计等多步分析。',
    status: 'ENABLED',
    version: '1.0',
    scene: '图片识别、文字提取、结合计算的多步分析',
    modelDisplayName: '跟随模型设置'
  },
  {
    code: 'data-agent',
    name: '数据问答智能体',
    description: '只读查询系统数据库，回答用户、知识库、调用量等数据统计问题，每一步 SQL 与结果实时可见。',
    status: 'ENABLED',
    version: '1.0',
    scene: '数据统计、业务问答、只读查询、SQL 过程可视化',
    modelDisplayName: '跟随模型设置'
  },
  {
    code: 'image-agent',
    name: '生图智能体',
    description: '根据对话内容整理提示词并调用生图工具生成 AI 图片，结果保存到 AI 生图历史，每一步执行过程实时可见。',
    status: 'ENABLED',
    version: '1.0',
    scene: 'AI 图片生成、提示词整理、执行过程可视化',
    modelDisplayName: '跟随模型设置'
  }
]

// 计算当前要展示的智能体列表
const displayAgentList = computed(() => {
  if (agentList.value.length > 0) {
    return agentList.value
  }
  return fallbackAgentList
})

onMounted(() => {
  loadAgents()
})

// 加载智能体列表
async function loadAgents() {
  try {
    loading.value = true
    const res = await getAgentList()
    agentList.value = res.data || []
  } catch (error) {
    console.error(error)
  } finally {
    loading.value = false
  }
}

// 进入选中的智能体详情页（智能体由后端注册表驱动，全部可直接进入）
function openAgent(agent) {
  router.push('/agent-tools?agent=' + agent.code + '&name=' + encodeURIComponent(agent.name || agent.code))
}

// —— 编排：打开对话框（无参=创建，传 agent=编辑） ——
async function openComposer(agent) {
  editingCode.value = agent ? agent.code : ''
  composerForm.name = agent ? agent.name : ''
  composerForm.description = agent ? agent.description : ''
  composerForm.scene = agent ? (agent.scene || '') : ''
  composerForm.skills = []
  composerForm.steps = []
  composerVisible.value = true
  loadSkills()
  if (agent) {
    try {
      const res = await getCustomAgent(agent.code)
      const detail = res.data || {}
      composerForm.name = detail.name || agent.name
      composerForm.description = detail.description || ''
      composerForm.scene = detail.scene || ''
      composerForm.systemPrompt = detail.systemPrompt || ''
      composerForm.skills = [...(detail.skills || [])]
      composerForm.steps = (detail.steps || []).map(toStepDraft)
    } catch (error) {
      console.error(error)
    }
  } else {
    composerForm.systemPrompt = ''
  }
}

// 后端步骤结构 -> 编辑器草稿（保持字段一致，便于原样提交）
function toStepDraft(step) {
  return {
    stepType: step.stepType || 'TOOL',
    stepName: step.stepName || '',
    toolName: step.toolName || '',
    argsTemplate: step.argsTemplate || '',
    instruction: step.instruction || ''
  }
}

// 追加一个步骤
function addStep() {
  composerForm.steps.push({
    stepType: 'TOOL',
    stepName: '',
    toolName: '',
    argsTemplate: '',
    instruction: ''
  })
}

// 删除指定步骤
function removeStep(index) {
  composerForm.steps.splice(index, 1)
}

// 上移/下移步骤（offset = -1 上移，1 下移）
function moveStep(index, offset) {
  const target = index + offset
  if (target < 0 || target >= composerForm.steps.length) return
  const [step] = composerForm.steps.splice(index, 1)
  composerForm.steps.splice(target, 0, step)
}

// 拉取可绑定技能清单
async function loadSkills() {
  if (skillOptions.value.length) return
  try {
    const res = await listAvailableSkills()
    skillOptions.value = res.data || []
  } catch (error) {
    console.error(error)
    skillOptions.value = []
  }
}

// 勾选/取消一个技能
function toggleSkill(name, checked) {
  if (checked) {
    if (!composerForm.skills.includes(name)) composerForm.skills.push(name)
  } else {
    composerForm.skills = composerForm.skills.filter(s => s !== name)
  }
}

// 提交创建或编辑
async function submitComposer() {
  if (!composerForm.name.trim()) {
    ElMessage.warning('请填写智能体名称')
    return
  }
  // 步骤校验：调用技能必须选工具，模型处理/收尾要求必须写指令
  for (let i = 0; i < composerForm.steps.length; i++) {
    const step = composerForm.steps[i]
    if (step.stepType === 'TOOL' && !step.toolName) {
      ElMessage.warning(`第 ${i + 1} 步还没有选择技能`)
      return
    }
    if (step.stepType !== 'TOOL' && !String(step.instruction || '').trim()) {
      ElMessage.warning(`第 ${i + 1} 步还没有填写指令`)
      return
    }
  }
  saving.value = true
  try {
    const payload = {
      name: composerForm.name,
      description: composerForm.description,
      scene: composerForm.scene,
      systemPrompt: composerForm.systemPrompt,
      skills: composerForm.skills,
      // stepNo 由前端按顺序重排，后端会再校验一遍
      steps: composerForm.steps.map((step, index) => ({
        stepNo: index + 1,
        stepName: step.stepName,
        stepType: step.stepType,
        toolName: step.stepType === 'TOOL' ? step.toolName : '',
        argsTemplate: step.argsTemplate,
        instruction: step.instruction,
        enabled: 1
      }))
    }
    if (editingCode.value) {
      await updateCustomAgent(editingCode.value, payload)
      ElMessage.success('智能体已更新')
    } else {
      await createCustomAgent(payload)
      ElMessage.success('智能体已创建，可直接进入对话')
    }
    composerVisible.value = false
    await loadAgents()
  } catch (error) {
    console.error(error)
  } finally {
    saving.value = false
  }
}

// 删除自定义智能体（二次确认）
async function removeCustom(agent) {
  try {
    await ElMessageBox.confirm(`删除后「${agent.name}」的配置与技能绑定将一并移除，确定删除吗？`, '删除智能体', {
      type: 'warning',
      confirmButtonText: '删除',
      cancelButtonText: '取消'
    })
  } catch {
    return
  }
  try {
    await deleteCustomAgent(agent.code)
    ElMessage.success('已删除')
    await loadAgents()
  } catch (error) {
    console.error(error)
  }
}

// 获取智能体状态文案
function statusText(status) {
  if (status === 'ENABLED') {
    return '已启用'
  }
  if (status === 'DISABLED') {
    return '已停用'
  }
  return status || '未知'
}

// 获取智能体状态语义色
function statusKind(status) {
  return status === 'ENABLED' ? 'ok' : 'idle'
}

// 行尾展示的模型信息：生图智能体同时展示对话模型与实际出图的生图模型
function agentMetaModel(agent) {
  if (agent.code === 'image-agent' && agent.imageModelDisplayName) {
    const imagePart = agent.imageProviderActive
      ? '生图 ' + agent.imageModelDisplayName
      : '未激活生图供应商'
    return imagePart + ' · 对话 ' + (agent.modelDisplayName || '跟随模型设置')
  }
  return agent.modelDisplayName || agent.modelName || '跟随模型设置'
}
</script>

<style scoped>
.list-label {
  margin-bottom: 10px;
}

/* 图鉴目录行：编号 + 衬线名 + 点线 + 右对齐读数 */
.agent-rows {
  display: flex;
  flex-direction: column;
}

.agent-entry {
  display: flex;
  align-items: baseline;
  gap: 18px;
  padding: 18px 8px 16px;
  border-bottom: 1px solid var(--rule);
  transition: background 0.15s var(--motion-curve);
}

.agent-entry:hover {
  background: var(--row-hover);
}

.agent-entry:hover .e-name {
  color: #fff;
}

.e-no {
  width: 34px;
  flex: 0 0 auto;
  color: var(--text-3);
  font-size: 11px;
}

.e-main {
  flex: 0 1 auto;
  min-width: 0;
}

.e-name {
  display: flex;
  align-items: baseline;
  gap: 14px;
  font-size: 20px;
  font-weight: 700;
  letter-spacing: 0.01em;
}

.e-desc {
  margin-top: 5px;
  color: var(--text-2);
  font-size: 12.5px;
  max-width: 620px;
  line-height: 1.7;
}

/* 技能词表：行内 mono 词，无 chip 盒 */
.skills-line {
  margin-top: 6px;
  color: var(--text-3);
  font-size: 10.5px;
  letter-spacing: 0.05em;
}

.skills-line b {
  color: var(--text-2);
  font-weight: 500;
}

.skills-line b + b::before {
  content: "\00B7";
  margin: 0 6px;
  color: var(--text-3);
}

/* 点线引导 */
.leaders {
  flex: 1;
  min-width: 40px;
  border-bottom: 1px dotted rgba(236, 233, 225, 0.32);
  transform: translateY(-4px);
}

.e-readout {
  flex: 0 0 auto;
  text-align: right;
  color: var(--text-3);
  font-size: 11px;
  line-height: 1.9;
  letter-spacing: 0.06em;
  white-space: nowrap;
}

.e-act {
  flex: 0 0 auto;
  display: flex;
  gap: 16px;
  align-items: baseline;
}

.recent-word {
  color: var(--text-3);
  font-size: 10px;
  letter-spacing: 0.14em;
  border: 1px solid var(--rule-strong);
  padding: 0 6px;
}

.empty-state {
  display: grid;
  place-items: center;
  gap: 8px;
  min-height: 220px;
  padding: 32px;
  text-align: center;
  color: var(--text-3);
  font-size: 13px;
}

.empty-link {
  color: var(--text-2);
  font-size: 13px;
  text-decoration: underline;
  text-underline-offset: 4px;
}

@media (max-width: 1100px) {
  .agent-entry {
    flex-wrap: wrap;
  }

  .leaders {
    display: none;
  }
}

/* 头部动作区 */
.head-actions {
  display: flex;
  gap: 12px;
  align-items: center;
}

.mine-word {
  color: var(--signal);
  border-color: var(--signal);
}

.row-cta.danger {
  color: var(--signal);
}

/* 编排对话框：星图年鉴语法（直角 / 1px 横线 / mono 小标） */
.composer-form {
  display: flex;
  flex-direction: column;
  gap: 18px;
}

.f-row {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.f-label {
  font-size: 11px;
  letter-spacing: 0.12em;
  color: var(--text-2);
}

.f-tip {
  margin-left: 10px;
  color: var(--text-3);
  letter-spacing: 0.02em;
}

.skill-grid {
  border: 1px solid var(--rule);
  padding: 4px 0;
  max-height: 240px;
  overflow: auto;
}

.skill-item {
  display: grid;
  grid-template-columns: 16px auto 1fr;
  align-items: baseline;
  gap: 10px;
  padding: 8px 12px;
  cursor: pointer;
  border-bottom: 1px solid var(--rule);
  transition: background 0.15s var(--motion-curve);
}

.skill-item:last-child {
  border-bottom: none;
}

.skill-item:hover {
  background: var(--row-hover);
}

.skill-check {
  appearance: none;
  width: 12px;
  height: 12px;
  border: 1px solid var(--rule-strong);
  cursor: pointer;
  position: relative;
  top: 1px;
}

.skill-check:checked {
  background: var(--signal);
  border-color: var(--signal);
}

.skill-check:checked::after {
  content: "✓";
  position: absolute;
  inset: 0;
  display: grid;
  place-items: center;
  font-size: 9px;
  color: #101014;
  font-weight: 700;
}

.s-name {
  font-size: 11.5px;
  color: var(--text-1);
}

.s-desc {
  font-size: 11px;
  color: var(--text-3);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.skill-loading {
  padding: 14px 12px;
  font-size: 11px;
  color: var(--text-3);
  border: 1px solid var(--rule);
}

/* 步骤编排编辑器 */
.step-editor {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.step-card {
  border: 1px solid var(--rule);
  padding: 10px 12px;
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.step-head {
  display: grid;
  grid-template-columns: 20px 104px 1fr auto;
  align-items: center;
  gap: 10px;
}

.step-no {
  font-size: 11px;
  color: var(--signal);
}

.step-type,
.step-name,
.step-tool {
  width: 100%;
}

.step-ops {
  display: flex;
  gap: 6px;
}

.step-op {
  font-size: 11px;
  padding: 3px 8px;
  color: var(--text-2);
  background: transparent;
  border: 1px solid var(--rule);
  cursor: pointer;
  transition: color 0.15s var(--motion-curve), border-color 0.15s var(--motion-curve);
}

.step-op:hover:not(:disabled) {
  color: var(--text-1);
  border-color: var(--rule-strong);
}

.step-op:disabled {
  opacity: 0.35;
  cursor: not-allowed;
}

.step-op.danger {
  color: var(--signal);
}

.step-add {
  align-self: flex-start;
  font-size: 11.5px;
  padding: 7px 14px;
  color: var(--text-2);
  background: transparent;
  border: 1px dashed var(--rule-strong);
  cursor: pointer;
  transition: color 0.15s var(--motion-curve), border-color 0.15s var(--motion-curve);
}

.step-add:hover {
  color: var(--text-1);
  border-color: var(--signal);
}
</style>