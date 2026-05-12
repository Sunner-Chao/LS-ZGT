<script setup lang="ts">
import { ref, computed, onMounted, watch } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { User, ChatDotRound, FolderOpened, Setting, CreditCard, SwitchButton, ArrowLeft, ArrowRight } from '@element-plus/icons-vue'
import AgentFloatingButton from './components/agent/AgentFloatingButton.vue'

const router = useRouter()
const route = useRoute()

// 状态管理
const isLoggedIn = ref(false)
const sidebarCollapsed = ref(false)

// 当前路由名称用于面包屑和侧边栏高亮
const isLanding = computed(() => route.meta?.isLanding === true)

const currentRouteName = computed(() => {
  const path = route.path
  if (path.startsWith('/app') || path === '/') return 'home'
  if (path === '/knowledge-base') return 'knowledge-base'
  if (path === '/settings') return 'settings'
  if (path === '/profile') return 'profile'
  if (path === '/pricing') return 'pricing'
  return 'home'
})

const breadcrumbTitle = computed(() => {
  const map: Record<string, string> = {
    home: '智能对话',
    'knowledge-base': '知识库管理',
    settings: 'AI 配置',
    profile: '个人中心',
    pricing: '套餐方案'
  }
  return map[currentRouteName.value] || '智能对话'
})

// 导航菜单
const navItems = [
  { key: 'home', icon: ChatDotRound, label: '智能对话', path: '/app/' },
  { key: 'knowledge-base', icon: FolderOpened, label: '知识库管理', path: '/knowledge-base' },
  { key: 'settings', icon: Setting, label: 'AI 配置', path: '/settings' },
]

const handleNavClick = (path: string) => {
  router.push(path)
}

const handleLogout = () => {
  localStorage.removeItem('token')
  isLoggedIn.value = false
  router.push('/login')
}

const handleDropdownCommand = (command: string) => {
  if (command === 'profile') {
    router.push('/profile')
  } else if (command === 'logout') {
    handleLogout()
  }
}

const toggleSidebar = () => {
  sidebarCollapsed.value = !sidebarCollapsed.value
}

onMounted(() => {
  const token = localStorage.getItem('token')
  isLoggedIn.value = !!token
})

// 监听路由变化更新登录状态
watch(() => route.path, () => {
  const token = localStorage.getItem('token')
  isLoggedIn.value = !!token
})
</script>

