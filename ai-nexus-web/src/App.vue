<template>
  <BootSequence />
  <router-view v-slot="{ Component, route }">
    <MainLayout v-if="route.meta.requiresAuth">
      <keep-alive :include="keepAliveViews">
        <component :is="Component" :key="viewKey" />
      </keep-alive>
    </MainLayout>
    <component v-else :is="Component" />
  </router-view>
</template>

<script setup>
import { computed } from 'vue'
import { useRoute } from 'vue-router'
import MainLayout from './layouts/MainLayout.vue'
import BootSequence from './components/BootSequence.vue'

// 聊天类页面启用组件缓存：切换菜单时保留对话状态，后台继续接收流式回答。
// 第1步：key 只用 route.path，params/query 不参与缓存实例的计算——
// 同一页面只是查询参数不同（如 ?kbId=1 → ?kbId=2）时复用同一个缓存实例，
// 否则 key 变化会让 keep-alive 里堆出永不释放的新实例。
// 第2步：智能体页例外——它的 agent 参数在 setup 里只读一次（AgentToolView.vue:395），
// 复用同一实例会导致「从 A 切到 B 仍显示 A」，因此把 agent 并进 key，切换时重建实例。
// 第3步：include 仍按组件名匹配，ChatView/AgentToolView/DataAnalysisView 照常命中缓存。
const keepAliveViews = ['AgentToolView', 'DataAnalysisView', 'ChatView']

const currentRoute = useRoute()
const viewKey = computed(() => {
  const agent = currentRoute.query.agent
  return agent ? `${currentRoute.path}?agent=${agent}` : currentRoute.path
})
</script>

<style>
</style>
