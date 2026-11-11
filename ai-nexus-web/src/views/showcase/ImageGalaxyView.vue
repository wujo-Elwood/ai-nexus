<template>
  <div class="galaxy-view">
    <canvas ref="canvasRef" class="galaxy-canvas"></canvas>

    <div class="gv-header">
      <span class="gv-eyebrow mono">AI Image Corridor</span>
      <h1 class="gv-title serif">图片走廊</h1>
      <p class="gv-desc">你的每一帧生成作品挂在长廊两侧 · 滚轮/拖拽推进 · 点击查看原图</p>
    </div>

    <div class="gv-toolbar mono">
      <button type="button" class="gv-btn" :class="{ active: autoWalk }" @click="autoWalk = !autoWalk">
        <span class="k">漫步</span><span>{{ autoWalk ? '开' : '停' }}</span>
      </button>
      <button type="button" class="gv-btn" @click="resetWalk">
        <span class="k">回到</span><span>入口</span>
      </button>
      <span class="gv-count">图鉴 {{ frames.length }} 帧</span>
    </div>

    <!-- 悬停信息：跟随鼠标的画框铭牌 -->
    <div v-if="hoverInfo" class="gv-tag mono" :style="{ left: hoverInfo.x + 'px', top: hoverInfo.y + 'px' }">
      <b>{{ hoverInfo.prompt }}</b>
    </div>

    <!-- 空态 -->
    <div v-if="!loading && frames.length === 0" class="gv-empty">
      <p>长廊还是空的——去「AI 生图」挂上第一幅作品吧</p>
      <button type="button" class="gv-btn" @click="goGenerate"><span class="k">前往</span><span>AI 生图 →</span></button>
    </div>

    <!-- 加载中 -->
    <div v-if="loading" class="gv-loading mono">正在布置长廊…</div>

    <!-- 预览浮层 -->
    <div v-if="preview" class="gv-preview" @click.self="preview = null">
      <div class="pv-card">
        <img v-if="preview" :src="authedSrcOf(preview.path)" :alt="preview.prompt" class="pv-img">
        <div class="pv-meta">
          <p class="pv-prompt">{{ preview.prompt || '（无提示词）' }}</p>
          <p class="pv-time mono">{{ preview.time }}</p>
          <div class="pv-actions">
            <a v-if="preview" :href="authedSrcOf(preview.path)" download class="gv-btn"><span class="k">打开原图</span><span>↗</span></a>
            <button type="button" class="gv-btn" @click="preview = null"><span class="k">关闭</span><span>×</span></button>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { onBeforeUnmount, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import * as THREE from 'three'
import { getImageHistory } from '../../api/image'
import { authedSrcOf, loadAuthedImage } from '../../utils/authImage'

const router = useRouter()
const canvasRef = ref(null)
const frames = ref([])
const loading = ref(true)
const autoWalk = ref(true)
const hoverInfo = ref(null)
const preview = ref(null)

// —— three.js 句柄（onBeforeUnmount 统一释放） ——
let renderer = null
let scene = null
let camera = null
let raycaster = null
let pointerNdc = null
let frameGroup = null        // 图片画框组
let wallLines = null         // 走廊透视线
let animationId = 0
let disposed = false
const frameMeshes = []       // { mesh, halo, baseZ, hoverT }

// —— 走廊参数 ——
const GAP = 5.2              // 相邻画框间距
const WALL_X = 3.1           // 两侧墙距（画框中心）
const CAM_HEIGHT = 1.9       // 视高
let walkZ = 0                // 沿走廊推进的距离（向 -Z 前进）
let totalLen = 0             // 走廊一圈长度（循环复用图片）

/** 历史条目 → 画框数据：图片走带鉴权头的 blob 缓存（不把 JWT 拼进 URL） */
function viewPath(id) {
  return `/api/image/history/${id}/view`
}

/** 加载生图历史并搭建走廊 */
async function loadFrames() {
  loading.value = true
  try {
    const res = await getImageHistory(48)
    const list = (res.data || [])
      .filter((history) => history && history.id != null)
      .map((history) => ({
        id: history.id,
        path: viewPath(history.id),
        prompt: history.prompt || '',
        time: String(history.createTime || '').replace('T', ' ').slice(0, 16)
      }))
    frames.value = list
    if (!disposed && list.length) buildCorridor(list)
  } catch (error) {
    console.error(error)
  } finally {
    loading.value = false
  }
}

function buildCorridor(list) {
  const canvas = canvasRef.value
  const width = canvas.clientWidth || window.innerWidth
  const height = canvas.clientHeight || window.innerHeight

  renderer = new THREE.WebGLRenderer({ canvas, antialias: true, alpha: true })
  renderer.setPixelRatio(Math.min(window.devicePixelRatio, 2))
  renderer.setSize(width, height, false)

  scene = new THREE.Scene()
  scene.fog = new THREE.FogExp2(0x05070f, 0.028)

  camera = new THREE.PerspectiveCamera(58, width / height, 0.1, 220)
  camera.position.set(0, CAM_HEIGHT, 0)
  camera.lookAt(0, CAM_HEIGHT, -10)

  // 深空星点：走廊外的大球壳
  const starCount = 700
  const starPositions = new Float32Array(starCount * 3)
  for (let i = 0; i < starCount; i++) {
    const r = 60 + Math.random() * 60
    const theta = Math.random() * Math.PI * 2
    const phi = Math.acos(Math.random() * 2 - 1)
    starPositions[i * 3] = r * Math.sin(phi) * Math.cos(theta)
    starPositions[i * 3 + 1] = r * Math.cos(phi)
    starPositions[i * 3 + 2] = r * Math.sin(phi) * Math.sin(theta)
  }
  const starGeometry = new THREE.BufferGeometry()
  starGeometry.setAttribute('position', new THREE.BufferAttribute(starPositions, 3))
  const starMaterial = new THREE.PointsMaterial({
    color: 0xece9e1, size: 0.5, sizeAttenuation: true,
    transparent: true, opacity: 0.5, depthWrite: false
  })
  const stars = new THREE.Points(starGeometry, starMaterial)
  stars.userData.isSky = true
  scene.add(stars)

  // 走廊透视线：地面边线 / 墙顶线，足够长并跟随相机（长廊的"空间骨架"）
  wallLines = new THREE.Group()
  const lineMaterial = new THREE.LineBasicMaterial({ color: 0xece9e1, transparent: true, opacity: 0.16 })
  const span = 4000   // 远超实际行进距离
  const lineDefs = [
    [-WALL_X - 0.9, 0], [WALL_X + 0.9, 0],          // 地面两侧
    [-WALL_X - 0.9, 4.2], [WALL_X + 0.9, 4.2]       // 墙顶
  ]
  for (const [x, y] of lineDefs) {
    const geo = new THREE.BufferGeometry().setFromPoints([
      new THREE.Vector3(x, y, span / 2),
      new THREE.Vector3(x, y, -span / 2)
    ])
    wallLines.add(new THREE.Line(geo, lineMaterial))
  }
  scene.add(wallLines)

  // 画框：贴图平面 + 背光晕，左右墙交错排列
  frameGroup = new THREE.Group()
  scene.add(frameGroup)

  const geometry = new THREE.PlaneGeometry(1, 1)
  const loader = new THREE.TextureLoader()
  totalLen = list.length * GAP

  list.forEach((frame, index) => {
    const material = new THREE.MeshBasicMaterial({ transparent: true, opacity: 0 })
    const mesh = new THREE.Mesh(geometry, material)
    mesh.scale.set(2.4, 2.4, 1)
    mesh.userData.frameIndex = index

    const haloMaterial = new THREE.MeshBasicMaterial({
      color: 0x7dd3fc, transparent: true, opacity: 0.13,
      blending: THREE.AdditiveBlending, depthWrite: false
    })
    const halo = new THREE.Mesh(geometry, haloMaterial)
    halo.scale.set(2.4 * 1.22, 2.4 * 1.22, 1)
    mesh.add(halo)

    // 左右交错挂画；轻微朝向走廊中心
    const side = index % 2 === 0 ? -1 : 1
    const baseZ = -index * GAP
    mesh.position.set(side * WALL_X, CAM_HEIGHT, baseZ)
    mesh.rotation.y = side * -0.32   // 画框微转向中轴
    frameGroup.add(mesh)

    frameMeshes.push({ mesh, halo, baseZ, hoverT: 0 })

    // 异步加载贴图：先经鉴权 blob 拿到 objectURL，再喂给纹理加载器；
    // 就绪后标记，渲染循环里平滑淡入。卸载后到达的回调直接释放纹理防泄漏
    loadAuthedImage(frame.path)
      .then((objectUrl) => {
        if (disposed) return
        loader.load(objectUrl, (texture) => {
          if (disposed) {
            texture.dispose()
            return
          }
          texture.colorSpace = THREE.SRGBColorSpace
          material.map = texture
          material.userData.loaded = true
          material.needsUpdate = true
        }, undefined, () => { /* 加载失败保持透明占位 */ })
      })
      .catch(() => { /* 取图失败保持透明占位 */ })
  })

  // 交互
  raycaster = new THREE.Raycaster()
  pointerNdc = new THREE.Vector2(-10, -10)
  bindPointer(canvas)
  animate()
}

// —— 指针交互：hover 铭牌 + 点击预览 + 拖拽转向 + 滚轮推进 ——
let dragging = false
let dragMoved = false
let lastX = 0
let lastY = 0
let yawOffset = 0
let walkSpeed = 0   // 拖拽纵向调节的推进速度（叠加在自动漫步上）
let hovered = null

function bindPointer(canvas) {
  canvas.addEventListener('pointermove', (e) => {
    const rect = canvas.getBoundingClientRect()
    pointerNdc.x = ((e.clientX - rect.left) / rect.width) * 2 - 1
    pointerNdc.y = -((e.clientY - rect.top) / rect.height) * 2 + 1
    if (dragging) {
      const dx = e.clientX - lastX
      const dy = e.clientY - lastY
      if (Math.abs(dx) + Math.abs(dy) > 2) dragMoved = true
      yawOffset = Math.max(-0.6, Math.min(0.6, yawOffset - dx * 0.0022))
      walkSpeed = Math.max(-0.14, Math.min(0.14, walkSpeed + dy * 0.0004))
      lastX = e.clientX
      lastY = e.clientY
    }
    hoverInfo.value = hovered
      ? { x: e.clientX + 14, y: e.clientY + 10, prompt: hovered.prompt.slice(0, 60) || '（无提示词）' }
      : null
  })
  canvas.addEventListener('pointerdown', (e) => {
    dragging = true
    dragMoved = false
    lastX = e.clientX
    lastY = e.clientY
  })
  canvas.addEventListener('pointerup', () => {
    dragging = false
    if (!dragMoved && hovered) preview.value = hovered
  })
  canvas.addEventListener('pointerleave', () => {
    dragging = false
    pointerNdc.set(-10, -10)
    hoverInfo.value = null
  })
  canvas.addEventListener('wheel', (e) => {
    e.preventDefault()
    walkSpeed = Math.max(-0.14, Math.min(0.14, walkSpeed - e.deltaY * 0.00012))
  }, { passive: false })
}

function resetWalk() {
  walkZ = 0
  walkSpeed = 0
  yawOffset = 0
}

// —— 渲染循环：推进 + 画框循环传送 + hover 拾取（材质预建，循环内只改数值） ——
function animate() {
  if (disposed) return
  animationId = requestAnimationFrame(animate)
  const t = performance.now()

  // 推进：自动漫步（可关）+ 拖拽/滚轮速度自然衰减
  const speed = (autoWalk.value ? 0.022 : 0) + walkSpeed
  walkZ += speed
  walkSpeed *= dragging ? 1 : 0.94
  if (frameMeshes.length) {
    walkZ = walkZ % totalLen   // 循环：走完一圈回到入口
  }

  // 相机：走廊中轴 + 轻微视差摇摆
  const sway = Math.sin(t * 0.0005) * 0.05
  camera.position.set(sway, CAM_HEIGHT + Math.sin(t * 0.0009) * 0.03, -walkZ)
  camera.rotation.set(0, yawOffset, 0)

  // 画框传送：落在相机身后一段之外的传送到前方（无限长廊）
  for (const f of frameMeshes) {
    let relative = f.mesh.position.z - (-walkZ)
    if (relative > GAP) {
      f.mesh.position.z -= totalLen
    } else if (relative < -totalLen + GAP) {
      f.mesh.position.z += totalLen
    }
  }

  // hover 拾取 + 淡入 + 呼吸
  if (raycaster && frameGroup) {
    raycaster.setFromCamera(pointerNdc, camera)
    const meshes = frameMeshes.map((f) => f.mesh)
    const hits = raycaster.intersectObjects(meshes, false)
    const hit = hits.length ? hits[0].object : null
    const hitIndex = hit ? hit.userData.frameIndex : -1
    hovered = hitIndex >= 0 ? frames.value[hitIndex] : null
    frameMeshes.forEach((f, i) => {
      const target = i === hitIndex ? 1.14 : 1
      f.hoverT += (target - f.hoverT) * 0.12
      const breathe = 1 + Math.sin(t * 0.0012 + i * 1.7) * 0.015
      f.mesh.scale.set(2.4 * f.hoverT * breathe, 2.4 * f.hoverT * breathe, 1)
      f.halo.material.opacity = i === hitIndex ? 0.38 : 0.12
      // 贴图就绪后平滑淡入
      if (f.mesh.material.userData.loaded) {
        f.mesh.material.opacity = Math.min(1, f.mesh.material.opacity + 0.045)
      }
    })
  }

  renderer.render(scene, camera)
}

function onResize() {
  if (!renderer || !camera) return
  const canvas = canvasRef.value
  const width = canvas.clientWidth || window.innerWidth
  const height = canvas.clientHeight || window.innerHeight
  camera.aspect = width / height
  camera.updateProjectionMatrix()
  renderer.setSize(width, height, false)
}

function goGenerate() {
  router.push('/image')
}

onMounted(() => {
  window.addEventListener('resize', onResize)
  loadFrames()
})

onBeforeUnmount(() => {
  disposed = true
  cancelAnimationFrame(animationId)
  window.removeEventListener('resize', onResize)
  frameMeshes.forEach((f) => {
    if (f.mesh.material.map) f.mesh.material.map.dispose()
    f.mesh.material.dispose()
    f.halo.material.dispose()
  })
  if (wallLines) {
    wallLines.traverse((child) => {
      if (child.geometry) child.geometry.dispose()
      if (child.material) child.material.dispose()
    })
  }
  if (renderer) renderer.dispose()
  frameMeshes.length = 0
})
</script>

<style scoped>
.galaxy-view {
  position: relative;
  width: 100%;
  height: 100vh;
  overflow: hidden;
  background: #05070f;
}

.galaxy-canvas {
  display: block;
  width: 100%;
  height: 100%;
  cursor: grab;
}

.galaxy-canvas:active {
  cursor: grabbing;
}

.gv-header {
  position: absolute;
  top: 26px;
  left: 32px;
  pointer-events: none;
  z-index: 2;
}

.gv-eyebrow {
  font-size: 10px;
  letter-spacing: 0.3em;
  color: var(--text-3, #5d5b55);
}

.gv-title {
  margin: 6px 0 4px;
  font-size: 34px;
  font-weight: 700;
  color: var(--text-1, #ece9e1);
}

.gv-desc {
  font-size: 12px;
  color: var(--text-2, #9a9890);
}

.gv-toolbar {
  position: absolute;
  top: 30px;
  right: 32px;
  display: flex;
  gap: 10px;
  align-items: center;
  z-index: 2;
}

.gv-count {
  font-size: 11px;
  letter-spacing: 0.14em;
  color: var(--text-3, #5d5b55);
}

.gv-btn {
  display: inline-flex;
  gap: 8px;
  align-items: baseline;
  padding: 5px 12px;
  border: 1px solid rgba(236, 233, 225, 0.28);
  background: rgba(5, 7, 15, 0.6);
  color: var(--text-1, #ece9e1);
  font-size: 11.5px;
  font-family: inherit;
  cursor: pointer;
  text-decoration: none;
  transition: border-color 0.15s ease, color 0.15s ease;
}

.gv-btn:hover {
  border-color: var(--signal, #ff6b57);
  color: #fff;
}

.gv-btn .k {
  font-size: 9.5px;
  letter-spacing: 0.14em;
  color: var(--text-3, #5d5b55);
}

.gv-btn.active {
  border-color: var(--signal, #ff6b57);
}

.gv-btn.active .k {
  color: var(--signal, #ff6b57);
}

.gv-tag {
  position: fixed;
  z-index: 3;
  max-width: 280px;
  padding: 5px 9px;
  border: 1px solid rgba(236, 233, 225, 0.25);
  background: rgba(5, 7, 15, 0.82);
  color: var(--text-2, #9a9890);
  font-size: 10.5px;
  line-height: 1.5;
  pointer-events: none;
}

.gv-tag b {
  font-weight: 400;
  color: var(--text-1, #ece9e1);
}

.gv-empty,
.gv-loading {
  position: absolute;
  inset: 0;
  display: grid;
  place-content: center;
  justify-items: center;
  gap: 14px;
  color: var(--text-2, #9a9890);
  font-size: 13px;
}

.gv-preview {
  position: fixed;
  inset: 0;
  z-index: 5;
  display: grid;
  place-items: center;
  background: rgba(3, 4, 10, 0.78);
}

.pv-card {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 240px;
  gap: 0;
  max-width: min(1080px, 92vw);
  max-height: 86vh;
  border: 1px solid rgba(236, 233, 225, 0.2);
  background: #0b0d15;
}

.pv-img {
  display: block;
  max-width: 100%;
  max-height: 86vh;
  object-fit: contain;
}

.pv-meta {
  display: flex;
  flex-direction: column;
  gap: 12px;
  padding: 18px;
  border-left: 1px solid rgba(236, 233, 225, 0.14);
}

.pv-prompt {
  font-size: 12.5px;
  line-height: 1.7;
  color: var(--text-1, #ece9e1);
  overflow: auto;
}

.pv-time {
  font-size: 10.5px;
  letter-spacing: 0.1em;
  color: var(--text-3, #5d5b55);
}

.pv-actions {
  margin-top: auto;
  display: flex;
  flex-direction: column;
  gap: 8px;
  align-items: stretch;
}

@media (max-width: 900px) {
  .pv-card {
    grid-template-columns: 1fr;
    overflow: auto;
  }

  .pv-meta {
    border-left: none;
    border-top: 1px solid rgba(236, 233, 225, 0.14);
  }
}
</style>
