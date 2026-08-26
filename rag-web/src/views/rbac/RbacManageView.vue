<template>
  <div class="rbac-page page-shell">
    <section class="page-hero">
      <div>
        <span class="eyebrow">权限管理</span>
        <h1 class="section-title">菜单、角色、用户授权</h1>
        <p class="section-desc">
          菜单决定左侧入口，角色决定能看到哪些菜单，用户通过角色挂到对应功能入口。
        </p>
      </div>
      <el-button :loading="loading" @click="loadPage">
        <el-icon><Refresh /></el-icon>
        刷新
      </el-button>
    </section>

    <section class="rbac-panel glass-panel motion-card">
      <el-tabs v-model="activeTab" class="rbac-tabs">
        <el-tab-pane label="菜单管理" name="menus">
          <div class="tab-head">
            <div>
              <h2>菜单入口</h2>
              <p>维护平台左侧菜单的名称、路由、排序和启停状态。</p>
            </div>
            <el-button type="primary" @click="openMenuDialog()">
              <el-icon><Plus /></el-icon>
              新增菜单
            </el-button>
          </div>

          <el-table :data="menuTree" row-key="id" default-expand-all :tree-props="{ children: 'children' }">
            <el-table-column prop="menuName" label="菜单名称" min-width="160" />
            <el-table-column prop="path" label="路由" min-width="150" />
            <el-table-column prop="permissionCode" label="权限标识" min-width="150" />
            <el-table-column prop="sortNo" label="排序" width="80" />
            <el-table-column label="状态" width="150">
              <template #default="{ row }">
                <el-tag :type="row.enabled === 1 ? 'success' : 'info'">
                  {{ row.enabled === 1 ? '启用' : '停用' }}
                </el-tag>
                <el-tag class="state-tag" :type="row.visible === 1 ? 'success' : 'info'">
                  {{ row.visible === 1 ? '显示' : '隐藏' }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="180" fixed="right">
              <template #default="{ row }">
                <div class="table-actions">
                  <el-button text @click="openMenuDialog(row)">
                    <el-icon><Edit /></el-icon>
                    编辑
                  </el-button>
                  <el-button text type="danger" @click="handleDeleteMenu(row)">
                    <el-icon><Delete /></el-icon>
                    删除
                  </el-button>
                </div>
              </template>
            </el-table-column>
          </el-table>
        </el-tab-pane>

        <el-tab-pane label="角色管理" name="roles">
          <div class="role-layout">
            <div class="role-list">
              <div class="tab-head compact">
                <div>
                  <h2>角色列表</h2>
                  <p>创建角色，并给角色分配菜单。</p>
                </div>
                <el-button type="primary" @click="openRoleDialog()">
                  <el-icon><Plus /></el-icon>
                  新增角色
                </el-button>
              </div>

              <el-table :data="roleList" highlight-current-row @current-change="handleRoleCurrentChange">
                <el-table-column prop="roleName" label="角色名称" min-width="120" />
                <el-table-column prop="roleCode" label="编码" min-width="120" />
                <el-table-column label="状态" width="90">
                  <template #default="{ row }">
                    <el-tag :type="row.enabled === 1 ? 'success' : 'info'">
                      {{ row.enabled === 1 ? '启用' : '停用' }}
                    </el-tag>
                  </template>
                </el-table-column>
                <el-table-column label="操作" width="150" fixed="right">
                  <template #default="{ row }">
                    <div class="table-actions">
                      <el-button text @click="openRoleDialog(row)">编辑</el-button>
                      <el-button text type="danger" @click="handleDeleteRole(row)">删除</el-button>
                    </div>
                  </template>
                </el-table-column>
              </el-table>
            </div>

            <div class="auth-panel">
              <div class="tab-head compact">
                <div>
                  <h2>角色菜单授权</h2>
                  <p>{{ selectedRole ? `当前角色：${selectedRole.roleName}` : '请选择左侧角色' }}</p>
                </div>
                <el-button type="primary" :disabled="!selectedRoleId" :loading="savingRoleMenus" @click="handleSaveRoleMenus">
                  <el-icon><Check /></el-icon>
                  保存授权
                </el-button>
              </div>
              <el-tree
                ref="roleMenuTreeRef"
                class="menu-auth-tree"
                :data="menuTree"
                node-key="id"
                show-checkbox
                default-expand-all
                :props="{ label: 'menuName', children: 'children' }"
              />
            </div>
          </div>
        </el-tab-pane>

        <el-tab-pane label="用户授权" name="users">
          <div class="user-auth-layout">
            <div class="user-list">
              <div class="tab-head compact">
                <div>
                  <h2>用户列表</h2>
                  <p>选择用户后，在右侧勾选这个用户拥有的角色。</p>
                </div>
              </div>
              <el-table :data="userList" highlight-current-row @current-change="handleUserCurrentChange">
                <el-table-column prop="username" label="用户名" min-width="130" />
                <el-table-column prop="nickname" label="昵称" min-width="130" />
                <el-table-column label="操作" width="120" fixed="right">
                  <template #default="{ row }">
                    <div class="table-actions">
                      <el-button text type="danger" :disabled="isProtectedUser(row)" @click.stop="handleDeleteUser(row)">
                        <el-icon><Delete /></el-icon>
                        删除
                      </el-button>
                    </div>
                  </template>
                </el-table-column>
              </el-table>
            </div>

            <div class="auth-panel">
              <div class="tab-head compact">
                <div>
                  <h2>用户角色</h2>
                  <p>{{ selectedUser ? `当前用户：${selectedUser.username}` : '请选择左侧用户' }}</p>
                </div>
                <el-button type="primary" :disabled="!selectedUserId" :loading="savingUserRoles" @click="handleSaveUserRoles">
                  <el-icon><Check /></el-icon>
                  保存角色
                </el-button>
              </div>
              <el-checkbox-group v-model="selectedUserRoleIds" class="role-checkbox-list">
                <el-checkbox v-for="role in roleList" :key="role.id" :label="role.id" border>
                  {{ role.roleName }}
                  <span>{{ role.roleCode }}</span>
                </el-checkbox>
              </el-checkbox-group>
            </div>
          </div>
        </el-tab-pane>
      </el-tabs>
    </section>

    <el-dialog v-model="menuDialogVisible" :title="editingMenuId ? '编辑菜单' : '新增菜单'" width="560px">
      <el-form :model="menuForm" label-width="96px">
        <el-form-item label="父级菜单">
          <el-select v-model="menuForm.parentId" filterable>
            <el-option :value="0" label="根菜单" />
            <el-option
              v-for="item in flatMenus"
              :key="item.id"
              :value="item.id"
              :label="item.menuName"
              :disabled="item.id === editingMenuId"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="菜单名称">
          <el-input v-model="menuForm.menuName" placeholder="例如：知识库" />
        </el-form-item>
        <el-form-item label="路由地址">
          <el-input v-model="menuForm.path" placeholder="例如：/kb" />
        </el-form-item>
        <el-form-item label="路由名称">
          <el-input v-model="menuForm.routeName" placeholder="例如：KnowledgeBase" />
        </el-form-item>
        <el-form-item label="组件标识">
          <el-input v-model="menuForm.component" placeholder="用于说明对应页面组件" />
        </el-form-item>
        <el-form-item label="图标">
          <el-input v-model="menuForm.icon" placeholder="例如：database、shield" />
        </el-form-item>
        <el-form-item label="权限标识">
          <el-input v-model="menuForm.permissionCode" placeholder="例如：kb:view" />
        </el-form-item>
        <el-form-item label="菜单类型">
          <el-radio-group v-model="menuForm.menuType">
            <el-radio-button label="MENU">菜单</el-radio-button>
            <el-radio-button label="DIR">目录</el-radio-button>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="排序值">
          <el-input-number v-model="menuForm.sortNo" :min="0" :max="9999" />
        </el-form-item>
        <el-form-item label="状态">
          <div class="switch-row">
            <el-switch v-model="menuForm.visible" :active-value="1" :inactive-value="0" active-text="显示" inactive-text="隐藏" />
            <el-switch v-model="menuForm.enabled" :active-value="1" :inactive-value="0" active-text="启用" inactive-text="停用" />
          </div>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="menuDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="savingMenu" @click="handleSaveMenu">保存</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="roleDialogVisible" :title="editingRoleId ? '编辑角色' : '新增角色'" width="520px">
      <el-form :model="roleForm" label-width="96px">
        <el-form-item label="角色名称">
          <el-input v-model="roleForm.roleName" placeholder="例如：平台管理员" />
        </el-form-item>
        <el-form-item label="角色编码">
          <el-input v-model="roleForm.roleCode" placeholder="例如：admin" />
        </el-form-item>
        <el-form-item label="角色说明">
          <el-input v-model="roleForm.description" type="textarea" :rows="3" />
        </el-form-item>
        <el-form-item label="状态">
          <el-switch v-model="roleForm.enabled" :active-value="1" :inactive-value="0" active-text="启用" inactive-text="停用" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="roleDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="savingRole" @click="handleSaveRole">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, nextTick, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Check, Delete, Edit, Plus, Refresh } from '@element-plus/icons-vue'
import { useUserStore } from '../../stores/user'
import {
  createMenu,
  createRole,
  deleteMenu,
  deleteRbacUser,
  deleteRole,
  getMenuTree,
  getRbacUsers,
  getRoleList,
  getRoleMenuIds,
  getUserRoles,
  saveRoleMenus,
  saveUserRoles,
  updateMenu,
  updateRole
} from '../../api/rbac'

const userStore = useUserStore()
const activeTab = ref('menus')
const loading = ref(false)
const menuTree = ref([])
const roleList = ref([])
const userList = ref([])
const selectedRoleId = ref(null)
const selectedUserId = ref(null)
const selectedUserRoleIds = ref([])
const roleMenuTreeRef = ref(null)
const menuDialogVisible = ref(false)
const roleDialogVisible = ref(false)
const editingMenuId = ref(null)
const editingRoleId = ref(null)
const savingMenu = ref(false)
const savingRole = ref(false)
const savingRoleMenus = ref(false)
const savingUserRoles = ref(false)

const menuForm = reactive({
  parentId: 0,
  menuName: '',
  path: '',
  routeName: '',
  component: '',
  icon: '',
  menuType: 'MENU',
  permissionCode: '',
  sortNo: 0,
  visible: 1,
  enabled: 1
})

const roleForm = reactive({
  roleName: '',
  roleCode: '',
  description: '',
  enabled: 1
})

const selectedRole = computed(() => roleList.value.find(item => item.id === selectedRoleId.value))
const selectedUser = computed(() => userList.value.find(item => item.id === selectedUserId.value))
const flatMenus = computed(() => flattenMenus(menuTree.value))

onMounted(() => {
  loadPage()
})

// 加载权限管理页面数据
async function loadPage() {
  try {
    loading.value = true
    const [menuRes, roleRes, userRes] = await Promise.all([
      getMenuTree(),
      getRoleList(),
      getRbacUsers()
    ])
    menuTree.value = menuRes.data || []
    roleList.value = roleRes.data || []
    userList.value = userRes.data || []
    await selectDefaultRoleAndUser()
  } catch (error) {
    console.error(error)
  } finally {
    loading.value = false
  }
}

// 默认选中第一条角色和用户
async function selectDefaultRoleAndUser() {
  if (!selectedRoleId.value && roleList.value.length > 0) {
    selectedRoleId.value = roleList.value[0].id
    await loadRoleMenus(selectedRoleId.value)
  }
  if (!selectedUserId.value && userList.value.length > 0) {
    selectedUserId.value = userList.value[0].id
    await loadUserRoles(selectedUserId.value)
  }
}

// 展开菜单树为扁平列表
function flattenMenus(nodes) {
  const result = []
  const visit = list => {
    for (const item of list || []) {
      result.push(item)
      if (item.children?.length) {
        visit(item.children)
      }
    }
  }
  visit(nodes)
  return result
}

// 打开菜单新增或编辑弹窗
function openMenuDialog(row) {
  if (row) {
    editingMenuId.value = row.id
    Object.assign(menuForm, {
      parentId: row.parentId || 0,
      menuName: row.menuName || '',
      path: row.path || '',
      routeName: row.routeName || '',
      component: row.component || '',
      icon: row.icon || '',
      menuType: row.menuType || 'MENU',
      permissionCode: row.permissionCode || '',
      sortNo: row.sortNo || 0,
      visible: row.visible ?? 1,
      enabled: row.enabled ?? 1
    })
  } else {
    editingMenuId.value = null
    resetMenuForm()
  }
  menuDialogVisible.value = true
}

// 重置菜单表单
function resetMenuForm() {
  Object.assign(menuForm, {
    parentId: 0,
    menuName: '',
    path: '',
    routeName: '',
    component: '',
    icon: '',
    menuType: 'MENU',
    permissionCode: '',
    sortNo: 0,
    visible: 1,
    enabled: 1
  })
}

// 保存菜单
async function handleSaveMenu() {
  if (!menuForm.menuName.trim()) {
    ElMessage.warning('请填写菜单名称')
    return
  }
  try {
    savingMenu.value = true
    if (editingMenuId.value) {
      await updateMenu(editingMenuId.value, { ...menuForm })
      ElMessage.success('菜单已修改')
    } else {
      await createMenu({ ...menuForm })
      ElMessage.success('菜单已新增')
    }
    menuDialogVisible.value = false
    await loadPage()
  } catch (error) {
    console.error(error)
  } finally {
    savingMenu.value = false
  }
}

// 删除菜单
async function handleDeleteMenu(row) {
  try {
    await ElMessageBox.confirm(`确定删除菜单“${row.menuName}”吗？`, '删除菜单', {
      confirmButtonText: '删除',
      cancelButtonText: '取消',
      type: 'warning'
    })
  } catch {
    return
  }
  try {
    await deleteMenu(row.id)
    ElMessage.success('菜单已删除')
    await loadPage()
  } catch (error) {
    console.error(error)
  }
}

// 打开角色新增或编辑弹窗
function openRoleDialog(row) {
  if (row) {
    editingRoleId.value = row.id
    Object.assign(roleForm, {
      roleName: row.roleName || '',
      roleCode: row.roleCode || '',
      description: row.description || '',
      enabled: row.enabled ?? 1
    })
  } else {
    editingRoleId.value = null
    resetRoleForm()
  }
  roleDialogVisible.value = true
}

// 重置角色表单
function resetRoleForm() {
  Object.assign(roleForm, {
    roleName: '',
    roleCode: '',
    description: '',
    enabled: 1
  })
}

// 保存角色
async function handleSaveRole() {
  if (!roleForm.roleName.trim() || !roleForm.roleCode.trim()) {
    ElMessage.warning('请填写角色名称和角色编码')
    return
  }
  try {
    savingRole.value = true
    if (editingRoleId.value) {
      await updateRole(editingRoleId.value, { ...roleForm })
      ElMessage.success('角色已修改')
    } else {
      await createRole({ ...roleForm })
      ElMessage.success('角色已新增')
    }
    roleDialogVisible.value = false
    await loadPage()
  } catch (error) {
    console.error(error)
  } finally {
    savingRole.value = false
  }
}

// 删除角色
async function handleDeleteRole(row) {
  try {
    await ElMessageBox.confirm(`确定删除角色“${row.roleName}”吗？`, '删除角色', {
      confirmButtonText: '删除',
      cancelButtonText: '取消',
      type: 'warning'
    })
  } catch {
    return
  }
  try {
    await deleteRole(row.id)
    ElMessage.success('角色已删除')
    if (selectedRoleId.value === row.id) {
      selectedRoleId.value = null
    }
    await loadPage()
  } catch (error) {
    console.error(error)
  }
}

// 处理角色表格选中变化
async function handleRoleCurrentChange(row) {
  if (!row) {
    return
  }
  selectedRoleId.value = row.id
  await loadRoleMenus(row.id)
}

// 加载角色菜单授权
async function loadRoleMenus(roleId) {
  try {
    const res = await getRoleMenuIds(roleId)
    const ids = res.data || []
    await nextTick()
    roleMenuTreeRef.value?.setCheckedKeys(ids, false)
  } catch (error) {
    console.error(error)
  }
}

// 保存角色菜单授权
async function handleSaveRoleMenus() {
  if (!selectedRoleId.value) {
    ElMessage.warning('请先选择角色')
    return
  }
  try {
    savingRoleMenus.value = true
    const checkedKeys = roleMenuTreeRef.value?.getCheckedKeys(false) || []
    const halfCheckedKeys = roleMenuTreeRef.value?.getHalfCheckedKeys() || []
    const ids = Array.from(new Set([...checkedKeys, ...halfCheckedKeys]))
    await saveRoleMenus(selectedRoleId.value, ids)
    ElMessage.success('角色菜单授权已保存')
  } catch (error) {
    console.error(error)
  } finally {
    savingRoleMenus.value = false
  }
}

// 处理用户表格选中变化
async function handleUserCurrentChange(row) {
  if (!row) {
    return
  }
  selectedUserId.value = row.id
  await loadUserRoles(row.id)
}

// 判断用户是否禁止删除
function isProtectedUser(row) {
  return String(row.id) === String(userStore.userId) || row.username === 'admin'
}

// 删除用户
async function handleDeleteUser(row) {
  try {
    await ElMessageBox.confirm(`确定删除用户“${row.username}”吗？删除后会同时移除该用户的角色授权。`, '删除用户', {
      confirmButtonText: '删除',
      cancelButtonText: '取消',
      type: 'warning'
    })
  } catch {
    return
  }
  try {
    await deleteRbacUser(row.id)
    ElMessage.success('用户已删除')
    if (selectedUserId.value === row.id) {
      selectedUserId.value = null
      selectedUserRoleIds.value = []
    }
    await loadPage()
  } catch (error) {
    console.error(error)
  }
}

// 加载用户角色授权
async function loadUserRoles(userId) {
  try {
    const res = await getUserRoles(userId)
    selectedUserRoleIds.value = (res.data || []).map(item => item.id)
  } catch (error) {
    console.error(error)
  }
}

// 保存用户角色授权
async function handleSaveUserRoles() {
  if (!selectedUserId.value) {
    ElMessage.warning('请先选择用户')
    return
  }
  try {
    savingUserRoles.value = true
    await saveUserRoles(selectedUserId.value, selectedUserRoleIds.value)
    ElMessage.success('用户角色已保存')
  } catch (error) {
    console.error(error)
  } finally {
    savingUserRoles.value = false
  }
}
</script>

<style scoped>
.rbac-panel {
  padding: 20px;
  border-radius: 12px;
}

.tab-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 18px;
  margin-bottom: 16px;
}

