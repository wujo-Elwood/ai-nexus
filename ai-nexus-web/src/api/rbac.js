import request from '../utils/request'

// 查询当前用户可见菜单
export function getCurrentMenus() {
  return request.get('/api/rbac/current-menus')
}

// 查询全部菜单树
export function getMenuTree() {
  return request.get('/api/rbac/menus')
}

// 新增菜单
export function createMenu(data) {
  return request.post('/api/rbac/menus', data)
}

// 修改菜单
export function updateMenu(id, data) {
  return request.put(`/api/rbac/menus/${id}`, data)
}

// 删除菜单
export function deleteMenu(id) {
  return request.delete(`/api/rbac/menus/${id}`)
}

// 查询全部角色
export function getRoleList() {
  return request.get('/api/rbac/roles')
}

// 新增角色
export function createRole(data) {
  return request.post('/api/rbac/roles', data)
}

// 修改角色
export function updateRole(id, data) {
  return request.put(`/api/rbac/roles/${id}`, data)
}

// 删除角色
export function deleteRole(id) {
  return request.delete(`/api/rbac/roles/${id}`)
}

// 查询角色菜单授权
export function getRoleMenuIds(id) {
  return request.get(`/api/rbac/roles/${id}/menus`)
}

// 保存角色菜单授权
export function saveRoleMenus(id, ids) {
  return request.put(`/api/rbac/roles/${id}/menus`, { ids })
}

// 查询用户列表
export function getRbacUsers() {
  return request.get('/api/rbac/users')
}

// 删除用户
export function deleteRbacUser(id) {
  return request.delete(`/api/rbac/users/${id}`)
}

// 查询用户角色授权
export function getUserRoles(id) {
  return request.get(`/api/rbac/users/${id}/roles`)
}

// 保存用户角色授权
export function saveUserRoles(id, ids) {
  return request.put(`/api/rbac/users/${id}/roles`, { ids })
}
