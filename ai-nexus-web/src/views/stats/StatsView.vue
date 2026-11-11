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
  // 第2步：以最大值为满高基准，但保底 5 次，避免单条少量调用把柱子顶满整个图表造成比例失真
  const max = Math.max(...dailyStats.value.map(d => d.callCount || 0), 1)
  const scale = Math.max(max, 5)
  // 第3步：按基准计算百分比
  return (count / scale) * 100
}
</script>

<style scoped>
/* 读数条：四格仪表读数，琥珀 mono 数字 */
.stats-cards {
  display: flex;
  margin-bottom: 20px;
  border: 0;
  border-radius: 0;
  background: none;
}

.stat-card {
  position: relative;
  flex: 1;
  min-height: 84px;
  padding: 14px 24px;
  border-right: 1px solid var(--line);
  border-radius: 0;
  color: var(--text-1);
  background: transparent;
  box-shadow: none;
}

.stat-card:last-child {
  border-right: none;
}

.stat-card span {
  color: var(--text-3);
  font-size: 11.5px;
}

.stat-card strong {
  display: block;
  margin-top: 6px;
  color: var(--amber-hi);
  font-family: var(--font-mono);
  font-feature-settings: "zero" off;
  font-size: 26px;
  font-weight: 600;
  line-height: 1.15;
  text-shadow: 0 0 14px rgba(236, 233, 225, 0.35);
}

.trend-head h2 {
  font-family: var(--font-display);
  letter-spacing: 0.02em;
}

.trend-section {
  padding: 20px 24px;
}

.trend-head {
  display: flex;
  align-items: baseline;
  gap: 14px;
  margin-bottom: 22px;
  padding-bottom: 12px;
  border-bottom: 1px solid var(--line);
}

.trend-head h2 {
  font-size: 14px;
  font-weight: 600;
}

.trend-head p {
  margin-top: 0;
  color: var(--text-3);
  font-size: 12.5px;
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
  border-radius: 0;
  background: none;
  border-bottom: 1px solid var(--rule);
}

.trend-bar {
  position: relative;
  width: 58%;
  min-height: 4px;
  border-radius: 0;
  background: rgba(236, 233, 225, 0.20);
  border: 1px solid rgba(236, 233, 225, 0.5);
  border-bottom: none;
  box-shadow: none;
  transition: height 0.3s ease;
}

/* 今天的柱：朱砂 */
.trend-chart .trend-bar-wrapper:last-child .trend-bar {
  background: rgba(255, 107, 87, 0.28);
  border-color: rgba(255, 107, 87, 0.65);
}

.trend-chart .trend-bar-wrapper:last-child .trend-bar-value {
  color: var(--signal);
}

.trend-bar-value {
  position: absolute;
  top: -26px;
  left: 50%;
  transform: translateX(-50%);
  color: var(--amber-hi);
  font-family: var(--font-mono);
  font-size: 12px;
  font-weight: 500;
  white-space: nowrap;
}

.trend-bar-label {
  margin-top: 10px;
  color: var(--text-3);
  font-family: var(--font-mono);
  font-size: 11.5px;
  font-weight: 500;
}

.empty-trend {
  width: 100%;
  padding: 80px 0;
  color: var(--text-3);
  text-align: center;
}

@media (max-width: 760px) {
  .stats-cards {
    flex-direction: column;
  }

  .stat-card {
    border-right: none;
    border-bottom: 1px solid var(--line);
  }
}
</style>
