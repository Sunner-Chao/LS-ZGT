<template>
  <div class="wechat-management-container">
    <!-- 页面头部 -->
    <div class="page-header">
      <div class="header-content">
        <div class="header-left">
          <el-button @click="$router.go(-1)" icon="ArrowLeft" class="back-btn">返回</el-button>
          <h1>微信公众号管理</h1>
        </div>
        <div class="header-actions">
          <el-button type="primary" @click="testConnection" :loading="testing">
            测试连接
          </el-button>
          <el-button @click="refreshStatus" icon="Refresh" :loading="refreshing">
            刷新状态
          </el-button>
        </div>
      </div>
    </div>

    <!-- 主要内容区域 -->
    <div class="main-content">
      <el-row :gutter="24">
        <!-- 左侧配置区域 -->
        <el-col :span="12">
          <el-card class="config-card" shadow="never">
            <template #header>
              <div class="card-header">
                <span class="card-title">
                  <el-icon><Setting /></el-icon>
                  公众号配置
                </span>
              </div>
            </template>
            
            <el-form :model="configForm" label-width="120px" class="config-form">
              <el-form-item label="AppID">
                <el-input v-model="configForm.appId" placeholder="请输入微信公众号AppID" />
              </el-form-item>
              
              <el-form-item label="AppSecret">
                <el-input v-model="configForm.appSecret" type="password" placeholder="请输入微信公众号AppSecret" show-password />
              </el-form-item>
              
              <el-form-item label="Token">
                <el-input v-model="configForm.token" placeholder="请输入微信公众号Token" />
              </el-form-item>
              
              <el-form-item label="EncodingAESKey">
                <el-input v-model="configForm.encodingAESKey" placeholder="请输入微信公众号EncodingAESKey" />
              </el-form-item>
              
              <el-form-item label="服务器URL">
                <el-input v-model="configForm.serverUrl" placeholder="微信公众号服务器URL" readonly>
                  <template #append>
                    <el-button @click="copyServerUrl">复制</el-button>
                  </template>
                </el-input>
              </el-form-item>
              
              <el-form-item>
                <el-button type="primary" @click="saveConfig" :loading="saving">
                  保存配置
                </el-button>
                <el-button @click="resetConfig">重置</el-button>
              </el-form-item>
            </el-form>
          </el-card>
        </el-col>

        <!-- 右侧状态区域 -->
        <el-col :span="12">
          <el-card class="status-card" shadow="never">
            <template #header>
              <div class="card-header">
                <span class="card-title">
                  <el-icon><InfoFilled /></el-icon>
                  连接状态
                </span>
              </div>
            </template>
            
            <div class="status-content">
              <div class="status-item">
                <span class="status-label">服务器状态:</span>
                <el-tag :type="serverStatus ? 'success' : 'danger'">
                  {{ serverStatus ? '正常' : '异常' }}
                </el-tag>
              </div>
              
              <div class="status-item">
                <span class="status-label">Access Token:</span>
                <el-tag :type="accessTokenStatus ? 'success' : 'warning'">
                  {{ accessTokenStatus ? '有效' : '无效' }}
                </el-tag>
              </div>
              
              <div class="status-item">
                <span class="status-label">菜单状态:</span>
                <el-tag :type="menuStatus ? 'success' : 'info'">
                  {{ menuStatus ? '已配置' : '未配置' }}
                </el-tag>
              </div>
              
              <div class="status-item">
                <span class="status-label">关注用户数:</span>
                <span class="status-value">{{ userCount }}</span>
              </div>
            </div>
          </el-card>

          <!-- 菜单管理 -->
          <el-card class="menu-card" shadow="never" style="margin-top: 20px;">
            <template #header>
              <div class="card-header">
                <span class="card-title">
                  <el-icon><Menu /></el-icon>
                  自定义菜单
                </span>
                <el-button type="primary" size="small" @click="createMenu">
                  创建菜单
                </el-button>
              </div>
            </template>
            
            <div class="menu-content">
              <el-button @click="getMenu" size="small">获取菜单</el-button>
              <el-button @click="deleteMenu" size="small" type="danger">删除菜单</el-button>
              
              <div class="menu-preview" v-if="menuData">
                <h4>菜单预览:</h4>
                <pre>{{ JSON.stringify(menuData, null, 2) }}</pre>
              </div>
            </div>
          </el-card>
        </el-col>
      </el-row>

      <!-- 消息管理 -->
      <el-card class="message-card" shadow="never" style="margin-top: 20px;">
        <template #header>
          <div class="card-header">
            <span class="card-title">
              <el-icon><ChatDotRound /></el-icon>
              消息管理
            </span>
          </div>
        </template>
        
        <el-form :model="messageForm" label-width="120px" class="message-form">
          <el-form-item label="用户OpenID">
            <el-input v-model="messageForm.openId" placeholder="请输入用户OpenID" />
          </el-form-item>
          
          <el-form-item label="消息内容">
            <el-input v-model="messageForm.content" type="textarea" :rows="3" placeholder="请输入要发送的消息内容" />
          </el-form-item>
          
          <el-form-item>
            <el-button type="primary" @click="sendMessage" :loading="sending">
              发送消息
            </el-button>
          </el-form-item>
        </el-form>
      </el-card>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { Setting, InfoFilled, Menu, ChatDotRound } from '@element-plus/icons-vue'
