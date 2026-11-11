<template>
  <div class="app-container console-shell">
    <div class="cosmic-background" aria-hidden="true">
      <span class="star-field star-field-far"></span>
      <span class="star-field star-field-middle"></span>
      <span class="star-field star-field-near"></span>
      <span
        v-for="star in twinkleStars"
        :key="star.id"
        class="twinkle-star"
        :style="star.style"
      ></span>
      <span
        v-for="meteor in meteors"
        :key="meteor.id"
        class="meteor"
        :style="meteor.style"
      ></span>
    </div>
    <section class="console-frame">
      <div class="console-body">
        <!-- 目录栏：编号 + 组标签 + 细线分组（年鉴目录页语法） -->
        <aside class="side-nav" @scroll="syncMascotHome()">
          <div class="brand">
            <img class="brand-logo" src="/logo.png" alt="AI NEXUS" />
            <small class="brand-sub">AI NEXUS</small>
          </div>

          <div class="nav-group">
            <div class="nav-cap">核心</div>
            <router-link
              v-for="item in primaryItems"
              :key="item.path"
              :to="item.path"
              class="side-item"
              :class="{ active: isActiveRoute(item.path) }"
              @click="onMenuClick($event, item)"
            >
              <i class="side-no">{{ itemNo(item) }}</i>
              <span>{{ menuItemLabel(item) }}</span>
            </router-link>
          </div>

          <div class="nav-group">
            <div class="nav-cap">管理</div>
            <router-link
              v-for="item in secondaryItems"
              :key="item.path"
              :to="item.path"
              class="side-item"
              :class="{ active: isActiveRoute(item.path) }"
              @click="onMenuClick($event, item)"
            >
              <i class="side-no">{{ itemNo(item) }}</i>
              <span>{{ menuItemLabel(item) }}</span>
            </router-link>
          </div>

          <div class="nav-group">
            <div class="nav-cap">展厅</div>
            <button
              type="button"
              class="side-item showcase-toggle"
              :class="{ active: showcaseActive, open: showcaseOpen }"
              :aria-expanded="showcaseOpen"
              @click="toggleShowcase"
            >
              <i class="side-no">{{ showcaseNo }}</i>
              <span class="showcase-toggle-label">{{ SHOWCASE_GROUP.label }}</span>
              <span class="showcase-arrow" aria-hidden="true">›</span>
            </button>
            <div v-show="showcaseOpen" class="showcase-children">
              <router-link
                v-for="(item, idx) in SHOWCASE_GROUP.children"
                :key="item.path"
                :to="item.path"
                class="side-item side-item-child"
                :class="{ active: isActiveRoute(item.path) }"
                @click="onMenuClick($event, item)"
              >
                <i class="side-no">{{ String(idx + 1).padStart(2, '0') }}</i>
                <span>{{ item.label }}</span>
              </router-link>
            </div>
          </div>

        </aside>

        <main class="content-area">
          <slot />
        </main>
      </div>

      <!-- 右上角入口：吉祥物头像 = 吉祥物设置，用户名 = 用户设置，两键拼接 -->
      <div class="top-account">
        <el-dropdown trigger="click" popper-class="account-dropdown-popper">
          <button class="top-user top-user-mascot" type="button" title="吉祥物设置">
            <svg class="top-user-avatar" viewBox="17 9 97 58" shape-rendering="crispEdges" aria-hidden="true">
              <rect x="31" y="9" width="68" height="47" :fill="mascot.skinColor.value" />
              <rect x="17" y="27" width="14" height="13" :fill="mascot.skinColor.value" />
              <rect x="100" y="27" width="14" height="13" :fill="mascot.skinColor.value" />
              <rect x="42" y="20" width="7" height="8" fill="#101014" />
              <rect x="78" y="20" width="7" height="8" fill="#101014" />
              <rect x="37" y="56" width="7" height="11" :fill="mascot.skinColor.value" />
              <rect x="49" y="56" width="8" height="11" :fill="mascot.skinColor.value" />
              <rect x="74" y="56" width="7" height="11" :fill="mascot.skinColor.value" />
              <rect x="86" y="56" width="7" height="11" :fill="mascot.skinColor.value" />
            </svg>
          </button>
          <template #dropdown>
            <el-dropdown-menu class="account-dropdown-menu">
              <el-dropdown-item disabled class="account-dropdown-user">
                吉祥物 · {{ mascot.name.value }} · 心情：{{ mascot.mood.value >= 80 ? '😊 开心' : mascot.mood.value <= 30 ? '😢 低落' : '😐 普通' }}
              </el-dropdown-item>
              <el-dropdown-item divided @click="mascot.toggle()">吉祥物：{{ mascot.enabled.value ? '隐藏' : '显示' }}</el-dropdown-item>
              <el-dropdown-item @click="mascot.toggleSound()">吉祥物音效：{{ mascot.soundOn.value ? '开' : '关' }}</el-dropdown-item>
              <el-dropdown-item @click="mascot.toggleChat()">吉祥物闲聊：{{ mascot.chatOn.value ? '开' : '关' }}</el-dropdown-item>
              <el-dropdown-item @click="renameMascot">给它起名：{{ mascot.name.value }}</el-dropdown-item>
              <el-dropdown-item @click="mascot.togglePomodoro()">番茄钟：{{ mascot.pomoOn.value ? '停止专注' : '陪我专注 25 分钟' }}</el-dropdown-item>
              <el-dropdown-item @click="mascot.cycleOutfit()">换装：{{ mascot.outfitLabel[mascot.state.outfit] }}</el-dropdown-item>
              <el-dropdown-item @click="mascot.cycleSkin()">换花色：{{ mascot.skinLabel[mascot.state.skin] }}</el-dropdown-item>
            </el-dropdown-menu>
          </template>
        </el-dropdown>
        <el-dropdown trigger="click" popper-class="account-dropdown-popper">
          <button class="top-user top-user-account" type="button" title="账号设置">
            <span class="top-user-name">{{ userStore.nickname || userStore.username || 'admin' }}</span>
          </button>
          <template #dropdown>
            <el-dropdown-menu class="account-dropdown-menu">
              <el-dropdown-item disabled class="account-dropdown-user">
                {{ userStore.nickname || userStore.username || 'admin' }}
              </el-dropdown-item>
              <el-dropdown-item divided @click="goToProfile">个人资料</el-dropdown-item>
              <el-dropdown-item @click="goToChangePassword">修改密码</el-dropdown-item>
              <el-dropdown-item divided @click="handleLogout">退出登录</el-dropdown-item>
            </el-dropdown-menu>
          </template>
        </el-dropdown>
      </div>
    </section>
    <AgentReturnPill />
    <MascotActor />
  </div>
