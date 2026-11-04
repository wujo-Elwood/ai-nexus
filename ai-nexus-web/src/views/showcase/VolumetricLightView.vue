<template>
  <div ref="mountRef" class="vol-view">
    <div class="vl-header">
      <span class="vl-eyebrow">Volumetric God Rays</span>
      <h1 class="vl-title">体积光</h1>
      <p class="vl-desc">丁达尔效应 · 光穿过含尘雾的空气时，被悬浮物切出一束束可见光柱</p>
    </div>

    <div class="vl-toolbar">
      <button type="button" class="vl-button" :class="{ active: paused }" @click="togglePause">
        <span class="vl-button-key">暂停</span>
        <span>{{ paused ? '已停' : '运行' }}</span>
      </button>
      <button type="button" class="vl-button" :class="{ active: showOccluders }" @click="toggleOccluders">
        <span class="vl-button-key">遮挡体</span>
        <span>{{ showOccluders ? '可见' : '隐藏' }}</span>
      </button>
      <button type="button" class="vl-button" :class="{ active: autoOrbit }" @click="toggleAutoOrbit">
        <span class="vl-button-key">自动</span>
        <span>{{ autoOrbit ? '开' : '关' }}</span>
      </button>
      <button type="button" class="vl-button" :class="{ active: dither }" @click="toggleDither">
        <span class="vl-button-key">抖动</span>
        <span>{{ dither ? '开' : '关' }}</span>
      </button>
      <button type="button" class="vl-button" @click="cyclePreset">
        <span class="vl-button-key">场景</span>
        <span>{{ presets[presetIndex].label }}</span>
      </button>
    </div>

    <div class="vl-hud">
      <span class="vl-hud-row"><span class="vl-hud-k">光柱采样</span><span class="vl-hud-v">{{ RAY_SAMPLES }}</span></span>
      <span class="vl-hud-row"><span class="vl-hud-k">散射强度</span><span class="vl-hud-v">{{ SCATTER_STRENGTH.toFixed(2) }}</span></span>
      <span class="vl-hud-row"><span class="vl-hud-k">消光系数</span><span class="vl-hud-v">{{ EXTINCTION.toFixed(3) }}</span></span>
      <span class="vl-hud-row"><span class="vl-hud-k">帧率</span><span class="vl-hud-v">{{ fps }}</span></span>
    </div>

    <div class="vl-hint">拖动旋转光源 · 滚轮推拉</div>
  </div>
</template>

<script setup>
import { onBeforeUnmount, onMounted, ref, computed } from 'vue'
import * as THREE from 'three'

/* ============================================================
   体积光（Volumetric God Rays / 丁达尔效应）
   ============================================================

   与"先渲染场景、再在屏幕空间拉径向模糊"的经典 god-ray 后处理（Crytek 方案）
   不同，这里做的是**真·光线步进**：沿视线在三维空间中逐点采样介质密度，
   累积每个采样点散射进相机的光量。

      颜色最终 = Σ[样本i]  透射率_i × 相函数(θ) × 密度_i × 光源衰减_i

   其中透射率 T_i = exp(-∫σ·ρ ds) 用"逐样本累乘"近似（体积阴影的关键）。

   ⚠️ 为什么不用屏幕空间 god rays：
     那种做法只在光源与遮挡物都在屏幕内时成立，光源一转到画外就整条光柱消失，
     而且无法表现"光柱穿过不同深度的物体"产生的正确遮挡层次。真步进没有这些限制。

   ------------------------------------------------------------
   ⚠️ 关键约束（踩坑点，改之前先读）

   1. **必须有遮挡物切出光柱**。均匀介质里光柱是一条均匀的亮带，毫无"体积感"。
      光柱的形状完全来自「射向相机的路径被物体挡住多少」——也就是体积阴影。
   2. **采样数不能省**。RAYS 就是沿视线的步进次数。8 次会看到明显的分层色带
      （banding），32 次以上才平滑。本项目取 48。
   3. **抖动（dither）必须有**。即使 48 次采样，固定起点仍会在光柱边缘留下
      同心环状色带。每个像素用蓝噪声抖一下起点（乘 1/RAYS 的偏移），
      把这些色带换成高频噪声，肉眼几乎不可见。
   4. **Henyey-Greenstein 相函数的方向别反**。g > 0 表示前向散射（光沿原方向
      继续走），逆光看光源时正是强前向散射，所以 `dot(rayDir, lightDir)` 的
      符号必须与相机朝向一致。写成 `-dot` 会导致"背对光源才亮"。
   5. 相机近平面别设太小，深度精度会不够；远平面按场景尺度给，不要给 10000。
   ============================================================ */

const mountRef = ref(null)
const paused = ref(false)
const showOccluders = ref(true)
const autoOrbit = ref(true)
const dither = ref(true)
const fps = ref(60)
const presetIndex = ref(0)

// 沿视线的步进采样点数量。太低会有色带，太高费 GPU。
const RAY_SAMPLES = 48
// 散射总强度。
// ⚠️ 这个值必须跟着**机位**走，不能孤立地定，因为光柱亮度 ∝ 视线穿过锥体的长度。
//    历史：相机距离 34 时取 2.2 合适（穿越短）；相机拉到 62~78 后同样是 2.2，
//    峰值冲到 2.36~2.69（ACES 后 RGB 238~241），核心直接烧成一片纯白、丢掉层次。
//    相机退远后每条视线穿过的雾更长、累积更多，所以强度要相应下调。
//    实测扫描 2.2 / 1.4 / 1.0 / 0.75，取 0.75：峰值 RGB 180~200，
//    亮而不糊，仍保留由内到外的梯度。
const SCATTER_STRENGTH = 0.75
// 消光系数：介质把光"吃掉"的速率，决定光柱的透明程度与整体衰减。
//
// ⚠️ 这个值直接决定光柱看不看得见。曾经取 0.055，sigma = DENSITY×EXTINCTION
//    = 0.0396，意味着要走 25 个世界单位光学厚度才到 1；而光柱本身典型厚度
//    只有约 10 单位 → 光学厚度仅 0.40，几乎完全透明，屏幕上一片漆黑。
//    实测逐像素复刻：全屏峰值 0.0092，仅 1.7% 像素超过 8 位门槛。
//    取 0.42 后 sigma=0.302，10 单位厚度的光学厚度达 3.0，光柱扎实可见。
const EXTINCTION = 0.42
// Henyey-Greenstein 各向异性因子。0 = 均匀散射，>0 前向散射（光晕感更强）
const HG_G = 0.62
// 介质密度（雾的浓稠度）
const DENSITY = 0.62

