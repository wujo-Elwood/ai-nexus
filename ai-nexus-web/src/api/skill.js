import request from '../utils/request'

// 技能清单（含启用状态与绑定的智能体）
export function getSkillList() {
  return request.get('/api/skills')
}

// 启用或停用技能
export function updateSkillEnabled(skillName, enabled) {
  return request.put(`/api/skills/${skillName}/enabled`, { enabled })
}

// 读取全部智能体的技能绑定
export function getSkillBindings() {
  return request.get('/api/skills/bindings')
}

// 查询各智能体的默认绑定技能（平台内置，不可移除）
export function getDefaultBindings() {
  return request.get('/api/skills/default-bindings')
}

// 技能调用统计（近 days 天）
export function getSkillStats(days) {
  return request.get('/api/skills/stats', { params: { days } })
}

// 保存某智能体的技能绑定（全量替换）
export function saveSkillBindings(agentCode, skillNames) {
  return request.put('/api/skills/bindings', { agentCode, skillNames })
}

// 读取 web_search 技能搜索源配置（当前生效配置 + 平台默认）
export function getWebSearchConfig() {
  return request.get('/api/skills/web-search/config')
}

// 保存 web_search 技能搜索源配置（scope: user=我的配置 / platform=平台默认）
export function saveWebSearchConfig(payload) {
  return request.put('/api/skills/web-search/config', payload)
}

// 清除 web_search 技能搜索源配置
export function deleteWebSearchConfig(scope) {
  return request.delete('/api/skills/web-search/config', { params: { scope } })
}
