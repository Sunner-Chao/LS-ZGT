<template>
  <div class="settings-container">
    <!-- 页面头部 -->
    <div class="page-header">
      <div class="header-content">
        <div class="header-left">
          <h1 class="page-title">AI 配置</h1>
          <p class="page-desc">配置对话模型与向量模型服务</p>
        </div>
        <div class="connection-status">
          <div class="status-dot" :class="connected ? 'connected' : 'disconnected'"></div>
          <span>{{ connected ? '已连接' : '未连接' }}</span>
        </div>
      </div>
    </div>

    <!-- 状态提示条 -->
    <Transition name="slide-fade">
      <div v-if="statusMsg" class="status-banner" :class="statusType">
        <el-icon><component :is="statusIcon" /></el-icon>
        <span>{{ statusMsg }}</span>
      </div>
    </Transition>

    <!-- 主配置区域 -->
    <div class="config-grid">
      <!-- 对话模型服务 -->
      <div class="config-section chat-section">
        <div class="section-header">
          <div class="section-icon chat-icon">
            <el-icon><ChatDotRound /></el-icon>
          </div>
          <div class="section-title">
            <h3>对话模型服务</h3>
            <p>用于智能对话生成</p>
          </div>
        </div>

        <div class="section-body">
          <!-- 提供方切换 -->
          <div class="provider-switch">
            <button
              class="provider-option"
              :class="{ active: config.chatProvider === 'local' }"
              @click="config.chatProvider = 'local'"
            >
              <el-icon><Monitor /></el-icon>
              <span>本地</span>
            </button>
            <button
              class="provider-option"
              :class="{ active: config.chatProvider === 'cloud' }"
              @click="config.chatProvider = 'cloud'"
            >
              <el-icon><Cloudy /></el-icon>
              <span>云端</span>
            </button>
          </div>

          <!-- 本地配置 -->
          <div v-if="config.chatProvider === 'local'" class="config-form">
            <div class="form-group">
              <label>服务地址</label>
              <el-input
                v-model="config.chatUrl"
                placeholder="http://localhost:8081"
                class="input-with-test"
              >
                <template #append>
                  <el-button @click="testChatConnection" :loading="testingChat">测试</el-button>
                </template>
              </el-input>
            </div>

            <div class="form-group">
              <label>当前模型</label>
              <el-select
                v-model="config.chatModel"
                filterable
                allow-create
                placeholder="选择或输入模型名"
                style="width: 100%"
              >
                <el-option v-for="m in chatModels" :key="m" :label="m" :value="m" />
              </el-select>
            </div>

            <div class="action-row">
              <el-button size="small" @click="scanChatService" :loading="scanningChat">
                <el-icon><Search /></el-icon>
                扫描服务
              </el-button>
              <el-button size="small" @click="openChatModelBrowser">
                <el-icon><FolderOpened /></el-icon>
                浏览模型文件
              </el-button>
            </div>
          </div>

          <!-- 云端配置 -->
          <div v-if="config.chatProvider === 'cloud'" class="config-form">
            <div class="form-group">
              <label>云服务商</label>
              <el-select v-model="config.cloudProvider" @change="onCloudProviderChange" style="width: 100%">
                <el-option label="OpenAI" value="openai" />
                <el-option label="Claude" value="claude" />
                <el-option label="DeepSeek" value="deepseek" />
                <el-option label="Moonshot" value="moonshot" />
                <el-option label="自定义" value="custom" />
              </el-select>
            </div>

            <div class="form-group">
              <label>API Key</label>
              <el-input
                v-model="config.cloudApiKey"
                type="password"
                show-password
                placeholder="sk-..."
              />
            </div>

            <div class="form-group">
              <label>API 地址</label>
              <el-input
                v-model="config.cloudBaseUrl"
                placeholder="https://api.openai.com/v1"
                class="input-with-test"
              >
                <template #append>
                  <el-button @click="testChatConnection" :loading="testingChat">测试</el-button>
                </template>
              </el-input>
            </div>

            <div class="form-group">
              <label>模型名称</label>
              <el-select
                v-model="config.chatModel"
                filterable
                allow-create
                placeholder="选择或输入模型名"
                style="width: 100%"
              >
                <el-option v-for="m in chatModels" :key="m" :label="m" :value="m" />
              </el-select>
            </div>
          </div>
        </div>
      </div>

      <!-- 向量模型服务 -->
      <div class="config-section embedding-section">
        <div class="section-header">
          <div class="section-icon embedding-icon">
            <el-icon><Collection /></el-icon>
          </div>
          <div class="section-title">
            <h3>向量模型服务</h3>
            <p>用于文本向量化检索</p>
          </div>
        </div>

        <div class="section-body">
          <div class="config-form">
            <div class="form-group">
              <label>服务地址</label>
              <el-input
                v-model="config.embeddingUrl"
                placeholder="http://localhost:8082"
                class="input-with-test"
              >
                <template #append>
                  <el-button @click="testEmbeddingConnection" :loading="testingEmbedding">测试</el-button>
                </template>
              </el-input>
            </div>

            <div class="form-group">
              <label>当前模型</label>
              <el-select
                v-model="config.embeddingModel"
                filterable
                allow-create
                placeholder="选择或输入模型名"
                style="width: 100%"
              >
                <el-option v-for="m in embeddingModels" :key="m" :label="m" :value="m" />
              </el-select>
            </div>

            <div class="action-row">
              <el-button size="small" @click="scanEmbeddingService" :loading="scanningEmbedding">
                <el-icon><Search /></el-icon>
                扫描服务
              </el-button>
              <el-button size="small" @click="openEmbeddingModelBrowser">
                <el-icon><FolderOpened /></el-icon>
                浏览模型文件
              </el-button>
            </div>
          </div>

          <!-- 提示信息 -->
          <div class="tip-box">
            <el-icon><InfoFilled /></el-icon>
            <span>向量模型用于将文本转换为向量，支持语义检索。推荐使用 bge-m3 等专用向量模型。</span>
          </div>
        </div>
      </div>
    </div>

    <!-- 底部操作栏 -->
    <div class="action-bar">
      <el-button @click="loadConfig">
        <el-icon><Refresh /></el-icon>
        重置
      </el-button>
      <el-button type="primary" @click="saveConfig" :loading="saving">
        <el-icon><Check /></el-icon>
        保存配置
      </el-button>
    </div>

    <!-- 模型文件浏览器弹窗 -->
    <el-dialog
      v-model="dirBrowserVisible"
      :title="browserTarget === 'chat' ? '选择对话模型文件' : '选择向量模型文件'"
      width="560px"
      :close-on-click-modal="false"
      class="dir-dialog"
    >
      <div class="dir-browser">
        <div class="dir-path-bar">
          <el-button size="small" :disabled="dirIsRoot" @click="browseDir(dirParentPath)" circle>
            <el-icon><ArrowLeft /></el-icon>
          </el-button>
          <div class="current-path">{{ dirIsRoot ? '选择目录' : dirCurrentPath }}</div>
        </div>

        <div class="dir-content" v-loading="dirLoading">
          <div v-if="!dirIsRoot" class="dir-item parent" @click="browseDir(dirParentPath)">
            <el-icon><FolderOpened /></el-icon>
            <span>返回上级</span>
          </div>
          <div
            v-for="item in dirItems"
            :key="item.path"
            class="dir-item"
            :class="{ 'is-file': item.type === 'file', 'selected': selectedModelPath === item.path }"
            @click="item.type === 'directory' ? browseDir(item.path) : (selectedModelPath = item.path)"
          >
            <el-icon v-if="item.type === 'directory'" class="icon-folder"><Folder /></el-icon>
            <el-icon v-else class="icon-file"><Document /></el-icon>
            <span class="item-name">{{ item.name }}</span>
            <span v-if="item.type === 'file'" class="item-size">{{ item.sizeFormatted }}</span>
            <el-button
              v-if="item.type === 'directory'"
              size="small"
              type="primary"
              link
              @click.stop="scanFolder(item.path)"
              :loading="scanningFolder"
            >
              扫描
            </el-button>
            <el-icon v-if="selectedModelPath === item.path" class="check-icon"><Check /></el-icon>
          </div>
          <div v-if="dirItems.length === 0 && !dirLoading" class="dir-empty">
            空目录
          </div>
        </div>

        <!-- 已选模型提示 -->
        <div v-if="selectedModelPath" class="selected-model-bar">
          <span class="selected-label">已选模型:</span>
          <span class="selected-path">{{ selectedModelPath.split('/').pop() }}</span>
        </div>
      </div>
      <template #footer>
        <el-button @click="dirBrowserVisible = false">关闭</el-button>
        <el-button type="primary" @click="switchModel(selectedModelPath)" :loading="switchingModel" :disabled="!selectedModelPath">
          切换模型
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import {
  Check,
  Refresh,
  CircleCheck,
  CircleClose,
  Warning,
  Search,
  FolderOpened,
  Document,
  Folder,
  ArrowLeft,
  Monitor,
  Cloudy,
  ChatDotRound,
  Collection,
  InfoFilled
} from '@element-plus/icons-vue'

