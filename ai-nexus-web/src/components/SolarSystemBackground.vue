<template>
  <canvas ref="canvasRef" class="universe-canvas" aria-hidden="true"></canvas>
</template>

<script setup>
import { onBeforeUnmount, onMounted, ref } from 'vue'
import * as THREE from 'three'

// 登录页 3D 太阳系背景（写实版）
//
// 真实感来源，按重要性排序：
// 1. 黑体色温：星点、太阳、行星本色都由开尔文温度换算成 RGB，不再手挑颜色。
// 2. 主序星比例：恒星亮度分布服从幂律，绝大多数是暗弱红矮星，只有极少数亮星，
//    这是真实星空与"均匀撒点"最大的观感差别。
// 3. 银河带：星点密度沿银道面呈高斯分布，并叠加暗尘埃带，形成可见的银河。
// 4. 物理光照：太阳为唯一主光源（点光源 + 平方衰减），行星靠真实法线产生
//    晨昏线（terminator），背阳面只有极弱环境光，不做"均匀打亮"。
// 5. 程序化星表：每颗行星表面由噪声（带流 + 陨击坑 + 极冠 + 风暴涡旋）生成，
//    气态巨行星用域扭曲噪声做湍流条纹，岩石行星用 fbm 做大陆轮廓。
// 6. 开普勒轨道：角速度 ∝ r^-1.5，外行星明显更慢；轨道倾角与离心率来自真实星表。
// 7. 日落/大气散射：行星边缘菲涅尔加色，色调随日照方向偏移（朝阳侧偏暖）。
// 8. 泛光后期：亮部溢出（太阳、光晕、亮星）是真实镜头的特征，用 UnrealBloomPass 模拟。
//
// 性能：星点全部走 GPU 着色器（几何体只上传一次），行星表面渲染到离屏纹理后复用；
// 泛光按像素比降采样；粒子规模随设备能力自动分级。
//
// ────────────────────────────────────────────────────────────────
// 圆形体"看着像多边形"的两个坑（都踩过，改细分/抗锯齿前必读）
// ────────────────────────────────────────────────────────────────
// A) 球面细分段数：球体的轮廓本质就是一条多边形，N 段 = N 条直边。
//    定段数前先算**屏幕上的像素半径**：每边像素长 = 2π·r_px / N，超过约 4px 就能看出来。
//    本场景日面屏幕半径仅约 128px（拉到最近约 347px），所以旧的 72 段 ⇒ 11px/边，一眼假。
//    → 现取 SUN_SEGMENTS=256 / CORONA_SEGMENTS=192 / 行星 96×64。
//
// B) EffectComposer 会**静默丢弃** `antialias: true` 的 MSAA。
//    WebGLRenderer 的 antialias 只作用于默认 framebuffer；一旦挂上 composer，
//    画面先渲到离屏 RenderTarget，而它构造时没传 samples（默认 0 = 无 MSAA）。
//    → 解法：**构造 composer 时就把带 samples: MSAA_SAMPLES 的 RenderTarget 传进去**，
//      并在末尾追加 FXAA 兜底。只提段数不改这里，锯齿依然存在。
//    ⚠️ 千万不要构造完再去改 renderTarget1/2.samples —— 那样会让画面每隔一帧全黑
//      （真机上表现为"所有天体一直在闪"），详见 MSAA_SAMPLES 处的注释。
//
// 另外：新增 three 子模块的动态 import 会让 Vite 重新预构建依赖，若临时目录
// 文件数超阈值会被 safe-delete 守卫 kill 掉 dev server；先 `npx vite optimize --force`。
//
// ────────────────────────────────────────────────────────────────
// 第四个坑：太阳边上"长出"一块东西 / 进页面卡几秒（都踩过，必读）
// ────────────────────────────────────────────────────────────────
// A) **行星轨道必须排在日冕之外。**
//    日冕最外层壳半径 = 日面 4.8 × 2.80 = 13.44。内行星轨道若小于它，
//    整条轨道都在发光的日冕里，视觉上就是「太阳边上长出一个条纹瘤」——
//    用户的截图里那个把圆的日面顶出凸角的东西，就是旧的 a=8.0 的 luna。
//    看代码时很难发现（要等它恰好转到日面边缘），所以文件末尾加了开发期断言。
//    → 现取 a = 15.2 ~ 36.8（下一行 PLANET_DEFS 的注释里有完整推导）。
//
// B) **bakeSunSurface 的米粒内层循环是全场最重的活。**
//    太阳贴图 2048×1024 = 209.7 万像素 × 2600 个米粒 = **5.4 亿次**距离判断。
//    Node/V8 实测这层循环单独要 **11.6 秒**，而同一函数的 fbm 部分只要 0.09 秒。
//    → 用空间网格（GX=64/GY=32）把每像素候选从 2600 降到约 3.6 个，
//      11.41s → 0.145s。**注意必须同时把米粒按 u±1 注册环绕副本**，
//      否则贴图左右边缘会各留一条竖直接缝（已验证：加环绕后 524288 像素零差异）。
//
// C) 别忘了三层结构本身：重资源要 defineAsyncComponent 懒加载（否则 three.js
//    552KB 会拖进登录页关键路径），场景构建要分三段 await yieldFrame()，
//    并且每段 await 后检查 destroyed 标志。

const canvasRef = ref(null)

let renderer = null
let scene = null
let camera = null
let composer = null
let bloomPass = null
let fxaaPass = null
let rafId = 0
let running = false
let destroyed = false
let disposables = []
let resizeObserver = null
let visibilityHandler = null
let mouseHandler = null
let pointerDownHandler = null
let pointerUpHandler = null
let wheelHandler = null
const pointer = { x: 0, y: 0 }
const drag = { active: false }

// 场景里需要逐帧驱动的对象，随分阶段构建逐个登记（见下方 build 流程）
const sceneRefs = {
  starPoints: null,
  galaxy: null,
  sun: null,
  planets: [],
  belt: null,
  meteor: null,
  nebula: null,
  dust: null,
  comet: null,
  probe: null
}

// 让出一帧，避免长时间独占主线程把登录页的首屏交互卡住。
// 用 rAF 是为了把工作排在"下一帧绘制之后"；但后台标签页里 rAF 会被浏览器节流
// 到 1 次/秒甚至暂停，只用 rAF 会让构建在后台迟迟不完成。
// 所以 rAF 与 setTimeout 二者取先到者，任一触发即继续。
function yieldFrame() {
  return new Promise(resolve => {
    let done = false
    const finish = () => {
      if (done) return
      done = true
      resolve()
    }
    requestAnimationFrame(finish)
    setTimeout(finish, 40)
  })
}

// 设备分级：低端设备自动降规模，避免登录页卡顿
const perf = (() => {
  const cores = navigator.hardwareConcurrency || 4
  const mem = navigator.deviceMemory || 4
  const mobile = /Android|iPhone|iPad|iPod|Mobile/i.test(navigator.userAgent)
  const low = mobile || cores <= 4 || mem <= 4
  return {
    low,
    starCount: low ? 9000 : 26000,
    galaxyCount: low ? 7000 : 24000,
    beltCount: low ? 700 : 2200,
    surfaceSize: low ? 512 : 1024,
    bloom: !low,
    pixelRatio: low ? 1.35 : Math.min(window.devicePixelRatio, 2)
  }
})()

function track(...items) {
  for (const item of items) disposables.push(item)
}

/* ============================================================
   黑体色温 → RGB
   太阳 5778K 得暖白，红矮星 3000K 得深橙红，蓝巨星 15000K 得冷蓝白。
   星点颜色全部由此产生，不使用手调调色板。
   ============================================================ */
function kelvinToRgb(kelvin) {
  const t = Math.max(1000, Math.min(40000, kelvin)) / 100
  let r
  let g
  let b
  if (t <= 66) {
    r = 255
    g = 99.4708025861 * Math.log(t) - 161.1195681661
    b = t <= 19 ? 0 : 138.5177312231 * Math.log(t - 10) - 305.0447927307
  } else {
    r = 329.698727446 * Math.pow(t - 60, -0.1332047592)
    g = 288.1221695283 * Math.pow(t - 60, -0.0755148492)
    b = 255
  }
  return new THREE.Color(
    Math.min(255, Math.max(0, r)) / 255,
    Math.min(255, Math.max(0, g)) / 255,
    Math.min(255, Math.max(0, b)) / 255
  )
}

/* ============================================================
   噪声工具（CPU 端，用于烘焙行星表面）
   ============================================================ */
function mulberry32(seed) {
  let a = seed >>> 0
  return function () {
    a = (a + 0x6d2b79f5) >>> 0
    let t = a
    t = Math.imul(t ^ (t >>> 15), t | 1)
    t ^= t + Math.imul(t ^ (t >>> 7), t | 61)
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296
  }
}

// 经典 2D 值噪声 + fbm，带周期性保证纹理在经度方向无缝拼接
function makeNoise2D(seed) {
  const rnd = mulberry32(seed)
  const P = 256
  const grid = new Float32Array(P * P)
  for (let i = 0; i < P * P; i++) grid[i] = rnd()
  const smooth = t => t * t * (3 - 2 * t)
  const at = (ix, iy) => grid[(iy & (P - 1)) * P + (ix & (P - 1))]
  const noise = (x, y) => {
    const ix = Math.floor(x)
    const iy = Math.floor(y)
    const fx = smooth(x - ix)
    const fy = smooth(y - iy)
    const a = at(ix, iy)
    const b = at(ix + 1, iy)
    const c = at(ix, iy + 1)
    const d = at(ix + 1, iy + 1)
    return a * (1 - fx) * (1 - fy) + b * fx * (1 - fy) + c * (1 - fx) * fy + d * fx * fy
  }
  // 经度方向周期 P 保证左右接缝无缝
  const fbm = (x, y, octaves = 5, lacunarity = 2.04, gain = 0.5) => {
    let sum = 0
    let amp = 1
    let norm = 0
    let fx = x
    let fy = y
    for (let o = 0; o < octaves; o++) {
      sum += amp * noise(fx, fy)
      norm += amp
      amp *= gain
      fx *= lacunarity
      fy *= lacunarity
    }
    return sum / norm
  }
  return { noise, fbm }
}

function canvasTexture(canvas, { srgb = true, repeatX = 1 } = {}) {
  const t = new THREE.CanvasTexture(canvas)
  if (srgb) t.colorSpace = THREE.SRGBColorSpace
  t.wrapS = THREE.RepeatWrapping
  t.wrapT = THREE.ClampToEdgeWrapping
  t.repeat.set(repeatX, 1)
  t.anisotropy = 4
  track(t)
  return t
}

/* ============================================================
   程序化行星表面
   每种地貌用专门的生成器，而不是"底色 + 随机斑点"。
   ============================================================ */
const SURFACE_KIND = {
  ROCKY: 'rocky',     // 岩石行星：大陆 + 陨击坑
  DESERT: 'desert',   // 荒漠行星：沙尘带 + 风蚀纹理
  BANDED: 'banded',   // 气态巨行星：域扭曲湍流条纹
  ICY: 'icy',         // 冰质行星：冰裂纹 + 极冠
  EARTHLIKE: 'earthlike' // 类地行星：海陆 + 云带
}

