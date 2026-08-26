-- 系统健康面板菜单升级脚本，支持重复执行
USE rag_db;

INSERT INTO sys_menu (parent_id, menu_name, path, route_name, component, icon, menu_type,
                      permission_code, sort_no, visible, enabled)
SELECT 0, CONVERT(0xE7B3BBE7BB9FE581A5E5BAB7 USING utf8mb4), '/health', 'SystemHealth', 'SystemHealthView', 'monitor', 'MENU',
       'health:view', 75, 1, 1
WHERE NOT EXISTS (
    SELECT 1 FROM sys_menu WHERE permission_code = 'health:view'
);

-- 修复早期 Windows 客户端编码错误写入的菜单名称
UPDATE sys_menu
SET menu_name = CONVERT(0xE7B3BBE7BB9FE581A5E5BAB7 USING utf8mb4)
WHERE permission_code = 'health:view'
  AND menu_name <> CONVERT(0xE7B3BBE7BB9FE581A5E5BAB7 USING utf8mb4);

INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT r.id, m.id
FROM sys_role r
INNER JOIN sys_menu m ON m.permission_code = 'health:view'
WHERE r.role_code = 'admin';
