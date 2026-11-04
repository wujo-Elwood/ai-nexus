package com.rag.mapper;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 文件映射 SQL 测试
 * 验证全局向量重建会让当前版本和历史版本的向量状态一起失效
 */
class FileMapperSqlTest {

    /**
     * 验证全局向量状态更新不限制当前版本
     */
    @Test
    void markAllVectorsPendingShouldIncludeHistoricalVersions() throws Exception {
        //读取 MyBatis 映射文件，直接验证实际执行的 SQL 条件
        try (InputStream input = getClass().getClassLoader().getResourceAsStream("mapper/FileMapper.xml")) {
            String mapperXml = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            int statementStart = mapperXml.indexOf("<update id=\"markAllVectorsPending\">");
            int statementEnd = mapperXml.indexOf("</update>", statementStart);
            String statement = mapperXml.substring(statementStart, statementEnd);

            assertTrue(statement.contains("SET vector_status = 'PENDING'"));
            assertFalse(statement.contains("is_current"));
        }
    }
}
