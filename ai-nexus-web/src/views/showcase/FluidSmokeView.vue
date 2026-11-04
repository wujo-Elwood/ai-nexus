<template>
  <div ref="mountRef" class="fluid-view">
    <div class="fs-header">
      <span class="fs-eyebrow">Gpu Navier Stokes</span>
      <h1 class="fs-title">流体烟雾</h1>
      <p class="fs-desc">按住拖动搅动烟雾 · 鼠标即画笔 · 速度场在 GPU 上实时求解</p>
    </div>

    <div class="fs-toolbar">
      <button type="button" class="fs-button" :class="{ active: paused }" @click="togglePause">
        <span class="fs-button-key">暂停</span>
        <span>{{ paused ? '已停' : '运行' }}</span>
      </button>
      <button type="button" class="fs-button" :class="{ active: showVelocity }" @click="toggleVelocity">
        <span class="fs-button-key">速度场</span>
        <span>{{ showVelocity ? '可见' : '隐藏' }}</span>
      </button>
      <button type="button" class="fs-button" :class="{ active: autoSplat }" @click="toggleAuto">
        <span class="fs-button-key">自动</span>
        <span>{{ autoSplat ? '开' : '关' }}</span>
      </button>
      <button type="button" class="fs-button" @click="clearField">
        <span class="fs-button-key">清空</span>
        <span>重置</span>
      </button>
    </div>

    <div class="fs-hud">
      <span class="fs-hud-row"><span class="fs-hud-k">网格</span><span class="fs-hud-v">{{ gridLabel }}</span></span>
      <span class="fs-hud-row"><span class="fs-hud-k">压力迭代</span><span class="fs-hud-v">{{ PRESSURE_ITERATIONS }}</span></span>
      <span class="fs-hud-row"><span class="fs-hud-k">衰减</span><span class="fs-hud-v">{{ (DENSITY_DISSIPATION).toFixed(3) }}</span></span>
      <span class="fs-hud-row"><span class="fs-hud-k">帧率</span><span class="fs-hud-v">{{ fps }}</span></span>
    </div>
  </div>
</template>

<script setup>
import { onBeforeUnmount, onMounted, ref, computed } from 'vue'
import * as THREE from 'three'

/* ============================================================
   流体烟雾：GPU 上的 Navier-Stokes 求解
   ============================================================

   核心思路（Stam 的 "Stable Fluids" 稳定解算器，全部在片元着色器里跑）：

     每一帧按顺序做五步，每步都是一个「读取上一张纹理 → 写出到另一张纹理」的
     全屏 pass。因为不能同时读写同一张纹理，所以每类场都需要两张纹理轮流用
     （ping-pong）。

       1. advect      速度场沿自身方向平流（把速度"搬"到新位置）
       2. splat       在鼠标处注入速度与烟雾密度（外力）
       3. divergence  求速度场的散度（流动的"源/汇"强度）
       4. jacobi      迭代解泊松方程，从散度反解出压力场
       5. gradient    从速度场中减去压力梯度 —— 这一步让流场变得不可压缩，
                      也就是烟雾会"绕过"障碍、卷成涡旋的关键

   涡量增强（curl）是可选的第 6 步：显式补回被数值耗散吃掉的涡旋细节，
   否则烟雾会越飘越"糊"，缺少锐利的卷曲。

   ⚠️ 关键约束（踩坑点，改之前先读）：
     - 速度场必须用**浮点纹理**（这里用 HalfFloatType）。用普通 8 位纹理会
       把速度量化成 256 级，烟雾会立刻碎成块状噪点。
     - 纹理过滤必须是 LinearFilter，否则平流时会看到明显的网格格子。
     - 包裹方式必须 ClampToEdge，用 Repeat 会让烟雾在下边界"穿越"到上边界。
     - 所有 pass 的相机必须是正交相机且网格一个像素不差，否则会出现整体偏移。
   ============================================================ */

const mountRef = ref(null)
const paused = ref(false)
const showVelocity = ref(false)
const autoSplat = ref(true)
const fps = ref(60)
const gridSize = ref(0)

const PRESSURE_ITERATIONS = 22
const DENSITY_DISSIPATION = 0.985
const VELOCITY_DISSIPATION = 0.992
const CURL_STRENGTH = 28
const SPLAT_RADIUS = 0.0028

