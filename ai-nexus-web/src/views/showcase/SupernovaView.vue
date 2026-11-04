<template>
  <div ref="mountRef" class="sn-view">
    <canvas ref="canvasRef" class="sn-canvas"></canvas>

    <!-- 黑幕：坍缩到静默阶段把画面压到几乎全黑，为爆发蓄势 -->
    <div class="sn-dim" :style="{ opacity: dimOpacity }" aria-hidden="true"></div>
    <!-- 白光：爆发瞬间炸满全屏。必须 pointer-events:none，否则会挡掉所有鼠标交互 -->
    <div class="sn-flash" :style="{ opacity: flashOpacity }" aria-hidden="true"></div>

    <div class="sn-header">
      <span class="sn-eyebrow">Supernova · 恒星死亡全程</span>
      <h1 class="sn-title">超新星爆发</h1>
      <p class="sn-desc">{{ hintText }}</p>
    </div>

    <div class="sn-toolbar">
      <button type="button" class="sn-button" :class="{ active: !playing }" @click="togglePlay">
        <span class="sn-button-key">播放</span>
        <span>{{ playing ? '暂停' : '继续' }}</span>
      </button>
      <button type="button" class="sn-button" @click="restart">
        <span class="sn-button-key">重播</span>
        <span>从头</span>
      </button>
      <button type="button" class="sn-button" @click="skipToBlast">
        <span class="sn-button-key">引爆</span>
        <span>直奔爆发</span>
      </button>
      <button type="button" class="sn-button" :class="{ active: speed !== 1 }" @click="cycleSpeed">
        <span class="sn-button-key">速度</span>
        <span>{{ speedLabel }}</span>
      </button>
      <button type="button" class="sn-button" :class="{ active: audioMode === 'mic' }" @click="toggleMic">
        <span class="sn-button-key">声音</span>
        <span>{{ audioMode === 'mic' ? '麦克风驱动中' : '接麦克风' }}</span>
      </button>
      <button type="button" class="sn-button" :class="{ active: audioMode === 'file' }" @click="pickFile">
        <span class="sn-button-key">声音</span>
        <span>{{ audioMode === 'file' ? '停止播放' : '播放音频文件' }}</span>
      </button>
      <button v-if="audioMode === 'file'" type="button" class="sn-button" :class="{ active: !musicPlaying }" @click="toggleMusic">
        <span class="sn-button-key">音乐</span>
        <span>{{ musicPlaying ? '暂停' : '继续' }}</span>
      </button>
      <button type="button" class="sn-button" @click="resetView">
        <span class="sn-button-key">视角</span>
        <span>复位</span>
      </button>
      <button type="button" class="sn-button" @click="toggleFullscreen">
        <span class="sn-button-key">显示</span>
        <span>全屏</span>
      </button>
    </div>

    <!-- 音频输入：文件模式复用这个隐藏 player；mic 模式只用 getUserMedia -->
    <audio ref="playerRef" class="sn-audio" loop></audio>
    <input ref="fileInputRef" type="file" accept="audio/*" class="sn-file-input" @change="onFileChange" />
    <div v-if="toast" class="sn-toast">{{ toast }}</div>

    <!-- 分镜字幕：居中偏下，随节拍淡入淡出 -->
    <div class="sn-caption" :style="{ opacity: captionOpacity }" aria-hidden="true">
      <span class="sn-caption-title">{{ captionTitle }}</span>
      <span class="sn-caption-sub">{{ captionSub }}</span>
    </div>

    <div class="sn-hud">
      <span class="sn-hud-row"><span class="sn-hud-k">阶段</span><span class="sn-hud-v">{{ stageName }}</span></span>
      <span class="sn-hud-row"><span class="sn-hud-k">时间</span><span class="sn-hud-v">{{ timeText }}</span></span>
      <span class="sn-hud-row"><span class="sn-hud-k">恒星半径</span><span class="sn-hud-v">{{ starRadiusText }}</span></span>
      <span class="sn-hud-row"><span class="sn-hud-k">冲击波</span><span class="sn-hud-v">{{ shockText }}</span></span>
      <span class="sn-hud-row"><span class="sn-hud-k">声音</span><span class="sn-hud-v">{{ audioText }}</span></span>
      <span class="sn-hud-row"><span class="sn-hud-k">帧率</span><span class="sn-hud-v">{{ fps }}</span></span>
    </div>
  </div>
</template>

<script setup>
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import * as THREE from 'three'
import { EffectComposer } from 'three/examples/jsm/postprocessing/EffectComposer.js'
import { RenderPass } from 'three/examples/jsm/postprocessing/RenderPass.js'
import { UnrealBloomPass } from 'three/examples/jsm/postprocessing/UnrealBloomPass.js'
import { ShaderPass } from 'three/examples/jsm/postprocessing/ShaderPass.js'
import { FXAAShader } from 'three/examples/jsm/shaders/FXAAShader.js'

/* ------------------------------------------------------------------
 * 超新星爆发 · 44 秒一轮的完整演出
 *
 * 五拍（时间轴见 BEATS）：
 *   1 主序膨胀  0 – 8s     恒星胀成红巨星，表面翻涌，物质被扯成丝抛出
 *   2 坍缩      8 – 12s    核心急剧收缩，画面跟着压暗
 *   3 静默      12 – 12.6s 几乎全黑，只剩一个亮点在抖 —— 整段演出的"呼吸"
 *   4 爆发      12.6 – 18s 白光炸满全屏，冲击波球面扩散，点亮尘埃壳层
 *   5 余烬      18 – 44s   冲击波淡出，抛射物冷却成星云
 *
 * ⚠️ 第 3 → 第 4 拍的落差是全部价值所在。没有那 0.6 秒的"黑"，
 *    后面的白光就只是"亮"，不是"炸"。调参时不要把这个对比度改平。
 *
 * ⚠️ 材质一律 ShaderMaterial，不用 RawShaderMaterial（后者不注入 position，
 *    漏声明就整页画不出像素）。代价是按 GLSL ES 3.00 编译，
 *    **变量名不能撞保留字**（active / sample / filter / input / output / smooth ...）。
 * ------------------------------------------------------------------ */

const CYCLE = 44
const T_EXPAND_END = 8
const T_COLLAPSE_END = 12
const T_SILENCE_END = 12.6
const T_BLAST_END = 18

const STAR_RADIUS_START = 3.2
const STAR_RADIUS_PEAK = 4.7
const STAR_RADIUS_MIN = 0.38
// ⚠️ 尘埃壳层半径必须与"默认机位 + 冲击波行程"一起定，三者是耦合的：
//    · 默认机位 44、fov 46 → 竖直半屏 18.7、水平半屏 33.2
//    · 壳层水平半径最大 23.5 → 留 9.7 余量，不会被裁掉
//    · 冲击波最大 42 < 44 → 相机始终在冲击波外面，不会突然被"淹没"
//    之前壳层取 25、机位取 34（水平半屏仅 25.6），光环正好卡在画面边缘。
const DUST_BASE_RADIUS = 19
const DUST_RADIAL_SPREAD = 2.8
const DUST_HEIGHT = 2.0
const CAMERA_DISTANCE_DEFAULT = 44

const STAR_COUNT = 2200
const EJECTA_COUNT = 2600
const DUST_COUNT = 22000
const NEBULA_COUNT = 3200

const MSAA_SAMPLES = 4

const SPEED_STEPS = [0.5, 1, 1.5, 2]

/* ---------------- GLSL 里的 3D 值噪声（恒星表面颗粒） ---------------- */

const GLSL_NOISE = `
  float hash31(vec3 p) {
    p = fract(p * 0.3183099 + vec3(0.1, 0.2, 0.3));
    p *= 17.0;
    return fract(p.x * p.y * p.z * (p.x + p.y + p.z));
  }
  float vnoise3(vec3 x) {
    vec3 i = floor(x);
    vec3 f = fract(x);
    f = f * f * (3.0 - 2.0 * f);
    return mix(
      mix(mix(hash31(i + vec3(0.0, 0.0, 0.0)), hash31(i + vec3(1.0, 0.0, 0.0)), f.x),
          mix(hash31(i + vec3(0.0, 1.0, 0.0)), hash31(i + vec3(1.0, 1.0, 0.0)), f.x), f.y),
      mix(mix(hash31(i + vec3(0.0, 0.0, 1.0)), hash31(i + vec3(1.0, 0.0, 1.0)), f.x),
          mix(hash31(i + vec3(0.0, 1.0, 1.0)), hash31(i + vec3(1.0, 1.0, 1.0)), f.x), f.y), f.z);
  }
  float fbm3(vec3 p) {
    float acc = 0.0;
    float amp = 0.5;
    for (int k = 0; k < 5; k++) {
      acc += amp * vnoise3(p);
      p *= 2.03;
      amp *= 0.5;
    }
    return acc;
  }`

