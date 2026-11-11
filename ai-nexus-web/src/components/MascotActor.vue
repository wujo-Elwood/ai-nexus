<template>
  <div
    v-show="mascot.state.mode !== 'hidden'"
    ref="rootEl"
    class="mascot-actor"
    :class="[mascot.state.mode, 'pose-' + mascot.state.pose, 'act-' + mascot.state.action, { petted: petted, petting: petting, shy: mascot.state.shy, dragging: mascot.state.dragging, 'star-eyes': mascot.state.starEyes, reading: mascot.state.reading }]"
    :style="{ left: mascot.state.x + 'px', top: mascot.state.y + 'px', '--walk-dur': mascot.state.walkDur + 'ms' }"
    title="点我换个动作 · 双击投喂 · 拖拽定居"
    @click.stop="onActorClick"
    @dblclick.stop="onFeed"
    @mousedown.stop="onDragStart"
    @mouseenter="onPetStart"
    @mouseleave="onPetEnd"
  >
    <!-- 脚下影子：随跳动同步缩放（最先渲染 = 垫底） -->
    <span class="mascot-shadow" aria-hidden="true"></span>
    <!-- 走路脚印：fixed 定位留在原地渐隐，不随身体移动 -->
    <span
      v-for="fp in footprints"
      :key="'fp' + fp.id"
      class="footprint"
      :style="{ left: fp.x + 'px', top: fp.y + 'px', transform: 'rotate(' + fp.rot + 'deg)' }"
    ></span>
    <!-- 登场烟雾：瞬移落点炸开的小烟团 -->
    <span
      v-for="p in poofs"
      :key="'pf' + p.id"
      class="fx poof"
      :style="{ left: p.x + 'px', top: p.y + 'px', animationDelay: p.delay + 's' }"
    ></span>
    <!-- 节日飘雪：戴围巾的季节全屏细雪（fixed，不随演员移动） -->
    <span
      v-for="f in snowflakes"
      :key="'sw' + f.id"
      class="snowflake"
      :style="{ left: f.left + 'vw', width: f.size + 'px', height: f.size + 'px', '--snow-dur': f.dur + 'ms', '--snow-drift': f.drift + 'px', animationDelay: f.delay + 'ms' }"
    ></span>
    <!-- 头顶小铭牌：起名后的展台标签（说话气泡出现时让位） -->
    <span v-if="!mascot.state.speak" class="mascot-nametag">{{ mascot.name.value }}</span>
    <!-- 说话气泡 -->
    <span v-if="mascot.state.speak" class="mascot-bubble">{{ mascot.state.speak }}</span>
    <!-- 摸头：♡ -->
    <span v-if="petting" class="fx fx-pet">♡</span>
    <!-- 投喂：掉落的饼干 -->
    <span v-if="treatShown" class="mascot-treat">{{ treatText }}</span>
    <!-- 吃：碎屑 -->
    <span v-if="mascot.state.action === 'eat'" class="fx fx-crumbs">●</span>
    <span v-if="mascot.state.action === 'eat'" class="fx fx-crumbs fx-c2">●</span>
    <!-- 庆祝彩纸 -->
    <template v-if="mascot.state.confetti">
      <span
        v-for="c in 12"
        :key="'cf' + c"
        class="confetti"
        :style="{ left: (c * 7.5 - 3) + '%', background: confettiColors[c % 4], animationDelay: (c * 0.055) + 's' }"
      ></span>
    </template>
    <span v-if="mascot.state.mode === 'cheering'" class="mascot-star">★</span>
    <!-- 睡觉：z 字逐只上飘（错峰循环） -->
    <template v-if="mascot.state.mode === 'sleeping'">
      <span class="fx zzz z1">z</span>
      <span class="fx zzz z2">z</span>
      <span class="fx zzz z3">z</span>
    </template>
    <!-- 庆祝星星眼：方块眼隐去，原位亮起两颗星 -->
    <span v-if="mascot.state.starEyes" class="fx fx-stareye s1">✦</span>
    <span v-if="mascot.state.starEyes" class="fx fx-stareye s2">✦</span>

    <!-- 被拎起来：汗珠乱飞 + 头顶惊叹号（配合挣扎动作） -->
    <template v-if="mascot.state.dragging">
      <span class="fx fx-drag-sweat">💦</span>
      <span class="fx fx-drag-sweat fx-ds2">💦</span>
      <span class="fx fx-drag-bang">!</span>
    </template>
    <!-- 放下后眩晕：两颗 @ 绕着脑袋转 -->
    <template v-if="mascot.state.action === 'dizzy'">
      <span class="fx fx-dizzy">@</span>
      <span class="fx fx-dizzy fx-dz2">@</span>
    </template>

    <!-- 走路：脚步尘土（两只脚交替） -->
    <span v-if="mascot.state.mode === 'walking'" class="fx fx-step">●</span>
    <span v-if="mascot.state.mode === 'walking'" class="fx fx-step fx-s2">●</span>
    <!-- 互动跳舞：爱心 -->
    <span v-if="petted" class="fx fx-heart">♡</span>
    <span v-if="petted" class="fx fx-heart fx-h2">♡</span>
    <!-- 欢呼动作（轮盘版）：星星 -->
    <span v-if="mascot.state.action === 'cheer'" class="fx fx-cheer-star">★</span>
    <!-- 待机小跳：每周期落地一次微尘 -->
    <span
      v-if="mascot.state.mode === 'idle' && mascot.state.action === 'none'"
      class="fx fx-idle-dust"
    >●</span>

    <!-- 动作专属动效 -->
    <span v-if="mascot.state.action === 'jump'" class="fx fx-dust">●</span>
    <span v-if="mascot.state.action === 'jump'" class="fx fx-dust fx-d2">●</span>
    <span v-if="mascot.state.action === 'roll'" class="fx fx-trail">〰</span>
    <span v-if="mascot.state.action === 'spin'" class="fx fx-spark">✧</span>
    <span v-if="mascot.state.action === 'inspect'" class="fx fx-q">?</span>
    <span v-if="mascot.state.action === 'nod'" class="fx fx-note">♪</span>
    <span v-if="mascot.state.action === 'shake'" class="fx fx-note">♪</span>
    <span v-if="mascot.state.action === 'shake'" class="fx fx-note fx-n2">♪</span>
    <span v-if="mascot.state.action === 'backflip'" class="fx fx-burst">✦</span>
    <span v-if="mascot.state.action === 'backflip'" class="fx fx-burst fx-b2">✦</span>
    <span v-if="mascot.state.pose === 'push'" class="fx fx-dust">●</span>
    <span v-if="mascot.state.pose === 'carry'" class="fx fx-sweat">💧</span>
    <!-- 新增动作特效 -->
    <span v-if="mascot.state.action === 'wave'" class="fx fx-hi">☆</span>
    <span v-if="mascot.state.action === 'excited'" class="fx fx-spark2">✦</span>
    <span v-if="mascot.state.action === 'excited'" class="fx fx-spark2 fx-k2">✧</span>
    <span v-if="mascot.state.action === 'hearteyes'" class="fx fx-love">❤❤</span>
    <span v-if="mascot.state.action === 'lookaround'" class="fx fx-dots">…</span>
    <span v-if="mascot.state.action === 'headstand'" class="fx fx-bang">!</span>
    <span v-if="mascot.state.action === 'tiptoe'" class="fx fx-stretch">✧</span>
    <!-- 第二批动作特效 -->
    <span v-if="mascot.state.action === 'pushups'" class="fx fx-sweat2">💧</span>
    <span v-if="mascot.state.action === 'moonwalk'" class="fx fx-glide">✧</span>
    <span v-if="mascot.state.action === 'moonwalk'" class="fx fx-rainbow"></span>
    <span v-if="mascot.state.action === 'slip'" class="fx fx-dizzy">@</span>
    <span v-if="mascot.state.action === 'slip'" class="fx fx-dizzy fx-dz2">@</span>
    <span v-if="mascot.state.action === 'sign'" class="mascot-sign">加油!</span>
    <span v-if="mascot.state.action === 'idea'" class="fx fx-idea">💡</span>
    <span v-if="mascot.state.action === 'magic'" class="fx fx-magic">✨</span>
    <span v-if="mascot.state.action === 'peek'" class="fx fx-peek">…?</span>
    <span v-if="mascot.state.action === 'meditate'" class="fx fx-halo"></span>
    <span v-if="mascot.state.action === 'sweep'" class="fx fx-broom">🧹</span>
    <span v-if="mascot.state.action === 'sulk'" class="fx fx-sulk">〰</span>
    <span v-if="mascot.state.action === 'sip'" class="fx fx-cup">☕</span>
    <span v-if="mascot.state.action === 'goodbye'" class="fx fx-tear">💧</span>
    <span v-if="mascot.state.action === 'goodbye'" class="fx fx-bye">👋</span>
    <!-- 第三批动作特效：打字 / 打喷嚏 -->
    <span v-if="mascot.state.action === 'type'" class="fx fx-key">⌨</span>
    <span v-if="mascot.state.action === 'sneeze'" class="fx fx-achoo">阿嚏!</span>
    <span v-if="mascot.state.action === 'sneeze'" class="fx fx-droplet">✦</span>
    <span v-if="mascot.state.action === 'sneeze'" class="fx fx-droplet fx-dr2">✦</span>
    <!-- 第四批动作特效：鼓掌 / 抛金币 -->
    <span v-if="mascot.state.action === 'clap'" class="fx fx-clap">啪啪!</span>
    <span v-if="mascot.state.action === 'clap'" class="fx fx-wave w1"></span>
    <span v-if="mascot.state.action === 'clap'" class="fx fx-wave w2"></span>
    <span v-if="mascot.state.action === 'juggle'" class="fx fx-coin">●</span>
    <span v-if="mascot.state.action === 'juggle'" class="fx fx-coin fx-cn2">●</span>
    <span v-if="mascot.state.action === 'juggle'" class="fx fx-coin fx-cn3">●</span>
    <!-- 第五批动作特效：打电话 / 比心 -->
    <span v-if="mascot.state.action === 'call'" class="fx fx-phone"></span>
    <span v-if="mascot.state.action === 'call'" class="fx fx-ring">嘟…嘟…</span>
    <span v-if="mascot.state.action === 'fingerheart'" class="fx fx-bigheart">♡</span>
    <!-- 第六批动作特效：耸肩 / 捂眼 / 举画框 / 伸懒腰 / 敬礼 -->
    <span v-if="mascot.state.action === 'shrug'" class="fx fx-shrug">╮(╯_╰)╭</span>
    <span v-if="mascot.state.action === 'cover'" class="fx fx-cover">不敢看!</span>
    <span v-if="mascot.state.action === 'draw'" class="fx fx-frame">🖼</span>
    <span v-if="mascot.state.action === 'draw'" class="fx fx-frame-star s1">✦</span>
    <span v-if="mascot.state.action === 'draw'" class="fx fx-frame-star s2">✧</span>
    <span v-if="mascot.state.action === 'stretch'" class="fx fx-yawn">呼——</span>
    <span v-if="mascot.state.action === 'salute'" class="fx fx-salute">★</span>
    <!-- 情境装饰：加班咖啡+黑眼圈 / 周五拎包 / 成就徽章 -->
    <span v-if="mascot.state.overtime" class="fx fx-late">☕</span>
    <span v-if="mascot.state.overtime" class="fx fx-tired t1"></span>
    <span v-if="mascot.state.overtime" class="fx fx-tired t2"></span>
    <span v-if="mascot.state.friday" class="fx fx-case">🧳</span>
    <span v-if="mascot.state.badge" class="mascot-badge">🎖 {{ mascot.state.badge }}</span>
    <svg class="mascot-body" viewBox="0 9 130 68" shape-rendering="crispEdges" aria-hidden="true">
      <rect x="31" y="9" width="68" height="47" :fill="skinColor" />
      <rect class="pt-arm pt-arm-l" x="17" y="27" width="14" height="13" :fill="skinColor" />
      <rect class="pt-arm pt-arm-r" x="100" y="27" width="14" height="13" :fill="skinColor" />
      <rect class="pt-eye" :x="42 + eyeDx" :y="20 + eyeDy" width="7" height="8" fill="#101014" />
      <rect class="pt-eye" :x="78 + eyeDx" :y="20 + eyeDy" width="7" height="8" fill="#101014" />
      <rect x="37" y="56" width="7" height="11" :fill="skinColor" class="pt-leg pt-leg-1" />
      <rect x="49" y="56" width="8" height="11" :fill="skinColor" class="pt-leg pt-leg-2" />
      <rect x="74" y="56" width="7" height="11" :fill="skinColor" class="pt-leg pt-leg-3" />
      <rect x="86" y="56" width="7" height="11" :fill="skinColor" class="pt-leg pt-leg-4" />
      <rect v-if="mascot.state.festival === 'scarf'" x="31" y="47" width="68" height="8" fill="#e05252" />
      <rect v-if="mascot.state.festival === 'scarf'" x="38" y="55" width="13" height="9" fill="#c14444" />
      <rect v-if="mascot.state.festival === 'nose'" x="59" y="27" width="8" height="7" fill="#e05252" />
      <!-- 换装/状态配件：画在 SVG 内，跟随身体的一切动作（跳动/摇摆/翻转/倒立） -->
      <g v-if="mascot.state.busy" class="outfit-helmet">
        <rect x="45" y="9" width="40" height="5" fill="#f4a83c" />
        <rect x="43" y="12" width="44" height="3" fill="#d98a20" />
      </g>
      <g v-if="mascot.state.outfit === 'hat' && !mascot.state.busy" class="outfit-hat">
        <rect x="61" y="0" width="8" height="4" fill="#2b5cd9" />
        <rect x="37" y="2" width="56" height="12" rx="4" fill="#4f8ef7" />
        <rect x="37" y="11" width="56" height="4" fill="#2b5cd9" />
        <rect x="90" y="10" width="24" height="5" rx="2" fill="#2b5cd9" />
      </g>
      <g v-if="mascot.state.outfit === 'glasses' && !mascot.state.shy && mascot.state.action !== 'hearteyes' && !mascot.state.starEyes" class="outfit-glasses">
        <rect x="31" y="21" width="8" height="2" fill="#1f1f24" />
        <rect x="91" y="21" width="8" height="2" fill="#1f1f24" />
        <rect x="38" y="16" width="15" height="16" fill="rgba(160,210,255,0.4)" stroke="#1f1f24" stroke-width="3" />
        <rect x="77" y="16" width="15" height="16" fill="rgba(160,210,255,0.4)" stroke="#1f1f24" stroke-width="3" />
        <rect x="53" y="22" width="24" height="3" fill="#1f1f24" />
        <rect x="41" y="19" width="5" height="2" fill="rgba(255,255,255,0.55)" />
        <rect x="80" y="19" width="5" height="2" fill="rgba(255,255,255,0.55)" />
      </g>
      <g v-if="mascot.state.outfit === 'shades' && !mascot.state.shy && mascot.state.action !== 'hearteyes' && !mascot.state.starEyes" class="outfit-shades">
        <rect x="31" y="21" width="8" height="2" fill="#101014" />
        <rect x="91" y="21" width="8" height="2" fill="#101014" />
        <rect x="37" y="17" width="56" height="12" fill="#101014" />
        <rect x="40" y="19" width="50" height="2" fill="rgba(255,255,255,0.22)" />
      </g>
      <g v-if="mascot.state.outfit === 'crown' && !mascot.state.busy" class="outfit-crown">
        <polygon points="42,14 42,8 47,1 57,8 65,1 73,8 83,1 88,8 88,14" fill="#f6c94a" />
        <rect x="42" y="12" width="46" height="2" fill="#d9a52e" />
        <rect x="50" y="9" width="4" height="3" fill="#e0527a" />
        <rect x="63" y="9" width="4" height="3" fill="#4f8ef7" />
        <rect x="76" y="9" width="4" height="3" fill="#4caf7d" />
      </g>
    </svg>
  </div>
