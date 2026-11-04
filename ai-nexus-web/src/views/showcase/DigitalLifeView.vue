<template>
  <div ref="mountRef" class="dl-view">
    <canvas ref="canvasRef" class="dl-canvas"></canvas>

    <div class="dl-header">
      <span class="dl-eyebrow">Digital Life</span>
      <h1 class="dl-title">数字生命</h1>
      <p class="dl-desc">{{ phaseText }}</p>
    </div>

    <div class="dl-toolbar">
      <button type="button" class="dl-button" @click="reEvolve">
        <span class="dl-button-key">演化</span>
        <span>重新演化</span>
      </button>
      <button type="button" class="dl-button" :class="{ active: audioMode === 'mic' }" @click="toggleMic">
        <span class="dl-button-key">音频</span>
        <span>{{ audioMode === 'mic' ? '麦克风驱动中' : '接麦克风' }}</span>
      </button>
      <button type="button" class="dl-button" :class="{ active: audioMode === 'file' }" @click="pickFile">
        <span class="dl-button-key">音频</span>
        <span>{{ audioMode === 'file' ? '停止播放' : '播放音频文件' }}</span>
      </button>
      <button v-if="audioMode === 'file'" type="button" class="dl-button" :class="{ active: !musicPlaying }" @click="toggleMusic">
        <span class="dl-button-key">音乐</span>
        <span>{{ musicPlaying ? '暂停' : '播放' }}</span>
      </button>
      <button type="button" class="dl-button" :class="{ active: paused }" @click="togglePause">
        <span class="dl-button-key">播放</span>
        <span>{{ paused ? '继续' : '暂停' }}</span>
      </button>
      <button type="button" class="dl-button" @click="toggleFullscreen">
        <span class="dl-button-key">显示</span>
        <span>全屏</span>
      </button>
      <span class="dl-stats">{{ fpsText }}</span>
    </div>

    <input ref="fileInputRef" type="file" accept="audio/*" class="dl-file" @change="onFileChange">
    <audio ref="playerRef" loop></audio>
    <div v-if="toastMsg" class="dl-toast">{{ toastMsg }}</div>
  </div>
</template>

<script setup>
import { onBeforeUnmount, onMounted, ref } from 'vue'

const mountRef = ref(null)
const canvasRef = ref(null)
const fileInputRef = ref(null)
const playerRef = ref(null)

const audioMode = ref('none')
const musicPlaying = ref(true)
const paused = ref(false)
const fpsText = ref('')
const toastMsg = ref('')
const phaseText = ref('萌芽 · 第一批细胞分裂')

// 渲染状态（非响应式）
const state = { time: 0, lookX: 0, lookY: 0.2, dist: 29, bass: 0, mid: 0, treble: 0, scale: 1, fovBase: 55 * Math.PI / 180 }
let seed = (Math.random() * 1e9) | 0

let gl = null
let rafId = 0
let resizeObserver = null
let toastTimer = null
let lastT = 0
let dtEma = 16.7
let frameCount = 0
let dragging = false
let lastX = 0
let lastY = 0

let actx = null
let analyser = null
let freqData = null
let micStream = null
let micSource = null
let fileSource = null
let fileURL = null

/* ================== 生命模拟核心（已经 life-sim-check.js 验证） ================== */
function mulberry32(a) {
  return function () {
    a |= 0; a = a + 0x6D2B79F5 | 0;
    let t = Math.imul(a ^ a >>> 15, 1 | a);
    t = t + Math.imul(t ^ t >>> 7, 61 | t) ^ t;
    return ((t ^ t >>> 14) >>> 0) / 4294967296;
  };
}

function makeNoise3(rand) {
  const perm = new Uint8Array(512);
  const p = new Uint8Array(256);
  for (let i = 0; i < 256; i++) p[i] = i;
  for (let i = 255; i > 0; i--) { const j = (rand() * (i + 1)) | 0; const t = p[i]; p[i] = p[j]; p[j] = t; }
  for (let i = 0; i < 512; i++) perm[i] = p[i & 255];
  const g = new Float32Array(256);
  for (let i = 0; i < 256; i++) g[i] = rand();
  const h = (x, y, z) => g[perm[(perm[(perm[x & 255] + y) & 255] + z) & 255]];
  const lp = (a, b, t) => a + (b - a) * t;
  return function (x, y, z) {
    const xi = Math.floor(x), yi = Math.floor(y), zi = Math.floor(z);
    let fx = x - xi, fy = y - yi, fz = z - zi;
    fx = fx * fx * (3 - 2 * fx); fy = fy * fy * (3 - 2 * fy); fz = fz * fz * (3 - 2 * fz);
    const X = xi & 255, Y = yi & 255, Z = zi & 255;
    return lp(
      lp(lp(h(X, Y, Z), h(X + 1, Y, Z), fx), lp(h(X, Y + 1, Z), h(X + 1, Y + 1, Z), fx), fy),
      lp(lp(h(X, Y, Z + 1), h(X + 1, Y, Z + 1), fx), lp(h(X, Y + 1, Z + 1), h(X + 1, Y + 1, Z + 1), fx), fy),
      fz
    );
  };
}

