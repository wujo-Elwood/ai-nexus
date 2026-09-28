<template>
  <div ref="rootRef" class="jarvis-root">
    <!-- 背景层：数据流瀑布 + 空间网格 -->
    <canvas ref="rainRef" class="jr-rain" aria-hidden="true"></canvas>
    <div class="jr-grid" aria-hidden="true"></div>

    <!-- 顶部状态栏 -->
    <header class="jr-topbar">
      <div class="jr-brand">
        <span class="jr-brand-mark">◎</span>
        <div class="jr-brand-text">
          <h1>J.A.R.V.I.S.</h1>
          <p>Just A Rather Very Intelligent System</p>
        </div>
      </div>
      <div class="jr-top-status">
        <span class="jr-live-dot"></span>
        <span>ONLINE · STARK INDUSTRIES NETWORK</span>
      </div>
      <div class="jr-clock">
        <strong>{{ clockText }}</strong>
        <span>{{ dateText }}</span>
      </div>
      <button type="button" class="jr-exit-btn" @click="exitToMenu">◄ 返回主菜单</button>
    </header>

    <!-- 中央舞台：陀螺环 + 弧反应堆 + 雷达 -->
    <div class="jr-stage">
      <div class="jr-gyro" aria-hidden="true">
        <div class="jg-ticks"></div>
        <div class="jg-ring jg-r1"><i class="jg-sat"></i></div>
        <div class="jg-ring jg-r2"><i class="jg-sat jg-sat-b"></i></div>
        <div class="jg-ring jg-r3"></div>
        <div class="jg-ring jg-r4"></div>
        <div class="jr-core">
          <i class="jr-core-ring"></i>
          <strong>{{ powerPct }}<span>%</span></strong>
        </div>
      </div>

      <div class="jr-radar jr-clickable" @click="togglePanel('scan')">
        <canvas ref="radarRef"></canvas>
        <span class="jr-radar-label">RADAR · 全球扫描</span>
      </div>
    </div>

    <!-- 左侧：系统监控（点击展开能量面板） -->
    <aside class="jr-side jr-side-left jr-clickable" @click="togglePanel('reactor')">
      <h3><span>SYS</span>系统监控</h3>
      <div v-for="s in stats" :key="s.label" class="js-row">
        <span class="js-label">{{ s.label }}</span>
        <div class="js-bar"><i :style="{ width: s.bar + '%' }"></i></div>
        <span class="js-val">{{ s.value }}<em>{{ s.unit }}</em></span>
      </div>
      <p class="js-hint">点击展开 · 能量面板</p>
    </aside>

    <!-- 右侧：目标追踪（点击展开扫描面板） -->
    <aside class="jr-side jr-side-right jr-clickable" @click="togglePanel('scan')">
      <h3><span>TGT</span>目标追踪</h3>
      <div v-for="t in targets" :key="t.id" class="jt-row">
        <span class="jt-id" :class="'th-' + t.threat">{{ t.id }}</span>
        <span class="jt-name">{{ t.name }}</span>
        <span class="jt-dist">{{ t.dist.toFixed(1) }} km</span>
      </div>
      <p class="js-hint">点击展开 · 扫描面板</p>
    </aside>

    <!-- 全息面板层：点击功能坞/侧栏展开 -->
    <div class="jr-panels">
      <transition name="holo">
        <section v-if="isOpen('reactor')" class="jp-panel">
          <header class="jp-head">
            <span class="jp-dot"></span>
            <h3>ARC REACTOR · 能量输出</h3>
            <button type="button" class="jp-close" @click="togglePanel('reactor')">×</button>
          </header>
          <div class="jp-body">
            <div class="jp-gauge-row">
              <div class="jp-gauge">
                <i class="jp-gauge-ring" :style="{ '--p': powerPct }"></i>
                <strong>{{ powerPct }}<span>%</span></strong>
              </div>
              <ul class="jp-kv">
                <li><span>输出功率</span><b>8.4 GJ/s</b></li>
                <li><span>核心温度</span><b>4 210 K</b></li>
                <li><span>钯污染</span><b>0.02%</b></li>
                <li><span>磁场强度</span><b>3.6 T</b></li>
              </ul>
            </div>
            <svg class="jp-spark" viewBox="0 0 200 48" preserveAspectRatio="none" aria-hidden="true">
              <polyline :points="reactorPoints" />
            </svg>
            <p class="jp-foot">输出曲线 · 最近 24 秒</p>
          </div>
        </section>
      </transition>

      <transition name="holo">
        <section v-if="isOpen('scan')" class="jp-panel">
          <header class="jp-head">
            <span class="jp-dot"></span>
            <h3>GLOBAL SCAN · 全球扫描</h3>
            <button type="button" class="jp-close" @click="togglePanel('scan')">×</button>
          </header>
          <div class="jp-body">
            <div class="jp-table-head">
              <span>编号</span><span>方位</span><span>距离</span><span>状态</span>
            </div>
            <div v-for="t in targets" :key="t.id" class="jp-tr">
              <span class="jp-td-id" :class="'th-' + t.threat">{{ t.id }}</span>
              <span>{{ Math.round(t.brg) }}°</span>
              <span>{{ t.dist.toFixed(1) }} km</span>
              <span class="jp-td-status">{{ threatText(t.threat) }}</span>
            </div>
            <p class="jp-foot">扫描周期 4.0s · 检测 {{ targets.length }} 个目标</p>
          </div>
        </section>
      </transition>

      <transition name="holo">
        <section v-if="isOpen('diag')" class="jp-panel">
          <header class="jp-head">
            <span class="jp-dot"></span>
            <h3>MARK LXXXV · 装甲诊断</h3>
            <button type="button" class="jp-close" @click="togglePanel('diag')">×</button>
          </header>
          <div class="jp-body">
            <div v-for="(d, i) in diagRows" :key="d.label" class="jp-diag" :style="{ animationDelay: i * 0.09 + 's' }">
              <span class="jp-diag-label">{{ d.label }}</span>
              <b :class="{ warn: !d.ok }">{{ d.value }}</b>
            </div>
            <p class="jp-foot">自检完成 · 5 项正常 / 1 项警告</p>
          </div>
        </section>
      </transition>

      <transition name="holo">
        <section v-if="isOpen('sat')" class="jp-panel">
          <header class="jp-head">
            <span class="jp-dot"></span>
            <h3>SATELLITE UPLINK · 卫星链路</h3>
            <button type="button" class="jp-close" @click="togglePanel('sat')">×</button>
          </header>
          <div class="jp-body">
            <div v-for="s in sats" :key="s.name" class="jp-sat">
              <span class="jp-sat-name">{{ s.name }}</span>
              <span class="jp-sat-orb">{{ s.orb }}</span>
              <span class="jp-sig">
                <i v-for="b in 5" :key="b" :class="{ on: b <= Math.round(s.signal / 20) }" :style="{ animationDelay: b * 0.12 + 's' }"></i>
              </span>
              <b class="jp-sat-val">{{ s.signal }}%</b>
            </div>
            <p class="jp-foot">加密信道 AES-4096 · 延迟 12ms</p>
          </div>
        </section>
      </transition>
    </div>

    <!-- 底部：JARVIS 台词 + 声纹 + 功能坞 -->
    <footer class="jr-footer">
      <div class="jr-speech">
        <span class="jr-speech-tag">JARVIS</span>
        <p class="jr-speech-line">{{ jarvisLine }}<span class="jr-caret"></span></p>
        <p class="jr-speech-hint">移动鼠标校准瞄准 · 底部功能坞展开全息面板 · ESC 关闭全部</p>
      </div>
      <div class="jr-wave" aria-hidden="true">
        <i v-for="(h, i) in waveBars" :key="i" :style="{ height: h + 'px', animationDelay: i * 0.08 + 's' }"></i>
      </div>
      <nav class="jr-dock">
        <button
          v-for="p in panelDefs"
          :key="p.id"
          type="button"
          class="jr-dock-btn"
          :class="{ open: p.open }"
          @click="togglePanel(p.id)"
        >
          {{ p.code }}
        </button>
      </nav>
    </footer>

    <!-- 屏幕四角装饰 -->
    <div class="jr-frame" aria-hidden="true"><i></i><i></i><i></i><i></i></div>

    <!-- 跟随鼠标的瞄准框 -->
    <div ref="reticleRef" class="jr-reticle" aria-hidden="true">
      <span class="jret tl"></span>
      <span class="jret tr"></span>
      <span class="jret bl"></span>
      <span class="jret br"></span>
      <span class="jret ch"></span>
      <span class="jret cv"></span>
      <span class="jret ring"></span>
      <span ref="retLabelRef" class="jret label">SCAN</span>
    </div>

    <!-- 氛围覆盖层 -->
    <div class="jr-vignette" aria-hidden="true"></div>
    <div class="jr-veil" aria-hidden="true"></div>
    <div class="jr-scanlines" aria-hidden="true"></div>

    <!-- 启动序列 -->
    <transition name="bootfade">
      <div v-if="booting" class="jr-boot" @click="finishBoot">
        <span class="jr-boot-mark">◎</span>
        <div class="jr-boot-lines">
          <p v-for="(l, i) in bootShown" :key="i">{{ l }}</p>
        </div>
        <div class="jr-boot-bar"><i></i></div>
        <p class="jr-boot-skip">点击任意处跳过</p>
      </div>
    </transition>
  </div>
