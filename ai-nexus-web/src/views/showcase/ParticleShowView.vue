<template>
  <div ref="mountRef" class="particle-show">
    <div class="show-header">
      <span class="show-eyebrow">Particle Show</span>
      <h1 class="show-title">粒子展厅</h1>
      <p class="show-desc">
        {{ particleCount }} 颗粒子随时待命 · 移动鼠标扰动粒子流 · 左键拖动旋转视角 · 滚轮缩放 · 点击画面爆散重组 · 空格键随机变形
      </p>
    </div>

    <div class="show-toolbar">
      <button
        v-for="(shape, index) in shapeMeta"
        :key="shape.key"
        type="button"
        class="shape-button"
        :class="{ active: activeIndex === index }"
        @click="selectShape(index)"
      >
        <span class="shape-index">{{ index + 1 }}</span>
        <span>{{ shape.label }}</span>
      </button>
      <button
        type="button"
        class="shape-button auto-button"
        :class="{ active: autoPlay }"
        @click="toggleAutoPlay"
      >
        <span class="shape-index">{{ autoPlay ? '▶' : '❚❚' }}</span>
        <span>自动轮播</span>
      </button>
    </div>

    <div class="show-progress" aria-hidden="true">
      <div class="show-progress-bar" :style="{ width: progressPercent + '%' }"></div>
    </div>
  </div>
</template>

<script setup>
import { onBeforeUnmount, onMounted, ref } from 'vue'
import * as THREE from 'three'

const mountRef = ref(null)
const activeIndex = ref(0)
const progressPercent = ref(0)
const autoPlay = ref(true)
const particleCount = window.matchMedia('(max-width: 768px)').matches ? 9000 : 16000

const COUNT = particleCount
const STIFFNESS = 0.013
const DAMPING = 0.88
const REPEL_RADIUS = 60
const REPEL_FORCE = 2.6
const CYCLE_MS = 9000

const shapeMeta = [
  { key: 'brand', label: 'AI NEXUS', flat: true },
  { key: 'slogan', label: '星辰大海', flat: true },
  { key: 'galaxy', label: '银河' },
  { key: 'knot', label: '环结' },
  { key: 'dna', label: '双螺旋' },
  { key: 'heart', label: '心形', flat: true }
]

let renderer = null
let scene = null
let camera = null
let tiltGroup = null
let orbitGroup = null
let spinGroup = null
let points = null
let geometry = null
let material = null
let spriteTexture = null
let rafId = 0
let clock = null
let resizeObserver = null
let progressTimer = null
let cycleStart = 0
let pausedAt = null

let positions = null
let colors = null
let velocities = null
let targetPositions = null
let targetColors = null
let shapes = {}

const LATIN_TEXT_FONT = '"Arial Black", "Microsoft YaHei", sans-serif'
const CJK_TEXT_FONT = '"Microsoft YaHei", "PingFang SC", "Noto Sans CJK SC", sans-serif'

// 各形态的目标数据构建器；文字形态依赖 canvas 字体渲染，改为首次使用时才构建
const shapeBuilders = {
  brand: () => ({ positions: sampleText('AI NEXUS', LATIN_TEXT_FONT), colors: buildColors(['#ffd9a0', '#ff9f43', '#5ec2ff', '#fff6e8']) }),
  slogan: () => ({ positions: sampleText('星辰大海', CJK_TEXT_FONT), colors: buildColors(['#7ee0ff', '#4f9dff', '#b8fff2', '#eaf6ff']) }),
  galaxy: () => ({ positions: makeGalaxy(), colors: buildColors(['#c6a0ff', '#7f73ff', '#ff9ad5', '#fff1c9']) }),
  knot: () => ({ positions: makeTorusKnot(), colors: buildColors(['#ff6b6b', '#ffd93d', '#6bff8f', '#6bc9ff', '#c56bff']) }),
  dna: () => ({ positions: makeDNA(), colors: buildColors(['#5effc0', '#39c6ff', '#a8ffea', '#e8fff8']) }),
  heart: () => ({ positions: makeHeart(), colors: buildColors(['#ff5f7e', '#ff9ab0', '#ff3d6e', '#ffdce3']) })
}