function createLifeSim(seedVal) {
  const rand = mulberry32(seedVal);
  const noise = makeNoise3(rand);
  const WORLD = 19;
  const NEURON_MAX = 80, SEG_MAX = 26000, TIP_MAX = 1200, PULSE_MAX = 6000, SYN_MAX = 260;
  const STEP = 0.34;
  const CELL = 2.4;
  const lerp = (a, b, t) => a + (b - a) * t;

  let simTime = 0, synCount = 0, fireCount = 0, fireEnabled = false;
  const synKeys = new Set();

  const neurons = [];   // {x,y,z, r, birth, fireT, syn:[], deg, rootSegs:[]}
  const segs = [];      // {ax,ay,az,bx,by,bz, n, parent, kids:[], leaf, syn, len, birth}
  const tips = [];      // {n, seg, px,py,pz, dx,dy,dz, life, depth}
  const pulses = [];    // {seg, s, speed}
  const fireQ = [];     // {n, t}
  const leaves = [];    // {x,y,z, n}

  const grid = new Map();
  const fdiv = (v) => Math.floor(v / CELL);
  const gkey = (x, y, z) => fdiv(x) + ',' + fdiv(y) + ',' + fdiv(z);
  function gridInsert(x, y, z, n) {
    const k = gkey(x, y, z);
    let a = grid.get(k);
    if (!a) { a = []; grid.set(k, a); }
    a.push([x, y, z, n]);
  }
  function gridNear(x, y, z, cb) {
    const cx = fdiv(x), cy = fdiv(y), cz = fdiv(z);
    for (let i = -1; i <= 1; i++) for (let j = -1; j <= 1; j++) for (let k = -1; k <= 1; k++) {
      const a = grid.get((cx + i) + ',' + (cy + j) + ',' + (cz + k));
      if (a) for (let m = 0; m < a.length; m++) cb(a[m]);
    }
  }

  const nutrient = (x, y, z) => noise(x * 0.085 + 53.7, y * 0.085 + 21.3, z * 0.085 + 77.1);
  function nutrientGrad(x, y, z, out) {
    const e = 0.7;
    out[0] = nutrient(x + e, y, z) - nutrient(x - e, y, z);
    out[1] = nutrient(x, y + e, z) - nutrient(x, y - e, z);
    out[2] = nutrient(x, y, z + e) - nutrient(x, y, z - e);
  }

  function addSeg(parent, ax, ay, az, bx, by, bz, n) {
    if (segs.length >= SEG_MAX) return -1;
    const len = Math.hypot(bx - ax, by - ay, bz - az) || 1e-4;
    segs.push({ ax, ay, az, bx, by, bz, n, parent, kids: [], leaf: true, syn: -1, len, birth: simTime });
    const id = segs.length - 1;
    if (parent >= 0) { segs[parent].leaf = false; segs[parent].kids.push(id); }
    else neurons[n].rootSegs.push(id);
    return id;
  }

  function addNeuron(x, y, z) {
    if (neurons.length >= NEURON_MAX || segs.length > SEG_MAX * 0.85) return -1;
    neurons.push({ x, y, z, r: 0.4, birth: simTime, fireT: -9, syn: [], deg: 1, rootSegs: [] });
    const n = neurons.length - 1;
    gridInsert(x, y, z, n);
    // 多极神经元：3 条初始生长锥
    for (let i = 0; i < 3; i++) {
      const th = rand() * Math.PI * 2, ph = Math.acos(2 * rand() - 1);
      tips.push({
        n, seg: -1, px: x, py: y, pz: z,
        dx: Math.sin(ph) * Math.cos(th), dy: Math.cos(ph), dz: Math.sin(ph) * Math.sin(th),
        life: 60 + rand() * 40, depth: 0
      });
    }
    return n;
  }

  const _g = [0, 0, 0];
  function stepTip(t) {
    // 方向 = 惯性 + 营养梯度 + 斥力 + 边界推 + 中心拉 + 随机游走
    nutrientGrad(t.px, t.py, t.pz, _g);
    let dx = t.dx * 0.60 + _g[0] * 8.0;
    let dy = t.dy * 0.60 + _g[1] * 8.0;
    let dz = t.dz * 0.60 + _g[2] * 8.0;
    let rx = 0, ry = 0, rz = 0;
    gridNear(t.px, t.py, t.pz, (pt) => {
      const ddx = t.px - pt[0], ddy = t.py - pt[1], ddz = t.pz - pt[2];
      const d2 = ddx * ddx + ddy * ddy + ddz * ddz;
      if (d2 < 1.69 && d2 > 1e-6) {
        const d = Math.sqrt(d2), w = (1.3 - d) / 1.3;
        rx += (ddx / d) * w; ry += (ddy / d) * w; rz += (ddz / d) * w;
      }
    });
    dx -= rx * 0.5; dy -= ry * 0.5; dz -= rz * 0.5;
    const m = Math.max(Math.abs(t.px), Math.abs(t.py), Math.abs(t.pz));
    if (m > WORLD - 3) {
      const w = (m - (WORLD - 3)) / 3;
      dx -= Math.sign(t.px) * w * 1.4; dy -= Math.sign(t.py) * w * 1.4; dz -= Math.sign(t.pz) * w * 1.4;
    }
    dx -= t.px * 0.0015; dy -= t.py * 0.0015; dz -= t.pz * 0.0015;
    dx += (rand() - 0.5) * 0.55; dy += (rand() - 0.5) * 0.55; dz += (rand() - 0.5) * 0.55;
    const dl = Math.hypot(dx, dy, dz) || 1;
    dx /= dl; dy /= dl; dz /= dl;

    const nx = t.px + dx * STEP, ny = t.py + dy * STEP, nz = t.pz + dz * STEP;

    // 死亡：营养枯竭 / 寿命耗尽 / 越界
    if (t.life <= 0 || nutrient(nx, ny, nz) < 0.22 || Math.max(Math.abs(nx), Math.abs(ny), Math.abs(nz)) > WORLD) {
      leaves.push({ x: t.px, y: t.py, z: t.pz, n: t.n });
      return false;
    }

    // 突触：生长锥碰到其他神经元的结构
    let synT = -1;
    gridNear(nx, ny, nz, (pt) => {
      if (synT < 0 && pt[3] !== t.n) {
        const ddx = nx - pt[0], ddy = ny - pt[1], ddz = nz - pt[2];
        if (ddx * ddx + ddy * ddy + ddz * ddz < 1.69) synT = pt[3];
      }
    });
    if (synT >= 0) {
      const pk = Math.min(t.n, synT) * 100000 + Math.max(t.n, synT);
      if (neurons[t.n].syn.length < 6 && !synKeys.has(pk) && synCount < SYN_MAX) {
        synKeys.add(pk);
        const sid = addSeg(t.seg, t.px, t.py, t.pz, nx, ny, nz, t.n);
        if (sid >= 0) {
          segs[sid].syn = synT;
          synCount++; neurons[t.n].deg++;
          if (rand() < 0.5) {   // 一半概率末梢化形成突触小体，一半继续生长
            leaves.push({ x: nx, y: ny, z: nz, n: t.n });
            return false;
          }
          t.seg = sid; t.px = nx; t.py = ny; t.pz = nz;
          t.life -= 1; t.depth++;
          gridInsert(nx, ny, nz, t.n);
          return true;
        }
      }
      synT = -1;
    }

    const sid = addSeg(t.seg, t.px, t.py, t.pz, nx, ny, nz, t.n);
    if (sid < 0) return false;
    t.seg = sid; t.px = nx; t.py = ny; t.pz = nz;
    t.life -= 1; t.depth++;
    gridInsert(nx, ny, nz, t.n);

    // 分支
    if (t.depth < 10 && tips.length < TIP_MAX && segs.length < SEG_MAX - 2 && rand() < 0.030) {
      const ang = 0.6 + rand() * 0.8, phi = rand() * Math.PI * 2;
      const w1 = Math.cos(ang), w2 = Math.sin(ang);
      // 在垂直于当前方向的平面内取旋转分支方向
      let ux = -dy, uy = dx, uz = 0;
      const ul = Math.hypot(ux, uy, uz) || 1; ux /= ul; uy /= ul; uz /= ul;
      let vx = dy * uz - dz * uy, vy = dz * ux - dx * uz, vz = dx * uy - dy * ux;
      const bx = dx * w1 + (ux * Math.cos(phi) + vx * Math.sin(phi)) * w2;
      const by = dy * w1 + (uy * Math.cos(phi) + vy * Math.sin(phi)) * w2;
      const bz = dz * w1 + (uz * Math.cos(phi) + vz * Math.sin(phi)) * w2;
      tips.push({
        n: t.n, seg: sid, px: nx, py: ny, pz: nz,
        dx: bx, dy: by, dz: bz,
        life: t.life * (0.55 + rand() * 0.25), depth: t.depth + 1
      });
    }
    return true;
  }

  function fire(n) {
    const nr = neurons[n];
    if (!nr || simTime - nr.fireT < 0.22) return;
    nr.fireT = simTime;
    fireCount++;
    for (const s of nr.rootSegs) {
      if (pulses.length < PULSE_MAX) pulses.push({ seg: s, s: 0, speed: 6.5 * (0.8 + 0.4 * rand()) });
    }
  }

  function update(dt, bass, mid) {
    simTime += dt;
    // 生长
    if (tips.length > 0 && segs.length < SEG_MAX) {
      const budget = Math.min(tips.length, 90);
      for (let i = 0; i < budget; i++) {
        const ti = (rand() * tips.length) | 0;
        if (!stepTip(tips[ti])) { tips[ti] = tips[tips.length - 1]; tips.pop(); }
      }
    }
    // 新神经元萌芽：错峰出生，撒在半径 8 的球内
    if (neurons.length < NEURON_MAX && segs.length < SEG_MAX * 0.85 && simTime < 70 && tips.length < 700 && rand() < Math.min(0.5, 3.2 * dt)) {
      const th = rand() * Math.PI * 2, ph = Math.acos(2 * rand() - 1), rr = 11 * Math.cbrt(rand());
      addNeuron(rr * Math.sin(ph) * Math.cos(th), rr * Math.cos(ph), rr * Math.sin(ph) * Math.sin(th));
    }
    // 觉醒：突触足够后开始放电
    if (!fireEnabled && (synCount >= 25 || simTime > 95)) fireEnabled = true;
    if (fireEnabled) {
      if (rand() < (2.0 + bass * 30) * dt) fire((rand() * neurons.length) | 0);
      for (let i = fireQ.length - 1; i >= 0; i--) {
        if (fireQ[i].t <= simTime) { fire(fireQ[i].n); fireQ.splice(i, 1); }
      }
    }
    // 脉冲传播
    const spd = 1 + mid * 0.8;
    for (let i = pulses.length - 1; i >= 0; i--) {
      const p = pulses[i], sg = segs[p.seg];
      p.s += p.speed * spd * dt / sg.len;
      if (p.s >= 1) {
        if (sg.kids.length) {
          p.seg = sg.kids[(rand() * sg.kids.length) | 0];
          p.s = 0;
        } else {
          if (sg.syn >= 0) fireQ.push({ n: sg.syn, t: simTime + 0.04 + rand() * 0.08 });
          pulses[i] = pulses[pulses.length - 1]; pulses.pop();
        }
      }
    }
    // 胞体半径随连接度成长
    for (const nr of neurons) nr.r = Math.min(1.05, 0.34 + nr.deg * 0.055);
  }

  function phase() {
    if (segs.length < 220) return '萌芽 · 第一批细胞分裂';
    if (tips.length > 0 && segs.length < 9000) return '生长 · 树突向营养蔓延';
    if (!fireEnabled) return '联结 · 突触网络成形';
    if (fireCount < 60) return '觉醒 · 第一次神经放电';
    return '繁盛 · 神经网络活跃中';
  }

  // 初始种子
  for (let i = 0; i < 5; i++) {
    addNeuron((rand() - 0.5) * 4, (rand() - 0.5) * 4, (rand() - 0.5) * 4);
  }

  return {
    update,
    phase,
    get neurons() { return neurons; },
    get segs() { return segs; },
    get tips() { return tips; },
    get pulses() { return pulses; },
    get leaves() { return leaves; },
    get simTime() { return simTime; },
    get synCount() { return synCount; },
    get fireCount() { return fireCount; },
    get fireEnabled() { return fireEnabled; },
  };
}

