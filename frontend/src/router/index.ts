import { createRouter, createWebHistory } from 'vue-router'
import HomeView from '../views/HomeView.vue'
import LoginView from '../views/LoginView.vue'
import ProfileView from '../views/ProfileView.vue'
import PricingView from '../views/PricingView.vue'
import KnowledgeBaseView from '../views/KnowledgeBaseView.vue'
import SettingsView from '../views/SettingsView.vue'
import LandingView from '../views/LandingView.vue'

const router = createRouter({
  history: createWebHistory(import.meta.env.BASE_URL),
  routes: [
    {
      path: '/login',
      name: 'login',
      component: LoginView,
      meta: { requiresAuth: false },
    },
    {
      path: '/',
      name: 'landing',
      component: LandingView,
      meta: { requiresAuth: false, isLanding: true },
    },
    {
      path: '/app/',
      name: 'home',
      component: HomeView,
      meta: { requiresAuth: true },
    },
    {
      path: '/about',
      name: 'about',
      component: () => import(/* @vite-ignore */ '../views/AboutView.vue'),
      meta: { requiresAuth: true },
    },
    {
      path: '/profile',
      name: 'profile',
      component: ProfileView,
      meta: { requiresAuth: true },
    },
    {
      path: '/pricing',
      name: 'pricing',
      component: PricingView,
      meta: { requiresAuth: true },
    },
    {
      path: '/knowledge-base',
      name: 'knowledge-base',
      component: KnowledgeBaseView,
      meta: { requiresAuth: true },
    },
    {
      path: '/settings',
      name: 'settings',
      component: SettingsView,
      meta: { requiresAuth: true },
    },
  ],
})

// 路由守卫
router.beforeEach((to, from, next) => {
  const requiresAuth = to.meta.requiresAuth !== false
  const token = localStorage.getItem('token')
  const isLanding = to.meta.isLanding

  // 落地页 — 始终允许
  if (isLanding) {
    next()
    return
  }

  // 如果需要认证且没有token，跳转登录页
  if (requiresAuth && !token) {
    next('/login')
    return
  }

  // 如果已登录且访问登录页，跳转首页
  if (to.path === '/login' && token) {
    next('/app/')
    return
  }

  // 其他情况正常放行
  next()
})

export default router
