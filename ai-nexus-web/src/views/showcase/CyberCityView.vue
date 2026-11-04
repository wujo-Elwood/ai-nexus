<template>
  <div ref="mountRef" class="cyber-city">
    <div class="city-header">
      <span class="city-eyebrow">Neon Street</span>
      <h1 class="city-title">赛博都市</h1>
      <p class="city-desc">雨夜霓虹峡谷 · 移动鼠标环视 · 滚轮推拉视野 · 下方切换速度 / 雨幕 / 色板</p>
    </div>

    <div class="city-toolbar">
      <button type="button" class="city-button" @click="cycleSpeed">
        <span class="city-button-key">速度</span>
        <span>{{ speedLabels[speedIndex] }}</span>
      </button>
      <button
        type="button"
        class="city-button"
        :class="{ active: rainOn }"
        @click="toggleRain"
      >
        <span class="city-button-key">雨幕</span>
        <span>{{ rainOn ? '开' : '关' }}</span>
      </button>
      <button type="button" class="city-button" @click="cyclePalette">
        <span class="city-button-key">色板</span>
        <span>{{ PALETTES[paletteIndex].name }}</span>
      </button>
    </div>
  </div>
</template>

<script setup>
import { onBeforeUnmount, onMounted, ref } from 'vue'
import * as THREE from 'three'

const mountRef = ref(null)
const speedLabels = ['慢', '中', '快']
const speedIndex = ref(1)
const rainOn = ref(true)
const paletteIndex = ref(0)

const SPEEDS = [14, 32, 66]
const BUILDING_COUNT = 620
const RAIN_COUNT = 1800
const SPAN = 620
const NEAR_Z = 30
const FOG_COLOR = new THREE.Color('#160722')
const FOG_NEAR = 30
const FOG_FAR = 430

// 色板只影响楼体基调与地面网格，招牌本身保持粉/青/紫/琥珀霓虹
const PALETTES = [
  { name: '粉青', tint: '#e879f9', grid: '#22d3ee' },
  { name: '霓虹', tint: '#f472b6', grid: '#a855f7' },
  { name: '冰蓝', tint: '#38bdf8', grid: '#22d3ee' }
]

let renderer = null
let scene = null
let camera = null
let clock = null
let rafId = 0
let resizeObserver = null

let buildingGeometry = null
let buildingMaterial = null
let reflectionMaterial = null
let buildingMesh = null
let reflectionMesh = null
let tintPick = null

let roadMesh = null
let roadMaterial = null
let roadGeometry = null

let glowMesh = null
let glowMaterial = null
let glowGeometry = null

let rainPoints = null
let rainMaterial = null
let rainGeometry = null

const pointer = { x: 0, y: 0, tx: 0, ty: 0 }

onMounted(() => {
  initScene()
  buildBuildings()
  buildRoad()
  buildGlow()
  buildRain()
  applyPalette()
  bindEvents()
  clock = new THREE.Clock()
  animate()
})

onBeforeUnmount(() => {
  window.cancelAnimationFrame(rafId)
  const container = mountRef.value
  if (container) {
    container.removeEventListener('pointermove', handlePointerMove)
    container.removeEventListener('wheel', handleWheel)
  }
  if (resizeObserver) {
    resizeObserver.disconnect()
  }
  ;[buildingGeometry, roadGeometry, glowGeometry, rainGeometry].forEach(g => g && g.dispose())
  ;[buildingMaterial, reflectionMaterial, roadMaterial, glowMaterial, rainMaterial].forEach(m => m && m.dispose())
  if (renderer) {
    renderer.dispose()
    renderer.domElement.remove()
  }
})

function initScene() {
  const container = mountRef.value
  renderer = new THREE.WebGLRenderer({ alpha: true, antialias: true, powerPreference: 'high-performance' })
  renderer.setPixelRatio(Math.min(window.devicePixelRatio, 2))
  renderer.setSize(container.clientWidth, container.clientHeight)
  renderer.setClearColor(0x000000, 0)
  renderer.domElement.classList.add('city-canvas')
  container.appendChild(renderer.domElement)

  scene = new THREE.Scene()
  camera = new THREE.PerspectiveCamera(62, container.clientWidth / container.clientHeight, 0.5, 1400)
  camera.position.set(0, 7, 0)
  camera.lookAt(0, 14, -200)
}