// 首次使用时才构建形态数据并缓存
function getShape(key) {
  if (!shapes[key]) {
    shapes[key] = shapeBuilders[key]()
  }
  return shapes[key]
}

const mouse = {
  ndcX: 0,
  ndcY: 0,
  active: false,
  world: new THREE.Vector3(),
  local: new THREE.Vector3()
}

// 左键拖拽旋转视角的状态（偏航 + 俯仰双轴，斜拖两轴同时动，可到达任意朝向）
const drag = {
  active: false,
  moved: 0,
  lastX: 0,
  lastY: 0,
  yaw: 0,
  pitch: 0,
  velYaw: 0,
  velPitch: 0
}

const DRAG_ROTATE_SPEED = 0.006
const DRAG_INERTIA_DECAY = 0.94
const DRAG_RETURN_SPEED = 0.045
const PITCH_LIMIT = Math.PI / 2 - 0.06

// 滚轮缩放：相机沿 Z 推拉，目标值平滑逼近
const zoom = {
  distance: 320,
  target: 320,
  min: 150,
  max: 680,
  speed: 0.0016
}

onMounted(() => {
  initScene()
  initParticles()
  bindEvents()
  clock = new THREE.Clock()
  cycleStart = performance.now()
  progressTimer = window.setInterval(updateProgress, 120)
  animate()
})

onBeforeUnmount(() => {
  window.cancelAnimationFrame(rafId)
  window.clearInterval(progressTimer)
  if (resizeObserver) {
    resizeObserver.disconnect()
  }
  const container = mountRef.value
  if (container) {
    container.removeEventListener('pointerdown', handlePointerDown)
    container.removeEventListener('pointermove', handlePointerMove)
    container.removeEventListener('pointerup', handlePointerUp)
    container.removeEventListener('pointercancel', handlePointerUp)
    container.removeEventListener('pointerleave', handlePointerLeave)
    container.removeEventListener('wheel', handleWheel)
  }
  window.removeEventListener('keydown', handleKeydown)
  if (geometry) {
    geometry.dispose()
  }
  if (material) {
    material.dispose()
  }
  if (spriteTexture) {
    spriteTexture.dispose()
  }
  if (renderer) {
    renderer.dispose()
    renderer.domElement.remove()
  }
})

// 切换到指定形态
function selectShape(index) {
  applyShape(index)
  if (autoPlay.value) {
    cycleStart = performance.now()
  } else {
    pausedAt = performance.now()
  }
}

// 切换自动轮播开关
function toggleAutoPlay() {
  autoPlay.value = !autoPlay.value
  if (autoPlay.value) {
    if (pausedAt) {
      cycleStart += performance.now() - pausedAt
      pausedAt = null
    } else {
      cycleStart = performance.now()
    }
  } else {
    pausedAt = performance.now()
  }
}

// 更新自动轮播进度条
function updateProgress() {
  if (!autoPlay.value) {
    return
  }
  const elapsed = performance.now() - cycleStart
  if (elapsed >= CYCLE_MS) {
    applyShape((activeIndex.value + 1) % shapeMeta.length)
    cycleStart = performance.now()
  }
  progressPercent.value = Math.min(100, (elapsed / CYCLE_MS) * 100)
}

// 应用某个形态作为粒子目标
function applyShape(index) {
  activeIndex.value = index
  const shape = getShape(shapeMeta[index].key)
  targetPositions = shape.positions
  targetColors = shape.colors
  // 平面文字形态先归一化朝向角，保证从最短路径回正到正面
  if (shapeMeta[index].flat) {
    const twoPi = Math.PI * 2
    spinGroup.rotation.y = ((spinGroup.rotation.y % twoPi) + twoPi + Math.PI) % twoPi - Math.PI
  }
}

// 初始化场景、相机和渲染器
function initScene() {
  const container = mountRef.value
  renderer = new THREE.WebGLRenderer({ alpha: true, antialias: false, powerPreference: 'high-performance' })
  renderer.setPixelRatio(Math.min(window.devicePixelRatio, 2))
  renderer.setSize(container.clientWidth, container.clientHeight)
  renderer.domElement.classList.add('show-canvas')
  container.appendChild(renderer.domElement)

  scene = new THREE.Scene()
  camera = new THREE.PerspectiveCamera(55, container.clientWidth / container.clientHeight, 1, 2000)
  camera.position.set(0, 0, 320)

  tiltGroup = new THREE.Group()
  orbitGroup = new THREE.Group()
  spinGroup = new THREE.Group()
  tiltGroup.add(orbitGroup)
  orbitGroup.add(spinGroup)
  scene.add(tiltGroup)
}

