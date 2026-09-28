<template>
  <div ref="mountRef" class="lm-view">
    <div class="lm-header">
      <span class="lm-eyebrow">Gravity-driven Mercury · Raymarched SDF · Live Cube Reflection</span>
      <h1 class="lm-title">液态水银</h1>
      <p class="lm-desc">鼠标环绕观察 · 单击炸开散落一地 · 按住拖动把它拽变形 · 滚轮推拉 · 空格崩回</p>
    </div>

    <div class="lm-toolbar">
      <button
        v-for="item in SHAPE_MENU"
        :key="item.key"
        type="button"
        class="lm-button"
        :class="{ active: targetShape === item.key }"
        @click="selectShape(item.key)"
      >
        <span class="lm-button-key">{{ item.short }}</span>
        <span>{{ item.name }}</span>
      </button>
      <button type="button" class="lm-button lm-button-accent" @click="triggerCollapse">
        <span class="lm-button-key">Space</span>
        <span>崩回</span>
      </button>
      <button type="button" class="lm-button" :class="{ active: autoCycle }" @click="toggleAuto">
        <span class="lm-button-key">自动</span>
        <span>{{ autoCycle ? '循环' : '停止' }}</span>
      </button>
    </div>

    <div class="lm-panel">
      <div class="lm-group">
        <span class="lm-group-label">重力</span>
        <button
          v-for="g in GRAVITY_MENU"
          :key="g.key"
          type="button"
          class="lm-chip"
          :class="{ active: gravityIndex === g.key }"
          @click="gravityIndex = g.key"
        >{{ g.name }}</button>
      </div>
      <div class="lm-group">
        <span class="lm-group-label">环境</span>
        <button
          v-for="e in ENV_MENU"
          :key="e.key"
          type="button"
          class="lm-chip"
          :class="{ active: envIndex === e.key }"
          @click="setEnv(e.key)"
        >{{ e.name }}</button>
      </div>
      <div class="lm-group">
        <button type="button" class="lm-chip" :class="{ active: selfRefl }" @click="selfRefl = !selfRefl">自反射</button>
        <button type="button" class="lm-chip" :class="{ active: paused }" @click="paused = !paused">
          {{ paused ? '已暂停' : '运行中' }}
        </button>
        <button type="button" class="lm-chip" @click="resetView">复位</button>
      </div>
    </div>

    <div class="lm-hud">
      <span class="lm-hud-row"><span class="lm-hud-k">阶段</span><span class="lm-hud-v">{{ phaseLabel }}</span></span>
      <span class="lm-hud-row"><span class="lm-hud-k">目标形态</span><span class="lm-hud-v">{{ targetShapeName }}</span></span>
      <span class="lm-hud-row"><span class="lm-hud-k">液滴</span><span class="lm-hud-v">{{ DROP_COUNT }}</span></span>
      <span class="lm-hud-row"><span class="lm-hud-k">重力 g</span><span class="lm-hud-v">{{ gravityLabel }}</span></span>
      <span class="lm-hud-row"><span class="lm-hud-k">环境</span><span class="lm-hud-v">{{ envLabel }}</span></span>
      <span class="lm-hud-row"><span class="lm-hud-k">步进</span><span class="lm-hud-v">{{ MAX_STEPS }}+{{ REFL_STEPS }}</span></span>
      <span class="lm-hud-row"><span class="lm-hud-k">渲染比例</span><span class="lm-hud-v">{{ resLabel }}</span></span>
      <span class="lm-hud-row"><span class="lm-hud-k">帧率</span><span class="lm-hud-v">{{ fps }}</span></span>
    </div>
  </div>
</template>

<script setup>
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import * as THREE from 'three'

/* ============================================================
   液态水银：重力驱动的 SDF 液体 + 实时立方体贴图反射
   ============================================================

   和"编舞式插值"版本的根本区别：**液滴是被力推着走的，不是被摆过去的**。

     1. 动力学。14 个液滴各有速度，每帧受四种力：
          · 弹簧力  k * (目标位 - 当前位置)     k 由"阶段"决定
          · 重力    -g                       g 由界面上的三档滑杆给
          · 阻尼    -λv                      水银黏性大，λ 高
          · 地面    穿地即反弹 (restitution 0.16) + 水平摩擦
        弹簧刚度 k 是全局标量，它的涨落就是"液体的骨架"：
          滩 → k=10（几乎瘫软，只有重力与地面在塑形）
          人形 → k=200（硬起来，能站着对抗重力）
        视觉上的分裂 / 融合仍然交给 smooth-min —— 两个液滴靠近时
        自动长出一条过渡带，不需要任何网格动画。

     2. 状态机 池 → 塑形 → 成型 → 崩回 → 池
          · 塑形(rise)：k 用 1.2/s 慢慢涨（液体被"吸"上去），
                       同时给全体一个向上冲量 → 拉丝
          · 成型(hold)：k 拉满，叠加高频微颤，表面还在缓慢流动
          · 崩回(collapse)：**k 用 40/s 瞬间归零**，弹簧一断，
                       重力立刻接管，4 个小液滴获得向上冲量变成飞溅
                       —— 这就是"瞬间崩回一滩"的全部实现
        注意 formT(形态插值) 在崩回时**比 k 慢**（2.2/s vs 40/s），
        所以顺序是"先散架，再落地"，而不是整体平移下去。

     3. 环境反射。真的在用 CubeCamera：
          一个独立的 envScene（摄影棚 / 霓虹都市 / 星际）挂在
          (0,0.9,0) 的立方体相机上，每 0.25s 重渲染一次，
          主 shader 里 textureCube 采样。
        所以**背景里看到的霓虹柱，就是水银上倒映的霓虹柱**，
        视角一转倒影跟着转 —— 这是解析式假环境做不到的。
        再加一次自反射 march：反射射线若打到别的液滴，就取那个
        点的二次反射 —— 球与球的接缝处会映出对方的身影，
        这是水银最标志性的观感。

   ⚠️ 关键约束（踩坑点，改之前先读）：
     - 材质是 RawShaderMaterial → GLSL ES 1.00。
       顶点着色器**必须自己写 `attribute vec3 position;`**，漏了整页零像素。
     - GLSL 没有内置 PI，必须 #define。
     - `pow()` 底数必须严格大于 0，可能取 0 的地方一律先 max(x, 1e-4)。
     - 椭球 SDF 必须乘最小半轴做保守下界，否则 raymarch 穿模。
     - 液滴数据用**逐个展开的 uniform**（uB0…uB13），绕开 GLSL ES 1.00
       对数组索引的限制。
     - CubeCamera.update() 结束后必须手动 setRenderTarget(null) 并把
       cube camera 从主场景排除（它挂在 envScene 上，天然不会进主场景）。
     - k 是弹簧刚度，用显式欧拉每帧积分时**必须分子步**：k=200 时
       ω=14 rad/s，dt=1/30 单步会振散，2 子步才稳。
     - HUD 必须 pointer-events: none，否则吃掉一整片拖拽区。
     - 绝不能在渲染循环里 new Material —— 每帧新建会不停编译 GPU
       program，表现为越跑越卡直到整页卡死。
   ============================================================ */

const DROP_COUNT = 14
const MAX_STEPS = 96
const REFL_STEPS = 28
const FLOOR_Y = 0.0
const GRAVITY_G = 9.0          // 基础重力加速度（世界单位 / s²）
const DAMPING = 7.0            // 黏性阻尼系数
const SUBSTEPS = 2
const ENV_REFRESH = 0.25       // 立方体贴图重渲染间隔（秒）

const PHASE = { POOL: 'pool', RISE: 'rise', HOLD: 'hold', COLLAPSE: 'collapse', BURST: 'burst' }

const PHASE_CFG = {
  pool:     { dur: 2.4, k: 22,  kRate: 2.0, form: 0, formRate: 1.4 },
  rise:     { dur: 2.6, k: 200, kRate: 1.2, form: 1, formRate: 1.5 },
  hold:     { dur: 3.6, k: 200, kRate: 6.0, form: 1, formRate: 6.0 },
  collapse: { dur: 2.1, k: 0,   kRate: 40,  form: 0, formRate: 2.2 },
  // 点击炸开后的恢复段：k 从 0 极慢地长回来。
  // ⚠️ 实测过：kRate=0.85 时液滴 2.8s 就归位了，观感是"化开"不是"炸开再收拢"。
  //    冲量加大后必须把 kRate 一起压到 0.5，否则弹簧会在液滴落地前把它们拽回来。
  burst:    { dur: 3.0, k: 18,  kRate: 0.5, form: 0, formRate: 3.0 }
}

const PHASE_LABEL = {
  pool: '流淌 · 重力铺开',
  rise: '塑形 · 逆重力生长',
  hold: '成型 · 站立颤抖',
  collapse: '崩回 · 自由落体',
  burst: '炸开 · 溅射散落'
}

/* ---------- 交互参数 ---------- */

