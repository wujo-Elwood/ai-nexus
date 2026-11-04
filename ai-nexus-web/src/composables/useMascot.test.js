import { describe, expect, it, beforeEach, afterEach, vi } from 'vitest'

// 监听函数会动态 import 的接口模块，统一 mock 掉（不依赖后端）
vi.mock('../api/tasks', () => ({ getTasks: vi.fn() }))
vi.mock('../api/health', () => ({ getSystemHealth: vi.fn() }))
import { getTasks } from '../api/tasks'
import { getSystemHealth } from '../api/health'

// watchTasks/watchHealth/speakSmart 已改为裸 fetch 静默轮询（不走 axios 拦截器），
// 这里 mock 全局 fetch：按 URL 分发响应体
function mockFetchByPath(handler) {
  vi.stubGlobal('fetch', vi.fn((path) => {
    const body = handler(String(path))
    return Promise.resolve({ ok: body != null, json: () => Promise.resolve(body) })
  }))
}

// useMascot 模块加载即读取 localStorage 里的开关，mock 必须先于动态 import 就位
class MemStorage {
  constructor() { this.map = new Map() }
  getItem(k) { return this.map.has(k) ? this.map.get(k) : null }
  setItem(k, v) { this.map.set(k, String(v)) }
  removeItem(k) { this.map.delete(k) }
  clear() { this.map.clear() }
}
global.localStorage = new MemStorage()

const { mascot } = await import('./useMascot')
// Vue 运行时在模块加载期会探测 document（node 环境下 undefined 走降级分支），
// 因此桩只能推迟到 import 完成后再挂，仅供 watchTasks/watchHealth 运行期读取 hidden
global.document = { hidden: false }

