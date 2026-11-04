<template>
  <div class="skill-page page-shell">
    <section class="page-hero skill-hero">
      <div>
        <span class="eyebrow">技能中心</span>
        <h1 class="section-title">技能管理</h1>
        <p class="section-desc">
          技能目录由后端代码自动同步，这里控制技能启停、配置智能体并管理绑定关系；调整后立即对对话生效。
        </p>
      </div>
      <el-button :loading="loading" @click="reload">
        <el-icon><Refresh /></el-icon>
        刷新
      </el-button>
    </section>

    <section class="skill-section">
      <div class="section-head">
        <div>
          <h2 class="section-title-sm">智能体</h2>
          <p class="section-sub">启用状态、信息编辑与技能绑定，全部在这一张卡片完成</p>
        </div>
        <el-button type="primary" @click="openAgentDialog()">新建智能体</el-button>
      </div>
      <div class="agent-rows">
        <article v-for="(agent, index) in agents" :key="agent.agentCode" class="agent-entry">
          <span class="e-no mono">{{ String(index + 1).padStart(2, '0') }}</span>
          <div class="e-main">
            <div class="e-head">
              <h3 class="e-name serif">{{ agent.agentName }}</h3>
              <span class="kind-word mono">{{ agent.builtIn === 1 ? '内置' : '自定义' }}</span>
              <span v-if="agent.enabled === 0" class="st idle">已停用</span>
            </div>
            <p class="e-desc">{{ agent.description || '暂无描述' }}</p>

            <div class="bind-zone">
              <div class="bind-line">
                <span class="bind-label mono">绑定技能</span>
                <div class="bind-words mono">
                  <span
                    v-for="name in (bindings[agent.agentCode] || [])"
                    :key="name"
                    class="bind-word"
                    :class="{ 'is-default': isDefault(agent.agentCode, name) }"
                  >
                    {{ name }}<i v-if="isDefault(agent.agentCode, name)">默认</i>
                  </span>
                  <span v-if="!(bindings[agent.agentCode] || []).length" class="muted">未绑定技能</span>
                </div>
              </div>
              <div class="bind-editor">
                <el-select
                  v-model="bindingForm[agent.agentCode]"
                  multiple
                  filterable
                  :disabled="!bindingsReady"
                  placeholder="添加技能…"
                  class="binding-select"
                >
                  <el-option v-for="skill in skills" :key="skill.name" :label="skill.name" :value="skill.name" />
                </el-select>
                <el-button
                  size="small"
                  :loading="savingAgent === agent.agentCode"
                  @click="saveBinding(agent)"
                >保存绑定</el-button>
                <el-button size="small" @click="openAgentDialog(agent)">编辑信息</el-button>
                <el-button v-if="agent.builtIn !== 1" size="small" type="danger" plain @click="onDeleteAgent(agent)">删除</el-button>
              </div>
              <p class="bind-hint">带「默认」角标的技能由平台内置，不能移除</p>
            </div>
          </div>
          <div class="e-side">
            <span class="side-cap mono">启停</span>
            <el-switch :model-value="agent.enabled === 1" @change="val => onToggleAgent(agent, val)" />
          </div>
        </article>
      </div>
    </section>

    <section class="skill-section">
      <div class="section-head">
        <div>
          <h2 class="section-title-sm">技能目录</h2>
          <p class="section-sub">由代码自动注册，可在此启停；带「选填」角标的是非必填参数</p>
        </div>
      </div>
      <table class="skill-table">
        <thead>
          <tr>
            <th class="col-no">NO.</th>
            <th style="width:180px">技能</th>
            <th style="width:44%">说明</th>
            <th style="width:200px">参数</th>
            <th style="width:150px">绑定智能体</th>
            <th style="width:70px">启停</th>
            <th style="width:110px">操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="(skill, index) in skills" :key="skill.name">
            <td class="e-no mono">{{ String(index + 1).padStart(2, '0') }}</td>
            <td><code class="skill-code mono">{{ skill.name }}</code></td>
            <td class="cell-desc">{{ skill.description }}</td>
            <td>
              <div class="word-list mono">
                <span
                  v-for="param in skill.params"
                  :key="param.name"
                  class="w-item"
                  :title="(param.required ? '必填参数：' : '选填参数：') + (param.description || '')"
                >{{ param.name }}<i v-if="!param.required">选填</i></span>
                <span v-if="!skill.params || !skill.params.length" class="muted">无参数</span>
              </div>
            </td>
            <td>
              <div class="word-list mono">
                <span v-for="code in skill.agents" :key="code" class="w-item">{{ agentLabel(code) }}</span>
                <span v-if="!skill.agents || !skill.agents.length" class="muted">未绑定</span>
              </div>
            </td>
            <td>
              <el-switch :model-value="skill.enabled" @change="val => onToggleSkill(skill, val)" />
            </td>
            <td>
              <el-button v-if="skill.name === 'web_search'" size="small" @click="openSearchConfig">搜索源配置</el-button>
            </td>
          </tr>
        </tbody>
      </table>
      <div v-if="!skills.length && !loading" class="empty-state">
        <h3>还没有可用技能</h3>
        <p>后端启动同步技能目录后，这里会自动展示。</p>
      </div>
    </section>

    <section class="skill-section">
      <div class="section-head">
        <div>
          <h2 class="section-title-sm">调用统计</h2>
          <p class="section-sub">技能与智能体的真实调用表现</p>
        </div>
        <el-select v-model="statsDays" class="stats-range" @change="loadStats">
          <el-option :value="7" label="近 7 天" />
          <el-option :value="30" label="近 30 天" />
        </el-select>
      </div>
      <div class="ledger">
        <div class="cell">
          <div class="k">总调用</div>
          <div class="v">{{ stats.total?.calls ?? 0 }}</div>
        </div>
        <div class="cell">
          <div class="k">成功率</div>
          <div class="v">{{ stats.total?.successRate ?? 0 }}<small>%</small></div>
        </div>
        <div class="cell">
          <div class="k">失败次数</div>
          <div class="v" :style="(stats.total?.failedCalls ?? 0) > 0 ? 'color: var(--signal)' : ''">{{ stats.total?.failedCalls ?? 0 }}</div>
        </div>
        <div class="cell">
          <div class="k">平均耗时</div>
          <div class="v">{{ stats.total?.avgCostMs ?? 0 }}<small>ms</small></div>
        </div>
      </div>
      <div class="stats-tables">
        <div class="stat-table glass-panel">
          <h3>按技能</h3>
          <table class="usage-table">
            <thead>
              <tr><th>技能</th><th>调用</th><th>成功</th><th>失败</th><th>成功率</th><th>平均耗时</th><th>最近调用</th></tr>
            </thead>
            <tbody>
              <tr v-for="row in stats.skills" :key="row.skillName">
                <td><code>{{ row.skillName }}</code></td>
                <td>{{ row.calls }}</td>
                <td>{{ row.successCount }}</td>
                <td :class="{ 'text-danger': row.failedCount > 0 }">{{ row.failedCount }}</td>
                <td>{{ successRate(row) }}</td>
                <td>{{ Math.round(row.avgCostMs || 0) }}ms</td>
                <td class="text-muted">{{ formatTime(row.lastCalledAt) }}</td>
              </tr>
              <tr v-if="!(stats.skills || []).length">
                <td colspan="7" class="muted">暂无调用记录</td>
              </tr>
            </tbody>
          </table>
        </div>
        <div class="stat-table glass-panel">
          <h3>按智能体</h3>
          <table class="usage-table">
            <thead>
              <tr><th>智能体</th><th>调用</th><th>成功</th><th>失败</th><th>成功率</th><th>平均耗时</th><th>最近调用</th></tr>
            </thead>
            <tbody>
              <tr v-for="row in stats.agents" :key="row.agentCode">
                <td>{{ agentLabel(row.agentCode) }}</td>
                <td>{{ row.calls }}</td>
                <td>{{ row.successCount }}</td>
                <td :class="{ 'text-danger': row.failedCount > 0 }">{{ row.failedCount }}</td>
                <td>{{ successRate(row) }}</td>
                <td>{{ Math.round(row.avgCostMs || 0) }}ms</td>
                <td class="text-muted">{{ formatTime(row.lastCalledAt) }}</td>
              </tr>
              <tr v-if="!(stats.agents || []).length">
                <td colspan="7" class="muted">暂无调用记录</td>
              </tr>
            </tbody>
          </table>
        </div>
      </div>
    </section>

    <el-dialog v-model="agentDialogVisible" :title="agentForm.originalCode ? '编辑智能体' : '新建智能体'" width="560px">
      <el-form label-width="92px">
        <el-form-item label="编码">
          <el-input
            v-model="agentForm.code"
            :disabled="!!agentForm.originalCode"
            placeholder="小写字母/数字/连字符，如 kb-helper"
          />
        </el-form-item>
        <el-form-item label="名称">
          <el-input v-model="agentForm.name" placeholder="智能体展示名称" />
        </el-form-item>
        <el-form-item label="描述">
          <el-input v-model="agentForm.description" type="textarea" :rows="2" placeholder="卡片上展示的描述" />
        </el-form-item>
        <el-form-item label="场景">
          <el-input v-model="agentForm.scene" placeholder="适用场景说明" />
        </el-form-item>
        <el-form-item label="系统提示词">
          <el-input
            v-model="agentForm.systemPrompt"
            type="textarea"
            :rows="6"
            placeholder="定义智能体的角色、行为和约束；留空则使用平台默认提示词"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="agentDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="savingAgentForm" @click="saveAgentForm">保存</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="searchConfigVisible" title="web_search 搜索源配置" width="540px">
      <el-alert
        v-if="searchConfig.source !== 'none'"
        :title="`当前生效：${searchConfig.source === 'user' ? '我的配置' : '平台默认'}；Key 保持回显值或留空即沿用原值`"
        type="info"
        :closable="false"
        show-icon
        class="config-alert"
      />
      <el-form label-width="96px">
        <el-form-item label="搜索源">
          <el-select v-model="searchConfig.provider" style="width: 100%">
            <el-option v-for="item in providerOptions" :key="item.value" :label="item.label" :value="item.value" />
          </el-select>
        </el-form-item>
        <el-form-item v-if="searchConfig.provider !== 'searxng'" label="API Key">
          <el-input
            v-model="searchConfig.apiKey"
            show-password
            :placeholder="providerOptions.find(item => item.value === searchConfig.provider)?.keyPlaceholder || '搜索源控制台申请的 API Key'"
          />
        </el-form-item>
        <el-form-item v-if="searchConfig.provider === 'searxng'" label="实例地址">
          <el-input v-model="searchConfig.baseUrl" placeholder="如 http://searxng:8080（免 Key）" />
        </el-form-item>
        <el-form-item label="结果条数">
          <el-input-number v-model="searchConfig.maxResults" :min="1" :max="10" />
        </el-form-item>
        <el-form-item label="保存到">
          <el-radio-group v-model="searchConfig.scope">
            <el-radio-button label="user">我的配置</el-radio-button>
            <el-radio-button label="platform">平台默认</el-radio-button>
          </el-radio-group>
        </el-form-item>
      </el-form>
      <p class="zone-hint">{{ providerHint }}</p>
      <template #footer>
        <el-button type="danger" plain @click="clearSearchConfig">清除配置</el-button>
        <el-button @click="searchConfigVisible = false">取消</el-button>
        <el-button type="primary" :loading="savingSearchConfig" @click="saveSearchConfig">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Refresh } from '@element-plus/icons-vue'
