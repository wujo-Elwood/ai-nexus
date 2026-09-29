package com.rag.agent.tools;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 数据库表清单工具：列出 rag_db 中的所有数据表和行数估计
 */
@Component
public class ListTablesTool extends DataAgentSupport implements AgentTool {

    public ListTablesTool(
            @org.springframework.beans.factory.annotation.Value("${data-agent.jdbc-url}") String jdbcUrl,
            @org.springframework.beans.factory.annotation.Value("${data-agent.username}") String username,
            @org.springframework.beans.factory.annotation.Value("${data-agent.password}") String password) {
        super(jdbcUrl, username, password);
    }

    @Override
    public String name() {
        return "list_tables";
    }

    @Override
    public String description() {
        return "列出系统数据库 rag_db 中的所有数据表、行数估计和用途说明。不确定该查哪张表时先调用它。";
    }

    @Override
    public List<AgentTool.ToolParam> params() {
        return List.of();
    }

    @Override
    public String execute(JsonNode arguments) throws Exception {
        List<Map<String, Object>> rows = runQuery(
                "SELECT TABLE_NAME AS table_name, TABLE_ROWS AS row_estimate, TABLE_COMMENT AS table_comment "
                        + "FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_SCHEMA = 'rag_db' ORDER BY TABLE_NAME");
        if (rows.isEmpty()) {
            return "数据库中没有数据表。";
        }
        StringBuilder text = new StringBuilder("数据库共有 ").append(rows.size()).append(" 张表：\n");
        for (Map<String, Object> row : rows) {
            text.append("- ").append(row.get("table_name"))
                    .append("（约 ").append(row.get("row_estimate")).append(" 行）");
            String comment = String.valueOf(row.get("table_comment"));
            if (!comment.isEmpty() && !"null".equals(comment)) {
                text.append("：").append(comment);
            }
            text.append("\n");
        }
        return text.toString();
    }
}