describe('吉祥物状态机', () => {
  beforeEach(() => {
    vi.useFakeTimers()
    localStorage.clear()
    localStorage.setItem('mascot-sound', '0')   // 测试全程静音，避免碰 AudioContext
    // 单例共享状态：清掉上一条用例的遗留（其复位定时器已随假时钟废弃，不会自行复位）
    mascot.state.mode = 'idle'
    mascot.state.action = 'none'
    mascot.state.speak = ''
    mascot.state.busy = false
    mascot.state.reading = false
  })
  afterEach(() => {
    vi.useRealTimers()
  })

  it('playAction 播放有效动作并到点自动复位', () => {
    expect(mascot.playAction('jump')).toBe(true)
    expect(mascot.state.action).toBe('jump')
    vi.advanceTimersByTime(1150)
    expect(mascot.state.action).toBe('none')
  })

  it('playAction 未知动作被忽略', () => {
    expect(mascot.playAction('not-an-action')).toBe(false)
    expect(mascot.state.action).toBe('none')
  })

  it('toggleSound 往返切换并持久化', () => {
    // beforeEach 已把 localStorage 置 0（静音）：第一次点击 = 从关到开
    mascot.toggleSound()
    expect(mascot.soundOn.value).toBe(true)
    expect(localStorage.getItem('mascot-sound')).toBe('1')
    mascot.toggleSound()
    expect(mascot.soundOn.value).toBe(false)
    expect(localStorage.getItem('mascot-sound')).toBe('0')
  })

  it('toggleChat 关闭时收回气泡并持久化', () => {
    expect(mascot.chatOn.value).toBe(true)
    mascot.speak('啦啦啦')
    vi.advanceTimersByTime(200)   // 打字机逐字显现完成
    expect(mascot.state.speak).toBe('啦啦啦')
    mascot.toggleChat()
    vi.advanceTimersByTime(400)   // 确认语同样逐字打出
    expect(mascot.chatOn.value).toBe(false)
    expect(localStorage.getItem('mascot-chat')).toBe('0')
    expect(mascot.state.speak).toBe('好的，我安静点～')
    mascot.toggleChat()
    expect(mascot.chatOn.value).toBe(true)
    expect(localStorage.getItem('mascot-chat')).toBe('1')
  })

  it('speak 打字机逐字显现，到点自动消失，关闭吉祥物时不冒泡', () => {
    mascot.speak('你好')
    vi.advanceTimersByTime(50)
    expect(mascot.state.speak).toBe('你')   // 首字先出
    vi.advanceTimersByTime(100)
    expect(mascot.state.speak).toBe('你好')   // 逐字补齐
    vi.advanceTimersByTime(3700)
    expect(mascot.state.speak).toBe('')

    mascot.toggle()   // 关闭 → hidden
    mascot.speak('看不见我')
    expect(mascot.state.speak).toBe('')
    mascot.toggle()   // 恢复开启
    expect(mascot.enabled.value).toBe(true)
  })

  it('rename 校验并持久化新名字', () => {
    expect(mascot.rename('豆豆')).toBe(true)
    expect(mascot.name.value).toBe('豆豆')
    expect(localStorage.getItem('mascot-name')).toBe('豆豆')
    expect(mascot.rename('')).toBe(false)
    expect(mascot.rename('   ')).toBe(false)
    expect(mascot.rename('九字九字九字九字九')).toBe(true)   // 超长截断到 8 字
    expect(mascot.name.value).toBe('九字九字九字九字')
  })

  it('sleep/wake 往返，已睡着时再睡是空操作', () => {
    mascot.state.mode = 'idle'
    mascot.sleep()
    expect(mascot.state.mode).toBe('sleeping')
    mascot.sleep()
    expect(mascot.state.mode).toBe('sleeping')
    mascot.wake()
    expect(mascot.state.mode).toBe('idle')
    mascot.wake()   // 非睡眠态惊醒是空操作
    expect(mascot.state.mode).toBe('idle')
  })

  it('cheer 每分钟最多 4 次', () => {
    let ok = 0
    for (let i = 0; i < 6; i++) {
      mascot.state.mode = 'idle'   // 强制回待机，绕开"动作中不接受"分支，专测节流
      mascot.cheer()
      if (mascot.state.mode === 'cheering') ok++
      vi.advanceTimersByTime(1400)   // 等这轮欢呼结束
    }
    expect(ok).toBe(4)
  })

  it('celebrate 冒彩纸和星星眼并到点收场', () => {
    mascot.celebrate()
    expect(mascot.state.confetti).toBe(true)
    expect(mascot.state.starEyes).toBe(true)
    vi.advanceTimersByTime(1900)
    expect(mascot.state.confetti).toBe(false)
    expect(mascot.state.starEyes).toBe(false)
    vi.advanceTimersByTime(2000)   // 翻跟头收尾复位
    expect(mascot.state.action).toBe('none')
  })

  it('toggle 隐藏/显示并持久化', () => {
    mascot.toggle()
    expect(mascot.enabled.value).toBe(false)
    expect(mascot.state.mode).toBe('hidden')
    expect(localStorage.getItem('mascot-enabled')).toBe('0')
    mascot.toggle()
    expect(mascot.enabled.value).toBe(true)
    expect(mascot.state.mode).toBe('idle')
    expect(localStorage.getItem('mascot-enabled')).toBe('1')
  })

  it('watchTasks：有任务戴帽开工，任务清零抛金币', async () => {
    mockFetchByPath((path) => path.includes('/api/tasks') ? { code: 200, data: [{}, {}] } : null)
    mascot.state.mode = 'idle'
    await mascot.watchTasks()
    expect(mascot.state.busy).toBe(true)
    vi.advanceTimersByTime(700)   // 等打字机打完台词（短于动作复位时长，避免误触发复位）
    expect(mascot.state.speak).toBe('有任务在跑，我来盯梢搬砖！')
    mockFetchByPath((path) => path.includes('/api/tasks') ? { code: 200, data: [] } : null)
    await mascot.watchTasks()
    expect(mascot.state.busy).toBe(false)
    expect(mascot.state.action).toBe('juggle')
    vi.advanceTimersByTime(700)
    expect(mascot.state.speak).toBe('任务跑完了，金币拿来！')
  })

  it('watchHealth：组件由好变坏那一刻才打电话', async () => {
    mockFetchByPath((path) => path.includes('/api/system-health') ? { code: 200, data: { components: [] } } : null)
    mascot.state.mode = 'idle'
    await mascot.watchHealth()
    expect(mascot.state.action).toBe('none')   // 首轮只登记基线，不触发
    mockFetchByPath((path) => path.includes('/api/system-health') ? { code: 200, data: { components: [{ status: 'DOWN' }] } } : null)
    await mascot.watchHealth()
    expect(mascot.state.action).toBe('call')
    vi.advanceTimersByTime(700)
    expect(mascot.state.speak).toBe('有组件出问题了，我打电话催修！')
  })

  it('watchHealth：无权限（403 空响应）静默跳过，不弹 toast 也不误报', async () => {
    mockFetchByPath(() => null)   // silentGet 对 !ok 返回 null
    mascot.state.mode = 'idle'
    await mascot.watchHealth()
    expect(mascot.state.action).toBe('none')
    expect(mascot.state.speak).toBe('')
  })

  it('startReading/stopReading 开关与守卫', () => {
    mascot.state.mode = 'idle'
    mascot.startReading()
    expect(mascot.state.reading).toBe(true)
    mascot.stopReading()
    expect(mascot.state.reading).toBe(false)
    mascot.toggle()   // 关闭吉祥物
    mascot.startReading()
    expect(mascot.state.reading).toBe(false)   // 关闭时不进入读回答
    mascot.toggle()
  })

  it('purr 在无音频环境的测试进程里不抛错', () => {
    expect(() => mascot.purr()).not.toThrow()
  })

  it('拖拽开始会挣扎喊放开我', () => {
    mascot.beginDrag()
    vi.advanceTimersByTime(3000)   // 等打字机打完台词
    expect(mascot.state.dragging).toBe(true)
    expect(mascot.state.speak).toBe('放开我！')
    mascot.state.dragging = false
  })

  it('番茄钟：启动报时，到点拉庆祝；再点可取消', () => {
    mascot.state.mode = 'idle'
    mascot.togglePomodoro()
    vi.advanceTimersByTime(3000)
    expect(mascot.pomoOn.value).toBe(true)
    expect(mascot.state.speak).toBe('好，陪你专注 25 分钟，别偷懒哦')
    // 精确推进到完工时刻（t=25min）：庆祝刚拉起、复位定时器尚在窗口外
    vi.advanceTimersByTime(25 * 60 * 1000 - 3000)
    expect(mascot.pomoOn.value).toBe(false)
    expect(mascot.state.confetti).toBe(true)
    vi.advanceTimersByTime(3000)
    expect(mascot.state.speak).toBe('番茄钟完成！休息 5 分钟，看看远处吧～')
    vi.advanceTimersByTime(1900)
    expect(mascot.state.confetti).toBe(false)

    mascot.togglePomodoro()
    expect(mascot.pomoOn.value).toBe(true)
    mascot.togglePomodoro()   // 中途取消
    vi.advanceTimersByTime(700)
    expect(mascot.pomoOn.value).toBe(false)
    expect(mascot.state.speak).toBe('番茄钟取消啦，随时再叫我')
  })

  it('applyMood 夹在 0~100 并持久化', () => {
    mascot.applyMood(-500)
    expect(mascot.mood.value).toBe(0)
    expect(localStorage.getItem('mascot-mood')).toBe('0')
    mascot.applyMood(500)
    expect(mascot.mood.value).toBe(100)
    mascot.applyMood(-40)
    expect(mascot.mood.value).toBe(60)
    expect(localStorage.getItem('mascot-mood')).toBe('60')
  })

  it('心情钩子：成就 +10、投喂 +6', () => {
    mascot.applyMood(-500)   // 归零
    mascot.celebrate()
    expect(mascot.mood.value).toBe(10)
    mascot.noteFed()
    expect(mascot.mood.value).toBe(16)
  })
})