import {
  getSkillList, updateSkillEnabled, getSkillBindings, getDefaultBindings, getSkillStats, saveSkillBindings,
  getWebSearchConfig, saveWebSearchConfig, deleteWebSearchConfig
} from '../../api/skill'
import { getAgentManageList, createAgent, updateAgent, updateAgentEnabled, deleteAgent } from '../../api/agent'
import { restoreMissingDefaults } from '../../utils/skillBindings'

const skills = ref([])
const agents = ref([])
const bindings = ref({})
const defaultBindings = ref({})
const bindingForm = reactive({})
const loading = ref(false)
const savingAgent = ref('')
// 绑定数据就绪前禁用编辑，避免首次点选在空表单上触发默认绑定告警
const bindingsReady = ref(false)
// 智能体新建/编辑弹窗
const agentDialogVisible = ref(false)
const savingAgentForm = ref(false)
const agentForm = reactive({ originalCode: '', code: '', name: '', description: '', scene: '', systemPrompt: '' })
// 调用统计
const stats = ref({})
const statsDays = ref(7)

// web_search 搜索源配置弹窗
const searchConfigVisible = ref(false)
const savingSearchConfig = ref(false)
const searchConfig = reactive({
  provider: 'tavily', apiKey: '', baseUrl: '', maxResults: 5, scope: 'user', source: 'none'
})
const providerOptions = [
  { value: 'tavily', label: 'Tavily（免费 1000 次/月）', keyPlaceholder: 'tvly-开头，控制台申请', hint: 'api.tavily.com，专为 LLM 设计，返回摘要与正文片段；国内部署需确认可直连。' },
  { value: 'serper', label: 'Serper（Google 索引）', keyPlaceholder: 'google.serper.dev 申请', hint: 'google.serper.dev，走 Google 索引，中英文质量都好；注册赠送额度。' },
  { value: 'bocha', label: '博查（国内直连）', keyPlaceholder: 'open.bochaai.com 申请', hint: 'api.bochaai.com，国内直连无障碍，中文结果质量最好，按次计费。' },
  { value: 'searxng', label: 'SearXNG（自建，免 Key）', keyPlaceholder: '', hint: '自建元搜索实例的 JSON 接口，无需 API Key；需填写实例地址，如 http://searxng:8080。' }
]
const providerHint = computed(() => providerOptions.find(item => item.value === searchConfig.provider)?.hint || '')

