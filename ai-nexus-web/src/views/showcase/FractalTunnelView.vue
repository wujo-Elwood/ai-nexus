<template>
  <div ref="mountRef" class="tunnel-view">
    <canvas ref="canvasRef" class="tv-canvas"></canvas>

    <div class="tv-header">
      <span class="tv-eyebrow">Fractal Tunnel</span>
      <h1 class="tv-title">无限分形隧道</h1>
      <p class="tv-desc">
        {{ audioMode === 'none' ? '拖拽环顾 · 滚轮调速 · 无音频时自动进入氛围模式' : '隧道随节奏穿梭 · 低音提速、高音增辉' }}
      </p>
    </div>

    <div class="tv-toolbar">
      <button type="button" class="tv-button" :class="{ active: audioMode === 'mic' }" @click="toggleMic">
        <span class="tv-button-key">音频</span>
        <span>{{ audioMode === 'mic' ? '麦克风驱动中' : '接麦克风' }}</span>
      </button>
      <button type="button" class="tv-button" :class="{ active: audioMode === 'file' }" @click="pickFile">
        <span class="tv-button-key">音频</span>
        <span>{{ audioMode === 'file' ? '停止播放' : '播放音频文件' }}</span>
      </button>
      <button v-if="audioMode === 'file'" type="button" class="tv-button" :class="{ active: !musicPlaying }" @click="toggleMusic">
        <span class="tv-button-key">音乐</span>
        <span>{{ musicPlaying ? '暂停' : '播放' }}</span>
      </button>
      <button type="button" class="tv-button" :class="{ active: paused }" @click="togglePause">
        <span class="tv-button-key">播放</span>
        <span>{{ paused ? '继续' : '暂停' }}</span>
      </button>
      <button type="button" class="tv-button" @click="toggleFullscreen">
        <span class="tv-button-key">显示</span>
        <span>全屏</span>
      </button>
      <label class="tv-slider">
        <span>灵敏度</span>
        <input type="range" min="0.4" max="3" step="0.05" v-model.number="sens">
      </label>
      <span class="tv-stats">{{ fpsText }}</span>
    </div>

    <input ref="fileInputRef" type="file" accept="audio/*" class="tv-file" @change="onFileChange">
    <audio ref="playerRef" loop></audio>
    <div v-if="toastMsg" class="tv-toast">{{ toastMsg }}</div>
  </div>
</template>

<script setup>
import { onBeforeUnmount, onMounted, ref } from 'vue'

const mountRef = ref(null)
const canvasRef = ref(null)
const fileInputRef = ref(null)
const playerRef = ref(null)

const audioMode = ref('none')
const musicPlaying = ref(true)
const paused = ref(false)
const sens = ref(1.2)
const fpsText = ref('')
const toastMsg = ref('')

// 渲染引擎状态（非响应式，避免每帧触发依赖收集）
const state = {
  time: 0, z: 0, phase: 0,
  bass: 0, mid: 0, treble: 0, level: 0,
  lookX: 0, lookY: 0,
  speedMul: 1, scale: 1,
  fovBase: 75 * Math.PI / 180
}

let gl = null
let prog = null
let rafId = 0
let resizeObserver = null
let toastTimer = null
let lastT = 0
let dtEma = 16.7
let frameCount = 0
let dragging = false
let lastX = 0
let lastY = 0

// 音频图
let actx = null
let analyser = null
let freqData = null
let micStream = null
let micSource = null
let fileSource = null
let fileURL = null

const VERT = `
attribute vec2 aPos;
void main(){ gl_Position = vec4(aPos, 0.0, 1.0); }
`

