/**
 * 微信相关配置和工具函数
 * 支持微信公众号和微信小程序
 */

// 微信小程序环境检测
export const isWeChatMiniProgram = (): boolean => {
  return typeof (globalThis as any).wx !== 'undefined' && (globalThis as any).wx.miniProgram
}

// 微信浏览器环境检测
export const isWeChatBrowser = (): boolean => {
  const userAgent = navigator.userAgent.toLowerCase()
  return userAgent.includes('micromessenger')
}

// 微信公众号环境检测
export const isWeChatOfficialAccount = (): boolean => {
  return isWeChatBrowser() && !isWeChatMiniProgram()
}

// 微信配置
export const wechatConfig = {
  // 微信公众号配置
  officialAccount: {
    appId: process.env.VUE_APP_WECHAT_APP_ID || 'your-wechat-official-account-appid',
    appSecret: process.env.VUE_APP_WECHAT_APP_SECRET || 'your-wechat-app-secret',
    token: process.env.VUE_APP_WECHAT_TOKEN || 'your-wechat-token',
    encodingAESKey: process.env.VUE_APP_WECHAT_ENCODING_AES_KEY || 'your-encoding-aes-key'
  },
  
  // 微信小程序配置
  miniProgram: {
    appId: process.env.VUE_APP_WECHAT_MINIPROGRAM_APP_ID || 'your-wechat-miniprogram-appid',
    pages: {
      home: '/pages/home/home',
      chat: '/pages/chat/chat',
      knowledge: '/pages/knowledge/knowledge'
    }
  },
  
  // API接口配置
  api: {
    baseUrl: process.env.NODE_ENV === 'production' 
      ? 'https://your-domain.com/api' 
      : 'http://localhost:8080/api',
    timeout: 30000,
    // 微信公众号API
    officialAccount: {
      accessTokenUrl: 'https://api.weixin.qq.com/cgi-bin/token',
      userInfoUrl: 'https://api.weixin.qq.com/cgi-bin/user/info',
      sendMessageUrl: 'https://api.weixin.qq.com/cgi-bin/message/custom/send',
      createMenuUrl: 'https://api.weixin.qq.com/cgi-bin/menu/create',
      getMenuUrl: 'https://api.weixin.qq.com/cgi-bin/menu/get',
      deleteMenuUrl: 'https://api.weixin.qq.com/cgi-bin/menu/delete'
    }
  }
}

// 微信公众号API封装
export class WeChatOfficialAccountAPI {
  private static instance: WeChatOfficialAccountAPI
  private accessToken: string = ''
  private tokenExpireTime: number = 0
  
  static getInstance(): WeChatOfficialAccountAPI {
    if (!WeChatOfficialAccountAPI.instance) {
      WeChatOfficialAccountAPI.instance = new WeChatOfficialAccountAPI()
    }
    return WeChatOfficialAccountAPI.instance
  }
  
  // 获取访问令牌
  async getAccessToken(): Promise<string> {
    // 检查令牌是否过期
    if (this.accessToken && Date.now() < this.tokenExpireTime) {
      return this.accessToken
    }
    
    try {
      const response = await fetch(`${wechatConfig.api.officialAccount.accessTokenUrl}?grant_type=client_credential&appid=${wechatConfig.officialAccount.appId}&secret=${wechatConfig.officialAccount.appSecret}`)
      const data = await response.json()
      
      if (data.access_token) {
        this.accessToken = data.access_token
        this.tokenExpireTime = Date.now() + (data.expires_in - 300) * 1000 // 提前5分钟过期
        return this.accessToken
      } else {
        throw new Error('获取微信访问令牌失败: ' + data.errmsg)
      }
    } catch (error) {
      console.error('获取微信访问令牌失败:', error)
      throw error
    }
  }
  
  // 获取用户信息
  async getUserInfo(openId: string): Promise<any> {
    const accessToken = await this.getAccessToken()
    const response = await fetch(`${wechatConfig.api.officialAccount.userInfoUrl}?access_token=${accessToken}&openid=${openId}&lang=zh_CN`)
    return response.json()
  }
  
