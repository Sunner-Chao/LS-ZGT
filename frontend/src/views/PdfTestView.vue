<template>
  <div class="pdf-test-container">
    <div class="test-header">
      <h1>PDF解析测试工具</h1>
      <p>上传PDF文件测试解析功能，诊断PDF解析问题</p>
      <el-alert 
        title="当前状态：模拟模式" 
        type="warning" 
        description="由于后端API接口存在405错误，当前使用模拟数据展示页面功能。真实的PDF解析功能需要修复后端配置问题。" 
        show-icon 
        :closable="false"
        style="margin-bottom: 20px;"
      />
      
      <!-- 滚动测试区域 -->
      <div style="background: #f0f9ff; padding: 20px; margin: 20px 0; border: 2px solid #0ea5e9; border-radius: 8px;">
        <h3 style="color: #0ea5e9; margin-bottom: 10px;">🔍 滚动测试区域</h3>
        <p style="margin-bottom: 15px;">如果你能看到这个蓝色框，说明页面布局正常。</p>
        <p style="margin-bottom: 15px;">请尝试滚动页面，查看是否能滚动到下面的内容。</p>
        <div style="background: #e0f2fe; padding: 10px; border-radius: 4px; margin-bottom: 15px;">
          <p style="margin: 0; font-size: 12px; color: #0277bd;">
            <strong>当前滚动状态：</strong><br>
            窗口高度: {{ scrollInfo.windowHeight }}px<br>
            页面总高度: {{ scrollInfo.pageHeight }}px<br>
            当前滚动位置: {{ scrollInfo.scrollTop }}px<br>
            可滚动距离: {{ scrollInfo.maxScroll }}px
          </p>
        </div>
        <el-button @click="testScroll" type="primary" size="small">创建滚动测试内容</el-button>
        <el-button @click="scrollToTop" type="success" size="small" style="margin-left: 10px;">回到顶部</el-button>
        <el-button @click="scrollToBottom" type="warning" size="small" style="margin-left: 10px;">滚动到底部</el-button>
        <el-button @click="debugScrollInfo" type="info" size="small" style="margin-left: 10px;">调试信息</el-button>
        <el-button @click="forceRefreshScroll" type="danger" size="small" style="margin-left: 10px;">强制刷新</el-button>
      </div>
    </div>

    <div class="test-content">
      <!-- 文件上传区域 -->
      <el-card class="upload-card" shadow="hover">
        <template #header>
          <div class="card-header">
            <span>PDF文件上传</span>
          </div>
        </template>
        
        <el-upload
          ref="uploadRef"
          class="upload-demo"
          drag
          :auto-upload="false"
          :on-change="handleFileChange"
          :before-upload="beforeUpload"
          accept=".pdf"
          :limit="1"
        >
          <el-icon class="el-icon--upload"><upload-filled /></el-icon>
          <div class="el-upload__text">
            将PDF文件拖到此处，或<em>点击上传</em>
          </div>
          <template #tip>
            <div class="el-upload__tip">
              只能上传PDF文件，且不超过100MB
            </div>
          </template>
        </el-upload>

        <div class="upload-actions" v-if="selectedFile">
          <el-button type="primary" @click="testParse" :loading="testing">
            <el-icon><Document /></el-icon>
            开始解析测试
          </el-button>
          <el-button @click="clearFile">清除文件</el-button>
          <el-button @click="checkConnection" :loading="checkingConnection">
            <el-icon><Connection /></el-icon>
            检查连接
          </el-button>
          <el-button @click="testApiEndpoint" :loading="testing">
            <el-icon><Document /></el-icon>
            测试API
          </el-button>
          <el-button @click="testScroll" type="success">
            <el-icon><Document /></el-icon>
            测试滚动
          </el-button>
        </div>
      </el-card>

      <!-- 测试结果区域 -->
      <el-card class="result-card" shadow="hover" v-if="testResult">
        <template #header>
          <div class="card-header">
            <span>解析结果</span>
            <el-tag :type="testResult.success ? 'success' : 'danger'">
              {{ testResult.success ? '解析成功' : '解析失败' }}
            </el-tag>
          </div>
        </template>

        <div class="result-info">
          <el-descriptions :column="2" border>
            <el-descriptions-item label="文件名">
              {{ testResult.fileName }}
            </el-descriptions-item>
            <el-descriptions-item label="文件大小">
              {{ formatFileSize(testResult.fileSize) }}
            </el-descriptions-item>
            <el-descriptions-item label="解析方法">
              {{ testResult.parseMethod }}
            </el-descriptions-item>
            <el-descriptions-item label="内容长度">
              {{ testResult.textLength }} 字符
            </el-descriptions-item>
          </el-descriptions>
        </div>

        <!-- 错误信息 -->
        <div v-if="!testResult.success" class="error-section">
          <el-alert
            :title="testResult.error"
            type="error"
            :closable="false"
            show-icon
          />
          
          <div v-if="testResult.pdfExtractKitError" class="error-detail">
            <h4>PDF-Extract-Kit错误：</h4>
            <el-text type="danger">{{ testResult.pdfExtractKitError }}</el-text>
          </div>
          
          <div v-if="testResult.pdfBoxError" class="error-detail">
            <h4>Apache PDFBox错误：</h4>
            <el-text type="danger">{{ testResult.pdfBoxError }}</el-text>
          </div>
        </div>

        <!-- 备用方案信息 -->
        <div v-if="testResult.fallbackReason" class="fallback-info">
          <el-alert
            :title="testResult.fallbackReason"
            type="warning"
            :closable="false"
            show-icon
          />
        </div>

        <!-- 内容预览 -->
        <div v-if="testResult.success" class="content-preview">
          <h3>内容预览</h3>
          <el-input
            v-model="testResult.preview"
            type="textarea"
            :rows="10"
            readonly
            placeholder="解析内容将显示在这里..."
          />
          
          <div class="preview-actions">
            <el-button @click="showFullContent" v-if="testResult.textLength > 1000">
              查看完整内容
            </el-button>
            <el-button @click="copyToClipboard">
              复制内容
            </el-button>
          </div>
        </div>
      </el-card>

      <!-- 完整内容对话框 -->
      <el-dialog
        v-model="showFullDialog"
        title="完整解析内容"
        width="80%"
        :before-close="handleCloseFullDialog"
      >
        <el-input
          v-model="testResult.text"
          type="textarea"
          :rows="20"
          readonly
          placeholder="完整内容将显示在这里..."
        />
        <template #footer>
          <el-button @click="showFullDialog = false">关闭</el-button>
          <el-button type="primary" @click="copyToClipboard">复制内容</el-button>
        </template>
      </el-dialog>
      
      <!-- 滚动测试成功提示 -->
      <div style="background: #dcfce7; padding: 20px; margin: 20px 0; border: 2px solid #22c55e; border-radius: 8px; text-align: center;">
        <h3 style="color: #22c55e; margin-bottom: 10px;">✅ 滚动功能已修复</h3>
        <p style="color: #16a34a; font-size: 14px;">现在你可以正常滚动查看所有内容了！</p>
      </div>
      
      <!-- 测试内容区域 - 确保页面有足够高度 -->
      <div style="background: #f3f4f6; padding: 30px; margin: 30px 0; border: 2px solid #6b7280; border-radius: 8px;">
        <h3 style="color: #374151; margin-bottom: 20px;">📄 测试内容区域</h3>
        <p style="color: #4b5563; margin-bottom: 15px;">这个区域用于测试页面滚动功能。如果你能看到下面的内容，说明滚动正常工作。</p>
        
        <!-- 生成大量测试内容 -->
        <div v-for="i in 20" :key="i" style="background: white; padding: 15px; margin: 10px 0; border-radius: 6px; border-left: 4px solid #3b82f6;">
          <h4 style="color: #1e40af; margin: 0 0 8px 0;">测试段落 {{ i }}</h4>
          <p style="color: #374151; margin: 0; line-height: 1.6;">
            这是第{{ i }}个测试段落。Lorem ipsum dolor sit amet, consectetur adipiscing elit. 
            Sed do eiusmod tempor incididunt ut labore et dolore magna aliqua. 
            Ut enim ad minim veniam, quis nostrud exercitation ullamco laboris.
          </p>
        </div>
        
        <!-- 底部提示 -->
        <div style="background: #dbeafe; padding: 20px; margin: 20px 0; border-radius: 8px; text-align: center; border: 2px solid #3b82f6;">
          <h3 style="color: #1e40af; margin-bottom: 10px;">🎉 滚动测试完成！</h3>
          <p style="color: #1e40af; margin: 0;">如果你能看到这个区域，说明页面滚动功能完全正常！</p>
        </div>
      </div>
    </div>
    
    <!-- 自定义滚动条 -->
    <div class="custom-scrollbar" ref="customScrollbar">
      <div class="scrollbar-track" ref="scrollbarTrack">
        <div class="scrollbar-thumb" ref="scrollbarThumb" @mousedown="startDrag"></div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { UploadFilled, Document, Connection } from '@element-plus/icons-vue'
