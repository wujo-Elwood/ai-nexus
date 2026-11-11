-- 知识库共享授权升级脚本
-- kb_grant 表：把私有知识库授权给指定用户或角色（组）。
--   授权对象为 USER 时 grantee_id 是 sys_user.id，为 ROLE 时是 sys_role.id（部门/组用角色表达）。
--   permission：READ=可阅读（问答检索、文件预览下载），MANAGE=可管理（在 READ 基础上可上传、删除、改配置）。
--   创建者和管理语义不变：checkAccess 放行 READ/MANAGE 授权，checkManageAccess 只放行 MANAGE 授权。
-- 知识库删除时需同步清理本表授权（KnowledgeBaseService.delete）。

CREATE TABLE IF NOT EXISTS kb_grant (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  kb_id BIGINT NOT NULL COMMENT '知识库编号',
  grantee_type VARCHAR(10) NOT NULL COMMENT '授权对象类型：USER=指定用户，ROLE=角色/组',
  grantee_id BIGINT NOT NULL COMMENT '授权对象编号：用户编号或角色编号',
  permission VARCHAR(10) NOT NULL DEFAULT 'READ' COMMENT '权限级别：READ=可阅读，MANAGE=可管理',
  create_user BIGINT NOT NULL COMMENT '授权操作人（知识库创建者）',
  create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_kb_grant (kb_id, grantee_type, grantee_id),
  INDEX idx_grantee (grantee_type, grantee_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='知识库共享授权表';
