<template>
  <div ref="mountRef" class="storm-view">
    <div class="sv-header">
      <span class="sv-eyebrow">Procedural Thunderstorm</span>
      <h1 class="sv-title">雷暴之眼</h1>
      <p class="sv-desc">点击地面指定落雷点 · 移动鼠标环视 · 闪电为分形分支实时生成</p>
    </div>

    <div class="sv-toolbar">
      <button type="button" class="sv-button" :class="{ active: stormOn }" @click="toggleStorm">
        <span class="sv-button-key">风暴</span>
        <span>{{ stormOn ? '开' : '关' }}</span>
      </button>
      <button type="button" class="sv-button" :class="{ active: rainOn }" @click="toggleRain">
        <span class="sv-button-key">暴雨</span>
        <span>{{ rainOn ? '开' : '关' }}</span>
      </button>
      <button type="button" class="sv-button" @click="strikeRandom">
        <span class="sv-button-key">落雷</span>
        <span>随机一击</span>
      </button>
    </div>
  </div>
</template>

<script setup>
import { onBeforeUnmount, onMounted, ref } from 'vue'
import * as THREE from 'three'
import { LineSegments2 } from 'three/examples/jsm/lines/LineSegments2.js'
import { LineSegmentsGeometry } from 'three/examples/jsm/lines/LineSegmentsGeometry.js'
import { LineMaterial } from 'three/examples/jsm/lines/LineMaterial.js'

const mountRef = ref(null)
const stormOn = ref(true)
const rainOn = ref(true)

// three.js 对象（模块级持有，卸载时统一释放）
let renderer = null
let scene = null
let camera = null
let clock = null
let rafId = 0
let resizeObserver = null

let groundMesh = null
let cityGroup = null
let rainLines = null
let cloudSprites = []
let cloudTexture = null
let coreLines = null
let glowLines = null
let coreMaterial = null
let glowMaterial = null
let ambientLight = null
let flashLight = null

const AMBIENT_BASE = 0.35
const CLOUD_BASE_COLOR = new THREE.Color(0x1a2230)
const CLOUD_FLASH_COLOR = new THREE.Color(0x9db8d4)

// 落雷状态：age 超过寿命后熄灭；restrike 在 0.25s 用同路径抖体重画一次
const strike = { active: false, age: 0, life: 0.75, restriked: false, origin: new THREE.Vector3(), target: new THREE.Vector3() }
let nextAutoStrikeAt = 0

// 雨滴数据
const RAIN_COUNT = 3600
let rainPositions = null
let rainSpeeds = null

// 相机视差
const pointerNdc = new THREE.Vector2(0, 0)
const camTarget = new THREE.Vector3(0, 26, 130)

// 拾取地面用的数学平面（不依赖 mesh，避免射线打空）
const groundPlane = new THREE.Plane(new THREE.Vector3(0, 1, 0), 0)
const raycaster = new THREE.Raycaster()
const tmpVec = new THREE.Vector3()

onMounted(() => {
  initScene()
  buildGround()
  buildCity()
  buildClouds()
  buildRain()
  buildLightningMesh()
  bindEvents()
  clock = new THREE.Clock()
  nextAutoStrikeAt = clock.elapsedTime + 1.2
  animate()
})

onBeforeUnmount(() => {
  window.cancelAnimationFrame(rafId)
  const container = mountRef.value
  if (container) {
    container.removeEventListener('pointermove', handlePointerMove)
    container.removeEventListener('click', handleClick)
  }
  if (resizeObserver) resizeObserver.disconnect()
  if (groundMesh) groundMesh.geometry.dispose()
  if (cityGroup) {
    cityGroup.children.forEach(c => c.geometry.dispose())
    if (cityGroup.userData.material) cityGroup.userData.material.dispose()
  }
  if (rainLines) { rainLines.geometry.dispose(); rainLines.material.dispose() }
  cloudSprites.forEach(s => s.material.dispose())
  if (cloudTexture) cloudTexture.dispose()
  if (coreLines) coreLines.geometry.dispose()
  if (glowLines) glowLines.geometry.dispose()
  if (coreMaterial) coreMaterial.dispose()
  if (glowMaterial) glowMaterial.dispose()
  if (renderer) {
    renderer.dispose()
    renderer.domElement.remove()
  }
})

