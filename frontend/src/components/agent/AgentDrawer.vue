<script setup lang="ts">
import { ref, nextTick, watch } from 'vue'
import SafeHtml from '../SafeHtml.vue'
import { markdownToHtml } from '../../utils/chat'
import { getAuthToken } from '../../utils/auth'
import { SSEParser } from '../../utils/sse'
import { useKnowledgeStore } from '../../stores/knowledgeStore'

interface Message {
  id: string
  role: 'user' | 'ai'
  content: string
  thinkContent?: string
  sources?: any[]
  loading?: boolean
}

const props = defineProps<{
  modelValue: boolean
}>()

const emit = defineEmits<{
  'update:modelValue': [value: boolean]
}>()

const messages = ref<Message[]>([])
const input = ref('')
const loading = ref(false)
const streaming = ref(false) // 流正在处理中
const inputRef = ref<HTMLTextAreaElement | null>(null)
const messagesRef = ref<HTMLElement | null>(null)
const knowledgeStore = useKnowledgeStore()
let aiMsgIndex = -1 // 当前流式回复所在的索引

const SUGGESTIONS = [
  '查询知识库中的文档',
  '帮我总结一下主要内容',
  '有哪些关键要点？',
  '详细解释一下这个概念'
]

const close = () => {
  emit('update:modelValue', false)
}

watch(() => props.modelValue, (val) => {
  if (val) {
    nextTick(() => {
      inputRef.value?.focus()
    })
  }
})

watch(messages, () => {
  nextTick(() => {
    if (messagesRef.value) {
      messagesRef.value.scrollTop = messagesRef.value.scrollHeight
    }
  })
}, { deep: true })

