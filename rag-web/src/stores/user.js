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
    nickname.value = data.nickname || username.value
    // 第2步：同步到 localStorage，刷新页面后仍然显示最新昵称
    localStorage.setItem('username', username.value)
    localStorage.setItem('nickname', nickname.value)
  }

  // 退出登录并清理本地用户信息
  function logout() {
    token.value = ''
    userId.value = ''
    username.value = ''
    nickname.value = ''

    localStorage.removeItem('token')
    localStorage.removeItem('userId')
    localStorage.removeItem('username')
    localStorage.removeItem('nickname')
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