import { getAuthToken } from '../utils/auth'

// 响应式数据
const uploadRef = ref()
const selectedFile = ref<File | null>(null)
const testing = ref(false)
const checkingConnection = ref(false)
const testResult = ref<any>(null)
const showFullDialog = ref(false)

// 自定义滚动条相关
const customScrollbar = ref()
const scrollbarTrack = ref()
const scrollbarThumb = ref()
const isDragging = ref(false)
const dragStartY = ref(0)
const thumbStartY = ref(0)

// 滚动信息
const scrollInfo = ref({
  windowHeight: 0,
  pageHeight: 0,
  scrollTop: 0,
  maxScroll: 0
})

// 文件选择处理
const handleFileChange = (file: any) => {
  selectedFile.value = file.raw
  testResult.value = null
}

// 文件上传前检查
const beforeUpload = (file: File) => {
  const isPDF = file.type === 'application/pdf'
  const isLt100M = file.size / 1024 / 1024 < 100

  if (!isPDF) {
    ElMessage.error('只能上传PDF文件!')
    return false
  }
  if (!isLt100M) {
    ElMessage.error('文件大小不能超过100MB!')
    return false
  }
  return false // 阻止自动上传
}

// 清除文件
const clearFile = () => {
  selectedFile.value = null
  testResult.value = null
  uploadRef.value?.clearFiles()
}