const sendMessage = async () => {
  const text = input.value.trim()
  if (!text || loading.value) return

  input.value = ''
  messages.value.push({
    id: `user_${Date.now()}`,
    role: 'user',
    content: text
  })

  loading.value = true
  streaming.value = true

  // 记录 AI 消息的索引，流式更新时直接定位
  aiMsgIndex = messages.value.length
  messages.value.push({
    id: `ai_${Date.now()}`,
    role: 'ai',
    content: '',
    thinkContent: ''
  })

  try {
    const currentKbId = knowledgeStore.currentNode?.path || knowledgeStore.currentNode?.name || 'default'
    const response = await fetch('/api/chat/stream', {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'Authorization': 'Bearer ' + getAuthToken()
      },
      body: JSON.stringify({
        question: text,
        knowledgeBaseId: currentKbId
      })
    })

    if (!response.ok) throw new Error(`HTTP ${response.status}`)

    const aiMsg = messages.value[aiMsgIndex]
    const parser = new SSEParser({
      onAnswer: (c) => { aiMsg.content += c },
      onSources: (s) => {
        aiMsg.sources = s
        aiMsg.thinkContent = s?.length ? `已找到 ${s.length} 个相关文档...` : '未找到相关文档...'
      },
      onLoading: (c) => { aiMsg.thinkContent = c || '模型思考中...' },
      onThinking: (c) => { aiMsg.thinkContent = (aiMsg.thinkContent || '') + (c || '') },
      onError: (e) => {
        aiMsg.content = '抱歉，服务出了点问题：' + e
        aiMsg.loading = false
      },
      onDone: () => { aiMsg.loading = false }
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
    const aiMsg = messages.value[aiMsgIndex]
    if (aiMsg) {
      aiMsg.content = '抱歉，服务出了点问题，请稍后重试。'
    }
  } finally {
    loading.value = false
    streaming.value = false
    if (messages.value[aiMsgIndex]) {
      messages.value[aiMsgIndex].content = messages.value[aiMsgIndex].content.trimEnd()
    }
    await nextTick()
    if (messagesRef.value) {
      messagesRef.value.scrollTop = messagesRef.value.scrollHeight
    }
    aiMsgIndex = -1
  }
}

const handleKeydown = (e: KeyboardEvent) => {
  if (e.key === 'Enter' && !e.shiftKey) {
    e.preventDefault()
    sendMessage()
  }
}

const handleSuggestion = (text: string) => {
  input.value = text
  inputRef.value?.focus()
}
</script>

<template>
  <Teleport to="body">
    <div v-if="modelValue" class="agent-drawer-overlay" @click.self="close">
      <div class="agent-drawer">
        <!-- Header -->
        <div class="drawer-header">
          <div class="header-left">
            <div class="header-icon">
              <ElIcon><ChatDotRound /></ElIcon>
            </div>
            <div class="header-text">
              <h3>孪数 AI 助手</h3>
              <p>智能知识库问答</p>
            </div>
          </div>
          <button class="close-btn" @click="close">
              <ElIcon><Close /></ElIcon>
            </button>
        </div>

        <!-- Messages -->
        <div ref="messagesRef" class="drawer-messages">
          <div v-if="messages.length === 0" class="empty-state">
            <div class="empty-icon">
              <ElIcon :size="40"><ChatDotRound /></ElIcon>
            </div>
            <p class="empty-title">你好，我是孪数 AI 助手</p>
            <p class="empty-desc">我可以回答关于知识库的各种问题</p>
            <div class="suggestions">
              <button
                v-for="(s, i) in SUGGESTIONS"
                :key="i"
                class="suggestion-chip"
                @click="handleSuggestion(s)"
              >
                {{ s }}
              </button>
            </div>
          </div>

          <div
            v-for="msg in messages"
            :key="msg.id"
            class="message-item"
            :class="msg.role"
          >
            <div class="message-avatar">
              <ElIcon v-if="msg.role === 'user'"><User /></ElIcon>
              <ElIcon v-else><ChatDotRound /></ElIcon>
            </div>
            <div class="message-content">
              <div class="message-bubble">
                <!-- 思考阶段：仅显示思考内容（content为空时） -->
                <div v-if="msg.thinkContent && !msg.content" class="think-indicator">
                  <span class="think-label">思考中...</span>
                  <span class="think-text">{{ msg.thinkContent }}</span>
                </div>
                <!-- 回答内容：独立检查，content有值时即显示（与thinkContent互不阻塞） -->
                <div v-if="msg.content" class="message-html">
                  <SafeHtml :html="markdownToHtml(msg.content)" />
                </div>
                <!-- 加载中：仅在无content且无thinkContent时显示 -->
                <div v-if="!msg.content && loading && streaming" class="message-loading">
                  <span class="loading-dot"></span>
                  <span class="loading-dot"></span>
                  <span class="loading-dot"></span>
                </div>
              </div>
              <div v-if="msg.sources?.length" class="message-sources">
                <div class="sources-title">参考资料</div>
                <div
                  v-for="(src, idx) in msg.sources.slice(0, 3)"
                  :key="idx"
                  class="source-item"
                >
                  <span class="source-name">{{ src.name || src.filename }}</span>
                  <span v-if="src.page" class="source-page">P{{ src.page }}</span>
                </div>
              </div>
            </div>
          </div>
        </div>

        <!-- Input -->
        <div class="drawer-input">
          <textarea
            ref="inputRef"
            v-model="input"
            class="input-area"
            placeholder="输入问题，Enter 发送..."
            rows="1"
            :disabled="loading"
            @keydown="handleKeydown"
          />
          <button
            class="send-btn"
            :disabled="!input.trim() || loading"
            @click="sendMessage"
          >
            <ElIcon><Promotion /></ElIcon>
          </button>
        </div>
      </div>
    </div>
  </Teleport>
</template>

<script lang="ts">
import { ChatDotRound, Close, User, Promotion } from '@element-plus/icons-vue'
export default {
  components: { ChatDotRound, Close, User, Promotion }
}
</script>

<style scoped>
.agent-drawer-overlay {
  position: fixed;
  inset: 0;
  background: rgba(0, 0, 0, 0.4);
  backdrop-filter: blur(4px);
  z-index: 9999;
  display: flex;
  justify-content: flex-end;
  animation: overlay-in 0.6s ease-out;
}

@keyframes overlay-in {
  from { opacity: 0; }
  to { opacity: 1; }
}

.agent-drawer {
  width: 420px;
  max-width: 100%;
  height: 100%;
  background: #ffffff;
  display: flex;
  flex-direction: column;
  animation: drawer-in 0.6s ease-out;
  box-shadow: -4px 0 40px rgba(0, 0, 0, 0.15);
}

@keyframes drawer-in {
  from {
    opacity: 0;
    transform: translateY(30px);
  }
  to {
    opacity: 1;
    transform: translateY(0);
  }
}

.drawer-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 16px 20px;
  border-bottom: 1px solid #e5e7eb;
  flex-shrink: 0;
}