/* ================== 着色器 ================== */
// 树突线束：新生段亮起后回落为冷色基底，随相机距离雾化
const LINE_VS = `
attribute vec4 aPB;
uniform vec3 uCam, uRgt, uUp, uFwd;
uniform mat4 uProj;
uniform float uTime;
varying vec3 vColor;
varying float vA;
void main(){
  vec3 rel = aPB.xyz - uCam;
  vec3 v = vec3(dot(rel, uRgt), dot(rel, uUp), -dot(rel, uFwd));
  gl_Position = uProj * vec4(v, 1.0);
  float fog = exp(-length(rel) * 0.008);
  float glow = clamp(exp(-max(uTime - aPB.w, 0.0) * 0.5), 0.0, 1.0);
  vColor = mix(vec3(0.34, 0.72, 1.00), vec3(0.70, 1.00, 1.10), glow);
  vA = (1.70 + 0.55 * glow) * fog;
}
`;
const LINE_FS = `
precision mediump float;
varying vec3 vColor;
varying float vA;
void main(){ gl_FragColor = vec4(vColor * vA, 1.0); }
`;

// 发光点：终端小体 / 胞体（放电时胀亮）/ 行进脉冲
const POINT_VS = `
attribute vec4 aPSB;
attribute vec2 aKF;
uniform vec3 uCam, uRgt, uUp, uFwd;
uniform mat4 uProj;
uniform float uTime, uPxScale, uTreble;
varying vec3 vColor;
varying float vI;
void main(){
  vec3 rel = aPSB.xyz - uCam;
  vec3 v = vec3(dot(rel, uRgt), dot(rel, uUp), -dot(rel, uFwd));
  gl_Position = uProj * vec4(v, 1.0);
  float fog = exp(-length(rel) * 0.011);
  float glow = aKF.y > -8.0 ? exp(-max(uTime - aKF.y, 0.0) * 2.4) : 0.0;
  float sz = aPSB.w;
  vec3 base;
  float inten;
  if (aKF.x < 0.5) {                       // 突触末端
    base = vec3(0.30, 0.55, 0.75); inten = 0.35; sz *= 3.0;
  } else if (aKF.x < 1.5) {                // 胞体
    base = mix(vec3(0.30, 0.65, 0.85), vec3(1.00, 0.80, 0.50), clamp(glow * 1.4, 0.0, 1.0));
    inten = 0.26 + glow * 1.2;
    sz *= 1.0 + glow * 1.7;
  } else {                                 // 放电脉冲
    base = vec3(0.75, 0.95, 1.00);
    inten = 2.0 + uTreble * 1.4;
    sz = 2.1 + uTreble * 1.0;
  }
  gl_PointSize = clamp(uPxScale * sz / max(0.1, -v.z), 1.0, 36.0);
  vColor = base * fog;
  vI = inten;
}
`;
const POINT_FS = `
precision mediump float;
varying vec3 vColor;
varying float vI;
void main(){
  vec2 d = gl_PointCoord * 2.0 - 1.0;
  float r2 = dot(d, d);
  if (r2 > 1.0) discard;
  float a = 1.0 - r2;
  a *= a;
  gl_FragColor = vec4(vColor * (a * vI), 1.0);
}
`;

