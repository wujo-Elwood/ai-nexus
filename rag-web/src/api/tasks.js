import request from '../utils/request'

// 查询统一任务中心列表
export function getTasks(params) { return request.get('/api/tasks', { params }) }

// 重试统一任务中心任务
export function retryTask(type, id) { return request.post(`/api/tasks/${type}/${id}/retry`) }

// 取消统一任务中心任务
export function cancelTask(type, id) { return request.post(`/api/tasks/${type}/${id}/cancel`) }
