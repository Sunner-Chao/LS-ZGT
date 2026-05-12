<template>
  <div class="knowledge-base-container">
    <!-- 页面头部 -->
    <div class="page-header">
      <div class="header-content">
        <div class="header-left">
          <el-button type="primary" @click="createKnowledgeBase" icon="Plus" size="small">
            新建知识库
          </el-button>
          <el-button @click="refreshAll" icon="Refresh" :loading="refreshing" size="small">
            同步数据
          </el-button>
        </div>
      </div>
    </div>

    <!-- 主要内容区域 -->
    <div class="main-content">
      <el-row :gutter="24">
        <!-- 左侧知识库树 -->
        <el-col :span="8">
          <el-card class="tree-card" shadow="never">
            <template #header>
              <div class="card-header">
                <span class="card-title">
                  <el-icon><FolderOpened /></el-icon>
                  知识库结构
                </span>
                <span class="tree-count">{{ (knowledgeStore.knowledgeBaseTree || []).length }} 个知识库</span>
              </div>
            </template>
            
            <div class="tree-container">
              <el-tree
                :data="knowledgeStore.knowledgeBaseTree"
                :props="{ children: 'children', label: 'name' }"
                @node-click="handleNodeClick"
                class="knowledge-tree"
                :default-expand-all="true"
                :expand-on-click-node="false"
                highlight-current
                :indent="20"
              >
                <template #default="{ data, node }">
                  <div class="tree-node-content" @mouseenter="hoveredNode = data.path" @mouseleave="hoveredNode = null">
                    <div class="node-info">
                      <el-icon class="node-icon" :class="data.type === 'folder' ? 'folder-icon' : 'file-icon'">
                        <FolderOpened v-if="data.type === 'folder'" />
                        <Document v-else />
                      </el-icon>
                      <span class="node-name">{{ data.name }}</span>
                    </div>
                    <div class="node-actions" v-show="hoveredNode === data.path || node.isCurrent">
                      <el-button link size="small" @click.stop="handleRename(data)">
                        <el-icon><Edit /></el-icon>
                      </el-button>
                      <el-button link size="small" @click.stop="handleDelete(data)" style="color: #f56c6c;">
                        <el-icon><Delete /></el-icon>
                      </el-button>
                    </div>
                  </div>
                </template>
              </el-tree>
              
              <div v-if="(knowledgeStore.knowledgeBaseTree || []).length === 0" class="empty-tree">
                <el-icon :size="48" color="#94a3b8" />
                <p>暂无知识库</p>
                <el-button type="primary" size="small" @click="createKnowledgeBase">
                  创建第一个知识库
                </el-button>
              </div>
            </div>
          </el-card>
        </el-col>

        <!-- 右侧内容区域 -->
        <el-col :span="16">
          <div v-if="!knowledgeStore.currentNode" class="empty-content">
            <div class="empty-card">
              <el-icon :size="64" color="#94a3b8" />
              <h3>请选择知识库</h3>
              <p>从左侧选择一个知识库来查看和管理文件</p>
            </div>
          </div>

                     <div v-else class="content-area">
             <!-- 文件预览区域 -->
            <el-card v-if="selectedFile" class="file-preview-card" shadow="never">
              <template #header>
                <div class="card-header">
                  <span class="card-title">
                    <el-icon><Document /></el-icon>
                    文件预览
                  </span>
                  <el-button link size="small" @click="selectedFile = null">
                    <el-icon><Close /></el-icon>
                    关闭
                  </el-button>
                </div>
              </template>
              <div class="preview-content">
                <div class="preview-header">
                  <el-icon class="preview-icon"><Document /></el-icon>
                  <div class="preview-info">
                    <h3 class="preview-name">{{ selectedFile.name }}</h3>
                    <div class="preview-meta">
                      <span class="preview-size">大小: {{ formatFileSize(selectedFile.size) }}</span>
                      <span class="preview-date" v-if="selectedFile.lastModified">修改: {{ formatDate(selectedFile.lastModified) }}</span>
                      <span class="preview-date" v-else-if="selectedFile.uploadTime">上传: {{ formatDate(selectedFile.uploadTime) }}</span>
                    </div>
                    <div class="preview-path-inline">
                      <el-icon class="path-icon"><FolderOpened /></el-icon>
                      <span class="path-text">{{ selectedFile.path }}</span>
                    </div>
                  </div>
                  <div class="preview-actions">
                    <el-button type="primary" size="small" @click="openFilePreview(selectedFile)">
                      <el-icon><View /></el-icon>
                      打开
                    </el-button>
                    <el-button size="small" @click="downloadFile(selectedFile)">
                      <el-icon><Download /></el-icon>
                      下载
                    </el-button>
                    <el-button type="danger" size="small" @click="deleteFile(selectedFile); selectedFile = null">
                      <el-icon><Delete /></el-icon>
                      删除
                    </el-button>
                  </div>
                </div>
                <!-- PDF 预览区域 -->
                <div v-if="isPdfFile(selectedFile)" class="pdf-preview-container">
                  <iframe
                    v-if="previewUrl"
                    :src="previewUrl"
                    class="pdf-iframe"
                    frameborder="0"
                    @load="pdfLoading = false"
                  ></iframe>
                  <div v-if="pdfLoading" class="pdf-loading">
                    <el-icon class="is-loading"><Loading /></el-icon>
                    <span>正在加载预览...</span>
                  </div>
                </div>
                <!-- Markdown 预览区域 -->
                <div v-else-if="isMarkdownFile(selectedFile)" class="markdown-preview-container">
                  <iframe
                    v-if="previewUrl"
                    :src="previewUrl"
                    class="markdown-iframe"
                    frameborder="0"
                    @load="pdfLoading = false"
                  ></iframe>
                  <div v-if="pdfLoading" class="pdf-loading">
                    <el-icon class="is-loading"><Loading /></el-icon>
                    <span>正在加载预览...</span>
                  </div>
                </div>
                <!-- 非 PDF/Markdown 文件提示 -->
                <div v-else class="non-pdf-preview">
                  <el-icon class="non-pdf-icon"><Document /></el-icon>
                  <p class="non-pdf-text">该文件格式不支持在线预览</p>
                  <div class="non-pdf-actions">
                    <el-button type="primary" @click="downloadFile(selectedFile)">
                      <el-icon><Download /></el-icon>
                      下载文件
                    </el-button>
                    <el-button @click="openFilePreview(selectedFile)">
                      <el-icon><View /></el-icon>
                      在新窗口打开
                    </el-button>
                  </div>
                </div>
              </div>
            </el-card>

            <!-- 文件上传区域 -->
            <el-card v-if="knowledgeStore.currentNode?.type === 'folder' && !selectedFile" class="upload-card" shadow="never">
              <template #header>
                <div class="card-header">
                  <span class="card-title">
                    <el-icon><Upload /></el-icon>
                    文件上传
                  </span>
                </div>
              </template>
              
              <el-upload
                class="upload-area"
                action="/api/upload"
                name="files"
                :headers="{ 'Authorization': 'Bearer ' + getAuthToken() }"
                :data="{ 
                  category: knowledgeStore.currentNode?.path || '',
                  knowledgeBaseId: knowledgeStore.currentNode?.path || ''
                }"
                :before-upload="beforeUpload"
                :on-success="handleUploadSuccess"
                :on-error="handleUploadError"
                :show-file-list="false"
                multiple
                drag
              >
                <div class="upload-content">
                  <el-icon class="upload-icon"><Upload /></el-icon>
                  <div class="upload-text">
                    <h4>拖拽文件到此处或点击上传</h4>
                    <p>支持 PDF、Word、TXT 等格式文件</p>
                  </div>
                  <el-button type="primary" icon="Upload" size="large">选择文件</el-button>
                </div>
              </el-upload>
            </el-card>

            <!-- 文件列表 -->
            <el-card v-if="!selectedFile" class="file-list-card" shadow="never">
              <template #header>
                <div class="card-header">
                  <span class="card-title">
                    <el-icon><Document /></el-icon>
                    文件列表 ({{ fileList.length }})
                  </span>
                  <div class="header-actions">
                    <el-button @click="loadFileListLocal" icon="Refresh" size="small" plain>刷新</el-button>
                  </div>
                </div>
              </template>

              <div v-if="fileList.length === 0" class="empty-file-list">
                <el-icon :size="48" color="#94a3b8" />
                <p>暂无文件</p>
                <el-button type="primary" size="small" @click="loadFileListLocal">
                  <el-icon><Refresh /></el-icon>
                  刷新文件列表
                </el-button>
              </div>

              <div v-else class="file-grid">
                <div
                  v-for="file in fileList"
                  :key="file.id"
                  class="file-card"
                  :class="{ 'selected': selectedFile?.id === file.id }"
                  @click="selectFile(file)"
                >
                  <div class="file-card-header">
                    <div class="file-card-left">
                      <el-icon class="file-card-icon"><Document /></el-icon>
                    </div>
                    <div class="file-card-actions">
                      <el-button 
                        v-if="!isBatchMode"
                        type="danger" 
                        icon="Delete" 
                        circle 
                        size="small" 
                        @click="deleteFile(file)" 
                      />
                    </div>
                  </div>
                  <div class="file-card-content">
                    <div class="file-card-name" :title="file.name">{{ file.name }}</div>
                    <div class="file-card-meta">
                      <span class="file-card-size">大小: {{ formatFileSize(file.size) }}</span>
                      <span class="file-card-date" v-if="file.lastModified">修改: {{ formatDate(file.lastModified) }}</span>
                      <span class="file-card-date" v-else-if="file.uploadTime">上传: {{ formatDate(file.uploadTime) }}</span>
                    </div>
                  </div>
                </div>
              </div>
            </el-card>
          </div>
        </el-col>
      </el-row>
    </div>

    <!-- 上传进度悬浮窗口 -->
    <Transition name="float-slide">
      <div v-if="isUploading && !uploadMinimized" class="upload-float-window">
        <div class="float-header">
          <div class="float-title">
            <el-icon class="float-icon"><Upload /></el-icon>
            <span>文件上传中</span>
          </div>
          <div class="float-actions">
            <el-button link size="small" @click="uploadMinimized = true" icon="Minus">
              最小化
            </el-button>
          </div>
        </div>
        <div class="float-content">
          <div class="upload-progress-bar">
            <el-progress
              :percentage="Math.min(Math.max(uploadProgress, 0), 100)"
              :show-text="false"
              :stroke-width="6"
              color="#3b82f6"/>
            <div class="upload-progress-percentage">{{ Math.min(Math.max(uploadProgress, 0), 100) }}%</div>
          </div>
          <div class="upload-progress-desc">正在上传文件，请稍候...</div>
        </div>
      </div>
    </Transition>

    <!-- 最小化后的悬浮按钮 -->
    <Transition name="fade">
      <div v-if="isUploading && uploadMinimized" class="upload-float-btn" @click="uploadMinimized = false">
        <el-icon class="float-btn-icon"><Upload /></el-icon>
        <span class="float-btn-text">上传中 {{ Math.min(Math.max(uploadProgress, 0), 100) }}%</span>
        <div class="float-btn-progress">
          <div class="float-btn-progress-fill" :style="{ width: Math.min(Math.max(uploadProgress, 0), 100) + '%' }"></div>
        </div>
      </div>
    </Transition>

  </div>