const TAP_SLOP = 7            // 按下后位移不超过这么多像素，才算"点击"
// ⚠️ 抓取半径实测调过：1.7 时，摊开的"滩"（液滴铺开半径约 1.8）里
//    几乎所有液滴都在半径外，权重趋近 0，拖动只剩下 0.08 的质心位移 —— 等于没拖。
//    2.6 能让近侧整块跟着走、远侧留一半，既拽得动又保留形变梯度。
const DRAG_RADIUS = 2.6       // 抓取影响半径（3D，从抓取点算到液滴目标球心）
const DRAG_MAX = 5.0          // 单次拖拽的最大位移，防止一甩就飞出画面
// 拖拽增益。基座换算（视线方向差 × 抓取点景深）保证"抓着的那一点跟着光标走"，
// 但抓取点离相机近时景深只有 3.3 左右，同样拖 260px 世界位移仅 1.2 ——
// 摊开的滩直径 3.6，质心才挪 0.35，看着像没动。放大 1.7 倍才有手感。
const DRAG_GAIN = 1.7
const DRAG_DEPTH_MIN = 3.0
const DRAG_DEPTH_MAX = 14.0

const easeInOut = (t) => t * t * (3.0 - 2.0 * t)
const clamp01 = (v) => (v < 0 ? 0 : (v > 1 ? 1 : v))
const lerp = (a, b, t) => a + (b - a) * t

const mountRef = ref(null)
const fps = ref(60)
const resLabel = ref('—')
const paused = ref(false)
const autoCycle = ref(true)
const selfRefl = ref(true)
const targetShape = ref(0)
const phase = ref(PHASE.POOL)
const gravityIndex = ref(1)
const envIndex = ref(0)

const GRAVITY_MENU = [
  { key: 0, name: '轻', value: 0.35, label: '0.35' },
  { key: 1, name: '标准', value: 1.0, label: '1.00' },
  { key: 2, name: '重', value: 2.1, label: '2.10' }
]

const ENV_MENU = [
  { key: 0, name: '摄影棚' },
  { key: 1, name: '霓虹' },
  { key: 2, name: '星际' }
]

const gravityLabel = computed(() => GRAVITY_MENU[gravityIndex.value].label)
const envLabel = computed(() => ENV_MENU[envIndex.value].name)
const phaseLabel = computed(() => PHASE_LABEL[phase.value])
const targetShapeName = computed(() => SHAPES[targetShape.value].name)

/* ------------------------------------------------------------
   形态定义
   ------------------------------------------------------------
   每个形态是 14 个液滴：p = 目标球心，r = 基准半径，s = 椭球比例。
   #0 是滩心 / 人的头 / 柱的底座 —— 编号要有语义对应，这样
   "从一滩里长出来"才是就地生长，而不是整体平移。
   ------------------------------------------------------------ */

const SHAPES = [
  {
    key: 0,
    name: '液态滩',
    k: 0.52,            // 融合强度：大 → 摊成一整片
    targetY: 0.34,
    dist: 5.4,
    // ⚠️ 滩的关键是"中间厚、边缘薄"：所有球心 y 都压到贴地（地面碰撞会把
    //    它们顶到 r*s.y 的高度），厚薄完全由 s.y 与 r 的递减来塑造。
    //    若把 s.y 写得很小（如 0.18），整滩会变成一张没有体积的煎饼。
    balls: [
      { p: [0.00, 0.10, 0.00], r: 1.00, s: [1.00, 0.62, 1.00] },
      { p: [-0.92, 0.10, 0.10], r: 0.72, s: [1.00, 0.52, 1.00] },
      { p: [0.94, 0.10, -0.12], r: 0.70, s: [1.00, 0.52, 1.00] },
      { p: [0.10, 0.10, 0.94], r: 0.68, s: [1.00, 0.48, 1.00] },
      { p: [-0.36, 0.10, -0.90], r: 0.66, s: [1.00, 0.48, 1.00] },
      { p: [1.50, 0.10, 0.62], r: 0.44, s: [1.00, 0.40, 1.00] },
      { p: [-1.44, 0.10, 0.72], r: 0.42, s: [1.00, 0.40, 1.00] },
      { p: [0.66, 0.10, 1.48], r: 0.38, s: [1.00, 0.36, 1.00] },
      { p: [-1.00, 0.10, -1.32], r: 0.40, s: [1.00, 0.36, 1.00] },
      { p: [1.66, 0.10, -0.56], r: 0.30, s: [1.00, 0.32, 1.00] },
      { p: [0.06, 0.10, 1.84], r: 0.26, s: [1.00, 0.30, 1.00] },
      { p: [-1.76, 0.10, -0.24], r: 0.28, s: [1.00, 0.30, 1.00] },
      { p: [-0.66, 0.10, 1.20], r: 0.34, s: [1.00, 0.34, 1.00] },
      { p: [1.18, 0.10, -1.10], r: 0.36, s: [1.00, 0.34, 1.00] }
    ]
  },
  {
    key: 1,
    name: '人形',
    // 融合强度：既要让手臂连成一条（否则是"珠串"），又不能让两腿粘成一片。
    // 0.15 会让四肢断成一串球，0.30 以上腿就并成一根柱子 —— 0.20 是这条线。
    k: 0.20,
    targetY: 0.95,
    dist: 4.6,
    balls: [
      { p: [0.00, 1.72, 0.00], r: 0.30, s: [1.00, 1.05, 1.00] },   // 头
      { p: [0.00, 1.44, 0.00], r: 0.19, s: [1.00, 1.00, 0.85] },   // 颈
      { p: [0.00, 1.14, 0.00], r: 0.35, s: [1.00, 1.02, 0.80] },   // 胸
      { p: [0.00, 0.86, 0.00], r: 0.28, s: [0.95, 0.95, 0.80] },   // 腰
      { p: [-0.50, 1.24, 0.00], r: 0.185, s: [1.00, 1.00, 1.00] }, // 左上臂
      { p: [-0.72, 0.94, 0.05], r: 0.165, s: [1.00, 1.00, 1.00] }, // 左小臂
      { p: [0.50, 1.24, 0.00], r: 0.185, s: [1.00, 1.00, 1.00] },  // 右上臂
      { p: [0.72, 0.94, 0.05], r: 0.165, s: [1.00, 1.00, 1.00] },  // 右小臂
      { p: [-0.21, 0.50, 0.00], r: 0.19, s: [1.00, 1.50, 1.00] },  // 左腿（拉长）
      { p: [0.21, 0.50, 0.00], r: 0.19, s: [1.00, 1.50, 1.00] },   // 右腿（拉长）
      { p: [-0.23, 0.13, 0.08], r: 0.15, s: [1.00, 0.70, 1.45] },  // 左脚
      { p: [0.23, 0.13, 0.08], r: 0.15, s: [1.00, 0.70, 1.45] },   // 右脚
      { p: [-0.84, 0.70, 0.07], r: 0.145, s: [1.00, 1.00, 1.00] }, // 左手
      { p: [0.84, 0.70, 0.07], r: 0.145, s: [1.00, 1.00, 1.00] }   // 右手
    ]
  },
  {
    key: 2,
    name: '银柱',
    k: 0.34,
    targetY: 1.32,
    dist: 6.4,
    balls: [
      { p: [0.00, 0.16, 0.00], r: 0.46, s: [1.00, 0.60, 1.00] },
      { p: [0.00, 0.44, 0.00], r: 0.38, s: [1.00, 1.00, 1.00] },
      { p: [0.00, 0.70, 0.00], r: 0.35, s: [1.00, 1.00, 1.00] },
      { p: [0.00, 0.96, 0.00], r: 0.33, s: [1.00, 1.00, 1.00] },
      { p: [0.00, 1.22, 0.00], r: 0.32, s: [1.00, 1.00, 1.00] },
      { p: [0.00, 1.48, 0.00], r: 0.31, s: [1.00, 1.00, 1.00] },
      { p: [0.00, 1.74, 0.00], r: 0.31, s: [1.00, 1.00, 1.00] },
      { p: [0.00, 2.00, 0.00], r: 0.30, s: [1.00, 1.00, 1.00] },
      { p: [0.00, 2.24, 0.00], r: 0.28, s: [1.00, 1.00, 1.00] },
      { p: [0.00, 2.46, 0.00], r: 0.25, s: [1.00, 1.00, 1.00] },
      { p: [0.00, 2.64, 0.00], r: 0.21, s: [1.00, 1.00, 1.00] },
      { p: [0.00, 2.78, 0.00], r: 0.17, s: [1.00, 1.00, 1.00] },
      { p: [0.00, 2.88, 0.00], r: 0.12, s: [1.00, 1.00, 1.00] },
      { p: [0.00, 2.95, 0.00], r: 0.08, s: [1.00, 1.00, 1.00] }
    ]
  },
  {
    key: 3,
    name: '银环',
    k: 0.26,
    targetY: 1.00,
    dist: 5.2,
    balls: (() => {
      const out = []
      for (let i = 0; i < DROP_COUNT; i += 1) {
        const a = (i / DROP_COUNT) * Math.PI * 2
        out.push({
          p: [Math.cos(a) * 1.15, 1.00 + Math.sin(a * 2) * 0.16, Math.sin(a) * 1.15],
          r: 0.30,
          s: [1.00, 0.90, 1.00]
        })
      }
      return out
    })()
  }
]

const SHAPE_MENU = SHAPES.map((s) => ({
  key: s.key,
  name: s.name,
  short: ['滩', '人', '柱', '环'][s.key]
}))

/* ------------------------------------------------------------
   着色器
   ------------------------------------------------------------ */