onMounted(reload)

async function reload() {
  try {
    loading.value = true
    bindingsReady.value = false
    await Promise.all([loadSkills(), loadAgents(), loadBindings(), loadDefaultBindings(), loadStats()])
    // 为还没有绑定记录的智能体补空表单，避免编辑器读到 undefined
    for (const agent of agents.value) {
      if (!Array.isArray(bindingForm[agent.agentCode])) {
        bindingForm[agent.agentCode] = [...(bindings.value[agent.agentCode] || [])]
      }
    }
    bindingsReady.value = true
  } finally {
    loading.value = false
  }
}

// 加载技能目录
async function loadSkills() {
  try {
    const res = await getSkillList()
    skills.value = res.data || []
  } catch (error) {
    console.error(error)
  }
}

// 加载智能体管理列表（含停用与内置标记）
async function loadAgents() {
  try {
    const res = await getAgentManageList()
    agents.value = res.data || []
  } catch (error) {
    console.error(error)
  }
}

// 加载绑定关系并填入表单（接口按"智能体→技能"分组）
async function loadBindings() {
  try {
    const res = await getSkillBindings()
    bindings.value = res.data || {}
    Object.keys(bindingForm).forEach(key => delete bindingForm[key])
    for (const [agentCode, names] of Object.entries(bindings.value)) {
      bindingForm[agentCode] = [...names]
    }
  } catch (error) {
    console.error(error)
  }
}

