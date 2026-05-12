/**
 * 设备检测工具
 * 用于判断当前设备类型和屏幕尺寸
 */

// 检测是否为移动端设备
export const isMobileDevice = (): boolean => {
  // 检查用户代理
  const userAgent = navigator.userAgent.toLowerCase()
  const mobileKeywords = [
    'android', 'iphone', 'ipad', 'ipod', 'blackberry', 
    'windows phone', 'mobile', 'tablet'
  ]
  
  const isMobileByUserAgent = mobileKeywords.some(keyword => 
    userAgent.includes(keyword)
  )
  
  // 检查屏幕尺寸
  const isMobileByScreen = window.innerWidth <= 768
  
  // 检查触摸支持
  const hasTouchSupport = 'ontouchstart' in window || navigator.maxTouchPoints > 0
  
  return isMobileByUserAgent || isMobileByScreen || hasTouchSupport
}

// 检测是否为平板设备
export const isTabletDevice = (): boolean => {
  const userAgent = navigator.userAgent.toLowerCase()
  const tabletKeywords = ['ipad', 'tablet']
  
  const isTabletByUserAgent = tabletKeywords.some(keyword => 
    userAgent.includes(keyword)
  )
  
  const isTabletByScreen = window.innerWidth > 768 && window.innerWidth <= 1024
  
  return isTabletByUserAgent || isTabletByScreen
}

// 检测是否为小屏幕设备
export const isSmallScreen = (): boolean => {
  return window.innerWidth <= 480
}

// 检测是否为中等屏幕设备
export const isMediumScreen = (): boolean => {
  return window.innerWidth > 480 && window.innerWidth <= 768
}

// 检测是否为大屏幕设备
export const isLargeScreen = (): boolean => {
  return window.innerWidth > 768
}

// 获取设备类型
export const getDeviceType = (): 'mobile' | 'tablet' | 'desktop' => {
  if (isMobileDevice()) {
    return 'mobile'
  } else if (isTabletDevice()) {
    return 'tablet'
  } else {
    return 'desktop'
  }
}

// 获取屏幕尺寸信息
export const getScreenInfo = () => {
  return {
    width: window.innerWidth,
    height: window.innerHeight,
    devicePixelRatio: window.devicePixelRatio,
    orientation: window.screen.orientation?.type || 'unknown'
  }
}

// 监听屏幕尺寸变化
export const onScreenResize = (callback: () => void) => {
  window.addEventListener('resize', callback)
  
  // 返回清理函数
  return () => {
    window.removeEventListener('resize', callback)
  }
}

// 检测是否为微信浏览器
export const isWeChatBrowser = (): boolean => {
  const userAgent = navigator.userAgent.toLowerCase()
  return userAgent.includes('micromessenger')
}

// 检测是否为微信小程序环境
export const isWeChatMiniProgram = (): boolean => {
  // 微信小程序环境检测
  return typeof (globalThis as any).wx !== 'undefined' && (globalThis as any).wx.miniProgram
}

// 检测是否为PWA环境
export const isPWA = (): boolean => {
  return window.matchMedia('(display-mode: standalone)').matches ||
         (window.navigator as any).standalone === true
}