// 全屏四边形顶点着色器。
// ⚠️ RawShaderMaterial 发的是 GLSL ES 1.00，不会自动注入 attribute 声明，
//    `position` 必须自己写，漏掉就是顶点着色器编译失败、整页零像素。
const QUAD_VERT = `
  precision highp float;
  attribute vec3 position;
  varying vec2 vUv;
  void main() {
    vUv = position.xy * 0.5 + 0.5;
    gl_Position = vec4(position.xy, 0.0, 1.0);
  }`

// 14 个液滴的 uniform 声明与 smin 链。
// ⚠️ 刻意展开而不是用 uniform 数组：GLSL ES 1.00 对数组索引有限制，
//    展开写法零兼容风险，且没有循环开销。
const BALL_DECLS = (() => {
  const lines = []
  for (let i = 0; i < DROP_COUNT; i += 1) {
    lines.push(`uniform vec4 uB${i};`)  // xyz = 球心，w = 基准半径
    lines.push(`uniform vec4 uE${i};`)  // xyz = 椭球比例（半轴 = w * xyz）
  }
  return lines.join('\n')
})()

const BALL_CHAIN = (() => {
  const lines = []
  for (let i = 0; i < DROP_COUNT; i += 1) {
    lines.push(`  d = smin(d, sdEllipsoid(p, uB${i}, uE${i}), uMorphK);`)
  }
  return lines.join('\n')
})()

const FRAG = `
  precision highp float;

  #define PI 3.141592653589793
  #define MAX_STEPS ${MAX_STEPS}
  #define REFL_STEPS ${REFL_STEPS}
  #define MAX_DIST 26.0
  #define SURF_EPS 0.0016
  #define FLOOR_Y ${FLOOR_Y.toFixed(1)}

  varying vec2 vUv;

  uniform vec2  uResolution;
  uniform float uTime;
  uniform float uMorphK;         // 平滑并集的融合强度
  uniform float uRippleScale;    // 表面位移总强度
  uniform float uShock;          // 崩回冲击（驱动冲击波）
  uniform vec2  uRippleCenter;   // 鼠标在世界 XZ 平面上的落点
  uniform vec3  uCamPos;
  uniform mat3  uCamMat;         // 列 = right / up / forward
  uniform samplerCube uEnvMap;
  uniform float uEnvMix;
  uniform float uSelfRefl;

${BALL_DECLS}

  /* ---------- SDF 基础 ---------- */

  // 椭球距离。
  // ⚠️ 必须乘"最小半轴长度"做保守下界：直接返回 length(q) - 1.0 会高估距离，
  //    raymarch 步长越过表面，画面会出现诡异空洞与穿透。
  float sdEllipsoid(vec3 p, vec4 ball, vec4 shape) {
    vec3 s = shape.xyz * ball.w;
    vec3 q = (p - ball.xyz) / s;
    float minS = min(s.x, min(s.y, s.z));
    return (length(q) - 1.0) * minS;
  }

  // 平滑并集。两个距离靠近时用三次曲线插值出一条圆润的过渡带，
  // 这就是液态金属"分裂与融合"的全部来源 —— 不需要任何网格动画。
  float smin(float a, float b, float k) {
    float h = clamp(0.5 + 0.5 * (b - a) / k, 0.0, 1.0);
    return mix(b, a, h) - k * h * (1.0 - h);
  }

  // 表面位移：全局缓慢起伏 + 鼠标落点涟漪 + 崩回冲击波
  float surfaceDisplace(vec3 p) {
    float base = sin(p.x * 5.4 + uTime * 1.5)
               * sin(p.z * 6.1 - uTime * 1.15)
               * sin(p.y * 6.7 + uTime * 1.8);

    vec2 delta = p.xz - uRippleCenter;
    float r = length(delta);
    float ring = sin(r * 7.0 - uTime * 7.2) * exp(-r * 1.1);

    // 崩回冲击：一圈从中心炸开、迅速衰减的大波
    float shock = sin(r * 11.0 - uTime * 17.0) * exp(-r * 2.1) * uShock;

    return (base * 0.34 + ring * 0.72) * uRippleScale + shock * 0.055;
  }

  float sdfScene(vec3 p) {
    float d = 1e9;
${BALL_CHAIN}
    return d + surfaceDisplace(p);
  }

  vec3 calcNormal(vec3 p) {
    vec2 e = vec2(0.0018, 0.0);
    return normalize(vec3(
      sdfScene(p + e.xyy) - sdfScene(p - e.xyy),
      sdfScene(p + e.yxy) - sdfScene(p - e.yxy),
      sdfScene(p + e.yyx) - sdfScene(p - e.yyx)
    ));
  }

  // 廉价环境遮蔽：融合 / 分裂的接缝处自然变暗，体积感全靠它
  float calcAO(vec3 p, vec3 n) {
    float occ = 0.0;
    float sca = 1.0;
    for (int i = 0; i < 5; i++) {
      float h = 0.012 + 0.13 * float(i) / 4.0;
      float d = sdfScene(p + n * h);
      occ += (h - d) * sca;
      sca *= 0.82;
    }
    return clamp(1.0 - 1.6 * occ, 0.0, 1.0);
  }

  // 主光方向（摄影棚柔光箱），保留一点解析高光让亮点足够锐利
  vec3 keyDir() { return normalize(vec3(0.34, 0.90, 0.22)); }

  /* ---------- 主函数 ---------- */

  void main() {
    vec2 uv = (gl_FragCoord.xy - 0.5 * uResolution) / uResolution.y;

    vec3 ro = uCamPos;
    vec3 rd = normalize(uCamMat * vec3(uv, 1.0));

    /* ---- 场景 raymarch ---- */
    float t = 0.0;
    float d = 0.0;
    bool hit = false;
    for (int i = 0; i < MAX_STEPS; i++) {
      vec3 p = ro + rd * t;
      d = sdfScene(p);
      if (d < SURF_EPS * max(t, 1.0)) { hit = true; break; }
      t += d * 0.92;                 // 留 8% 余量，避免穿模
      if (t > MAX_DIST) break;
    }

    /* ---- 地面（解析平面，不用 raymarch）---- */
    float tFloor = 1e9;
    if (rd.y < -0.001) {
      tFloor = (FLOOR_Y - ro.y) / rd.y;
      if (tFloor < 0.0) tFloor = 1e9;
    }

    vec3 col;
    vec3 L = keyDir();
    float fogAmt;

    if (hit && t < tFloor) {
      /* ---------- 水银表面 ---------- */
      vec3 p = ro + rd * t;
      vec3 n = calcNormal(p);
      vec3 refl = reflect(rd, n);
      vec3 rc = p + n * 0.006 + refl * 0.010;   // 偏移起点，避免自交

      // 反射射线再 march 一次：打到别的液滴就取它的二次反射。
      // 球与球的接缝处会映出对方的身影 —— 水银最标志性的观感。
      vec3 metal;
      bool mirrored = false;
      if (uSelfRefl > 0.5) {
        float t2 = 0.0;
        for (int j = 0; j < REFL_STEPS; j++) {
          vec3 q = rc + refl * t2;
          float d2 = sdfScene(q);
          if (d2 < SURF_EPS * max(t2, 1.0) * 2.4) { mirrored = true; break; }
          t2 += d2 * 0.86;
          if (t2 > 7.5) break;
        }
        if (mirrored) {
          vec3 q = rc + refl * t2;
          vec3 n2 = calcNormal(q);
          vec3 r2 = reflect(refl, n2);
          vec3 c2 = textureCube(uEnvMap, r2).rgb * uEnvMix;
          float fres2 = pow(max(1.0 - clamp(dot(n2, -refl), 0.0, 1.0), 1e-4), 4.0);
          metal = (c2 + vec3(0.55, 0.68, 0.92) * fres2 * 0.30) * vec3(0.86, 0.90, 0.96);
        }
      }
      if (!mirrored) {
        metal = textureCube(uEnvMap, refl).rgb * uEnvMix * vec3(0.90, 0.93, 0.97);
      }

      // 掠射角补亮：勾出轮廓，让相邻液滴在视觉上分得开
      float fres = pow(max(1.0 - clamp(dot(n, -rd), 0.0, 1.0), 1e-4), 4.0);
      metal += vec3(0.55, 0.68, 0.92) * fres * 0.26;

      // 主高光
      vec3 H = normalize(L - rd);
      float spec = pow(max(dot(n, H), 1e-4), 240.0);
      metal += vec3(1.00, 0.98, 0.95) * spec * 3.2;

      // 水银的自色：极暗的冷灰，几乎完全靠反射成像
      metal = mix(metal, vec3(0.055, 0.063, 0.078), 0.17);

      float ao = calcAO(p, n);
      metal *= mix(0.32, 1.0, ao);

      fogAmt = 1.0 - exp(-0.030 * t * t);
      col = mix(metal, textureCube(uEnvMap, rd).rgb * uEnvMix * 0.62, clamp(fogAmt, 0.0, 1.0));
    } else if (tFloor < 1e8) {
      /* ---------- 地面 ---------- */
      vec3 p = ro + rd * tFloor;

      // 接触暗部：液体越近地面越暗，避免像"浮"在空中
      float contact = sdfScene(vec3(p.x, FLOOR_Y + 0.06, p.z));
      float shade = clamp(contact / 0.55, 0.0, 1.0);

      vec3 base = textureCube(uEnvMap, reflect(rd, vec3(0.0, 1.0, 0.0))).rgb * uEnvMix * 0.16;

      // 细网格线，给地面一点尺度感
      vec2 g = abs(fract(p.xz * 0.5) - 0.5);
      float grid = 1.0 - smoothstep(0.0, 0.02, min(g.x, g.y));
      base += vec3(0.10, 0.16, 0.26) * grid * 0.30;

      base *= mix(0.10, 1.0, shade);

      fogAmt = 1.0 - exp(-0.016 * tFloor * tFloor);
      col = mix(base, textureCube(uEnvMap, rd).rgb * uEnvMix * 0.62, clamp(fogAmt, 0.0, 1.0));
    } else {
      /* ---------- 背景 = 环境的天空盒 ----------
         背景里看到的霓虹柱，就是水银上倒映的那批霓虹柱。 */
      col = textureCube(uEnvMap, rd).rgb * uEnvMix * 0.62;
      // 地平线以下再压暗一点，避免下半屏被地面色块糊住
      col = mix(col, col * 0.25, 1.0 - smoothstep(-0.55, 0.0, rd.y));
    }

    /* ---- 色调映射 + 暗角 ---- */
    col = col / (col + vec3(0.85));
    col = pow(max(col, vec3(1e-4)), vec3(0.4545));

    vec2 q = gl_FragCoord.xy / uResolution;
    float vig = pow(max(16.0 * q.x * q.y * (1.0 - q.x) * (1.0 - q.y), 1e-4), 0.2);
    col *= 0.38 + 0.62 * vig;

    gl_FragColor = vec4(col, 1.0);
  }`