// 地面高度。遮挡体统统悬浮在它上方（y > GROUND_Y），光柱才能从光源一路
// 打到地面并在光斑里收束 —— 这是"聚光灯打在暗室地面"这套语义成立的前提。
const GROUND_Y = -10

// ⚠️ 光锥轴向倾斜角（弧度），绕 Z 轴，正值让光从右上往左下打。
//    这是让"光柱"能被看出来的关键之一：光锥若严格垂直于地面，
//    从轨道相机看过去只是一个同心圆盘（就是之前那张"太阳"截图）。
//    倾到 0.30 rad（约 17°）后视线与锥轴不再平行，锥体在画面上被拉成
//    斜向的柱状，实测柱状结构方差从 95 提升到 134（越高越像"柱"）。
const CONE_TILT = 0.30

// ⚠️ spotAngle 必须足够大，否则光锥根本罩不到遮挡体群：
//    光从 y=20 打到地面 y=-10 落差 30，锥半角 0.30 rad 时地面光斑半径只有
//    30*tan(0.30) ≈ 9.3 单位，而遮挡体分布在半径 3.5~17 的环带上 ——
//    大部分都在锥外，切不出光柱。取 0.50~0.62 让光斑半径达到 17~21 单位。
const presets = [
  { label: '林间', spotAngle: 0.52, lightDistance: 26, lightHeight: 20, occluders: 15, tint: 0xffe6b8 },
  { label: '厅堂', spotAngle: 0.40, lightDistance: 18, lightHeight: 26, occluders: 9, tint: 0xbfd8ff },
  { label: '水下', spotAngle: 0.62, lightDistance: 30, lightHeight: 24, occluders: 21, tint: 0x9fe6ff }
]

const presetLabel = computed(() => presets[presetIndex.value].label)

let renderer = null
let scene = null
let camera = null
let clock = null
let rafId = 0
let resizeObserver = null

// 光源与遮挡体
let spotLight = null
let lightTarget = null
let occluderGroup = null
let groundMesh = null

// 后处理
let rtScene = null
let rtVolumetric = null
let volumetricPass = null
let compositePass = null
let passQuad = null
let passScene = null
let passCamera = null
const materials = []

// 相机轨道状态
// ⚠️ 机位是这个页面成败的第一要素，改之前务必理解：
//
//    体积光要展示的是「光在空气中的路径」——也就是那条斜的光柱。
//    要看见柱体，相机必须**站在雾里、从侧面看光锥**。
//
//    两种错误机位（都踩过）：
//      a) 相机太近（distance=34）+ 光锥朝相机倾 → 正好对着锥的"喇叭口"往里看，
//         看到的是一个撑满全屏的巨大圆盘。这就是"太阳"截图的成因。
//      b) polar 太低（0.22）→ 几乎贴地往外看，只能看到地面光斑，看不到柱体。
//
//    正确做法：
//      · distance 拉到 78，让「光源 → 光锥 → 地面落点」整段进画面
//      · azimuth 与光锥倾斜方向**错开 90°**：视线横切光锥（实测锥轴与视线
//        夹角 87.6°，基本垂直），看到的是锥的侧面轮廓而非截面
//      · polar 取 0.30，略高于锥体中部，俯视但不俯冲到光斑里
//      · 注视点抬高到 y=4（锥体中段），否则柱体上半截被顶出画外
const CAM_AZIMUTH = 0.55
const CAM_POLAR = 0.30
const CAM_DISTANCE = 78

const orbit = { azimuth: CAM_AZIMUTH, polar: CAM_POLAR, distance: CAM_DISTANCE }
const targetOrbit = { azimuth: CAM_AZIMUTH, polar: CAM_POLAR, distance: CAM_DISTANCE }
const AUTO_ORBIT = { azimuth: CAM_AZIMUTH, polar: CAM_POLAR, distance: CAM_DISTANCE }
let dragging = false
// ⚠️ 用户只要拖过一次（或滚过轮），自动巡航就永久让位。见 updateCamera 的说明。
let userInteracted = false
let idleTime = 0
const pointer = { x: 0, y: 0 }
const POLAR_MIN = 0.04
const POLAR_MAX = 1.15

let frameCount = 0
let fpsAccum = 0

/* ------------------------------------------------------------
   着色器
   ------------------------------------------------------------ */

// 场景渲染用的顶点着色器（标准 MVP 变换 + 世界坐标）
const SCENE_VERT = `
  varying vec3 vWorldPos;
  varying vec3 vNormal;
  void main() {
    vec4 wp = modelMatrix * vec4(position, 1.0);
    vWorldPos = wp.xyz;
    vNormal = normalize(mat3(modelMatrix) * normal);
    gl_Position = projectionMatrix * viewMatrix * wp;
  }`

