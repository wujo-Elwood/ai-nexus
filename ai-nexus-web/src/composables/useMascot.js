import { reactive, ref, computed } from 'vue'

// =====================================================================
// 吉祥物全局单例：状态机 + 位置 + 对系统事件的反应
// 方案：docs/吉祥物系统方案.md
// 模式：hidden（关闭）/ idle（待机）/ walking（走动）/ cheering（开心）
//       sad（沮丧）/ sleeping（睡觉）
// =====================================================================

const W = 54   // 演员宽度
const H = 28   // 演员高度

// 响应式渲染状态
const state = reactive({
  mode: 'hidden',
  x: -200,
  y: -200,
  walkDur: 0,        // 本次移动的过渡时长（ms），0 = 瞬移
  pose: 'none',      // 工位姿势：none / push / carry
  action: 'none',    // 热点动作：none / jump / cheer / roll / spin / inspect
  speak: '',         // 说话气泡内容（空 = 不显示）
  dragging: false,   // 正在被拖拽
  shy: false,        // 用户打字中：背过身去
  confetti: false,   // 庆祝彩纸
  festival: '',      // 节日装饰：scarf / nose / 空
  overtime: false,   // 深夜加班模式：22 点后头顶咖啡 + 黑眼圈
  friday: false,     // 周五傍晚：拎包准备下班
  badge: '',         // 成就徽章文案（达标时显示 9 秒）
  starEyes: false,   // 庆祝星星眼：celebrate 期间眼睛变 ✦
  busy: false,       // 任务中心有 PROCESSING 任务：戴安全帽搬砖
  reading: false,    // 聊天回答流式输出中：盯着内容区读
  outfit: localStorage.getItem('mascot-outfit') || 'none',   // 换装：none/hat/glasses/crown
  skin: localStorage.getItem('mascot-skin') || 'classic',    // 花色：classic/gray/pink/cow
})

// —— 心情值：互动积累（点赞/投喂/成就/番茄钟加分，点踩/戳烦扣分），影响轮盘节奏、走路速度与台词 ——
const _moodRaw = parseInt(localStorage.getItem('mascot-mood') ?? '', 10)
const mood = ref(Number.isNaN(_moodRaw) ? 60 : _moodRaw)
function applyMood(delta) {
  mood.value = Math.max(0, Math.min(100, mood.value + delta))
  localStorage.setItem('mascot-mood', String(mood.value))
}

const enabled = ref(localStorage.getItem('mascot-enabled') !== '0')
const petTick = ref(0)   // 撸吉祥物的计数（驱动舞蹈动画）
const mascotName = ref(localStorage.getItem('mascot-name') || '小吉')   // 它的名字：起名后气泡会用来自称

// —— 内部量 ——
let homeRect = null        // 激活行站位（viewport 坐标）
let timers = {}            // 命名定时器
let actedAt = []           // cheer/sad 节流时间戳
let sleepTimer = null
let sweepTimer = null     // 睡前打扫阶段
let meditateTimer = null  // 睡前冥想阶段
let hiddenAt = 0          // 标签页离开时刻（回归探望用）
let shyTimer = null
let inputBound = false
let lastMouseMove = 0

// —— 生命周期：init 与 dispose 成对管理定时器和全局监听 ————————
let inited = false          // 已初始化标记：重复 init 直接返回，dispose 后复位
let initIntervals = []      // init 里注册的常驻轮询定时器 id（dispose 时统一清理）
let globalListeners = []    // 全局监听登记表：{ target, type, handler, options }
let dialogObserver = null   // Element Plus 弹窗观察器（dispose 时断开）

// 注册全局监听并登记：连匿名箭头函数也能靠引用精确解绑
function addGlobalListener(target, type, handler, options) {
  target.addEventListener(type, handler, options)
  globalListeners.push({ target, type, handler, options })
}

// 解绑所有已登记的全局监听（pointerdown / keydown / wheel / mousemove / visibilitychange / click / scroll / resize 等）
function removeGlobalListeners() {
  globalListeners.forEach(({ target, type, handler, options }) => {
    try {
      target.removeEventListener(type, handler, options)
    } catch (e) { /* 目标已销毁则忽略 */ }
  })
  globalListeners = []
}

function clearTimer(key) {
  if (timers[key]) {
    clearTimeout(timers[key])
    timers[key] = null
  }
}

function after(ms, fn, key) {
  clearTimer(key)
  timers[key] = setTimeout(fn, ms)
}

// 计算站位坐标：用户拖拽定居点优先，其次激活行右缘
const customHome = ref(null)

function loadCustomHome() {
  try { customHome.value = JSON.parse(localStorage.getItem('mascot-home') || 'null') } catch (e) { customHome.value = null }
}
function saveCustomHome(x, y) {
  customHome.value = { x, y }
  localStorage.setItem('mascot-home', JSON.stringify(customHome.value))
}
function clearCustomHome() {
  customHome.value = null
  localStorage.removeItem('mascot-home')
}

function homePos() {
  if (customHome.value) return { x: customHome.value.x, y: customHome.value.y }
  if (!homeRect) return null
  return { x: homeRect.right - W - 8, y: homeRect.bottom - H - 2 }
}

// —— 拖拽定居：抓住拖到任意位置放下 ——
let lastStruggleAt = 0
function beginDrag() {
  if (!enabled.value) return
  walkSeq++
  state.mode = 'idle'
  state.walkDur = 0
  state.dragging = true
  state.action = 'none'
  state.speak = ''
  const now = Date.now()
  if (now - lastStruggleAt > 20000) {   // 挣扎台词 20s 节流：常拖定居的人不被刷屏
    lastStruggleAt = now
    showSpeak('放开我！')
  }
}

function dragTo(x, y) {
  state.x = Math.max(8, Math.min(window.innerWidth - W - 8, x))
  state.y = Math.max(8, Math.min(window.innerHeight - H - 8, y))
}

function endDrag() {
  state.dragging = false
  if (state.x < 230) {
    clearCustomHome()   // 拖回侧栏 = 回家
  } else {
    saveCustomHome(state.x, state.y)
  }
}

let walkSeq = 0   // 步伐序号：新步伐启动后，旧步伐的完成定时器作废

// 摆放：walk=true 时多远都实打实走过去（步伐时长与距离成比例）
function place(x, y, walk) {
  if (walk && state.positioned !== false && state.x > -100) {
    walkSeq++
    const seq = walkSeq
    const dist = Math.max(Math.abs(x - state.x), Math.abs(y - state.y))
    state.walkDur = Math.min(1600, Math.max(560, Math.round(dist * 2.6 * (mood.value <= 30 ? 1.3 : 1))))   // 心情低落时蔫蔫慢走
    state.mode = 'walking'
    state.x = x
    state.y = y
    after(state.walkDur + 40, () => {
      // 只有最新一次步伐的完成回调才生效
      if (seq === walkSeq && state.mode === 'walking') {
        state.mode = 'idle'
        state.walkDur = 0
        maybeSlip()   // 走太急偶发打滑（低概率彩蛋）
      }
    }, 'walk')
  } else {
    state.walkDur = 0
    state.x = x
    state.y = y
    if (state.mode === 'walking') state.mode = 'idle'
  }
}

// —— 打滑彩蛋：走路偶发滑一跤（6% 概率 + 90s 冷却，睡觉/上班中不摔） ——
let lastSlipAt = 0
function maybeSlip() {
  if (!enabled.value || state.mode !== 'idle' || postTimer) return
  const now = Date.now()
  if (now - lastSlipAt < 90000 || Math.random() > 0.06) return
  lastSlipAt = now
  playAction('slip')
  sound('slip')
}

// —— 对外 API ——

