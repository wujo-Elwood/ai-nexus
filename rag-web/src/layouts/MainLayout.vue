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
        <aside class="side-nav">
          <nav class="nav-section feature-nav">
            <h2>功能</h2>
            <router-link
              v-for="item in menuItems"
              :key="item.path"
              :to="item.path"
              class="side-menu-item"
              :class="{ active: isActiveRoute(item.path) }"
            >
              <span class="menu-symbol">{{ item.symbol }}</span>
              <span>{{ item.label }}</span>
            </router-link>
          </nav>
        </aside>

        <section class="workspace">
          <header class="top-toolbar">
            <div class="toolbar-title">
              <span class="toolbar-title-dot"></span>
              <span>AI智能平台</span>
            </div>

            <div class="toolbar-actions">
              <el-dropdown trigger="click" popper-class="account-dropdown-popper">
                <button class="avatar-button" type="button">
                  {{ userInitial }}
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
          </header>

          <main class="content-area">
            <slot />
          </main>
        </section>
      </div>
    </section>
  </div>
</template>

<script setup>
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useUserStore } from '../stores/user'
import { getCurrentMenus } from '../api/rbac'

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
    path: '/eval',
    label: 'RAG 评测',
    symbol: '✓'
  }
]

const userInitial = computed(() => {
  const name = userStore.nickname || userStore.username || 'A'
  return name.charAt(0).toUpperCase()
})

onMounted(() => {
  menuItems.value = cachedMenuItems.length > 0 ? cachedMenuItems : fallbackMenuItems
  loadCurrentMenus()
  startCosmicEffects()
})

onBeforeUnmount(() => {
  stopCosmicEffects()
})

// 判断当前菜单是否选中
function isActiveRoute(path) {
  // 第1步：文件页面属于知识库菜单
  if (path === '/kb' && route.path.startsWith('/file')) {
    return true
  }
  // 第2步：普通页面按路由前缀判断是否选中
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
    'settings:view': '⚙',
    'stats:view': '▥',
    'health:view': '◉',
    'tasks:view': '▤',
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
    radial-gradient(ellipse 34% 28% at 22% 78%, rgba(229, 160, 68, 0.05), transparent 62%),
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
    radial-gradient(ellipse at 72% 62%, rgba(229, 160, 68, 0.08), transparent 22%),
    conic-gradient(from 135deg at 50% 50%, transparent 0deg, rgba(86, 118, 214, 0.1) 64deg, transparent 128deg, rgba(229, 160, 68, 0.06) 210deg, transparent 300deg);
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
    0 0 calc(62px * var(--twinkle-glow)) rgba(229, 160, 68, 0.3);
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
  border-radius: 999px;
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
  border-radius: 999px;
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
  border-radius: 999px;
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
  background: rgba(6, 8, 14, 0.16);
  box-shadow: none;
  backdrop-filter: blur(0.5px);
}

.console-body {
  display: grid;
  grid-template-columns: 220px minmax(0, 1fr);
  height: 100%;
  min-height: 0;
}

.side-nav {
  display: flex;
  flex-direction: column;
  min-width: 0;
  padding: 16px;
  border-right: 1px solid var(--border);
  background: rgba(12, 14, 18, 0.68);
  backdrop-filter: blur(18px);
}

.nav-section {
  display: grid;
  gap: 6px;
  margin-top: 0;
}

.nav-section h2 {
  margin: 0 0 6px;
  color: var(--text-secondary);
  font-size: 11px;
  font-weight: 650;
  letter-spacing: 0.05em;
  text-transform: uppercase;
}

.side-menu-item {
  min-height: 34px;
  display: flex;
  align-items: center;
  gap: 7px;
  padding: 0 10px;
  border: 0;
  border-radius: 8px;
  color: var(--text-secondary);
  background: transparent;
  text-align: left;
  text-decoration: none;
  font-size: 14px;
  font-weight: 650;
  transition:
    color 0.2s ease,
    background 0.2s ease;
}

.side-menu-item:hover,
.side-menu-item.active {
  color: var(--text-primary);
  background: rgba(229, 160, 68, 0.1);
}

.menu-symbol {
  width: 14px;
  color: var(--primary-color);
  font-size: 12px;
}

.workspace {
  min-width: 0;
  min-height: 0;
  display: flex;
  flex-direction: column;
  background: transparent;
}

.top-toolbar {
  min-height: 56px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  padding: 0 20px;
  border-bottom: 1px solid var(--border);
  background: rgba(12, 14, 18, 0.64);
  backdrop-filter: blur(18px);
}

.toolbar-title {
  display: inline-flex;
  align-items: center;
  gap: 10px;
  color: var(--text-primary);
  font-size: 14px;
  font-weight: 850;
}

.toolbar-title-dot {
  width: 9px;
  height: 9px;
  border-radius: 50%;
  background: var(--primary-color);
  box-shadow: 0 0 0 5px rgba(229, 160, 68, 0.1);
}

.avatar-button {
  border: 1px solid var(--border);
  color: var(--text-primary);
  background: rgba(247, 245, 242, 0.03);
}

.toolbar-actions {
  display: flex;
  align-items: center;
  gap: 10px;
}

.avatar-button {
  width: 34px;
  height: 34px;
  display: grid;
  place-items: center;
  border-radius: 8px;
}

.avatar-button {
  border-color: rgba(229, 160, 68, 0.28);
  color: var(--primary-color);
  background: rgba(229, 160, 68, 0.25);
  font-weight: 850;
}

.content-area {
  position: relative;
  min-width: 0;
  min-height: 0;
  flex: 1;
  overflow: auto;
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
  .console-shell {
    padding: 0;
  }

  .console-frame {
    height: 100dvh;
    min-height: 100dvh;
    border-radius: 0;
  }

  .console-body {
    grid-template-columns: 1fr;
    min-height: 100dvh;
    height: 100%;
  }

  .side-nav {
    border-right: 0;
    border-bottom: 1px solid var(--line-color);
  }

  .feature-nav {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

}

@media (max-width: 560px) {
  .top-toolbar {
    padding: 0 12px;
  }

}
</style>