</template>

<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useUserStore } from '../stores/user'
import { getCurrentMenus } from '../api/rbac'
import { ElMessageBox } from 'element-plus'
import AgentReturnPill from '../components/AgentReturnPill.vue'
import MascotActor from '../components/MascotActor.vue'
import { mascot } from '../composables/useMascot'

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()
const twinkleStars = ref([])
const meteors = ref([])
const menuItems = ref([])
let twinkleTimer = null
let meteorTimer = null
let cosmicEffectId = 0
let cachedMenuItems = []

// 展厅类纯展示入口，不依赖后端菜单配置，统一收在可折叠的「视觉展厅」大菜单下
const SHOWCASE_GROUP = {
  label: '视觉展厅',
  symbol: '✧',
  children: [
    { path: '/showcase', label: '粒子展厅', symbol: '✧' },
    { path: '/cyber-city', label: '赛博都市', symbol: '⬢' },
    { path: '/jarvis', label: 'JARVIS 工作台', symbol: '◎' },
    { path: '/fractal-tunnel', label: '无限分形隧道', symbol: '∞' },
    { path: '/black-hole', label: '黑洞引力透镜', symbol: '⬤' },
    { path: '/storm', label: '雷暴之眼', symbol: '⚡' },
    { path: '/image-galaxy', label: '图片走廊', symbol: '❋' },
    { path: '/digital-life', label: '数字生命', symbol: '✺' },
    { path: '/aurora', label: '极光雪原', symbol: '❅' },
    { path: '/fluid-smoke', label: '流体烟雾', symbol: '≈' },
    { path: '/volumetric-light', label: '体积光', symbol: '◐' },
    { path: '/ink-wash', label: '水墨晕染', symbol: '墨' },
    { path: '/tesseract', label: '四维超立方体', symbol: '◈' },
    { path: '/supernova', label: '超新星爆发', symbol: '✹' },
    { path: '/liquid-metal', label: '液态水银', symbol: '◒' }
  ]
}

// 展开状态：默认收起，仅当当前路由命中展厅子项时自动展开
const SHOWCASE_STORAGE_KEY = 'ai-nexus-showcase-menu-open'
const showcaseOpen = ref(
  isShowcaseRoute() || localStorage.getItem(SHOWCASE_STORAGE_KEY) === '1'
)

// 当前路由是否落在展厅子项内（用于父菜单高亮）
const showcaseActive = computed(() =>
  SHOWCASE_GROUP.children.some(item => route.path.startsWith(item.path))
)

// 吉祥物：全局宠物（组件见 components/MascotActor.vue）
// 点击菜单：当前页则撸吉祥物（不跳转不闪屏），否则正常导航
function onMenuClick(e, item) {
  if (isActiveRoute(item.path)) {
    e.preventDefault()
    mascot.pet()
  }
}

