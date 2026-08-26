<template>
  <div class="user-page page-shell">
    <section class="page-hero">
      <div>
        <span class="eyebrow">账号中心</span>
        <h1 class="section-title">个人资料</h1>
        <p class="section-desc">修改当前账号在平台里的展示信息，用户名用于登录，不在这里修改。</p>
      </div>
    </section>

    <section class="profile-layout">
      <article class="profile-card glass-panel motion-card">
        <div class="profile-avatar">
          <span>{{ avatarText }}</span>
        </div>
        <h2>{{ form.nickname || form.username || '用户' }}</h2>
        <p>{{ form.username || '暂无用户名' }}</p>
        <div class="profile-meta">
          <span>用户ID</span>
          <strong>{{ form.userId || '--' }}</strong>
        </div>
        <div class="profile-meta">
          <span>注册时间</span>
          <strong>{{ formatDateTime(form.createTime) }}</strong>
        </div>
      </article>

      <article class="profile-form-panel glass-panel motion-card">
        <div class="panel-head">
          <div>
            <h2>资料信息</h2>
            <p>保存后右上角昵称会立即同步。</p>
          </div>
          <el-button :loading="loading" @click="loadProfile">刷新</el-button>
        </div>

        <el-form label-position="top" class="profile-form">
          <el-form-item label="用户名">
            <el-input v-model="form.username" disabled />
          </el-form-item>
          <el-form-item label="昵称">
            <el-input v-model="form.nickname" maxlength="50" show-word-limit placeholder="请输入昵称" />
          </el-form-item>
          <el-form-item label="头像地址">
            <el-input v-model="form.avatar" maxlength="255" show-word-limit placeholder="可填写头像图片 URL，暂不上传文件" />
          </el-form-item>
        </el-form>

        <div class="form-actions">
          <el-button @click="resetForm">重置</el-button>
          <el-button type="primary" :loading="saving" @click="handleSave">保存资料</el-button>
        </div>
      </article>
    </section>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { getUserProfile, updateUserProfile } from '../../api/user'
import { useUserStore } from '../../stores/user'

const userStore = useUserStore()
const loading = ref(false)
const saving = ref(false)
const originalProfile = ref(null)
const form = reactive({
  userId: '',
  username: '',
  nickname: '',
  avatar: '',
  createTime: ''
})

// 计算头像占位文字
const avatarText = computed(() => {
  // 第1步：优先使用昵称首字母或首个汉字
  const name = form.nickname || form.username || 'U'
  // 第2步：截取第一个字符展示到头像里
  return name.charAt(0).toUpperCase()
})

onMounted(() => {
  loadProfile()
})

// 加载当前用户资料
async function loadProfile() {
  try {
    // 第1步：打开加载状态
    loading.value = true
    // 第2步：读取后端当前登录用户资料
    const res = await getUserProfile()
    // 第3步：回填页面表单和原始快照
    fillForm(res.data || {})
    originalProfile.value = { ...form }
  } catch (error) {
    // 第4步：加载失败时记录错误
    console.error(error)
  } finally {
    // 第5步：关闭加载状态
    loading.value = false
  }
}

// 保存当前用户资料
async function handleSave() {
  const nickname = form.nickname.trim()
  if (!nickname) {
    // 第1步：昵称为空时提醒用户
    ElMessage.warning('昵称不能为空')
    return
  }
  try {
    // 第2步：打开保存状态
    saving.value = true
    // 第3步：提交资料修改
    const res = await updateUserProfile({
      nickname,
      avatar: form.avatar.trim()
    })
    // 第4步：同步页面、Pinia 和 localStorage
    fillForm(res.data || {})
    originalProfile.value = { ...form }
    userStore.updateProfile(res.data || {})
    ElMessage.success('资料已保存')
  } catch (error) {
    // 第5步：保存失败时记录错误
    console.error(error)
  } finally {
    // 第6步：关闭保存状态
    saving.value = false
  }
}

// 重置为最近一次加载或保存的资料
function resetForm() {
  // 第1步：没有原始资料时重新加载
  if (!originalProfile.value) {
    loadProfile()
    return
  }
  // 第2步：恢复页面表单
  fillForm(originalProfile.value)
}

// 回填用户资料表单
function fillForm(profile) {
  // 第1步：逐项写入表单，避免替换 reactive 对象
  form.userId = profile.userId || ''
  form.username = profile.username || ''
  form.nickname = profile.nickname || ''
  form.avatar = profile.avatar || ''
  form.createTime = profile.createTime || ''
}

// 格式化日期时间
function formatDateTime(dateStr) {
  // 第1步：空时间返回占位符
  if (!dateStr) {
    return '--'
  }
  // 第2步：使用中文日期时间格式
  return new Date(dateStr).toLocaleString('zh-CN')
}
</script>

<style scoped>
.user-page {
  width: min(980px, calc(100% - 64px));
}

.profile-layout {
  display: grid;
  grid-template-columns: 280px minmax(0, 1fr);
  gap: 16px;
}

.profile-card,
.profile-form-panel {
  border-radius: 12px;
}

.profile-card {
  min-height: 360px;
  display: flex;
  flex-direction: column;
  align-items: center;
  padding: 28px 22px;
  text-align: center;
}

.profile-avatar {
  width: 86px;
  height: 86px;
  display: grid;
  place-items: center;
  border: 1px solid rgba(229, 160, 68, 0.34);
  border-radius: 22px;
  color: var(--primary-color);
  background:
    radial-gradient(circle at 30% 20%, rgba(255, 230, 170, 0.2), transparent 52%),
    rgba(229, 160, 68, 0.12);
  font-size: 34px;
  font-weight: 900;
}

.profile-card h2 {
  margin-top: 18px;
  color: var(--ink-color);
  font-size: 20px;
  font-weight: 850;
}

.profile-card p {
  margin-top: 6px;
  color: var(--muted-color);
}

.profile-meta {
  width: 100%;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-top: 18px;
  padding: 13px 0;
  border-top: 1px solid var(--line-color);
}

.profile-meta span {
  color: var(--muted-color);
}

.profile-meta strong {
  color: var(--ink-color);
  font-weight: 850;
}

.profile-form-panel {
  padding: 22px;
}

.panel-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 20px;
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

.profile-form {
  max-width: 620px;
}

/* 去掉输入框字数统计的默认白底，只保留普通文字 */
.profile-form :deep(.el-input__count) {
  color: var(--muted-color);
  background: transparent;
  box-shadow: none;
}

/* Element Plus 会给字数统计内部再套一层背景，这里一起清掉 */
.profile-form :deep(.el-input__count-inner) {
  padding: 0;
  color: var(--muted-color);
  background: transparent;
  border: 0;
  border-radius: 0;
  box-shadow: none;
}

.form-actions {
  display: flex;
  justify-content: flex-end;
  gap: 10px;
  margin-top: 18px;
}

@media (max-width: 860px) {
  .profile-layout {
    grid-template-columns: 1fr;
  }
}

@media (max-width: 620px) {
  .user-page {
    width: min(100% - 28px, 980px);
  }

  .panel-head,
  .form-actions {
    align-items: stretch;
    flex-direction: column;
  }
}
</style>