/* ------------------------------------------------------------
   运行时状态
   ------------------------------------------------------------ */

let renderer = null
let scene = null
let camera = null
let clock = null
let rafId = 0
let quadMesh = null
let material = null
let uniforms = null
let resizeObserver = null

let envScene = null
let envGroup = null
let cubeCam = null
let cubeRT = null
let envTimer = 0

let simTime = 0
let formT = 0                 // 0 = 滩，1 = 当前目标形态
let springK = PHASE_CFG.pool.k
let morphShock = 0
let rippleScale = 0.012
let phaseT = 0
let burstT = 0                // 1 → 0：炸开的余威，暂时压低黏性、放宽速度上限

/** @type {{p: THREE.Vector3, v: THREE.Vector3, tp: THREE.Vector3, tr: number, ts: THREE.Vector3, phase: number}[]} */
const drops = []

// 相机（轨道式）
const camPos = new THREE.Vector3()
const camTarget = new THREE.Vector3()
const camForward = new THREE.Vector3()
const camRight = new THREE.Vector3()
const camUp = new THREE.Vector3()
const camMat = new THREE.Matrix3()
const worldUp = new THREE.Vector3(0, 1, 0)

const cam = {
  dist: 5.9, azim: 0.0, elev: 0.20, targetY: 0.34,
  tDist: 5.9, tAzim: 0.0, tElev: 0.20, tTargetY: 0.34
}

const pointer = { nx: 0.5, ny: 0.5, down: false }
let canvasAspect = 1

/* ------------------------------------------------------------
   手势状态
   ------------------------------------------------------------
   两种手势靠"按下后有没有挪动"区分，不靠时间戳 —— 时间阈值会让
   "按住不动再松手"变成什么都不发生，手感发闷。

     · 单击（位移 ≤ TAP_SLOP）→ 炸开
     · 拖动（位移 > TAP_SLOP）→ 抓住按下点附近的那一团，整体平移
   每次 pointerdown 都先把"抓取快照"拍好，两种手势共用同一份射线拾取。
   ------------------------------------------------------------ */

const drag = {
  active: false,
  depth: 6,                       // 拖拽时指针所在的景深（相机到抓取点的距离）
  point: new THREE.Vector3(),     // 抓取点（世界坐标，打在液面上）
  ray0: new THREE.Vector3(),      // 按下那一刻的视线方向，delta 的基准
  delta: new THREE.Vector3(),     // 相对基准的位移
  anchors: [],                    // 抓取瞬间每个液滴的目标位快照
  weights: new Float32Array(DROP_COUNT)
}

const press = {
  x: 0, y: 0,
  tap: false,
  point: new THREE.Vector3()      // 按下时的拾取点：既是爆点，也是抓取中心
}

// 分辨率自适应（raymarching 是像素级开销，降分辨率最有效）
let resScale = 0.72
let resCooldown = 0
let frameCount = 0
let fpsAccum = 0

/* ------------------------------------------------------------
   初始化
   ------------------------------------------------------------ */

onMounted(() => {
  const container = mountRef.value
  if (!container) return

  const canvas = document.createElement('canvas')
  const gl = canvas.getContext('webgl2', { alpha: false, antialias: false })
  if (!gl) {
    container.innerHTML =
      '<div style="padding:40px;color:#7d90b5;font-size:14px">当前浏览器不支持 WebGL2，无法运行 raymarching 金属渲染。</div>'
    return
  }

  renderer = new THREE.WebGLRenderer({
    canvas,
    context: gl,
    antialias: false,
    alpha: false,
    powerPreference: 'high-performance'
  })
  renderer.setClearColor(0x04060b, 1)
  renderer.domElement.classList.add('lm-canvas')
  container.appendChild(renderer.domElement)

  scene = new THREE.Scene()
  camera = new THREE.OrthographicCamera(-1, 1, 1, -1, 0, 1)

  // ---- 环境：独立场景 + 立方体相机 ----
  envScene = new THREE.Scene()
  envScene.background = new THREE.Color(0x03050a)
  envGroup = buildEnv(ENV_MENU[envIndex.value].name)
  envScene.add(envGroup)

  // ⚠️ 立方体贴图的分辨率直接决定"背景天空盒"的清晰度：背景就是把这张
  //    cube map 铺开画的，256 会让星点变成方块、霓虹柱边缘发毛。512 起。
  cubeRT = new THREE.WebGLCubeRenderTarget(512)
  cubeCam = new THREE.CubeCamera(0.1, 220, cubeRT)
  cubeCam.position.set(0, 0.9, 0)
  envScene.add(cubeCam)

  uniforms = {
    uResolution: { value: new THREE.Vector2(1, 1) },
    uTime: { value: 0 },
    uMorphK: { value: SHAPES[0].k },
    uRippleScale: { value: rippleScale },
    uShock: { value: 0 },
    uRippleCenter: { value: new THREE.Vector2(0, 0) },
    uCamPos: { value: camPos },
    uCamMat: { value: camMat },
    uEnvMap: { value: cubeRT.texture },
    uEnvMix: { value: 1.0 },
    uSelfRefl: { value: 1 }
  }
  for (let i = 0; i < DROP_COUNT; i += 1) {
    uniforms[`uB${i}`] = { value: new THREE.Vector4() }
    uniforms[`uE${i}`] = { value: new THREE.Vector4(1, 1, 1, 0) }
  }

  // 材质只建一次，渲染循环里只改 uniform 的值。
  // ⚠️ 绝不能在循环里 new Material —— 每帧新建会不断编译 GPU program。
  material = new THREE.RawShaderMaterial({
    vertexShader: QUAD_VERT,
    fragmentShader: FRAG,
    uniforms,
    depthTest: false,
    depthWrite: false
  })

  const geo = new THREE.BufferGeometry()
  geo.setAttribute('position', new THREE.BufferAttribute(new Float32Array([
    -1, -1, 0, 1, -1, 0, 1, 1, 0,
    -1, -1, 0, 1, 1, 0, -1, 1, 0
  ]), 3))
  quadMesh = new THREE.Mesh(geo, material)
  quadMesh.frustumCulled = false
  scene.add(quadMesh)

  initDrops()
  applyShapeCamera(0, true)
  applySize()
  updateEnvMap()
  bindEvents()

  clock = new THREE.Clock()
  animate()
})

function initDrops() {
  drops.length = 0
  const base = SHAPES[0].balls
  for (let i = 0; i < DROP_COUNT; i += 1) {
    const b = base[i]
    drops.push({
      p: new THREE.Vector3(b.p[0], b.p[1], b.p[2]),
      v: new THREE.Vector3(0, 0, 0),
      tp: new THREE.Vector3(b.p[0], b.p[1], b.p[2]),   // 目标球心（随 formT 插值）
      tr: b.r,                                          // 目标半径（= 渲染半径）
      ts: new THREE.Vector3(b.s[0], b.s[1], b.s[2]),    // 目标椭球比例
      phase: Math.random() * Math.PI * 2
    })
  }
}

/* ------------------------------------------------------------
   环境场景（摄影棚 / 霓虹 / 星际）
   ------------------------------------------------------------ */