</template>

<script setup>
import { ref, computed, watch, onMounted, onBeforeUnmount } from 'vue'
import { mascot } from '../composables/useMascot'

const rootEl = ref(null)
const petted = ref(false)
let pettedTimer = null
const confettiColors = ['#7dd3fc', '#818cf8', '#e879f9', '#fbbf24']

// 花色颜色映射在 useMascot 全局共享（演员与右上角头像同色），这里直接引用
const skinColor = mascot.skinColor

// —— 眼睛跟随鼠标：瞳孔朝光标方向微移（±3 SVG 单位内） ——
const eyeDx = ref(0)
const eyeDy = ref(0)

function onEyeMove(e) {
  if (petting.value || !rootEl.value) return
  const r = rootEl.value.getBoundingClientRect()
  const cx = r.x + r.width / 2
  const cy = r.y + r.height / 2
  let dx = (e.clientX - cx) / 60
  let dy = (e.clientY - cy) / 60
  dx = Math.max(-3, Math.min(3, dx))
  dy = Math.max(-2.5, Math.min(2.5, dy))
  eyeDx.value = Math.round(dx * 10) / 10
  eyeDy.value = Math.round(dy * 10) / 10
}

// —— 走路脚印：walking 期间每约 190ms 在脚下留一枚渐隐脚印（fixed 定位，留在原地） ——
const footprints = ref([])
let fpId = 0
let lastFpAt = 0
watch(() => [mascot.state.x, mascot.state.y], () => {
  if (mascot.state.mode !== 'walking') return
  const now = Date.now()
  if (now - lastFpAt < 190) return
  lastFpAt = now
  const id = ++fpId
  footprints.value.push({
    id,
    x: mascot.state.x + (fpId % 2 ? 14 : 26),   // 左右脚交替
    y: mascot.state.y + 23,
    rot: fpId % 2 ? -12 : 12
  })
  setTimeout(() => { footprints.value = footprints.value.filter(f => f.id !== id) }, 1400)
})

// —— 登场烟雾：非走路的大幅瞬移（跨区/首次登场）在落点炸开小烟团 ——
const poofs = ref([])
let poofId = 0
function spawnPoof(cx, cy) {
  ;[[0, 0, 0], [-18, 6, 0.2], [18, 5, 0.4]].forEach(([dx, dy, delay]) => {
    const id = ++poofId
    poofs.value.push({ id, x: cx + 18 + dx, y: cy + 20 + dy, delay })
    setTimeout(() => { poofs.value = poofs.value.filter(p => p.id !== id) }, 2500)
  })
}
watch(() => [mascot.state.x, mascot.state.y], ([nx, ny], [ox, oy]) => {
  if (mascot.state.walkDur > 0 || mascot.state.mode === 'hidden') return
  if (Math.hypot(nx - ox, ny - oy) < 90) return
  spawnPoof(nx, ny)
})

// 登场烟雾的主场景：从 hidden 现身的那一刻（首次登场/开关打开）在落点炸烟——
// hidden 期间演员节点 display:none，普通瞬移烟雾看不见，必须挂在这个切换上
watch(() => mascot.state.mode, (m, old) => {
  if (old === 'hidden' && m !== 'hidden') spawnPoof(mascot.state.x, mascot.state.y)
})

// —— 节日飘雪：戴围巾的季节全屏细雪（开发预览：localStorage.setItem('mascot-festival','scarf') 后刷新） ——
const snowflakes = ref([])
let snowId = 0
let snowTimer = null
function spawnSnow() {
  if (mascot.state.festival !== 'scarf' || !mascot.enabled.value || document.hidden) return
  if (snowflakes.value.length > 14) return   // 并发上限，防堆积
  const n = 1 + Math.floor(Math.random() * 2)
  for (let i = 0; i < n; i++) {
    const id = ++snowId
    const dur = 9000 + Math.random() * 6000
    snowflakes.value.push({
      id,
      left: Math.random() * 100,
      size: 3 + Math.random() * 3,
      dur,
      drift: Math.round(Math.random() * 80 - 40),
      delay: Math.round(Math.random() * 900)
    })
    setTimeout(() => { snowflakes.value = snowflakes.value.filter(f => f.id !== id) }, dur + 1200)
  }
}

// —— 拖拽定居 ——
let dragStart = null
let dragMoved = false

function onDragStart(e) {
  if (e.button !== 0) return
  dragStart = { x: e.clientX, y: e.clientY, ox: mascot.state.x, oy: mascot.state.y }
  dragMoved = false
  window.addEventListener('mousemove', onDragMove)
  window.addEventListener('mouseup', onDragEnd, { once: true })
}

function onDragMove(e) {
  if (!dragStart) return
  const dx = e.clientX - dragStart.x
  const dy = e.clientY - dragStart.y
  if (!dragMoved && Math.hypot(dx, dy) > 12) {
    dragMoved = true
    mascot.beginDrag()
  }
  if (dragMoved) mascot.dragTo(dragStart.ox + dx, dragStart.oy + dy)
}

function onDragEnd() {
  window.removeEventListener('mousemove', onDragMove)
  if (dragStart && dragMoved) mascot.endDrag()
  dragStart = null
}

// —— 投喂：双击掉饼干，它接住吃掉 ——
const treatShown = ref(false)
const treatText = ref('🍪')
const FOODS = [
  { emoji: '🍪', line: '好吃！谢谢投喂', action: 'eat' },
  { emoji: '☕', line: '咖啡！元气满满，跑一圈！', action: 'excited' },
  { emoji: '🌶️', line: '辣辣辣！要喷火啦！！', action: 'shake' },
  { emoji: '🐟', line: '鱼！比心给你！', action: 'fingerheart' },
]
let foodIdx = 0
let treatTimer = null