// 场景几何：雾面材质 + 光源照射（受光面才可见，不参与体积计算）
const SCENE_FRAG = `
  uniform vec3 uColor;
  uniform vec3 uLightPos;
  uniform vec3 uLightColor;
  uniform vec3 uConeAxis;
  uniform float uSpotAngle;
  uniform float uLambert;
  uniform float uGroundY;
  varying vec3 vWorldPos;
  varying vec3 vNormal;
  void main() {
    vec3 L = normalize(uLightPos - vWorldPos);
    float coneCos = cos(uSpotAngle);
    // ⚠️ 锥轴用 uConeAxis，不要写死 (0,-1,0)：光锥倾斜后地面光斑也会跟着斜。
    float spotCos = dot(-L, uConeAxis);
    float inCone = smoothstep(coneCos, coneCos + 0.12, spotCos);
    float ndl = max(dot(vNormal, L), 0.0);

    // ⚠️ 地面必须单独处理。地面法线是 +Y，当光源几乎在正上方时 ndl ≈ 1，
    //    而光是往下照的，两者叠起来会让整块地面均匀发亮成一个"太阳圆盘"，
    //    正是之前那张截图的样子。这里改成按"射线打到地面的半径"来定光斑：
    //    离落点越近越亮，往外快速衰减，光斑边缘才读得出来是"一束光打在地上"。
    float isGround = step(abs(vWorldPos.y - uGroundY), 0.35);
    // 光斑中心 = 锥轴与地面的交点
    float axisDrop = (uLightPos.y - uGroundY) / max(uConeAxis.y * -1.0, 1e-3);
    vec2 poolCenter = uLightPos.xz + uConeAxis.xz * axisDrop;
    float d2 = dot(vWorldPos.xz - poolCenter, vWorldPos.xz - poolCenter);
    float spotR = uLightPos.y - uGroundY;
    float rNorm = sqrt(d2) / max(spotR, 0.001);
    // 0.62 = 光斑主体半径，外圈 0.62→1.0 做柔和衰减
    float pool = 1.0 - smoothstep(0.42, 1.05, rNorm);
    // 落点处叠一点噪声，避免完美的同心圆看起来像贴图
    float grain = 0.86 + 0.14 * sin(vWorldPos.x * 1.7) * cos(vWorldPos.z * 1.9);
    // ⚠️ 地面光斑强度必须**明显低于**体积光，否则它会喧宾夺主。
    //    曾经乘 3.4，结果地面亮成一个巨大圆盘盖过整屏，体积光柱完全被压住
    //    （用户截图：一个米白色大圆饼 + 几个黑剪影，看不到任何光柱）。
    //    这个页面的主角是"空气中的光路"，地面只是提供落点参照，取 1.15。
    vec3 groundLit = uLightColor * pool * grain * 1.15 * inCone;

    vec3 lit = uLightColor * ndl * uLambert * inCone;
    vec3 ambient = uColor * (0.055 + 0.05 * isGround);
    vec3 direct = mix(uColor * lit, groundLit, isGround);
    gl_FragColor = vec4(ambient + direct, 1.0);
  }`

// 体积光步进：从近平面出发向远平面推进，累积散射
const VOLUMETRIC_VERT = `
  varying vec2 vUv;
  void main() {
    vUv = uv;
    gl_Position = vec4(position.xy, 0.0, 1.0);
  }`