// 初始化粒子几何体和材质
function initParticles() {
  positions = new Float32Array(COUNT * 3)
  colors = new Float32Array(COUNT * 3)
  velocities = new Float32Array(COUNT * 3)
  const sizes = new Float32Array(COUNT)
  const phases = new Float32Array(COUNT)

  for (let i = 0; i < COUNT; i++) {
    // 开场从远处随机球面飞入，形成汇聚动画
    const theta = Math.random() * Math.PI * 2
    const phi = Math.acos(2 * Math.random() - 1)
    const radius = 420 + Math.random() * 480
    positions[i * 3] = Math.sin(phi) * Math.cos(theta) * radius
    positions[i * 3 + 1] = Math.sin(phi) * Math.sin(theta) * radius
    positions[i * 3 + 2] = Math.cos(phi) * radius
    sizes[i] = Math.random() < 0.045 ? 3.2 + Math.random() * 2.2 : 1.1 + Math.random() * 1.5
    phases[i] = Math.random() * Math.PI * 2
  }

  const first = getShape(shapeMeta[0].key)
  colors.set(first.colors)

  geometry = new THREE.BufferGeometry()
  geometry.setAttribute('position', new THREE.BufferAttribute(positions, 3))
  geometry.setAttribute('aColor', new THREE.BufferAttribute(colors, 3))
  geometry.setAttribute('aSize', new THREE.BufferAttribute(sizes, 1))
  geometry.setAttribute('aPhase', new THREE.BufferAttribute(phases, 1))

  spriteTexture = makeSpriteTexture()
  material = new THREE.ShaderMaterial({
    uniforms: {
      uTime: { value: 0 },
      uPixelRatio: { value: Math.min(window.devicePixelRatio, 2) },
      uMap: { value: spriteTexture }
    },
    vertexShader: `
      attribute float aSize;
      attribute float aPhase;
      attribute vec3 aColor;
      uniform float uTime;
      uniform float uPixelRatio;
      varying vec3 vColor;
      varying float vAlpha;
      void main() {
        vColor = aColor;
        vAlpha = 0.72 + 0.28 * sin(uTime * 2.0 + aPhase);
        vec3 p = position;
        p.x += sin(uTime * 1.4 + aPhase) * 1.5;
        p.y += cos(uTime * 1.15 + aPhase * 1.7) * 1.5;
        p.z += sin(uTime * 0.9 + aPhase * 0.6) * 1.5;
        vec4 mv = modelViewMatrix * vec4(p, 1.0);
        gl_PointSize = aSize * uPixelRatio * (320.0 / -mv.z);
        gl_Position = projectionMatrix * mv;
      }
    `,
    fragmentShader: `
      uniform sampler2D uMap;
      varying vec3 vColor;
      varying float vAlpha;
      void main() {
        vec4 tex = texture2D(uMap, gl_PointCoord);
        gl_FragColor = vec4(vColor * vAlpha, tex.a * vAlpha);
      }
    `,
    transparent: true,
    depthWrite: false,
    blending: THREE.AdditiveBlending
  })

  points = new THREE.Points(geometry, material)
  spinGroup.add(points)
  applyShape(0)
}

// 生成粒子光晕贴图
function makeSpriteTexture() {
  const canvas = document.createElement('canvas')
  canvas.width = 64
  canvas.height = 64
  const ctx = canvas.getContext('2d')
  const gradient = ctx.createRadialGradient(32, 32, 0, 32, 32, 32)
  gradient.addColorStop(0, 'rgba(255, 255, 255, 1)')
  gradient.addColorStop(0.25, 'rgba(255, 255, 255, 0.85)')
  gradient.addColorStop(0.55, 'rgba(255, 255, 255, 0.28)')
  gradient.addColorStop(1, 'rgba(255, 255, 255, 0)')
  ctx.fillStyle = gradient
  ctx.fillRect(0, 0, 64, 64)
  const texture = new THREE.CanvasTexture(canvas)
  texture.needsUpdate = true
  return texture
}

