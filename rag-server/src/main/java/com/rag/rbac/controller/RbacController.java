package com.rag.rbac.controller;

import com.rag.entity.SysUser;
import com.rag.rbac.dto.IdListRequest;
import com.rag.rbac.entity.SysMenu;
import com.rag.rbac.entity.SysRole;
import com.rag.rbac.service.RbacService;
import com.rag.rbac.vo.MenuTreeNode;
import com.rag.service.UserService;
import com.rag.vo.Result;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * RBAC 权限管理控制器
 * 提供当前用户菜单、菜单管理、角色管理和用户授权接口
 */
@RestController
@RequestMapping("/api/rbac")
public class RbacController {

    private final RbacService rbacService;
    private final UserService userService;

    /**
     * 创建 RBAC 权限管理控制器
     */
    public RbacController(RbacService rbacService, UserService userService) {
        this.rbacService = rbacService;
        this.userService = userService;
    }

    /**
     * 查询当前登录用户可见菜单
     */
    @GetMapping("/current-menus")
    public Result<List<MenuTreeNode>> listCurrentMenus(HttpServletRequest request) {
        // 第1步：读取当前登录用户编号
        Long userId = (Long) request.getAttribute("userId");
        // 第2步：按用户角色返回菜单树
        return Result.success(rbacService.listCurrentUserMenus(userId));
    }

    /**
     * 查询全部菜单
     */
    @GetMapping("/menus")
    public Result<List<MenuTreeNode>> listMenus() {
        // 第1步：返回全部菜单树，供菜单管理维护
        return Result.success(rbacService.listAllMenus());
    }

    /**
     * 新增菜单
     */
    @PostMapping("/menus")
    public Result<SysMenu> createMenu(@RequestBody SysMenu menu) {
        // 第1步：新增菜单并返回数据库编号
        return Result.success(rbacService.createMenu(menu));
    }

    /**
     * 修改菜单
     */
    @PutMapping("/menus/{id}")
    public Result<SysMenu> updateMenu(@PathVariable Long id, @RequestBody SysMenu menu) {
        // 第1步：更新指定菜单
        return Result.success(rbacService.updateMenu(id, menu));
    }

    /**
     * 删除菜单
     */
    @DeleteMapping("/menus/{id}")
    public Result<Void> deleteMenu(@PathVariable Long id) {
        // 第1步：删除菜单和相关授权
        rbacService.deleteMenu(id);
        return Result.success();
    }

    /**
     * 查询全部角色
     */
    @GetMapping("/roles")
    public Result<List<SysRole>> listRoles() {
        // 第1步：返回角色列表
        return Result.success(rbacService.listRoles());
    }

    /**
     * 新增角色
     */
    @PostMapping("/roles")
    public Result<SysRole> createRole(@RequestBody SysRole role) {
        // 第1步：新增角色
        return Result.success(rbacService.createRole(role));
    }

    /**
     * 修改角色
     */
    @PutMapping("/roles/{id}")
    public Result<SysRole> updateRole(@PathVariable Long id, @RequestBody SysRole role) {
        // 第1步：更新角色
        return Result.success(rbacService.updateRole(id, role));
    }

    /**
     * 删除角色
     */
    @DeleteMapping("/roles/{id}")
    public Result<Void> deleteRole(@PathVariable Long id) {
        // 第1步：删除角色和相关授权
        rbacService.deleteRole(id);
        return Result.success();
    }

    /**
     * 查询角色菜单授权
     */
    @GetMapping("/roles/{id}/menus")
    public Result<List<Long>> listRoleMenus(@PathVariable Long id) {
        // 第1步：返回角色已勾选菜单编号
        return Result.success(rbacService.listRoleMenuIds(id));
    }

    /**
     * 保存角色菜单授权
     */
    @PutMapping("/roles/{id}/menus")
    public Result<Void> saveRoleMenus(@PathVariable Long id, @RequestBody IdListRequest request) {
        // 第1步：保存角色菜单授权
        rbacService.saveRoleMenus(id, request.getIds());
        return Result.success();
    }

    /**
     * 查询用户列表
     */
    @GetMapping("/users")
    public Result<List<SysUser>> listUsers() {
        // 第1步：返回不包含密码的用户列表
        return Result.success(userService.listUsers());
    }

    /**
     * 删除用户
     */
    @DeleteMapping("/users/{id}")
    public Result<Void> deleteUser(@PathVariable Long id, HttpServletRequest request) {
        // 第1步：读取当前登录用户编号
        Long currentUserId = (Long) request.getAttribute("userId");
        // 第2步：删除指定用户及其角色授权
        userService.deleteUser(id, currentUserId);
        return Result.success();
    }

    /**
     * 查询用户角色授权
     */
    @GetMapping("/users/{id}/roles")
    public Result<List<SysRole>> listUserRoles(@PathVariable Long id) {
        // 第1步：返回用户拥有的角色
        return Result.success(rbacService.listUserRoles(id));
    }

    /**
     * 保存用户角色授权
     */
    @PutMapping("/users/{id}/roles")
    public Result<Void> saveUserRoles(@PathVariable Long id, @RequestBody IdListRequest request) {
        // 第1步：保存用户角色授权
        rbacService.saveUserRoles(id, request.getIds());
        return Result.success();
    }
}