/* ================== GL 资源 ================== */
const SEG_MAX = 26000, LEAF_MAX = 9000, NEURON_MAX = 150, PULSE_MAX = 6000;
let sim = null
let lineProg = null, pointProg = null
let segBuf = null, segArr = null, segRendered = 0
let leafBuf = null, leafArr = null, leafRendered = 0
let somaBuf = null, somaArr = null
let pulseBuf = null, pulseArr = null
let U = {}          // line uniforms
let UP = {}         // point uniforms
let projMat = null
let camPos = [0, 0, 46], camRgt = [1, 0, 0], camUp = [0, 1, 0], camFwd = [0, 0, -1]

function compileShader(glCtx, type, src) {
  const sh = glCtx.createShader(type)
  glCtx.shaderSource(sh, src)
  glCtx.compileShader(sh)
  if (!glCtx.getShaderParameter(sh, glCtx.COMPILE_STATUS)) {
    throw new Error(glCtx.getShaderInfoLog(sh))
  }
  return sh
}

function makeProgram(glCtx, vs, fs) {
  const p = glCtx.createProgram()
  glCtx.attachShader(p, compileShader(glCtx, glCtx.VERTEX_SHADER, vs))
  glCtx.attachShader(p, compileShader(glCtx, glCtx.FRAGMENT_SHADER, fs))
  glCtx.linkProgram(p)
  if (!glCtx.getProgramParameter(p, glCtx.LINK_STATUS)) throw new Error(glCtx.getProgramInfoLog(p))
  return p
}

