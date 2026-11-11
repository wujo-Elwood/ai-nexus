<template>
  <div ref="mountRef" class="tt-view">
    <div class="tt-header">
      <span class="tt-eyebrow">Tesseract · 四维超立方体</span>
      <h1 class="tt-title">四维超立方体</h1>
      <p class="tt-desc">双重旋转 · 四维投影到三维再投影到屏幕 · 拖拽转视角，滚轮改投影距离</p>
    </div>

    <div class="tt-toolbar">
      <button type="button" class="tt-button" :class="{ active: speedIndex > 0 }" @click="cycleSpeed">
        <span class="tt-button-key">旋转</span>
        <span>{{ speedLabel }}</span>
      </button>
      <button type="button" class="tt-button" @click="cyclePlane">
        <span class="tt-button-key">轴面</span>
        <span>{{ planeLabel }}</span>
      </button>
      <button type="button" class="tt-button" :class="{ active: perspective }" @click="togglePerspective">
        <span class="tt-button-key">投影</span>
        <span>{{ perspective ? '透视' : '正交' }}</span>
      </button>
      <button type="button" class="tt-button" :class="{ active: glowIndex > 0 }" @click="cycleGlow">
        <span class="tt-button-key">辉光</span>
        <span>{{ glowLabel }}</span>
      </button>
      <button type="button" class="tt-button" :class="{ active: layers > 1 }" @click="cycleLayers">
        <span class="tt-button-key">嵌套</span>
        <span>{{ layers }} 层</span>
      </button>
      <button type="button" class="tt-button" @click="cyclePalette">
        <span class="tt-button-key">配色</span>
        <span>{{ paletteLabel }}</span>
      </button>
      <button type="button" class="tt-button" @click="toggleFullscreen">
        <span class="tt-button-key">显示</span>
        <span>全屏</span>
      </button>
    </div>

    <div class="tt-hud">
      <span class="tt-hud-row"><span class="tt-hud-k">顶点 / 棱</span><span class="tt-hud-v">16 / 32</span></span>
      <span class="tt-hud-row"><span class="tt-hud-k">旋转面</span><span class="tt-hud-v">{{ planeLabel }}</span></span>
      <span class="tt-hud-row"><span class="tt-hud-k">视角 θ₁ / θ₂</span><span class="tt-hud-v">{{ angleText }}</span></span>
      <span class="tt-hud-row"><span class="tt-hud-k">投影距离 d₄</span><span class="tt-hud-v">{{ d4Text }}</span></span>
      <span class="tt-hud-row"><span class="tt-hud-k">帧率</span><span class="tt-hud-v">{{ fps }}</span></span>
    </div>
  </div>
</template>

<script setup>
import { onBeforeUnmount, onMounted, ref, computed } from 'vue'
import * as THREE from 'three'

/* ============================================================
   四维超立方体（Tesseract）
   ============================================================

   不是"画个线框立方体转起来"，这是个真四维物体。三件事要分清楚：

     1. **16 个顶点在四维空间里**：坐标是 (±1, ±1, ±1, ±1)，
        每两个只差一个坐标位的点连一条棱 → 4 × 2³ = **32 条棱**。

     2. **四维旋转是"双旋转"**：三维里旋转由一根轴决定，四维里旋转由一个
        **平面**决定。一个四维旋转可以同时在**两个互相正交的平面**上转
        （XW 与 YZ），这就是超立方体看着"内外翻来翻去"的来源 ——
        单平面旋转只会让它像普通立方体那样平移式地转，没有那种魔术感。

     3. **两级投影**：4D → 3D（按 w 做透视除法）→ 屏幕（常规 MVP）。
        第一级的 d₄ 就是"四维眼睛到物体的距离"，滚轮改的就是它。

   ------------------------------------------------------------
   ⚠️ 关键约束（踩坑点，改之前先读）

   1. **棱就用 THREE.LineSegments 画，别自己撑四边形。**
      我曾经为了"可控线宽"在顶点着色器里按屏幕法线撑开每条棱（每条棱 6 个顶点）。
      数据、矩阵、shader 全部核对无误（连 GPU 里的顶点缓冲都读回来验过：
      drawArrays 确实画了 192 个顶点、三角形面积也有 200+ 像素²），
      但屏幕上一条线都没有 —— 撑开后的四边形顶点的"某条属性"经插值后
      会把抗锯齿算成 0，整条线被啃掉。查了很久，收益却只是"线粗一点"。
      **结论：线宽不值得为它手写一套屏幕空间展开。** 用原生线（1px）+
      后面的两级辉光，细亮丝 + 大光晕本身就是最经典的科幻线框质感。
   2. **加性混合下别把强度乘进 rgb**。AdditiveBlending = (SrcAlpha, One)，
      源 alpha 会被再乘一次；rgb 里再乘一遍 vAlpha 等于亮度平方，线会暗到看不见。
   3. **RT 必须是 HalfFloat**。亮线要在辉光里"溢"出来，8 位纹理钳在 1.0
      就永远 bloom 不出来，只会得到一批灰片。
   4. **辉光是两趟降采样**（1/2 再 1/4），不是一趟大模糊：近处的锐利光晕
      和远处的弥散光晕是两种尺度，一趟模糊只能选一个。
   5. 全屏后处理必须在**独立场景**里渲染那个四边形，别塞进主场景 ——
      否则 5 个全屏 pass 会把棱和顶点再画 5 遍。
   6. 渲染循环里只改 uniform，不 new Material（沿用展厅其它页面的不变量）。
   ============================================================ */