// 同步家：MainLayout 在路由/菜单/滚动变化后传入激活行 rect
// 有常驻工位时忽略（吉祥物在页面上班，不在侧栏）
// ignoreCustom=true 仅用于刷新加载：一律回菜单栏，不恢复拖拽定居点（定居只在会话内有效）
function setHome(rect, ignoreCustom = false) {
  homeRect = rect
  if (!enabled.value) return
  if (state.mode === 'sleeping') return   // 睡觉时页面在背后变化，不醒
  if (state.mode === 'cheering' || state.mode === 'sad') return
  if (postTimer) return                    // 在工位上班中
  const pos = ignoreCustom && rect
    ? { x: rect.right - W - 8, y: rect.bottom - H - 2 }
    : homePos()
  if (pos) place(pos.x, pos.y, true)
}

// 开心：原地蹦两下冒星星
function cheer() {
  if (!enabled.value) return
  if (state.mode === 'sleeping') wake()
  if (state.mode !== 'idle') return
  const now = Date.now()
  actedAt = actedAt.filter(t => now - t < 60000)
  if (actedAt.length >= 4) return          // 每分钟最多 4 次
  actedAt.push(now)
  const delay = Math.round(Math.random() * 400)
  state.mode = 'cheering'
  after(1300, () => {
    if (state.mode === 'cheering') state.mode = 'idle'
  }, 'react')
  void delay
}

// 沮丧：趴下变灰
function sad() {
  if (!enabled.value) return
  if (state.mode === 'sleeping') wake()
  if (state.mode !== 'idle') return
  const now = Date.now()
  actedAt = actedAt.filter(t => now - t < 60000)
  if (actedAt.length >= 4) return
  actedAt.push(now)
  state.mode = 'sad'
  after(1500, () => {
    if (state.mode === 'sad') state.mode = 'idle'
  }, 'react')
}

// 睡觉
function sleep() {
  if (!enabled.value) return
  if (state.mode !== 'idle') return
  state.mode = 'sleeping'
  sound('sleep')
  scheduleDream()
}

// 说梦话：睡着后隔 6~13 秒嘟囔一句（尊重闲聊开关）
const DREAM_LINES = ['…zzz…做梦都在搬砖…', '…zz…别抢我的饼干…', '…zzz…明天也要元气满满…']
let dreamTimer = null
function scheduleDream() {
  if (dreamTimer) clearTimeout(dreamTimer)
  dreamTimer = setTimeout(() => {
    dreamTimer = null
    if (enabled.value && state.mode === 'sleeping') {
      if (chatOn.value && Math.random() < 0.6) showSpeak(DREAM_LINES[Math.floor(Math.random() * DREAM_LINES.length)])
      scheduleDream()
    }
  }, 6000 + Math.floor(Math.random() * 7000))
}

// 惊醒
function wake() {
  if (dreamTimer) { clearTimeout(dreamTimer); dreamTimer = null }
  if (state.mode === 'sleeping') state.mode = 'idle'
  restartSleepTimer()
}

// 撸吉祥物：驱动舞蹈动画（演员组件监听 petTick）
function pet() {
  if (!enabled.value) return
  if (state.mode === 'sleeping') wake()
  petTick.value++
  applyMood(2)   // 被撸心情小涨
  trackAchievement('pets')
}

// —— 页面工位 ————————————————————————————————
let postTimer = null      // 非 null = 正在工位上班
let postAnchor = null
let postRecook = null

// 上工：走到锚点旁站定并摆姿势
// side: 'right' 站锚点右侧 / 'left' 站左侧
function gotoPost(anchorEl, side, pose) {
  if (!enabled.value || !anchorEl || !anchorEl.isConnected) return
  const rect = anchorEl.getBoundingClientRect()
  const vh = window.innerHeight
  if (rect.top > vh - 40 || rect.bottom < 40) return   // 锚点不在视口，不上工
  postAnchor = anchorEl
  postSide = side
  postTimer = pose
  const x = side === 'left'
    ? Math.max(236, rect.left - W - 10)
    : Math.min(window.innerWidth - W - 24, rect.right + 10)
  const y = Math.min(vh - H - 8, Math.max(8, rect.bottom - H + 4))
  state.pose = pose
  state.mode = 'idle'
  place(x, y, true)   // 跨区移动：淡出淡入由演员组件处理（x 距离大时自然表现为走过去）
}

// 滚动/缩放时跟随锚点（瞬移，不走路）
function trackPost() {
  if (!postTimer || !postAnchor) return
  if (!postAnchor.isConnected) { leavePost(); return }
  const rect = postAnchor.getBoundingClientRect()
  const vh = window.innerHeight
  if (rect.top > vh - 40 || rect.bottom < 40) return
  const side = postSide
  state.x = side === 'left'
    ? Math.max(236, rect.left - W - 10)
    : Math.min(window.innerWidth - W - 24, rect.right + 10)
  state.y = Math.min(vh - H - 8, Math.max(8, rect.bottom - H + 4))
}
let postSide = 'right'

// 下班：清姿势走回侧栏家
function leavePost() {
  postTimer = null
  postAnchor = null
  state.pose = 'none'
  const pos = homePos()
  if (enabled.value && pos) place(pos.x, pos.y, true)
}

// 路由变化时由 MainLayout 调：有声明且锚点就绪 → 上工，否则下班
function applyRoutePost(routePath, retries = 6, arrive = true) {
  cancelTour(arrive)
  if (arrive) runTour(routePath)   // 入场巡逻已退役（切换菜单回菜单栏），仅当 arrive=true 才走
  if (!arrive) {   // 同步清工位状态：导航/刷新后 setHome 才能立刻重新摆放（清晚了会被 postTimer 守卫拦住）
    postTimer = null
    postAnchor = null
    state.pose = 'none'
  }
  import('./../utils/mascotPosts').then(({ POSTS, DEFAULT_HOTSPOTS }) => {
    hotspotList = (POSTS[routePath] && POSTS[routePath].hotspots) || DEFAULT_HOTSPOTS
    if (!arrive) return
    const post = POSTS[routePath]
    if (!post || !enabled.value) { leavePost(); return }
    // 等页面锚点渲染（跳转后 DOM 晚一拍）
    const tryFind = (left) => {
      const el = document.querySelector(post.anchor)
      if (el) {
        postSide = post.side
        postTimer = post.pose
        gotoPost(el, post.side, post.pose)
      } else if (left > 0) {
        setTimeout(() => tryFind(left - 1), 350)
      } else {
        leavePost()
      }
    }
    tryFind(retries)
  })
}

// —— 到站巡逻：先回菜单栏（走路 ≤1.6s），到站后再进内容区随机游玩 ——
let patrolTimer = null
function patrol(routePath) {
  if (patrolTimer) clearTimeout(patrolTimer)
  if (!enabled.value) return
  patrolTimer = setTimeout(() => {
    patrolTimer = null
    if (enabled.value) runTour(routePath)
  }, 2100)
}

// —— 入场巡逻：进页面后随机挑 3 个按钮/控件，随机动作逐一表演 ——
let tourSeq = 0
let tourTimers = []

// 巡逻候选控件池（全站通用，随机取样）
const TOUR_CANDIDATES = '.btn-primary, .el-button--primary, .el-button:not(.el-button--text):not(.el-button--link), .el-switch, .search, .el-upload-dragger, .el-tabs__item, .el-select, .el-table, .ledger, .el-form, .ghost-upload, .upload-zone, .mode-card, .kb-select, .agent-entry, .rebuild-strip, .trend-section, .stats-cards, .el-textarea'
const TOUR_ACTIONS = ['backflip', 'jump', 'cheer', 'roll', 'spin', 'inspect', 'nod', 'shake', 'pushups', 'moonwalk', 'idea', 'magic', 'sneeze', 'clap', 'juggle', 'fingerheart']

