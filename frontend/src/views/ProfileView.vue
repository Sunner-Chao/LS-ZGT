<template>
  <div class="profile-container">
    <!-- 页面头部 -->
    <div class="profile-header">
      <div class="header-content">
        <div class="header-left">
        </div>
      </div>
    </div>

    <!-- 主要内容 -->
    <div class="profile-content">
      <!-- 用户信息卡片 -->
      <div class="user-card">
        <div class="user-card-bg"></div>
        <div class="user-card-content">
          <div class="user-avatar-section">
            <div class="avatar-wrapper">
              <el-avatar :size="100" icon="User" class="user-avatar" />
              <span class="avatar-status"></span>
            </div>
            <div class="user-info">
              <h2 class="user-name">{{ userInfo.username }}</h2>
              <p class="user-id">ID: {{ userInfo.userId }}</p>
              <p class="user-date">注册时间: {{ formatDate(userInfo.createTime) }}</p>
            </div>
          </div>
        </div>
      </div>

      <!-- 订阅信息 -->
      <div class="subscription-section">
        <div class="section-header">
          <h3>
            <el-icon class="section-icon"><CreditCard /></el-icon>
            订阅信息
          </h3>
          <el-button type="primary" @click="goToPricing" class="upgrade-btn">
            查看套餐详情
          </el-button>
        </div>

        <div class="subscription-grid">
          <div class="subscription-card plan-card">
            <div class="plan-badge">{{ subscriptionInfo.planName }}</div>
            <p class="plan-desc">{{ subscriptionInfo.planDesc }}</p>
            <div class="plan-expire">
              <span class="expire-label">到期时间</span>
              <span class="expire-date">{{ formatDate(subscriptionInfo.expireDate) }}</span>
            </div>
            <el-button type="warning" size="small" @click="renewSubscription" class="renew-btn">
              续费
            </el-button>
          </div>

          <div class="subscription-card usage-card">
            <h4>使用情况</h4>
            <div class="usage-list">
              <div class="usage-item">
                <div class="usage-header">
                  <span class="usage-label">Token余额</span>
                  <span class="usage-value">{{ subscriptionInfo.tokenBalance.toLocaleString() }}</span>
                </div>
                <div class="usage-bar">
                  <div class="usage-bar-fill" style="width: 75%"></div>
                </div>
              </div>
              <div class="usage-item">
                <div class="usage-header">
                  <span class="usage-label">知识库</span>
                  <span class="usage-value">{{ subscriptionInfo.kbCount }} / {{ subscriptionInfo.kbLimit }}</span>
                </div>
                <div class="usage-bar">
                  <div class="usage-bar-fill" :style="{ width: (subscriptionInfo.kbCount / subscriptionInfo.kbLimit * 100) + '%' }"></div>
                </div>
              </div>
              <div class="usage-item">
                <div class="usage-header">
                  <span class="usage-label">文件数量</span>
                  <span class="usage-value">{{ subscriptionInfo.fileCount }} / {{ subscriptionInfo.fileLimit }}</span>
                </div>
                <div class="usage-bar">
                  <div class="usage-bar-fill" :style="{ width: (subscriptionInfo.fileCount / subscriptionInfo.fileLimit * 100) + '%' }"></div>
                </div>
              </div>
            </div>
          </div>
        </div>
      </div>

      <!-- 使用记录 -->
      <div class="history-section">
        <div class="section-header">
          <h3>
            <el-icon class="section-icon"><Clock /></el-icon>
            使用记录
          </h3>
        </div>

        <div class="history-table-wrapper">
          <el-table :data="usageHistory" class="history-table">
            <el-table-column prop="date" label="日期" width="120">
              <template #default="{ row }">
                <span class="date-cell">{{ row.date }}</span>
              </template>
            </el-table-column>
            <el-table-column prop="action" label="操作" width="120">
              <template #default="{ row }">
                <el-tag :type="getActionType(row.action)" size="small">{{ row.action }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="tokens" label="Token消耗" width="120">
              <template #default="{ row }">
                <span class="token-cell">{{ row.tokens }}</span>
              </template>
            </el-table-column>
            <el-table-column prop="description" label="描述">
              <template #default="{ row }">
                <span class="desc-cell">{{ row.description }}</span>
              </template>
            </el-table-column>
          </el-table>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { CreditCard, Clock } from '@element-plus/icons-vue'

const router = useRouter()

// 用户信息
const userInfo = ref({
  username: 'Admin',
  userId: 'U001',
  createTime: new Date('2024-01-01')
})

// 订阅信息
const subscriptionInfo = ref({
  planName: '专业版',
  planDesc: '适合个人和小团队使用',
  tokenBalance: 10000,
  kbCount: 3,
  kbLimit: 10,
  fileCount: 25,
  fileLimit: 100,
  expireDate: new Date('2024-12-31')
})

// 使用记录
const usageHistory = ref([
  {
    date: '2024-01-15',
    action: '上传文件',
    tokens: 50,
    description: '上传了3个PDF文档到知识库'
  },
  {
    date: '2024-01-14',
    action: 'AI对话',
    tokens: 200,
    description: '进行了5次AI问答对话'
  },
  {
    date: '2024-01-13',
    action: '创建知识库',
    tokens: 0,
    description: '创建了新的知识库'
  }
])

// 格式化日期
const formatDate = (date: Date) => {
  return date.toLocaleDateString('zh-CN')
}

// 跳转到收费详情页面
const goToPricing = () => {
  router.push('/pricing')
}

// 续费
const renewSubscription = () => {
  router.push('/pricing')
}

// 获取操作类型
const getActionType = (action: string) => {
  const types: Record<string, string> = {
    '上传文件': 'primary',
    'AI对话': 'success',
    '创建知识库': 'warning'
  }
  return types[action] || 'info'
}

onMounted(() => {
  // 这里可以加载用户数据
})
</script>

<style scoped>
/* 容器 */
.profile-container {
  min-height: 100vh;
  display: flex;
  flex-direction: column;
  background: #ffffff;
  color: #000000;
  animation: fadeInUp 0.6s ease-out;
}

@keyframes fadeInUp {
  from { opacity: 0; transform: translateY(20px); }
  to { opacity: 1; transform: translateY(0); }
}

/* 页面头部 */
.profile-header {
  background: rgba(255, 255, 255, 0.85);
  backdrop-filter: blur(12px);
  border-bottom: 1px solid var(--border-light);
  position: relative;
}

.profile-header::before {
  display: none;
}

.header-content {
  max-width: 1200px;
  margin: 0 auto;
  padding: 1.5rem 2rem;
  display: flex;
  justify-content: space-between;
  align-items: center;
  position: relative;
  z-index: 1;
}

.header-left {
  display: flex;
  align-items: center;
  gap: 1rem;
}

.back-btn {
  background: rgba(255, 255, 255, 0.08) !important;
  border: 1px solid rgba(255, 255, 255, 0.15) !important;
  color: white !important;
  border-radius: var(--radius-lg) !important;
  transition: all 0.25s ease !important;
}

.back-btn:hover {
  background: rgba(255, 255, 255, 0.15) !important;
  transform: translateX(-2px);
}

.header-left h1 {
  margin: 0;
  font-size: 1.75rem;
  font-weight: 700;
  color: #000000;
}

/* 主内容区域 */
.profile-content {
  flex: 1;
  overflow-y: auto;
  padding: 2rem;
  max-width: 1200px;
  margin: 0 auto;
  width: 100%;
}

/* 用户卡片 */
.user-card {
  position: relative;
  background: white;
  border-radius: var(--radius-2xl);
  overflow: hidden;
  box-shadow: 0 4px 24px rgba(0, 0, 0, 0.06);
  margin-bottom: 2rem;
}

.user-card-bg {
  height: 120px;
  background: linear-gradient(135deg, #3b82f6 0%, #2563eb 50%, #1d4ed8 100%);
  position: relative;
}

.user-card-bg::after {
  content: '';
  position: absolute;
  inset: 0;
  background: url("data:image/svg+xml,%3Csvg width='60' height='60' viewBox='0 0 60 60' xmlns='http://www.w3.org/2000/svg'%3E%3Cg fill='none' fill-rule='evenodd'%3E%3Cg fill='%23ffffff' fill-opacity='0.1'%3E%3Cpath d='M36 34v-4h-2v4h-4v2h4v4h2v-4h4v-2h-4zm0-30V0h-2v4h-4v2h4v4h2V6h4V4h-4zM6 34v-4H4v4H0v2h4v4h2v-4h4v-2H6zM6 4V0H4v4H0v2h4v4h2V6h4V4H6z'/%3E%3C/g%3E%3C/g%3E%3C/svg%3E");
}

.user-card-content {
  padding: 0 2rem 2rem;
}

.user-avatar-section {
  display: flex;
  align-items: center;
  gap: 1.5rem;
  margin-top: -50px;
  position: relative;
}

.avatar-wrapper {
  position: relative;
  flex-shrink: 0;
}

.user-avatar {
  background: linear-gradient(135deg, #3b82f6 0%, #2563eb 100%);
  border: 4px solid white;
  box-shadow: 0 4px 12px rgba(0, 0, 0, 0.15);
}

.avatar-status {
  position: absolute;
  bottom: 8px;
  right: 8px;
  width: 20px;
  height: 20px;
  background: #10b981;
  border: 3px solid white;
  border-radius: 50%;
}

.user-info {
  padding-top: 60px;
}

.user-name {
  margin: 0 0 0.25rem;
  font-size: 1.5rem;
  font-weight: 700;
  color: #000000;
}

.user-id, .user-date {
  margin: 0;
  font-size: 0.875rem;
  color: #333333;
}

/* 订阅区域 */
.subscription-section {
  margin-bottom: 2rem;
}

.section-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 1.25rem;
}

.section-header h3 {
  margin: 0;
  font-size: 1.25rem;
  font-weight: 600;
  color: #000000;
  display: flex;
  align-items: center;
  gap: 0.5rem;
}

.section-icon {
  font-size: 1.25rem;
  color: var(--primary-color);
}

.upgrade-btn {
  background: linear-gradient(135deg, #3b82f6 0%, #2563eb 100%) !important;
  border: none !important;
  border-radius: var(--radius-lg) !important;
  font-weight: 600 !important;
}

.subscription-grid {
  display: grid;
  grid-template-columns: 1fr 1.5fr;
  gap: 1.5rem;
}

.subscription-card {
  background: white;
  border-radius: var(--radius-xl);
  padding: 1.5rem;
  box-shadow: 0 4px 16px rgba(0, 0, 0, 0.04);
}

/* 套餐卡片 */
.plan-card {
  background: linear-gradient(135deg, rgba(59, 130, 246, 0.05) 0%, rgba(37, 99, 235, 0.03) 100%);
  border: 1px solid rgba(59, 130, 246, 0.1);
  display: flex;
  flex-direction: column;
  align-items: center;
  text-align: center;
}

.plan-badge {
  display: inline-block;
  padding: 0.5rem 1.5rem;
  background: linear-gradient(135deg, #3b82f6 0%, #2563eb 100%);
  color: white;
  border-radius: var(--radius-full);
  font-weight: 700;
  font-size: 1rem;
  margin-bottom: 0.75rem;
}

.plan-desc {
  color: #333333;
  font-size: 0.875rem;
  margin: 0 0 1.25rem;
}

.plan-expire {
  display: flex;
  flex-direction: column;
  gap: 0.25rem;
  margin-bottom: 1rem;
}

.expire-label {
  font-size: 0.75rem;
  color: #666666;
}

.expire-date {
  font-weight: 600;
  color: #3b82f6;
}

.renew-btn {
  border-radius: var(--radius-lg) !important;
  font-weight: 600 !important;
}

/* 使用情况卡片 */
.usage-card h4 {
  margin: 0 0 1rem;
  font-size: 1rem;
  font-weight: 600;
  color: #000000;
}

.usage-list {
  display: flex;
  flex-direction: column;
  gap: 1rem;
}

.usage-item {
  display: flex;
  flex-direction: column;
  gap: 0.5rem;
}

.usage-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.usage-label {
  font-size: 0.875rem;
  color: #333333;
}

.usage-value {
  font-weight: 600;
  color: var(--primary-color);
}

.usage-bar {
  height: 6px;
  background: var(--bg-tertiary);
  border-radius: var(--radius-full);
  overflow: hidden;
}

.usage-bar-fill {
  height: 100%;
  background: linear-gradient(90deg, #3b82f6 0%, #2563eb 100%);
  border-radius: var(--radius-full);
  transition: width 0.5s ease;
}

/* 使用记录 */
.history-section {
  margin-bottom: 2rem;
}

.history-table-wrapper {
  background: white;
  border-radius: var(--radius-xl);
  overflow: hidden;
  box-shadow: 0 4px 16px rgba(0, 0, 0, 0.04);
}

.history-table {
  width: 100%;
}

.date-cell {
  font-weight: 500;
  color: #000000;
}

.token-cell {
  font-weight: 600;
  color: var(--primary-color);
}

.desc-cell {
  color: #333333;
  font-size: 0.875rem;
}

/* 响应式 */
@media (max-width: 768px) {
  .profile-content {
    padding: 1rem;
  }

  .header-content {
    padding: 1rem;
  }

  .header-left h1 {
    font-size: 1.25rem;
  }

  .user-avatar-section {
    flex-direction: column;
    text-align: center;
    margin-top: -50px;
  }

  .user-info {
    padding-top: 1rem;
  }

  .subscription-grid {
    grid-template-columns: 1fr;
  }
}
</style>