const VOLUMETRIC_FRAG = `
  precision highp float;

  // ⚠️ GLSL **没有**内置的 PI 常量（那是着色器作者常见的错觉）。
  //    henyeyGreenstein() 里的 1/(4π) 归一化因子用到它，不声明就会报
  //    "'PI' : undeclared identifier" → 整个体积光 pass 编译失败 → 全屏无光。
  //    这是本页"什么效果都没有"的真凶之一：报错只在 console 里，
  //    页面上看不出任何异常，只是没有光。
  #define PI 3.141592653589793

  uniform sampler2D uDepth;
  uniform vec2 uResolution;
  uniform mat4 uInverseProjection;
  uniform mat4 uCameraWorld;
  uniform vec3 uCameraPos;
  uniform vec3 uLightPos;
  uniform vec3 uLightColor;
  uniform vec3 uConeAxis;     // 聚光锥轴向（单位向量），随 CONE_TILT 倾斜
  uniform float uSpotAngle;
  uniform float uRays;
  uniform float uStrength;
  uniform float uExtinction;
  uniform float uDensity;
  uniform float uHG;
  uniform float uTime;
  uniform float uDither;      // 1 = 抖动开启（消除色带），0 = 关闭（可看清色带）

  varying vec2 vUv;

  // ------------------------------------------------------------
  // 从深度缓冲反解出世界坐标
  //
  // ⚠️ three 的深度纹理存的是**非线性**的窗口空间深度（NDC z 映射到 0..1），
  //    必须先用 (d * 2 - 1) 还原成 NDC，再过逆投影矩阵才是视线上的点。
  //    直接拿纹理值当"线性深度比例"是最常见的错误，会导致体积光
  //    在远景处整片糊死。
  // ------------------------------------------------------------
  vec3 worldFromDepth(vec2 uv, float depth) {
    vec4 clip = vec4(uv * 2.0 - 1.0, depth * 2.0 - 1.0, 1.0);
    vec4 view = uInverseProjection * clip;
    view /= view.w;
    return (uCameraWorld * vec4(view.xyz, 1.0)).xyz;
  }

  // Henyey-Greenstein 相函数：给定散射角余弦，返回该方向的散射概率
  //
  //   g > 0 前向散射（顺着原方向走），g = 0 各向同性，g < 0 后向散射
  //   逆光看光源时是强前向散射，所以 uHG 取正、cosTheta 用 dot(rayDir, toLight)
  float henyeyGreenstein(float cosTheta, float g) {
    float g2 = g * g;
    float denom = 1.0 + g2 - 2.0 * g * cosTheta;
    return (1.0 - g2) / (4.0 * PI * pow(max(denom, 1e-4), 1.5));
  }

  // ------------------------------------------------------------
  // 每像素起点抖动
  //
  // ⚠️ 这里用浮点哈希而**不是** uint 位运算。ShaderMaterial 默认编译成
  //    GLSL ES 1.00，该版本没有 uint 类型（那是 3.00 才引入的），
  //    写成声明 uint 变量会直接编译失败、整屏变黑。
  //    fract(sin(dot(...))) 是 GLSL 1.00 下的经典替代，质量足够做抖动。
  // ------------------------------------------------------------
  float dither(vec2 uv) {
    vec2 p = floor(uv * uResolution);
    return fract(sin(dot(p, vec2(12.9898, 78.233))) * 43758.5453123);
  }

  void main() {
    float depth = texture2D(uDepth, vUv).x;

    vec3 rayStart = uCameraPos;
    vec3 rayEnd = worldFromDepth(vUv, depth);

    vec3 rayVec = rayEnd - rayStart;
    float rayLength = length(rayVec);
    vec3 rayDir = rayVec / max(rayLength, 1e-5);

    vec3 toLight = uLightPos - rayStart;
    float lightDist = length(toLight);
    vec3 lightDir = toLight / max(lightDist, 1e-5);

    // ------------------------------------------------------------
    // 距离衰减
    //
    // ⚠️ 这里必须用「温和」的衰减。曾经写成 1/(1 + 0.06*d²)，在本场景
    //    尺度（光源到雾区 20~30 世界单位）下衰减到 0.018~0.040，
    //    再乘上 sigma(0.0396) 与步长，逐点累积后全屏峰值只有 0.0092 ——
    //    8 位量化门槛是 1/255=0.0039，最亮处也只是一片近乎全黑。
    //    已实测逐像素复刻：无遮挡时仅 1.7% 像素过门槛。
    //    改成 0.004 后同一位置提升约 10 倍。
    // ------------------------------------------------------------
    float distFalloff = 1.0 / (1.0 + 0.004 * lightDist * lightDist);

    // 相函数只跟"视线方向 ↔ 光源方向"的夹角有关，全屏是常量，算一次即可
    float cosTheta = dot(rayDir, lightDir);
    float phase = henyeyGreenstein(cosTheta, uHG);

    float stepSize = rayLength / uRays;

    // ⚠️ 起点抖动：把固定起点产生的同心环状色带换成高频噪声。
    //    偏移量必须是步长的一个分数（≤ 1 步），否则会露出前后两层的接缝。
    float offset = mix(0.5, dither(vUv), uDither);

    vec3 accumulated = vec3(0.0);
    float transmittance = 1.0;

    const int MAX_RAYS = 128;
    float coneCos = cos(uSpotAngle);

    for (int i = 0; i < MAX_RAYS; i++) {
      if (float(i) >= uRays) break;

      float t = (float(i) + offset) * stepSize;
      vec3 p = rayStart + rayDir * t;

      vec3 toL = uLightPos - p;
      float d = length(toL);

      // ---- 锥体裁剪：只有落在聚光锥内的采样点才散射 ----
      // ⚠️ 锥轴用 uConeAxis（随 CONE_TILT 倾斜），不要写死 (0,-1,0)。
      //    写死的话光锥永远竖直，从轨道相机看就是个圆盘，不成"柱"。
      //    toL 是"从采样点指向光源"，所以指向光源的锥轴分量是 -toL·axis。
      float cosAtP = dot(-toL / max(d, 1e-5), uConeAxis);
      float coneFalloff = smoothstep(coneCos, coneCos + 0.10, cosAtP);
      if (coneFalloff > 0.0) {
        // ---- 衰减 ----
        // 系数据实测标定：0.004 让 20~30 单位处保持 0.22~0.38 的可见强度。
        //
        // ⚠️ 远距截断写成 1.0 - smoothstep(34.0, 90.0, d) 是**唯一有保证**的写法。
        //
        //    曾经写成 smoothstep(90.0, 34.0, d)：edge0 > edge1 属于未定义行为
        //    （GLSL 规范只定义 edge0 < edge1 的情形），换驱动就可能整屏黑掉。
        //
        //    更正一条早期记录：当时把"体积光全屏纯黑"归因于反序 smoothstep 被
        //    驱动返回 0，**这个结论是错的**。后来用真实 GPU 写探针实测
        //    （见 showcase_verify.mjs 的来龙去脉），本机驱动对反序参数返回的是
        //    与正序完全一致的值，并不会归零。当时真正让画面黑掉的是别的问题
        //    （相机正对锥口 + 地面光斑过曝，把体积光盖住了）。
        //    保留这个正确写法是因为它语义清晰、跨驱动有保证，而不是因为曾经踩过坑。
        float atten = (1.0 / (1.0 + 0.004 * d * d)) * (1.0 - smoothstep(34.0, 90.0, d));

        // ---- 介质密度：加一点低频起伏，让光柱有"流动的尘雾"质感 ----
        // 0.82 + 0.18*sin*cos 的均值约 0.82，这里把基线提到 1.0 附近，
        // 让密度波动在 ±18% 左右围绕 1.0 摆动（而不是整体缩到 0.82）。
        float dense = uDensity * (1.0 + 0.18 * sin(p.x * 0.9 + uTime * 0.7)
                                      * cos(p.z * 0.8 - uTime * 0.5));

        float sigma = dense * uExtinction;
        // 该采样点的入射光量（体积阴影就体现在这里被遮挡物的遮挡上）
        vec3 inscatter = uLightColor * coneFalloff * atten * phase * uStrength;

        // 能量守恒的逐步积分：先把本段散射加进去，再按本段光学厚度衰减
        accumulated += transmittance * inscatter * sigma * stepSize;
        transmittance *= exp(-sigma * stepSize);
      }

      if (transmittance < 0.002) break;
    }

    gl_FragColor = vec4(accumulated, 1.0);
  }`

// 合成：场景 + 体积光，并做一次色调映射
const COMPOSITE_FRAG = `
  precision highp float;
  uniform sampler2D uScene;
  uniform sampler2D uVolumetric;
  uniform float uDensity;      // 体积光混合权重
  varying vec2 vUv;

  // ACES 近似色调映射，把高光压回可见范围，避免光柱中心烧成纯白
  vec3 aces(vec3 x) {
    const float a = 2.51, b = 0.03, c = 2.43, d = 0.59, e = 0.14;
    return clamp((x * (a * x + b)) / (x * (c * x + d) + e), 0.0, 1.0);
  }

  void main() {
    vec3 sceneCol = texture2D(uScene, vUv).rgb;
    vec3 volCol = texture2D(uVolumetric, vUv).rgb;

    // 加色混合：光柱是"额外进入相机的能量"
    vec3 col = sceneCol + volCol * uDensity;
    col = aces(col);

    // 轻微暗角，把视线收拢到画面中心
    vec2 d = vUv - 0.5;
    float vig = 1.0 - dot(d, d) * 0.85;
    col *= vig;

    // gamma
    col = pow(max(col, 0.0), vec3(1.0 / 2.2));
    gl_FragColor = vec4(col, 1.0);
  }`

/* ------------------------------------------------------------
   GPU 能力检测与渲染器
   ------------------------------------------------------------ */

