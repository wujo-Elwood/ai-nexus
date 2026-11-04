import request from '../utils/request'

/** 查询指定时间窗口的知识缺口报告。 */
export function getKnowledgeGapReport(days) {
  return request.get('/api/knowledge-gaps/report', { params: { days } })
}

/** 管理员提交指定时间窗口的知识缺口分析。 */
export function analyzeKnowledgeGap(days) {
  return request.post('/api/knowledge-gaps/analyze', null, { params: { days } })
}
