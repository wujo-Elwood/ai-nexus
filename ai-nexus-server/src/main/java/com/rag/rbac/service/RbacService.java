package com.rag.rbac.service;

import com.rag.common.BusinessException;
import com.rag.rbac.entity.SysMenu;
import com.rag.rbac.entity.SysRole;
import com.rag.rbac.mapper.SysMenuMapper;
import com.rag.rbac.mapper.SysRoleMapper;
import com.rag.rbac.vo.MenuTreeNode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * RBAC 权限服务
 * 负责菜单树、角色菜单授权和用户角色授权
 */
@Service
public class RbacService {

    private final SysMenuMapper menuMapper;
    private final SysRoleMapper roleMapper;

    /**
     * 创建 RBAC 权限服务
     */
    public RbacService(SysMenuMapper menuMapper, SysRoleMapper roleMapper) {
        this.menuMapper = menuMapper;
        this.roleMapper = roleMapper;
    }

    /**
     * 查询当前用户可见菜单树
     */
    public List<MenuTreeNode> listCurrentUserMenus(Long userId) {
        // 第1步：用户编号为空时直接拒绝
        if (userId == null) {
            throw new BusinessException(401, "请先登录");
        }
        // 第2步：按用户角色查询可见菜单，并整理成树
        return buildMenuTree(menuMapper.findByUserId(userId));
    }

    /**
     * 判断用户是否拥有指定菜单权限
     */
    public boolean hasPermission(Long userId, String permissionCode) {
        // 第1步：登录用户和权限编码为空时直接拒绝
        if (userId == null || permissionCode == null || permissionCode.isBlank()) {
            return false;
        }
        // 第2步：复用当前用户菜单查询，确保启用状态和角色状态判断一致
        return menuMapper.findByUserId(userId).stream()
                .anyMatch(menu -> permissionCode.equals(menu.getPermissionCode()));
    }

    /**
     * 判断用户是否拥有启用的指定角色
     */
    public boolean hasRole(Long userId, String roleCode) {
        if (userId == null || roleCode == null || roleCode.isBlank()) {
            return false;
        }
        return roleMapper.findByUserId(userId).stream()
                .anyMatch(role -> role.getEnabled() != null && role.getEnabled() == 1
                        && roleCode.equals(role.getRoleCode()));
    }

    /**
     * 查询全部菜单树
     */
    public List<MenuTreeNode> listAllMenus() {
        // 第1步：菜单管理页面需要展示全部菜单，包括隐藏菜单
        return buildMenuTree(menuMapper.findAll());
    }

    /**
     * 新增菜单
     */
    public SysMenu createMenu(SysMenu menu) {
        // 第1步：清洗菜单基础字段
        normalizeMenu(menu);
        // 第2步：写入菜单并返回带编号的数据
        menuMapper.insert(menu);
        return menu;
    }

    /**
     * 修改菜单
     */
    public SysMenu updateMenu(Long id, SysMenu menu) {
        // 第1步：确认菜单存在
        SysMenu oldMenu = requireMenu(id);
        // 第2步：保留编号并清洗字段
        menu.setId(oldMenu.getId());
        normalizeMenu(menu);
        menuMapper.update(menu);
        return menuMapper.findById(id);
    }

    /**
     * 删除菜单
     */
    @Transactional
    public void deleteMenu(Long id) {
        // 第1步：确认菜单存在
        requireMenu(id);
        // 第2步：先删除角色授权，再删除菜单本身
        menuMapper.deleteRoleMenuByMenuId(id);
        menuMapper.deleteById(id);
    }

    /**
     * 查询全部角色
     */
    public List<SysRole> listRoles() {
        // 第1步：直接返回全部角色，供角色管理和用户授权使用
        return roleMapper.findAll();
    }

    /**
     * 新增角色
     */
    public SysRole createRole(SysRole role) {
        // 第1步：清洗角色字段
        normalizeRole(role);
        // 第2步：写入角色并返回
        roleMapper.insert(role);
        return role;
    }

    /**
     * 修改角色
     */
    public SysRole updateRole(Long id, SysRole role) {
        // 第1步：确认角色存在
        requireRole(id);
        // 第2步：保留编号并更新
        role.setId(id);
        normalizeRole(role);
        roleMapper.update(role);
        return roleMapper.findById(id);
    }

    /**
     * 删除角色
     */
    @Transactional
    public void deleteRole(Long id) {
        // 第1步：确认角色存在
        requireRole(id);
        // 第2步：删除角色相关授权，再删除角色
        roleMapper.deleteUserRoleByRoleId(id);
        roleMapper.deleteRoleMenuByRoleId(id);
        roleMapper.deleteById(id);
    }

    /**
     * 查询角色菜单编号
     */
    public List<Long> listRoleMenuIds(Long roleId) {
        // 第1步：确认角色存在
        requireRole(roleId);
        // 第2步：返回角色已授权菜单编号
        return roleMapper.findMenuIdsByRoleId(roleId);
    }

    /**
     * 保存角色菜单授权
     */
    @Transactional
    public void saveRoleMenus(Long roleId, List<Long> menuIds) {
        // 第1步：确认角色存在
        requireRole(roleId);
        // 第2步：清空旧授权
        roleMapper.deleteRoleMenus(roleId);
        // 第3步：逐条写入新授权
        for (Long menuId : safeIdList(menuIds)) {
            roleMapper.insertRoleMenu(roleId, menuId);
        }
    }

    /**
     * 查询用户角色
     */
    public List<SysRole> listUserRoles(Long userId) {
        // 第1步：返回用户已拥有的角色
        return roleMapper.findByUserId(userId);
    }