// 随机取样：全页随机挑 n 个互不重叠的可见控件（跨大区由 place() 淡出现身，不飞行）
function pickTourStops(n) {
  const all = [...document.querySelectorAll(TOUR_CANDIDATES)]
  const visible = all.filter((el) => {
    const r = el.getBoundingClientRect()
    return r.width > 30 && r.height > 16 && r.top > 40 && r.bottom < window.innerHeight - 30 && r.left > 236
  })
  const uniq = []
  for (const el of visible) {
    const r = el.getBoundingClientRect()
    if (!uniq.some((u) => {
      const ur = u.getBoundingClientRect()
      return Math.abs(ur.x - r.x) < 60 && Math.abs(ur.y - r.y) < 40
    })) uniq.push(el)
  }
  const picks = []
  const pool = uniq.slice()
  while (picks.length < n && pool.length) {
    picks.push(pool.splice(Math.floor(Math.random() * pool.length), 1)[0])
  }
  const acts = []
  for (let i = 0; i < picks.length; i++) {
    let a
    if (i === 0) {
      a = 'backflip'   // 首站固定翻跟头（随机抽取太难得见到）
    } else {
      a = TOUR_ACTIONS[Math.floor(Math.random() * TOUR_ACTIONS.length)]
      if (a === acts[i - 1]) a = TOUR_ACTIONS[(TOUR_ACTIONS.indexOf(a) + 1) % TOUR_ACTIONS.length]
    }
    acts.push(a)
  }
  return picks.map((el, i) => ({ el, action: acts[i] }))
}

function cancelTour(goHome = true) {
  tourSeq++
  tourTimers.forEach(clearTimeout)
  tourTimers = []
  if (state.action !== 'none') state.action = 'none'
  // 打断时如果正走向目标，恢复正常姿态并回家（goHome=false：刷新加载中，位置归 setHome 管，别被定居点抢走）
  if (goHome && state.mode === 'walking') {
    state.mode = 'idle'
    state.walkDur = 0
    const pos = homePos()
    if (pos) place(pos.x, pos.y, false)
  }
}

function runTour(routePath) {
  if (!enabled.value) return
  if (window.matchMedia && window.matchMedia('(prefers-reduced-motion: reduce)').matches) return
  // 慢节奏巡逻：随机 2 个控件，走到按钮旁站稳后原地做动作（动作在走路完成后才触发，
  // 否则翻转/旋转会和位移叠在一起，既不是原地特效也看不清），做完安静站 4 秒再走下一站
  setTimeout(() => {
    if (!enabled.value) return
    cancelTour()
    const seq = tourSeq
    const stops = pickTourStops(2)
    if (!stops.length) return
    let idx = 0
    const doStop = () => {
      if (seq !== tourSeq || !enabled.value) return
      // 巡逻结束回家
      if (idx >= stops.length) {
        const pos = homePos()
        if (pos && state.mode === 'idle') place(pos.x, pos.y, true)
        return
      }
      const st = stops[idx++]
      const el = st.el
      if (!el || !el.isConnected) { doStop(); return }
      const rect = el.getBoundingClientRect()
      if (rect.top > window.innerHeight - 60 || rect.bottom < 60) { doStop(); return }
      restartSleepTimer()
      const vw = window.innerWidth
      const side = rect.left > vw / 2 ? 'left' : 'right'
      const x = side === 'left'
        ? Math.max(236, rect.left - 64)
        : Math.min(vw - 62, rect.right + 10)
      const y = Math.min(window.innerHeight - 36, Math.max(8, rect.bottom - 26))
      state.pose = 'none'
      state.mode = 'idle'
      place(x, y, true)   // 实打实走过去
      const walkMs = (state.walkDur || 600) + 60
      // 走完站稳（mode 回 idle）才原地做动作
      tourTimers.push(setTimeout(() => {
        if (seq !== tourSeq || !enabled.value) return
        playAction(st.action)   // 走 playAction：音效与成就贯通
        const dur = ACTION_DUR[st.action] || 1000
        tourTimers.push(setTimeout(() => {
          if (seq === tourSeq && state.action === st.action) state.action = 'none'
          tourTimers.push(setTimeout(doStop, 4000))   // 安静站 4 秒再下一站
        }, dur))
      }, walkMs))
    }
    doStop()
  }, 1000)
}

// —— 动作轮盘：页面停留时间长时，把所有动作轮着做一遍 ————————
// 每隔 13~18 秒原地做一个动作；动作队列洗牌循环（一轮做完重新洗牌）
const WHEEL_ACTIONS = ['jump', 'cheer', 'roll', 'spin', 'inspect', 'nod', 'shake', 'backflip', 'wave', 'excited', 'hearteyes', 'lookaround', 'tiptoe', 'headstand', 'pushups', 'moonwalk', 'sign', 'idea', 'magic', 'meditate', 'type', 'sneeze', 'clap', 'juggle', 'fingerheart']
let wheelQueue = []
let wheelTimer = null

function shuffleQueue() {
  wheelQueue = WHEEL_ACTIONS.slice()
  for (let i = wheelQueue.length - 1; i > 0; i--) {
    const j = Math.floor(Math.random() * (i + 1))
    ;[wheelQueue[i], wheelQueue[j]] = [wheelQueue[j], wheelQueue[i]]
  }
}

function startWheel() {
  if (wheelTimer) return
  if (!wheelQueue.length) shuffleQueue()
  wheelTick()
}

function stopWheel() {
  if (wheelTimer) {
    clearTimeout(wheelTimer)
    wheelTimer = null
  }
}

function wheelTick() {
  // 轮盘节奏随心情：开心更活跃，低落懒洋洋且四成概率偷懒跳过
  const interval = mood.value >= 80 ? 8000 + Math.floor(Math.random() * 5000)
    : mood.value <= 30 ? 20000 + Math.floor(Math.random() * 8000)
      : 13000 + Math.floor(Math.random() * 5000)
  wheelTimer = setTimeout(() => {
    try {
      const dialogOpen = !![...document.querySelectorAll('.el-overlay')].find((o) => getComputedStyle(o).display !== 'none')
      const pageHidden = document.hidden
      const reducedMotion = window.matchMedia && window.matchMedia('(prefers-reduced-motion: reduce)').matches
      const tooSad = mood.value <= 30 && Math.random() < 0.4
      // 只有待机且没有正在播放的动作时才表演；睡觉/关闭/弹窗/读回答/减少动态/心情低谷期间静默跳过
      if (enabled.value && !pageHidden && !dialogOpen && !reducedMotion && !tooSad && state.mode === 'idle' && state.action === 'none' && !postTimer && !state.reading) {
        if (!wheelQueue.length) shuffleQueue()
        const a = wheelQueue.pop()
        playAction(a)   // 走 playAction：音效与翻跟头成就计数贯通
      }
    } catch (e) {
      console.error('mascot wheel error:', e)
    } finally {
      wheelTick()   // 无论发生什么，链路不断
    }
  }, interval)
}

// —— 成长系统：记住陪伴天数，里程碑打招呼 ——
function trackGrowth() {
  try {
    const today = new Date().toISOString().slice(0, 10)
    const raw = JSON.parse(localStorage.getItem('mascot-days') || '{"dates":[]}')
    if (!Array.isArray(raw.dates)) raw.dates = []
    if (!raw.dates.includes(today)) {
      raw.dates.push(today)
      if (raw.dates.length > 90) raw.dates = raw.dates.slice(-90)
      localStorage.setItem('mascot-days', JSON.stringify(raw))
      const n = raw.dates.length
      if (n >= 2) {
        setTimeout(() => {
          showSpeak('今天是陪你第 ' + n + ' 天！')
          state.action = 'backflip'
          setTimeout(() => { if (state.action === 'backflip') state.action = 'none' }, 1150)
        }, 2500)
      }
    }
  } catch (e) { /* 存储不可用忽略 */ }
}

// —— 节日装饰：日期命中即换装 ——
function festivalOf(d) {
  const dd = d || new Date()
  const m = dd.getMonth() + 1
  const day = dd.getDate()
  if ((m === 1 && day <= 15) || (m === 2 && day <= 4) || (m === 10 && day === 1)) return 'scarf'
  if (m === 12 && day >= 20) return 'nose'
  return localStorage.getItem('mascot-festival') || ''   // 开发期覆盖：mascot-festival=scarf/nose
}

