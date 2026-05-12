<template>
  <div class="pricing-container">
    <!-- 页面头部 -->
    <div class="pricing-header">
      <div class="header-content">
        <div class="header-left">
        </div>
      </div>
    </div>

    <!-- 主要内容 -->
    <div class="pricing-content">
      <!-- Token说明卡片 -->
      <div class="token-section">
        <div class="section-header">
          <h3>
            <el-icon class="section-icon"><Coin /></el-icon>
            Token收费说明
          </h3>
        </div>

        <div class="token-grid">
          <div class="token-card info-card">
            <div class="token-icon">
              <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5">
                <circle cx="12" cy="12" r="10"/>
                <path d="M12 6v6l4 2"/>
              </svg>
            </div>
            <h4>什么是Token？</h4>
            <p>Token是AI模型处理文本时的计量单位，用于计算AI对话、文档处理等服务的费用。1个Token约等于1.5个汉字或1个英文单词。</p>
          </div>

          <div class="token-card usage-card">
            <div class="token-icon">
              <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5">
                <path d="M9 5H7a2 2 0 00-2 2v12a2 2 0 002 2h10a2 2 0 002-2V7a2 2 0 00-2-2h-2"/>
                <rect x="9" y="3" width="6" height="4" rx="1"/>
                <path d="M9 12h6M9 16h6"/>
              </svg>
            </div>
            <h4>Token消耗规则</h4>
            <ul class="usage-list">
              <li><span class="usage-label">AI对话</span><span class="usage-value">50-200 Token/次</span></li>
              <li><span class="usage-label">文档上传</span><span class="usage-value">100-500 Token/个</span></li>
              <li><span class="usage-label">文档处理</span><span class="usage-value">200-1000 Token</span></li>
              <li><span class="usage-label">知识库创建</span><span class="usage-value free">免费</span></li>
            </ul>
          </div>

          <div class="token-card price-card">
            <div class="token-icon">
              <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5">
                <path d="M12 2v20M17 5H9.5a3.5 3.5 0 000 7h5a3.5 3.5 0 010 7H6"/>
              </svg>
            </div>
            <h4>Token价格</h4>
            <div class="price-info">
              <div class="main-price">
                <span class="price-value">¥0.001</span>
                <span class="price-unit">/Token</span>
              </div>
              <div class="discount-info">
                <span class="discount-badge">批量优惠</span>
                <span>满10000享9折</span>
              </div>
              <div class="discount-info">
                <span class="discount-badge enterprise">企业专享</span>
                <span>享8折优惠</span>
              </div>
            </div>
          </div>
        </div>
      </div>

      <!-- 套餐对比 -->
      <div class="plans-section">
        <div class="section-header">
          <h3>
            <el-icon class="section-icon"><Star /></el-icon>
            套餐对比
          </h3>
          <p class="section-desc">选择适合您的套餐方案</p>
        </div>

        <div class="plans-grid">
          <div
            v-for="plan in plans"
            :key="plan.id"
            class="plan-card"
            :class="{ 'popular': plan.id === 'pro' && !hasUserSelected, 'selected': plan.id === selectedPlan && hasUserSelected }"
            @click="selectPlan(plan)"
          >
            <div v-if="plan.id === 'pro' && !hasUserSelected" class="popular-badge">最受欢迎</div>

            <div v-if="plan.id === selectedPlan && hasUserSelected" class="selected-badge">
              <el-icon><Check /></el-icon>
              当前选择
            </div>

            <div class="plan-header">
              <h3 class="plan-name">{{ plan.name }}</h3>
              <div class="plan-price">
                <span class="currency">¥</span>
                <span class="amount">{{ plan.price }}</span>
                <span class="period">/月</span>
              </div>
              <p class="plan-desc">{{ plan.description }}</p>
            </div>

            <div class="plan-features">
              <div class="feature-item" v-for="feature in plan.features" :key="feature">
                <el-icon class="feature-icon"><Check /></el-icon>
                <span>{{ feature }}</span>
              </div>
            </div>

            <div class="plan-limits">
              <div class="limit-item">
                <span class="limit-label">知识库</span>
                <span class="limit-value">{{ plan.kbLimit }}</span>
              </div>
              <div class="limit-item">
                <span class="limit-label">文件数</span>
                <span class="limit-value">{{ plan.fileLimit }}</span>
              </div>
              <div class="limit-item">
                <span class="limit-label">Token额度</span>
                <span class="limit-value highlight">{{ plan.tokenBalance.toLocaleString() }}</span>
              </div>
            </div>

            <el-button
              :type="plan.id === selectedPlan && hasUserSelected ? 'primary' : 'default'"
              size="large"
              class="select-btn"
              :class="{ 'is-selected': plan.id === selectedPlan && hasUserSelected }"
            >
              {{ plan.id === selectedPlan && hasUserSelected ? '当前套餐' : '选择套餐' }}
            </el-button>
          </div>
        </div>
      </div>

      <!-- 常见问题 -->
      <div class="faq-section">
        <div class="section-header">
          <h3>
            <el-icon class="section-icon"><QuestionFilled /></el-icon>
            常见问题
          </h3>
        </div>

        <div class="faq-list">
          <div class="faq-item" v-for="(faq, index) in faqs" :key="index">
            <div class="faq-question">
              <el-icon class="q-icon"><QuestionFilled /></el-icon>
              <span>{{ faq.question }}</span>
            </div>
            <div class="faq-answer">{{ faq.answer }}</div>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { ElMessage } from 'element-plus'