</template>

<script setup lang="ts">
import { ref, onMounted, computed } from 'vue'
import { useKnowledgeStore } from '../stores/knowledgeStore'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  FolderOpened,
  Document,
  Plus,
  Refresh,
  Upload,
  Edit,
  Delete,
  Setting,
  Minus,
  Select,
  Close,
  View,
  Download,
  Loading
} from '@element-plus/icons-vue'
import { useRouter } from 'vue-router'
import { getAuthToken } from '../utils/auth'
import { loadFileList } from '../utils/chat'

const knowledgeStore = useKnowledgeStore()
const router = useRouter()

// 状态管理
const fileList = ref<any[]>([])
const uploadProgress = ref(0)
const isUploading = ref(false)
const uploadMinimized = ref(false)
const refreshing = ref(false)
const hoveredNode = ref<string | null>(null)
const selectedFile = ref<any>(null)
const previewUrl = ref<string>('')
const pdfLoading = ref(false)
const isBatchMode = ref(false)


// 处理节点点击
const handleNodeClick = (data: any) => {
  knowledgeStore.setCurrentNode(data)

  // 如果点击的是文件，则预览文件
  if (data.type === 'file') {
    selectedFile.value = data
    // 如果是 PDF 文件，加载预览 URL
    if (isPdfFile(data)) {
      pdfLoading.value = true
      previewUrl.value = getFilePreviewUrl(data)
    } else if (isMarkdownFile(data)) {
      pdfLoading.value = true
      previewUrl.value = getMarkdownPreviewUrl(data)
    } else {
      previewUrl.value = ''
      pdfLoading.value = false
    }
  } else {
    // 文件夹则加载文件列表
    selectedFile.value = null
    previewUrl.value = ''
    pdfLoading.value = false
    loadFileListLocal()
  }
}

// 选择文件进行预览
const selectFile = (file: any) => {
  selectedFile.value = file
  // 如果是 PDF 文件，加载预览 URL
  if (isPdfFile(file)) {
    pdfLoading.value = true
    previewUrl.value = getFilePreviewUrl(file)
  } else if (isMarkdownFile(file)) {
    // Markdown 文件使用专门的渲染端点
    pdfLoading.value = true
    previewUrl.value = getMarkdownPreviewUrl(file)
  } else {
    previewUrl.value = ''
    pdfLoading.value = false
  }
}