/* ---------------- 恒星本体 ---------------- */

const STAR_VERT = `
  varying vec3 vLocal;
  varying vec3 vNormalV;
  varying vec3 vPosV;
  void main() {
    vLocal = position;
    vNormalV = normalize(normalMatrix * normal);
    vec4 mv = modelViewMatrix * vec4(position, 1.0);
    vPosV = mv.xyz;
    gl_Position = projectionMatrix * mv;
  }`

const STAR_FRAG = `
  precision highp float;
  uniform float uTime;
  uniform float uHeat;
  uniform float uCollapse;
  uniform vec3 uColorCool;
  uniform vec3 uColorHot;
  varying vec3 vLocal;
  varying vec3 vNormalV;
  varying vec3 vPosV;
  ${GLSL_NOISE}
  void main() {
    // 两层噪声叠加：低频当对流元胞，高频当米粒组织
    float n1 = fbm3(vLocal * 4.2 + vec3(0.0, uTime * 0.32, 0.0));
    float n2 = fbm3(vLocal * 11.0 - vec3(uTime * 0.21, 0.0, uTime * 0.17));
    float grain = n1 * 0.68 + n2 * 0.32;

    float cells = smoothstep(0.30, 0.76, grain);
    vec3 col = mix(uColorCool, uColorHot, clamp(cells * 0.85 + uHeat * 0.55, 0.0, 1.0));

    // 临边昏暗 + 边缘增亮，让球体看起来是"发光的气体"而不是"塑料球"。
    // ⚠️ 增亮系数别给大：球面本身已经很亮，再叠加会整片过曝、泛光糊屏。
    vec3 viewDir = normalize(-vPosV);
    float ndv = clamp(dot(normalize(vNormalV), viewDir), 0.0, 1.0);
    float limb = pow(1.0 - ndv, 2.6);
    col += uColorHot * limb * 0.42;

    // 坍缩末期整体过曝成白
    col = mix(col, vec3(1.0), uCollapse * 0.6);
    // ⚠️ 再乘一个 >1 的增益：坍缩成致密残核后，星体只剩几个像素，
    //    而黑幕会把它压到 0.1 倍。不推过曝，静默阶段就真的什么都看不见了
    //    —— 而"黑幕里唯一那个刺眼的点"正是整段演出的呼吸。
    col *= 1.0 + uCollapse * 1.8;

    gl_FragColor = vec4(col, 1.0);
  }`

/* ---------------- 通用点精灵（星空 / 抛射物 / 尘埃 / 星云） ---------------- */

// 抛射物：position 存的是单位方向，半径在着色器里按进度推出去
const EJECTA_VERT = `
  attribute float aSeed;
  attribute float aBirth;
  attribute vec3 aColor;
  uniform float uProgress;
  uniform float uOpacity;
  uniform float uTime;
  uniform float uSize;
  uniform float uPixelRatio;
  varying float vAlpha;
  varying vec3 vColor;
  void main() {
    float age = clamp((uProgress - aBirth) / max(0.08, 1.0 - aBirth), 0.0, 1.0);
    float radius = 3.0 + age * 16.0;
    vec3 dir = normalize(position);
    // 加一点切向旋转，抛射物会拧成螺旋丝而不是笔直的辐条
    float ang = age * 1.6 * (0.55 + aSeed);
    float ca = cos(ang);
    float sa = sin(ang);
    vec3 p = vec3(dir.x * ca - dir.z * sa, dir.y, dir.x * sa + dir.z * ca) * radius;

    // ⚠️ 加色混合下，2600 颗粒子沿视线会重叠好几层，
    //    单颗 alpha 给到 1 会直接把画面叠爆。
    //    系数 0.15 是配"实心核心（uSoft=0.26）+ uSize 2.0 + 12 簇/抖动 0.03"标定的：
    //    实心核心让点内平均能量涨了约 2.89 倍，收簇又让重叠变密（×1.43），
    //    所以系数从 0.22 一路降到 0.15。
    //    屏幕峰值亮度 0.53；再乘音频增益最坏 ×1.82 是 0.97，仍不截断。
    vAlpha = smoothstep(0.0, 0.08, age) * (1.0 - smoothstep(0.72, 1.0, age)) * uOpacity * 0.15;
    vColor = aColor;

    vec4 mv = modelViewMatrix * vec4(p, 1.0);
    gl_PointSize = uSize * (0.55 + aSeed * 0.9) * uPixelRatio * (300.0 / -mv.z);
    gl_Position = projectionMatrix * mv;
  }`

const EJECTA_FRAG = `
  precision highp float;
  // 核心实心半径占比：0 = 整颗都软（云/雾用），0.5 = 从圆心到边缘才开始柔化。
  // ⚠️ 曾经写死 smoothstep(0.0, 0.5, r) —— 从圆心就衰减，整颗都是虚的，
  //    抛射物 2600 颗叠起来就是一团糊斑。尘埃环那种"云"要软边，
  //    但抛射物是"物质颗粒"，必须有实心核心才看得清。
  uniform float uSoft;
  varying float vAlpha;
  varying vec3 vColor;
  void main() {
    vec2 p = gl_PointCoord - 0.5;
    float r = length(p);
    if (r > 0.5) discard;
    float soft = 1.0 - smoothstep(uSoft, 0.5, r);
    float a = soft * vAlpha;
    gl_FragColor = vec4(vColor * a, a);
  }`

// 尘埃壳层：核心效果 —— 冲击波扫过时整层被点亮，形成一圈光环
const DUST_VERT = `
  attribute float aSeed;
  attribute float aRadius;
  attribute vec3 aColor;
  uniform float uShockRadius;
  uniform float uShockWidth;
  uniform float uIntensity;
  uniform float uShellGlow;
  // 音频增益（0~1）：只做小幅叠加。
  // ⚠️ 加色亮度按 alpha 平方缩放，系数给大了会直接过曝糊屏，
  //    所以这里只让峰值 alpha 从 0.12 抬到 0.15 左右（亮度约 ×1.5）。
  uniform float uAudioBoost;
  uniform float uTime;
  uniform float uSize;
  uniform float uPixelRatio;
  varying float vAlpha;
  varying vec3 vColor;
  void main() {
    float d = aRadius - uShockRadius;
    // 高斯包络：只有落在冲击波锋面附近的尘埃才被点亮
    float lit = exp(-(d * d) / (uShockWidth * uShockWidth));
    // 尾迹：只保留锋面**之后很短一段**的余辉。
    // ⚠️ 曾经写成"凡在锋面之后一律给常数 0.05" —— 那等于把整个内层球壳均匀点亮，
    //    从正面看就是一个和光环一样亮的实心圆盘，光环彻底被淹没。
    float behind = max(0.0, uShockRadius - aRadius);
    float wake = exp(-behind / (uShockWidth * 2.0)) * 0.05;
    // ⚠️ 22000 颗尘埃在锋面上会叠出好几层，单颗系数必须很小。
    //    这里曾经用 1.35，结果整块画布被加成纯白 —— 数值验证只算了单颗亮度，
    //    没算沿视线累加的总量，所以漏掉了。
    float base = 0.004;
    // uShellGlow：冲击波远去后整层冷却余晖，只在余烬阶段升起
    float glow = (base + lit * (0.12 + uAudioBoost * 0.03) + wake) * uIntensity
               + uShellGlow + uAudioBoost * 0.015;

    vAlpha = glow;
    vColor = aColor;

    vec4 mv = modelViewMatrix * vec4(position, 1.0);
    float twinkle = 0.82 + 0.18 * sin(uTime * 2.0 + aSeed * 50.0);
    gl_PointSize = uSize * (0.45 + glow * 1.7) * twinkle * uPixelRatio * (300.0 / -mv.z);
    gl_Position = projectionMatrix * mv;
  }`

const DUST_FRAG = EJECTA_FRAG