<template>
  <div class="app-container">
    <!-- 登录页和落地页独立渲染 -->
    <template v-if="$route.path === '/login' || isLanding">
      <router-view />
    </template>

    <!-- 主布局：侧边栏 + 顶栏 + 内容 -->
    <template v-else>
      <aside class="app-sidebar" :class="{ collapsed: sidebarCollapsed }">
        <!-- Logo -->
        <div class="sidebar-logo">
          <div class="sidebar-logo-icon">
            <img src="/logo.png" alt="Logo" />
          </div>
          <div class="sidebar-logo-text">
            <h1 class="sidebar-logo-title">筑规通</h1>
            <span class="sidebar-logo-subtitle">ZhuGuiTong AI</span>
          </div>
        </div>

        <!-- 导航菜单 -->
        <nav class="sidebar-nav">
          <div class="sidebar-nav-group">
            <div
              v-for="item in navItems"
              :key="item.key"
              class="sidebar-nav-item"
              :class="{ active: currentRouteName === item.key }"
              @click="handleNavClick(item.path)"
            >
              <el-icon class="nav-icon"><component :is="item.icon" /></el-icon>
              <span class="nav-label">{{ item.label }}</span>
            </div>
          </div>
        </nav>

        <!-- 收缩按钮 -->
        <div class="sidebar-collapse-btn" @click="toggleSidebar">
          <el-icon size="18">
            <ArrowLeft v-if="!sidebarCollapsed" />
            <ArrowRight v-else />
          </el-icon>
        </div>
      </aside>

      <div class="app-right-panel">
        <!-- 顶部导航栏 -->
        <header class="app-top-header">
          <div class="header-breadcrumb">
            <span class="breadcrumb-current">{{ breadcrumbTitle }}</span>
          </div>

          <div class="header-right">
            <!-- 用户下拉菜单 -->
            <el-dropdown v-if="isLoggedIn" trigger="click" @command="handleDropdownCommand" placement="bottom-end">
              <div class="user-avatar-wrapper">
                <el-avatar class="user-avatar" :size="36">
                  <el-icon><User /></el-icon>
                </el-avatar>
                <span class="avatar-status"></span>
              </div>
              <template #dropdown>
                <el-dropdown-menu class="user-dropdown">
                  <div class="dropdown-header">
                    <el-avatar class="dropdown-avatar" :size="44">
                      <el-icon><User /></el-icon>
                    </el-avatar>
                    <div class="dropdown-user-info">
                      <span class="dropdown-username">Admin</span>
                      <span class="dropdown-email">admin@luanshu.com</span>
                    </div>
                  </div>
                  <el-dropdown-item command="profile" class="dropdown-item">
                    <el-icon><User /></el-icon>
                    <span>个人中心</span>
                  </el-dropdown-item>
                  <el-dropdown-item divided command="logout" class="dropdown-item logout-item">
                    <el-icon><SwitchButton /></el-icon>
                    <span>退出登录</span>
                  </el-dropdown-item>
                </el-dropdown-menu>
              </template>
            </el-dropdown>

            <el-button v-else type="primary" size="small" class="auth-btn" @click="router.push('/login')">
              登录
            </el-button>
          </div>
        </header>

        <!-- 主内容区域 -->
        <main class="app-main-content">
          <router-view v-slot="{ Component }">
            <transition name="page" mode="out-in">
              <div class="transition-wrapper">
                <component :is="Component" />
              </div>
            </transition>
          </router-view>
        </main>
      </div>
    </template>

    <!-- AI 助手浮动按钮 -->
    <AgentFloatingButton v-if="isLoggedIn && !isLanding" />
  </div>
</template>

<style scoped>
.app-container {
  min-height: 100vh;
  min-height: 100dvh;
  display: flex;
  background: var(--bg-secondary);
}

/* 侧边栏 */
.app-sidebar {
  width: 240px;
  min-width: 240px;
  max-width: 240px;
  height: 100vh;
  background: linear-gradient(180deg, #1e293b 0%, #0f172a 100%);
  display: flex;
  flex-direction: column;
  transition: all 0.3s cubic-bezier(0.4, 0, 0.2, 1);
  position: fixed;
  left: 0;
  top: 0;
  z-index: 100;
  overflow: hidden;
}

.app-sidebar.collapsed {
  width: 64px;
  min-width: 64px;
  max-width: 64px;
}

.sidebar-logo {
  height: 64px;
  display: flex;
  align-items: center;
  padding: 0 16px;
  gap: 12px;
  border-bottom: 1px solid rgba(255, 255, 255, 0.08);
  flex-shrink: 0;
  overflow: hidden;
}

.sidebar-logo-icon {
  width: 36px;
  height: 36px;
  display: flex;
  align-items: center;
  justify-content: center;
  background: #FFFFFF;
  border-radius: 10px;
  flex-shrink: 0;
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.15);
}

.sidebar-logo-icon img {
  width: 26px;
  height: 26px;
  object-fit: contain;
  filter: none;
}

.sidebar-logo-text {
  display: flex;
  flex-direction: column;
  gap: 1px;
  overflow: hidden;
  white-space: nowrap;
  opacity: 1;
  transition: opacity 0.2s ease;
}

.app-sidebar.collapsed .sidebar-logo-text {
  opacity: 0;
  width: 0;
}

.sidebar-logo-title {
  margin: 0;
  font-size: 0.95rem;
  font-weight: 700;
  color: #f1f5f9;
  letter-spacing: 0.02em;
  line-height: 1.3;
  overflow: hidden;
  text-overflow: ellipsis;
}

.sidebar-logo-subtitle {
  font-size: 0.6rem;
  color: rgba(148, 163, 184, 0.8);
  letter-spacing: 0.5px;
  text-transform: uppercase;
  font-weight: 500;
}

.sidebar-nav {
  flex: 1;
  padding: 12px 8px;
  overflow-y: auto;
  overflow-x: hidden;
}