// 绑定交互事件和窗口尺寸监听
function bindEvents() {
  const container = mountRef.value
  container.addEventListener('pointerdown', handlePointerDown)
  container.addEventListener('pointermove', handlePointerMove)
  container.addEventListener('pointerup', handlePointerUp)
  container.addEventListener('pointercancel', handlePointerUp)
  container.addEventListener('pointerleave', handlePointerLeave)
  container.addEventListener('wheel', handleWheel, { passive: false })
  window.addEventListener('keydown', handleKeydown)

  resizeObserver = new ResizeObserver(() => {
    if (!renderer || !container) {
      return
    }
    const width = container.clientWidth
    const height = container.clientHeight
    if (width === 0 || height === 0) {
      return
    }
    renderer.setSize(width, height)
    camera.aspect = width / height
    camera.updateProjectionMatrix()
  })
  resizeObserver.observe(container)
}

// 更新鼠标在画布内的归一化坐标
function updateMouseNdc(event) {
  const rect = renderer.domElement.getBoundingClientRect()
  mouse.ndcX = ((event.clientX - rect.left) / rect.width) * 2 - 1
  mouse.ndcY = -(((event.clientY - rect.top) / rect.height) * 2 - 1)
  mouse.active = true
}

// 在画布上按下左键开始拖拽旋转
function handlePointerDown(event) {
  if (event.button !== 0 || event.target !== renderer.domElement) {
    return
  }
  updateMouseNdc(event)
  drag.active = true
  drag.moved = 0
  drag.lastX = event.clientX
  drag.lastY = event.clientY
  drag.velYaw = 0
  drag.velPitch = 0
  renderer.domElement.classList.add('dragging')
  try {
    renderer.domElement.setPointerCapture(event.pointerId)
  } catch (error) {
    // 忽略捕获失败，拖拽仍可用
  }
}

// 拖拽中按位移旋转视角，非拖拽时只更新扰动中心
function handlePointerMove(event) {
  updateMouseNdc(event)
  if (!drag.active) {
    return
  }
  const dx = event.clientX - drag.lastX
  const dy = event.clientY - drag.lastY
  drag.lastX = event.clientX
  drag.lastY = event.clientY
  drag.moved += Math.abs(dx) + Math.abs(dy)
  drag.velYaw = dx * DRAG_ROTATE_SPEED
  drag.velPitch = dy * DRAG_ROTATE_SPEED
  drag.yaw += drag.velYaw
  drag.pitch = clampPitch(drag.pitch + drag.velPitch)
}

// 抬起左键：几乎没移动视为点击触发爆散，否则保留角速度做惯性
function handlePointerUp(event) {
  if (!drag.active) {
    return
  }
  drag.active = false
  renderer.domElement.classList.remove('dragging')
  try {
    renderer.domElement.releasePointerCapture(event.pointerId)
  } catch (error) {
    // 忽略释放失败
  }
  if (drag.moved < 6) {
    doBurst()
  }
}

// 鼠标离开后停止扰动
function handlePointerLeave() {
  mouse.active = false
}

// 滚轮缩放：向上滚推近，向下滚拉远（乘法缩放手感更自然）
function handleWheel(event) {
  event.preventDefault()
  // 归一化 deltaMode：行/页模式换算到像素量级，保证各浏览器手感一致
  let delta = event.deltaY
  if (event.deltaMode === 1) {
    delta *= 16
  } else if (event.deltaMode === 2) {
    delta *= 100
  }
  const next = zoom.target * (1 + delta * zoom.speed)
  zoom.target = Math.max(zoom.min, Math.min(zoom.max, next))
}

// 空格键随机切换到另一个形态
function handleKeydown(event) {
  if (event.code !== 'Space' || event.repeat) {
    return
  }
  const target = document.activeElement
  if (target && (target.tagName === 'INPUT' || target.tagName === 'TEXTAREA')) {
    return
  }
  event.preventDefault()
  let next = Math.floor(Math.random() * shapeMeta.length)
  if (next === activeIndex.value) {
    next = (next + 1) % shapeMeta.length
  }
  selectShape(next)
}

