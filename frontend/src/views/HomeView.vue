<template>
  <div class="chat-home-container">
    <!-- 左侧边栏 -->
    <aside class="chat-sidebar" :class="{ collapsed: sidebarCollapsed }">
      <!-- 侧边栏头部 -->
      <div class="sidebar-header">
        <div class="sidebar-title" v-show="!sidebarCollapsed">
          <el-icon class="sidebar-icon"><FolderOpened /></el-icon>
          <span>知识库</span>
        </div>
        <el-button
          :icon="sidebarCollapsed ? ArrowRight : ArrowLeft"
          link
          @click="sidebarCollapsed = !sidebarCollapsed"
          class="collapse-btn"
        />
      </div>

      <!-- 知识库列表 -->
      <div class="sidebar-section" v-show="!sidebarCollapsed">
        <div class="section-header">
          <span class="section-title">选择知识库</span>
          <el-button link size="small" @click="goToKnowledgeBase">
            <el-icon><Setting /></el-icon>
          </el-button>
        </div>
        <div class="kb-list">
          <div v-if="topLevelKnowledgeBases.length === 0" class="empty-kb">
            暂无知识库
          </div>
          <div
            v-for="kb in topLevelKnowledgeBases"
            :key="kb.path || kb.name"
            class="kb-item-wrapper"
          >
            <div
              class="kb-item"
              :class="{ active: knowledgeStore.currentNode?.path === kb.path }"
              @click="selectKnowledgeBase(kb)"
            >
              <el-icon class="kb-icon"><FolderOpened /></el-icon>
              <span class="kb-name">{{ kb.name }}</span>
              <span class="kb-file-count" v-if="kb.children?.length">{{ kb.children.length }} 文件</span>
              <el-icon
                v-if="kb.children?.length"
                class="kb-expand-btn"
                :class="{ expanded: expandedKbs.has(kb.path || kb.name) }"
                @click.stop="toggleKbExpand(kb)"
              >
                <ArrowRight />
              </el-icon>
            </div>
            <Transition name="kb-slide">
              <div v-if="expandedKbs.has(kb.path || kb.name) && kb.children?.length" class="kb-children">
                <div
                  v-for="child in kb.children"
                  :key="child.path || child.name"
                  class="kb-child-item"
                >
                  <el-icon class="kb-child-icon"><Document /></el-icon>
                  <span class="kb-child-name">{{ child.name }}</span>
                </div>
              </div>
            </Transition>
          </div>
        </div>
      </div>

      <!-- 历史记录 -->
      <div class="sidebar-section history-section" v-show="!sidebarCollapsed">
        <div class="section-header">
          <span class="section-title">历史记录</span>
          <el-button link size="small" @click="clearAllHistory">
            <el-icon><Delete /></el-icon>
          </el-button>
        </div>
        <div class="history-list">
          <div v-if="chatHistories.length === 0" class="empty-history">
            暂无历史记录
          </div>
          <div
            v-for="(history, idx) in chatHistories"
            :key="idx"
            class="history-item"
            :class="{ active: currentHistoryIndex === idx }"
            @click="loadHistory(idx)"
          >
            <div class="history-icon">
              <el-icon><ChatDotRound /></el-icon>
            </div>
            <div class="history-content">
              <div class="history-title">{{ history.title || '新对话' }}</div>
              <div class="history-time">{{ formatTime(history.time) }}</div>
            </div>
            <el-button
              link
              size="small"
              class="history-delete"
              @click.stop="deleteHistory(idx)"
            >
              <el-icon><Close /></el-icon>
            </el-button>
          </div>
        </div>
      </div>

      <!-- 收起状态的图标 -->
      <div v-if="sidebarCollapsed" class="collapsed-icons">
        <div
          v-for="kb in topLevelKnowledgeBases"
          :key="kb.path || kb.name"
          class="collapsed-icon-item"
          :class="{ active: knowledgeStore.currentNode?.path === kb.path }"
          :title="kb.name"
          @click="selectKnowledgeBase(kb)"
        >
          <el-icon><FolderOpened /></el-icon>
        </div>
      </div>
    </aside>

    <!-- 主聊天区域 -->
    <main class="chat-main">
      <!-- 顶部栏 -->
      <header class="chat-header">
        <div class="header-left">
          <h2 class="chat-title">
            {{ knowledgeStore.currentNode?.name || '智能对话' }}
          </h2>
          <span class="chat-subtitle" v-if="knowledgeStore.currentNode">
            {{ fileList.length }} 个文档
          </span>
        </div>
        <div class="header-right">
          <el-button :icon="Plus" circle title="新建对话" @click="startNewConversation" />
        </div>
      </header>

      <!-- 消息区域 -->
      <div class="chat-messages" ref="messagesRef">
        <!-- 欢迎页面 -->
        <div v-if="messages.length === 0" class="welcome-area">
          <div class="welcome-icon">
            <el-icon :size="64"><ChatDotRound /></el-icon>
          </div>
          <h2>欢迎使用孪数 AI 助手</h2>
          <p>基于智能知识库的精准检索查询平台</p>

          <div class="feature-grid">
            <div class="feature-card">
              <el-icon :size="32"><Document /></el-icon>
              <span>智能文档解析</span>
            </div>
            <div class="feature-card">
              <el-icon :size="32"><Search /></el-icon>
              <span>精准知识检索</span>
            </div>
          </div>

          <div v-if="!knowledgeStore.currentNode" class="welcome-tip">
            <el-icon><InfoFilled /></el-icon>
            <span>请从左侧选择一个知识库开始对话</span>
          </div>

          <div v-else class="suggestion-chips">
            <button
              v-for="(suggestion, i) in suggestions"
              :key="i"
              class="suggestion-chip"
              @click="sendSuggestion(suggestion)"
            >
              {{ suggestion }}
            </button>
          </div>
        </div>

        <!-- 消息列表 -->
        <div v-else class="message-list">
          <div
            v-for="(msg, idx) in messages"
            :key="idx"
            class="message-item"
            :class="msg.role"
          >
            <div class="message-avatar">
              <el-icon v-if="msg.role === 'user'"><User /></el-icon>
              <el-icon v-else><ChatDotRound /></el-icon>
            </div>
            <div class="message-bubble-wrapper">
              <div class="message-bubble">
                <!-- 消息内容 -->
                <div v-if="msg.content" class="message-text">
                  <SafeHtml :html="msg.content" />
                </div>

                <!-- 加载动画 -->
                <div v-if="msg.loading" class="loading-dots">
                  <span></span><span></span><span></span>
                </div>
              </div>

              <!-- 参考资料 -->
              <div v-if="msg.sources?.length" class="sources-section">
                <div class="sources-header">
                  <el-icon><Document /></el-icon>
                  <span>参考资料</span>
                  <el-badge :value="msg.sources.length" type="primary" />
                </div>
                <div class="sources-list">
                  <div
                    v-for="(source, sIdx) in msg.sources.slice(0, 5)"
                    :key="sIdx"
                    class="source-card"
                  >
                    <div class="source-card-header" @click="toggleSourceExpand(sIdx)">
                      <div class="source-left">
                        <span class="source-rank">{{ source.rank || sIdx + 1 }}</span>
                        <div class="source-info">
                          <span class="source-name">{{ source.documentName || source.filename || '未知文档' }}</span>
                          <div class="source-meta-row">
                            <span class="source-meta" v-if="source.page">第 {{ source.page }} 页</span>
                            <span class="source-meta section-path" v-if="source.sectionPath">{{ source.sectionPath }}</span>
                            <span class="source-meta" v-if="source.vectorRank">向量 {{ source.vectorRank }}</span>
                            <span class="source-meta" v-if="source.bm25Rank">BM25 {{ source.bm25Rank }}</span>
                          </div>
                        </div>
                      </div>
                      <div class="source-right">
                        <div class="similarity-indicator">
                          <div class="similarity-bar">
                            <div
                              class="similarity-fill"
                              :style="{ width: getSimilarityPercent(source) + '%' }"
                              :class="{
                                'high': getSimilarityPercent(source) > 80,
                                'medium': getSimilarityPercent(source) > 60 && getSimilarityPercent(source) <= 80,
                                'low': getSimilarityPercent(source) <= 60
                              }"
                            ></div>
                          </div>
                          <span class="similarity-text">{{ getSimilarityPercent(source) }}%</span>
                        </div>
                        <el-icon class="expand-icon" :class="{ expanded: expandedSources.has(sIdx) }">
                          <ArrowRight />
                        </el-icon>
                      </div>
                    </div>
                    <Transition name="source-expand">
                      <div v-if="expandedSources.has(sIdx) && source.text" class="source-text-preview">
                        <p>{{ source.text }}</p>
                      </div>
                    </Transition>
                    <div class="source-actions">
                      <button
                        class="preview-btn"
                        @click.stop="openPagePreview(source)"
                        title="查看原文"
                      >
                        <el-icon><View /></el-icon>
                        <span>查看原文</span>
                      </button>
                    </div>
                  </div>
                </div>
              </div>
            </div>
          </div>
        </div>
      </div>

      <!-- 输入区域 -->
      <div class="chat-input-area">
        <div class="input-wrapper">
          <textarea
            ref="inputRef"
            v-model="inputText"
            class="input-field"
            :placeholder="knowledgeStore.currentNode ? '输入问题，Enter 发送...' : '输入问题，Enter 发送...'"
            :disabled="loading"
            rows="1"
            @keydown.enter.exact.prevent="sendMessage"
            @input="autoResize"
          />
          <button
            class="send-btn"
            :disabled="!inputText.trim() || loading"
            @click="sendMessage"
          >
            <el-icon><Promotion /></el-icon>
          </button>
        </div>
        <div class="input-tips">
          <span>Enter 发送</span>
          <span>Shift+Enter 换行</span>
        </div>
      </div>
    </main>

    <!-- 原文档页面预览弹窗 -->
    <Teleport to="body">
      <Transition name="fade">
        <div v-if="previewVisible" class="preview-overlay" @click.self="closePagePreview">
          <div class="preview-modal">
            <div class="preview-header">
              <span class="preview-title">{{ previewDocName }}</span>
              <span class="preview-page" v-if="previewPage">第 {{ previewPage }} 页</span>
              <button class="preview-close" @click="closePagePreview">
                <el-icon><Close /></el-icon>
              </button>
            </div>
            <div class="preview-body">
              <div v-if="previewLoading" class="preview-loading">
                <el-icon class="is-loading"><Loading /></el-icon>
                <span>加载中...</span>
              </div>
              <div v-if="previewError" class="preview-error">
                <el-icon><Warning /></el-icon>
                <span>{{ previewError }}</span>
              </div>
              <img
                v-show="previewImageUrl && !previewLoading && !previewError"
                :src="previewImageUrl"
                class="preview-image"
                @load="onPreviewImageLoad"
                @error="onPreviewImageError"
                @click.stop
              />
            </div>
          </div>
        </div>
      </Transition>
    </Teleport>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted, watch, nextTick } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import {
  User,
  ChatDotRound,
  Document,
  FolderOpened,
  Setting,
  ArrowLeft,
  ArrowRight,
  Search,
  Warning,
  InfoFilled,
  Promotion,
  Close,
  Delete,
  RefreshRight,
  Plus,
  View,
  Loading
} from '@element-plus/icons-vue'
import SafeHtml from '../components/SafeHtml.vue'
import { useKnowledgeStore } from '../stores/knowledgeStore'
import { getAuthToken } from '../utils/auth'
import { markdownToHtml } from '../utils/chat'
import { SSEParser } from '../utils/sse'