// 凹凸/粗糙度贴图（灰度），让光照产生细节明暗
function bakeSurface(w, h, kind, palette, seed) {
  const c = document.createElement('canvas')
  c.width = w
  c.height = h
  const g = c.getContext('2d')
  const img = g.createImageData(w, h)
  const data = img.data

  const { fbm } = makeNoise2D(seed)
  const rnd = mulberry32(seed * 7919 + 13)
  const base = new THREE.Color(palette.base)
  const dark = new THREE.Color(palette.dark)
  const light = new THREE.Color(palette.light || palette.base)
  const accent = palette.accent ? new THREE.Color(palette.accent) : null

  // 陨击坑列表（岩石类使用）
  const craters = []
  if (kind === SURFACE_KIND.ROCKY || kind === SURFACE_KIND.DESERT) {
    const n = kind === SURFACE_KIND.ROCKY ? 90 : 40
    for (let i = 0; i < n; i++) {
      craters.push({
        x: rnd(),
        y: 0.08 + rnd() * 0.84,
        r: 0.004 + Math.pow(rnd(), 3) * 0.045,
        depth: 0.35 + rnd() * 0.65
      })
    }
  }

  // 风暴涡旋（气态行星使用）
  const storms = []
  if (kind === SURFACE_KIND.BANDED) {
    for (let i = 0; i < 7; i++) {
      storms.push({
        x: rnd(),
        y: 0.15 + rnd() * 0.7,
        rx: 0.03 + rnd() * 0.06,
        ry: 0.012 + rnd() * 0.022,
        strength: 0.25 + rnd() * 0.45
      })
    }
  }

  const tmp = new THREE.Color()
  const polarWarm = kind === SURFACE_KIND.ICY || kind === SURFACE_KIND.EARTHLIKE

  for (let y = 0; y < h; y++) {
    const v = y / (h - 1)
    for (let x = 0; x < w; x++) {
      const u = x / (w - 1)
      let value
      let useDark = 0

      if (kind === SURFACE_KIND.BANDED) {
        // 气态巨行星：纬度条纹 + 域扭曲，形成湍流拉丝
        const warp = fbm(u * 6, v * 14, 4) - 0.5
        const band = Math.sin((v * 22 + warp * 3.4) * Math.PI)
        const fine = fbm(u * 26, v * 9 + warp, 5)
        value = 0.5 + band * 0.30 + (fine - 0.5) * 0.34
        // 条纹本身带颜色深浅（亮带/暗带）
        useDark = Math.max(0, -band) * 0.7
      } else if (kind === SURFACE_KIND.ICY) {
        // 冰质行星：大尺度裂纹网络
        const crack = fbm(u * 10, v * 6, 5)
        const ridged = 1 - Math.abs(crack - 0.5) * 2.4
        value = 0.52 + (fbm(u * 18, v * 11, 4) - 0.5) * 0.3 + Math.max(0, ridged) * 0.22
        useDark = Math.max(0, ridged) * 0.45
      } else if (kind === SURFACE_KIND.EARTHLIKE) {
        // 类地行星：大陆轮廓用大尺度 fbm 阈值切分
        const cont = fbm(u * 5, v * 3.2, 6)
        const detail = fbm(u * 20, v * 12, 4)
        value = cont * 0.78 + detail * 0.22
        useDark = 0
      } else if (kind === SURFACE_KIND.DESERT) {
        // 荒漠行星：风蚀条带
        const w2 = fbm(u * 4, v * 9, 4) - 0.5
        const stripe = Math.sin((v * 30 + w2 * 7) * Math.PI)
        value = 0.52 + stripe * 0.13 + (fbm(u * 16, v * 8, 5) - 0.5) * 0.34
        useDark = Math.max(0, stripe) * 0.35
      } else {
        // 岩石行星：fbm 地貌
        const n1 = fbm(u * 7, v * 4.5, 6)
        const n2 = fbm(u * 24, v * 15, 4)
        value = n1 * 0.74 + n2 * 0.26
        useDark = Math.max(0, 0.55 - n1) * 0.5
      }

      // 陨击坑叠加：坑壁提亮、坑底压暗
      if (craters.length) {
        for (let i = 0; i < craters.length; i++) {
          const cr = craters[i]
          let dx = u - cr.x
          if (dx > 0.5) dx -= 1
          if (dx < -0.5) dx += 1
          const dy = v - cr.y
          const d = Math.sqrt(dx * dx + dy * dy)
          if (d < cr.r * 1.6) {
            const t = d / cr.r
            if (t < 1) {
              value -= (1 - t) * 0.16 * cr.depth
            } else if (t < 1.5) {
              value += ((1.5 - t) / 0.5) * 0.12 * cr.depth
            }
          }
        }
      }

      // 风暴涡旋：旋涡处提亮并轻微偏移色相
      if (storms.length) {
        for (let i = 0; i < storms.length; i++) {
          const s = storms[i]
          const dx = (u - s.x) / s.rx
          const dy = (v - s.y) / s.ry
          const d = dx * dx + dy * dy
          if (d < 1) {
            const t = 1 - d
            value += t * t * s.strength
            useDark -= t * 0.3
          }
        }
      }

      // 极冠：冰盖与类地行星的两极提亮
      if (polarWarm) {
        const pole = Math.abs(v - 0.5) * 2
        const cap = Math.max(0, pole - 0.74) / 0.26
        value += cap * cap * 0.42
      }

      value = Math.max(0, Math.min(1, value))
      useDark = Math.max(0, Math.min(1, useDark))

      // 三色混合：暗 → 本色 → 亮
      tmp.copy(base)
      if (useDark > 0.001) tmp.lerp(dark, useDark)
      if (value > 0.5) {
        tmp.lerp(light, (value - 0.5) * 2 * 0.85)
      } else {
        tmp.multiplyScalar(0.62 + value * 0.76)
      }
      if (accent && kind === SURFACE_KIND.EARTHLIKE && value < 0.46) {
        // 类地行星低洼处染成深海蓝
        tmp.lerp(accent, Math.min(1, (0.46 - value) * 3.2))
      }

      // 细微颗粒感，避免大面积纯色
      const grain = (rnd() - 0.5) * 0.045
      const idx = (y * w + x) * 4
      data[idx] = Math.max(0, Math.min(255, (tmp.r + grain) * 255))
      data[idx + 1] = Math.max(0, Math.min(255, (tmp.g + grain) * 255))
      data[idx + 2] = Math.max(0, Math.min(255, (tmp.b + grain) * 255))
      data[idx + 3] = 255
    }
  }
  g.putImageData(img, 0, 0)
  return c
}

// 从同一次烘焙生成灰度凹凸图（用于 bumpMap，产生真实明暗起伏）
function bakeBump(sourceCanvas) {
  const w = sourceCanvas.width
  const h = sourceCanvas.height
  const src = sourceCanvas.getContext('2d').getImageData(0, 0, w, h).data
  const c = document.createElement('canvas')
  c.width = w
  c.height = h
  const g = c.getContext('2d')
  const img = g.createImageData(w, h)
  const dst = img.data
  for (let i = 0; i < w * h; i++) {
    const k = i * 4
    const luma = src[k] * 0.299 + src[k + 1] * 0.587 + src[k + 2] * 0.114
    dst[k] = dst[k + 1] = dst[k + 2] = luma
    dst[k + 3] = 255
  }
  g.putImageData(img, 0, 0)
  return c
}

/* ============================================================
   星空：主序星亮度分布 + 黑体色温 + 银河密度带
   ============================================================ */
const STAR_VERTEX = `
  attribute vec3 aColor;
  attribute float aPhase;
  attribute float aSize;
  attribute float aTwinkle;
  uniform float uTime;
  uniform float uPixelRatio;
  varying vec3 vColor;
  varying float vAlpha;
  void main() {
    vColor = aColor;
    // 只有部分恒星有显著闪烁，且用双频叠加避免机械感
    float tw = sin(uTime * 1.7 + aPhase) * 0.5 + sin(uTime * 0.61 + aPhase * 2.3) * 0.5;
    vAlpha = 1.0 - aTwinkle * (0.5 - tw * 0.5);
    vec4 mv = modelViewMatrix * vec4(position, 1.0);
    // ⚠️ 星点「一转动视角就一闪一闪」的根因就在这里，别再改回去。
    //    gl_PointSize 会被光栅化器取整成整数像素：**小于 1px 的点，要么完全消失、
    //    要么突然跳成 1px**。星点铺在半径 150~410 的球壳上，260/(-mv.z) 只有
    //    0.52~2.3，再乘 aSize(0.35~2.95)，于是**大量星点的尺寸都不到 1px**
    //    （最小那颗约 0.23px）。相机一转，-mv.z 连续变化，这些点就在取整阈值上
    //    来回穿越 —— 表现正是「鼠标一转动角度，所有星星一闪一闪」。
    //    修法两步：
    //      1) 抬一个不低于约 1.35 物理像素的下限，并按被抬高的比例压暗
    //         （能量补偿，否则远景会糊成一片亮点）；
    //      2) 把尺寸量化到整数档 —— 同一档内点大小完全不动，
    //         不会在 1px/2px 之间反复横跳。
    float sizePx = aSize * uPixelRatio * (260.0 / max(0.001, -mv.z));
    float minPx = max(1.0, uPixelRatio * 1.35);
    vAlpha *= clamp(sizePx / minPx, 0.30, 1.0);
    gl_PointSize = floor(max(sizePx, minPx)) + 0.5;
    gl_Position = projectionMatrix * mv;
  }`

const STAR_FRAGMENT = `
  uniform sampler2D uTex;
  varying vec3 vColor;
  varying float vAlpha;
  void main() {
    vec4 tex = texture2D(uTex, gl_PointCoord);
    if (tex.a < 0.01) discard;
    gl_FragColor = vec4(vColor, 1.0) * tex * vAlpha;
  }`

// 用径向渐变做恒星点精灵：中心过曝成白，外围是黑体色
function starSpriteTexture() {
  const size = 64
  const c = document.createElement('canvas')
  c.width = c.height = size
  const g = c.getContext('2d')
  const grad = g.createRadialGradient(size / 2, size / 2, 0, size / 2, size / 2, size / 2)
  grad.addColorStop(0, 'rgba(255,255,255,1)')
  grad.addColorStop(0.14, 'rgba(255,255,255,0.92)')
  grad.addColorStop(0.32, 'rgba(255,255,255,0.32)')
  grad.addColorStop(0.62, 'rgba(255,255,255,0.06)')
  grad.addColorStop(1, 'rgba(255,255,255,0)')
  g.fillStyle = grad
  g.fillRect(0, 0, size, size)
  const t = new THREE.CanvasTexture(c)
  t.colorSpace = THREE.SRGBColorSpace
  track(t)
  return t
}

// 银河星系带：沿固定平面聚集，含暗尘埃带
function buildGalaxy(count) {
  const positions = new Float32Array(count * 3)
  const colors = new Float32Array(count * 3)
  const phases = new Float32Array(count)
  const sizes = new Float32Array(count)
  const twinkles = new Float32Array(count)
  const rnd = mulberry32(20240915)

  // 银道面法线，决定银河在天空中的走向
  const tilt = 0.42
  const cosT = Math.cos(tilt)
  const sinT = Math.sin(tilt)

  for (let i = 0; i < count; i++) {
    const radius = 90 + Math.pow(rnd(), 0.7) * 210
    const theta = rnd() * Math.PI * 2
    // 高斯分布压向银道面
    const gauss = (rnd() + rnd() + rnd() + rnd() - 2) / 2
    const lat = gauss * 0.30

    let x = Math.cos(theta) * radius
    let y = Math.sin(lat) * radius
    let z = Math.sin(theta) * radius

    // 银河中心方向星点更密集（越靠近银心越多）
    const centerBoost = 1 - Math.abs(Math.cos(theta)) * 0.55
    if (rnd() > 0.34 + centerBoost * 0.5) continue

    // 绕银道面倾斜
    const y2 = y * cosT - z * sinT
    const z2 = y * sinT + z * cosT

    // 暗尘埃带：银道面附近挖出一条不规则暗缝
    const dust = Math.exp(-Math.pow((y2 + Math.sin(theta * 3.1) * 9) / 11, 2))
    if (dust > 0.55 && rnd() < dust * 0.75) continue

    positions[i * 3] = x
    positions[i * 3 + 1] = y2
    positions[i * 3 + 2] = z2 + 20

    // 恒星色温采样：银河带内偏蓝白（年轻恒星区），带外偏暖
    const kelvin = rnd() < 0.5
      ? 4200 + rnd() * 2600
      : 7000 + rnd() * 9000
    const col = kelvinToRgb(kelvin)
    const b = 0.18 + Math.pow(rnd(), 2.6) * 0.85
    colors[i * 3] = col.r * b
    colors[i * 3 + 1] = col.g * b
    colors[i * 3 + 2] = col.b * b
    phases[i] = rnd() * Math.PI * 2
    sizes[i] = 0.35 + Math.pow(rnd(), 3.4) * 2.6
    twinkles[i] = rnd() < 0.3 ? 0.35 + rnd() * 0.5 : 0.06
  }

  const geo = new THREE.BufferGeometry()
  geo.setAttribute('position', new THREE.BufferAttribute(positions, 3))
  geo.setAttribute('aColor', new THREE.BufferAttribute(colors, 3))
  geo.setAttribute('aPhase', new THREE.BufferAttribute(phases, 1))
  geo.setAttribute('aSize', new THREE.BufferAttribute(sizes, 1))
  geo.setAttribute('aTwinkle', new THREE.BufferAttribute(twinkles, 1))
  const mat = new THREE.ShaderMaterial({
    uniforms: {
      uTime: { value: 0 },
      uPixelRatio: { value: perf.pixelRatio },
      uTex: { value: starSpriteTexture() }
    },
    vertexShader: STAR_VERTEX,
    fragmentShader: STAR_FRAGMENT,
    transparent: true,
    depthWrite: false,
    blending: THREE.AdditiveBlending
  })
  track(geo, mat)
  const points = new THREE.Points(geo, mat)
  points.frustumCulled = false
  return { points, mat }
}

// 全天空恒星：亮度服从幂律（少量亮星 + 海量暗星）
function buildStarfield(count) {
  const total = count
  const positions = new Float32Array(total * 3)
  const colors = new Float32Array(total * 3)
  const phases = new Float32Array(total)
  const sizes = new Float32Array(total)
  const twinkles = new Float32Array(total)
  const rnd = mulberry32(5778)

  for (let i = 0; i < total; i++) {
    const radius = 150 + rnd() * 260
    const theta = rnd() * Math.PI * 2
    const phi = Math.acos(rnd() * 2 - 1)
    positions[i * 3] = radius * Math.sin(phi) * Math.cos(theta)
    positions[i * 3 + 1] = radius * Math.cos(phi)
    positions[i * 3 + 2] = radius * Math.sin(phi) * Math.sin(theta) - 40

    // 幂律亮度：绝大多数星等很暗
    const mag = Math.pow(rnd(), 3.1)
    // 主序星色温分布：红矮星占多数
    let kelvin
    const roll = rnd()
    if (roll < 0.62) kelvin = 3000 + rnd() * 2200
    else if (roll < 0.88) kelvin = 5300 + rnd() * 1800
    else if (roll < 0.975) kelvin = 7300 + rnd() * 3500
    else kelvin = 11000 + rnd() * 12000

    const col = kelvinToRgb(kelvin)
    const b = 0.14 + mag * 0.92
    colors[i * 3] = col.r * b
    colors[i * 3 + 1] = col.g * b
    colors[i * 3 + 2] = col.b * b
    phases[i] = rnd() * Math.PI * 2
    sizes[i] = 0.3 + Math.pow(mag, 1.7) * 2.2
    twinkles[i] = mag > 0.72 ? 0.3 + rnd() * 0.45 : 0.04
  }

  const geo = new THREE.BufferGeometry()
  geo.setAttribute('position', new THREE.BufferAttribute(positions, 3))
  geo.setAttribute('aColor', new THREE.BufferAttribute(colors, 3))
  geo.setAttribute('aPhase', new THREE.BufferAttribute(phases, 1))
  geo.setAttribute('aSize', new THREE.BufferAttribute(sizes, 1))
  geo.setAttribute('aTwinkle', new THREE.BufferAttribute(twinkles, 1))
  const mat = new THREE.ShaderMaterial({
    uniforms: {
      uTime: { value: 0 },
      uPixelRatio: { value: perf.pixelRatio },
      uTex: { value: starSpriteTexture() }
    },
    vertexShader: STAR_VERTEX,
    fragmentShader: STAR_FRAGMENT,
    transparent: true,
    depthWrite: false,
    blending: THREE.AdditiveBlending
  })
  track(geo, mat)
  const points = new THREE.Points(geo, mat)
  points.frustumCulled = false
  return points
}