// 加载默认绑定（平台内置，不可移除）
async function loadDefaultBindings() {
  try {
    const res = await getDefaultBindings()
    defaultBindings.value = res.data || {}
  } catch (error) {
    console.error(error)
  }
}

// 加载技能调用统计
async function loadStats() {
  try {
    const res = await getSkillStats(statsDays.value)
    stats.value = res.data || {}
  } catch (error) {
    console.error(error)
  }
}

// 判断某技能是否为智能体的默认绑定
function isDefault(agentCode, skillName) {
  return (defaultBindings.value[agentCode] || []).includes(skillName)
}

// 默认绑定技能被移除时立即拦回；数据未就绪时静默恢复不弹提示
watch(bindingForm, (form) => {
  const restored = restoreMissingDefaults(form, defaultBindings.value)
  if (bindingsReady.value) {
    for (const item of restored) {
      ElMessage.warning(`「${item.skillName}」是「${agentLabel(item.agentCode)}」的默认技能，不能移除`)
    }
  }
}, { deep: true })

// 启停技能
async function onToggleSkill(skill, val) {
  try {
    await updateSkillEnabled(skill.name, val ? 1 : 0)
    skill.enabled = val
    ElMessage.success(`技能 ${skill.name} 已${val ? '启用' : '停用'}`)
  } catch (error) {
    ElMessage.error('更新技能状态失败')
  }
}

