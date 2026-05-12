<template>
  <div class="mobile-container">
         <!-- 顶部导航栏 -->
     <div class="mobile-header" style="height: 48px !important; overflow: visible !important;">
       <div class="header-content" style="height: 48px !important; overflow: visible !important;">
        <div class="header-left">
          <el-button 
            type="text" 
            @click="showKnowledgeBaseDrawer = true"
            class="menu-button"
          >
            <el-icon><Menu /></el-icon>
          </el-button>
        </div>
                 <div class="header-center" style="height: 48px !important; overflow: visible !important;">
           <h1 class="app-title" style="white-space: nowrap !important; word-break: normal !important; word-wrap: normal !important; hyphens: none !important; overflow: visible !important; text-overflow: clip !important; height: auto !important; line-height: 1.2 !important; display: block !important;">孪数建筑行业规范检索查询智能体</h1>
           <p class="current-kb" style="white-space: nowrap !important; word-break: normal !important; word-wrap: normal !important; hyphens: none !important; overflow: visible !important; text-overflow: clip !important; height: auto !important; line-height: 1.2 !important; display: block !important;">{{ currentKnowledgeBase || '请选择知识库' }}</p>
         </div>
                 <div class="header-right">
           <el-button 
             type="text" 
             @click="clearChat"
             v-if="!chatState.showWelcome"
             class="clear-button"
           >
             <el-icon><Delete /></el-icon>
           </el-button>
           <el-button 
             type="text" 
             @click="switchToDesktop"
             class="desktop-switch-btn"
           >
             <el-icon><Monitor /></el-icon>
           </el-button>
         </div>
      </div>
    </div>

    <!-- 主要内容区域 -->
    <div class="mobile-content">
      <!-- 欢迎页面 -->
      <div v-if="chatState.showWelcome" class="welcome-mobile">
        <div class="welcome-card-mobile">
          <div class="welcome-icon-mobile">
            <img src="/logo.png" alt="孪数建筑行业规范检索查询智能体" />
          </div>
          <h2>欢迎使用孪数建筑行业规范检索查询智能体</h2>
          <p>基于智能知识库的精准检索查询平台</p>
          <div class="feature-list-mobile">
            <div class="feature-item-mobile">
              <span class="feature-icon">📄</span>
              <span>智能文档解析</span>
            </div>
            <div class="feature-item-mobile">
              <span class="feature-icon">🧠</span>
              <span>深度思考分析</span>
            </div>
            <div class="feature-item-mobile">
              <span class="feature-icon">🔍</span>
              <span>精准知识检索</span>
            </div>
          </div>
          <el-button 
            type="primary" 
            size="large" 
            @click="showKnowledgeBaseDrawer = true"
            class="start-button"
          >
            选择知识库开始对话
          </el-button>
        </div>
      </div>

      <!-- 聊天消息列表 -->
      <div v-else class="message-list-mobile">
        <div
          v-for="msg in processedMessages"
          :key="msg.id"
          :class="['message-item-mobile', msg.type === 'user' ? 'user-message-mobile' : 'ai-message-mobile']"
        >
          <div class="message-avatar-mobile">
            <el-avatar 
              :icon="msg.type === 'user' ? User : ChatDotRound" 
              size="small"
            />
          </div>
          <div class="message-content-mobile">
            <div class="message-sender-mobile">
              {{ msg.type === 'user' ? '我' : 'AI助手' }}
            </div>
            
            <!-- 深度思考内容 -->
            <div v-if="msg.thinkContent && msg.thinkContent.trim() && msg.type === 'ai'" 
                 class="think-content-mobile">
              <div class="think-header">
                <span class="think-icon">💡</span>
                <span>深度思考</span>
              </div>
              <div class="think-text" v-html="msg.processedThinkContent"></div>
            </div>
            
            <!-- 主要内容 -->
            <div v-if="msg.processedContent && msg.processedContent.trim()" 
                 class="message-main-content-mobile" 
                 v-html="'<p>' + msg.processedContent + '</p>'">
            </div>
            
            <!-- 来源信息 -->
            <div v-if="msg.sources && msg.sources.length > 0" class="message-sources-mobile">
              <div class="sources-header">参考来源</div>
              <div class="sources-list">
                <div v-for="(source, idx) in msg.sources" :key="idx" class="source-item">
                  <div class="source-title">
                    {{ source.documentName }} ({{ source.page || 1 }})
                  </div>
                  <div class="source-text">{{ source.text }}</div>
                  <div v-if="source.score !== undefined" class="source-score">
                    相关性: {{ source.score.toFixed(2) }}
                  </div>
                </div>
              </div>
            </div>
          </div>
        </div>
        
        <!-- 加载状态 -->
        <div v-if="chatState.loading" class="loading-message-mobile">
          <div class="loading-spinner-mobile"></div>
          <div class="loading-text-mobile">AI正在思考中...</div>
        </div>
      </div>
    </div>

    <!-- 底部输入区域 -->
    <div class="mobile-footer" v-if="!chatState.showWelcome">
      <div class="input-container-mobile">
        <div class="input-row-mobile">
          <el-input
            v-model="chatState.message"
            type="textarea"
            :rows="1"
            placeholder="请输入您的问题..."
            @keydown.enter.exact.prevent="sendMessage"
            class="message-input-mobile"
            :autosize="{ minRows: 1, maxRows: 4 }"
          />
          <el-button 
            :type="chatState.loading ? 'danger' : 'primary'"
            @click="chatState.loading ? stopChat() : sendMessage()" 
            :loading="false"
            :disabled="!chatState.message.trim() && !chatState.loading"
            class="action-button-mobile"
          >
            {{ chatState.loading ? '停止' : '发送' }}
          </el-button>
        </div>
      </div>
    </div>

    <!-- 知识库选择抽屉 -->
    <el-drawer
      v-model="showKnowledgeBaseDrawer"
      title="选择知识库"
      direction="left"
      size="80%"
      class="knowledge-drawer"
    >
      <div class="drawer-content">
        <div class="drawer-header">
          <h3>知识库列表</h3>
          <el-button 
            type="primary" 
            size="small" 
            @click="goToKnowledgeBase"
          >
            管理知识库
          </el-button>
        </div>
        
        <div class="knowledge-list">
          <div 
            v-for="kb in knowledgeStore.knowledgeBaseTree" 
            :key="kb.id || kb.name"
            class="knowledge-item"
            :class="{ 'active': knowledgeStore.currentNode?.name === kb.name }"
            @click="selectKnowledgeBase(kb)"
          >
            <div class="knowledge-icon">
              <el-icon><FolderOpened /></el-icon>
            </div>
            <div class="knowledge-info">
              <div class="knowledge-name">{{ kb.name }}</div>
              <div class="knowledge-path">{{ kb.path || kb.name }}</div>
            </div>
            <div class="knowledge-arrow">
              <el-icon><ArrowRight /></el-icon>
            </div>
          </div>
        </div>
      </div>
    </el-drawer>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, computed, onMounted, onUnmounted, nextTick } from 'vue'
