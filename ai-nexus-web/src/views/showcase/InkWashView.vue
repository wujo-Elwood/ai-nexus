<template>
  <div ref="mountRef" class="ink-view">
    <div class="ik-header">
      <span class="ik-eyebrow">Ink Wash · 写意山水</span>
      <h1 class="ik-title">水墨晕染</h1>
      <p class="ik-desc">墨分五色 · 宣纸渗化 · 拖动落笔成山，松手让墨自己走</p>
    </div>

    <div class="ik-toolbar">
      <button type="button" class="ik-button" :class="{ active: painting }" @click="togglePainting">
        <span class="ik-button-key">作画</span>
        <span>{{ painting ? '进行' : '暂停' }}</span>
      </button>
      <button type="button" class="ik-button" :class="{ active: isWet }" @click="cycleWetness">
        <span class="ik-button-key">湿度</span>
        <span>{{ wetnessLabel }}</span>
      </button>
      <button type="button" class="ik-button" :class="{ active: showPaper }" @click="togglePaper">
        <span class="ik-button-key">宣纸</span>
        <span>{{ showPaper ? '有纹' : '平滑' }}</span>
      </button>
      <button type="button" class="ik-button" @click="clearCanvas">
        <span class="ik-button-key">洗笔</span>
        <span>清空</span>
      </button>
      <button type="button" class="ik-button" @click="stampMountain">
        <span class="ik-button-key">成画</span>
        <span>随机山水</span>
      </button>
    </div>

    <div class="ik-hud">
      <span class="ik-hud-row"><span class="ik-hud-k">纸面</span><span class="ik-hud-v">{{ canvasLabel }}</span></span>
      <span class="ik-hud-row"><span class="ik-hud-k">墨色层数</span><span class="ik-hud-v">{{ INK_LAYERS }}</span></span>
      <span class="ik-hud-row"><span class="ik-hud-k">扩散半径</span><span class="ik-hud-v">{{ DIFFUSE_RADIUS.toFixed(1) }}</span></span>
      <span class="ik-hud-row"><span class="ik-hud-k">帧率</span><span class="ik-hud-v">{{ fps }}</span></span>
    </div>

    <div class="ik-seal">墨</div>
  </div>
</template>

<script setup>
import { onBeforeUnmount, onMounted, ref, computed } from 'vue'
import * as THREE from 'three'

/* ============================================================
   水墨晕染（写意山水 · 宣纸渗化）
   ============================================================

   这不是"把噪声画成灰色"那种伪水墨。真实水墨有三个必须分别模拟的物理行为：

     1. **渗化（bleeding）**：水在宣纸上向外扩散，把墨粒带着走。
        对应算法是**反应-扩散系统**（Reaction-Diffusion），用两个场：
          A = 墨的浓度（pigment）
          B = 水的浓度（water）
        墨靠水扩散（扩散系数正比于水量），水自己也会扩散但会逐渐蒸发。

     2. **边缘沉积（edge deposition / coffee-ring）**：
        这是水墨最标志性的特征 —— 水干时墨粒被推到边界，形成**深色毛边**。
        纯扩散只会让墨均匀变淡，永远画不出那条"墨边"。
        实现方式：墨的扩散系数随**墨浓度梯度**变化 —— 边缘浓度低处扩散快，
        中心高处扩散慢，于是墨自动往边缘堆积。

     3. **宣纸纹理（paper fiber）**：
        宣纸纤维有方向性，让渗化呈各向异性；同时纸的凹凸影响墨的沉降量。
        用固定种子的 value-noise 生成一张静态纹理，采样后调制扩散系数。

   ------------------------------------------------------------
   ⚠️ 关键约束（踩坑点，改之前先读）

   1. **必须是浮点纹理**。墨浓度跨度很大（从 0.001 的淡墨到 1.0 的焦墨），
      8 位纹理只有 256 级，淡墨会直接量化成 0 —— 画面只剩几团硬边的黑块。
   2. **反应-扩散的步长和系数必须匹配**。扩散系数太大（> 0.25）会数值发散，
      表现为满屏棋盘格噪声。本项目把扩散拆成"每个邻居"的权重累加形式，
      保证四邻居权重和 ≤ 1。
   3. **不要每帧都重新生成纸张噪声**。噪声只跟像素位置有关，是静态的，
      建一次纹理复用即可；每帧重算既慢又会让纸纹"沸腾"。
   4. **笔刷轨迹要插值**。鼠标在两帧之间可能移动几十像素，直接在各帧位置
      落点会画成一串断开的圆点（"串珠"）。必须沿上一帧到当前帧的线段采样。
      采样间距要按 `simHeight` 折算 —— 笔触的像素半径是 `radius * simHeight`
      （见 STAMP_FRAG 的 `d.x *= uTexel.y / uTexel.x`），按 simWidth 估会大 1.7 倍。
   5. **笔触核不能"全渐隐"，也不能太硬**。"太模糊"的根因就是旧写法
      `1.0 - smoothstep(0.0, uRadius, dist)`：从圆心一路渐隐，几十个 stamp
      叠出来是没有边界的灰雾。正确做法是「实心核 + 一圈柔化边」（0.65 倍半径），
      但核再往外推（试过 0.78）会跟 ~0.45 倍半径的采样间距打起来 —— 串珠。
   6. **显示端的 S 曲线是"看着清晰"的关键**：浓度先线性映射再过
      `a*a*(3-2a)`，淡雾更淡、实笔更实。系数别贪大（1.25 会把浓度 0.8
      直接顶到死黑，笔道失去浓淡层次，像马克笔）。
   7. **改采样密度必须同步配平墨量**。落墨次数变多，单位长度的墨量会成倍变化，
      本文件里所有 `w.pigment * k` / `w.water * k` 的 k 都是彼此耦合的，别单独调一个。
   ============================================================ */