const gridLabel = computed(() => (gridSize.value ? `${gridSize.value} × ${gridSize.value}` : '—'))

let renderer = null
let scene = null
let camera = null
let clock = null
let rafId = 0
let resizeObserver = null

// 全屏 pass 的载体：一个覆盖整个 NDC 的四边形。
// ⚠️ 不用 gl_VertexID 走「单三角形」写法 —— 那是 GLSL ES 3.00 的内置变量，
//    而 RawShaderMaterial 默认发的是 GLSL ES 1.00，会直接编译失败。
let quadMesh = null

// 各类场的渲染目标（ping-pong 各两张）
let velocity = null      // RG 速度场
let density = null       // R 烟雾密度场
let pressure = null      // R 压力场
let divergence = null    // R 散度场
let curlRT = null        // R 涡量场

let displayMat = null
const materials = []

let simWidth = 0
let simHeight = 0

// 指针状态：记录上一帧位置以计算拖拽速度
const pointer = {
  x: 0.5, y: 0.5,
  prevX: 0.5, prevY: 0.5,
  down: false,
  moved: false,
  hue: 0
}
let nextAutoSplatAt = 0
let frameCount = 0
let fpsAccum = 0

/* ------------------------------------------------------------
   着色器
   ------------------------------------------------------------ */

// 全屏四边形的顶点着色器（GLSL ES 1.00）。
//
// ⚠️ 这里刻意**不用** gl_VertexID 生成全屏三角形。
//    虽然那写法更省一个顶点缓冲，但 gl_VertexID 是 GLSL ES 3.00 才有的内置变量，
//    而本项目所有 shader 都跑在 GLSL ES 1.00（RawShaderMaterial 默认不写 #version）。
//    混用会导致着色器编译失败、整个页面一片空白。
//    代价只是一个 6 顶点（两个三角形）的 buffer，可以忽略。
const QUAD_VERT = `
  precision highp float;
  attribute vec3 position;
  varying vec2 vUv;
  void main() {
    vUv = position.xy * 0.5 + 0.5;
    gl_Position = vec4(position.xy, 0.0, 1.0);
  }`

// 平流：沿速度方向回采上一帧的值。这就是"烟雾跟着风走"的实现。
const ADVECT_FRAG = `
  precision highp float;
  varying vec2 vUv;
  uniform sampler2D uSource;
  uniform sampler2D uVelocity;
  uniform vec2 uTexelSize;
  uniform float uDt;
  uniform float uDissipation;

  void main() {
    // 用线性插值回采速度场，得到该点的连续速度
    vec2 vel = texture2D(uVelocity, vUv).xy;
    // 逆着速度方向回溯：这就是特征线法的核心
    vec2 coord = vUv - uDt * vel * uTexelSize;
    vec4 result = texture2D(uSource, coord);
    // 每帧轻微衰减，否则烟雾会永远累积、整屏变白
    result *= uDissipation;
    gl_FragColor = result;
  }`

// 注入：在指定位置叠加一个高斯核的速度与密度（鼠标拖拽产生的风）
const SPLAT_FRAG = `
  precision highp float;
  varying vec2 vUv;
  uniform sampler2D uTarget;
  uniform float uAspect;
  uniform vec3 uColor;
  uniform vec2 uPoint;
  uniform float uRadius;

  void main() {
    vec2 p = vUv - uPoint;
    p.x *= uAspect;                  // 修正宽高比，保证注入点是正圆而不是椭圆
    float falloff = exp(-dot(p, p) / uRadius);
    vec3 base = texture2D(uTarget, vUv).xyz;
    gl_FragColor = vec4(base + uColor * falloff, 1.0);
  }`

// 散度：∇·v = ∂u/∂x + ∂v/∂y，用中心差分
const DIVERGENCE_FRAG = `
  precision highp float;
  varying vec2 vUv;
  uniform sampler2D uVelocity;
  uniform vec2 uTexelSize;

  void main() {
    float L = texture2D(uVelocity, vUv - vec2(uTexelSize.x, 0.0)).x;
    float R = texture2D(uVelocity, vUv + vec2(uTexelSize.x, 0.0)).x;
    float B = texture2D(uVelocity, vUv - vec2(0.0, uTexelSize.y)).y;
    float T = texture2D(uVelocity, vUv + vec2(0.0, uTexelSize.y)).y;
    float div = 0.5 * (R - L + T - B);
    gl_FragColor = vec4(div, 0.0, 0.0, 1.0);
  }`

