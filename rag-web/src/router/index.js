import { createRouter, createWebHistory } from 'vue-router'

const routes = [
  {
    path: '/login',
    name: 'Login',
    component: () => import('../views/login/LoginView.vue')
  },
  {
    path: '/',
    redirect: '/kb'
  },
  {
    path: '/kb',
    name: 'KnowledgeBase',
    component: () => import('../views/kb/KbView.vue'),
    meta: { requiresAuth: true }
  },
  {
    path: '/file/:kbId',
    name: 'File',
    component: () => import('../views/file/FileView.vue'),
    meta: { requiresAuth: true }
  },
  {
    path: '/chat',
    name: 'Chat',
    component: () => import('../views/chat/ChatView.vue'),
    meta: { requiresAuth: true }
  },
  {
    path: '/extract',
    name: 'Extract',
    component: () => import('../views/extract/ExtractView.vue'),
    meta: { requiresAuth: true }
  },
  {
    path: '/settings',
    name: 'ModelSettings',
    component: () => import('../views/settings/ModelSettings.vue'),
    meta: { requiresAuth: true }
  },
  {
    path: '/stats',
    name: 'Stats',
    component: () => import('../views/stats/StatsView.vue'),
    meta: { requiresAuth: true }
  }
]

const router = createRouter({
  history: createWebHistory(),
  routes
})

router.beforeEach((to, from, next) => {
  const token = localStorage.getItem('token')
  if (to.meta.requiresAuth && !token) {
    next('/login')
  } else {
    next()
  }
})

export default router
