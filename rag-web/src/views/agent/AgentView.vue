<template>
  <div class="agent-page page-shell">
    <section class="page-hero agent-hero">
      <div>
        <span class="eyebrow">智能体管理</span>
        <h1 class="section-title">当前智能体</h1>
        <p class="section-desc">
          这里作为智能体入口总览，只展示平台当前可用的智能体。具体运行、报告和配置操作进入对应智能体后再使用。
        </p>
      </div>
      <el-button :loading="loading" @click="loadAgents">
        <el-icon><Refresh /></el-icon>
        刷新
      </el-button>
    </section>

    <section v-if="displayAgentList.length > 0" class="agent-grid">
      <article
        v-for="agent in displayAgentList"
        :key="agent.code"
        class="agent-card motion-card"
        role="button"
        tabindex="0"
        @click="openAgent(agent)"
        @keydown.enter.prevent="openAgent(agent)"
        @keydown.space.prevent="openAgent(agent)"
      >
        <div class="agent-card-top">
          <div class="agent-symbol">
            <el-icon><Cpu /></el-icon>
          </div>
          <el-tag :type="getStatusTagType(agent.status)" effect="dark">
            {{ getStatusText(agent.status) }}
          </el-tag>
        </div>

        <div class="agent-card-body">
          <h2>{{ agent.name }}</h2>
          <p>{{ agent.description }}</p>
        </div>

        <div class="agent-tags">
          <span v-for="tag in getAgentTags(agent)" :key="tag">{{ tag }}</span>
        </div>

        <div class="agent-meta-row">
          <span>{{ agent.modelDisplayName || agent.modelName || '跟随模型设置' }}</span>
          <small>{{ agent.scene || '平台智能体' }}</small>
        </div>

        <div class="agent-card-footer">
          <el-button type="primary" @click.stop="openAgent(agent)">
            进入智能体
            <el-icon><ArrowRight /></el-icon>
          </el-button>
        </div>
      </article>
    </section>

    <section v-else class="empty-state glass-panel">
      <div class="empty-icon">
        <el-icon><Cpu /></el-icon>
      </div>
      <h3>还没有可展示的智能体</h3>
      <p>后端返回智能体后，这里会以卡片形式展示入口。</p>
    </section>
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { ArrowRight, Cpu, Refresh } from '@element-plus/icons-vue'
import { getAgentList } from '../../api/agent'

const router = useRouter()
const agentList = ref([])
const loading = ref(false)

const fallbackAgentList = [
  {
    code: 'general-tool',
    name: '天气查询智能体',
    description: '调用天气定位与查询工具回答实时天气问题，每一步执行过程实时可见。',
    status: 'ENABLED',
    version: '1.0',
    scene: '实时天气查询、坐标定位、执行过程可视化',
    modelDisplayName: '跟随模型设置'
  }
]

// 计算当前要展示的智能体卡片
const displayAgentList = computed(() => {
  if (agentList.value.length > 0) {
    return agentList.value
  }
  return fallbackAgentList
})

onMounted(() => {
  loadAgents()
})

// 加载智能体卡片列表
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

// 进入选中的智能体详情页
function openAgent(agent) {
  if (agent.code === 'general-tool') {
    router.push('/agent-tools')
    return
  }
  ElMessage.info('这个智能体详情页还没有接入')
}

// 获取智能体状态文案
function getStatusText(status) {
  if (status === 'ENABLED') {
    return '已启用'
  }
  if (status === 'DISABLED') {
    return '已停用'
  }
  return status || '未知'
}

// 获取智能体状态标签颜色
function getStatusTagType(status) {
  if (status === 'ENABLED') {
    return 'success'
  }
  return 'info'
}

// 获取智能体能力标签
function getAgentTags(agent) {
  if (agent.code === 'general-tool') {
    return ['天气查询', '坐标定位', '步骤可视', '实时数据']
  }
  return [agent.scene || '智能体']
}
</script>

<style scoped>
.agent-hero {
  padding-top: 0;
  padding-bottom: 24px;
}

.agent-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 16px;
}

.agent-card {
  min-height: 280px;
  display: flex;
  flex-direction: column;
  padding: 20px;
  border: 1px solid var(--border);
  border-radius: 12px;
  color: var(--ink-color);
  background: var(--surface-elevated);
  outline: none;
  cursor: pointer;
}

.agent-card:focus-visible {
  border-color: rgba(229, 160, 68, 0.72) !important;
  box-shadow: 0 0 0 3px rgba(229, 160, 68, 0.14) !important;
}

.agent-card-top,
.agent-meta-row,
.agent-card-footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.agent-symbol {
  width: 44px;
  height: 44px;
  display: grid;
  place-items: center;
  border: 1px solid rgba(229, 160, 68, 0.26);
  border-radius: 12px;
  color: var(--primary-color);
  font-size: 22px;
  background:
    radial-gradient(circle at 30% 20%, rgba(255, 230, 170, 0.18), transparent 52%),
    rgba(229, 160, 68, 0.1);
}

.agent-card-body {
  margin-top: 22px;
}

.agent-card-body h2 {
  margin: 0;
  color: var(--ink-color);
  font-size: 18px;
  font-weight: 850;
}

.agent-card-body p {
  min-height: 68px;
  margin-top: 10px;
  color: var(--muted-color);
  font-size: 13px;
  line-height: 1.7;
}

.agent-tags {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-top: 16px;
}

.agent-tags span {
  display: inline-flex;
  align-items: center;
  min-height: 26px;
  padding: 0 9px;
  border: 1px solid rgba(247, 245, 242, 0.08);
  border-radius: 8px;
  color: var(--text-secondary);
  background: rgba(247, 245, 242, 0.04);
  font-size: 12px;
  font-weight: 750;
}

.agent-meta-row {
  margin-top: auto;
  padding-top: 18px;
}

.agent-meta-row span {
  max-width: 190px;
  padding: 7px 11px;
  border-radius: 999px;
  color: var(--primary-color);
  background: rgba(229, 160, 68, 0.12);
  font-size: 12px;
  font-weight: 850;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.agent-meta-row small {
  max-width: 160px;
  color: var(--muted-color);
  font-size: 12px;
  line-height: 1.4;
  text-align: right;
}

.agent-card-footer {
  justify-content: flex-end;
  margin-top: 18px;
}

.empty-state {
  display: grid;
  place-items: center;
  min-height: 280px;
  padding: 32px;
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

.empty-state h3 {
  margin: 0;
  color: var(--ink-color);
  font-size: 18px;
}

.empty-state p {
  margin-top: 8px;
  color: var(--muted-color);
}

@media (max-width: 1080px) {
  .agent-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}

@media (max-width: 760px) {
  .agent-hero {
    display: grid;
  }

  .agent-grid {
    grid-template-columns: 1fr;
  }
}
</style>