// 立面着色器：真实楼体与地面倒影共用一套逻辑，refl 分支控制镜像/涟漪/衰减
function facadeVertex(refl) {
  return `
    attribute vec3 aOffset;
    attribute vec3 aSize;
    attribute float aSeed;
    attribute vec3 aTint;
    uniform float uTime;
    uniform float uSpeed;
    uniform float uSpan;
    uniform float uNearZ;
    varying vec3 vNormal;
    varying vec3 vLocal;
    varying vec3 vSize;
    varying float vSeed;
    varying vec3 vTint;
    varying float vViewZ;
    varying float vGroundY;
    void main() {
      vNormal = normal;
      vLocal = position;
      vSize = aSize;
      vSeed = aSeed;
      vTint = aTint;
      vec3 scaled = position * aSize;
      scaled.y = scaled.y + aSize.y * 0.5;
      ${refl ? 'scaled.y = -scaled.y;' : ''}
      float z = uNearZ - mod(aOffset.z - uTime * uSpeed, uSpan);
      vec3 world = vec3(aOffset.x, scaled.y, z + scaled.z);
      vGroundY = abs(scaled.y);
      vec4 mv = viewMatrix * vec4(world, 1.0);
      vViewZ = mv.z;
      gl_Position = projectionMatrix * mv;
    }
  `
}

function facadeFragment(refl) {
  return `
    varying vec3 vNormal;
    varying vec3 vLocal;
    varying vec3 vSize;
    varying float vSeed;
    varying vec3 vTint;
    varying float vViewZ;
    varying float vGroundY;
    uniform float uTime;
    uniform vec3 uFogColor;
    uniform float uFogNear;
    uniform float uFogFar;
    float hash21(vec2 p) {
      return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453123);
    }
    vec3 neon(float h) {
      vec3 a = vec3(1.0, 0.16, 0.56);
      vec3 b = vec3(0.16, 0.9, 1.0);
      vec3 c = vec3(0.62, 0.34, 1.0);
      vec3 d = vec3(1.0, 0.64, 0.22);
      return h < 0.3 ? a : (h < 0.55 ? b : (h < 0.8 ? c : d));
    }
    void main() {
      float up = vLocal.y + 0.5;
      float wy = up * vSize.y;
      ${refl ? 'float ru = sin(vGroundY * 0.3 + uTime * 2.0) * 1.2;' : 'float ru = 0.0;'}
      float u = (abs(vNormal.x) > 0.5 ? (vLocal.z * vSize.z) : (vLocal.x * vSize.x)) + ru;
      float ww = abs(vNormal.x) > 0.5 ? vSize.z : vSize.x;
      vec3 col = vec3(0.012, 0.013, 0.028);
      if (abs(vNormal.y) > 0.5) {
        col = vec3(0.008, 0.009, 0.02);
      } else {
        float cw = 3.2;
        float ch = 4.2;
        vec2 cell = vec2((u + ww * 0.5) / cw, wy / ch);
        vec2 cid = floor(cell);
        vec2 f = fract(cell);
        vec2 dd = abs(f - 0.5) - vec2(0.22, 0.2);
        float win = smoothstep(0.05, 0.0, max(dd.x, dd.y));
        float r = hash21(cid + vSeed * 91.7);
        float lit = step(0.6, r);
        float wh = hash21(cid * 2.3 + vSeed);
        vec3 wcol = wh < 0.55 ? vec3(1.0, 0.82, 0.55) : (wh < 0.82 ? vec3(0.62, 0.85, 1.0) : neon(hash21(cid * 3.7 + vSeed)));
        float fl = hash21(cid * 1.7 + vSeed);
        float flick = 1.0;
        if (fl > 0.94) {
          flick = 0.45 + 0.55 * step(0.5, fract(uTime * (1.0 + fl * 4.0) + fl * 20.0));
        }
        float bright = lit * flick * (0.25 + 0.5 * hash21(cid + 13.0));
        col += win * wcol * bright;

        float stripOn = step(0.4, hash21(vec2(vSeed, 9.0)));
        float sx = (hash21(vec2(vSeed, 13.0)) - 0.5) * ww * 0.7;
        float strip = (1.0 - smoothstep(0.0, 1.2, abs(u - sx))) * stripOn;
        float stripH = step(vSize.y * 0.1, wy) * step(wy, vSize.y * 0.98);
        col += strip * stripH * neon(hash21(vec2(vSeed, 11.0))) * (0.7 + 0.3 * sin(uTime * 3.0 + vSeed * 10.0)) * 1.3;

        float band = floor(wy / 9.0);
        float hasSign = step(0.72, hash21(vec2(vSeed, band)));
        float fb = fract(wy / 9.0);
        float inBand = step(0.15, fb) * step(fb, 0.7);
        float span = step(ww * 0.18, abs(u)) * step(abs(u), ww * 0.46);
        float glyph = step(0.45, fract(u * 0.9 + band * 2.0));
        col += hasSign * inBand * span * neon(hash21(vec2(vSeed, band * 1.7))) * (0.5 + 0.7 * glyph) * 1.1;

        col *= mix(0.55, 1.0, up);
      }
      float fogF = smoothstep(uFogNear, uFogFar, -vViewZ);
      ${refl
        ? 'float fade = exp(-vGroundY * 0.024) * (1.0 - fogF); gl_FragColor = vec4(col * fade * 0.3, 1.0);'
        : 'col = mix(col, uFogColor, fogF * 0.92); gl_FragColor = vec4(col, 1.0);'}
    }
  `
}

