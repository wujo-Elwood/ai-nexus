import { describe, expect, it } from 'vitest'
import { findMissingDefaults, restoreMissingDefaults } from './skillBindings'

const defaults = {
  'general-tool': ['search_city_location', 'get_weather'],
  'image-agent': ['generate_image']
}

describe('restoreMissingDefaults', () => {
  it('恢复被移除的默认技能并返回恢复明细', () => {
    const form = { 'general-tool': ['search_city_location'] }
    const restored = restoreMissingDefaults(form, defaults)
    expect(restored).toEqual([{ agentCode: 'general-tool', skillName: 'get_weather' }])
    expect(form['general-tool']).toEqual(['search_city_location', 'get_weather'])
  })

  it('全部完整时返回空数组且不改写表单', () => {
    const form = { 'general-tool': ['get_weather', 'search_city_location'] }
    expect(restoreMissingDefaults(form, defaults)).toEqual([])
    expect(form['general-tool']).toEqual(['get_weather', 'search_city_location'])
  })

  it('跳过没有默认绑定的智能体', () => {
    const form = { 'data-agent': [] }
    expect(restoreMissingDefaults(form, defaults)).toEqual([])
    expect(form['data-agent']).toEqual([])
  })

  it('多个智能体同时恢复', () => {
    const form = { 'general-tool': [], 'image-agent': [] }
    const restored = restoreMissingDefaults(form, defaults)
    expect(restored).toHaveLength(3)
    expect(form['general-tool']).toEqual(['search_city_location', 'get_weather'])
    expect(form['image-agent']).toEqual(['generate_image'])
  })
})

describe('findMissingDefaults', () => {
  it('列出缺失的默认技能', () => {
    expect(findMissingDefaults('image-agent', ['calculate'], defaults)).toEqual(['generate_image'])
  })

  it('保存列表完整时返回空数组', () => {
    expect(findMissingDefaults('image-agent', ['generate_image', 'calculate'], defaults)).toEqual([])
  })

  it('未知智能体视为无默认约束', () => {
    expect(findMissingDefaults('custom-agent', [], defaults)).toEqual([])
  })

  it('保存列表非法（非数组）时按全缺处理', () => {
    expect(findMissingDefaults('image-agent', null, defaults)).toEqual(['generate_image'])
  })
})
