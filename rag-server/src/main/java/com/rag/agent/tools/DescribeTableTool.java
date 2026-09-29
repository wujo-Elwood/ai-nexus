package com.rag.agent.tools;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 表结构查看工具：返回指定表的字段、类型、说明和行数
 */
@Component
public class DescribeTableTool extends DataAgentSupport implements AgentTool {

    public DescribeTableTool(
            @org.springframework.beans.factory.annotation.Value("${data-agent.jdbc-url}") String jdbcUrl,
            @org.springframework.beans.factory.annotation.Value("${data-agent.username}") String username,
            @org.springframework.beans.factory.annotation.Value("${data-agent.password}") String password) {
        super(jdbcUrl, username, password);
    }

    @Override
    public String name() {
        return "describe_table";
    }

    @Override
    public String description() {
        return "查看某张数据表的字段结构（字段名、类型、说明）和总行数。表名可先通过 list_tables 获取。";
    }

    @Override
    public List<AgentTool.ToolParam> params() {
        return List.of(new ToolParam("table", "string", "表名，例如 kb_file、sys_user", true));
    }

    @Override
    public String execute(JsonNode arguments) throws Exception {
        String table = arguments.path("table").asText("").trim().toLowerCase();
        if (!table.matches("[a-z0-9_]+")) {
            return "参数错误：表名只能包含小写字母、数字和下划线";
        }
        List<Map<String, Object>> columns = runQuery(
                "SELECT COLUMN_NAME AS column_name, COLUMN_TYPE AS column_type, "
                        + "COLUMN_COMMENT AS column_comment FROM INFORMATION_SCHEMA.COLUMNS "
                        + "WHERE TABLE_SCHEMA = 'rag_db' AND TABLE_NAME = '" + table + "' ORDER BY ORDINAL_POSITION");
        if (columns.isEmpty()) {
            return "表 " + table + " 不存在，请通过 list_tables 确认表名";
        }
        long total = runCount("SELECT COUNT(*) FROM " + table);
        StringBuilder text = new StringBuilder("表 ").append(table).append(" 共 ").append(total).append(" 行，字段：\n");
        for (Map<String, Object> column : columns) {
            text.append("- ").append(column.get("column_name"))
                    .append("（").append(column.get("column_type")).append("）");
            String comment = String.valueOf(column.get("column_comment"));
            if (!comment.isEmpty() && !"null".equals(comment)) {
                text.append("：").append(comment);
            }
            text.append("\n");
        }
        return text.toString();
    }
}