// 让粒子从当前鼠标处爆散
function doBurst() {
  const center = mouse.active ? mouse.local.clone() : new THREE.Vector3(0, 0, 0)
  for (let i = 0; i < COUNT; i++) {
    const i3 = i * 3
    const dx = positions[i3] - center.x || (Math.random() - 0.5) * 0.6
    const dy = positions[i3 + 1] - center.y || (Math.random() - 0.5) * 0.6
    const dz = positions[i3 + 2] - center.z || (Math.random() - 0.5) * 0.6
    const length = Math.sqrt(dx * dx + dy * dy + dz * dz) || 1
    const force = 5 + Math.random() * 8
    velocities[i3] += (dx / length) * force
    velocities[i3 + 1] += (dy / length) * force
    velocities[i3 + 2] += (dz / length) * force
  }
}

// 俯仰限位，避免上下翻转穿底
function clampPitch(value) {
  return Math.max(-PITCH_LIMIT, Math.min(PITCH_LIMIT, value))
}

// 每帧更新物理和渲染
function animate() {
  rafId = window.requestAnimationFrame(animate)
  const elapsed = clock.getElapsedTime()
  material.uniforms.uTime.value = elapsed

  // 3D 形态持续自转；平面文字形态回正到正面并轻微摇摆，避免文字转到侧面不可读
  if (shapeMeta[activeIndex.value].flat) {
    const sway = Math.sin(elapsed * 0.35) * 0.18
    spinGroup.rotation.y += (sway - spinGroup.rotation.y) * 0.04
  } else {
    spinGroup.rotation.y += 0.0018
  }
  const tiltX = mouse.active ? -mouse.ndcY * 0.14 : 0
  const tiltY = mouse.active ? mouse.ndcX * 0.18 : 0
  tiltGroup.rotation.x += (tiltX - tiltGroup.rotation.x) * 0.04
  tiltGroup.rotation.y += (tiltY - tiltGroup.rotation.y) * 0.04

  // 松手后按残余角速度继续旋转并衰减，形成甩动惯性
  if (!drag.active) {
    drag.yaw += drag.velYaw
    drag.pitch = clampPitch(drag.pitch + drag.velPitch)
    drag.velYaw *= DRAG_INERTIA_DECAY
    drag.velPitch *= DRAG_INERTIA_DECAY
    // 文字形态静止后缓慢回正到正面，保证可读
    if (shapeMeta[activeIndex.value].flat
      && Math.abs(drag.velYaw) < 0.0006 && Math.abs(drag.velPitch) < 0.0006) {
      drag.yaw += (0 - drag.yaw) * DRAG_RETURN_SPEED
      drag.pitch += (0 - drag.pitch) * DRAG_RETURN_SPEED
    }
  }
  orbitGroup.rotation.y = drag.yaw
  orbitGroup.rotation.x = drag.pitch

  // 滚轮缩放：相机 Z 平滑逼近目标距离
  camera.position.z += (zoom.target - camera.position.z) * 0.12

  updateMouseLocal()
  integrateParticles()

  geometry.attributes.position.needsUpdate = true
  geometry.attributes.aColor.needsUpdate = true
  renderer.render(scene, camera)
}

// 把鼠标位置换算到粒子所在的本地坐标系
function updateMouseLocal() {
  if (!mouse.active) {
    return
  }
  const ndc = new THREE.Vector3(mouse.ndcX, mouse.ndcY, 0.5).unproject(camera)
  const dir = ndc.sub(camera.position).normalize()
  const distance = -camera.position.z / dir.z
  if (distance > 0) {
    mouse.world.copy(camera.position).addScaledVector(dir, distance)
    mouse.local.copy(mouse.world)
    spinGroup.worldToLocal(mouse.local)
  }
}