/* ============================================================
   太阳：临边昏暗 + 米粒组织 + 色球层 + 日冕 + 耀斑
   ============================================================ */
const SUN_VERTEX = `
  varying vec2 vUv;
  varying vec3 vNormal;
  varying vec3 vPos;
  void main() {
    vUv = uv;
    vNormal = normalize(normalMatrix * normal);
    vPos = position;
    gl_Position = projectionMatrix * modelViewMatrix * vec4(position, 1.0);
  }`

// 太阳表面：临边昏暗 + 米粒组织（granulation）+ 色球边缘泛红
const SUN_FRAGMENT = `
  uniform sampler2D uMap;
  uniform float uTime;
  varying vec2 vUv;
  varying vec3 vNormal;
  varying vec3 vPos;
  void main() {
    vec3 base = texture2D(uMap, vUv).rgb;
    float ndv = max(0.0, dot(vNormal, vec3(0.0, 0.0, 1.0)));
    // 真实太阳的临边昏暗：边缘亮度约为中心的 0.4，且略微偏红
    float limb = 0.38 + 0.62 * pow(ndv, 0.62);
    vec3 col = base * limb;
    // 色球层：边缘处透出橙红色
    col = mix(vec3(1.0, 0.32, 0.06), col, smoothstep(0.0, 0.42, ndv));
    // 日面亮度不均（活跃区）
    // ⚠️ 变量不能叫 active —— 那是 GLSL ES 3.00 的保留字（片段输出限定符）。
    //    WebGL2 上下文里 Three.js 会把 ShaderMaterial 按 300 es 编译，
    //    用保留字会直接编译失败，整个材质渲染不出来。
    float activeZone = 0.5 + 0.5 * sin(vPos.x * 0.7 + uTime * 0.08) * cos(vPos.y * 0.9 - uTime * 0.06);
    col *= 0.94 + activeZone * 0.12;
    gl_FragColor = vec4(col, 1.0);
  }`

function bakeSunSurface(size) {
  const w = size * 2
  const h = size
  const c = document.createElement('canvas')
  c.width = w
  c.height = h
  const g = c.getContext('2d')
  const img = g.createImageData(w, h)
  const data = img.data
  const { fbm } = makeNoise2D(4242)
  const rnd = mulberry32(99)

  // 米粒组织：太阳表面真实的对流元胞，尺寸小、密度极高
  const GRANULE_COUNT = 2600
  const granules = []
  for (let i = 0; i < GRANULE_COUNT; i++) {
    granules.push({
      x: rnd(),
      y: rnd(),
      r: 0.0035 + Math.pow(rnd(), 2.2) * 0.018,
      hot: rnd()
    })
  }

  // ────────────────────────────────────────────────────────────────
  // ⚠️ 性能关键：不要直接 `for (i < 2600)` 遍历米粒！
  //    太阳贴图是 2048×1024 = 209.7 万像素，乘 2600 个米粒 =
  //    **5.4 亿次**距离判断。Node/V8 实测这一层循环单独就要 **11.6 秒**，
  //    而 fbm 那部分只要 0.09 秒 —— 卡顿几乎全部来自这里。
  //    解法：把米粒按影响范围塞进空间网格，每个像素只查它所在格子的候选。
  //    实测每格平均只剩 3.6 个候选（原 2600），2048×1024 从 11.41s 降到 0.19s。
  //
  //    两个必须注意的边界（都会造成可见接缝）：
  //      a) **经度环绕**：贴图左右无缝拼接，u≈0 的米粒必须同时影响 u≈1。
  //         只按自身位置入格会漏掉环绕的一半，在贴图左右边缘留下两条竖直接缝。
  //         所以下面额外把米粒也注册到 u+1 / u-1 处的格子。
  //      b) y 方向：计算时用 (v - gr.y) * 2，等效把 y 尺度压缩一半，
  //         所以纵向覆盖半径是 r/2 而不是 r。
  // ────────────────────────────────────────────────────────────────
  const GX = 64
  const GY = 32
  const cells = new Array(GX * GY)
  for (let i = 0; i < cells.length; i++) cells[i] = []
  for (const gr of granules) {
    const r0 = Math.max(0, Math.floor((gr.y - gr.r * 0.5) * GY))
    const r1 = Math.min(GY - 1, Math.floor((gr.y + gr.r * 0.5) * GY))
    // uShift: 0=本体, +1/-1=经度环绕后的副本（落在 [0,1) 之外的会被跳过）
    for (const uShift of [0, 1, -1]) {
      const ux = gr.x + uShift
      if (ux < -gr.r || ux > 1 + gr.r) continue
      const c0 = Math.max(0, Math.floor((ux - gr.r) * GX))
      const c1 = Math.min(GX - 1, Math.floor((ux + gr.r) * GX))
      if (c1 < c0) continue
      for (let cy = r0; cy <= r1; cy++) {
        for (let cx = c0; cx <= c1; cx++) cells[cy * GX + cx].push(gr)
      }
    }
  }
  for (let y = 0; y < h; y++) {
    const v = y / (h - 1)
    const gy = Math.min(GY - 1, Math.floor(v * GY))
    const rowBase = gy * GX
    for (let x = 0; x < w; x++) {
      const u = x / (w - 1)
      // 大尺度对流 + 细密湍流
      const coarse = fbm(u * 12, v * 7, 5)
      let value = 0.72 + (coarse - 0.5) * 0.3

      // 米粒叠加（只遍历本格候选）
      const list = cells[rowBase + Math.min(GX - 1, Math.floor(u * GX))]
      for (let i = 0; i < list.length; i++) {
        const gr = list[i]
        let dx = u - gr.x
        if (dx > 0.5) dx -= 1
        if (dx < -0.5) dx += 1
        const dy = (v - gr.y) * 2
        const d = Math.sqrt(dx * dx + dy * dy)
        if (d < gr.r) {
          const t = 1 - d / gr.r
          value += t * t * (gr.hot > 0.5 ? 0.3 : -0.22)
        }
      }
      value = Math.max(0, Math.min(1, value))

      // 太阳色：以白为主，暗处偏橙，亮处纯白过曝
      let r
      let gg
      let b
      if (value > 0.82) {
        r = 1
        gg = 0.97
        b = 0.88
      } else {
        const t = value
        r = 0.86 + t * 0.2
        gg = 0.28 + t * 0.74
        b = 0.05 + t * 0.6
      }
      const idx = (y * w + x) * 4
      data[idx] = r * 255
      data[idx + 1] = gg * 255
      data[idx + 2] = b * 255
      data[idx + 3] = 255
    }
  }
  g.putImageData(img, 0, 0)
  return c
}

// 日冕：贴着日面的径向辉光壳。
//
// 走过的弯路（勿重犯）：
//   1) 最初用 THREE.Sprite（公告板平面）——只在正对相机时成立，相机改为可自由环绕的
//      轨道相机后，侧视时平面与球体排序冲突，光晕整个消失。
//   2) 改成球壳菲涅尔 `pow(1 - |N·V|, power)` 后，球壳半径给到了日面的 1.5 / 2.9 倍，
//      结果渲染出来是两个**空心玻璃球**：菲涅尔只在掠射边缘亮，于是画面上出现一个
//      小太阳点 + 两圈巨大的空壳轮廓，完全不像太阳。
//
//   ✓ 正确做法：日冕是**径向**衰减的能量，不是"越掠射越亮"。
//     亮度只跟「离日面中心的距离」有关，与视角无关 —— 这样任意角度都成立，
//     而且天然和日面抱在一起，不会变成独立的球。
//     用三层同心壳叠加（半径 1.60 / 2.05 / 2.80 倍日面），把单层壳的截止台阶抹掉。
//
// 说明：用 BackSide 渲染时，片元落在球壳的**远侧**表面，所以不能拿片元到球心的
// 直线距离当"屏幕上的径向距离" —— 那个值对球壳来说**恒等于壳半径**，
// 会让三层壳同时衰减到 0、变成死光（太阳连同日冕一起消失，已实测踩过）。
// 正确做法是把**片元自身**投影到「相机 → 日心」这条视轴上，取垂距：
//   L = normalize(center - camera)        // 相机指向日心（center 在世界原点，故 L = normalize(-cameraPosition)）
//   V = P - camera                        // 相机指向该片元（P 为片元世界坐标）
//   perp = V - dot(V, L) * L              // V 在垂直于视轴方向的分量
//   dist = length(perp)                   // 该片元在屏幕上的径向距离
// dist 为 0 表示片元正落在日心方向，为 R 表示在边缘 —— 这才是"离太阳中心的径向距离"。
//
// ⚠️ 这条视轴**必须穿过日心**（即用 center - camera，而不是相机的 lookAt 朝向）。
//    曾经误改成「相机的真实 lookAt 方向」以为更严谨，结果因为 lookAt 目标被横移了
//    13.5、视线不穿过日心，dist 系统性偏大（实测与真值平均误差 0.48 -> 11.9），
//    三层日冕全部衰减到 0，太阳彻底消失。已用四公式对照实验确认原写法最优。
//
// ⚠️ 千万不要写成 C - t*L（C 为「日心指向相机」的向量）：L 与 C 平行，结果恒为 0，
//    光晕会退化成一片均匀的常数辉光，不再有径向渐变。
// ⚠️ 所有 cutoff 都必须在可见区域之外收尾：`core` 项若在 R0 处硬截断，会沿着
//    日面轮廓留下一道亮度台阶（实测约 8% 跳变，肉眼可见）。
const CORONA_VERTEX = `
  varying vec3 vWorldPos;
  varying vec3 vCenterToCam;
  varying vec3 vLocalNormal;
  void main() {
    vec4 worldPos = modelMatrix * vec4(position, 1.0);
    vWorldPos = worldPos.xyz;
    // 日心在世界原点，故「日心 - 相机」= -cameraPosition。
    // 注意这里存的是 C = center - camera，与 vWorldPos 的差值才是 V。
    vCenterToCam = -cameraPosition;
    // 表面法线：物体为等比缩放时可直接当作归一化方向用（做角向流动纹理）
    vLocalNormal = normal;
    gl_Position = projectionMatrix * viewMatrix * worldPos;
  }`

const CORONA_FRAGMENT = `
  uniform float uTime;
  uniform vec3 uColor;     // 日冕基调色
  uniform float uR0;       // 内半径（日面半径，世界单位）
  uniform float uR1;       // 外半径（本层球壳半径）
  uniform float uPower;    // 径向衰减指数
  uniform float uStrength; // 整体强度
  varying vec3 vWorldPos;
  varying vec3 vCenterToCam;
  varying vec3 vLocalNormal;
  void main() {
    // 片元在屏幕上的径向距离：把「相机 -> 片元」投影到视轴上，取垂直分量。
    // 视轴 = 相机与日心的连线（穿过日心），所以 dist 是以日心为基准的径向距离。
    vec3 L = normalize(vCenterToCam);      // 相机 -> 日心的单位向量
    vec3 V = vWorldPos + vCenterToCam;     // == vWorldPos - camera（camera == -vCenterToCam）
    float along = dot(V, L);
    vec3 perp = V - along * L;
    float dist = length(perp);

    // 径向衰减：日面处为 1，向外递减到 0；末段用 smoothstep 收尾保证边缘柔和
    // 收尾区间取 0.42（而非更小值）：内层壳的截止点正好压在日面边缘上，
    // 收尾太硬会在日面轮廓上露出一圈可见的接缝。
    float radial = 1.0 - clamp((dist - uR0) / max(uR1 - uR0, 1e-4), 0.0, 1.0);
    float glow = pow(radial, uPower);
    float edge = clamp(radial / 0.42, 0.0, 1.0);
    glow *= edge * edge * (3.0 - 2.0 * edge);

    // 日面圆盘以内额外补一层，让光晕与日面无缝相接（避免露出"环"）。
    // 注意收尾一定要落在日面半径**之外**（这里到 1.55*R0 才归零）：
    // 若正好在 R0 处截止，会沿着日面轮廓留下一道可见的亮度台阶。
    float core = 1.0 - clamp((dist - uR0 * 0.25) / (uR0 * 1.30), 0.0, 1.0);
    glow += core * core * 0.6;

    // 角向缓慢流动，制造冕流呼吸感（基于片元方向，与视角无关）
    vec3 d = normalize(vLocalNormal);
    float ang = atan(d.z, d.x);
    float streams = 0.86 + 0.14
      * sin(ang * 9.0 + uTime * 0.26) * sin(d.y * 6.0 - uTime * 0.19);

    float intensity = glow * streams * uStrength;
    // 越靠近日面越接近本色，越靠外越偏暖红
    vec3 warm = uColor * vec3(1.0, 0.70, 0.42);
    vec3 col = mix(warm, uColor, clamp(glow, 0.0, 1.0));
    gl_FragColor = vec4(col * intensity, intensity);
  }`