</template>

<script setup>
import { computed, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'

const router = useRouter()

// 工作台是全屏覆盖层，跳回知识库页即回到常规项目菜单
function exitToMenu() {
  router.push('/kb')
}

// ============ 模板引用 ============
const rootRef = ref(null)
const rainRef = ref(null)
const radarRef = ref(null)
const reticleRef = ref(null)
const retLabelRef = ref(null)

// ============ 顶部状态 ============
const clockText = ref('')
const dateText = ref('')
const powerPct = ref(94)

// ============ 启动序列 ============
const booting = ref(true)
const bootShown = ref([])
const BOOT_LINES = [
  '初始化全息投影矩阵 …… OK',
  '加载姿态陀螺仪阵列 …… OK',
  '雷达扫描线上线 …… OK',
  '能量核心握手 · 弧反应堆 94% …… OK',
  'J.A.R.V.I.S. 已上线'
]
let bootTimer = 0
let bootHideTimer = 0

function startBoot() {
  let i = 0
  bootTimer = window.setInterval(() => {
    bootShown.value.push(BOOT_LINES[i])
    i += 1
    if (i >= BOOT_LINES.length) {
      window.clearInterval(bootTimer)
      bootHideTimer = window.setTimeout(finishBoot, 700)
    }
  }, 340)
}

function finishBoot() {
  if (!booting.value) return
  window.clearInterval(bootTimer)
  window.clearTimeout(bootHideTimer)
  booting.value = false
}

// ============ JARVIS 台词打字机 ============
const jarvisLine = ref('')
const JARVIS_PHRASES = [
  '晚上好，先生。全息工作台已就绪，所有系统运行正常。',
  '弧反应堆输出稳定，能量储备充足。',
  '雷达阵列检测到多个目标，威胁等级均在可控范围内。',
  '先生，要不要我把咖啡机也接入系统？',
  '正在后台同步斯塔克工业数据库，进度 67%。',
  '全息投影矩阵校准完成，误差 0.02%。',
  '如果需要的话，我可以把这一幕录下来，先生。'
]
let phraseIndex = 0
let typeTimer = 0
let phraseTimer = 0

function speak(text) {
  window.clearInterval(typeTimer)
  jarvisLine.value = ''
  let idx = 0
  typeTimer = window.setInterval(() => {
    jarvisLine.value = text.slice(0, idx + 1)
    idx += 1
    if (idx >= text.length) window.clearInterval(typeTimer)
  }, 45)
}

function startSpeech() {
  speak(JARVIS_PHRASES[0])
  phraseTimer = window.setInterval(() => {
    phraseIndex = (phraseIndex + 1) % JARVIS_PHRASES.length
    speak(JARVIS_PHRASES[phraseIndex])
  }, 8000)
}

// ============ 左侧系统监控 ============
const stats = reactive([
  { label: 'PWR', value: 94, unit: '%', bar: 94, min: 80, max: 99 },
  { label: 'CPU', value: 62, unit: '%', bar: 62, min: 30, max: 92 },
  { label: 'MEM', value: 71, unit: '%', bar: 71, min: 40, max: 88 },
  { label: 'NET', value: 48, unit: 'Mb', bar: 48, min: 12, max: 96 }
])
let statsTimer = 0

// ============ 目标数据（雷达 + 侧栏 + 扫描面板共用） ============
const targets = reactive([
  { id: 'ST-01', name: '斯塔克塔 · 信标', dist: 0.4, brg: 8, threat: 'none' },
  { id: 'MK-42', name: 'MARK-XLII 无人机', dist: 1.2, brg: 47, threat: 'low' },
  { id: 'AV-03', name: '复仇者大厦 · 中继', dist: 2.6, brg: 275, threat: 'none' },
  { id: 'UN-07', name: '未知目标 · 07', dist: 3.8, brg: 132, threat: 'mid' },
  { id: 'UN-11', name: '未知目标 · 11', dist: 5.1, brg: 319, threat: 'high' }
])
let targetTimer = 0

function threatText(threat) {
  return { none: '友军', low: '观察', mid: '跟踪', high: '锁定' }[threat] || '未知'
}

// ============ 全息面板 ============
const panelDefs = reactive([
  { id: 'reactor', code: 'A.R.', open: false },
  { id: 'scan', code: 'SCAN', open: false },
  { id: 'diag', code: 'DIAG', open: false },
  { id: 'sat', code: 'SAT', open: false }
])

function isOpen(id) {
  const p = panelDefs.find(item => item.id === id)
  return p ? p.open : false
}

function togglePanel(id) {
  const p = panelDefs.find(item => item.id === id)
  if (!p) return
  p.open = !p.open
  if (p.open) {
    const lines = {
      reactor: '能量面板已展开，反应堆状态良好。',
      scan: '正在扫描全球网络节点，请稍候。',
      diag: '启动装甲自检程序，预计两秒完成。',
      sat: '卫星链路已建立，加密信道全开。'
    }
    if (lines[id]) speak(lines[id])
  }
}

const diagRows = [
  { label: '推进器阵列', value: 'NOMINAL', ok: true },
  { label: '飞行稳定系统', value: 'NOMINAL', ok: true },
  { label: '纳米装甲完整度', value: '97%', ok: true },
  { label: '目标追踪系统', value: 'ONLINE', ok: true },
  { label: '能量护盾', value: '82% · WARN', ok: false },
  { label: '语音模块', value: 'ONLINE', ok: true }
]

const sats = [
  { name: 'STARK-7', orb: 'LEO', signal: 96 },
  { name: 'PEPPER-3', orb: 'MEO', signal: 82 },
  { name: 'FRIDAY-9', orb: 'LEO', signal: 91 },
  { name: 'VISION-1', orb: 'GEO', signal: 74 }
]

// ============ 反应堆输出曲线 ============
const reactorSeries = ref(Array.from({ length: 48 }, () => 90 + Math.random() * 8))
let seriesTimer = 0
const reactorPoints = computed(() => {
  const list = reactorSeries.value
  const step = 200 / (list.length - 1)
  return list.map((v, i) => `${(i * step).toFixed(1)},${(46 - (v / 100) * 42).toFixed(1)}`).join(' ')
})

// ============ 底部声纹 ============
const waveBars = ref(Array.from({ length: 36 }, () => 6 + Math.random() * 20))

// ============ 数据流瀑布（背景字符雨） ============
const RAIN_CHARS = '01<>[]{}#$%&*+=アイウエオカキクスソタチナニヌ'
const RAIN_COL_W = 18
const RAIN_ROW_H = 16
let rainCtx = null
let rainColumns = []
let lastRainAt = 0
let rainDpr = 1

function fitRain() {
  const canvas = rainRef.value
  if (!canvas) return
  rainDpr = Math.min(window.devicePixelRatio || 1, 2)
  const rect = canvas.getBoundingClientRect()
  canvas.width = Math.max(1, Math.round(rect.width * rainDpr))
  canvas.height = Math.max(1, Math.round(rect.height * rainDpr))
  const count = Math.ceil(canvas.width / (RAIN_COL_W * rainDpr))
  rainColumns = Array.from({ length: count }, () => ({
    y: Math.random() * 60 - 30,
    speed: 0.5 + Math.random() * 0.9
  }))
  rainCtx = canvas.getContext('2d')
}

function drawRain() {
  if (!rainCtx) return
  const { width, height } = rainCtx.canvas
  rainCtx.clearRect(0, 0, width, height)
  rainCtx.font = `${12 * rainDpr}px Consolas, monospace`
  for (let i = 0; i < rainColumns.length; i += 1) {
    const col = rainColumns[i]
    const x = i * RAIN_COL_W * rainDpr + 2
    for (let k = 0; k < 6; k += 1) {
      const y = (col.y - k) * RAIN_ROW_H * rainDpr
      if (y < 0 || y > height) continue
      const alpha = k === 0 ? 0.55 : 0.42 - k * 0.08
      rainCtx.fillStyle = k === 0 ? `rgba(150,235,255,${alpha})` : `rgba(83,216,255,${alpha})`
      rainCtx.fillText(RAIN_CHARS[(Math.random() * RAIN_CHARS.length) | 0], x, y)
    }
    col.y += col.speed
    if (col.y * RAIN_ROW_H * rainDpr > height + 40 && Math.random() < 0.04) {
      col.y = Math.random() * -20
      col.speed = 0.5 + Math.random() * 0.9
    }
  }
}

// ============ 雷达 ============
let radarCtx = null
let radarDpr = 1
let sweepAngle = 0

function fitRadar() {
  const canvas = radarRef.value
  if (!canvas) return
  radarDpr = Math.min(window.devicePixelRatio || 1, 2)
  const rect = canvas.getBoundingClientRect()
  canvas.width = Math.max(1, Math.round(rect.width * radarDpr))
  canvas.height = Math.max(1, Math.round(rect.height * radarDpr))
  radarCtx = canvas.getContext('2d')
}

function drawRadar() {
  if (!radarCtx) return
  const ctx = radarCtx
  const { width, height } = ctx.canvas
  const cx = width / 2
  const cy = height / 2
  const r = Math.min(cx, cy) - 4 * radarDpr
  ctx.clearRect(0, 0, width, height)

  // 网格环与十字线
  ctx.strokeStyle = 'rgba(83,216,255,0.22)'
  ctx.lineWidth = 1
  for (let i = 1; i <= 4; i += 1) {
    ctx.beginPath()
    ctx.arc(cx, cy, (r * i) / 4, 0, Math.PI * 2)
    ctx.stroke()
  }
  ctx.beginPath()
  ctx.moveTo(cx - r, cy)
  ctx.lineTo(cx + r, cy)
  ctx.moveTo(cx, cy - r)
  ctx.lineTo(cx, cy + r)
  ctx.stroke()

  // 扫描拖尾
  for (let i = 0; i < 55; i += 1) {
    const a = sweepAngle - i * 0.016
    ctx.beginPath()
    ctx.moveTo(cx, cy)
    ctx.lineTo(cx + Math.cos(a) * r, cy + Math.sin(a) * r)
    ctx.strokeStyle = `rgba(83,216,255,${0.38 * (1 - i / 55)})`
    ctx.stroke()
  }
  ctx.beginPath()
  ctx.moveTo(cx, cy)
  ctx.lineTo(cx + Math.cos(sweepAngle) * r, cy + Math.sin(sweepAngle) * r)
  ctx.strokeStyle = 'rgba(180,240,255,0.9)'
  ctx.stroke()

  // 目标光点：扫过时点亮，随后衰减
  for (const t of targets) {
    const a = (t.brg * Math.PI) / 180
    let diff = sweepAngle - a
    while (diff < 0) diff += Math.PI * 2
    if (diff < 0.06) t._alpha = 1
    if (t._alpha === undefined) t._alpha = 0
    t._alpha *= 0.985
    if (t._alpha < 0.02) continue
    const d = r * (0.15 + 0.85 * Math.min(1, t.dist / 6))
    const x = cx + Math.cos(a) * d
    const y = cy + Math.sin(a) * d
    ctx.beginPath()
    ctx.arc(x, y, 2.4 * radarDpr, 0, Math.PI * 2)
    ctx.fillStyle = t.threat === 'high' ? `rgba(255,110,90,${t._alpha})` : `rgba(140,235,180,${t._alpha})`
    ctx.fill()
    if (t._alpha > 0.8) {
      ctx.beginPath()
      ctx.arc(x, y, 6 * radarDpr, 0, Math.PI * 2)
      ctx.strokeStyle = `rgba(180,240,255,${(t._alpha - 0.8) * 2})`
      ctx.stroke()
    }
  }
}

// ============ 瞄准框 ============
let mouseX = -200
let mouseY = -200
let retX = 0
let retY = 0
let reticleSeen = false
let fireTimer = 0

function onPointerMove(event) {
  mouseX = event.clientX
  mouseY = event.clientY
  if (!reticleSeen) {
    reticleSeen = true
    retX = mouseX
    retY = mouseY
    if (reticleRef.value) reticleRef.value.style.opacity = '1'
  }
}

function onPointerOver(event) {
  const hit = event.target.closest && event.target.closest('button, .jr-clickable')
  if (reticleRef.value) reticleRef.value.classList.toggle('is-active', Boolean(hit))
  if (retLabelRef.value) retLabelRef.value.textContent = hit ? 'INTERACT' : 'SCAN'
}

function onPointerDown() {
  if (!reticleRef.value) return
  reticleRef.value.classList.remove('is-fire')
  // 强制重启动效
  void reticleRef.value.offsetWidth
  reticleRef.value.classList.add('is-fire')
  window.clearTimeout(fireTimer)
  fireTimer = window.setTimeout(() => {
    if (reticleRef.value) reticleRef.value.classList.remove('is-fire')
  }, 350)
}

function updateReticle() {
  const el = reticleRef.value
  if (!el || !reticleSeen) return
  retX += (mouseX - retX) * 0.35
  retY += (mouseY - retY) * 0.35
  el.style.transform = `translate3d(${(retX - 60).toFixed(1)}px, ${(retY - 60).toFixed(1)}px, 0)`
  if (retLabelRef.value) {
    retLabelRef.value.textContent =
      el.classList.contains('is-active')
        ? 'INTERACT'
        : `X ${String(Math.round(mouseX)).padStart(4, '0')} · Y ${String(Math.round(mouseY)).padStart(4, '0')}`
  }
}

// ============ 时钟 ============
let clockTimer = 0
function updateClock() {
  const now = new Date()
  clockText.value = now.toLocaleTimeString('zh-CN', { hour12: false })
  dateText.value = now.toLocaleDateString('zh-CN', { year: 'numeric', month: '2-digit', day: '2-digit', weekday: 'short' })
}

// ============ 周期性数据漂移 ============
function startTimers() {
  clockTimer = window.setInterval(updateClock, 1000)
  updateClock()

  statsTimer = window.setInterval(() => {
    for (const s of stats) {
      const next = s.value + (Math.random() - 0.5) * 8
      s.value = Math.round(Math.min(s.max, Math.max(s.min, next)))
      s.bar = s.value
    }
  }, 900)

  targetTimer = window.setInterval(() => {
    for (const t of targets) {
      t.dist = Math.min(6.4, Math.max(0.2, t.dist + (Math.random() - 0.5) * 0.16))
      t.brg = (t.brg + (Math.random() - 0.5) * 6 + 360) % 360
    }
  }, 1400)

  seriesTimer = window.setInterval(() => {
    const list = reactorSeries.value
    list.push(Math.min(99, Math.max(86, list[list.length - 1] + (Math.random() - 0.5) * 5)))
    list.shift()
  }, 500)

  window.setInterval(() => {
    powerPct.value = Math.round(Math.min(99, Math.max(88, powerPct.value + (Math.random() - 0.5) * 4)))
  }, 800)
}

// ============ 主循环 ============
let rafId = 0
let lastTime = 0

function loop(now) {
  const dt = Math.min(50, now - lastTime) / 1000
  lastTime = now
  sweepAngle = (sweepAngle + dt * 1.6) % (Math.PI * 2)
  drawRadar()
  if (now - lastRainAt > 70) {
    lastRainAt = now
    drawRain()
  }
  updateReticle()
  rafId = requestAnimationFrame(loop)
}

// ============ 事件与生命周期 ============
function onResize() {
  fitRain()
  fitRadar()
}

function onKeydown(event) {
  if (event.key === 'Escape') {
    for (const p of panelDefs) p.open = false
  }
}

onMounted(() => {
  fitRain()
  fitRadar()
  startTimers()
  startBoot()
  startSpeech()
  window.addEventListener('pointermove', onPointerMove, { passive: true })
  window.addEventListener('pointerover', onPointerOver, true)
  window.addEventListener('pointerdown', onPointerDown, true)
  window.addEventListener('keydown', onKeydown)
  window.addEventListener('resize', onResize)
  lastTime = performance.now()
  rafId = requestAnimationFrame(loop)
})

onBeforeUnmount(() => {
  cancelAnimationFrame(rafId)
  window.clearInterval(clockTimer)
  window.clearInterval(statsTimer)
  window.clearInterval(targetTimer)
  window.clearInterval(seriesTimer)
  window.clearInterval(typeTimer)
  window.clearInterval(phraseTimer)
  window.clearInterval(bootTimer)
  window.clearTimeout(bootHideTimer)
  window.clearTimeout(fireTimer)
  window.removeEventListener('pointermove', onPointerMove)
  window.removeEventListener('pointerover', onPointerOver, true)
  window.removeEventListener('pointerdown', onPointerDown, true)
  window.removeEventListener('keydown', onKeydown)
  window.removeEventListener('resize', onResize)
})
</script>

<style scoped>
.jarvis-root {
  position: fixed;
  inset: 0;
  overflow: hidden;
  background: radial-gradient(1200px 700px at 50% 42%, #062031 0%, #030f1a 45%, #010609 100%);
  color: #bfeaff;
  font-family: 'Segoe UI', 'PingFang SC', 'Microsoft YaHei', sans-serif;
  user-select: none;
  cursor: none;
}
.jarvis-root * {
  cursor: none;
}

/* ---------- 背景层 ---------- */
.jr-rain {
  position: absolute;
  inset: 0;
  width: 100%;
  height: 100%;
  opacity: 0.45;
  z-index: 0;
}
.jr-grid {
  position: absolute;
  inset: 0;
  z-index: 1;
  background-image:
    linear-gradient(rgba(83, 216, 255, 0.05) 1px, transparent 1px),
    linear-gradient(90deg, rgba(83, 216, 255, 0.05) 1px, transparent 1px);
  background-size: 64px 64px;
  -webkit-mask-image: radial-gradient(ellipse at center, #000 30%, transparent 78%);
  mask-image: radial-gradient(ellipse at center, #000 30%, transparent 78%);
}

/* ---------- 顶部状态栏 ---------- */
.jr-topbar {
  position: absolute;
  top: 0;
  left: 0;
  right: 0;
  height: 68px;
  padding: 0 28px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  z-index: 20;
  border-bottom: 1px solid rgba(83, 216, 255, 0.18);
  background: linear-gradient(180deg, rgba(4, 20, 32, 0.85), rgba(4, 20, 32, 0));
}
.jr-brand {
  display: flex;
  align-items: center;
  gap: 12px;
}
.jr-brand-mark {
  font-size: 30px;
  color: #53d8ff;
  text-shadow: 0 0 14px rgba(83, 216, 255, 0.9);
  animation: jr-mark-pulse 3s ease-in-out infinite;
}
.jr-brand-text h1 {
  margin: 0;
  font-size: 20px;
  letter-spacing: 6px;
  color: #e6f9ff;
  text-shadow: 0 0 12px rgba(83, 216, 255, 0.6);
}
.jr-brand-text p {
  margin: 2px 0 0;
  font-size: 10px;
  letter-spacing: 2px;
  color: rgba(140, 210, 235, 0.55);
}
.jr-top-status {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 11px;
  letter-spacing: 2px;
  color: rgba(140, 235, 255, 0.75);
}
.jr-live-dot {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: #61ffb0;
  box-shadow: 0 0 10px #61ffb0;
  animation: jr-blink 1.6s ease-in-out infinite;
}
.jr-clock {
  text-align: right;
  font-family: Consolas, 'JetBrains Mono', monospace;
}
.jr-clock strong {
  display: block;
  font-size: 20px;
  color: #9fe9ff;
  text-shadow: 0 0 10px rgba(83, 216, 255, 0.5);
}
.jr-clock span {
  font-size: 10px;
  color: rgba(140, 210, 235, 0.55);
}

/* ---------- 中央舞台 ---------- */
.jr-stage {
  position: absolute;
  top: 68px;
  bottom: 118px;
  left: 0;
  right: 0;
  z-index: 10;
}
.jr-gyro {
  position: absolute;
  inset: 0;
  margin: auto;
  width: 460px;
  height: 460px;
  perspective: 900px;
  transform-style: preserve-3d;
}

/* 旋转陀螺环 */
.jg-ring {
  position: absolute;
  inset: 0;
  margin: auto;
  border-radius: 50%;
  transform-style: preserve-3d;
  pointer-events: none;
}
.jg-r1 {
  width: 442px;
  height: 442px;
  border: 1px dashed rgba(83, 216, 255, 0.55);
  box-shadow: 0 0 18px rgba(83, 216, 255, 0.22), inset 0 0 24px rgba(83, 216, 255, 0.12);
  animation: jr-spin-flat 14s linear infinite;
}
.jg-r2 {
  width: 368px;
  height: 368px;
  border: 2px solid rgba(83, 216, 255, 0.42);
  border-top-color: transparent;
  border-bottom-color: rgba(140, 240, 255, 0.85);
  animation: jr-spin-flat-rev 9s linear infinite;
}
.jg-r3 {
  width: 300px;
  height: 300px;
  border: 1px dotted rgba(140, 240, 255, 0.5);
  animation: jr-spin-flat 19s linear infinite;
}
.jg-r4 {
  width: 442px;
  height: 442px;
  border: 1px solid rgba(83, 216, 255, 0.28);
  border-left-color: rgba(180, 245, 255, 0.8);
  animation: jr-spin-meridian 22s linear infinite;
}
/* 刻度环 */
.jg-ticks {
  position: absolute;
  inset: 0;
  margin: auto;
  width: 420px;
  height: 420px;
  border-radius: 50%;
  background: repeating-conic-gradient(rgba(83, 216, 255, 0.55) 0 0.7deg, transparent 0.7deg 6deg);
  -webkit-mask-image: radial-gradient(closest-side, transparent 91%, #000 92%);
  mask-image: radial-gradient(closest-side, transparent 91%, #000 92%);
  opacity: 0.55;
  animation: jr-spin-flat 40s linear infinite;
  pointer-events: none;
}
/* 环上的轨道卫星点 */
.jg-sat {
  position: absolute;
  top: -3px;
  left: 50%;
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: #c9f6ff;
  box-shadow: 0 0 12px rgba(180, 245, 255, 1);
}
.jg-sat-b {
  top: auto;
  bottom: -3px;
  background: #ffd28a;
  box-shadow: 0 0 12px rgba(255, 190, 110, 0.95);
}

/* 弧反应堆核心 */
.jr-core {
  position: absolute;
  inset: 0;
  margin: auto;
  width: 132px;
  height: 132px;
  border-radius: 50%;
  display: flex;
  align-items: center;
  justify-content: center;
  background: radial-gradient(circle at 50% 42%, #eafcff 0%, #9be8ff 22%, #35b6e8 50%, rgba(10, 60, 90, 0.92) 74%, rgba(4, 18, 30, 0.95) 100%);
  box-shadow: 0 0 42px rgba(120, 225, 255, 0.55), 0 0 130px rgba(83, 216, 255, 0.28), inset 0 0 26px rgba(255, 255, 255, 0.25);
  animation: jr-core-pulse 2.8s ease-in-out infinite;
}
.jr-core-ring {
  position: absolute;
  inset: -14px;
  border-radius: 50%;
  border: 1px dashed rgba(200, 245, 255, 0.5);
  animation: jr-spin-flat-rev 6s linear infinite;
}
.jr-core strong {
  font-family: Consolas, 'JetBrains Mono', monospace;
  font-size: 26px;
  color: #04283c;
  text-shadow: 0 0 8px rgba(255, 255, 255, 0.6);
}
.jr-core strong span {
  font-size: 13px;
  margin-left: 1px;
}

/* 雷达 */
.jr-radar {
  position: absolute;
  left: 36px;
  bottom: 20px;
  width: 196px;
  height: 196px;
  padding: 6px;
  border: 1px solid rgba(83, 216, 255, 0.3);
  border-radius: 8px;
  background: rgba(4, 22, 34, 0.55);
  box-shadow: inset 0 0 24px rgba(83, 216, 255, 0.08);
}
.jr-radar canvas {
  width: 100%;
  height: 100%;
  display: block;
  border-radius: 50%;
}
.jr-radar-label {
  position: absolute;
  top: -20px;
  left: 4px;
  font-size: 10px;
  letter-spacing: 2px;
  color: rgba(140, 225, 250, 0.65);
}

/* ---------- 左右侧栏 ---------- */
.jr-side {
  position: absolute;
  top: 50%;
  transform: translateY(-50%);
  width: 232px;
  padding: 14px 16px 10px;
  z-index: 15;
  border: 1px solid rgba(83, 216, 255, 0.28);
  background: linear-gradient(165deg, rgba(8, 30, 46, 0.72), rgba(3, 14, 24, 0.78));
  box-shadow: 0 0 22px rgba(83, 216, 255, 0.1), inset 0 0 30px rgba(83, 216, 255, 0.04);
  clip-path: polygon(0 12px, 12px 0, 100% 0, 100% calc(100% - 12px), calc(100% - 12px) 100%, 0 100%);
  transition: box-shadow 0.25s ease, border-color 0.25s ease;
}
.jr-side:hover {
  border-color: rgba(140, 240, 255, 0.7);
  box-shadow: 0 0 30px rgba(83, 216, 255, 0.25), inset 0 0 30px rgba(83, 216, 255, 0.08);
}
.jr-side-left {
  left: 36px;
}
.jr-side-right {
  right: 36px;
}
.jr-side h3 {
  margin: 0 0 12px;
  font-size: 12px;
  font-weight: 600;
  letter-spacing: 3px;
  color: #9fe9ff;
  border-bottom: 1px solid rgba(83, 216, 255, 0.25);
  padding-bottom: 8px;
}
.jr-side h3 span {
  font-size: 9px;
  color: rgba(83, 216, 255, 0.6);
  margin-right: 8px;
  padding: 2px 5px;
  border: 1px solid rgba(83, 216, 255, 0.4);
}
.js-row {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 10px;
}
.js-label {
  width: 34px;
  font-size: 10px;
  letter-spacing: 1px;
  color: rgba(160, 225, 250, 0.8);
  font-family: Consolas, monospace;
}
.js-bar {
  flex: 1;
  height: 6px;
  background: rgba(83, 216, 255, 0.12);
  overflow: hidden;
  position: relative;
}
.js-bar i {
  position: absolute;
  inset: 0 auto 0 0;
  background: linear-gradient(90deg, rgba(83, 216, 255, 0.5), #9beaff);
  box-shadow: 0 0 8px rgba(83, 216, 255, 0.8);
  transition: width 0.6s ease;
}
.js-val {
  width: 46px;
  text-align: right;
  font-size: 11px;
  font-family: Consolas, monospace;
  color: #c9f4ff;
}
.js-val em {
  font-style: normal;
  font-size: 9px;
  color: rgba(140, 210, 235, 0.5);
}
.js-hint {
  margin: 10px 0 2px;
  font-size: 10px;
  color: rgba(120, 200, 230, 0.4);
  letter-spacing: 1px;
}
.jt-row {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 9px;
  font-size: 11px;
}
.jt-id {
  width: 46px;
  padding: 1px 0;
  text-align: center;
  font-family: Consolas, monospace;
  font-size: 10px;
  border: 1px solid rgba(83, 216, 255, 0.4);
  color: #9fe9ff;
}
.jt-id.th-high {
  border-color: rgba(255, 110, 90, 0.7);
  color: #ff9c8a;
  box-shadow: 0 0 8px rgba(255, 110, 90, 0.35);
}
.jt-id.th-mid {
  border-color: rgba(255, 196, 110, 0.6);
  color: #ffd28a;
}
.jt-id.th-low {
  border-color: rgba(120, 240, 170, 0.5);
  color: #9dffc8;
}
.jt-name {
  flex: 1;
  color: rgba(190, 235, 255, 0.85);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.jt-dist {
  font-family: Consolas, monospace;
  color: rgba(140, 225, 250, 0.75);
}

/* ---------- 全息面板 ---------- */
.jr-panels {
  position: absolute;
  left: 50%;
  bottom: 130px;
  transform: translateX(-50%);
  display: flex;
  flex-wrap: wrap;
  justify-content: center;
  align-items: flex-end;
  gap: 20px;
  z-index: 25;
  pointer-events: none;
  max-width: 94vw;
}
.jp-panel {
  width: 284px;
  pointer-events: auto;
  border: 1px solid rgba(83, 216, 255, 0.4);
  background: linear-gradient(165deg, rgba(10, 34, 52, 0.86), rgba(4, 16, 26, 0.9));
  box-shadow: 0 0 28px rgba(83, 216, 255, 0.16), inset 0 0 42px rgba(83, 216, 255, 0.05);
  clip-path: polygon(0 14px, 14px 0, 100% 0, 100% calc(100% - 14px), calc(100% - 14px) 100%, 0 100%);
  animation: jr-flicker 0.5s linear both;
}
.jp-head {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 10px 12px;
  border-bottom: 1px solid rgba(83, 216, 255, 0.25);
  background: linear-gradient(90deg, rgba(83, 216, 255, 0.14), transparent 60%);
}
.jp-head h3 {
  flex: 1;
  margin: 0;
  font-size: 11px;
  font-weight: 600;
  letter-spacing: 2px;
  color: #bdefff;
}
.jp-dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: #61ffb0;
  box-shadow: 0 0 8px #61ffb0;
  animation: jr-blink 1.4s ease-in-out infinite;
}
.jp-close {
  width: 20px;
  height: 20px;
  padding: 0;
  border: 1px solid rgba(83, 216, 255, 0.4);
  background: transparent;
  color: #9fe9ff;
  font-size: 13px;
  line-height: 1;
}
.jp-close:hover {
  background: rgba(83, 216, 255, 0.2);
}
.jp-body {
  padding: 12px;
}
.jp-foot {
  margin: 10px 0 0;
  font-size: 10px;
  color: rgba(120, 200, 230, 0.45);
  letter-spacing: 1px;
}

/* 能量面板 */
.jp-gauge-row {
  display: flex;
  align-items: center;
  gap: 14px;
}
.jp-gauge {
  position: relative;
  width: 92px;
  height: 92px;
  flex: none;
  display: flex;
  align-items: center;
  justify-content: center;
}
.jp-gauge-ring {
  position: absolute;
  inset: 0;
  border-radius: 50%;
  background: conic-gradient(#53d8ff calc(var(--p) * 1%), rgba(83, 216, 255, 0.13) 0);
  -webkit-mask-image: radial-gradient(closest-side, transparent 64%, #000 65%);
  mask-image: radial-gradient(closest-side, transparent 64%, #000 65%);
  filter: drop-shadow(0 0 6px rgba(83, 216, 255, 0.6));
  transition: background 0.6s ease;
}
.jp-gauge strong {
  font-family: Consolas, monospace;
  font-size: 20px;
  color: #d8f7ff;
}
.jp-gauge strong span {
  font-size: 11px;
  color: rgba(160, 225, 250, 0.7);
}
.jp-kv {
  list-style: none;
  margin: 0;
  padding: 0;
  flex: 1;
}
.jp-kv li {
  display: flex;
  justify-content: space-between;
  font-size: 11px;
  margin-bottom: 6px;
}
.jp-kv span {
  color: rgba(150, 215, 240, 0.65);
}
.jp-kv b {
  font-family: Consolas, monospace;
  color: #c9f4ff;
  font-weight: 600;
}
.jp-spark {
  display: block;
  width: 100%;
  height: 48px;
  margin-top: 10px;
}
.jp-spark polyline {
  fill: none;
  stroke: #53d8ff;
  stroke-width: 1.6;
  filter: drop-shadow(0 0 4px rgba(83, 216, 255, 0.8));
}

/* 扫描面板 */
.jp-table-head,
.jp-tr {
  display: grid;
  grid-template-columns: 52px 44px 1fr 44px;
  gap: 6px;
  font-size: 11px;
  padding: 4px 0;
}
.jp-table-head {
  color: rgba(120, 200, 230, 0.5);
  border-bottom: 1px solid rgba(83, 216, 255, 0.2);
  letter-spacing: 1px;
}
.jp-tr {
  font-family: Consolas, monospace;
  color: rgba(190, 235, 255, 0.85);
  border-bottom: 1px dashed rgba(83, 216, 255, 0.1);
}
.jp-td-id.th-high {
  color: #ff9c8a;
}
.jp-td-id.th-mid {
  color: #ffd28a;
}
.jp-td-id.th-low {
  color: #9dffc8;
}
.jp-td-status {
  text-align: right;
  color: #9fe9ff;
}
.jp-tr .jp-td-id.th-high + span + span + .jp-td-status {
  color: #ff9c8a;
}

/* 诊断面板 */
.jp-diag {
  display: flex;
  justify-content: space-between;
  align-items: center;
  font-size: 11px;
  padding: 6px 0;
  border-bottom: 1px dashed rgba(83, 216, 255, 0.1);
  animation: jr-diag-in 0.4s ease both;
}
.jp-diag-label {
  color: rgba(170, 225, 248, 0.8);
}
.jp-diag b {
  font-family: Consolas, monospace;
  color: #7dffbe;
  font-weight: 600;
}
.jp-diag b.warn {
  color: #ffd28a;
  text-shadow: 0 0 8px rgba(255, 196, 110, 0.5);
}

/* 卫星面板 */
.jp-sat {
  display: flex;
  align-items: center;
  gap: 10px;
  font-size: 11px;
  padding: 7px 0;
  border-bottom: 1px dashed rgba(83, 216, 255, 0.1);
}
.jp-sat-name {
  width: 74px;
  font-family: Consolas, monospace;
  color: #c9f4ff;
}
.jp-sat-orb {
  width: 34px;
  font-size: 10px;
  color: rgba(120, 200, 230, 0.55);
}
.jp-sig {
  flex: 1;
  display: flex;
  align-items: flex-end;
  gap: 3px;
  height: 16px;
}
.jp-sig i {
  width: 5px;
  height: 4px;
  background: rgba(83, 216, 255, 0.18);
}
.jp-sig i.on {
  background: #6fe4ff;
  box-shadow: 0 0 6px rgba(83, 216, 255, 0.8);
  animation: jr-sig 1.2s ease-in-out infinite;
}
.jp-sat-val {
  font-family: Consolas, monospace;
  color: #c9f4ff;
}

/* ---------- 底部 ---------- */
.jr-footer {
  position: absolute;
  left: 0;
  right: 0;
  bottom: 0;
  height: 108px;
  padding: 0 28px;
  display: flex;
  align-items: center;
  gap: 28px;
  z-index: 20;
  border-top: 1px solid rgba(83, 216, 255, 0.18);
  background: linear-gradient(0deg, rgba(4, 20, 32, 0.85), rgba(4, 20, 32, 0));
}
.jr-speech {
  flex: 1;
  min-width: 0;
}
.jr-speech-tag {
  display: inline-block;
  font-size: 9px;
  letter-spacing: 2px;
  color: #53d8ff;
  border: 1px solid rgba(83, 216, 255, 0.5);
  padding: 1px 6px;
  margin-bottom: 6px;
}
.jr-speech-line {
  margin: 0;
  font-size: 14px;
  color: #d8f7ff;
  text-shadow: 0 0 10px rgba(83, 216, 255, 0.35);
  min-height: 20px;
}
.jr-caret {
  display: inline-block;
  width: 8px;
  height: 14px;
  margin-left: 3px;
  vertical-align: -2px;
  background: #53d8ff;
  animation: jr-blink 0.9s steps(1) infinite;
}
.jr-speech-hint {
  margin: 6px 0 0;
  font-size: 10px;
  color: rgba(120, 200, 230, 0.38);
  letter-spacing: 1px;
}
.jr-wave {
  display: flex;
  align-items: center;
  gap: 3px;
  height: 34px;
  flex: none;
}
.jr-wave i {
  width: 3px;
  background: linear-gradient(180deg, #9beaff, rgba(83, 216, 255, 0.3));
  box-shadow: 0 0 6px rgba(83, 216, 255, 0.5);
  animation: jr-wave 1.2s ease-in-out infinite;
}
.jr-dock {
  display: flex;
  gap: 10px;
  flex: none;
}
.jr-dock-btn {
  min-width: 58px;
  padding: 9px 10px;
  border: 1px solid rgba(83, 216, 255, 0.4);
  background: rgba(8, 30, 46, 0.6);
  color: #9fe9ff;
  font-size: 11px;
  letter-spacing: 2px;
  font-family: Consolas, monospace;
  clip-path: polygon(0 8px, 8px 0, 100% 0, 100% calc(100% - 8px), calc(100% - 8px) 100%, 0 100%);
  transition: all 0.2s ease;
}
.jr-dock-btn:hover {
  border-color: #9beaff;
  box-shadow: 0 0 14px rgba(83, 216, 255, 0.4);
}
.jr-dock-btn.open {
  background: rgba(83, 216, 255, 0.22);
  color: #eaffff;
  box-shadow: 0 0 18px rgba(83, 216, 255, 0.5), inset 0 0 12px rgba(83, 216, 255, 0.3);
}

/* 返回主菜单按钮 */
.jr-exit-btn {
  flex: none;
  margin-left: 18px;
  padding: 7px 12px;
  border: 1px solid rgba(83, 216, 255, 0.45);
  background: rgba(8, 30, 46, 0.6);
  color: #9fe9ff;
  font-size: 11px;
  letter-spacing: 2px;
  clip-path: polygon(0 7px, 7px 0, 100% 0, 100% calc(100% - 7px), calc(100% - 7px) 100%, 0 100%);
  transition: all 0.2s ease;
}
.jr-exit-btn:hover {
  border-color: #9beaff;
  color: #eaffff;
  background: rgba(83, 216, 255, 0.18);
  box-shadow: 0 0 16px rgba(83, 216, 255, 0.45);
}

/* ---------- 屏幕四角 ---------- */
.jr-frame i {
  position: absolute;
  width: 26px;
  height: 26px;
  border: 1px solid rgba(83, 216, 255, 0.55);
  z-index: 30;
  pointer-events: none;
}
.jr-frame i:nth-child(1) {
  top: 80px;
  left: 16px;
  border-right: none;
  border-bottom: none;
}
.jr-frame i:nth-child(2) {
  top: 80px;
  right: 16px;
  border-left: none;
  border-bottom: none;
}
.jr-frame i:nth-child(3) {
  bottom: 118px;
  left: 16px;
  border-right: none;
  border-top: none;
}
.jr-frame i:nth-child(4) {
  bottom: 118px;
  right: 16px;
  border-left: none;
  border-top: none;
}

/* ---------- 瞄准框 ---------- */
.jr-reticle {
  position: absolute;
  top: 0;
  left: 0;
  width: 120px;
  height: 120px;
  z-index: 70;
  pointer-events: none;
  opacity: 0;
  transition: opacity 0.3s ease;
  will-change: transform;
}
.jret {
  position: absolute;
}
.jret.tl,
.jret.tr,
.jret.bl,
.jret.br {
  width: 16px;
  height: 16px;
  border: 1.5px solid rgba(140, 240, 255, 0.9);
  filter: drop-shadow(0 0 4px rgba(83, 216, 255, 0.8));
  transition: width 0.2s ease, height 0.2s ease, border-color 0.2s ease;
}
.jret.tl {
  top: 18px;
  left: 18px;
  border-right: none;
  border-bottom: none;
}
.jret.tr {
  top: 18px;
  right: 18px;
  border-left: none;
  border-bottom: none;
}
.jret.bl {
  bottom: 18px;
  left: 18px;
  border-right: none;
  border-top: none;
}
.jret.br {
  bottom: 18px;
  right: 18px;
  border-left: none;
  border-top: none;
}
.jret.ch,
.jret.cv {
  background: rgba(140, 240, 255, 0.65);
}
.jret.ch {
  top: 50%;
  left: 46px;
  width: 28px;
  height: 1px;
}
.jret.cv {
  left: 50%;
  top: 46px;
  width: 1px;
  height: 28px;
}
.jret.ring {
  inset: 34px;
  border: 1px dashed rgba(83, 216, 255, 0.5);
  border-radius: 50%;
  animation: jr-spin-flat 7s linear infinite;
}
.jret.label {
  bottom: -6px;
  left: 50%;
  transform: translateX(-50%);
  font-family: Consolas, monospace;
  font-size: 9px;
  letter-spacing: 1px;
  color: rgba(160, 235, 255, 0.85);
  white-space: nowrap;
}
.jr-reticle.is-active .jret.tl,
.jr-reticle.is-active .jret.tr,
.jr-reticle.is-active .jret.bl,
.jr-reticle.is-active .jret.br {
  border-color: #ffd28a;
  filter: drop-shadow(0 0 6px rgba(255, 196, 110, 0.9));
}
.jr-reticle.is-fire {
  animation: jr-fire 0.32s ease;
}

/* ---------- 氛围覆盖层 ---------- */
.jr-vignette {
  position: absolute;
  inset: 0;
  z-index: 58;
  pointer-events: none;
  background: radial-gradient(ellipse at center, transparent 52%, rgba(0, 4, 10, 0.6) 100%);
}
.jr-veil {
  position: absolute;
  inset: 0;
  z-index: 59;
  pointer-events: none;
  background: rgba(140, 230, 255, 0.02);
  animation: jr-veil 7s ease-in-out infinite;
}
.jr-scanlines {
  position: absolute;
  inset: 0;
  z-index: 60;
  pointer-events: none;
  background: repeating-linear-gradient(0deg, rgba(255, 255, 255, 0.026) 0 1px, transparent 1px 3px);
}

/* ---------- 启动序列 ---------- */
.jr-boot {
  position: absolute;
  inset: 0;
  z-index: 80;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 22px;
  background: radial-gradient(900px 600px at 50% 50%, #04141f 0%, #010609 70%);
}
.jr-boot-mark {
  font-size: 64px;
  color: #53d8ff;
  text-shadow: 0 0 30px rgba(83, 216, 255, 1);
  animation: jr-mark-pulse 1.4s ease-in-out infinite;
}
.jr-boot-lines {
  min-height: 110px;
  font-family: Consolas, monospace;
  font-size: 13px;
  color: rgba(160, 235, 255, 0.85);
  text-align: left;
}
.jr-boot-lines p {
  margin: 4px 0;
  animation: jr-boot-line 0.3s ease both;
}
.jr-boot-bar {
  width: 240px;
  height: 3px;
  background: rgba(83, 216, 255, 0.15);
  overflow: hidden;
}
.jr-boot-bar i {
  display: block;
  height: 100%;
  background: linear-gradient(90deg, rgba(83, 216, 255, 0.6), #bdf2ff);
  box-shadow: 0 0 10px rgba(83, 216, 255, 0.9);
  animation: jr-boot-bar 2.2s linear both;
}
.jr-boot-skip {
  font-size: 10px;
  color: rgba(120, 200, 230, 0.4);
  letter-spacing: 2px;
}
.bootfade-leave-active {
  transition: opacity 0.7s ease;
}
.bootfade-leave-to {
  opacity: 0;
}

/* ---------- 全息面板过渡 ---------- */
.holo-enter-active {
  transition: all 0.38s cubic-bezier(0.2, 0.9, 0.3, 1.25);
}
.holo-enter-from {
  opacity: 0;
  transform: translateY(30px) scale(0.7) rotateX(14deg);
}
.holo-leave-active {
  transition: all 0.2s ease;
}
.holo-leave-to {
  opacity: 0;
  transform: translateY(16px) scale(0.86);
}

/* ---------- 关键帧 ---------- */
@keyframes jr-spin-flat {
  from {
    transform: rotateX(66deg) rotateZ(0deg);
  }
  to {
    transform: rotateX(66deg) rotateZ(360deg);
  }
}
@keyframes jr-spin-flat-rev {
  from {
    transform: rotateX(66deg) rotateZ(360deg);
  }
  to {
    transform: rotateX(66deg) rotateZ(0deg);
  }
}
@keyframes jr-spin-meridian {
  from {
    transform: rotateY(0deg) rotateX(12deg);
  }
  to {
    transform: rotateY(360deg) rotateX(12deg);
  }
}
@keyframes jr-core-pulse {
  0%,
  100% {
    box-shadow: 0 0 42px rgba(120, 225, 255, 0.55), 0 0 130px rgba(83, 216, 255, 0.28), inset 0 0 26px rgba(255, 255, 255, 0.25);
  }
  50% {
    box-shadow: 0 0 66px rgba(140, 235, 255, 0.8), 0 0 170px rgba(83, 216, 255, 0.42), inset 0 0 32px rgba(255, 255, 255, 0.4);
  }
}
@keyframes jr-mark-pulse {
  0%,
  100% {
    opacity: 0.85;
    text-shadow: 0 0 14px rgba(83, 216, 255, 0.9);
  }
  50% {
    opacity: 1;
    text-shadow: 0 0 26px rgba(140, 235, 255, 1);
  }
}
@keyframes jr-blink {
  0%,
  100% {
    opacity: 1;
  }
  50% {
    opacity: 0.25;
  }
}
@keyframes jr-flicker {
  0%,
  7% {
    opacity: 0.15;
  }
  10%,
  19% {
    opacity: 0.9;
  }
  22% {
    opacity: 0.35;
  }
  27% {
    opacity: 1;
  }
  32% {
    opacity: 0.55;
  }
  38%,
  100% {
    opacity: 1;
  }
}
@keyframes jr-diag-in {
  from {
    opacity: 0;
    transform: translateX(-10px);
  }
  to {
    opacity: 1;
    transform: translateX(0);
  }
}
@keyframes jr-sig {
  0%,
  100% {
    opacity: 1;
  }
  50% {
    opacity: 0.45;
  }
}
@keyframes jr-wave {
  0%,
  100% {
    transform: scaleY(0.35);
  }
  50% {
    transform: scaleY(1);
  }
}
@keyframes jr-veil {
  0%,
  100% {
    opacity: 0.4;
  }
  50% {
    opacity: 1;
  }
}
@keyframes jr-boot-line {
  from {
    opacity: 0;
    transform: translateY(4px);
  }
  to {
    opacity: 1;
    transform: translateY(0);
  }
}
@keyframes jr-boot-bar {
  from {
    width: 0;
  }
  to {
    width: 100%;
  }
}
@keyframes jr-fire {
  0% {
    filter: brightness(2.2);
  }
  60% {
    filter: brightness(0.85);
  }
  100% {
    filter: brightness(1);
  }
}

/* ---------- 响应式 ---------- */
@media (max-width: 1280px) {
  .jr-side {
    width: 200px;
  }
  .jr-side-left {
    left: 20px;
  }
  .jr-side-right {
    right: 20px;
  }
  .jr-radar {
    left: 20px;
  }
}
@media (max-width: 1080px) {
  .jr-side {
    display: none;
  }
  .jr-gyro {
    transform: scale(0.82);
  }
}
@media (max-height: 780px) {
  .jr-gyro {
    transform: scale(0.78);
  }
}
@media (prefers-reduced-motion: reduce) {
  .jg-ring,
  .jg-ticks,
  .jr-core,
  .jr-core-ring,
  .jr-wave i,
  .jr-brand-mark,
  .jret.ring {
    animation: none !important;
  }
}
</style>
