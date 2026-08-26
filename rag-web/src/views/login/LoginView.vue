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
      <div class="solar-video-shell">
        <video
          class="solar-video"
          autoplay
          muted
          loop
          playsinline
          preload="auto"
          poster="/images/ai-platform-background-2560x1440.png"
        >
          <source src="/videos/solar-system-panorama.mp4" type="video/mp4">
        </video>
        <span class="solar-video-glow"></span>
        <span class="solar-video-grain"></span>
      </div>
    </section>
  </main>
</template>

<script setup>
import { reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { login, register } from '../../api/auth'
import { useUserStore } from '../../stores/user'

const router = useRouter()
const userStore = useUserStore()

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
  color: #f6f1e8;
  background:
    radial-gradient(ellipse at 33% 46%, rgba(45, 97, 145, 0.28), transparent 34%),
    radial-gradient(ellipse at 68% 30%, rgba(63, 87, 115, 0.2), transparent 26%),
    radial-gradient(ellipse at 26% 80%, rgba(229, 160, 68, 0.08), transparent 28%),
    linear-gradient(180deg, #08090d 0%, #030508 58%, #020305 100%);
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
  border: 1px solid rgba(255, 255, 255, 0.1);
  border-radius: 16px;
  background:
    linear-gradient(145deg, rgba(34, 31, 27, 0.88), rgba(16, 17, 20, 0.92)),
    rgba(22, 23, 27, 0.88);
  box-shadow:
    inset 0 1px 0 rgba(255, 255, 255, 0.055),
    0 28px 90px rgba(0, 0, 0, 0.48);
  backdrop-filter: blur(22px);
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

.solar-video-shell {
  position: absolute;
  inset: 0;
  overflow: hidden;
  background: #030508;
}

.solar-video-shell::before {
  content: "";
  position: absolute;
  inset: 0;
  z-index: 2;
  background:
    radial-gradient(circle at 48% 51%, rgba(255, 198, 92, 0.2), transparent 20%),
    radial-gradient(ellipse at 24% 20%, rgba(86, 111, 157, 0.18), transparent 38%),
    linear-gradient(90deg, rgba(3, 5, 8, 0.04) 0%, rgba(3, 5, 8, 0.12) 45%, rgba(3, 5, 8, 0.56) 72%, rgba(3, 5, 8, 0.96) 100%),
    linear-gradient(180deg, rgba(2, 3, 6, 0.08), rgba(2, 3, 6, 0.34));
}

.solar-video-shell::after {
  content: "";
  position: absolute;
  top: 0;
  right: 0;
  bottom: 0;
  z-index: 3;
  width: min(58vw, 880px);
  background: linear-gradient(90deg, rgba(3, 5, 8, 0), rgba(3, 5, 8, 0.72) 42%, #030508 100%);
}

.solar-video {
  position: absolute;
  left: 50%;
  top: 50%;
  width: 100%;
  height: 100%;
  object-fit: cover;
  object-position: 42% center;
  opacity: 0.94;
  filter: saturate(1.08) contrast(1.05) brightness(0.86);
  transform: translate(-50%, -50%);
}

.solar-video-glow {
  position: absolute;
  left: 45%;
  top: 50%;
  z-index: 4;
  width: clamp(260px, 28vw, 520px);
  height: clamp(260px, 28vw, 520px);
  border-radius: 50%;
  background: radial-gradient(circle, rgba(255, 206, 116, 0.2) 0 14%, rgba(255, 164, 69, 0.12) 32%, transparent 70%);
  filter: blur(18px);
  mix-blend-mode: screen;
  transform: translate(-50%, -50%);
  animation: solarGlowPulse 5.8s ease-in-out infinite;
}

.solar-video-grain {
  position: absolute;
  inset: 0;
  z-index: 5;
  opacity: 0.18;
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

@keyframes solarGlowPulse {
  50% {
    opacity: 0.72;
    transform: translate(-50%, -50%) scale(1.08);
  }
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

  .solar-video-shell {
    inset: 0;
  }

  .solar-video {
    object-position: 44% center;
    filter: saturate(1.02) contrast(1.02) brightness(0.62);
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