const mountRef = ref(null)
const painting = ref(true)
const showPaper = ref(true)
const fps = ref(60)
const wetnessIndex = ref(1)

// 墨色层数：每层用一种不同浓度的墨叠加，模拟"墨分五色"
const INK_LAYERS = 5
// 扩散半径系数
const DIFFUSE_RADIUS = 2.4
// 水蒸发速率：越大墨干得越快、晕染范围越小
const EVAPORATION = 0.0055
// 墨的扩散系数（会被水量与纸张纹理调制）
// ⚠️ 这个值直接决定笔道"糊不糊"。原来 0.16 偏大：每帧都把墨抹平一次，
//    拖出来的是一条没有边界的灰雾。收到 0.10，笔道中心才留得住浓度。
const PIGMENT_DIFFUSION = 0.10
// 水的扩散系数（比墨大，水跑得比墨快）
// 水跑多远，墨就被带多远。0.22 → 0.15，收一下水的外溢范围。
const WATER_DIFFUSION = 0.15
// 边缘沉积强度：把墨往边界推的力度
const EDGE_DEPOSIT = 0.85

const WETNESS_PRESETS = [
  { label: '焦墨', pigment: 0.92, water: 0.22, evap: 0.010 },
  { label: '重墨', pigment: 0.68, water: 0.44, evap: 0.0055 },
  { label: '淡墨', pigment: 0.36, water: 0.72, evap: 0.0032 },
  { label: '清墨', pigment: 0.18, water: 0.90, evap: 0.0020 }
]

const wetnessLabel = computed(() => WETNESS_PRESETS[wetnessIndex.value].label)
// 水量过半的预设（淡墨 / 清墨）在按钮上高亮，让当前"润湿程度"一眼可见
const isWet = computed(() => WETNESS_PRESETS[wetnessIndex.value].water > 0.5)
const canvasLabel = computed(() => (paperSize.value ? `${paperSize.value.x} × ${paperSize.value.y}` : '—'))

let renderer = null
let scene = null
let camera = null
let clock = null
let rafId = 0
let resizeObserver = null
let quadMesh = null
const materials = []

// 模拟场：pigment（墨）/ water（水）各两张 ping-pong
let pigmentFBO = null
let waterFBO = null
let paperTex = null

// 预建的 pass 材质（遵循"渲染循环内绝不 new Material"不变量）
let matDiffusePigment = null
let matDiffuseWater = null
let matEvaporate = null
let matStampPigment = null
let matStampWater = null
let matDisplay = null
let matClear = null

const paperSize = ref(null)

let simWidth = 0
let simHeight = 0

// 指针：记录上一帧位置，用于沿线段插值落笔
const pointer = {
  x: 0.5, y: 0.5,
  prevX: 0.5, prevY: 0.5,
  down: false,
  active: false
}
let strokeSeed = 0
let frameCount = 0
let fpsAccum = 0
let elapsed = 0

/* ------------------------------------------------------------
   着色器
   ------------------------------------------------------------ */

// 全屏四边形：位置直接就是 NDC，uv 在顶点着色器里由 position 推导
const QUAD_VERT = `
  precision highp float;
  // ⚠️ 必须自己声明 position！RawShaderMaterial **不会**注入 Three.js 的内置属性
  //    （ShaderMaterial 才会注入 position/uv/modelMatrix 等）。
  //    曾经这里只声明了 aUv 却直接用 position.xy —— 顶点着色器报
  //    「'position' : undeclared identifier」直接编译失败，
  //    整个页面一个像素都画不出来（点开毫无反应）。
  attribute vec3 position;
  varying vec2 vUv;
  void main() {
    vUv = position.xy * 0.5 + 0.5;
    gl_Position = vec4(position.xy, 0.0, 1.0);
  }`

// 扩散 pass：把墨/水向四邻居扩散
//
// ⚠️ 用「本点权重 + 四邻居权重」的显式格式，四邻居权重和必须 ≤ 1，
//    否则迭代会数值发散（满屏棋盘格）。
// ⚠️ 墨的扩散系数额外被「墨浓度梯度」调制，这是**边缘沉积**的来源：
//    梯度大的地方（墨迹边缘）扩散更快，墨于是往边界堆积，形成深色毛边。
const DIFFUSE_FRAG = `
  precision highp float;
  uniform sampler2D uField;
  uniform sampler2D uWater;
  uniform sampler2D uPaper;
  uniform vec2 uTexel;
  uniform float uDiffusion;
  uniform float uIsPigment;
  uniform float uEdgeDeposit;
  uniform float uPaperInfluence;
  varying vec2 vUv;

  void main() {
    vec2 t = uTexel;
    float c  = texture2D(uField, vUv).r;
    float l  = texture2D(uField, vUv - vec2(t.x, 0.0)).r;
    float r  = texture2D(uField, vUv + vec2(t.x, 0.0)).r;
    float d  = texture2D(uField, vUv - vec2(0.0, t.y)).r;
    float u  = texture2D(uField, vUv + vec2(0.0, t.y)).r;

    // 纸张纤维的各向异性：纸纹让扩散沿某方向更快
    float paper = texture2D(uPaper, vUv).r;
    float aniso = mix(1.0, 0.75 + paper * 0.5, uPaperInfluence);

    // 水的存在让墨更容易走（湿处扩散快，干处几乎不动）
    float w = texture2D(uWater, vUv).r;
    float wetness = clamp(w * 1.6, 0.0, 1.0);
    float k = uDiffusion * mix(0.18, 1.0, wetness) * aniso;

    // ---- 边缘沉积 ----
    // 用拉普拉斯算子估浓度曲率：墨迹边缘处曲率为负（比邻居淡），
    // 于是给它额外的权重，墨粒被推向边界。
    float laplacian = (l + r + d + u) - 4.0 * c;
    float edge = clamp(-laplacian * 4.0, 0.0, 1.0) * uIsPigment * uEdgeDeposit;

    // ⚠️ 必须「守恒」：本点收缩的权重 = 扩散 k + 沉积系数之和。
    //    曾经的写法是 c*(1-k) + gain + deposit，沉积项**额外加权**，
    //    四邻居总权重达到 1.2975（>1）—— 数值验证实测第 57 步就发散、
    //    墨浓度顶到钳位上限，画面变成一片死黑。
    //    正确做法：沉积项要显式从本点扣掉，保证总权重恒为 1。
    float depositK = edge * 0.30;
    // 兜底钳位：无论如何都不能让收缩权重超过 1，否则 c*(1-shrink) 变负、
    // 迭代立刻炸掉。正常参数下是不会碰到的，属于安全网。
    float shrink = min(k + depositK, 0.95);

    float outv = c * (1.0 - shrink) + shrink * 0.25 * (l + r + d + u);
    gl_FragColor = vec4(clamp(outv, 0.0, 2.0), 0.0, 0.0, 1.0);
  }`

