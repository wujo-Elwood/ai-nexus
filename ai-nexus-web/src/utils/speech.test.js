import { describe, expect, it, beforeEach, afterEach, vi } from 'vitest'
import { stripMarkdownForSpeech, isSTTSupported, isTTSSupported, speak, stopSpeaking } from './speech'

describe('stripMarkdownForSpeech', () => {
  it('普通文本原样保留', () => {
    expect(stripMarkdownForSpeech('你好，有什么可以帮你？')).toBe('你好，有什么可以帮你？')
  })

  it('代码块读成（代码略）', () => {
    const out = stripMarkdownForSpeech('用法如下：\n```js\nconsole.log(1)\n```')
    expect(out).toContain('（代码略）')
    expect(out).not.toContain('console')
  })

  it('行内代码只保留内容', () => {
    expect(stripMarkdownForSpeech('运行 `npm run dev` 启动')).toBe('运行 npm run dev 启动')
  })

  it('链接只保留文字', () => {
    expect(stripMarkdownForSpeech('参考 [官方文档](https://example.com) 说明')).toBe('参考 官方文档 说明')
  })

  it('去掉标题、列表符号和表格结构', () => {
    const out = stripMarkdownForSpeech('## 第 1 页\n\n- 营收增长 20%\n\n| 指标 | 数值 |\n| --- | --- |\n| 营收 | 1.2 亿 |')
    expect(out).not.toContain('#')
    expect(out).not.toContain('- ')
    expect(out).not.toContain('|')
    expect(out).not.toContain('---')
    expect(out).toContain('营收增长 20%')
    expect(out).toContain('1.2 亿')
  })

  it('不引入与原文标点叠加的句号', () => {
    const out = stripMarkdownForSpeech('第一段结束.\n\n第二段开始')
    expect(out).not.toContain('.。')
    expect(out).toBe('第一段结束. 第二段开始')
  })

  it('空值返回空字符串', () => {
    expect(stripMarkdownForSpeech('')).toBe('')
    expect(stripMarkdownForSpeech(null)).toBe('')
  })
})

describe('能力检测', () => {
  it('jsdom 下 STT/TTS 能力按真实环境返回布尔值', () => {
    expect(typeof isSTTSupported()).toBe('boolean')
    expect(typeof isTTSSupported()).toBe('boolean')
  })
})

describe('朗读与停止', () => {
  beforeEach(() => {
    // vitest 默认 node 环境无 window 与 Utterance 构造器，一并桩掉
    vi.stubGlobal('SpeechSynthesisUtterance', class {
      constructor(text) {
        this.text = text
      }
    })
    vi.stubGlobal('window', {
      speechSynthesis: {
        cancel: vi.fn(),
        // 提交话语后异步触发 onend，模拟朗读自然结束
        speak: vi.fn((utterance) => setTimeout(() => utterance.onend && utterance.onend(), 0)),
        speaking: false,
        pending: false,
        getVoices: vi.fn(() => [])
      }
    })
  })

  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('不支持朗读时 speak 直接完成不抛错', async () => {
    vi.stubGlobal('window', {})
    await expect(speak('你好')).resolves.toBeUndefined()
  })

  it('speak 取消上一次朗读并向 synthesis 提交新话语', async () => {
    await speak('测试朗读')
    expect(window.speechSynthesis.cancel).toHaveBeenCalled()
    expect(window.speechSynthesis.speak).toHaveBeenCalledTimes(1)
    // 提交的话语是清洗后的文本
    const utterance = window.speechSynthesis.speak.mock.calls[0][0]
    expect(utterance.text).toBe('测试朗读')
    expect(utterance.lang).toBe('zh-CN')
  })

  it('stopSpeaking 调用 cancel', () => {
    stopSpeaking()
    expect(window.speechSynthesis.cancel).toHaveBeenCalled()
  })
})