  // 发送客服消息
  async sendCustomMessage(openId: string, message: any): Promise<any> {
    const accessToken = await this.getAccessToken()
    const response = await fetch(`${wechatConfig.api.officialAccount.sendMessageUrl}?access_token=${accessToken}`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json'
      },
      body: JSON.stringify({
        touser: openId,
        ...message
      })
    })
    return response.json()
  }
  
  // 发送文本消息
  async sendTextMessage(openId: string, content: string): Promise<any> {
    return this.sendCustomMessage(openId, {
      msgtype: 'text',
      text: { content }
    })
  }
  
  // 发送图文消息
  async sendNewsMessage(openId: string, articles: any[]): Promise<any> {
    return this.sendCustomMessage(openId, {
      msgtype: 'news',
      news: { articles }
    })
  }
  
  // 创建自定义菜单
  async createMenu(menuData: any): Promise<any> {
    const accessToken = await this.getAccessToken()
    const response = await fetch(`${wechatConfig.api.officialAccount.createMenuUrl}?access_token=${accessToken}`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json'
      },
      body: JSON.stringify(menuData)
    })
    return response.json()
  }
  
  // 获取自定义菜单
  async getMenu(): Promise<any> {
    const accessToken = await this.getAccessToken()
    const response = await fetch(`${wechatConfig.api.officialAccount.getMenuUrl}?access_token=${accessToken}`)
    return response.json()
  }
  
  // 删除自定义菜单
  async deleteMenu(): Promise<any> {
    const accessToken = await this.getAccessToken()
    const response = await fetch(`${wechatConfig.api.officialAccount.deleteMenuUrl}?access_token=${accessToken}`)
    return response.json()
  }
}

// 微信公众号消息处理
export class WeChatMessageHandler {
  // 处理接收到的消息
  static handleMessage(message: any): any {
    const { MsgType, FromUserName, ToUserName, Content, Event } = message
    
    switch (MsgType) {
      case 'text':
        return this.handleTextMessage(message)
      case 'event':
        return this.handleEventMessage(message)
      case 'image':
        return this.handleImageMessage(message)
      case 'voice':
        return this.handleVoiceMessage(message)
      case 'video':
        return this.handleVideoMessage(message)
      case 'location':
        return this.handleLocationMessage(message)
      case 'link':
        return this.handleLinkMessage(message)
      default:
        return this.handleUnknownMessage(message)
    }
  }
  
  // 处理文本消息
  private static handleTextMessage(message: any): any {
    const { FromUserName, ToUserName, Content } = message
    
    // 这里可以集成你的AI聊天功能
    return {
      type: 'text',
      touser: FromUserName,
      fromuser: ToUserName,
      content: `收到您的消息：${Content}，正在为您处理...`
    }
  }
  
  // 处理事件消息
  private static handleEventMessage(message: any): any {
    const { Event, FromUserName, ToUserName } = message
    
    switch (Event) {
      case 'subscribe':
        return {
          type: 'text',
          touser: FromUserName,
          fromuser: ToUserName,
          content: '欢迎关注孪数建筑行业规范检索查询智能体！我是您的智能知识库助手，可以为您提供专业的检索查询服务。'
        }
      case 'unsubscribe':
        return null // 用户取消关注，不需要回复
      case 'CLICK':
        return this.handleMenuClick(message)
      case 'VIEW':
        return this.handleMenuView(message)
      default:
        return null
    }
  }
  
  // 处理菜单点击事件
  private static handleMenuClick(message: any): any {
    const { FromUserName, ToUserName, EventKey } = message
    
    switch (EventKey) {
      case 'START_CHAT':
        return {
          type: 'text',
          touser: FromUserName,
          fromuser: ToUserName,
          content: '开始智能对话，请直接发送您的问题。'
        }
      case 'KNOWLEDGE_BASE':
        return {
          type: 'text',
          touser: FromUserName,
          fromuser: ToUserName,
          content: '知识库功能，请访问我们的网页版：https://your-domain.com'
        }
      case 'HELP':
        return {
          type: 'text',
          touser: FromUserName,
          fromuser: ToUserName,
          content: '使用帮助：\n1. 直接发送问题开始对话\n2. 点击菜单使用特定功能\n3. 访问网页版获得更好体验'
        }
      default:
        return null
    }
  }
  
  // 处理菜单跳转事件
  private static handleMenuView(message: any): any {
    // 菜单跳转不需要回复
    return null
  }
  