// 保存某个智能体的绑定
async function saveBinding(agent) {
  try {
    savingAgent.value = agent.agentCode
    await saveSkillBindings(agent.agentCode, bindingForm[agent.agentCode] || [])
    ElMessage.success(`已保存「${agent.agentName}」的技能绑定`)
    await loadSkills()
  } catch (error) {
    ElMessage.error('保存绑定失败')
  } finally {
    savingAgent.value = ''
  }
}

// 智能体编码转展示名
function agentLabel(agentCode) {
  const agent = agents.value.find(item => item.agentCode === agentCode)
  return agent ? agent.agentName : agentCode
}

// 打开新建/编辑智能体弹窗
function openAgentDialog(agent) {
  if (agent) {
    Object.assign(agentForm, {
      originalCode: agent.agentCode,
      code: agent.agentCode,
      name: agent.agentName,
      description: agent.description || '',
      scene: agent.scene || '',
      systemPrompt: agent.systemPrompt || ''
    })
  } else {
    Object.assign(agentForm, { originalCode: '', code: '', name: '', description: '', scene: '', systemPrompt: '' })
  }
  agentDialogVisible.value = true
}

// 保存智能体（新建或编辑）
async function saveAgentForm() {
  if (!agentForm.name.trim()) {
    ElMessage.warning('请填写智能体名称')
    return
  }
  const payload = {
    name: agentForm.name.trim(),
    description: agentForm.description.trim(),
    scene: agentForm.scene.trim(),
    systemPrompt: agentForm.systemPrompt.trim()
  }
  try {
    savingAgentForm.value = true
    if (agentForm.originalCode) {
      await updateAgent(agentForm.originalCode, payload)
      ElMessage.success('智能体已更新')
    } else {
      await createAgent({ code: agentForm.code.trim(), ...payload })
      ElMessage.success('智能体已创建，请在卡片上为它绑定技能')
    }
    agentDialogVisible.value = false
    await Promise.all([loadAgents(), loadSkills(), loadBindings()])
  } catch (error) {
    ElMessage.error('保存失败')
  } finally {
    savingAgentForm.value = false
  }
}

// 启停智能体
async function onToggleAgent(agent, val) {
  try {
    await updateAgentEnabled(agent.agentCode, val ? 1 : 0)
    agent.enabled = val ? 1 : 0
    ElMessage.success(`智能体「${agent.agentName}」已${val ? '启用' : '停用'}`)
  } catch (error) {
    ElMessage.error('更新智能体状态失败')
  }
}

// 删除自定义智能体
async function onDeleteAgent(agent) {
  try {
    await ElMessageBox.confirm(`确定删除智能体「${agent.agentName}」？删除后不可恢复`, '删除确认', { type: 'warning' })
  } catch (error) {
    return
  }
  try {
    await deleteAgent(agent.agentCode)
    ElMessage.success('智能体已删除')
    await Promise.all([loadAgents(), loadSkills(), loadBindings()])
  } catch (error) {
    ElMessage.error('删除失败')
  }
}

// ---------- web_search 搜索源配置 ----------

// 打开弹窗并回填当前生效配置（Key 为脱敏值，保持原样保存即沿用）
async function openSearchConfig() {
  searchConfigVisible.value = true
  try {
    const res = await getWebSearchConfig()
    const data = res.data || {}
    searchConfig.source = data.source || 'none'
    const effective = data.effective || {}
    if (effective.provider) {
      searchConfig.provider = effective.provider
      searchConfig.apiKey = effective.apiKey || ''
      searchConfig.baseUrl = effective.baseUrl || ''
      searchConfig.maxResults = effective.maxResults || 5
    }
    // 没有任何配置时按当前选择的搜索源给出对应的默认保存位置
    searchConfig.scope = 'user'
  } catch (error) {
    console.error(error)
  }
}