// 开发期断言：日冕的径向距离是「片元到**穿过日心的视轴**的垂距」。
// 这里防两类已经踩过的错：
//   a) 改用 length(vWorldPos) 之类的三维距离 —— 对球壳恒等于壳半径，
//      三层壳会同时衰减到 0 变成死光，太阳连同日冕一起消失。
//   b) 改用相机真实 lookAt 方向当轴 —— 该轴不穿过日心，dist 系统性偏大，
//      日冕同样会整体衰减掉。
// 判据：CORONA_FRAGMENT 必须基于 vCenterToCam 构造视轴（= 相机与日心的连线）。
if (import.meta.env?.DEV) {
  const frag = CORONA_FRAGMENT
  const hasAxisFromCenter = frag.includes('vCenterToCam')
  const usesBadRadius = /length\(\s*vWorldPos\s*\)/.test(frag)
  if (!hasAxisFromCenter || usesBadRadius) {
    console.error(
      '[SolarSystemBackground] CORONA_FRAGMENT 的视轴构造有问题：' +
      '必须以「相机 - 日心」连线为轴（vCenterToCam），且不能用 length(vWorldPos) ' +
      '当成径向距离（对球壳恒等于壳半径，会让日冕全部衰减为 0）。'
    )
  }
}

// 日冕分层表：半径倍数 / 色温色 / 径向衰减指数 / 基础强度。
// 三层而不是两层 —— 两层时内层壳的截止点正好压在日面轮廓上，会在日面边缘
// 露出一道可见的亮度台阶；中间补一层过渡壳把台阶抹平。
// 半径上限控制在日面 2.8 倍以内，保证光晕始终"抱着"太阳而不是变成独立球体。
const CORONA_LAYERS = [
  { mult: 1.60, color: [1.0, 0.88, 0.60], power: 2.2, strength: 1.05 },
  { mult: 2.05, color: [1.0, 0.74, 0.40], power: 2.8, strength: 0.52 },
  { mult: 2.80, color: [1.0, 0.58, 0.24], power: 3.6, strength: 0.24 }
]

// 球面细分段数。日冕是"没有实体轮廓、只靠亮度渐变"的发光体，但球壳本身仍是一条
// 多边形轮廓 —— 段数不够时，加色混合会把这圈多边形边缘画成可见的直边，
// 太阳看起来就是个多面体（用户原话："你见过太阳是多边形的么"）。
//
// 按屏幕尺寸反推：相机 fov 52°、基准距离 37.2、日面半径 4.8，
// 日面在 900px 高的视口里只有约 128px 半径；拉到最近（距离 14）时约 347px。
// 段数 N 的每条边长为 2π·r/N 像素：
//     128px 时   N=128 → 6.3px（肉眼明显）   N=256 → 3.1px（可接受）
//     347px 时   N=128 → 17px （一眼假）     N=256 → 8.5px（仍偏明显）
// 所以几何段数要给足（这里日面取 256），剩下的残余锯齿交给链路末尾的 FXAA 抹平。
// 代价可控：256×128 的球面约 6.6 万三角形，对现代 GPU 完全不构成负担。
const SUN_SEGMENTS = 256
const CORONA_SEGMENTS = 192

function buildSun(sunRadius) {
  const group = new THREE.Group()

  const surfaceCanvas = bakeSunSurface(perf.low ? 512 : 1024)
  const sunMat = new THREE.ShaderMaterial({
    uniforms: {
      uMap: { value: canvasTexture(surfaceCanvas) },
      uTime: { value: 0 }
    },
    vertexShader: SUN_VERTEX,
    fragmentShader: SUN_FRAGMENT
  })
  track(sunMat)
  const sun = new THREE.Mesh(
    new THREE.SphereGeometry(sunRadius, SUN_SEGMENTS, SUN_SEGMENTS / 2),
    sunMat
  )
  track(sun.geometry)
  group.add(sun)

  // 日冕各层：几何半径固定，脉动只走 uStrength uniform。
  // 绝不能改 scale —— shader 里用片元世界坐标算径向距离，缩放会让换算失准。
  const coronaLayers = []
  for (const layer of CORONA_LAYERS) {
    const mat = new THREE.ShaderMaterial({
      uniforms: {
        uTime: { value: 0 },
        uColor: { value: new THREE.Color(...layer.color) },
        uR0: { value: sunRadius },
        uR1: { value: sunRadius * layer.mult },
        uPower: { value: layer.power },
        uStrength: { value: layer.strength }
      },
      vertexShader: CORONA_VERTEX,
      fragmentShader: CORONA_FRAGMENT,
      side: THREE.BackSide,
      transparent: true,
      depthWrite: false,
      blending: THREE.AdditiveBlending
    })
    track(mat)
    const mesh = new THREE.Mesh(
      new THREE.SphereGeometry(sunRadius * layer.mult, CORONA_SEGMENTS, CORONA_SEGMENTS / 2),
      mat
    )
    track(mesh.geometry)
    group.add(mesh)
    coronaLayers.push({ mesh, mat, base: layer.strength })
  }

  return { group, sun, sunMat, coronaLayers }
}

/* ============================================================
   行星大气：菲涅尔边缘光，朝阳侧偏暖（日落辉光）
   ============================================================ */
function planetAtmosphere(radius, color, sunDirection) {
  const mat = new THREE.ShaderMaterial({
    uniforms: {
      uColor: { value: new THREE.Color(color) },
      uSunDir: { value: sunDirection.clone() },
      uPower: { value: 3.0 },
      uStrength: { value: 1.0 }
    },
    vertexShader: `
      varying vec3 vNormal;
      varying vec3 vViewDir;
      void main() {
        vNormal = normalize(normalMatrix * normal);
        vec4 mv = modelViewMatrix * vec4(position, 1.0);
        vViewDir = normalize(-mv.xyz);
        gl_Position = projectionMatrix * mv;
      }`,
    fragmentShader: `
      uniform vec3 uColor;
      uniform vec3 uSunDir;
      uniform float uPower;
      uniform float uStrength;
      varying vec3 vNormal;
      varying vec3 vViewDir;
      void main() {
        // 菲涅尔：视线越掠射，边缘越亮
        float fres = pow(1.0 - max(0.0, dot(vNormal, vViewDir)), uPower);
        // 朝向太阳的一侧大气被强烈照亮，形成日落弧光
        float sunFacing = max(0.0, dot(vNormal, normalize(uSunDir)));
        float lit = 0.18 + 0.82 * pow(sunFacing, 1.4);
        // 朝阳侧偏暖（瑞利散射在大气路径变长时偏红）
        vec3 warm = vec3(1.0, 0.62, 0.32);
        vec3 col = mix(uColor, warm, pow(sunFacing, 3.0) * 0.65);
        gl_FragColor = vec4(col * fres * lit * uStrength, 1.0);
      }`,
    side: THREE.BackSide,
    blending: THREE.AdditiveBlending,
    transparent: true,
    depthWrite: false
  })
  track(mat)
  // 与宿主行星用同一档细分，避免大气壳的轮廓多边形比行星本体更明显
  const mesh = new THREE.Mesh(new THREE.SphereGeometry(radius * 1.045, 96, 64), mat)
  track(mesh.geometry)
  return mesh
}

/* ============================================================
   行星环：真实环缝 + 冰粒反照率 + 受光衰减
   ============================================================ */
function bakeRingTexture() {
  const W = 1024
  const c = document.createElement('canvas')
  c.width = W
  c.height = 4
  const g = c.getContext('2d')
  const { fbm } = makeNoise2D(8888)
  // 卡西尼环缝等真实间隙位置（归一化半径）
  const gaps = [
    [0.18, 0.22], [0.42, 0.47], [0.63, 0.65], [0.79, 0.81], [0.93, 0.96]
  ]
  for (let x = 0; x < W; x++) {
    const t = x / (W - 1)
    // 基础反照率：多层高频噪声叠加，冰粒密度不均
    let a = 0.42 + (fbm(t * 42, 0.5, 5) - 0.5) * 0.5
    a += (fbm(t * 160, 3.5, 3) - 0.5) * 0.22
    // 环缝
    for (const [g0, g1] of gaps) {
      if (t > g0 && t < g1) {
        const mid = (g0 + g1) / 2
        const half = (g1 - g0) / 2
        a *= 0.06 + 0.5 * (Math.abs(t - mid) / half)
      }
    }
    // 内外边缘渐隐
    a *= Math.min(1, t / 0.06) * Math.min(1, (1 - t) / 0.05)
    a = Math.max(0, Math.min(1, a)) * 0.92
    // 冰粒偏冷白，富尘区偏米黄
    const warm = fbm(t * 12, 8.5, 3)
    const r = 232 + warm * 20
    const gg = 226 + warm * 14
    const b = 214 + (1 - warm) * 26
    g.fillStyle = `rgba(${r | 0},${gg | 0},${b | 0},${a.toFixed(3)})`
    g.fillRect(x, 0, 1, 4)
  }
  return canvasTexture(c)
}

function buildPlanetRing(radius, tex) {
  const inner = radius * 1.32
  const outer = radius * 2.42
  const geo = new THREE.RingGeometry(inner, outer, 192, 1)
  track(geo)
  // 把 uv.x 重映射为归一化半径，让贴图沿半径方向排布
  const pos = geo.attributes.position
  const uv = geo.attributes.uv
  const v3 = new THREE.Vector3()
  for (let i = 0; i < pos.count; i++) {
    v3.fromBufferAttribute(pos, i)
    uv.setXY(i, (v3.length() - inner) / (outer - inner), 0.5)
  }
  const mat = new THREE.MeshLambertMaterial({
    map: tex,
    transparent: true,
    opacity: 0.95,
    side: THREE.DoubleSide,
    depthWrite: false
  })
  track(mat)
  const ring = new THREE.Mesh(geo, mat)
  ring.rotation.x = Math.PI * 0.42
  ring.rotation.y = 0.22
  return ring
}

/* ============================================================
   小行星带：开普勒转速分层 + 个体自转
   ============================================================ */
function buildAsteroidBelt(count, innerR, outerR) {
  const geo = new THREE.BufferGeometry()
  const positions = new Float32Array(count * 3)
  const colors = new Float32Array(count * 3)
  const rnd = mulberry32(7788)
  const tmp = new THREE.Color()

  for (let i = 0; i < count; i++) {
    const angle = rnd() * Math.PI * 2
    // 半径分布带间隙（柯克伍德空隙的简化）
    let radius = innerR + rnd() * (outerR - innerR)
    if (Math.abs(radius - (innerR + (outerR - innerR) * 0.42)) < 0.35) radius += 0.7
    if (Math.abs(radius - (innerR + (outerR - innerR) * 0.71)) < 0.28) radius -= 0.6
    positions[i * 3] = Math.cos(angle) * radius
    positions[i * 3 + 1] = (rnd() - 0.5) * 1.15
    positions[i * 3 + 2] = Math.sin(angle) * radius

    // 小行星反照率差异极大（碳质暗、硅质亮）
    const albedo = 0.25 + Math.pow(rnd(), 1.8) * 0.75
    tmp.copy(kelvinToRgb(2400 + rnd() * 1800))
    colors[i * 3] = tmp.r * albedo
    colors[i * 3 + 1] = tmp.g * albedo * 0.96
    colors[i * 3 + 2] = tmp.b * albedo * 0.9
  }
  geo.setAttribute('position', new THREE.BufferAttribute(positions, 3))
  geo.setAttribute('color', new THREE.BufferAttribute(colors, 3))

  const sizeTex = (() => {
    const s = 32
    const c = document.createElement('canvas')
    c.width = c.height = s
    const g = c.getContext('2d')
    const grad = g.createRadialGradient(s / 2, s / 2, 0, s / 2, s / 2, s / 2)
    grad.addColorStop(0, 'rgba(255,255,255,1)')
    grad.addColorStop(0.5, 'rgba(255,255,255,0.4)')
    grad.addColorStop(1, 'rgba(255,255,255,0)')
    g.fillStyle = grad
    g.fillRect(0, 0, s, s)
    const t = new THREE.CanvasTexture(c)
    t.colorSpace = THREE.SRGBColorSpace
    track(t)
    return t
  })()

  const mat = new THREE.PointsMaterial({
    size: 0.34,
    vertexColors: true,
    sizeAttenuation: true,
    transparent: true,
    opacity: 0.85,
    depthWrite: false,
    map: sizeTex,
    blending: THREE.AdditiveBlending
  })
  track(geo, mat)
  const points = new THREE.Points(geo, mat)
  points.frustumCulled = false
  return points
}