// 开始解析测试
const testParse = async () => {
  if (!selectedFile.value) {
    ElMessage.warning('请先选择PDF文件')
    return
  }

  testing.value = true
  testResult.value = null

  try {
    // 由于API接口有问题，先显示模拟结果
    ElMessage.info('由于API接口配置问题，显示模拟测试结果...')
    
    // 模拟延迟
    await new Promise(resolve => setTimeout(resolve, 2000))
    
    const mockResult = {
      success: true,
      fileName: selectedFile.value.name,
      fileSize: selectedFile.value.size,
      parseMethod: 'PDF-Extract-Kit (模拟)',
      textLength: 1234,
      text: `这是PDF文件 "${selectedFile.value.name}" 的模拟解析结果。\n\n在实际环境中，这里会显示PDF文件的真实解析内容。\n\n文件信息：\n- 文件名：${selectedFile.value.name}\n- 文件大小：${formatFileSize(selectedFile.value.size)}\n- 解析方法：PDF-Extract-Kit\n- 文本长度：1234字符\n\n注意：这是模拟数据，用于测试页面功能。实际的PDF解析需要修复后端API接口的405错误。`,
      processingTime: 2000,
      error: null
    }
    
    testResult.value = mockResult
    ElMessage.success(`模拟解析成功！文件：${mockResult.fileName}，文本长度：${mockResult.textLength}字符`)
    
  } catch (error) {
    console.error('解析测试失败:', error)
    
    let errorMessage = '解析测试失败'
    if (error instanceof Error) {
      if (error.name === 'AbortError') {
        errorMessage = '请求超时，请检查文件大小或网络连接'
      } else if (error.message.includes('Failed to fetch')) {
        errorMessage = '网络连接失败，请检查服务器状态'
      } else if (error.message.includes('ERR_CONNECTION_ABORTED')) {
        errorMessage = '连接被中断，可能是文件太大或服务器处理超时'
      } else {
        errorMessage = `解析测试失败：${error.message}`
      }
    }
    
    ElMessage.error(errorMessage)
    testResult.value = {
      success: false,
      error: errorMessage,
      fileName: selectedFile.value.name,
      fileSize: selectedFile.value.size,
      networkError: true
    }
  } finally {
    testing.value = false
  }
}