// 雅可比迭代：解 ∇²p = ∇·v 的离散形式。
// 每一步用邻居的平均值更新，重复 PRESSURE_ITERATIONS 次即收敛到压力场。
const JACOBI_FRAG = `
  precision highp float;
  varying vec2 vUv;
  uniform sampler2D uPressure;
  uniform sampler2D uDivergence;
  uniform vec2 uTexelSize;

  void main() {
    float L = texture2D(uPressure, vUv - vec2(uTexelSize.x, 0.0)).x;
    float R = texture2D(uPressure, vUv + vec2(uTexelSize.x, 0.0)).x;
    float B = texture2D(uPressure, vUv - vec2(0.0, uTexelSize.y)).x;
    float T = texture2D(uPressure, vUv + vec2(0.0, uTexelSize.y)).x;
    float div = texture2D(uDivergence, vUv).x;
    // 泊松方程离散解：(L+R+B+T - div) / 4
    float p = (L + R + B + T - div) * 0.25;
    gl_FragColor = vec4(p, 0.0, 0.0, 1.0);
  }`

// 梯度减去：v = v - ∇p。这一步是"不可压缩"的来源。
const GRADIENT_SUBTRACT_FRAG = `
  precision highp float;
  varying vec2 vUv;
  uniform sampler2D uPressure;
  uniform sampler2D uVelocity;
  uniform vec2 uTexelSize;

  void main() {
    float L = texture2D(uPressure, vUv - vec2(uTexelSize.x, 0.0)).x;
    float R = texture2D(uPressure, vUv + vec2(uTexelSize.x, 0.0)).x;
    float B = texture2D(uPressure, vUv - vec2(0.0, uTexelSize.y)).x;
    float T = texture2D(uPressure, vUv + vec2(0.0, uTexelSize.y)).x;
    vec2 vel = texture2D(uVelocity, vUv).xy;
    vel -= vec2(R - L, T - B) * 0.5;
    gl_FragColor = vec4(vel, 0.0, 1.0);
  }`

// 涡量：curl = ∂v/∂x - ∂u/∂y
const CURL_FRAG = `
  precision highp float;
  varying vec2 vUv;
  uniform sampler2D uVelocity;
  uniform vec2 uTexelSize;

  void main() {
    float L = texture2D(uVelocity, vUv - vec2(uTexelSize.x, 0.0)).y;
    float R = texture2D(uVelocity, vUv + vec2(uTexelSize.x, 0.0)).y;
    float B = texture2D(uVelocity, vUv - vec2(0.0, uTexelSize.y)).x;
    float T = texture2D(uVelocity, vUv + vec2(0.0, uTexelSize.y)).x;
    float vorticity = 0.5 * ((R - L) - (T - B));
    gl_FragColor = vec4(vorticity, 0.0, 0.0, 1.0);
  }`

// 涡量增强：把被数值耗散抹掉的涡旋细节显式加回去，让烟雾卷得更锐利
const VORTICITY_FRAG = `
  precision highp float;
  varying vec2 vUv;
  uniform sampler2D uVelocity;
  uniform sampler2D uCurl;
  uniform vec2 uTexelSize;
  uniform float uCurlStrength;
  uniform float uDt;

  void main() {
    float L = texture2D(uCurl, vUv - vec2(uTexelSize.x, 0.0)).x;
    float R = texture2D(uCurl, vUv + vec2(uTexelSize.x, 0.0)).x;
    float B = texture2D(uCurl, vUv - vec2(0.0, uTexelSize.y)).x;
    float T = texture2D(uCurl, vUv + vec2(0.0, uTexelSize.y)).x;
    float C = texture2D(uCurl, vUv).x;

    // 涡量的梯度方向 × 涡量大小 = 指向涡心的力
    vec2 force = 0.5 * vec2(abs(T) - abs(B), abs(R) - abs(L));
    force /= length(force) + 1e-4;
    force *= uCurlStrength * C;
    force.y *= -1.0;

    vec2 vel = texture2D(uVelocity, vUv).xy;
    vel += force * uDt;
    // 限幅，避免注入过强时数值爆炸
    vel = clamp(vel, -1000.0, 1000.0);
    gl_FragColor = vec4(vel, 0.0, 1.0);
  }`