// 弹簧吸附 + 鼠标排斥 + 颜色渐变的粒子积分
function integrateParticles() {
  const repelSquared = REPEL_RADIUS * REPEL_RADIUS
  const repelling = mouse.active && !drag.active
  const mx = mouse.local.x
  const my = mouse.local.y
  const mz = mouse.local.z

  for (let i = 0; i < COUNT; i++) {
    const i3 = i * 3
    let vx = (velocities[i3] + (targetPositions[i3] - positions[i3]) * STIFFNESS) * DAMPING
    let vy = (velocities[i3 + 1] + (targetPositions[i3 + 1] - positions[i3 + 1]) * STIFFNESS) * DAMPING
    let vz = (velocities[i3 + 2] + (targetPositions[i3 + 2] - positions[i3 + 2]) * STIFFNESS) * DAMPING

    if (repelling) {
      const dx = positions[i3] - mx
      const dy = positions[i3 + 1] - my
      const dz = positions[i3 + 2] - mz
      const d2 = dx * dx + dy * dy + dz * dz
      if (d2 < repelSquared && d2 > 0.01) {
        const d = Math.sqrt(d2)
        const falloff = 1 - d / REPEL_RADIUS
        const force = falloff * falloff * REPEL_FORCE / d
        vx += dx * force
        vy += dy * force
        vz += dz * force
      }
    }

    velocities[i3] = vx
    velocities[i3 + 1] = vy
    velocities[i3 + 2] = vz
    positions[i3] += vx
    positions[i3 + 1] += vy
    positions[i3 + 2] += vz

    colors[i3] += (targetColors[i3] - colors[i3]) * 0.035
    colors[i3 + 1] += (targetColors[i3 + 1] - colors[i3 + 1]) * 0.035
    colors[i3 + 2] += (targetColors[i3 + 2] - colors[i3 + 2]) * 0.035
  }
}

// 用离屏 canvas 采样文字像素，得到文字形态的粒子目标
// 采样结果做质量自检：过空或过实都说明字体渲染异常，自动换字体栈重试一次
function sampleText(text, fontStack) {
  const first = paintTextPixels(text, fontStack)
  const second = first.quality < 0.15 || first.quality > 0.85
    ? paintTextPixels(text, fontStack === CJK_TEXT_FONT ? LATIN_TEXT_FONT : CJK_TEXT_FONT)
    : null
  const best = second && second.quality > first.quality ? second : first

  const out = new Float32Array(COUNT * 3)
  if (best.candidates.length === 0 || best.quality > 0.85) {
    return makeSphereFallback(out)
  }
  const scale = 250 / best.measured
  const candidateCount = best.candidates.length / 2
  for (let i = 0; i < COUNT; i++) {
    const pick = Math.floor(Math.random() * candidateCount) * 2
    out[i * 3] = (best.candidates[pick] - 700) * scale
    out[i * 3 + 1] = -(best.candidates[pick + 1] - 210) * scale
    out[i * 3 + 2] = (Math.random() - 0.5) * 22
  }
  return out
}

// 在离屏画布上绘制文字并收集不透明像素样本，quality 为采样命中占包围盒的比例
function paintTextPixels(text, fontStack) {
  const canvas = document.createElement('canvas')
  canvas.width = 1400
  canvas.height = 420
  const ctx = canvas.getContext('2d')
  let fontSize = 300
  ctx.font = `900 ${fontSize}px ${fontStack}`
  let measured = ctx.measureText(text).width
  if (measured > 1280) {
    fontSize = Math.floor((fontSize * 1280) / measured)
    ctx.font = `900 ${fontSize}px ${fontStack}`
    measured = ctx.measureText(text).width
  }
  ctx.textAlign = 'center'
  ctx.textBaseline = 'middle'
  ctx.fillStyle = '#ffffff'
  ctx.fillText(text, canvas.width / 2, canvas.height / 2)

  const data = ctx.getImageData(0, 0, canvas.width, canvas.height).data
  const candidates = []
  let minX = canvas.width
  let maxX = 0
  let minY = canvas.height
  let maxY = 0
  for (let y = 0; y < canvas.height; y += 2) {
    for (let x = 0; x < canvas.width; x += 2) {
      if (data[(y * canvas.width + x) * 4 + 3] > 140) {
        candidates.push(x, y)
        if (x < minX) minX = x
        if (x > maxX) maxX = x
        if (y < minY) minY = y
        if (y > maxY) maxY = y
      }
    }
  }

  const boxWidth = (maxX - minX) / 2 + 1
  const boxHeight = (maxY - minY) / 2 + 1
  const quality = candidates.length / 2 / (boxWidth * boxHeight)
  return { candidates, measured, quality }
}