function onFeed() {
  if (dragMoved) { dragMoved = false; return }
  if (clickTimer) { clearTimeout(clickTimer); clickTimer = null }  // 双击赢了单击：撤销待播动作
  clickTimes = []   // 投喂是友好互动：不计入"戳太多次"
  if (!mascot.enabled.value) return
  if (mascot.state.mode === 'sleeping') mascot.wake()
  mascot.noteFed()   // 喂饱了：重置肚子饿计时
  const food = FOODS[foodIdx++ % FOODS.length]   // 投喂轮换：饼干→咖啡→辣椒→鱼
  treatText.value = food.emoji
  treatShown.value = true
  if (treatTimer) clearTimeout(treatTimer)
  treatTimer = setTimeout(() => { treatShown.value = false }, 1500)
  setTimeout(() => { mascot.state.action = food.action }, 450)
  setTimeout(() => {
    if (mascot.state.action === food.action) mascot.state.action = 'none'
    mascot.speak(food.line)
  }, 1600)
}

// 点击仲裁：拖拽后的 click 不触发动作；单击从洗牌队列取动作（一轮演完重洗，不与上轮收尾重复）
// 戳太多次它会烦：10 秒内 6 次单击 → 闹脾气蹲下不理人 12 秒
const CLICK_ACTIONS = ['cheer', 'spin', 'jump', 'wave', 'excited', 'hearteyes', 'nod', 'shake', 'tiptoe', 'roll', 'backflip', 'sneeze', 'type', 'clap', 'juggle', 'fingerheart', 'lookaround', 'inspect', 'headstand', 'pushups', 'moonwalk', 'magic', 'meditate']
let clickQueue = []
let lastClickAction = ''
let clickTimer = null
let clickTimes = []
let annoyedUntil = 0

function nextClickAction() {
  if (!clickQueue.length) {
    clickQueue = CLICK_ACTIONS.slice()
    for (let i = clickQueue.length - 1; i > 0; i--) {
      const j = Math.floor(Math.random() * (i + 1))
      ;[clickQueue[i], clickQueue[j]] = [clickQueue[j], clickQueue[i]]
    }
    // 避免新一轮的第一个动作和上一轮收尾重复
    if (clickQueue.length > 1 && clickQueue[clickQueue.length - 1] === lastClickAction) {
      ;[clickQueue[0], clickQueue[clickQueue.length - 1]] = [clickQueue[clickQueue.length - 1], clickQueue[0]]
    }
  }
  lastClickAction = clickQueue.pop()
  return lastClickAction
}

function onActorClick() {
  if (dragMoved) { dragMoved = false; return }
  if (!mascot.enabled.value) return
  if (clickTimer) { clearTimeout(clickTimer); clickTimer = null }   // 新点击进来先撤销待播动作
  const now = Date.now()
  if (now < annoyedUntil) return   // 正在闹脾气，别戳
  clickTimes = clickTimes.filter(t => now - t < 10000)
  clickTimes.push(now)
  if (clickTimes.length >= 6) {
    clickTimes = []
    annoyedUntil = now + 12000
    if (mascot.state.mode === 'sleeping') mascot.wake()
    mascot.playAction('sulk')
    mascot.applyMood(-4)   // 被戳烦了心情变差
    mascot.speak('别戳啦，好痒！')
    return
  }
  clickTimer = setTimeout(() => {
    clickTimer = null
    if (mascot.state.mode === 'sleeping') mascot.wake()
    mascot.playAction(nextClickAction())
  }, 260)
}

// —— 摸头：悬停 0.9 秒后眯眼享受 + ♡ ——
const petting = ref(false)
let petStartTimer = null
let petEndTimer = null

function onPetStart() {
  if (petting.value) return
  if (petStartTimer) clearTimeout(petStartTimer)
  petStartTimer = setTimeout(() => {
    if (mascot.state.mode === 'idle' || mascot.state.mode === 'sleeping') {
      if (mascot.state.mode === 'sleeping') mascot.wake()
      petting.value = true
      mascot.purr()   // 摸得舒服：咕噜两声
      if (petEndTimer) clearTimeout(petEndTimer)
      petEndTimer = setTimeout(() => { petting.value = false }, 1500)
    }
  }, 900)
}

function onPetEnd() {
  if (petStartTimer) { clearTimeout(petStartTimer); petStartTimer = null }
}

// 撸吉祥物：petTick 变化 → 二连跳舞蹈
watch(() => mascot.petTick.value, () => {
  petted.value = false
  setTimeout(() => {          // rAF 在后台标签页会被冻结，改用宏任务
    petted.value = true
    if (pettedTimer) clearTimeout(pettedTimer)
    pettedTimer = setTimeout(() => {
      petted.value = false
    }, 1150)
  })
})

onMounted(() => {
  window.addEventListener('mousemove', onEyeMove, { passive: true })
  snowTimer = setInterval(spawnSnow, 2600)
  if (import.meta.env.DEV) {
    // 开发期预览登场烟雾：$mascotPoof() 在它当前位置炸一团，$mascotPoof(500, 300) 指定位置
    window.$mascotPoof = (x, y) => spawnPoof(x ?? mascot.state.x, y ?? mascot.state.y)
  }
})

onBeforeUnmount(() => {
  window.removeEventListener('mousemove', onEyeMove)
  if (petStartTimer) clearTimeout(petStartTimer)
  if (petEndTimer) clearTimeout(petEndTimer)
  if (snowTimer) clearInterval(snowTimer)
  footprints.value = []
  poofs.value = []
  snowflakes.value = []
})
</script>

<style scoped>
.mascot-actor.traveling {
  opacity: 0;
  transition: opacity 0.16s ease;
}

.mascot-actor {
  position: fixed;
  z-index: 50;
  width: 54px;
  height: 28px;
  cursor: pointer;
  user-select: none;
  transition:
    left var(--walk-dur, 0ms) linear,
    top var(--walk-dur, 0ms) linear,
    opacity 0.16s ease;
}

.mascot-body {
  width: 100%;
  height: 100%;
  display: block;
  transform-origin: 50% 100%;
  overflow: visible;   /* 鸭舌帽帽顶凸出头顶：放开 viewBox 裁剪 */
}

/* 待机：周期小跳 + 眨眼 */
.mascot-actor.idle .mascot-body {
  animation: mascot-jump 3.4s ease-in-out infinite;
}

.mascot-body .pt-eye {
  transform-box: fill-box;
  transform-origin: center;
  animation: mascot-blink 3.4s ease-in-out infinite;
}

/* 走动：小短腿摇摆 */
.mascot-actor.walking .mascot-body {
  animation: mascot-waddle var(--walk-dur, 600ms) ease-in-out infinite;
  transform-origin: 50% 100%;
}

/* 开心：蹦两下冒星星 */
.mascot-actor.cheering .mascot-body {
  animation: mascot-cheer 1.25s ease-in-out;
}

/* 沮丧：趴下变灰 */
.mascot-actor.sad .mascot-body {
  animation: mascot-sad 1.5s ease-in-out both;
  filter: grayscale(1);
  opacity: 0.75;
}

/* 睡觉：压扁 + 闭眼 */
/* 睡觉：压扁入场 + 缓慢呼吸起伏（0.3s 入睡动画与呼吸首帧无缝衔接） */
.mascot-actor.sleeping .mascot-body {
  animation: mascot-sleep 0.3s ease-out both, pt-breathe 3.2s ease-in-out 0.3s infinite;
}
@keyframes pt-breathe {
  0%, 100% { transform: translateY(6px) scale(1.04, 0.72); }
  50% { transform: translateY(5px) scale(1.09, 0.78); }
}

.mascot-actor.sleeping .mascot-body .pt-eye {
  animation: none;
  transform: scaleY(0.12);
}

/* 工位姿势：推（压向左侧发力） */
.pose-push .mascot-body {
  transform: rotate(-6deg) translateX(-2px);
}

/* 工位姿势：扛（手臂上举的错觉——身体微仰） */
.pose-carry .mascot-body {
  transform: rotate(4deg) translateY(-1px);
}

/* ===== 新增动作 ===== */
/* 挥手：右臂上下摆动 */
.mascot-actor.act-wave .pt-arm:nth-of-type(3) {
  transform-box: fill-box;
  transform-origin: left center;
  animation: pt-wave 0.36s ease-in-out 3;
}
@keyframes pt-wave {
  0%, 100% { transform: rotate(0deg); }
  50% { transform: rotate(-38deg); }
}

/* 兴奋：快速小碎步抖动 */
.mascot-actor.act-excited .mascot-body {
  animation: pt-excited 0.9s ease-in-out;
  transform-origin: 50% 100%;
}
@keyframes pt-excited {
  0%, 100% { transform: translateX(0); }
  12% { transform: translateX(-1.5px) rotate(-1deg); }
  25% { transform: translateX(1.5px) rotate(1deg); }
  37% { transform: translateX(-1.5px) rotate(-1deg); }
  50% { transform: translateX(1.5px) rotate(1deg); }
  62% { transform: translateX(-1.5px) rotate(-1deg); }
  75% { transform: translateX(1.5px) rotate(1deg); }
  87% { transform: translateX(-1px) rotate(0deg); }
}

/* 花痴：眼睛消失（由 ❤❤ 特效盖住） */
.mascot-actor.act-hearteyes .pt-eye {
  opacity: 0;
}

/* 张望：眼睛左右平移 */
.mascot-actor.act-lookaround .pt-eye {
  transform-box: fill-box;
  animation: pt-look 1.2s ease-in-out;
}
@keyframes pt-look {
  0%, 100% { transform: translateX(0); }
  30% { transform: translateX(-4px); }
  65% { transform: translateX(4px); }
}

/* 踮脚：身体缓缓抬起再落下 */
.mascot-actor.act-tiptoe .mascot-body {
  animation: pt-tiptoe 1.3s ease-in-out;
  transform-origin: 50% 100%;
}
@keyframes pt-tiptoe {
  0%, 100% { transform: translateY(0); }
  50% { transform: translateY(-6px); }
}

/* 倒立：翻上去定住再翻回来 */
.mascot-actor.act-headstand .mascot-body {
  animation: pt-headstand 1.4s ease-in-out;
  transform-origin: 50% 55%;
}
@keyframes pt-headstand {
  0% { transform: rotate(0deg); }
  32%, 70% { transform: rotate(-180deg) translateY(3px); }
  100% { transform: rotate(-360deg); }
}

/* 热点动作：跳（二连跳） */
.mascot-actor.act-jump .mascot-body {
  animation: mascot-dance 1.15s ease-in-out;
  transform-origin: 50% 100%;
}

/* 热点动作：欢呼 */
.mascot-actor.act-cheer .mascot-body {
  animation: mascot-cheer 1.3s ease-in-out;
  transform-origin: 50% 100%;
}

/* 热点动作：翻滚 */
.mascot-actor.act-roll .mascot-body {
  animation: mascot-roll 0.95s ease-in-out;
  transform-origin: 50% 100%;
}

/* 热点动作：原地旋转 */
.mascot-actor.act-spin .mascot-body {
  animation: mascot-spin 0.85s ease-in-out;
  transform-origin: 50% 100%;
}

/* 热点动作：凑近查看（歪头打量） */
.mascot-actor.act-inspect .mascot-body {
  animation: mascot-inspect 1.3s ease-in-out;
  transform-origin: 50% 100%;
}

