<template>
  <div class="app-container">
    <!-- 第1步：渲染顶部导航栏 -->
    <header class="top-nav">
      <div class="brand-area">
        <div class="brand-mark">
          <svg viewBox="0 0 24 24" fill="none">
            <path d="M12 3L3 7.5L12 12L21 7.5L12 3Z" stroke="currentColor" stroke-width="2" stroke-linejoin="round" />
            <path d="M3 12L12 16.5L21 12" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" />
            <path d="M3 16.5L12 21L21 16.5" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" />
          </svg>
        </div>
        <div>
          <div class="brand-name">WUJO RAG</div>
          <div class="brand-desc">知识库智能工作台</div>
        </div>
      </div>

      <nav class="top-menu">
        <router-link
          v-for="item in menuItems"
          :key="item.path"
          :to="item.path"
          class="top-menu-item"
          :class="{ active: isActiveRoute(item.path) }"
        >
          <span class="menu-icon" v-html="item.icon"></span>
          <span>{{ item.label }}</span>
        </router-link>
      </nav>

      <div class="user-area">
        <div class="user-avatar">
          {{ (userStore.nickname || userStore.username || 'U').charAt(0).toUpperCase() }}
        </div>
        <el-dropdown trigger="click">
          <button class="user-menu">
            <span>{{ userStore.nickname || userStore.username }}</span>
            <svg viewBox="0 0 24 24" fill="none" class="chevron-icon">
              <path d="M6 9l6 6 6-6" stroke="currentColor" stroke-width="2" stroke-linecap="round" />
            </svg>
          </button>
          <template #dropdown>
            <el-dropdown-menu>
              <el-dropdown-item @click="handleLogout">退出登录</el-dropdown-item>
            </el-dropdown-menu>
          </template>
        </el-dropdown>
      </div>
    </header>

    <!-- 第2步：渲染主内容区域 -->
    <main class="content-area">
      <slot />
    </main>
  </div>
</template>

<script setup>
import { useRoute, useRouter } from 'vue-router'
import { useUserStore } from '../stores/user'

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()

const menuItems = [
  {
    path: '/kb',
    label: '知识库',
    icon: '<svg viewBox="0 0 24 24" fill="none"><path d="M5 4.5A2.5 2.5 0 0 1 7.5 2H19v18H7.5A2.5 2.5 0 0 0 5 22V4.5Z" stroke="currentColor" stroke-width="2"/><path d="M5 19.5A2.5 2.5 0 0 1 7.5 17H19" stroke="currentColor" stroke-width="2"/></svg>'
  },
  {
    path: '/chat',
    label: 'AI 聊天',
    icon: '<svg viewBox="0 0 24 24" fill="none"><path d="M21 12a8 8 0 0 1-8 8H7l-4 3v-6.2A8 8 0 1 1 21 12Z" stroke="currentColor" stroke-width="2"/></svg>'
  },
  {
    path: '/extract',
    label: '文档抽取',
    icon: '<svg viewBox="0 0 24 24" fill="none"><path d="M7 3h7l5 5v13H7a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2Z" stroke="currentColor" stroke-width="2"/><path d="M14 3v5h5M8 13h8M8 17h6" stroke="currentColor" stroke-width="2" stroke-linecap="round"/></svg>'
  },
  {
    path: '/settings',
    label: '模型设置',
    icon: '<svg viewBox="0 0 24 24" fill="none"><path d="M12 15.5A3.5 3.5 0 1 0 12 8a3.5 3.5 0 0 0 0 7.5Z" stroke="currentColor" stroke-width="2"/><path d="M19.4 15a1.8 1.8 0 0 0 .36 1.98l.04.04a2 2 0 1 1-2.82 2.82l-.04-.04A1.8 1.8 0 0 0 15 19.4a1.8 1.8 0 0 0-1 1.62V21a2 2 0 1 1-4 0v-.06a1.8 1.8 0 0 0-1-1.62 1.8 1.8 0 0 0-1.98.36l-.04.04a2 2 0 1 1-2.82-2.82l.04-.04A1.8 1.8 0 0 0 4.6 15a1.8 1.8 0 0 0-1.62-1H3a2 2 0 1 1 0-4h.06A1.8 1.8 0 0 0 4.68 9a1.8 1.8 0 0 0-.36-1.98l-.04-.04A2 2 0 1 1 7.1 4.16l.04.04A1.8 1.8 0 0 0 9 4.6a1.8 1.8 0 0 0 1-1.62V3a2 2 0 1 1 4 0v.06A1.8 1.8 0 0 0 15 4.68a1.8 1.8 0 0 0 1.98-.36l.04-.04a2 2 0 1 1 2.82 2.82l-.04.04A1.8 1.8 0 0 0 19.4 9c.22.6.84 1 1.52 1H21a2 2 0 1 1 0 4h-.06A1.8 1.8 0 0 0 19.4 15Z" stroke="currentColor" stroke-width="1.5"/></svg>'
  },
  {
    path: '/stats',
    label: '用量统计',
    icon: '<svg viewBox="0 0 24 24" fill="none"><path d="M5 20V11M12 20V4M19 20v-7" stroke="currentColor" stroke-width="2" stroke-linecap="round"/></svg>'
  }
]

