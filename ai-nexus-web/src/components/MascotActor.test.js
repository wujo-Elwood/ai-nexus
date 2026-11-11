// @vitest-environment jsdom
import { describe, expect, it, beforeEach } from 'vitest'
import { mount } from '@vue/test-utils'

// jsdom 自带 localStorage/window/document；测试全程静音，避免碰音频实现
localStorage.setItem('mascot-sound', '0')

const { default: MascotActor } = await import('./MascotActor.vue')
const { mascot } = await import('../composables/useMascot')

describe('吉祥物交互组件', () => {
  beforeEach(() => {
    mascot.state.mode = 'idle'
    mascot.state.action = 'none'
    mascot.state.speak = ''
  })

  it('10 秒内单击 6 次 → 闹脾气蹲下不理人', async () => {
    const w = mount(MascotActor)
    for (let i = 0; i < 5; i++) await w.trigger('click')
    expect(mascot.state.action).not.toBe('sulk')   // 前 5 次只是待播轮换
    await w.trigger('click')   // 第 6 次：触发闹脾气
    expect(mascot.state.action).toBe('sulk')
    await new Promise(r => setTimeout(r, 600))   // 等打字机打完台词
    expect(mascot.state.speak).toBe('别戳啦，好痒！')
    w.unmount()
  })

  it('投喂不计入戳头次数：5 击 + 投喂 + 1 击不触发闹脾气', async () => {
    const w = mount(MascotActor)
    for (let i = 0; i < 5; i++) await w.trigger('click')
    await w.trigger('dblclick')   // 投喂：清零戳头计数
    await w.trigger('click')
    expect(mascot.state.action).not.toBe('sulk')
    expect(mascot.state.speak).not.toBe('别戳啦，好痒！')
    w.unmount()
  })

  it('从 hidden 现身的那一刻炸开三团登场烟雾', async () => {
    mascot.state.mode = 'hidden'
    mascot.state.x = 300
    mascot.state.y = 300
    const w = mount(MascotActor)
    expect(w.findAll('.poof').length).toBe(0)   // 还没现身
    mascot.state.mode = 'idle'   // 登场瞬间
    await w.vm.$nextTick()
    expect(w.findAll('.poof').length).toBe(3)
    w.unmount()
  })

  it('换装渲染对应 SVG 配件分组，便装时不渲染', async () => {
    const w = mount(MascotActor)
    mascot.state.outfit = 'glasses'
    await w.vm.$nextTick()
    expect(w.find('.outfit-glasses').exists()).toBe(true)
    mascot.state.outfit = 'shades'
    await w.vm.$nextTick()
    expect(w.find('.outfit-shades').exists()).toBe(true)
    mascot.state.outfit = 'crown'
    await w.vm.$nextTick()
    expect(w.find('.outfit-crown').exists()).toBe(true)
    expect(w.find('.outfit-glasses').exists()).toBe(false)
    expect(w.find('.outfit-shades').exists()).toBe(false)
    mascot.state.outfit = 'none'
    await w.vm.$nextTick()
    expect(w.find('.outfit-crown').exists()).toBe(false)
    w.unmount()
  })

  it('铭牌显示名字且说话时让位，奶牛花色只换颜色不加图案', async () => {
    const w = mount(MascotActor)
    expect(w.find('.mascot-nametag').exists()).toBe(true)
    expect(w.find('.mascot-nametag').text()).toBe(mascot.name.value)
    mascot.state.speak = '你好呀'
    await w.vm.$nextTick()
    expect(w.find('.mascot-nametag').exists()).toBe(false)
    mascot.state.speak = ''
    mascot.state.skin = 'cow'
    await w.vm.$nextTick()
    // 奶牛 = 与经典款完全同构，仅身体换色：不渲染任何额外图案分组
    expect(w.findAll('svg g').length).toBe(0)
    const body = w.find('.mascot-body rect').attributes('fill')
    expect(body).toBe('#f5f2ec')
    mascot.state.skin = 'classic'
    w.unmount()
  })
})