// 同步吉祥物的家：激活菜单行的位置（ignoreCustom=刷新加载时忽略定居点，强制回菜单栏）
function syncMascotHome(ignoreCustom = false) {
  nextTick(() => {
    const el = document.querySelector('.side-item.active')
    if (el) mascot.setHome(el.getBoundingClientRect(), ignoreCustom)
  })
}

// 用户是否已点过菜单导航。纯刷新加载（未导航）期间，menuWatch 可能触发多次
// （loadCurrentMenus 分步赋值），每次都强制回菜单栏；导航后定居点才恢复优先。
let navigated = false
watch(() => route.path, (path) => {
  navigated = true
  mascot.applyRoutePost(path, 6, false)  // 先同步清工位/巡逻状态
  mascot.wake()                           // 切换菜单是主动行为：睡着也唤醒
  syncMascotHome(true)                    // 先回到新激活的菜单行
  mascot.patrol(path)                     // 到站后再进内容区游玩
})
watch(menuItems, () => syncMascotHome(!navigated))

// 图幅头里展厅大菜单的编号：排在功能菜单之后
const showcaseNo = computed(() => String(menuItems.value.length + 1).padStart(2, '0'))

// 菜单分组：一级行放核心工作流，二级行放管理与洞察；编号全局连续
const PRIMARY_PATHS = ['/kb', '/chat', '/image', '/image-canvas', '/extract', '/agents', '/analysis']

function isPrimaryPath(path) {
  return PRIMARY_PATHS.some(p => path === p || path.startsWith(p + '/'))
}

const primaryItems = computed(() => menuItems.value.filter(item => isPrimaryPath(item.path)))
const secondaryItems = computed(() => menuItems.value.filter(item => !isPrimaryPath(item.path)))

// 编号 = 菜单在全量列表中的原始顺序
function itemNo(item) {
  const idx = menuItems.value.indexOf(item)
  return String((idx >= 0 ? idx : 0) + 1).padStart(2, '0')
}

// 判断当前路由是否属于展厅
function isShowcaseRoute() {
  return SHOWCASE_GROUP.children.some(item => route.path.startsWith(item.path))
}

// 展开/收起展厅大菜单，并记住用户选择
function toggleShowcase() {
  // 第1步：切换展开状态
  showcaseOpen.value = !showcaseOpen.value
  // 第2步：持久化选择，刷新后保持
  localStorage.setItem(SHOWCASE_STORAGE_KEY, showcaseOpen.value ? '1' : '0')
}

const fallbackMenuItems = [
  {
    path: '/kb',
    label: '知识库',
    symbol: '▣'
  },
  {
    path: '/chat',
    label: 'AI 聊天',
    symbol: '◇'
  },
  {
    path: '/image',
    label: 'AI 生图',
    symbol: '◈'
  },
  {
    path: '/image-canvas',
    label: '生图画布',
    symbol: '▦'
  },
  {
    path: '/extract',
    label: '文档抽取',
    symbol: '≋'
  },
  {
    path: '/agents',
    label: '智能体管理',
    symbol: '✦'
  },
  {
    path: '/skills',
    label: '技能管理',
    symbol: '❖'
  },
  {
    path: '/analysis',
    label: '数据分析',
    symbol: '∑'
  },
  {
    path: '/agent-tools',
    label: '天气查询智能体',
    symbol: '☁'
  },
  {
    path: '/rbac',
    label: '权限管理',
    symbol: '▧'
  },
  {
    path: '/settings',
    label: '模型设置',
    symbol: '⚙'
  },
  {
    path: '/stats',
    label: '用量统计',
    symbol: '▥'
  },
  {
    path: '/health',
    label: '系统健康',
    symbol: '◉'
  },
  {
    path: '/tasks',
    label: '任务中心',
    symbol: '▤'
  },
  {
    path: '/knowledge-gaps',
    label: '知识缺口',
    symbol: '⌁'
  },
  {
    path: '/eval',
    label: 'RAG 评测',
    symbol: '✓'
  }
]

// 视口变化时同步吉祥物的家（命名函数：卸载时才能解绑）
function onViewportResize() {
  syncMascotHome()
}

onMounted(() => {
  menuItems.value = cachedMenuItems.length > 0 ? cachedMenuItems : fallbackMenuItems
  loadCurrentMenus()
  startCosmicEffects()
  syncMascotHome(true)   // 刷新加载：先回菜单栏（定居点只在会话内有效）
  // 刷新加载：只登记热点+清工位，不上岗
  mascot.applyRoutePost(route.path, 6, false)
  mascot.patrol(route.path)   // 到站后再进内容区游玩
  window.addEventListener('resize', onViewportResize)
  // 登出清理（dispose）后再次登录，MainLayout 会重新挂载：这里补一次初始化，
  // 已经初始化过时 init 内部会直接返回，不会重复注册
  mascot.init()
})

