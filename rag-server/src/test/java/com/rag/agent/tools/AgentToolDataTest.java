package com.rag.agent.tools;

import com.rag.common.BusinessException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 数据问答工具测试：验证只读 SQL 校验、LIMIT 补全和结果渲染（不依赖真实数据库）
 */
class AgentToolDataTest {

    private DataAgentSupport newSupport() {
        return new DataAgentSupport("jdbc:mysql://localhost:3306/none", "u", "p") {
            @Override
            public String name() {
                return "test";
            }

            @Override
            public String description() {
                return "test";
            }

            @Override
            public List<ToolParam> params() {
                return List.of();
            }

            @Override
            public String execute(com.fasterxml.jackson.databind.JsonNode arguments) {
                return "";
            }
        };
    }

    @Test
    void shouldWrapQueryWithLimitWhenMissing() {
        String result = newSupport().validateAndLimit("SELECT * FROM sys_user");
        assertTrue(result.startsWith("SELECT * FROM ("), result);
        assertTrue(result.endsWith("LIMIT 50"), result);
        assertTrue(result.contains("sys_user"), result);
    }

    @Test
    void shouldKeepExplicitLimit() {
        String result = newSupport().validateAndLimit("SELECT id FROM sys_user LIMIT 5");
        assertFalse(result.contains("data_agent_limited"), result);
        assertTrue(result.endsWith("LIMIT 5"), result);
    }

    @Test
    void shouldRejectWriteStatement() {
        DataAgentSupport support = newSupport();
        for (String sql : new String[]{
                "DELETE FROM sys_user", "UPDATE sys_user SET id = 1", "DROP TABLE kb_file",
                "INSERT INTO sys_user VALUES (1)", "TRUNCATE TABLE kb_file", "GRANT SELECT ON *.* TO 'u'"}) {
            try {
                support.validateAndLimit(sql);
                throw new AssertionError("应被拒绝：" + sql);
            } catch (BusinessException e) {
                assertTrue(e.getMessage().contains("只读"), sql + " -> " + e.getMessage());
            }
        }
    }

    @Test
    void shouldRejectMultipleStatementsAndComments() {
        DataAgentSupport support = newSupport();
        try {
            support.validateAndLimit("SELECT 1; DROP TABLE sys_user");
            throw new AssertionError("多语句应被拒绝");
        } catch (BusinessException e) {
            assertTrue(e.getMessage().contains("多条语句"));
        }
        try {
            support.validateAndLimit("SELECT * FROM sys_user -- comment");
            throw new AssertionError("注释应被拒绝");
        } catch (BusinessException e) {
            assertTrue(e.getMessage().contains("注释"));
        }
    }

    @Test
    void shouldRenderMarkdownTable() {
        DataAgentSupport support = newSupport();
        List<Map<String, Object>> rows = List.of(
                Map.of("id", "1", "name", "测试"),
                Map.of("id", "2", "name", "演示"));
        String rendered = support.renderTable(rows);
        //Map.of 是无序 Map，列顺序由实现决定，这里只断言表格结构、单元格内容和行数统计
        String header = rendered.split("\n", 2)[0];
        assertTrue(header.startsWith("| "), rendered);
        assertTrue(header.contains("id"), rendered);
        assertTrue(header.contains("name"), rendered);
        assertTrue(rendered.contains("| 1 |"), rendered);
        assertTrue(rendered.contains("测试"), rendered);
        assertTrue(rendered.contains("| 2 |"), rendered);
        assertTrue(rendered.contains("演示"), rendered);
        assertTrue(rendered.contains("共 2 行"), rendered);
    }

    @Test
    void shouldRenderEmptyResult() {
        DataAgentSupport support = newSupport();
        String rendered = support.renderTable(List.of());
        assertTrue(rendered.contains("没有匹配的数据"), rendered);
    }
}
