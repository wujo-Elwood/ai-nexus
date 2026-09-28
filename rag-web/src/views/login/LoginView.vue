<template>
  <main class="login-page">
    <div class="login-background">
      <span class="orbit orbit-one"></span>
      <span class="orbit orbit-two"></span>
      <span class="orbit orbit-three"></span>
      <span class="glow-dot dot-one"></span>
      <span class="glow-dot dot-two"></span>
      <span class="glow-dot dot-three"></span>
    </div>

    <section class="login-card">
      <header class="login-brand">
        <div class="brand-icon">
          <span>A</span>
          <b></b>
        </div>
        <div>
          <h1>AI智能平台</h1>
          <p>一个入口，调用全球顶尖模型</p>
        </div>
      </header>

      <el-form
        v-if="activeTab === 'login'"
        ref="loginFormRef"
        :model="loginForm"
        :rules="loginRules"
        class="login-form"
        label-position="top"
        @submit.prevent="handleLogin"
      >
        <el-form-item label="用户名" prop="username">
          <el-input
            v-model="loginForm.username"
            placeholder="请输入用户名"
            size="large"
            autocomplete="username"
          />
        </el-form-item>

        <el-form-item label="密码" prop="password">
          <el-input
            v-model="loginForm.password"
            type="password"
            placeholder="请输入密码"
            size="large"
            show-password
            autocomplete="current-password"
          />
        </el-form-item>

        <div class="login-options">
          <el-checkbox v-model="rememberMe">记住我</el-checkbox>
          <button class="text-button" type="button" @click="showPasswordResetTip">忘记密码？</button>
        </div>

        <el-button
          type="primary"
          :loading="loading"
          class="login-button"
          native-type="submit"
        >
          {{ loading ? '登录中...' : '登录' }}
        </el-button>

        <p class="switch-text">
          还没有账号？
          <button type="button" @click="switchToRegister">注册</button>
        </p>
      </el-form>

      <el-form
        v-else
        ref="registerFormRef"
        :model="registerForm"
        :rules="registerRules"
        class="login-form"
        label-position="top"
        @submit.prevent="handleRegister"
      >
        <el-form-item label="用户名" prop="username">
          <el-input
            v-model="registerForm.username"
            placeholder="请输入用户名"
            size="large"
            autocomplete="username"
          />
        </el-form-item>

        <el-form-item label="密码" prop="password">
          <el-input
            v-model="registerForm.password"
            type="password"
            placeholder="至少 6 位字符"
            size="large"
            show-password
            autocomplete="new-password"
          />
        </el-form-item>

        <el-form-item label="确认密码" prop="confirmPassword">
          <el-input
            v-model="registerForm.confirmPassword"
            type="password"
            placeholder="请再次输入密码"
            size="large"
            show-password
            autocomplete="new-password"
          />
        </el-form-item>

        <el-form-item label="昵称" prop="nickname">
          <el-input
            v-model="registerForm.nickname"
            placeholder="用于平台内展示，可不填"
            size="large"
          />
        </el-form-item>

        <el-button
          type="primary"
          :loading="loading"
          class="login-button"
          native-type="submit"
        >
          {{ loading ? '注册中...' : '注册' }}
        </el-button>

        <p class="switch-text register-switch">
          已有账号？
          <button type="button" @click="switchToLogin">返回登录</button>
        </p>
      </el-form>
    </section>

    <section class="solar-stage" aria-hidden="true">
      <div class="universe-shell">
        <!-- 3D 场景就绪前先铺一层纯 CSS 的静态星空，避免出现空档期 -->
        <div v-if="!stageReady" class="stage-placeholder"></div>
        <SolarSystemBackground v-else />
        <span class="universe-grain"></span>
      </div>
    </section>

    <p class="stage-hint" aria-hidden="true">拖动旋转视角 · 滚轮缩放</p>
  </main>
</template>