// 隧道距离场：6 扇区万花筒折叠 + 5 次 KIFS 盒子折叠，纯 GPU raymarching
const FRAG = `
precision highp float;

uniform vec2  uRes;
uniform float uTime;
uniform float uZ;
uniform float uPhase;
uniform float uBass, uMid, uTreble;
uniform vec2  uLook;
uniform float uFov;

#define TAU 6.28318530718
#define PI  3.14159265359

const float R     = 1.35;
const float SECT  = TAU / 6.0;
const int   ITER  = 5;
const vec3  FOLD  = vec3(0.17, 0.55, 0.42);
const vec3  BOXH  = vec3(0.10, 0.50, 0.35);

mat2 rot(float a){ float c = cos(a), s = sin(a); return mat2(c, -s, s, c); }

float gTrap, gStripe;

// 蛇形中心线：多谐波叠加，低音调制幅度，路径随时间缓变
vec2 tunnelPath(float z){
  float k = 1.0 + 0.30 * uBass;
  float t = uTime * 0.04;
  return vec2(
    1.35 * sin(z * 0.105 + t) + 0.70 * sin(z * 0.047 - t * 0.7 + 1.7),
    1.05 * cos(z * 0.081 - t * 0.8) + 0.55 * sin(z * 0.033 + t * 0.5)
  ) * k;
}
vec2 tunnelTan(float z){
  float k = 1.0 + 0.30 * uBass;
  float t = uTime * 0.04;
  return vec2(
    1.35 * 0.105 * cos(z * 0.105 + t) + 0.70 * 0.047 * cos(z * 0.047 - t * 0.7 + 1.7),
    -1.05 * 0.081 * sin(z * 0.081 - t * 0.8) + 0.55 * 0.033 * cos(z * 0.033 + t * 0.5)
  ) * k;
}

float map(vec3 pos){
  float zw = pos.z;
  vec2 rel = pos.xy - tunnelPath(zw);
  rel = rot(0.06 * uTime + zw * 0.05) * rel;
  float rad = length(rel);
  float a = atan(rel.y, rel.x);
  gStripe = a;
  a = mod(a + 0.5 * SECT, SECT) - 0.5 * SECT;
  vec3 q = vec3(R - rad, a * rad, zw);
  float s = 1.0;
  float trap = 1e9;
  for (int i = 0; i < ITER; i++) {
    q = abs(q) - FOLD;
    q.xy = rot(0.42) * q.xy;
    q.yz = rot(0.24 + 0.10 * sin(uTime * 0.23 + float(i) * 1.7)) * q.yz;
    q *= 1.5; s *= 1.5;
    trap = min(trap, length(q));
  }
  gTrap = trap;
  vec3 b = max(abs(q) - BOXH, 0.0);
  float dStrut = (length(b) - 0.06) / s;
  return min(R - rad, dStrut);
}

vec3 calcNormal(vec3 p){
  const float h = 0.0015;
  vec2 k = vec2(1.0, -1.0);
  return normalize( k.xyy * map(p + k.xyy * h)
                  + k.yyx * map(p + k.yyx * h)
                  + k.yxy * map(p + k.yxy * h)
                  + k.xxx * map(p + k.xxx * h) );
}

vec3 pal(float t){ return 0.55 + 0.45 * cos(TAU * (vec3(0.0, 0.33, 0.67) + t)); }

void main(){
  vec2 frag = gl_FragCoord.xy;
  vec2 uv = (frag * 2.0 - uRes) / uRes.y;

  // 相机锁定在弯曲中心线上，朝向切线方向
  vec3 fwd = normalize(vec3(tunnelTan(uZ), 1.0));
  vec3 rgt = normalize(cross(vec3(0.0, 1.0, 0.0), fwd));
  vec3 upv = cross(fwd, rgt);
  vec3 ro = vec3(tunnelPath(uZ), uZ) + rgt * (0.16 * sin(uTime * 0.31)) + upv * (0.13 * cos(uTime * 0.23));
  uv = rot(0.05 * sin(uTime * 0.17) + uMid * 0.14 * sin(uTime * 0.4)) * uv;
  vec3 camDir = vec3(uv, 1.0 / tan(uFov * 0.5));
  camDir.xz = rot(-uLook.x) * camDir.xz;
  camDir.yz = rot(uLook.y) * camDir.yz;
  vec3 rd = normalize(camDir.x * rgt + camDir.y * upv + camDir.z * fwd);

  float t = 0.02;
  float glow = 0.0;
  float hit = 0.0;
  float steps = 0.0;
  for (int i = 0; i < 110; i++) {
    vec3 pos = ro + rd * t;
    float d = map(pos);
    glow += exp(-6.0 * d);
    if (d < 0.0015 * t + 0.0008) { hit = 1.0; break; }
    t += d * 0.7;
    steps += 1.0;
    if (t > 30.0) break;
  }

  vec3 col;
  if (hit > 0.5) {
    vec3 pos = ro + rd * t;
    vec3 n = calcNormal(pos);
    map(pos);
    float hue = gTrap * 0.55 + pos.z * 0.045 + uPhase;
    vec3 alb = pal(hue + gStripe * 0.08);

    vec3 l1 = normalize(vec3(cos(uTime * 0.40), sin(uTime * 0.40), -0.60));
    vec3 l2 = normalize(vec3(-sin(uTime * 0.31), cos(uTime * 0.27), -0.40));
    float dif  = clamp(dot(n, l1), 0.0, 1.0);
    float dif2 = clamp(dot(n, l2), 0.0, 1.0);
    float spe  = pow(clamp(dot(reflect(rd, n), l1), 0.0, 1.0), 24.0);
    float rim  = pow(1.0 - clamp(dot(n, -rd), 0.0, 1.0), 2.0);
    float ao   = clamp(1.0 - steps / 110.0, 0.0, 1.0); ao *= ao;

    col = alb * (0.20 * ao)
        + alb * dif  * vec3(0.35, 0.75, 1.00) * 1.10
        + alb * dif2 * vec3(1.00, 0.30, 0.75) * 0.90
        + spe * vec3(1.0) * (0.8 + uTreble * 1.5)
        + rim * pal(hue + 0.5) * (0.9 + uBass * 1.4);

    col = mix(vec3(0.02, 0.00, 0.05), col, exp(-0.075 * t));
  } else {
    col = vec3(0.02, 0.00, 0.05);
  }

  vec3 glowCol = pal(uPhase * 1.3 + 0.33 + uZ * 0.01);
  col += glowCol * glow * 0.028 * (0.75 + uTreble * 1.8);

  vec2 vq = frag / uRes;
  col *= 0.35 + 0.65 * pow(16.0 * vq.x * vq.y * (1.0 - vq.x) * (1.0 - vq.y), 0.25);
  col = 1.0 - exp(-col * 1.35);
  col = pow(col, vec3(0.85));
  gl_FragColor = vec4(col, 1.0);
}
`