// —— 庆祝：彩纸 + 三连翻转 ——
function celebrate() {
  if (!enabled.value) return
  if (state.mode === 'sleeping') wake()
  state.pose = 'none'
  state.speak = ''
  state.mode = 'idle'
  state.confetti = true
  state.starEyes = true
  applyMood(10)   // 达成成就心情大好
  sound('cheer')
  setTimeout(() => { state.confetti = false; state.starEyes = false }, 1900)
  const flips = [0, 1250, 2500]
  flips.forEach((d, i) => {
    setTimeout(() => {
      state.action = 'backflip'
      setTimeout(() => {
        if (state.action === 'backflip') state.action = 'none'
      }, 1150)
    }, d + (i === 2 ? 0 : 0))
  })
  setTimeout(() => { state.action = 'none' }, 3800)
}

// —— 说话气泡：偶尔冒一句真话（数据来自系统接口） ————
const STATIC_LINES = [
  '点我一下可以跳舞哦',
  '要不要来个后空翻？',
  '眨眼也是一种休息…',
  '这颗星球今天也很安静',
  '别忘了我可以帮你查数据'
]

let speakTimer = null
let speakHideTimer = null
let typeTimer = null   // 打字机逐字显现
const chatOn = ref(localStorage.getItem('mascot-chat') !== '0')   // 闲聊气泡独立开关（菜单可切，用户触发的反应台词不受影响）

function showSpeak(text) {
  if (!enabled.value) return
  if (speakHideTimer) clearTimeout(speakHideTimer)
  if (typeTimer) clearInterval(typeTimer)
  const chars = [...String(text)]
  let i = 0
  state.speak = ''
  typeTimer = setInterval(() => {
    i++
    state.speak = chars.slice(0, i).join('')
    if (i >= chars.length) { clearInterval(typeTimer); typeTimer = null }
  }, 45)
  speakHideTimer = setTimeout(() => {
    state.speak = ''
    if (typeTimer) { clearInterval(typeTimer); typeTimer = null }
  }, 3600 + chars.length * 45)
}

async function speakSmart() {
  if (!enabled.value || !chatOn.value || state.reading || document.hidden) return
  if (state.mode !== 'idle' || state.action !== 'none' || postTimer) return
  if ([...document.querySelectorAll('.el-overlay')].find((o) => getComputedStyle(o).display !== 'none')) return
  let line = null
  try {
    const which = Math.floor(Math.random() * 3)
    if (which === 0) {
      const res = await silentGet('/api/usage/stats')
      const t = res && res.data && res.data.today
      if (t && t.callCount != null) line = '今日已调用 ' + t.callCount + ' 次，托管给我吧'
    } else if (which === 1) {
      const res = await silentGet('/api/tasks?status=PROCESSING')
      const list = (res && res.data) || []
      line = list.length ? '还有 ' + list.length + ' 个任务在跑，我盯着呢' : '任务全部跑完啦，真清净'
      if (list.length) playAction('sign')   // 举牌应援
    } else {
      const res = await silentGet('/api/system-health/overview')
      const comps = (res && res.data && res.data.components) || []
      const bad = comps.find((c) => c.status === 'DOWN' || c.status === 'DEGRADED')
      line = bad ? ((bad.name || bad.component || '有个组件') + ' 不太舒服，我去看看') : '系统一切正常，放心用～'
    }
  } catch (e) { /* 接口失败则用静态台词 */ }
  if (!line) {
    if (mood.value <= 30) {
      // 低落专属台词
      const sad = ['有点想你夸我…', '今天有点蔫蔫的', '摸摸头会好一点吧…']
      line = sad[Math.floor(Math.random() * sad.length)]
    } else {
      const pool = [...STATIC_LINES, '叫我' + mascotName.value + '就行', '我是' + mascotName.value + '，这颗星球最忙的员工']
      if (mood.value >= 80) pool.push('心情好，看什么都顺眼！', '今天也是元气满满的一天！')
      line = pool[Math.floor(Math.random() * pool.length)]
    }
  }
  showSpeak(line)
}

function startSpeak() {
  if (speakTimer) return
  const tick = () => {
    speakTimer = setTimeout(() => {
      try { speakSmart() } catch (e) { /* 忽略 */ }
      tick()
    }, 20000 + Math.floor(Math.random() * 15000))
  }
  tick()
}

function stopSpeak() {
  if (speakTimer) { clearTimeout(speakTimer); speakTimer = null }
  if (speakHideTimer) { clearTimeout(speakHideTimer); speakHideTimer = null }
  if (typeTimer) { clearInterval(typeTimer); typeTimer = null }
  state.speak = ''
}

// 闲聊开关：关掉后不再定时冒台词，正在显示的台词立刻收回（气泡改为确认语）
function toggleChat() {
  chatOn.value = !chatOn.value
  localStorage.setItem('mascot-chat', chatOn.value ? '1' : '0')
  if (!chatOn.value) {
    if (speakHideTimer) { clearTimeout(speakHideTimer); speakHideTimer = null }
    if (typeTimer) { clearInterval(typeTimer); typeTimer = null }
    state.speak = ''
    showSpeak('好的，我安静点～')
  } else {
    showSpeak('嘿嘿，我又可以说话啦～')
  }
}

// —— 肚子饿：3 分钟没投喂 → 咕噜两声求投喂（尊重闲聊开关，10 分钟最多提醒一次） ——
let lastFedAt = Date.now()
let lastHungryAt = 0
function noteFed() {
  lastFedAt = Date.now()
  applyMood(6)   // 投喂是最大的善意
}
function nudgeHungry() {
  if (!enabled.value || !chatOn.value || state.reading || document.hidden) return
  if (state.mode !== 'idle' || state.action !== 'none' || postTimer) return
  if ([...document.querySelectorAll('.el-overlay')].find((o) => getComputedStyle(o).display !== 'none')) return
  const now = Date.now()
  if (now - lastFedAt < 180000 || now - lastHungryAt < 600000) return
  lastHungryAt = now
  sound('nom')
  setTimeout(() => sound('nom'), 160)   // 咕噜咕噜
  showSpeak('肚子有点饿了，双击投喂我一下？')
}

// —— 久用提醒：连续使用 75 分钟 → 伸懒腰劝休息（每 5 分钟检查，触发后重新计时） ——
const STRETCH_INTERVAL_MS = 75 * 60 * 1000
let lastStretchAt = Date.now()
function checkStretch() {
  if (!enabled.value || document.hidden) return
  if (state.mode !== 'idle' || state.action !== 'none' || postTimer || state.reading) return
  if ([...document.querySelectorAll('.el-overlay')].find((o) => getComputedStyle(o).display !== 'none')) return
  if (Date.now() - lastStretchAt < STRETCH_INTERVAL_MS) return
  lastStretchAt = Date.now()
  playAction('stretch')
  showSpeak('连续用很久啦，起来活动一下吧！')
}

// —— 后台轮询专用静默 GET：不走 axios 拦截器，无权限/断网时不弹全局错误 toast ——
async function silentGet(path) {
  try {
    const token = localStorage.getItem('token')
    const resp = await fetch(path, { headers: token ? { Authorization: 'Bearer ' + token } : {} })
    if (!resp.ok) return null
    return await resp.json()
  } catch (e) { return null }
}

// —— 任务完成播报：轮询任务中心，PROCESSING 数量下降 → 抛金币庆祝（事件播报，不受闲聊开关限制） ——
let prevProcessing = null
async function watchTasks() {
  if (!enabled.value || document.hidden) return
  const body = await silentGet('/api/tasks?status=PROCESSING')
  if (!body) return
  const n = (body.data || []).length
  const wasBusy = state.busy
  state.busy = n > 0
  if (!wasBusy && state.busy && state.mode === 'idle') {
    showSpeak('有任务在跑，我来盯梢搬砖！')
  }
  if (prevProcessing != null && n < prevProcessing && state.mode === 'idle') {
    playAction('juggle')
    showSpeak('任务跑完了，金币拿来！')
  }
  prevProcessing = n
}