onBeforeUnmount(() => {
  stopCosmicEffects()
  window.removeEventListener('resize', onViewportResize)
  mascot.dispose()   // 卸载（登出/回登录页）时收回吉祥物的全局监听与定时器
})

// 智能体入口的动态标签：正在使用哪个智能体，菜单就显示哪个，不再固定写"天气查询智能体"
const currentAgentLabel = ref('')
watch(() => route.fullPath, () => {
  if (route.path !== '/agent-tools') {
    return
  }
  if (route.query.name) {
    currentAgentLabel.value = String(route.query.name)
    return
  }
  try {
    const raw = localStorage.getItem('agent-recent')
    const recent = raw ? JSON.parse(raw) : null
    currentAgentLabel.value = recent && recent.agentName ? recent.agentName : ''
  } catch (error) {
    currentAgentLabel.value = ''
  }
}, { immediate: true })

// 菜单标签：智能体入口用动态标签，其余用固定标签
function menuItemLabel(item) {
  if (item.path === '/agent-tools' && currentAgentLabel.value) {
    return currentAgentLabel.value
  }
  return item.label
}

// 判断当前菜单是否选中
function isActiveRoute(path) {
  // 第1步：文件页面属于知识库菜单
  if (path === '/kb' && route.path.startsWith('/file')) {
    return true
  }
  // 第2步：画布页面不属于 AI 生图菜单，避免两个菜单同时高亮
  if (path === '/image' && route.path.startsWith('/image-canvas')) {
    return false
  }
  // 第3步：普通页面按路由前缀判断是否选中
  return route.path.startsWith(path)
}

// 加载当前用户可见菜单
async function loadCurrentMenus() {
  try {
    // 第1步：从后端读取当前用户角色授权后的菜单树
    const res = await getCurrentMenus()
    const items = buildMenuItems(res.data || [])
    // 第2步：接口返回菜单时替换默认菜单
    if (items.length > 0) {
      // 展厅入口不在后端菜单表内，独立成组渲染，不参与此处拼接
      cachedMenuItems = items
      menuItems.value = items
    }
  } catch (error) {
    // 第3步：权限表未初始化或接口异常时保留默认菜单，避免页面入口消失
    console.error(error)
  }
}

// 把后端菜单树整理成侧边栏需要的扁平菜单
function buildMenuItems(nodes) {
  // 第1步：递归展开菜单树，目录本身没有路径时只展示它的子菜单
  const items = []
  const visit = list => {
    for (const node of list || []) {
      if (node.enabled === 0 || node.visible === 0) {
        continue
      }
      if (node.path) {
        items.push({
          path: node.path,
          label: node.menuName,
          symbol: getMenuSymbol(node)
        })
      }
      if (node.children?.length) {
        visit(node.children)
      }
    }
  }
  visit(nodes)
  return items
}

// 根据菜单权限标识生成侧边栏符号
function getMenuSymbol(menu) {
  // 第1步：优先使用平台内置入口的固定符号
  const symbolMap = {
    'kb:view': '▣',
    'chat:view': '◇',
    'image:view': '◈',
    'extract:view': '≋',
    'agents:view': '✦',
    'rbac:manage': '▧',
    'skills:view': '❖',
    'analysis:view': '∑',
    'settings:view': '⚙',
    'stats:view': '▥',
    'health:view': '◉',
    'tasks:view': '▤',
    'knowledge-gap:view': '⌁',
    'eval:view': '✓'
  }
  if (symbolMap[menu.permissionCode]) {
    return symbolMap[menu.permissionCode]
  }
  // 第2步：自定义菜单使用统一占位符号
  return '•'
}

// 退出当前登录账号
function handleLogout() {
  // 第1步：清理本地登录信息
  userStore.logout()
  // 第2步：回到登录页
  router.push('/login')
}

// 打开个人资料页面
function goToProfile() {
  // 第1步：跳转到账号资料页
  router.push('/profile')
}

// 打开修改密码页面
function goToChangePassword() {
  // 第1步：跳转到修改密码页
  router.push('/change-password')
}

// 给吉祥物起名（存 localStorage，它说话时会用这个名字自称）
async function renameMascot() {
  try {
    const result = await ElMessageBox.prompt('最多 8 个字，它以后会用这个名字自称', '给吉祥物起名', {
      confirmButtonText: '就叫这个',
      cancelButtonText: '算了',
      inputValue: mascot.name.value,
      inputPattern: /\S+/,
      inputErrorMessage: '名字不能为空'
    })
    mascot.rename(result.value)
  } catch (error) {
    // 用户取消起名，不做处理
  }
}

// 启动随机星空动效
function startCosmicEffects() {
  // 第1步：先触发几颗随机闪烁星，避免页面刚打开时太静
  for (let i = 0; i < 9; i++) {
    window.setTimeout(createRandomTwinkleStar, randomInt(180, 1600))
  }
  // 第2步：让第一条流星尽快出现，避免用户打开页面时等太久
  window.setTimeout(createRandomMeteor, randomInt(900, 1800))
  // 第3步：用随机间隔循环生成闪烁星和流星
  scheduleTwinkleStar()
  scheduleMeteor()
}