  // 处理其他类型消息
  private static handleImageMessage(message: any): any {
    return {
      type: 'text',
      touser: message.FromUserName,
      fromuser: message.ToUserName,
      content: '收到您的图片，目前暂不支持图片识别，请发送文字问题。'
    }
  }
  
  private static handleVoiceMessage(message: any): any {
    return {
      type: 'text',
      touser: message.FromUserName,
      fromuser: message.ToUserName,
      content: '收到您的语音，目前暂不支持语音识别，请发送文字问题。'
    }
  }
  
  private static handleVideoMessage(message: any): any {
    return {
      type: 'text',
      touser: message.FromUserName,
      fromuser: message.ToUserName,
      content: '收到您的视频，目前暂不支持视频处理，请发送文字问题。'
    }
  }
  
  private static handleLocationMessage(message: any): any {
    return {
      type: 'text',
      touser: message.FromUserName,
      fromuser: message.ToUserName,
      content: '收到您的位置信息，目前暂不支持位置相关功能，请发送文字问题。'
    }
  }
  
  private static handleLinkMessage(message: any): any {
    return {
      type: 'text',
      touser: message.FromUserName,
      fromuser: message.ToUserName,
      content: '收到您分享的链接，请直接发送您的问题。'
    }
  }
  
  private static handleUnknownMessage(message: any): any {
    return {
      type: 'text',
      touser: message.FromUserName,
      fromuser: message.ToUserName,
      content: '收到您的消息，请发送文字问题。'
    }
  }
}

// 微信小程序API封装（保持原有功能）
export class WeChatAPI {
  private static instance: WeChatAPI
  
  static getInstance(): WeChatAPI {
    if (!WeChatAPI.instance) {
      WeChatAPI.instance = new WeChatAPI()
    }
    return WeChatAPI.instance
  }
  
  // 获取用户信息
  async getUserInfo(): Promise<any> {
    if (!isWeChatMiniProgram()) {
      throw new Error('非微信小程序环境')
    }
    
    return new Promise((resolve, reject) => {
      (globalThis as any).wx.getUserInfo({
        success: (res: any) => resolve(res.userInfo),
        fail: reject
      })
    })
  }
  
  // 获取用户授权设置
  async getSetting(): Promise<any> {
    if (!isWeChatMiniProgram()) {
      throw new Error('非微信小程序环境')
    }
    
    return new Promise((resolve, reject) => {
      (globalThis as any).wx.getSetting({
        success: resolve,
        fail: reject
      })
    })
  }
  
  // 请求用户授权
  async authorize(scope: string): Promise<any> {
    if (!isWeChatMiniProgram()) {
      throw new Error('非微信小程序环境')
    }
    
    return new Promise((resolve, reject) => {
      (globalThis as any).wx.authorize({
        scope,
        success: resolve,
        fail: reject
      })
    })
  }
  
  // 显示加载提示
  showLoading(title: string = '加载中...'): void {
    if (isWeChatMiniProgram()) {
      (globalThis as any).wx.showLoading({ title })
    }
  }
  
  // 隐藏加载提示
  hideLoading(): void {
    if (isWeChatMiniProgram()) {
      (globalThis as any).wx.hideLoading()
    }
  }
  
  // 显示提示信息
  showToast(title: string, icon: 'success' | 'error' | 'loading' | 'none' = 'none'): void {
    if (isWeChatMiniProgram()) {
      (globalThis as any).wx.showToast({ title, icon })
    }
  }
  
  // 显示模态对话框
  showModal(title: string, content: string): Promise<boolean> {
    if (!isWeChatMiniProgram()) {
      return Promise.resolve(confirm(`${title}\n${content}`))
    }
    
    return new Promise((resolve) => {
      (globalThis as any).wx.showModal({
        title,
        content,
        success: (res: any) => resolve(res.confirm),
        fail: () => resolve(false)
      })
    })
  }
  