// —— 健康告警：组件由好变坏那一刻 → 打电话催修（事件播报，不受闲聊开关限制） ——
// 普通用户无健康面板权限（403）：silentGet 返回 null，静默跳过不更新状态
let prevHealthBad = null
async function watchHealth() {
  if (!enabled.value || document.hidden) return
  const body = await silentGet('/api/system-health/overview')
  if (!body) return
  const comps = (body.data && body.data.components) || []
  const bad = comps.some((c) => c.status === 'DOWN' || c.status === 'DEGRADED')
  if (prevHealthBad === false && bad && state.mode === 'idle') {
    playAction('call')
    showSpeak('有组件出问题了，我打电话催修！')
  }
  prevHealthBad = bad
}

// —— 时段问候：进来按时间打一声招呼（一次性事件，不受闲聊开关限制） ——
function greetByHour() {
  const h = new Date().getHours()
  let line = '晚上好，我陪你加班'
  let act = 'wave'
  if (h >= 5 && h < 11) { line = '早上好！今天也要元气满满'; act = 'excited' }
  else if (h < 14) { line = '中午好，记得吃饭呀'; act = 'sip' }
  else if (h < 18) { line = '下午好，累了就歇会儿'; act = 'sign' }
  else if (h < 22) { line = '晚上好，我陪你加班'; act = 'wave' }
  else { line = '这么晚还没睡？早点休息呀'; act = 'sulk' }
  setTimeout(() => {
    if (!enabled.value || document.hidden) return
    if (state.mode !== 'idle') return
    showSpeak(line)
    playAction(act)
  }, 1500)
}

// —— 摸头呼噜：被摸得舒服时的咕噜两声 ——
function purr() {
  if (!enabled.value) return
  sound('nom')
  setTimeout(() => sound('nom'), 280)
}

// —— 读回答：聊天流式输出期间盯着内容区（轮盘/闲聊/饿肚子提醒暂停，读完恢复） ——
function startReading() {
  if (!enabled.value) return
  if (state.mode === 'sleeping') wake()
  state.reading = true
}
function stopReading() {
  state.reading = false
}

// —— 番茄钟：陪用户专注 25 分钟，每 5 分钟报一次剩余，到点拉庆祝；再点一次菜单可取消 ——
const POMODORO_MS = 25 * 60 * 1000
const pomoOn = ref(false)
let pomoTimer = null
let pomoTickTimer = null
let pomoEndAt = 0

function pomoLeftText() {
  const left = Math.max(0, pomoEndAt - Date.now())
  const m = Math.floor(left / 60000)
  const s = Math.floor((left % 60000) / 1000)
  return m > 0 ? m + ' 分 ' + s + ' 秒' : s + ' 秒'
}

function schedulePomoTick() {
  if (pomoTickTimer) clearTimeout(pomoTickTimer)
  pomoTickTimer = setTimeout(() => {
    if (!pomoOn.value) return
    if (!enabled.value) { togglePomodoro(); return }
    if (state.mode === 'idle' && state.action === 'none' && !document.hidden) {
      showSpeak('专注还剩 ' + pomoLeftText())
      playAction('sign')
    }
    schedulePomoTick()
  }, 5 * 60 * 1000)
}

function finishPomodoro() {
  pomoOn.value = false
  if (pomoTickTimer) { clearTimeout(pomoTickTimer); pomoTickTimer = null }
  if (!enabled.value) return
  applyMood(5)   // 专注完成心情好
  celebrate()
  showSpeak('番茄钟完成！休息 5 分钟，看看远处吧～')
}

function togglePomodoro() {
  if (pomoOn.value) {
    pomoOn.value = false
    if (pomoTimer) { clearTimeout(pomoTimer); pomoTimer = null }
    if (pomoTickTimer) { clearTimeout(pomoTickTimer); pomoTickTimer = null }
    showSpeak('番茄钟取消啦，随时再叫我')
    return
  }
  if (!enabled.value) return
  if (state.mode === 'sleeping') wake()
  pomoEndAt = Date.now() + POMODORO_MS
  pomoOn.value = true
  playAction('meditate')
  showSpeak('好，陪你专注 25 分钟，别偷懒哦')
  schedulePomoTick()
  pomoTimer = setTimeout(finishPomodoro, POMODORO_MS)
}

// —— 换装：菜单循环切换便装/鸭舌帽/黑框眼镜/金皇冠，localStorage 记住 ——
const OUTFIT_LABEL = { none: '便装', hat: '鸭舌帽', glasses: '黑框眼镜', shades: '墨镜', crown: '金皇冠' }
const OUTFITS = Object.keys(OUTFIT_LABEL)
function cycleOutfit() {
  const i = OUTFITS.indexOf(state.outfit)
  state.outfit = OUTFITS[(i + 1) % OUTFITS.length]
  localStorage.setItem('mascot-outfit', state.outfit)
  showSpeak(state.outfit === 'none' ? '换回便装，轻便！' : '换上' + OUTFIT_LABEL[state.outfit] + '，好看吗？')
  queuePrefsSync()   // 偏好跟随账号
}

// —— 换花色：菜单循环切换皮肤颜色，localStorage 记住（颜色映射全局共享：演员与右上角头像同色） ——
export const SKIN_COLORS = { classic: '#dd886c', gray: '#9aa3b2', pink: '#e89aae', cow: '#f5f2ec' }
const SKIN_LABEL = { classic: '经典橘', gray: '灰蓝', pink: '樱花粉', cow: '奶牛' }
const skinColor = computed(() => SKIN_COLORS[state.skin] || SKIN_COLORS.classic)
function cycleSkin() {
  const keys = Object.keys(SKIN_LABEL)
  const i = keys.indexOf(state.skin)
  state.skin = keys[(i + 1) % keys.length]
  localStorage.setItem('mascot-skin', state.skin)
  showSpeak('换成' + SKIN_LABEL[state.skin] + '皮肤！')
  queuePrefsSync()   // 偏好跟随账号：换地址/换设备登录也能恢复
}

// —— 偏好跟随账号持久化：localStorage 按浏览器源隔离，换地址/清缓存/换设备都会丢，
//    所以换花色/换装/起名后静默同步到后端 sys_user.mascot_prefs，登录后拉取恢复 ——
let prefsSyncTimer = null
function queuePrefsSync() {
  if (prefsSyncTimer) clearTimeout(prefsSyncTimer)
  prefsSyncTimer = setTimeout(() => {
    prefsSyncTimer = null
    silentPut('/api/user/mascot-prefs', { skin: state.skin, outfit: state.outfit, name: mascotName.value })
  }, 800)
}

async function silentPut(path, body) {
  try {
    const token = localStorage.getItem('token')
    if (!token) return
    await fetch(path, {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json', Authorization: 'Bearer ' + token },
      body: JSON.stringify(body)
    })
  } catch (e) { /* 静默：偏好同步失败不打扰用户 */ }
}

// 从服务端拉取偏好并应用（登录后/启动时调用；后端值优先，未设置过的字段保持本地值）
async function applyServerPrefs() {
  const body = await silentGet('/api/user/profile')
  const raw = body && body.data && body.data.mascotPrefs
  if (!raw) return
  let prefs = null
  try { prefs = JSON.parse(raw) } catch (e) { return }
  if (!prefs) return
  if (prefs.skin && SKIN_COLORS[prefs.skin] && state.skin !== prefs.skin) {
    state.skin = prefs.skin
    localStorage.setItem('mascot-skin', prefs.skin)
  }
  if (prefs.outfit && OUTFIT_LABEL[prefs.outfit] && state.outfit !== prefs.outfit) {
    state.outfit = prefs.outfit
    localStorage.setItem('mascot-outfit', prefs.outfit)
  }
  if (prefs.name) {
    const clean = String(prefs.name).trim().slice(0, 8)
    if (clean && mascotName.value !== clean) {
      mascotName.value = clean
      localStorage.setItem('mascot-name', clean)
    }
  }
}