// 星云 / 星空：只要一个透明度
const SOFT_VERT = `
  attribute float aSeed;
  uniform float uTime;
  uniform float uSize;
  uniform float uPixelRatio;
  uniform float uOpacity;
  uniform vec3 uColor;
  varying float vAlpha;
  varying vec3 vColor;
  void main() {
    vColor = uColor;
    vAlpha = uOpacity * (0.7 + 0.3 * sin(uTime * 0.9 + aSeed * 37.0));
    vec4 mv = modelViewMatrix * vec4(position, 1.0);
    gl_PointSize = uSize * uPixelRatio * (300.0 / -mv.z);
    gl_Position = projectionMatrix * mv;
  }`

const SOFT_FRAG = EJECTA_FRAG

/* ---------------- 冲击波壳层（菲涅尔球） ---------------- */

const SHELL_VERT = `
  varying vec3 vNormalV;
  varying vec3 vPosV;
  void main() {
    vNormalV = normalize(normalMatrix * normal);
    vec4 mv = modelViewMatrix * vec4(position, 1.0);
    vPosV = mv.xyz;
    gl_Position = projectionMatrix * mv;
  }`

const SHELL_FRAG = `
  precision highp float;
  uniform vec3 uColor;
  uniform float uIntensity;
  uniform float uPower;
  varying vec3 vNormalV;
  varying vec3 vPosV;
  void main() {
    vec3 viewDir = normalize(-vPosV);
    // DoubleSide 渲染，取 abs 让前后两面都发光，球壳看起来更"厚"
    float ndv = abs(dot(normalize(vNormalV), viewDir));
    float rim = pow(1.0 - clamp(ndv, 0.0, 1.0), uPower);
    float a = rim * uIntensity;
    gl_FragColor = vec4(uColor * a, a);
  }`

/* ---------------- 缓动 ---------------- */

function clamp01(x) {
  return x < 0 ? 0 : x > 1 ? 1 : x
}
function easeInOut(x) {
  const c = clamp01(x)
  return c < 0.5 ? 2 * c * c : 1 - Math.pow(-2 * c + 2, 2) / 2
}
function easeInCubic(x) {
  const c = clamp01(x)
  return c * c * c
}
function easeOutCubic(x) {
  return 1 - Math.pow(1 - clamp01(x), 3)
}

/* ---------------- 分镜定义 ---------------- */

const CAPTIONS = {
  expand: { title: '主序膨胀', sub: '恒星正在胀成红巨星，物质被扯成丝抛出去' },
  collapse: { title: '坍缩', sub: '核心在急剧收缩，光芒被抽干' },
  silence: { title: '静默', sub: '' },
  blast: { title: '爆发', sub: '' },
  remnant: { title: '余烬', sub: '冲击波扫过尘埃壳层，把它点亮成一圈光环' }
}

/* ---------------- 组件状态 ---------------- */

const mountRef = ref(null)
const canvasRef = ref(null)
const fps = ref(0)
const stageName = ref('主序膨胀')
const timeText = ref('0.0 s')
const starRadiusText = ref('—')
const shockText = ref('—')
const dimOpacity = ref(0)
const flashOpacity = ref(0)
const captionTitle = ref(CAPTIONS.expand.title)
const captionSub = ref(CAPTIONS.expand.sub)
const captionOpacity = ref(1)
const playing = ref(true)
const speedIndex = ref(1)
const audioMode = ref('none')
const musicPlaying = ref(false)
const toast = ref('')
const audioText = ref('未接入')
const playerRef = ref(null)
const fileInputRef = ref(null)

const hintText = computed(() => {
  if (audioMode.value === 'mic') return '声音越大，尘埃环越亮、画面抖得越狠 · 点击画面立即引爆'
  if (audioMode.value === 'file') return '音乐驱动中 · 点击画面立即引爆 · 余烬阶段点击则重播'
  return '拖拽环绕 · 滚轮推拉 · 点击画面立即引爆 · 44 秒一轮完整演出'
})
const speed = computed(() => SPEED_STEPS[speedIndex.value])
const speedLabel = computed(() => `${SPEED_STEPS[speedIndex.value]}×`)

/* ---------------- 运行时引用 ---------------- */
// 全部挂在模块级闭包变量上，onBeforeUnmount 统一释放
// ---- 音频 ----
// 复用项目里已有的约定（见 FractalTunnelView / BlackHoleView）：
// AudioContext + AnalyserNode，支持麦克风与音频文件两种输入，
// 未接入时走"氛围模式"用合成律动驱动，保证没声音也有呼吸感。
let actx = null
let analyser = null
let freqData = null
let micStream = null
let micSource = null
let fileSource = null
let fileURL = null
let toastTimer = 0
const audio = { bass: 0, mid: 0, treble: 0, level: 0 }

let renderer = null
let scene = null
let camera = null
let composer = null
let bloomPass = null
let fxaaPass = null
let starMesh = null
let starMaterial = null
let coronaMesh = null
let coronaMaterial = null
let remnantMesh = null
let remnantMaterial = null
let ejectaPoints = null
let ejectaMaterial = null
let dustPoints = null
let dustMaterial = null
let nebulaPoints = null
let nebulaMaterial = null
let starsPoints = null
let starsMaterial = null
let shockMesh = null
let shockMaterial = null
let shockOuterMesh = null
let shockOuterMaterial = null
let rafId = 0
let resizeObserver = null
let destroyed = false

const clock = new THREE.Clock()
const unitSphere = new THREE.SphereGeometry(1, 128, 96)

// 播放时间轴
let playTime = 0

// 演出状态（每帧由 updateShow() 重算）
const show = {
  starRadius: STAR_RADIUS_START,
  starHeat: 0,
  starCollapse: 0,
  starVisible: true,
  remnantVisible: false,
  shockRadius: -1,
  shockIntensity: 0,
  shellGlow: 0,
  ejectaProgress: 0,
  ejectaOpacity: 1,
  nebulaOpacity: 0
}

// 相机：球坐标轨道 + 阻尼
let yaw = 0.6
let pitch = 0.5
let targetYaw = 0.6
let targetPitch = 0.5
let distance = CAMERA_DISTANCE_DEFAULT
let targetDistance = CAMERA_DISTANCE_DEFAULT
let dragging = false
let pointerDownX = 0
let pointerDownY = 0
let pointerMoved = 0

/* ---------------- 构建 ---------------- */

function buildStars() {
  const positions = new Float32Array(STAR_COUNT * 3)
  const seeds = new Float32Array(STAR_COUNT)
  for (let i = 0; i < STAR_COUNT; i += 1) {
    const u = Math.random() * 2 - 1
    const theta = Math.random() * Math.PI * 2
    const ringR = Math.sqrt(Math.max(0, 1 - u * u))
    const radius = 150 + Math.random() * 130
    positions[i * 3] = Math.cos(theta) * ringR * radius
    positions[i * 3 + 1] = u * radius
    positions[i * 3 + 2] = Math.sin(theta) * ringR * radius
    seeds[i] = Math.random()
  }
  const geo = new THREE.BufferGeometry()
  geo.setAttribute('position', new THREE.BufferAttribute(positions, 3))
  geo.setAttribute('aSeed', new THREE.BufferAttribute(seeds, 1))

  starsMaterial = new THREE.ShaderMaterial({
    uniforms: {
      uTime: { value: 0 },
      uSize: { value: 1.3 },
      uPixelRatio: { value: 1 },
      uOpacity: { value: 0.85 },
      uColor: { value: new THREE.Color('#cfe4ff') },
      // 星点尺寸只有 1~3px，软边影响可忽略，保持原标定
      uSoft: { value: 0.0 }
    },
    vertexShader: SOFT_VERT,
    fragmentShader: SOFT_FRAG,
    transparent: true,
    depthWrite: false,
    blending: THREE.AdditiveBlending
  })
  starsPoints = new THREE.Points(geo, starsMaterial)
  starsPoints.frustumCulled = false
  scene.add(starsPoints)
}