const router = useRouter()
const knowledgeStore = useKnowledgeStore()

// 状态
const sidebarCollapsed = ref(false)
const inputText = ref('')
const loading = ref(false)
const messages = ref<Array<{
  role: 'user' | 'ai'
  content: string
  thinkContent?: string
  sources?: SourceItem[]
  loading?: boolean
}>>([])

interface SourceItem {
  documentName?: string
  filename?: string
  page?: number
  score?: number
  text?: string
  documentId?: string
  vectorRank?: number
  bm25Rank?: number
  rank?: number
  section?: string
  sectionPath?: string
  vectorScore?: number
  bm25Score?: number
}

const expandedSources = ref<Set<number>>(new Set())

// 计算相似度百分比
// 优先使用 vectorScore（L2 距离，越小越相关），否则使用 RRF score（越大越相关）
const getSimilarityPercent = (source: SourceItem): number => {
  // 优先使用 L2 距离计算相似度
  if (source.vectorScore !== undefined && source.vectorScore !== null) {
    // L2 距离：0 = 完全相同，4+ = 完全不相关
    // 公式：(4 - 距离) / 4 * 100，限制在 0-100
    return Math.round(Math.max(0, Math.min(100, (4 - source.vectorScore) / 4 * 100)))
  }
  // 降级：使用 RRF score（如果是 <= 1 的小数值，乘以 100）
  const score = source.score
  if (score === undefined || score === null) return 0
  if (score <= 1) return Math.round(Math.max(0, Math.min(100, score * 100)))
  // 如果 > 1 但没有 vectorScore，当作 L2 距离处理
  return Math.round(Math.max(0, Math.min(100, (4 - score) / 4 * 100)))
}