const mountRef = ref(null)

// ---------------- 四维几何（静态） ----------------

// 16 个顶点：(±1, ±1, ±1, ±1)
const VERTS4 = []
for (let i = 0; i < 16; i += 1) {
  VERTS4.push([(i & 1) ? 1 : -1, (i & 2) ? 1 : -1, (i & 4) ? 1 : -1, (i & 8) ? 1 : -1])
}
// 32 条棱：只差一个坐标位的两个顶点
const EDGES = []
for (let i = 0; i < 16; i += 1) {
  for (let b = 0; b < 4; b += 1) {
    const j = i ^ (1 << b)
    if (j > i) EDGES.push([i, j])
  }
}

// 双旋转的两个正交平面（四个坐标轴 0..3 分成两对，共三种分法）
const PLANES = [
  { label: 'XW · YZ', a: [0, 3], b: [1, 2] },
  { label: 'XY · ZW', a: [0, 1], b: [2, 3] },
  { label: 'XZ · YW', a: [0, 2], b: [1, 3] }
]

const SPEEDS = [
  { label: '停', w: 0 },
  { label: '慢', w: 0.17 },
  { label: '中', w: 0.36 },
  { label: '快', w: 0.68 }
]

const GLOWS = [
  { label: '关', v: 0 },
  { label: '中', v: 1.0 },
  { label: '强', v: 2.0 }
]

// 配色：uColA = 远端（w 小）棱色，uColB = 近端（w 大）棱色
const PALETTES = [
  { label: '青蓝', a: [0.16, 0.70, 1.00], b: [0.72, 0.42, 1.00], node: [0.78, 0.96, 1.00], tint: [0.10, 0.24, 0.58] },
  { label: '紫金', a: [0.66, 0.30, 1.00], b: [1.00, 0.78, 0.34], node: [1.00, 0.92, 0.66], tint: [0.30, 0.13, 0.44] },
  { label: '冰红', a: [0.26, 0.84, 1.00], b: [1.00, 0.34, 0.42], node: [1.00, 0.72, 0.70], tint: [0.38, 0.11, 0.22] },
  { label: '青翠', a: [0.18, 1.00, 0.70], b: [0.30, 0.70, 1.00], node: [0.80, 1.00, 0.92], tint: [0.07, 0.32, 0.28] }
]

const MAX_LAYERS = 3
const LINES_PER_LAYER = EDGES.length * 2   // 每条棱两个端点
const NODES_PER_LAYER = VERTS4.length
// 投影基准：相机距离，用于"近亮远暗"的换算
const CAM_Z = 4.6

// ---------------- 着色器 ----------------

// 棱：原生线段，只算颜色与亮度
//
// ⚠️ RawShaderMaterial 不注入任何内置量：position / projectionMatrix /
//    modelViewMatrix 都必须自己声明（不声明就直接编译失败，整页画不出来）。
const LINE_VERT = `
  precision highp float;
  uniform mat4 projectionMatrix;
  uniform mat4 modelViewMatrix;
  uniform float uTime;
  uniform vec3 uColA;
  uniform vec3 uColB;
  uniform float uIntensity;
  attribute vec3 position;
  attribute float aT;
  attribute float aW;
  attribute float aLayer;
  varying vec3 vColor;
  varying float vAlpha;

  void main() {
    vec4 clip = projectionMatrix * modelViewMatrix * vec4(position, 1.0);
    gl_Position = clip;

    // 沿棱奔跑的能量脉冲（aT 是 0→1 的棱参数）
    float pulse = 0.5 + 0.5 * sin(aT * 12.5664 - uTime * 3.1 + aLayer * 2.1);
    // w 越大越靠近"四维的眼睛"：更亮、色相更偏近端色
    float wf = clamp(aW * 0.42 + 0.5, 0.0, 1.0);
    // 相机深度衰减 + 嵌套层衰减
    float camFade = clamp(1.0 - (clip.w - 3.2) * 0.16, 0.22, 1.0);
    float layerFade = 1.0 / (1.0 + aLayer * 1.05);

    vColor = mix(uColA, uColB, wf);
    vAlpha = (0.30 + pulse * 1.15) * (0.34 + wf * 0.95) * camFade * layerFade * uIntensity;
  }`

const LINE_FRAG = `
  precision highp float;
  varying vec3 vColor;
  varying float vAlpha;
  void main() {
    // ⚠️ 别把 vAlpha 乘进 rgb：加性混合本身还会再乘一次源 alpha
    //    （AdditiveBlending = SrcAlpha, One），乘两遍等于亮度平方，
    //    线会暗到几乎看不见。强度只放在 alpha 里。
    gl_FragColor = vec4(vColor, vAlpha);
  }`