const config = ref({
  // 对话模型配置
  chatProvider: 'local' as 'local' | 'cloud',
  chatUrl: '',
  chatModel: '',
  // 向量模型配置
  embeddingUrl: '',
  embeddingModel: '',
  // 云端配置
  cloudProvider: 'openai' as string,
  cloudApiKey: '',
  cloudBaseUrl: ''
})

const chatModels = ref<string[]>([])
const embeddingModels = ref<string[]>([])
const testingChat = ref(false)
const testingEmbedding = ref(false)
const scanningChat = ref(false)
const scanningEmbedding = ref(false)
const saving = ref(false)
const connected = ref(false)

// 目录浏览器
const dirBrowserVisible = ref(false)
const browserTarget = ref<'chat' | 'embedding'>('chat')
const dirCurrentPath = ref('/')
const dirParentPath = ref('/')
const dirItems = ref<any[]>([])
const dirLoading = ref(false)
const dirIsRoot = ref(true)
const scanningFolder = ref(false)
const switchingModel = ref(false)
const selectedModelPath = ref('')

// 状态提示
const statusMsg = ref('')
const statusType = ref<'success' | 'warning' | 'error'>('success')
const statusIcon = ref(CircleCheck)

const cloudDefaults: Record<string, string> = {
  openai: 'https://api.openai.com/v1',
  claude: 'https://api.anthropic.com/v1',
  deepseek: 'https://api.deepseek.com/v1',
  moonshot: 'https://api.moonshot.cn/v1',
  custom: ''
}

