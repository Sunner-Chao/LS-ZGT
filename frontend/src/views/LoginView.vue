<!-- 模板整体：Login 页面结构 -->
<template>
  <div class="login-container">
    <!-- 动态背景 -->
    <div class="login-background">
      <div class="bg-gradient"></div>
      <div class="bg-pattern"></div>
      <div class="bg-orb bg-orb-1"></div>
      <div class="bg-orb bg-orb-2"></div>
      <div class="bg-orb bg-orb-3"></div>
    </div>

    <!-- 登录主体内容 -->
    <div class="login-content">
      <!-- 页头 -->
      <div class="login-header">
        <div class="logo-section">
          <div class="logo-wrapper">
            <img src="/logo.png" alt="孪数建筑行业规范检索查询智能体" class="login-logo" />
          </div>
          <div class="company-info">
            <h1 class="company-name">孪数建筑行业规范检索查询智能体</h1>
            <p class="company-slogan">智能建筑 · 数字孪生 · 规范检索</p>
          </div>
        </div>
        <button class="back-home-btn" @click="goHome">
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
            <path d="M3 9l9-7 9 7v11a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z"/>
            <polyline points="9 22 9 12 15 12 15 22"/>
          </svg>
          返回首页
        </button>
      </div>

      <!-- 登录卡片 -->
      <div class="login-card glass-card">
        <div class="login-card-header">
          <div class="header-icon">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round">
              <path d="M12 2L2 7l10 5 10-5-10-5z"/>
              <path d="M2 17l10 5 10-5"/>
              <path d="M2 12l10 5 10-5"/>
            </svg>
          </div>
          <h2 class="login-title">欢迎登录</h2>
          <p class="login-subtitle">企业级AI知识库问答平台</p>
        </div>

        <el-form
          ref="loginFormRef"
          :model="loginForm"
          :rules="loginRules"
          class="login-form"
        >
          <el-form-item prop="username">
            <el-input
              v-model="loginForm.username"
              placeholder="请输入用户名"
              prefix-icon="User"
              class="login-input"
              size="large"
            />
          </el-form-item>
          <el-form-item prop="password">
            <el-input
              v-model="loginForm.password"
              type="password"
              placeholder="请输入密码"
              prefix-icon="Lock"
              class="login-input"
              size="large"
              show-password
            />
          </el-form-item>
          <el-form-item>
            <el-button
              type="primary"
              class="login-button"
              @click="handleLogin"
              :loading="isLoading"
              size="large"
            >
              <span v-if="!isLoading">登录系统</span>
              <span v-else>登录中...</span>
            </el-button>
          </el-form-item>
        </el-form>

        <div class="login-footer">
          <p class="copyright">&copy; 2024 孪数建筑行业规范检索查询智能体科技有限公司</p>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive } from 'vue';
import { useRouter } from 'vue-router';
import { ElMessage, ElForm } from 'element-plus';
import axios from 'axios';

const router = useRouter();
const isLoading = ref(false);
const loginForm = reactive({ username: '', password: '' });

const loginRules = reactive({
  username: [ { required: true, message: '请输入用户名', trigger: 'blur' } ],
  password: [ { required: true, message: '请输入密码', trigger: 'blur' } ]
});

const loginFormRef = ref<InstanceType<typeof ElForm>>();

const handleLogin = async () => {
  if (!loginFormRef.value) return;

  try {
    await loginFormRef.value.validate();
    isLoading.value = true;

    const response = await axios.post('/api/token', {
      username: loginForm.username,
      password: loginForm.password
    });

    const { token } = response.data;
    localStorage.setItem('token', token);

    ElMessage.success('登录成功');

    // 登录成功后后台预加载知识库树
    import('../stores/knowledgeStore').then(({ useKnowledgeStore }) => {
      const knowledgeStore = useKnowledgeStore();
      knowledgeStore.startBackgroundPrefetch();
    });

    router.push('/app/');
  } catch (error: unknown) {
    if (typeof error === 'object' && error && 'response' in error) {
      const axiosError = error as { response: { data: { message?: string } } };
      ElMessage.error(axiosError.response.data.message || '登录失败');
    } else {
      ElMessage.error('登录失败，请检查网络连接');
    }
  } finally {
    isLoading.value = false;
  }
};

const goHome = () => {
  router.push('/');
};
</script>

<style scoped>
/* 顶层容器 */
.login-container {
  display: flex;
  justify-content: center;
  align-items: center;
  min-height: 100vh;
  position: relative;
  overflow: hidden;
}

/* 动态背景 */
.login-background {
  position: absolute;
  inset: 0;
  z-index: 0;
}