const toggleSourceExpand = (idx: number) => {
  if (expandedSources.value.has(idx)) {
    expandedSources.value.delete(idx)
  } else {
    expandedSources.value.add(idx)
  }
}

const getScoreLabel = (source: SourceItem): string => {
  const percent = getSimilarityPercent(source)
  if (source.vectorScore !== undefined) {
    if (source.vectorScore < 1) return '高度相似'
    if (source.vectorScore < 2) return '相似'
    if (source.vectorScore < 3) return '一般'
    return '弱相关'
  }
  if (percent > 80) return '高度相关'
  if (percent > 60) return '相关'
  if (percent > 40) return '一般'
  return '弱相关'
}
const fileList = ref<any[]>([])
const messagesRef = ref()
const inputRef = ref()
const chatHistories = ref<Array<{ title: string; messages: any[]; time: number }>>([])
const currentHistoryIndex = ref(-1)
const expandedKbs = ref<Set<string>>(new Set())

const suggestions = [
  '查询知识库中的文档',
  '帮我总结主要内容',
  '有哪些关键要点？',
  '详细解释这个概念'
]

// 原文档页面预览
const previewVisible = ref(false)
const previewLoading = ref(false)
const previewError = ref('')
const previewImageUrl = ref('')
const previewDocName = ref('')
const previewPage = ref(1)

const openPagePreview = async (source: SourceItem) => {
  const docName = source.documentName || source.filename || ''
  if (!docName) {
    ElMessage.warning('缺少文档名称，无法预览')
    return
  }

  previewDocName.value = docName
  previewPage.value = source.page || 1
  previewVisible.value = true
  previewLoading.value = true
  previewError.value = ''
  previewImageUrl.value = ''

  // 设置图片 URL，保持 loading 直到 @load 或 @error 触发
  const apiUrl = `/api/kb/page-preview?source=${encodeURIComponent(docName)}&page=${previewPage.value}`
  previewImageUrl.value = apiUrl
}

const onPreviewImageLoad = () => {
  previewLoading.value = false
}

const onPreviewImageError = () => {
  previewLoading.value = false
  previewError.value = '图片加载失败，请检查文档是否存在'
}

const closePagePreview = () => {
  previewVisible.value = false
  previewImageUrl.value = ''
  previewError.value = ''
}

// 初始化
onMounted(async () => {
  await knowledgeStore.fetchKnowledgeBaseTree()
  loadHistoriesFromStorage()

  // 检查 URL 参数是否有历史记录
  const urlParams = new URLSearchParams(window.location.search)
  const historyIdx = urlParams.get('history')
  if (historyIdx !== null) {
    const idx = parseInt(historyIdx)
    if (!isNaN(idx) && idx >= 0 && idx < chatHistories.value.length) {
      loadHistory(idx)
    }
  }
})