// 顶点节点：会呼吸的光点
const NODE_VERT = `
  precision highp float;
  uniform mat4 projectionMatrix;
  uniform mat4 modelViewMatrix;
  uniform float uTime;
  uniform float uSize;
  uniform float uIntensity;
  attribute vec3 position;
  attribute float aW;
  attribute float aLayer;
  varying float vAlpha;

  void main() {
    vec4 clip = projectionMatrix * modelViewMatrix * vec4(position, 1.0);
    gl_Position = clip;
    float persp = ${CAM_Z.toFixed(1)} / max(clip.w, 0.0001);
    float wf = clamp(aW * 0.42 + 0.5, 0.0, 1.0);
    float breathe = 0.78 + 0.22 * sin(uTime * 3.0 + aLayer * 1.7 + position.x * 2.0 + position.y * 1.3);
    gl_PointSize = max(uSize * persp * (0.7 + wf * 0.75) * breathe, 1.5);
    vAlpha = (0.30 + wf * 0.95) / (1.0 + aLayer * 1.1) * uIntensity * (0.82 + 0.18 * breathe);
  }`

const NODE_FRAG = `
  precision highp float;
  uniform vec3 uColNode;
  varying float vAlpha;
  void main() {
    vec2 d = gl_PointCoord - 0.5;
    float r = length(d) * 2.0;
    float core = 1.0 - smoothstep(0.10, 0.52, r);
    float halo = 1.0 - smoothstep(0.0, 1.0, r);
    // 同上：光斑剖面进 rgb、强度进 alpha，避免被加性混合乘成平方
    float a = core * 1.6 + halo * 0.55;
    gl_FragColor = vec4(uColNode * a, vAlpha);
  }`

// 全屏四边形：位置直接就是 NDC，uv 由 position 推出
const QUAD_VERT = `
  precision highp float;
  // ⚠️ 必须自己声明 position！RawShaderMaterial 不注入任何内置属性
  attribute vec3 position;
  varying vec2 vUv;
  void main() {
    vUv = position.xy * 0.5 + 0.5;
    gl_Position = vec4(position.xy, 0.0, 1.0);
  }`

// 9 抽头可分离高斯（权重和 = 1），第一趟顺带做阈值提取
const BLUR_FRAG = `
  precision highp float;
  uniform sampler2D uSrc;
  uniform vec2 uTexel;
  uniform vec2 uDir;
  uniform float uThreshold;
  varying vec2 vUv;

  void main() {
    vec2 d = uDir * uTexel;
    vec3 c = texture2D(uSrc, vUv).rgb * 0.2270;
    c += (texture2D(uSrc, vUv + d) + texture2D(uSrc, vUv - d)).rgb * 0.1945;
    c += (texture2D(uSrc, vUv + d * 2.0) + texture2D(uSrc, vUv - d * 2.0)).rgb * 0.1216;
    c += (texture2D(uSrc, vUv + d * 3.0) + texture2D(uSrc, vUv - d * 3.0)).rgb * 0.0541;
    c += (texture2D(uSrc, vUv + d * 4.0) + texture2D(uSrc, vUv - d * 4.0)).rgb * 0.0162;
    if (uThreshold > 0.0) c = max(c - uThreshold, vec3(0.0)) / max(1.0 - uThreshold, 0.001);
    gl_FragColor = vec4(c, 1.0);
  }`

// 合成：场景 + 两级辉光 + 程序化星野/星云 + ACES + 暗角
const COMPOSITE_FRAG = `
  precision highp float;
  uniform sampler2D uScene;
  uniform sampler2D uGlowNear;
  uniform sampler2D uGlowFar;
  uniform vec2 uResolution;
  uniform vec2 uPan;
  uniform float uTime;
  uniform float uGlow;
  uniform vec3 uTint;
  varying vec2 vUv;

  float hash21(vec2 p) {
    p = fract(p * vec2(123.34, 456.21));
    p += dot(p, p + 45.32);
    return fract(p.x * p.y);
  }
  float vnoise(vec2 p) {
    vec2 i = floor(p);
    vec2 f = fract(p);
    vec2 u = f * f * (3.0 - 2.0 * f);
    float a = hash21(i);
    float b = hash21(i + vec2(1.0, 0.0));
    float c = hash21(i + vec2(0.0, 1.0));
    float d = hash21(i + vec2(1.0, 1.0));
    return mix(mix(a, b, u.x), mix(c, d, u.x), u.y);
  }
  // 一层星：每个格子最多一颗，用亮度哈希决定疏密
  float starLayer(vec2 p, float seed, float size) {
    vec2 id = floor(p);
    vec2 f = fract(p) - 0.5;
    float h = hash21(id + seed);
    if (h < 0.94) return 0.0;
    vec2 off = (vec2(hash21(id + seed + 7.1), hash21(id + seed + 3.7)) - 0.5) * 0.72;
    float d = length(f - off);
    float tw = 0.55 + 0.45 * sin(uTime * 2.1 + h * 63.0);
    return (1.0 - smoothstep(0.0, size, d)) * tw * (h - 0.94) / 0.06;
  }
  vec3 aces(vec3 x) {
    return clamp((x * (2.51 * x + 0.03)) / (x * (2.43 * x + 0.59) + 0.14), 0.0, 1.0);
  }

  void main() {
    vec2 uv = vUv;
    vec2 e = uv - 0.5;
    float r2 = dot(e, e);

    // 色散：辉光按通道做径向偏移，边缘泛出彩边
    vec2 ca = e * 0.0075;
    float gR = texture2D(uGlowNear, uv - ca).r;
    float gG = texture2D(uGlowNear, uv).g;
    float gB = texture2D(uGlowNear, uv + ca).b;
    vec3 glow = vec3(gR, gG, gB) * 0.82 + texture2D(uGlowFar, uv).rgb * 1.5;

    vec3 col = texture2D(uScene, uv).rgb + glow * uGlow;

    // 星野：三层不同密度，跟着旋转缓慢平移做视差
    // ⚠️ gl_FragCoord/uResolution.y 已经是等比坐标了，别再乘一次 aspect ——
    //    乘两次会把格子拉成 260px 的大方块，星点变成一团团大白斑。
    vec2 sq = gl_FragCoord.xy / uResolution.y;
    vec2 p1 = sq * 78.0 + uPan * 2.0;
    vec2 p2 = p1 * 1.9 - uPan * 3.1;
    vec2 p3 = p1 * 3.6 + uPan * 5.2;
    float st = starLayer(p1, 0.0, 0.30) * 0.8
             + starLayer(p2, 11.0, 0.24) * 0.55
             + starLayer(p3, 23.0, 0.18) * 0.32;
    col += vec3(0.86, 0.92, 1.0) * st * 0.8;

    // 星云：低频（约 2.4 格铺满屏高），压得极暗，只负责给背景一点"体"
    // ⚠️ 别拿星野坐标 p1 来算 —— 那是 78 格的尺度，出来是一层高频麻点，
    //    而且亮到会把线条整个淹掉。
    vec2 nq = gl_FragCoord.xy / uResolution.y * 2.4 + uPan * 0.18;
    float n1 = vnoise(nq);
    float n2 = vnoise(nq * 2.1 + 4.7);
    float neb = n1 * 0.65 + n2 * 0.35;
    col += uTint * neb * neb * 0.24;

    col = aces(col * 1.06);
    col = pow(col, vec3(1.0 / 2.2));

    // 边缘压暗 + 轻微颗粒，避免整屏发灰
    col *= 1.0 - r2 * 0.55;
    float grain = hash21(gl_FragCoord.xy + fract(uTime) * 91.7) - 0.5;
    col += vec3(grain * 0.016);

    gl_FragColor = vec4(col, 1.0);
  }`