import { Check, Coin, Star, QuestionFilled } from '@element-plus/icons-vue'

const plans = ref([
  {
    id: 'free',
    name: '免费版',
    price: 0,
    description: '适合个人试用体验',
    features: [
      '基础AI对话功能',
      '最多3个知识库',
      '最多10个文件',
      '1000 Token免费额度',
      '基础客服支持'
    ],
    kbLimit: 3,
    fileLimit: 10,
    tokenBalance: 1000
  },
  {
    id: 'pro',
    name: '专业版',
    price: 99,
    description: '适合个人和小团队使用',
    features: [
      '完整AI对话功能',
      '最多10个知识库',
      '最多100个文件',
      '10000 Token免费额度',
      '优先客服支持',
      '高级文档处理'
    ],
    kbLimit: 10,
    fileLimit: 100,
    tokenBalance: 10000
  },
  {
    id: 'enterprise',
    name: '企业版',
    price: 299,
    description: '适合大型团队和企业',
    features: [
      '所有专业版功能',
      '无限知识库',
      '无限文件',
      '50000 Token免费额度',
      '专属客服支持',
      'API接口',
      '数据导出',
      '团队管理'
    ],
    kbLimit: '无限',
    fileLimit: '无限',
    tokenBalance: 50000
  }
])

const faqs = ref([
  {
    question: 'Token用完了怎么办？',
    answer: '您可以随时购买额外的Token包，或者在下一个计费周期自动获得额度补充。'
  },
  {
    question: '可以升级或降级套餐吗？',
    answer: '可以随时升级或降级套餐。升级后立即生效，降级将在当前计费周期结束后生效。'
  },
  {
    question: '企业版有定制服务吗？',
    answer: '企业版支持专属定制服务，包括私有化部署、定制开发、专属技术支持等。'
  }
])

const selectedPlan = ref('pro')
const hasUserSelected = ref(false)

const selectPlan = (plan: any) => {
  selectedPlan.value = plan.id
  hasUserSelected.value = true
  ElMessage.success(`已选择${plan.name}套餐`)
}
</script>