/* 热点动作：点头 */
.mascot-actor.act-nod .mascot-body {
  animation: mascot-nod 0.9s ease-in-out;
  transform-origin: 50% 100%;
}

/* 热点动作：扭动（左右甩身体） */
.mascot-actor.act-shake .mascot-body {
  animation: mascot-shake 1s ease-in-out;
  transform-origin: 50% 100%;
}

/* 热点动作：后空翻（蹲→腾空绕身体中心整转一圈→落地缓冲回弹；
   必须绕中心转且 transform-box: fill-box——绕脚底转会变成"头画圆"的钟摆弧线） */
.mascot-actor.act-backflip .mascot-body {
  animation: mascot-backflip 1.1s cubic-bezier(.35, 0, .25, 1);
  transform-box: fill-box;
  transform-origin: center;
}

/* ===== 第二批动作：俯卧撑/太空步/打滑/举牌/灵光/魔术/探望/冥想/打扫/画圈/喝咖啡/告别 ===== */
.mascot-actor.act-pushups .mascot-body { transform-origin: 50% 100%; animation: mascot-pushups 2.4s ease-in-out; }
@keyframes mascot-pushups {
  0%, 100% { transform: translateY(0) scale(1, 1); }
  10% { transform: translateY(5px) scale(1.18, 0.6); }
  25% { transform: translateY(1px) scale(1.08, 0.84); }
  40% { transform: translateY(5px) scale(1.2, 0.58); }
  55% { transform: translateY(1px) scale(1.08, 0.86); }
  70% { transform: translateY(5px) scale(1.2, 0.58); }
  85% { transform: translateY(1px) scale(1.1, 0.88); }
}
.mascot-actor.act-moonwalk .mascot-body { animation: mascot-moonwalk 1.5s ease-in-out; }
@keyframes mascot-moonwalk {
  0%, 100% { transform: translateX(0) rotate(0deg); }
  15%, 45%, 75% { transform: translateX(-8px) rotate(-6deg); }
  30%, 60% { transform: translateX(0) rotate(0deg); }
}
.mascot-actor.act-slip .mascot-body { transform-origin: 50% 100%; animation: mascot-slip 1.7s ease-in-out; }
@keyframes mascot-slip {
  0% { transform: translateY(0) rotate(0deg); }
  18% { transform: translateY(-4px) rotate(16deg); }
  38%, 70% { transform: translateY(5px) rotate(76deg) scale(1.06, 0.82); }
  88% { transform: translateY(-2px) rotate(18deg); }
  100% { transform: translateY(0) rotate(0deg); }
}
.mascot-actor.act-sign .mascot-body { animation: mascot-sign 2.1s ease-in-out; }
@keyframes mascot-sign {
  0%, 100% { transform: translateY(0) rotate(0deg); }
  20%, 80% { transform: translateY(-2px) rotate(-4deg); }
}
.mascot-actor.act-idea .mascot-body { animation: mascot-idea 1.4s cubic-bezier(.34, 1.56, .64, 1); }
@keyframes mascot-idea {
  0%, 100% { transform: translateY(0) rotate(0deg); }
  20% { transform: translateY(3px) rotate(-8deg); }
  55% { transform: translateY(-10px) rotate(6deg); }
}
.mascot-actor.act-magic .mascot-body { animation: mascot-magic 1.9s ease-in-out; }
@keyframes mascot-magic {
  0% { transform: rotate(0deg); }
  25% { transform: rotate(-12deg) scale(0.96, 1.05); }
  55%, 100% { transform: rotate(360deg) scale(1, 1); }
}
.mascot-actor.act-peek .mascot-body { animation: mascot-peek 1.9s ease-in-out; }
@keyframes mascot-peek {
  0%, 100% { transform: translateX(0) rotate(0deg); }
  25%, 55% { transform: translateX(-10px) rotate(-8deg); }
  40% { transform: translateX(-4px) rotate(-3deg); }
}
.mascot-actor.act-meditate .mascot-body { animation: mascot-meditate 3.2s ease-in-out; }
@keyframes mascot-meditate {
  0%, 100% { transform: translateY(0) scale(1, 1); }
  25%, 75% { transform: translateY(-4px) scale(0.94, 1.06); }
  50% { transform: translateY(-6px) scale(0.92, 1.08); }
}
.mascot-actor.act-sweep .mascot-body { transform-origin: 50% 100%; animation: mascot-sweep 2.2s ease-in-out; }
@keyframes mascot-sweep {
  0%, 100% { transform: rotate(0deg); }
  20%, 60% { transform: rotate(-10deg); }
  40%, 80% { transform: rotate(10deg); }
}
.mascot-actor.act-sulk .mascot-body { transform-origin: 50% 100%; animation: mascot-sulk 2.1s ease-in-out; }
@keyframes mascot-sulk {
  0%, 100% { transform: translateY(0) rotate(0deg); }
  15%, 45% { transform: translateY(4px) rotate(-6deg) scale(1.05, 0.9); }
  30%, 60% { transform: translateY(4px) rotate(6deg) scale(1.05, 0.9); }
}
.mascot-actor.act-sip .mascot-body { transform-origin: 50% 100%; animation: mascot-sip 1.9s ease-in-out; }
@keyframes mascot-sip {
  0%, 100% { transform: rotate(0deg); }
  25%, 60% { transform: rotate(7deg); }
  40% { transform: rotate(4deg); }
}
.mascot-actor.act-goodbye .mascot-body { transform-origin: 50% 100%; animation: mascot-goodbye 1.9s ease-in-out; }
@keyframes mascot-goodbye {
  0%, 100% { transform: rotate(0deg); }
  20%, 50%, 80% { transform: rotate(-8deg); }
  35%, 65% { transform: rotate(8deg); }
}

/* —— 新动作 fx 元素 —— */
.fx-sweat2 { top: -12px; right: 0; font-size: 10px; animation: fx-pop 1.2s ease-out both; }
.fx-glide { top: -14px; right: -6px; color: var(--accent); animation: fx-pop 1.5s ease-out both; }
.fx-dizzy { top: -14px; left: 2px; font-size: 11px; color: var(--text-2); animation: fx-dizzy-spin 1.7s linear both; }
.fx-dizzy.fx-dz2 { left: auto; right: 2px; animation-delay: .12s; }
@keyframes fx-dizzy-spin {
  0% { opacity: 0; transform: rotate(0deg); }
  20% { opacity: 1; }
  100% { opacity: 0; transform: rotate(360deg); }
}
.mascot-sign {
  position: absolute;
  bottom: calc(100% + 6px);
  left: 50%;
  transform: translateX(-50%);
  padding: 3px 8px;
  background: var(--accent);
  color: #101014;
  font-size: 11px;
  font-weight: 700;
  white-space: nowrap;
  animation: fx-sign-pop 2.1s cubic-bezier(.34, 1.56, .64, 1) both;
}
@keyframes fx-sign-pop {
  0% { opacity: 0; transform: translateX(-50%) translateY(8px) scale(.6); }
  15%, 85% { opacity: 1; transform: translateX(-50%) translateY(0) scale(1); }
  100% { opacity: 0; transform: translateX(-50%) translateY(-6px) scale(.9); }
}
.fx-idea { top: -22px; left: 50%; margin-left: -8px; font-size: 14px; animation: fx-idea-pop 1.4s cubic-bezier(.34, 1.56, .64, 1) both; }
@keyframes fx-idea-pop {
  0% { opacity: 0; transform: scale(.3) rotate(-20deg); }
  40% { opacity: 1; transform: scale(1.25) rotate(8deg); }
  100% { opacity: 0; transform: scale(1) rotate(0); }
}
.fx-magic { top: -16px; right: -2px; animation: fx-pop 1.9s ease-out both; }
.fx-peek { top: -14px; left: -14px; color: var(--text-2); animation: fx-pop 1.9s ease-out both; }
.fx-halo {
  top: -20px;
  left: 50%;
  margin-left: -11px;
  width: 22px;
  height: 22px;
  border: 2px dashed var(--accent);
  border-radius: 50%;
  animation: fx-halo-spin 3.2s linear both;
}
@keyframes fx-halo-spin {
  0% { opacity: 0; transform: rotate(0deg) scale(.5); }
  15%, 85% { opacity: .9; transform: rotate(200deg) scale(1); }
  100% { opacity: 0; transform: rotate(400deg) scale(.7); }
}
.fx-broom { top: -20px; right: -14px; font-size: 13px; transform-origin: 80% 90%; animation: fx-broom-swing 2.2s ease-in-out both; }
@keyframes fx-broom-swing {
  0%, 100% { transform: rotate(0deg); }
  20%, 60% { transform: rotate(-24deg); }
  40%, 80% { transform: rotate(14deg); }
}
.fx-sulk { top: -12px; left: -10px; color: var(--text-2); animation: fx-sulk-draw 2.1s ease-in-out both; }
@keyframes fx-sulk-draw {
  0% { opacity: 0; transform: rotate(0deg); }
  25%, 75% { opacity: 1; transform: rotate(360deg); }
  100% { opacity: 0; transform: rotate(720deg); }
}
.fx-cup { top: -14px; left: -12px; font-size: 12px; animation: fx-pop 1.9s ease-out both; }
.fx-tear { top: 2px; right: 0; font-size: 10px; animation: fx-tear-fall 1.9s ease-in both; }
@keyframes fx-tear-fall {
  0% { opacity: 0; transform: translateY(0); }
  25% { opacity: 1; }
  100% { opacity: 0; transform: translateY(10px); }
}
.fx-bye { top: -18px; right: -10px; font-size: 13px; animation: fx-bye-wave 1.9s ease-in-out both; }
@keyframes fx-bye-wave {
  0%, 100% { transform: rotate(0deg); }
  25%, 65% { transform: rotate(-20deg); }
  45%, 85% { transform: rotate(15deg); }
}