// 保存搜索源配置；非 SearXNG 且 Key 为空时阻止提交，避免误清空
async function saveSearchConfig() {
  if (searchConfig.provider !== 'searxng' && !searchConfig.apiKey.trim()) {
    ElMessage.warning('请填写 API Key')
    return
  }
  if (searchConfig.provider === 'searxng' && !/^https?:\/\//.test(searchConfig.baseUrl.trim())) {
    ElMessage.warning('SearXNG 实例地址需以 http(s):// 开头')
    return
  }
  try {
    savingSearchConfig.value = true
    await saveWebSearchConfig({
      provider: searchConfig.provider,
      apiKey: searchConfig.apiKey.trim(),
      baseUrl: searchConfig.baseUrl.trim(),
      maxResults: searchConfig.maxResults,
      scope: searchConfig.scope
    })
    ElMessage.success(searchConfig.scope === 'platform' ? '已保存为平台默认配置' : '已保存为我的配置')
    searchConfigVisible.value = false
  } catch (error) {
    // 后端错误信息由 request 拦截器统一弹出
    console.error(error)
  }
}

// 清除当前保存位置的配置
async function clearSearchConfig() {
  const scopeLabel = searchConfig.scope === 'platform' ? '平台默认' : '我的'
  try {
    await ElMessageBox.confirm(`确定清除${scopeLabel}搜索源配置？清除后 web_search 技能将不可用`, '清除确认', { type: 'warning' })
  } catch (error) {
    return
  }
  try {
    await deleteWebSearchConfig(searchConfig.scope)
    ElMessage.success('配置已清除')
    searchConfigVisible.value = false
  } catch (error) {
    console.error(error)
  }
}

// 单行成功率
function successRate(row) {
  if (!row.calls) {
    return '-'
  }
  return Math.round((row.successCount || 0) * 1000 / row.calls) / 10 + '%'
}

// 兼容数组与字符串两种时间序列化格式
function formatTime(value) {
  if (!value) {
    return '-'
  }
  if (Array.isArray(value)) {
    const [y, m, d, hh = 0, mm = 0, ss = 0] = value
    const pad = n => String(n).padStart(2, '0')
    return `${y}-${pad(m)}-${pad(d)} ${pad(hh)}:${pad(mm)}:${pad(ss)}`
  }
  return String(value).replace('T', ' ').slice(0, 19)
}
</script>

<style scoped>
.skill-hero {
  padding-top: 0;
}

.skill-section {
  margin-top: 26px;
}

.section-head {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 14px;
}

.section-title-sm {
  margin: 0;
  padding-left: 10px;
  border-left: 3px solid #e8a33d;
  font-size: 17px;
  color: var(--text-primary, #e8eaf0);
  line-height: 1.2;
}

.section-sub {
  margin: 6px 0 0;
  font-size: 12px;
  color: var(--muted-color);
}

/* ---------- 智能体卡片 ---------- */

.agent-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(380px, 1fr));
  gap: 14px;
}

.agent-card {
  display: flex;
  flex-direction: column;
  gap: 10px;
  padding: 16px 18px;
  border-radius: 0;
  transition: transform 0.2s ease, border-color 0.2s ease;
}

.agent-card:hover {
  transform: translateY(-2px);
  border-color: rgba(232, 163, 61, 0.35);
}

.agent-card-top {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
}

.agent-titles {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 6px;
}

.agent-titles h3 {
  margin: 0;
  font-size: 15px;
  color: var(--text-primary, #e8eaf0);
}

.chip {
  padding: 1px 8px;
  font-size: 11px;
  border-radius: 0;
  border: 1px solid transparent;
}

.chip-plain {
  color: #9aa0ac;
  border-color: #2a2d35;
}

.chip-amber {
  color: #e8a33d;
  border-color: rgba(232, 163, 61, 0.45);
}

.chip-muted {
  color: var(--muted-color);
  border-color: #2a2d35;
}

.agent-desc {
  margin: 0;
  font-size: 13px;
  line-height: 1.6;
  color: var(--text-secondary, #9aa0ac);
}

.skill-zone {
  padding: 10px 12px;
  border: 1px solid #23262e;
  border-radius: 0;
  background: rgba(255, 255, 255, 0.02);
}

.zone-label {
  margin-bottom: 6px;
  font-size: 11px;
  letter-spacing: 0.06em;
  color: var(--muted-color);
}

.bound-tags {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}

.bound-tag {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  padding: 2px 8px;
  border-radius: 0;
  background: rgba(127, 176, 255, 0.08);
  border: 1px solid rgba(127, 176, 255, 0.25);
}

.bound-tag code {
  font-size: 12px;
  color: #7fb0ff;
}

.bound-tag i {
  font-style: normal;
  font-size: 10px;
  color: #e8a33d;
  border: 1px solid rgba(232, 163, 61, 0.45);
  border-radius: 0;
  padding: 0 4px;
}

.bound-tag-default {
  border-color: rgba(232, 163, 61, 0.35);
  background: rgba(232, 163, 61, 0.06);
}

.binding-editor {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 8px;
}

.binding-select {
  flex: 1;
}

.zone-hint {
  margin: 6px 0 0;
  font-size: 11px;
  color: var(--subtle-color);
}

.agent-card-foot {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
}

/* ---------- 技能目录卡片 ---------- */

.skill-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(380px, 1fr));
  gap: 14px;
}