// 加载历史记录
const loadHistoriesFromStorage = () => {
  try {
    const stored = localStorage.getItem('chat_histories')
    if (stored) {
      chatHistories.value = JSON.parse(stored)
    }
  } catch {
    chatHistories.value = []
  }
}

// 保存历史记录
const saveHistoriesToStorage = () => {
  try {
    localStorage.setItem('chat_histories', JSON.stringify(chatHistories.value))
  } catch {
    // ignore
  }
}

// 格式化时间
const formatTime = (timestamp: number) => {
  const now = Date.now()
  const diff = now - timestamp
  const minutes = Math.floor(diff / 60000)
  const hours = Math.floor(diff / 3600000)
  const days = Math.floor(diff / 86400000)

  if (minutes < 1) return '刚刚'
  if (minutes < 60) return `${minutes}分钟前`
  if (hours < 24) return `${hours}小时前`
  if (days < 7) return `${days}天前`
  return new Date(timestamp).toLocaleDateString()
}

// 获取顶层知识库列表（只包含文件夹类型的顶层节点）
const topLevelKnowledgeBases = computed(() => {
  return (knowledgeStore.knowledgeBaseTree || []).filter((node: any) => node.type === 'folder')
})

// 选择知识库
const selectKnowledgeBase = async (kb: any) => {
  knowledgeStore.setCurrentNode(kb)
  await loadFileList()
}

// 展开/收起知识库文件列表
const toggleKbExpand = (kb: any) => {
  const key = kb.path || kb.name
  if (expandedKbs.value.has(key)) {
    expandedKbs.value.delete(key)
  } else {
    expandedKbs.value.add(key)
  }
}

// 加载文件列表
const loadFileList = async () => {
  if (!knowledgeStore.currentNode) return
  try {
    const path = knowledgeStore.currentNode.path || knowledgeStore.currentNode.name
    const response = await fetch(`/api/kb/${encodeURIComponent(path)}/files`, {
      headers: { 'Authorization': 'Bearer ' + getAuthToken() }
    })
    if (response.ok) {
      const data = await response.json()
      fileList.value = data.data || []
    }
  } catch {
    fileList.value = []
  }
}

// 发送消息
const sendMessage = async () => {
  const text = inputText.value.trim()
  if (!text || loading.value) return

  inputText.value = ''
  loading.value = true

  // 添加用户消息
  messages.value.push({ role: 'user', content: markdownToHtml(text) })

  // 添加 AI 消息占位
  const aiMsgIndex = messages.value.length
  messages.value.push({ role: 'ai', content: '', thinkContent: '', sources: [], loading: true })

  // 保存到历史记录
  saveToHistory(text)

  // 滚动到底部
  await nextTick()
  scrollToBottom()

  try {
    const response = await fetch('/api/chat/stream', {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'Authorization': 'Bearer ' + getAuthToken()
      },
      body: JSON.stringify({
        question: text,
        knowledgeBaseId: knowledgeStore.currentNode?.path || knowledgeStore.currentNode?.name || 'default'
      })
    })

    if (!response.ok) throw new Error(`HTTP ${response.status}`)

    const aiMsg = messages.value[aiMsgIndex]
    const parser = new SSEParser({
      onAnswer: (c) => {
        aiMsg.content += c
        aiMsg.loading = false
      },
      onSources: (s) => {
        aiMsg.sources = s
        aiMsg.thinkContent = s?.length
          ? `已找到 ${s.length} 个相关文档...`
          : '未找到相关文档...'
      },
      onLoading: (c) => {
        aiMsg.thinkContent = c || '模型思考中...'
      },
      onThinking: (c) => {
        aiMsg.thinkContent = (aiMsg.thinkContent || '') + (c || '')
      },
      onError: (e) => {
        aiMsg.content = '抱歉，服务出了点问题：' + e
        aiMsg.loading = false
      },
      onDone: () => {
        aiMsg.loading = false
      }
    })

    const reader = response.body?.getReader()
    if (!reader) throw new Error('无法读取响应')

    try {
      while (true) {
        const { done, value } = await reader.read()
        if (done) break
        parser.push(value)
      }
    } finally {
      parser.end()
      reader.releaseLock()
    }
  } catch (error) {
    const aiMsgFailed = messages.value[aiMsgIndex]
    if (aiMsgFailed) {
      aiMsgFailed.content = '抱歉，服务出了点问题，请稍后重试。'
      aiMsgFailed.loading = false
    }
  } finally {
    loading.value = false
    if (messages.value[aiMsgIndex]) {
      messages.value[aiMsgIndex].loading = false
    }
    await nextTick()
    scrollToBottom()
    saveHistoriesToStorage()
  }
}

// 发送建议
const sendSuggestion = (text: string) => {
  inputText.value = text
  sendMessage()
}

// 滚动到底部
const scrollToBottom = () => {
  if (messagesRef.value) {
    messagesRef.value.scrollTop = messagesRef.value.scrollHeight
  }
}

// 自动调整输入框高度
const autoResize = (e: Event) => {
  const textarea = e.target as HTMLTextAreaElement
  textarea.style.height = 'auto'
  textarea.style.height = Math.min(textarea.scrollHeight, 120) + 'px'
}