/* —— 情境装饰：加班咖啡 / 周五拎包 / 成就徽章 / 黑眼圈 —— */
.fx-late { top: -14px; right: -10px; font-size: 11px; animation: mascot-float 3s ease-in-out infinite; }
.fx-case { top: -14px; left: -12px; font-size: 11px; animation: mascot-float 3s ease-in-out infinite; }
.fx-tired { position: absolute; width: 5px; height: 2px; background: #6b7280; opacity: .8; }
.fx-tired.t1 { top: 12px; left: 40%; }
.fx-tired.t2 { top: 12px; right: 40%; }
.mascot-badge {
  position: absolute;
  bottom: calc(100% + 8px);
  left: 50%;
  transform: translateX(-50%);
  padding: 3px 9px;
  background: #fbbf24;
  color: #101014;
  font-size: 11px;
  font-weight: 700;
  white-space: nowrap;
  z-index: 3;
  animation: fx-sign-pop 9s ease-in-out both;
}
@keyframes mascot-snore {
  0% { opacity: .4; transform: scale(.7); }
  40% { opacity: 1; transform: scale(1.25); }
  100% { opacity: .5; transform: scale(.8); }
}

/* 被点：二连跳舞蹈（覆盖待机） */
.mascot-actor.petted .mascot-body {
  animation: mascot-dance 1.1s ease-in-out;
}

.mascot-actor.petted .mascot-body rect[fill='#101014'] {
  animation: mascot-blink-big 1.1s ease-in-out;
}

.mascot-star {
  position: absolute;
  top: -18px;
  left: 50%;
  transform: translateX(-50%);
  color: #fbbf24;
  font-size: 14px;
  animation: mascot-float 1.25s ease-out both;
}

/* 睡觉：z 字逐只上飘（错峰循环） */
.zzz {
  top: -8px;
  right: 2px;
  color: var(--text-2);
  font-family: var(--font-mono);
  font-size: 10px;
  opacity: 0;
  animation: zzz-fly 3s linear infinite;
}
.zzz.z2 {
  right: 9px;
  font-size: 13px;
  animation-delay: 1s;
}
.zzz.z3 {
  right: -3px;
  font-size: 8px;
  animation-delay: 2s;
}
@keyframes zzz-fly {
  0% { opacity: 0; transform: translate(0, 0) rotate(0deg); }
  15% { opacity: 0.9; }
  100% { opacity: 0; transform: translate(9px, -22px) rotate(14deg); }
}

@keyframes mascot-jump {
  0%, 60% { transform: translateY(0) scaleX(1) scaleY(1); }
  66% { transform: translateY(1px) scaleX(1.08) scaleY(0.86); }
  76% { transform: translateY(-8px) scaleX(0.94) scaleY(1.1); }
  84% { transform: translateY(0) scaleX(1.05) scaleY(0.92); }
  92%, 100% { transform: translateY(0) scaleX(1) scaleY(1); }
}

@keyframes mascot-blink {
  0%, 88%, 100% { transform: scaleY(1); }
  92%, 95% { transform: scaleY(0.1); }
}

@keyframes mascot-waddle {
  0% { transform: translateY(0) rotate(-5deg); }
  25% { transform: translateY(-7px) rotate(0deg) scaleY(1.05); }
  50% { transform: translateY(0) rotate(5deg); }
  75% { transform: translateY(-7px) rotate(0deg) scaleY(1.05); }
  100% { transform: translateY(0) rotate(-5deg); }
}

@keyframes mascot-cheer {
  0% { transform: translateY(0) scale(1, 1); }
  18% { transform: translateY(2px) scale(1.06, 0.88); }
  34% { transform: translateY(-9px) scale(0.96, 1.08); }
  50% { transform: translateY(0) scale(1.05, 0.9); }
  64% { transform: translateY(-7px) scale(0.97, 1.05); }
  78% { transform: translateY(0) scale(1.04, 0.92); }
  100% { transform: translateY(0) scale(1, 1); }
}

@keyframes mascot-sad {
  0% { transform: translateY(0) scale(1, 1); }
  20%, 82% { transform: translateY(5px) scale(1.05, 0.78); }
  100% { transform: translateY(0) scale(1, 1); }
}

@keyframes mascot-sleep {
  from { transform: translateY(0) scale(1, 1); }
  to { transform: translateY(6px) scale(1.04, 0.72); }
}

@keyframes mascot-dance {
  0% { transform: translateY(0) scale(1, 1); }
  14% { transform: translateY(2px) scale(1.07, 0.86); }
  30% { transform: translateY(-11px) scale(0.94, 1.12); }
  45% { transform: translateY(0) scale(1.06, 0.88); }
  56% { transform: translateY(-8px) scale(0.96, 1.07); }
  68% { transform: translateY(0) scale(1.05, 0.9); }
  80%, 100% { transform: translateY(0) scale(1, 1); }
}

@keyframes mascot-blink-big {
  0%, 80%, 100% { transform: scaleY(1); }
  88%, 94% { transform: scaleY(0.08); }
}

@keyframes mascot-roll {
  0% { transform: translateY(0) rotate(0deg); }
  25% { transform: translateY(4px) rotate(120deg); }
  55% { transform: translateY(4px) rotate(240deg); }
  80% { transform: translateY(0) rotate(345deg); }
  100% { transform: translateY(0) rotate(360deg); }
}

@keyframes mascot-spin {
  0% { transform: rotate(0deg) scale(1, 1); }
  40% { transform: rotate(360deg) scale(1.05, 0.95); }
  70% { transform: rotate(720deg) scale(0.96, 1.04); }
  100% { transform: rotate(720deg) scale(1, 1); }
}

@keyframes mascot-inspect {
  0% { transform: translateY(0) rotate(0deg); }
  25%, 60% { transform: translateY(-2px) rotate(-12deg) translateX(3px); }
  40% { transform: translateY(-2px) rotate(-6deg) translateX(5px); }
  100% { transform: translateY(0) rotate(0deg); }
}

@keyframes mascot-nod {
  0% { transform: translateY(0) rotate(0deg); }
  25% { transform: translateY(2px) rotate(0deg) scaleY(0.9); }
  50% { transform: translateY(0) rotate(0deg) scaleY(1.04); }
  75% { transform: translateY(2px) rotate(0deg) scaleY(0.9); }
  100% { transform: translateY(0) rotate(0deg) scaleY(1); }
}

@keyframes mascot-shake {
  0%, 100% { transform: translateX(0) rotate(0deg); }
  15% { transform: translateX(-4px) rotate(-7deg); }
  35% { transform: translateX(4px) rotate(7deg); }
  55% { transform: translateX(-4px) rotate(-7deg); }
  75% { transform: translateX(4px) rotate(7deg); }
  90% { transform: translateX(0) rotate(0deg); }
}

@keyframes mascot-backflip {
  0%   { transform: translateY(0) rotate(0deg) scale(1, 1); }
  12%  { transform: translateY(3px) rotate(0deg) scale(1.14, 0.8); }        /* 下蹲蓄力 */
  36%  { transform: translateY(-26px) rotate(-175deg) scale(0.94, 1.08); }  /* 腾空转前半圈 */
  62%  { transform: translateY(-22px) rotate(-345deg) scale(0.94, 1.08); }  /* 空中转完后半圈 */
  84%  { transform: translateY(0) rotate(-360deg) scale(1.16, 0.82); }      /* 落地压缩 */
  100% { transform: translateY(0) rotate(-360deg) scale(1, 1); }            /* 回弹站直 */
}

@keyframes mascot-float {
  0% { opacity: 0; transform: translate(-50%, 6px); }
  20% { opacity: 1; }
  100% { opacity: 0; transform: translate(-50%, -10px); }
}

/* ===== 说话气泡 ===== */
.mascot-bubble {
  position: absolute;
  bottom: calc(100% + 10px);
  left: 50%;
  transform: translateX(-50%);
  max-width: 260px;
  white-space: nowrap;
  padding: 6px 12px;
  border: 1px solid var(--rule-strong);
  border-radius: 8px;
  background: #0b0d15;
  color: var(--text-1);
  font-size: 11.5px;
  line-height: 1.5;
  box-shadow: 0 10px 30px rgba(0, 0, 0, 0.4);
  animation: bubble-in 0.22s var(--motion-curve) both;
  pointer-events: none;
  z-index: 5;
}
@keyframes bubble-in {
  from { opacity: 0; transform: translateX(-50%) translateY(4px); }
  to { opacity: 1; transform: translateX(-50%) translateY(0); }
}

/* ===== 摸头享受：身体轻晃 ===== */
.mascot-actor.petting .mascot-body {
  animation: pt-enjoy 1.5s ease-in-out;
  transform-origin: 50% 100%;
}
@keyframes pt-enjoy {
  0%, 100% { transform: rotate(0deg); }
  25% { transform: rotate(-3deg) translateY(-1px); }
  55% { transform: rotate(3deg) translateY(-1px); }
  80% { transform: rotate(-1.5deg); }
}
.fx-pet {
  top: -16px;
  left: 50%;
  transform: translateX(-50%);
  color: #f9a8d4;
  font-size: 13px;
  animation: mascot-float 1.5s ease-out both;
}

/* ===== 动作专属动效 ===== */
.fx {
  position: absolute;
  pointer-events: none;
  font-style: normal;
}

/* 跳跃落地尘土：脚下两侧的小圆点（延迟到落地时刻扬起） */
.fx-dust {
  bottom: -2px;
  left: -4px;
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: rgba(236, 233, 225, 0.45);
  animation: fx-dust 0.7s ease-out both;
}
.fx-d2 {
  left: auto;
  right: -4px;
  animation-delay: 0.12s;
}

/* act-jump（二连跳 1.15s）：落地在 0.52s / 0.78s */
.mascot-actor.act-jump .fx-dust {
  animation: fx-dust 0.45s ease-out 0.5s both;
}
.mascot-actor.act-jump .fx-d2 {
  animation-delay: 0.75s;
}
@keyframes fx-dust {
  0% { opacity: 0; transform: translateY(0) scale(0.5); }
  30% { opacity: 1; }
  100% { opacity: 0; transform: translateY(6px) scale(1.4); }
}

/* 翻滚拖尾 */
.fx-trail {
  top: 6px;
  left: -16px;
  color: rgba(125, 211, 252, 0.8);
  font-size: 13px;
  animation: fx-slide-left 0.95s ease-out both;
}
@keyframes fx-slide-left {
  0% { opacity: 0; transform: translateX(14px); }
  30% { opacity: 1; }
  100% { opacity: 0; transform: translateX(-16px); }
}

/* 旋转星光 */
.fx-spark {
  top: -14px;
  right: -8px;
  color: #fbbf24;
  font-size: 13px;
  animation: fx-twirl 0.85s ease-in-out both;
}
@keyframes fx-twirl {
  0% { opacity: 0; transform: rotate(0deg) translateY(4px); }
  50% { opacity: 1; transform: rotate(180deg) translateY(-4px); }
  100% { opacity: 0; transform: rotate(360deg) translateY(2px); }
}

/* 凑近查看：问号气泡 */
.fx-q {
  top: -22px;
  left: 50%;
  transform: translateX(-50%);
  color: #7dd3fc;
  font-family: "JetBrains Mono", monospace;
  font-size: 14px;
  font-weight: 700;
  animation: mascot-float 1.3s ease-out both;
}

/* 点头/扭动：音符 */
.fx-note {
  top: -14px;
  right: -6px;
  color: #a5b4fc;
  font-size: 13px;
  animation: mascot-float 1s ease-out both;
}
.fx-n2 {
  right: auto;
  left: -6px;
  animation-delay: 0.18s;
}

/* 后空翻：爆星两颗 */
.fx-burst {
  top: -12px;
  left: -8px;
  color: var(--signal);
  font-size: 13px;
  animation: fx-burst 1.1s ease-out both;
}
.fx-b2 {
  left: auto;
  right: -8px;
  animation-delay: 0.3s;
}
@keyframes fx-burst {
  0% { opacity: 0; transform: scale(0.4); }
  35% { opacity: 1; transform: scale(1.3) translateY(-6px); }
  100% { opacity: 0; transform: scale(0.9) translateY(-14px); }
}

/* 扛文件：汗滴 */
.fx-sweat {
  top: -10px;
  right: 2px;
  font-size: 11px;
  animation: fx-drip 1.4s ease-in infinite;
}
@keyframes fx-drip {
  0% { opacity: 0; transform: translateY(-2px); }
  30% { opacity: 1; }
  100% { opacity: 0; transform: translateY(10px); }
}

/* 走路脚步尘：交替扬起 */
.fx-step {
  bottom: -2px;
  left: 8px;
  width: 5px;
  height: 5px;
  border-radius: 50%;
  background: rgba(236, 233, 225, 0.35);
  animation: fx-dust 0.32s ease-out infinite;
}
.fx-s2 {
  left: auto;
  right: 8px;
  animation-delay: 0.16s;
}

/* 互动跳舞：爱心浮起 */
.fx-heart {
  top: -14px;
  left: -8px;
  color: #f9a8d4;
  font-size: 13px;
  animation: mascot-float 1.15s ease-out both;
}
.fx-h2 {
  left: auto;
  right: -8px;
  animation-delay: 0.25s;
}

/* 欢呼星星（轮盘动作版） */
.fx-cheer-star {
  top: -20px;
  right: -6px;
  color: #fbbf24;
  font-size: 15px;
  animation: mascot-float 1.3s ease-out both;
}

/* 待机小跳：每周期落地微尘（与 3.4s 跳跃周期同步） */
.fx-idle-dust {
  bottom: -2px;
  left: 50%;
  margin-left: -3px;
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: rgba(236, 233, 225, 0.28);
  animation: fx-idle-dust 3.4s ease-out infinite;
}
@keyframes fx-idle-dust {
  0%, 78% { opacity: 0; transform: translateY(0) scale(0.4); }
  86% { opacity: 0.8; transform: translateY(2px) scale(1.2); }
  100% { opacity: 0; transform: translateY(6px) scale(1.5); }
}

/* 挥手 ☆ */
.fx-hi {
  top: -18px;
  right: -6px;
  color: #fbbf24;
  font-size: 13px;
  animation: mascot-float 1.1s ease-out both;
}
/* 兴奋 ✦✧ */
.fx-spark2 {
  top: -16px;
  left: -6px;
  color: #7dd3fc;
  font-size: 12px;
  animation: mascot-float 0.9s ease-out both;
}
.fx-k2 {
  left: auto;
  right: -6px;
  animation-delay: 0.15s;
}
/* 花痴 ❤❤：盖在眼睛位置 */
.fx-love {
  top: 4px;
  left: 17px;
  color: #fb7185;
  font-size: 8.5px;
  letter-spacing: 7px;
  animation: fx-lovebeat 1.4s ease-in-out both;
}
@keyframes fx-lovebeat {
  0% { opacity: 0; transform: scale(0.5); }
  18%, 80% { opacity: 1; transform: scale(1.15); }
  30%, 60% { transform: scale(1); }
  100% { opacity: 0; transform: scale(1.05); }
}
/* 张望 … */
.fx-dots {
  top: -16px;
  left: 50%;
  transform: translateX(-50%);
  color: var(--text-2);
  font-family: "JetBrains Mono", monospace;
  font-size: 12px;
  letter-spacing: 0.2em;
  animation: mascot-float 1.2s ease-out both;
}
/* 踮脚 ✧ */
.fx-stretch {
  top: -20px;
  left: 50%;
  transform: translateX(-50%);
  color: #a5f3fc;
  font-size: 12px;
  animation: mascot-float 1.3s ease-out both;
}
/* 倒立 ! */
.fx-bang {
  top: -16px;
  left: 50%;
  transform: translateX(-50%);
  color: var(--signal);
  font-family: "JetBrains Mono", monospace;
  font-size: 14px;
  font-weight: 700;
  animation: mascot-float 1.4s ease-out both;
}

/* ===== 全局氛围：影子 / 脚印 ===== */
/* 脚下影子：贴地椭圆，缩放节奏与待机跳动逐帧同步 */
.mascot-shadow {
  position: absolute;
  bottom: -3px;
  left: 16%;
  width: 68%;
  height: 5px;
  border-radius: 50%;
  background: rgba(0, 0, 0, 0.5);
  filter: blur(1px);
  pointer-events: none;
}
.mascot-actor.idle .mascot-shadow {
  animation: shadow-idle 3.4s ease-in-out infinite;
}
.mascot-actor.sleeping .mascot-shadow {
  transform: scaleX(1.15);
}
@keyframes shadow-idle {
  0%, 60% { transform: scale(1); }
  66% { transform: scale(1.12); }   /* 蓄力压扁 → 影子摊大 */
  76% { transform: scale(0.72); }   /* 腾空 → 影子缩小 */
  84% { transform: scale(1.08); }   /* 落地 */
  92%, 100% { transform: scale(1); }
}

/* 走路脚印：fixed 于视口留在原地，1.4s 渐隐 */
.footprint {
  position: fixed;
  z-index: 49;
  width: 6px;
  height: 3px;
  border-radius: 45%;
  background: rgba(236, 233, 225, 0.26);
  pointer-events: none;
  animation: footprint-fade 1.4s ease-out both;
}
@keyframes footprint-fade {
  0% { opacity: 0.8; }
  100% { opacity: 0; }
}

/* ===== 特效增强：彩虹尾迹 / 掌声波 / 星星眼 ===== */
/* 太空步：脚下向身后拉出一道彩虹光带再消散 */
.fx-rainbow {
  bottom: 0;
  right: 50%;
  width: 48px;
  height: 4px;
  border-radius: 2px;
  background: linear-gradient(90deg, rgba(244, 114, 182, 0), #f472b6, #fbbf24, #a3e635, #38bdf8, #a78bfa);
  opacity: 0.85;
  transform-origin: right center;
  animation: rainbow-out 1.5s ease-out both;
}
@keyframes rainbow-out {
  0% { opacity: 0; transform: scaleX(0); }
  35% { opacity: 0.9; transform: scaleX(1); }
  100% { opacity: 0; transform: scaleX(1) translateX(-6px); }
}

/* 鼓掌：两圈声波环与两声掌声同拍扩散 */
.fx-wave {
  top: 30%;
  left: 50%;
  width: 10px;
  height: 10px;
  margin: -5px 0 0 -5px;
  border: 1.5px solid var(--text-3);
  border-radius: 50%;
  opacity: 0;
}
.fx-wave.w1 { animation: fx-wave-out 0.7s ease-out both; animation-delay: 0.26s; }
.fx-wave.w2 { animation: fx-wave-out 0.7s ease-out both; animation-delay: 0.81s; }
@keyframes fx-wave-out {
  0% { opacity: 0.85; transform: scale(0.6); }
  100% { opacity: 0; transform: scale(4.2); }
}

/* 星星眼：原眼位（约 30% / 57% 宽处）闪烁金 ✦ */
.fx-stareye {
  top: 4px;
  font-size: 8px;
  color: #f4c542;
  animation: fx-stareye-twinkle 0.5s ease-in-out infinite alternate;
}
.fx-stareye.s1 { left: 30%; }
.fx-stareye.s2 { left: 57%; animation-delay: 0.25s; }
@keyframes fx-stareye-twinkle {
  0% { opacity: 0.4; transform: scale(0.8) rotate(0deg); }
  100% { opacity: 1; transform: scale(1.2) rotate(20deg); }
}
.mascot-actor.star-eyes .pt-eye { opacity: 0; }

/* ===== 第五批动作：打电话 / 比心 ===== */
/* 打电话：右臂弯到耳边举着小手机，身体仰头等接通 */
.mascot-actor.act-call .mascot-body {
  animation: pt-call 2.6s ease-in-out;
  transform-origin: 50% 100%;
}
.mascot-actor.act-call .pt-arm:nth-of-type(3) {
  transform-box: fill-box;
  transform-origin: top center;
  animation: pt-call-arm 2.6s ease-in-out;
}
@keyframes pt-call {
  0%, 100% { transform: rotate(0deg); }
  20%, 80% { transform: rotate(-5deg) translateY(-1px); }
}
@keyframes pt-call-arm {
  0%, 100% { transform: rotate(0deg); }
  15%, 85% { transform: rotate(95deg); }
}
.fx-phone {
  top: -6px;
  right: -2px;
  width: 7px;
  height: 11px;
  border-radius: 2px;
  background: #101014;
  border: 1px solid var(--rule-strong);
  transform: rotate(12deg);
  animation: fx-pop 2.6s ease-out both;
}
.fx-ring {
  top: -18px;
  left: -12px;
  font-size: 10px;
  color: var(--text-2);
  white-space: nowrap;
  animation: fx-ring-blink 0.9s ease-in-out 3 both;
}
@keyframes fx-ring-blink {
  0%, 100% { opacity: 0; }
  40% { opacity: 1; }
}

/* 比心：双臂举过头顶拼成 V，大♡ 升起 */
.mascot-actor.act-fingerheart .pt-arm:nth-of-type(2) {
  transform-box: fill-box;
  transform-origin: top center;
  animation: pt-heart-l 1.6s ease-in-out;
}
.mascot-actor.act-fingerheart .pt-arm:nth-of-type(3) {
  transform-box: fill-box;
  transform-origin: top center;
  animation: pt-heart-r 1.6s ease-in-out;
}
@keyframes pt-heart-l {
  0%, 100% { transform: rotate(0deg); }
  25%, 75% { transform: rotate(-105deg); }
}
@keyframes pt-heart-r {
  0%, 100% { transform: rotate(0deg); }
  25%, 75% { transform: rotate(105deg); }
}
.fx-bigheart {
  bottom: calc(100% + 2px);
  left: 50%;
  margin-left: -8px;
  font-size: 16px;
  color: #e0527a;
  animation: fx-bigheart-pop 1.6s ease-out both;
}
@keyframes fx-bigheart-pop {
  0% { opacity: 0; transform: translateY(4px) scale(0.4); }
  30% { opacity: 1; transform: translateY(-6px) scale(1.25); }
  70% { opacity: 1; transform: translateY(-14px) scale(1); }
  100% { opacity: 0; transform: translateY(-22px) scale(0.9); }
}

/* ===== 状态装饰 / 换装 ===== */
/* 安全帽与换装配件均已改为 SVG 子元素（见模板），跟随身体动作，无需 HTML 定位 */

/* 读回答：朝内容区专注微倾 */
.mascot-actor.reading .mascot-body {
  animation: pt-reading 2.2s ease-in-out infinite;
  transform-origin: 50% 100%;
}
@keyframes pt-reading {
  0%, 100% { transform: rotate(-6deg); }
  50% { transform: rotate(-8.5deg) translateY(-1px); }
}

/* 登场烟雾：fixed 于落点炸开渐散（单团 2s，全程约 2.4s，浓白大团） */
.poof {
  position: fixed;
  z-index: 49;
  width: 26px;
  height: 14px;
  border-radius: 50%;
  background: rgba(242, 239, 230, 0.55);
  filter: blur(3px);
  pointer-events: none;
  animation: poof-burst 2s ease-out both;
}
@keyframes poof-burst {
  0% { opacity: 1; transform: scale(0.35); }
  45% { opacity: 0.95; transform: scale(1.6); }
  75% { opacity: 0.55; transform: scale(2.6) translateY(-10px); }
  100% { opacity: 0; transform: scale(3.4) translateY(-16px); }
}

/* ===== 拖拽挣扎 / 节日飘雪 ===== */
/* 被鼠标拎起来时：四条腿乱蹬、双臂乱挥、身体扭动，喊"放开我" */
/* 注：动画定向到 .pt-leg-1~4 / .pt-arm-l,r 这些显式类名，
   不用 nth-of-type —— rect 兄弟节点太多，序号不稳定，之前四条腿其实一直没蹬起来 */
.mascot-actor.dragging .pt-leg {
  transform-box: fill-box;
  transform-origin: top center;
  animation: pt-kick 0.22s ease-in-out infinite alternate;
}
.mascot-actor.dragging .pt-leg-2 { animation-delay: 0.05s; animation-duration: 0.26s; }
.mascot-actor.dragging .pt-leg-3 { animation-delay: 0.1s; animation-duration: 0.19s; }
.mascot-actor.dragging .pt-leg-4 { animation-delay: 0.15s; animation-duration: 0.24s; }
@keyframes pt-kick {
  0% { transform: rotate(-30deg) translateY(0); }
  100% { transform: rotate(30deg) translateY(-3px); }
}

/* 双臂乱挥：像在喊"放开我" */
.mascot-actor.dragging .pt-arm {
  transform-box: fill-box;
  transform-origin: bottom center;
  animation: pt-flail 0.26s ease-in-out infinite alternate;
}
.mascot-actor.dragging .pt-arm-r { animation-delay: 0.13s; }
@keyframes pt-flail {
  0% { transform: rotate(-28deg); }
  100% { transform: rotate(24deg); }
}

/* 整体扭动：被拎着左右晃 */
.mascot-actor.dragging .mascot-body {
  transform-origin: 50% 92%;
  animation: pt-drag-wobble 0.52s ease-in-out infinite;
}
@keyframes pt-drag-wobble {
  0% { transform: rotate(-5deg) scale(1.04, 0.96); }
  50% { transform: rotate(5deg) scale(1.02, 0.98); }
  100% { transform: rotate(-5deg) scale(1.04, 0.96); }
}

/* 挣扎特效：两侧汗珠 + 头顶惊叹号 */
.fx-drag-sweat {
  top: -10px;
  left: -4px;
  font-size: 10px;
  animation: fx-drag-fly 0.9s ease-out infinite;
}
.fx-drag-sweat.fx-ds2 { left: auto; right: -2px; animation-delay: 0.45s; }
@keyframes fx-drag-fly {
  0% { opacity: 0; transform: translateY(2px) scale(0.8); }
  30% { opacity: 1; transform: translateY(-3px) scale(1); }
  100% { opacity: 0; transform: translateY(-11px) scale(0.9); }
}
.fx-drag-bang {
  top: -19px;
  left: 50%;
  margin-left: -3px;
  font-size: 12px;
  font-weight: 700;
  color: var(--signal);
  animation: fx-drag-bang 0.48s ease-in-out infinite alternate;
}
@keyframes fx-drag-bang {
  from { transform: translateY(0) scale(1); }
  to { transform: translateY(-2px) scale(1.18); }
}

/* 放下后眩晕：站不稳地晃两下 */
.mascot-actor.act-dizzy .mascot-body {
  transform-origin: 50% 92%;
  animation: mascot-dizzy 1.5s ease-in-out;
}
@keyframes mascot-dizzy {
  0% { transform: rotate(0deg); }
  15% { transform: rotate(-8deg) translateX(-2px); }
  35% { transform: rotate(7deg) translateX(2px); }
  55% { transform: rotate(-5deg) translateX(-1px); }
  75% { transform: rotate(3deg); }
  100% { transform: rotate(0deg); }
}

/* 换装配件已改为 SVG 子元素（见模板 svg 内 outfit-* 分组），随身体动作，无 HTML 定位 */

/* 节日飘雪：全屏细雪，缓落带横向漂移 */
.snowflake {
  position: fixed;
  top: -10px;
  z-index: 6;
  border-radius: 50%;
  background: rgba(255, 255, 255, 0.8);
  pointer-events: none;
  animation: snow-fall var(--snow-dur) linear both;
}
@keyframes snow-fall {
  0% { opacity: 0; transform: translate3d(0, -10px, 0); }
  8% { opacity: 0.75; }
  88% { opacity: 0.5; }
  100% { opacity: 0; transform: translate3d(var(--snow-drift), 105vh, 0); }
}

/* 头顶小铭牌：脚底展台标签样式，说话气泡出现时让位 */
.mascot-nametag {
  position: absolute;
  top: calc(100% + 4px);
  left: 50%;
  transform: translateX(-50%);
  padding: 1px 5px;
  background: rgba(2, 3, 10, 0.78);
  border: 1px solid var(--rule);
  color: var(--text-2);
  font-size: 9px;
  line-height: 1.5;
  white-space: nowrap;
  pointer-events: none;
}

/* 拖拽态：去过渡 + 抓手（身体的缩放/扭动交给 .dragging .mascot-body 的动画） */
.mascot-actor.dragging {
  transition: none !important;
  cursor: grabbing;
  z-index: 60;
}

/* 投喂饼干 */
.mascot-treat {
  position: absolute;
  top: 50%;
  left: 50%;
  font-size: 14px;
  transform: translate(-50%, 0);
  animation: treat-drop 0.45s ease-in both;
  pointer-events: none;
}
@keyframes treat-drop {
  from { opacity: 0; transform: translate(-50%, -26px) rotate(-30deg); }
  to { opacity: 1; transform: translate(-50%, 6px) rotate(0deg); }
}

/* 吃：身体压两下 + 碎屑 */
.mascot-actor.act-eat .mascot-body {
  animation: pt-eat 1.3s ease-in-out;
  transform-origin: 50% 100%;
}
@keyframes pt-eat {
  0%, 100% { transform: translateY(0) scale(1, 1); }
  20% { transform: translateY(2px) scale(1.05, 0.9); }
  35% { transform: translateY(0) scale(1, 1); }
  50% { transform: translateY(2px) scale(1.05, 0.9); }
  65% { transform: translateY(0) scale(1, 1); }
  80% { transform: translateY(1px) scale(1.03, 0.95); }
}
.fx-crumbs {
  bottom: -2px;
  left: 6px;
  width: 5px;
  height: 5px;
  border-radius: 50%;
  background: #d9a05b;
  animation: fx-dust 0.6s ease-out both;
}
.fx-c2 {
  left: auto;
  right: 6px;
  animation-delay: 0.15s;
}

/* 庆祝彩纸 */
.confetti {
  position: absolute;
  top: -12px;
  width: 6px;
  height: 9px;
  border-radius: 1px;
  pointer-events: none;
  animation: confetti-fall 1.7s ease-in both;
}
@keyframes confetti-fall {
  0% { opacity: 0; transform: translateY(0) rotate(0deg); }
  15% { opacity: 1; }
  100% { opacity: 0; transform: translateY(110px) rotate(500deg); }
}

/* ===== 第三批动作：打字 / 打喷嚏 ===== */
/* 打字：身体前倾，两臂交替敲击，头顶键盘 */
.mascot-actor.act-type .mascot-body {
  animation: pt-type-lean 2.6s ease-in-out;
  transform-origin: 50% 100%;
}
.mascot-actor.act-type .pt-arm:nth-of-type(2) {
  transform-box: fill-box;
  transform-origin: top center;
  animation: pt-type-l 0.3s ease-in-out 8;
}
.mascot-actor.act-type .pt-arm:nth-of-type(3) {
  transform-box: fill-box;
  transform-origin: top center;
  animation: pt-type-r 0.3s ease-in-out 8;
  animation-delay: 0.15s;
}
@keyframes pt-type-lean {
  0%, 100% { transform: rotate(0deg); }
  15%, 85% { transform: rotate(7deg) translateY(1px); }
}
@keyframes pt-type-l {
  0%, 100% { transform: rotate(0deg); }
  50% { transform: rotate(34deg); }
}
@keyframes pt-type-r {
  0%, 100% { transform: rotate(0deg); }
  50% { transform: rotate(-34deg); }
}
.fx-key {
  top: -18px;
  right: -10px;
  font-size: 12px;
  color: var(--text-2);
  animation: fx-pop 2.6s ease-out both;
}

/* 打喷嚏：仰头蓄力 → 爆发前冲 + 星屑 */
.mascot-actor.act-sneeze .mascot-body {
  animation: pt-sneeze 1.5s ease-in-out;
  transform-origin: 50% 100%;
}
@keyframes pt-sneeze {
  0% { transform: scale(1, 1); }
  32% { transform: scale(1.08, 0.88) rotate(-5deg); }
  52% { transform: scale(0.92, 1.14) rotate(7deg) translateY(-3px); }
  68% { transform: scale(1.04, 0.96) translateY(1px); }
  100% { transform: scale(1, 1); }
}
.fx-achoo {
  top: -18px;
  left: 50%;
  transform: translateX(-50%);
  font-size: 12px;
  font-weight: 700;
  color: var(--signal);
  white-space: nowrap;
  animation: fx-achoo-burst 1.5s ease-out both;
}
@keyframes fx-achoo-burst {
  0%, 36% { opacity: 0; transform: translateX(-50%) scale(0.4); }
  48% { opacity: 1; transform: translateX(-50%) scale(1.35); }
  62% { transform: translateX(-50%) scale(1); }
  100% { opacity: 0; transform: translateX(-50%) translateY(-6px) scale(1); }
}
.fx-droplet {
  top: -12px;
  left: -6px;
  font-size: 9px;
  color: var(--accent);
  animation: fx-droplet-l 1.5s ease-out both;
}
.fx-droplet.fx-dr2 {
  left: auto;
  right: -6px;
  animation: fx-droplet-r 1.5s ease-out both;
  animation-delay: 0.5s;
}
@keyframes fx-droplet-l {
  0%, 42% { opacity: 0; transform: translate(0, 0) scale(0.5); }
  52% { opacity: 1; }
  100% { opacity: 0; transform: translate(-11px, -12px) scale(1); }
}
@keyframes fx-droplet-r {
  0%, 42% { opacity: 0; transform: translate(0, 0) scale(0.5); }
  52% { opacity: 1; }
  100% { opacity: 0; transform: translate(11px, -12px) scale(1); }
}

/* ===== 第四批动作：鼓掌 / 抛金币 ===== */
/* 鼓掌：两臂轮流合拢对拍，两轮 */
.mascot-actor.act-clap .pt-arm:nth-of-type(2) {
  transform-box: fill-box;
  transform-origin: top center;
  animation: pt-clap-l 0.55s ease-in-out 2;
}
.mascot-actor.act-clap .pt-arm:nth-of-type(3) {
  transform-box: fill-box;
  transform-origin: top center;
  animation: pt-clap-r 0.55s ease-in-out 2;
}
@keyframes pt-clap-l {
  0%, 100% { transform: rotate(0deg); }
  45% { transform: rotate(-52deg); }
}
@keyframes pt-clap-r {
  0%, 100% { transform: rotate(0deg); }
  45% { transform: rotate(52deg); }
}
.fx-clap {
  top: -16px;
  left: 50%;
  transform: translateX(-50%);
  font-size: 11px;
  font-weight: 700;
  color: var(--signal);
  white-space: nowrap;
  animation: fx-achoo-burst 1.4s ease-out both;
}

/* 抛金币：双臂轮流上抛，三枚金币依次腾空 */
.mascot-actor.act-juggle .pt-arm:nth-of-type(2) {
  transform-box: fill-box;
  transform-origin: top center;
  animation: pt-juggle-l 0.45s ease-in-out 4;
}
.mascot-actor.act-juggle .pt-arm:nth-of-type(3) {
  transform-box: fill-box;
  transform-origin: top center;
  animation: pt-juggle-r 0.45s ease-in-out 4;
  animation-delay: 0.22s;
}
@keyframes pt-juggle-l {
  0%, 100% { transform: rotate(0deg); }
  50% { transform: rotate(-55deg); }
}
@keyframes pt-juggle-r {
  0%, 100% { transform: rotate(0deg); }
  50% { transform: rotate(55deg); }
}
.fx-coin {
  top: -8px;
  left: 50%;
  margin-left: -3px;
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: #f4c542;
  animation: fx-coin-toss 0.9s ease-in-out 2 both;
}
.fx-coin.fx-cn2 {
  animation-delay: 0.35s;
  background: #e8b02f;
}
.fx-coin.fx-cn3 {
  animation-delay: 0.7s;
  background: #d99b1f;
}
@keyframes fx-coin-toss {
  0% { opacity: 0; transform: translateY(0) scale(0.6); }
  15% { opacity: 1; }
  50% { transform: translateY(-26px) scale(1.05); }
  85% { opacity: 1; }
  100% { opacity: 0; transform: translateY(4px) scale(0.8); }
}

/* 打字背身：眼睛隐去（转过去的意思） */
.mascot-actor.shy .pt-eye {
  opacity: 0;
  transition: opacity 0.25s ease;
}

/* ===== 第六批动作：耸肩 / 捂眼 / 举画框 / 伸懒腰 / 敬礼 ===== */
/* 手臂方向约定（与 wave/fingerheart 一致）：正值顺时针=甩向左上，负值逆时针=甩向右上 */
/* 耸肩：双臂外摊上耸两下——"知识库里没有，我也不瞎编" */
.mascot-actor.act-shrug .mascot-body {
  animation: pt-shrug 1.5s ease-in-out;
  transform-origin: 50% 100%;
}
.mascot-actor.act-shrug .pt-arm:nth-of-type(2) {
  transform-box: fill-box;
  transform-origin: top center;
  animation: pt-shrug-l 1.5s ease-in-out;
}
.mascot-actor.act-shrug .pt-arm:nth-of-type(3) {
  transform-box: fill-box;
  transform-origin: top center;
  animation: pt-shrug-r 1.5s ease-in-out;
}
@keyframes pt-shrug {
  0%, 100% { transform: translateY(0) rotate(0deg); }
  25%, 55% { transform: translateY(-1px) rotate(-2deg) scale(1.02, 0.98); }
  40%, 70% { transform: translateY(0) rotate(0deg); }
}
@keyframes pt-shrug-l {
  0%, 100% { transform: rotate(0deg); }
  20%, 50% { transform: rotate(54deg); }
  35% { transform: rotate(40deg); }
  65% { transform: rotate(52deg); }
}
@keyframes pt-shrug-r {
  0%, 100% { transform: rotate(0deg); }
  20%, 50% { transform: rotate(-54deg); }
  35% { transform: rotate(-40deg); }
  65% { transform: rotate(-52deg); }
}
.fx-shrug {
  top: -18px;
  left: 50%;
  transform: translateX(-50%);
  font-size: 10px;
  color: var(--text-2);
  white-space: nowrap;
  animation: mascot-float 1.5s ease-out both;
}

/* 捂眼：双臂抄起盖住眼位，眼睛隐去——删除确认框弹出时不敢看 */
.mascot-actor.act-cover .mascot-body {
  animation: pt-cover 1.7s ease-in-out;
  transform-origin: 50% 100%;
}
.mascot-actor.act-cover .pt-eye {
  opacity: 0;
}
.mascot-actor.act-cover .pt-arm:nth-of-type(2) {
  transform-box: fill-box;
  transform-origin: top center;
  animation: pt-cover-l 1.7s ease-in-out;
}
.mascot-actor.act-cover .pt-arm:nth-of-type(3) {
  transform-box: fill-box;
  transform-origin: top center;
  animation: pt-cover-r 1.7s ease-in-out;
}
@keyframes pt-cover {
  0%, 100% { transform: rotate(0deg) scale(1, 1); }
  25%, 82% { transform: rotate(2deg) scale(0.97, 1.04); }
}
@keyframes pt-cover-l {
  0%, 100% { transform: rotate(0deg); }
  22%, 82% { transform: rotate(-102deg); }
  30% { transform: rotate(-96deg); }
}
@keyframes pt-cover-r {
  0%, 100% { transform: rotate(0deg); }
  22%, 82% { transform: rotate(102deg); }
  30% { transform: rotate(96deg); }
}
.fx-cover {
  top: -18px;
  left: 50%;
  transform: translateX(-50%);
  font-size: 11px;
  font-weight: 700;
  color: var(--signal);
  white-space: nowrap;
  animation: fx-achoo-burst 1.7s ease-out both;
}

/* 举画框：双手在头顶捧框亮相，骄傲地小跳两下——生图完成 */
.mascot-actor.act-draw .mascot-body {
  animation: pt-draw 2.2s ease-in-out;
  transform-origin: 50% 100%;
}
.mascot-actor.act-draw .pt-arm:nth-of-type(2) {
  transform-box: fill-box;
  transform-origin: top center;
  animation: pt-draw-l 2.2s ease-in-out;
}
.mascot-actor.act-draw .pt-arm:nth-of-type(3) {
  transform-box: fill-box;
  transform-origin: top center;
  animation: pt-draw-r 2.2s ease-in-out;
}
@keyframes pt-draw {
  0%, 100% { transform: translateY(0) scale(1, 1); }
  15% { transform: translateY(1px) scale(1.04, 0.94); }
  30%, 55% { transform: translateY(-5px) scale(0.98, 1.05); }
  42% { transform: translateY(0) scale(1.03, 0.96); }
  70%, 88% { transform: translateY(-1px) rotate(0deg) scale(1, 1.02); }
}
@keyframes pt-draw-l {
  0%, 100% { transform: rotate(0deg); }
  20%, 85% { transform: rotate(-122deg); }
}
@keyframes pt-draw-r {
  0%, 100% { transform: rotate(0deg); }
  20%, 85% { transform: rotate(122deg); }
}
.fx-frame {
  top: -30px;
  left: 50%;
  margin-left: -8px;
  font-size: 14px;
  animation: fx-frame-pop 2.2s ease-in-out both;
}
@keyframes fx-frame-pop {
  0% { opacity: 0; transform: translateY(10px) scale(0.4) rotate(-14deg); }
  20% { opacity: 1; transform: translateY(-4px) scale(1.2) rotate(4deg); }
  30%, 82% { opacity: 1; transform: translateY(-8px) scale(1) rotate(-3deg); }
  100% { opacity: 0; transform: translateY(-18px) scale(0.9) rotate(0deg); }
}
.fx-frame-star {
  top: -22px;
  color: #fbbf24;
  font-size: 9px;
  animation: mascot-float 2.2s ease-out both;
}
.fx-frame-star.s1 { left: -6px; animation-delay: 0.25s; }
.fx-frame-star.s2 { left: auto; right: -6px; animation-delay: 0.45s; }

/* 伸懒腰：踮脚拔高，双臂伸过头顶——久用提醒 */
.mascot-actor.act-stretch .mascot-body {
  animation: pt-stretch 2.6s ease-in-out;
  transform-origin: 50% 100%;
}
.mascot-actor.act-stretch .pt-arm:nth-of-type(2) {
  transform-box: fill-box;
  transform-origin: top center;
  animation: pt-stretch-l 2.6s ease-in-out;
}
.mascot-actor.act-stretch .pt-arm:nth-of-type(3) {
  transform-box: fill-box;
  transform-origin: top center;
  animation: pt-stretch-r 2.6s ease-in-out;
}
@keyframes pt-stretch {
  0%, 100% { transform: translateY(0) scale(1, 1); }
  25%, 55% { transform: translateY(-5px) scale(0.94, 1.08); }
  40% { transform: translateY(-6px) scale(0.93, 1.1); }
  80% { transform: translateY(1px) scale(1.05, 0.96); }
}
@keyframes pt-stretch-l {
  0%, 100% { transform: rotate(0deg); }
  25%, 55% { transform: rotate(150deg); }
  40% { transform: rotate(158deg); }
}
@keyframes pt-stretch-r {
  0%, 100% { transform: rotate(0deg); }
  25%, 55% { transform: rotate(-150deg); }
  40% { transform: rotate(-158deg); }
}
.fx-yawn {
  top: -22px;
  left: 50%;
  transform: translateX(-50%);
  font-size: 10px;
  color: var(--text-2);
  white-space: nowrap;
  animation: fx-yawn-float 2.6s ease-in-out both;
}
@keyframes fx-yawn-float {
  0%, 30% { opacity: 0; transform: translateX(-50%) translateY(4px); }
  45%, 75% { opacity: 1; transform: translateX(-50%) translateY(-4px); }
  100% { opacity: 0; transform: translateX(-50%) translateY(-10px); }
}

/* 敬礼：右臂举到眉边定住再放下——备份/导出成功 */
.mascot-actor.act-salute .mascot-body {
  animation: pt-salute 1.7s ease-in-out;
  transform-origin: 50% 100%;
}
.mascot-actor.act-salute .pt-arm:nth-of-type(3) {
  transform-box: fill-box;
  transform-origin: top center;
  animation: pt-salute-r 1.7s ease-in-out;
}
@keyframes pt-salute {
  0%, 100% { transform: translateY(0) rotate(0deg) scale(1, 1); }
  25%, 75% { transform: translateY(-1px) rotate(0deg) scale(0.98, 1.03); }
}
@keyframes pt-salute-r {
  0%, 100% { transform: rotate(0deg); }
  22%, 78% { transform: rotate(145deg); }
  30% { transform: rotate(138deg); }
}
.fx-salute {
  top: -18px;
  right: -6px;
  color: #fbbf24;
  font-size: 12px;
  animation: mascot-float 1.7s ease-out both;
}

@media (prefers-reduced-motion: reduce) {
  .fx { display: none; }
  .confetti { display: none; }
  .snowflake { display: none; }
  .footprint { display: none; }
}
</style>