function perspM(fovy, aspect) {
  const near = 0.1, far = 120
  const f = 1 / Math.tan(fovy / 2)
  const out = new Float32Array(16)
  out[0] = f / aspect; out[5] = f
  out[10] = (far + near) / (near - far); out[11] = -1
  out[14] = 2 * far * near / (near - far)
  return out
}

function initSim() {
  sim = createLifeSim(seed)
  segRendered = 0
  leafRendered = 0
  segArr.fill(0)
  leafArr.fill(0)
}

function initGL() {
  const canvas = canvasRef.value
  gl = canvas.getContext('webgl', { antialias: false, alpha: false, powerPreference: 'high-performance' })
        || canvas.getContext('experimental-webgl')
  if (!gl) {
    showToast('当前浏览器不支持 WebGL，无法渲染数字生命')
    return false
  }
  try {
    lineProg = makeProgram(gl, LINE_VS, LINE_FS)
    pointProg = makeProgram(gl, POINT_VS, POINT_FS)
  } catch (e) {
    showToast('着色器初始化失败：' + e.message)
    return false
  }
  for (const n of ['uCam', 'uRgt', 'uUp', 'uFwd', 'uProj', 'uTime']) U[n] = gl.getUniformLocation(lineProg, n)
  for (const n of ['uCam', 'uRgt', 'uUp', 'uFwd', 'uProj', 'uTime', 'uPxScale', 'uTreble']) UP[n] = gl.getUniformLocation(pointProg, n)
  U.aPB = gl.getAttribLocation(lineProg, 'aPB')
  UP.aPSB = gl.getAttribLocation(pointProg, 'aPSB')
  UP.aKF = gl.getAttribLocation(pointProg, 'aKF')

  segArr = new Float32Array(SEG_MAX * 2 * 4)
  segBuf = gl.createBuffer()
  gl.bindBuffer(gl.ARRAY_BUFFER, segBuf)
  gl.bufferData(gl.ARRAY_BUFFER, segArr.byteLength, gl.DYNAMIC_DRAW)

  leafArr = new Float32Array(LEAF_MAX * 6)
  leafBuf = gl.createBuffer()
  gl.bindBuffer(gl.ARRAY_BUFFER, leafBuf)
  gl.bufferData(gl.ARRAY_BUFFER, leafArr.byteLength, gl.DYNAMIC_DRAW)

  somaArr = new Float32Array(NEURON_MAX * 6)
  somaBuf = gl.createBuffer()
  gl.bindBuffer(gl.ARRAY_BUFFER, somaBuf)
  gl.bufferData(gl.ARRAY_BUFFER, somaArr.byteLength, gl.DYNAMIC_DRAW)

  pulseArr = new Float32Array(PULSE_MAX * 6)
  pulseBuf = gl.createBuffer()
  gl.bindBuffer(gl.ARRAY_BUFFER, pulseBuf)
  gl.bufferData(gl.ARRAY_BUFFER, pulseArr.byteLength, gl.DYNAMIC_DRAW)

  initSim()
  applySize()
  return true
}

function bindLineAttribs() {
  gl.enableVertexAttribArray(U.aPB)
  gl.vertexAttribPointer(U.aPB, 4, gl.FLOAT, false, 16, 0)
}

function bindPointAttribs() {
  gl.enableVertexAttribArray(UP.aPSB)
  gl.enableVertexAttribArray(UP.aKF)
  gl.vertexAttribPointer(UP.aPSB, 4, gl.FLOAT, false, 24, 0)
  gl.vertexAttribPointer(UP.aKF, 2, gl.FLOAT, false, 24, 16)
}

function bindEvents() {
  const container = mountRef.value
  const canvas = canvasRef.value
  container.addEventListener('pointerdown', handlePointerDown)
  container.addEventListener('pointermove', handlePointerMove)
  container.addEventListener('pointerup', handlePointerUp)
  container.addEventListener('pointercancel', handlePointerUp)
  container.addEventListener('wheel', handleWheel, { passive: false })
  canvas.addEventListener('webglcontextlost', handleContextLost)
  resizeObserver = new ResizeObserver(() => applySize())
  resizeObserver.observe(container)
}

function handleContextLost(e) {
  e.preventDefault()
  showToast('GPU 上下文丢失，请重新进入本页')
}

/* ---- 交互 ---- */
function handlePointerDown(e) {
  if (e.button !== 0) return
  dragging = true
  lastX = e.clientX
  lastY = e.clientY
  try { canvasRef.value.setPointerCapture(e.pointerId) } catch (err) {}
}

