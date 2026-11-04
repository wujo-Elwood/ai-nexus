<template>
  <div v-if="visible" :class="['boot-screen', closing ? 'boot-closing' : '']" @click="finish">
    <div class="boot-scanlines"></div>
    <div class="boot-sweep"></div>
    <div class="boot-vignette"></div>
    <pre class="boot-log">{{ logText }}<span class="boot-cursor">█</span></pre>
    <pre v-if="showLogo" class="boot-logo">{{ LOGO_ART }}</pre>
    <div v-if="showEnter" class="boot-enter">SYSTEM READY · 按任意键 / 点击进入</div>
  </div>
</template>

<script setup>
import { onBeforeUnmount, onMounted, ref } from 'vue'

const LOGO_ART = [
  '██╗    ██╗ ██╗   ██╗      ██╗  ██████╗  █████╗  ██████╗ ',
  '██║    ██║ ██║   ██║      ██║ ██╔════╝ ██╔══██╗ ██╔════╝ ',
  '██║ █╗ ██║ ██║   ██║      ██║ ██║  ███╗███████║ ██║  ███╗',
  '██║███╗██║ ██║   ██║ ██   ██║ ██║   ██║██╔══██║ ██║   ██║',
  '╚███╔███╔╝ ╚██████╔╝ ██ ╚██╔╝ ╚██████╔╝██║  ██║ ╚██████╔╝',
  ' ╚══╝╚══╝   ╚═════╝   ╚═══╝   ╚═════╝ ╚═╝  ╚═╝  ╚═════╝ '
].join('\n')

const BOOT_LINES = [
  { text: 'AI NEXUS BIOS v3.2.1 — NEURAL SYSTEM CHECK', delay: 120 },
  { text: '', delay: 60 },
  { text: 'CPU0 : QUANTUM CORE @ 8.8 GHZ .......... OK', delay: 90 },
  { text: 'MEMORY TEST : 262144K .................. OK', delay: 90 },
  { text: 'DETECTING GPU : RTX-COSMOS 24G ......... OK', delay: 90 },
  { text: 'MOUNT /dev/qdrant ..................... OK', delay: 80 },
  { text: 'MOUNT /dev/knowledge .................. OK', delay: 80 },
  { text: 'LOADING EMBEDDING ENGINE .............. OK', delay: 90 },
  { text: 'LOADING PARTICLE ENGINE ............... OK', delay: 80 },
  { text: 'CALIBRATING NEON GRID ................. OK', delay: 80 },
  { text: 'SYNCING STARFIELD ..................... OK', delay: 80 },
  { text: 'ESTABLISHING UPLINK ................... OK', delay: 100 },
  { text: '', delay: 60 },
  { text: 'BOOT SEQUENCE COMPLETE', delay: 260 }
]

const visible = ref(false)
const closing = ref(false)
const logText = ref('')
const showLogo = ref(false)
const showEnter = ref(false)

let timers = []
let finished = false

onMounted(() => {
  // 每个会话只播放一次
  if (sessionStorage.getItem('wjo_boot_done')) {
    return
  }
  visible.value = true
  document.body.style.overflow = 'hidden'
  window.addEventListener('keydown', handleSkip, true)
  startSequence()
})

onBeforeUnmount(() => {
  timers.forEach(clearTimeout)
  window.removeEventListener('keydown', handleSkip, true)
  document.body.style.overflow = ''
})

function schedule(fn, delay) {
  timers.push(setTimeout(fn, delay))
}

function startSequence() {
  let acc = 350
  for (const line of BOOT_LINES) {
    schedule(() => {
      logText.value += line.text + '\n'
    }, acc)
    acc += line.delay
  }
  schedule(() => {
    showLogo.value = true
  }, acc)
  schedule(() => {
    showEnter.value = true
  }, acc + 900)
  schedule(() => {
    finish()
  }, acc + 2400)
}

// 点击或按键直接完成启动
function handleSkip() {
  if (!visible.value || finished) {
    return
  }
  finish()
}