function facadeUniforms() {
  return {
    uTime: { value: 0 },
    uSpeed: { value: SPEEDS[speedIndex.value] },
    uSpan: { value: SPAN },
    uNearZ: { value: NEAR_Z },
    uFogColor: { value: FOG_COLOR },
    uFogNear: { value: FOG_NEAR },
    uFogFar: { value: FOG_FAR }
  }
}

// 街道峡谷：楼体紧贴道路两侧，密不透风的霓虹立面
function buildBuildings() {
  buildingGeometry = new THREE.BoxGeometry(1, 1, 1)
  const offsets = new Float32Array(BUILDING_COUNT * 3)
  const sizes = new Float32Array(BUILDING_COUNT * 3)
  const seeds = new Float32Array(BUILDING_COUNT)
  const tints = new Float32Array(BUILDING_COUNT * 3)
  tintPick = new Float32Array(BUILDING_COUNT)
  const tmp = new THREE.Color()

  for (let i = 0; i < BUILDING_COUNT; i++) {
    const sign = Math.random() < 0.5 ? -1 : 1
    const x = sign * (12 + Math.pow(Math.random(), 0.6) * 62)
    offsets[i * 3] = x
    offsets[i * 3 + 1] = 0
    offsets[i * 3 + 2] = Math.random() * SPAN
    const w = 12 + Math.random() * 20
    const d = 12 + Math.random() * 20
    const h = 60 + Math.pow(Math.random(), 1.5) * 230
    sizes[i * 3] = w
    sizes[i * 3 + 1] = h
    sizes[i * 3 + 2] = d
    seeds[i] = Math.random() * 100
    tintPick[i] = Math.random()
    tmp.set(PALETTES[0].tint)
    tints[i * 3] = tmp.r
    tints[i * 3 + 1] = tmp.g
    tints[i * 3 + 2] = tmp.b
  }

  buildingGeometry.setAttribute('aOffset', new THREE.InstancedBufferAttribute(offsets, 3))
  buildingGeometry.setAttribute('aSize', new THREE.InstancedBufferAttribute(sizes, 3))
  buildingGeometry.setAttribute('aSeed', new THREE.InstancedBufferAttribute(seeds, 1))
  buildingGeometry.setAttribute('aTint', new THREE.InstancedBufferAttribute(tints, 3))

  buildingMaterial = new THREE.ShaderMaterial({
    uniforms: facadeUniforms(),
    vertexShader: facadeVertex(false),
    fragmentShader: facadeFragment(false)
  })
  buildingMesh = new THREE.InstancedMesh(buildingGeometry, buildingMaterial, BUILDING_COUNT)
  buildingMesh.frustumCulled = false
  buildingMesh.renderOrder = 2
  scene.add(buildingMesh)

  // 湿地倒影：镜像楼体，加法混合，贴着路面随距离衰减并带涟漪
  reflectionMaterial = new THREE.ShaderMaterial({
    uniforms: facadeUniforms(),
    vertexShader: facadeVertex(true),
    fragmentShader: facadeFragment(true),
    transparent: true,
    depthWrite: false,
    depthTest: false,
    blending: THREE.AdditiveBlending
  })
  reflectionMesh = new THREE.InstancedMesh(buildingGeometry, reflectionMaterial, BUILDING_COUNT)
  reflectionMesh.frustumCulled = false
  reflectionMesh.renderOrder = 1
  scene.add(reflectionMesh)
}