// 显示完整内容
const showFullContent = () => {
  showFullDialog.value = true
}

// 关闭完整内容对话框
const handleCloseFullDialog = () => {
  showFullDialog.value = false
}

// 复制到剪贴板
const copyToClipboard = async () => {
  const text = testResult.value?.text || testResult.value?.preview
  if (!text) return

  try {
    await navigator.clipboard.writeText(text)
    ElMessage.success('内容已复制到剪贴板')
  } catch (error) {
    console.error('复制失败:', error)
    ElMessage.error('复制失败，请手动复制')
  }
}

// 检查连接状态
const checkConnection = async () => {
  checkingConnection.value = true
  
  try {
    const response = await fetch('http://localhost:8080/api/debug/milvus-status', {
      method: 'GET',
      headers: {
        'Authorization': 'Bearer ' + getAuthToken()
      },
      mode: 'cors',
      credentials: 'include'
    })
    
    if (response.ok) {
      ElMessage.success('服务器连接正常')
    } else {
      ElMessage.warning(`服务器响应异常: ${response.status}`)
    }
  } catch (error) {
    console.error('连接检查失败:', error)
    ElMessage.error('无法连接到服务器，请检查网络连接')
  } finally {
    checkingConnection.value = false
  }
}

// 测试API端点
const testApiEndpoint = async () => {
  testing.value = true
  
  try {
    // 先测试一个简单的GET接口
    const response = await fetch('http://localhost:8080/api/debug/milvus-status', {
      method: 'GET',
      headers: {
        'Authorization': 'Bearer ' + getAuthToken()
      },
      mode: 'cors',
      credentials: 'include'
    })
    
    console.log('API测试响应:', {
      status: response.status,
      statusText: response.statusText,
      headers: Object.fromEntries(response.headers.entries())
    })
    
    if (response.ok) {
      const result = await response.json()
      ElMessage.success(`API端点可访问，响应: ${JSON.stringify(result)}`)
    } else {
      const errorText = await response.text()
      ElMessage.warning(`API端点响应异常: ${response.status} - ${errorText}`)
    }
  } catch (error) {
    console.error('API测试失败:', error)
    const errorMessage = error instanceof Error ? error.message : '未知错误'
    ElMessage.error(`API测试失败: ${errorMessage}`)
  } finally {
    testing.value = false
  }
}

// 测试滚动功能
const testScroll = () => {
  // 创建一个测试结果来验证滚动
  const scrollTestResult = {
    success: true,
    fileName: '滚动测试文件.pdf',
    fileSize: 1024000,
    parseMethod: '滚动测试',
    textLength: 5000,
    text: `这是滚动测试内容。\n\n`.repeat(100) + `\n\n滚动测试完成！如果你能看到这段文字，说明滚动功能正常工作。`,
    processingTime: 1000,
    error: null
  }
  
  testResult.value = scrollTestResult
  ElMessage.success('滚动测试已创建，请尝试滚动查看内容')
}

// 滚动到顶部
const scrollToTop = () => {
  window.scrollTo({ top: 0, behavior: 'smooth' })
  ElMessage.success('已滚动到顶部')
}

// 滚动到底部
const scrollToBottom = () => {
  const maxScroll = Math.max(
    document.body.scrollHeight,
    document.documentElement.scrollHeight,
    document.body.offsetHeight,
    document.documentElement.offsetHeight,
    document.body.clientHeight,
    document.documentElement.clientHeight
  ) - window.innerHeight
  
  window.scrollTo({ top: maxScroll, behavior: 'smooth' })
  ElMessage.success('已滚动到底部')
}