// ---------------- 页面状态 ----------------

const speedIndex = ref(1)
const planeIndex = ref(0)
const perspective = ref(true)
const glowIndex = ref(1)
const layers = ref(1)
const paletteIndex = ref(0)
const fps = ref(60)

const speedLabel = computed(() => SPEEDS[speedIndex.value].label)
const planeLabel = computed(() => PLANES[planeIndex.value].label)
const glowLabel = computed(() => GLOWS[glowIndex.value].label)
const paletteLabel = computed(() => PALETTES[paletteIndex.value].label)
const angleText = ref('0° / 0°')
const d4Text = ref('3.40')

// ---------------- 运行时状态 ----------------

let renderer = null
let scene = null
// 后处理专用场景：只装那个全屏四边形
let postScene = null
let quadMesh = null
let camera = null
let clock = null
let rafId = 0
let resizeObserver = null
const materials = []

let sceneRT = null
let glowNearA = null
let glowNearB = null
let glowFarA = null
let glowFarB = null

let lineMesh = null
let nodePoints = null
let lineGeo = null
let nodeGeo = null
let linePosAttr = null
let lineWAttr = null
let nodePosAttr = null
let nodeWAttr = null

let matLine = null
let matNode = null
let matBlurH1 = null
let matBlurV1 = null
let matBlurH2 = null
let matBlurV2 = null
let matComposite = null

// 静态布局表：第 v 个线段顶点属于 (层, 第几条棱, 哪个端点)
const lineLayout = []
// 4D 旋转角（弧度）与滚轮控制的投影距离
let angA = 0
let angB = 0
let d4 = 3.4
let elapsed = 0
let frameCount = 0
let fpsAccum = 0
let hudAccum = 0

const pointer = { down: false, x: 0, y: 0 }
const pan = new THREE.Vector2(0, 0)

// 复用的临时数组：每帧重算 16 顶点 × 3 层，别在循环里 new
const projBuf = new Float32Array(MAX_LAYERS * 16 * 3)
const wBuf = new Float32Array(MAX_LAYERS * 16)

/* ------------------------------------------------------------
   几何构建
   ------------------------------------------------------------ */