.tab-head.compact {
  align-items: center;
}

.tab-head h2 {
  margin: 0;
  color: var(--ink-color);
  font-size: 16px;
  font-weight: 850;
}

.tab-head p {
  margin-top: 5px;
  color: var(--muted-color);
  font-size: 13px;
  line-height: 1.6;
}

.state-tag {
  margin-left: 6px;
}

.table-actions {
  display: flex;
  align-items: center;
  gap: 4px;
}

.role-layout,
.user-auth-layout {
  display: grid;
  grid-template-columns: minmax(360px, 1fr) minmax(360px, 1fr);
  gap: 18px;
}

.role-list,
.user-list,
.auth-panel {
  min-width: 0;
}

.menu-auth-tree {
  max-height: 540px;
  overflow: auto;
  padding: 12px;
  border: 1px solid var(--line-color);
  border-radius: 12px;
  background: rgba(247, 245, 242, 0.03);
}

.role-checkbox-list {
  display: grid;
  gap: 10px;
}

.role-checkbox-list :deep(.el-checkbox) {
  width: 100%;
  height: auto;
  min-height: 42px;
  margin-right: 0;
}

.role-checkbox-list span {
  margin-left: 8px;
  color: var(--muted-color);
  font-size: 12px;
}

.switch-row {
  display: flex;
  align-items: center;
  gap: 18px;
}

@media (max-width: 980px) {
  .role-layout,
  .user-auth-layout {
    grid-template-columns: 1fr;
  }
}

@media (max-width: 620px) {
  .tab-head {
    align-items: stretch;
    flex-direction: column;
  }
}
</style>