.header-left {
  display: flex;
  align-items: center;
  gap: 12px;
}

.header-icon {
  width: 40px;
  height: 40px;
  border-radius: 12px;
  background: linear-gradient(135deg, #3b82f6, #2563eb);
  display: flex;
  align-items: center;
  justify-content: center;
  color: #ffffff;
  font-size: 20px;
}

.header-text h3 {
  margin: 0;
  font-size: 15px;
  font-weight: 600;
  color: #1f2937;
}

.header-text p {
  margin: 2px 0 0;
  font-size: 11px;
  color: #9ca3af;
}

.close-btn {
  width: 32px;
  height: 32px;
  border: none;
  background: #f3f4f6;
  border-radius: 8px;
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  color: #6b7280;
  transition: all 0.2s;
}

.close-btn:hover {
  background: #fee2e2;
  color: #ef4444;
}

.drawer-messages {
  flex: 1;
  overflow-y: auto;
  padding: 20px;
}

.empty-state {
  text-align: center;
  padding: 40px 0;
}

.empty-icon {
  width: 80px;
  height: 80px;
  border-radius: 20px;
  background: #f3f4f6;
  display: flex;
  align-items: center;
  justify-content: center;
  margin: 0 auto 16px;
  color: #9ca3af;
}

.empty-title {
  font-size: 16px;
  font-weight: 600;
  color: #1f2937;
  margin: 0 0 8px;
}

.empty-desc {
  font-size: 13px;
  color: #9ca3af;
  margin: 0 0 20px;
}

.suggestions {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  justify-content: center;
}

.suggestion-chip {
  padding: 8px 14px;
  background: #f3f4f6;
  border: none;
  border-radius: 20px;
  font-size: 12px;
  color: #4b5563;
  cursor: pointer;
  transition: all 0.2s;
}

.suggestion-chip:hover {
  background: #e5e7eb;
  color: #1f2937;
}

.message-item {
  display: flex;
  gap: 12px;
  margin-bottom: 20px;
  animation: msg-in 0.3s ease;
}

@keyframes msg-in {
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
  font-size: 16px;
}

.message-item.user .message-avatar {
  background: linear-gradient(135deg, #3b82f6, #60a5fa);
  color: #ffffff;
}

.message-item.ai .message-avatar {
  background: linear-gradient(135deg, #3b82f6, #2563eb);
  color: #ffffff;
}

.message-content {
  max-width: 80%;
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

.message-html {
  word-break: break-word;
}

.message-loading {
  display: flex;
  gap: 4px;
  padding: 4px 0;
}

.loading-dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: #9ca3af;
  animation: bounce 1.4s infinite ease-in-out;
}

.loading-dot:nth-child(1) { animation-delay: 0s; }
.loading-dot:nth-child(2) { animation-delay: 0.2s; }
.loading-dot:nth-child(3) { animation-delay: 0.4s; }

@keyframes bounce {
  0%, 80%, 100% { transform: scale(0.8); opacity: 0.5; }
  40% { transform: scale(1.2); opacity: 1; }
}

.think-indicator {
  margin-bottom: 8px;
  padding-bottom: 8px;
  border-bottom: 1px dashed #d1d5db;
}

.think-label {
  font-size: 11px;
  color: #9ca3af;
  display: block;
  margin-bottom: 4px;
}

.think-text {
  font-size: 12px;
  color: #6b7280;
}

.message-sources {
  margin-top: 8px;
  padding: 8px 12px;
  background: #f9fafb;
  border-radius: 8px;
}

.sources-title {
  font-size: 11px;
  font-weight: 600;
  color: #6b7280;
  margin-bottom: 6px;
}

.source-item {
  display: flex;
  justify-content: space-between;
  font-size: 11px;
  padding: 3px 0;
  color: #9ca3af;
}

.drawer-input {
  padding: 16px 20px;
  border-top: 1px solid #e5e7eb;
  display: flex;
  gap: 12px;
  align-items: flex-end;
  flex-shrink: 0;
}

.input-area {
  flex: 1;
  padding: 12px 16px;
  border: 1px solid #e5e7eb;
  border-radius: 12px;
  font-size: 14px;
  resize: none;
  outline: none;
  font-family: inherit;
  max-height: 120px;
  transition: border-color 0.2s;
}

.input-area:focus {
  border-color: #3b82f6;
}

.input-area:disabled {
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
</style>
