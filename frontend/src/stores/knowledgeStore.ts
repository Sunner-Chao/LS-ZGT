import { defineStore } from 'pinia'
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import axios from 'axios'
import { ElMessage } from 'element-plus'

interface ChatMessage {
  type: 'user' | 'ai'
  content: string
  timestamp?: string
  sources?: Array<{
    text: string
    documentName: string
    page: number
    score: number
  }>
}

export const useKnowledgeStore = defineStore('knowledge', () => {
  const router = useRouter()
  const message = ref('')
  const messageList = ref<ChatMessage[]>([])
  const currentNode = ref<any>(null)
  const knowledgeBaseTree = ref<any[]>([])
  const isFetchingTree = ref(false)

  // 后台预加载与心跳状态
  const isBackgroundPrefetchActive = ref(false)
  let backgroundHeartbeatTimer: ReturnType<typeof setInterval> | null = null
  let backgroundFetchController: AbortController | null = null
  const HEARTBEAT_INTERVAL = 3 * 60 * 1000 // 3分钟心跳检测间隔

  const clearChat = () => {
    messageList.value = []
  }

  // 后台预加载知识库树（静默执行，不弹窗）
  const backgroundFetchTree = async () => {
    if (isBackgroundPrefetchActive.value) {
      console.info('[KnowledgeStore] backgroundFetchTree skipped: already running')
      return
    }
    isBackgroundPrefetchActive.value = true
    const maxRetries = 3
    const delay = (ms: number) => new Promise(res => setTimeout(res, ms))

    for (let attempt = 1; attempt <= maxRetries; attempt++) {
      try {
        if (backgroundFetchController) {
          backgroundFetchController.abort()
        }
        backgroundFetchController = new AbortController()

        const response = await axios.get('/api/kb/tree', {
          signal: backgroundFetchController.signal,
          timeout: 30000
        })
        if (response.data.success) {
          knowledgeBaseTree.value = response.data.data || []
          console.info('[KnowledgeStore] backgroundFetch success on attempt', attempt)
          return
        }
      } catch (error: any) {
        if (error.name === 'CanceledError' || error.code === 'ERR_CANCELLED') {
          // 被新请求取消，属正常行为
          isBackgroundPrefetchActive.value = false
          return
        }
        console.warn('[KnowledgeStore] backgroundFetch attempt', attempt, 'error:', error?.message || error)
      }
      if (attempt < maxRetries) {
        await delay(3000)
      }
    }
    console.warn('[KnowledgeStore] backgroundFetch failed after', maxRetries, 'attempts')
    isBackgroundPrefetchActive.value = false
  }

  // 启动后台预加载 + 心跳循环
  const startBackgroundPrefetch = () => {
    // 立即执行一次后台预加载
    backgroundFetchTree()

    // 若已有心跳，先清除
    stopBackgroundPrefetch()

    // 每隔 HEARTBEAT_INTERVAL 分钟静默刷新一次
    backgroundHeartbeatTimer = setInterval(() => {
      console.info('[KnowledgeStore] heartbeat: refreshing knowledge base tree')
      backgroundFetchTree()
    }, HEARTBEAT_INTERVAL)
  }

  // 停止心跳
  const stopBackgroundPrefetch = () => {
    if (backgroundHeartbeatTimer !== null) {
      clearInterval(backgroundHeartbeatTimer)
      backgroundHeartbeatTimer = null
    }
    if (backgroundFetchController) {
      backgroundFetchController.abort()
      backgroundFetchController = null
    }
    isBackgroundPrefetchActive.value = false
  }

  const fetchKnowledgeBaseTree = async () => {
    if (isFetchingTree.value) {
      console.info('[KnowledgeStore] fetchKnowledgeBaseTree skipped: already fetching')
      return
    }
    isFetchingTree.value = true
    const maxRetries = 5
    const delay = (ms: number) => new Promise(res => setTimeout(res, ms))
    let lastError: any = null
    console.info('[KnowledgeStore] fetchKnowledgeBaseTree start')
    try { localStorage.setItem('kb_fetch_state', JSON.stringify({ status: 'started', time: Date.now() })); } catch (e) {}

    // 不使用本地缓存，直接从后端拉取最新知识库树
    // 显示初始状态消息
    let currentMessage: any = null

    try {
      for (let attempt = 1; attempt <= maxRetries; attempt++) {
        // 关闭之前的消息
        if (currentMessage) {
          currentMessage.close()
        }
        // 显示当前尝试状态（指数退避：2s, 4s, 8s, 16s, 32s）
        const backoffMs = 2000 * Math.pow(2, attempt - 1)
        const label = attempt === 1 ? '正在连接知识库服务...' : `正在重试... (${attempt}/${maxRetries})`
        currentMessage = ElMessage({
          message: label,
          type: 'info',
          duration: backoffMs - 500,
          showClose: false
        })

        console.info('[KnowledgeStore] fetch attempt', attempt, 'backoff', backoffMs, 'ms')
        try { localStorage.setItem('kb_fetch_state', JSON.stringify({ status: 'attempt', attempt, time: Date.now() })); } catch (e) {}
        try {
          const response = await axios.get('/api/kb/tree')
          if (response.data.success) {
            knowledgeBaseTree.value = response.data.data || []
            console.info('[KnowledgeStore] fetch success on attempt', attempt)
            try { localStorage.setItem('kb_fetch_state', JSON.stringify({ status: 'success', attempt, time: Date.now() })); } catch (e) {}
            // 关闭状态消息
            if (currentMessage) {
              currentMessage.close()
            }
            ElMessage.success('知识库树加载成功')
            isFetchingTree.value = false
            return
          } else {
            lastError = response.data.message
          }
        } catch (error) {
          lastError = error
          console.warn('[KnowledgeStore] fetch attempt error', attempt, error)
          try { localStorage.setItem('kb_fetch_state', JSON.stringify({ status: 'error', attempt, time: Date.now(), error: String(error) })); } catch (e) {}
        }
        if (attempt < maxRetries) {
          await delay(backoffMs)
        }
      }
      console.error('获取知识库树失败:', lastError)
      try { localStorage.setItem('kb_fetch_state', JSON.stringify({ status: 'failed', attempts: maxRetries, time: Date.now(), error: String(lastError) })); } catch (e) {}
      // 关闭状态消息
      if (currentMessage) {
        currentMessage.close()
      }
      ElMessage.error('知识库树加载失败，请检查后端服务是否启动')
    } finally {
      isFetchingTree.value = false
    }
  }

  const uploadFile = async (formData: FormData) => {
      try {
        // 添加知识库ID到FormData（后端以 knowledgeBaseId 决定磁盘路径与 Milvus collection）
        // 注意：后端知识库树节点通常只有 name/path，没有 id
        if (currentNode.value) {
          const knowledgeBaseId = String(currentNode.value.path || currentNode.value.name || 'default_knowledge_base')
          formData.append('knowledgeBaseId', knowledgeBaseId)
          console.log('[Frontend Debug] 上传文件到知识库:', knowledgeBaseId)
        }

        const response = await axios.post('/api/upload', formData, {
          headers: { 'Content-Type': 'multipart/form-data' }
        })
        ElMessage.success('文件上传成功')

        // 上传成功后刷新知识库树
        console.log('[Frontend Debug] 文件上传成功，刷新知识库树');
        await fetchKnowledgeBaseTree();

        // 触发全局文件上传事件
        window.dispatchEvent(new CustomEvent('fileUploaded'));

        return response.data
      } catch (error) {
        console.error('文件上传失败:', error)
        ElMessage.error('文件上传失败')
        throw error
    }
  }

  const sendMessage = async (content: string) => {
    if (!currentNode.value) {
      ElMessage.warning('请先选择知识库')
      return
    }

    // 添加用户消息
    messageList.value.push({
      type: 'user',
      content,
      timestamp: new Date().toLocaleTimeString()
    })

    // 添加 AI 消息占位
    const aiMessage: ChatMessage = {
      type: 'ai',
      content: '',
      timestamp: new Date().toLocaleTimeString()
    }
    messageList.value.push(aiMessage)

    try {
      const token = localStorage.getItem('token');
      const knowledgeBaseId = String(currentNode.value.path || currentNode.value.name || 'default_knowledge_base')
      const requestBody = {
        question: content,
        knowledgeBaseId
      };

      console.log('[Frontend Debug] 发送聊天请求:', {
        question: content,
        knowledgeBaseId,
        currentNode: currentNode.value
      });

      const response = await fetch('/api/chat/stream', {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          ...(token ? { 'Authorization': 'Bearer ' + token } : {})
        },
        body: JSON.stringify(requestBody)
      })

      const reader = response.body?.getReader()
      const decoder = new TextDecoder()

      if (!reader) {
        throw new Error('无法读取响应流')
      }

      while (true) {
        const { done, value } = await reader.read()
        if (done) break

        const chunk = decoder.decode(value)
        const lines = chunk.split('\n')

        for (const line of lines) {
          if (line.startsWith('data: ')) {
            try {
              const data = JSON.parse(line.slice(6))

              // 处理不同类型的消息
              switch (data.type) {
                case 'sources':
                  // 更新参考来源
                  aiMessage.sources = data.sources
                  break

                case 'answer':
                  // 追加 AI 回答内容
                  aiMessage.content += data.content
                  break

                case 'error':
                  // 显示错误消息
                  ElMessage.error(data.content)
                  aiMessage.content = '抱歉，处理您的问题时遇到了问题。'
                  break

                default:
                  console.warn('未知的消息类型:', data)
              }
            } catch (e) {
              console.error('解析 SSE 消息失败:', e)
            }
          }
        }
      }
      } catch (error) {
      console.error('发送消息失败:', error)
      aiMessage.content = '抱歉，发送消息时遇到了问题。'
      ElMessage.error('发送消息失败')
    }
  }

  const setCurrentNode = (node: any) => {
    currentNode.value = node
  }

  const handleAuthExpired = () => {
    ElMessage.error('登录已过期，请重新登录')
    localStorage.removeItem('token')
    router.push('/login')
  }

  const createKnowledgeBase = async (name: string) => {
    try {
      const response = await axios.post('/api/kb/create_folder', {
        path: name
      })
      if (response.data.success) {
        // 新建成功后清理本地缓存，确保能获取到后端的最新知识库树
        await fetchKnowledgeBaseTree()
        ElMessage.success('知识库创建成功')
      } else {
        ElMessage.error(response.data.message || '创建知识库失败')
      }
    } catch (error: any) {
      console.error('创建知识库失败:', error)
      if (error.response?.status === 401) {
        handleAuthExpired()
      } else {
        ElMessage.error('创建知识库失败: ' + (error.response?.data?.message || error.message))
      }
    }
  }

  const renameItem = async (src: string, dst: string) => {
    try {
      const dstPath = src.replace(/[^/]+$/, dst); // 确保目标路径是基于源路径的正确拼接
      console.log('[Frontend] Sending rename request:', { src, dst, dstPath });
      const response = await axios.post('/api/kb/rename', {
        src,
        dst: dstPath
      })
      console.log('[Frontend] Rename response:', response.data);
      if (response.data.success) {
        await fetchKnowledgeBaseTree()
        ElMessage.success('重命名成功')

        // 如果当前选中的节点是被重命名的节点，需要更新选中状态
        if (currentNode.value && currentNode.value.path === src) {
          // 在刷新后的树中找到新的节点
          const findNewNode = (nodes: any[], oldPath: string, newPath: string): any => {
            for (const node of nodes) {
              if (node.path === newPath) {
                return node
              }
              if (node.children) {
                const found = findNewNode(node.children, oldPath, newPath)
                if (found) return found
              }
            }
            return null
          }

          const newNode = findNewNode(knowledgeBaseTree.value, src, dst)
          if (newNode) {
            currentNode.value = newNode
          } else {
            currentNode.value = null
          }
        }
      } else {
        ElMessage.error(response.data.message || '重命名失败')
      }
    } catch (error: any) {
      console.error('重命名失败:', error)
      if (error.response?.status === 401) {
        handleAuthExpired()
      } else {
        ElMessage.error('重命名失败: ' + (error.response?.data?.message || error.message))
      }
    }
  }

  const deleteItem = async (path: string) => {
    try {
      const response = await axios.post('/api/kb/delete', {
        path
      })
      if (response.data.success) {
        await fetchKnowledgeBaseTree()
        ElMessage.success('删除成功')
      } else {
        ElMessage.error(response.data.message || '删除失败')
      }
    } catch (error: any) {
      console.error('删除失败:', error)
      if (error.response?.status === 401) {
        handleAuthExpired()
      } else {
        ElMessage.error('删除失败: ' + (error.response?.data?.message || error.message))
      }
    }
  }

  const findNodeByPath = (path: string): any => {
    const traverse = (nodes: any[]): any => {
      for (const node of nodes) {
        if (node.path === path) {
          return node;
        }
        if (node.children) {
          const found = traverse(node.children);
          if (found) return found;
        }
      }
      return null;
    };

    return traverse(knowledgeBaseTree.value);
  };

  return {
    message,
    messageList,
    currentNode,
    knowledgeBaseTree,
    clearChat,
    fetchKnowledgeBaseTree,
    uploadFile,
    sendMessage,
    setCurrentNode,
    createKnowledgeBase,
    renameItem,
    deleteItem,
    findNodeByPath,
    startBackgroundPrefetch,
    stopBackgroundPrefetch,
    isBackgroundPrefetchActive
  }
})
