import { fileURLToPath, URL } from 'node:url'

import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
// 已从node:url导入fileURLToPath
// https://vite.dev/config/
export default defineConfig({
  build: {
    sourcemap: false, // 关闭sourcemap，保护代码
    minify: 'terser', // 使用terser压缩，进一步保护代码
    terserOptions: {
      compress: {
        drop_console: true, // 移除console.log
        drop_debugger: true, // 移除debugger
      },
    },
  },
  server: {
    watch: {
      usePolling: true,
    },
    host: '0.0.0.0',
    port: 5173,
    strictPort: true,
    hmr: true,
    // 允许 iframe 嵌入，用于 PDF 预览
    headers: {
      'X-Frame-Options': 'SAMEORIGIN',
    },
    allowedHosts: [
      'localhost',
      '127.0.0.1',
      '.ngrok-free.app',
      '.ngrok-free.dev',
      '.ngrok.io',
      'ecobim.cn',
      '.ecobim.cn',
    ],
    proxy: {
      '/api': {
        target: 'http://backend:8080',
        changeOrigin: true,
        secure: false,
        ws: true,
      },
    },
  },

  plugins: [vue()],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url)),
    },
  },
})
