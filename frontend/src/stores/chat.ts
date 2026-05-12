import { defineStore } from 'pinia'
import { ElMessage } from 'element-plus'

interface Message {
  id: number
  type: 'user' | 'ai'
  content: string
  timestamp: string
  sources?: any[]
}

export const useChatStore = defineStore('chat', {
  state: () => ({
    message: '',
    messageList: [] as Message[],
    loading: false,
    showWelcome: true,
  }),
  actions: {
    clearChat() {
      this.messageList = []
      this.showWelcome = true
    },
    async sendMessage(content: string, knowledgeBaseId: number) {
      if (!knowledgeBaseId) {
        ElMessage.warning('请先选择一个知识库')
        return
      }

      // 添加用户消息
      this.messageList.push({
        id: Date.now(),
        type: 'user',
        content,
        timestamp: new Date().toLocaleTimeString(),
      })
      this.message = ''
      this.loading = true

      try {
        const requestBody = {
          question: content,
          history: this.messageList.map((msg) => ({
            content: msg.content,
            isUser: msg.type === 'user',
          })),
          knowledgeBaseId: String(knowledgeBaseId),
          tenantId: 'admin', // 默认使用 admin 租户
        }

        const startTime = Date.now()
        console.log('[Frontend Debug] 发送请求:', {
          question: content,
          knowledgeBaseId: String(knowledgeBaseId),
          historyLength: this.messageList.length,
          timestamp: new Date().toISOString(),
        })

        const response = await fetch('/api/chat', {
          method: 'POST',
          headers: {
            'Content-Type': 'application/json',
            Authorization: 'Bearer ' + localStorage.getItem('token'),
          },
          body: JSON.stringify(requestBody),
        })

        console.log(
          '[Frontend Debug] 响应状态:',
          response.status,
          '耗时(ms):',
          Date.now() - startTime,
        )
        try {
          // 打印响应头便于诊断 chunked / content-type
          try {
            response.headers.forEach((value: string, key: string) => {
              console.log('[Frontend Debug] 响应头:', key, value)
            })
          } catch (hErr) {
            console.warn('[Frontend Debug] 无法读取响应头', hErr)
          }

          if (!response.ok) {
            const errorText = await response.text()
            console.error('[Frontend Debug] 响应错误文本:', errorText)
            throw new Error('获取AI回复失败: ' + errorText)
          }

          let result: any = null
          try {
            result = await response.json()
            console.log('[Frontend Debug] 解析到 JSON:', result)
          } catch (jsonErr) {
            // 可能后端以流/分块或纯文本返回，记录原始文本以便排查
            console.error('[Frontend Debug] JSON 解析失败:', jsonErr)
            const text = await response.text()
            console.warn('[Frontend Debug] 响应文本 (备用):', text.slice(0, 2000))
            // 如果是纯文本，则当作 answer 使用；否则留 null
            result = { answer: text }
          }

          this.messageList.push({
            id: Date.now() + 1,
            type: 'ai',
            content: result?.answer ?? '[无回复内容]',
            timestamp: new Date().toLocaleTimeString(),
            sources: result?.sources,
          })
        } catch (innerErr) {
          console.error('[Frontend Debug] 处理响应时出错:', innerErr)
          throw innerErr
        }
      } catch (error: any) {
        ElMessage.error('获取AI回复失败: ' + error.message)
      } finally {
        this.loading = false
      }
    },
  },
})
