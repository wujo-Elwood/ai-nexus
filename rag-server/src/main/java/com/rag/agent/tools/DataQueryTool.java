package com.rag.agent.tools;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 数据查询工具：执行模型生成的只读 SELECT 语句
 * 双重防护：SQL 校验（仅允许只读查询）+ 独立只读数据库账号
 */
@Component
public class DataQueryTool extends DataAgentSupport implements AgentTool {

    public DataQueryTool(
            @org.springframework.beans.factory.annotation.Value("${data-agent.jdbc-url}") String jdbcUrl,
            @org.springframework.beans.factory.annotation.Value("${data-agent.username}") String username,
            @org.springframework.beans.factory.annotation.Value("${data-agent.password}") String password) {
        super(jdbcUrl, username, password);
    }

    @Override
    public String name() {
        return "query_database";
    }

    @Override
    public String description() {
        return "对系统数据库 rag_db 执行只读 SELECT 查询并返回结果（Markdown 表格，最多 50 行）。"
                + "表结构可先用 list_tables / describe_table 查看。仅允许 SELECT，禁止任何写入。";
    }

    @Override
    public List<AgentTool.ToolParam> params() {
        return List.of(new ToolParam("sql", "string",
                "只读 SELECT 查询语句，例如 SELECT COUNT(*) FROM kb_file", true));
    }

    @Override
    public String execute(JsonNode arguments) {
        String sql = arguments.path("sql").asText("").trim();
        if (sql.isEmpty()) {
            return "参数错误：sql 不能为空";
        }
        try {
            String limited = validateAndLimit(sql);
            List<Map<String, Object>> rows = runQuery(limited);
            return renderTable(rows);
        } catch (java.sql.SQLException e) {
            return "SQL 执行失败：" + e.getMessage() + "。请检查表名和字段名（可用 describe_table 查看结构）后重试。";
        } catch (com.rag.common.BusinessException e) {
            return "SQL 校验未通过：" + e.getMessage();
        }
    }
}