// —— 热点互动：点击页面控件 → 原地做对应动作 ——————————
let hotspotList = []
let reactTimes = []

const ACTION_DUR = { jump: 1150, cheer: 1300, roll: 950, spin: 850, inspect: 1300, nod: 900, shake: 1000, backflip: 1100, wave: 1100, excited: 900, hearteyes: 1400, lookaround: 1200, tiptoe: 1300, headstand: 1400, eat: 1300, pushups: 2400, moonwalk: 1500, slip: 1700, sign: 2100, idea: 1400, magic: 1900, peek: 1900, meditate: 3200, sip: 1900, sulk: 2100, goodbye: 1900, type: 2600, sneeze: 1500, clap: 1400, juggle: 1800, call: 2600, fingerheart: 1600, shrug: 1500, cover: 1700, draw: 2200, stretch: 2600, salute: 1700 }

// —— 音效：Kenney「Interface Sounds」CC0 音频文件（public/sounds/<音效名>.ogg，可换同音效名的文件）——
// 播放失败（浏览器不支持 ogg / 文件缺失）时回退到 WebAudio 8-bit 合成；localStorage 可关
let audioCtx = null
const SOUND_ON = () => localStorage.getItem('mascot-sound') !== '0'
const soundOn = ref(SOUND_ON())   // 响应式镜像：菜单标签据此显示当前开关状态
const soundPool = {}   // 音效名 -> Audio 元素池（同一音效需要重叠播放时补充新元素）

function playSoundFile(name) {
  try {
    const pool = soundPool[name] || (soundPool[name] = [])
    let audio = pool.find(a => a.ended || a.paused)
    if (!audio) {
      audio = new Audio('/sounds/' + name + '.ogg')
      audio.volume = 0.45
      pool.push(audio)
    }
    audio.currentTime = 0
    if (audio.play) {
      audio.play().catch(() => {
        // 播放失败（文件缺失/格式不支持/被自动播放策略拦截）：弃用该元素并回退合成
        const i = pool.indexOf(audio)
        if (i >= 0) pool.splice(i, 1)
        playSynth(name)
      })
    }
    return true
  } catch (e) {
    return false
  }
}

// 8-bit 合成兜底：方波小旋律（hum* 为空闲哼歌小调）
const SYNTH_TONES = {
  flip: [[520, 0, .08], [760, .09, .08], [980, .18, .12]],
  cheer: [[523, 0, .09], [659, .1, .09], [784, .2, .16]],
  nom: [[220, 0, .06], [170, .09, .07]],
  hop: [[440, 0, .05], [660, .06, .06]],
  slip: [[320, 0, .12], [160, .13, .22]],
  sleep: [[420, 0, .18], [310, .2, .18], [210, .4, .28]],
  magic: [[880, 0, .06], [1100, .07, .06], [1320, .14, .14]],
  idea: [[660, 0, .07], [880, .08, .1]],
  hum1: [[523, 0, .12], [659, .14, .12], [784, .28, .14], [659, .44, .12], [523, .58, .22]],
  hum2: [[392, 0, .12], [440, .14, .12], [494, .28, .12], [523, .42, .26]],
  hum3: [[659, 0, .09], [587, .11, .09], [523, .22, .09], [587, .33, .09], [659, .44, .18]],
}
function playSynth(name) {
  try {
    audioCtx = audioCtx || new (window.AudioContext || window.webkitAudioContext)()
    if (audioCtx.state === 'suspended') audioCtx.resume()
    const t0 = audioCtx.currentTime
    ;(SYNTH_TONES[name] || []).forEach(([freq, delay, dur]) => {
      const o = audioCtx.createOscillator()
      const g = audioCtx.createGain()
      o.type = 'square'
      o.frequency.value = freq
      g.gain.setValueAtTime(0.035, t0 + delay)
      g.gain.exponentialRampToValueAtTime(0.001, t0 + delay + dur)
      o.connect(g).connect(audioCtx.destination)
      o.start(t0 + delay)
      o.stop(t0 + delay + dur + 0.02)
    })
  } catch (e) { /* 无音频环境忽略 */ }
}
function sound(name) {
  if (!SOUND_ON()) return
  // 文件音效（SOUND_NAMES 登记的）优先走文件；其余（如哼歌旋律）直接走合成器
  if (SOUND_NAMES.includes(name) && playSoundFile(name)) return
  playSynth(name)
}

// 音效预热：首次交互后把 8 个音频元素建好并入池，消除首次播放的加载/解码延迟
const SOUND_NAMES = ['flip', 'cheer', 'nom', 'hop', 'slip', 'sleep', 'magic', 'idea', 'coin', 'call']
function warmupSounds() {
  SOUND_NAMES.forEach((name) => {
    if (soundPool[name] && soundPool[name].length) return
    try {
      const audio = new Audio('/sounds/' + name + '.ogg')
      audio.volume = 0.45
      audio.preload = 'auto'
      audio.load()
      soundPool[name] = [audio]
    } catch (e) { /* 无音频环境忽略 */ }
  })
}
function toggleSound() {
  const next = SOUND_ON() ? '0' : '1'
  localStorage.setItem('mascot-sound', next)
  soundOn.value = next === '1'
  showSpeak(next === '1' ? '音效已开启♪' : '音效已关，安静模式')
  if (next === '1') sound('cheer')
}

// —— 成就系统：摸头 / 看翻跟头 / 陪伴天数，达标播报一次并戴勋章 ——
const ACH_GOALS = [
  { key: 'pets10', label: '撸它 10 次', test: (a) => a.pets >= 10 },
  { key: 'pets50', label: '撸它 50 次', test: (a) => a.pets >= 50 },
  { key: 'flips5', label: '看它翻 5 个跟头', test: (a) => a.flips >= 5 },
  { key: 'flips20', label: '看它翻 20 个跟头', test: (a) => a.flips >= 20 },
  { key: 'days3', label: '陪伴满 3 天', test: (a) => a.days >= 3 },
  { key: 'days7', label: '陪伴满 7 天', test: (a) => a.days >= 7 },
]
function trackAchievement(field) {
  try {
    const raw = JSON.parse(localStorage.getItem('mascot-ach') || '{"pets":0,"flips":0,"unlocked":[]}')
    if (field) raw[field] = (raw[field] || 0) + 1
    raw.days = (JSON.parse(localStorage.getItem('mascot-days') || '{"dates":[]}')).dates.length || 0
    const hit = ACH_GOALS.find((g) => !raw.unlocked.includes(g.key) && g.test(raw))
    localStorage.setItem('mascot-ach', JSON.stringify(raw))
    if (hit) {
      raw.unlocked.push(hit.key)
      localStorage.setItem('mascot-ach', JSON.stringify(raw))
      state.badge = hit.label
      setTimeout(() => { if (state.badge === hit.label) state.badge = '' }, 9000)
      celebrate()
      setTimeout(() => showSpeak('成就达成：' + hit.label + '！'), 500)
    }
  } catch (e) { /* 存储不可用忽略 */ }
}

// 播放一个动作并到点自动复位；未知动作忽略（点击轮换与调试钩子共用）
function playAction(a) {
  if (!ACTION_DUR[a]) return false
  state.action = a
  if (a === 'backflip') { sound('flip'); trackAchievement('flips') }
  else if (a === 'jump') sound('hop')
  else if (a === 'magic') sound('magic')
  else if (a === 'idea') sound('idea')
  else if (a === 'sneeze') setTimeout(() => sound('nom'), 600)   // 喷嚏爆发那一下配个 pop
  else if (a === 'clap') { setTimeout(() => sound('nom'), 260); setTimeout(() => sound('nom'), 810) }   // 对拍两轮，双 pop 当掌声
  else if (a === 'juggle') setTimeout(() => sound('coin'), 300)   // 金币抛到最高点叮一声
  else if (a === 'call') { sound('call'); setTimeout(() => sound('call'), 420); setTimeout(() => sound('call'), 840) }   // 嘟…嘟…嘟
  else if (a === 'fingerheart') setTimeout(() => sound('nom'), 500)   // 比心成形那一下"啵"
  else if (a === 'draw') setTimeout(() => sound('magic'), 450)   // 画框亮相那一下配个魔法音
  else if (a === 'salute') setTimeout(() => sound('cheer'), 300)   // 敬礼定位那一下来一声短促欢呼
  setTimeout(() => { if (state.action === a) state.action = 'none' }, ACTION_DUR[a])
  return true
}