const onCloudProviderChange = (provider: string) => {
  if (cloudDefaults[provider]) {
    config.value.cloudBaseUrl = cloudDefaults[provider]
  }
}

// 扫描对话服务
const scanChatService = async () => {
  scanningChat.value = true
  try {
    const res = await fetch('/api/llm/scan-services')
    const json = await res.json()
    if (json.success && json.data && json.data.length > 0) {
      // 找 chat 类型的服务
      const chatService = json.data.find((s: any) => s.serviceType === 'chat') || json.data[0]
      config.value.chatUrl = chatService.url
      if (chatService.models && chatService.models.length > 0) {
        chatModels.value = chatService.models
        if (!config.value.chatModel) {
          config.value.chatModel = chatService.models[0]
        }
      }
      showStatus(`发现对话服务: ${chatService.url}`, 'success')
    } else {
      showStatus('未发现对话服务', 'warning')
    }
  } catch (e: any) {
    showStatus('扫描失败: ' + e.message, 'error')
  } finally {
    scanningChat.value = false
  }
}

// 扫描向量服务
const scanEmbeddingService = async () => {
  scanningEmbedding.value = true
  try {
    const res = await fetch('/api/llm/scan-services')
    const json = await res.json()
    if (json.success && json.data && json.data.length > 0) {
      // 找 embedding 类型的服务
      const embService = json.data.find((s: any) => s.serviceType === 'embedding') || json.data[1] || json.data[0]
      config.value.embeddingUrl = embService.url
      if (embService.models && embService.models.length > 0) {
        embeddingModels.value = embService.models
        if (!config.value.embeddingModel) {
          config.value.embeddingModel = embService.models[0]
        }
      }
      showStatus(`发现向量服务: ${embService.url}`, 'success')
    } else {
      showStatus('未发现向量服务', 'warning')
    }
  } catch (e: any) {
    showStatus('扫描失败: ' + e.message, 'error')
  } finally {
    scanningEmbedding.value = false
  }
}

