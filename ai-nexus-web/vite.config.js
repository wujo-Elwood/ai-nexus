import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

export default defineConfig({
  plugins: [vue()],
  server: {
    host: '0.0.0.0',
    port: 5173,
    hmr: {
      host: '127.0.0.1',
      port: 5173
    },
    proxy: {
      '/api/chat/stream': {
        target: 'http://localhost:8888',
        changeOrigin: true,
        selfHandleResponse: false,
        configure: (proxy) => {
          // 第1步：代理收到后端响应时补充禁用缓冲的响应头
          proxy.on('proxyRes', (proxyRes) => {
            // 第2步：告诉浏览器和中间层不要缓存 SSE
            proxyRes.headers['cache-control'] = 'no-cache'
            // 第3步：告诉反向代理不要缓冲 SSE
            proxyRes.headers['x-accel-buffering'] = 'no'
          })
        }
      },
      '/api': {
        target: 'http://localhost:8888',
        changeOrigin: true
      }
    }
  }
})