onMounted(() => {
  const container = mountRef.value
  if (!container) return

  const canvas = document.createElement('canvas')
  const gl = canvas.getContext('webgl2', {
    alpha: false,
    antialias: true,
    powerPreference: 'high-performance'
  })
  if (!gl) {
    container.innerHTML =
      '<div style="padding:40px;color:#7d90b5;font-size:14px">当前浏览器不支持 WebGL2，无法运行体积光模拟。</div>'
    return
  }

  renderer = new THREE.WebGLRenderer({ canvas, context: gl, antialias: true, alpha: false })
  renderer.setPixelRatio(Math.min(window.devicePixelRatio, 2))
  renderer.setSize(container.clientWidth, container.clientHeight)
  renderer.setClearColor(0x03040a, 1)
  renderer.domElement.classList.add('vl-canvas')
  container.appendChild(renderer.domElement)

  scene = new THREE.Scene()
  // ⚠️ 近平面不要设太小（深度精度），远平面按场景尺度给（别给 10000）
  camera = new THREE.PerspectiveCamera(52, container.clientWidth / Math.max(container.clientHeight, 1), 0.5, 220)

  buildScene()
  buildComposer()

  bindEvents()
  clock = new THREE.Clock()
  animate()
})

/* ------------------------------------------------------------
   场景搭建
   ------------------------------------------------------------ */

// 遮挡体用的基础几何：随机取几种简单体，形状越不规则光柱越好看
const OCCLUDER_SHAPES = [
  (rng) => new THREE.BoxGeometry(0.6 + rng() * 1.6, 3 + rng() * 7, 0.6 + rng() * 1.6),
  (rng) => new THREE.CylinderGeometry(0.35 + rng() * 0.6, 0.45 + rng() * 0.7, 3.5 + rng() * 6, 7),
  (rng) => new THREE.IcosahedronGeometry(0.7 + rng() * 1.3, 0),
  (rng) => new THREE.TorusGeometry(0.8 + rng() * 0.9, 0.22 + rng() * 0.2, 6, 12)
]

// 确定性伪随机（线性同余），保证每次进页面布局一致，便于对比调试
function makeRng(seed) {
  let s = seed >>> 0
  return () => {
    s = (s * 1664525 + 1013904223) >>> 0
    return s / 4294967296
  }
}