.sidebar-nav-group {
  margin-bottom: 8px;
}

.sidebar-nav-group-title {
  font-size: 0.65rem;
  font-weight: 600;
  color: rgba(148, 163, 184, 0.5);
  text-transform: uppercase;
  letter-spacing: 0.08em;
  padding: 8px 12px 6px;
  white-space: nowrap;
  overflow: hidden;
  transition: opacity 0.2s ease;
}

.app-sidebar.collapsed .sidebar-nav-group-title {
  opacity: 0;
  height: 0;
  padding: 0;
  margin: 0;
}

.sidebar-nav-item {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 10px 12px;
  border-radius: 10px;
  color: rgba(203, 213, 225, 0.9);
  font-size: 0.875rem;
  font-weight: 500;
  cursor: pointer;
  transition: all 0.2s ease;
  white-space: nowrap;
  overflow: hidden;
  text-decoration: none;
  position: relative;
  margin-bottom: 2px;
}

.sidebar-nav-item:hover {
  background: rgba(59, 130, 246, 0.12);
  color: #fed7aa;
}

.sidebar-nav-item.active {
  background: linear-gradient(135deg, rgba(59, 130, 246, 0.2) 0%, rgba(37, 99, 235, 0.15) 100%);
  color: #1d4ed8;
  font-weight: 600;
}