function finish() {
  if (finished) {
    return
  }
  finished = true
  sessionStorage.setItem('wjo_boot_done', '1')
  // 先清掉启动序列的定时器，再安排淡出后卸载——不依赖 transitionend，任何环境下都会消失
  timers.forEach(clearTimeout)
  timers = []
  closing.value = true
  schedule(() => {
    visible.value = false
    document.body.style.overflow = ''
  }, 640)
}
</script>

<style scoped>
.boot-screen {
  position: fixed;
  inset: 0;
  z-index: 9999;
  overflow: hidden;
  background: radial-gradient(ellipse 90% 70% at 50% 45%, #06120a 0%, #020503 70%, #000 100%);
  cursor: pointer;
}

.boot-scanlines {
  position: absolute;
  inset: 0;
  pointer-events: none;
  background: repeating-linear-gradient(
    0deg,
    rgba(0, 0, 0, 0.32) 0 1px,
    transparent 1px 3px
  );
  animation: bootFlicker 0.18s steps(2) infinite;
}

.boot-sweep {
  position: absolute;
  left: 0;
  width: 100%;
  height: 120px;
  pointer-events: none;
  background: linear-gradient(
    180deg,
    transparent 0%,
    rgba(110, 255, 160, 0.05) 45%,
    rgba(110, 255, 160, 0.09) 50%,
    transparent 100%
  );
  animation: bootSweep 2.6s linear infinite;
}

.boot-vignette {
  position: absolute;
  inset: 0;
  pointer-events: none;
  background: radial-gradient(ellipse 76% 66% at 50% 50%, transparent 55%, rgba(0, 0, 0, 0.72) 100%);
}

.boot-log {
  position: absolute;
  top: 5%;
  left: 6%;
  margin: 0;
  color: #4dff88;
  font-family: 'Consolas', 'Courier New', monospace;
  font-size: 13px;
  line-height: 1.65;
  white-space: pre-wrap;
  text-shadow: 0 0 8px rgba(77, 255, 136, 0.55);
}

.boot-cursor {
  animation: bootCursor 0.7s steps(1) infinite;
}

.boot-logo {
  position: absolute;
  top: 32%;
  left: 50%;
  transform: translateX(-50%);
  margin: 0;
  color: #7dffb2;
  font-family: 'Consolas', 'Courier New', monospace;
  font-size: clamp(8px, 1.15vw, 15px);
  line-height: 1.3;
  white-space: pre;
  text-shadow:
    0 0 10px rgba(125, 255, 178, 0.8),
    0 0 32px rgba(77, 255, 136, 0.5);
}

.boot-enter {
  position: absolute;
  bottom: 12%;
  left: 50%;
  transform: translateX(-50%);
  color: rgba(125, 255, 178, 0.85);
  font-family: 'Consolas', 'Courier New', monospace;
  font-size: 14px;
  letter-spacing: 0.18em;
  text-shadow: 0 0 12px rgba(77, 255, 136, 0.6);
  animation: bootBlink 1.1s ease-in-out infinite;
}

.boot-screen.boot-closing {
  animation: bootOut 0.64s ease forwards;
  pointer-events: none;
}

.boot-logo {
  animation: bootLogoIn 0.7s ease forwards;
}

.boot-fade-leave-active {
  display: none;
}

@keyframes bootOut {
  to {
    opacity: 0;
    filter: brightness(2.4) contrast(1.4);
  }
}

@keyframes bootLogoIn {
  from {
    opacity: 0;
    filter: blur(6px) brightness(2.8);
  }
  to {
    opacity: 1;
    filter: blur(0) brightness(1);
  }
}

@keyframes bootFlicker {
  0% { opacity: 1; }
  50% { opacity: 0.93; }
  100% { opacity: 1; }
}

@keyframes bootSweep {
  0% { top: -15%; }
  100% { top: 105%; }
}

@keyframes bootCursor {
  0%, 49% { opacity: 1; }
  50%, 100% { opacity: 0; }
}

@keyframes bootBlink {
  0%, 100% { opacity: 0.9; }
  50% { opacity: 0.35; }
}

@media (max-width: 920px) {
  .boot-log {
    font-size: 11px;
  }
}
</style>