// 显示：把密度场映射成烟雾颜色。可选叠加速度场可视化。
const DISPLAY_FRAG = `
  precision highp float;
  varying vec2 vUv;
  uniform sampler2D uDensity;
  uniform sampler2D uVelocity;
  uniform float uShowVelocity;

  void main() {
    float d = texture2D(uDensity, vUv).x;

    // 烟雾配色：核心暖白 → 边缘偏青蓝，模拟被侧光照亮的烟体
    vec3 cool = vec3(0.16, 0.34, 0.62);
    vec3 warm = vec3(0.98, 0.86, 0.72);
    vec3 col = mix(cool, warm, clamp(d * 1.55, 0.0, 1.0));
    col *= smoothstep(0.0, 0.16, d);

    if (uShowVelocity > 0.5) {
      vec2 v = texture2D(uVelocity, vUv).xy * 0.06;
      col += vec3(v.x, v.y * 0.6, length(v)) * 0.55;
    }

    // 密度越高越不透明（烟雾遮挡背景）
    float alpha = clamp(d * 1.5, 0.0, 1.0);
    gl_FragColor = vec4(col, alpha);
  }`

// 清空：把整张纹理归零
const CLEAR_FRAG = `
  precision highp float;
  varying vec2 vUv;
  uniform float uValue;
  void main() { gl_FragColor = vec4(uValue, 0.0, 0.0, 1.0); }`

/* ------------------------------------------------------------
   工具
   ------------------------------------------------------------ */

// 全屏四边形：两个三角形拼成一个覆盖整个 NDC 的方片。
// 顶点属性只用到 position.xy，z 固定为 0（正交相机下无意义）。
function createFullscreenQuadGeometry() {
  const geo = new THREE.BufferGeometry()
  // 位置是 3 分量，因为 RawShaderMaterial 里声明的是 attribute vec3 position
  const positions = new Float32Array([
    -1, -1, 0,
     1, -1, 0,
     1,  1, 0,
    -1, -1, 0,
     1,  1, 0,
    -1,  1, 0
  ])
  geo.setAttribute('position', new THREE.BufferAttribute(positions, 3))
  return geo
}

function createFBO(w, h, filter = THREE.LinearFilter) {
  const rt = new THREE.WebGLRenderTarget(w, h, {
    // ⚠️ HalfFloat 是必须的：8 位纹理会把速度量化成 256 级，烟雾立刻碎成噪点
    type: THREE.HalfFloatType,
    format: THREE.RGBAFormat,
    minFilter: filter,
    magFilter: filter,
    wrapS: THREE.ClampToEdgeWrapping,
    wrapT: THREE.ClampToEdgeWrapping,
    depthBuffer: false,
    stencilBuffer: false
  })
  rt.texture.generateMipmaps = false
  return rt
}

function createDoubleFBO(w, h, filter) {
  let fbo1 = createFBO(w, h, filter)
  let fbo2 = createFBO(w, h, filter)
  return {
    width: w,
    height: h,
    get read() { return fbo1 },
    get write() { return fbo2 },
    swap() { const t = fbo1; fbo1 = fbo2; fbo2 = t }
  }
}

function makeMaterial(fragmentShader, uniforms) {
  const mat = new THREE.RawShaderMaterial({
    vertexShader: QUAD_VERT,
    fragmentShader,
    uniforms,
    depthTest: false,
    depthWrite: false
  })
  materials.push(mat)
  return mat
}

/* ------------------------------------------------------------
   预建的 pass 材质
   ------------------------------------------------------------
   ⚠️ 绝不能在渲染循环里 new Material！
     每个 ShaderMaterial 都会编译并上传一个 GPU program，在 60fps 下每帧新建
     十来个材质会迅速耗尽显存并让驱动不停重编译，表现为越跑越卡直到卡死。
     所有 pass 的材质在这里一次性建好，循环里只更新 uniform 的值。
   ------------------------------------------------------------ */
let matAdvect = null
let matAdvectDensity = null
let matSplatVel = null
let matSplatDensity = null
let matDivergence = null
let matJacobi = null
let matGradient = null
let matCurl = null
let matVorticity = null
let matClear = null