function buildStar() {
  starMaterial = new THREE.ShaderMaterial({
    uniforms: {
      uTime: { value: 0 },
      uHeat: { value: 0 },
      uCollapse: { value: 0 },
      // 底色压暗：红巨星本体是"橙红底 + 亮米粒"，不是一颗白球。
      // 底色暗下来，泛光才会只挑亮米粒和临边溢出，而不是糊满整屏。
      uColorCool: { value: new THREE.Color('#8f2f06') },
      uColorHot: { value: new THREE.Color('#ffc46b') }
    },
    vertexShader: STAR_VERT,
    fragmentShader: STAR_FRAG
  })
  starMesh = new THREE.Mesh(unitSphere, starMaterial)
  scene.add(starMesh)

  // 日冕：菲涅尔壳，贴着本体（半径只放大 1.06 倍，否则会变成空心玻璃球）
  coronaMaterial = new THREE.ShaderMaterial({
    uniforms: {
      uColor: { value: new THREE.Color('#ff9d3c') },
      uIntensity: { value: 0.9 },
      uPower: { value: 2.4 }
    },
    vertexShader: SHELL_VERT,
    fragmentShader: SHELL_FRAG,
    transparent: true,
    depthWrite: false,
    side: THREE.BackSide,
    blending: THREE.AdditiveBlending
  })
  coronaMesh = new THREE.Mesh(unitSphere, coronaMaterial)
  scene.add(coronaMesh)

  // 坍缩后留下的致密残核：极小的超亮点，靠泛光撑成一颗刺眼的星
  remnantMaterial = new THREE.ShaderMaterial({
    uniforms: {
      uColor: { value: new THREE.Color('#eaf6ff') },
      uIntensity: { value: 1.4 },
      uPower: { value: 1.5 }
    },
    vertexShader: SHELL_VERT,
    fragmentShader: SHELL_FRAG,
    transparent: true,
    depthWrite: false,
    side: THREE.DoubleSide,
    blending: THREE.AdditiveBlending
  })
  remnantMesh = new THREE.Mesh(unitSphere, remnantMaterial)
  remnantMesh.visible = false
  scene.add(remnantMesh)
}

function buildEjecta() {
  const positions = new Float32Array(EJECTA_COUNT * 3)
  const seeds = new Float32Array(EJECTA_COUNT)
  const births = new Float32Array(EJECTA_COUNT)
  const colors = new Float32Array(EJECTA_COUNT * 3)

  const palette = [
    new THREE.Color('#ffd9a0'),
    new THREE.Color('#ff9d5c'),
    new THREE.Color('#ff6b6b'),
    new THREE.Color('#ffc46b')
  ]

  // ⚠️ 抛射物必须**成团**才有"丝状喷流"的样子。
  //    之前每颗独立随机方向 → 均匀铺满整个球壳 → 从正面看就是一坨均匀圆盘，
  //    像电视雪花，完全没有喷射感。改成先抽 ~110 个喷流方向，粒子挂在方向上做小抖动。
  // ⚠️ 簇数决定"丝"的观感：40 簇时每簇 65 颗，铺满球面成了"星尘"；
  //    12 簇（每簇 217 颗）才收得成明显的丝状喷流。抖动同步收到 0.03。
  const CLUMPS = 12
  const clumpDirs = []
  for (let c = 0; c < CLUMPS; c += 1) {
    const u = Math.random() * 2 - 1
    const theta = Math.random() * Math.PI * 2
    const ringR = Math.sqrt(Math.max(0, 1 - u * u))
    clumpDirs.push([Math.cos(theta) * ringR, u, Math.sin(theta) * ringR])
  }

  for (let i = 0; i < EJECTA_COUNT; i += 1) {
    const base = clumpDirs[i % CLUMPS]
    const jitter = 0.03
    let dx = base[0] + (Math.random() - 0.5) * jitter
    let dy = base[1] + (Math.random() - 0.5) * jitter
    let dz = base[2] + (Math.random() - 0.5) * jitter
    const len = Math.hypot(dx, dy, dz) || 1
    dx /= len
    dy /= len
    dz /= len
    positions[i * 3] = dx
    positions[i * 3 + 1] = dy
    positions[i * 3 + 2] = dz
    seeds[i] = Math.random()
    // 出生时刻做成"前密后疏"，抛射看起来是持续喷发而不是一次性炸开
    births[i] = Math.pow(Math.random(), 0.65)
    const c = palette[Math.floor(Math.random() * palette.length)]
    colors[i * 3] = c.r
    colors[i * 3 + 1] = c.g
    colors[i * 3 + 2] = c.b
  }

  const geo = new THREE.BufferGeometry()
  geo.setAttribute('position', new THREE.BufferAttribute(positions, 3))
  geo.setAttribute('aSeed', new THREE.BufferAttribute(seeds, 1))
  geo.setAttribute('aBirth', new THREE.BufferAttribute(births, 1))
  geo.setAttribute('aColor', new THREE.BufferAttribute(colors, 3))

  ejectaMaterial = new THREE.ShaderMaterial({
    uniforms: {
      uProgress: { value: 0 },
      uOpacity: { value: 1 },
      uTime: { value: 0 },
      uSize: { value: 2.0 },
      uPixelRatio: { value: 1 },
      // 抛射物是"被抛出去的物质颗粒"，必须有实心核心，否则 2600 颗叠成一团糊斑
      uSoft: { value: 0.26 }
    },
    vertexShader: EJECTA_VERT,
    fragmentShader: EJECTA_FRAG,
    transparent: true,
    depthWrite: false,
    blending: THREE.AdditiveBlending
  })
  ejectaPoints = new THREE.Points(geo, ejectaMaterial)
  ejectaPoints.frustumCulled = false
  scene.add(ejectaPoints)
}

function buildDust() {
  const positions = new Float32Array(DUST_COUNT * 3)
  const seeds = new Float32Array(DUST_COUNT)
  const radii = new Float32Array(DUST_COUNT)
  const colors = new Float32Array(DUST_COUNT * 3)

  // 环的颜色要够饱和，加色混合后偏暖白才好看
  const palette = [
    new THREE.Color('#ffb347'),
    new THREE.Color('#7c9dff'),
    new THREE.Color('#38d0ff'),
    new THREE.Color('#ff7b8a')
  ]

  for (let i = 0; i < DUST_COUNT; i += 1) {
    // ⚠️ 尘埃必须是**环面**，不能是球壳。
    //    球壳（哪怕压扁成椭球）投影到屏幕仍然是一个**实心圆盘** ——
    //    因为球面的近半球会覆盖整个投影面。实测径向剖面：中心 0.110、锋面 0.168，
    //    对比度只有 1.5 倍，整屏就是一坨均匀斑点，光环根本立不住。
    //    真实遗迹（如 SN 1987A）之所以是环，是因为尘埃集中在赤道面上。
    const phi = Math.random() * Math.PI * 2
    const rho = DUST_BASE_RADIUS + (Math.random() - 0.5) * DUST_RADIAL_SPREAD * 2
    const py = (Math.random() - 0.5) * DUST_HEIGHT
    const px = Math.cos(phi) * rho
    const pz = Math.sin(phi) * rho
    positions[i * 3] = px
    positions[i * 3 + 1] = py
    positions[i * 3 + 2] = pz
    radii[i] = Math.hypot(px, py, pz)
    seeds[i] = Math.random()
    const c = palette[Math.floor(Math.random() * palette.length)]
    colors[i * 3] = c.r
    colors[i * 3 + 1] = c.g
    colors[i * 3 + 2] = c.b
  }

  const geo = new THREE.BufferGeometry()
  geo.setAttribute('position', new THREE.BufferAttribute(positions, 3))
  geo.setAttribute('aSeed', new THREE.BufferAttribute(seeds, 1))
  geo.setAttribute('aRadius', new THREE.BufferAttribute(radii, 1))
  geo.setAttribute('aColor', new THREE.BufferAttribute(colors, 3))

  dustMaterial = new THREE.ShaderMaterial({
    uniforms: {
      uShockRadius: { value: -100 },
      uShockWidth: { value: 2.6 },
      uIntensity: { value: 1 },
      uShellGlow: { value: 0 },
      uAudioBoost: { value: 0 },
      uTime: { value: 0 },
      uSize: { value: 4.2 },
      uPixelRatio: { value: 1 },
      // 尘埃环要保持全软边（它是"云"，22000 颗靠重叠成连续光环）
      uSoft: { value: 0.0 }
    },
    vertexShader: DUST_VERT,
    fragmentShader: DUST_FRAG,
    transparent: true,
    depthWrite: false,
    blending: THREE.AdditiveBlending
  })
  dustPoints = new THREE.Points(geo, dustMaterial)
  dustPoints.frustumCulled = false
  scene.add(dustPoints)
}

