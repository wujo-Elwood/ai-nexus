<template>
    <div class="stats-page page-shell">
      <section class="page-hero">
        <div>
          <span class="eyebrow">Usage Analytics</span>
          <h1 class="section-title">用量统计</h1>
          <p class="section-desc">查看模型调用次数、Token 消耗和最近 7 天趋势，方便评估系统使用情况。</p>
        </div>
      </section>

      <section class="stats-cards">
        <div class="stat-card">
          <span>今日调用次数</span>
          <strong>{{ today.callCount || 0 }}</strong>
        </div>
        <div class="stat-card">
          <span>今日总 Token</span>
          <strong>{{ formatNumber(today.totalTokens) }}</strong>
        </div>
        <div class="stat-card">
          <span>输入 Token</span>
          <strong>{{ formatNumber(today.promptTokens) }}</strong>
        </div>
        <div class="stat-card">
          <span>输出 Token</span>
          <strong>{{ formatNumber(today.completionTokens) }}</strong>
        </div>
      </section>

      <section class="trend-section glass-panel">
        <div class="trend-head">
          <h2>最近 7 天趋势</h2>
          <p>柱状高度按调用次数自动缩放。</p>
        </div>
        <div class="trend-chart">
          <div v-for="day in dailyStats" :key="day.date" class="trend-bar-wrapper">
            <div class="trend-bar-bg">
              <div class="trend-bar" :style="{ height: getBarHeight(day.callCount) + '%' }">
                <span class="trend-bar-value">{{ day.callCount }}</span>
              </div>
            </div>
            <div class="trend-bar-label">{{ formatDate(day.date) }}</div>
          </div>
          <div v-if="dailyStats.length === 0" class="empty-trend">暂无数据</div>
        </div>
      </section>
    </div>
</template>

<script setup>
import { onMounted, ref } from 'vue'
import { getUsageStats } from '../../api/usage'

const today = ref({})
const dailyStats = ref([])

onMounted(() => {
  loadStats()
})

// 加载统计数据
async function loadStats() {
  try {
    // 第1步：请求后端统计接口
    const res = await getUsageStats()
    // 第2步：保存今日数据
    today.value = res.data?.today || {}
    // 第3步：保存每日趋势数据
    dailyStats.value = res.data?.daily || []
  } catch (error) {
    // 第4步：加载失败时记录错误
    console.error(error)
  }
}

// 格式化数字
function formatNumber(value) {
  // 第1步：空值显示为 0
  if (!value) {
    return '0'
  }
  // 第2步：加千分位显示
  return Number(value).toLocaleString()
}

// 格式化日期
function formatDate(dateStr) {
  // 第1步：空日期直接返回空字符串
  if (!dateStr) {
    return ''
  }
  // 第2步：显示月日
  const d = new Date(dateStr)
  return `${d.getMonth() + 1}/${d.getDate()}`
}

// 计算柱状图高度
function getBarHeight(count) {
  // 第1步：没有调用次数时高度为 0
  if (!count || dailyStats.value.length === 0) {
    return 0
  }
  // 第2步：找到最大调用次数
  const max = Math.max(...dailyStats.value.map(d => d.callCount || 0), 1)
  // 第3步：按最大值计算百分比
  return (count / max) * 100
}
</script>

<style scoped>
.stats-cards {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 18px;
  margin-bottom: 20px;
}

.stat-card {
  min-height: 150px;
  padding: 24px;
  border-radius: 26px;
  color: #ffffff;
  background: linear-gradient(135deg, #101828, #344054);
  box-shadow: var(--shadow-soft);
}

.stat-card:nth-child(2) {
  background: linear-gradient(135deg, #155eef, #123f96);
}

.stat-card:nth-child(3) {
  background: linear-gradient(135deg, #0f9f7a, #08785c);
}

.stat-card:nth-child(4) {
  background: linear-gradient(135deg, #b54708, #d97706);
}

.stat-card span {
  color: rgba(255, 255, 255, 0.74);
}

.stat-card strong {
  display: block;
  margin-top: 18px;
  font-size: 38px;
  font-weight: 900;
}

.trend-section {
  padding: 26px;
}

.trend-head {
  margin-bottom: 22px;
}

.trend-head h2 {
  font-size: 24px;
  font-weight: 900;
}

.trend-head p {
  margin-top: 6px;
  color: var(--muted-color);
}

.trend-chart {
  display: flex;
  gap: 14px;
  align-items: flex-end;
  min-height: 260px;
}

.trend-bar-wrapper {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
}

.trend-bar-bg {
  width: 100%;
  height: 220px;
  display: flex;
  align-items: flex-end;
  justify-content: center;
  border-radius: 18px;
  background: rgba(21, 94, 239, 0.06);
}

.trend-bar {
  position: relative;
  width: 62%;
  min-height: 4px;
  border-radius: 14px 14px 0 0;
  background: linear-gradient(180deg, #0f9f7a, #155eef);
  transition: height 0.3s ease;
}

.trend-bar-value {
  position: absolute;
  top: -26px;
  left: 50%;
  transform: translateX(-50%);
  color: var(--muted-color);
  font-size: 12px;
  font-weight: 800;
  white-space: nowrap;
}

.trend-bar-label {
  margin-top: 10px;
  color: var(--muted-color);
  font-weight: 700;
}

.empty-trend {
  width: 100%;
  padding: 80px 0;
  color: var(--muted-color);
  text-align: center;
}

@media (max-width: 760px) {
  .stats-cards {
    grid-template-columns: repeat(2, 1fr);
  }
}
</style>
