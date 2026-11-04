package com.rag.mapper;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 知识库共享授权 SQL 测试
 * 直接验证 MyBatis 映射文件中实际执行的 SQL 条件
 */
class KbGrantMapperSqlTest {

    /** 读取映射文件中指定语句的 SQL 片段 */
    private String statement(String xmlFile, String selectId) throws Exception {
        try (InputStream input = getClass().getClassLoader().getResourceAsStream(xmlFile)) {
            String mapperXml = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            int start = mapperXml.indexOf("<select id=\"" + selectId + "\"");
            assertTrue(start >= 0, xmlFile + " 缺少语句 " + selectId);
            int end = mapperXml.indexOf("</select>", start);
            return mapperXml.substring(start, end);
        }
    }

    /**
     * 知识库列表查询必须包含授权子查询：USER 直接命中、ROLE 按用户角色命中
     */
    @Test
    void findVisibleShouldIncludeGrantSubquery() throws Exception {
        String statement = statement("mapper/KnowledgeBaseMapper.xml", "findVisible");
        assertTrue(statement.contains("kb_grant"), "findVisible 应关联 kb_grant 授权表");
        assertTrue(statement.contains("grantee_type = 'USER'"), "findVisible 应包含用户授权分支");
        assertTrue(statement.contains("grantee_type = 'ROLE'"), "findVisible 应包含角色授权分支");
        assertTrue(statement.contains("sys_user_role"), "角色授权应通过用户-角色关联判定");
    }

    /**
     * 兜底查询不依赖 kb_grant 表，保证升级脚本未执行时列表可用
     */
    @Test
    void findVisibleWithoutGrantShouldNotReferenceGrantTable() throws Exception {
        String statement = statement("mapper/KnowledgeBaseMapper.xml", "findVisibleWithoutGrant");
        assertFalse(statement.contains("kb_grant"), "兜底查询不应依赖 kb_grant 表");
    }

    /**
     * 可管理判定必须收紧到 MANAGE 权限级别
     */
    @Test
    void countManageableShouldRequireManagePermission() throws Exception {
        String statement = statement("mapper/KbGrantMapper.xml", "countManageable");
        assertTrue(statement.contains("permission = 'MANAGE'"), "countManageable 必须限定 MANAGE 授权");
    }

    /**
     * 可读判定不限定权限级别：READ 与 MANAGE 授权都视为可读
     */
    @Test
    void countReadableShouldAcceptReadAndManageGrants() throws Exception {
        String statement = statement("mapper/KbGrantMapper.xml", "countReadable");
        assertFalse(statement.contains("g.permission"), "countReadable 不应按权限级别过滤，READ/MANAGE 均可读");
        assertTrue(statement.contains("grantee_type = 'USER'"), "countReadable 应包含用户授权分支");
        assertTrue(statement.contains("grantee_type = 'ROLE'"), "countReadable 应包含角色授权分支");
    }

    /**
     * 授权列表查询应关联用户和角色表取展示名称
     */
    @Test
    void findByKbIdShouldJoinUserAndRoleForNames() throws Exception {
        String statement = statement("mapper/KbGrantMapper.xml", "findByKbId");
        assertTrue(statement.contains("LEFT JOIN sys_user"), "授权列表应关联用户表");
        assertTrue(statement.contains("LEFT JOIN sys_role"), "授权列表应关联角色表");
        assertTrue(statement.contains("granteeName"), "授权列表应返回展示名称");
    }
}