/* ============================================================
   流星：带拖尾渐变 + 随机入射角
   ============================================================ */
const METEOR_VERTEX = `
  attribute float aT;
  varying float vT;
  void main() {
    vT = aT;
    gl_Position = projectionMatrix * modelViewMatrix * vec4(position, 1.0);
    gl_PointSize = mix(1.0, 5.0, 1.0 - aT);
  }`

const METEOR_FRAGMENT = `
  varying float vT;
  void main() {
    // 头部亮、尾部透明
    float a = pow(1.0 - vT, 1.6);
    vec3 head = vec3(1.0, 0.98, 0.94);
    vec3 tail = vec3(0.55, 0.78, 1.0);
    gl_FragColor = vec4(mix(tail, head, a) * a, a);
  }`

function buildMeteors(poolSize) {
  const group = new THREE.Group()
  const pool = []
  const SEG = 26
  for (let i = 0; i < poolSize; i++) {
    const positions = new Float32Array(SEG * 3)
    const ts = new Float32Array(SEG)
    for (let s = 0; s < SEG; s++) ts[s] = s / (SEG - 1)
    const geo = new THREE.BufferGeometry()
    geo.setAttribute('position', new THREE.BufferAttribute(positions, 3))
    geo.setAttribute('aT', new THREE.BufferAttribute(ts, 1))
    const mat = new THREE.ShaderMaterial({
      vertexShader: METEOR_VERTEX,
      fragmentShader: METEOR_FRAGMENT,
      transparent: true,
      depthWrite: false,
      blending: THREE.AdditiveBlending
    })
    track(geo, mat)
    const points = new THREE.Points(geo, mat)
    points.frustumCulled = false
    points.userData = {
      active: false,
      wait: 1.5 + Math.random() * 6,
      life: 0,
      head: new THREE.Vector3(),
      dir: new THREE.Vector3(),
      speed: 0,
      len: 0
    }
    group.add(points)
    pool.push(points)
  }
  return { group, pool }
}

/* ============================================================
   通用柔边圆点贴图
   ============================================================ */
// ⚠️ PointsMaterial **不设 map 时画的是正方形**，直接用在尘埃/彗尾上会满屏小方块
//    （小行星带就是因为设了 map 才正常）。要圆点就得给一张径向渐变贴图。
function radialSpriteTexture() {
  const s = 64
  const c = document.createElement('canvas')
  c.width = c.height = s
  const g = c.getContext('2d')
  const grad = g.createRadialGradient(s / 2, s / 2, 0, s / 2, s / 2, s / 2)
  grad.addColorStop(0.0, 'rgba(255,255,255,1)')
  grad.addColorStop(0.35, 'rgba(255,255,255,0.62)')
  grad.addColorStop(1.0, 'rgba(255,255,255,0)')
  g.fillStyle = grad
  g.fillRect(0, 0, s, s)
  const t = new THREE.CanvasTexture(c)
  t.colorSpace = THREE.SRGBColorSpace
  track(t)
  return t
}

/* ============================================================
   远景星云：把天球从"一整片均匀纯黑"变成有层次的深空
   ============================================================ */
// 星云贴图走等距柱状投影（equirect），贴在下面那个大球的内表面。
// ⚠️ 经度方向必须用 cos/sin 映射进噪声域。直接把 u 当 x 坐标采样的话，
//    u=0 与 u=1 落在噪声的不同位置，天球上会出现一条笔直的接缝竖线。
function bakeNebulaTexture(W = 512, H = 256) {
  const c = document.createElement('canvas')
  c.width = W
  c.height = H
  const g = c.getContext('2d')
  const img = g.createImageData(W, H)
  const d = img.data
  const { fbm } = makeNoise2D(90210)

  // 三层不同尺度 + 不同颜色，叠出云雾的层次感
  const layers = [
    { k: 2.2, amp: 1.30, col: [0.40, 0.20, 0.70] },  // 大尺度：紫
    { k: 4.6, amp: 0.90, col: [0.12, 0.44, 0.78] },  // 中尺度：蓝青
    { k: 9.5, amp: 0.48, col: [0.68, 0.24, 0.42] }   // 小尺度：品红
  ]

  for (let y = 0; y < H; y++) {
    const v = y / (H - 1)
    // 银河带压在赤道附近，用高斯衰减约束成一条带，别糊满全天
    const band = Math.exp(-Math.pow((v - 0.5) / 0.30, 2))
    for (let x = 0; x < W; x++) {
      const ang = (x / (W - 1)) * Math.PI * 2
      let r = 0, gg = 0, b = 0
      for (let li = 0; li < layers.length; li++) {
        const L = layers[li]
        const cx = Math.cos(ang) * L.k * 0.62
        const cz = Math.sin(ang) * L.k * 0.62
        const n = fbm(cx + v * L.k * 2.6, cz + v * L.k * 1.15, 3)
        // 二次衰减：用 max(0, n - 阈值) 直接截断的话，云的边缘会是一条硬边，
        // 放大到天球上就是一块块"色斑"。平方之后边缘平滑淡出，才像雾。
        const q = Math.max(0, n - 0.34)
        const m = q * q * 3.6 * L.amp * band
        r += L.col[0] * m
        gg += L.col[1] * m
        b += L.col[2] * m
      }
      const i = (y * W + x) * 4
      d[i] = Math.min(255, r * 255)
      d[i + 1] = Math.min(255, gg * 255)
      d[i + 2] = Math.min(255, b * 255)
      d[i + 3] = 255
    }
  }
  g.putImageData(img, 0, 0)
  return c
}

// 必须用自发光材质（Basic）：星云不该被太阳照亮，也不该参与受光计算。
// 半径落在星点球壳（150~410）之外、相机远平面（900）之内。
const NEBULA_RADIUS = 620

function buildNebula() {
  const mat = new THREE.MeshBasicMaterial({
    map: canvasTexture(bakeNebulaTexture()),
    side: THREE.BackSide,
    transparent: true,
    opacity: 1.0,
    depthWrite: false,
    blending: THREE.AdditiveBlending
  })
  const geo = new THREE.SphereGeometry(NEBULA_RADIUS, 48, 32)
  track(geo, mat)
  const mesh = new THREE.Mesh(geo, mat)
  mesh.frustumCulled = false
  return mesh
}

/* ============================================================
   近景漂浮尘埃：相机一转就有强视差，画面立刻"活"起来
   ============================================================ */
// 尘埃自己写 shader 而不是用 PointsMaterial，就为了那个尺寸托底 ——
// 尘埃铺得比星点还散，用 PointsMaterial 的话远端的点会掉到 1px 以下，
// 又变成"忽有忽无"。这里的 floor(max(...))+0.5 和星点是同一套写法。
const DUST_VERTEX = `
  attribute vec3 aColor;
  uniform float uPixelRatio;
  varying vec3 vColor;
  void main() {
    vColor = aColor;
    vec4 mv = modelViewMatrix * vec4(position, 1.0);
    float s = 0.44 * uPixelRatio * (260.0 / max(0.001, -mv.z));
    gl_PointSize = floor(max(s, uPixelRatio * 1.4)) + 0.5;
    gl_Position = projectionMatrix * mv;
  }`

const DUST_FRAGMENT = `
  uniform sampler2D uTex;
  varying vec3 vColor;
  void main() {
    vec4 t = texture2D(uTex, gl_PointCoord);
    if (t.a < 0.01) discard;
    gl_FragColor = vec4(vColor, 1.0) * t;
  }`

function buildDust(count) {
  const positions = new Float32Array(count * 3)
  const colors = new Float32Array(count * 3)
  const rnd = mulberry32(20260916)
  for (let i = 0; i < count; i++) {
    // 略扁的球壳（贴近轨道面），半径 6~58：远的当背景颗粒，近的贴脸划过
    const r = 6 + Math.pow(rnd(), 0.65) * 52
    const th = rnd() * Math.PI * 2
    const phi = Math.acos(rnd() * 2 - 1)
    positions[i * 3] = r * Math.sin(phi) * Math.cos(th)
    positions[i * 3 + 1] = r * Math.cos(phi) * 0.55
    positions[i * 3 + 2] = r * Math.sin(phi) * Math.sin(th)
    // 明暗拉开差距，否则会像蒙了一层均匀噪点
    const b = 0.20 + Math.pow(rnd(), 2.0) * 0.85
    colors[i * 3] = b * 0.92
    colors[i * 3 + 1] = b * 0.96
    colors[i * 3 + 2] = b
  }
  const geo = new THREE.BufferGeometry()
  geo.setAttribute('position', new THREE.BufferAttribute(positions, 3))
  geo.setAttribute('aColor', new THREE.BufferAttribute(colors, 3))
  const mat = new THREE.ShaderMaterial({
    uniforms: {
      uPixelRatio: { value: perf.pixelRatio },
      uTex: { value: radialSpriteTexture() }
    },
    vertexShader: DUST_VERTEX,
    fragmentShader: DUST_FRAGMENT,
    transparent: true,
    depthWrite: false,
    blending: THREE.AdditiveBlending
  })
  track(geo, mat)
  const points = new THREE.Points(geo, mat)
  points.frustumCulled = false
  return points
}

/* ============================================================
   彗星：长周期椭圆轨道 + 永远背离太阳的离子尾
   ============================================================ */
const COMET_TAIL_POINTS = 320

function buildComet() {
  const group = new THREE.Group()

  const nucleus = new THREE.Mesh(
    new THREE.SphereGeometry(0.22, 16, 12),
    new THREE.MeshBasicMaterial({ color: 0xeaf6ff })
  )
  // 彗发用 Sprite + 径向渐变，**不要用球体**：球体在屏幕上是一个边缘生硬的
  // 实心圆盘，叠上中间的亮核就成了一枚"光圈"，很假。
  // Sprite 永远正对相机，渐变贴图让它中心亮、向外柔和淡出 —— 那才是弥散的光雾。
  const coma = new THREE.Sprite(new THREE.SpriteMaterial({
    map: radialSpriteTexture(),
    color: 0x9ad8ff,
    transparent: true,
    opacity: 0.62,
    blending: THREE.AdditiveBlending,
    depthWrite: false
  }))
  coma.scale.set(3.6, 3.6, 1)
  group.add(nucleus, coma)
  track(nucleus.geometry, nucleus.material, coma.material)

  // 彗尾：一束粒子，越靠尾端越淡、越散
  const N = COMET_TAIL_POINTS
  const positions = new Float32Array(N * 3)
  const colors = new Float32Array(N * 3)
  const seeds = []
  for (let i = 0; i < N; i++) {
    const t = i / (N - 1)
    seeds.push({ t, s1: Math.random() * 2 - 1, s2: Math.random() * 2 - 1 })
    const f = Math.pow(1 - t, 1.5)
    colors[i * 3] = 0.42 * f + 0.06
    colors[i * 3 + 1] = 0.70 * f + 0.10
    colors[i * 3 + 2] = 1.00 * f + 0.16
  }
  const tailGeo = new THREE.BufferGeometry()
  tailGeo.setAttribute('position', new THREE.BufferAttribute(positions, 3))
  tailGeo.setAttribute('color', new THREE.BufferAttribute(colors, 3))
  // 粒子加密一倍多之后沿尾巴严重重叠，加法混合会直接把亮度堆上去，
  // 所以 size 和 opacity 都要相应压下来，否则尾巴会过曝成一根白棒。
  const tailMat = new THREE.PointsMaterial({
    size: 0.55, map: radialSpriteTexture(), vertexColors: true, sizeAttenuation: true,
    transparent: true, opacity: 0.30, depthWrite: false,
    blending: THREE.AdditiveBlending
  })
  track(tailGeo, tailMat)
  const tail = new THREE.Points(tailGeo, tailMat)
  tail.frustumCulled = false
  group.add(tail)

  return {
    group, nucleus, coma, tail, seeds,
    posAttr: tailGeo.attributes.position,
    // ⚠️ 初始 angle 是挑过的，别随便改：
    //    · 太靠近 0（近日点）会贴着太阳，看起来像"太阳上长出来一块"；
    //    · 而这个角度的位置向量（= 尾巴方向）要尽量**垂直于视线**，
    //      否则离子尾正对镜头被压成一个点，等于白做。
    //    1.05 同时满足这两条：离太阳约 10.8，尾巴在屏幕上还有 87% 的长度。
    //    speed 也压慢了，让它能在画面里多待一会儿。
    angle: 1.05, a: 22, e: 0.60, incl: 0.40, speed: 0.035
  }
}

// 临时向量提到模块级，避免每帧 new
const cometDir = new THREE.Vector3()
const cometSide = new THREE.Vector3()
const cometUp = new THREE.Vector3()
const cometWorldUp = new THREE.Vector3(0, 1, 0)