import { getAuthToken } from '../utils/auth'

// 状态管理
const testing = ref(false)
const refreshing = ref(false)
const saving = ref(false)
const sending = ref(false)

const serverStatus = ref(false)
const accessTokenStatus = ref(false)
const menuStatus = ref(false)
const userCount = ref(0)
const menuData = ref(null)

// 配置表单
const configForm = reactive({
  appId: '',
  appSecret: '',
  token: '',
  encodingAESKey: '',
  serverUrl: `${window.location.origin}/api/wechat/official-account`
})

// 消息表单
const messageForm = reactive({
  openId: '',
  content: ''
})

// 测试连接
const testConnection = async () => {
  testing.value = true
  try {
    const response = await fetch('/api/wechat/access-token', {
      headers: {
        'Authorization': 'Bearer ' + getAuthToken()
      }
    })
    
    if (response.ok) {
      const data = await response.json()
      if (data.success) {
        ElMessage.success('连接测试成功')
        accessTokenStatus.value = true
      } else {
        ElMessage.error('连接测试失败: ' + data.message)
        accessTokenStatus.value = false
      }
    } else {
      ElMessage.error('连接测试失败')
      accessTokenStatus.value = false
    }
  } catch (error) {
    ElMessage.error('连接测试失败: ' + error)
    accessTokenStatus.value = false
  } finally {
    testing.value = false
  }
}

// 刷新状态
const refreshStatus = async () => {
  refreshing.value = true
  try {
    // 测试服务器连接
    await testConnection()
    
    // 获取菜单状态
    const menuResponse = await fetch('/api/wechat/get-menu', {
      headers: {
        'Authorization': 'Bearer ' + getAuthToken()
      }
    })
    
    if (menuResponse.ok) {
      const menuResult = await menuResponse.json()
      menuStatus.value = menuResult.success
      if (menuResult.success && menuResult.result) {
        menuData.value = JSON.parse(menuResult.result)
      }
    }
    
    serverStatus.value = true
    ElMessage.success('状态刷新成功')
  } catch (error) {
    ElMessage.error('状态刷新失败: ' + error)
    serverStatus.value = false
  } finally {
    refreshing.value = false
  }
}

// 保存配置
const saveConfig = async () => {
  saving.value = true
  try {
    // 这里应该调用后端API保存配置
    ElMessage.success('配置保存成功')
  } catch (error) {
    ElMessage.error('配置保存失败: ' + error)
  } finally {
    saving.value = false
  }
}

// 重置配置
const resetConfig = () => {
  configForm.appId = ''
  configForm.appSecret = ''
  configForm.token = ''
  configForm.encodingAESKey = ''
  ElMessage.info('配置已重置')
}

// 复制服务器URL
const copyServerUrl = async () => {
  try {
    await navigator.clipboard.writeText(configForm.serverUrl)
    ElMessage.success('服务器URL已复制到剪贴板')
  } catch (error) {
    ElMessage.error('复制失败')
  }
}

// 创建菜单
const createMenu = async () => {
  try {
    const menuData = {
      button: [
        {
          type: 'click',
          name: '开始对话',
          key: 'START_CHAT'
        },
        {
          name: '功能菜单',
          sub_button: [
            {
              type: 'click',
              name: '知识库',
              key: 'KNOWLEDGE_BASE'
            },
            {
              type: 'click',
              name: '使用帮助',
              key: 'HELP'
            }
          ]
        },
        {
          type: 'view',
          name: '网页版',
          url: window.location.origin
        }
      ]
    }
    
    const response = await fetch('/api/wechat/create-menu', {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'Authorization': 'Bearer ' + getAuthToken()
      },
      body: JSON.stringify(menuData)
    })
    
    if (response.ok) {
      const result = await response.json()
      if (result.success) {
        ElMessage.success('菜单创建成功')
        menuStatus.value = true
      } else {
        ElMessage.error('菜单创建失败: ' + result.message)
      }
    } else {
      ElMessage.error('菜单创建失败')
    }
  } catch (error) {
    ElMessage.error('菜单创建失败: ' + error)
  }
}

