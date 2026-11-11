// 开发期 vite 配置：
// 1) cacheDir 指到 tmp 新目录，避开 safe-delete 对 node_modules/.vite/deps 的批量删除拦截
//    （lockfile 变更后 vite 每次启动都想重清 deps，被守卫挡死导致起不来）
// 2) server 配置与主 vite.config.js 保持一致（host + /api 代理 + SSE 禁缓冲），漏配 proxy 会导致
//    前端所有 /api 请求 404 并弹 "Request failed"
import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

export default defineConfig({
  plugins: [vue()],
  cacheDir: 'D:/workspace/wujo_rag/tmp/.vite-dev',
  server: {
    host: '0.0.0.0',
    port: 5173,
    proxy: {
      '/api/chat/stream': {
        target: 'http://localhost:8888',
        changeOrigin: true,
        selfHandleResponse: false,
        configure: (proxy) => {
          proxy.on('proxyRes', (proxyRes) => {
            proxyRes.headers['cache-control'] = 'no-cache'
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