function buildEnv(name) {
  const g = new THREE.Group()

  // 地面（三套环境共用）
  const floor = new THREE.Mesh(
    new THREE.PlaneGeometry(80, 80),
    new THREE.MeshBasicMaterial({ color: name === '星际' ? 0x020306 : 0x04060a })
  )
  floor.rotation.x = -Math.PI / 2
  g.add(floor)

  if (name === '摄影棚') {
    // 顶部柔光箱。⚠️ 尺寸要克制：滩面是水平的镜面，头顶的柔光箱会被
    //    整片映出来 —— 9×5 的大白板会让整个液面糊成一块死白。
    const box = new THREE.Mesh(
      new THREE.PlaneGeometry(4.6, 2.6),
      new THREE.MeshBasicMaterial({ color: 0xf2f6ff })
    )
    box.rotation.x = Math.PI / 2
    box.position.set(0, 6.2, 0)
    g.add(box)

    // 四周等角分布的条形灯 —— "看起来很贵"的关键
    const barGeo = new THREE.BoxGeometry(0.15, 3.4, 0.15)
    for (let i = 0; i < 6; i += 1) {
      const a = (i / 6) * Math.PI * 2
      const bar = new THREE.Mesh(barGeo, new THREE.MeshBasicMaterial({
        color: i % 2 === 0 ? 0xd6e6ff : 0xa8c8ff
      }))
      bar.position.set(Math.cos(a) * 5.2, 2.0, Math.sin(a) * 5.2)
      g.add(bar)
    }

    // 地平线亮环：给曲面一个清晰的明暗分界
    const ring = new THREE.Mesh(
      new THREE.TorusGeometry(10, 0.035, 6, 96),
      new THREE.MeshBasicMaterial({ color: 0x9fc0ff })
    )
    ring.rotation.x = Math.PI / 2
    ring.position.y = 1.2
    g.add(ring)

    const grid = new THREE.GridHelper(80, 80, 0x1a2c4a, 0x0c182a)
    grid.position.y = 0.01
    g.add(grid)
  } else if (name === '霓虹') {
    const COLORS = [0x22e0ff, 0xff3bd4, 0x8a5cff, 0x2effc0]

    // 一圈高度错落的霓虹柱
    const pillarGeo = new THREE.BoxGeometry(0.17, 1, 0.17)
    for (let i = 0; i < 18; i += 1) {
      const a = (i / 18) * Math.PI * 2 + (i % 3) * 0.33
      const rr = 4.4 + (i % 4) * 1.6
      const h = 1.5 + ((i * 7) % 5) * 0.95
      const pillar = new THREE.Mesh(pillarGeo, new THREE.MeshBasicMaterial({
        color: COLORS[i % 4]
      }))
      pillar.scale.y = h
      pillar.position.set(Math.cos(a) * rr, h / 2 + 0.35, Math.sin(a) * rr)
      g.add(pillar)
    }

    // 悬浮水平光环
    for (let i = 0; i < 3; i += 1) {
      const rad = 2.8 + i * 1.8
      const ring = new THREE.Mesh(
        new THREE.TorusGeometry(rad, 0.035, 6, 96),
        new THREE.MeshBasicMaterial({ color: COLORS[(i + 1) % 4] })
      )
      ring.rotation.x = Math.PI / 2
      ring.position.y = 0.75 + i * 1.6
      g.add(ring)
    }

    const grid = new THREE.GridHelper(80, 80, 0x3a1a5a, 0x150f2a)
    grid.position.y = 0.01
    g.add(grid)
  } else {
    // 星际：星点 + 远处恒星 + 暗淡星云
    const count = 2400
    const pos = new Float32Array(count * 3)
    for (let i = 0; i < count; i += 1) {
      const u = Math.random() * 2 - 1
      const th = Math.random() * Math.PI * 2
      const rr = Math.sqrt(1 - u * u)
      const rad = 34 + Math.random() * 46
      pos[i * 3] = Math.cos(th) * rr * rad
      pos[i * 3 + 1] = u * rad * 0.75 + 8
      pos[i * 3 + 2] = Math.sin(th) * rr * rad
    }
    const starGeo = new THREE.BufferGeometry()
    starGeo.setAttribute('position', new THREE.BufferAttribute(pos, 3))
    g.add(new THREE.Points(starGeo, new THREE.PointsMaterial({
      color: 0xdce8ff, size: 1.3, sizeAttenuation: true
    })))

    // 远处恒星：水银上最亮的那颗点就是它
    const sun = new THREE.Mesh(
      new THREE.SphereGeometry(2.4, 24, 16),
      new THREE.MeshBasicMaterial({ color: 0xfff2d6 })
    )
    sun.position.set(-17, 9, -23)
    g.add(sun)

    // 星云（大球反面）
    const NEBULA = [
      { c: 0x1b0c33, r: 17, p: [14, -4, 16] },
      { c: 0x08203a, r: 21, p: [-20, 6, 12] },
      { c: 0x220b2e, r: 15, p: [4, 10, -22] }
    ]
    for (const nb of NEBULA) {
      const m = new THREE.Mesh(
        new THREE.SphereGeometry(nb.r, 20, 14),
        new THREE.MeshBasicMaterial({ color: nb.c, side: THREE.BackSide })
      )
      m.position.set(nb.p[0], nb.p[1], nb.p[2])
      g.add(m)
    }

    const grid = new THREE.GridHelper(80, 40, 0x14284a, 0x08111f)
    grid.position.y = 0.01
    g.add(grid)
  }

  return g
}

function disposeEnv(group) {
  if (!group) return
  group.traverse((o) => {
    if (o.geometry) o.geometry.dispose()
    if (o.material) {
      if (Array.isArray(o.material)) o.material.forEach((m) => m.dispose())
      else o.material.dispose()
    }
  })
}

function setEnv(index) {
  envIndex.value = index
  if (!envScene) return
  if (envGroup) {
    envScene.remove(envGroup)
    disposeEnv(envGroup)
  }
  envGroup = buildEnv(ENV_MENU[index].name)
  envScene.add(envGroup)
  envTimer = 0
  updateEnvMap()
}

function updateEnvMap() {
  if (!cubeCam || !renderer) return
  cubeCam.update(renderer, envScene)
  renderer.setRenderTarget(null)
}

/* ------------------------------------------------------------
   尺寸 / 相机
   ------------------------------------------------------------ */

function applyShapeCamera(index, instant) {
  const s = SHAPES[index]
  cam.tTargetY = s.targetY
  cam.tDist = s.dist
  if (instant) {
    cam.targetY = s.targetY
    cam.dist = s.dist
  }
}

function applySize() {
  const container = mountRef.value
  if (!container || !renderer) return
  const w = container.clientWidth
  const h = container.clientHeight
  if (!w || !h) return
  canvasAspect = w / h
  renderer.setPixelRatio(Math.min(window.devicePixelRatio, 2) * resScale)
  renderer.setSize(w, h)
  const size = renderer.getDrawingBufferSize(new THREE.Vector2())
  uniforms.uResolution.value.copy(size)
  resLabel.value = `${Math.round(resScale * 100)}%`
}

/* ------------------------------------------------------------
   形态目标 + 动力学
   ------------------------------------------------------------ */

// 把「滩」与「当前目标形态」按 formT 插值出每个液滴的目标位 / 半径 / 椭球比例
function refreshTargets() {
  const e = easeInOut(clamp01(formT))
  const base = SHAPES[0].balls
  const target = SHAPES[targetShapeIndex()].balls
  for (let i = 0; i < DROP_COUNT; i += 1) {
    const a = base[i]
    const b = target[i]
    const d = drops[i]
    d.tp.set(lerp(a.p[0], b.p[0], e), lerp(a.p[1], b.p[1], e), lerp(a.p[2], b.p[2], e))
    d.tr = lerp(a.r, b.r, e)
    d.ts.set(lerp(a.s[0], b.s[0], e), lerp(a.s[1], b.s[1], e), lerp(a.s[2], b.s[2], e))
  }

  /* ---- 拖拽：把抓住的那一团整体平移 ----
     effTarget = lerp(原始目标, 抓取锚点 + delta, w)
     近处的液滴 w→1，整块跟着走；远处的 w→0，留在原地 ——
     中间那段就被拉长、拉细，松手后弹簧再把它们收回去，
     这就是"水银随鼠标变形"的全部来源，不需要给形状做任何建模。 */
  if (drag.active) {
    const dx = drag.delta.x
    const dy = drag.delta.y
    const dz = drag.delta.z
    for (let i = 0; i < DROP_COUNT; i += 1) {
      const w = drag.weights[i]
      if (w <= 0) continue
      const d = drops[i]
      const an = drag.anchors[i]
      d.tp.set(
        lerp(d.tp.x, an.x + dx, w),
        lerp(d.tp.y, an.y + dy, w),
        lerp(d.tp.z, an.z + dz, w)
      )
    }
  }
}

// formT 只在「滩(0) → 当前形态(1)」之间走，所以目标形态为滩时恒等于 0
function targetShapeIndex() {
  return targetShape.value === 0 ? 0 : targetShape.value
}

