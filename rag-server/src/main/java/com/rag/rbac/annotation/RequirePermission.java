package com.rag.rbac.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 接口权限声明注解
 * 标注在控制器方法或类上，由 WebConfig 的认证拦截器统一校验当前登录用户
 * 是否拥有指定菜单权限编码，没有则返回 403
 * 权限编码与 sys_menu.permission_code 使用同一套值，保证前端菜单可见性和后端接口权限一致
 */
@Documented
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface RequirePermission {

    /** 菜单权限编码，如 rbac:manage、settings:view */
    String value();
}