const U = {}

onMounted(() => {
  if (!initGL()) return
  bindEvents()
  lastT = performance.now()
  rafId = window.requestAnimationFrame(frame)
})

onBeforeUnmount(() => {
  window.cancelAnimationFrame(rafId)
  const container = mountRef.value
  const canvas = canvasRef.value
  if (container) {
    container.removeEventListener('pointerdown', handlePointerDown)
    container.removeEventListener('pointermove', handlePointerMove)
    container.removeEventListener('pointerup', handlePointerUp)
    container.removeEventListener('pointercancel', handlePointerUp)
    container.removeEventListener('wheel', handleWheel, { passive: false })
  }
  if (canvas) {
    canvas.removeEventListener('webglcontextlost', handleContextLost)
  }
  if (resizeObserver) resizeObserver.disconnect()
  stopMic()
  stopFile()
  if (actx) {
    actx.close().catch(() => {})
    actx = null
    analyser = null
    freqData = null
  }
  if (gl && prog) {
    gl.deleteProgram(prog)
    prog = null
  }
  clearTimeout(toastTimer)
})

function initGL() {
  const canvas = canvasRef.value
  gl = canvas.getContext('webgl', { antialias: false, alpha: false, powerPreference: 'high-performance' })
        || canvas.getContext('experimental-webgl')
  if (!gl) {
    showToast('当前浏览器不支持 WebGL，无法渲染分形隧道')
    return false
  }
  try {
    const vs = compileShader(gl.VERTEX_SHADER, VERT)
    const fs = compileShader(gl.FRAGMENT_SHADER, FRAG)
    prog = gl.createProgram()
    gl.attachShader(prog, vs)
    gl.attachShader(prog, fs)
    gl.linkProgram(prog)
    if (!gl.getProgramParameter(prog, gl.LINK_STATUS)) throw new Error(gl.getProgramInfoLog(prog))
    gl.useProgram(prog)
    gl.deleteShader(vs)
    gl.deleteShader(fs)
  } catch (e) {
    showToast('着色器初始化失败：' + e.message)
    return false
  }
  const buf = gl.createBuffer()
  gl.bindBuffer(gl.ARRAY_BUFFER, buf)
  gl.bufferData(gl.ARRAY_BUFFER, new Float32Array([-1, -1, 3, -1, -1, 3]), gl.STATIC_DRAW)
  const aPos = gl.getAttribLocation(prog, 'aPos')
  gl.enableVertexAttribArray(aPos)
  gl.vertexAttribPointer(aPos, 2, gl.FLOAT, false, 0, 0)
  for (const n of ['uRes', 'uTime', 'uZ', 'uPhase', 'uBass', 'uMid', 'uTreble', 'uLook', 'uFov']) {
    U[n] = gl.getUniformLocation(prog, n)
  }
  applySize()
  return true
}