// 加载文件列表
const loadFileListLocal = async () => {
  if (!knowledgeStore.currentNode) return
  
  const fileListResult = await loadFileList(knowledgeStore.currentNode)
  fileList.value = fileListResult
}

// 创建知识库
const createKnowledgeBase = async () => {
  try {
    const name = await ElMessageBox.prompt('请输入知识库名称', '创建知识库', {
      confirmButtonText: '确定',
      cancelButtonText: '取消',
      inputPattern: /\S+/,
      inputErrorMessage: '知识库名称不能为空'
    })
    
    if (name.value) {
      await knowledgeStore.createKnowledgeBase(name.value)
      ElMessage.success('知识库创建成功')
      // 刷新文件列表
      loadFileListLocal()
    }
  } catch (error) {
    console.error('创建知识库失败:', error)
  }
}

// 重命名
const handleRename = async (node: any) => {
  try {
    const { value: newName } = await ElMessageBox.prompt('请输入新名称', '重命名', {
      confirmButtonText: '确定',
      cancelButtonText: '取消',
      inputValue: node.name,
      inputPattern: /\S+/,
      inputErrorMessage: '名称不能为空'
    })
    
    if (newName && newName !== node.name) {
      await knowledgeStore.renameItem(node.path, newName)

      // 主动刷新知识库树，确保能马上看到重命名后的节点
      try {
        await knowledgeStore.fetchKnowledgeBaseTree()
      } catch (e) {
        console.error('刷新知识库树失败:', e)
      }

      // 检查并设置新的当前节点（优先使用 path，如果找不到再使用 name）
      const newPath = node.path.replace(node.name, newName)
      let updatedNode = knowledgeStore.findNodeByPath(newPath)
      if (!updatedNode) {
        // 尝试按名称查找（兼容缺少 path 的情况）
        const findByName = (nodes: any[]): any => {
          for (const n of nodes) {
            if (n.name === newName) return n
            if (n.children) {
              const f = findByName(n.children)
              if (f) return f
            }
          }
          return null
        }
        updatedNode = findByName(knowledgeStore.knowledgeBaseTree)
      }

      if (updatedNode) {
        knowledgeStore.setCurrentNode(updatedNode)
      } else if (knowledgeStore.currentNode?.path === node.path) {
        // 如果找不到，就清空当前节点，避免指向已不存在的老路径
        knowledgeStore.setCurrentNode(null)
      }

      // 重新加载文件列表
      if (knowledgeStore.currentNode && knowledgeStore.currentNode.type === 'folder') {
        await loadFileListLocal()
      }
    }
  } catch (error) {
    console.error('重命名失败:', error)
  }
}

// 删除
const handleDelete = async (node: any) => {
  try {
    await ElMessageBox.confirm(`确定要删除 "${node.name}" 吗？`, '确认删除', {
      confirmButtonText: '确定',
      cancelButtonText: '取消',
      type: 'warning'
    })

    await knowledgeStore.deleteItem(node.path)

    // 检查当前节点是否是被删除的节点
    if (knowledgeStore.currentNode?.path === node.path) {
      knowledgeStore.setCurrentNode(null) // 清空当前节点
    }

    // 刷新文件列表
    loadFileListLocal()
  } catch (error) {
    console.error('删除失败:', error)
  }
}

// 刷新所有数据
const refreshAll = async () => {
  try {
    refreshing.value = true
    // 显示同步提示
    ElMessage.info('正在同步向量数据库，请稍候...')
    
    // 保存当前选中的节点
    const currentNode = knowledgeStore.currentNode;
    
    // 1. 同步向量数据库
    const syncResponse = await fetch('/api/sync_vector_db', {
      method: 'POST',
      headers: {
        'Authorization': 'Bearer ' + getAuthToken()
      }
    })
    
    if (syncResponse.ok) {
      const syncResult = await syncResponse.json()
      if (syncResult.success) {
        ElMessage.success('向量数据库同步完成')
        console.log('同步结果:', syncResult.data)
      } else {
        ElMessage.warning('向量数据库同步失败: ' + syncResult.message)
      }
    } else {
      ElMessage.warning('向量数据库同步请求失败')
    }
    
    // 2. 刷新知识库树
    await knowledgeStore.fetchKnowledgeBaseTree()
    
    // 恢复当前选中的节点
    if (currentNode) {
      // 在刷新后的树中找到对应的节点
      const findNode = (nodes: any[], targetPath: string): any => {
        for (const node of nodes) {
          if (node.path === targetPath || node.name === currentNode.name) {
            return node;
          }
          if (node.children) {
            const found = findNode(node.children, targetPath);
            if (found) return found;
          }
        }
        return null;
      };
      
      const restoredNode = findNode(knowledgeStore.knowledgeBaseTree, currentNode.path || currentNode.name);
      if (restoredNode) {
        knowledgeStore.currentNode = restoredNode;
        console.log('[App] 恢复选中节点:', restoredNode);
      }
    }
    
    // 3. 刷新文件列表
    loadFileListLocal()
    
  } catch (error) {
    console.error('刷新失败:', error)
    ElMessage.error('刷新失败: ' + (error as any).message)
  } finally {
    refreshing.value = false
  }
}



// 格式化文件大小
const formatFileSize = (size: number | undefined | null) => {
  if (size === undefined || size === null || isNaN(size)) return '未知大小'
  if (size < 1024) return `${size} B`
  if (size < 1024 * 1024) return `${(size / 1024).toFixed(2)} KB`
  if (size < 1024 * 1024 * 1024) return `${(size / (1024 * 1024)).toFixed(2)} MB`
  return `${(size / (1024 * 1024 * 1024)).toFixed(2)} GB`
}

// 格式化日期
const formatDate = (date: string | Date) => {
  if (!date) return ''
  const d = new Date(date)
  return d.toLocaleDateString('zh-CN')
}

// 打开文件预览
const openFilePreview = (file: any) => {
  // 如果是 PDF 文件，在新窗口打开预览
  if (isPdfFile(file)) {
    const url = getFilePreviewUrl(file)
    if (url) {
      window.open(url, '_blank')
    } else {
      ElMessage.warning('无法生成预览链接，请尝试下载文件')
    }
  } else if (isMarkdownFile(file)) {
    // Markdown 文件在新窗口打开渲染后的预览
    const url = getMarkdownPreviewUrl(file)
    if (url) {
      window.open(url, '_blank')
    } else {
      ElMessage.warning('无法生成预览链接，请尝试下载文件')
    }
  } else {
    // 其他文件直接下载
    downloadFile(file)
  }
}