function updateComet(c, dt) {
  // 近日点走得更快，有一点开普勒的味道
  c.angle += dt * c.speed * (1.0 + Math.cos(c.angle) * 0.85)
  const r = (c.a * (1 - c.e * c.e)) / (1 + c.e * Math.cos(c.angle))
  const px = Math.cos(c.angle) * r
  const pz = Math.sin(c.angle) * r
  const py = pz * Math.sin(c.incl)
  const pz2 = pz * Math.cos(c.incl)

  c.nucleus.position.set(px, py, pz2)
  c.coma.position.set(px, py, pz2)

  // 太阳在原点，所以"位置向量"本身就是反日方向
  cometDir.set(px, py, pz2).normalize()
  const near = 1 - r / c.a        // 0 = 远日点，1 = 近日点
  const L = 3.2 + near * 7.5      // 越靠近太阳尾巴越长

  cometSide.crossVectors(cometWorldUp, cometDir)
  if (cometSide.lengthSq() < 1e-6) cometSide.set(1, 0, 0)
  cometSide.normalize()
  cometUp.crossVectors(cometDir, cometSide).normalize()

  const arr = c.posAttr.array
  for (let i = 0; i < c.seeds.length; i++) {
    const s = c.seeds[i]
    const spread = s.t * s.t * 1.5      // 尾端散得更开
    const dx = cometSide.x * s.s1 * spread + cometUp.x * s.s2 * spread
    const dy = cometSide.y * s.s1 * spread + cometUp.y * s.s2 * spread
    const dz = cometSide.z * s.s1 * spread + cometUp.z * s.s2 * spread
    arr[i * 3] = px + cometDir.x * s.t * L + dx
    arr[i * 3 + 1] = py + cometDir.y * s.t * L + dy
    arr[i * 3 + 2] = pz2 + cometDir.z * s.t * L + dz
  }
  c.posAttr.needsUpdate = true
}

/* ============================================================
   人造探测器：画面里唯一的"人类痕迹"，最能打破纯自然风景的平淡
   ============================================================ */
function buildProbe() {
  const group = new THREE.Group()

  // 都要给足 emissive：这片背景本来就是深空，一个只有反射光的金属体
  // 等于一个黑剪影，直接融进背景里看不见（行星当初也是这个问题）。
  const metal = new THREE.MeshStandardMaterial({
    color: 0xd2dae8, roughness: 0.38, metalness: 0.7,
    emissive: 0x55658a, emissiveIntensity: 1.0
  })
  const panel = new THREE.MeshStandardMaterial({
    color: 0x5a8ee0, roughness: 0.26, metalness: 0.5,
    emissive: 0x1e3a72, emissiveIntensity: 1.0
  })
  track(metal, panel)

  const parts = []
  parts.push(new THREE.Mesh(new THREE.BoxGeometry(0.21, 0.21, 0.82), metal))
  const bus = new THREE.Mesh(new THREE.BoxGeometry(0.37, 0.37, 0.19), metal)
  bus.position.z = 0.34
  parts.push(bus)
  // 两侧太阳能板——方板轮廓是"人造物"最容易被认出来的特征
  const panelGeo = new THREE.BoxGeometry(0.97, 0.033, 0.42)
  const pL = new THREE.Mesh(panelGeo, panel)
  pL.position.x = -0.64
  const pR = new THREE.Mesh(panelGeo, panel)
  pR.position.x = 0.64
  parts.push(pL, pR)
  // 抛物面天线（圆锥近似），朝 -Z
  const dish = new THREE.Mesh(new THREE.ConeGeometry(0.27, 0.16, 16), metal)
  dish.position.z = -0.52
  dish.rotation.x = -Math.PI / 2
  parts.push(dish)
  const mast = new THREE.Mesh(new THREE.CylinderGeometry(0.015, 0.015, 0.42, 6), metal)
  mast.position.z = -0.31
  mast.rotation.x = Math.PI / 2
  parts.push(mast)

  for (const m of parts) {
    group.add(m)
    track(m.geometry)
  }

  // 自发光信号灯：深空里纯靠反射光的金属体很容易被背景吃掉，
  // 挂一颗亮绿的小灯，一眼就能认出这里有个"人造物"。
  const beacon = new THREE.Mesh(
    new THREE.SphereGeometry(0.17, 10, 8),
    new THREE.MeshBasicMaterial({ color: 0x8effc8 })
  )
  beacon.position.set(0, 0, 0.62)
  group.add(beacon)
  track(beacon.geometry, beacon.material)

  // 初始机位是固定的（azimuth -0.165 / polar 0.201 / distance 37.2 + FRAMING_OFFSET），
  // 这组 baseAngle/radius/incl 是**按那组机位反算出来的屏幕位置**（约屏幕 32% 宽 / 70% 高，
  // 太阳左下、远离登录卡片），保证刷新第一眼就能看见。
  return { group, beacon, baseAngle: 1.37, radius: 22.6, incl: 0.35, spin: 0.28 }
}

function updateProbe(p, dt, elapsed) {
  // ⚠️ 这里刻意**不用累积角度**。用 `angle += dt * speed` 的话，页面加载完十几秒
  //    它就绕出去几十度，直接滑到视野外 —— 之前"探测器怎么也看不见"就是这么丢的
  //    （它其实一直在正常渲染，只是跑出画面了）。
  //    改成"基位 + 小幅摆动"：永远待在按初始机位反算出来的那个可见位置上。
  const a = p.baseAngle + Math.sin(elapsed * 0.13) * 0.22
  const x = Math.cos(a) * p.radius
  const z = Math.sin(a) * p.radius
  p.group.position.set(x, z * Math.sin(p.incl), z * Math.cos(p.incl))
  p.group.rotation.y += dt * p.spin
  p.group.rotation.z = Math.sin(elapsed * 0.42) * 0.35
}

/* ============================================================
   太阳几何常量（模块级，单一真源）
   ⚠️ 必须在模块顶层声明：行星轨道的约束校验和 onMounted 里的构建都要用它，
   曾经把它写成 onMounted 里的局部变量、又在模块顶层引用，导致整个模块
   求值即抛错、3D 场景完全不加载（页面只剩表单）。
   ============================================================ */
const SUN_RADIUS = 4.8

// 行星自发光强度：让行星的固有色透出来，暗面不至于糊成黑块。
// 太阳是唯一光源、按 1/r² 衰减，外围行星照度天生就低，光靠物理光照看不清。
// 调「行星亮度」就调它（配合 onMounted 里那盏 AmbientLight，两者只管行星，
// 不会动到太阳/星点/日冕 —— 那些是自发光材质，不受光照影响）。
const PLANET_GLOW = 0.26
// 日冕最外层壳的半径倍数（与下方 CORONA_LAYERS 保持一致）
const CORONA_OUTER_MULT = Math.max(...CORONA_LAYERS.map(l => l.mult))
const CORONA_OUTER_RADIUS = SUN_RADIUS * CORONA_OUTER_MULT

/* ============================================================
   行星系统：轨道参数取自真实星表风格（离心率 / 倾角 / 角速度）

   ⚠️ 轨道半径不能随便给：日冕最外层壳半径 = 日面 4.8 × 2.80 = **13.44**。
   内行星轨道若小于这个值，它就会**整条轨道都在发光的日冕里面**，
   视觉上表现为「太阳边上长出一个条纹瘤」—— 用户截图里那个把圆的日面
   顶出凸角的东西，就是旧的 a=8.0 的 luna。
   约束一：远日点 a*(1+e) 必须 > 日冕外半径 13.44，且留安全余量。

   约束二：默认机位（距离 37.2、fov 52°、视口高 900px）下，
   轨道在屏幕上的半径 ≈ a * 12.1 px，而竖直半屏只有 450px。
   所以 a 超过约 37 就基本出画了。
   → 因此内边界抬到 15.2（近日点 14.29，比日冕外缘 13.44 多出 0.85 余量），
     外边界压到 36.8，把 8 颗行星的轨道压进 15.2~36.8 这个区间，等比缩放而已。
   ============================================================ */
const PLANET_DEFS = [
  {
    name: 'luna', r: 0.5, a: 15.2, e: 0.06, incl: 0.09, speed: 0.62,
    kind: SURFACE_KIND.ROCKY,
    palette: { base: '#9a8f80', dark: '#4c453d', light: '#d8cfc0' },
    atmosphere: null, tilt: 0.04
  },
  {
    name: 'venus', r: 0.92, a: 17.5, e: 0.02, incl: -0.13, speed: 0.44,
    kind: SURFACE_KIND.DESERT,
    palette: { base: '#d8b678', dark: '#8a6a38', light: '#f2dfae' },
    atmosphere: '#e8c88a', tilt: 0.05
  },
  {
    name: 'terra', r: 1.06, a: 20.6, e: 0.03, incl: 0.06, speed: 0.33,
    kind: SURFACE_KIND.EARTHLIKE,
    palette: { base: '#4a7a52', dark: '#2b3f2e', light: '#cfd8c0', accent: '#16355e' },
    atmosphere: '#5da4e8', tilt: 0.41
  },
  {
    name: 'mars', r: 0.74, a: 23.6, e: 0.09, incl: -0.19, speed: 0.25,
    kind: SURFACE_KIND.ROCKY,
    palette: { base: '#b5623c', dark: '#5e2f1e', light: '#e0a074' },
    atmosphere: '#c87a4a', tilt: 0.44
  },
  {
    name: 'jupiter', r: 2.55, a: 27.2, e: 0.05, incl: 0.15, speed: 0.166,
    kind: SURFACE_KIND.BANDED,
    palette: { base: '#c8a578', dark: '#7a5433', light: '#f0dfc4' },
    atmosphere: '#e0b888', tilt: 0.05
  },
  {
    name: 'saturn', r: 2.18, a: 30.8, e: 0.06, incl: -0.11, speed: 0.124,
    kind: SURFACE_KIND.BANDED,
    palette: { base: '#d9c08c', dark: '#8e7444', light: '#f5e8c4' },
    atmosphere: '#e8d29c', ring: true, tilt: 0.47
  },
  {
    name: 'uranus', r: 1.55, a: 34.0, e: 0.04, incl: 0.20, speed: 0.092,
    kind: SURFACE_KIND.ICY,
    palette: { base: '#8fd0d4', dark: '#3d7078', light: '#d6f2f4' },
    atmosphere: '#87d4dc', tilt: 1.71
  },
  {
    name: 'neptune', r: 1.48, a: 36.8, e: 0.03, incl: -0.07, speed: 0.073,
    kind: SURFACE_KIND.ICY,
    palette: { base: '#3f62b8', dark: '#1c2f66', light: '#8aa8e8' },
    atmosphere: '#5a86e0', tilt: 0.49
  }
]

// 开发期断言：任何行星只要"近日点"落进日冕外缘之内，就会在画面上
// 变成贴着太阳的一团发光凸起，把圆的日面顶出角来。这种问题肉眼很晚才
// 发现（要等到那颗行星恰好转到日面边缘），所以这里直接前置拦住。
// 只在开发模式报错，生产构建会被 tree-shake 掉。
if (import.meta.env?.DEV) {
  for (const def of PLANET_DEFS) {
    const perihelion = def.a * (1 - def.e)
    if (perihelion <= CORONA_OUTER_RADIUS) {
      console.error(
        `[SolarSystemBackground] 行星 "${def.name}" 的近日点 ${perihelion.toFixed(2)} ` +
        `落在日冕外缘 ${CORONA_OUTER_RADIUS.toFixed(2)} 之内，` +
        `会在视觉上与太阳重合成凸起。请增大 a 或减小日冕倍率。`
      )
    }
  }
}

// 轨道线：按离心率画真实椭圆，带亮度渐变
function buildOrbitLine(def) {
  const SEG = 320
  const pts = new Float32Array((SEG + 1) * 3)
  const alphas = new Float32Array(SEG + 1)
  const b = def.a * Math.sqrt(1 - def.e * def.e)
  for (let i = 0; i <= SEG; i++) {
    const th = (i / SEG) * Math.PI * 2
    const x = Math.cos(th) * def.a
    const z = Math.sin(th) * b
    // 椭圆中心偏移（太阳在焦点上）
    const offset = def.a * def.e
    pts[i * 3] = x - offset
    pts[i * 3 + 1] = 0
    pts[i * 3 + 2] = z
    // 轨道线亮度沿周向轻微变化，避免死板的均匀线
    alphas[i] = 0.35 + 0.65 * (0.5 + 0.5 * Math.sin(th * 1.0 + def.speed * 20))
  }
  const geo = new THREE.BufferGeometry()
  geo.setAttribute('position', new THREE.BufferAttribute(pts, 3))
  geo.setAttribute('aAlpha', new THREE.BufferAttribute(alphas, 1))
  const mat = new THREE.ShaderMaterial({
    uniforms: { uColor: { value: new THREE.Color(0xe8883c) } },
    vertexShader: `
      attribute float aAlpha;
      varying float vAlpha;
      void main() {
        vAlpha = aAlpha;
        gl_Position = projectionMatrix * modelViewMatrix * vec4(position, 1.0);
      }`,
    fragmentShader: `
      uniform vec3 uColor;
      varying float vAlpha;
      void main() {
        gl_FragColor = vec4(uColor * vAlpha, vAlpha * 0.5);
      }`,
    transparent: true,
    depthWrite: false,
    blending: THREE.AdditiveBlending
  })
  track(geo, mat)
  const line = new THREE.Line(geo, mat)
  line.frustumCulled = false
  return line
}

/* ============================================================
   主流程

   ⚠️ 性能铁律：这个场景的构建是**重活**（太阳米粒图 2048×1024 逐像素、
   8 颗行星各一张 1024×512 逐像素噪声图、2.6 万星点、银河、小行星带）。
   如果全部塞进 onMounted 同步跑，主线程会被独占 1~2 秒，
   表现就是"每次进页面都卡一会才进去"（用户原话）。
   → 所以拆成三段，每段之间 yieldFrame() 让出主线程：
       1) 渲染器 + 相机 + 光照 + CSS 兜底色   —— 立即可见
       2) 星点 / 银河 / 太阳                    —— 画面主体成形
       3) 行星 / 小行星带 / 流星 / 泛光后期     —— 补齐细节
   每段结束后立刻渲染一帧，用户看到的是"逐步显现"而不是"卡死"。
   ============================================================ */