// 四条旋臂的螺旋星系
function makeGalaxy() {
  const out = new Float32Array(COUNT * 3)
  const arms = 4
  for (let i = 0; i < COUNT; i++) {
    const r = Math.pow(Math.random(), 0.65) * 190 + 6
    const angle = (i % arms / arms) * Math.PI * 2 + r * 0.032 + (Math.random() - 0.5) * (1.15 - r / 280)
    const thickness = 9 * (1 - r / 240) + 2
    out[i * 3] = Math.cos(angle) * r + gauss(7)
    out[i * 3 + 1] = Math.sin(angle) * r * 0.92 + gauss(7)
    out[i * 3 + 2] = gauss(thickness)
  }
  return out
}

// 三叶环面结（p=2, q=3）
function makeTorusKnot() {
  const out = new Float32Array(COUNT * 3)
  const scale = 52
  for (let i = 0; i < COUNT; i++) {
    const t = Math.random() * Math.PI * 2
    const tube = Math.sqrt(Math.random()) * 11
    const tubeAngle = Math.random() * Math.PI * 2
    const cx = (2 + Math.cos(3 * t)) * Math.cos(2 * t)
    const cy = (2 + Math.cos(3 * t)) * Math.sin(2 * t)
    const cz = Math.sin(3 * t)
    out[i * 3] = cx * scale + Math.cos(tubeAngle) * tube
    out[i * 3 + 1] = cy * scale + Math.sin(tubeAngle) * tube
    out[i * 3 + 2] = cz * scale + gauss(6)
  }
  return out
}

// DNA 双螺旋与碱基横档
function makeDNA() {
  const out = new Float32Array(COUNT * 3)
  const height = 230
  const radius = 40
  const turns = 3.2
  const rungCount = 34
  for (let i = 0; i < COUNT; i++) {
    if (Math.random() < 0.62) {
      const u = Math.random()
      const angle = u * Math.PI * 2 * turns + (i % 2) * Math.PI
      out[i * 3] = Math.cos(angle) * radius + gauss(2.2)
      out[i * 3 + 1] = (u - 0.5) * height + gauss(2)
      out[i * 3 + 2] = Math.sin(angle) * radius + gauss(2.2)
    } else {
      const step = Math.floor(Math.random() * rungCount)
      const u = (step + 0.5) / rungCount
      const angle = u * Math.PI * 2 * turns
      const across = Math.random() * 2 - 1
      out[i * 3] = Math.cos(angle) * radius * across + gauss(1.4)
      out[i * 3 + 1] = (u - 0.5) * height + gauss(1.4)
      out[i * 3 + 2] = Math.sin(angle) * radius * across + gauss(1.4)
    }
  }
  return out
}

// 心形曲线填充
function makeHeart() {
  const out = new Float32Array(COUNT * 3)
  const scale = 7.6
  for (let i = 0; i < COUNT; i++) {
    const t = Math.random() * Math.PI * 2
    const fill = Math.sqrt(Math.random())
    const hx = 16 * Math.pow(Math.sin(t), 3)
    const hy = 13 * Math.cos(t) - 5 * Math.cos(2 * t) - 2 * Math.cos(3 * t) - Math.cos(4 * t)
    out[i * 3] = hx * fill * scale + gauss(1.2)
    out[i * 3 + 1] = hy * fill * scale + gauss(1.2)
    out[i * 3 + 2] = gauss(9)
  }
  return out
}

// 按调色板生成带亮度抖动的粒子颜色
function buildColors(palette) {
  const out = new Float32Array(COUNT * 3)
  const tmp = new THREE.Color()
  for (let i = 0; i < COUNT; i++) {
    tmp.set(palette[Math.floor(Math.random() * palette.length)])
    const jitter = 0.8 + Math.random() * 0.4
    out[i * 3] = Math.min(1, tmp.r * jitter)
    out[i * 3 + 1] = Math.min(1, tmp.g * jitter)
    out[i * 3 + 2] = Math.min(1, tmp.b * jitter)
  }
  return out
}

// 采样失败时的随机球面兜底
function makeSphereFallback(out) {
  for (let i = 0; i < COUNT; i++) {
    const theta = Math.random() * Math.PI * 2
    const phi = Math.acos(2 * Math.random() - 1)
    const radius = 130
    out[i * 3] = Math.sin(phi) * Math.cos(theta) * radius
    out[i * 3 + 1] = Math.sin(phi) * Math.sin(theta) * radius
    out[i * 3 + 2] = Math.cos(phi) * radius
  }
  return out
}