// 自定义滚动条功能
const startDrag = (e: MouseEvent) => {
  isDragging.value = true
  dragStartY.value = e.clientY
  thumbStartY.value = scrollbarThumb.value.offsetTop
  
  document.addEventListener('mousemove', handleDrag)
  document.addEventListener('mouseup', stopDrag)
  e.preventDefault()
}

const handleDrag = (e: MouseEvent) => {
  if (!isDragging.value) return
  
  const deltaY = e.clientY - dragStartY.value
  const trackHeight = scrollbarTrack.value.offsetHeight
  const thumbHeight = scrollbarThumb.value.offsetHeight
  const maxThumbTop = trackHeight - thumbHeight
  
  let newThumbTop = thumbStartY.value + deltaY
  newThumbTop = Math.max(0, Math.min(newThumbTop, maxThumbTop))
  
  scrollbarThumb.value.style.top = newThumbTop + 'px'
  
  // 计算滚动位置
  const scrollRatio = newThumbTop / maxThumbTop
  const maxScroll = Math.max(
    document.body.scrollHeight,
    document.documentElement.scrollHeight,
    document.body.offsetHeight,
    document.documentElement.offsetHeight
  ) - window.innerHeight
  
  const scrollTop = scrollRatio * maxScroll
  window.scrollTo({ top: scrollTop, behavior: 'auto' })
}

const stopDrag = () => {
  isDragging.value = false
  document.removeEventListener('mousemove', handleDrag)
  document.removeEventListener('mouseup', stopDrag)
}

// 更新滚动条位置
const updateScrollbar = () => {
  if (!scrollbarThumb.value || !scrollbarTrack.value) return
  
  const scrollTop = window.pageYOffset
  const windowHeight = window.innerHeight
  const pageHeight = Math.max(
    document.body.scrollHeight,
    document.documentElement.scrollHeight,
    document.body.offsetHeight,
    document.documentElement.offsetHeight
  )
  const maxScroll = pageHeight - windowHeight
  
  // 更新滚动信息
  scrollInfo.value = {
    windowHeight,
    pageHeight,
    scrollTop,
    maxScroll
  }
  
  const scrollRatio = maxScroll > 0 ? scrollTop / maxScroll : 0
  
  const trackHeight = scrollbarTrack.value.offsetHeight
  const thumbHeight = scrollbarThumb.value.offsetHeight
  const maxThumbTop = trackHeight - thumbHeight
  
  const thumbTop = scrollRatio * maxThumbTop
  scrollbarThumb.value.style.top = thumbTop + 'px'
}

// 调试滚动信息
const debugScrollInfo = () => {
  const info = {
    windowHeight: window.innerHeight,
    bodyScrollHeight: document.body.scrollHeight,
    documentScrollHeight: document.documentElement.scrollHeight,
    bodyOffsetHeight: document.body.offsetHeight,
    documentOffsetHeight: document.documentElement.offsetHeight,
    bodyClientHeight: document.body.clientHeight,
    documentClientHeight: document.documentElement.clientHeight,
    currentScrollTop: window.pageYOffset,
    maxScroll: Math.max(
      document.body.scrollHeight,
      document.documentElement.scrollHeight,
      document.body.offsetHeight,
      document.documentElement.offsetHeight
    ) - window.innerHeight
  }
  
  console.log('滚动调试信息:', info)
  ElMessageBox.alert(
    `滚动调试信息：
窗口高度: ${info.windowHeight}px
页面总高度: ${info.maxScroll + info.windowHeight}px
可滚动距离: ${info.maxScroll}px
当前滚动位置: ${info.currentScrollTop}px
剩余滚动距离: ${info.maxScroll - info.currentScrollTop}px`,
    '滚动调试信息',
    { type: 'info' }
  )
}

// 强制刷新滚动信息
const forceRefreshScroll = () => {
  // 重新计算页面高度
  updateScrollbar()
  ElMessage.success('滚动信息已刷新')
}

// 初始化滚动信息
const initScrollInfo = () => {
  updateScrollbar()
}

// 监听滚动事件
window.addEventListener('scroll', updateScrollbar)
window.addEventListener('resize', updateScrollbar)

// 页面加载时初始化
initScrollInfo()

// 页面加载完成后初始化滚动信息
setTimeout(() => {
  updateScrollbar()
}, 500)