function handlePointerMove(e) {
  if (!dragging) return
  state.lookX += (e.clientX - lastX) * 0.005
  state.lookY += (e.clientY - lastY) * 0.004
  state.lookY = Math.max(-1.2, Math.min(1.2, state.lookY))
  lastX = e.clientX
  lastY = e.clientY
}

function handlePointerUp(e) {
  dragging = false
  try { canvasRef.value.releasePointerCapture(e.pointerId) } catch (err) {}
}

function handleWheel(e) {
  e.preventDefault()
  state.dist = Math.max(18, Math.min(90, state.dist * (e.deltaY > 0 ? 1.1 : 0.91)))
}

/* ---- 音频 ---- */
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
  for (let i = lo; i < hi; i++) {
    const v = freqData[i] / 255
    sum += v * v
  }
  return Math.min(1, Math.sqrt(sum / (hi - lo)) * 2.6)
}

function readAudio() {
  if (audioMode.value === 'none' || !analyser) {
    const t = state.time
    return {
      bass: Math.max(0, 0.30 + 0.20 * Math.sin(t * 0.9) + 0.10 * Math.sin(t * 2.3 + 1.7)),
      mid: Math.max(0, 0.25 + 0.15 * Math.sin(t * 0.53 + 2.0)),
      treble: Math.max(0, 0.18 + 0.10 * Math.sin(t * 1.31 + 4.0))
    }
  }
  analyser.getByteFrequencyData(freqData)
  const sr = actx.sampleRate
  const bin = sr / analyser.fftSize
  return {
    bass: Math.min(1, bandEnergy(Math.max(1, Math.floor(20 / bin)), Math.floor(250 / bin)) * 1.2),
    mid: Math.min(1, bandEnergy(Math.floor(250 / bin), Math.floor(2000 / bin)) * 1.2),
    treble: Math.min(1, bandEnergy(Math.floor(2000 / bin), Math.floor(8000 / bin)) * 1.2)
  }
}

function smooth(cur, target, dt) {
  const rate = target > cur ? 28 : 6
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
    showToast('已连接麦克风，低音会引发神经放电风暴')
  } catch (e) {
    showToast('麦克风不可用：' + e.name + (e.name === 'NotAllowedError' ? '（权限被拒绝）' : ''))
  }
}

function stopMic() {
  if (audioMode.value !== 'mic') return
  if (micStream) micStream.getTracks().forEach(t => t.stop())
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
  fileInputRef.value.click()
}

function onFileChange() {
  const f = fileInputRef.value.files[0]
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
    try { fileSource.disconnect() } catch (e) {}
    fileSource = null
  }
  if (fileURL) {
    URL.revokeObjectURL(fileURL)
    fileURL = null
  }
  musicPlaying.value = true
  if (audioMode.value === 'file') audioMode.value = 'none'
}

function togglePause() {
  paused.value = !paused.value
}

function toggleFullscreen() {
  if (document.fullscreenElement) document.exitFullscreen()
  else mountRef.value.requestFullscreen().catch(() => {})
}

function reEvolve() {
  seed = (Math.random() * 1e9) | 0
  initSim()
  showToast('新的生命开始了')
}

function showToast(msg) {
  toastMsg.value = msg
  clearTimeout(toastTimer)
  toastTimer = setTimeout(() => { toastMsg.value = '' }, 4000)
}

/* ---- 自适应分辨率 ---- */
const DPR_CAP = 1.75
function applySize() {
  const container = mountRef.value
  const canvas = canvasRef.value
  if (!container || !canvas) return
  const dpr = Math.min(window.devicePixelRatio || 1, DPR_CAP)
  const w = Math.max(2, Math.round(container.clientWidth * dpr * state.scale))
  const h = Math.max(2, Math.round(container.clientHeight * dpr * state.scale))
  if (canvas.width !== w || canvas.height !== h) {
    canvas.width = w
    canvas.height = h
    if (gl) gl.viewport(0, 0, w, h)
  }
  if (gl) projMat = perspM(state.fovBase, w / h)
}