    /**
     * 保存用户角色授权
     */
    @Transactional
    public void saveUserRoles(Long userId, List<Long> roleIds) {
        // 第1步：清空旧角色
        roleMapper.deleteUserRoles(userId);
        // 第2步：逐条写入新角色
        for (Long roleId : safeIdList(roleIds)) {
            roleMapper.insertUserRole(userId, roleId);
        }
    }

    /**
     * 构建菜单树
     */
    private List<MenuTreeNode> buildMenuTree(List<SysMenu> menus) {
        // 第1步：先按排序号和编号稳定排序
        List<SysMenu> sortedMenus = new ArrayList<>(menus == null ? List.of() : menus);
        sortedMenus.sort(Comparator
                .comparing((SysMenu menu) -> menu.getSortNo() == null ? 0 : menu.getSortNo())
                .thenComparing(menu -> menu.getId() == null ? 0L : menu.getId()));
        // 第2步：把实体转换成节点，并用 LinkedHashMap 保留排序
        Map<Long, MenuTreeNode> nodeMap = new LinkedHashMap<>();
        for (SysMenu menu : sortedMenus) {
            nodeMap.put(menu.getId(), buildMenuNode(menu));
        }
        // 第3步：按 parentId 组装父子层级
        List<MenuTreeNode> roots = new ArrayList<>();
        for (MenuTreeNode node : nodeMap.values()) {
            Long parentId = node.getParentId() == null ? 0L : node.getParentId();
            MenuTreeNode parent = nodeMap.get(parentId);
            if (parentId == 0L || parent == null) {
                roots.add(node);
            } else {
                parent.getChildren().add(node);
            }
        }
        return roots;
    }

    /**
     * 构建单个菜单树节点
     */
    private MenuTreeNode buildMenuNode(SysMenu menu) {
        // 第1步：复制前端需要的菜单字段
        MenuTreeNode node = new MenuTreeNode();
        node.setId(menu.getId());
        node.setParentId(menu.getParentId());
        node.setMenuName(menu.getMenuName());
        node.setPath(menu.getPath());
        node.setRouteName(menu.getRouteName());
        node.setComponent(menu.getComponent());
        node.setIcon(menu.getIcon());
        node.setMenuType(menu.getMenuType());
        node.setPermissionCode(menu.getPermissionCode());
        node.setSortNo(menu.getSortNo());
        node.setVisible(menu.getVisible());
        node.setEnabled(menu.getEnabled());
        return node;
    }

    /**
     * 清洗菜单字段
     */
    private void normalizeMenu(SysMenu menu) {
        // 第1步：菜单名称不能为空
        String menuName = menu.getMenuName() == null ? "" : menu.getMenuName().trim();
        if (menuName.isEmpty()) {
            throw new BusinessException(400, "菜单名称不能为空");
        }
        // 第2步：设置默认字段，降低前端新增菜单的填写成本
        menu.setMenuName(menuName);
        menu.setParentId(menu.getParentId() == null ? 0L : menu.getParentId());
        menu.setPath(menu.getPath() == null ? "" : menu.getPath().trim());
        menu.setRouteName(menu.getRouteName() == null ? "" : menu.getRouteName().trim());
        menu.setComponent(menu.getComponent() == null ? "" : menu.getComponent().trim());
        menu.setIcon(menu.getIcon() == null ? "" : menu.getIcon().trim());
        menu.setMenuType(menu.getMenuType() == null || menu.getMenuType().isBlank() ? "MENU" : menu.getMenuType().trim());
        menu.setPermissionCode(menu.getPermissionCode() == null ? "" : menu.getPermissionCode().trim());
        menu.setSortNo(menu.getSortNo() == null ? 0 : menu.getSortNo());
        menu.setVisible(menu.getVisible() == null ? 1 : menu.getVisible());
        menu.setEnabled(menu.getEnabled() == null ? 1 : menu.getEnabled());
    }

    /**
     * 清洗角色字段
     */
    private void normalizeRole(SysRole role) {
        // 第1步：角色名称和编码不能为空
        String roleName = role.getRoleName() == null ? "" : role.getRoleName().trim();
        String roleCode = role.getRoleCode() == null ? "" : role.getRoleCode().trim();
        if (roleName.isEmpty() || roleCode.isEmpty()) {
            throw new BusinessException(400, "角色名称和角色编码不能为空");
        }
        // 第2步：回填清洗后的角色字段
        role.setRoleName(roleName);
        role.setRoleCode(roleCode);
        role.setDescription(role.getDescription() == null ? "" : role.getDescription().trim());
        role.setEnabled(role.getEnabled() == null ? 1 : role.getEnabled());
    }

    /**
     * 查询必须存在的菜单
     */
    private SysMenu requireMenu(Long id) {
        // 第1步：按编号查询菜单
        SysMenu menu = menuMapper.findById(id);
        if (menu == null) {
            throw new BusinessException(404, "菜单不存在");
        }
        return menu;
    }

    /**
     * 查询必须存在的角色
     */
    private SysRole requireRole(Long id) {
        // 第1步：按编号查询角色
        SysRole role = roleMapper.findById(id);
        if (role == null) {
            throw new BusinessException(404, "角色不存在");
        }
        return role;
    }

    /**
     * 清洗编号列表
     */
    private List<Long> safeIdList(List<Long> ids) {
        // 第1步：过滤空编号，避免写入无效授权
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        return ids.stream().filter(id -> id != null && id > 0).distinct().toList();
    }
}
