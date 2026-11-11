import { defineStore } from 'pinia'
import { ref } from 'vue'

export const useUserStore = defineStore('user', () => {
  const token = ref(localStorage.getItem('token') || '')
  const userId = ref(localStorage.getItem('userId') || '')
  const username = ref(localStorage.getItem('username') || '')
  const nickname = ref(localStorage.getItem('nickname') || '')

  // 保存登录成功后的用户信息
  function setUserInfo(data) {
    token.value = data.token
    userId.value = data.userId
    username.value = data.username
    nickname.value = data.nickname

    localStorage.setItem('token', data.token)
    localStorage.setItem('userId', data.userId)
    localStorage.setItem('username', data.username)
    localStorage.setItem('nickname', data.nickname)
  }

  // 更新当前用户的本地展示信息
  function updateProfile(data) {
    // 第1步：后端返回新资料后同步到 Pinia
    username.value = data.username || username.value
    nickname.value = data.nickname || nickname.value
    // 第2步：同步到 localStorage，刷新页面后仍然显示最新昵称
    localStorage.setItem('username', username.value)
    localStorage.setItem('nickname', nickname.value)
  }

  // 退出登录并清理本地用户信息
  function logout() {
    // 第1步：清掉登录态本身
    token.value = ''
    userId.value = ''
    username.value = ''
    nickname.value = ''

    localStorage.removeItem('token')
    localStorage.removeItem('userId')
    localStorage.removeItem('username')
    localStorage.removeItem('nickname')

    // 第2步：收集与账号绑定的本地缓存键：
    // mascot-* 系列（吉祥物名字/花色/换装/心情/成就等）和 agent-recent（最近智能体，
    // 会驱动侧边栏菜单标签），不清掉换账号后会看到上一个账号的名字和菜单
    const keysToRemove = []
    for (let i = 0; i < localStorage.length; i += 1) {
      const key = localStorage.key(i)
      if (!key) {
        continue
      }
      // 第3步：吉祥物与智能体的账号级缓存（agent-last-conversation:xxx 存着上个账号的会话 ID）
      if (key.startsWith('mascot-') || key === 'agent-recent' || key.startsWith('agent-last-conversation:')) {
        keysToRemove.push(key)
      }
    }
    // 第4步：统一移除（先收集再删，避免边遍历边删导致索引错位）；
    // 与登录无关的界面偏好（wjo_boot_done、ai-nexus-showcase-menu-open 等）保留不动
    keysToRemove.forEach((key) => localStorage.removeItem(key))
  }

  return {
    token,
    userId,
    username,
    nickname,
    setUserInfo,
    updateProfile,
    logout
  }
})