function buildGeometry() {
  const lineCount = MAX_LAYERS * LINES_PER_LAYER
  const nodeCount = MAX_LAYERS * NODES_PER_LAYER

  lineGeo = new THREE.BufferGeometry()
  linePosAttr = new THREE.BufferAttribute(new Float32Array(lineCount * 3), 3)
  lineWAttr = new THREE.BufferAttribute(new Float32Array(lineCount), 1)
  const tAttr = new THREE.BufferAttribute(new Float32Array(lineCount), 1)
  const layerAttr = new THREE.BufferAttribute(new Float32Array(lineCount), 1)

  let v = 0
  for (let L = 0; L < MAX_LAYERS; L += 1) {
    for (let e = 0; e < EDGES.length; e += 1) {
      // 一条棱两个端点的顺序是固定的：先 A 后 B
      for (let end = 0; end < 2; end += 1) {
        lineLayout[v] = { layer: L, edge: e, end }
        tAttr.array[v] = end
        layerAttr.array[v] = L
        v += 1
      }
    }
  }

  lineGeo.setAttribute('position', linePosAttr)
  lineGeo.setAttribute('aT', tAttr)
  lineGeo.setAttribute('aW', lineWAttr)
  lineGeo.setAttribute('aLayer', layerAttr)

  nodeGeo = new THREE.BufferGeometry()
  nodePosAttr = new THREE.BufferAttribute(new Float32Array(nodeCount * 3), 3)
  nodeWAttr = new THREE.BufferAttribute(new Float32Array(nodeCount), 1)
  const nodeLayerAttr = new THREE.BufferAttribute(new Float32Array(nodeCount), 1)
  for (let L = 0; L < MAX_LAYERS; L += 1) {
    for (let i = 0; i < NODES_PER_LAYER; i += 1) {
      nodeLayerAttr.array[L * NODES_PER_LAYER + i] = L
    }
  }
  nodeGeo.setAttribute('position', nodePosAttr)
  nodeGeo.setAttribute('aW', nodeWAttr)
  nodeGeo.setAttribute('aLayer', nodeLayerAttr)

  // 顶点每帧都在动，别让 three 拿旧的包围球去剔除
  lineGeo.boundingSphere = new THREE.Sphere(new THREE.Vector3(), 12)
  nodeGeo.boundingSphere = new THREE.Sphere(new THREE.Vector3(), 12)
}

/* ------------------------------------------------------------
   四维数学
   ------------------------------------------------------------ */

// 在 (i, j) 平面上旋转角 θ
function rotatePlane(p, i, j, ang) {
  const c = Math.cos(ang)
  const s = Math.sin(ang)
  const a = p[i]
  const b = p[j]
  p[i] = a * c - b * s
  p[j] = a * s + b * c
}

// 把 16 个四维顶点旋转 + 投影成三维，写进 projBuf / wBuf
function projectLayer(L) {
  const plane = PLANES[planeIndex.value]
  const persp = perspective.value
  // 嵌套层：整体缩小 + 相位错开，奇偶层反向旋转（对转才好看）
  // 0.82 是留给画面的余量：w 的极值是 ±√2，透视除法会把顶点推到 ~1.7 倍，
  // 不留边距就会顶到画布上边缘
  const scale = 0.82 / (1 + L * 0.52)
  const phase = L * 0.95
  const dir = (L % 2 === 0) ? 1 : -1

  for (let i = 0; i < 16; i += 1) {
    const src = VERTS4[i]
    const p = [src[0], src[1], src[2], src[3]]
    rotatePlane(p, plane.a[0], plane.a[1], angA * dir + phase)
    rotatePlane(p, plane.b[0], plane.b[1], angB * dir - phase)

    const w = p[3]
    // 4D → 3D：按 w 做透视除法。正交投影下 w 只用于上色（远近色差）
    let k = 1
    if (persp) {
      k = d4 / Math.max(d4 - w, 0.22)
    }
    const o = (L * 16 + i) * 3
    projBuf[o] = p[0] * k * scale
    projBuf[o + 1] = p[1] * k * scale
    projBuf[o + 2] = p[2] * k * scale
    wBuf[L * 16 + i] = w
  }
}

function updateGeometry() {
  for (let L = 0; L < MAX_LAYERS; L += 1) projectLayer(L)

  for (let v = 0; v < lineLayout.length; v += 1) {
    const info = lineLayout[v]
    const vi = EDGES[info.edge][info.end === 0 ? 0 : 1]
    const src = (info.layer * 16 + vi) * 3
    linePosAttr.array[v * 3] = projBuf[src]
    linePosAttr.array[v * 3 + 1] = projBuf[src + 1]
    linePosAttr.array[v * 3 + 2] = projBuf[src + 2]
    lineWAttr.array[v] = wBuf[info.layer * 16 + vi]
  }

  for (let L = 0; L < MAX_LAYERS; L += 1) {
    for (let i = 0; i < 16; i += 1) {
      const n = L * 16 + i
      const src = (L * 16 + i) * 3
      nodePosAttr.array[n * 3] = projBuf[src]
      nodePosAttr.array[n * 3 + 1] = projBuf[src + 1]
      nodePosAttr.array[n * 3 + 2] = projBuf[src + 2]
      nodeWAttr.array[n] = wBuf[L * 16 + i]
    }
  }

  linePosAttr.needsUpdate = true
  lineWAttr.needsUpdate = true
  nodePosAttr.needsUpdate = true
  nodeWAttr.needsUpdate = true

  // 只画"嵌套层数"之内的顶点：缓冲是按层顺序排的，直接截断
  lineGeo.setDrawRange(0, layers.value * LINES_PER_LAYER)
  nodeGeo.setDrawRange(0, layers.value * NODES_PER_LAYER)
}

/* ------------------------------------------------------------
   初始化
   ------------------------------------------------------------ */