// 水：扩散 + 蒸发
const EVAPORATE_FRAG = `
  precision highp float;
  uniform sampler2D uWater;
  uniform sampler2D uPaper;
  uniform vec2 uTexel;
  uniform float uDiffusion;
  uniform float uEvaporation;
  uniform float uPaperInfluence;
  varying vec2 vUv;

  void main() {
    vec2 t = uTexel;
    float c  = texture2D(uWater, vUv).r;
    float l  = texture2D(uWater, vUv - vec2(t.x, 0.0)).r;
    float r  = texture2D(uWater, vUv + vec2(t.x, 0.0)).r;
    float d  = texture2D(uWater, vUv - vec2(0.0, t.y)).r;
    float u  = texture2D(uWater, vUv + vec2(0.0, t.y)).r;

    float paper = texture2D(uPaper, vUv).r;
    // 纸纹高处吸水性差（水难停留），低洼处积水 —— 这是"纸不匀"的观感来源
    float absorb = mix(1.0, 0.7 + (1.0 - paper) * 0.6, uPaperInfluence);
    float k = uDiffusion * absorb;

    float diffused = c * (1.0 - k) + k * 0.25 * (l + r + d + u);
    // 蒸发：水在边缘蒸发更快（暴露面积大），于是墨迹自然收边
    float edgeLoss = abs(l - r) + abs(d - u);
    float evap = uEvaporation * (1.0 + edgeLoss * 2.0);

    gl_FragColor = vec4(max(diffused - evap, 0.0), 0.0, 0.0, 1.0);
  }`

// 落笔：在给定点注入墨与水
const STAMP_FRAG = `
  precision highp float;
  uniform sampler2D uField;
  uniform vec2 uTexel;
  uniform vec2 uPoint;
  uniform vec2 uDir;
  uniform float uRadius;
  uniform float uAmount;
  uniform float uWet;
  uniform float uSeed;
  varying vec2 vUv;

  // GLSL ES 1.00 的浮点哈希（不能用 uint，那是 3.00 特性）
  float hash(vec2 p) {
    return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453123);
  }
  float vnoise(vec2 p) {
    vec2 i = floor(p);
    vec2 f = fract(p);
    vec2 u2 = f * f * (3.0 - 2.0 * f);
    float a = hash(i);
    float b = hash(i + vec2(1.0, 0.0));
    float c = hash(i + vec2(0.0, 1.0));
    float d = hash(i + vec2(1.0, 1.0));
    return mix(mix(a, b, u2.x), mix(c, d, u2.x), u2.y);
  }

  void main() {
    // 修正纵横比，让笔触是圆的而不是椭圆
    vec2 d = vUv - uPoint;
    d.x *= uTexel.y / uTexel.x;
    float dist = length(d);

    // 笔触形状：实心笔肚 + 一圈柔化边（毛笔分叉的毛边靠下面的 fibers 噪声出）
    // ⚠️ 旧写法 1.0 - smoothstep(0.0, uRadius, dist) 是从圆心一路渐隐到边缘，
    //    整条笔画处处都是半透明渐变，几十个 stamp 叠出来就是一团没有边界的雾
    //    —— 这就是"太模糊"的直接来源。现在半径 65% 以内保持满浓度、
    //    最外 35% 柔化：笔道立得住，又留得住宣纸渗化的软边。
    //    ⚠️ 别把实心核再往外推（试过 0.78）：stamp 的实际间距约 0.45 倍像素半径，
    //    核太硬会让相邻 stamp 的叠加出现"串珠"（一圈圈圆盘连成的糖葫芦）。
    float core = 1.0 - smoothstep(uRadius * 0.65, uRadius, dist);
    if (core <= 0.0) {
      gl_FragColor = vec4(texture2D(uField, vUv).r, 0.0, 0.0, 1.0);
      return;
    }

    // 沿笔锋方向的纤维噪声，制造"飞白"
    float fibers = vnoise(vec2(dot(d, uDir) * 60.0, dot(d, vec2(-uDir.y, uDir.x)) * 14.0) + uSeed);
    // 飞白太强会把笔道啃成断续的斑，0.45 → 0.32
    float ragged = mix(1.0, fibers, 0.32);

    // 落笔速度影响墨量：慢笔浓、快笔枯（这是真实的运笔规律）
    float amount = uAmount * core * ragged;
    float cur = texture2D(uField, vUv).r;

    gl_FragColor = vec4(min(cur + amount, 2.0), 0.0, 0.0, 1.0);
    // 水的注入量单独由 uWet 决定，写在同一张纹理的 G 通道备用
  }`