function initScene() {
  const container = mountRef.value
  renderer = new THREE.WebGLRenderer({ antialias: true, powerPreference: 'high-performance' })
  renderer.setPixelRatio(Math.min(window.devicePixelRatio, 2))
  renderer.setSize(container.clientWidth, container.clientHeight)
  renderer.setClearColor(0x030409, 1)
  renderer.domElement.classList.add('sv-canvas')
  container.appendChild(renderer.domElement)

  scene = new THREE.Scene()
  scene.fog = new THREE.FogExp2(0x04060d, 0.0032)

  camera = new THREE.PerspectiveCamera(55, container.clientWidth / container.clientHeight, 0.5, 3000)
  camera.position.copy(camTarget)
  camera.lookAt(0, 30, -200)

  ambientLight = new THREE.AmbientLight(0x3a4a66, AMBIENT_BASE)
  scene.add(ambientLight)

  // 落雷照明：从云底方向打下来的平行光，平时强度 0，闪击时飙升
  flashLight = new THREE.DirectionalLight(0xcfe4ff, 0)
  flashLight.position.set(0, 200, -120)
  scene.add(flashLight)
}

function buildGround() {
  const geo = new THREE.PlaneGeometry(2400, 2400, 1, 1)
  const mat = new THREE.MeshStandardMaterial({ color: 0x0a0e16, roughness: 1, metalness: 0 })
  groundMesh = new THREE.Mesh(geo, mat)
  groundMesh.rotation.x = -Math.PI / 2
  scene.add(groundMesh)
}

function buildCity() {
  // 地平线上的建筑剪影：纯暗色块，落雷闪光时勾出轮廓
  cityGroup = new THREE.Group()
  const mat = new THREE.MeshStandardMaterial({ color: 0x070a12, roughness: 1 })
  cityGroup.userData.material = mat
  for (let i = 0; i < 64; i += 1) {
    const w = 8 + Math.random() * 22
    const h = 12 + Math.random() * 70
    const d = 8 + Math.random() * 18
    const geo = new THREE.BoxGeometry(w, h, d)
    const box = new THREE.Mesh(geo, mat)
    const x = -560 + Math.random() * 1120
    const z = -380 - Math.random() * 160
    box.position.set(x, h / 2, z)
    cityGroup.add(box)
  }
  scene.add(cityGroup)
}

function makeCloudTexture() {
  const size = 256
  const canvas = document.createElement('canvas')
  canvas.width = size
  canvas.height = size
  const ctx = canvas.getContext('2d')
  const grad = ctx.createRadialGradient(size / 2, size / 2, 10, size / 2, size / 2, size / 2)
  grad.addColorStop(0, 'rgba(255,255,255,0.85)')
  grad.addColorStop(0.55, 'rgba(255,255,255,0.35)')
  grad.addColorStop(1, 'rgba(255,255,255,0)')
  ctx.fillStyle = grad
  ctx.fillRect(0, 0, size, size)
  const tex = new THREE.CanvasTexture(canvas)
  return tex
}

function buildClouds() {
  cloudTexture = makeCloudTexture()
  for (let i = 0; i < 12; i += 1) {
    const mat = new THREE.SpriteMaterial({
      map: cloudTexture,
      color: CLOUD_BASE_COLOR.clone(),
      transparent: true,
      opacity: 0.55 + Math.random() * 0.3,
      depthWrite: false
    })
    const sprite = new THREE.Sprite(mat)
    const s = 320 + Math.random() * 380
    sprite.scale.set(s, s * 0.42, 1)
    sprite.position.set(-700 + Math.random() * 1400, 175 + Math.random() * 55, -560 + Math.random() * 700)
    sprite.userData.drift = 2 + Math.random() * 5
    scene.add(sprite)
    cloudSprites.push(sprite)
  }
}