onMounted(() => {
  const container = mountRef.value
  if (!container) return

  const canvas = document.createElement('canvas')
  const gl = canvas.getContext('webgl2', { alpha: false, antialias: false, powerPreference: 'high-performance' })
    || canvas.getContext('webgl', { alpha: false, antialias: false, powerPreference: 'high-performance' })
  if (!gl) {
    container.innerHTML =
      '<div style="padding:40px;color:#7f8fb5;font-size:14px">当前浏览器不支持 WebGL，无法运行四维超立方体。</div>'
    return
  }

  renderer = new THREE.WebGLRenderer({ canvas, context: gl, antialias: false, alpha: false })
  renderer.setPixelRatio(Math.min(window.devicePixelRatio, 2))
  renderer.setSize(container.clientWidth, container.clientHeight)
  renderer.setClearColor(0x000000, 1)
  renderer.domElement.classList.add('tt-canvas')
  container.appendChild(renderer.domElement)

  scene = new THREE.Scene()
  postScene = new THREE.Scene()
  camera = new THREE.PerspectiveCamera(42, container.clientWidth / Math.max(container.clientHeight, 1), 0.1, 40)
  camera.position.set(0, 0, CAM_Z)

  buildGeometry()
  buildMeshes()
  buildTargets()
  buildPassMaterials()
  bindEvents()

  clock = new THREE.Clock()
  animate()
})

function buildMeshes() {
  matLine = new THREE.RawShaderMaterial({
    vertexShader: LINE_VERT,
    fragmentShader: LINE_FRAG,
    uniforms: {
      uTime: { value: 0 },
      uColA: { value: new THREE.Vector3(...PALETTES[0].a) },
      uColB: { value: new THREE.Vector3(...PALETTES[0].b) },
      uIntensity: { value: 1.5 }
    },
    transparent: true,
    blending: THREE.AdditiveBlending,
    depthTest: false,
    depthWrite: false
  })
  materials.push(matLine)

  matNode = new THREE.RawShaderMaterial({
    vertexShader: NODE_VERT,
    fragmentShader: NODE_FRAG,
    uniforms: {
      uTime: { value: 0 },
      uSize: { value: 13 },
      uColNode: { value: new THREE.Vector3(...PALETTES[0].node) },
      uIntensity: { value: 1.5 }
    },
    transparent: true,
    blending: THREE.AdditiveBlending,
    depthTest: false,
    depthWrite: false
  })
  materials.push(matNode)

  lineMesh = new THREE.LineSegments(lineGeo, matLine)
  lineMesh.frustumCulled = false
  scene.add(lineMesh)

  nodePoints = new THREE.Points(nodeGeo, matNode)
  nodePoints.frustumCulled = false
  scene.add(nodePoints)
}

// 建 RT：场景全分辨率（HalfFloat 才是 HDR），两级辉光分别是 1/2 与 1/4
function makeTarget(w, h) {
  const rt = new THREE.WebGLRenderTarget(Math.max(w, 2), Math.max(h, 2), {
    type: THREE.HalfFloatType,
    format: THREE.RGBAFormat,
    minFilter: THREE.LinearFilter,
    magFilter: THREE.LinearFilter,
    wrapS: THREE.ClampToEdgeWrapping,
    wrapT: THREE.ClampToEdgeWrapping,
    depthBuffer: false,
    stencilBuffer: false
  })
  rt.texture.generateMipmaps = false
  return rt
}

function disposeTargets() {
  for (const rt of [sceneRT, glowNearA, glowNearB, glowFarA, glowFarB]) rt?.dispose?.()
  sceneRT = glowNearA = glowNearB = glowFarA = glowFarB = null
}

function buildTargets() {
  disposeTargets()
  const size = renderer.getDrawingBufferSize(new THREE.Vector2())
  const w = Math.max(Math.round(size.x), 2)
  const h = Math.max(Math.round(size.y), 2)
  sceneRT = makeTarget(w, h)
  glowNearA = makeTarget(Math.round(w / 2), Math.round(h / 2))
  glowNearB = makeTarget(Math.round(w / 2), Math.round(h / 2))
  glowFarA = makeTarget(Math.round(w / 4), Math.round(h / 4))
  glowFarB = makeTarget(Math.round(w / 4), Math.round(h / 4))
}

function makeMaterial(frag, uniforms) {
  const mat = new THREE.RawShaderMaterial({
    vertexShader: QUAD_VERT,
    fragmentShader: frag,
    uniforms,
    depthTest: false,
    depthWrite: false
  })
  materials.push(mat)
  return mat
}

function buildPassMaterials() {
  const quad = createQuadGeometry()
  quadMesh = new THREE.Mesh(quad, null)
  quadMesh.frustumCulled = false
  postScene.add(quadMesh)

  const mk = (dirX, dirY, threshold) => makeMaterial(BLUR_FRAG, {
    uSrc: { value: null },
    uTexel: { value: new THREE.Vector2(1, 1) },
    uDir: { value: new THREE.Vector2(dirX, dirY) },
    uThreshold: { value: threshold }
  })
  matBlurH1 = mk(1, 0, 0.24)
  matBlurV1 = mk(0, 1, 0)
  matBlurH2 = mk(1, 0, 0)
  matBlurV2 = mk(0, 1, 0)

  matComposite = makeMaterial(COMPOSITE_FRAG, {
    uScene: { value: null },
    uGlowNear: { value: null },
    uGlowFar: { value: null },
    uResolution: { value: new THREE.Vector2(1, 1) },
    uPan: { value: new THREE.Vector2(0, 0) },
    uTime: { value: 0 },
    uGlow: { value: GLOWS[glowIndex.value].v },
    uTint: { value: new THREE.Vector3(...PALETTES[0].tint) }
  })
}