function stepDrops(dt) {
  refreshTargets()

  const g = GRAVITY_G * GRAVITY_MENU[gravityIndex.value].value
  const holding = phase.value === PHASE.HOLD
  const h = dt / SUBSTEPS

  // 炸开的余威：黏性暂时让位。
  // 黏性 7/s 的时间常数只有 0.14s，冲量再大也会在 0.2 秒内被吃掉，
  // 液滴根本甩不出去 —— 所以破开的瞬间必须把阻尼压到 1/3 并保持住
  // （burstT 衰减太快也没用，实测 2.6 的衰减率下液滴只飞出去 1.8 个单位，
  //   比"滩"本身的 1.4 半径大不了多少，看着就是"化开"而不是"炸开"）。
  const dampStep = Math.exp(-DAMPING * (1 - 0.72 * burstT) * (drag.active ? 1.45 : 1.0) * h)
  const maxSp = 16 + 16 * burstT

  for (let s = 0; s < SUBSTEPS; s += 1) {
    for (let i = 0; i < DROP_COUNT; i += 1) {
      const d = drops[i]

      let ax = (d.tp.x - d.p.x) * springK
      let ay = (d.tp.y - d.p.y) * springK - g
      let az = (d.tp.z - d.p.z) * springK

      if (holding) {
        // 成型期的高频微颤：站着的液体还在抖
        ax += Math.sin(simTime * 9.0 + d.phase) * 8.0
        az += Math.cos(simTime * 7.3 + d.phase * 1.7) * 8.0
      }
      if (springK < 28) {
        // 滩状态：极缓慢的蠕动，让它看起来一直在流
        ax += Math.sin(simTime * 1.6 + d.phase * 2.1) * 1.1
        az += Math.cos(simTime * 1.3 + d.phase * 1.6) * 1.1
      }

      d.v.x += ax * h
      d.v.y += ay * h
      d.v.z += az * h

      d.v.multiplyScalar(dampStep)

      const sp = d.v.length()
      if (sp > maxSp) d.v.multiplyScalar(maxSp / sp)

      d.p.addScaledVector(d.v, h)

      // 地面碰撞：反弹 + 水平摩擦
      const minY = d.tr * d.ts.y
      if (d.p.y < minY) {
        d.p.y = minY
        if (d.v.y < 0) d.v.y = -d.v.y * 0.16
        d.v.x *= 0.86
        d.v.z *= 0.86
      }
    }

    // 表面张力：邻近液滴之间一点微弱的吸引，防止滩无限散开
    for (let i = 0; i < DROP_COUNT; i += 1) {
      for (let j = i + 1; j < DROP_COUNT; j += 1) {
        const a = drops[i]
        const b = drops[j]
        const dx = b.p.x - a.p.x
        const dy = b.p.y - a.p.y
        const dz = b.p.z - a.p.z
        const dist = Math.sqrt(dx * dx + dy * dy + dz * dz)
        if (dist < 1e-4 || dist > 1.7) continue
        const f = (dist - 0.95) * 0.9 * h
        const ix = (dx / dist) * f
        const iy = (dy / dist) * f
        const iz = (dz / dist) * f
        a.v.x += ix; a.v.y += iy; a.v.z += iz
        b.v.x -= ix; b.v.y -= iy; b.v.z -= iz
      }
    }
  }
}

function uploadDrops() {
  // ⚠️ 半径 / 椭球比例必须用 refreshTargets() 算出的 tr / ts（随 formT 插值），
  //    不能用 initDrops 里那个初始常量 —— 否则从滩长成人形时半径不跟着缩，
  //    一堆大球叠在一起会被 smooth-min 融成一摞"飞碟"。
  for (let i = 0; i < DROP_COUNT; i += 1) {
    const d = drops[i]
    uniforms[`uB${i}`].value.set(d.p.x, d.p.y, d.p.z, d.tr)
    uniforms[`uE${i}`].value.set(d.ts.x, d.ts.y, d.ts.z, 0)
  }

  const e = easeInOut(clamp01(formT))
  const kBase = SHAPES[0].k
  const kTarget = SHAPES[targetShapeIndex()].k
  uniforms.uMorphK.value = lerp(kBase, kTarget, e)
}

/* ------------------------------------------------------------
   状态机
   ------------------------------------------------------------ */

function setPhase(next) {
  phase.value = next
  phaseT = 0

  if (next === PHASE.RISE) {
    // 上冲：液体被"抽"上去的第一步，制造拉丝
    for (const d of drops) {
      d.v.y += 0.34 + Math.random() * 0.26
      d.v.x += (Math.random() - 0.5) * 0.24
      d.v.z += (Math.random() - 0.5) * 0.24
    }
  }

  if (next === PHASE.COLLAPSE) {
    // 崩回：弹簧一断，重力接管；几个小液滴获得向上冲量变成飞溅
    morphShock = 1.0
    const splash = [5, 7, 10, 12]
    for (let i = 0; i < DROP_COUNT; i += 1) {
      const d = drops[i]
      if (splash.indexOf(i) >= 0) {
        d.v.y += 2.7 + Math.random() * 1.5
        d.v.x += (Math.random() - 0.5) * 2.6
        d.v.z += (Math.random() - 0.5) * 2.6
      } else {
        d.v.y += 0.30
        d.v.x += (Math.random() - 0.5) * 0.95
        d.v.z += (Math.random() - 0.5) * 0.95
      }
    }
  }
}

function advancePhase() {
  switch (phase.value) {
    case PHASE.POOL:
      if (autoCycle.value) {
        // 摊够了就换下一个形态站起来
        const next = targetShape.value === 0
          ? 1
          : (targetShape.value % 3) + 1
        selectShape(next)
      } else {
        phaseT = -1e9    // 停在滩里：phaseT 永远追不上 dur，状态机不再推进
      }
      break
    case PHASE.RISE:
      setPhase(PHASE.HOLD)
      break
    case PHASE.HOLD:
      setPhase(PHASE.COLLAPSE)
      break
    case PHASE.COLLAPSE:
      setPhase(PHASE.POOL)
      break
    case PHASE.BURST:
      // 飞散段结束，交给 POOL 继续慢慢聚拢（POOL 的 kRate 更陡，负责收尾）
      setPhase(PHASE.POOL)
      break
    default:
      setPhase(PHASE.POOL)
  }
}

/* ------------------------------------------------------------
   拾取：把鼠标射线打到液面上
   ------------------------------------------------------------
   和 shader 的 sdfScene 是同一套公式（椭球 + smin 链，不含表面位移），
   只在 pointerdown 时跑一次，代价可以忽略。
   有了真实落点，炸开才是"从你点的那个地方炸"，
   拖拽才是"抓住你摸到的那一块"，而不是从世界原点或滩心取近似。
   ------------------------------------------------------------ */

const _pickDir = new THREE.Vector3()
const _pickTmp = new THREE.Vector3()

function sdfSceneJS(x, y, z) {
  const k = Math.max(uniforms.uMorphK.value, 1e-3)
  let d = 1e9
  for (let i = 0; i < DROP_COUNT; i += 1) {
    const dr = drops[i]
    const sx = dr.tr * dr.ts.x
    const sy = dr.tr * dr.ts.y
    const sz = dr.tr * dr.ts.z
    const qx = (x - dr.p.x) / sx
    const qy = (y - dr.p.y) / sy
    const qz = (z - dr.p.z) / sz
    const minS = Math.min(sx, Math.min(sy, sz))
    const di = (Math.sqrt(qx * qx + qy * qy + qz * qz) - 1.0) * minS
    const h = clamp01(0.5 + 0.5 * (di - d) / k)
    d = lerp(di, d, h) - k * h * (1.0 - h)
  }
  return d
}

// 视线方向（与 shader 里 uCamMat * vec3(uv, 1.0) 完全一致）
function pointerRay(out) {
  const uvx = (pointer.nx - 0.5) * canvasAspect
  const uvy = 0.5 - pointer.ny
  return out.copy(camRight)
    .multiplyScalar(uvx)
    .addScaledVector(camUp, uvy)
    .addScaledVector(camForward, 1.0)
    .normalize()
}

// 打在液面上返回落点；打空则退回到"离射线最近的液滴球心"，
// 保证对着背景乱点也总能炸出一个合理的爆点。
function pickSurface(out) {
  pointerRay(_pickDir)
  let t = 0.02
  let hit = false
  for (let i = 0; i < 80; i += 1) {
    const d = sdfSceneJS(
      camPos.x + _pickDir.x * t,
      camPos.y + _pickDir.y * t,
      camPos.z + _pickDir.z * t
    )
    if (d < 0.006 * Math.max(t, 1.0)) { hit = true; break }
    t += Math.max(d * 0.9, 0.004)
    if (t > 40) break
  }
  if (hit) {
    out.copy(_pickDir).multiplyScalar(t).add(camPos)
    return true
  }

  let best = 0
  let bestD = Infinity
  for (let i = 0; i < DROP_COUNT; i += 1) {
    const dv = _pickTmp.subVectors(drops[i].p, camPos)
    const proj = clamp(0, 40, dv.dot(_pickDir))
    const px = dv.x - _pickDir.x * proj
    const py = dv.y - _pickDir.y * proj
    const pz = dv.z - _pickDir.z * proj
    const dd = px * px + py * py + pz * pz
    if (dd < bestD) { bestD = dd; best = i }
  }
  out.copy(drops[best].p)
  return false
}

