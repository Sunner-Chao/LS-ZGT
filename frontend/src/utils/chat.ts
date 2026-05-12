/**
 * 聊天相关工具函数
 */

// Markdown转HTML的简单函数
export const markdownToHtml = (text: string): string => {
  return text
    .replace(/\*\*(.*?)\*\*/g, '<strong>$1</strong>')
    .replace(/\*(.*?)\*/g, '<em>$1</em>')
    .replace(/`(.*?)`/g, '<code>$1</code>')
    .replace(/```([\s\S]*?)```/g, '<pre><code>$1</code></pre>')
    .replace(/\n/g, '<br>')
    .replace(/<br><br>/g, '</p><p>')
    .replace(/^[【\[]*回答[】\]]*[：:]\s*/g, '')
    .replace(/^[【\[]*Answer[】\]]*[：:]\s*/gi, '')
}

// 获取知识库ID
export const getKnowledgeBaseId = (currentNode: any): string => {
  if (!currentNode) return 'default_knowledge_base'
  
  // 优先使用id，如果没有则使用path
  if (currentNode.id) {
    return String(currentNode.id)
  }
  
  if (currentNode.path) {
    return currentNode.path
  }
  
  return currentNode.name || 'default_knowledge_base'
}

// 加载文件列表
export const loadFileList = async (currentNode: any): Promise<any[]> => {
  if (!currentNode) return []
  
  // 只处理目录，不处理文件
  if (currentNode.type === 'file') {
    console.log('当前选择的是文件，不加载文件列表')
    return []
  }
  
  try {
    const path = currentNode.path || currentNode.name
    console.log('加载文件列表，路径:', path)
    
    const { getAuthToken } = await import('./auth')
    
    const response = await fetch(`/api/kb/${encodeURIComponent(path)}/files`, {
      headers: {
        'Authorization': 'Bearer ' + getAuthToken()
      }
    })
    
    if (response.ok) {
      const data = await response.json()
      const fileList = data.data || []
      
      // 过滤掉系统文件和隐藏文件
      const filteredFileList = fileList.filter((file: any) => {
        const fileName = file.name || file.filename || ''
        // 过滤掉 .DS_Store、Thumbs.db 等系统文件
        return !fileName.startsWith('.') && 
               !fileName.toLowerCase().includes('thumbs.db') &&
               !fileName.toLowerCase().includes('desktop.ini') &&
               fileName !== '.DS_Store'
      })
      
      console.log('文件列表加载成功:', filteredFileList)
      return filteredFileList
    } else {
      console.error('加载文件列表失败:', response.statusText)
      return []
    }
  } catch (error) {
    console.error('加载文件列表出错:', error)
    return []
  }
}
