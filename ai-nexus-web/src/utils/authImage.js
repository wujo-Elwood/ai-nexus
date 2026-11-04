/**
 * 带鉴权的图片加载工具
 * 统一用 Authorization 头拉取受保护的图片并转为 object URL，
 * 避免 JWT 出现在 URL 查询串（会被浏览器历史/访问日志/Referer 记录）。
 * 所有调用共享一个缓存：同一路径只真正请求一次，重复取用直接复用 object URL。
 */
import request from './request'
import { reactive } from 'vue'

const cache = new Map()   // path → objectURL（含进行中的 Promise 防并发重复请求）

/** 响应式镜像：path → objectURL | ''（加载中）。供模板 :src 同步绑定，加载完成自动触发重渲染 */
export const authedImageSrc = reactive(new Map())

/** 模板专用：按需触发加载并返回当前可用的 src（未就绪时为空串） */
export function authedSrcOf(path) {
  if (!authedImageSrc.has(path)) {
    authedImageSrc.set(path, '')
    loadAuthedImage(path)
      .then((url) => authedImageSrc.set(path, url))
      .catch(() => authedImageSrc.delete(path))
  }
  return authedImageSrc.get(path) || ''
}

/**
 * 获取受保护图片的 object URL（带缓存）
 * @param {string} path 相对路径，如 /api/image/history/47/view
 * @returns {Promise<string>} blob object URL
 */
export function loadAuthedImage(path) {
  if (cache.has(path)) {
    return cache.get(path)
  }
  const pending = request
    .get(path, { responseType: 'blob' })
    .then((response) => {
      const url = URL.createObjectURL(response.data)
      cache.set(path, url)
      return url
    })
    .catch((error) => {
      cache.delete(path)   // 失败允许重试
      throw error
    })
  cache.set(path, pending)
  return pending
}

/** 下载受保护文件：拉取 blob 后触发浏览器下载 */
export async function downloadAuthedFile(path, filename) {
  const response = await request.get(path, { responseType: 'blob' })
  const url = URL.createObjectURL(response.data)
  const anchor = document.createElement('a')
  anchor.href = url
  anchor.download = filename || ''
  document.body.appendChild(anchor)
  anchor.click()
  anchor.remove()
  setTimeout(() => URL.revokeObjectURL(url), 30_000)
}

/** 新标签页打开受保护图片：拉取 blob 后用 object URL 打开 */
export async function openAuthedImage(path) {
  const url = await loadAuthedImage(path)
  window.open(url, '_blank')
}