function createQuadGeometry() {
  const geo = new THREE.BufferGeometry()
  geo.setAttribute('position', new THREE.BufferAttribute(new Float32Array([
    -1, -1, 0, 1, -1, 0, 1, 1, 0,
    -1, -1, 0, 1, 1, 0, -1, 1, 0
  ]), 3))
  return geo
}

function runPass(material, target) {
  quadMesh.material = material
  renderer.setRenderTarget(target)
  renderer.render(postScene, camera)
  renderer.setRenderTarget(null)
}

/* ------------------------------------------------------------
   渲染循环
   ------------------------------------------------------------ */

function animate() {
  rafId = requestAnimationFrame(animate)
  const dt = Math.min(clock.getDelta(), 1 / 30)
  elapsed += dt

  // 双旋转：两个正交平面各自匀速转
  const w = SPEEDS[speedIndex.value].w
  angA += dt * w
  angB += dt * w * 0.63

  // 星野缓慢视差
  pan.set(Math.sin(angA * 0.35) * 0.9, Math.cos(angB * 0.42) * 0.9)

  updateGeometry()

  matLine.uniforms.uTime.value = elapsed
  matNode.uniforms.uTime.value = elapsed
  matComposite.uniforms.uTime.value = elapsed
  matComposite.uniforms.uPan.value.copy(pan)
  matComposite.uniforms.uResolution.value.set(
    renderer.domElement.width, renderer.domElement.height
  )

  // 1) 场景（黑底 + 加性发光的棱与顶点）→ sceneRT
  renderer.setRenderTarget(sceneRT)
  renderer.clear()
  renderer.render(scene, camera)
  renderer.setRenderTarget(null)

  // 2) 近端辉光：抠亮部 → 1/2 分辨率两趟高斯
  matBlurH1.uniforms.uSrc.value = sceneRT.texture
  matBlurH1.uniforms.uTexel.value.set(1 / glowNearA.width, 1 / glowNearA.height)
  runPass(matBlurH1, glowNearA)

  matBlurV1.uniforms.uSrc.value = glowNearA.texture
  matBlurV1.uniforms.uTexel.value.set(1 / glowNearB.width, 1 / glowNearB.height)
  runPass(matBlurV1, glowNearB)

  // 3) 远端辉光：从近端辉光再降到 1/4，做出弥散的大光晕
  matBlurH2.uniforms.uSrc.value = glowNearB.texture
  matBlurH2.uniforms.uTexel.value.set(1 / glowFarA.width, 1 / glowFarA.height)
  runPass(matBlurH2, glowFarA)

  matBlurV2.uniforms.uSrc.value = glowFarA.texture
  matBlurV2.uniforms.uTexel.value.set(1 / glowFarB.width, 1 / glowFarB.height)
  runPass(matBlurV2, glowFarB)

  // 4) 合成到屏幕
  matComposite.uniforms.uScene.value = sceneRT.texture
  matComposite.uniforms.uGlowNear.value = glowNearB.texture
  matComposite.uniforms.uGlowFar.value = glowFarB.texture
  matComposite.uniforms.uGlow.value = GLOWS[glowIndex.value].v
  runPass(matComposite, null)

  frameCount += 1
  fpsAccum += dt
  if (fpsAccum >= 0.5) {
    fps.value = Math.round(frameCount / fpsAccum)
    frameCount = 0
    fpsAccum = 0
  }

  // HUD 上的角度别每帧刷（Vue 重渲染吃不消），4Hz 足够
  hudAccum += dt
  if (hudAccum >= 0.25) {
    hudAccum = 0
    const deg = (r) => {
      const d = (r * 180 / Math.PI) % 360
      return (d < 0 ? d + 360 : d).toFixed(0)
    }
    angleText.value = `${deg(angA)}° / ${deg(angB)}°`
    d4Text.value = d4.toFixed(2)
  }
}

/* ------------------------------------------------------------
   交互
   ------------------------------------------------------------ */

function onPointerDown(e) {
  if (e.target.closest('.tt-button')) return
  pointer.down = true
  pointer.x = e.clientX
  pointer.y = e.clientY
  renderer.domElement.setPointerCapture?.(e.pointerId)
}

function onPointerMove(e) {
  if (!pointer.down) return
  const dx = e.clientX - pointer.x
  const dy = e.clientY - pointer.y
  pointer.x = e.clientX
  pointer.y = e.clientY
  // 直接把拖拽量加到四维旋转角上：拖的是"旋转本身"，不是相机
  angA += dx * 0.0075
  angB += dy * 0.0075
}

function onPointerUp(e) {
  pointer.down = false
  renderer.domElement.releasePointerCapture?.(e.pointerId)
}

// 滚轮改四维投影距离：d₄ 越小，"四维的眼睛"贴得越近，翻出来的幅度越夸张
function onWheel(e) {
  e.preventDefault()
  d4 = Math.min(Math.max(d4 + e.deltaY * 0.0018, 2.05), 7.6)
}

function bindEvents() {
  const el = renderer.domElement
  el.addEventListener('pointerdown', onPointerDown)
  el.addEventListener('pointermove', onPointerMove)
  el.addEventListener('pointerup', onPointerUp)
  el.addEventListener('pointercancel', onPointerUp)
  el.addEventListener('wheel', onWheel, { passive: false })
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
  camera.aspect = w / h
  camera.updateProjectionMatrix()
  buildTargets()
}