function compileShader(type, src) {
  const sh = gl.createShader(type)
  gl.shaderSource(sh, src)
  gl.compileShader(sh)
  if (!gl.getShaderParameter(sh, gl.COMPILE_STATUS)) {
    throw new Error(gl.getShaderInfoLog(sh))
  }
  return sh
}

function bindEvents() {
  const container = mountRef.value
  const canvas = canvasRef.value
  container.addEventListener('pointerdown', handlePointerDown)
  container.addEventListener('pointermove', handlePointerMove)
  container.addEventListener('pointerup', handlePointerUp)
  container.addEventListener('pointercancel', handlePointerUp)
  container.addEventListener('wheel', handleWheel, { passive: false })
  canvas.addEventListener('webglcontextlost', handleContextLost)
  resizeObserver = new ResizeObserver(() => applySize())
  resizeObserver.observe(container)
}

function handleContextLost(e) {
  e.preventDefault()
  showToast('GPU 上下文丢失，请重新进入本页')
}

/* ---- 交互 ---- */
function handlePointerDown(e) {
  if (e.button !== 0) return
  dragging = true
  lastX = e.clientX
  lastY = e.clientY
  try { canvasRef.value.setPointerCapture(e.pointerId) } catch (err) {}
}

function handlePointerMove(e) {
  if (!dragging) return
  state.lookX += (e.clientX - lastX) * 0.0035
  state.lookY += (e.clientY - lastY) * 0.0035
  state.lookY = Math.max(-1.1, Math.min(1.1, state.lookY))
  lastX = e.clientX
  lastY = e.clientY
}

function handlePointerUp(e) {
  dragging = false
  try { canvasRef.value.releasePointerCapture(e.pointerId) } catch (err) {}
}

function handleWheel(e) {
  e.preventDefault()
  state.speedMul = Math.max(0.25, Math.min(3.5, state.speedMul * (e.deltaY > 0 ? 0.88 : 1.14)))
}

/* ---- 音频 ---- */
function ensureAudio() {
  if (!actx) {
    actx = new (window.AudioContext || window.webkitAudioContext)()
    analyser = actx.createAnalyser()
    analyser.fftSize = 2048
    analyser.smoothingTimeConstant = 0.55
    freqData = new Uint8Array(analyser.frequencyBinCount)
  }
  if (actx.state === 'suspended') actx.resume()
}

function bandEnergy(lo, hi) {
  let sum = 0
  for (let i = lo; i < hi; i++) {
    const v = freqData[i] / 255
    sum += v * v
  }
  return Math.min(1, Math.sqrt(sum / (hi - lo)) * 2.6)
}

function readAudio() {
  if (audioMode.value === 'none' || !analyser) {
    // 氛围模式：无音频时的模拟律动
    const t = state.time
    return {
      bass: Math.max(0, 0.30 + 0.20 * Math.sin(t * 0.9) + 0.10 * Math.sin(t * 2.3 + 1.7)),
      mid: Math.max(0, 0.25 + 0.15 * Math.sin(t * 0.53 + 2.0)),
      treble: Math.max(0, 0.18 + 0.10 * Math.sin(t * 1.31 + 4.0))
    }
  }
  analyser.getByteFrequencyData(freqData)
  const sr = actx.sampleRate
  const bin = sr / analyser.fftSize
  const k = sens.value
  return {
    bass: Math.min(1, bandEnergy(Math.max(1, Math.floor(20 / bin)), Math.floor(250 / bin)) * k),
    mid: Math.min(1, bandEnergy(Math.floor(250 / bin), Math.floor(2000 / bin)) * k),
    treble: Math.min(1, bandEnergy(Math.floor(2000 / bin), Math.floor(8000 / bin)) * k)
  }
}

function smooth(cur, target, dt) {
  const rate = target > cur ? 28 : 6
  return cur + (target - cur) * (1 - Math.exp(-rate * dt))
}

async function toggleMic() {
  if (audioMode.value === 'mic') {
    stopMic()
    return
  }
  stopFile()
  try {
    ensureAudio()
    micStream = await navigator.mediaDevices.getUserMedia({
      audio: { echoCancellation: false, noiseSuppression: false, autoGainControl: false }
    })
    micSource = actx.createMediaStreamSource(micStream)
    micSource.connect(analyser)
    audioMode.value = 'mic'
    showToast('已连接麦克风，声音越大穿梭越快、色彩越烈')
  } catch (e) {
    showToast('麦克风不可用：' + e.name + (e.name === 'NotAllowedError' ? '（权限被拒绝）' : ''))
  }
}