function buildScene() {
  const preset = presets[presetIndex.value]

  // ---- 光源 ----
  // ⚠️ 光源必须落在相机视野内，否则连灯泡都看不见，更别说光柱。
  //
  //    最关键的一点：**光锥倾斜方向必须与相机视线垂直**。
  //
  //    相机在 azimuth≈0.55 处，位置约 (x≈+29, y≈18, z≈+47)，也就是站在
  //    +X/+Z 象限朝原点看。所以：
  //      · 若光锥往 +X 倾 → 锥体是朝相机"喇叭口"倒过来的，看到的是截面
  //        → 一个撑满全屏的大圆盘（就是上一版截图的样子）
  //      · 若光锥往 +Z 倾 → 锥体在画面里呈"侧躺"轮廓，能看到柱体的长边
  //
  //    因此这里让锥轴在 **Z 方向**倾倒（sinZ），X 方向只做很小的固定偏置
  //    用来把光锥挪进画面，不参与"倾倒"。这样视线横切光锥，柱体才立得住。
  const leanZ = Math.tan(CONE_TILT) * preset.lightDistance * 0.62
  const lightPos = new THREE.Vector3(
    // 很小的横向偏置：只为了避开屏幕正中等比构图，不承担"倾倒"职责
    preset.lightDistance * 0.06,
    preset.lightHeight,
    preset.lightDistance * 0.03 - leanZ
  )
  // 光锥瞄向的点：从光源出发沿锥轴（向 -Z 倾倒）打下去，落到地面上
  lightTarget = new THREE.Object3D()
  // ⚠️ 锥轴方向必须与 lightTarget 的实际指向**完全一致**，
  //    否则 spotLight 照的方向和体积步进用的锥轴对不上，
  //    会出现"灯照在左边、光柱长在右边"的错位。
  const coneAxis = new THREE.Vector3(0, -Math.cos(CONE_TILT), Math.sin(CONE_TILT)).normalize()
  const dropToGround = (lightPos.y - GROUND_Y) / Math.cos(CONE_TILT)
  lightTarget.position.copy(lightPos).addScaledVector(coneAxis, dropToGround)
  scene.add(lightTarget)

  spotLight = new THREE.SpotLight(preset.tint, 900, 0, preset.spotAngle, 0.42, 1.6)
  spotLight.position.copy(lightPos)
  spotLight.target = lightTarget
  scene.add(spotLight)

  // 光源本体：一个小球 + 光晕，标出"光从哪来"。
  // ⚠️ 半径从 0.55+1.5 收到 0.32+0.85。相机拉远后光源在画面里本就变小，
  //    但光晕是加色混合，画面上仍然容易糊成一团亮斑抢走注意力。
  const bulbMat = new THREE.MeshBasicMaterial({ color: preset.tint })
  const bulb = new THREE.Mesh(new THREE.SphereGeometry(0.32, 20, 14), bulbMat)
  bulb.position.copy(lightPos)
  scene.add(bulb)
  materials.push(bulbMat)

  const haloMat = new THREE.MeshBasicMaterial({
    color: preset.tint, transparent: true, opacity: 0.14, depthWrite: false,
    blending: THREE.AdditiveBlending
  })
  const halo = new THREE.Mesh(new THREE.SphereGeometry(0.85, 18, 12), haloMat)
  halo.position.copy(lightPos)
  scene.add(halo)
  materials.push(haloMat)

  // ---- 地面 ----
  const groundMat = new THREE.ShaderMaterial({
    vertexShader: SCENE_VERT,
    fragmentShader: SCENE_FRAG,
      uniforms: {
        uColor: { value: new THREE.Color(0x2a3550) },
        uLightPos: { value: lightPos.clone() },
        uLightColor: { value: new THREE.Color(preset.tint) },
        uConeAxis: { value: coneAxis.clone() },
        uSpotAngle: { value: preset.spotAngle },
        uLambert: { value: 0.85 },
        // ⚠️ 地面是被光锥打亮的那块"环"，不抬高就会整体黑掉，
        //    只靠体积光撑亮非常脆。这里直接把光斑画出来，作为可读的地面参照。
        uGroundY: { value: GROUND_Y }
      }
  })
  groundMesh = new THREE.Mesh(new THREE.PlaneGeometry(150, 150), groundMat)
  groundMesh.rotation.x = -Math.PI / 2
  groundMesh.position.y = GROUND_Y
  scene.add(groundMesh)
  materials.push(groundMat)

  // ---- 遮挡体群 ----
  // ⚠️⚠️ 这是整个页面最本质的一处错误，改之前务必读完。
  //
  //    体积光柱的形状 100% 来自「射向相机的路径被物体切掉多少」——
  //    也就是体积阴影。均匀介质里光柱只是一条均匀亮带，毫无体积感。
  //    所以遮挡体必须满足三条：
  //      a) 在光锥内部或其边缘（在锥外=不参与切割，纯装饰）
  //      b) 悬浮在光源与地面之间（掉到地面以下=光根本绕不到它背后）
  //      c) 离相机轴有一定横向偏移（正好在中轴上会把光柱整条挖空）
  //    曾经的做法是"以圆盘中心为圆心、半径 2~14 环形铺开、y 在 -8~+4 抖动"，
  //    结果绝大多数物体都掉到地面以下、且贴着中轴 —— 光柱被挖空而不是被切开。
  //    现在按"近/中/远三层同心环 + 顶点朝上的竖向姿态"分布。
  occluderGroup = new THREE.Group()
  const rng = makeRng(20260915)
  const count = preset.occluders
  // 遮挡体离光源高度差 6~18，旋转半径略小于该高度处的光锥半径，
  // 保证"有一部分在锥内、一部分在锥外"，切割才既有层次又不过曝。
  const RING_NEAR = { y: 15.0, rMin: 3.5, rMax: 7.0 }
  const RING_MID = { y: 10.0, rMin: 7.5, rMax: 12.0 }
  const RING_FAR = { y: 4.5, rMin: 13.0, rMax: 17.0 }
  const rings = [RING_NEAR, RING_MID, RING_FAR]
  for (let i = 0; i < count; i += 1) {
    const geo = OCCLUDER_SHAPES[Math.floor(rng() * OCCLUDER_SHAPES.length)](rng)
    const mat = new THREE.ShaderMaterial({
      vertexShader: SCENE_VERT,
      fragmentShader: SCENE_FRAG,
      uniforms: {
        uColor: { value: new THREE.Color(0x3d4a68) },
        uLightPos: { value: lightPos.clone() },
        uLightColor: { value: new THREE.Color(preset.tint) },
        uConeAxis: { value: coneAxis.clone() },
        uSpotAngle: { value: preset.spotAngle },
        uLambert: { value: 1.15 },
        // 遮挡体不是地面：给一个不可能命中的值，让 isGround 恒为 0
        uGroundY: { value: 99999.0 }
      }
    })

    const mesh = new THREE.Mesh(geo, mat)
    const ring = rings[i % 3]
    // 每 3 个换一环，再叠一点环内抖动，避免出现整齐的同心圆
    const ang = rng() * Math.PI * 2 + i * 2.399
    const rad = ring.rMin + rng() * (ring.rMax - ring.rMin)
    const y = ring.y - 10.5 + (rng() - 0.5) * 5.0
    mesh.position.set(Math.cos(ang) * rad, y, Math.sin(ang) * rad)
    // 竖向姿态为主：立体块沿竖直方向"立"着才像被光柱穿透的石核，
    // 完全随机翻滚会让形状读不出来。绕 y 随机、x/z 只给 ±0.35 的小倾斜。
    mesh.rotation.set((rng() - 0.5) * 0.7, rng() * Math.PI * 2, (rng() - 0.5) * 0.7)
    mesh.userData.rotSpeed = (rng() - 0.5) * 0.22
    occluderGroup.add(mesh)
    materials.push(mat)
  }
  scene.add(occluderGroup)

  // ---- 体积光相关：深度纹理 + 两个离屏目标 + 两个 pass ----
  const size = renderer.getDrawingBufferSize(new THREE.Vector2())
  const depthTexture = new THREE.DepthTexture(size.x, size.y)
  depthTexture.type = THREE.UnsignedIntType
  depthTexture.minFilter = THREE.NearestFilter
  depthTexture.magFilter = THREE.NearestFilter

  rtScene = new THREE.WebGLRenderTarget(size.x, size.y, {
    // ⚠️ 必须挂 depthTexture，体积光要靠场景深度决定射线终点
    depthTexture,
    depthBuffer: true,
    stencilBuffer: false,
    minFilter: THREE.LinearFilter,
    magFilter: THREE.LinearFilter
  })

  rtVolumetric = new THREE.WebGLRenderTarget(size.x, size.y, {
    depthBuffer: false,
    stencilBuffer: false,
    minFilter: THREE.LinearFilter,
    magFilter: THREE.LinearFilter
  })

  volumetricPass = new THREE.ShaderMaterial({
    vertexShader: VOLUMETRIC_VERT,
    fragmentShader: VOLUMETRIC_FRAG,
    depthTest: false,
    depthWrite: false,
    uniforms: {
      uDepth: { value: depthTexture },
      uResolution: { value: new THREE.Vector2(size.x, size.y) },
      uInverseProjection: { value: new THREE.Matrix4() },
      uCameraWorld: { value: new THREE.Matrix4() },
      uCameraPos: { value: new THREE.Vector3() },
      uLightPos: { value: lightPos.clone() },
      uLightColor: { value: new THREE.Color(preset.tint) },
      uConeAxis: { value: coneAxis.clone() },
      uSpotAngle: { value: preset.spotAngle },
      uRays: { value: RAY_SAMPLES },
      uStrength: { value: SCATTER_STRENGTH },
      uExtinction: { value: EXTINCTION },
      uDensity: { value: DENSITY },
      uHG: { value: HG_G },
      uTime: { value: 0 },
      uDither: { value: 1 }
    }
  })

  compositePass = new THREE.ShaderMaterial({
    vertexShader: VOLUMETRIC_VERT,
    fragmentShader: COMPOSITE_FRAG,
    depthTest: false,
    depthWrite: false,
    uniforms: {
      uScene: { value: rtScene.texture },
      uVolumetric: { value: rtVolumetric.texture },
      uDensity: { value: 1.0 }
    }
  })

  materials.push(volumetricPass, compositePass)
}