import { useRouter } from 'vue-router'
import { useKnowledgeStore } from '../stores/knowledgeStore'
import { ElMessage } from 'element-plus'
import { 
  User, 
  ChatDotRound, 
  Menu, 
  Delete, 
  FolderOpened, 
  ArrowRight,
  Monitor
} from '@element-plus/icons-vue'
import { getAuthToken } from '../utils/auth'
import { markdownToHtml, getKnowledgeBaseId, loadFileList } from '../utils/chat'

const router = useRouter()
const knowledgeStore = useKnowledgeStore()

// 状态管理
const showKnowledgeBaseDrawer = ref(false)
const currentKnowledgeBase = ref('')

// 聊天状态
const chatState = reactive({
  message: '',
  loading: false,
  showWelcome: true,
  messages: [] as any[],
  abortController: null as AbortController | null
})



// 处理后的消息列表
function sanitizeHtml(html: string): string {
  if (!html) return ''
  try {
    const parser = new DOMParser()
    const doc = parser.parseFromString(String(html), 'text/html')
    doc.querySelectorAll('script,style').forEach(n => n.remove())
    doc.querySelectorAll('*').forEach((el) => {
      Array.from(el.attributes).forEach((attr) => {
        if (/^on/i.test(attr.name)) {
          try { el.removeAttribute(attr.name) } catch (e) {}
        }
        if (/(href|src)/i.test(attr.name) && /javascript:/i.test(attr.value || '')) {
          try { el.removeAttribute(attr.name) } catch (e) {}
        }
      })
    })
    return doc.body.innerHTML || ''
  } catch (e) {
    let out = String(html)
    out = out.replace(/<script[\s\S]*?>[\s\S]*?<\/script>/gi, '')
    out = out.replace(/ on[\w-]+=(["'][\s\S]*?["']|[^\s>]+)/gi, '')
    out = out.replace(/(href|src)\s*=\s*("|')?javascript:[^"'>\s]+(\2)?/gi, '')
    return out
  }
}

const processedMessages = computed(() => {
  const cache = (processedMessages as any)._cache || ((processedMessages as any)._cache = new WeakMap())

  return chatState.messages.map((msg, index) => {
    const content = String(msg.content || msg.answer || '')
    const thinkContent = String(msg.thinkContent || '')
    const sourcesArr = Array.isArray(msg.sources) ? msg.sources : []

    const isStreamingLastAi = !!chatState.loading && index === (chatState.messages.length - 1) && String(msg.type || '') === 'ai'

    let processedContent = ''
    let processedThinkContent = ''

    if (isStreamingLastAi) {
      const escape = (s: string) => String(s || '')
        .replace(/&/g, '&amp;')
        .replace(/</g, '&lt;')
        .replace(/>/g, '&gt;')
        .replace(/\r\n/g, '\n')
        .replace(/\r/g, '\n')
      processedContent = escape(content).replace(/\n/g, '<br/>')
      processedThinkContent = escape(thinkContent).replace(/\n/g, '<br/>')
    } else {
      const cached = cache.get(msg)
      if (cached && cached.content === content && cached.thinkContent === thinkContent) {
        processedContent = cached.processedContent
        processedThinkContent = cached.processedThinkContent
      } else {
        try {
          processedContent = markdownToHtml(content)
        } catch (e) {
          processedContent = content.replace(/</g, '&lt;').replace(/>/g, '&gt;')
        }
        try {
          processedThinkContent = markdownToHtml(thinkContent)
        } catch (e) {
          processedThinkContent = thinkContent.replace(/</g, '&lt;').replace(/>/g, '&gt;')
        }

        processedContent = String(processedContent || '')
        processedThinkContent = String(processedThinkContent || '')

        processedContent = sanitizeHtml ? sanitizeHtml(processedContent) : processedContent
        processedThinkContent = sanitizeHtml ? sanitizeHtml(processedThinkContent) : processedThinkContent

        cache.set(msg, { content, thinkContent, processedContent, processedThinkContent })
      }
    }

    return {
      ...msg,
      id: index,
      sources: sourcesArr,
      processedContent,
      processedThinkContent
    }
  })
})

// 选择知识库
const selectKnowledgeBase = (kb: any) => {
  knowledgeStore.setCurrentNode(kb)
  currentKnowledgeBase.value = kb.name
  chatState.showWelcome = false
  showKnowledgeBaseDrawer.value = false
  
  // 加载文件列表
  if (kb.type === 'folder') {
    loadFileList(kb).then(fileList => {
      console.log('文件列表加载成功:', fileList)
    })
  }
}

// 跳转到知识库管理页面
const goToKnowledgeBase = () => {
  router.push('/knowledge-base')
}

// 清空聊天
const clearChat = () => {
  chatState.messages = []
  chatState.showWelcome = true
  currentKnowledgeBase.value = ''
}

// 停止对话
const stopChat = () => {
  if (chatState.abortController) {
    chatState.abortController.abort()
    chatState.abortController = null
    chatState.loading = false
    ElMessage.info('对话已停止')
  }
}

// 发送消息
const sendMessage = async () => {
  if (!knowledgeStore.currentNode) {
    ElMessage.warning('请先选择知识库')
    return
  }
  
  const message = chatState.message.trim()
  if (!message) return
  
  // 添加用户消息
  const userMessage = {
    type: 'user',
    content: message,
    timestamp: new Date().toLocaleString()
  }
  
  chatState.messages.push(userMessage)
  chatState.message = ''
  chatState.loading = true
  
  // 创建AI消息占位符
  const aiMessage = {
    type: 'ai',
    content: '',
    thinkContent: '正在分析您的问题...',
    // 仅用于 <think>...</think> 段落解析，不等同“加载中/思考中”的 UI 状态。
    inThinkMode: false,
    timestamp: new Date().toLocaleString(),
    sources: []
  }
  
  chatState.messages.push(aiMessage)
  
  try {
    const requestBody = {
      question: message,
      knowledgeBaseId: getKnowledgeBaseId(knowledgeStore.currentNode)
    }
    
    chatState.abortController = new AbortController()
    const timeoutId = setTimeout(() => chatState.abortController?.abort(), 300000)
    
    const response = await fetch('/api/chat/stream', {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'Authorization': 'Bearer ' + getAuthToken()
      },
      body: JSON.stringify(requestBody),
      signal: chatState.abortController.signal
    })
    
    clearTimeout(timeoutId)
    
    if (!response.ok) {
      throw new Error(`HTTP error! status: ${response.status}`)
    }
    
    const reader = response.body?.getReader()
    if (!reader) {
      throw new Error('无法读取响应流')
    }
    
    let buffer = ''
    let hasReceivedSources = false
    let hasStartedAnswer = false

    const isPlaceholderThink = (s: any) => {
      const t = String(s || '').trim()
      if (!t) return true
      if (t === '正在分析您的问题...') return true
      if (t.includes('已找到') && t.includes('正在生成回答')) return true
      if (t.includes('未找到相关文档') && t.includes('生成回答')) return true
      if (t.includes('模型加载中') && t.includes('请稍候')) return true
      return false
    }

    const splitThinkAndAnswerByMarkers = (text: string) => {
      const raw = String(text || '')
      if (!raw.trim()) return { hasSplit: false, think: '', answer: raw }
      const thinkRe = /(^|\n)\s*(深度思考|思考过程|思考|推理过程|推理|分析过程|分析|思路)\s*[:：]/
      const answerRe = /(^|\n)\s*(最终回答|最终答案|答案|回答|结论|最终结论)\s*[:：]/
      const thinkMatch = raw.match(thinkRe)
      const answerMatch = raw.match(answerRe)
      const thinkIdx = thinkMatch ? raw.indexOf(thinkMatch[0]) : -1
      const answerIdx = answerMatch ? raw.indexOf(answerMatch[0]) : -1
      if (answerIdx >= 0) {
        const before = raw.slice(0, answerIdx)
        const after = raw.slice(answerIdx)
        const afterClean = after.replace(answerRe, '\n').trimStart()
        let thinkPart = before
        if (thinkIdx >= 0 && thinkIdx < answerIdx) {
          thinkPart = raw.slice(thinkIdx, answerIdx).replace(thinkRe, '\n').trim()
        }
        return { hasSplit: true, think: String(thinkPart || '').trim(), answer: String(afterClean || '').trim() }
      }
      return { hasSplit: false, think: '', answer: raw }
    }
    
    while (true) {
      const { done, value } = await reader.read()
      
      if (done) break
      
      const chunk = new TextDecoder().decode(value)
      buffer += chunk
      
      const lines = buffer.split('\n')
      buffer = lines.pop() || ''
      
      for (const line of lines) {
        if (line.trim() === '') continue
        
        if (line.startsWith('data:')) {
          try {
            // line 可能是 "data: {...}\r" 或 "data:    {...}"，JSON.parse 允许前后空白但这里显式 trim。
            const data = JSON.parse(line.slice(5).trim())
            
            if (data.type === 'sources') {
              aiMessage.sources = data.sources
              hasReceivedSources = true
              
              if (data.sources && data.sources.length > 0) {
                aiMessage.thinkContent = `已找到 ${data.sources.length} 个相关文档，正在生成回答...`
              } else {
                aiMessage.thinkContent = '未找到相关文档，正在基于通用知识生成回答...'
              }
              
              chatState.messages = [...chatState.messages]

            } else if (data.type === 'loading') {
              // 后端 loading 心跳：作为思考区状态提示
              if (!hasStartedAnswer && isPlaceholderThink(aiMessage.thinkContent)) {
                aiMessage.thinkContent = (data.content && String(data.content).trim().length > 0)
                  ? String(data.content)
                  : '模型加载中，请稍候...'
                chatState.messages = [...chatState.messages]
              }

            } else if (data.type === 'thinking') {
              const thinkChunk = (data.content && String(data.content).length > 0)
                ? String(data.content)
                : String((data.thinking || (data.message && (data.message.thinking || data.message.content)) || '') || '')
              if (thinkChunk && thinkChunk.length > 0) {
                if (isPlaceholderThink(aiMessage.thinkContent)) aiMessage.thinkContent = ''
                aiMessage.thinkContent += thinkChunk
                chatState.messages = [...chatState.messages]
              }
              
            } else if (data.type === 'answer') {
              if (!hasStartedAnswer) {
                // 不清空 thinkContent（保留 sources/loading 状态提示），避免“思考/回答”混在一起
                hasStartedAnswer = true
              }
              
              const content = String(data.content || '')

              // 默认把 answer 追加到 content；只有进入 <think> 模式时才写入 thinkContent。
              if (content.includes('<think>') && content.includes('</think>')) {
                const parts = content.split('</think>')
                const thinkPart = parts[0].replace('<think>', '')
                const answerPart = parts.slice(1).join('</think>')
                if (isPlaceholderThink(aiMessage.thinkContent)) aiMessage.thinkContent = ''
                aiMessage.thinkContent += String(thinkPart || '')
                aiMessage.inThinkMode = false
                aiMessage.content += String(answerPart || '')
                chatState.messages = [...chatState.messages]
                continue
              }
              
              if (content.includes('<think>') && !aiMessage.inThinkMode) {
                aiMessage.inThinkMode = true
                if (isPlaceholderThink(aiMessage.thinkContent)) aiMessage.thinkContent = ''
                aiMessage.thinkContent += content.replace('<think>', '')
              } else if (content.includes('</think>') && aiMessage.inThinkMode) {
                aiMessage.inThinkMode = false
                aiMessage.content += content.replace('</think>', '')
              } else if (aiMessage.inThinkMode) {
                if (isPlaceholderThink(aiMessage.thinkContent)) aiMessage.thinkContent = ''
                aiMessage.thinkContent += content
              } else {
                ;(aiMessage as any)._rawAnswerCombined = String((aiMessage as any)._rawAnswerCombined || '') + String(content || '')
                const combined = String((aiMessage as any)._rawAnswerCombined || '')
                const split = splitThinkAndAnswerByMarkers(combined)
                if (split.hasSplit) {
                  const thinkPart = String(split.think || '')
                  const answerPart = String(split.answer || '')
                  if (thinkPart) {
                    if (isPlaceholderThink(aiMessage.thinkContent)) {
                      aiMessage.thinkContent = thinkPart
                    } else {
                      const prefix = String(aiMessage.thinkContent || '').trim()
                      aiMessage.thinkContent = prefix ? (prefix + '\n\n' + thinkPart) : thinkPart
                    }
                  }
                  aiMessage.content = answerPart
                } else {
                  aiMessage.content = combined
                }
              }
              
              chatState.messages = [...chatState.messages]
            }
            
          } catch (error) {
            console.error('解析JSON失败:', error)
          }
        }
      }
    }

    // 流结束时，可能还有最后一行没有以 \n 结尾（例如连接关闭前未补换行），这里把残留 buffer 也解析一遍
    if (buffer && buffer.trim()) {
      const leftoverLines = buffer.split('\n').filter((l: string) => String(l || '').trim())
      buffer = ''

      for (const line of leftoverLines) {
        if (line.trim() === '') continue

        if (line.startsWith('data:')) {
          try {
            const data = JSON.parse(line.slice(5).trim())

            if (data.type === 'sources') {
              aiMessage.sources = data.sources
              hasReceivedSources = true

              if (data.sources && data.sources.length > 0) {
                aiMessage.thinkContent = `已找到 ${data.sources.length} 个相关文档，正在生成回答...`
              } else {
                aiMessage.thinkContent = '未找到相关文档，正在基于通用知识生成回答...'
              }

              chatState.messages = [...chatState.messages]

            } else if (data.type === 'loading') {
              if (!hasStartedAnswer && isPlaceholderThink(aiMessage.thinkContent)) {
                aiMessage.thinkContent = (data.content && String(data.content).trim().length > 0)
                  ? String(data.content)
                  : '模型加载中，请稍候...'
                chatState.messages = [...chatState.messages]
              }

            } else if (data.type === 'answer') {
              if (!hasStartedAnswer) {
                hasStartedAnswer = true
              }

              const content = String(data.content || '')

              if (content.includes('<think>') && content.includes('</think>')) {
                const parts = content.split('</think>')
                const thinkPart = parts[0].replace('<think>', '')
                const answerPart = parts.slice(1).join('</think>')
                if (isPlaceholderThink(aiMessage.thinkContent)) aiMessage.thinkContent = ''
                aiMessage.thinkContent += String(thinkPart || '')
                aiMessage.inThinkMode = false
                aiMessage.content += String(answerPart || '')
                chatState.messages = [...chatState.messages]
                continue
              }

              if (content.includes('<think>') && !aiMessage.inThinkMode) {
                aiMessage.inThinkMode = true
                if (isPlaceholderThink(aiMessage.thinkContent)) aiMessage.thinkContent = ''
                aiMessage.thinkContent += content.replace('<think>', '')
              } else if (content.includes('</think>') && aiMessage.inThinkMode) {
                aiMessage.inThinkMode = false
                aiMessage.content += content.replace('</think>', '')
              } else if (aiMessage.inThinkMode) {
                if (isPlaceholderThink(aiMessage.thinkContent)) aiMessage.thinkContent = ''
                aiMessage.thinkContent += content
              } else {
                ;(aiMessage as any)._rawAnswerCombined = String((aiMessage as any)._rawAnswerCombined || '') + String(content || '')
                const combined = String((aiMessage as any)._rawAnswerCombined || '')
                const split = splitThinkAndAnswerByMarkers(combined)
                if (split.hasSplit) {
                  const thinkPart = String(split.think || '')
                  const answerPart = String(split.answer || '')
                  if (thinkPart) {
                    if (isPlaceholderThink(aiMessage.thinkContent)) {
                      aiMessage.thinkContent = thinkPart
                    } else {
                      const prefix = String(aiMessage.thinkContent || '').trim()
                      aiMessage.thinkContent = prefix ? (prefix + '\n\n' + thinkPart) : thinkPart
                    }
                  }
                  aiMessage.content = answerPart
                } else {
                  aiMessage.content = combined
                }
              }

              chatState.messages = [...chatState.messages]
            }
          } catch (error) {
            console.error('解析JSON失败:', error)
          }
        }
      }
    }
    
    chatState.messages = [...chatState.messages]
    
  } catch (error) {
    console.error('发送消息失败:', error)
    
    if ((error as any).name === 'AbortError') {
      ElMessage.error('请求超时，请稍后重试')
      aiMessage.content = '抱歉，回答生成超时，请尝试重新提问或稍后重试。'
    } else {
      ElMessage.error('发送消息失败')
      aiMessage.content = '抱歉，处理您的问题时遇到了技术问题，请稍后重试。'
    }
    
    aiMessage.inThinkMode = false
    aiMessage.thinkContent = ''
    chatState.messages = [...chatState.messages]
    
  } finally {
    chatState.loading = false
    chatState.abortController = null
  }
}

onMounted(() => {
  knowledgeStore.fetchKnowledgeBaseTree()
  
  // 强制设置顶部导航栏样式，防止标题换行
  nextTick(() => {
    // 强制设置标题样式
    const appTitle = document.querySelector('.app-title')
    const currentKb = document.querySelector('.current-kb')
    const mobileHeader = document.querySelector('.mobile-header')
    const headerContent = document.querySelector('.header-content')
    const headerCenter = document.querySelector('.header-center')
    
    if (appTitle) {
      appTitle.setAttribute('style', 'white-space: nowrap !important; word-break: normal !important; word-wrap: normal !important; hyphens: none !important; overflow: visible !important; text-overflow: clip !important; height: auto !important; line-height: 1.2 !important; display: block !important;')
    }
    
    if (currentKb) {
      currentKb.setAttribute('style', 'white-space: nowrap !important; word-break: normal !important; word-wrap: normal !important; hyphens: none !important; overflow: visible !important; text-overflow: clip !important; height: auto !important; line-height: 1.2 !important; display: block !important;')
    }
    
    if (mobileHeader) {
      mobileHeader.setAttribute('style', 'height: 48px !important; overflow: visible !important;')
    }
    
    if (headerContent) {
      headerContent.setAttribute('style', 'height: 48px !important; overflow: visible !important;')
    }
    
    if (headerCenter) {
      headerCenter.setAttribute('style', 'height: 48px !important; overflow: visible !important;')
    }
    
    // 监听窗口大小变化，确保样式始终生效
    const handleResize = () => {
      if (appTitle) {
        appTitle.setAttribute('style', 'white-space: nowrap !important; word-break: normal !important; word-wrap: normal !important; hyphens: none !important; overflow: visible !important; text-overflow: clip !important; height: auto !important; line-height: 1.2 !important; display: block !important;')
      }
      if (currentKb) {
        currentKb.setAttribute('style', 'white-space: nowrap !important; word-break: normal !important; word-wrap: normal !important; hyphens: none !important; overflow: visible !important; text-overflow: clip !important; height: auto !important; line-height: 1.2 !important; display: block !important;')
      }
    }
    
    window.addEventListener('resize', handleResize)
    
    // 清理事件监听器
    onUnmounted(() => {
      window.removeEventListener('resize', handleResize)
    })
  })
})

// 切换到桌面版
const switchToDesktop = () => {
  // 设置用户偏好为桌面版
  localStorage.setItem('prefer-desktop', 'true')
  router.push('/app/')
}
</script>

<style scoped>
/* CSS重置 - 确保移动端布局正确 */
* {
  box-sizing: border-box;
}

/* 全局防止标题换行 - 使用最高优先级 */
.app-title, .current-kb {
  word-break: normal !important;
  word-wrap: normal !important;
  hyphens: none !important;
  white-space: nowrap !important;
  overflow: visible !important;
  text-overflow: clip !important;
}

/* 使用更具体的选择器来确保优先级 */
.mobile-container .mobile-header .header-content .header-center .app-title,
.mobile-container .mobile-header .header-content .header-center .current-kb {
  word-break: normal !important;
  word-wrap: normal !important;
  hyphens: none !important;
  white-space: nowrap !important;
  overflow: visible !important;
  text-overflow: clip !important;
  height: auto !important;
  line-height: 1.2 !important;
  display: block !important;
  max-width: none !important;
  width: auto !important;
}

/* 强制覆盖任何可能的全局样式 */
.mobile-container .mobile-header .header-content .header-center .app-title {
  font-size: 16px !important;
  font-weight: 600 !important;
  color: white !important;
  margin: 0 !important;
  padding: 0 !important;
}

.mobile-container .mobile-header .header-content .header-center .current-kb {
  font-size: 11px !important;
  color: rgba(255, 255, 255, 0.8) !important;
  margin: 2px 0 0 0 !important;
  padding: 0 !important;
}

/* 移动端视口处理 */
html, body {
  height: 100%;
  overflow: hidden;
  position: fixed;
  width: 100%;
}

/* 移动端容器 */
.mobile-container {
  height: 100vh;
  height: 100dvh;
  display: flex;
  flex-direction: column;
  background: #f5f7fa;
  overflow: hidden;
  position: relative;
  width: 100%;
  box-sizing: border-box;
  /* 确保容器不会超出视口 */
  max-height: 100vh;
  max-height: 100dvh;
}

/* 顶部导航栏 - 完全重写，确保标题正常显示 */
.mobile-header {
  background: linear-gradient(135deg, #409EFF 0%, #2c5aa0 100%);
  color: white;
  padding: 0;
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.1);
  z-index: 100;
  flex-shrink: 0;
  position: relative;
  width: 100%;
  box-sizing: border-box;
  height: 48px;
  overflow: visible;
}

.header-content {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 8px 12px;
  height: 48px;
  overflow: visible;
}

.header-left, .header-right {
  flex: 0 0 auto;
  display: flex;
  align-items: center;
}

.header-center {
  flex: 1;
  text-align: center;
  margin: 0 8px;
  min-width: 0;
  max-width: 100%;
  display: flex;
  flex-direction: column;
  justify-content: center;
  align-items: center;
  overflow: visible;
}

.app-title {
  margin: 0;
  font-size: 16px;
  font-weight: 600;
  color: white;
  white-space: nowrap;
  overflow: visible;
  text-overflow: clip;
  line-height: 1.2;
  max-width: 100%;
  display: block;
  word-break: normal;
  word-wrap: normal;
  hyphens: none;
}

.current-kb {
  margin: 2px 0 0 0;
  font-size: 11px;
  color: rgba(255, 255, 255, 0.8);
  white-space: nowrap;
  overflow: visible;
  text-overflow: clip;
  line-height: 1.2;
  max-width: 100%;
  display: block;
  word-break: normal;
  word-wrap: normal;
  hyphens: none;
}

.menu-button, .clear-button, .desktop-switch-btn {
  color: white;
  font-size: 18px;
  padding: 6px;
  min-width: 32px;
  height: 32px;
}

.desktop-switch-btn {
  margin-left: 8px;
}

/* 主要内容区域 */
.mobile-content {
  flex: 1;
  overflow: hidden;
  display: flex;
  flex-direction: column;
  position: relative;
  min-height: 0;
  width: 100%;
  box-sizing: border-box;
  /* 为固定的输入栏留出空间 */
  padding-bottom: 120px;
  max-height: calc(100vh - 120px);
  max-height: calc(100dvh - 120px);
  /* 确保与顶部导航栏有合适的间距 */
  margin-top: 0;
}

/* 欢迎页面 */
.welcome-mobile {
  flex: 1;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 20px;
}

.welcome-card-mobile {
  text-align: center;
  max-width: 100%;
  padding: 40px 20px;
  background: white;
  border-radius: 16px;
  box-shadow: 0 4px 20px rgba(0, 0, 0, 0.1);
}

.welcome-icon-mobile img {
  width: 80px;
  height: 80px;
  object-fit: contain;
  margin-bottom: 20px;
}

.welcome-card-mobile h2 {
  margin: 0 0 12px 0;
  font-size: 24px;
  font-weight: 600;
  color: #303133;
}

.welcome-card-mobile p {
  margin: 0 0 30px 0;
  color: #606266;
  font-size: 16px;
}

.feature-list-mobile {
  display: flex;
  flex-direction: column;
  gap: 12px;
  margin-bottom: 30px;
}

.feature-item-mobile {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 12px 16px;
  background: #f8f9fa;
  border-radius: 12px;
  border: 1px solid #e9ecef;
}

.feature-icon {
  font-size: 20px;
}

.feature-item-mobile span:last-child {
  color: #303133;
  font-weight: 500;
}

.start-button {
  width: 100%;
  height: 48px;
  font-size: 16px;
  font-weight: 500;
  border-radius: 12px;
}

/* 消息列表 */
.message-list-mobile {
  flex: 1;
  overflow-y: auto;
  padding: 16px;
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.message-item-mobile {
  display: flex;
  gap: 12px;
  animation: fadeIn 0.3s ease-out;
}

.message-avatar-mobile {
  flex-shrink: 0;
  margin-top: 4px;
}

.message-content-mobile {
  flex: 1;
  max-width: 85%;
}

.message-sender-mobile {
  font-weight: 500;
  color: #303133;
  margin-bottom: 8px;
  font-size: 14px;
}

/* 用户消息样式 */
.user-message-mobile {
  flex-direction: row-reverse;
}

.user-message-mobile .message-content-mobile {
  text-align: right;
}

.user-message-mobile .message-sender-mobile {
  text-align: right;
}

.user-message-mobile .message-main-content-mobile {
  background: linear-gradient(135deg, #409EFF 0%, #2c5aa0 100%);
  color: white;
  border-radius: 18px 18px 4px 18px;
  padding: 12px 16px;
  margin-left: 20px;
}

/* AI消息样式 */
.ai-message-mobile .message-main-content-mobile {
  background: white;
  color: #303133;
  border-radius: 18px 18px 18px 4px;
  padding: 12px 16px;
  margin-right: 20px;
  border: 1px solid #e9ecef;
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.05);
}

/* 思考内容样式 */
.think-content-mobile {
  background: #f8f9fa;
  border-radius: 12px;
  padding: 12px;
  margin-bottom: 12px;
  border-left: 4px solid #409EFF;
}

.think-header {
  display: flex;
  align-items: center;
  gap: 8px;
  color: #409EFF;
  font-weight: 500;
  margin-bottom: 8px;
  font-size: 14px;
}

.think-icon {
  font-size: 16px;
}

.think-text {
  color: #606266;
  font-size: 14px;
  line-height: 1.5;
}

/* 来源信息样式 */
.message-sources-mobile {
  margin-top: 12px;
  background: #f8f9fa;
  border-radius: 12px;
  padding: 12px;
  border: 1px solid #e9ecef;
}

.sources-header {
  font-weight: 500;
  color: #303133;
  margin-bottom: 8px;
  font-size: 14px;
}

.sources-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.source-item {
  background: white;
  border-radius: 8px;
  padding: 8px 12px;
  border: 1px solid #e9ecef;
}

.source-title {
  font-weight: 500;
  color: #409EFF;
  font-size: 13px;
  margin-bottom: 4px;
}

.source-text {
  color: #606266;
  font-size: 12px;
  line-height: 1.4;
  margin-bottom: 4px;
}

.source-score {
  color: #909399;
  font-size: 11px;
}

/* 加载状态 */
.loading-message-mobile {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 12px 16px;
  color: #606266;
  background: white;
  border-radius: 12px;
  border: 1px solid #e9ecef;
  margin: 8px 0;
}

.loading-spinner-mobile {
  width: 20px;
  height: 20px;
  border: 2px solid #e9ecef;
  border-top: 2px solid #409EFF;
  border-radius: 50%;
  animation: spin 1s linear infinite;
}

.loading-text-mobile {
  font-size: 14px;
}

@keyframes spin {
  0% { transform: rotate(0deg); }
  100% { transform: rotate(360deg); }
}

@keyframes fadeIn {
  from { opacity: 0; transform: translateY(10px); }
  to { opacity: 1; transform: translateY(0); }
}

/* 底部输入区域 */
.mobile-footer {
  background: white;
  border-top: 1px solid #e9ecef;
  padding: 12px 16px;
  flex-shrink: 0;
  position: fixed;
  bottom: 0;
  left: 0;
  right: 0;
  z-index: 1000;
  width: 100%;
  box-sizing: border-box;
  /* 确保在移动端完全可见 */
  padding-bottom: calc(12px + env(safe-area-inset-bottom));
}

.input-container-mobile {
  display: flex;
  flex-direction: column;
  gap: 8px;
  width: 100%;
  box-sizing: border-box;
}

.input-row-mobile {
  display: flex;
  gap: 8px;
  width: 100%;
  box-sizing: border-box;
}

.message-input-mobile {
  flex: 1;
  width: auto !important;
  box-sizing: border-box !important;
}

/* 使用更强的选择器来覆盖Element Plus的默认样式 */
.message-input-mobile :deep(.el-textarea) {
  width: 100% !important;
  box-sizing: border-box !important;
}

.message-input-mobile :deep(.el-textarea__inner) {
  border-radius: 20px !important;
  border: 1px solid #e9ecef !important;
  padding: 12px 16px !important;
  font-size: 16px !important;
  line-height: 1.4 !important;
  resize: none !important;
  background: #f8f9fa !important;
  width: 100% !important;
  box-sizing: border-box !important;
  min-height: 44px !important;
  max-height: 120px !important;
  display: block !important;
}

.message-input-mobile :deep(.el-textarea__inner):focus {
  border-color: #409EFF !important;
  background: white !important;
  box-shadow: 0 0 0 3px rgba(64, 158, 255, 0.1) !important;
}

.action-button-mobile {
  flex-shrink: 0;
  height: 40px;
  width: 60px;
  border-radius: 20px;
  font-weight: 500;
  font-size: 14px;
  min-width: 0;
  box-sizing: border-box;
  /* 确保按钮可见 */
  color: white !important;
  border: none !important;
  transition: all 0.3s ease;
}

.action-button-mobile.el-button--primary {
  background: linear-gradient(135deg, #409EFF 0%, #2c5aa0 100%) !important;
}

.action-button-mobile.el-button--danger {
  background: linear-gradient(135deg, #f56c6c 0%, #e74c3c 100%) !important;
}

.action-button-mobile:hover {
  transform: translateY(-1px);
  box-shadow: 0 4px 12px rgba(0, 0, 0, 0.15);
}

.action-button-mobile:active {
  transform: translateY(0);
}

/* 知识库抽屉 */
.knowledge-drawer :deep(.el-drawer__header) {
  background: linear-gradient(135deg, #409EFF 0%, #2c5aa0 100%);
  color: white;
  margin-bottom: 0;
  padding: 20px;
}

.knowledge-drawer :deep(.el-drawer__title) {
  color: white;
  font-size: 18px;
  font-weight: 600;
}

.drawer-content {
  padding: 20px;
}

.drawer-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 20px;
  padding-bottom: 16px;
  border-bottom: 1px solid #e9ecef;
}

.drawer-header h3 {
  margin: 0;
  color: #303133;
  font-size: 16px;
  font-weight: 600;
}

.knowledge-list {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.knowledge-item {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 16px;
  background: white;
  border-radius: 12px;
  border: 1px solid #e9ecef;
  cursor: pointer;
  transition: all 0.3s ease;
}

.knowledge-item:hover {
  border-color: #409EFF;
  box-shadow: 0 2px 8px rgba(64, 158, 255, 0.1);
}

.knowledge-item.active {
  background: linear-gradient(135deg, rgba(64, 158, 255, 0.1) 0%, rgba(64, 158, 255, 0.05) 100%);
  border-color: #409EFF;
}

.knowledge-icon {
  flex-shrink: 0;
  width: 40px;
  height: 40px;
  display: flex;
  align-items: center;
  justify-content: center;
  background: rgba(64, 158, 255, 0.1);
  border-radius: 8px;
  color: #409EFF;
}

.knowledge-info {
  flex: 1;
  min-width: 0;
}

.knowledge-name {
  font-weight: 500;
  color: #303133;
  font-size: 16px;
  margin-bottom: 4px;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.knowledge-path {
  color: #909399;
  font-size: 12px;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.knowledge-arrow {
  flex-shrink: 0;
  color: #c0c4cc;
}

.knowledge-item:hover .knowledge-arrow {
  color: #409EFF;
}

/* 响应式调整 */
@media (max-width: 480px) {
  .mobile-header {
    height: 44px;
  }
  
  .header-content {
    padding: 6px 10px;
    height: 44px;
  }
  
  .header-center {
    height: 44px;
  }
  
  .app-title {
    font-size: 14px;
  }
  
  .current-kb {
    font-size: 10px;
    margin: 1px 0 0 0;
  }
  
  .menu-button, .clear-button, .desktop-switch-btn {
    font-size: 16px;
    padding: 5px;
    min-width: 28px;
    height: 28px;
  }
  
  .welcome-card-mobile {
    padding: 30px 16px;
  }
  
  .welcome-card-mobile h2 {
    font-size: 20px;
  }
  
  .message-list-mobile {
    padding: 12px;
  }
  
  .mobile-footer {
    padding: 8px 12px;
  }
  
  .drawer-content {
    padding: 16px;
  }
  
  .message-input-mobile :deep(.el-textarea__inner) {
    font-size: 14px;
    padding: 10px 14px;
    min-height: 40px;
  }
  
  .action-button-mobile {
    height: 36px;
    font-size: 13px;
  }
}

@media (max-width: 360px) {
  .mobile-header {
    height: 40px;
  }
  
  .header-content {
    padding: 5px 8px;
    height: 40px;
  }
  
  .header-center {
    height: 40px;
  }
  
  .app-title {
    font-size: 13px;
  }
  
  .current-kb {
    font-size: 9px;
    margin: 1px 0 0 0;
  }
  
  .menu-button, .clear-button, .desktop-switch-btn {
    font-size: 15px;
    padding: 4px;
    min-width: 26px;
    height: 26px;
  }
  
  .mobile-footer {
    padding: 6px 10px;
  }
  
  .message-input-mobile :deep(.el-textarea__inner) {
    padding: 8px 12px;
    font-size: 13px;
  }
  
  .action-button-mobile {
    height: 32px;
    font-size: 12px;
  }
}

/* iOS Safari 特殊处理 */
@supports (-webkit-touch-callout: none) {
  .mobile-container {
    height: -webkit-fill-available;
    min-height: -webkit-fill-available;
  }
  
  .mobile-content {
    max-height: calc(-webkit-fill-available - 120px);
  }
  
  .mobile-footer {
    position: sticky;
    bottom: 0;
    background: white;
    z-index: 1000;
  }
}

/* 确保输入框在移动端完全可见 */
@media (max-width: 768px) {
  .mobile-footer {
    position: fixed;
    bottom: 0;
    left: 0;
    right: 0;
    background: white;
    z-index: 1000;
    padding-bottom: calc(12px + env(safe-area-inset-bottom));
    /* 确保输入栏不被键盘遮挡 */
    transform: translateZ(0);
  }
  
  .mobile-content {
    padding-bottom: 140px;
  }
  
  .message-input-mobile :deep(.el-textarea__inner) {
    font-size: 16px; /* 防止iOS缩放 */
  }
  
  /* 确保按钮组正常显示 */
  .action-button-mobile {
    display: block !important;
    visibility: visible !important;
    opacity: 1 !important;
  }
}

/* 超小屏幕设备的特殊处理 */
@media (max-width: 360px) {
  .mobile-footer {
    padding: 8px 12px;
    padding-bottom: calc(8px + env(safe-area-inset-bottom));
  }
  
  .mobile-content {
    padding-bottom: 120px;
  }
  
  .action-button-mobile {
    height: 36px;
    font-size: 13px;
  }
}


</style>