// 湿滑柏油路：暗底 + 极淡滚动网格 + 雾
function buildRoad() {
  roadGeometry = new THREE.PlaneGeometry(3000, 3000)
  roadGeometry.rotateX(-Math.PI / 2)
  roadMaterial = new THREE.ShaderMaterial({
    uniforms: {
      uTime: { value: 0 },
      uSpeed: { value: SPEEDS[speedIndex.value] },
      uGridColor: { value: new THREE.Color(PALETTES[0].grid) },
      uFogColor: { value: FOG_COLOR },
      uFogNear: { value: FOG_NEAR },
      uFogFar: { value: FOG_FAR }
    },
    vertexShader: `
      varying vec3 vWorld;
      varying float vViewZ;
      void main() {
        vec4 wp = modelMatrix * vec4(position, 1.0);
        vWorld = wp.xyz;
        vec4 mv = viewMatrix * wp;
        vViewZ = mv.z;
        gl_Position = projectionMatrix * mv;
      }
    `,
    fragmentShader: `
      varying vec3 vWorld;
      varying float vViewZ;
      uniform float uTime;
      uniform float uSpeed;
      uniform vec3 uGridColor;
      uniform vec3 uFogColor;
      uniform float uFogNear;
      uniform float uFogFar;
      void main() {
        float sz = vWorld.z + uTime * uSpeed;
        vec2 g = vec2(vWorld.x, sz) / 24.0;
        vec2 gf = abs(fract(g - 0.5) - 0.5) / fwidth(g);
        float line = 1.0 - min(min(gf.x, gf.y), 1.0);
        vec3 col = vec3(0.012, 0.014, 0.028) + line * uGridColor * 0.12;
        float fogF = smoothstep(uFogNear, uFogFar, -vViewZ);
        col = mix(col, uFogColor, fogF);
        gl_FragColor = vec4(col, 1.0);
      }
    `
  })
  roadMaterial.extensions = { derivatives: true }
  roadMesh = new THREE.Mesh(roadGeometry, roadMaterial)
  roadMesh.frustumCulled = false
  roadMesh.renderOrder = 0
  scene.add(roadMesh)
}

// 街道尽头的霓虹光污染辉光（billboard 加法混合，随呼吸微亮）
function buildGlow() {
  glowGeometry = new THREE.PlaneGeometry(640, 380)
  glowMaterial = new THREE.ShaderMaterial({
    uniforms: {
      uTime: { value: 0 },
      uColor: { value: new THREE.Color('#ff5fae') }
    },
    vertexShader: `
      varying vec2 vUv;
      void main() {
        vUv = uv;
        gl_Position = projectionMatrix * modelViewMatrix * vec4(position, 1.0);
      }
    `,
    fragmentShader: `
      varying vec2 vUv;
      uniform float uTime;
      uniform vec3 uColor;
      void main() {
        vec2 p = vUv - vec2(0.5, 0.42);
        p.y *= 1.35;
        float d = length(p);
        float glow = smoothstep(0.5, 0.0, d);
        glow *= 0.5 + 0.12 * sin(uTime * 1.5);
        gl_FragColor = vec4(uColor * glow, glow);
      }
    `,
    transparent: true,
    depthWrite: false,
    blending: THREE.AdditiveBlending
  })
  glowMesh = new THREE.Mesh(glowGeometry, glowMaterial)
  glowMesh.position.set(0, 26, -430)
  glowMesh.renderOrder = 1
  scene.add(glowMesh)
}