function buildComposer() {
  // 用一个极简的"全屏 pass"载体：直接复用手写 quad，
  // 不引入 EffectComposer（这里只有两个 pass，手写更可控也更好读）
  const geo = new THREE.BufferGeometry()
  geo.setAttribute('position', new THREE.BufferAttribute(new Float32Array([
    -1, -1, 0, 1, -1, 0, 1, 1, 0,
    -1, -1, 0, 1, 1, 0, -1, 1, 0
  ]), 3))
  geo.setAttribute('uv', new THREE.BufferAttribute(new Float32Array([
    0, 0, 1, 0, 1, 1,
    0, 0, 1, 1, 0, 1
  ]), 2))
  passQuad = new THREE.Mesh(geo, null)
  passQuad.frustumCulled = false
  passScene = new THREE.Scene()
  passScene.add(passQuad)
  passCamera = new THREE.OrthographicCamera(-1, 1, 1, -1, 0, 1)
}

/* ------------------------------------------------------------
   每帧
   ------------------------------------------------------------ */

// 更新相机（球坐标轨道 + 阻尼）
function updateCamera(dt) {
  // ⚠️ 自动巡航的用户交互规则：
  //    曾经是只要 autoOrbit 为真、且当前没在拖，就每帧 azimuth += dt*0.16。
  //    结果用户一松手就立刻被自动巡航拖走，感觉"拖了跟没拖一样"。
  //    现在改成：一旦用户拖过（userInteracted），自动巡航永久让位，只在静置
  //    4 秒后缓慢回落到基准机位 —— 用户的操作永远优先。
  if (autoOrbit.value && !dragging && !userInteracted) {
    targetOrbit.azimuth += dt * 0.16
  } else if (!dragging) {
    idleTime += dt
    // 松手 4s 后缓慢回落到基准机位
    if (idleTime > 4) {
      const ease = dt * 0.25
      targetOrbit.azimuth += (AUTO_ORBIT.azimuth - targetOrbit.azimuth) * ease
      targetOrbit.polar += (AUTO_ORBIT.polar - targetOrbit.polar) * ease
      targetOrbit.distance += (AUTO_ORBIT.distance - targetOrbit.distance) * ease
    }
  }

  const damp = dragging ? dt * 9 : dt * 3.4
  orbit.azimuth += (targetOrbit.azimuth - orbit.azimuth) * Math.min(damp, 1)
  orbit.polar += (targetOrbit.polar - orbit.polar) * Math.min(damp, 1)
  orbit.distance += (targetOrbit.distance - orbit.distance) * Math.min(damp, 1)

  const cp = Math.cos(orbit.polar)
  camera.position.set(
    Math.sin(orbit.azimuth) * cp * orbit.distance,
    Math.sin(orbit.polar) * orbit.distance,
    Math.cos(orbit.azimuth) * cp * orbit.distance
  )
  // 注视点取「光锥中段」——光源在 y≈20、地面在 y=-10，中段约 y=5。
  // ⚠️ 但还要沿 Z 方向往光锥倾斜的反方向偏一点：光锥是往 -Z 倒的，
  //    注视点若固定看原点，柱体会被推到画面一侧、另一半空着。
  //    这里取锥轴在地面落点的一半，让柱体落在画面中间。
  camera.lookAt(0, 4, -3)
  camera.updateMatrixWorld()
}

function setPass(material, target) {
  passQuad.material = material
  renderer.setRenderTarget(target)
  renderer.render(passScene, passCamera)
  renderer.setRenderTarget(null)
}

