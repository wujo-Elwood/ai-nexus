import axios from 'axios'
import { ElMessage } from 'element-plus'
import router from '../router'

const request = axios.create({
  baseURL: '',
  timeout: 30000
})

request.interceptors.request.use(
  config => {
    const token = localStorage.getItem('token')
    if (token) {
      config.headers.Authorization = `Bearer ${token}`
    }
    return config
  },
  error => {
    return Promise.reject(error)
  }
)

request.interceptors.response.use(
  response => {
    // 第1步：二进制下载接口直接返回完整响应
    if (response.config.responseType === 'blob') {
      return response
    }
    // 第2步：普通接口继续读取统一响应体
    const { data } = response
    // 第3步：业务成功时返回统一响应体
    if (data.code === 200) {
      return data
    }
    // 第4步：业务失败时提示后端错误信息
    ElMessage.error(data.message || 'Request failed')
    return Promise.reject(data)
  },
  error => {
    if (error.response?.status === 401) {
      localStorage.removeItem('token')
      router.push('/login')
      ElMessage.error('Session expired, please login again')
    } else {
      ElMessage.error(error.message || 'Network error')
    }
    return Promise.reject(error)
  }
)

export default request