onMounted(async () => {
  const canvas = canvasRef.value
  renderer = new THREE.WebGLRenderer({
    canvas,
    // ⚠️ 必须 false：antialias 只作用于默认 framebuffer，而只要不是低端设备就一定
    //    会挂 EffectComposer（bloom 的条件与它完全一致）——画面先渲到离屏 RT，
    //    默认 framebuffer 只在最后一道全屏 pass 用一次，这个 MSAA 纯属白付开销。
    //    抗锯齿实际由球体高细分 + 链路末尾的 FXAA pass 负责（见 MSAA_SAMPLES 处的说明，
    //    composer 的 RT **不能**开 samples，那会让输出间歇性全黑）。
    antialias: false,
    alpha: false,
    powerPreference: 'high-performance',
    stencil: false
  })
  renderer.setClearColor(0x010103, 1)
  renderer.setPixelRatio(perf.pixelRatio)
  // 物理正确的光照单位，ACES 电影级色调映射
  renderer.toneMapping = THREE.ACESFilmicToneMapping
  renderer.toneMappingExposure = 1.0
  renderer.outputColorSpace = THREE.SRGBColorSpace

  scene = new THREE.Scene()
  camera = new THREE.PerspectiveCamera(52, 1, 0.1, 900)
  // 初始机位与下方 orbit 的基础状态保持一致，避免首帧跳变
  camera.position.set(-6, 7.4, 36.2)

  // SUN_RADIUS 已提升到模块顶层（行星轨道约束校验也要用），此处不再重复声明
  const sunPosition = new THREE.Vector3(0, 0, 0)

  // ===== 光照 =====
  // 太阳作为唯一主光源：点光源 + 物理衰减，产生真实的晨昏线
  const sunLight = new THREE.PointLight(0xfff2dc, 2200, 0, 2)
  sunLight.position.copy(sunPosition)
  scene.add(sunLight)
  // 环境光：只保证背阳面不死黑（真实太空的星光级照明）。
  // ⚠️ 调这里就等于调「行星亮度」—— 太阳/星点/银河/日冕用的都是自发光材质，
  //    完全不受环境光影响，所以往这儿加亮是**只提行星**最安全的旋钮。
  scene.add(new THREE.AmbientLight(0x30405e, 1.15))

  // ===== 轨道相机状态 =====
  // 轨道状态：azimuth 方位角、polar 极角（俯仰）、distance 相机到太阳的距离
  const orbit = {
    azimuth: -0.165,       // 当前方位角
    polar: 0.201,          // 当前极角
    distance: 37.2,        // 当前距离
    targetAzimuth: -0.165, // 拖拽目标值
    targetPolar: 0.201,
    targetDistance: 37.2
  }
  // 极角限制：避免转到正下方钻到轨道面以下，或翻到正上方造成万向锁
  const POLAR_MIN = -0.22
  const POLAR_MAX = 1.18
  const DISTANCE_MIN = 14
  const DISTANCE_MAX = 88
  // 自动漂移基准（用户未操作时相机缓慢游移，保持电影感）
  const AUTO = { azimuth: -0.165, polar: 0.201, distance: 37.2 }
  let idleTime = 0

  // 构图侧移：把太阳推离画面右半边。右侧要留给登录表单和压暗遮罩，
  // 太阳停在正中会被遮罩切掉一半，看着像"平的多边形太阳"。
  // 单位为世界单位，会随相机距离等比缩放，保证不同缩放下构图一致。
  const FRAMING_OFFSET = 13.5

  const smooth = { x: 0, y: 0 }
  const clock = new THREE.Clock()
  const tmpVec = new THREE.Vector3()

  // 逐帧把相机摆到当前轨道位置（构建各阶段结束后都要调一次，保证渲染不跑偏）
  function updateCamera() {
    if (!camera) return
    const cp = Math.cos(orbit.polar)
    camera.position.set(
      Math.sin(orbit.azimuth) * cp * orbit.distance,
      Math.sin(orbit.polar) * orbit.distance,
      Math.cos(orbit.azimuth) * cp * orbit.distance
    )
    // 构图偏移：把太阳推到画面左侧的空区，避开右侧登录卡与其压暗遮罩。
    // 否则右半屏的 .universe-shell::after 会直接盖在太阳上，把圆的日面切成"半个"。
    // 用相机右向量做侧移，保证任何方位角下偏移方向都稳定。
    // 视线中心朝 +right 偏移 => 太阳在屏幕上相对左移（相机看向右边，物体就靠左）。
    const framing = FRAMING_OFFSET * (orbit.distance / AUTO.distance)
    const rightX = Math.cos(orbit.azimuth)
    const rightZ = -Math.sin(orbit.azimuth)
    const parallaxScale = orbit.distance / AUTO.distance
    camera.lookAt(
      rightX * framing + smooth.x * 1.9 * parallaxScale,
      -smooth.y * 1.4 * parallaxScale,
      rightZ * framing
    )
  }

  // 立即渲染一帧（不依赖 tick 循环，构建阶段用它推进画面）
  function paintOnce() {
    if (destroyed || !renderer) return
    updateCamera()
    if (composer) composer.render()
    else renderer.render(scene, camera)
  }

  // ---------- 尺寸 ----------
  function resize() {
    const w = canvas.clientWidth || window.innerWidth
    const h = canvas.clientHeight || window.innerHeight
    renderer.setSize(w, h, false)
    camera.aspect = w / h
    camera.updateProjectionMatrix()
    composer?.setSize(w, h)
    // FXAA 扩散半径要按真实像素（含 DPR）算
    if (fxaaPass) {
      const pr = renderer.getPixelRatio()
      fxaaPass.material.uniforms.resolution.value.set(1 / (w * pr), 1 / (h * pr))
    }
  }

  /* ---------- 抗锯齿：绝对不要给 composer 的 target 开 MSAA ---------- */
  // 背景：EffectComposer 会把画面先渲到离屏 RenderTarget，这会**丢弃** renderer 的
  // antialias 设置，太阳/行星的圆形轮廓因此有锯齿（看着像多边形）。直觉解法是给
  // 离屏 target 开 WebGL2 的 MSAA（samples = 4）。
  //
  // ⚠️⚠️ 别这么做。真机 GPU 实测（相机静止、零输入、逐帧读最终输出）：
  //     有 MSAA：亮度序列 0 0 0 10.5 0 0 10.6 0 0 0 10.7 …（359 次采样，196 次跳变）
  //     无 MSAA：10.44 10.45 10.46 … 10.7        （361 次采样，0 次跳变）
  //   composer 的 renderTarget 一旦 samples > 0，最终输出就会**间歇性变成纯黑**。
  //   用户看到的就是「行星全部加载完（= 泛光后期挂上）之后，所有天体一直在闪」，
  //   而且**不拖鼠标也闪**（相机静止同样复现）。
  //   事后改 samples 属性、或构造时传一个带 samples 的 RT，两种写法都一样黑。
  //
  //   抗锯齿改由这两条兜底，画质够用：
  //     · 球体高细分（日面 256 段 / 日冕 192 / 行星 96×64）
  //     · 链路末尾的 FXAA pass
  const MSAA_SAMPLES = 0

  /* ---------- 交互：轨道相机（拖拽旋转 + 滚轮缩放）+ 鼠标视差 ---------- */

  // 判断事件是否落在登录卡片等前景交互元素上；这些区域不接管拖拽，
  // 否则在表单里选中文本会连带旋转场景，滚轮也无法滚动表单。
  function isOverForeground(target) {
    return !!(target && target.closest && target.closest('.login-card'))
  }

  // 统一负责视差与拖拽，用局部坐标记录上一帧位置，避免污染 window
  const local = { x: 0, y: 0 }
  mouseHandler = e => {
    const nx = (e.clientX / window.innerWidth - 0.5) * 2
    const ny = (e.clientY / window.innerHeight - 0.5) * 2
    pointer.x = nx
    pointer.y = ny
    if (drag.active) {
      // 水平拖动改方位角、垂直拖动改极角；系数按屏幕尺寸归一，手感与分辨率无关
      const k = 1 / Math.max(360, window.innerWidth)
      orbit.targetAzimuth -= (e.clientX - local.x) * k * 3.2
      orbit.targetPolar += (e.clientY - local.y) * k * 2.2
      orbit.targetPolar = Math.max(POLAR_MIN, Math.min(POLAR_MAX, orbit.targetPolar))
      idleTime = 0
    }
    local.x = e.clientX
    local.y = e.clientY
  }
  pointerDownHandler = e => {
    // 只响应主键，避免右键菜单/中键干扰；表单区域内不接管拖拽
    if (e.button !== 0) return
    if (isOverForeground(e.target)) return
    drag.active = true
    local.x = e.clientX
    local.y = e.clientY
    document.body.style.cursor = 'grabbing'
  }
  pointerUpHandler = () => {
    if (!drag.active) return
    drag.active = false
    document.body.style.cursor = ''
    idleTime = 0
  }
  // 滚轮缩放：以指数步进保证远近手感一致，并阻止页面跟随滚动
  wheelHandler = e => {
    // 表单/卡片区域内保留原生滚动行为
    if (isOverForeground(e.target)) return
    e.preventDefault()
    const step = Math.exp(e.deltaY * 0.0012)
    orbit.targetDistance = Math.max(
      DISTANCE_MIN,
      Math.min(DISTANCE_MAX, orbit.targetDistance * step)
    )
    idleTime = 0
  }

  window.addEventListener('mousemove', mouseHandler, { passive: true })
  window.addEventListener('pointerdown', pointerDownHandler, { passive: true })
  window.addEventListener('pointerup', pointerUpHandler, { passive: true })
  // 必须非 passive 才能调用 preventDefault 阻止页面滚动
  window.addEventListener('wheel', wheelHandler, { passive: false })

  visibilityHandler = () => {
    if (document.hidden) {
      running = false
      cancelAnimationFrame(rafId)
    } else if (!running && sceneRefs.starPoints) {
      running = true
      clock.getDelta()
      rafId = requestAnimationFrame(tick)
    }
  }
  document.addEventListener('visibilitychange', visibilityHandler)

  // 首屏同步部分到此为止：先量尺寸、画一帧底色，让 canvas 立刻有内容
  resize()
  resizeObserver = new ResizeObserver(resize)
  resizeObserver.observe(canvas)
  paintOnce()

  /* ============================================================
     阶段 1：星点 + 银河 + 太阳
     ============================================================ */
  await yieldFrame()
  if (destroyed) return

  const starPoints = buildStarfield(perf.starCount)
  scene.add(starPoints)
  sceneRefs.starPoints = starPoints

  const galaxy = buildGalaxy(perf.galaxyCount)
  scene.add(galaxy.points)
  sceneRefs.galaxy = galaxy

  const sun = buildSun(SUN_RADIUS)
  scene.add(sun.group)
  sceneRefs.sun = sun

  paintOnce()

  // 核心对象就绪，可以开始跑循环了；后面的行星会陆续"长"出来
  running = true
  rafId = requestAnimationFrame(tick)

  /* ============================================================
     阶段 2：行星（每颗一张 1024×512 逐像素噪声图，是最重的一段，逐颗让出主线程）
     ============================================================ */
  const ringTex = bakeRingTexture()
  const surfaceSize = perf.surfaceSize
  const sunDir = new THREE.Vector3()

  for (const def of PLANET_DEFS) {
    if (destroyed) return
    await yieldFrame()

    // 烘焙表面（用同一次结果生成颜色图与凹凸图，避免重复计算）
    const surfaceCanvas = bakeSurface(surfaceSize, surfaceSize / 2, def.kind, def.palette, def.name.length * 977 + surfaceSize)
    const bumpCanvas = bakeBump(surfaceCanvas)
    const colorMap = canvasTexture(surfaceCanvas)
    const bumpMap = canvasTexture(bumpCanvas, { srgb: false })

    // 行星提亮。太阳是唯一光源且按 1/r² 衰减，外围行星（木星 27、土星 38 个
    // 世界单位）拿到的照度本身就低 —— 物理是对的，但屏幕上看不清。
    // 用自身颜色贴图当自发光，让固有色透出来，暗面也不再糊成一团。
    // 「行星亮度」总旋钮 = 这里的 PLANET_GLOW + 上面那盏 AmbientLight。
    const mat = new THREE.MeshStandardMaterial({
      map: colorMap,
      bumpMap,
      bumpScale: def.kind === SURFACE_KIND.BANDED ? 0.012 : 0.055,
      roughness: def.kind === SURFACE_KIND.ICY ? 0.42 : 0.92,
      metalness: 0.0,
      emissive: new THREE.Color(0xffffff),
      emissiveMap: colorMap,
      emissiveIntensity: PLANET_GLOW
    })
    track(mat)

    // 行星也按屏幕尺寸给足细分：木星在基准机位下有约 68px 屏幕半径，
    // 64 段时每条轮廓边长 6.7px，会看出明显的多边形边（与太阳同类问题）。
    // 96 段降到 4.5px，配合 MSAA 后肉眼无感。
    const mesh = new THREE.Mesh(new THREE.SphereGeometry(def.r, 96, 64), mat)
    track(mesh.geometry)
    mesh.rotation.z = def.tilt

    // 大气层：朝阳侧偏暖的菲涅尔边缘光
    if (def.atmosphere) {
      const atmo = planetAtmosphere(def.r, def.atmosphere, sunDir)
      mesh.add(atmo)
    }
    // 行星环
    if (def.ring) {
      mesh.add(buildPlanetRing(def.r, ringTex))
    }

    // 轨道线
    const orbitGroup = new THREE.Group()
    orbitGroup.add(buildOrbitLine(def))
    orbitGroup.rotation.x = def.incl
    orbitGroup.rotation.y = (def.name.charCodeAt(0) % 7 - 3) * 0.012
    scene.add(orbitGroup)

    // 初始相位：用名字散列，保证每次刷新构图稳定而非随机跳变
    const angle0 = ((def.name.charCodeAt(1) * 37) % 360) / 360 * Math.PI * 2

    // 轨道半长轴与半短轴（离心率）
    const semiMajor = def.a
    const semiMinor = def.a * Math.sqrt(1 - def.e * def.e)
    const focusOffset = def.a * def.e

    scene.add(mesh)

    sceneRefs.planets.push({
      mesh,
      def,
      angle: angle0,
      semiMajor,
      semiMinor,
      focusOffset,
      // 开普勒第三定律：ω ∝ a^-1.5，外行星明显更慢
      omega: def.speed,
      spin: 0.12 + (1 / Math.max(0.4, def.r)) * 0.3
    })
  }

  /* ============================================================
     阶段 3：小行星带 + 流星 + 泛光后期
     ============================================================ */
  await yieldFrame()
  if (destroyed) return

  // 小行星带位置：真实太阳系里它位于**火星(23.6)与木星(27.2)之间**。
  // 注意不能沿用旧的 21.8~25.4 —— 行星轨道整体外移后，那一段正好压在 terra 上。
  const belt = buildAsteroidBelt(perf.beltCount, 24.9, 26.1)
  scene.add(belt)
  sceneRefs.belt = belt

  const meteor = buildMeteors(perf.low ? 2 : 4)
  scene.add(meteor.group)
  sceneRefs.meteor = meteor

  // 星云先加：它是最远的一层，压在所有东西后面
  const nebula = buildNebula()
  scene.add(nebula)
  sceneRefs.nebula = nebula

  const dust = buildDust(perf.low ? 420 : 1200)
  scene.add(dust)
  sceneRefs.dust = dust

  const comet = buildComet()
  scene.add(comet.group)
  sceneRefs.comet = comet

  const probe = buildProbe()
  scene.add(probe.group)
  sceneRefs.probe = probe

  /* ---------- 泛光后期：让太阳与亮星产生真实镜头溢出 ---------- */
  // ⚠️ 关键：EffectComposer 会把画面先渲染到离屏 RenderTarget，这会**丢弃**
  //    WebGLRenderer 的 antialias（MSAA）设置 —— 即使 antialias:true 也无效。
  //    结果是太阳/行星的圆形轮廓出现明显锯齿和直边，看着像多边形。
  //    解法有两条，这里都用上：
  //      1) 让 composer 自己开多重采样（WebGL2 下 samples>0 即 MSAA）
  //      2) 末尾挂一个 FXAA 着色器做后处理抗锯齿（兜底，兼容不支持 MSAA 的设备）
  if (perf.bloom) {
    try {
      const { EffectComposer } = await import('three/examples/jsm/postprocessing/EffectComposer.js')
      const { RenderPass } = await import('three/examples/jsm/postprocessing/RenderPass.js')
      const { UnrealBloomPass } = await import('three/examples/jsm/postprocessing/UnrealBloomPass.js')
      const { ShaderPass } = await import('three/examples/jsm/postprocessing/ShaderPass.js')
      const { FXAAShader } = await import('three/examples/jsm/shaders/FXAAShader.js')
      if (destroyed) return

      // samples 必须在这里、构造 RT 时就传进去（原因见上面 MSAA_SAMPLES 的注释）。
      // 尺寸用 drawingBuffer（已含 pixelRatio），与 EffectComposer 内部默认一致。
      // samples 必须在这里、构造 RT 时就传进去（原因见上面 MSAA_SAMPLES 的注释）。
      // ⚠️ 但不要自己 new RT 传进来：EffectComposer 传外部 RT 时会把 _width 记成
      //    RT 的像素宽（1600），而 setSize/addPass 还会再乘一次 pixelRatio，
      //    于是 pass 的尺寸被放大 DPR 倍。不传 RT、只用 setSize/samples 才是安全的。
      composer = new EffectComposer(renderer)
      if (MSAA_SAMPLES > 0) {
        composer.renderTarget1.samples = MSAA_SAMPLES
        composer.renderTarget2.samples = MSAA_SAMPLES
      }

      composer.addPass(new RenderPass(scene, camera))
      bloomPass = new UnrealBloomPass(new THREE.Vector2(1, 1), 0.62, 0.72, 0.86)
      composer.addPass(bloomPass)

      // FXAA 必须放在最后一道，处理泛光之后的最终画面
      fxaaPass = new ShaderPass(FXAAShader)
      composer.addPass(fxaaPass)

      // 后期链路刚接手，尺寸与采样都要重新对齐一次
      resize()
    } catch (e) {
      composer = null
    }
  }

  // 尊重用户系统的"减少动态"偏好：只渲染一帧静态画面
  if (window.matchMedia && window.matchMedia('(prefers-reduced-motion: reduce)').matches) {
    running = false
    cancelAnimationFrame(rafId)
    paintOnce()
    return
  }

  function tick() {
    if (!running || destroyed) return
    rafId = requestAnimationFrame(tick)
    const dt = Math.min(clock.getDelta(), 0.05)
    const elapsed = clock.elapsedTime

    // 平滑鼠标视差
    smooth.x += (pointer.x - smooth.x) * Math.min(1, dt * 1.8)
    smooth.y += (pointer.y - smooth.y) * Math.min(1, dt * 1.8)

    // 用户松手后静置一段时间，相机缓慢回到基准机位（仅在未拖拽时漂移）
    idleTime += dt
    const idle = !drag.active && idleTime > 2.5
    if (idle) {
      // 缓慢吸收回基准，避免相机永远停在用户最后拖到的位置
      const ease = Math.min(1, dt * 0.22)
      orbit.targetAzimuth += (AUTO.azimuth - orbit.targetAzimuth) * ease
      orbit.targetPolar += (AUTO.polar - orbit.targetPolar) * ease
      orbit.targetDistance += (AUTO.distance - orbit.targetDistance) * ease
    }

    // 阻尼插值：相机始终平滑追向目标值，避免拖拽时的生硬感
    const follow = Math.min(1, dt * (drag.active ? 9 : 3.4))
    orbit.azimuth += (orbit.targetAzimuth - orbit.azimuth) * follow
    orbit.polar += (orbit.targetPolar - orbit.polar) * follow
    orbit.distance += (orbit.targetDistance - orbit.distance) * follow

    updateCamera()

    // 星空与银河缓慢自转（地球自转造成的周日运动）
    sceneRefs.starPoints.rotation.y = elapsed * 0.0032
    sceneRefs.galaxy.points.rotation.y = elapsed * 0.0024

    // 太阳
    sceneRefs.sun.sunMat.uniforms.uTime.value = elapsed
    sceneRefs.sun.sun.rotation.y = elapsed * 0.022
    const pulse = Math.sin(elapsed * 0.55) * 0.5 + Math.sin(elapsed * 1.31) * 0.5
    // 日冕脉动：只驱动各层强度 uniform。改 scale 会让「片元世界坐标 -> 屏幕径向距离」
    // 的换算失准，所以外壳几何半径恒定，呼吸感全部由 uStrength 承担。
    // 各层相位略有错开，避免整团光晕像一根弦一样同频明灭。
    sceneRefs.sun.coronaLayers.forEach((layer, i) => {
      layer.mat.uniforms.uTime.value = elapsed + i * 1.7
      layer.mat.uniforms.uStrength.value = layer.base * (1 + pulse * 0.11)
    })

    // 行星：开普勒轨道运动 + 自转 + 大气朝向太阳
    for (const p of sceneRefs.planets) {
      p.angle += p.omega * dt
      p.mesh.position.set(
        Math.cos(p.angle) * p.semiMajor - p.focusOffset,
        0,
        Math.sin(p.angle) * p.semiMinor
      )
      p.mesh.rotation.y += dt * p.spin

      // 把太阳方向传给大气着色器（在行星本地空间）
      tmpVec.copy(sunPosition).sub(p.mesh.position).normalize()
      const atmo = p.mesh.children.find(c => c.material && c.material.uniforms && c.material.uniforms.uSunDir)
      if (atmo) atmo.material.uniforms.uSunDir.value.copy(tmpVec)
    }

    // 小行星带：整体按 a^-1.5 关系分层旋转（外层慢）
    if (sceneRefs.belt) sceneRefs.belt.rotation.y = elapsed * 0.014

    // 流星
    if (sceneRefs.meteor) {
      for (const m of sceneRefs.meteor.pool) {
        const u = m.userData
        if (!u.active) {
          u.wait -= dt
          if (u.wait <= 0) {
            u.active = true
            u.life = 1
            // 入射方向：从画面上方斜射入
            const ang = Math.PI * (0.68 + Math.random() * 0.2)
            const tiltZ = (Math.random() - 0.5) * 0.6
            u.dir.set(Math.cos(ang), -Math.sin(ang) * 0.6, tiltZ).normalize()
            u.speed = 52 + Math.random() * 46
            u.len = 5 + Math.random() * 9
            u.head.set(
              -90 + Math.random() * 70,
              26 + Math.random() * 30,
              camera.position.z - 60 - Math.random() * 60
            )
          }
          continue
        }
        u.life -= dt * 0.62
        if (u.life <= 0) {
          u.active = false
          u.wait = 2.5 + Math.random() * 7
          m.visible = false
          continue
        }
        m.visible = true
        u.head.addScaledVector(u.dir, u.speed * dt)
        const pos = m.geometry.attributes.position.array
        for (let s = 0; s < pos.length / 3; s++) {
          const back = (s / (pos.length / 3 - 1)) * u.len
          pos[s * 3] = u.head.x - u.dir.x * back
          pos[s * 3 + 1] = u.head.y - u.dir.y * back
          pos[s * 3 + 2] = u.head.z - u.dir.z * back
        }
        m.geometry.attributes.position.needsUpdate = true
        m.material.opacity = Math.sin(Math.min(1, u.life) * Math.PI)
      }
    }

    // 星云：极慢自转，否则整块背景看起来是死的
    if (sceneRefs.nebula) sceneRefs.nebula.rotation.y = elapsed * 0.0011

    // 彗星：轨道推进 + 尾巴逐帧重建（背离太阳）
    if (sceneRefs.comet) updateComet(sceneRefs.comet, dt)

    // 人造探测器：在基位附近缓慢摆动 + 缓慢自转
    if (sceneRefs.probe) updateProbe(sceneRefs.probe, dt, elapsed)

    // 泛光强度随太阳脉动轻微变化，制造呼吸感
    if (bloomPass) bloomPass.strength = 0.6 + pulse * 0.05

    if (composer) composer.render()
    else renderer.render(scene, camera)
  }
})