// 水注入（与墨共用 STAMP 结构，但写入 water 场）
const STAMP_WATER_FRAG = `
  precision highp float;
  uniform sampler2D uField;
  uniform vec2 uTexel;
  uniform vec2 uPoint;
  uniform float uRadius;
  uniform float uAmount;
  varying vec2 vUv;

  void main() {
    vec2 d = vUv - uPoint;
    d.x *= uTexel.y / uTexel.x;
    float dist = length(d);
    // 水比墨多铺一点点（真实落笔是水先漫开），但必须收着给：
    // 旧的 1.35 倍半径全柔化水圈，会把笔道周围的墨整体拽散，是另一处"模糊"来源。
    float core = 1.0 - smoothstep(uRadius * 0.85, uRadius * 1.15, dist);
    float cur = texture2D(uField, vUv).r;
    gl_FragColor = vec4(min(cur + uAmount * core, 2.0), 0.0, 0.0, 1.0);
  }`

// 显示：墨 + 水 + 纸纹 → 屏幕
const DISPLAY_FRAG = `
  precision highp float;
  uniform sampler2D uPigment;
  uniform sampler2D uWater;
  uniform sampler2D uPaper;
  uniform float uPaperMix;
  uniform float uTime;
  varying vec2 vUv;

  // 宣纸底色：偏暖的米白，带一点不均匀（真实宣纸不是纯白）
  vec3 paperColor(vec2 uv) {
    float fiber = texture2D(uPaper, uv).r;
    float fiber2 = texture2D(uPaper, uv * 3.7).r;
    vec3 base = vec3(0.937, 0.918, 0.878);
    // 纤维造成的轻微明暗起伏
    base *= 0.972 + fiber * 0.030 + fiber2 * 0.014;
    return base;
  }

  void main() {
    float ink = texture2D(uPigment, vUv).r;
    float water = texture2D(uWater, vUv).r;

    vec3 paper = paperColor(vUv);
    // 水让纸变暗一点（湿痕），干后恢复
    paper *= 1.0 - clamp(water * 0.16, 0.0, 0.22);

    // 墨色不是纯黑，而是偏暖的褐色（真实墨含胶，干后泛褐）
    vec3 inkColor = vec3(0.075, 0.070, 0.082);

    // 墨分五色：低浓度时偏灰蓝（淡墨），中段就要压到近黑（焦墨）
    // 旧参数 smoothstep(0.05, 0.85) 太靠后，中浓度全落在灰雾区，越看越糊。
    float density = clamp(ink, 0.0, 1.6);
    vec3 tint = mix(vec3(0.30, 0.32, 0.38), inkColor, smoothstep(0.04, 0.60, density));

    // 不透明度：线性映射后再过一道 S 曲线 —— 淡雾更淡、实笔更实。
    // 0→0、1→1，不会断层；这是"看着清晰"的关键一步。
    // ⚠️ 前面的系数别调太大（试过 1.25）：浓度 0.8 就顶到 1.0，
    //    整条笔道会被压成没有浓淡变化的死黑（像马克笔），失掉墨色层次。
    float alpha = clamp(density * 0.95, 0.0, 1.0);
    alpha = alpha * alpha * (3.0 - 2.0 * alpha);

    vec3 col = mix(paper, tint, alpha);

    // 极淡的墨处再压一点冷色，模拟"墨气"
    col -= vec3(0.02, 0.02, 0.035) * smoothstep(0.0, 0.22, density) * (1.0 - step(0.5, density));

    // 纸纹颗粒叠加（只在有墨处明显，纯纸上很微弱）
    float grain = texture2D(uPaper, vUv * 7.3 + uTime * 0.0007).r;
    col *= 1.0 - grain * 0.035 * (0.35 + alpha * 0.65) * uPaperMix;

    // 纸张边缘轻微压暗（裱褙感）
    vec2 e = vUv - 0.5;
    col *= 1.0 - dot(e, e) * 0.14;

    gl_FragColor = vec4(col, 1.0);
  }`

// 清空场
const CLEAR_FRAG = `
  precision highp float;
  uniform float uValue;
  varying vec2 vUv;
  void main() { gl_FragColor = vec4(uValue, 0.0, 0.0, 1.0); }`

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
      '<div style="padding:40px;color:#8a7a5f;font-size:14px">当前浏览器不支持 WebGL，无法运行水墨模拟。</div>'
    return
  }

  renderer = new THREE.WebGLRenderer({ canvas, context: gl, antialias: false, alpha: false })
  renderer.setPixelRatio(Math.min(window.devicePixelRatio, 2))
  renderer.setSize(container.clientWidth, container.clientHeight)
  renderer.setClearColor(0xefeadd, 1)
  renderer.domElement.classList.add('ik-canvas')
  container.appendChild(renderer.domElement)

  scene = new THREE.Scene()
  camera = new THREE.OrthographicCamera(-1, 1, 1, -1, 0, 1)
  quadMesh = new THREE.Mesh(createQuadGeometry(), null)
  quadMesh.frustumCulled = false
  scene.add(quadMesh)

  initSimulation()
  bindEvents()

  clock = new THREE.Clock()
  // 开局保持一张空纸：只留背景润湿（水场 0.12，不是墨），不落任何墨。
  // 想看成画效果，点右上角「成画 · 随机山水」。
  animate()
})