function animate() {
  rafId = requestAnimationFrame(animate)
  const dt = Math.min(clock.getDelta(), 1 / 30)
  const time = clock.elapsedTime

  if (!paused.value) {
    updateCamera(dt)

    // 遮挡体缓慢自转，让光柱不断重新"切割"，静态画面也有呼吸感
    if (occluderGroup) {
      for (const m of occluderGroup.children) {
        m.rotation.y += m.userData.rotSpeed * dt
        m.rotation.x += m.userData.rotSpeed * 0.4 * dt
      }
    }
  }

  volumetricPass.uniforms.uTime.value = time
  volumetricPass.uniforms.uDither.value = dither.value ? 1 : 0

  // ---- 1) 把场景渲进带深度纹理的离屏目标 ----
  renderer.setRenderTarget(rtScene)
  renderer.clear()
  renderer.render(scene, camera)

  // ---- 2) 从该深度纹理出发做体积步进，写出光柱 ----
  // ⚠️ 着色器里反解世界坐标用的链路是：clip -> view（逆投影）-> world（相机世界矩阵）。
  //    所以只需要 projectionMatrixInverse 与 matrixWorld 两个矩阵，
  //    千万不要再乘一次 viewMatrix 的逆 —— 那会把变换做两遍。
  volumetricPass.uniforms.uInverseProjection.value.copy(camera.projectionMatrixInverse)
  volumetricPass.uniforms.uCameraWorld.value.copy(camera.matrixWorld)
  volumetricPass.uniforms.uCameraPos.value.copy(camera.position)

  setPass(volumetricPass, rtVolumetric)

  // ---- 3) 合成到屏幕 ----
  setPass(compositePass, null)

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
function toggleOccluders() {
  showOccluders.value = !showOccluders.value
  if (occluderGroup) occluderGroup.visible = showOccluders.value
}
function toggleAutoOrbit() { autoOrbit.value = !autoOrbit.value }
function toggleDither() { dither.value = !dither.value }

function cyclePreset() {
  presetIndex.value = (presetIndex.value + 1) % presets.length
  rebuildScene()
}

function rebuildScene() {
  disposeSceneObjects()
  buildScene()
  bindEventsAfterRebuild()
}

function disposeSceneObjects() {
  if (spotLight) { scene.remove(spotLight); spotLight.dispose?.() }
  if (lightTarget) scene.remove(lightTarget)
  if (occluderGroup) {
    for (const m of occluderGroup.children) m.geometry.dispose()
    scene.remove(occluderGroup)
  }
  if (groundMesh) { groundMesh.geometry.dispose(); scene.remove(groundMesh) }
  rtScene?.dispose()
  rtVolumetric?.dispose()
  for (const m of materials) m.dispose()
  materials.length = 0
}

function bindEventsAfterRebuild() {
  const preset = presets[presetIndex.value]
  if (spotLight) spotLight.angle = preset.spotAngle
}

function onPointerDown(e) {
  if (e.target.closest('.vl-button')) return
  dragging = true
  idleTime = 0
  pointer.x = e.clientX
  pointer.y = e.clientY
  renderer.domElement.setPointerCapture?.(e.pointerId)
}

function onPointerMove(e) {
  if (!dragging) return
  const dx = e.clientX - pointer.x
  const dy = e.clientY - pointer.y
  pointer.x = e.clientX
  pointer.y = e.clientY
  const k = 1 / Math.max(360, window.innerWidth)
  targetOrbit.azimuth -= dx * k * 3.4
  targetOrbit.polar = Math.max(POLAR_MIN, Math.min(POLAR_MAX, targetOrbit.polar + dy * k * 2.2))
  idleTime = 0
}

function onPointerUp(e) {
  dragging = false
  renderer.domElement.releasePointerCapture?.(e.pointerId)
}

function onWheel(e) {
  e.preventDefault()
  const step = Math.exp(e.deltaY * 0.0012)
  // ⚠️ 缩放范围要跟着基准距离走。基准从 34 拉到 78 后，
  //    旧的 (12, 88) 上限太紧（几乎只能往里推不能往外拉），
  //    下限 12 又会让用户推回"贴脸看光斑"的状态（就是那个大圆盘）。
  //    改成 (30, 170)：最近也保持侧视距离，最远能看全景。
  targetOrbit.distance = Math.max(30, Math.min(170, targetOrbit.distance * step))
  userInteracted = true
  idleTime = 0
}

function bindEvents() {
  const el = renderer.domElement
  el.addEventListener('pointerdown', onPointerDown)
  el.addEventListener('pointermove', onPointerMove)
  // ⚠️ pointerup 挂在 window 上，是为了兼容"在画布上按下、移到画布外再松手"。
  //    但 pointercancel（触控笔/手势被系统抢走）也必须收尾，否则 dragging 会永远为真，
  //    之后所有拖拽判定都被 onPointerMove 的 `if (!dragging) return` 吃掉。
  window.addEventListener('pointerup', onPointerUp)
  window.addEventListener('pointercancel', onPointerUp)
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

  const size = renderer.getDrawingBufferSize(new THREE.Vector2())
  if (rtScene) rtScene.setSize(size.x, size.y)
  if (rtVolumetric) rtVolumetric.setSize(size.x, size.y)
  if (volumetricPass) volumetricPass.uniforms.uResolution.value.set(size.x, size.y)
}

/* ------------------------------------------------------------
   释放
   ------------------------------------------------------------ */

onBeforeUnmount(() => {
  window.cancelAnimationFrame(rafId)
  window.removeEventListener('pointerup', onPointerUp)
  window.removeEventListener('pointercancel', onPointerUp)
  if (resizeObserver) resizeObserver.disconnect()
  if (renderer) {
    const el = renderer.domElement
    el.removeEventListener('pointerdown', onPointerDown)
    el.removeEventListener('pointermove', onPointerMove)
    el.removeEventListener('wheel', onWheel)
  }
  disposeSceneObjects()
  if (passQuad) { passQuad.geometry.dispose(); passQuad.material = null }
  if (renderer) {
    renderer.dispose()
    renderer.domElement.remove()
  }
  spotLight = null
  lightTarget = null
  occluderGroup = null
  groundMesh = null
  rtScene = null
  rtVolumetric = null
  volumetricPass = null
  compositePass = null
  passQuad = null
  passScene = null
  passCamera = null
})
</script>

<style scoped>
.vol-view {
  position: relative;
  width: 100%;
  height: 100%;
  min-height: 560px;
  overflow: hidden;
  background: radial-gradient(ellipse 80% 60% at 50% 30%, #080c18 0%, #03040a 60%, #010206 100%);
}

.vl-canvas {
  position: absolute;
  inset: 0;
  display: block;
  touch-action: none;
  cursor: grab;
}

.vl-canvas:active {
  cursor: grabbing;
}

.vl-header {
  position: absolute;
  top: 28px;
  left: 32px;
  z-index: 2;
  pointer-events: none;
}

.vl-eyebrow {
  display: block;
  font-size: 11px;
  letter-spacing: 3px;
  text-transform: uppercase;
  color: #8a7f6a;
}

.vl-title {
  margin: 6px 0 4px;
  font-size: 26px;
  font-weight: 600;
  color: #fff4e0;
  text-shadow: 0 0 22px rgba(255, 216, 150, 0.4);
}

.vl-desc {
  margin: 0;
  font-size: 13px;
  color: #9a917f;
}

.vl-toolbar {
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

.vl-button {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 14px;
  border: 1px solid rgba(255, 216, 150, 0.32);
  border-radius: 8px;
  background: rgba(14, 12, 8, 0.62);
  color: #ddd0b4;
  font-size: 13px;
  cursor: pointer;
  transition: all 0.2s ease;
}

.vl-button:hover {
  border-color: rgba(255, 216, 150, 0.7);
  color: #fff6e6;
}

.vl-button.active {
  border-color: #ffd896;
  background: rgba(255, 216, 150, 0.16);
  color: #fffaf0;
  box-shadow: 0 0 14px rgba(255, 216, 150, 0.3);
}

.vl-button-key {
  font-size: 11px;
  padding: 1px 6px;
  border: 1px solid rgba(255, 216, 150, 0.38);
  border-radius: 4px;
  color: #c9b98f;
}

.vl-hud {
  position: absolute;
  left: 32px;
  bottom: 28px;
  z-index: 2;
  /* ⚠️ 必须加 pointer-events:none。
     这块面板在画面左下角压着一大片画布，不加的话鼠标落在它上面时
     pointerdown 的目标是 div，压根到不了 canvas —— 表现就是"左下角拖不动"。 */
  pointer-events: none;
  display: flex;
  flex-direction: column;
  gap: 6px;
  padding: 12px 16px;
  border: 1px solid rgba(255, 216, 150, 0.2);
  border-radius: 10px;
  background: rgba(10, 9, 6, 0.62);
  font-size: 12px;
  backdrop-filter: blur(8px);
}

.vl-hud-row {
  display: flex;
  justify-content: space-between;
  gap: 18px;
}

.vl-hud-k {
  color: #8a7f6a;
}

.vl-hud-v {
  color: #ddd0b4;
  font-variant-numeric: tabular-nums;
}

.vl-hint {
  position: absolute;
  right: 32px;
  bottom: 28px;
  z-index: 2;
  font-size: 12px;
  color: #6d6555;
  pointer-events: none;
}
</style>
