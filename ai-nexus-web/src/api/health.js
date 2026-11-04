import request from '../utils/request'

// 查询系统健康概览
export function getSystemHealth() {
  return request.get('/api/system-health/overview')
}