// 全屏四边形。位置直接用 NDC 坐标；uv 在顶点着色器里由 position 推出。
// ⚠️ 不用 gl_VertexID（GLSL ES 1.00 没有，那是 3.00 的特性）。
// ⚠️ 也别指望 aUv —— RawShaderMaterial 下多一个属性就多一份绑定开销，
//    而 position.xy*0.5+0.5 已经就是 uv，没必要单独传。
function createQuadGeometry() {
  const geo = new THREE.BufferGeometry()
  geo.setAttribute('position', new THREE.BufferAttribute(new Float32Array([
    -1, -1, 0, 1, -1, 0, 1, 1, 0,
    -1, -1, 0, 1, 1, 0, -1, 1, 0
  ]), 3))
  return geo
}

// 生成宣纸纤维纹理（静态，只做一次）
//
// ⚠️ 绝不能每帧重算：噪声只与像素位置有关，是静态的。
//    每帧重算既浪费 CPU，又会让纸纹随帧跳动（"沸腾"）。
function bakePaperTexture(w, h) {
  const data = new Uint8Array(w * h * 4)
  // 确定性哈希，保证每次进页面纸纹一致
  const hash = (x, y) => {
    let n = x * 374761393 + y * 668265263
    n = (n ^ (n >> 13)) * 1274126177
    return ((n ^ (n >> 16)) >>> 0) / 4294967296
  }
  const vnoise = (fx, fy) => {
    const ix = Math.floor(fx), iy = Math.floor(fy)
    const xf = fx - ix, yf = fy - iy
    const u = xf * xf * (3 - 2 * xf)
    const v = yf * yf * (3 - 2 * yf)
    const a = hash(ix, iy), b = hash(ix + 1, iy)
    const c = hash(ix, iy + 1), d = hash(ix + 1, iy + 1)
    return (a * (1 - u) + b * u) * (1 - v) + (c * (1 - u) + d * u) * v
  }

  for (let y = 0; y < h; y += 1) {
    for (let x = 0; x < w; x += 1) {
      const nx = x / w, ny = y / h
      // 各向异性纤维：横向拉伸的噪声（宣纸纤维多为横向排列）
      let v = vnoise(nx * 26, ny * 130) * 0.55
      v += vnoise(nx * 96, ny * 210) * 0.30
      v += vnoise(nx * 240, ny * 340) * 0.15
      // 少量纵向粗纤维
      v = v * 0.86 + vnoise(nx * 150, ny * 18) * 0.14
      const c = Math.max(0, Math.min(255, Math.round(v * 255)))
      const i = (y * w + x) * 4
      data[i] = c; data[i + 1] = c; data[i + 2] = c; data[i + 3] = 255
    }
  }

  const tex = new THREE.DataTexture(data, w, h, THREE.RGBAFormat)
  tex.wrapS = THREE.RepeatWrapping
  tex.wrapT = THREE.RepeatWrapping
  tex.minFilter = THREE.LinearFilter
  tex.magFilter = THREE.LinearFilter
  tex.needsUpdate = true
  return tex
}