// 判断当前菜单是否激活
function isActiveRoute(path) {
  // 第1步：文件管理页也归属到知识库菜单
  if (path === '/kb' && route.path.startsWith('/file')) {
    return true
  }
  // 第2步：普通页面用路径前缀判断
  return route.path.startsWith(path)
}

// 退出当前登录账号
function handleLogout() {
  // 第1步：清理本地用户状态
  userStore.logout()
  // 第2步：跳转到登录页
  router.push('/login')
}
</script>

<style scoped>
.app-container {
  min-height: 100vh;
  padding: 18px;
}

.top-nav {
  position: sticky;
  top: 18px;
  z-index: 100;
  display: grid;
  grid-template-columns: minmax(220px, 1fr) auto minmax(220px, 1fr);
  align-items: center;
  gap: 20px;
  min-height: 78px;
  padding: 12px 16px;
  border: 1px solid rgba(255, 255, 255, 0.16);
  border-radius: 28px;
  color: #ffffff;
  background:
    linear-gradient(135deg, rgba(12, 20, 35, 0.94), rgba(21, 33, 54, 0.88)),
    radial-gradient(circle at 12% 20%, rgba(21, 94, 239, 0.4), transparent 28%);
  box-shadow: 0 24px 70px rgba(12, 20, 35, 0.2);
  backdrop-filter: blur(20px);
}

.brand-area,
.user-area,
.top-menu-item,
.user-menu {
  display: flex;
  align-items: center;
}

.brand-area {
  gap: 12px;
}

.brand-mark {
  width: 52px;
  height: 52px;
  display: grid;
  place-items: center;
  border-radius: 18px;
  color: #ffffff;
  background: linear-gradient(135deg, #155eef, #0f9f7a);
  box-shadow: 0 18px 38px rgba(21, 94, 239, 0.36);
}

.brand-mark svg {
  width: 28px;
  height: 28px;
}

.brand-name {
  font-size: 18px;
  font-weight: 900;
  letter-spacing: 0.8px;
}

.brand-desc {
  margin-top: 3px;
  color: rgba(255, 255, 255, 0.62);
  font-size: 12px;
}

.top-menu {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 7px;
  border: 1px solid rgba(255, 255, 255, 0.1);
  border-radius: 999px;
  background: rgba(255, 255, 255, 0.07);
}

.top-menu-item {
  gap: 8px;
  padding: 11px 16px;
  border-radius: 999px;
  color: rgba(255, 255, 255, 0.66);
  text-decoration: none;
  font-weight: 800;
  transition: all 0.22s ease;
}

.top-menu-item:hover {
  color: #ffffff;
  background: rgba(255, 255, 255, 0.08);
}

.top-menu-item.active {
  color: #0b1220;
  background: #ffffff;
  box-shadow: 0 12px 30px rgba(255, 255, 255, 0.18);
}

.menu-icon {
  width: 18px;
  height: 18px;
  display: grid;
  place-items: center;
}

.menu-icon :deep(svg) {
  width: 18px;
  height: 18px;
}

.user-area {
  justify-content: flex-end;
  gap: 10px;
}

.user-avatar {
  width: 44px;
  height: 44px;
  display: grid;
  place-items: center;
  border-radius: 16px;
  color: #ffffff;
  font-weight: 900;
  background: linear-gradient(135deg, #0f9f7a, #155eef);
}

.user-menu {
  gap: 8px;
  border: none;
  color: rgba(255, 255, 255, 0.9);
  background: transparent;
  cursor: pointer;
  font-weight: 800;
}

.chevron-icon {
  width: 16px;
  height: 16px;
}

.content-area {
  padding: 26px 4px 10px;
}

@media (max-width: 980px) {
  .top-nav {
    grid-template-columns: 1fr;
    position: static;
  }

  .top-menu {
    justify-content: center;
    flex-wrap: wrap;
    border-radius: 22px;
  }

  .user-area {
    justify-content: flex-start;
  }
}

@media (max-width: 640px) {
  .app-container {
    padding: 10px;
  }

  .top-menu-item {
    flex: 1;
    justify-content: center;
    padding: 10px;
  }
}
</style>