function buildRain() {
  rainPositions = new Float32Array(RAIN_COUNT * 2 * 3)
  rainSpeeds = new Float32Array(RAIN_COUNT)
  for (let i = 0; i < RAIN_COUNT; i += 1) {
    const x = -320 + Math.random() * 640
    const y = Math.random() * 170
    const z = -320 + Math.random() * 640
    const len = 2.2 + Math.random() * 2.4
    rainPositions.set([x, y, z, x + 0.5, y - len, z], i * 6)
    rainSpeeds[i] = 1.6 + Math.random() * 1.6
  }
  const geo = new THREE.BufferGeometry()
  geo.setAttribute('position', new THREE.BufferAttribute(rainPositions, 3))
  const mat = new THREE.LineBasicMaterial({ color: 0x7fb2d8, transparent: true, opacity: 0.3 })
  rainLines = new THREE.LineSegments(geo, mat)
  scene.add(rainLines)
}

function buildLightningMesh() {
  // 核心白线 + 外围辉光宽线，两层叠加出闪电的“炽芯光晕”
  const emptyGeo = new LineSegmentsGeometry()
  coreMaterial = new LineMaterial({
    color: 0xe8f6ff,
    linewidth: 2.4,
    transparent: true,
    opacity: 0,
    blending: THREE.AdditiveBlending,
    depthWrite: false
  })
  glowMaterial = new LineMaterial({
    color: 0x5ea8ff,
    linewidth: 10,
    transparent: true,
    opacity: 0,
    blending: THREE.AdditiveBlending,
    depthWrite: false
  })
  coreMaterial.resolution.set(window.innerWidth, window.innerHeight)
  glowMaterial.resolution.set(window.innerWidth, window.innerHeight)
  coreLines = new LineSegments2(emptyGeo, coreMaterial)
  glowLines = new LineSegments2(emptyGeo.clone(), glowMaterial)
  // LineSegments2 的动态几何体不更新包围球，开启视锥剔除会被误剔除
  coreLines.frustumCulled = false
  glowLines.frustumCulled = false
  coreLines.visible = false
  glowLines.visible = false
  scene.add(coreLines)
  scene.add(glowLines)
}

// ---------- 分形闪电生成 ----------

function generateBoltSegments(origin, target) {
  const segments = []

  function channel(a, b, displace, intensity, depth) {
    if (depth >= 8 || displace < 0.8) {
      segments.push({ a, b, intensity })
      return
    }
    const mid = a.clone().lerp(b, 0.5)
    mid.x += (Math.random() - 0.5) * displace
    mid.z += (Math.random() - 0.5) * displace
    mid.y += (Math.random() - 0.5) * displace * 0.35
    channel(a, mid, displace * 0.55, intensity, depth + 1)
    channel(mid, b, displace * 0.55, intensity, depth + 1)
    // 概率分叉：从当前中点岔出一条更暗更短的支道
    if (depth < 5 && Math.random() < 0.34 && segments.length < 2600) {
      const end = mid.clone().add(new THREE.Vector3(
        (Math.random() - 0.5) * 70,
        -18 - Math.random() * 42,
        (Math.random() - 0.5) * 70
      ))
      end.y = Math.max(4, end.y)
      channel(mid, end, displace * 0.6, intensity * 0.5, depth + 1)
    }
  }

  channel(origin.clone(), target.clone(), 64, 1, 0)
  return segments
}

function applyBoltGeometry(segments) {
  const corePos = new Float32Array(segments.length * 6)
  const glowPos = new Float32Array(segments.length * 6)
  segments.forEach((s, i) => {
    corePos.set([s.a.x, s.a.y, s.a.z, s.b.x, s.b.y, s.b.z], i * 6)
    glowPos.set([s.a.x, s.a.y, s.a.z, s.b.x, s.b.y, s.b.z], i * 6)
  })
  coreLines.geometry.dispose()
  glowLines.geometry.dispose()
  coreLines.geometry = new LineSegmentsGeometry().setPositions(corePos)
  glowLines.geometry = new LineSegmentsGeometry().setPositions(glowPos)
  coreLines.visible = true
  glowLines.visible = true
}