function buildPassMaterials() {
  const texelSize = new THREE.Vector2(1 / simWidth, 1 / simHeight)

  // 平流：速度场与密度场共用同一份 shader，但需要两套独立 uniform（互相不知道对方）
  matAdvect = makeMaterial(ADVECT_FRAG, {
    uSource: { value: null },
    uVelocity: { value: null },
    uTexelSize: { value: texelSize },
    uDt: { value: 0 },
    uDissipation: { value: VELOCITY_DISSIPATION }
  })
  matAdvectDensity = makeMaterial(ADVECT_FRAG, {
    uSource: { value: null },
    uVelocity: { value: null },
    uTexelSize: { value: texelSize },
    uDt: { value: 0 },
    uDissipation: { value: DENSITY_DISSIPATION }
  })

  matSplatVel = makeMaterial(SPLAT_FRAG, {
    uTarget: { value: null },
    uAspect: { value: simWidth / simHeight },
    uColor: { value: new THREE.Vector3() },
    uPoint: { value: new THREE.Vector2() },
    uRadius: { value: SPLAT_RADIUS }
  })
  matSplatDensity = makeMaterial(SPLAT_FRAG, {
    uTarget: { value: null },
    uAspect: { value: simWidth / simHeight },
    uColor: { value: new THREE.Vector3() },
    uPoint: { value: new THREE.Vector2() },
    uRadius: { value: SPLAT_RADIUS }
  })

  matDivergence = makeMaterial(DIVERGENCE_FRAG, {
    uVelocity: { value: null },
    uTexelSize: { value: texelSize }
  })

  matJacobi = makeMaterial(JACOBI_FRAG, {
    uPressure: { value: null },
    uDivergence: { value: null },
    uTexelSize: { value: texelSize }
  })

  matGradient = makeMaterial(GRADIENT_SUBTRACT_FRAG, {
    uPressure: { value: null },
    uVelocity: { value: null },
    uTexelSize: { value: texelSize }
  })

  matCurl = makeMaterial(CURL_FRAG, {
    uVelocity: { value: null },
    uTexelSize: { value: texelSize }
  })

  matVorticity = makeMaterial(VORTICITY_FRAG, {
    uVelocity: { value: null },
    uCurl: { value: null },
    uTexelSize: { value: texelSize },
    uCurlStrength: { value: CURL_STRENGTH },
    uDt: { value: 0 }
  })

  matClear = makeMaterial(CLEAR_FRAG, { uValue: { value: 0.0 } })
}

// 执行一个全屏 pass：渲染到 target（null = 渲染到屏幕）
function runPass(material, target) {
  quadMesh.material = material
  renderer.setRenderTarget(target)
  renderer.render(scene, camera)
  renderer.setRenderTarget(null)
}

/* ------------------------------------------------------------
   初始化
   ------------------------------------------------------------ */

onMounted(() => {
  const container = mountRef.value
  if (!container) return

  // 用 WebGL2 拿 HalfFloat 渲染目标（流体模拟必须要半精度浮点，8 位会碎成噪点）
  const canvas = document.createElement('canvas')
  const gl = canvas.getContext('webgl2', { alpha: false, antialias: false })
  if (!gl) {
    container.innerHTML =
      '<div style="padding:40px;color:#7d90b5;font-size:14px">当前浏览器不支持 WebGL2，无法运行 GPU 流体模拟。</div>'
    return
  }

  renderer = new THREE.WebGLRenderer({
    canvas,
    context: gl,
    antialias: false,
    alpha: false,
    powerPreference: 'high-performance'
  })
  renderer.setPixelRatio(Math.min(window.devicePixelRatio, 2))
  renderer.setSize(container.clientWidth, container.clientHeight)
  renderer.setClearColor(0x05070d, 1)
  renderer.domElement.classList.add('fs-canvas')
  container.appendChild(renderer.domElement)

  // 正交相机 + 全屏四边形：pass 只做全屏纹理搬运，不需要透视
  scene = new THREE.Scene()
  camera = new THREE.OrthographicCamera(-1, 1, 1, -1, 0, 1)
  quadMesh = new THREE.Mesh(createFullscreenQuadGeometry(), null)
  quadMesh.frustumCulled = false
  scene.add(quadMesh)

  initSimulation()
  bindEvents()

  clock = new THREE.Clock()
  nextAutoSplatAt = 1.0
  animate()
})