function stopMic() {
  if (audioMode.value !== 'mic') return
  if (micStream) micStream.getTracks().forEach(t => t.stop())
  if (micSource) micSource.disconnect()
  micStream = null
  micSource = null
  audioMode.value = 'none'
}

function pickFile() {
  if (audioMode.value === 'file') {
    stopFile()
    return
  }
  fileInputRef.value.click()
}

function onFileChange() {
  const f = fileInputRef.value.files[0]
  if (!f) return
  stopMic()
  stopFile()
  ensureAudio()
  const player = playerRef.value
  fileURL = URL.createObjectURL(f)
  player.src = fileURL
  fileSource = actx.createMediaElementSource(player)
  fileSource.connect(analyser)
  analyser.connect(actx.destination)
  player.play().catch(() => showToast('音频播放失败'))
  musicPlaying.value = true
  audioMode.value = 'file'
  fileInputRef.value.value = ''
}

function toggleMusic() {
  const player = playerRef.value
  if (!player || audioMode.value !== 'file') return
  if (player.paused) {
    player.play().then(() => { musicPlaying.value = true }).catch(() => showToast('音频播放失败'))
  } else {
    player.pause()
    musicPlaying.value = false
  }
}

function stopFile() {
  const player = playerRef.value
  if (player) player.pause()
  if (fileSource) {
    try { fileSource.disconnect() } catch (e) {}
    fileSource = null
  }
  if (fileURL) {
    URL.revokeObjectURL(fileURL)
    fileURL = null
  }
  musicPlaying.value = true
  if (audioMode.value === 'file') audioMode.value = 'none'
}

function togglePause() {
  paused.value = !paused.value
}

function toggleFullscreen() {
  if (document.fullscreenElement) document.exitFullscreen()
  else mountRef.value.requestFullscreen().catch(() => {})
}

function showToast(msg) {
  toastMsg.value = msg
  clearTimeout(toastTimer)
  toastTimer = setTimeout(() => { toastMsg.value = '' }, 4000)
}

/* ---- 自适应分辨率 ---- */
const DPR_CAP = 1.75
function applySize() {
  const container = mountRef.value
  const canvas = canvasRef.value
  if (!container || !canvas) return
  const dpr = Math.min(window.devicePixelRatio || 1, DPR_CAP)
  const w = Math.max(2, Math.round(container.clientWidth * dpr * state.scale))
  const h = Math.max(2, Math.round(container.clientHeight * dpr * state.scale))
  if (canvas.width !== w || canvas.height !== h) {
    canvas.width = w
    canvas.height = h
    if (gl) gl.viewport(0, 0, w, h)
  }
}

/* ---- 主循环 ---- */
function frame(now) {
  rafId = window.requestAnimationFrame(frame)
  if (!gl || !prog) return
  let dt = (now - lastT) / 1000
  lastT = now
  dt = Math.min(dt, 0.1)
  dtEma += (dt * 1000 - dtEma) * 0.05

  // 帧耗高则降采样，富余则回升
  if (dtEma > 24 && state.scale > 0.45) {
    state.scale = Math.max(0.45, state.scale - 0.05)
    applySize()
  } else if (dtEma < 13 && state.scale < 1.0) {
    state.scale = Math.min(1.0, state.scale + 0.02)
    applySize()
  }

  const sim = readAudio()
  if (!paused.value) {
    state.bass = smooth(state.bass, sim.bass, dt)
    state.mid = smooth(state.mid, sim.mid, dt)
    state.treble = smooth(state.treble, sim.treble, dt)
    state.level = smooth(state.level, (sim.bass + sim.mid + sim.treble) / 3, dt)
    state.z += state.speedMul * (2.0 + 3.0 * state.bass) * dt
    state.time += dt
    state.phase += dt * (0.05 + state.level * 0.38)
  }

  const canvas = canvasRef.value
  gl.uniform2f(U.uRes, canvas.width, canvas.height)
  gl.uniform1f(U.uTime, state.time)
  gl.uniform1f(U.uZ, state.z)
  gl.uniform1f(U.uPhase, state.phase)
  gl.uniform1f(U.uBass, state.bass)
  gl.uniform1f(U.uMid, state.mid)
  gl.uniform1f(U.uTreble, state.treble)
  gl.uniform2f(U.uLook, state.lookX, state.lookY)
  gl.uniform1f(U.uFov, state.fovBase * (1 + 0.11 * state.bass))
  gl.drawArrays(gl.TRIANGLES, 0, 3)

  if (frameCount++ % 30 === 0) {
    fpsText.value = `${(1000 / Math.max(1, dtEma)).toFixed(0)} fps · 渲染 ${(state.scale * 100).toFixed(0)}%`
  }
}
</script>

