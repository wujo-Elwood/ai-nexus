<template>
  <div ref="mountRef" class="aurora-view">
    <div class="au-header">
      <span class="au-eyebrow">Polar Night</span>
      <h1 class="au-title">极光雪原</h1>
      <p class="au-desc">移动鼠标环视 · 极光与落雪由噪声驱动实时演化</p>
    </div>

    <div class="au-toolbar">
      <button type="button" class="au-button" :class="{ active: auroraOn }" @click="toggleAurora">
        <span class="au-button-key">极光</span>
        <span>{{ auroraOn ? '开' : '关' }}</span>
      </button>
      <button type="button" class="au-button" :class="{ active: snowOn }" @click="toggleSnow">
        <span class="au-button-key">落雪</span>
        <span>{{ snowOn ? '开' : '关' }}</span>
      </button>
      <button type="button" class="au-button" :class="{ active: roamOn }" @click="toggleRoam">
        <span class="au-button-key">漫游</span>
        <span>{{ roamOn ? '开' : '关' }}</span>
      </button>
    </div>
  </div>
</template>

<script setup>
import { onBeforeUnmount, onMounted, ref } from 'vue'
import * as THREE from 'three'

const mountRef = ref(null)
const auroraOn = ref(true)
const snowOn = ref(true)
const roamOn = ref(true)

let renderer = null
let scene = null
let camera = null
let clock = null
let rafId = 0
let resizeObserver = null

let terrainMesh = null
let treeGroup = null
let stars = null
let moonSprite = null
let moonTexture = null
let snowPoints = null
let snowTexture = null
let snowflakes = null
const auroraMeshes = []

// 极光配色：底缘亮色 → 顶部过渡色
const AURORA_PRESETS = [
  { bottom: 0x59ffb0, top: 0x7b5cff, baseY: 95, z: -300, width: 760, height: 200, arc: 96, phase: 0 },
  { bottom: 0x3ef2d0, top: 0x2f7bff, baseY: 120, z: -370, width: 940, height: 240, arc: 110, phase: 3.7 },
  { bottom: 0xc06bff, top: 0x3b6bff, baseY: 140, z: -440, width: 1080, height: 260, arc: 120, phase: 7.3 }
]
// 极光与 HemisphereLight 共享同一时间源，保证天光和飘带同步呼吸
const sharedTime = { value: 0 }

let hemiLight = null
let moonLight = null

// 相机视差与漫游
const pointerNdc = new THREE.Vector2(0, 0)
const camLook = new THREE.Vector3(0, 70, -320)

// 雪花数据
const SNOW_COUNT = 2600
let snowPositions = null
let snowDrift = null

// ---------- CPU 噪声（地形生成用） ----------

function hash2(x, y) {
  const s = Math.sin(x * 127.1 + y * 311.7) * 43758.5453
  return s - Math.floor(s)
}

function vnoise(x, y) {
  const ix = Math.floor(x)
  const iy = Math.floor(y)
  const fx = x - ix
  const fy = y - iy
  const ux = fx * fx * (3 - 2 * fx)
  const uy = fy * fy * (3 - 2 * fy)
  const a = hash2(ix, iy)
  const b = hash2(ix + 1, iy)
  const c = hash2(ix, iy + 1)
  const d = hash2(ix + 1, iy + 1)
  return a + (b - a) * ux + (c - a) * uy + (a - b - c + d) * ux * uy
}

function fbm2(x, y) {
  let v = 0
  let amp = 0.55
  let freq = 1
  for (let i = 0; i < 4; i += 1) {
    v += amp * vnoise(x * freq, y * freq)
    freq *= 2.1
    amp *= 0.5
  }
  return v
}

function terrainHeight(x, z) {
  return fbm2(x * 0.004 + 7.3, z * 0.004 + 2.9) * 16 - 5
}

// ---------- 场景构建 ----------

onMounted(() => {
  initScene()
  buildTerrain()
  buildTrees()
  buildStars()
  buildMoon()
  buildAuroras()
  buildSnow()
  bindEvents()
  clock = new THREE.Clock()
  animate()
})