function createFBO(w, h) {
  const rt = new THREE.WebGLRenderTarget(w, h, {
    // ⚠️ 必须浮点：8 位会让淡墨直接量化成 0，只剩几团硬边黑块
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

function createDoubleFBO(w, h) {
  let a = createFBO(w, h)
  let b = createFBO(w, h)
  return {
    width: w,
    height: h,
    get read() { return a },
    get write() { return b },
    swap() { const t = a; a = b; b = t }
  }
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

function runPass(material, target) {
  quadMesh.material = material
  renderer.setRenderTarget(target)
  renderer.render(scene, camera)
  renderer.setRenderTarget(null)
}

// 预建所有 pass 材质：渲染循环里只改 uniform 的值，绝不 new Material
function buildPassMaterials() {
  const texel = new THREE.Vector2(1 / simWidth, 1 / simHeight)
  const w = WETNESS_PRESETS[wetnessIndex.value]

  matDiffusePigment = makeMaterial(DIFFUSE_FRAG, {
    uField: { value: null },
    uWater: { value: null },
    uPaper: { value: paperTex },
    uTexel: { value: texel },
    uDiffusion: { value: PIGMENT_DIFFUSION * DIFFUSE_RADIUS * 0.1 },
    uIsPigment: { value: 1 },
    uEdgeDeposit: { value: EDGE_DEPOSIT },
    uPaperInfluence: { value: 1 }
  })

  matDiffuseWater = makeMaterial(DIFFUSE_FRAG, {
    uField: { value: null },
    uWater: { value: null },
    uPaper: { value: paperTex },
    uTexel: { value: texel },
    uDiffusion: { value: WATER_DIFFUSION * DIFFUSE_RADIUS * 0.1 },
    uIsPigment: { value: 0 },
    uEdgeDeposit: { value: 0 },
    uPaperInfluence: { value: 1 }
  })

  matEvaporate = makeMaterial(EVAPORATE_FRAG, {
    uWater: { value: null },
    uPaper: { value: paperTex },
    uTexel: { value: texel },
    uDiffusion: { value: WATER_DIFFUSION * DIFFUSE_RADIUS * 0.08 },
    uEvaporation: { value: w.evap },
    uPaperInfluence: { value: 1 }
  })

  matStampPigment = makeMaterial(STAMP_FRAG, {
    uField: { value: null },
    uTexel: { value: texel },
    uPoint: { value: new THREE.Vector2() },
    uDir: { value: new THREE.Vector2(1, 0) },
    uRadius: { value: 0.02 },
    uAmount: { value: 0.5 },
    uWet: { value: 0.5 },
    uSeed: { value: 0 }
  })

  matStampWater = makeMaterial(STAMP_WATER_FRAG, {
    uField: { value: null },
    uTexel: { value: texel },
    uPoint: { value: new THREE.Vector2() },
    uRadius: { value: 0.02 },
    uAmount: { value: 0.5 }
  })

  matDisplay = makeMaterial(DISPLAY_FRAG, {
    uPigment: { value: null },
    uWater: { value: null },
    uPaper: { value: paperTex },
    uPaperMix: { value: 1 },
    uTime: { value: 0 }
  })

  matClear = makeMaterial(CLEAR_FRAG, { uValue: { value: 0.0 } })
}

function initSimulation() {
  const container = mountRef.value
  const aspect = container.clientWidth / Math.max(container.clientHeight, 1)

  // 反应-扩散对分辨率不敏感，但太低会糊、太高就只是更贵。
  // 以较短边定基准，保证墨迹在横竖屏下粗细一致。
  const base = Math.min(Math.max(container.clientWidth * 0.75, 480), 1100)
  simWidth = Math.round(base * (aspect > 1 ? 1 : aspect))
  simHeight = Math.round(base * (aspect > 1 ? 1 / aspect : 1))
  simWidth = Math.max(simWidth, 256)
  simHeight = Math.max(simHeight, 256)

  paperSize.value = { x: simWidth, y: simHeight }

  paperTex = bakePaperTexture(512, 512)

  pigmentFBO = createDoubleFBO(simWidth, simHeight)
  waterFBO = createDoubleFBO(simWidth, simHeight)

  buildPassMaterials()

  // 开场：整张纸均匀润湿一点，墨才有地方走
  runPass(matClear, pigmentFBO.read)
  runPass(matClear, pigmentFBO.write)

  matClear.uniforms.uValue.value = 0.12
  runPass(matClear, waterFBO.read)
  runPass(matClear, waterFBO.write)
  matClear.uniforms.uValue.value = 0.0
}

/* ------------------------------------------------------------
   绘画操作
   ------------------------------------------------------------ */

// 沿线段插值落笔
//
// ⚠️ 鼠标在两帧之间可能移动几十个像素，只在各帧位置落点会画成
//    一串断开的圆点（"串珠"）。必须沿上一帧到当前帧的线段等距采样。
function strokeSegment(x0, y0, x1, y1, amount, wet, radius) {
  const dx = x1 - x0
  const dy = y1 - y0

  // 按"半个半径"的间距采样，保证笔触连续且不过度重叠
  // ⚠️ 间距必须按 simHeight 算，不能用 simWidth。
  //    STAMP_FRAG 里 d.x *= uTexel.y / uTexel.x 之后，笔触是一个**像素空间的正圆**，
  //    它的像素半径 = radius * simHeight。横屏画布（simWidth > simHeight）下按
  //    simWidth 估间距会大 1.7 倍，相邻 stamp 的实心核刚好擦边 ——
  //    一笔下去就是一串断开的小圆盘（"串珠"）。
  const dist = Math.hypot(dx * simWidth, dy * simHeight)
  const steps = Math.max(1, Math.min(Math.ceil(dist / (radius * simHeight * 0.45)), 64))
  const dir = new THREE.Vector2(dx, dy)
  if (dir.lengthSq() > 1e-12) dir.normalize()
  else dir.set(1, 0)

  strokeSeed += 1

  for (let i = 1; i <= steps; i += 1) {
    const t = i / steps
    const px = x0 + dx * t
    const py = y0 + dy * t

    // 落笔速度影响墨量：慢笔浓、快笔枯
    const speedFactor = 1 / (1 + dist * 0.004)

    matStampPigment.uniforms.uField.value = pigmentFBO.read.texture
    matStampPigment.uniforms.uPoint.value.set(px, py)
    matStampPigment.uniforms.uDir.value.copy(dir)
    matStampPigment.uniforms.uRadius.value = radius
    matStampPigment.uniforms.uAmount.value = amount * speedFactor
    matStampPigment.uniforms.uSeed.value = strokeSeed * 0.37 + i * 0.11
    runPass(matStampPigment, pigmentFBO.write)
    pigmentFBO.swap()

    matStampWater.uniforms.uField.value = waterFBO.read.texture
    matStampWater.uniforms.uPoint.value.set(px, py)
    matStampWater.uniforms.uRadius.value = radius
    matStampWater.uniforms.uAmount.value = wet
    runPass(matStampWater, waterFBO.write)
    waterFBO.swap()
  }
}

// 生成一幅"随机山水"：几笔远山 + 一点近景
function stampMountain() {
  const w = WETNESS_PRESETS[wetnessIndex.value]
  // 用时间做种子，每次点的画面不同
  const seed = (Date.now() % 100000) / 100000
  const rnd = (i) => {
    const v = Math.sin(seed * 9301 + i * 4973) * 43758.5453
    return v - Math.floor(v)
  }

  // 远山：一条起伏的横向笔触，淡墨
  // ⚠️ 墨量系数是跟着"采样密度"重新配平的：采样间距从 radius*simWidth*0.45
  //    收到 radius*simHeight*0.45 之后，同样一笔的落墨次数约 1.7 倍，
  //    再加上实心核的覆盖面比原来的渐变锥更大，总量约为旧值的 3 倍。
  //    沿用旧的 0.30/0.48/0.66 会把远山糊成三条死黑带。
  const layers = 3
  for (let L = 0; L < layers; L += 1) {
    const yBase = 0.62 - L * 0.11
    const amp = 0.05 + L * 0.022
    const ink = w.pigment * (0.14 + L * 0.08)
    const radius = 0.026 + L * 0.007
    let px = 0.06
    let py = yBase + (rnd(L * 10) - 0.5) * amp
    while (px < 0.94) {
      const nx = px + 0.028 + rnd(L * 10 + px * 100) * 0.02
      const ny = yBase + Math.sin(px * 11 + L * 2.4 + seed * 6) * amp
        + (rnd(L * 10 + px * 57) - 0.5) * amp * 0.7
      strokeSegment(px, py, nx, ny, ink, w.water * 0.32, radius)
      px = nx
      py = ny
    }
  }

  // 近处一棵/一块石头：重墨
  const rx = 0.16 + rnd(99) * 0.6
  const ry = 0.80
  strokeSegment(rx, ry, rx + (rnd(7) - 0.5) * 0.05, ry - 0.10, w.pigment * 0.36, w.water * 0.20, 0.030)

  // 一点飞白点缀
  for (let i = 0; i < 6; i += 1) {
    const ax = 0.1 + rnd(i * 3) * 0.8
    const ay = 0.8 + rnd(i * 5) * 0.12
    strokeSegment(ax, ay, ax + (rnd(i) - 0.5) * 0.03, ay + (rnd(i * 2) - 0.5) * 0.02,
      w.pigment * 0.11, w.water * 0.14, 0.012)
  }
}

function clearCanvas() {
  if (!renderer) return
  matClear.uniforms.uValue.value = 0.0
  runPass(matClear, pigmentFBO.read)
  runPass(matClear, pigmentFBO.write)
  runPass(matClear, waterFBO.read)
  runPass(matClear, waterFBO.write)
  matClear.uniforms.uValue.value = 0.12
  runPass(matClear, waterFBO.read)
  runPass(matClear, waterFBO.write)
  matClear.uniforms.uValue.value = 0.0
  quadMesh.material = matDisplay
}

/* ------------------------------------------------------------
   模拟步进
   ------------------------------------------------------------ */

function step() {
  const w = WETNESS_PRESETS[wetnessIndex.value]

  // 1) 墨扩散（含边缘沉积）
  matDiffusePigment.uniforms.uField.value = pigmentFBO.read.texture
  matDiffusePigment.uniforms.uWater.value = waterFBO.read.texture
  runPass(matDiffusePigment, pigmentFBO.write)
  pigmentFBO.swap()

  // 2) 水扩散 + 蒸发
  matEvaporate.uniforms.uWater.value = waterFBO.read.texture
  matEvaporate.uniforms.uEvaporation.value = w.evap
  runPass(matEvaporate, waterFBO.write)
  waterFBO.swap()

  // 3) 墨再单独做一次弱扩散：让边缘沉积的墨粒更自然地摊开
  matDiffuseWater.uniforms.uField.value = pigmentFBO.read.texture
  matDiffuseWater.uniforms.uWater.value = waterFBO.read.texture
  runPass(matDiffuseWater, pigmentFBO.write)
  pigmentFBO.swap()
}

/* ------------------------------------------------------------
   渲染循环
   ------------------------------------------------------------ */

function animate() {
  rafId = requestAnimationFrame(animate)
  const dt = Math.min(clock.getDelta(), 1 / 30)
  elapsed += dt

  if (painting.value) {
    // 反应-扩散每帧跑固定次数，保证不同帧率下墨迹演化速度一致
    step()

    // 指针按下时持续落笔（即使鼠标不动，也会渗一点，模拟"驻笔"）
    if (pointer.down && pointer.active) {
      const w = WETNESS_PRESETS[wetnessIndex.value]
      // 墨量给足、笔锋略细：笔道才有实体感（快笔枯的规律由 strokeSegment 的 speedFactor 负责）
      // ⚠️ 采样间距变密之后（见 strokeSegment），单位长度的墨量约为原来的 3 倍，
      //    这里的系数是重新配平的：0.24（旧间距下的虚淡）→ 0.26（新间距下的浓度刚好）。
      strokeSegment(pointer.prevX, pointer.prevY, pointer.x, pointer.y,
        w.pigment * 0.26, w.water * 0.12, 0.013)
      pointer.prevX = pointer.x
      pointer.prevY = pointer.y
    }
  }

  // 合成到屏幕
  matDisplay.uniforms.uPigment.value = pigmentFBO.read.texture
  matDisplay.uniforms.uWater.value = waterFBO.read.texture
  matDisplay.uniforms.uPaperMix.value = showPaper.value ? 1 : 0
  matDisplay.uniforms.uTime.value = elapsed
  runPass(matDisplay, null)

  frameCount += 1
  fpsAccum += dt
  if (fpsAccum >= 0.5) {
    fps.value = Math.round(frameCount / fpsAccum)
    frameCount = 0
    fpsAccum = 0
  }
}

/* ------------------------------------------------------------
   事件
   ------------------------------------------------------------ */

function pointerPos(e) {
  const rect = renderer.domElement.getBoundingClientRect()
  // 等比映射到 [0,1]：以较长边为准，避免拉伸
  const cx = (e.clientX - rect.left) / rect.width
  const cy = 1 - (e.clientY - rect.top) / rect.height
  return { x: cx, y: cy }
}

function onPointerDown(e) {
  if (e.target.closest('.ik-button')) return
  const p = pointerPos(e)
  pointer.x = p.x
  pointer.y = p.y
  pointer.prevX = p.x
  pointer.prevY = p.y
  pointer.down = true
  pointer.active = true
  renderer.domElement.setPointerCapture?.(e.pointerId)
}

function onPointerMove(e) {
  const p = pointerPos(e)
  pointer.x = p.x
  pointer.y = p.y
  pointer.active = true
  // 不按下时也更新 prev，避免下次按下时从旧位置拉一条长线
  if (!pointer.down) {
    pointer.prevX = p.x
    pointer.prevY = p.y
  }
}

function onPointerUp(e) {
  pointer.down = false
  renderer.domElement.releasePointerCapture?.(e.pointerId)
}

function onPointerLeave() {
  pointer.active = false
}

function togglePainting() { painting.value = !painting.value }
function togglePaper() { showPaper.value = !showPaper.value }
function cycleWetness() {
  wetnessIndex.value = (wetnessIndex.value + 1) % WETNESS_PRESETS.length
}

function bindEvents() {
  const el = renderer.domElement
  el.addEventListener('pointerdown', onPointerDown)
  el.addEventListener('pointermove', onPointerMove)
  el.addEventListener('pointerleave', onPointerLeave)
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
  // ⚠️ 不重建模拟网格：重建会丢掉整幅画。只改视口。
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
    el.removeEventListener('pointerleave', onPointerLeave)
  }

  const disposeDouble = (d) => {
    if (!d) return
    d.read?.dispose?.()
    d.write?.dispose?.()
  }
  disposeDouble(pigmentFBO)
  disposeDouble(waterFBO)
  paperTex?.dispose?.()

  for (const m of materials) m.dispose()
  if (quadMesh) quadMesh.geometry.dispose()
  if (renderer) {
    renderer.dispose()
    renderer.domElement.remove()
  }

  pigmentFBO = waterFBO = paperTex = null
  // 清空预建材质引用，否则组件重新挂载（切路由回来）会拿到已销毁的材质
  matDiffusePigment = matDiffuseWater = matEvaporate = null
  matStampPigment = matStampWater = matClear = null
  matDisplay = null
  materials.length = 0
})
</script>

<style scoped>
.ink-view {
  position: relative;
  width: 100%;
  height: 100%;
  min-height: 560px;
  overflow: hidden;
  background: #efeadd;
}

.ik-canvas {
  position: absolute;
  inset: 0;
  display: block;
  touch-action: none;
  cursor: crosshair;
}

.ik-header {
  position: absolute;
  top: 28px;
  left: 32px;
  z-index: 2;
  pointer-events: none;
}

.ik-eyebrow {
  display: block;
  font-size: 11px;
  letter-spacing: 3px;
  text-transform: uppercase;
  color: #a89a7d;
}

.ik-title {
  margin: 6px 0 4px;
  font-size: 28px;
  font-weight: 600;
  letter-spacing: 4px;
  color: #2c2620;
}

.ik-desc {
  margin: 0;
  font-size: 13px;
  color: #7a6d58;
}

.ik-toolbar {
  position: absolute;
  top: 28px;
  right: 32px;
  z-index: 2;
  display: flex;
  gap: 10px;
  flex-wrap: wrap;
  justify-content: flex-end;
  max-width: 62%;
}

.ik-button {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 14px;
  border: 1px solid rgba(84, 68, 44, 0.28);
  border-radius: 4px;
  background: rgba(255, 253, 247, 0.72);
  color: #4a3f2e;
  font-size: 13px;
  cursor: pointer;
  transition: all 0.2s ease;
  backdrop-filter: blur(6px);
}

.ik-button:hover {
  border-color: rgba(84, 68, 44, 0.6);
  background: rgba(255, 253, 247, 0.92);
}

.ik-button.active {
  border-color: #6b5636;
  background: rgba(107, 86, 54, 0.14);
  color: #2c2620;
  box-shadow: 0 0 0 1px rgba(107, 86, 54, 0.25);
}

.ik-button-key {
  font-size: 11px;
  padding: 1px 6px;
  border: 1px solid rgba(84, 68, 44, 0.32);
  border-radius: 3px;
  color: #7a6d58;
}

.ik-hud {
  position: absolute;
  left: 32px;
  bottom: 28px;
  z-index: 2;
  /* ⚠️ 必须放行指针：HUD 压在画布上，不放行会吃掉左下角一整片落笔区域 */
  pointer-events: none;
  display: flex;
  flex-direction: column;
  gap: 6px;
  padding: 12px 16px;
  border: 1px solid rgba(84, 68, 44, 0.2);
  border-radius: 4px;
  background: rgba(255, 253, 247, 0.7);
  font-size: 12px;
  backdrop-filter: blur(6px);
}

.ik-hud-row {
  display: flex;
  justify-content: space-between;
  gap: 18px;
}

.ik-hud-k {
  color: #8c8069;
}

.ik-hud-v {
  color: #4a3f2e;
  font-variant-numeric: tabular-nums;
}

/* 右下角印章 */
.ik-seal {
  position: absolute;
  right: 36px;
  bottom: 32px;
  z-index: 2;
  width: 44px;
  height: 44px;
  display: flex;
  align-items: center;
  justify-content: center;
  border: 2px solid #a8342a;
  border-radius: 3px;
  color: #a8342a;
  font-size: 22px;
  font-weight: 500;
  letter-spacing: 0;
  background: rgba(168, 52, 42, 0.06);
  pointer-events: none;
  transform: rotate(-4deg);
}
</style>