function initSimulation() {
  const container = mountRef.value
  const aspect = container.clientWidth / Math.max(container.clientHeight, 1)

  // 以较短边为基准定分辨率，保证两个方向的一致性
  const base = Math.min(Math.max(container.clientWidth, 320), 1024)
  simWidth = Math.floor(base * (aspect > 1 ? 1 : aspect))
  simHeight = Math.floor(base * (aspect > 1 ? 1 / aspect : 1))
  simWidth = Math.max(simWidth, 128)
  simHeight = Math.max(simHeight, 128)
  gridSize.value = Math.max(simWidth, simHeight)

  const filtering = THREE.LinearFilter
  velocity = createDoubleFBO(simWidth, simHeight, filtering)
  density = createDoubleFBO(simWidth, simHeight, filtering)
  pressure = createDoubleFBO(simWidth, simHeight, THREE.NearestFilter)
  divergence = createFBO(simWidth, simHeight, THREE.NearestFilter)
  curlRT = createFBO(simWidth, simHeight, THREE.NearestFilter)

  displayMat = makeMaterial(DISPLAY_FRAG, {
    uDensity: { value: density.read.texture },
    uVelocity: { value: velocity.read.texture },
    uShowVelocity: { value: 0 }
  })

  // 所有 pass 材质一次性建好，渲染循环里只改 uniform 的值（见 buildPassMaterials 注释）
  buildPassMaterials()
}

/* ------------------------------------------------------------
   事件
   ------------------------------------------------------------ */

function updatePointerFromEvent(e) {
  const rect = renderer.domElement.getBoundingClientRect()
  pointer.prevX = pointer.x
  pointer.prevY = pointer.y
  pointer.x = (e.clientX - rect.left) / rect.width
  // WebGL 的 v 轴朝上，DOM 的 y 轴朝下，需要翻转
  pointer.y = 1.0 - (e.clientY - rect.top) / rect.height
  pointer.moved = true
}

function onPointerDown(e) {
  pointer.down = true
  pointer.hue = Math.random() * 360
  updatePointerFromEvent(e)
  pointer.prevX = pointer.x
  pointer.prevY = pointer.y
  renderer.domElement.setPointerCapture?.(e.pointerId)
}

function onPointerMove(e) {
  updatePointerFromEvent(e)
}

function onPointerUp(e) {
  pointer.down = false
  renderer.domElement.releasePointerCapture?.(e.pointerId)
}

function bindEvents() {
  const el = renderer.domElement
  el.addEventListener('pointerdown', onPointerDown)
  el.addEventListener('pointermove', onPointerMove)
  window.addEventListener('pointerup', onPointerUp)
  resizeObserver = new ResizeObserver(resize)
  resizeObserver.observe(mountRef.value)
}

function resize() {
  const container = mountRef.value
  if (!container || !renderer) return
  const w = container.clientWidth
  const h = container.clientHeight
  if (w === 0 || h === 0) return
  renderer.setSize(w, h)
  // 网格分辨率不随窗口变化重建（重建会丢失当前烟雾状态），只改视口
}

/* ------------------------------------------------------------
   模拟步进
   ------------------------------------------------------------ */

// 把速度/密度注入到指定点
//
// ⚠️ 这里只更新 uniform 的值，复用 buildPassMaterials() 里预建好的材质。
//    绝不能在渲染循环里 new Material —— 每个 ShaderMaterial 都会编译并上传一个
//    GPU program，60fps 下每帧新建十来份会迅速耗尽显存、驱动反复重编译，
//    表现为越跑越卡直到整页卡死。
function splat(x, y, dx, dy, color) {
  // 速度注入
  matSplatVel.uniforms.uTarget.value = velocity.read.texture
  matSplatVel.uniforms.uColor.value.set(dx, dy, 0)
  matSplatVel.uniforms.uPoint.value.set(x, y)
  runPass(matSplatVel, velocity.write)
  velocity.swap()

  // 密度注入（颜色即烟雾浓度）
  matSplatDensity.uniforms.uTarget.value = density.read.texture
  matSplatDensity.uniforms.uColor.value.set(color, color * 0.92, color * 0.8)
  matSplatDensity.uniforms.uPoint.value.set(x, y)
  runPass(matSplatDensity, density.write)
  density.swap()
}