onBeforeUnmount(() => {
  window.cancelAnimationFrame(rafId)
  const container = mountRef.value
  if (container) container.removeEventListener('pointermove', handlePointerMove)
  if (resizeObserver) resizeObserver.disconnect()
  if (terrainMesh) terrainMesh.geometry.dispose()
  if (treeGroup) {
    treeGroup.children.forEach(c => c.geometry.dispose())
    if (treeGroup.userData.material) treeGroup.userData.material.dispose()
  }
  if (stars) { stars.geometry.dispose(); stars.material.dispose() }
  if (snowPoints) { snowPoints.geometry.dispose(); snowPoints.material.dispose() }
  if (snowTexture) snowTexture.dispose()
  if (moonSprite) moonSprite.material.dispose()
  if (moonTexture) moonTexture.dispose()
  auroraMeshes.forEach(m => { m.geometry.dispose(); m.material.dispose() })
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
  renderer.setClearColor(0x050a18, 1)
  renderer.domElement.classList.add('au-canvas')
  container.appendChild(renderer.domElement)

  scene = new THREE.Scene()
  scene.fog = new THREE.FogExp2(0x0a1322, 0.0026)

  camera = new THREE.PerspectiveCamera(58, container.clientWidth / container.clientHeight, 0.5, 3000)
  camera.position.set(0, 11, 190)
  camera.lookAt(camLook)

  // 天光颜色随极光呼吸，让雪原被极光染色；月光作为主光源保持雪的冷白
  hemiLight = new THREE.HemisphereLight(0x2a5f8f, 0x0a1428, 0.4)
  scene.add(hemiLight)
  moonLight = new THREE.DirectionalLight(0xa8bcde, 1.05)
  moonLight.position.set(260, 200, -500)
  scene.add(moonLight)
}

function buildTerrain() {
  const geo = new THREE.PlaneGeometry(1900, 1900, 130, 130)
  geo.rotateX(-Math.PI / 2)
  const pos = geo.attributes.position
  for (let i = 0; i < pos.count; i += 1) {
    pos.setY(i, terrainHeight(pos.getX(i), pos.getZ(i)))
  }
  geo.computeVertexNormals()
  const mat = new THREE.MeshStandardMaterial({ color: 0xc9d8ea, roughness: 0.92, metalness: 0 })
  terrainMesh = new THREE.Mesh(geo, mat)
  scene.add(terrainMesh)
}

function buildTrees() {
  treeGroup = new THREE.Group()
  const mat = new THREE.MeshStandardMaterial({ color: 0x0d1522, roughness: 1 })
  treeGroup.userData.material = mat
  for (let i = 0; i < 60; i += 1) {
    const h = 10 + Math.random() * 16
    const geo = new THREE.ConeGeometry(3 + Math.random() * 3.5, h, 6)
    const cone = new THREE.Mesh(geo, mat)
    const x = -520 + Math.random() * 1040
    const z = -140 - Math.random() * 420
    cone.position.set(x, terrainHeight(x, z) + h / 2, z)
    treeGroup.add(cone)
  }
  scene.add(treeGroup)
}

function buildStars() {
  const COUNT = 1500
  const positions = new Float32Array(COUNT * 3)
  for (let i = 0; i < COUNT; i += 1) {
    // 均匀撒在天球上半部分
    const theta = Math.random() * Math.PI * 2
    const phi = Math.acos(Math.random() * 0.92)
    const r = 920
    positions.set([
      Math.sin(phi) * Math.cos(theta) * r,
      Math.cos(phi) * r * 0.75 + 40,
      Math.sin(phi) * Math.sin(theta) * r
    ], i * 3)
  }
  const geo = new THREE.BufferGeometry()
  geo.setAttribute('position', new THREE.BufferAttribute(positions, 3))
  const mat = new THREE.PointsMaterial({
    color: 0xbfd4ff,
    size: 1.7,
    sizeAttenuation: true,
    transparent: true,
    opacity: 0.85,
    depthWrite: false
  })
  stars = new THREE.Points(geo, mat)
  stars.renderOrder = 1
  scene.add(stars)
}

function makeGlowTexture(inner, outer) {
  const size = 256
  const canvas = document.createElement('canvas')
  canvas.width = size
  canvas.height = size
  const ctx = canvas.getContext('2d')
  const grad = ctx.createRadialGradient(size / 2, size / 2, 4, size / 2, size / 2, size / 2)
  grad.addColorStop(0, inner)
  grad.addColorStop(0.35, outer)
  grad.addColorStop(1, 'rgba(255,255,255,0)')
  ctx.fillStyle = grad
  ctx.fillRect(0, 0, size, size)
  return new THREE.CanvasTexture(canvas)
}

function buildMoon() {
  moonTexture = makeGlowTexture('rgba(255,255,255,1)', 'rgba(190,214,255,0.35)')
  const mat = new THREE.SpriteMaterial({ map: moonTexture, transparent: true, opacity: 0.95, depthWrite: false })
  moonSprite = new THREE.Sprite(mat)
  moonSprite.scale.set(90, 90, 1)
  moonSprite.position.set(330, 230, -700)
  moonSprite.renderOrder = 1
  scene.add(moonSprite)
}

// ---------- 极光飘带 ----------

const AURORA_VERT = `
  varying vec2 vUv;
  uniform float uTime;
  uniform float uPhase;

  float aHash(vec2 p) { return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453123); }
  float aNoise(vec2 p) {
    vec2 i = floor(p);
    vec2 f = fract(p);
    vec2 u = f * f * (3.0 - 2.0 * f);
    return mix(mix(aHash(i), aHash(i + vec2(1.0, 0.0)), u.x),
               mix(aHash(i + vec2(0.0, 1.0)), aHash(i + vec2(1.0, 1.0)), u.x), u.y);
  }

  void main() {
    vUv = uv;
    vec3 pos = position;
    float t = uTime * 0.22 + uPhase;
    pos.y += aNoise(vec2(position.x * 0.008 + t, uPhase)) * 30.0 - 12.0;
    pos.z += aNoise(vec2(position.x * 0.006 - t, uPhase + 9.0)) * 24.0 - 10.0;
    gl_Position = projectionMatrix * modelViewMatrix * vec4(pos, 1.0);
  }
`

const AURORA_FRAG = `
  varying vec2 vUv;
  uniform float uTime;
  uniform float uPhase;
  uniform vec3 uColorBottom;
  uniform vec3 uColorTop;

  float aHash(vec2 p) { return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453123); }
  float aNoise(vec2 p) {
    vec2 i = floor(p);
    vec2 f = fract(p);
    vec2 u = f * f * (3.0 - 2.0 * f);
    return mix(mix(aHash(i), aHash(i + vec2(1.0, 0.0)), u.x),
               mix(aHash(i + vec2(0.0, 1.0)), aHash(i + vec2(1.0, 1.0)), u.x), u.y);
  }
  float aFbm(vec2 p) {
    float v = 0.0;
    float a = 0.5;
    for (int i = 0; i < 4; i++) { v += a * aNoise(p); p *= 2.03; a *= 0.5; }
    return v;
  }

  void main() {
    float t = uTime * 0.18 + uPhase;
    // 沿幕布方向缓慢流动的大尺度亮度
    float flow = aFbm(vec2(vUv.x * 3.2 + t * 0.55, t * 0.32));
    // 垂直光柱条纹，叠加流动产生经典“帘幕”感
    float rays = 0.55 + 0.45 * sin(vUv.x * 46.0 + flow * 9.0 + uPhase * 7.0);
    rays = mix(rays, 1.0, 0.35);
    // 底缘最亮，向上衰减
    float fade = pow(1.0 - vUv.y, 1.6);
    float fringe = smoothstep(0.18, 0.0, vUv.y) * 0.9 + 0.25;
    vec3 col = mix(uColorBottom, uColorTop, clamp(vUv.y * 1.2 + flow * 0.25, 0.0, 1.0));
    float alpha = fade * fringe * rays * flow * 1.35;
    gl_FragColor = vec4(col * (0.8 + fringe), clamp(alpha, 0.0, 0.85));
  }
`

function makeCurtainGeometry(width, height, arcDeg) {
  // 把平面弯成朝向相机的圆弧幕布
  const geo = new THREE.PlaneGeometry(width, height, 200, 12)
  const pos = geo.attributes.position
  const span = THREE.MathUtils.degToRad(arcDeg)
  const radius = width / Math.max(0.001, span)
  for (let i = 0; i < pos.count; i += 1) {
    const u = pos.getX(i) / width + 0.5
    const ang = (u - 0.5) * span
    pos.setXYZ(i, Math.sin(ang) * radius, pos.getY(i), -radius + Math.cos(ang) * radius)
  }
  return geo
}

function buildAuroras() {
  for (const p of AURORA_PRESETS) {
    const mat = new THREE.ShaderMaterial({
      vertexShader: AURORA_VERT,
      fragmentShader: AURORA_FRAG,
      uniforms: {
        uTime: sharedTime,
        uPhase: { value: p.phase },
        uColorBottom: { value: new THREE.Color(p.bottom) },
        uColorTop: { value: new THREE.Color(p.top) }
      },
      transparent: true,
      blending: THREE.AdditiveBlending,
      depthWrite: false,
      side: THREE.DoubleSide
    })
    const mesh = new THREE.Mesh(makeCurtainGeometry(p.width, p.height, p.arc), mat)
    mesh.position.set(0, p.baseY + p.height / 2, p.z)
    mesh.renderOrder = 2
    scene.add(mesh)
    auroraMeshes.push(mesh)
  }
}

// ---------- 落雪 ----------

function buildSnow() {
  snowTexture = makeGlowTexture('rgba(255,255,255,0.95)', 'rgba(255,255,255,0.4)')
  snowPositions = new Float32Array(SNOW_COUNT * 3)
  snowDrift = new Float32Array(SNOW_COUNT)
  for (let i = 0; i < SNOW_COUNT; i += 1) {
    snowPositions.set([
      -360 + Math.random() * 720,
      Math.random() * 190,
      -380 + Math.random() * 760
    ], i * 3)
    snowDrift[i] = Math.random() * Math.PI * 2
  }
  const geo = new THREE.BufferGeometry()
  geo.setAttribute('position', new THREE.BufferAttribute(snowPositions, 3))
  const mat = new THREE.PointsMaterial({
    map: snowTexture,
    color: 0xffffff,
    size: 2.1,
    sizeAttenuation: true,
    transparent: true,
    opacity: 0.85,
    depthWrite: false
  })
  snowPoints = new THREE.Points(geo, mat)
  snowPoints.renderOrder = 3
  scene.add(snowPoints)
}

// ---------- 事件 ----------

function bindEvents() {
  const container = mountRef.value
  container.addEventListener('pointermove', handlePointerMove)
  resizeObserver = new ResizeObserver(() => {
    const w = container.clientWidth
    const h = container.clientHeight
    if (!w || !h) return
    renderer.setSize(w, h)
    camera.aspect = w / h
    camera.updateProjectionMatrix()
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

function toggleAurora() {
  auroraOn.value = !auroraOn.value
  auroraMeshes.forEach(m => { m.visible = auroraOn.value })
}

function toggleSnow() {
  snowOn.value = !snowOn.value
  snowPoints.visible = snowOn.value
}

function toggleRoam() {
  roamOn.value = !roamOn.value
}

// ---------- 主循环 ----------

function animate() {
  rafId = window.requestAnimationFrame(animate)
  const dt = Math.min(0.05, clock.getDelta())
  const now = clock.elapsedTime
  sharedTime.value = now

  // 相机：漫游缓慢漂移 + 鼠标视差
  const sway = roamOn.value ? Math.sin(now * 0.07) * 46 : 0
  const lift = roamOn.value ? Math.sin(now * 0.11) * 6 : 0
  camera.position.x += (pointerNdc.x * 34 + sway - camera.position.x) * 0.03
  camera.position.y += (11 - pointerNdc.y * 6 + lift - camera.position.y) * 0.03
  camLook.x += (pointerNdc.x * 42 - camLook.x) * 0.03
  camLook.y += (70 - pointerNdc.y * 16 - camLook.y) * 0.03
  camera.lookAt(camLook)

  // 极光幕布整体缓慢平移
  if (auroraOn.value) {
    auroraMeshes.forEach((m, i) => {
      m.position.x = Math.sin(now * 0.05 + i * 1.9) * 60
    })
  }

  // 落雪：下落 + 横向摇摆
  if (snowOn.value) {
    for (let i = 0; i < SNOW_COUNT; i += 1) {
      const o = i * 3
      snowPositions[o + 1] -= dt * (5 + (i % 7))
      snowPositions[o] += Math.sin(now * 0.7 + snowDrift[i]) * dt * 3.2
      if (snowPositions[o + 1] < 0) {
        snowPositions[o] = -360 + Math.random() * 720
        snowPositions[o + 1] = 185 + Math.random() * 20
        snowPositions[o + 2] = -380 + Math.random() * 760
      }
    }
    snowPoints.geometry.attributes.position.needsUpdate = true
  }

  // 天光随极光呼吸：底色在青绿与紫之间摆动，饱和度压低避免雪原被染成纯绿
  const breath = 0.5 + 0.5 * Math.sin(now * 0.21)
  hemiLight.color.setHSL(0.38 + breath * 0.18, 0.42, 0.26 + breath * 0.05)
  hemiLight.intensity = 0.34 + breath * 0.2
  if (!auroraOn.value) {
    hemiLight.color.set(0x2a5f8f)
    hemiLight.intensity = 0.3
  }

  renderer.render(scene, camera)
}
</script>

<style scoped>
.aurora-view {
  position: relative;
  width: 100%;
  height: 100%;
  min-height: 560px;
  overflow: hidden;
  background: radial-gradient(ellipse 90% 70% at 50% 20%, #0c1830 0%, #060b1a 55%, #020409 100%);
}

.au-canvas {
  position: absolute;
  inset: 0;
  display: block;
  touch-action: none;
  cursor: crosshair;
}

.au-header {
  position: absolute;
  top: 28px;
  left: 32px;
  z-index: 2;
  pointer-events: none;
}

.au-eyebrow {
  display: block;
  font-size: 11px;
  letter-spacing: 3px;
  text-transform: uppercase;
  color: #5f7ba6;
}

.au-title {
  margin: 6px 0 4px;
  font-size: 26px;
  font-weight: 600;
  color: #dceeff;
  text-shadow: 0 0 18px rgba(89, 255, 176, 0.3);
}

.au-desc {
  margin: 0;
  font-size: 13px;
  color: #7d90b5;
}

.au-toolbar {
  position: absolute;
  top: 28px;
  right: 32px;
  z-index: 2;
  display: flex;
  gap: 10px;
}

.au-button {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 14px;
  border: 1px solid rgba(120, 220, 190, 0.35);
  border-radius: 8px;
  background: rgba(8, 18, 30, 0.6);
  color: #b9d8e8;
  font-size: 13px;
  cursor: pointer;
  transition: all 0.2s ease;
}

.au-button:hover {
  border-color: rgba(120, 220, 190, 0.7);
  color: #e6fff5;
}

.au-button.active {
  border-color: #59ffb0;
  background: rgba(89, 255, 176, 0.14);
  color: #eafff6;
  box-shadow: 0 0 14px rgba(89, 255, 176, 0.3);
}

.au-button-key {
  font-size: 11px;
  padding: 1px 6px;
  border: 1px solid rgba(120, 220, 190, 0.4);
  border-radius: 4px;
  color: #8fd8c0;
}
</style>
