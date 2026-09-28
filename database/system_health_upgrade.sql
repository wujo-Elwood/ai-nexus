-- 系统健康面板菜单升级脚本，支持重复执行
USE rag_db;

-- 修复早期 Windows 客户端编码错误写入的菜单名称
UPDATE sys_menu
SET menu_name = CONVERT(0xE7B3BBE7BB9FE581A5E5BAB7 USING utf8mb4)
WHERE permission_code = 'health:view'
  AND menu_name <> CONVERT(0xE7B3BBE7BB9FE581A5E5BAB7 USING utf8mb4);

