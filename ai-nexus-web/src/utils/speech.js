// 语音输入与朗读：基于浏览器 Web Speech API
// 能力检测独立暴露，调用方据此决定显示/隐藏入口与降级提示；
// Chrome 的语音识别依赖谷歌服务（国内网络可能不可用），失败时通过 onError 明确告知原因

/** 浏览器是否支持语音识别（语音转文字） */
export function isSTTSupported() {
  return typeof window !== 'undefined' && Boolean(window.SpeechRecognition || window.webkitSpeechRecognition)
}

/** 浏览器是否支持语音合成（朗读） */
export function isTTSSupported() {
  return typeof window !== 'undefined' && Boolean(window.speechSynthesis)
}

/**
 * 创建语音听写实例
 * @param {Object} handlers { onText(全文), onError(中文原因), onStop() }
 *   onText 收到"已有文本 + 本轮识别结果"的合并全文，由调用方写入输入框
 * @returns {{ start: Function, stop: Function }}
 */
export function createDictation({ onText, onError, onStop } = {}) {
  const Ctor = window.SpeechRecognition || window.webkitSpeechRecognition
  if (!Ctor) {
    return { start: () => onError && onError('当前浏览器不支持语音识别'), stop: () => {} }
  }
  const recognition = new Ctor()
  recognition.lang = 'zh-CN'
  recognition.interimResults = true
  recognition.continuous = true
  // 已确认的最终文本沉淀在 baseText，中间结果实时叠加显示
  let baseText = ''
  let listening = false

  recognition.onresult = (event) => {
    let interim = ''
    for (let i = event.resultIndex; i < event.results.length; i++) {
      const text = event.results[i][0].transcript
      if (event.results[i].isFinal) {
        baseText += text
      } else {
        interim += text
      }
    }
    if (onText) {
      onText((baseText + interim).trim())
    }
  }
  recognition.onerror = (event) => {
    const reasons = {
      'not-allowed': '麦克风权限被拒绝，请在浏览器地址栏允许麦克风后重试',
      'service-not-allowed': '语音识别服务不可用（Chrome 需访问谷歌服务，国内网络可能受限，可改用 Edge 浏览器）',
      network: '语音识别服务网络不可达（Chrome 需访问谷歌服务，可改用 Edge 浏览器）',
      'no-speech': '没有听到说话',
      'audio-capture': '未检测到麦克风设备'
    }
    if (onError) {
      onError(reasons[event.error] || '语音识别出错：' + event.error)
    }
  }
  recognition.onend = () => {
    // 浏览器可能在静音后自动结束，统一回调让调用方复位按钮状态
    listening = false
    if (onStop) {
      onStop()
    }
  }

  return {
    start() {
      if (listening) return
      baseText = ''
      listening = true
      recognition.start()
    },
    stop() {
      if (!listening) return
      listening = false
      try {
        recognition.stop()
      } catch (error) {
        // 已停止时 stop 可能抛错，忽略
      }
    }
  }
}

/** 朗读前清洗 Markdown：去掉代码块、表格结构、链接和标记符号，只留可读文字 */
export function stripMarkdownForSpeech(text) {
  if (!text) return ''
  return text
    .replace(/```[\s\S]*?```/g, '（代码略）')
    .replace(/`([^`]*)`/g, '$1')
    .replace(/!?\[([^\]]*)]\([^)]*\)/g, '$1')
    .replace(/^\s{0,3}#{1,6}\s+/gm, '')
    .replace(/^\s*[-*]\s+/gm, '')
    // 表格分隔行等纯符号行整行去掉，避免读出"横线横线"
    .replace(/^[\s|:-]+$/gm, '')
    .replace(/\|/g, ' ')
    .replace(/[*_~>#]+/g, '')
    // 段落间隔折成空格：不人为加句号，避免与原有标点叠加
    .replace(/\n{2,}/g, ' ')
    .replace(/\s+/g, ' ')
    .trim()
}

/**
 * 朗读一段文本；同一时间只允许一段朗读，重复调用会打断上一段
 * @returns Promise，朗读自然结束或被停止时 resolve
 */
export function speak(text, { lang = 'zh-CN', rate = 1 } = {}) {
  return new Promise((resolve) => {
    if (!isTTSSupported()) {
      resolve()
      return
    }
    window.speechSynthesis.cancel()
    const utterance = new SpeechSynthesisUtterance(text)
    utterance.lang = lang
    utterance.rate = rate
    const voice = window.speechSynthesis.getVoices().find(v => v.lang && v.lang.startsWith('zh'))
    if (voice) {
      utterance.voice = voice
    }
    utterance.onend = () => resolve()
    utterance.onerror = () => resolve()
    window.speechSynthesis.speak(utterance)
  })
}

/** 停止全部朗读 */
export function stopSpeaking() {
  if (isTTSSupported()) {
    window.speechSynthesis.cancel()
  }
}

/** 当前是否正在朗读 */
export function isSpeaking() {
  return isTTSSupported() && window.speechSynthesis.speaking
}
