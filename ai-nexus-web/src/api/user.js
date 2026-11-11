import request from '../utils/request'

// 获取当前登录用户资料
export function getUserProfile() {
  return request.get('/api/user/profile')
}

// 修改当前登录用户资料
export function updateUserProfile(data) {
  return request.put('/api/user/profile', data)
}

// 修改当前登录用户密码
export function changeUserPassword(data) {
  return request.put('/api/user/password', data)
}