// 抓取权重：到抓取点的 3D 距离越近越"跟着走"。
// 用 3D 而不是 XZ —— 站在人形胸口抓一把时，腿应该留在原地，
// 只把上半身拽变形，这样才有"被拉长、被扯断"的观感。
//
// ⚠️ 半径必须自适应，这是实测踩出来的：
//    固定 2.6 时，抓取点落在"摊开的滩"（液滴铺开半径约 1.8）的一侧，
//    另一侧的液滴距离超过 2.6 → 权重全 0 → 权重均值只有 0.14，
//    指针在世界里挪了 2.58，质心才动 0.36，拖起来像没反应。
//    改成"至少 2.6，但跟着液滴团的实际铺开范围放大"，人形（团很小）不受影响，
//    滩（团很大）也能整块带起来，同时远侧仍留 0.1 左右的权重保住形变梯度。
function updateGrabWeights(point) {
  let maxD = 0
  const raw = []
  for (let i = 0; i < DROP_COUNT; i += 1) {
    const dd = drops[i].tp.distanceTo(point)
    raw.push(dd)
    if (dd > maxD) maxD = dd
  }
  const r = Math.min(DRAG_RADIUS * 2.2, Math.max(DRAG_RADIUS, maxD * 0.92))
  for (let i = 0; i < DROP_COUNT; i += 1) {
    const t = clamp01(1.0 - raw[i] / r)
    drag.weights[i] = t * t * (3.0 - 2.0 * t)
  }
}

/* ------------------------------------------------------------
   事件
   ------------------------------------------------------------ */

function updatePointer(e) {
  const el = renderer.domElement
  const rect = el.getBoundingClientRect()
  if (!rect.width || !rect.height) return
  pointer.nx = (e.clientX - rect.left) / rect.width
  pointer.ny = (e.clientY - rect.top) / rect.height

  // ⚠️ 按住期间不挪相机：拖拽要的是"世界不动、水银动"，
  //    相机跟着鼠标转会立刻把拖拽搅成一团漂移。
  if (!pointer.down) {
    cam.tAzim = (pointer.nx - 0.5) * 1.05
    cam.tElev = clamp(-0.02, 0.82, 0.20 + (0.5 - pointer.ny) * 0.58)
  }

  updateRippleCenter()
}

const clamp = (lo, hi, v) => (v < lo ? lo : (v > hi ? hi : v))

// 把鼠标位置投影到 y ≈ 0.14 的水平面上，作为涟漪中心
function updateRippleCenter() {
  if (!renderer || !uniforms) return
  const uvx = (pointer.nx - 0.5) * canvasAspect
  const uvy = 0.5 - pointer.ny
  const dir = new THREE.Vector3()
    .addScaledVector(camRight, uvx)
    .addScaledVector(camUp, uvy)
    .addScaledVector(camForward, 1.0)
    .normalize()

  if (Math.abs(dir.y) < 1e-4) return
  const t = (0.14 - camPos.y) / dir.y
  if (t <= 0 || t > 60) return
  uniforms.uRippleCenter.value.set(camPos.x + dir.x * t, camPos.z + dir.z * t)
}

function onPointerDown(e) {
  if (e.button !== undefined && e.button !== 0) return
  pointer.down = true
  updatePointer(e)
  renderer.domElement.setPointerCapture?.(e.pointerId)

  press.x = e.clientX
  press.y = e.clientY
  press.tap = true
  pickSurface(press.point)

  // 抓取快照：锚点取"目标位"而不是当前位置 —— 按下瞬间 delta = 0，
  // 此时 lerp(tp, anchor, w) 恒等于 tp，画面不会有任何跳变。
  for (let i = 0; i < DROP_COUNT; i += 1) drag.anchors[i] = drops[i].tp.clone()
  updateGrabWeights(press.point)
  drag.point.copy(press.point)
  drag.depth = clamp(DRAG_DEPTH_MIN, DRAG_DEPTH_MAX, camPos.distanceTo(press.point))
  drag.ray0.copy(pointerRay(_pickDir))
  drag.delta.set(0, 0, 0)
  drag.active = false
}

function onPointerMove(e) {
  updatePointer(e)
  if (!pointer.down) return

  if (press.tap) {
    const dx = e.clientX - press.x
    const dy = e.clientY - press.y
    if (Math.sqrt(dx * dx + dy * dy) <= TAP_SLOP) return
    press.tap = false
    drag.active = true
    // 锚点改用"这一刻"的目标位：按下到越过阈值之间形态可能已经动了，
    // 用旧快照会在这个瞬间产生一次位移跳变。
    for (let i = 0; i < DROP_COUNT; i += 1) drag.anchors[i].copy(drops[i].tp)
  }

  if (!drag.active) return

  // 位移按"固定景深"换算：指针被搬到相机前 depth 米处的那个平面上。
  // 不用水平面求交 —— 相机仰角一低（elev 最小 -0.02，机位几乎贴着地面），
  // 水平面的交点会跑到无穷远，灵敏度直接爆炸；固定景深永远稳。
  const ray = pointerRay(_pickDir)
  const ox = drag.ray0.x * drag.depth
  const oy = drag.ray0.y * drag.depth
  const oz = drag.ray0.z * drag.depth
  drag.delta.set(
    (ray.x * drag.depth - ox) * DRAG_GAIN,
    (ray.y * drag.depth - oy) * DRAG_GAIN * 0.72,   // 竖直方向收一点，免得一抬手就整体飘走
    (ray.z * drag.depth - oz) * DRAG_GAIN
  )
  const len = drag.delta.length()
  if (len > DRAG_MAX) drag.delta.multiplyScalar(DRAG_MAX / len)
}

function onPointerUp(e) {
  if (!pointer.down) return
  const wasTap = press.tap
  pointer.down = false
  press.tap = false
  drag.active = false
  renderer.domElement.releasePointerCapture?.(e.pointerId)
  if (wasTap) burstAt(press.point)
}

function onPointerCancel(e) {
  pointer.down = false
  press.tap = false
  drag.active = false
  renderer.domElement.releasePointerCapture?.(e.pointerId)
}

function onWheel(e) {
  e.preventDefault()
  const next = cam.tDist * (1 + e.deltaY * 0.0009)
  cam.tDist = clamp(2.6, 13.5, next)
}

function onKeyDown(e) {
  if (e.code === 'Space') {
    e.preventDefault()
    triggerCollapse()
  }
}

function bindEvents() {
  const el = renderer.domElement
  el.addEventListener('pointerdown', onPointerDown)
  el.addEventListener('pointermove', onPointerMove)
  el.addEventListener('pointercancel', onPointerCancel)
  el.addEventListener('wheel', onWheel, { passive: false })
  window.addEventListener('pointerup', onPointerUp)
  window.addEventListener('keydown', onKeyDown)
  resizeObserver = new ResizeObserver(() => {
    applySize()
    updateRippleCenter()
  })
  resizeObserver.observe(mountRef.value)
}

/* ------------------------------------------------------------
   渲染循环
   ------------------------------------------------------------ */

function animate() {
  rafId = requestAnimationFrame(animate)
  const dt = Math.min(clock.getDelta(), 1 / 30)   // 限幅，避免切标签页回来炸掉

  if (!paused.value) {
    simTime += dt

    // 炸开余威衰减：~1.6s 后黏性回到常态，飞散的液滴开始被"收"回来
    burstT *= Math.exp(-dt * 1.7)
    if (burstT < 0.002) burstT = 0

    // 阶段推进（按住期间冻结：拖拽中形态自己往前跑会把水银拽脱手）
    const cfg = PHASE_CFG[phase.value]
    if (!pointer.down) {
      phaseT += dt
      if (phaseT >= cfg.dur) advancePhase()
    }

    // 刚度 / 形态插值（各自独立的速率 —— 崩回时 k 比 formT 快得多，
    // 于是顺序是"先散架，再落地"）
    const c = PHASE_CFG[phase.value]
    springK += (c.k - springK) * Math.min(1, dt * c.kRate)
    formT += (c.form - formT) * Math.min(1, dt * c.formRate)

    morphShock *= Math.exp(-dt * 3.0)

    stepDrops(dt)
  }

  uploadDrops()
  uniforms.uTime.value = simTime
  uniforms.uShock.value = morphShock
  uniforms.uSelfRefl.value = selfRefl.value ? 1 : 0

  // 涟漪强度：静止时轻轻呼吸，拖拽时明显搅动，炸开瞬间冲击
  const targetRipple = (drag.active ? 0.044 : (pointer.down ? 0.030 : 0.012))
    + morphShock * 0.055
  rippleScale += (targetRipple - rippleScale) * Math.min(1, dt * 6)
  uniforms.uRippleScale.value = rippleScale

  // 相机平滑（弹簧式追随）
  const k = Math.min(1, dt * 4.2)
  cam.azim += (cam.tAzim - cam.azim) * k
  cam.elev += (cam.tElev - cam.elev) * k
  cam.dist += (cam.tDist - cam.dist) * k
  cam.targetY += (cam.tTargetY - cam.targetY) * k

  camTarget.set(0, cam.targetY, 0)
  camPos.set(
    Math.cos(cam.elev) * Math.sin(cam.azim) * cam.dist,
    cam.targetY + Math.sin(cam.elev) * cam.dist,
    Math.cos(cam.elev) * Math.cos(cam.azim) * cam.dist
  )
  camForward.copy(camTarget).sub(camPos).normalize()
  camRight.crossVectors(camForward, worldUp).normalize()
  camUp.crossVectors(camRight, camForward).normalize()
  // Three.js 的 Matrix3 是列主序；按行 set，让列恰好是 right / up / forward
  camMat.set(
    camRight.x, camUp.x, camForward.x,
    camRight.y, camUp.y, camForward.y,
    camRight.z, camUp.z, camForward.z
  )

  // 环境立方体贴图：低频重渲染（环境是静态几何，没必要每帧渲 6 面）
  if (!paused.value) {
    envTimer -= dt
    if (envTimer <= 0) {
      updateEnvMap()
      envTimer = ENV_REFRESH
    }
  }

  renderer.render(scene, camera)

  // 帧率统计 + 分辨率自适应
  frameCount += 1
  fpsAccum += dt
  if (resCooldown > 0) resCooldown -= dt
  if (fpsAccum >= 0.6) {
    const value = frameCount / fpsAccum
    fps.value = Math.round(value)
    if (resCooldown <= 0) {
      if (value < 38 && resScale > 0.45) {
        resScale = Math.max(0.45, resScale * 0.86)
        applySize()
        resCooldown = 2.0
      } else if (value > 57 && resScale < 0.95) {
        resScale = Math.min(0.95, resScale * 1.06)
        applySize()
        resCooldown = 2.0
      }
    }
    frameCount = 0
    fpsAccum = 0
  }
}