// 测试对话连接
const testChatConnection = async () => {
  testingChat.value = true
  try {
    await saveConfigSilent()
    const res = await fetch('/api/llm/models')
    const json = await res.json()
    if (json.success) {
      chatModels.value = json.data || []
      showStatus(`对话服务连接成功，发现 ${chatModels.value.length} 个模型`, 'success')
      connected.value = true
    } else {
      showStatus('连接失败: ' + (json.message || '未知错误'), 'error')
    }
  } catch (e: any) {
    showStatus('连接失败: ' + e.message, 'error')
  } finally {
    testingChat.value = false
  }
}

// 测试向量连接
const testEmbeddingConnection = async () => {
  testingEmbedding.value = true
  try {
    const res = await fetch(`${config.value.embeddingUrl}/v1/models`)
    const json = await res.json()
    if (json.data) {
      embeddingModels.value = json.data.map((m: any) => m.id) || []
      showStatus(`向量服务连接成功`, 'success')
      connected.value = true
    } else {
      showStatus('向量服务连接失败', 'error')
    }
  } catch (e: any) {
    showStatus('连接失败: ' + e.message, 'error')
  } finally {
    testingEmbedding.value = false
  }
}

// 打开模型浏览器
const openChatModelBrowser = async () => {
  browserTarget.value = 'chat'
  selectedModelPath.value = ''
  dirBrowserVisible.value = true
  dirCurrentPath.value = '/'
  dirIsRoot.value = true
  await browseDir('/')
}

const openEmbeddingModelBrowser = async () => {
  browserTarget.value = 'embedding'
  selectedModelPath.value = ''
  dirBrowserVisible.value = true
  dirCurrentPath.value = '/'
  dirIsRoot.value = true
  await browseDir('/')
}

// 浏览目录
const browseDir = async (path: string) => {
  dirLoading.value = true
  try {
    const res = await fetch(`/api/llm/list-dirs?path=${encodeURIComponent(path)}`)
    const json = await res.json()
    if (json.success && json.data) {
      if (json.data.dirs) {
        dirIsRoot.value = true
        dirCurrentPath.value = '/'
        dirParentPath.value = '/'
        dirItems.value = (json.data.dirs || []).map((d: any) => ({
          name: d.name || d.path,
          path: d.path,
          type: 'directory',
          hasChildren: d.hasChildren
        }))
      } else {
        dirIsRoot.value = false
        dirCurrentPath.value = json.data.currentPath || path
        dirParentPath.value = json.data.parentPath || '/'
        dirItems.value = json.data.items || []
      }
    }
  } catch (e: any) {
    ElMessage.error('浏览目录失败: ' + e.message)
  } finally {
    dirLoading.value = false
  }
}

// 扫描文件夹
const scanFolder = async (path: string) => {
  scanningFolder.value = true
  try {
    const res = await fetch('/api/llm/scan-folder', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ path })
    })
    const json = await res.json()
    if (json.success && json.data) {
      const models = json.data.models || []
      if (models.length > 0) {
        showStatus(`发现 ${models.length} 个模型文件`, 'success')
      } else {
        showStatus('未发现模型文件', 'warning')
      }
    }
  } catch (e: any) {
    showStatus('扫描失败: ' + e.message, 'error')
  } finally {
    scanningFolder.value = false
  }
}

