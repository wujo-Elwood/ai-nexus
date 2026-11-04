/**
 * 智能体绑定表单的默认技能保护逻辑（纯函数，供技能管理页与单元测试复用）
 */

/**
 * 恢复被移除的默认绑定技能
 *
 * @param {Object} form 绑定表单，结构为 { agentCode: [skillName, ...] }，会被就地修改
 * @param {Object} defaultBindings 默认绑定，结构为 { agentCode: [skillName, ...] }
 * @returns {Array<{agentCode: string, skillName: string}>} 被恢复的默认技能列表
 */
export function restoreMissingDefaults(form, defaultBindings) {
  const restored = []
  for (const [agentCode, names] of Object.entries(form)) {
    for (const skillName of defaultBindings[agentCode] || []) {
      if (!names.includes(skillName)) {
        names.push(skillName)
        restored.push({ agentCode, skillName })
      }
    }
  }
  return restored
}

/**
 * 校验待保存的绑定是否完整保留了默认技能
 *
 * @param {string} agentCode 智能体编码
 * @param {string[]} skillNames 待保存的技能列表
 * @param {Object} defaultBindings 默认绑定
 * @returns {string[]} 缺失的默认技能列表，空数组表示校验通过
 */
export function findMissingDefaults(agentCode, skillNames, defaultBindings) {
  const names = Array.isArray(skillNames) ? skillNames : []
  return (defaultBindings[agentCode] || []).filter(skillName => !names.includes(skillName))
}
