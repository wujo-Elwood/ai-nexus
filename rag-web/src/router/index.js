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
    path: '/knowledge-gaps',
    name: 'KnowledgeGaps',
    component: () => import('../views/knowledgegap/KnowledgeGapView.vue'),
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
  },
  {
    path: '/showcase',
    name: 'ParticleShowcase',
    component: () => import('../views/showcase/ParticleShowView.vue'),
    meta: { requiresAuth: true }
  },
  {
    path: '/cyber-city',
    name: 'CyberCity',
    component: () => import('../views/showcase/CyberCityView.vue'),
    meta: { requiresAuth: true }
  },
  {
    path: '/jarvis',
    name: 'JarvisHud',
    component: () => import('../views/showcase/JarvisHudView.vue'),
    meta: { requiresAuth: true }
  },
  {
    path: '/fractal-tunnel',
    name: 'FractalTunnel',
    component: () => import('../views/showcase/FractalTunnelView.vue'),
    meta: { requiresAuth: true }
  },
  {
    path: '/black-hole',
    name: 'BlackHole',
    component: () => import('../views/showcase/BlackHoleView.vue'),
    meta: { requiresAuth: true }
  },
  {
    path: '/storm',
    name: 'Storm',
    component: () => import('../views/showcase/StormView.vue'),
    meta: { requiresAuth: true }
  },
  {
    path: '/digital-life',
    name: 'DigitalLife',
    component: () => import('../views/showcase/DigitalLifeView.vue'),
    meta: { requiresAuth: true }
  },
  {
    path: '/aurora',
    name: 'Aurora',
    component: () => import('../views/showcase/AuroraView.vue'),
    meta: { requiresAuth: true }
  },
  {
    path: '/fluid-smoke',
    name: 'FluidSmoke',
    component: () => import('../views/showcase/FluidSmokeView.vue'),
    meta: { requiresAuth: true }
  },
  {
    path: '/volumetric-light',
    name: 'VolumetricLight',
    component: () => import('../views/showcase/VolumetricLightView.vue'),
    meta: { requiresAuth: true }
  },
  {
    path: '/ink-wash',
    name: 'InkWash',
    component: () => import('../views/showcase/InkWashView.vue'),
    meta: { requiresAuth: true }
  },
  {
    path: '/tesseract',
    name: 'Tesseract',
    component: () => import('../views/showcase/TesseractView.vue'),
    meta: { requiresAuth: true }
  },
  {
    path: '/supernova',
    name: 'Supernova',
    component: () => import('../views/showcase/SupernovaView.vue'),
    meta: { requiresAuth: true }
  },
  {
    path: '/liquid-metal',
    name: 'LiquidMetal',
    component: () => import('../views/showcase/LiquidMetalView.vue'),
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