.sidebar-nav-item.active::before {
  content: '';
  position: absolute;
  left: 0;
  top: 50%;
  transform: translateY(-50%);
  width: 3px;
  height: 20px;
  background: linear-gradient(180deg, #3b82f6 0%, #2563eb 100%);
  border-radius: 0 3px 3px 0;
}

.sidebar-nav-item .nav-icon {
  font-size: 1.2rem;
  flex-shrink: 0;
  width: 20px;
  display: flex;
  align-items: center;
  justify-content: center;
}

.sidebar-nav-item .nav-label {
  overflow: hidden;
  text-overflow: ellipsis;
  opacity: 1;
  transition: opacity 0.2s ease;
}

.app-sidebar.collapsed .sidebar-nav-item .nav-label {
  opacity: 0;
  width: 0;
}

.sidebar-collapse-btn {
  height: 48px;
  display: flex;
  align-items: center;
  justify-content: center;
  border-top: 1px solid rgba(255, 255, 255, 0.06);
  cursor: pointer;
  color: rgba(148, 163, 184, 0.6);
  transition: all 0.2s ease;
  flex-shrink: 0;
}

.sidebar-collapse-btn:hover {
  color: #1d4ed8;
  background: rgba(59, 130, 246, 0.08);
}

/* 右侧面板 */
.app-right-panel {
  flex: 1;
  display: flex;
  flex-direction: column;
  min-width: 0;
  overflow: hidden;
  margin-left: 240px;
  transition: margin-left 0.3s cubic-bezier(0.4, 0, 0.2, 1);
}

.app-sidebar.collapsed ~ .app-right-panel {
  margin-left: 64px;
}

/* 顶部导航栏 */
.app-top-header {
  height: 64px;
  background: rgba(255, 255, 255, 0.85);
  backdrop-filter: blur(20px);
  -webkit-backdrop-filter: blur(20px);
  border-bottom: 1px solid var(--border-light);
  padding: 0 24px;
  display: flex;
  justify-content: space-between;
  align-items: center;
  position: sticky;
  top: 0;
  z-index: var(--z-sticky);
  transition: all 0.3s ease;
  flex-shrink: 0;
}

.app-top-header::before {
  content: '';
  position: absolute;
  inset: 0;
  background: linear-gradient(180deg, rgba(255,255,255,0.98) 0%, rgba(255,255,255,0.92) 100%);
  z-index: -1;
}

.header-breadcrumb {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 0.875rem;
  color: var(--text-secondary);
}

.breadcrumb-current {
  color: var(--text-primary);
  font-weight: 700;
  font-size: 1.1rem;
  letter-spacing: -0.01em;
}

.header-right {
  display: flex;
  align-items: center;
  gap: 12px;
}

/* 用户头像 */
.user-avatar-wrapper {
  position: relative;
  cursor: pointer;
  transition: transform 0.2s ease;
  display: flex;
  align-items: center;
  gap: 10px;
}

.user-avatar-wrapper:hover {
  transform: scale(1.05);
}

.user-avatar {
  background: linear-gradient(135deg, #3b82f6 0%, #2563eb 100%);
  border: 2px solid transparent;
  box-shadow: 0 2px 8px rgba(59, 130, 246, 0.25);
  transition: all 0.2s ease;
}

.user-avatar-wrapper:hover .user-avatar {
  box-shadow: 0 4px 12px rgba(59, 130, 246, 0.35);
}

.avatar-status {
  position: absolute;
  bottom: 2px;
  right: 2px;
  width: 10px;
  height: 10px;
  background: var(--success-color);
  border: 2px solid white;
  border-radius: 50%;
}

/* 下拉菜单 */
:deep(.user-dropdown) {
  padding: 0.5rem !important;
  min-width: 220px;
  border-radius: var(--radius-xl) !important;
  box-shadow: 0 10px 40px rgba(0, 0, 0, 0.15) !important;
  border: 1px solid var(--border-light) !important;
  overflow: hidden;
}

.dropdown-header {
  display: flex;
  align-items: center;
  gap: 0.75rem;
  padding: 1rem;
  background: linear-gradient(135deg, rgba(59, 130, 246, 0.06) 0%, rgba(37, 99, 235, 0.04) 100%);
  margin-bottom: 0.5rem;
}

.dropdown-avatar {
  background: linear-gradient(135deg, #3b82f6 0%, #2563eb 100%);
}

.dropdown-user-info {
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.dropdown-username {
  font-weight: 600;
  font-size: 0.95rem;
  color: var(--text-primary);
}

.dropdown-email {
  font-size: 0.75rem;
  color: var(--text-tertiary);
}

.dropdown-item {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  padding: 0.625rem 1rem !important;
  border-radius: var(--radius-md) !important;
  margin: 0.25rem 0.5rem !important;
  font-size: 0.875rem;
  transition: all 0.2s ease !important;
}

.dropdown-item:hover {
  background: rgba(59, 130, 246, 0.08) !important;
  color: #3b82f6 !important;
}

.dropdown-item .el-icon {
  font-size: 1rem;
}

.logout-item:hover {
  background: rgba(239, 68, 68, 0.08) !important;
  color: var(--danger-color) !important;
}

/* 登录按钮 */
.auth-btn {
  background: linear-gradient(135deg, #3b82f6 0%, #2563eb 100%) !important;
  border: none !important;
  border-radius: var(--radius-lg) !important;
  font-weight: 600 !important;
  box-shadow: 0 2px 8px rgba(59, 130, 246, 0.3);
}

.auth-btn:hover {
  transform: translateY(-1px);
  box-shadow: 0 4px 12px rgba(59, 130, 246, 0.4);
}

/* 主内容区域 */
.app-main-content {
  flex: 1;
  overflow: visible;
  min-height: 0;
}

/* 页面切换动画 */
.transition-wrapper {
  width: 100%;
  min-height: 100%;
}

.page-enter-active,
.page-leave-active {
  transition: all 0.6s ease-out;
}

.page-enter-from {
  opacity: 0;
  transform: translateY(30px);
}

.page-leave-to {
  opacity: 0;
  transform: translateY(-30px);
}

/* 响应式设计 */
@media (max-width: 1024px) {
  .app-sidebar {
    width: 64px;
    min-width: 64px;
    max-width: 64px;
  }

  .app-sidebar .sidebar-logo-text {
    opacity: 0;
    width: 0;
  }

  .app-sidebar .sidebar-nav-group-title {
    opacity: 0;
    height: 0;
    padding: 0;
  }

  .app-sidebar .sidebar-nav-item .nav-label {
    opacity: 0;
    width: 0;
  }

  .app-right-panel {
    margin-left: 64px;
  }
}

@media (max-width: 768px) {
  .app-sidebar {
    display: none;
  }

  .app-right-panel {
    margin-left: 0;
  }

  .app-top-header {
    padding: 0 16px;
  }
}
</style>