// 切换模型（重启容器）
const switchModel = async (modelPath: string) => {
  if (!modelPath) {
    ElMessage.warning('请先选择一个模型文件')
    return
  }

  switchingModel.value = true
  try {
    const res = await fetch('/api/llm/switch-model', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        serviceType: browserTarget.value,
        modelPath: modelPath
      })
    })
    const json = await res.json()
    if (json.success) {
      showStatus(`模型切换成功，${browserTarget.value === 'chat' ? '对话' : '向量'}服务已重启`, 'success')
      dirBrowserVisible.value = false
      // 重新扫描服务获取新模型
      if (browserTarget.value === 'chat') {
        await scanChatService()
      } else {
        await scanEmbeddingService()
      }
    } else {
      showStatus('切换失败: ' + (json.message || '未知错误'), 'error')
    }
  } catch (e: any) {
    showStatus('切换失败: ' + e.message, 'error')
  } finally {
    switchingModel.value = false
  }
}

// 加载配置
const loadConfig = async () => {
  try {
    const res = await fetch('/api/agent/config')
    const json = await res.json()
    if (json.success && json.data) {
      const d = json.data
      config.value.chatProvider = d.provider || 'local'
      config.value.chatUrl = d.url || ''
      config.value.embeddingUrl = d.embeddingUrl || ''
      config.value.chatModel = d.model || ''
      config.value.embeddingModel = d.embeddingModel || ''
      config.value.cloudProvider = d.cloudProvider || 'openai'
      config.value.cloudBaseUrl = d.cloudBaseUrl || ''
      config.value.cloudApiKey = d.cloudApiKey || ''
      connected.value = true
    }
  } catch {
    connected.value = false
  }
}

// 静默保存
const saveConfigSilent = async () => {
  const body: Record<string, any> = {
    provider: config.value.chatProvider,
    model: config.value.chatModel,
    embeddingModel: config.value.embeddingModel,
    embeddingUrl: config.value.embeddingUrl
  }
  if (config.value.chatProvider === 'local') {
    body.url = config.value.chatUrl
  } else {
    body.cloudProvider = config.value.cloudProvider
    body.cloudApiKey = config.value.cloudApiKey
    body.cloudBaseUrl = config.value.cloudBaseUrl
  }
  await fetch('/api/agent/config', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body)
  })
}

// 保存配置
const saveConfig = async () => {
  saving.value = true
  try {
    await saveConfigSilent()
    connected.value = true
    ElMessage.success('配置保存成功')
    showStatus('配置已更新', 'success')
  } catch (e: any) {
    ElMessage.error('配置保存失败: ' + e.message)
  } finally {
    saving.value = false
  }
}

// 显示状态
const showStatus = (msg: string, type: 'success' | 'warning' | 'error') => {
  statusMsg.value = msg
  statusType.value = type
  statusIcon.value = type === 'success' ? CircleCheck : type === 'warning' ? Warning : CircleClose
  setTimeout(() => { statusMsg.value = '' }, 4000)
}

onMounted(() => {
  loadConfig()
})
</script>

<style scoped>
.settings-container {
  padding: 2rem;
  max-width: 1000px;
  margin: 0 auto;
  min-height: calc(100vh - 64px);
  background: #ffffff;
  animation: fadeInUp 0.6s ease-out;
}

@keyframes fadeInUp {
  from { opacity: 0; transform: translateY(30px); }
  to { opacity: 1; transform: translateY(0); }
}

@keyframes slideInLeft {
  from { opacity: 0; transform: translateX(-30px); }
  to { opacity: 1; transform: translateX(0); }
}

@keyframes slideInRight {
  from { opacity: 0; transform: translateX(30px); }
  to { opacity: 1; transform: translateX(0); }
}

/* 页面头部 */
.page-header {
  margin-bottom: 1.5rem;
}

.header-content {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
}

.page-title {
  font-size: 1.5rem;
  font-weight: 700;
  color: #1f2937;
  margin: 0 0 0.25rem;
}