function buildNebula() {
  const positions = new Float32Array(NEBULA_COUNT * 3)
  const seeds = new Float32Array(NEBULA_COUNT)
  for (let i = 0; i < NEBULA_COUNT; i += 1) {
    const u = Math.random() * 2 - 1
    const theta = Math.random() * Math.PI * 2
    const ringR = Math.sqrt(Math.max(0, 1 - u * u))
    const radius = 14 + Math.pow(Math.random(), 0.6) * 34
    positions[i * 3] = Math.cos(theta) * ringR * radius
    positions[i * 3 + 1] = u * radius * 0.62
    positions[i * 3 + 2] = Math.sin(theta) * ringR * radius
    seeds[i] = Math.random()
  }
  const geo = new THREE.BufferGeometry()
  geo.setAttribute('position', new THREE.BufferAttribute(positions, 3))
  geo.setAttribute('aSeed', new THREE.BufferAttribute(seeds, 1))

  nebulaMaterial = new THREE.ShaderMaterial({
    uniforms: {
      uTime: { value: 0 },
      uSize: { value: 13 },
      uPixelRatio: { value: 1 },
      uOpacity: { value: 0 },
      uColor: { value: new THREE.Color('#6d5bd0') },
      // 星云是弥散光，保持全软边
      uSoft: { value: 0.0 }
    },
    vertexShader: SOFT_VERT,
    fragmentShader: SOFT_FRAG,
    transparent: true,
    depthWrite: false,
    blending: THREE.AdditiveBlending
  })
  nebulaPoints = new THREE.Points(geo, nebulaMaterial)
  nebulaPoints.frustumCulled = false
  scene.add(nebulaPoints)
}

function buildShockwave() {
  // 主锋面 + 一层更快的微弱前驱壳，叠起来能抹掉单层的硬边
  shockMaterial = new THREE.ShaderMaterial({
    uniforms: {
      uColor: { value: new THREE.Color('#cfe9ff') },
      uIntensity: { value: 0 },
      uPower: { value: 3.2 }
    },
    vertexShader: SHELL_VERT,
    fragmentShader: SHELL_FRAG,
    transparent: true,
    depthWrite: false,
    side: THREE.DoubleSide,
    blending: THREE.AdditiveBlending
  })
  shockMesh = new THREE.Mesh(unitSphere, shockMaterial)
  scene.add(shockMesh)

  shockOuterMaterial = new THREE.ShaderMaterial({
    uniforms: {
      uColor: { value: new THREE.Color('#a78bfa') },
      uIntensity: { value: 0 },
      uPower: { value: 4.0 }
    },
    vertexShader: SHELL_VERT,
    fragmentShader: SHELL_FRAG,
    transparent: true,
    depthWrite: false,
    side: THREE.DoubleSide,
    blending: THREE.AdditiveBlending
  })
  shockOuterMesh = new THREE.Mesh(unitSphere, shockOuterMaterial)
  scene.add(shockOuterMesh)
}

/* ---------------- 演出时间轴 ---------------- */

function updateShow(t) {
  if (t < T_EXPAND_END) {
    // 第 1 拍 · 主序膨胀
    const p = t / T_EXPAND_END
    show.starRadius = STAR_RADIUS_START + easeInOut(p) * (STAR_RADIUS_PEAK - STAR_RADIUS_START)
    show.starHeat = p * 0.3
    show.starCollapse = 0
    show.starVisible = true
    show.remnantVisible = false
    show.ejectaProgress = easeOutCubic(p) * 0.85
    show.ejectaOpacity = 1
    show.nebulaOpacity = 0
    show.shockRadius = -1
    show.shockIntensity = 0
    show.shellGlow = 0
    dimOpacity.value = p * 0.06
    flashOpacity.value = 0
    stageName.value = '主序膨胀'
    setCaption('expand', p)
  } else if (t < T_COLLAPSE_END) {
    // 第 2 拍 · 坍缩：半径三次方衰减，前段几乎不动、末段急速收缩
    const p = (t - T_EXPAND_END) / (T_COLLAPSE_END - T_EXPAND_END)
    show.starRadius = STAR_RADIUS_PEAK - easeInCubic(p) * (STAR_RADIUS_PEAK - STAR_RADIUS_MIN)
    show.starHeat = 0.3 + p * 0.7
    show.starCollapse = clamp01((p - 0.45) / 0.55)
    show.starVisible = true
    show.remnantVisible = false
    show.ejectaProgress = 0.85 + p * 0.15
    show.ejectaOpacity = 1 - p * 0.5
    show.nebulaOpacity = 0
    show.shockRadius = -1
    show.shockIntensity = 0
    show.shellGlow = 0
    // 画面跟着一起压暗，为爆发蓄势
    dimOpacity.value = 0.06 + easeInCubic(p) * 0.78
    flashOpacity.value = 0
    stageName.value = '坍缩'
    setCaption('collapse', p)
  } else if (t < T_SILENCE_END) {
    // 第 3 拍 · 静默：几乎全黑，只剩一个亮点在抖
    const p = (t - T_COLLAPSE_END) / (T_SILENCE_END - T_COLLAPSE_END)
    show.starRadius = STAR_RADIUS_MIN + Math.sin(p * 30) * 0.035
    show.starHeat = 1
    show.starCollapse = 1
    show.starVisible = true
    show.remnantVisible = false
    show.ejectaProgress = 1
    show.ejectaOpacity = 0.5
    show.nebulaOpacity = 0
    show.shockRadius = -1
    show.shockIntensity = 0
    show.shellGlow = 0
    dimOpacity.value = 0.84
    flashOpacity.value = 0
    stageName.value = '静默'
    setCaption('silence', p)
  } else if (t < T_BLAST_END) {
    // 第 4 拍 · 爆发
    const p = (t - T_SILENCE_END) / (T_BLAST_END - T_SILENCE_END)
    const since = t - T_SILENCE_END
    show.starVisible = false
    show.remnantVisible = true
    show.ejectaOpacity = 0.5
    show.nebulaOpacity = 0
    // ⚠️ 必须在这一拍内把锋面推到尘埃壳层之外（壳层半径 19.5 ~ 30.5）。
    //    之前最大只到 23，光环永远点不亮 —— 而"光环被点亮"正是整页的核心效果。
    //    现在 r=25 大约在 14.2s 被扫过，整层在 14.2 ~ 16.0s 内依次点亮。
    // ⚠️ 用二次缓出而不是三次：三次缓出太前重，锋面会在 0.7 秒内就穿过整层尘埃，
    //    光环一闪而过看不清。二次缓出把点亮过程拉到约 1.4 秒，节奏才对。
    const q = Math.min(1, p / 0.95)
    show.shockRadius = (1 - (1 - q) * (1 - q)) * 34
    show.shockIntensity = Math.max(0, 1.0 - p * 0.5)
    // 锋面一旦越过环（半径 14.5~23.5），整圈环开始保留余辉。
    // 否则光环只亮 1 秒就熄灭，"点亮的环"这个核心画面留不住。
    show.shellGlow = 0.13 * clamp01((show.shockRadius - 19) / 9)
    // 白光：0.12 秒冲顶，再 1.6 秒衰减
    const rise = Math.min(1, since / 0.12)
    const fall = Math.max(0, 1 - Math.max(0, since - 0.12) / 1.6)
    flashOpacity.value = rise * fall
    // 黑幕被白光顶掉
    dimOpacity.value = Math.max(0, 0.84 - since / 0.32)
    stageName.value = '爆发'
    setCaption('blast', p)
  } else {
    // 第 5 拍 · 余烬
    const p = clamp01((t - T_BLAST_END) / (CYCLE - T_BLAST_END))
    show.starVisible = false
    show.remnantVisible = true
    show.ejectaOpacity = Math.max(0, 0.5 - p * 0.45)
    show.shockRadius = 34 + easeOutCubic(Math.min(1, p / 0.75)) * 8
    show.shockIntensity = Math.max(0, 0.5 * (1 - p))
    // 承接爆发末尾的 0.13，之后缓慢衰减，避免余烬阶段画面空掉
    show.shellGlow = 0.13 * (1 - p * 0.55)
    show.nebulaOpacity = clamp01((p - 0.06) / 0.45)
    dimOpacity.value = 0
    flashOpacity.value = 0
    stageName.value = '余烬'
    setCaption('remnant', p)
  }
}

function setCaption(key, p) {
  const cap = CAPTIONS[key]
  captionTitle.value = cap.title
  captionSub.value = cap.sub
  // 每拍首尾各留 12% 做淡入淡出
  const fade = Math.min(1, Math.min(p, 1 - p) / 0.12)
  captionOpacity.value = clamp01(fade)
}

/* ---------------- 配色 / 尺寸 ---------------- */