// 声明式接线：不用改任何页面模板，点击委托按选择器匹配
function bindHotspots() {
  addGlobalListener(document, 'click', (e) => {
    if (!hotspotList.length || !enabled.value) return
    if (e.target.closest && e.target.closest('.mascot-actor')) return
    for (const h of hotspotList) {
      const el = e.target.closest && e.target.closest(h.selector)
      if (el) { reactTo(el, h.action); break }
    }
  }, true)
}

function reactTo(el, action) {
  if (!ACTION_DUR[action]) return
  if ([...document.querySelectorAll('.el-overlay')].find((o) => getComputedStyle(o).display !== 'none')) return
  if (state.mode === 'sleeping') wake()          // 点热点 = 用户活动，先惊醒
  if (state.mode !== 'idle' && state.mode !== 'walking') return
  const now = Date.now()
  reactTimes = reactTimes.filter(t => now - t < 60000)
  if (reactTimes.length >= 6) return          // 每分钟最多 6 次
  reactTimes.push(now)
  const rect = el.getBoundingClientRect()
  const vw = window.innerWidth
  const side = rect.left > vw / 2 ? 'left' : 'right'
  const x = side === 'left'
    ? Math.max(236, rect.left - 64)
    : Math.min(vw - 62, rect.right + 10)
  const y = Math.min(window.innerHeight - 36, Math.max(8, rect.bottom - 26))
  state.pose = 'none'
  state.mode = 'idle'
  place(x, y, true)   // 走到按钮旁
  playAction(action)  // 走 playAction：音效贯通
  after(ACTION_DUR[action], () => {
    if (state.action === action) state.action = 'none'
  }, 'react')
}

// 弹窗避让：Element Plus 弹窗打开 → 下班回家（body 挂 el-popup-parent--hidden 时）
function watchDialogs() {
  // 观察器保存到模块级变量，dispose 时才能 disconnect
  dialogObserver = new MutationObserver(() => {
    const dialogOpen = !!document.querySelector('.el-overlay:not([style*="display: none"])')
    if (dialogOpen && postTimer) {
      leavePost()
      if (state.mode === 'idle' || state.mode === 'walking') {
        const pos = homePos()
        if (pos) place(pos.x, pos.y, false)
      }
    }
  })
  dialogObserver.observe(document.body, { childList: true, subtree: true })
}

// 滚动/缩放跟随
function bindPostTracking() {
  addGlobalListener(window, 'scroll', trackPost, { passive: true, capture: true })
  addGlobalListener(window, 'resize', trackPost, { passive: true })
}

// —— 起名：菜单里改名（≤8 字），气泡会用新名字自称 ——
function rename(newName) {
  const clean = String(newName || '').trim().slice(0, 8)
  if (!clean || clean === mascotName.value) return false
  mascotName.value = clean
  localStorage.setItem('mascot-name', clean)
  if (state.mode === 'sleeping') wake()
  playAction('cheer')
  showSpeak('以后叫我「' + clean + '」吧！')
  queuePrefsSync()   // 偏好跟随账号
  return true
}

// 开关
function toggle() {
  enabled.value = !enabled.value
  localStorage.setItem('mascot-enabled', enabled.value ? '1' : '0')
  if (!enabled.value) {
    postTimer = null
    postAnchor = null
    state.pose = 'none'
    state.action = 'none'
    stopWheel()
    stopSpeak()
    if (dreamTimer) { clearTimeout(dreamTimer); dreamTimer = null }
    pomoOn.value = false
    if (pomoTimer) { clearTimeout(pomoTimer); pomoTimer = null }
    if (pomoTickTimer) { clearTimeout(pomoTickTimer); pomoTickTimer = null }
    state.dragging = false
    state.shy = false
    state.confetti = false
    state.starEyes = false
    state.mode = 'hidden'
  } else {
    state.mode = 'idle'
    const pos = homePos()
    if (pos) place(pos.x, pos.y, false)
    startWheel()   // 重新开启时必须重启轮盘（否则动作循环永久停摆）
    startSpeak()
  }
}

// —— 睡眠计时：45s 打扫 → 60s 冥想 → 63s 入睡（任何输入重置全链） ——
function restartSleepTimer() {
  if (sleepTimer) clearTimeout(sleepTimer)
  if (sweepTimer) clearTimeout(sweepTimer)
  if (meditateTimer) clearTimeout(meditateTimer)
  sweepTimer = setTimeout(() => {
    if (enabled.value && state.mode === 'idle' && !postTimer) playAction('sweep')
  }, 45000)
  meditateTimer = setTimeout(() => {
    if (enabled.value && state.mode === 'idle' && !postTimer) playAction('meditate')
  }, 60000)
  sleepTimer = setTimeout(() => { if (state.mode === 'idle') sleep() }, 63000)
}

function bindInput() {
  if (inputBound) return
  inputBound = true
  const onActivity = (e) => {
    if (state.mode === 'sleeping') wake()
    restartSleepTimer()
    // 巡逻打断：仅当用户在输入框打字时（普通按键如关闭启动层不打断）
    if (tourTimers.length && e && e.type === 'keydown') {
      const t = e.target
      if (t && (t.tagName === 'INPUT' || t.tagName === 'TEXTAREA' || t.isContentEditable)) {
        cancelTour()
      }
    }
    // 打字背身：用户在输入框打字时背过身去（隐私礼仪），停手 1.6 秒转回来
    if (e && e.type === 'keydown') {
      const t = e.target
      if (t && (t.tagName === 'INPUT' || t.tagName === 'TEXTAREA' || t.isContentEditable)) {
        state.shy = true
        if (shyTimer) clearTimeout(shyTimer)
        shyTimer = setTimeout(() => { state.shy = false }, 1600)
      }
    }
  }
  // mousemove 单独命名，登记后才能解绑
  const onMouseMove = () => {
    const now = Date.now()
    if (now - lastMouseMove > 1000) {
      lastMouseMove = now
      onActivity()
    }
  }
  // 第1步：统一走登记式绑定，dispose 时才能逐条 removeEventListener
  addGlobalListener(window, 'pointerdown', onActivity, { passive: true })
  addGlobalListener(window, 'keydown', onActivity, { passive: true })
  addGlobalListener(window, 'wheel', onActivity, { passive: true })
  addGlobalListener(window, 'mousemove', onMouseMove, { passive: true })
}

// —— 回归探望：切走标签页超过 8s 再回来 → 走到屏幕右缘探头张望 ——
function bindVisibility() {
  addGlobalListener(document, 'visibilitychange', () => {
    if (document.hidden) { hiddenAt = Date.now(); return }
    if (!enabled.value || !hiddenAt || Date.now() - hiddenAt < 8000) return
    hiddenAt = 0
    if (state.mode !== 'idle' || postTimer) return
    wake()
    const hp = homePos()
    const y = Math.min(window.innerHeight - 40, Math.max(60, hp ? hp.y : 200))
    place(window.innerWidth - 26, y, true)   // 半个身子藏到屏幕右缘外
    setTimeout(() => {
      if (state.mode === 'idle' && enabled.value) playAction('peek')
      setTimeout(() => {
        const pos = homePos()
        if (enabled.value && state.mode === 'idle' && pos) place(pos.x, pos.y, true)
      }, 2300)
    }, (state.walkDur || 600) + 80)
  })
}

// —— 加班模式：22 点~6 点头顶咖啡+黑眼圈；周五 17 点后拎包 ——
function updateOvertime() {
  const d = new Date()
  const h = d.getHours()
  state.overtime = h >= 22 || h < 6
  state.friday = d.getDay() === 5 && h >= 17
}