.bg-gradient {
  position: absolute;
  inset: 0;
  background: linear-gradient(135deg, #0f172a 0%, #1e293b 40%, #334155 100%);
}

.bg-pattern {
  position: absolute;
  inset: 0;
  background-image:
    radial-gradient(circle at 20% 80%, rgba(59, 130, 246, 0.15) 0%, transparent 50%),
    radial-gradient(circle at 80% 20%, rgba(37, 99, 235, 0.15) 0%, transparent 50%),
    radial-gradient(circle at 50% 50%, rgba(59, 130, 246, 0.08) 0%, transparent 70%);
}

.bg-orb {
  position: absolute;
  border-radius: 50%;
  filter: blur(80px);
  opacity: 0.4;
  animation: float 8s ease-in-out infinite;
}

.bg-orb-1 {
  width: 400px;
  height: 400px;
  background: radial-gradient(circle, rgba(59, 130, 246, 0.6) 0%, transparent 70%);
  top: -10%;
  right: -5%;
  animation-delay: 0s;
}

.bg-orb-2 {
  width: 350px;
  height: 350px;
  background: radial-gradient(circle, rgba(37, 99, 235, 0.5) 0%, transparent 70%);
  bottom: -5%;
  left: -5%;
  animation-delay: -3s;
}

.bg-orb-3 {
  width: 250px;
  height: 250px;
  background: radial-gradient(circle, rgba(59, 130, 246, 0.4) 0%, transparent 70%);
  top: 40%;
  left: 30%;
  animation-delay: -5s;
}

@keyframes float {
  0%, 100% { transform: translate(0, 0) scale(1); }
  25% { transform: translate(20px, -30px) scale(1.05); }
  50% { transform: translate(-10px, 20px) scale(0.95); }
  75% { transform: translate(15px, 10px) scale(1.02); }
}

/* 内容容器 */
.login-content {
  position: relative;
  z-index: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 2.5rem;
  width: 100%;
  max-width: 1200px;
  padding: 2rem;
  animation: fadeInUp 0.8s ease-out;
}

@keyframes fadeInUp {
  from { opacity: 0; transform: translateY(30px); }
  to { opacity: 1; transform: translateY(0); }
}

/* 页头 */
.login-header {
  text-align: center;
  margin-bottom: 0.5rem;
}

.logo-section {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 1.25rem;
}

.logo-wrapper {
  width: 72px;
  height: 72px;
  display: flex;
  align-items: center;
  justify-content: center;
  background: rgba(255, 255, 255, 0.1);
  border-radius: 20px;
  border: 1px solid rgba(255, 255, 255, 0.15);
  backdrop-filter: blur(10px);
  padding: 10px;
  transition: transform 0.3s ease;
}

.logo-wrapper:hover {
  transform: scale(1.05) rotate(5deg);
}

.login-logo {
  width: 100%;
  height: 100%;
  object-fit: contain;
  filter: drop-shadow(0 4px 12px rgba(255, 255, 255, 0.3));
}

.company-info {
  text-align: left;
}

.company-name {
  font-size: 2rem;
  font-weight: 800;
  margin: 0;
  background: linear-gradient(135deg, #ffffff 0%, #fdba74 50%, #1d4ed8 100%);
  -webkit-background-clip: text;
  -webkit-text-fill-color: transparent;
  background-clip: text;
  letter-spacing: 2px;
  line-height: 1.2;
}

.company-slogan {
  font-size: 1.1rem;
  color: rgba(255, 255, 255, 0.7);
  margin: 0.5rem 0 0 0;
  font-weight: 300;
  letter-spacing: 4px;
}

/* 返回首页按钮 */
.back-home-btn {
  position: absolute;
  top: 1.5rem;
  right: 1.5rem;
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 8px 16px;
  background: rgba(255, 255, 255, 0.1);
  border: 1px solid rgba(255, 255, 255, 0.2);
  border-radius: 8px;
  color: rgba(255, 255, 255, 0.8);
  font-size: 0.85rem;
  cursor: pointer;
  transition: all 0.3s ease;
  backdrop-filter: blur(8px);
}

.back-home-btn:hover {
  background: rgba(255, 255, 255, 0.15);
  border-color: rgba(255, 255, 255, 0.3);
  color: white;
  transform: translateY(-1px);
}

.back-home-btn svg {
  width: 16px;
  height: 16px;
}

/* 登录卡片 */
.glass-card {
  background: rgba(255, 255, 255, 0.08);
  backdrop-filter: blur(20px);
  -webkit-backdrop-filter: blur(20px);
  border: 1px solid rgba(255, 255, 255, 0.12);
  border-radius: 24px;
  box-shadow:
    0 25px 50px -12px rgba(0, 0, 0, 0.4),
    inset 0 1px 0 rgba(255, 255, 255, 0.1);
}

.login-card {
  width: 440px;
  padding: 3rem;
  animation: scaleIn 0.6s ease-out 0.2s both;
}

@keyframes scaleIn {
  from { opacity: 0; transform: scale(0.95) translateY(10px); }
  to { opacity: 1; transform: scale(1) translateY(0); }
}

/* 卡片头 */
.login-card-header {
  text-align: center;
  margin-bottom: 2rem;
}

.header-icon {
  width: 56px;
  height: 56px;
  margin: 0 auto 1.25rem;
  display: flex;
  align-items: center;
  justify-content: center;
  background: linear-gradient(135deg, rgba(59, 130, 246, 0.2) 0%, rgba(37, 99, 235, 0.2) 100%);
  border-radius: 16px;
  border: 1px solid rgba(255, 255, 255, 0.1);
  color: #fdba74;
}

.header-icon svg {
  width: 28px;
  height: 28px;
}

.login-title {
  font-size: 1.75rem;
  font-weight: 700;
  color: #ffffff;
  margin: 0 0 0.5rem 0;
  letter-spacing: 0.5px;
}

.login-subtitle {
  font-size: 0.95rem;
  color: rgba(255, 255, 255, 0.5);
  margin: 0;
  font-weight: 400;
  letter-spacing: 1px;
}

/* 表单 */
.login-form {
  margin-top: 1.5rem;
}

.login-input {
  margin-bottom: 1.25rem;
}

.login-input :deep(.el-input__wrapper) {
  background: rgba(255, 255, 255, 0.06) !important;
  border: 1px solid rgba(255, 255, 255, 0.12) !important;
  border-radius: 14px !important;
  box-shadow: none !important;
  height: 52px !important;
  transition: all 0.3s ease !important;
}

.login-input :deep(.el-input__wrapper:hover) {
  border-color: rgba(59, 130, 246, 0.5) !important;
  background: rgba(255, 255, 255, 0.1) !important;
}

.login-input :deep(.el-input__wrapper.is-focus) {
  border-color: rgba(59, 130, 246, 0.7) !important;
  box-shadow: 0 0 0 4px rgba(59, 130, 246, 0.15) !important;
  background: rgba(255, 255, 255, 0.12) !important;
}

.login-input :deep(.el-input__inner) {
  border: none !important;
  box-shadow: none !important;
  background: transparent !important;
  font-size: 1rem !important;
  color: #ffffff !important;
}

.login-input :deep(.el-input__inner::placeholder) {
  color: rgba(255, 255, 255, 0.35) !important;
}

.login-input :deep(.el-input__prefix) {
  color: rgba(255, 255, 255, 0.4) !important;
}

.login-input :deep(.el-input__suffix) {
  color: rgba(255, 255, 255, 0.4) !important;
}

/* 登录按钮 */
.login-button {
  width: 100%;
  height: 52px;
  border-radius: 14px;
  font-size: 1.1rem;
  font-weight: 700;
  background: linear-gradient(135deg, #3b82f6 0%, #2563eb 50%, #1d4ed8 100%) !important;
  border: none !important;
  transition: all 0.3s ease;
  margin-top: 0.75rem;
  letter-spacing: 2px;
  box-shadow: 0 4px 15px rgba(59, 130, 246, 0.4);
}

.login-button:hover {
  transform: translateY(-2px);
  box-shadow: 0 8px 25px rgba(59, 130, 246, 0.5);
  background: linear-gradient(135deg, #7c7ff7 0%, #9d6ffc 50%, #b97afb 100%) !important;
}

.login-button:active {
  transform: translateY(0);
  box-shadow: 0 2px 10px rgba(59, 130, 246, 0.4);
}

/* 页脚 */
.login-footer {
  text-align: center;
  margin-top: 2rem;
  padding-top: 1.5rem;
  border-top: 1px solid rgba(255, 255, 255, 0.08);
}

.copyright {
  font-size: 0.8rem;
  color: rgba(255, 255, 255, 0.3);
  margin: 0;
  letter-spacing: 0.5px;
}

/* 响应式 */
@media (max-width: 768px) {
  .login-content {
    padding: 1rem;
  }

  .login-card {
    width: 100%;
    max-width: 420px;
    padding: 2rem;
  }

  .company-name {
    font-size: 2rem;
  }

  .logo-section {
    flex-direction: column;
    gap: 0.75rem;
  }

  .company-info {
    text-align: center;
  }
}

@media (max-width: 480px) {
  .login-card {
    padding: 1.5rem;
    border-radius: 20px;
  }

  .company-name {
    font-size: 1.6rem;
  }

  .login-logo {
    width: 48px;
    height: 48px;
  }
}
</style>