function triggerStrike(targetPoint) {
  const origin = new THREE.Vector3(
    targetPoint.x + (Math.random() - 0.5) * 90,
    195,
    targetPoint.z + (Math.random() - 0.5) * 60
  )
  strike.origin.copy(origin)
  strike.target.copy(targetPoint)
  strike.target.y = 1
  applyBoltGeometry(generateBoltSegments(origin, strike.target))
  strike.active = true
  strike.age = 0
  strike.restriked = false
}

function strikeRandom() {
  const x = -420 + Math.random() * 840
  const z = -420 + Math.random() * 500
  triggerStrike(new THREE.Vector3(x, 0, z))
}

// 双峰闪烁包络：先亮 → 骤暗 → 再亮 → 衰减
function flashEnvelope(t) {
  const x = t / strike.life
  if (x < 0.06) return x / 0.06
  if (x < 0.16) return 1 - ((x - 0.06) / 0.1) * 0.72
  if (x < 0.26) return 0.28 + ((x - 0.16) / 0.1) * 0.72
  return Math.max(0, 1 - (x - 0.26) / 0.74)
}

// ---------- 事件 ----------

function bindEvents() {
  const container = mountRef.value
  container.addEventListener('pointermove', handlePointerMove)
  container.addEventListener('click', handleClick)

  resizeObserver = new ResizeObserver(() => {
    const w = container.clientWidth
    const h = container.clientHeight
    if (!w || !h) return
    renderer.setSize(w, h)
    camera.aspect = w / h
    camera.updateProjectionMatrix()
    coreMaterial.resolution.set(w, h)
    glowMaterial.resolution.set(w, h)
  })
  resizeObserver.observe(container)
}

function handlePointerMove(event) {
  const rect = mountRef.value.getBoundingClientRect()
  pointerNdc.set(
    ((event.clientX - rect.left) / rect.width) * 2 - 1,
    -((event.clientY - rect.top) / rect.height) * 2 + 1
  )
}

function handleClick(event) {
  const rect = mountRef.value.getBoundingClientRect()
  const ndc = new THREE.Vector2(
    ((event.clientX - rect.left) / rect.width) * 2 - 1,
    -((event.clientY - rect.top) / rect.height) * 2 + 1
  )
  raycaster.setFromCamera(ndc, camera)
  if (raycaster.ray.intersectPlane(groundPlane, tmpVec)) {
    tmpVec.x = THREE.MathUtils.clamp(tmpVec.x, -500, 500)
    tmpVec.z = THREE.MathUtils.clamp(tmpVec.z, -500, 260)
    triggerStrike(tmpVec.clone())
  } else {
    strikeRandom()
  }
}

function toggleStorm() {
  stormOn.value = !stormOn.value
  if (stormOn.value) nextAutoStrikeAt = clock.elapsedTime + 0.6
}

function toggleRain() {
  rainOn.value = !rainOn.value
}

// ---------- 主循环 ----------