// 保存到历史记录
const saveToHistory = (firstMessage: string) => {
  // 如果当前有未保存的对话，先保存
  if (currentHistoryIndex.value >= 0 && messages.value.length > 2) {
    chatHistories.value[currentHistoryIndex.value].messages = [...messages.value]
    chatHistories.value[currentHistoryIndex.value].title = firstMessage.slice(0, 30)
    chatHistories.value[currentHistoryIndex.value].time = Date.now()
  } else {
    // 创建新历史
    chatHistories.value.unshift({
      title: firstMessage.slice(0, 30),
      messages: [...messages.value],
      time: Date.now()
    })
    currentHistoryIndex.value = 0
    // 最多保存 20 条
    if (chatHistories.value.length > 20) {
      chatHistories.value = chatHistories.value.slice(0, 20)
    }
  }
  saveHistoriesToStorage()
}

// 加载历史记录
const loadHistory = (idx: number) => {
  if (chatHistories.value[idx]) {
    currentHistoryIndex.value = idx
    messages.value = [...chatHistories.value[idx].messages]
    knowledgeStore.setCurrentNode(knowledgeStore.findNodeByPath(
      chatHistories.value[idx].messages[0]?.kbPath || ''
    ) || null)
    nextTick(() => scrollToBottom())
  }
}

// 删除历史记录
const deleteHistory = (idx: number) => {
  chatHistories.value.splice(idx, 1)
  saveHistoriesToStorage()
  if (currentHistoryIndex.value === idx) {
    currentHistoryIndex.value = -1
    messages.value = []
  } else if (currentHistoryIndex.value > idx) {
    currentHistoryIndex.value--
  }
}

// 清空所有历史
const clearAllHistory = () => {
  chatHistories.value = []
  currentHistoryIndex.value = -1
  messages.value = []
  saveHistoriesToStorage()
}

// 新建对话：清除后端会话，重置本地状态
const startNewConversation = async () => {
  try {
    const token = getAuthToken()
    const response = await fetch('/api/chat/session', {
      method: 'DELETE',
      headers: {
        'Authorization': token ? `Bearer ${token}` : '',
        'Content-Type': 'application/json'
      }
    })
    if (!response.ok) {
      throw new Error('清除会话失败')
    }
  } catch (err) {
    console.error('新建对话失败:', err)
  }
  // 重置本地状态
  messages.value = []
  currentHistoryIndex.value = -1
  inputText.value = ''
}

// 跳转到知识库管理
const goToKnowledgeBase = () => {
  router.push('/knowledge-base')
}
</script>

<style scoped>
/* 整体布局 */
.chat-home-container {
  display: flex;
  height: calc(100vh - 64px);
  background: #ffffff;
  color: #1f2937;
  animation: fadeInUp 0.6s ease-out;
}

@keyframes fadeInUp {
  from {
    opacity: 0;
    transform: translateY(30px);
  }
  to {
    opacity: 1;
    transform: translateY(0);
  }
}

@keyframes slideInLeft {
  from {
    opacity: 0;
    transform: translateX(-30px);
  }
  to {
    opacity: 1;
    transform: translateX(0);
  }
}

@keyframes slideInRight {
  from {
    opacity: 0;
    transform: translateX(30px);
  }
  to {
    opacity: 1;
    transform: translateX(0);
  }
}

/* 侧边栏 */
.chat-sidebar {
  width: 280px;
  min-width: 280px;
  background: #f8fafc;
  border-right: 1px solid #e5e7eb;
  display: flex;
  flex-direction: column;
  transition: all 0.3s ease;
  overflow: hidden;
  animation: slideInLeft 0.6s ease-out 0.2s both;
}

.chat-sidebar.collapsed {
  width: 60px;
  min-width: 60px;
}

.sidebar-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 16px;
  border-bottom: 1px solid #e5e7eb;
  flex-shrink: 0;
}

.sidebar-title {
  display: flex;
  align-items: center;
  gap: 8px;
  font-weight: 600;
  color: #1f2937;
}

.sidebar-icon {
  font-size: 18px;
  color: #3b82f6;
}

.collapse-btn {
  font-size: 16px;
}

.sidebar-section {
  padding: 12px;
  border-bottom: 1px solid #e5e7eb;
}

.history-section {
  flex: 1;
  overflow: hidden;
  display: flex;
  flex-direction: column;
}

.section-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 8px;
}

.section-title {
  font-size: 12px;
  font-weight: 600;
  color: #6b7280;
  text-transform: uppercase;
  letter-spacing: 0.05em;
}