/* ------------------------------------------------------------
   按钮
   ------------------------------------------------------------ */

function cycleSpeed() { speedIndex.value = (speedIndex.value + 1) % SPEEDS.length }

function cyclePlane() {
  planeIndex.value = (planeIndex.value + 1) % PLANES.length
}

function togglePerspective() { perspective.value = !perspective.value }

function cycleGlow() { glowIndex.value = (glowIndex.value + 1) % GLOWS.length }

function cycleLayers() { layers.value = (layers.value % MAX_LAYERS) + 1 }

function cyclePalette() {
  paletteIndex.value = (paletteIndex.value + 1) % PALETTES.length
  const p = PALETTES[paletteIndex.value]
  matLine.uniforms.uColA.value.set(...p.a)
  matLine.uniforms.uColB.value.set(...p.b)
  matNode.uniforms.uColNode.value.set(...p.node)
  matComposite.uniforms.uTint.value.set(...p.tint)
}

function toggleFullscreen() {
  if (document.fullscreenElement) document.exitFullscreen()
  else mountRef.value?.requestFullscreen?.().catch(() => {})
}

/* ------------------------------------------------------------
   释放
   ------------------------------------------------------------ */

onBeforeUnmount(() => {
  window.cancelAnimationFrame(rafId)
  if (resizeObserver) resizeObserver.disconnect()
  if (renderer) {
    const el = renderer.domElement
    el.removeEventListener('pointerdown', onPointerDown)
    el.removeEventListener('pointermove', onPointerMove)
    el.removeEventListener('pointerup', onPointerUp)
    el.removeEventListener('pointercancel', onPointerUp)
    el.removeEventListener('wheel', onWheel)
  }

  disposeTargets()
  lineGeo?.dispose()
  nodeGeo?.dispose()
  quadMesh?.geometry.dispose()
  for (const m of materials) m.dispose()
  if (renderer) {
    renderer.dispose()
    renderer.domElement.remove()
  }

  // 清空预建引用：组件重新挂载（切路由回来）不能再拿到已销毁的对象
  materials.length = 0
  matLine = matNode = matBlurH1 = matBlurV1 = matBlurH2 = matBlurV2 = matComposite = null
  lineMesh = nodePoints = quadMesh = null
  linePosAttr = lineWAttr = nodePosAttr = nodeWAttr = null
  lineLayout.length = 0
})
</script>

<style scoped>
.tt-view {
  position: relative;
  width: 100%;
  height: 100%;
  min-height: 560px;
  overflow: hidden;
  background: #01030a;
}

.tt-canvas {
  position: absolute;
  inset: 0;
  display: block;
  touch-action: none;
  cursor: grab;
}

.tt-canvas:active {
  cursor: grabbing;
}

.tt-header {
  position: absolute;
  top: 28px;
  left: 32px;
  z-index: 2;
  pointer-events: none;
}

.tt-eyebrow {
  display: block;
  font-size: 11px;
  letter-spacing: 3px;
  text-transform: uppercase;
  color: #4f7fa8;
}

.tt-title {
  margin: 6px 0 4px;
  font-size: 26px;
  font-weight: 600;
  letter-spacing: 2px;
  color: #d8ecff;
  text-shadow: 0 0 20px rgba(90, 190, 255, 0.35);
}

.tt-desc {
  margin: 0;
  font-size: 13px;
  color: #7d90b5;
}

.tt-toolbar {
  position: absolute;
  top: 28px;
  right: 32px;
  z-index: 2;
  display: flex;
  gap: 10px;
  flex-wrap: wrap;
  justify-content: flex-end;
  max-width: 66%;
}

.tt-button {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 14px;
  border: 1px solid rgba(96, 190, 255, 0.32);
  border-radius: 8px;
  background: rgba(4, 12, 26, 0.62);
  color: #b6d6f2;
  font-size: 13px;
  cursor: pointer;
  transition: all 0.2s ease;
}

.tt-button:hover {
  border-color: rgba(96, 190, 255, 0.72);
  color: #eaf6ff;
}

.tt-button.active {
  border-color: rgba(120, 210, 255, 0.9);
  background: rgba(30, 90, 150, 0.34);
  color: #f0f9ff;
  box-shadow: 0 0 14px rgba(80, 180, 255, 0.28);
}

.tt-button-key {
  font-size: 11px;
  padding: 1px 6px;
  border: 1px solid rgba(96, 190, 255, 0.42);
  border-radius: 4px;
  color: #7fc4f0;
}

.tt-hud {
  position: absolute;
  left: 32px;
  bottom: 28px;
  z-index: 2;
  /* ⚠️ 必须放行指针：HUD 压在画布上，不放行会吃掉左下角一整片拖拽区域 */
  pointer-events: none;
  display: flex;
  flex-direction: column;
  gap: 6px;
  padding: 12px 16px;
  border: 1px solid rgba(96, 190, 255, 0.2);
  border-radius: 8px;
  background: rgba(3, 10, 22, 0.66);
  font-size: 12px;
  backdrop-filter: blur(6px);
}

.tt-hud-row {
  display: flex;
  justify-content: space-between;
  gap: 18px;
}

.tt-hud-k {
  color: #6b83a8;
}

.tt-hud-v {
  color: #c8e4fb;
  font-variant-numeric: tabular-nums;
}
</style>