<style scoped>
.tunnel-view {
  position: relative;
  width: 100%;
  height: 100%;
  min-height: 560px;
  overflow: hidden;
  background: #030208;
}

.tv-canvas {
  position: absolute;
  inset: 0;
  width: 100%;
  height: 100%;
  display: block;
  touch-action: none;
  cursor: grab;
}

.tv-canvas:active {
  cursor: grabbing;
}

.tv-header {
  position: absolute;
  top: 28px;
  left: 32px;
  z-index: 2;
  pointer-events: none;
  user-select: none;
}

.tv-eyebrow {
  display: block;
  color: #b78cff;
  font-size: 11px;
  font-weight: 700;
  letter-spacing: 0.32em;
  text-transform: uppercase;
  text-shadow: 0 0 14px rgba(183, 140, 255, 0.6);
}

.tv-title {
  margin: 6px 0 8px;
  color: #f4eeff;
  font-size: 30px;
  font-weight: 850;
  letter-spacing: 0.06em;
  text-shadow: 0 0 24px rgba(183, 140, 255, 0.5);
}

.tv-desc {
  margin: 0;
  max-width: 520px;
  color: rgba(214, 204, 240, 0.66);
  font-size: 13px;
  line-height: 1.7;
}

.tv-toolbar {
  position: absolute;
  bottom: 34px;
  left: 50%;
  z-index: 2;
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: center;
  gap: 8px;
  padding: 8px 10px;
  border: 1px solid rgba(183, 140, 255, 0.24);
  border-radius: 14px;
  background: rgba(16, 10, 34, 0.55);
  backdrop-filter: blur(14px);
  transform: translateX(-50%);
}

.tv-button {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  min-height: 32px;
  padding: 0 14px;
  border: 1px solid rgba(150, 126, 214, 0.24);
  border-radius: 9px;
  color: rgba(224, 214, 244, 0.8);
  background: transparent;
  font-size: 13px;
  font-weight: 650;
  cursor: pointer;
  white-space: nowrap;
  transition:
    color 0.2s ease,
    border-color 0.2s ease,
    background 0.2s ease,
    box-shadow 0.2s ease;
}

.tv-button:hover {
  color: #f4eeff;
  border-color: rgba(183, 140, 255, 0.55);
}

.tv-button.active {
  color: #0a0614;
  border-color: rgba(183, 140, 255, 0.85);
  background: linear-gradient(135deg, #d9b8ff, #8a5cff);
  box-shadow: 0 0 18px rgba(138, 92, 255, 0.4);
}

.tv-button-key {
  font-size: 11px;
  font-weight: 800;
  opacity: 0.7;
  letter-spacing: 0.05em;
}

.tv-slider {
  display: inline-flex;
  align-items: center;
  gap: 7px;
  color: rgba(214, 204, 240, 0.7);
  font-size: 12px;
}

.tv-slider input {
  width: 92px;
  accent-color: #9d7bff;
  cursor: pointer;
}

.tv-stats {
  min-width: 96px;
  color: rgba(214, 204, 240, 0.5);
  font-size: 11px;
  font-variant-numeric: tabular-nums;
  text-align: right;
}

.tv-file {
  display: none;
}

.tv-toast {
  position: absolute;
  top: 24px;
  left: 50%;
  z-index: 3;
  transform: translateX(-50%);
  max-width: 80%;
  padding: 9px 16px;
  border: 1px solid rgba(255, 110, 130, 0.5);
  border-radius: 10px;
  background: rgba(60, 20, 30, 0.85);
  color: #ffd7dd;
  font-size: 13px;
  pointer-events: none;
  backdrop-filter: blur(8px);
}

@media (max-width: 920px) {
  .tv-header {
    top: 18px;
    left: 18px;
  }

  .tv-title {
    font-size: 24px;
  }

  .tv-desc {
    font-size: 12px;
  }

  .tv-toolbar {
    bottom: 20px;
    max-width: calc(100% - 24px);
  }
}
</style>