function applyComposerSamples() {
  if (!composer) return
  // ⚠️ EffectComposer 的离屏 RenderTarget 默认 samples=0（无 MSAA），
  //    挂上 composer 后 renderer 的 antialias 就失效了，必须手动补。
  for (const rt of [composer.renderTarget1, composer.renderTarget2]) {
    if (rt && rt.samples !== MSAA_SAMPLES) rt.samples = MSAA_SAMPLES
  }
}

function syncFxaaResolution() {
  if (!fxaaPass) return
  const pr = renderer.getPixelRatio()
  const w = Math.max(1, Math.floor(renderer.domElement.width))
  const h = Math.max(1, Math.floor(renderer.domElement.height))
  // ⚠️ 必须乘 devicePixelRatio，否则 FXAA 完全不起作用
  fxaaPass.material.uniforms.resolution.value.set(1 / (w * pr), 1 / (h * pr))
}

function resize() {
  const el = mountRef.value
  if (!el || !renderer || !camera) return
  const w = el.clientWidth
  const h = el.clientHeight
  if (!w || !h) return
  renderer.setSize(w, h, false)
  camera.aspect = w / h
  camera.updateProjectionMatrix()
  composer?.setSize(w, h)
  applyComposerSamples()
  syncFxaaResolution()
  const pr = renderer.getPixelRatio()
  for (const mat of [starsMaterial, ejectaMaterial, dustMaterial, nebulaMaterial]) {
    if (mat) mat.uniforms.uPixelRatio.value = pr
  }
}

/* ---------------- 交互 ---------------- */

function onPointerDown(event) {
  if (event.button !== 0) return
  pointerDownX = event.clientX
  pointerDownY = event.clientY
  pointerMoved = 0
  dragging = true
  canvasRef.value?.setPointerCapture?.(event.pointerId)
}

function onPointerMove(event) {
  if (!dragging) return
  const dx = event.clientX - pointerDownX
  const dy = event.clientY - pointerDownY
  pointerMoved += Math.abs(dx) + Math.abs(dy)
  pointerDownX = event.clientX
  pointerDownY = event.clientY
  targetYaw += dx * 0.006
  targetPitch = Math.max(-1.25, Math.min(1.25, targetPitch + dy * 0.005))
}

function onPointerUp(event) {
  if (!dragging) return
  dragging = false
  canvasRef.value?.releasePointerCapture?.(event.pointerId)
  // 位移很小才算"点击"，避免拖拽结束时误触发引爆
  if (pointerMoved < 6) detonate()
}

// 点击引爆：把时间轴直接推到静默起点前 0.6 秒 ——
// 保留那 0.6 秒的黑，才有"炸"的感觉；直接跳到白光会只剩"亮"。
function detonate() {
  if (playTime >= T_BLAST_END) {
    // 余烬阶段点击 = 重播（此时恒星已经没了，再点只能是重来）
    restart()
    showToast('重新开始')
    return
  }
  playTime = T_SILENCE_END - 0.6
  playing.value = true
  showToast('引爆')
}

function onWheel(event) {
  event.preventDefault()
  targetDistance = Math.max(20, Math.min(90, targetDistance + event.deltaY * 0.03))
}

function togglePlay() {
  playing.value = !playing.value
}

function restart() {
  playTime = 0
  playing.value = true
}

function skipToBlast() {
  // 停在爆发前 0.35 秒，让那 0.6 秒的黑自己走完，节奏才在
  playTime = T_SILENCE_END - 0.35
  playing.value = true
}

function cycleSpeed() {
  speedIndex.value = (speedIndex.value + 1) % SPEED_STEPS.length
}

function resetView() {
  targetYaw = 0.6
  targetPitch = 0.5
  targetDistance = CAMERA_DISTANCE_DEFAULT
}

/* ---------------- 音频 ---------------- */

function showToast(msg) {
  toast.value = msg
  clearTimeout(toastTimer)
  toastTimer = setTimeout(() => { toast.value = '' }, 2600)
}

function ensureAudio() {
  if (!actx) {
    actx = new (window.AudioContext || window.webkitAudioContext)()
    analyser = actx.createAnalyser()
    analyser.fftSize = 2048
    analyser.smoothingTimeConstant = 0.55
    freqData = new Uint8Array(analyser.frequencyBinCount)
  }
  if (actx.state === 'suspended') actx.resume()
}

function bandEnergy(lo, hi) {
  let sum = 0
  for (let i = lo; i < hi; i += 1) {
    const v = freqData[i] / 255
    sum += v * v
  }
  return Math.min(1, Math.sqrt(sum / Math.max(1, hi - lo)) * 2.6)
}

// 未接入音频时的"氛围律动"：保证没声音也有呼吸感，而不是死板地一动不动
function ambientPulse(t) {
  return {
    bass: Math.max(0, 0.26 + 0.18 * Math.sin(t * 0.9) + 0.09 * Math.sin(t * 2.3 + 1.7)),
    mid: Math.max(0, 0.22 + 0.13 * Math.sin(t * 0.53 + 2.0)),
    treble: Math.max(0, 0.16 + 0.09 * Math.sin(t * 1.31 + 4.0))
  }
}

function readAudio(t) {
  if (audioMode.value === 'none' || !analyser) return ambientPulse(t)
  analyser.getByteFrequencyData(freqData)
  const bin = actx.sampleRate / analyser.fftSize
  return {
    bass: bandEnergy(Math.max(1, Math.floor(20 / bin)), Math.floor(250 / bin)),
    mid: bandEnergy(Math.floor(250 / bin), Math.floor(2000 / bin)),
    treble: bandEnergy(Math.floor(2000 / bin), Math.floor(8000 / bin))
  }
}

// 起音快、收音慢：这样节拍感"跟得上"，但不会抖成闪烁
function smooth(cur, target, dt) {
  const rate = target > cur ? 26 : 5
  return cur + (target - cur) * (1 - Math.exp(-rate * dt))
}

async function toggleMic() {
  if (audioMode.value === 'mic') {
    stopMic()
    return
  }
  stopFile()
  try {
    ensureAudio()
    micStream = await navigator.mediaDevices.getUserMedia({
      audio: { echoCancellation: false, noiseSuppression: false, autoGainControl: false }
    })
    micSource = actx.createMediaStreamSource(micStream)
    micSource.connect(analyser)
    audioMode.value = 'mic'
    showToast('已连接麦克风：声音越大，尘埃环越亮、画面抖得越狠')
  } catch (e) {
    showToast('麦克风不可用：' + e.name + (e.name === 'NotAllowedError' ? '（权限被拒绝）' : ''))
  }
}

function stopMic() {
  if (audioMode.value !== 'mic') return
  if (micStream) micStream.getTracks().forEach((t) => t.stop())
  if (micSource) micSource.disconnect()
  micStream = null
  micSource = null
  audioMode.value = 'none'
}

function pickFile() {
  if (audioMode.value === 'file') {
    stopFile()
    return
  }
  fileInputRef.value?.click()
}

function onFileChange() {
  const f = fileInputRef.value?.files?.[0]
  if (!f) return
  stopMic()
  stopFile()
  ensureAudio()
  const player = playerRef.value
  fileURL = URL.createObjectURL(f)
  player.src = fileURL
  fileSource = actx.createMediaElementSource(player)
  fileSource.connect(analyser)
  analyser.connect(actx.destination)
  player.play().catch(() => showToast('音频播放失败'))
  musicPlaying.value = true
  audioMode.value = 'file'
  fileInputRef.value.value = ''
  showToast('音乐已接入：低频点亮尘埃环、中频撑开星云、高频让尘埃更闪烁')
}

function toggleMusic() {
  const player = playerRef.value
  if (!player || audioMode.value !== 'file') return
  if (player.paused) {
    player.play().then(() => { musicPlaying.value = true }).catch(() => showToast('音频播放失败'))
  } else {
    player.pause()
    musicPlaying.value = false
  }
}

function stopFile() {
  const player = playerRef.value
  if (player) player.pause()
  if (fileSource) {
    try { fileSource.disconnect() } catch (e) { /* 已断开 */ }
    fileSource = null
  }
  if (fileURL) {
    URL.revokeObjectURL(fileURL)
    fileURL = null
  }
  musicPlaying.value = false
  if (audioMode.value === 'file') audioMode.value = 'none'
}

function toggleFullscreen() {
  const el = mountRef.value
  if (!el) return
  if (document.fullscreenElement) document.exitFullscreen?.()
  else el.requestFullscreen?.()
}