// —— 哼歌：空闲时偶尔哼一小段 8-bit 旋律（合成器现成，3~5 分钟一次，心情低落时不哼） ——
let humTimer = null
function scheduleHum() {
  if (humTimer) clearTimeout(humTimer)
  humTimer = setTimeout(() => {
    if (enabled.value && mood.value > 30 && state.mode === 'idle' && state.action === 'none' && !postTimer && !state.reading && !document.hidden) {
      const tunes = ['hum1', 'hum2', 'hum3']
      sound(tunes[Math.floor(Math.random() * tunes.length)])
      if (chatOn.value && Math.random() < 0.5) showSpeak('♪～')
    }
    scheduleHum()
  }, 180000 + Math.floor(Math.random() * 120000))
}

// —— ElMessage 钩子：成功/失败自动反应（业务代码零改动） ——
// 关键词分流：导出/备份成功 → 敬礼；其余成功 → 欢呼
function msgTextOf(args) {
  try {
    return args.map((a) => {
      if (typeof a === 'string') return a
      if (a && typeof a === 'object') return String(a.message || a.title || '')
      return ''
    }).join(' ')
  } catch (e) { return '' }
}

function patchElMessage() {
  import('element-plus').then((mod) => {
    const { ElMessage, ElMessageBox } = mod
    if (ElMessage.__mascotPatched) return
    const success = ElMessage.success.bind(ElMessage)
    const error = ElMessage.error.bind(ElMessage)
    ElMessage.success = (...args) => {
      const r = success(...args)
      if (/导出|备份/.test(msgTextOf(args))) playAction('salute')
      else cheer()
      return r
    }
    ElMessage.error = (...args) => {
      const r = error(...args)
      sad()
      return r
    }
    ElMessage.__mascotPatched = true
    // 删除类确认框：弹出瞬间它捂眼不敢看（本地反应，不影响弹窗本身）
    if (ElMessageBox && !ElMessageBox.__mascotPatched) {
      const confirm = ElMessageBox.confirm.bind(ElMessageBox)
      ElMessageBox.confirm = (...args) => {
        if (/删除|清空/.test(msgTextOf(args))) {
          if (state.mode === 'sleeping') wake()
          if (state.mode === 'idle' || state.mode === 'cheering') playAction('cover')
        }
        return confirm(...args)
      }
      ElMessageBox.__mascotPatched = true
    }
  })
}

// 初始化：输入监听 + 睡眠计时 + 消息钩子
function init() {
  // 已初始化过就直接返回：重复 init 会叠出一堆全局监听和定时器
  if (inited) return
  inited = true
  // 先用本地存储对齐外观：登出（user.js 的 logout）已清掉 mascot-* 键，
  // 重登时这里回落到默认值，避免气泡里还挂着上一个账号起的名字/花色/换装
  mascotName.value = localStorage.getItem('mascot-name') || '小吉'
  state.skin = localStorage.getItem('mascot-skin') || 'classic'
  state.outfit = localStorage.getItem('mascot-outfit') || 'none'
  bindInput()
  addGlobalListener(window, 'pointerdown', warmupSounds, { once: true })
  restartSleepTimer()
  patchElMessage()
  watchDialogs()
  bindPostTracking()
  bindHotspots()
  startWheel()   // 动作轮盘：停留期间轮着做所有动作
  startSpeak()   // 说话气泡：偶尔冒一句真话
  loadCustomHome()
  state.festival = festivalOf()
  trackGrowth()  // 成长系统：陪伴天数
  bindVisibility()   // 回归探望：切走标签页再回来探头张望
  updateOvertime()   // 加班模式：深夜咖啡+黑眼圈 / 周五拎包
  applyServerPrefs()   // 偏好跟随账号：登录态下从服务端恢复花色/换装/名字
  greetByHour()   // 时段问候：按时间打一声招呼
  // 常驻轮询定时器统一登记到 initIntervals，dispose 时逐条 clearInterval
  initIntervals.push(
    setInterval(updateOvertime, 60000),
    setInterval(nudgeHungry, 60000),   // 肚子饿检查：3 分钟没投喂提醒一次
    setInterval(checkStretch, 5 * 60 * 1000),   // 久用检查：连续 75 分钟伸懒腰劝休息
    setInterval(watchTasks, 45000),   // 任务完成监听：PROCESSING 数量下降就庆祝
    setInterval(watchHealth, 90000)   // 健康告警监听：组件由好变坏就打电话
  )
  scheduleHum()   // 哼歌：空闲时偶尔哼一小段 8-bit 旋律
  if (import.meta.env.DEV) {
    window.$mascot = mascot   // 开发期手动验证：$mascot.cheer() / sleep() / sad()
    // 开发期强制触发动作（验证特效用，生产构建不含）
    window.$mascot._forceAction = (a) => { playAction(a) }
    // 开发期调试探针：查看轮盘内部状态
    window.$mascot._debug = () => ({
      wheelAlive: !!wheelTimer,
      queueLen: wheelQueue.length,
      postTimer: postTimer,
      enabled: enabled.value,
      mode: state.mode,
      action: state.action,
      hidden: document.hidden,
      dialog: !!document.querySelector('.el-overlay')
    })
  }
}

// 清理：登出（MainLayout 卸载）时调用，收回 init 注册的所有定时器与全局监听。
// 不改变 state 的外观字段，只停掉后台行为，下次 init 可以正常重建
function dispose() {
  inited = false
  // 第1步：清掉 init 注册的 5 个常驻轮询（45s 任务 / 90s 健康 / 60s 加班与饿肚子 / 5min 久用）
  initIntervals.forEach((id) => clearInterval(id))
  initIntervals = []
  // 第2步：停掉动作轮盘与说话气泡的循环链
  stopWheel()
  stopSpeak()
  // 第3步：清掉睡眠三段计时、哼歌、番茄钟、说梦话、巡逻、偏好同步等一次性定时器
  const oneShotTimers = [
    sleepTimer, sweepTimer, meditateTimer, shyTimer, humTimer,
    pomoTimer, pomoTickTimer, dreamTimer, patrolTimer, prefsSyncTimer
  ]
  oneShotTimers.forEach((id) => { if (id) clearTimeout(id) })
  sleepTimer = null
  sweepTimer = null
  meditateTimer = null
  shyTimer = null
  humTimer = null
  pomoTimer = null
  pomoTickTimer = null
  dreamTimer = null
  patrolTimer = null
  prefsSyncTimer = null
  // 第4步：清掉入场巡逻的链式定时器与 after() 命名定时器（不回家，卸载中不改位置状态）
  cancelTour(false)
  Object.keys(timers).forEach((key) => clearTimer(key))
  // 第5步：解绑全局监听（pointerdown / keydown / wheel / mousemove / visibilitychange / click / scroll / resize）
  removeGlobalListeners()
  inputBound = false   // 复位输入监听标记，下次 init 才能重新绑定
  // 第6步：断开 Element Plus 弹窗观察器，并复位"读回答"标记
  if (dialogObserver) {
    dialogObserver.disconnect()
    dialogObserver = null
  }
  state.reading = false
}

export const mascot = {
  state,
  enabled,
  petTick,
  name: mascotName,
  rename,
  setHome,
  applyRoutePost,
  leavePost,
  workAt: gotoPost,
  speak: showSpeak,
  beginDrag,
  dragTo,
  endDrag,
  celebrate,
  patrol,
  playAction,
  toggleSound,
  soundOn,
  toggleChat,
  chatOn,
  noteFed,
  purr,
  startReading,
  stopReading,
  watchTasks,
  watchHealth,
  togglePomodoro,
  pomoOn,
  cycleOutfit,
  outfitLabel: OUTFIT_LABEL,
  cycleSkin,
  skinLabel: SKIN_LABEL,
  skinColor,
  mood,
  applyMood,
  cheer,
  sad,
  sleep,
  wake,
  pet,
  applyServerPrefs,
  toggle,
  init,
  dispose
}
