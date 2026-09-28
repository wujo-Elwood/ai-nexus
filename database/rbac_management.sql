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