/* ---------------- 生命周期 ---------------- */

onMounted(async () => {
  const el = mountRef.value
  const canvas = canvasRef.value
  if (!el || !canvas) return

  renderer = new THREE.WebGLRenderer({ canvas, antialias: false, alpha: false })
  renderer.setPixelRatio(Math.min(window.devicePixelRatio, 2))
  renderer.setClearColor(0x01030a, 1)
  renderer.setSize(el.clientWidth, el.clientHeight, false)

  scene = new THREE.Scene()
  camera = new THREE.PerspectiveCamera(46, el.clientWidth / Math.max(1, el.clientHeight), 0.1, 900)
  camera.position.set(0, 0, distance)
  camera.lookAt(0, 0, 0)

  // 分阶段构建，段间让出主线程，避免首屏一次性长任务卡死
  buildStars()
  await yieldFrame()
  if (destroyed) return

  buildStar()
  buildShockwave()
  await yieldFrame()
  if (destroyed) return

  buildEjecta()
  buildDust()
  await yieldFrame()
  if (destroyed) return

  buildNebula()

  composer = new EffectComposer(renderer)
  composer.addPass(new RenderPass(scene, camera))
  // ⚠️ 泛光参数必须压住。之前用 (1.05, 0.85, 0.18)，阈值太低 + 强度太高，
  //    结果恒星的亮部被糊成一个占满画面 70% 的白球，什么都看不见。
  //    阈值 0.5 只让"确实过曝"的像素溢出（米粒亮点、临边、冲击波锋面），
  //    球体本身的橙红底色不再参与泛光。
  bloomPass = new UnrealBloomPass(new THREE.Vector2(1, 1), 0.55, 0.6, 0.5)
  composer.addPass(bloomPass)
  fxaaPass = new ShaderPass(FXAAShader)
  composer.addPass(fxaaPass)
  applyComposerSamples()

  resize()

  canvas.addEventListener('pointerdown', onPointerDown)
  canvas.addEventListener('pointermove', onPointerMove)
  canvas.addEventListener('pointerup', onPointerUp)
  canvas.addEventListener('pointercancel', onPointerUp)
  canvas.addEventListener('wheel', onWheel, { passive: false })

  resizeObserver = new ResizeObserver(resize)
  resizeObserver.observe(el)

  animate()
})

function yieldFrame() {
  // rAF 与 setTimeout 竞速：后台标签页 rAF 会被节流到 1 次/秒甚至暂停
  return new Promise((resolve) => {
    let done = false
    const finish = () => {
      if (done) return
      done = true
      resolve()
    }
    requestAnimationFrame(finish)
    setTimeout(finish, 60)
  })
}

let frameCount = 0
let fpsAccum = 0

function animate() {
  if (destroyed) return
  rafId = requestAnimationFrame(animate)
  if (!renderer || !scene || !camera) return

  const dt = Math.min(clock.getDelta(), 0.05)
  const elapsed = clock.getElapsedTime()

  if (playing.value) {
    playTime += dt * speed.value
    if (playTime >= CYCLE) playTime -= CYCLE
  }
  updateShow(playTime)

  // 音频：起音快、收音慢，避免抖成闪烁
  const raw = readAudio(elapsed)
  audio.bass = smooth(audio.bass, raw.bass, dt)
  audio.mid = smooth(audio.mid, raw.mid, dt)
  audio.treble = smooth(audio.treble, raw.treble, dt)
  audio.level = (audio.bass + audio.mid + audio.treble) / 3
  if (audioMode.value === 'none') {
    audioText.value = '氛围律动'
  } else {
    const bars = Math.round(audio.level * 10)
    audioText.value = '▮'.repeat(bars) + '▯'.repeat(Math.max(0, 10 - bars))
  }

  // 恒星本体
  if (starMesh) {
    starMesh.visible = show.starVisible
    starMesh.scale.setScalar(Math.max(0.001, show.starRadius))
    starMaterial.uniforms.uTime.value = elapsed
    starMaterial.uniforms.uHeat.value = show.starHeat
    starMaterial.uniforms.uCollapse.value = show.starCollapse
  }
  if (coronaMesh) {
    coronaMesh.visible = show.starVisible
    // ⚠️ 壳半径只放大 1.06 倍。放太大 + 用菲涅尔 = 画出一个空心玻璃球
    coronaMesh.scale.setScalar(Math.max(0.001, show.starRadius * 1.06))
    coronaMaterial.uniforms.uIntensity.value = 0.25 + show.starHeat * 0.5
    coronaMaterial.uniforms.uColor.value.setHex(show.starHeat > 0.6 ? 0xfff0c8 : 0xff9d3c)
  }
  if (remnantMesh) {
    remnantMesh.visible = show.remnantVisible
    remnantMesh.scale.setScalar(0.24)
    remnantMaterial.uniforms.uIntensity.value = 1.15 + Math.sin(elapsed * 6.2) * 0.25
  }

  // 抛射物 / 尘埃 / 星云
  if (ejectaMaterial) {
    ejectaMaterial.uniforms.uProgress.value = show.ejectaProgress
    // 高频让抛射物更闪烁
    // ⚠️ 增益乘在 alpha 上，而加色亮度按 alpha² 缩放：0.35 在 treble=1 时等于亮度 ×1.82
    //    （基准 0.425 → 0.77）。原来给 0.6 会到 ×2.56 直接过曝。
    ejectaMaterial.uniforms.uOpacity.value = show.ejectaOpacity * (1 + audio.treble * 0.35)
    ejectaMaterial.uniforms.uTime.value = elapsed
  }
  if (dustMaterial) {
    dustMaterial.uniforms.uShockRadius.value = show.shockRadius
    dustMaterial.uniforms.uIntensity.value = show.shockIntensity
    dustMaterial.uniforms.uTime.value = elapsed
    // 锋面越往外扩，包络越宽，光环看起来越"厚"
    dustMaterial.uniforms.uShockWidth.value = 2.2 + Math.max(0, show.shockRadius) * 0.09
    dustMaterial.uniforms.uShellGlow.value = show.shellGlow
    // 低频点亮尘埃环
    dustMaterial.uniforms.uAudioBoost.value = audio.bass
  }
  if (nebulaMaterial) {
    // 中频撑开星云
    // ⚠️ 基准系数 0.11 保持不动 —— 它是**真机截图标定**出来的，不要照数值模型的阈值砍。
    //    模型给的线性峰值是 0.79，看着"超标"，曾经照它的 0.5 阈值砍到 0.073，
    //    结果星云在真机上几乎看不见 —— 典型的过度修正。
    //    教训：峰值亮度 ≠ 观感亮度，阈值必须来自真机截图。
    //    另外本页**没有** tone mapping（全前端只有 SolarSystemBackground /
    //    VolumetricLight / Tesseract 有），高光不会被压缩，别把体积光页的经验搬过来。
    // ⚠️ 真正要压的是音频增益：它乘在 alpha 上，而加色亮度按 alpha² 缩放。
    //    原来给 1.4 —— mid 最高能到 1.0（bandEnergy 会钳到 1），那等于亮度 ×5.76，糊屏。
    //    现取 0.25，按 alpha² 反推：星云基准峰值 0.791 已吃掉大半高光预算，
    //    (1+0.25)² × 0.791 = 1.24（只有最亮像素轻微截断）；mid=0.4 时 +21%，仍可感。
    //    （曾写 0.45 并注"mid=1.0 时线性 1.23" —— 那是漏了平方的错算，实际是 1.66。）
    nebulaMaterial.uniforms.uOpacity.value = show.nebulaOpacity * 0.11 * (1 + audio.mid * 0.25)
    nebulaMaterial.uniforms.uTime.value = elapsed
  }
  if (starsMaterial) starsMaterial.uniforms.uTime.value = elapsed

  // 冲击波壳
  if (shockMesh) {
    const r = show.shockRadius
    shockMesh.visible = r > 0.01
    shockOuterMesh.visible = r > 0.01
    if (r > 0.01) {
      shockMesh.scale.setScalar(r)
      shockOuterMesh.scale.setScalar(r * 1.16)
      // 低频增强冲击波亮度。增益受 alpha² 放大（SHELL_FRAG 输出 vec4(uColor*a, a) + 加色）。
      // ⚠️ 这里**没有余量**可留：轮廓处 rim=1，基准 uIntensity 峰值 1.0 ⇒ a=1 ⇒ a²=1，
      //    恰好卡在截断点上。任何增益都只会把"被截断的带"加粗。
      //    真机冻结配对实测（暂停场景、只切麦克风，消掉环移动的干扰）：
      //      g=0.35 → 饱和像素 +95%、区域均值 +41%，白色锋核明显变粗、青蓝渐变被冲掉
      //      g=0.18 → 饱和像素 +54%、区域均值 +21%，环保持锐利，低频响应仍清晰可感
      //    与解析式吻合：截断带宽度 1-(1+g)^(-1/3.2)，0.35→9%，0.18→4.8%，比值 53%。
      shockMaterial.uniforms.uIntensity.value = show.shockIntensity * (1 + audio.bass * 0.18)
      shockOuterMaterial.uniforms.uIntensity.value = show.shockIntensity * 0.4 * (1 + audio.bass * 0.18)
    }
  }

  // 相机：轨道 + 阻尼；爆发的头 1.4 秒加抖，冲击感主要靠它
  yaw += (targetYaw - yaw) * 0.1
  pitch += (targetPitch - pitch) * 0.1
  distance += (targetDistance - distance) * 0.1
  const cosP = Math.cos(pitch)
  camera.position.set(
    Math.sin(yaw) * cosP * distance,
    Math.sin(pitch) * distance,
    Math.cos(yaw) * cosP * distance
  )
  const sinceBlast = playTime - T_SILENCE_END
  if (sinceBlast > 0 && sinceBlast < 1.4) {
    const amp = (1 - sinceBlast / 1.4) * 0.85
    camera.position.x += (Math.random() - 0.5) * amp
    camera.position.y += (Math.random() - 0.5) * amp
    camera.position.z += (Math.random() - 0.5) * amp
  }
  // 音频抖动：与爆发抖动叠加，让声音"推"画面
  const audioShake = audio.level * 0.32
  if (audioShake > 0.002) {
    camera.position.x += (Math.random() - 0.5) * audioShake
    camera.position.y += (Math.random() - 0.5) * audioShake
  }
  camera.lookAt(0, 0, 0)

  if (composer) composer.render()
  else renderer.render(scene, camera)

  timeText.value = `${playTime.toFixed(1)} s`
  starRadiusText.value = show.starVisible ? show.starRadius.toFixed(2) : '—'
  shockText.value = show.shockRadius > 0 ? show.shockRadius.toFixed(1) : '—'

  frameCount += 1
  fpsAccum += dt
  if (fpsAccum >= 0.5) {
    fps.value = Math.round(frameCount / fpsAccum)
    frameCount = 0
    fpsAccum = 0
  }
}