.skill-card {
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding: 15px 17px;
  border-radius: 0;
  transition: transform 0.2s ease, border-color 0.2s ease;
}

.skill-card:hover {
  transform: translateY(-2px);
  border-color: rgba(127, 176, 255, 0.35);
}

.skill-card-top {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
}

.skill-name {
  font-size: 13px;
  font-weight: 600;
  color: #7fb0ff;
}

.skill-desc {
  margin: 0;
  font-size: 12.5px;
  line-height: 1.6;
  color: var(--text-secondary, #9aa0ac);
}

.skill-params {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}

.param-chip {
  padding: 2px 8px;
  font-size: 12px;
  color: #c3c8d1;
  border: 1px solid #2a2d35;
  border-radius: 0;
  cursor: default;
}

.param-chip i {
  font-style: normal;
  margin-left: 4px;
  padding: 0 4px;
  font-size: 10px;
  color: #e8a33d;
  border: 1px solid rgba(232, 163, 61, 0.4);
  border-radius: 0;
}

.param-chip.param-optional {
  border-style: dashed;
}

.skill-foot {
  margin-top: auto;
  padding-top: 8px;
  border-top: 1px dashed #23262e;
}

.config-row {
  display: flex;
  justify-content: flex-end;
  margin-top: 8px;
}

.config-alert {
  margin-bottom: 14px;
}

.muted {
  font-size: 12px;
  color: var(--subtle-color);
}

.empty-state {
  padding: 40px;
  text-align: center;
  grid-column: 1 / -1;
}

/* ---------- 调用统计 ---------- */

.stats-range {
  width: 120px;
}

.stats-cards {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 12px;
  margin-bottom: 12px;
}

.stat-card {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 14px 16px;
  border-radius: 0;
}

.stat-icon {
  width: 34px;
  height: 34px;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 15px;
  border: 1px solid var(--line);
  border-radius: 0;
  background: transparent;
  flex-shrink: 0;
}

.icon-blue {
  color: var(--indigo);
}

.icon-green {
  color: var(--ok);
}

.icon-red {
  color: var(--bad);
}

.icon-amber {
  color: var(--amber);
}

.stat-body {
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.stat-body b {
  font-size: 22px;
  line-height: 1.1;
  color: var(--text-primary, #e8eaf0);
}

.stat-body b i {
  font-style: normal;
  font-size: 12px;
  color: var(--text-secondary, #9aa0ac);
  margin-left: 2px;
}

.stat-body span {
  font-size: 12px;
  color: var(--text-secondary, #9aa0ac);
}

.stats-tables {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 12px;
}

.stat-table {
  padding: 14px 16px;
  border-radius: 0;
  overflow-x: auto;
}

.stat-table h3 {
  margin: 0 0 10px;
  font-size: 14px;
  color: var(--text-primary, #e8eaf0);
}

.usage-table {
  width: 100%;
  border-collapse: collapse;
  font-size: 13px;
}

.usage-table th,
.usage-table td {
  padding: 7px 8px;
  text-align: left;
  border-bottom: 1px solid #1e2026;
  white-space: nowrap;
}

.usage-table tbody tr:hover {
  background: rgba(255, 255, 255, 0.03);
}

.usage-table th {
  color: var(--muted-color);
  font-weight: 500;
}

.usage-table code {
  font-size: 12px;
  color: #7fb0ff;
}

.text-danger {
  color: #f56c6c;
}

.text-muted {
  color: var(--muted-color);
}

/* ===== Atlas 目录行：智能体（行内绑定控件） ===== */
.agent-rows {
  display: flex;
  flex-direction: column;
  border-top: 1px solid var(--rule-strong);
}

.agent-entry {
  display: flex;
  align-items: flex-start;
  gap: 18px;
  padding: 18px 8px 16px;
  border-bottom: 1px solid var(--rule);
}

.agent-entry:hover {
  background: var(--row-hover);
}

.e-no {
  width: 34px;
  flex: 0 0 auto;
  color: var(--text-3);
  font-size: 11px;
  padding-top: 4px;
}

.e-main {
  flex: 1;
  min-width: 0;
}

.e-head {
  display: flex;
  align-items: baseline;
  gap: 14px;
}

.e-name {
  font-size: 18px;
  font-weight: 700;
  letter-spacing: 0.01em;
}

.kind-word {
  color: var(--text-3);
  font-size: 10px;
  letter-spacing: 0.14em;
  border: 1px solid var(--rule-strong);
  padding: 0 7px;
}

.e-desc {
  margin-top: 4px;
  color: var(--text-2);
  font-size: 12.5px;
  max-width: 640px;
  line-height: 1.7;
}

/* 绑定区：词表 + 行内编辑器 */
.bind-zone {
  margin-top: 10px;
}

.bind-line {
  display: flex;
  align-items: baseline;
  gap: 16px;
}

.bind-label {
  flex: 0 0 64px;
  color: var(--text-3);
  font-size: 9.5px;
  letter-spacing: 0.22em;
}

.bind-words {
  color: var(--text-2);
  font-size: 11.5px;
  line-height: 2;
}

.bind-word i {
  font-style: normal;
  color: var(--text-3);
  font-size: 9.5px;
  margin-left: 5px;
}

.bind-word.is-default {
  color: var(--text-1);
}

.bind-word + .bind-word::before {
  content: "\00B7";
  margin: 0 7px;
  color: var(--text-3);
}

.bind-editor {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-top: 8px;
}

.bind-editor .el-select {
  width: 340px;
}

.bind-hint {
  margin-top: 6px;
  color: var(--text-3);
  font-size: 11px;
}

.e-side {
  flex: 0 0 auto;
  display: flex;
  flex-direction: column;
  align-items: flex-end;
  gap: 8px;
  padding-top: 4px;
}

.side-cap {
  color: var(--text-3);
  font-size: 9.5px;
  letter-spacing: 0.22em;
}

/* ===== 技能目录表 ===== */
.skill-table {
  width: 100%;
  border-collapse: collapse;
  border-top: 1px solid var(--rule-strong);
}

.skill-table th {
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

.skill-table td {
  padding: 13px 10px;
  border-bottom: 1px solid rgba(236, 233, 225, 0.06);
  font-size: 13px;
  vertical-align: baseline;
}

.skill-table tbody tr:hover td {
  background: var(--row-hover);
}

.skill-table .col-no {
  width: 44px;
}

.skill-code {
  color: var(--text-1);
  font-size: 12.5px;
  background: none;
  border: 0;
  padding: 0;
}

.cell-desc {
  color: var(--text-2);
  font-size: 12.5px;
  line-height: 1.65;
}

.word-list {
  display: flex;
  flex-wrap: wrap;
  gap: 4px 0;
  color: var(--text-2);
  font-size: 11px;
  line-height: 1.8;
}

.w-item i {
  font-style: normal;
  color: var(--text-3);
  font-size: 9.5px;
  margin-left: 4px;
}

.w-item + .w-item::before {
  content: "\00B7";
  margin: 0 6px;
  color: var(--text-3);
}

/* ===== 读数列（与全局一致） ===== */
.ledger {
  display: flex;
  padding: 18px 0 16px;
}

.ledger .cell {
  flex: 1;
}

.ledger .cell + .cell {
  border-left: 1px solid var(--rule);
  padding-left: 30px;
}

.ledger .k {
  color: var(--text-3);
  font-size: 10px;
  letter-spacing: 0.3em;
  font-family: var(--font-mono);
}

.ledger .v {
  margin-top: 8px;
  color: var(--text-1);
  font-family: var(--font-mono);
  font-size: 30px;
  font-weight: 500;
  font-variant-numeric: tabular-nums;
  line-height: 1.1;
}

.ledger .v small {
  font-size: 14px;
  color: var(--text-3);
  margin-left: 3px;
}
</style>