function step(dt) {
  // 1) 涡量增强（把数值耗散吃掉的涡旋补回来）
  matCurl.uniforms.uVelocity.value = velocity.read.texture
  runPass(matCurl, curlRT)

  matVorticity.uniforms.uVelocity.value = velocity.read.texture
  matVorticity.uniforms.uCurl.value = curlRT.texture
  matVorticity.uniforms.uDt.value = dt
  runPass(matVorticity, velocity.write)
  velocity.swap()

  // 2) 散度
  matDivergence.uniforms.uVelocity.value = velocity.read.texture
  runPass(matDivergence, divergence)

  // 3) 清压力场为 0（用上一步的压力值当初始猜测会更快收敛，这里从零开始更稳定）
  runPass(matClear, pressure.write)
  pressure.swap()

  // 4) 雅可比迭代解压力
  matJacobi.uniforms.uDivergence.value = divergence.texture
  for (let i = 0; i < PRESSURE_ITERATIONS; i += 1) {
    matJacobi.uniforms.uPressure.value = pressure.read.texture
    runPass(matJacobi, pressure.write)
    pressure.swap()
  }

  // 5) 梯度减去 -> 速度场变得不可压缩
  matGradient.uniforms.uPressure.value = pressure.read.texture
  matGradient.uniforms.uVelocity.value = velocity.read.texture
  runPass(matGradient, velocity.write)
  velocity.swap()

  // 6) 平流速度场
  const velRead = velocity.read.texture
  matAdvect.uniforms.uSource.value = velRead
  matAdvect.uniforms.uVelocity.value = velRead
  matAdvect.uniforms.uDt.value = dt
  runPass(matAdvect, velocity.write)
  velocity.swap()

  // 7) 平流密度场（用刚更新完的速度场来搬运烟）
  matAdvectDensity.uniforms.uSource.value = density.read.texture
  matAdvectDensity.uniforms.uVelocity.value = velocity.read.texture
  matAdvectDensity.uniforms.uDt.value = dt
  runPass(matAdvectDensity, density.write)
  density.swap()
}

/* ------------------------------------------------------------
   渲染循环
   ------------------------------------------------------------ */

function animate() {
  rafId = requestAnimationFrame(animate)
  const dt = Math.min(clock.getDelta(), 1 / 30)   // 限幅，避免切标签页回来时炸掉
  const now = clock.elapsedTime

  // 指针拖拽 -> 注入风
  if (pointer.moved && !paused.value) {
    const dx = (pointer.x - pointer.prevX) * 42
    const dy = (pointer.y - pointer.prevY) * 42
    const strength = pointer.down ? 1.0 : 0.28   // 未按下时轻微跟随，保持"活"的感觉
    if (Math.abs(dx) > 1e-5 || Math.abs(dy) > 1e-5) {
      splat(pointer.x, pointer.y, dx * strength, dy * strength, pointer.down ? 1.0 : 0.42)
    }
    pointer.prevX = pointer.x
    pointer.prevY = pointer.y
    pointer.moved = false
  }

  // 自动注入：无人操作时随机位置冒烟，保证画面不空
  if (autoSplat.value && !paused.value && now >= nextAutoSplatAt) {
    const a = Math.random() * Math.PI * 2
    const r = 0.28 + Math.random() * 0.2
    const x = 0.5 + Math.cos(a) * r
    const y = 0.5 + Math.sin(a) * r
    splat(x, y, Math.cos(a) * 8, Math.sin(a) * 8, 0.75 + Math.random() * 0.25)
    nextAutoSplatAt = now + 0.35 + Math.random() * 0.5
  }

  if (!paused.value) step(dt)

  // 合成到屏幕
  displayMat.uniforms.uDensity.value = density.read.texture
  displayMat.uniforms.uVelocity.value = velocity.read.texture
  displayMat.uniforms.uShowVelocity.value = showVelocity.value ? 1 : 0
  runPass(displayMat, null)

  // 帧率统计
  frameCount += 1
  fpsAccum += dt
  if (fpsAccum >= 0.5) {
    fps.value = Math.round(frameCount / fpsAccum)
    frameCount = 0
    fpsAccum = 0
  }
}

/* ------------------------------------------------------------
   交互
   ------------------------------------------------------------ */

function togglePause() { paused.value = !paused.value }
function toggleVelocity() { showVelocity.value = !showVelocity.value }
function toggleAuto() { autoSplat.value = !autoSplat.value }