onBeforeUnmount(() => {
  destroyed = true
  cancelAnimationFrame(rafId)
  clearTimeout(toastTimer)

  // 音频：必须显式停掉麦克风轨道，否则浏览器标签页会一直显示"正在录音"
  stopMic()
  stopFile()
  if (actx) {
    actx.close().catch(() => {})
    actx = null
  }
  analyser = null
  freqData = null

  resizeObserver?.disconnect()
  resizeObserver = null

  const canvas = canvasRef.value
  if (canvas) {
    canvas.removeEventListener('pointerdown', onPointerDown)
    canvas.removeEventListener('pointermove', onPointerMove)
    canvas.removeEventListener('pointerup', onPointerUp)
    canvas.removeEventListener('pointercancel', onPointerUp)
    canvas.removeEventListener('wheel', onWheel)
  }

  unitSphere.dispose()

  scene?.traverse((obj) => {
    if (obj.geometry && obj.geometry !== unitSphere) obj.geometry.dispose?.()
    const mat = obj.material
    if (Array.isArray(mat)) mat.forEach((m) => m.dispose?.())
    else mat?.dispose?.()
  })

  composer?.dispose?.()
  composer = null
  bloomPass = null
  fxaaPass = null
  renderer?.dispose?.()
  renderer = null
  scene = null
  camera = null
  starMesh = null
  starMaterial = null
  coronaMesh = null
  coronaMaterial = null
  remnantMesh = null
  remnantMaterial = null
  ejectaPoints = null
  ejectaMaterial = null
  dustPoints = null
  dustMaterial = null
  nebulaPoints = null
  nebulaMaterial = null
  starsPoints = null
  starsMaterial = null
  shockMesh = null
  shockMaterial = null
  shockOuterMesh = null
  shockOuterMaterial = null
})
</script>

<style scoped>
.sn-view {
  position: relative;
  width: 100%;
  height: 100%;
  min-height: 560px;
  overflow: hidden;
  background: #01030a;
}

.sn-canvas {
  position: absolute;
  inset: 0;
  display: block;
  width: 100%;
  height: 100%;
  touch-action: none;
  cursor: grab;
}

.sn-canvas:active {
  cursor: grabbing;
}

/* ⚠️ 黑幕与白光都盖在画布上，必须放行指针，
   否则会把拖拽、滚轮、按钮全部吃掉 */
.sn-dim,
.sn-flash {
  position: absolute;
  inset: 0;
  z-index: 1;
  pointer-events: none;
  transition: none;
}

.sn-dim {
  background: #000000;
}

.sn-flash {
  background: #ffffff;
}

.sn-header {
  position: absolute;
  top: 28px;
  left: 32px;
  z-index: 2;
  pointer-events: none;
}

.sn-eyebrow {
  display: block;
  font-size: 11px;
  letter-spacing: 3px;
  text-transform: uppercase;
  color: #4f7fa8;
}

.sn-title {
  margin: 6px 0 4px;
  font-size: 26px;
  font-weight: 600;
  letter-spacing: 2px;
  color: #d8ecff;
  text-shadow: 0 0 20px rgba(90, 190, 255, 0.35);
}

.sn-desc {
  margin: 0;
  font-size: 13px;
  color: #7d90b5;
}

.sn-toolbar {
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

.sn-button {
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

.sn-button:hover {
  border-color: rgba(96, 190, 255, 0.72);
  color: #eaf6ff;
}

.sn-button.active {
  border-color: rgba(120, 210, 255, 0.9);
  background: rgba(30, 90, 150, 0.34);
  color: #f0f9ff;
  box-shadow: 0 0 14px rgba(80, 180, 255, 0.28);
}

.sn-button-key {
  font-size: 11px;
  padding: 1px 6px;
  border: 1px solid rgba(96, 190, 255, 0.42);
  border-radius: 4px;
  color: #7fb6dd;
}

.sn-caption {
  position: absolute;
  left: 50%;
  bottom: 64px;
  z-index: 2;
  /* ⚠️ 字幕横在画面中央，不放行指针会挡住一大片拖拽区域 */
  pointer-events: none;
  transform: translateX(-50%);
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 6px;
  text-align: center;
}

.sn-caption-title {
  font-size: 30px;
  font-weight: 300;
  letter-spacing: 12px;
  text-indent: 12px;
  color: #eaf6ff;
  text-shadow: 0 0 26px rgba(120, 200, 255, 0.5);
}

.sn-caption-sub {
  font-size: 13px;
  letter-spacing: 1px;
  color: #8fb0d4;
}

.sn-hud {
  position: absolute;
  left: 32px;
  bottom: 28px;
  z-index: 2;
  /* ⚠️ 同上：不放行指针会吃掉左下角一整片操作区域 */
  pointer-events: none;
  display: flex;
  flex-direction: column;
  gap: 6px;
  padding: 12px 16px;
  border: 1px solid rgba(96, 190, 255, 0.22);
  border-radius: 10px;
  background: rgba(3, 10, 22, 0.55);
  font-variant-numeric: tabular-nums;
}

.sn-hud-row {
  display: flex;
  align-items: baseline;
  gap: 12px;
  font-size: 12px;
}

.sn-hud-k {
  min-width: 62px;
  color: #5c7fa6;
}

.sn-hud-v {
  color: #a9d8f5;
}

.sn-audio,
.sn-file-input {
  display: none;
}

.sn-toast {
  position: absolute;
  left: 50%;
  bottom: 132px;
  z-index: 3;
  /* ⚠️ 同上：提示条压在画布上，不放行指针会挡掉拖拽 */
  pointer-events: none;
  transform: translateX(-50%);
  padding: 8px 18px;
  border: 1px solid rgba(96, 190, 255, 0.34);
  border-radius: 8px;
  background: rgba(3, 10, 22, 0.82);
  color: #cfe8ff;
  font-size: 13px;
  white-space: nowrap;
}
</style>