.kb-list {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.empty-kb {
  text-align: center;
  color: #9ca3af;
  font-size: 13px;
  padding: 20px 0;
}

.kb-item {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 10px 12px;
  border-radius: 8px;
  cursor: pointer;
  transition: all 0.2s;
  border: 1.5px solid transparent;
}

.kb-item:hover {
  background: rgba(59, 130, 246, 0.06);
  border-color: rgba(59, 130, 246, 0.15);
}

.kb-item.active {
  background: rgba(59, 130, 246, 0.1);
  border-color: #3b82f6;
  color: #3b82f6;
}

.kb-icon {
  font-size: 16px;
  color: #3b82f6;
  flex-shrink: 0;
}

.kb-name {
  flex: 1;
  font-size: 13px;
  font-weight: 500;
  color: #374151;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.kb-item.active .kb-name {
  color: #3b82f6;
  font-weight: 600;
}

.kb-file-count {
  font-size: 11px;
  color: #9ca3af;
  background: #f3f4f6;
  padding: 2px 6px;
  border-radius: 10px;
  flex-shrink: 0;
}

.kb-item.active .kb-file-count {
  background: rgba(59, 130, 246, 0.12);
  color: #3b82f6;
}

.kb-expand-btn {
  font-size: 12px;
  color: #9ca3af;
  flex-shrink: 0;
  transition: transform 0.25s ease, color 0.2s;
  cursor: pointer;
  padding: 2px;
  border-radius: 4px;
}

.kb-expand-btn:hover {
  color: #3b82f6;
  background: rgba(59, 130, 246, 0.08);
}

.kb-expand-btn.expanded {
  transform: rotate(90deg);
  color: #3b82f6;
}

.kb-item-wrapper {
  display: flex;
  flex-direction: column;
}

.kb-children {
  padding: 2px 0 4px 28px;
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.kb-child-item {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 6px 8px;
  border-radius: 6px;
  font-size: 12px;
  color: #6b7280;
  transition: background 0.15s;
}

.kb-child-item:hover {
  background: rgba(0, 0, 0, 0.03);
}

.kb-child-icon {
  font-size: 13px;
  color: #10b981;
  flex-shrink: 0;
}

.kb-child-name {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

/* 展开收起动画 */
.kb-slide-enter-active,
.kb-slide-leave-active {
  transition: all 0.25s ease;
  overflow: hidden;
}

.kb-slide-enter-from,
.kb-slide-leave-to {
  opacity: 0;
  max-height: 0;
  padding-top: 0;
  padding-bottom: 0;
}

.kb-slide-enter-to,
.kb-slide-leave-from {
  opacity: 1;
  max-height: 500px;
}

/* 历史记录 */
.history-list {
  flex: 1;
  overflow-y: auto;
}

.empty-history {
  text-align: center;
  color: #9ca3af;
  font-size: 13px;
  padding: 20px 0;
}

.history-item {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 10px 8px;
  border-radius: 8px;
  cursor: pointer;
  transition: background 0.2s;
}

.history-item:hover {
  background: #e5e7eb;
}

.history-item.active {
  background: #fee2e2;
}

.history-icon {
  width: 32px;
  height: 32px;
  border-radius: 8px;
  background: #f3f4f6;
  display: flex;
  align-items: center;
  justify-content: center;
  color: #6b7280;
  flex-shrink: 0;
}

.history-content {
  flex: 1;
  min-width: 0;
}

.history-title {
  font-size: 13px;
  font-weight: 500;
  color: #1f2937;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.history-time {
  font-size: 11px;
  color: #9ca3af;
}

.history-delete {
  opacity: 0;
  transition: opacity 0.2s;
}

.history-item:hover .history-delete {
  opacity: 1;
}

.collapsed-icons {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  padding-top: 12px;
  gap: 8px;
}

.collapsed-icon-item {
  width: 40px;
  height: 40px;
  display: flex;
  align-items: center;
  justify-content: center;
  border-radius: 8px;
  cursor: pointer;
  color: #6b7280;
  transition: all 0.2s;
  border: 1.5px solid transparent;
}

.collapsed-icon-item:hover {
  background: #e5e7eb;
  color: #3b82f6;
}

.collapsed-icon-item.active {
  background: rgba(59, 130, 246, 0.1);
  color: #3b82f6;
  border-color: #3b82f6;
}

/* 主聊天区域 */
.chat-main {
  flex: 1;
  display: flex;
  flex-direction: column;
  min-width: 0;
  background: #ffffff;
  animation: slideInRight 0.6s ease-out 0.4s both;
}

/* 顶部栏 */
.chat-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 16px 24px;
  border-bottom: 1px solid #e5e7eb;
  background: #ffffff;
  flex-shrink: 0;
}

.header-left {
  display: flex;
  align-items: baseline;
  gap: 12px;
}

.header-right {
  display: flex;
  align-items: center;
  gap: 8px;
}

.chat-title {
  font-size: 18px;
  font-weight: 600;
  color: #1f2937;
  margin: 0;
}

.chat-subtitle {
  font-size: 13px;
  color: #9ca3af;
}

/* 消息区域 */
.chat-messages {
  flex: 1;
  overflow-y: auto;
  padding: 24px;
}

.welcome-area {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  height: 100%;
  text-align: center;
  padding: 40px;
}

.welcome-icon {
  width: 120px;
  height: 120px;
  border-radius: 24px;
  background: linear-gradient(135deg, #fef3e2 0%, #fee2e2 100%);
  display: flex;
  align-items: center;
  justify-content: center;
  color: #3b82f6;
  margin-bottom: 24px;
}

.welcome-area h2 {
  font-size: 24px;
  font-weight: 700;
  color: #1f2937;
  margin: 0 0 8px;
}

.welcome-area p {
  font-size: 14px;
  color: #6b7280;
  margin: 0 0 32px;
}

.feature-grid {
  display: flex;
  gap: 16px;
  margin-bottom: 32px;
}

.feature-card {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 8px;
  padding: 20px 24px;
  background: #f9fafb;
  border-radius: 12px;
  color: #6b7280;
  font-size: 13px;
}

.welcome-tip {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 12px 20px;
  background: #fef3c7;
  border-radius: 8px;
  color: #92400e;
  font-size: 13px;
  margin-bottom: 24px;
}

.suggestion-chips {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  justify-content: center;
  max-width: 500px;
}

.suggestion-chip {
  padding: 8px 16px;
  background: #f3f4f6;
  border: none;
  border-radius: 20px;
  font-size: 13px;
  color: #4b5563;
  cursor: pointer;
  transition: all 0.2s;
}

.suggestion-chip:hover {
  background: #e5e7eb;
  color: #1f2937;
}

/* 消息列表 */
.message-list {
  display: flex;
  flex-direction: column;
  gap: 20px;
}

.message-item {
  display: flex;
  gap: 12px;
  animation: msgIn 0.3s ease;
}

@keyframes msgIn {
  from { opacity: 0; transform: translateY(10px); }
  to { opacity: 1; transform: translateY(0); }
}

.message-item.user {
  flex-direction: row-reverse;
}

.message-avatar {
  width: 36px;
  height: 36px;
  border-radius: 50%;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}

.message-item.user .message-avatar {
  background: linear-gradient(135deg, #3b82f6, #60a5fa);
  color: #ffffff;
}

.message-item.ai .message-avatar {
  background: linear-gradient(135deg, #3b82f6, #2563eb);
  color: #ffffff;
}

.message-bubble-wrapper {
  max-width: 70%;
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.message-item.user .message-bubble-wrapper {
  align-items: flex-end;
}

.message-bubble {
  padding: 12px 16px;
  border-radius: 16px;
  font-size: 14px;
  line-height: 1.6;
}

.message-item.user .message-bubble {
  background: linear-gradient(135deg, #3b82f6, #60a5fa);
  color: #ffffff;
  border-bottom-right-radius: 4px;
}

.message-item.ai .message-bubble {
  background: #f3f4f6;
  color: #1f2937;
  border-bottom-left-radius: 4px;
}

.think-section {
  padding: 10px 12px;
  margin-bottom: 8px;
  background: rgba(59, 130, 246, 0.08);
  border-radius: 8px;
  border-left: 3px solid #3b82f6;
}

.think-label {
  display: flex;
  align-items: center;
  gap: 4px;
  font-size: 12px;
  font-weight: 600;
  color: #3b82f6;
  margin-bottom: 6px;
}

.think-content {
  font-size: 13px;
  color: #6b7280;
}

.message-text {
  word-break: break-word;
}

.loading-dots {
  display: flex;
  gap: 4px;
  padding: 4px 0;
}

.loading-dots span {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: #9ca3af;
  animation: bounce 1.4s infinite ease-in-out;
}

.loading-dots span:nth-child(1) { animation-delay: 0s; }
.loading-dots span:nth-child(2) { animation-delay: 0.2s; }
.loading-dots span:nth-child(3) { animation-delay: 0.4s; }

@keyframes bounce {
  0%, 80%, 100% { transform: scale(0.8); opacity: 0.5; }
  40% { transform: scale(1.2); opacity: 1; }
}

/* 参考资料 */
.sources-section {
  padding: 12px 14px;
  background: linear-gradient(135deg, #f8fafc 0%, #f1f5f9 100%);
  border-radius: 12px;
  font-size: 13px;
  border: 1px solid #e2e8f0;
  margin-top: 12px;
}

.sources-header {
  display: flex;
  align-items: center;
  gap: 8px;
  font-weight: 600;
  color: #475569;
  margin-bottom: 12px;
  padding-bottom: 8px;
  border-bottom: 1px solid #e2e8f0;
}

.sources-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.source-card {
  background: #ffffff;
  border-radius: 8px;
  border: 1px solid #e2e8f0;
  overflow: hidden;
  transition: all 0.2s ease;
}

.source-card:hover {
  border-color: #cbd5e1;
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.04);
}

.source-card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 10px 12px;
  cursor: pointer;
  user-select: none;
}

.source-left {
  display: flex;
  align-items: center;
  gap: 10px;
  min-width: 0;
  flex: 1;
}

.source-rank {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 22px;
  height: 22px;
  background: linear-gradient(135deg, #6366f1 0%, #8b5cf6 100%);
  color: white;
  font-size: 11px;
  font-weight: 700;
  border-radius: 6px;
  flex-shrink: 0;
}

.source-info {
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-width: 0;
}

.source-name {
  font-weight: 500;
  color: #1e293b;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  max-width: 200px;
}

.source-meta {
  font-size: 11px;
  color: #94a3b8;
}

.source-meta.section-path {
  color: #6366f1;
  font-weight: 500;
  max-width: 200px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.source-meta-row {
  display: flex;
  gap: 6px;
  align-items: center;
  flex-wrap: wrap;
}

.source-right {
  display: flex;
  align-items: center;
  gap: 12px;
  flex-shrink: 0;
}

.similarity-indicator {
  display: flex;
  align-items: center;
  gap: 8px;
}

.similarity-bar {
  width: 60px;
  height: 6px;
  background: #e2e8f0;
  border-radius: 3px;
  overflow: hidden;
}

.similarity-fill {
  height: 100%;
  border-radius: 3px;
  transition: width 0.4s ease;
}

.similarity-fill.high {
  background: linear-gradient(90deg, #10b981, #34d399);
}

.similarity-fill.medium {
  background: linear-gradient(90deg, #f59e0b, #fbbf24);
}

.similarity-fill.low {
  background: linear-gradient(90deg, #ef4444, #f87171);
}

.similarity-text {
  font-size: 12px;
  font-weight: 600;
  color: #64748b;
  min-width: 36px;
  text-align: right;
}

.expand-icon {
  color: #94a3b8;
  transition: transform 0.2s ease;
  font-size: 14px;
}

.expand-icon.expanded {
  transform: rotate(90deg);
}

.source-expand-enter-active,
.source-expand-leave-active {
  transition: all 0.25s ease;
  overflow: hidden;
}

.source-expand-enter-from,
.source-expand-leave-to {
  opacity: 0;
  max-height: 0;
}

.source-expand-enter-to,
.source-expand-leave-from {
  opacity: 1;
  max-height: 200px;
}

.source-text-preview {
  padding: 12px;
  background: #f8fafc;
  border-top: 1px solid #e2e8f0;
  font-size: 12px;
  line-height: 1.6;
  color: #475569;
  max-height: 150px;
  overflow-y: auto;
}

.source-text-preview p {
  margin: 0;
  white-space: pre-wrap;
  word-break: break-word;
}

/* 输入区域 */
.chat-input-area {
  padding: 16px 24px 24px;
  border-top: 1px solid #e5e7eb;
  background: #ffffff;
  flex-shrink: 0;
}

.input-wrapper {
  display: flex;
  gap: 12px;
  align-items: flex-end;
}

.input-field {
  flex: 1;
  padding: 12px 16px;
  border: 1px solid #e5e7eb;
  border-radius: 12px;
  font-size: 14px;
  resize: none;
  outline: none;
  font-family: inherit;
  line-height: 1.5;
  max-height: 120px;
  transition: border-color 0.2s;
}

.input-field:focus {
  border-color: #3b82f6;
}

.input-field:disabled {
  background: #f9fafb;
  color: #9ca3af;
}

.send-btn {
  width: 44px;
  height: 44px;
  border: none;
  border-radius: 12px;
  background: linear-gradient(135deg, #3b82f6, #2563eb);
  color: #ffffff;
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 18px;
  transition: all 0.2s;
  flex-shrink: 0;
}

.send-btn:hover:not(:disabled) {
  transform: scale(1.05);
  box-shadow: 0 4px 12px rgba(59, 130, 246, 0.3);
}

.send-btn:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.input-tips {
  display: flex;
  gap: 16px;
  margin-top: 8px;
  font-size: 11px;
  color: #9ca3af;
}

/* 响应式 */
@media (max-width: 768px) {
  .chat-sidebar,
  .chat-main {
    animation: fadeInUp 0.6s ease-out;
  }

  .chat-sidebar {
    position: fixed;
    left: 0;
    top: 0;
    bottom: 0;
    z-index: 100;
    transform: translateX(-100%);
  }

  .chat-sidebar:not(.collapsed) {
    transform: translateX(0);
  }

  .chat-main {
    width: 100%;
  }
}

/* 来源卡片预览按钮 */
.source-actions {
  padding: 6px 12px 10px;
  border-top: 1px solid #e2e8f0;
}

.preview-btn {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 4px 10px;
  border: 1px solid #e2e8f0;
  border-radius: 6px;
  background: #ffffff;
  color: #6366f1;
  font-size: 12px;
  cursor: pointer;
  transition: all 0.2s;
}

.preview-btn:hover {
  background: #eef2ff;
  border-color: #c7d2fe;
}

.preview-btn .el-icon {
  font-size: 14px;
}

/* 预览弹窗 */
.preview-overlay {
  position: fixed;
  inset: 0;
  z-index: 9999;
  background: rgba(0, 0, 0, 0.5);
  display: flex;
  align-items: center;
  justify-content: center;
  backdrop-filter: blur(4px);
}

.preview-modal {
  background: #ffffff;
  border-radius: 16px;
  box-shadow: 0 25px 50px rgba(0, 0, 0, 0.25);
  width: 90vw;
  max-width: 900px;
  max-height: 90vh;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}

.preview-header {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 16px 20px;
  border-bottom: 1px solid #e2e8f0;
  flex-shrink: 0;
}

.preview-title {
  font-weight: 600;
  font-size: 14px;
  color: #1e293b;
  flex: 1;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.preview-page {
  font-size: 13px;
  color: #6366f1;
  background: #eef2ff;
  padding: 2px 10px;
  border-radius: 10px;
  font-weight: 500;
  flex-shrink: 0;
}

.preview-close {
  width: 32px;
  height: 32px;
  border: none;
  border-radius: 8px;
  background: transparent;
  color: #64748b;
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 18px;
  transition: all 0.2s;
  flex-shrink: 0;
}

.preview-close:hover {
  background: #f1f5f9;
  color: #1e293b;
}

.preview-body {
  flex: 1;
  overflow: auto;
  display: flex;
  align-items: center;
  justify-content: center;
  min-height: 300px;
  padding: 20px;
}

.preview-loading {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 12px;
  color: #64748b;
  font-size: 14px;
}

.preview-loading .el-icon {
  font-size: 32px;
  color: #6366f1;
}

.preview-error {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 8px;
  color: #ef4444;
  font-size: 14px;
}

.preview-error .el-icon {
  font-size: 28px;
}

.preview-image {
  max-width: 100%;
  max-height: 75vh;
  border-radius: 4px;
  box-shadow: 0 2px 12px rgba(0, 0, 0, 0.08);
  cursor: zoom-in;
}

/* 预览弹窗过渡动画 */
.fade-enter-active,
.fade-leave-active {
  transition: opacity 0.25s ease;
}

.fade-enter-from,
.fade-leave-to {
  opacity: 0;
}
</style>