/* ---- 主循环 ---- */
function frame(now) {
  rafId = window.requestAnimationFrame(frame)
  if (!gl || !sim) return
  let dt = (now - lastT) / 1000
  lastT = now
  dt = Math.min(dt, 0.1)
  dtEma += (dt * 1000 - dtEma) * 0.05

  if (dtEma > 26 && state.scale > 0.45) {
    state.scale = Math.max(0.45, state.scale - 0.05)
    applySize()
  } else if (dtEma < 13 && state.scale < 1.0) {
    state.scale = Math.min(1.0, state.scale + 0.02)
    applySize()
  }

  const simAudio = readAudio()
  if (!paused.value) {
    state.bass = smooth(state.bass, simAudio.bass, dt)
    state.mid = smooth(state.mid, simAudio.mid, dt)
    state.treble = smooth(state.treble, simAudio.treble, dt)
    state.time += dt
    if (!dragging) state.lookX += dt * 0.04
    sim.update(dt, state.bass, state.mid)
  }

  // 相机
  const az = state.lookX, el = Math.max(-1.2, Math.min(1.2, state.lookY))
  camPos = [
    Math.sin(az) * Math.cos(el) * state.dist,
    Math.sin(el) * state.dist,
    Math.cos(az) * Math.cos(el) * state.dist
  ]
  const fl = Math.hypot(...camPos)
  camFwd = [-camPos[0] / fl, -camPos[1] / fl, -camPos[2] / fl]
  // rgt = fwd × worldUp（保证画面不镜像），up = rgt × fwd
  const rl = Math.hypot(camFwd[2], camFwd[0]) || 1
  camRgt = [-camFwd[2] / rl, 0, camFwd[0] / rl]
  camUp = [
    camRgt[1] * camFwd[2] - camRgt[2] * camFwd[1],
    camRgt[2] * camFwd[0] - camRgt[0] * camFwd[2],
    camRgt[0] * camFwd[1] - camRgt[1] * camFwd[0]
  ]

  gl.clearColor(0.008, 0.006, 0.016, 1)
  gl.clear(gl.COLOR_BUFFER_BIT)
  gl.enable(gl.BLEND)
  gl.blendFunc(gl.ONE, gl.ONE)
  gl.disable(gl.DEPTH_TEST)

  // 1. 树突线束：追加新段
  const segs = sim.segs
  if (segs.length > segRendered) {
    for (let i = segRendered; i < segs.length; i++) {
      const s = segs[i], o = i * 8
      segArr[o] = s.ax; segArr[o + 1] = s.ay; segArr[o + 2] = s.az; segArr[o + 3] = s.birth
      segArr[o + 4] = s.bx; segArr[o + 5] = s.by; segArr[o + 6] = s.bz; segArr[o + 7] = s.birth
    }
    gl.bindBuffer(gl.ARRAY_BUFFER, segBuf)
    gl.bufferSubData(gl.ARRAY_BUFFER, segRendered * 32, segArr.subarray(segRendered * 8, segs.length * 8))
    segRendered = segs.length
  }
  if (segRendered > 0) {
    gl.useProgram(lineProg)
    gl.uniformMatrix4fv(U.uProj, false, projMat)
    gl.uniform3f(U.uCam, camPos[0], camPos[1], camPos[2])
    gl.uniform3f(U.uRgt, camRgt[0], camRgt[1], camRgt[2])
    gl.uniform3f(U.uUp, camUp[0], camUp[1], camUp[2])
    gl.uniform3f(U.uFwd, camFwd[0], camFwd[1], camFwd[2])
    gl.uniform1f(U.uTime, state.time)
    // 顶点属性 0 = aPos（全屏三角形），1 = aPB（线段）
    gl.bindBuffer(gl.ARRAY_BUFFER, segBuf)
    bindLineAttribs()
    gl.drawArrays(gl.LINES, 0, segRendered * 2)
  }

  // 2. 突触末端（静态追加）
  const leaves = sim.leaves
  if (leaves.length > leafRendered) {
    for (let i = leafRendered; i < leaves.length && i < LEAF_MAX; i++) {
      const l = leaves[i], o = i * 6
      leafArr[o] = l.x; leafArr[o + 1] = l.y; leafArr[o + 2] = l.z
      leafArr[o + 3] = 0.55; leafArr[o + 4] = 0; leafArr[o + 5] = -9
    }
    gl.bindBuffer(gl.ARRAY_BUFFER, leafBuf)
    gl.bufferSubData(gl.ARRAY_BUFFER, leafRendered * 24, leafArr.subarray(leafRendered * 6, Math.min(leaves.length, LEAF_MAX) * 6))
    leafRendered = Math.min(leaves.length, LEAF_MAX)
  }

  // 3. 发光点程序：胞体 + 脉冲 + 末端
  gl.useProgram(pointProg)
  gl.uniformMatrix4fv(UP.uProj, false, projMat)
  gl.uniform3f(UP.uCam, camPos[0], camPos[1], camPos[2])
  gl.uniform3f(UP.uRgt, camRgt[0], camRgt[1], camRgt[2])
  gl.uniform3f(UP.uUp, camUp[0], camUp[1], camUp[2])
  gl.uniform3f(UP.uFwd, camFwd[0], camFwd[1], camFwd[2])
  gl.uniform1f(UP.uTime, state.time)
  gl.uniform1f(UP.uPxScale, (canvasRef.value ? canvasRef.value.height : 720) * 0.5 / Math.tan(state.fovBase / 2))
  gl.uniform1f(UP.uTreble, state.treble)

  const neurons = sim.neurons
  for (let i = 0; i < neurons.length; i++) {
    const nr = neurons[i], o = i * 6
    somaArr[o] = nr.x; somaArr[o + 1] = nr.y; somaArr[o + 2] = nr.z
    somaArr[o + 3] = nr.r * 0.45; somaArr[o + 4] = 1; somaArr[o + 5] = nr.fireT
  }
  gl.bindBuffer(gl.ARRAY_BUFFER, somaBuf)
  gl.bufferSubData(gl.ARRAY_BUFFER, 0, somaArr.subarray(0, neurons.length * 6))
  bindPointAttribs()
  gl.drawArrays(gl.POINTS, 0, neurons.length)

  if (leafRendered > 0) {
    gl.bindBuffer(gl.ARRAY_BUFFER, leafBuf)
    bindPointAttribs()
    gl.drawArrays(gl.POINTS, 0, leafRendered)
  }

  const pulses = sim.pulses
  const pn = Math.min(pulses.length, PULSE_MAX)
  for (let i = 0; i < pn; i++) {
    const p = pulses[i], s = sim.segs[p.seg], o = i * 6
    const t = p.s
    pulseArr[o] = s.ax + (s.bx - s.ax) * t
    pulseArr[o + 1] = s.ay + (s.by - s.ay) * t
    pulseArr[o + 2] = s.az + (s.bz - s.az) * t
    pulseArr[o + 3] = 1.5; pulseArr[o + 4] = 2; pulseArr[o + 5] = -9
  }
  if (pn > 0) {
    gl.bindBuffer(gl.ARRAY_BUFFER, pulseBuf)
    gl.bufferSubData(gl.ARRAY_BUFFER, 0, pulseArr.subarray(0, pn * 6))
    bindPointAttribs()
    gl.drawArrays(gl.POINTS, 0, pn)
  }

  if (frameCount++ % 30 === 0) {
    fpsText.value = `${(1000 / Math.max(1, dtEma)).toFixed(0)} fps · 渲染 ${(state.scale * 100).toFixed(0)}%`
    phaseText.value = `${sim.phase()} · 神经元 ${neurons.length} · 突触 ${sim.synCount}`
  }
}