function animate() {
  rafId = window.requestAnimationFrame(animate)
  const dt = Math.min(0.05, clock.getDelta())
  const now = clock.elapsedTime

  // 相机视差 + 缓慢漂移
  const driftX = Math.sin(now * 0.08) * 14
  const driftY = Math.sin(now * 0.13) * 4
  camTarget.x += ((pointerNdc.x * 26 + driftX) - camTarget.x) * 0.04
  camTarget.y += ((26 - pointerNdc.y * 10 + driftY) - camTarget.y) * 0.04
  camera.position.copy(camTarget)
  camera.lookAt(0, 34, -220)

  // 云层漂移
  for (const c of cloudSprites) {
    c.position.x += c.userData.drift * dt
    if (c.position.x > 900) c.position.x = -900
  }

  // 雨
  if (rainOn.value) {
    rainLines.visible = true
    for (let i = 0; i < RAIN_COUNT; i += 1) {
      const o = i * 6
      const fall = rainSpeeds[i] * dt * 60
      rainPositions[o + 1] -= fall
      rainPositions[o + 4] -= fall
      rainPositions[o] += 0.35
      rainPositions[o + 3] += 0.35
      if (rainPositions[o + 1] < 0) {
        const x = -320 + Math.random() * 640
        const z = -320 + Math.random() * 640
        const y = 165 + Math.random() * 15
        const len = 2.2 + Math.random() * 2.4
        rainPositions.set([x, y, z, x + 0.5, y - len, z], o)
      }
    }
    rainLines.geometry.attributes.position.needsUpdate = true
  } else {
    rainLines.visible = false
  }

  // 自动落雷
  if (stormOn.value && !strike.active && now >= nextAutoStrikeAt) {
    strikeRandom()
    nextAutoStrikeAt = now + 2.4 + Math.random() * 3.2
  }

  // 落雷演化：亮度包络 + 0.25s 抖体重击
  let flash = 0
  if (strike.active) {
    strike.age += dt
    if (!strike.restriked && strike.age > 0.25) {
      strike.restriked = true
      applyBoltGeometry(generateBoltSegments(strike.origin, strike.target))
    }
    if (strike.age >= strike.life) {
      strike.active = false
      coreLines.visible = false
      glowLines.visible = false
    } else {
      flash = flashEnvelope(strike.age)
      coreMaterial.opacity = flash
      glowMaterial.opacity = flash * 0.5
    }
  }

  // 闪光照明：平行光 + 环境光抬升 + 云层提亮
  flashLight.intensity = flash * 9
  flashLight.position.set(strike.origin.x, 200, strike.origin.z)
  ambientLight.intensity = AMBIENT_BASE + flash * 1.4
  for (const c of cloudSprites) {
    c.material.color.copy(CLOUD_BASE_COLOR).lerp(CLOUD_FLASH_COLOR, flash * 0.85)
  }

  renderer.render(scene, camera)
}
</script>

<style scoped>
.storm-view {
  position: relative;
  width: 100%;
  height: 100%;
  min-height: 560px;
  overflow: hidden;
  background: radial-gradient(ellipse 80% 60% at 50% 30%, #0b1226 0%, #05070f 55%, #020308 100%);
}

.sv-canvas {
  position: absolute;
  inset: 0;
  display: block;
  touch-action: none;
  cursor: crosshair;
}

.sv-header {
  position: absolute;
  top: 28px;
  left: 32px;
  z-index: 2;
  pointer-events: none;
}

.sv-eyebrow {
  display: block;
  font-size: 11px;
  letter-spacing: 3px;
  text-transform: uppercase;
  color: #5f7ba6;
}

.sv-title {
  margin: 6px 0 4px;
  font-size: 26px;
  font-weight: 600;
  color: #dce8ff;
  text-shadow: 0 0 18px rgba(94, 168, 255, 0.35);
}

.sv-desc {
  margin: 0;
  font-size: 13px;
  color: #7d90b5;
}

.sv-toolbar {
  position: absolute;
  top: 28px;
  right: 32px;
  z-index: 2;
  display: flex;
  gap: 10px;
}

.sv-button {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 14px;
  border: 1px solid rgba(94, 168, 255, 0.35);
  border-radius: 8px;
  background: rgba(10, 16, 32, 0.6);
  color: #b9c9e8;
  font-size: 13px;
  cursor: pointer;
  transition: all 0.2s ease;
}

.sv-button:hover {
  border-color: rgba(94, 168, 255, 0.7);
  color: #e6f0ff;
}

.sv-button.active {
  border-color: #5ea8ff;
  background: rgba(94, 168, 255, 0.18);
  color: #eaf4ff;
  box-shadow: 0 0 14px rgba(94, 168, 255, 0.35);
}

.sv-button-key {
  font-size: 11px;
  padding: 1px 6px;
  border: 1px solid rgba(94, 168, 255, 0.4);
  border-radius: 4px;
  color: #8fb4e8;
}
</style>
