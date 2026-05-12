/**
 * 认证相关工具函数
 */

// 获取认证token
export const getAuthToken = (): string => {
  const token = localStorage.getItem('token')
  if (!token) {
    console.warn('未找到JWT token')
    return ''
  }
  return token
}

// 检查是否已登录
export const isAuthenticated = (): boolean => {
  return !!localStorage.getItem('token')
}

// 清除认证信息
export const clearAuth = (): void => {
  localStorage.removeItem('token')
}

// 设置认证token
export const setAuthToken = (token: string): void => {
  localStorage.setItem('token', token)
}