// 停止随机星空动效
function stopCosmicEffects() {
  // 第1步：清理定时器，避免切换页面后继续触发
  window.clearTimeout(twinkleTimer)
  window.clearTimeout(meteorTimer)
  // 第2步：清空正在播放的装饰元素
  twinkleStars.value = []
  meteors.value = []
}

// 安排下一次星星随机闪烁
function scheduleTwinkleStar() {
  // 第1步：随机决定下一次闪烁时间
  twinkleTimer = window.setTimeout(() => {
    // 第2步：生成当前闪烁星，并继续安排下一次
    createRandomTwinkleStar()
    scheduleTwinkleStar()
  }, randomInt(420, 1500))
}

// 生成一颗随机闪烁星
function createRandomTwinkleStar() {
  // 第1步：随机星星的位置、大小和亮度
  const id = ++cosmicEffectId
  const size = randomNumber(3.6, 9.8)
  const duration = randomNumber(900, 1800)
  const star = {
    id,
    style: {
      left: `${randomNumber(2, 98)}%`,
      top: `${randomNumber(3, 96)}%`,
      width: `${size}px`,
      height: `${size}px`,
      '--twinkle-ray-size': `${size * randomNumber(7, 12)}px`,
      '--twinkle-duration': `${duration}ms`,
      '--twinkle-glow': randomNumber(1.05, 1.55).toFixed(2)
    }
  }
  // 第2步：放入页面播放一次动画
  twinkleStars.value.push(star)
  // 第3步：动画结束后删除，下一次再随机生成
  window.setTimeout(() => {
    twinkleStars.value = twinkleStars.value.filter(item => item.id !== id)
  }, duration + 120)
}

// 安排下一次随机流星
function scheduleMeteor() {
  // 第1步：随机决定下一次流星出现时间
  meteorTimer = window.setTimeout(() => {
    // 第2步：生成当前流星，并继续安排下一次
    createRandomMeteor()
    scheduleMeteor()
  }, randomInt(4800, 8200))
}

// 生成一条随机光束流星
function createRandomMeteor() {
  // 第1步：随机选择斜向方向，让流星每次用不同角度穿过屏幕
  const id = ++cosmicEffectId
  const directions = [
    { startSideX: 'left', startSideY: 'top', moveX: 1, moveY: 1 },
    { startSideX: 'left', startSideY: 'bottom', moveX: 1, moveY: -1 },
    { startSideX: 'right', startSideY: 'top', moveX: -1, moveY: 1 },
    { startSideX: 'right', startSideY: 'bottom', moveX: -1, moveY: -1 }
  ]
  const direction = directions[randomInt(0, directions.length - 1)]
  const startX = direction.startSideX === 'left' ? randomNumber(-32, -6) : randomNumber(106, 132)
  const startY = direction.startSideY === 'top' ? randomNumber(-24, 22) : randomNumber(78, 124)
  const travelX = direction.moveX * randomNumber(128, 168)
  const slope = randomNumber(0.36, 0.82)
  const travelY = direction.moveY * Math.abs(travelX) * slope
  const endX = startX + travelX
  const endY = startY + travelY
  const deltaX = endX - startX
  const deltaY = endY - startY
  const angle = Math.atan2(deltaY, deltaX) * 180 / Math.PI
  const duration = randomNumber(3000, 6000)
  const length = randomNumber(24, 34)
  // 第2步：把随机参数写入 CSS 变量，让动画从起点移动到终点
  const meteor = {
    id,
    style: {
      left: `${startX}%`,
      top: `${startY}%`,
      width: `${length}vw`,
      height: `${randomNumber(1.4, 2.4)}px`,
      '--meteor-angle': `${angle}deg`,
      '--meteor-x': `${deltaX}vw`,
      '--meteor-y': `${deltaY}vh`,
      '--meteor-duration': `${duration}ms`
    }
  }
  // 第3步：放入页面播放一次动画
  meteors.value.push(meteor)
  // 第4步：动画结束后删除，保持 DOM 干净
  window.setTimeout(() => {
    meteors.value = meteors.value.filter(item => item.id !== id)
  }, duration + 180)
}

// 生成随机整数
function randomInt(min, max) {
  // 第1步：生成指定范围内的整数
  return Math.floor(randomNumber(min, max + 1))
}

// 生成随机小数
function randomNumber(min, max) {
  // 第1步：生成指定范围内的小数
  return Math.random() * (max - min) + min
}
</script>

<style scoped>
.console-shell {
  position: relative;
  width: 100%;
  height: 100dvh;
  min-height: 100dvh;
  padding: 0;
  overflow: hidden;
  color: var(--ink-color);
  background: #02030a;
}