// 雨幕
function buildRain() {
  rainGeometry = new THREE.BufferGeometry()
  const pos = new Float32Array(RAIN_COUNT * 3)
  const phase = new Float32Array(RAIN_COUNT)
  for (let i = 0; i < RAIN_COUNT; i++) {
    pos[i * 3] = (Math.random() * 2 - 1) * 120
    pos[i * 3 + 1] = Math.random() * 160
    pos[i * 3 + 2] = -Math.random() * 520 + 40
    phase[i] = Math.random()
  }
  rainGeometry.setAttribute('position', new THREE.BufferAttribute(pos, 3))
  rainGeometry.setAttribute('aPhase', new THREE.BufferAttribute(phase, 1))
  rainMaterial = new THREE.ShaderMaterial({
    uniforms: { uTime: { value: 0 } },
    vertexShader: `
      attribute float aPhase;
      uniform float uTime;
      varying float vA;
      void main() {
        float fall = 160.0;
        float y = mod(position.y - uTime * 280.0 - aPhase * 160.0, fall);
        vec3 world = vec3(position.x, y, position.z);
        vec4 mv = viewMatrix * vec4(world, 1.0);
        gl_PointSize = 2.0 * (300.0 / -mv.z);
        // 近/远两端各淡出一次，把雨丝限制在 40~200 的可见带内。
        // 写成 1.0 - smoothstep(200, 540, d) 而不是 smoothstep(540, 200, d)：
        // 前者是规范定义的行为，后者属于 edge0 > edge1 的未定义区间。
        // （注：实测本机 ANGLE/SwiftShader 对反序参数返回值与正序一致，
        //   所以这更像一次"把 UB 写成有保证的形式"，而非修复一个已出现的故障。）
        vA = smoothstep(0.0, 40.0, -mv.z) * (1.0 - smoothstep(200.0, 540.0, -mv.z));
        gl_Position = projectionMatrix * mv;
      }
    `,
    fragmentShader: `
      varying float vA;
      void main() {
        vec2 pc = gl_PointCoord - 0.5;
        float streak = smoothstep(0.5, 0.0, abs(pc.x)) * smoothstep(0.5, 0.0, abs(pc.y * 0.3));
        vec3 col = vec3(0.55, 0.7, 1.0);
        gl_FragColor = vec4(col, streak * 0.45 * vA);
      }
    `,
    transparent: true,
    depthWrite: false,
    blending: THREE.AdditiveBlending
  })
  rainPoints = new THREE.Points(rainGeometry, rainMaterial)
  rainPoints.frustumCulled = false
  scene.add(rainPoints)
}

