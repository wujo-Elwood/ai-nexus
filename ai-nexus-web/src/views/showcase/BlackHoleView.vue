<template>
  <div ref="mountRef" class="bh-view">
    <canvas ref="canvasRef" class="bh-canvas"></canvas>

    <div class="bh-header">
      <span class="bh-eyebrow">Gravitational Lensing</span>
      <h1 class="bh-title">黑洞引力透镜</h1>
      <p class="bh-desc">
        {{ audioMode === 'none' ? '拖拽环绕 · 滚轮推近拉远 · 无音频时自动进入氛围模式' : '吸积盘随低音增亮、随中音加速翻涌' }}
      </p>
    </div>

    <div class="bh-toolbar">
      <button type="button" class="bh-button" :class="{ active: audioMode === 'mic' }" @click="toggleMic">
        <span class="bh-button-key">音频</span>
        <span>{{ audioMode === 'mic' ? '麦克风驱动中' : '接麦克风' }}</span>
      </button>
      <button type="button" class="bh-button" :class="{ active: audioMode === 'file' }" @click="pickFile">
        <span class="bh-button-key">音频</span>
        <span>{{ audioMode === 'file' ? '停止播放' : '播放音频文件' }}</span>
      </button>
      <button v-if="audioMode === 'file'" type="button" class="bh-button" :class="{ active: !musicPlaying }" @click="toggleMusic">
        <span class="bh-button-key">音乐</span>
        <span>{{ musicPlaying ? '暂停' : '播放' }}</span>
      </button>
      <button type="button" class="bh-button" :class="{ active: paused }" @click="togglePause">
        <span class="bh-button-key">播放</span>
        <span>{{ paused ? '继续' : '暂停' }}</span>
      </button>
      <button type="button" class="bh-button" @click="toggleFullscreen">
        <span class="bh-button-key">显示</span>
        <span>全屏</span>
      </button>
      <label class="bh-slider">
        <span>灵敏度</span>
        <input type="range" min="0.4" max="3" step="0.05" v-model.number="sens">
      </label>
      <span class="bh-stats">{{ fpsText }}</span>
    </div>

    <input ref="fileInputRef" type="file" accept="audio/*" class="bh-file" @change="onFileChange">
    <audio ref="playerRef" loop></audio>
    <div v-if="toastMsg" class="bh-toast">{{ toastMsg }}</div>
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

