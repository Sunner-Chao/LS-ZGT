import './assets/main.css'
import './assets/styles/modern.css'
import ElementPlus from 'element-plus'
import 'element-plus/dist/index.css'
import { createApp } from 'vue'
import { createPinia } from 'pinia'
import App from './App.vue'
import router from './router'
import axios from 'axios';

axios.interceptors.request.use(config => {
  const token = localStorage.getItem('token');
  if (token) {
    config.headers = config.headers || {};
    config.headers['Authorization'] = 'Bearer ' + token;
  }
  return config;
});

// 添加响应拦截器处理401错误
axios.interceptors.response.use(
  response => response,
  error => {
    if (error.response?.status === 401) {
      console.warn('Token已过期，清除本地存储并跳转到登录页面');
      localStorage.removeItem('token');
      // 避免在登录页面重复跳转
      if (window.location.pathname !== '/login') {
        window.location.href = '/login';
      }
    }
    return Promise.reject(error);
  }
);

// 删除全局 style 注入，避免覆盖自定义样式
// const style = document.createElement('style')
// style.textContent = `
//   html, body, #app {
//     height: 100%;
//     width: 100%;
//     margin: 0;
//     padding: 0;
//     overflow: hidden;
//   }
// `
// document.head.appendChild(style)

const app = createApp(App)
app.use(createPinia())
app.use(router)
app.use(ElementPlus)
app.mount('#app')

// Vue 全局错误处理器：记录到 localStorage 便于离线分析，并更新全局 debug 面板
try {
  app.config.errorHandler = (err: unknown, vm: any, info: string) => {
    try {
      const stack = (err && (err as any).stack) ? (err as any).stack : String(err)
      const payload = { time: new Date().toISOString(), message: String(err), stack, info }
      try { localStorage.setItem('luanshu_last_error', JSON.stringify(payload)) } catch (e) {}
      try { const dbg = (window as any).__luanshu_debug; if (dbg && dbg.update) dbg.update({ lastError: stack, info }) } catch (e) {}
      console.error('[Vue ErrorHandler]', info, err)
    } catch (e) { console.error('[Vue ErrorHandler] fail to record', e) }
  }
} catch (e) {}

// 全局错误与未处理 Promise 拦截，用于客户端定位问题
window.addEventListener('error', (event) => {
  try {
    console.error('[Global Error] message:', (event as ErrorEvent).message, 'error:', (event as ErrorEvent).error);
  } catch (e) {
    console.error('[Global Error] unknown event:', event, e);
  }
});

window.addEventListener('unhandledrejection', (event) => {
  try {
    console.error('[UnhandledRejection] reason:', (event as PromiseRejectionEvent).reason);
  } catch (e) {
    console.error('[UnhandledRejection] unknown event:', event, e);
  }
});

// 兼容性的全局捕获（window.onerror / onunhandledrejection）以确保记录到 localStorage
try {
  window.onerror = function (message, source, lineno, colno, error) {
    try {
      const stack = error && error.stack ? error.stack : `${message} at ${source}:${lineno}:${colno}`
      const payload = { time: new Date().toISOString(), message: String(message), stack }
      try { localStorage.setItem('luanshu_last_error', JSON.stringify(payload)) } catch (e) {}
      try { const dbg = (window as any).__luanshu_debug; if (dbg && dbg.update) dbg.update({ lastError: stack }) } catch (e) {}
    } catch (e) {}
    return false
  }
  window.onunhandledrejection = function (ev: any) {
    try {
      const reason = ev && ev.reason ? (ev.reason.stack || ev.reason.toString()) : String(ev)
      const payload = { time: new Date().toISOString(), reason }
      try { localStorage.setItem('luanshu_last_rejection', JSON.stringify(payload)) } catch (e) {}
      try { const dbg = (window as any).__luanshu_debug; if (dbg && dbg.update) dbg.update({ lastError: reason }) } catch (e) {}
    } catch (e) {}
  }
} catch (e) {}

console.log('[Frontend Debug] 应用已挂载，开始监听全局错误事件');

// Initialize a no-op global debug interface to avoid inserting DOM elements.
try {
  if (typeof window !== 'undefined' && !(window as any).__luanshu_debug) {
    (window as any).__luanshu_debug = { update: (_: any) => {}, force_text: false }
  }
} catch (e) {
  try { console.error('[LUANSHU DEBUG] init no-op debug failed', e) } catch {}
}