function clearField() {
  if (!renderer) return
  // 复用预建的 matClear，不要在这里 new 材质（每点一次就多一份 GPU program）
  for (const rt of [velocity.read, velocity.write, density.read, density.write]) {
    runPass(matClear, rt)
  }
  quadMesh.material = displayMat
}

/* ------------------------------------------------------------
   释放
   ------------------------------------------------------------ */

onBeforeUnmount(() => {
  window.cancelAnimationFrame(rafId)
  window.removeEventListener('pointerup', onPointerUp)
  if (resizeObserver) resizeObserver.disconnect()
  if (renderer) {
    const el = renderer.domElement
    el.removeEventListener('pointerdown', onPointerDown)
    el.removeEventListener('pointermove', onPointerMove)
  }

  const disposeFBO = (d) => {
    if (!d) return
    d.read?.dispose?.()
    d.write?.dispose?.()
  }
  disposeFBO(velocity)
  disposeFBO(density)
  disposeFBO(pressure)
  divergence?.dispose?.()
  curlRT?.dispose?.()
  for (const m of materials) m.dispose()
  if (quadMesh) quadMesh.geometry.dispose()
  if (renderer) {
    renderer.dispose()
    renderer.domElement.remove()
  }
  velocity = density = pressure = divergence = curlRT = null
  // 预建材质已随 materials 一起 dispose，这里必须清空引用，
  // 否则组件被重新挂载（如切路由回来）时 splat/step 会拿到已销毁的材质。
  matAdvect = matAdvectDensity = matSplatVel = matSplatDensity = null
  matDivergence = matJacobi = matGradient = matCurl = matVorticity = matClear = null
  displayMat = null
  materials.length = 0
})
</script>

<style scoped>
.fluid-view {
  position: relative;
  width: 100%;
  height: 100%;
  min-height: 560px;
  overflow: hidden;
  background: radial-gradient(ellipse 80% 60% at 50% 35%, #0a1020 0%, #05070d 55%, #020306 100%);
}

.fs-canvas {
  position: absolute;
  inset: 0;
  display: block;
  touch-action: none;
  cursor: crosshair;
}

.fs-header {
  position: absolute;
  top: 28px;
  left: 32px;
  z-index: 2;
  pointer-events: none;
}

.fs-eyebrow {
  display: block;
  font-size: 11px;
  letter-spacing: 3px;
  text-transform: uppercase;
  color: #6a7fa8;
}

.fs-title {
  margin: 6px 0 4px;
  font-size: 26px;
  font-weight: 600;
  color: #dce8ff;
  text-shadow: 0 0 18px rgba(94, 168, 255, 0.35);
}

.fs-desc {
  margin: 0;
  font-size: 13px;
  color: #7d90b5;
}

.fs-toolbar {
  position: absolute;
  top: 28px;
  right: 32px;
  z-index: 2;
  display: flex;
  gap: 10px;
  flex-wrap: wrap;
  justify-content: flex-end;
  max-width: 60%;
}

.fs-button {
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

.fs-button:hover {
  border-color: rgba(94, 168, 255, 0.7);
  color: #e6f0ff;
}

.fs-button.active {
  border-color: #5ea8ff;
  background: rgba(94, 168, 255, 0.18);
  color: #eaf4ff;
  box-shadow: 0 0 14px rgba(94, 168, 255, 0.35);
}

.fs-button-key {
  font-size: 11px;
  padding: 1px 6px;
  border: 1px solid rgba(94, 168, 255, 0.4);
  border-radius: 4px;
  color: #8fb4e8;
}

.fs-hud {
  position: absolute;
  left: 32px;
  bottom: 28px;
  z-index: 2;
  /* ⚠️ 必须放行指针：HUD 压在画布上，不放行会吃掉左下角一整片搅动烟雾的区域 */
  pointer-events: none;
  display: flex;
  flex-direction: column;
  gap: 6px;
  padding: 12px 16px;
  border: 1px solid rgba(94, 168, 255, 0.22);
  border-radius: 10px;
  background: rgba(8, 13, 24, 0.62);
  font-size: 12px;
  backdrop-filter: blur(8px);
}

.fs-hud-row {
  display: flex;
  justify-content: space-between;
  gap: 18px;
}

.fs-hud-k {
  color: #6a7fa8;
}

.fs-hud-v {
  color: #b9c9e8;
  font-variant-numeric: tabular-nums;
}
</style>