/* ------------------------------------------------------------
   交互
   ------------------------------------------------------------ */

// 选形态：滩 = 直接躺平；其余 = 立刻进"塑形"，之后自动 成型 → 崩回 → 躺平。
// 刻意不动 autoCycle —— 用户关掉循环后，一次表演结束就停在滩里。
function selectShape(index) {
  if (index === 0) {
    targetShape.value = 0
    setPhase(PHASE.POOL)
    applyShapeCamera(0, false)
    return
  }
  targetShape.value = index
  setPhase(PHASE.RISE)
  applyShapeCamera(index, false)
}

function triggerCollapse() {
  if (phase.value === PHASE.COLLAPSE) return
  setPhase(PHASE.COLLAPSE)
}

/* ------------------------------------------------------------
   单击炸开
   ------------------------------------------------------------
   和"崩回"的区别：崩回只是放开弹簧让它掉回原地，
   炸开是**在掉回去之前先把它甩出去**：

     1. springK 直接拍成 0、formT 直接拍成 0
        —— 弹簧瞬间断开，目标位立刻变回"滩"，站着的形态就地散架；不是插值，是切断。
     2. burstT = 1 把黏性压到 1/3、速度上限拉到 30，
        否则阻尼会在 0.2 秒内把冲量吃干净，一滴都飞不出去。
     3. 冲量沿"爆点 → 液滴"的径向给，越近越猛，再叠一点随机切向，
        免得飞散成一个过于工整的圆环。
     4. 之后交给 BURST 阶段：k 从 0 以 0.85/s 极慢地长回来，
        液滴先落地摊开，再被弹簧一点点收拢 → 慢慢结成一滩。
   ------------------------------------------------------------ */

function burstAt(point) {
  targetShape.value = 0
  springK = 0
  formT = 0
  burstT = 1
  morphShock = 1.0
  setPhase(PHASE.BURST)

  // 冲击环跟着爆点走（shader 里的 shock 环就是以 uRippleCenter 为圆心）
  uniforms.uRippleCenter.value.set(point.x, point.z)
  rippleScale = Math.max(rippleScale, 0.08)

  const dir = new THREE.Vector3()
  for (let i = 0; i < DROP_COUNT; i += 1) {
    const d = drops[i]
    dir.subVectors(d.p, point)
    const dist = Math.max(dir.length(), 0.12)
    dir.multiplyScalar(1 / dist)

    const near = clamp01(1.0 - dist / 3.0)          // 近处受压最狠
    const power = 0.70 + near * 0.60 + Math.random() * 0.30

    d.v.addScaledVector(dir, 10.8 * power)
    d.v.x += (Math.random() - 0.5) * 2.6            // 随机切向抖动
    d.v.z += (Math.random() - 0.5) * 2.6
    d.v.y += 1.6 + near * 4.2 + Math.random() * 1.8
  }
}

function toggleAuto() {
  autoCycle.value = !autoCycle.value
  if (autoCycle.value && phase.value === PHASE.POOL) phaseT = 0
}

function resetView() {
  cam.tAzim = 0
  cam.tElev = 0.20
  cam.tDist = SHAPES[targetShapeIndex()].dist
  morphShock = 0.6
}

/* ------------------------------------------------------------
   释放
   ------------------------------------------------------------ */

onBeforeUnmount(() => {
  window.cancelAnimationFrame(rafId)
  window.removeEventListener('pointerup', onPointerUp)
  window.removeEventListener('keydown', onKeyDown)
  if (resizeObserver) resizeObserver.disconnect()
  if (renderer) {
    const el = renderer.domElement
    el.removeEventListener('pointerdown', onPointerDown)
    el.removeEventListener('pointermove', onPointerMove)
    el.removeEventListener('pointercancel', onPointerCancel)
    el.removeEventListener('wheel', onWheel)
  }
  if (quadMesh) quadMesh.geometry.dispose()
  if (material) material.dispose()
  disposeEnv(envGroup)
  envGroup = null
  if (cubeRT) cubeRT.dispose()
  cubeRT = null
  cubeCam = null
  envScene = null
  if (renderer) {
    renderer.dispose()
    renderer.domElement.remove()
  }
  // 清空引用：组件被重新挂载（切路由回来）时，animate 会拿到已销毁的材质
  material = null
  uniforms = null
  quadMesh = null
  drops.length = 0
})
</script>

<style scoped>
.lm-view {
  position: relative;
  width: 100%;
  height: 100%;
  min-height: 560px;
  overflow: hidden;
  background: radial-gradient(ellipse 80% 60% at 50% 35%, #0a1020 0%, #05070d 55%, #020306 100%);
}

.lm-canvas {
  position: absolute;
  inset: 0;
  display: block;
  touch-action: none;
  cursor: crosshair;
}

.lm-header {
  position: absolute;
  top: 28px;
  left: 32px;
  z-index: 2;
  pointer-events: none;
}

.lm-eyebrow {
  display: block;
  font-size: 11px;
  letter-spacing: 3px;
  text-transform: uppercase;
  color: #6a7fa8;
}

.lm-title {
  margin: 6px 0 4px;
  font-size: 26px;
  font-weight: 600;
  color: #e6eefc;
  text-shadow: 0 0 18px rgba(150, 190, 255, 0.35);
}

.lm-desc {
  margin: 0;
  font-size: 13px;
  color: #7d90b5;
}

.lm-toolbar {
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

.lm-button {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 14px;
  border: 1px solid rgba(150, 190, 255, 0.32);
  border-radius: 8px;
  background: rgba(10, 16, 32, 0.6);
  color: #b9c9e8;
  font-size: 13px;
  cursor: pointer;
  transition: all 0.2s ease;
}

.lm-button:hover {
  border-color: rgba(150, 190, 255, 0.7);
  color: #eef5ff;
}

.lm-button.active {
  border-color: #9fc4ff;
  background: rgba(150, 190, 255, 0.18);
  color: #f2f7ff;
  box-shadow: 0 0 14px rgba(150, 190, 255, 0.32);
}

.lm-button-accent {
  border-color: rgba(255, 150, 150, 0.4);
  color: #ffc9c9;
}

.lm-button-accent:hover {
  border-color: rgba(255, 150, 150, 0.85);
  color: #fff0f0;
  box-shadow: 0 0 14px rgba(255, 130, 130, 0.28);
}

.lm-button-key {
  font-size: 11px;
  padding: 1px 6px;
  border: 1px solid rgba(150, 190, 255, 0.4);
  border-radius: 4px;
  color: #9dbde8;
}

.lm-panel {
  position: absolute;
  right: 32px;
  bottom: 28px;
  z-index: 2;
  display: flex;
  flex-direction: column;
  align-items: flex-end;
  gap: 8px;
  padding: 12px 14px;
  border: 1px solid rgba(150, 190, 255, 0.2);
  border-radius: 10px;
  background: rgba(8, 13, 24, 0.62);
  backdrop-filter: blur(8px);
}

.lm-group {
  display: flex;
  align-items: center;
  gap: 6px;
}

.lm-group-label {
  width: 30px;
  font-size: 11px;
  color: #6a7fa8;
}

.lm-chip {
  padding: 4px 10px;
  border: 1px solid rgba(150, 190, 255, 0.26);
  border-radius: 6px;
  background: rgba(10, 16, 32, 0.5);
  color: #a8bcdd;
  font-size: 12px;
  cursor: pointer;
  transition: all 0.2s ease;
}

.lm-chip:hover {
  border-color: rgba(150, 190, 255, 0.6);
  color: #eef5ff;
}

.lm-chip.active {
  border-color: #9fc4ff;
  background: rgba(150, 190, 255, 0.18);
  color: #f2f7ff;
}

.lm-hud {
  position: absolute;
  left: 32px;
  bottom: 28px;
  z-index: 2;
  /* ⚠️ 必须放行指针：HUD 压在画布上，不放行会吃掉左下角一整片拖拽区 */
  pointer-events: none;
  display: flex;
  flex-direction: column;
  gap: 6px;
  padding: 12px 16px;
  border: 1px solid rgba(150, 190, 255, 0.2);
  border-radius: 10px;
  background: rgba(8, 13, 24, 0.62);
  font-size: 12px;
  backdrop-filter: blur(8px);
}

.lm-hud-row {
  display: flex;
  justify-content: space-between;
  gap: 18px;
}

.lm-hud-k {
  color: #6a7fa8;
}

.lm-hud-v {
  color: #b9c9e8;
  font-variant-numeric: tabular-nums;
}
</style>
