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
    path: '/kb/:kbId/insights',
    name: 'KnowledgeBaseInsights',
    component: () => import('../views/kb/KnowledgeBaseInsightsView.vue'),
    meta: { requiresAuth: true }
  },
  {
    path: '/chat',
    name: 'Chat',
    component: () => import('../views/chat/ChatView.vue'),
    meta: { requiresAuth: true }
  },
  {
    path: '/image',
    name: 'ImageGenerate',
    component: () => import('../views/image/ImageGenerateView.vue'),
    meta: { requiresAuth: true }
  },
  {
    path: '/extract',
    name: 'Extract',
    component: () => import('../views/extract/ExtractView.vue'),
    meta: { requiresAuth: true }
  },
  {
    path: '/agents',
    name: 'Agents',
    component: () => import('../views/agent/AgentView.vue'),
    meta: { requiresAuth: true }
  },
  {
    path: '/agents/kb-quality',
    name: 'KnowledgeQualityAgent',
    component: () => import('../views/agent/KnowledgeQualityAgentView.vue'),
    meta: { requiresAuth: true }
  },
  {
    path: '/tasks',
    name: 'TaskCenter',
    component: () => import('../views/task/TaskCenterView.vue'),
    meta: { requiresAuth: true }
  },
  {
    path: '/eval',
    name: 'EvalCenter',
    component: () => import('../views/eval/EvalCenterView.vue'),
    meta: { requiresAuth: true }
  },
  {
    path: '/rbac',
    name: 'RbacManage',
    component: () => import('../views/rbac/RbacManageView.vue'),
    meta: { requiresAuth: true }
  },
  {
    path: '/settings',
    name: 'ModelSettings',
    component: () => import('../views/settings/ModelSettings.vue'),
    meta: { requiresAuth: true }
  },
  {
    path: '/profile',
    name: 'Profile',
    component: () => import('../views/user/ProfileView.vue'),
    meta: { requiresAuth: true }
  },
  {
    path: '/change-password',
    name: 'ChangePassword',
    component: () => import('../views/user/ChangePasswordView.vue'),
    meta: { requiresAuth: true }
  },
  {
    path: '/stats',
    name: 'Stats',
    component: () => import('../views/stats/StatsView.vue'),
    meta: { requiresAuth: true }
  },
  {
    path: '/health',
    name: 'SystemHealth',
    component: () => import('../views/health/SystemHealthView.vue'),
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