// 格式化文件大小
const formatFileSize = (bytes: number) => {
  if (bytes === 0) return '0 Bytes'
  const k = 1024
  const sizes = ['Bytes', 'KB', 'MB', 'GB']
  const i = Math.floor(Math.log(bytes) / Math.log(k))
  return parseFloat((bytes / Math.pow(k, i)).toFixed(2)) + ' ' + sizes[i]
}
</script>

<style scoped>
.pdf-test-container {
  max-width: 1200px;
  margin: 0 auto;
  padding: 20px;
  min-height: 100vh; /* 最小高度为视口高度 */
  height: auto; /* 允许高度自动扩展 */
  box-sizing: border-box;
  position: relative;
  overflow: visible;
}

.test-header {
  text-align: center;
  margin-bottom: 30px;
}

.test-header h1 {
  color: #303133;
  margin-bottom: 10px;
}

.test-header p {
  color: #606266;
  font-size: 14px;
}

.test-content {
  display: flex;
  flex-direction: column;
  gap: 20px;
  padding-bottom: 50px; /* 确保底部有足够空间 */
}

.upload-card,
.result-card {
  width: 100%;
}

.card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.upload-demo {
  width: 100%;
}

.upload-actions {
  margin-top: 20px;
  text-align: center;
}

.upload-actions .el-button {
  margin: 0 10px;
}

.result-info {
  margin-bottom: 20px;
}

.error-section {
  margin-bottom: 20px;
}

.error-detail {
  margin-top: 15px;
  padding: 10px;
  background-color: #fef0f0;
  border: 1px solid #fbc4c4;
  border-radius: 4px;
}

.error-detail h4 {
  margin: 0 0 10px 0;
  color: #f56c6c;
}

.fallback-info {
  margin-bottom: 20px;
}

.content-preview {
  margin-top: 20px;
}

.content-preview h3 {
  margin-bottom: 15px;
  color: #303133;
}

.preview-actions {
  margin-top: 15px;
  text-align: center;
}

.preview-actions .el-button {
  margin: 0 10px;
}

:deep(.el-upload-dragger) {
  width: 100%;
  height: 200px;
}

:deep(.el-upload__tip) {
  text-align: center;
  color: #606266;
  font-size: 12px;
  margin-top: 10px;
}

/* 自定义滚动条样式 */
.custom-scrollbar {
  position: fixed;
  right: 20px;
  top: 50%;
  transform: translateY(-50%);
  width: 20px;
  height: 300px;
  z-index: 1000;
  background: rgba(0, 0, 0, 0.1);
  border-radius: 10px;
  backdrop-filter: blur(10px);
}

.scrollbar-track {
  position: relative;
  width: 100%;
  height: 100%;
  background: rgba(255, 255, 255, 0.2);
  border-radius: 10px;
  cursor: pointer;
}

.scrollbar-thumb {
  position: absolute;
  top: 0;
  left: 2px;
  right: 2px;
  background: linear-gradient(45deg, #3b82f6, #1d4ed8);
  border-radius: 8px;
  cursor: grab;
  transition: all 0.2s ease;
  min-height: 30px;
  box-shadow: 0 2px 8px rgba(59, 130, 246, 0.3);
}

.scrollbar-thumb:hover {
  background: linear-gradient(45deg, #2563eb, #1e40af);
  box-shadow: 0 4px 12px rgba(59, 130, 246, 0.4);
}

.scrollbar-thumb:active {
  cursor: grabbing;
  background: linear-gradient(45deg, #1d4ed8, #1e3a8a);
}

/* 滚动条提示 */
.custom-scrollbar::before {
  content: "拖拽滚动";
  position: absolute;
  top: -30px;
  left: 50%;
  transform: translateX(-50%);
  background: rgba(0, 0, 0, 0.8);
  color: white;
  padding: 4px 8px;
  border-radius: 4px;
  font-size: 12px;
  white-space: nowrap;
  opacity: 0;
  transition: opacity 0.3s ease;
  pointer-events: none;
}

.custom-scrollbar:hover::before {
  opacity: 1;
}
</style>