.cosmic-background {
  position: absolute;
  inset: 0;
  z-index: 0;
  overflow: hidden;
  pointer-events: none;
  background:
    radial-gradient(ellipse 42% 34% at 72% 18%, rgba(48, 73, 139, 0.18), transparent 66%),
    radial-gradient(ellipse 34% 28% at 22% 78%, rgba(236, 233, 225, 0.05), transparent 62%),
    radial-gradient(ellipse 86% 70% at 50% 52%, rgba(12, 20, 48, 0.42), transparent 76%),
    linear-gradient(180deg, #050711 0%, #02030a 52%, #000105 100%);
}

.cosmic-background::before {
  content: "";
  position: absolute;
  inset: -20%;
  opacity: 0.28;
  background:
    radial-gradient(ellipse at 34% 28%, rgba(73, 102, 190, 0.18), transparent 26%),
    radial-gradient(ellipse at 72% 62%, rgba(236, 233, 225, 0.08), transparent 22%),
    conic-gradient(from 135deg at 50% 50%, transparent 0deg, rgba(86, 118, 214, 0.1) 64deg, transparent 128deg, rgba(236, 233, 225, 0.06) 210deg, transparent 300deg);
  filter: blur(34px);
  animation: nebulaDrift 34s ease-in-out infinite alternate;
}

.cosmic-background::after {
  content: "";
  position: absolute;
  inset: 0;
  z-index: 1;
  background:
    radial-gradient(circle at 50% 50%, transparent 0 44%, rgba(0, 1, 5, 0.18) 100%),
    linear-gradient(90deg, rgba(0, 1, 5, 0.32), rgba(0, 1, 5, 0.02) 44%, rgba(0, 1, 5, 0.14)),
    linear-gradient(180deg, rgba(0, 1, 5, 0.14), transparent 34%, rgba(0, 1, 5, 0.24));
}

.star-field {
  position: absolute;
  inset: 0;
  z-index: 0;
  background-repeat: repeat;
  opacity: 1;
}

.star-field-far {
  background-image:
    radial-gradient(circle, rgba(255, 255, 255, 0.92) 0 0.85px, transparent 1.25px),
    radial-gradient(circle, rgba(156, 192, 255, 0.74) 0 0.9px, transparent 1.35px),
    radial-gradient(circle, rgba(255, 233, 192, 0.68) 0 0.8px, transparent 1.25px);
  background-position:
    0 0,
    38px 56px,
    84px 24px;
  background-size:
    54px 50px,
    86px 78px,
    118px 106px;
  animation: starDriftFar 112s linear infinite;
}

.star-field-middle {
  opacity: 0.96;
  background-image:
    radial-gradient(circle, rgba(255, 255, 255, 1) 0 1px, transparent 1.55px),
    radial-gradient(circle, rgba(190, 214, 255, 0.86) 0 1.1px, transparent 1.75px),
    radial-gradient(circle, rgba(255, 240, 210, 0.82) 0 1px, transparent 1.6px);
  background-position:
    16px 20px,
    60px 74px,
    118px 32px;
  background-size:
    96px 86px,
    144px 128px,
    206px 178px;
  animation: starDriftMiddle 92s linear infinite;
}

.star-field-near {
  opacity: 0.9;
  background-image:
    radial-gradient(circle, rgba(255, 236, 190, 1) 0 1.45px, transparent 2.35px),
    radial-gradient(circle, rgba(255, 255, 255, 0.96) 0 1.25px, transparent 2px),
    radial-gradient(circle, rgba(146, 184, 255, 0.92) 0 1.2px, transparent 1.9px);
  background-position:
    22px 34px,
    76px 12px,
    166px 88px;
  background-size:
    188px 148px,
    254px 214px,
    342px 260px;
  animation: starDriftNear 74s linear infinite;
}

.twinkle-star {
  position: absolute;
  z-index: 4;
  width: 4px;
  height: 4px;
  border-radius: 50%;
  background: rgba(255, 255, 255, 0.98);
  opacity: 0;
  box-shadow:
    0 0 calc(12px * var(--twinkle-glow)) rgba(255, 255, 255, 0.95),
    0 0 calc(36px * var(--twinkle-glow)) rgba(150, 190, 255, 0.76),
    0 0 calc(62px * var(--twinkle-glow)) rgba(236, 233, 225, 0.3);
  animation: randomStarTwinkle var(--twinkle-duration) ease-out forwards;
}

.twinkle-star::before,
.twinkle-star::after {
  content: "";
  position: absolute;
  left: 50%;
  top: 50%;
  width: var(--twinkle-ray-size);
  height: 1.4px;
  border-radius: 0;
  background: linear-gradient(90deg, transparent, rgba(255, 255, 255, 0.96), transparent);
  transform: translate(-50%, -50%);
}

.twinkle-star::after {
  transform: translate(-50%, -50%) rotate(90deg);
}

.meteor {
  position: absolute;
  z-index: 4;
  width: 220px;
  height: 4px;
  border-radius: 0;
  background:
    linear-gradient(90deg, rgba(255, 95, 120, 0) 0%, rgba(255, 107, 132, 0.1) 12%, rgba(255, 139, 166, 0.28) 36%, rgba(255, 186, 213, 0.64) 66%, rgba(255, 240, 250, 0.95) 90%, #fff 100%),
    linear-gradient(90deg, transparent 0%, rgba(255, 205, 225, 0.08) 18%, rgba(255, 236, 246, 0.34) 70%, rgba(255, 255, 255, 0.96) 100%);
  filter:
    drop-shadow(0 0 6px rgba(255, 255, 255, 0.82))
    drop-shadow(0 0 14px rgba(255, 168, 202, 0.54))
    drop-shadow(0 0 34px rgba(255, 102, 134, 0.28));
  opacity: 0;
  transform: rotate(var(--meteor-angle)) translate3d(0, 0, 0);
  transform-origin: right center;
  animation: randomMeteorSweep var(--meteor-duration) linear forwards;
}

.meteor::before {
  content: "";
  position: absolute;
  right: -18px;
  top: 50%;
  width: 96px;
  height: 36px;
  border-radius: 0;
  background:
    linear-gradient(90deg, transparent 0%, rgba(255, 112, 146, 0.12) 22%, rgba(255, 203, 228, 0.35) 72%, rgba(255, 255, 255, 0.44) 100%),
    radial-gradient(ellipse at 100% 50%, rgba(255, 255, 255, 0.58) 0 8%, rgba(255, 174, 210, 0.34) 28%, transparent 72%);
  opacity: 0.58;
  filter: blur(8px);
  transform: translateY(-50%);
}

.console-frame {
  position: relative;
  z-index: 2;
  width: 100%;
  height: 100%;
  min-height: 0;
  margin: 0;
  overflow: hidden;
  border: 0;
  border-radius: 0;
  background: transparent;
  box-shadow: none;
  backdrop-filter: none;
}

.console-body {
  display: grid;
  grid-template-columns: 216px minmax(0, 1fr);
  height: 100%;
  min-height: 0;
}

/* ===== 目录栏：年鉴目录页语法 ===== */
.side-nav {
  display: flex;
  flex-direction: column;
  min-width: 0;
  padding: 20px 14px 14px;
  border-right: 1px solid var(--rule);
  background: rgba(2, 3, 10, 0.55);
  overflow-y: auto;
  overscroll-behavior: contain;
}

.side-nav::-webkit-scrollbar {
  width: 4px;
}

.side-nav::-webkit-scrollbar-thumb {
  background: rgba(236, 233, 225, 0.12);
}

.brand {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 2px 10px 16px;
  border-bottom: 1px solid var(--rule);
  margin-bottom: 14px;
}

.brand-logo {
  display: block;
  width: 40px;
  height: 40px;
  object-fit: contain;
}

.brand-sub {
  color: var(--text-2);
  font-family: Georgia, 'Times New Roman', var(--font-mono);
  font-size: 13px;
  font-weight: 600;
  letter-spacing: 0.22em;
  white-space: nowrap;
}

.nav-group {
  margin-bottom: 6px;
}

.nav-cap {
  padding: 12px 12px 6px;
  color: var(--text-2);
  font-family: var(--font-mono);
  font-size: 13px;
  font-weight: 500;
  letter-spacing: 0.3em;
}

.side-item {
  position: relative;
  display: flex;
  align-items: center;
  gap: 10px;
  width: 100%;
  min-height: 34px;
  padding: 0 10px;
  border: 0;
  background: none;
  color: var(--text-2);
  font-size: 15px;
  font-weight: 500;
  text-align: left;
  text-decoration: none;
  white-space: nowrap;
  transition: color 0.15s var(--motion-curve), background 0.15s var(--motion-curve);
}

.side-no {
  font-style: normal;
  color: var(--text-3);
  font-family: var(--font-mono);
  font-size: 10px;
  letter-spacing: 0.08em;
  flex: 0 0 auto;
}

.side-item:hover {
  color: var(--text-1);
  background: var(--row-hover);
}

.side-item.active {
  color: var(--text-1);
  background: var(--row-hover);
}

.side-item.active .side-no {
  color: var(--signal);
}





/* 激活短线：贴在目录栏左缘，永远对齐 */
.side-item.active::before {
  content: "";
  position: absolute;
  left: -14px;
  top: 50%;
  transform: translateY(-50%);
  width: 2px;
  height: 16px;
  background: var(--signal);
}

.showcase-toggle {
  cursor: pointer;
  font-family: inherit;
  width: 100%;
}

.showcase-arrow {
  margin-left: auto;
  color: var(--text-3);
  font-size: 12px;
  line-height: 1;
  transform: rotate(0deg);
  transition: transform 0.2s var(--motion-curve);
}

.showcase-toggle.open .showcase-arrow {
  transform: rotate(90deg);
}

.showcase-children {
  display: flex;
  flex-direction: column;
  margin: 2px 0 4px;
  padding-left: 14px;
  border-left: 1px solid var(--rule);
}

.side-item-child {
  min-height: 30px;
  font-size: 14px;
  color: var(--text-2);
}

.side-item-child .side-no {
  font-size: 9px;
}

/* ===== 右上角入口：吉祥物 + 账号双键（分开摆放） ===== */
.top-account {
  position: absolute;
  top: 10px;
  right: 14px;
  z-index: 30;
  display: flex;
  gap: 8px;
}

.top-user {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 6px 12px 6px 9px;
  border: 1px solid var(--rule);
  background: rgba(2, 3, 10, 0.72);
  color: var(--text-2);
  font-size: 14px;
  cursor: pointer;
  backdrop-filter: blur(6px);
  transition: color 0.15s var(--motion-curve), border-color 0.15s var(--motion-curve);
}

.top-user:hover {
  color: var(--text-1);
  border-color: var(--rule-strong);
}

/* 头像 = 吉祥物全身（身体 + 手臂 + 眼睛 + 四条腿），沿用演员组件的眨眼节奏 */
.top-user-avatar {
  width: 40px;
  height: 24px;
  display: block;
}

.top-user-avatar rect[fill='#101014'] {
  transform-box: fill-box;
  transform-origin: center;
  animation: avatar-blink 3.4s ease-in-out infinite;
}

@keyframes avatar-blink {
  0%, 91%, 100% { transform: scaleY(1); }
  95% { transform: scaleY(0.12); }
}

.top-user-name {
  max-width: 9em;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

/* ===== 内容区：图版底 ===== */
.content-area {
  position: relative;
  min-width: 0;
  min-height: 0;
  overflow: auto;
  background: rgba(2, 3, 10, 0.45);
}

@keyframes nebulaDrift {
  0% {
    transform: translate3d(-1.5%, -1%, 0) scale(1);
  }

  100% {
    transform: translate3d(1.5%, 1%, 0) scale(1.05);
  }
}

@keyframes starDriftFar {
  100% {
    background-position:
      54px 50px,
      124px 134px,
      202px 130px;
  }
}

@keyframes starDriftMiddle {
  100% {
    background-position:
      112px 106px,
      204px 202px,
      324px 210px;
  }
}

@keyframes starDriftNear {
  100% {
    background-position:
      210px 182px,
      330px 226px,
      508px 348px;
  }
}

@keyframes randomStarTwinkle {
  0% {
    opacity: 0;
    transform: scale(0.18) rotate(0deg);
  }

  18% {
    opacity: 0.95;
    transform: scale(1.18) rotate(0deg);
  }

  38% {
    opacity: 0.2;
    transform: scale(0.6) rotate(8deg);
  }

  58% {
    opacity: 1;
    transform: scale(1.05) rotate(0deg);
  }

  100% {
    opacity: 0;
    transform: scale(0.2) rotate(-8deg);
  }
}

@keyframes randomMeteorSweep {
  0% {
    opacity: 0;
    transform: rotate(var(--meteor-angle)) translate3d(0, 0, 0) scaleX(0.65);
  }

  10% {
    opacity: 1;
    transform: rotate(var(--meteor-angle)) translate3d(0, 0, 0) scaleX(1);
  }

  72% {
    opacity: 0.95;
  }

  100% {
    opacity: 0;
    transform: rotate(var(--meteor-angle)) translate3d(var(--meteor-x), var(--meteor-y), 0) scaleX(1);
  }
}

@media (max-width: 920px) {
  .console-body {
    grid-template-columns: 1fr;
  }

  /* 窄屏：目录栏转为顶部横向条，菜单自然换行 */
  .side-nav {
    border-right: 0;
    border-bottom: 1px solid var(--rule);
    overflow-y: visible;
  }

  .nav-group {
    display: inline-flex;
    flex-wrap: wrap;
    align-items: center;
    margin-right: 18px;
  }

  .nav-cap {
    padding: 6px 8px;
  }

  .side-item {
    width: auto;
    min-height: 32px;
    padding: 0 8px;
  }

  .side-item.active::before {
    left: 0;
  }

  .showcase-children {
    display: inline-flex;
    flex-direction: row;
    flex-wrap: wrap;
    padding-left: 0;
    border-left: 0;
  }

  /* 窄屏：目录栏转为顶部横条，用户名限宽避免压住菜单（拆分后按钮不能变空壳） */
  .top-user-name {
    max-width: 5em;
  }
}
</style>