// 判断是否为 PDF 文件
const isPdfFile = (file: any) => {
  if (!file) return false
  const name = file.name || file.path || ''
  return name.toLowerCase().endsWith('.pdf')
}

// 判断是否为 Markdown 文件
const isMarkdownFile = (file: any) => {
  if (!file) return false
  const name = file.name || file.path || ''
  return name.toLowerCase().endsWith('.md') || name.toLowerCase().endsWith('.markdown')
}

// 获取 Markdown 预览 URL
const getMarkdownPreviewUrl = (file: any) => {
  if (!file) return ''
  const token = getAuthToken()
  if (!token) return ''
  const normalizedPath = (file.path || '').replace(/\\/g, '/')
  if (normalizedPath) {
    return `/api/kb/markdown_preview?path=${encodeURIComponent(normalizedPath)}&token=${encodeURIComponent(token)}`
  }
  if (file.id) {
    return `/api/kb/files/${file.id}/markdown_preview?token=${encodeURIComponent(token)}`
  }
  return ''
}

// 获取文件预览 URL
const getFilePreviewUrl = (file: any) => {
  if (!file) return ''
  // 使用文件 ID 或路径构建预览 URL
  // 注意：iframe 无法使用 Authorization header，所以后端需要支持 token 查询参数
  const token = getAuthToken()
  if (!token) return ''
  // 统一将反斜杠替换为正斜杠，避免后端路径解析问题
  const normalizedPath = (file.path || '').replace(/\\/g, '/')
  if (normalizedPath) {
    return `/api/kb/preview?path=${encodeURIComponent(normalizedPath)}&token=${encodeURIComponent(token)}`
  }
  if (file.id) {
    return `/api/kb/files/${file.id}/preview?token=${encodeURIComponent(token)}`
  }
  return ''
}