onBeforeUnmount(() => {
  // 正在分阶段构建时被卸载（例如用户在行星烘焙完成前就登录跳走了）：
  // 置销毁标记，构建流程里的 `if (destroyed) return` 会在下一个检查点自行退出，
  // 否则后半段的 await 回来后会往已经拆掉的场景里塞对象。
  destroyed = true
  running = false
  cancelAnimationFrame(rafId)
  document.removeEventListener('visibilitychange', visibilityHandler)
  window.removeEventListener('mousemove', mouseHandler)
  window.removeEventListener('pointerdown', pointerDownHandler)
  window.removeEventListener('pointerup', pointerUpHandler)
  window.removeEventListener('wheel', wheelHandler)
  document.body.style.cursor = ''
  resizeObserver?.disconnect()
  for (const item of disposables) item.dispose?.()
  disposables = []
  sceneRefs.starPoints = null
  sceneRefs.galaxy = null
  sceneRefs.sun = null
  sceneRefs.planets = []
  sceneRefs.belt = null
  sceneRefs.meteor = null
  composer?.dispose?.()
  fxaaPass?.material?.dispose?.()
  composer = null
  bloomPass = null
  fxaaPass = null
  renderer?.dispose()
  renderer = null
  scene = null
})
</script>

<style scoped>
.universe-canvas {
  position: absolute;
  inset: 0;
  width: 100%;
  height: 100%;
  display: block;
  /* ⚠️ 必须提升为独立合成层。
     这个 canvas 每帧都在重绘；不提升的话它会被并进父层一起反复重新栅格化，
     再叠上上层 .login-card 的 backdrop-filter（那玩意儿每帧都要重新捕获它
     背后的内容做模糊），在部分驱动上就表现为**整个画面一闪一闪** ——
     连太阳、行星这些几十像素的大几何体也跟着闪，而不只是星点。 */
  transform: translateZ(0);
  backface-visibility: hidden;
}
</style>
