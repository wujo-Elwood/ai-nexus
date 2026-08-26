<template>
  <div class="password-page page-shell">
    <section class="page-hero">
      <div>
        <span class="eyebrow">账号中心</span>
        <h1 class="section-title">修改密码</h1>
        <p class="section-desc">修改成功后会退出当前登录，需要用新密码重新登录。</p>
      </div>
    </section>

    <section class="password-panel glass-panel motion-card">
      <div class="panel-head">
        <div>
          <h2>密码安全</h2>
          <p>请输入原密码，并设置一个不少于 6 位的新密码。</p>
        </div>
      </div>

      <el-form label-position="top" class="password-form" @submit.prevent>
        <el-form-item label="原密码">
          <el-input
            v-model="form.oldPassword"
            type="password"
            show-password
            autocomplete="current-password"
            placeholder="请输入原密码"
          />
        </el-form-item>
        <el-form-item label="新密码">
          <el-input
            v-model="form.newPassword"
            type="password"
            show-password
            autocomplete="new-password"
            placeholder="请输入新密码"
          />
        </el-form-item>
        <el-form-item label="确认新密码">
          <el-input
            v-model="form.confirmPassword"
            type="password"
            show-password
            autocomplete="new-password"
            placeholder="请再次输入新密码"
          />
        </el-form-item>
      </el-form>

      <div class="password-rules">
        <span :class="{ active: form.newPassword.length >= 6 }">至少 6 位</span>
        <span :class="{ active: form.newPassword && form.newPassword !== form.oldPassword }">不能与原密码相同</span>
        <span :class="{ active: form.confirmPassword && form.newPassword === form.confirmPassword }">两次输入一致</span>
      </div>

      <div class="form-actions">
        <el-button @click="clearForm">清空</el-button>
        <el-button type="primary" :loading="saving" @click="handleChangePassword">确认修改</el-button>
      </div>
    </section>
  </div>
</template>

<script setup>
import { reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { changeUserPassword } from '../../api/user'
import { useUserStore } from '../../stores/user'

const router = useRouter()
const userStore = useUserStore()
const saving = ref(false)
const form = reactive({
  oldPassword: '',
  newPassword: '',
  confirmPassword: ''
})

// 修改当前登录用户密码
async function handleChangePassword() {
  if (!validateForm()) {
    return
  }
  try {
    // 第1步：打开保存状态
    saving.value = true
    // 第2步：提交修改密码请求
    await changeUserPassword({
      oldPassword: form.oldPassword,
      newPassword: form.newPassword,
      confirmPassword: form.confirmPassword
    })
    // 第3步：修改成功后清理登录态，要求用户重新登录
    ElMessage.success('密码已修改，请重新登录')
    userStore.logout()
    router.push('/login')
  } catch (error) {
    // 第4步：修改失败时记录错误
    console.error(error)
  } finally {
    // 第5步：关闭保存状态
    saving.value = false
  }
}

// 校验修改密码表单
function validateForm() {
  // 第1步：校验必填项
  if (!form.oldPassword || !form.newPassword || !form.confirmPassword) {
    ElMessage.warning('请填写完整密码信息')
    return false
  }
  // 第2步：校验新密码长度
  if (form.newPassword.length < 6) {
    ElMessage.warning('新密码不能少于 6 位')
    return false
  }
  // 第3步：校验新旧密码不能相同
  if (form.newPassword === form.oldPassword) {
    ElMessage.warning('新密码不能和原密码相同')
    return false
  }
  // 第4步：校验两次新密码一致
  if (form.newPassword !== form.confirmPassword) {
    ElMessage.warning('两次输入的新密码不一致')
    return false
  }
  return true
}

// 清空修改密码表单
function clearForm() {
  // 第1步：清理三个密码输入框
  form.oldPassword = ''
  form.newPassword = ''
  form.confirmPassword = ''
}
</script>

<style scoped>
.password-page {
  width: min(760px, calc(100% - 64px));
}

.password-panel {
  padding: 24px;
  border-radius: 12px;
}

.panel-head {
  margin-bottom: 22px;
}

.panel-head h2 {
  margin: 0;
  color: var(--ink-color);
  font-size: 18px;
  font-weight: 850;
}

.panel-head p {
  margin-top: 6px;
  color: var(--muted-color);
}

.password-form {
  max-width: 560px;
}

.password-rules {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-top: 8px;
}

.password-rules span {
  min-height: 28px;
  display: inline-flex;
  align-items: center;
  padding: 0 10px;
  border: 1px solid var(--line-color);
  border-radius: 8px;
  color: var(--muted-color);
  background: rgba(247, 245, 242, 0.04);
  font-size: 12px;
  font-weight: 750;
}

.password-rules span.active {
  border-color: rgba(88, 214, 141, 0.42);
  color: var(--success-color);
  background: rgba(88, 214, 141, 0.1);
}

.form-actions {
  display: flex;
  justify-content: flex-end;
  gap: 10px;
  margin-top: 24px;
}

@media (max-width: 620px) {
  .password-page {
    width: min(100% - 28px, 760px);
  }

  .form-actions {
    align-items: stretch;
    flex-direction: column;
  }
}
</style>
