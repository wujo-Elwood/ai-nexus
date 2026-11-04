import { describe, expect, it, vi } from 'vitest'

// init/dispose 生命周期用例：确认全局监听与定时器成对收放（登出后不残留）
vi.mock('element-plus', () => ({
  ElMessage: Object.assign(() => {}, { success: () => {}, error: () => {} }),
  ElMessageBox: { confirm: () => {} }
}))

class MemStorage {
  constructor() { this.map = new Map() }
  getItem(k) { return this.map.has(k) ? this.map.get(k) : null }
  setItem(k, v) { this.map.set(k, String(v)) }
  removeItem(k) { this.map.delete(k) }
  clear() { this.map.clear() }
}
global.localStorage = new MemStorage()
localStorage.setItem('mascot-sound', '0')

function makeTarget() {
  return {
    addEventListener: vi.fn(),
    removeEventListener: vi.fn()
  }
}

const win = makeTarget()
win.matchMedia = () => ({ matches: false })
win.innerWidth = 1440
win.innerHeight = 900
win.setTimeout = setTimeout
win.clearTimeout = clearTimeout
win.setInterval = setInterval
win.clearInterval = clearInterval

const doc = makeTarget()
doc.hidden = false
doc.body = { nodeType: 1 }
doc.querySelector = () => null
doc.querySelectorAll = () => []

global.window = win
global.MutationObserver = class {
  observe() {}
  disconnect() { this.closed = true }
}
global.fetch = vi.fn(() => Promise.resolve({ ok: false, json: () => Promise.resolve(null) }))

// Vue 运行时在模块加载期就会探测 document，桩要等 import 完成后再挂
const { mascot } = await import('./useMascot')
global.document = doc

describe('吉祥物 init/dispose 生命周期', () => {
  it('init 注册的监听在 dispose 后逐条解绑，重复 init 不重复注册', () => {
    mascot.init()
    mascot.init()   // 第二次应为空操作
    const added = win.addEventListener.mock.calls.length + doc.addEventListener.mock.calls.length
    expect(added).toBeGreaterThan(5)

    mascot.dispose()
    const removed = win.removeEventListener.mock.calls.length + doc.removeEventListener.mock.calls.length
    expect(removed).toBe(added)

    // dispose 之后可以再次 init（登出后再登录）
    mascot.init()
    expect(win.addEventListener.mock.calls.length + doc.addEventListener.mock.calls.length).toBe(added * 2)
    mascot.dispose()
  })
})