// 下载文件
const downloadFile = (file: any) => {
  if (!file) return

  const token = getAuthToken()
  if (!token) {
    ElMessage.warning('请先登录')
    return
  }

  // 使用 fetch + blob 方式下载，支持 Authorization header
  let downloadPath = ''
  if (file.path) {
    // 统一将反斜杠替换为正斜杠，避免后端路径解析问题
    downloadPath = file.path.replace(/\\/g, '/')
  }

  if (downloadPath) {
    fetch(`/api/kb/download?path=${encodeURIComponent(downloadPath)}`, {
      headers: { 'Authorization': 'Bearer ' + token }
    })
      .then(response => {
        if (!response.ok) throw new Error('下载失败: HTTP ' + response.status)
        const disposition = response.headers.get('Content-Disposition')
        let filename = file.name || 'download'
        if (disposition) {
          const match = disposition.match(/filename[^;=\n]*=((['"]).*?\2|[^;\n]*)/)
          if (match && match[1]) {
            filename = match[1].replace(/['"]/g, '')
          }
        }
        return response.blob().then(blob => ({ blob, filename }))
      })
      .then(({ blob, filename }) => {
        const url = URL.createObjectURL(blob)
        const link = document.createElement('a')
        link.href = url
        link.download = filename
        document.body.appendChild(link)
        link.click()
        document.body.removeChild(link)
        URL.revokeObjectURL(url)
      })
      .catch(error => {
        console.error('下载文件失败:', error)
        ElMessage.error('下载文件失败: ' + error.message)
      })
  } else if (file.id) {
    // 回退到 id 方式
    fetch(`/api/kb/files/${file.id}/download`, {
      headers: { 'Authorization': 'Bearer ' + token }
    })
      .then(response => {
        if (!response.ok) throw new Error('下载失败: HTTP ' + response.status)
        const disposition = response.headers.get('Content-Disposition')
        let filename = file.name || 'download'
        if (disposition) {
          const match = disposition.match(/filename[^;=\n]*=((['"]).*?\2|[^;\n]*)/)
          if (match && match[1]) {
            filename = match[1].replace(/['"]/g, '')
          }
        }
        return response.blob().then(blob => ({ blob, filename }))
      })
      .then(({ blob, filename }) => {
        const url = URL.createObjectURL(blob)
        const link = document.createElement('a')
        link.href = url
        link.download = filename
        document.body.appendChild(link)
        link.click()
        document.body.removeChild(link)
        URL.revokeObjectURL(url)
      })
      .catch(error => {
        console.error('下载文件失败:', error)
        ElMessage.error('下载文件失败: ' + error.message)
      })
  } else {
    ElMessage.error('无法下载文件：缺少文件路径或ID')
  }
}


// 删除文件
const deleteFile = async (file: any) => {
  try {
    await ElMessageBox.confirm(`确定要删除文件 "${file.name}" 吗？`, '确认删除', {
      confirmButtonText: '确定',
      cancelButtonText: '取消',
      type: 'warning'
    })

    const token = getAuthToken()
    if (!token) {
      return
    }

    // 优先使用 path 进行删除，因为 id (hashCode) 可能不可靠
    if (file.path) {
      const response = await fetch('/api/kb/delete', {
        method: 'POST',
        headers: {
          'Authorization': 'Bearer ' + token,
          'Content-Type': 'application/json'
        },
        body: JSON.stringify({ path: file.path })
      })

      if (response.ok) {
        const result = await response.json()
        if (result.success) {
          ElMessage.success('文件删除成功')
          // 刷新知识库树和文件列表
          refreshAll()
        } else {
          ElMessage.error('文件删除失败: ' + (result.message || '未知错误'))
        }
      } else if (response.status === 401) {
        ElMessage.error('登录已过期，请重新登录')
        router.push('/login')
      } else {
        ElMessage.error('文件删除失败: HTTP ' + response.status)
      }
    } else if (file.id) {
      // 回退到 id 方式
      const response = await fetch(`/api/kb/files/${file.id}`, {
        method: 'DELETE',
        headers: {
          'Authorization': 'Bearer ' + token
        }
      })

      if (response.ok) {
        const result = await response.json()
        if (result.success) {
          ElMessage.success('文件删除成功')
          // 刷新知识库树和文件列表
          refreshAll()
        } else {
          ElMessage.error('文件删除失败: ' + (result.message || '未知错误'))
        }
      } else if (response.status === 401) {
        ElMessage.error('登录已过期，请重新登录')
        router.push('/login')
      } else {
        ElMessage.error('文件删除失败: HTTP ' + response.status)
      }
    } else {
      ElMessage.error('无法删除文件：缺少文件路径或ID')
    }
  } catch (error) {
    console.error('删除文件失败:', error)
    ElMessage.error('删除文件失败: ' + (error as any).message)
  }
}

// 上传前检查
const beforeUpload = (file: File) => {
  if (!knowledgeStore.currentNode) {
    ElMessage.warning('请先选择知识库')
    return false
  }
  
  // 开始上传进度
  isUploading.value = true
  uploadProgress.value = 0
  
  // 模拟上传进度
  const progressInterval = setInterval(() => {
    if (uploadProgress.value < 90) {
      const increment = Math.random() * 10 + 5
      // 确保进度不超过100
      uploadProgress.value = Math.min(uploadProgress.value + increment, 100)
    }
  }, 200)
  
  // 保存interval ID，在成功或失败时清除
  ;(file as any).progressInterval = progressInterval
  
  return true
}

// 上传成功
const handleUploadSuccess = async (response: any, file: any) => {
  console.log('文件上传成功:', response)
  
  // 清除进度定时器
  if ((file as any).progressInterval) {
    clearInterval((file as any).progressInterval)
  }
  
  // 完成上传进度，确保不超过100
  uploadProgress.value = Math.min(uploadProgress.value, 100)
  
  // 延迟关闭进度对话框，让用户看到100%
  setTimeout(() => {
    isUploading.value = false
    uploadMinimized.value = false
    uploadProgress.value = 0
    ElMessage.success('文件上传成功')
    
    // 上传成功后只刷新知识库树/文件列表；不要自动触发全量向量库同步（会造成冗余甚至重复入库）
    ;(async () => {
      try {
        await knowledgeStore.fetchKnowledgeBaseTree()
        await loadFileListLocal()
      } catch (e) {
        console.error('上传后刷新失败:', e)
      }
    })()
  }, 500)
}

// 上传失败
const handleUploadError = (error: any, file: any) => {
  console.error('文件上传失败:', error)
  
  // 清除进度定时器
  if ((file as any).progressInterval) {
    clearInterval((file as any).progressInterval)
  }
  
  // 关闭上传进度，确保进度值合法
  isUploading.value = false
    uploadMinimized.value = false
  uploadProgress.value = Math.min(Math.max(uploadProgress.value, 0), 100)
  
  ElMessage.error('文件上传失败')
}


onMounted(() => {
  // 知识库树已由登录后后台预加载，此处仅在本地数据为空时触发一次快速加载
  if (!knowledgeStore.knowledgeBaseTree || knowledgeStore.knowledgeBaseTree.length === 0) {
    knowledgeStore.fetchKnowledgeBaseTree()
  }
})
</script>

<style scoped>
/* ========================================
   知识库管理页面 - 精美设计
   ======================================== */

.knowledge-base-container {
  min-height: 100vh !important;
  display: flex;
  flex-direction: column;
  background: #ffffff;
  overflow: visible !important;
  position: relative !important;
}

/* 页面头部 - 简洁设计 */
.page-header {
  flex-shrink: 0;
  background: #ffffff;
  border-bottom: 1px solid #e5e7eb;
  padding: 0;
  position: relative;
}

.page-header::before {
  display: none;
}

.page-header::after {
  display: none;
}

.header-content {
  display: flex;
  justify-content: flex-end;
  align-items: center;
  max-width: 1400px;
  margin: 0 auto;
  padding: 12px 24px;
  position: relative;
  z-index: 1;
}

.header-left {
  display: flex;
  align-items: center;
  gap: 8px;
}

.back-btn {
  display: none;
}

.back-btn:hover {
  background: rgba(59, 130, 246, 0.1) !important;
  border-color: rgba(59, 130, 246, 0.25) !important;
  transform: translateX(-2px);
}

.header-left h1 {
  margin: 0;
  color: #1f2937;
  font-size: 1.75rem;
  font-weight: 700;
  letter-spacing: -0.02em;
}

.header-actions {
  display: flex;
  gap: 0.75rem;
}

.header-actions .el-button {
  border-radius: var(--radius-lg) !important;
  font-weight: 600 !important;
  transition: all 0.25s ease !important;
}

.header-actions .el-button--primary {
  background: linear-gradient(135deg, #3b82f6 0%, #2563eb 100%) !important;
  border: none !important;
  box-shadow: 0 4px 12px rgba(59, 130, 246, 0.3) !important;
}

.header-actions .el-button--primary:hover {
  transform: translateY(-2px);
  box-shadow: 0 6px 16px rgba(59, 130, 246, 0.4) !important;
}

.header-actions .el-button:not(.el-button--primary) {
  background: rgba(255, 255, 255, 0.08) !important;
  border: 1px solid rgba(255, 255, 255, 0.15) !important;
  color: white !important;
}

.header-actions .el-button:not(.el-button--primary):hover {
  background: rgba(255, 255, 255, 0.15) !important;
  border-color: rgba(255, 255, 255, 0.25) !important;
}



/* 主内容区域 */
.main-content {
  flex: 1;
  overflow-y: visible !important;
  padding: 2rem;
  max-width: 1400px;
  margin: 0 auto;
  width: 100%;
  min-height: auto !important;
  height: auto !important;
}

/* 树形卡片 - 浅色主题设计 */
.tree-card {
  height: fit-content;
  border: 1px solid #e5e7eb !important;
  background: #ffffff !important;
  border-radius: var(--radius-2xl) !important;
  box-shadow: 0 1px 8px rgba(0, 0, 0, 0.08) !important;
  overflow: hidden;
}

.tree-card :deep(.el-card__header) {
  background: #f9fafb !important;
  border-bottom: 1px solid #e5e7eb !important;
  padding: 1rem 1.25rem !important;
}

.card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.card-title {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  font-weight: 600;
  font-size: 0.95rem;
  color: #1f2937;
}

.card-title .el-icon {
  font-size: 1.1rem;
  color: var(--primary-color);
}

.tree-count {
  font-size: 0.75rem;
  color: var(--primary-color);
  background: rgba(59, 130, 246, 0.1);
  padding: 0.25rem 0.75rem;
  border-radius: var(--radius-full);
  font-weight: 600;
}

.tree-container {
  min-height: 400px;
  max-height: 600px;
  overflow-y: auto;
  padding: 0.5rem;
}

/* 树形组件美化 */
.knowledge-tree {
  border: none;
  width: 100%;
}

.knowledge-tree :deep(.el-tree-node__content) {
  padding: 0.625rem 0.5rem;
  height: auto;
  min-height: 44px;
  border-radius: var(--radius-lg);
  margin: 0.25rem 0;
  transition: all 0.2s ease;
}

.knowledge-tree :deep(.el-tree-node__content:hover) {
  background: rgba(59, 130, 246, 0.06);
}

.knowledge-tree :deep(.is-current > .el-tree-node__content) {
  background: linear-gradient(135deg, rgba(59, 130, 246, 0.12) 0%, rgba(37, 99, 235, 0.08) 100%);
  color: var(--primary-color);
  font-weight: 600;
  box-shadow: inset 3px 0 0 var(--primary-color);
}

/* 树形展开/收起箭头优化 */
.knowledge-tree :deep(.el-tree-node__expand-icon) {
  font-size: 14px;
  color: #94a3b8;
  transition: transform 0.25s cubic-bezier(0.4, 0, 0.2, 1), color 0.2s ease;
  cursor: pointer;
  padding: 6px;
  border-radius: 6px;
}

.knowledge-tree :deep(.el-tree-node__expand-icon:hover) {
  color: #3b82f6;
  background: rgba(59, 130, 246, 0.08);
}

.knowledge-tree :deep(.el-tree-node__expand-icon.expanded) {
  color: #3b82f6;
}

.knowledge-tree :deep(.el-tree-node__expand-icon.is-leaf) {
  color: transparent;
  cursor: default;
}

.tree-node-content {
  display: flex;
  align-items: center;
  justify-content: space-between;
  width: 100%;
  min-height: 32px;
  padding-right: 0.5rem;
  box-sizing: border-box;
}

.node-info {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  flex: 1;
  min-width: 0;
}

.node-icon {
  font-size: 1.1rem;
  transition: transform 0.2s ease;
}

.tree-node-content:hover .node-icon {
  transform: scale(1.1);
}

.folder-icon {
  color: #3b82f6;
}

.file-icon {
  color: #10b981;
}

.node-name {
  font-weight: 500;
  color: #374151;
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: 0.9rem;
}

.node-actions {
  display: flex;
  gap: 2px;
  flex-shrink: 0;
  opacity: 0;
  transition: opacity 0.15s ease;
}

.tree-node-content:hover .node-actions {
  opacity: 1;
}

.node-actions .el-button {
  padding: 4px !important;
  border-radius: 6px !important;
}

.node-actions .el-button:hover {
  background: rgba(59, 130, 246, 0.08) !important;
}

/* 空状态 */
.empty-tree {
  text-align: center;
  padding: 3rem 1.5rem;
  color: #94a3b8;
}

.empty-tree p {
  margin: 1rem 0 1.25rem;
  font-size: 0.9rem;
}

.empty-content {
  display: flex;
  align-items: center;
  justify-content: center;
  min-height: 400px;
}

.empty-card {
  text-align: center;
  padding: 4rem 3rem;
  background: #ffffff;
  border-radius: var(--radius-2xl);
  box-shadow: 0 1px 8px rgba(0, 0, 0, 0.08);
  border: 1px solid #e5e7eb;
}

.empty-card h3 {
  margin: 1.25rem 0 0.5rem;
  color: #1f2937;
  font-size: 1.25rem;
  font-weight: 600;
}

.empty-card p {
  color: #6b7280;
  margin: 0;
  font-size: 0.9rem;
}

/* 内容区域 */
.content-area {
  display: flex;
  flex-direction: column;
  gap: 1.25rem;
  min-height: auto !important;
  height: auto !important;
  overflow: visible !important;
}

/* 文件预览卡片 */
.file-preview-card {
  border: 1px solid #e5e7eb !important;
  background: #ffffff !important;
  border-radius: var(--radius-2xl) !important;
  box-shadow: 0 1px 8px rgba(0, 0, 0, 0.08) !important;
  overflow: hidden;
}

.file-preview-card :deep(.el-card__header) {
  background: #f9fafb !important;
  border-bottom: 1px solid #e5e7eb !important;
  padding: 1rem 1.25rem !important;
}

.preview-content {
  padding: 1.5rem;
}

.preview-header {
  display: flex;
  align-items: flex-start;
  gap: 1.25rem;
  margin-bottom: 1.5rem;
  justify-content: space-between;
}

.preview-icon {
  font-size: 3rem;
  color: #3b82f6;
  flex-shrink: 0;
}

.preview-info {
  flex: 1;
  min-width: 0;
}

.preview-name {
  margin: 0 0 0.5rem 0;
  color: #1f2937;
  font-size: 1.25rem;
  font-weight: 600;
  word-break: break-all;
}

.preview-meta {
  display: flex;
  gap: 0.75rem;
  flex-wrap: wrap;
  align-items: center;
}

.preview-size,
.preview-date {
  font-size: 0.8rem;
  color: #333333;
  background: #f3f4f6;
  padding: 0.2rem 0.6rem;
  border-radius: 4px;
}

.preview-path-inline {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  margin-top: 0.5rem;
  padding: 0.4rem 0.75rem;
  background: #f9fafb;
  border-radius: 6px;
  border: 1px solid #e5e7eb;
}

.preview-path-inline .path-icon {
  font-size: 1rem;
  color: #94a3b8;
  flex-shrink: 0;
}

.preview-path-inline .path-text {
  font-size: 0.8rem;
  color: #6b7280;
  word-break: break-all;
}

/* PDF 预览容器 */
.pdf-preview-container {
  margin-top: 1.5rem;
  border: 1px solid #e5e7eb;
  border-radius: 12px;
  overflow: hidden;
  background: #f9fafb;
}

.pdf-iframe {
  width: 100%;
  height: 600px;
  border: none;
  display: block;
}

/* Markdown 预览容器（与 PDF 预览共用样式） */
.markdown-preview-container {
  margin-top: 1.5rem;
  border: 1px solid #e5e7eb;
  border-radius: 12px;
  overflow: hidden;
  background: #fff;
}

.markdown-iframe {
  width: 100%;
  height: 600px;
  border: none;
  display: block;
}

.pdf-loading {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  height: 400px;
  gap: 1rem;
  color: #6b7280;
}

.pdf-loading .el-icon {
  font-size: 2rem;
  color: #3b82f6;
}

/* 非 PDF/Markdown 文件预览区域 */
.non-pdf-preview {
  margin-top: 1.5rem;
  padding: 3rem 2rem;
  text-align: center;
  border: 1px solid #e5e7eb;
  border-radius: 12px;
  background: #f9fafb;
}

.non-pdf-icon {
  font-size: 4rem;
  color: #94a3b8;
  margin-bottom: 1rem;
}

.non-pdf-text {
  font-size: 1rem;
  color: #6b7280;
  margin-bottom: 1.5rem;
}

.non-pdf-actions {
  display: flex;
  justify-content: center;
  gap: 0.75rem;
}

/* 预览操作按钮组 */
.preview-actions {
  display: flex;
  gap: 0.5rem;
  flex-shrink: 0;
}

/* 上传卡片 - 浅色主题 */
.upload-card {
  border: 1px solid #e5e7eb !important;
  background: #ffffff !important;
  border-radius: var(--radius-2xl) !important;
  box-shadow: 0 1px 8px rgba(0, 0, 0, 0.08) !important;
  overflow: hidden;
}

.upload-card :deep(.el-card__header) {
  background: #f9fafb !important;
  border-bottom: 1px solid #e5e7eb !important;
  padding: 1rem 1.25rem !important;
}

.upload-area {
  padding: 0;
  background: transparent;
  border: none;
}

.upload-content {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 1.25rem;
  padding: 3rem 2rem;
  background: linear-gradient(135deg, rgba(59, 130, 246, 0.04) 0%, rgba(37, 99, 235, 0.02) 100%);
  border-radius: var(--radius-xl);
  border: 2px dashed rgba(59, 130, 246, 0.2);
  transition: all 0.3s ease;
  cursor: pointer;
  min-height: 140px;
}

.upload-content:hover {
  background: linear-gradient(135deg, rgba(59, 130, 246, 0.08) 0%, rgba(37, 99, 235, 0.04) 100%);
  border-color: rgba(59, 130, 246, 0.4);
  transform: translateY(-2px);
  box-shadow: 0 8px 24px rgba(59, 130, 246, 0.1);
}

.upload-icon {
  font-size: 2.5rem;
  color: var(--primary-color);
  opacity: 0.8;
  transition: all 0.3s ease;
}

.upload-content:hover .upload-icon {
  opacity: 1;
  transform: scale(1.1);
}

.upload-text {
  text-align: center;
}

.upload-text h4 {
  margin: 0 0 0.5rem 0;
  color: #1f2937;
  font-size: 1.1rem;
  font-weight: 600;
}

.upload-text p {
  margin: 0;
  color: #94a3b8;
  font-size: 0.875rem;
}

/* 文件列表卡片 - 浅色主题 */
.file-list-card {
  border: 1px solid #e5e7eb !important;
  background: #ffffff !important;
  border-radius: var(--radius-2xl) !important;
  box-shadow: 0 1px 8px rgba(0, 0, 0, 0.08) !important;
  max-height: none !important;
  overflow: visible !important;
}

.file-list-card :deep(.el-card__header) {
  background: #f9fafb !important;
  border-bottom: 1px solid #e5e7eb !important;
  padding: 1rem 1.25rem !important;
}

.header-actions {
  display: flex;
  gap: 0.5rem;
  flex-wrap: wrap;
}

.header-actions .el-button {
  border-radius: var(--radius-md) !important;
  font-size: 0.8rem !important;
}

.empty-file-list {
  text-align: center;
  padding: 3rem 1.5rem;
  color: #94a3b8;
}

.empty-file-list p {
  margin: 1rem 0 1.25rem;
  font-size: 0.9rem;
}

/* 文件网格 - Bento 风格 */
.file-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(220px, 1fr));
  gap: 1rem;
  padding: 0.5rem 0;
  min-height: auto !important;
  overflow: visible !important;
  height: auto !important;
}

.file-card {
  background: #ffffff;
  border: 1px solid #e5e7eb;
  border-radius: var(--radius-xl);
  padding: 1.25rem;
  transition: all 0.25s ease;
  cursor: pointer;
  position: relative;
  overflow: hidden;
}

.file-card::before {
  content: '';
  position: absolute;
  top: 0;
  left: 0;
  right: 0;
  height: 3px;
  background: linear-gradient(90deg, #3b82f6, #2563eb);
  opacity: 0;
  transition: opacity 0.25s ease;
}

.file-card:hover::before {
  opacity: 1;
}

.file-card.batch-mode {
  cursor: default;
}

.file-card.selected {
  border-color: var(--primary-color);
  background: rgba(59, 130, 246, 0.08) !important;
  box-shadow: 0 0 0 2px rgba(59, 130, 246, 0.3);
}

.file-card.selected::before {
  opacity: 1;
}

.file-card:hover {
  border-color: rgba(59, 130, 246, 0.3);
  box-shadow: 0 8px 24px rgba(0, 0, 0, 0.3);
  transform: translateY(-4px);
}

.file-card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 1rem;
}

.file-card-left {
  display: flex;
  align-items: center;
  gap: 0.5rem;
}

.file-checkbox {
  margin-right: 0.25rem;
}

.selected-count {
  color: var(--primary-color);
  font-weight: 600;
  font-size: 0.75rem;
}

.file-card-icon {
  font-size: 1.5rem;
  color: var(--primary-color);
  transition: transform 0.2s ease;
}

.file-card:hover .file-card-icon {
  transform: scale(1.1);
}

.file-card-actions {
  opacity: 0;
  transition: opacity 0.2s;
}

.file-card:hover .file-card-actions {
  opacity: 1;
}

.file-card-content {
  min-height: 60px;
}

.file-card-name {
  font-weight: 600;
  color: #1f2937;
  margin-bottom: 0.5rem;
  font-size: 0.9rem;
  line-height: 1.4;
  overflow: hidden;
  text-overflow: ellipsis;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
}

.file-card-meta {
  display: flex;
  flex-direction: column;
  gap: 0.25rem;
  font-size: 0.75rem;
}

.file-card-size {
  color: var(--primary-color);
  font-weight: 600;
}

.file-card-date {
  color: #333333;
}

/* 上传进度悬浮窗口 */
.upload-float-window {
  position: fixed;
  bottom: 24px;
  right: 24px;
  width: 320px;
  background: #ffffff;
  border-radius: 16px;
  box-shadow: 0 12px 40px rgba(0, 0, 0, 0.2);
  z-index: 2000;
  overflow: hidden;
  border: 1px solid #e5e7eb;
}

.float-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 12px 16px;
  background: linear-gradient(135deg, #3b82f6 0%, #2563eb 100%);
  color: white;
}

.float-title {
  display: flex;
  align-items: center;
  gap: 8px;
  font-weight: 600;
  font-size: 0.875rem;
}

.float-icon {
  font-size: 1rem;
  animation: bounce 1s ease-in-out infinite;
}

.float-actions .el-button {
  color: rgba(255, 255, 255, 0.8) !important;
}

.float-actions .el-button:hover {
  color: white !important;
}

.float-content {
  padding: 16px;
}

.float-content .upload-progress-bar {
  margin-bottom: 8px;
}

.float-content .upload-progress-percentage {
  text-align: center;
  margin-top: 8px;
  font-weight: 700;
  color: #3b82f6;
  font-size: 1.1rem;
}

.float-content .upload-progress-desc {
  text-align: center;
  font-size: 0.8rem;
  color: #94a3b8;
}

/* 最小化悬浮按钮 */
.upload-float-btn {
  position: fixed;
  bottom: 24px;
  right: 24px;
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 10px 20px;
  background: linear-gradient(135deg, #3b82f6 0%, #2563eb 100%);
  color: white;
  border-radius: 24px;
  box-shadow: 0 8px 24px rgba(59, 130, 246, 0.4);
  cursor: pointer;
  z-index: 2000;
  transition: all 0.25s ease;
  position: fixed;
}

.upload-float-btn:hover {
  transform: translateY(-2px);
  box-shadow: 0 12px 32px rgba(59, 130, 246, 0.5);
}

.float-btn-icon {
  font-size: 1rem;
  animation: bounce 1s ease-in-out infinite;
}

.float-btn-text {
  font-weight: 600;
  font-size: 0.875rem;
  white-space: nowrap;
}

.float-btn-progress {
  position: absolute;
  bottom: 0;
  left: 0;
  right: 0;
  height: 3px;
  background: rgba(255, 255, 255, 0.2);
  border-radius: 0 0 24px 24px;
  overflow: hidden;
}

.float-btn-progress-fill {
  height: 100%;
  background: rgba(255, 255, 255, 0.8);
  border-radius: 0 0 24px 24px;
  transition: width 0.3s ease;
}

/* 悬浮窗口动画 */
.float-slide-enter-active,
.float-slide-leave-active {
  transition: all 0.3s cubic-bezier(0.4, 0, 0.2, 1);
}

.float-slide-enter-from {
  opacity: 0;
  transform: translateY(20px) scale(0.95);
}

.float-slide-leave-to {
  opacity: 0;
  transform: translateY(10px) scale(0.95);
}

.fade-enter-active,
.fade-leave-active {
  transition: all 0.2s ease;
}

.fade-enter-from,
.fade-leave-to {
  opacity: 0;
  transform: scale(0.9);
}

@keyframes bounce {
  0%, 100% { transform: translateY(0); }
  50% { transform: translateY(-6px); }
}

/* 批量操作浮动栏 */
.batch-action-bar {
  position: fixed;
  bottom: 24px;
  left: 50%;
  transform: translateX(-50%);
  display: flex;
  align-items: center;
  gap: 20px;
  padding: 12px 24px;
  background: #ffffff;
  border-radius: 16px;
  box-shadow: 0 12px 40px rgba(0, 0, 0, 0.2);
  z-index: 1999;
  border: 1px solid #e5e7eb;
}

.batch-bar-left {
  display: flex;
  align-items: center;
  gap: 8px;
}

.batch-bar-icon {
  font-size: 1.1rem;
  color: #3b82f6;
}

.batch-bar-text {
  font-size: 0.875rem;
  color: #6b7280;
  white-space: nowrap;
}

.batch-bar-text strong {
  color: #3b82f6;
  font-weight: 700;
}

.batch-bar-actions {
  display: flex;
  gap: 8px;
}

.batch-bar-actions .el-button {
  border-radius: 8px !important;
  font-weight: 600 !important;
}

/* 页面加载动画 */
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

.knowledge-base-container {
  animation: fadeInUp 0.6s ease-out;
}

.tree-card {
  animation: slideInLeft 0.6s ease-out 0.2s both;
}

.content-area {
  animation: slideInRight 0.6s ease-out 0.4s both;
}

/* 响应式设计 */
@media (max-width: 1024px) {
  .header-content {
    padding: 1.25rem 1.5rem;
  }

  .header-left h1 {
    font-size: 1.5rem;
  }

  .main-content {
    padding: 1.5rem;
  }
}

@media (max-width: 768px) {
  .main-content {
    padding: 1rem;
  }

  .header-content {
    flex-direction: column;
    gap: 1rem;
    align-items: stretch;
    padding: 1rem;
  }

  .header-actions {
    justify-content: center;
    flex-wrap: wrap;
  }

  .el-row {
    margin: 0 !important;
  }

  .el-col {
    padding: 0 !important;
  }

  .file-grid {
    grid-template-columns: 1fr;
  }

  .tree-card,
  .content-area {
    animation: fadeInUp 0.6s ease-out;
  }

  .header-left h1 {
    font-size: 1.25rem;
  }
}

/* 虚拟人AI对话信息样式 */
.virtual-human-info-container {
  padding: 1.25rem;
}

.virtual-human-info-container .info-content {
  margin-bottom: 1.25rem;
}

.virtual-human-info-container .feature-list {
  margin: 1.25rem 0;
}

.virtual-human-info-container .feature-list h4 {
  margin: 0 0 0.75rem 0;
  color: #1f2937;
  font-size: 1rem;
  font-weight: 600;
}

.virtual-human-info-container .feature-list ul {
  margin: 0;
  padding-left: 1.25rem;
  color: #94a3b8;
}

.virtual-human-info-container .feature-list li {
  margin-bottom: 0.5rem;
  font-size: 0.875rem;
}

.virtual-human-info-container .usage-info {
  margin-top: 1.25rem;
  padding: 1rem;
  background: var(--bg-tertiary);
  border-radius: var(--radius-lg);
  border-left: 4px solid var(--success-color);
}

.virtual-human-info-container .usage-info h4 {
  margin: 0 0 0.5rem 0;
  color: #1f2937;
  font-size: 0.875rem;
  font-weight: 600;
}

.virtual-human-info-container .usage-info p {
  margin: 0 0 0.5rem 0;
  color: #94a3b8;
  font-size: 0.8rem;
  line-height: 1.5;
}

.virtual-human-info-container .usage-info p:last-child {
  margin-bottom: 0;
}

.dialog-footer {
  display: flex;
  justify-content: flex-end;
  gap: 0.75rem;
}

/* 浅色主题 Element Plus 组件覆盖 */
.knowledge-base-container :deep(.el-tree) {
  background: transparent;
  color: #374151;
}

.knowledge-base-container :deep(.el-tree-node__content:hover) {
  background: rgba(59, 130, 246, 0.08) !important;
}

.knowledge-base-container :deep(.el-tree-node.is-current > .el-tree-node__content) {
  background: rgba(59, 130, 246, 0.15) !important;
  color: #3b82f6;
}

.knowledge-base-container :deep(.el-tree-node__expand-icon) {
  color: #6b7280;
}

.knowledge-base-container :deep(.el-button) {
  color: #374151;
}

.knowledge-base-container :deep(.el-card) {
  color: #374151;
}

.knowledge-base-container :deep(.el-upload) {
  color: #374151;
}

.knowledge-base-container :deep(.el-upload-dragger) {
  background: transparent;
  border-color: rgba(59, 130, 246, 0.3);
  color: #374151;
}

.knowledge-base-container :deep(.el-upload-dragger:hover) {
  border-color: #3b82f6;
}

.knowledge-base-container :deep(.el-checkbox__inner) {
  background: #ffffff;
  border-color: #d1d5db;
}

.knowledge-base-container :deep(.el-checkbox__input.is-checked .el-checkbox__inner) {
  background: #3b82f6;
  border-color: #3b82f6;
}

/* 新建知识库输入框字体改为黑色 */
.knowledge-base-container :deep(.el-message-box__input .el-input__inner) {
  color: #000000 !important;
  background: #FFFFFF !important;
  border-color: #dcdfe6 !important;
}

.knowledge-base-container :deep(.el-message-box__input .el-input__inner:focus) {
  border-color: #409eff !important;
  background: #FFFFFF !important;
}
</style>