<style scoped>
/* 容器 */
.pricing-container {
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
.pricing-header {
  background: rgba(255, 255, 255, 0.85);
  backdrop-filter: blur(12px);
  border-bottom: 1px solid var(--border-light);
  position: relative;
}

.pricing-header::before {
  display: none;
}

.header-content {
  max-width: 1200px;
  margin: 0 auto;
  padding: 12px 24px;
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
  display: none;
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
.pricing-content {
  flex: 1;
  overflow-y: auto;
  padding: 2rem;
  max-width: 1200px;
  margin: 0 auto;
  width: 100%;
}

/* 区块头部 */
.section-header {
  margin-bottom: 1.5rem;
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

.section-desc {
  margin: 0.5rem 0 0;
  font-size: 0.875rem;
  color: #666666;
}

/* Token说明区域 */
.token-section {
  margin-bottom: 2.5rem;
}

.token-grid {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 1.5rem;
}

.token-card {
  background: white;
  border-radius: var(--radius-xl);
  padding: 1.5rem;
  box-shadow: 0 4px 16px rgba(0, 0, 0, 0.04);
  transition: all 0.3s ease;
}

.token-card:hover {
  transform: translateY(-4px);
  box-shadow: 0 8px 24px rgba(0, 0, 0, 0.08);
}

.token-icon {
  width: 48px;
  height: 48px;
  background: linear-gradient(135deg, rgba(59, 130, 246, 0.1) 0%, rgba(37, 99, 235, 0.1) 100%);
  border-radius: var(--radius-lg);
  display: flex;
  align-items: center;
  justify-content: center;
  margin-bottom: 1rem;
  color: var(--primary-color);
}

.token-icon svg {
  width: 24px;
  height: 24px;
}

.token-card h4 {
  margin: 0 0 0.75rem;
  font-size: 1rem;
  font-weight: 600;
  color: #000000;
}

.token-card p {
  margin: 0;
  font-size: 0.875rem;
  color: #333333;
  line-height: 1.6;
}

/* 使用列表 */
.usage-list {
  margin: 0;
  padding: 0;
  list-style: none;
}

.usage-list li {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 0.5rem 0;
  border-bottom: 1px solid var(--bg-tertiary);
}

.usage-list li:last-child {
  border-bottom: none;
}

.usage-label {
  font-size: 0.875rem;
  color: #333333;
}

.usage-value {
  font-size: 0.875rem;
  font-weight: 600;
  color: var(--primary-color);
}

.usage-value.free {
  color: var(--success-color);
}

/* 价格卡片 */
.price-info {
  display: flex;
  flex-direction: column;
  gap: 0.75rem;
}

.main-price {
  display: flex;
  align-items: baseline;
  gap: 0.25rem;
}

.price-value {
  font-size: 1.75rem;
  font-weight: 700;
  color: var(--primary-color);
}

.price-unit {
  font-size: 0.875rem;
  color: #666666;
}

.discount-info {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  font-size: 0.875rem;
  color: #333333;
}

.discount-badge {
  padding: 0.125rem 0.5rem;
  background: linear-gradient(135deg, rgba(59, 130, 246, 0.1) 0%, rgba(37, 99, 235, 0.1) 100%);
  border-radius: var(--radius-sm);
  font-size: 0.75rem;
  font-weight: 600;
  color: var(--primary-color);
}

.discount-badge.enterprise {
  background: linear-gradient(135deg, rgba(245, 158, 11, 0.1) 0%, rgba(59, 130, 246, 0.1) 100%);
  color: #f59e0b;
}

/* 套餐区域 */
.plans-section {
  margin-bottom: 2.5rem;
}

.plans-grid {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 1.5rem;
}

.plan-card {
  background: white;
  border-radius: var(--radius-xl);
  padding: 2rem;
  box-shadow: 0 4px 16px rgba(0, 0, 0, 0.04);
  border: 2px solid transparent;
  transition: all 0.3s ease;
  position: relative;
  display: flex;
  flex-direction: column;
  cursor: pointer;
}

.plan-card:hover {
  transform: translateY(-8px);
  box-shadow: 0 12px 32px rgba(0, 0, 0, 0.1);
  border-color: rgba(59, 130, 246, 0.3);
}

.plan-card.popular {
  border-color: var(--primary-color);
  background: linear-gradient(180deg, rgba(59, 130, 246, 0.02) 0%, white 100%);
}

.plan-card.selected {
  border-color: var(--primary-color);
  box-shadow: 0 0 0 3px rgba(59, 130, 246, 0.15), 0 12px 32px rgba(0, 0, 0, 0.1);
  background: linear-gradient(180deg, rgba(59, 130, 246, 0.04) 0%, white 100%);
}

.plan-card.selected .plan-name {
  color: var(--primary-color);
}

.popular-badge {
  position: absolute;
  top: -12px;
  left: 50%;
  transform: translateX(-50%);
  padding: 0.25rem 1rem;
  background: linear-gradient(135deg, #3b82f6 0%, #2563eb 100%);
  color: white;
  font-size: 0.75rem;
  font-weight: 600;
  border-radius: var(--radius-full);
  white-space: nowrap;
}

.selected-badge {
  position: absolute;
  top: 12px;
  right: 12px;
  display: flex;
  align-items: center;
  gap: 0.25rem;
  padding: 0.25rem 0.75rem;
  background: linear-gradient(135deg, #3b82f6 0%, #2563eb 100%);
  color: white;
  font-size: 0.75rem;
  font-weight: 600;
  border-radius: var(--radius-full);
  white-space: nowrap;
  box-shadow: 0 2px 8px rgba(59, 130, 246, 0.3);
}

.plan-header {
  text-align: center;
  margin-bottom: 1.5rem;
  padding-bottom: 1.5rem;
  border-bottom: 1px solid var(--bg-tertiary);
}

.plan-name {
  margin: 0 0 0.75rem;
  font-size: 1.25rem;
  font-weight: 700;
  color: #000000;
}

.plan-price {
  display: flex;
  align-items: baseline;
  justify-content: center;
  gap: 0.125rem;
  margin-bottom: 0.5rem;
}

.currency {
  font-size: 1rem;
  color: #333333;
}

.amount {
  font-size: 2.5rem;
  font-weight: 800;
  background: linear-gradient(135deg, #3b82f6 0%, #2563eb 100%);
  -webkit-background-clip: text;
  -webkit-text-fill-color: transparent;
  background-clip: text;
}

.period {
  font-size: 0.875rem;
  color: #666666;
}

.plan-desc {
  margin: 0;
  font-size: 0.875rem;
  color: #666666;
}

.plan-features {
  flex: 1;
  margin-bottom: 1.5rem;
}

.feature-item {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  padding: 0.5rem 0;
  font-size: 0.875rem;
  color: #333333;
}

.feature-icon {
  color: var(--success-color);
  font-size: 1rem;
  flex-shrink: 0;
}

.plan-limits {
  background: var(--bg-secondary);
  border-radius: var(--radius-lg);
  padding: 1rem;
  margin-bottom: 1.5rem;
}

.limit-item {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 0.375rem 0;
}

.limit-item:not(:last-child) {
  border-bottom: 1px solid rgba(0, 0, 0, 0.04);
}

.limit-label {
  font-size: 0.875rem;
  color: #666666;
}

.limit-value {
  font-size: 0.875rem;
  font-weight: 600;
  color: #000000;
}

.limit-value.highlight {
  color: var(--primary-color);
}

.select-btn {
  width: 100%;
  border-radius: var(--radius-lg) !important;
  font-weight: 600 !important;
  transition: all 0.25s ease !important;
}

.select-btn:hover {
  transform: translateY(-2px);
}

.select-btn.is-selected {
  background: linear-gradient(135deg, #3b82f6 0%, #2563eb 100%) !important;
  border-color: transparent !important;
  color: white !important;
  box-shadow: 0 4px 12px rgba(59, 130, 246, 0.35);
}

/* FAQ区域 */
.faq-section {
  margin-bottom: 2rem;
}

.faq-list {
  display: flex;
  flex-direction: column;
  gap: 1rem;
}

.faq-item {
  background: white;
  border-radius: var(--radius-xl);
  padding: 1.25rem 1.5rem;
  box-shadow: 0 4px 16px rgba(0, 0, 0, 0.04);
}

.faq-question {
  display: flex;
  align-items: center;
  gap: 0.75rem;
  font-weight: 600;
  color: #000000;
  margin-bottom: 0.5rem;
}

.q-icon {
  color: var(--primary-color);
  font-size: 1.125rem;
}

.faq-answer {
  font-size: 0.875rem;
  color: #333333;
  line-height: 1.6;
  padding-left: 2rem;
}

/* 响应式 */
@media (max-width: 1024px) {
  .token-grid {
    grid-template-columns: 1fr;
  }

  .plans-grid {
    grid-template-columns: 1fr;
  }
}

@media (max-width: 768px) {
  .pricing-content {
    padding: 1rem;
  }

  .header-content {
    padding: 1rem;
  }

  .header-left h1 {
    font-size: 1.25rem;
  }

  .plan-card {
    padding: 1.5rem;
  }

  .amount {
    font-size: 2rem;
  }
}
</style>
