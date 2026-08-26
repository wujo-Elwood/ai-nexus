USE rag_db;

-- 第1步：创建系统菜单表
CREATE TABLE IF NOT EXISTS sys_menu (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    parent_id BIGINT DEFAULT 0 COMMENT '父级菜单ID，0表示根节点',
    menu_name VARCHAR(100) NOT NULL COMMENT '菜单名称',
    path VARCHAR(200) DEFAULT '' COMMENT '前端路由地址',
    route_name VARCHAR(100) DEFAULT '' COMMENT '前端路由名称',
    component VARCHAR(255) DEFAULT '' COMMENT '前端组件路径',
    icon VARCHAR(80) DEFAULT '' COMMENT '菜单图标',
    menu_type VARCHAR(20) DEFAULT 'MENU' COMMENT '菜单类型：DIR=目录，MENU=菜单',
    permission_code VARCHAR(120) DEFAULT '' COMMENT '权限标识',
    sort_no INT DEFAULT 0 COMMENT '排序号',
    visible TINYINT DEFAULT 1 COMMENT '是否可见：1=显示，0=隐藏',
    enabled TINYINT DEFAULT 1 COMMENT '是否启用：1=启用，0=停用',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_parent_id (parent_id),
    INDEX idx_permission_code (permission_code),
    INDEX idx_sort_no (sort_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 第2步：创建系统角色表
CREATE TABLE IF NOT EXISTS sys_role (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    role_name VARCHAR(80) NOT NULL COMMENT '角色名称',
    role_code VARCHAR(80) NOT NULL COMMENT '角色编码',
    description VARCHAR(500) DEFAULT '' COMMENT '角色说明',
    enabled TINYINT DEFAULT 1 COMMENT '是否启用：1=启用，0=停用',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_role_code (role_code),
    INDEX idx_enabled (enabled)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 第3步：创建用户角色关联表
CREATE TABLE IF NOT EXISTS sys_user_role (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL COMMENT '用户ID',
    role_id BIGINT NOT NULL COMMENT '角色ID',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_user_role (user_id, role_id),
    INDEX idx_user_id (user_id),
    INDEX idx_role_id (role_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 第4步：创建角色菜单关联表
CREATE TABLE IF NOT EXISTS sys_role_menu (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    role_id BIGINT NOT NULL COMMENT '角色ID',
    menu_id BIGINT NOT NULL COMMENT '菜单ID',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_role_menu (role_id, menu_id),
    INDEX idx_role_id (role_id),
    INDEX idx_menu_id (menu_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 第5步：初始化平台菜单入口
INSERT INTO sys_menu (parent_id, menu_name, path, route_name, component, icon, menu_type, permission_code, sort_no, visible, enabled)
SELECT 0, '知识库', '/kb', 'KnowledgeBase', 'KbView', 'database', 'MENU', 'kb:view', 10, 1, 1
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE permission_code = 'kb:view');

INSERT INTO sys_menu (parent_id, menu_name, path, route_name, component, icon, menu_type, permission_code, sort_no, visible, enabled)
SELECT 0, 'AI 聊天', '/chat', 'Chat', 'ChatView', 'message-circle', 'MENU', 'chat:view', 20, 1, 1
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE permission_code = 'chat:view');

INSERT INTO sys_menu (parent_id, menu_name, path, route_name, component, icon, menu_type, permission_code, sort_no, visible, enabled)
SELECT 0, 'AI 生图', '/image', 'ImageGenerate', 'ImageGenerateView', 'image', 'MENU', 'image:view', 30, 1, 1
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE permission_code = 'image:view');

INSERT INTO sys_menu (parent_id, menu_name, path, route_name, component, icon, menu_type, permission_code, sort_no, visible, enabled)
SELECT 0, '文档抽取', '/extract', 'Extract', 'ExtractView', 'file-text', 'MENU', 'extract:view', 40, 1, 1
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE permission_code = 'extract:view');

INSERT INTO sys_menu (parent_id, menu_name, path, route_name, component, icon, menu_type, permission_code, sort_no, visible, enabled)
SELECT 0, '智能体管理', '/agents', 'Agents', 'AgentView', 'cpu', 'MENU', 'agents:view', 50, 1, 1
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE permission_code = 'agents:view');

INSERT INTO sys_menu (parent_id, menu_name, path, route_name, component, icon, menu_type, permission_code, sort_no, visible, enabled)
SELECT 0, '模型设置', '/settings', 'ModelSettings', 'ModelSettings', 'settings', 'MENU', 'settings:view', 60, 1, 1
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE permission_code = 'settings:view');

INSERT INTO sys_menu (parent_id, menu_name, path, route_name, component, icon, menu_type, permission_code, sort_no, visible, enabled)
SELECT 0, '用量统计', '/stats', 'Stats', 'StatsView', 'bar-chart', 'MENU', 'stats:view', 70, 1, 1
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE permission_code = 'stats:view');

INSERT INTO sys_menu (parent_id, menu_name, path, route_name, component, icon, menu_type, permission_code, sort_no, visible, enabled)
SELECT 0, '权限管理', '/rbac', 'RbacManage', 'RbacManageView', 'shield', 'MENU', 'rbac:manage', 80, 1, 1
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE permission_code = 'rbac:manage');

-- 第6步：初始化平台角色
INSERT INTO sys_role (role_name, role_code, description, enabled)
SELECT '平台管理员', 'admin', '拥有平台全部菜单和权限管理能力', 1
WHERE NOT EXISTS (SELECT 1 FROM sys_role WHERE role_code = 'admin');

INSERT INTO sys_role (role_name, role_code, description, enabled)
SELECT '普通用户', 'user', '拥有知识库、聊天、生图、文档抽取和智能体入口', 1
WHERE NOT EXISTS (SELECT 1 FROM sys_role WHERE role_code = 'user');

-- 第7步：给管理员角色授权全部菜单
INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT r.id, m.id
FROM sys_role r
INNER JOIN sys_menu m ON 1 = 1
WHERE r.role_code = 'admin';

-- 第8步：给普通用户角色授权业务菜单
INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT r.id, m.id
FROM sys_role r
INNER JOIN sys_menu m ON m.permission_code IN ('kb:view', 'chat:view', 'image:view', 'extract:view', 'agents:view')
WHERE r.role_code = 'user';

-- 第9步：把 admin 用户挂到管理员角色
INSERT IGNORE INTO sys_user_role (user_id, role_id)
SELECT u.id, r.id
FROM sys_user u
INNER JOIN sys_role r ON r.role_code = 'admin'
WHERE u.username = 'admin';

-- 第10步：给没有角色的历史用户补普通用户角色
INSERT IGNORE INTO sys_user_role (user_id, role_id)
SELECT u.id, r.id
FROM sys_user u
INNER JOIN sys_role r ON r.role_code = 'user'
WHERE NOT EXISTS (
    SELECT 1 FROM sys_user_role ur WHERE ur.user_id = u.id
);
