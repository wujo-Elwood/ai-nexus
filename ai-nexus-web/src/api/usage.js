import request from '../utils/request'

/**
 * 获取用量统计数据
 * 返回：today（今日统计）+ daily（近 7 天趋势）
 */
export function getUsageStats() {
  return request.get('/api/usage/stats')
}