onMounted(() => {
  if (!initGL()) return
  bindEvents()
  lastT = performance.now()
  rafId = window.requestAnimationFrame(frame)
})

onBeforeUnmount(() => {
  window.cancelAnimationFrame(rafId)
  const container = mountRef.value
  const canvas = canvasRef.value
  if (container) {
    container.removeEventListener('pointerdown', handlePointerDown)
    container.removeEventListener('pointermove', handlePointerMove)
    container.removeEventListener('pointerup', handlePointerUp)
    container.removeEventListener('pointercancel', handlePointerUp)
    container.removeEventListener('wheel', handleWheel, { passive: false })
  }
  if (canvas) {
    canvas.removeEventListener('webglcontextlost', handleContextLost)
  }
  if (resizeObserver) resizeObserver.disconnect()
  stopMic()
  stopFile()
  if (actx) {
    actx.close().catch(() => {})
    actx = null
    analyser = null
    freqData = null
  }
  clearTimeout(toastTimer)
})
</script>

<style scoped>
.dl-view {
  position: relative;
  width: 100%;
  height: 100%;
  min-height: 560px;
  overflow: hidden;
  background: #020208;
}

.dl-canvas {
  position: absolute;
  inset: 0;
  width: 100%;
  height: 100%;
  display: block;
  touch-action: none;
  cursor: grab;
}

.dl-canvas:active {
  cursor: grabbing;
}

.dl-header {
  position: absolute;
  top: 28px;
  left: 32px;
  z-index: 2;
  pointer-events: none;
  user-select: none;
}

.dl-eyebrow {
  display: block;
  color: #5fd4b0;
  font-size: 11px;
  font-weight: 700;
  letter-spacing: 0.32em;
  text-transform: uppercase;
  text-shadow: 0 0 14px rgba(95, 212, 176, 0.55);
}

.dl-title {
  margin: 6px 0 8px;
  color: #eafff6;
  font-size: 30px;
  font-weight: 850;
  letter-spacing: 0.06em;
  text-shadow: 0 0 24px rgba(95, 212, 176, 0.45);
}

.dl-desc {
  margin: 0;
  max-width: 560px;
  color: rgba(206, 234, 222, 0.7);
  font-size: 13px;
  line-height: 1.7;
}

.dl-toolbar {
  position: absolute;
  bottom: 34px;
  left: 50%;
  z-index: 2;
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: center;
  gap: 8px;
  padding: 8px 10px;
  border: 1px solid rgba(95, 212, 176, 0.24);
  border-radius: 14px;
  background: rgba(8, 24, 20, 0.55);
  backdrop-filter: blur(14px);
  transform: translateX(-50%);
}

.dl-button {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  min-height: 32px;
  padding: 0 14px;
  border: 1px solid rgba(95, 180, 156, 0.24);
  border-radius: 9px;
  color: rgba(220, 240, 232, 0.8);
  background: transparent;
  font-size: 13px;
  font-weight: 650;
  cursor: pointer;
  white-space: nowrap;
  transition:
    color 0.2s ease,
    border-color 0.2s ease,
    background 0.2s ease,
    box-shadow 0.2s ease;
}

.dl-button:hover {
  color: #eafff6;
  border-color: rgba(95, 212, 176, 0.55);
}

.dl-button.active {
  color: #04120d;
  border-color: rgba(95, 212, 176, 0.85);
  background: linear-gradient(135deg, #a8ffd9, #3cd9a0);
  box-shadow: 0 0 18px rgba(60, 217, 160, 0.4);
}

.dl-button-key {
  font-size: 11px;
  font-weight: 800;
  opacity: 0.7;
  letter-spacing: 0.05em;
}

.dl-stats {
  min-width: 96px;
  color: rgba(206, 234, 222, 0.5);
  font-size: 11px;
  font-variant-numeric: tabular-nums;
  text-align: right;
}

.dl-file {
  display: none;
}

.dl-toast {
  position: absolute;
  top: 24px;
  left: 50%;
  z-index: 3;
  transform: translateX(-50%);
  max-width: 80%;
  padding: 9px 16px;
  border: 1px solid rgba(255, 110, 130, 0.5);
  border-radius: 10px;
  background: rgba(60, 20, 30, 0.85);
  color: #ffd7dd;
  font-size: 13px;
  pointer-events: none;
  backdrop-filter: blur(8px);
}

@media (max-width: 920px) {
  .dl-header {
    top: 18px;
    left: 18px;
  }

  .dl-title {
    font-size: 24px;
  }

  .dl-desc {
    font-size: 12px;
  }

  .dl-toolbar {
    bottom: 20px;
    max-width: calc(100% - 24px);
  }
}
</style>