// 渲染状态（非响应式）
const state = {
  time: 0,
  lookX: 0,        // 方位角（含自动环绕增量）
  lookY: 0.14,     // 俯仰角，默认略高于盘面
  dist: 13,        // 相机距离（rs 单位）
  bass: 0, mid: 0, treble: 0, level: 0,
  scale: 1,
  fovBase: 60 * Math.PI / 180
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

// 史瓦西测地线积分（starless 法，rs=1）：光线在弯曲时空中逐步追踪，
// 穿越赤道面时采样吸积盘，落进视界则终止，逃逸后以弯曲方向采样星空
const FRAG = `
precision highp float;

uniform vec2  uRes;
uniform float uTime;
uniform float uBass, uMid, uTreble;
uniform vec2  uLook;
uniform float uDist;
uniform float uFov;

#define TAU 6.28318530718

const float DISK_IN  = 2.8;
const float DISK_OUT = 11.0;

mat2 rot(float a){ float c = cos(a), s = sin(a); return mat2(c, -s, s, c); }

float hash13(vec3 p){
  p = fract(p * 0.1031);
  p += dot(p, p.zyx + 31.32);
  return fract((p.x + p.y) * p.z);
}

float vnoise(vec3 x){
  vec3 i = floor(x);
  vec3 f = fract(x);
  f = f * f * (3.0 - 2.0 * f);
  float n000 = hash13(i);
  float n100 = hash13(i + vec3(1.0, 0.0, 0.0));
  float n010 = hash13(i + vec3(0.0, 1.0, 0.0));
  float n110 = hash13(i + vec3(1.0, 1.0, 0.0));
  float n001 = hash13(i + vec3(0.0, 0.0, 1.0));
  float n101 = hash13(i + vec3(1.0, 0.0, 1.0));
  float n011 = hash13(i + vec3(0.0, 1.0, 1.0));
  float n111 = hash13(i + vec3(1.0, 1.0, 1.0));
  return mix(
    mix(mix(n000, n100, f.x), mix(n010, n110, f.x), f.y),
    mix(mix(n001, n101, f.x), mix(n011, n111, f.x), f.y),
    f.z
  );
}

float fbm(vec3 p){
  float a = 0.5;
  float s = 0.0;
  for (int i = 0; i < 4; i++) {
    s += a * vnoise(p);
    p = p * 2.03 + vec3(1.7, 9.2, 4.1);
    a *= 0.5;
  }
  return s;
}

vec3 starLayer(vec3 rd, float s, float tw){
  vec3 p = rd * s;
  vec3 id = floor(p);
  vec3 f = fract(p) - 0.5;
  float h = hash13(id);
  float star = step(0.982, h) * smoothstep(0.38, 0.0, length(f));
  float twk = 0.65 + 0.35 * sin(uTime * 2.4 + h * 61.0);
  vec3 tint = mix(vec3(0.72, 0.82, 1.0), vec3(1.0, 0.88, 0.72), fract(h * 37.0));
  return tint * star * (0.55 + tw * twk);
}

vec3 skyColor(vec3 rd){
  vec3 col = vec3(0.008, 0.006, 0.016);
  float band = exp(-abs(dot(rd, normalize(vec3(0.3, 1.0, 0.15)))) * 4.0);
  col += vec3(0.05, 0.06, 0.10) * band * (0.6 + 0.4 * fbm(rd * 3.0));
  float neb = fbm(rd * 2.2 + 7.0);
  col += vec3(0.10, 0.04, 0.16) * pow(max(neb - 0.35, 0.0), 1.6) * (0.9 + uTreble * 0.8);
  col += vec3(0.02, 0.09, 0.12) * pow(max(fbm(rd * 1.7 + 13.0) - 0.4, 0.0), 1.8) * 0.7;
  col += starLayer(rd, 90.0, 0.5 + uTreble);
  col += starLayer(rd, 160.0, 0.5 + uTreble) * 0.7;
  col += starLayer(rd, 260.0, 0.5 + uTreble) * 0.5;
  return col;
}

vec4 diskShade(vec3 cp, vec3 rd){
  float rc = length(cp.xz);
  float fade = smoothstep(DISK_IN, DISK_IN + 0.6, rc) * smoothstep(DISK_OUT, DISK_OUT - 3.5, rc);
  float ang = atan(cp.z, cp.x);
  // 开普勒差速旋转：内圈快、外圈慢，噪声条纹随角速度剪切
  float sp = 3.4 * pow(rc, -1.5) * (1.0 + uMid * 0.8);
  float a2 = ang + uTime * sp;
  vec3 q = vec3(cos(a2), sin(a2), 0.0) * rc;
  float n = fbm(vec3(q.xy * 0.8, rc * 0.6));
  n = 0.55 + 0.45 * n;
  float e = pow(DISK_IN / rc, 2.2);
  // 多普勒束射：物质绕行方向接近侧增亮
  vec3 vdir = normalize(cross(vec3(0.0, 1.0, 0.0), cp));
  float beta = clamp(sqrt(0.5 / rc) * 1.6, 0.0, 0.6);
  float dopp = clamp(pow(1.0 / (1.0 - beta * dot(vdir, -rd)), 3.0), 0.2, 4.0);
  float tnorm = (rc - DISK_IN) / (DISK_OUT - DISK_IN);
  vec3 c = mix(vec3(1.0, 0.96, 0.90), vec3(1.0, 0.72, 0.35), smoothstep(0.0, 0.35, tnorm));
  c = mix(c, vec3(0.55, 0.16, 0.06), smoothstep(0.3, 0.9, tnorm));
  c = mix(c, vec3(0.85, 0.92, 1.0), clamp((dopp - 1.6) * 0.4, 0.0, 0.5));
  float alpha = fade * n * (0.30 + 0.70 * e);
  return vec4(c * e * dopp * n * (0.9 + 1.2 * uBass), alpha);
}

void main(){
  vec2 uv = (gl_FragCoord.xy * 2.0 - uRes) / uRes.y;

  // 轨道相机
  float az = uLook.x;
  float el = clamp(uLook.y, -1.25, 1.25);
  vec3 ro = vec3(sin(az) * cos(el), sin(el), cos(az) * cos(el)) * uDist;
  vec3 fwd = normalize(-ro);
  vec3 rgt = normalize(cross(vec3(0.0, 1.0, 0.0), fwd));
  vec3 upv = cross(fwd, rgt);
  vec3 rd = normalize(uv.x * rgt + uv.y * upv + (1.0 / tan(uFov * 0.5)) * fwd);

  vec3 pos = ro;
  vec3 vel = rd;
  vec3 h2v = cross(pos, vel);
  float h2 = dot(h2v, h2v);

  vec3 col = vec3(0.0);
  float trans = 1.0;
  bool blocked = false;
  for (int i = 0; i < 200; i++) {
    float r = length(pos);
    if (r < 1.0) { blocked = true; break; }
    if (r > 30.0 && dot(pos, vel) > 0.0) break;
    // 远场弯曲可忽略用大步长，近场自适应小步长
    float dt = r > 18.0 ? 1.0 : clamp(0.05 * r + 0.03, 0.03, 0.45);
    vel += (-1.5 * h2 * pos / pow(dot(pos, pos), 2.5)) * dt;
    vec3 np = pos + vel * dt;
    if (pos.y * np.y < 0.0) {
      float f = pos.y / (pos.y - np.y);
      vec3 cp = mix(pos, np, vec3(f));
      float rc = length(cp.xz);
      if (rc > DISK_IN && rc < DISK_OUT) {
        vec4 dc = diskShade(cp, normalize(vel));
        col += trans * dc.rgb * dc.a;
        trans *= 1.0 - dc.a;
        if (trans < 0.02) { blocked = true; break; }
      }
    }
    pos = np;
  }
  if (!blocked) col += trans * skyColor(normalize(vel));

  col = 1.0 - exp(-col * 1.6);
  vec2 vq = gl_FragCoord.xy / uRes;
  col *= 0.30 + 0.70 * pow(16.0 * vq.x * vq.y * (1.0 - vq.x) * (1.0 - vq.y), 0.22);
  gl_FragColor = vec4(pow(col, vec3(0.9)), 1.0);
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
    showToast('当前浏览器不支持 WebGL，无法渲染黑洞')
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
  for (const n of ['uRes', 'uTime', 'uBass', 'uMid', 'uTreble', 'uLook', 'uDist', 'uFov']) {
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
  state.lookX += (e.clientX - lastX) * 0.005
  state.lookY += (e.clientY - lastY) * 0.004
  state.lookY = Math.max(-1.25, Math.min(1.25, state.lookY))
  lastX = e.clientX
  lastY = e.clientY
}

function handlePointerUp(e) {
  dragging = false
  try { canvasRef.value.releasePointerCapture(e.pointerId) } catch (err) {}
}

function handleWheel(e) {
  e.preventDefault()
  state.dist = Math.max(5.5, Math.min(30, state.dist * (e.deltaY > 0 ? 1.12 : 0.89)))
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
    showToast('已连接麦克风，低音越强吸积盘越亮')
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
    state.time += dt
    if (!dragging) state.lookX += dt * 0.06   // 缓慢自动环绕
  }

  const canvas = canvasRef.value
  gl.uniform2f(U.uRes, canvas.width, canvas.height)
  gl.uniform1f(U.uTime, state.time)
  gl.uniform1f(U.uBass, state.bass)
  gl.uniform1f(U.uMid, state.mid)
  gl.uniform1f(U.uTreble, state.treble)
  gl.uniform2f(U.uLook, state.lookX, state.lookY)
  gl.uniform1f(U.uDist, state.dist)
  gl.uniform1f(U.uFov, state.fovBase)
  gl.drawArrays(gl.TRIANGLES, 0, 3)

  if (frameCount++ % 30 === 0) {
    fpsText.value = `${(1000 / Math.max(1, dtEma)).toFixed(0)} fps · 渲染 ${(state.scale * 100).toFixed(0)}%`
  }
}
</script>

<style scoped>
.bh-view {
  position: relative;
  width: 100%;
  height: 100%;
  min-height: 560px;
  overflow: hidden;
  background: #010104;
}

.bh-canvas {
  position: absolute;
  inset: 0;
  width: 100%;
  height: 100%;
  display: block;
  touch-action: none;
  cursor: grab;
}

.bh-canvas:active {
  cursor: grabbing;
}

.bh-header {
  position: absolute;
  top: 28px;
  left: 32px;
  z-index: 2;
  pointer-events: none;
  user-select: none;
}

.bh-eyebrow {
  display: block;
  color: #ffb066;
  font-size: 11px;
  font-weight: 700;
  letter-spacing: 0.32em;
  text-transform: uppercase;
  text-shadow: 0 0 14px rgba(255, 176, 102, 0.55);
}

.bh-title {
  margin: 6px 0 8px;
  color: #fff4e8;
  font-size: 30px;
  font-weight: 850;
  letter-spacing: 0.06em;
  text-shadow: 0 0 24px rgba(255, 176, 102, 0.45);
}

.bh-desc {
  margin: 0;
  max-width: 520px;
  color: rgba(240, 224, 208, 0.66);
  font-size: 13px;
  line-height: 1.7;
}

.bh-toolbar {
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
  border: 1px solid rgba(255, 176, 102, 0.24);
  border-radius: 14px;
  background: rgba(30, 18, 10, 0.55);
  backdrop-filter: blur(14px);
  transform: translateX(-50%);
}

.bh-button {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  min-height: 32px;
  padding: 0 14px;
  border: 1px solid rgba(214, 158, 110, 0.24);
  border-radius: 9px;
  color: rgba(244, 228, 210, 0.8);
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

.bh-button:hover {
  color: #fff4e8;
  border-color: rgba(255, 176, 102, 0.55);
}

.bh-button.active {
  color: #140a04;
  border-color: rgba(255, 176, 102, 0.85);
  background: linear-gradient(135deg, #ffd9a8, #ff8a3c);
  box-shadow: 0 0 18px rgba(255, 138, 60, 0.4);
}

.bh-button-key {
  font-size: 11px;
  font-weight: 800;
  opacity: 0.7;
  letter-spacing: 0.05em;
}

.bh-slider {
  display: inline-flex;
  align-items: center;
  gap: 7px;
  color: rgba(240, 224, 208, 0.7);
  font-size: 12px;
}

.bh-slider input {
  width: 92px;
  accent-color: #ff9a4d;
  cursor: pointer;
}

.bh-stats {
  min-width: 96px;
  color: rgba(240, 224, 208, 0.5);
  font-size: 11px;
  font-variant-numeric: tabular-nums;
  text-align: right;
}

.bh-file {
  display: none;
}

.bh-toast {
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
  .bh-header {
    top: 18px;
    left: 18px;
  }

  .bh-title {
    font-size: 24px;
  }

  .bh-desc {
    font-size: 12px;
  }

  .bh-toolbar {
    bottom: 20px;
    max-width: calc(100% - 24px);
  }
}
</style>
