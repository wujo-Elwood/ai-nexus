<template>
  <transition name="pill-fade">
    <button
      v-if="visible"
      type="button"
      class="agent-return-pill"
      :title="`回到 ${recent.agentName}（会话自动接上）`"
      @click="returnToAgent"
    >
      <span class="pill-icon">⟲</span>
      <span class="pill-label">{{ recent.agentName }}</span>
    </button>
  </transition>
</template>

<script setup>
// 全局"回到智能体"悬浮链接（会话级入口）：
// - 本次打开页面期间进入过智能体才显示，刷新/重开页面即消失；
// - 10 分钟未再次进入智能体自动消失；
// - 点击按记录的 fullPath 原样跳回——命中 keep-alive 缓存时整页状态瞬时恢复，
//   缓存未命中（如刷新后重新进入）则由 AgentToolView 的最近会话记忆自动接上。
// 记录来源是内存态 store（stores/agentRecent），与侧边栏动态菜单用的
// localStorage agent-recent 互不相干，后者保持跨刷新持久。
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { readAgentVisit } from '../stores/agentRecent'

const route = useRoute()
const router = useRouter()

// 这些页面自身与智能体相关，不需要返回入口
const HIDDEN_PATHS = ['/agent-tools', '/agents', '/login', '/register']

const recent = ref(readAgentVisit())

// 路由切换时复查；另用短周期定时器保证 10 分钟到期后链接自动淡出
watch(() => route.fullPath, () => {
  recent.value = readAgentVisit()
})

let expiryTimer = null
onMounted(() => {
  expiryTimer = window.setInterval(() => {
    recent.value = readAgentVisit()
  }, 10_000)
})

onBeforeUnmount(() => {
  window.clearInterval(expiryTimer)
})

const visible = computed(() => {
  return Boolean(recent.value && recent.value.agentCode)
    && !HIDDEN_PATHS.includes(route.path)
})

function returnToAgent() {
  const target = recent.value.fullPath
    || `/agent-tools?agent=${recent.value.agentCode}&name=${encodeURIComponent(recent.value.agentName || recent.value.agentCode)}`
  router.push(target)
}
</script>

<style scoped>
.agent-return-pill {
  position: fixed;
  right: 28px;
  /* 抬高避开聊天/智能体页右下角的发送按钮与输入工具行 */
  bottom: 128px;
  z-index: 1900;
  display: inline-flex;
  align-items: center;
  gap: 6px;
  max-width: 320px;
  padding: 2px 0;
  border: none;
  color: var(--amber-hi, #ece9e1);
  background: none;
  font-size: 13px;
  font-weight: 500;
  text-decoration: underline;
  text-underline-offset: 5px;
  text-decoration-color: rgba(236, 233, 225, 0.5);
  transition: color 0.18s ease;
}

.agent-return-pill:hover {
  color: var(--text-1, #f2efe9);
}

.pill-icon {
  font-size: 15px;
  line-height: 1;
}

.pill-label {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.pill-fade-enter-active,
.pill-fade-leave-active {
  transition: opacity 0.2s ease, transform 0.2s ease;
}

.pill-fade-enter-from,
.pill-fade-leave-to {
  opacity: 0;
  transform: translateY(8px);
}
</style>