<script setup>
import { defineAsyncComponent, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { login, register } from '../../api/auth'
import { useUserStore } from '../../stores/user'

// 3D 背景按需异步加载。
// 它连同 three.js 一起有近 1MB（gzip 后约 300KB），如果写成静态 import，
// 登录页的 JS 要等整包下载解析完才会执行，用户看到的就是"白屏卡一会才进页面"。
// 改成异步组件后：外壳背景 CSS 立刻可见 → 表单立刻可交互 → 3D 稍后就位。
const SolarSystemBackground = defineAsyncComponent(
  () => import('../../components/SolarSystemBackground.vue')
)

// 标记 3D 背景是否已挂载：未就绪时用 CSS 兜底光斑占位，避免出现"先黑后亮"的空档。
const stageReady = ref(false)

const router = useRouter()
const userStore = useUserStore()

onMounted(() => {
  // 等首帧绘制完成再挂载 3D，确保登录表单优先抢占主线程
  requestAnimationFrame(() => {
    requestAnimationFrame(() => {
      stageReady.value = true
    })
  })
})

const activeTab = ref('login')
const loading = ref(false)
const rememberMe = ref(true)
const loginFormRef = ref(null)
const registerFormRef = ref(null)

const loginForm = reactive({
  username: '',
  password: ''
})

const registerForm = reactive({
  username: '',
  password: '',
  confirmPassword: '',
  nickname: ''
})

const loginRules = {
  username: [{ required: true, message: '请输入用户名', trigger: 'blur' }],
  password: [{ required: true, message: '请输入密码', trigger: 'blur' }]
}

const registerRules = {
  username: [
    { required: true, message: '请输入用户名', trigger: 'blur' },
    { min: 3, max: 50, message: '账号长度需要在 3 到 50 个字符之间', trigger: 'blur' }
  ],
  password: [
    { required: true, message: '请输入密码', trigger: 'blur' },
    { min: 6, message: '密码长度至少 6 个字符', trigger: 'blur' }
  ],
  confirmPassword: [{ required: true, message: '请再次输入密码', trigger: 'blur' }]
}

// 切换到注册表单
function switchToRegister() {
  // 第1步：把当前表单切换为注册表单
  activeTab.value = 'register'
}

// 切换到登录表单
function switchToLogin() {
  // 第1步：把当前表单切换为登录表单
  activeTab.value = 'login'
}

// 提示用户找管理员重置密码
function showPasswordResetTip() {
  // 第1步：显示当前平台的密码重置提示
  ElMessage.info('请联系管理员重置密码')
}

// 登录平台账号
async function handleLogin() {
  try {
    // 第1步：校验登录表单是否填写完整
    await loginFormRef.value.validate()
    // 第2步：打开按钮加载状态，避免重复提交
    loading.value = true
    // 第3步：调用登录接口获取用户信息
    const res = await login(loginForm)
    // 第4步：保存登录后的用户信息
    userStore.setUserInfo(res.data)
    // 第5步：提示登录成功并进入智能问答页面
    ElMessage.success('登录成功')
    router.push('/chat')
  } catch (error) {
    // 第6步：登录失败时记录错误，接口拦截器会处理提示
    console.error(error)
  } finally {
    // 第7步：关闭按钮加载状态
    loading.value = false
  }
}

// 注册平台账号
async function handleRegister() {
  try {
    // 第1步：校验注册表单是否填写正确
    await registerFormRef.value.validate()
    // 第2步：两次密码不一致时阻止注册
    if (registerForm.password !== registerForm.confirmPassword) {
      ElMessage.warning('两次输入的密码不一致')
      return
    }
    // 第3步：打开按钮加载状态，避免重复提交
    loading.value = true
    // 第4步：调用注册接口创建账号，不向后端传递确认密码
    const res = await register({
      username: registerForm.username,
      password: registerForm.password,
      nickname: registerForm.nickname
    })
    // 第5步：保存注册后的用户信息
    userStore.setUserInfo(res.data)
    // 第6步：提示注册成功并进入智能问答页面
    ElMessage.success('注册成功')
    router.push('/chat')
  } catch (error) {
    // 第7步：注册失败时记录错误，接口拦截器会处理提示
    console.error(error)
  } finally {
    // 第8步：关闭按钮加载状态
    loading.value = false
  }
}
</script>

<style scoped>
.login-page {
  position: relative;
  width: 100%;
  min-height: 100dvh;
  padding: 0;
  overflow: hidden;
  /* 背景可拖拽旋转，用抓手光标提示可交互 */
  cursor: grab;
  /* 按住拖动旋转视角时不要触发文本/图片选中：选区高亮会在画布上方跟着跳动，
     看起来就像画面在闪；而且"按住拖动"本身就是浏览器的选择手势。
     表单区域单独放回 text（见 .login-card），否则输入框里选不了字。 */
  user-select: none;
  color: #f6f1e8;
  background:
    radial-gradient(ellipse at 33% 46%, rgba(45, 97, 145, 0.28), transparent 34%),
    radial-gradient(ellipse at 68% 30%, rgba(63, 87, 115, 0.2), transparent 26%),
    radial-gradient(ellipse at 26% 80%, rgba(229, 160, 68, 0.08), transparent 28%),
    linear-gradient(180deg, #08090d 0%, #030508 58%, #020305 100%);
}

/* 拖拽过程中由背景组件把 body 光标切换为 grabbing，此处只需处理表单区 */
.login-card {
  cursor: default;
}

.login-page::before {
  content: "";
  position: absolute;
  inset: 0;
  pointer-events: none;
  background-image:
    radial-gradient(circle, rgba(255, 255, 255, 0.72) 0 1px, transparent 1px),
    radial-gradient(circle, rgba(151, 188, 226, 0.52) 0 1px, transparent 1px),
    radial-gradient(circle, rgba(229, 160, 68, 0.42) 0 1px, transparent 1px);
  background-position:
    0 0,
    36px 62px,
    18px 31px;
  background-size:
    96px 86px,
    146px 132px,
    220px 190px;
  opacity: 0.58;
}

.login-page::after {
  content: "";
  position: absolute;
  left: -8%;
  right: -8%;
  bottom: -26%;
  height: 62%;
  pointer-events: none;
  background-image:
    radial-gradient(ellipse at 40% 30%, rgba(121, 161, 201, 0.2), transparent 42%),
    linear-gradient(rgba(255, 255, 255, 0.045) 1px, transparent 1px),
    linear-gradient(90deg, rgba(255, 255, 255, 0.045) 1px, transparent 1px);
  background-size:
    auto,
    56px 56px,
    56px 56px;
  transform: perspective(760px) rotateX(64deg);
  transform-origin: center bottom;
  opacity: 0.32;
}

.login-background {
  position: absolute;
  inset: 0;
  pointer-events: none;
}

.orbit {
  position: absolute;
  left: 38%;
  top: 50%;
  border: 1px solid rgba(229, 160, 68, 0.12);
  border-radius: 50%;
  transform: translate(-50%, -50%);
}

.orbit-one {
  width: min(42vw, 620px);
  aspect-ratio: 1;
}

.orbit-two {
  width: min(58vw, 860px);
  aspect-ratio: 1;
  border-color: rgba(229, 160, 68, 0.08);
}

.orbit-three {
  width: min(76vw, 1140px);
  aspect-ratio: 1;
  border-color: rgba(255, 255, 255, 0.055);
}

.glow-dot {
  position: absolute;
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: #e5a044;
  box-shadow: 0 0 16px rgba(229, 160, 68, 0.78);
}

.dot-one {
  left: 28.5%;
  top: 42%;
}

.dot-two {
  right: 31%;
  top: 61%;
}

.dot-three {
  left: 36%;
  bottom: 29%;
  width: 4px;
  height: 4px;
}

.login-card {
  position: absolute;
  right: clamp(180px, 13vw, 260px);
  top: 50%;
  z-index: 4;
  width: min(430px, 100%);
  padding: 48px;
  border: 1px solid rgba(255, 255, 255, 0.12);
  border-radius: 16px;
  background:
    linear-gradient(145deg, rgba(30, 28, 26, 0.92), rgba(12, 13, 16, 0.95)),
    rgba(18, 19, 23, 0.94);
  box-shadow:
    inset 0 1px 0 rgba(255, 255, 255, 0.07),
    0 0 0 1px rgba(0, 0, 0, 0.3),
    0 28px 90px rgba(0, 0, 0, 0.62);
  backdrop-filter: blur(26px) saturate(1.1);
  transform: translateY(-50%);
}

.login-brand {
  display: grid;
  grid-template-columns: 40px 1fr;
  gap: 14px;
  align-items: center;
  margin-bottom: 34px;
}

.brand-icon {
  position: relative;
  width: 40px;
  height: 40px;
  display: grid;
  place-items: center;
  border-radius: 10px;
  color: #ffc76d;
  background: rgba(229, 160, 68, 0.14);
}

.brand-icon span {
  font-size: 24px;
  font-weight: 900;
  line-height: 1;
}

.brand-icon b {
  position: absolute;
  right: 8px;
  top: 9px;
  width: 5px;
  height: 5px;
  border-radius: 50%;
  background: #e5a044;
  box-shadow: 0 0 12px rgba(229, 160, 68, 0.8);
}

.login-brand h1 {
  margin: 0;
  color: #fff7eb;
  font-size: 24px;
  font-weight: 800;
  letter-spacing: 0;
}

.login-brand p {
  margin: 10px 0 0;
  color: rgba(246, 241, 232, 0.58);
  font-size: 14px;
  line-height: 1.4;
}

.solar-stage {
  position: absolute;
  inset: 0;
  z-index: 1;
  overflow: hidden;
  pointer-events: none;
}

.universe-shell {
  position: absolute;
  inset: 0;
  overflow: hidden;
  background: #030508;
}

/* 3D 未就绪时的占位：用静态光斑模拟"远处有一颗恒星"，
   与真实场景的天空底色、地平暖调保持一致，切换时几乎看不出接缝。 */
.stage-placeholder {
  position: absolute;
  inset: 0;
  background:
    radial-gradient(circle at 29% 51%, rgba(255, 214, 150, 0.5), rgba(255, 150, 60, 0.16) 9%, rgba(255, 130, 50, 0) 20%),
    radial-gradient(circle at 29% 51%, rgba(120, 90, 60, 0.12), transparent 42%),
    radial-gradient(circle at 24% 20%, rgba(86, 111, 157, 0.16), transparent 38%),
    linear-gradient(180deg, #08090d 0%, #030508 58%, #020305 100%);
}

.universe-shell::before {
  content: "";
  position: absolute;
  inset: 0;
  z-index: 2;
  background:
    radial-gradient(ellipse at 24% 20%, rgba(86, 111, 157, 0.16), transparent 38%),
    linear-gradient(90deg, rgba(3, 5, 8, 0.05) 0%, rgba(3, 5, 8, 0.12) 45%, rgba(3, 5, 8, 0.56) 72%, rgba(3, 5, 8, 0.94) 100%),
    linear-gradient(180deg, rgba(2, 3, 6, 0.08), rgba(2, 3, 6, 0.32));
}

.universe-shell::after {
  content: "";
  position: absolute;
  top: 0;
  right: 0;
  bottom: 0;
  z-index: 3;
  width: min(62vw, 940px);
  /* ⚠️ 这里曾经用 `radial-gradient(ellipse at 72% 50%, ...)` 做局部压暗，
     它在 78% 半径处**硬切到透明**，边界在屏幕上是一条可见的竖直亮暗分界。
     太阳一旦漂到那条线附近，圆圆的日面就会被"切"出一个直边，
     看起来像个平面的多边形 —— 这个坑排查了很久，务必不要再改回 ellipse。
     现在改为纯水平线性渐变：从左到右单调加深，任意位置都不会出现突变边界。 */
  background:
    linear-gradient(
      90deg,
      rgba(3, 5, 8, 0) 0%,
      rgba(3, 5, 8, 0.18) 30%,
      rgba(3, 5, 8, 0.58) 62%,
      rgba(3, 5, 8, 0.9) 86%,
      rgba(3, 5, 8, 0.97) 100%
    );
}

.universe-grain {
  position: absolute;
  inset: 0;
  z-index: 5;
  opacity: 0.16;
  background-image:
    radial-gradient(circle, rgba(255, 255, 255, 0.62) 0 1px, transparent 1px),
    radial-gradient(circle, rgba(255, 205, 132, 0.36) 0 1px, transparent 1px);
  background-position:
    0 0,
    27px 43px;
  background-size:
    128px 118px,
    190px 172px;
}

/* 交互提示：左下角常驻，不拦截指针事件 */
.stage-hint {
  position: absolute;
  left: clamp(20px, 3vw, 44px);
  bottom: clamp(18px, 3vh, 34px);
  z-index: 6;
  margin: 0;
  padding: 7px 13px;
  border: 1px solid rgba(255, 255, 255, 0.09);
  border-radius: 999px;
  color: rgba(246, 241, 232, 0.52);
  font-size: 12px;
  letter-spacing: 0.02em;
  background: rgba(3, 5, 8, 0.42);
  backdrop-filter: blur(8px);
  pointer-events: none;
}

.login-form {
  display: grid;
  gap: 18px;
}

.login-form :deep(.el-form-item) {
  margin-bottom: 0;
}

.login-form :deep(.el-form-item__label) {
  margin-bottom: 9px;
  color: rgba(246, 241, 232, 0.66);
  font-size: 13px;
  font-weight: 600;
}

.login-form :deep(.el-input__wrapper) {
  min-height: 44px;
  border: 1px solid rgba(255, 255, 255, 0.12);
  border-radius: 9px;
  background: rgba(255, 255, 255, 0.055);
  box-shadow: none;
  transition:
    border-color 0.2s ease,
    background 0.2s ease,
    box-shadow 0.2s ease;
}

.login-form :deep(.el-input__wrapper:hover),
.login-form :deep(.el-input__wrapper.is-focus) {
  border-color: rgba(229, 160, 68, 0.55);
  background: rgba(255, 255, 255, 0.075);
  box-shadow: 0 0 0 3px rgba(229, 160, 68, 0.1);
}

.login-form :deep(.el-input__inner) {
  color: #f7f1e7;
  font-size: 14px;
}

.login-form :deep(.el-input__inner::placeholder) {
  color: rgba(246, 241, 232, 0.32);
}

.login-options {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  margin-top: 2px;
}

.login-options :deep(.el-checkbox) {
  height: auto;
  color: rgba(246, 241, 232, 0.72);
}

.login-options :deep(.el-checkbox__label) {
  color: rgba(246, 241, 232, 0.72);
  font-size: 13px;
}

.login-options :deep(.el-checkbox__input.is-checked .el-checkbox__inner) {
  border-color: #e5a044;
  background: #e5a044;
}

.login-options :deep(.el-checkbox__input.is-checked + .el-checkbox__label) {
  color: rgba(246, 241, 232, 0.82);
}

.text-button,
.switch-text button {
  padding: 0;
  border: 0;
  color: #e5a044;
  font: inherit;
  font-weight: 700;
  background: transparent;
  cursor: pointer;
}

.login-button {
  width: 100%;
  min-height: 48px;
  margin-top: 8px;
  border: 0;
  border-radius: 9px;
  color: #11100e;
  font-size: 15px;
  font-weight: 800;
  background: #e5a044;
  box-shadow: 0 18px 36px rgba(229, 160, 68, 0.22);
}

.login-button:hover,
.login-button:focus {
  color: #11100e;
  background: #ffc76d;
  transform: translateY(-1px);
}

.login-button:active {
  transform: translateY(0);
}

.switch-text {
  margin: 14px 0 0;
  text-align: center;
  color: rgba(246, 241, 232, 0.56);
  font-size: 14px;
}

.register-switch {
  margin-top: 4px;
}

@media (max-width: 900px) {
  .login-page {
    display: grid;
    place-items: center;
    padding: 18px;
  }

  .login-card {
    position: relative;
    left: auto;
    right: auto;
    top: auto;
    justify-self: center;
    transform: none;
  }

  .solar-stage {
    position: absolute;
    inset: 0;
    padding: 0;
    opacity: 0.42;
  }

  .universe-shell {
    inset: 0;
  }

  /* 移动端卡片居中覆盖 3D 场景，右侧渐变遮罩失效，改为整体均匀压暗 */
  .universe-shell::after {
    width: 100%;
    background: radial-gradient(ellipse at 50% 50%, rgba(3, 5, 8, 0.82), rgba(3, 5, 8, 0.9) 70%);
  }

  .orbit-one {
    width: 520px;
  }

  .orbit-two {
    width: 720px;
  }

  .orbit-three {
    width: 940px;
  }

  /* 移动端空间紧张且以触屏为主，隐藏交互提示 */
  .stage-hint {
    display: none;
  }

}

@media (max-width: 560px) {
  .login-page {
    padding: 18px;
  }

  .login-card {
    padding: 30px 22px;
  }

  .login-brand {
    grid-template-columns: 36px 1fr;
  }

  .brand-icon {
    width: 36px;
    height: 36px;
  }

  .login-brand h1 {
    font-size: 22px;
  }

}
</style>