function bindEvents() {
  const container = mountRef.value
  container.addEventListener('pointermove', handlePointerMove)
  container.addEventListener('wheel', handleWheel, { passive: false })
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

function handlePointerMove(event) {
  const rect = renderer.domElement.getBoundingClientRect()
  pointer.tx = ((event.clientX - rect.left) / rect.width) * 2 - 1
  pointer.ty = -(((event.clientY - rect.top) / rect.height) * 2 - 1)
}

function handleWheel(event) {
  event.preventDefault()
  let delta = event.deltaY
  if (event.deltaMode === 1) {
    delta *= 16
  } else if (event.deltaMode === 2) {
    delta *= 100
  }
  camera.position.z = Math.max(-60, Math.min(120, camera.position.z + delta * 0.06))
}

function cycleSpeed() {
  speedIndex.value = (speedIndex.value + 1) % SPEEDS.length
  const s = SPEEDS[speedIndex.value]
  buildingMaterial.uniforms.uSpeed.value = s
  reflectionMaterial.uniforms.uSpeed.value = s
  roadMaterial.uniforms.uSpeed.value = s
}

function toggleRain() {
  rainOn.value = !rainOn.value
  rainPoints.visible = rainOn.value
}

function applyPalette() {
  const palette = PALETTES[paletteIndex.value]
  const tints = buildingGeometry.getAttribute('aTint')
  const tmp = new THREE.Color()
  for (let i = 0; i < BUILDING_COUNT; i++) {
    tmp.set(palette.tint)
    tints.setXYZ(i, tmp.r, tmp.g, tmp.b)
  }
  tints.needsUpdate = true
  roadMaterial.uniforms.uGridColor.value.set(palette.grid)
}

function cyclePalette() {
  paletteIndex.value = (paletteIndex.value + 1) % PALETTES.length
  applyPalette()
}

function animate() {
  rafId = window.requestAnimationFrame(animate)
  const t = clock.getElapsedTime()
  buildingMaterial.uniforms.uTime.value = t
  reflectionMaterial.uniforms.uTime.value = t
  roadMaterial.uniforms.uTime.value = t
  glowMaterial.uniforms.uTime.value = t
  rainMaterial.uniforms.uTime.value = t

  pointer.x += (pointer.tx - pointer.x) * 0.05
  pointer.y += (pointer.ty - pointer.y) * 0.05
  camera.position.x = pointer.x * 9
  camera.position.y = 7 + Math.sin(t * 0.6) * 0.6 + pointer.y * 2.5
  camera.lookAt(camera.position.x * 0.4, 14, -200)

  renderer.render(scene, camera)
}
</script>

<style scoped>
.cyber-city {
  position: relative;
  width: 100%;
  height: 100%;
  min-height: 560px;
  overflow: hidden;
  background:
    radial-gradient(ellipse 80% 46% at 50% 92%, rgba(120, 30, 110, 0.3), transparent 60%),
    linear-gradient(180deg, #04030a 0%, #0a0616 45%, #160826 78%, #2c0e34 100%);
}

.city-canvas {
  position: absolute;
  inset: 0;
  display: block;
  touch-action: none;
  cursor: crosshair;
}

.city-header {
  position: absolute;
  top: 28px;
  left: 32px;
  z-index: 2;
  pointer-events: none;
  user-select: none;
}

.city-eyebrow {
  display: block;
  color: #e879f9;
  font-size: 11px;
  font-weight: 700;
  letter-spacing: 0.32em;
  text-transform: uppercase;
  text-shadow: 0 0 14px rgba(232, 121, 249, 0.6);
}

.city-title {
  margin: 6px 0 8px;
  color: #f5eaff;
  font-size: 30px;
  font-weight: 850;
  letter-spacing: 0.06em;
  text-shadow: 0 0 24px rgba(34, 211, 238, 0.5);
}

.city-desc {
  margin: 0;
  max-width: 520px;
  color: rgba(214, 200, 240, 0.66);
  font-size: 13px;
  line-height: 1.7;
}

.city-toolbar {
  position: absolute;
  bottom: 34px;
  left: 50%;
  z-index: 2;
  display: flex;
  flex-wrap: wrap;
  justify-content: center;
  gap: 8px;
  padding: 8px;
  border: 1px solid rgba(232, 121, 249, 0.22);
  border-radius: 14px;
  background: rgba(20, 8, 34, 0.55);
  backdrop-filter: blur(14px);
  transform: translateX(-50%);
}

.city-button {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  min-height: 32px;
  padding: 0 14px;
  border: 1px solid rgba(126, 156, 214, 0.2);
  border-radius: 9px;
  color: rgba(224, 214, 244, 0.8);
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

.city-button:hover {
  color: #f5eaff;
  border-color: rgba(232, 121, 249, 0.5);
}

.city-button.active {
  color: #0a0618;
  border-color: rgba(34, 211, 238, 0.8);
  background: linear-gradient(135deg, #67e8f9, #22d3ee);
  box-shadow: 0 0 18px rgba(34, 211, 238, 0.4);
}

.city-button-key {
  font-size: 11px;
  font-weight: 800;
  opacity: 0.7;
  letter-spacing: 0.05em;
}

@media (max-width: 920px) {
  .city-header {
    top: 18px;
    left: 18px;
  }

  .city-title {
    font-size: 24px;
  }

  .city-desc {
    font-size: 12px;
  }

  .city-toolbar {
    bottom: 20px;
    max-width: calc(100% - 24px);
  }
}
</style>