  // 网络请求
  async request(options: {
    url: string
    method?: 'GET' | 'POST' | 'PUT' | 'DELETE'
    data?: any
    header?: any
  }): Promise<any> {
    if (!isWeChatMiniProgram()) {
      // 在非小程序环境下使用fetch
      const response = await fetch(options.url, {
        method: options.method || 'GET',
        headers: {
          'Content-Type': 'application/json',
          ...options.header
        },
        body: options.data ? JSON.stringify(options.data) : undefined
      })
      return response.json()
    }
    
    return new Promise((resolve, reject) => {
      (globalThis as any).wx.request({
        url: options.url,
        method: options.method || 'GET',
        data: options.data,
        header: {
          'Content-Type': 'application/json',
          ...options.header
        },
        success: (res: any) => resolve(res.data),
        fail: reject
      })
    })
  }
  
  // 页面跳转
  navigateTo(url: string): void {
    if (isWeChatMiniProgram()) {
      (globalThis as any).wx.navigateTo({ url })
    } else {
      window.location.href = url
    }
  }
  
  // 页面重定向
  redirectTo(url: string): void {
    if (isWeChatMiniProgram()) {
      (globalThis as any).wx.redirectTo({ url })
    } else {
      window.location.replace(url)
    }
  }
  
  // 返回上一页
  navigateBack(delta: number = 1): void {
    if (isWeChatMiniProgram()) {
      (globalThis as any).wx.navigateBack({ delta })
    } else {
      window.history.back()
    }
  }
}

// 导出单例实例
export const wechatAPI = WeChatAPI.getInstance()
export const wechatOfficialAccountAPI = WeChatOfficialAccountAPI.getInstance()

// 微信小程序适配的存储工具
export class WeChatStorage {
  // 设置存储
  static set(key: string, value: any): void {
    if (isWeChatMiniProgram()) {
      (globalThis as any).wx.setStorageSync(key, value)
    } else {
      localStorage.setItem(key, JSON.stringify(value))
    }
  }
  
  // 获取存储
  static get(key: string): any {
    if (isWeChatMiniProgram()) {
      return (globalThis as any).wx.getStorageSync(key)
    } else {
      const value = localStorage.getItem(key)
      return value ? JSON.parse(value) : null
    }
  }
  
  // 删除存储
  static remove(key: string): void {
    if (isWeChatMiniProgram()) {
      (globalThis as any).wx.removeStorageSync(key)
    } else {
      localStorage.removeItem(key)
    }
  }
  
  // 清空存储
  static clear(): void {
    if (isWeChatMiniProgram()) {
      (globalThis as any).wx.clearStorageSync()
    } else {
      localStorage.clear()
    }
  }
}

// 微信小程序适配的HTTP客户端
export class WeChatHTTPClient {
  private baseURL: string
  private timeout: number
  
  constructor(baseURL: string = wechatConfig.api.baseUrl, timeout: number = wechatConfig.api.timeout) {
    this.baseURL = baseURL
    this.timeout = timeout
  }
  
  // 发送请求
  async request<T = any>(options: {
    url: string
    method?: 'GET' | 'POST' | 'PUT' | 'DELETE'
    data?: any
    headers?: Record<string, string>
  }): Promise<T> {
    const url = options.url.startsWith('http') ? options.url : `${this.baseURL}${options.url}`
    
    // 添加认证token
    const headers: Record<string, string> = {
      'Content-Type': 'application/json',
      ...options.headers
    }
    
    const token = WeChatStorage.get('token')
    if (token) {
      headers['Authorization'] = `Bearer ${token}`
    }
    
    try {
      const result = await wechatAPI.request({
        url,
        method: options.method || 'GET',
        data: options.data,
        header: headers
      })
      
      return result
    } catch (error) {
      console.error('HTTP请求失败:', error)
      throw error
    }
  }
  
  // GET请求
  async get<T = any>(url: string, params?: Record<string, any>): Promise<T> {
    const queryString = params ? `?${new URLSearchParams(params).toString()}` : ''
    return this.request<T>({ url: `${url}${queryString}`, method: 'GET' })
  }
  
  // POST请求
  async post<T = any>(url: string, data?: any): Promise<T> {
    return this.request<T>({ url, method: 'POST', data })
  }
  
  // PUT请求
  async put<T = any>(url: string, data?: any): Promise<T> {
    return this.request<T>({ url, method: 'PUT', data })
  }
  
  // DELETE请求
  async delete<T = any>(url: string): Promise<T> {
    return this.request<T>({ url, method: 'DELETE' })
  }
}

// 导出HTTP客户端实例
export const httpClient = new WeChatHTTPClient()