.page-desc {
  font-size: 0.875rem;
  color: #6b7280;
  margin: 0;
}

.connection-status {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  padding: 0.5rem 1rem;
  background: #f8fafc;
  border-radius: 20px;
  font-size: 0.875rem;
  font-weight: 500;
  color: #1f2937;
  border: 1px solid #e5e7eb;
}

.status-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
}

.status-dot.connected { background: #10b981; }
.status-dot.disconnected { background: #ef4444; }

/* 状态提示条 */
.status-banner {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  padding: 0.75rem 1rem;
  border-radius: 12px;
  font-size: 0.875rem;
  margin-bottom: 1.5rem;
}

.status-banner.success {
  background: #ecfdf5;
  color: #059669;
  border: 1px solid #a7f3d0;
}

.status-banner.warning {
  background: #fffbeb;
  color: #d97706;
  border: 1px solid #fde68a;
}

.status-banner.error {
  background: #fef2f2;
  color: #dc2626;
  border: 1px solid #fecaca;
}

/* 配置网格 */
.config-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 1.5rem;
  margin-bottom: 1.5rem;
}

@media (max-width: 768px) {
  .config-grid { grid-template-columns: 1fr; }
}

/* 配置卡片 */
.config-section {
  background: #ffffff;
  border-radius: 16px;
  border: 1px solid #e5e7eb;
  overflow: hidden;
}

.chat-section { animation: slideInLeft 0.6s ease-out 0.2s both; }
.embedding-section { animation: slideInRight 0.6s ease-out 0.4s both; }

.section-header {
  display: flex;
  align-items: center;
  gap: 1rem;
  padding: 1.25rem 1.5rem;
  background: #f8fafc;
  border-bottom: 1px solid #e5e7eb;
}

.section-icon {
  width: 40px;
  height: 40px;
  display: flex;
  align-items: center;
  justify-content: center;
  border-radius: 12px;
  color: #ffffff;
  font-size: 1.25rem;
}

.section-icon.chat-icon {
  background: linear-gradient(135deg, #3b82f6, #60a5fa);
}

.section-icon.embedding-icon {
  background: linear-gradient(135deg, #10b981, #34d399);
}

.section-title h3 {
  margin: 0;
  font-size: 1rem;
  font-weight: 600;
  color: #1f2937;
}

.section-title p {
  margin: 0.125rem 0 0;
  font-size: 0.75rem;
  color: #6b7280;
}

.section-body {
  padding: 1.5rem;
}

/* 提供方切换 */
.provider-switch {
  display: flex;
  gap: 0.75rem;
  margin-bottom: 1.25rem;
}

.provider-option {
  flex: 1;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 0.5rem;
  padding: 0.75rem;
  background: #f8fafc;
  border: 1.5px solid #e5e7eb;
  border-radius: 10px;
  cursor: pointer;
  transition: all 0.2s;
  font-size: 0.875rem;
  font-weight: 500;
  color: #6b7280;
}

.provider-option:hover {
  border-color: #3b82f6;
  color: #3b82f6;
}

.provider-option.active {
  background: rgba(59, 130, 246, 0.1);
  border-color: #3b82f6;
  color: #3b82f6;
}

/* 表单 */
.config-form {
  display: flex;
  flex-direction: column;
  gap: 1rem;
}

.form-group {
  display: flex;
  flex-direction: column;
  gap: 0.375rem;
}

.form-group label {
  font-size: 0.875rem;
  font-weight: 600;
  color: #1f2937;
}

/* 带测试按钮的输入框 */
.input-with-test { width: 100%; }

.input-with-test :deep(.el-input-group__append) {
  background: #3b82f6;
  border-color: #3b82f6;
  padding: 0;
}

.input-with-test :deep(.el-input-group__append .el-button) {
  color: #ffffff;
  background: transparent;
  border: none;
  margin: 0;
}

.input-with-test :deep(.el-input-group__append .el-button:hover) {
  background: rgba(255, 255, 255, 0.1);
}

.input-with-test :deep(.el-input__wrapper) {
  border-top-right-radius: 0 !important;
  border-bottom-right-radius: 0 !important;
}

/* 操作行 */
.action-row {
  display: flex;
  gap: 0.5rem;
  margin-top: 0.5rem;
}

/* 提示框 */
.tip-box {
  display: flex;
  align-items: flex-start;
  gap: 0.5rem;
  padding: 0.75rem;
  background: #f0f9ff;
  border-radius: 8px;
  font-size: 0.75rem;
  color: #0369a1;
  margin-top: 1rem;
}

.tip-box .el-icon {
  flex-shrink: 0;
  margin-top: 2px;
}

/* 操作栏 */
.action-bar {
  display: flex;
  justify-content: flex-end;
  gap: 0.75rem;
  padding: 1rem 0;
}

/* 目录浏览器弹窗 */
.dir-dialog :deep(.el-dialog__body) { padding: 0; }

.dir-browser { min-height: 320px; }

.dir-path-bar {
  display: flex;
  align-items: center;
  gap: 0.75rem;
  padding: 1rem 1.25rem;
  background: #f8fafc;
  border-bottom: 1px solid #e5e7eb;
}

.current-path {
  flex: 1;
  font-family: monospace;
  font-size: 0.8125rem;
  color: #1f2937;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.dir-content {
  max-height: 360px;
  overflow-y: auto;
  padding: 0.5rem 0;
}

.dir-item {
  display: flex;
  align-items: center;
  gap: 0.625rem;
  padding: 0.625rem 1.25rem;
  cursor: pointer;
  transition: background 0.15s;
}

.dir-item:hover { background: rgba(59, 130, 246, 0.06); }
.dir-item.parent { color: #3b82f6; font-weight: 500; }
.dir-item.is-file { color: #6b7280; cursor: pointer; }
.dir-item.is-file:hover { background: #f9fafb; }
.dir-item.selected { background: rgba(59, 130, 246, 0.1); border-left: 3px solid #3b82f6; }

.icon-folder { color: #f59e0b; font-size: 1.125rem; }
.icon-file { color: #3b82f6; font-size: 1rem; }

.item-name { flex: 1; font-size: 0.875rem; color: #1f2937; }
.item-size { font-size: 0.75rem; color: #9ca3af; }
.check-icon { color: #10b981; font-size: 1rem; }
.dir-empty { padding: 2rem; text-align: center; color: #9ca3af; font-size: 0.875rem; }

/* 已选模型栏 */
.selected-model-bar {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  padding: 0.75rem 1.25rem;
  background: #f0f9ff;
  border-top: 1px solid #e5e7eb;
}

.selected-label {
  font-size: 0.75rem;
  color: #6b7280;
}

.selected-path {
  font-family: monospace;
  font-size: 0.8125rem;
  color: #0369a1;
  font-weight: 500;
}

/* 过渡动画 */
.slide-fade-enter-active { transition: all 0.3s ease-out; }
.slide-fade-leave-active { transition: all 0.2s ease-in; }
.slide-fade-enter-from { transform: translateY(-10px); opacity: 0; }
.slide-fade-leave-to { transform: translateY(-10px); opacity: 0; }

/* Element Plus 样式覆盖 */
.config-form :deep(.el-input__inner) { color: #1f2937 !important; }
.config-form :deep(.el-input__inner::placeholder) { color: #9ca3af !important; }
.config-form :deep(.el-select__placeholder) { color: #9ca3af !important; }

.config-form :deep(.el-input__wrapper) {
  transition: box-shadow 0.2s, border-color 0.2s !important;
}

.config-form :deep(.el-input__wrapper:focus-within) {
  box-shadow: 0 0 0 1px #3b82f6 inset !important;
}

/* 响应式 */
@media (max-width: 768px) {
  .settings-container { padding: 1rem; }
  .header-content { flex-direction: column; gap: 1rem; }
  .connection-status { align-self: flex-start; }
  .section-body { padding: 1rem; }
  .chat-section, .embedding-section { animation: fadeInUp 0.6s ease-out; }
}
</style>