// 获取菜单
const getMenu = async () => {
  try {
    const response = await fetch('/api/wechat/get-menu', {
      headers: {
        'Authorization': 'Bearer ' + getAuthToken()
      }
    })
    
    if (response.ok) {
      const result = await response.json()
      if (result.success) {
        menuData.value = JSON.parse(result.result)
        ElMessage.success('菜单获取成功')
      } else {
        ElMessage.error('菜单获取失败: ' + result.message)
      }
    } else {
      ElMessage.error('菜单获取失败')
    }
  } catch (error) {
    ElMessage.error('菜单获取失败: ' + error)
  }
}

// 删除菜单
const deleteMenu = async () => {
  try {
    const response = await fetch('/api/wechat/delete-menu', {
      method: 'DELETE',
      headers: {
        'Authorization': 'Bearer ' + getAuthToken()
      }
    })
    
    if (response.ok) {
      const result = await response.json()
      if (result.success) {
        ElMessage.success('菜单删除成功')
        menuStatus.value = false
        menuData.value = null
      } else {
        ElMessage.error('菜单删除失败: ' + result.message)
      }
    } else {
      ElMessage.error('菜单删除失败')
    }
  } catch (error) {
    ElMessage.error('菜单删除失败: ' + error)
  }
}

// 发送消息
const sendMessage = async () => {
  if (!messageForm.openId || !messageForm.content) {
    ElMessage.warning('请填写完整的消息信息')
    return
  }
  
  sending.value = true
  try {
    const response = await fetch('/api/wechat/send-message', {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'Authorization': 'Bearer ' + getAuthToken()
      },
      body: JSON.stringify({
        openId: messageForm.openId,
        content: messageForm.content
      })
    })
    
    if (response.ok) {
      const result = await response.json()
      if (result.success) {
        ElMessage.success('消息发送成功')
        messageForm.openId = ''
        messageForm.content = ''
      } else {
        ElMessage.error('消息发送失败: ' + result.message)
      }
    } else {
      ElMessage.error('消息发送失败')
    }
  } catch (error) {
    ElMessage.error('消息发送失败: ' + error)
  } finally {
    sending.value = false
  }
}

onMounted(() => {
  refreshStatus()
})
</script>

<style scoped>
.wechat-management-container {
  padding: 20px;
  background: #f5f7fa;
  min-height: 100vh;
}

.page-header {
  background: white;
  border-radius: 8px;
  padding: 20px;
  margin-bottom: 20px;
  box-shadow: 0 2px 4px rgba(0, 0, 0, 0.1);
}

.header-content {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.header-left {
  display: flex;
  align-items: center;
  gap: 16px;
}

.header-left h1 {
  margin: 0;
  font-size: 24px;
  font-weight: 600;
  color: #303133;
}

.header-actions {
  display: flex;
  gap: 12px;
}

.back-btn {
  color: #606266;
}

.main-content {
  display: flex;
  flex-direction: column;
  gap: 20px;
}

.config-card, .status-card, .menu-card, .message-card {
  border-radius: 8px;
  border: 1px solid #e9ecef;
}

.card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.card-title {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 16px;
  font-weight: 600;
  color: #303133;
}

.config-form {
  max-width: 100%;
}

.status-content {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.status-item {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 12px 0;
  border-bottom: 1px solid #f0f0f0;
}

.status-item:last-child {
  border-bottom: none;
}

.status-label {
  font-weight: 500;
  color: #606266;
}

.status-value {
  font-weight: 600;
  color: #303133;
}

.menu-content {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.menu-preview {
  margin-top: 16px;
  padding: 16px;
  background: #f8f9fa;
  border-radius: 6px;
  border: 1px solid #e9ecef;
}

.menu-preview h4 {
  margin: 0 0 12px 0;
  color: #303133;
}

.menu-preview pre {
  margin: 0;
  font-size: 12px;
  color: #606266;
  white-space: pre-wrap;
  word-break: break-all;
}

.message-form {
  max-width: 600px;
}

@media (max-width: 768px) {
  .wechat-management-container {
    padding: 12px;
  }
  
  .header-content {
    flex-direction: column;
    gap: 16px;
    align-items: flex-start;
  }
  
  .header-actions {
    width: 100%;
    justify-content: flex-end;
  }
}
</style>
