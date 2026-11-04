package com.rag.mapper;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 文本切片查询 SQL 测试
 * 验证聊天按切片编号回查时不会读取历史版本或未完成文件
 */
class ChunkMapperSqlTest {

    /**
     * 验证知识库切片回查同时限制当前版本和处理完成状态
     */
    @Test
    void findByIdAndKbIdShouldOnlyReturnCompletedCurrentVersion() throws Exception {
        //读取 MyBatis 映射文件，直接验证实际执行的 SQL 条件
        try (InputStream input = getClass().getClassLoader().getResourceAsStream("mapper/ChunkMapper.xml")) {
            String mapperXml = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            int statementStart = mapperXml.indexOf("<select id=\"findByIdAndKbId\"");
            int statementEnd = mapperXml.indexOf("</select>", statementStart);
            String statement = mapperXml.substring(statementStart, statementEnd);

            assertTrue(statement.contains("f.is_current = 1"));
            assertTrue(statement.contains("f.status = 'COMPLETED'"));
        }
    }
}