// 近似高斯的随机抖动
function gauss(spread) {
  return ((Math.random() * 2 - 1) + (Math.random() * 2 - 1)) * 0.5 * spread
}
</script>

<style scoped>
.particle-show {
  position: relative;
  width: 100%;
  height: 100%;
  min-height: 560px;
  overflow: hidden;
  background:
    radial-gradient(ellipse 46% 38% at 70% 22%, rgba(48, 73, 139, 0.2), transparent 66%),
    radial-gradient(ellipse 40% 34% at 24% 76%, rgba(229, 160, 68, 0.07), transparent 62%),
    linear-gradient(180deg, #050711 0%, #02030a 55%, #000105 100%);
}

.show-canvas {
  position: absolute;
  inset: 0;
  display: block;
  touch-action: none;
  cursor: grab;
}

.show-canvas.dragging {
  cursor: grabbing;
}

.show-header {
  position: absolute;
  top: 28px;
  left: 32px;
  z-index: 2;
  pointer-events: none;
  user-select: none;
}

.show-eyebrow {
  display: block;
  color: var(--primary-color, #e5a044);
  font-size: 11px;
  font-weight: 700;
  letter-spacing: 0.32em;
  text-transform: uppercase;
}

.show-title {
  margin: 6px 0 8px;
  color: #f5f7ff;
  font-size: 30px;
  font-weight: 850;
  letter-spacing: 0.04em;
  text-shadow: 0 0 24px rgba(94, 194, 255, 0.35);
}

.show-desc {
  margin: 0;
  max-width: 460px;
  color: rgba(214, 222, 240, 0.62);
  font-size: 13px;
  line-height: 1.7;
}

.show-toolbar {
  position: absolute;
  bottom: 34px;
  left: 50%;
  z-index: 2;
  display: flex;
  flex-wrap: wrap;
  justify-content: center;
  gap: 8px;
  padding: 8px;
  border: 1px solid rgba(126, 156, 214, 0.18);
  border-radius: 14px;
  background: rgba(10, 13, 22, 0.55);
  backdrop-filter: blur(14px);
  transform: translateX(-50%);
}

.shape-button {
  display: inline-flex;
  align-items: center;
  gap: 7px;
  min-height: 32px;
  padding: 0 13px;
  border: 1px solid rgba(126, 156, 214, 0.2);
  border-radius: 9px;
  color: rgba(210, 220, 240, 0.72);
  background: transparent;
  font-size: 13px;
  font-weight: 650;
  cursor: pointer;
  transition:
    color 0.2s ease,
    border-color 0.2s ease,
    background 0.2s ease,
    box-shadow 0.2s ease;
}

.shape-button:hover {
  color: #f5f7ff;
  border-color: rgba(126, 156, 214, 0.42);
}

.shape-button.active {
  color: #0a0c12;
  border-color: rgba(229, 160, 68, 0.8);
  background: linear-gradient(135deg, #ffd9a0, #e5a044);
  box-shadow: 0 0 18px rgba(229, 160, 68, 0.35);
}

.auto-button.active {
  color: #eaf6ff;
  border-color: rgba(94, 194, 255, 0.7);
  background: rgba(94, 194, 255, 0.14);
  box-shadow: 0 0 18px rgba(94, 194, 255, 0.28);
}

.shape-index {
  font-size: 11px;
  font-weight: 800;
  opacity: 0.78;
}

.show-progress {
  position: absolute;
  bottom: 0;
  left: 0;
  width: 100%;
  height: 2px;
  background: rgba(126, 156, 214, 0.12);
}

.show-progress-bar {
  height: 100%;
  background: linear-gradient(90deg, rgba(94, 194, 255, 0.85), rgba(229, 160, 68, 0.9));
  box-shadow: 0 0 10px rgba(229, 160, 68, 0.45);
  transition: width 0.12s linear;
}

@media (max-width: 920px) {
  .show-header {
    top: 18px;
    left: 18px;
  }

  .show-title {
    font-size: 24px;
  }

  .show-desc {
    font-size: 12px;
  }

  .show-toolbar {
    bottom: 20px;
    max-width: calc(100% - 24px);
  }
}
</style>
