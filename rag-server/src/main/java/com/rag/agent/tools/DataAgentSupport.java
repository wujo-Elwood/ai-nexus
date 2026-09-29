package com.rag.agent.tools;

import com.rag.common.BusinessException;
import org.springframework.beans.factory.annotation.Value;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 数据问答智能体公共支撑：只读数据源配置与查询执行
 * 使用独立的只读数据库账号（仅 SELECT 权限），即使校验被绕过也无法写入
 */
public abstract class DataAgentSupport implements AgentTool {

    /** 单次最多返回行数 */
    static final int MAX_ROWS = 50;
    /** 单元格文本最大长度 */
    private static final int MAX_CELL_LENGTH = 120;
    /** 查询超时秒数 */
    private static final int QUERY_TIMEOUT_SECONDS = 10;

    private final String jdbcUrl;
    private final String username;
    private final String password;

    public DataAgentSupport(
            @Value("${data-agent.jdbc-url}") String jdbcUrl,
            @Value("${data-agent.username}") String username,
            @Value("${data-agent.password}") String password) {
        this.jdbcUrl = jdbcUrl;
        this.username = username;
        this.password = password;
    }

    /** 执行只读查询，返回行数据列表（每行为 列名->文本值） */
    List<Map<String, Object>> runQuery(String sql) throws SQLException {
        try (Connection connection = DriverManager.getConnection(jdbcUrl, username, password)) {
            connection.setReadOnly(true);
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
                statement.setMaxRows(MAX_ROWS + 1);
                try (ResultSet resultSet = statement.executeQuery()) {
                    ResultSetMetaData meta = resultSet.getMetaData();
                    int columnCount = meta.getColumnCount();
                    String[] columns = new String[columnCount];
                    for (int i = 1; i <= columnCount; i++) {
                        columns[i - 1] = meta.getColumnLabel(i);
                    }
                    List<Map<String, Object>> rows = new ArrayList<>();
                    while (resultSet.next() && rows.size() < MAX_ROWS) {
                        Map<String, Object> row = new LinkedHashMap<>();
                        for (int i = 1; i <= columnCount; i++) {
                            row.put(columns[i - 1], truncate(resultSet.getString(i)));
                        }
                        rows.add(row);
                    }
                    return rows;
                }
            }
        }
    }

    /** 执行返回单数值的查询（如 COUNT） */
    long runCount(String sql) throws SQLException {
        try (Connection connection = DriverManager.getConnection(jdbcUrl, username, password)) {
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
                try (ResultSet resultSet = statement.executeQuery()) {
                    resultSet.next();
                    return resultSet.getLong(1);
                }
            }
        }
    }

    private String truncate(String value) {
        if (value == null) {
            return "";
        }
        return value.length() <= MAX_CELL_LENGTH ? value : value.substring(0, MAX_CELL_LENGTH) + "…";
    }

    /**
     * 只读校验：仅允许 SELECT/SHOW/DESC/EXPLAIN/WITH 开头的单条语句，
     * 拒绝多语句、注释和任何写入/DDL 关键字；并自动补 LIMIT 防止全表刷屏
     */
    String validateAndLimit(String sql) {
        String clean = sql == null ? "" : sql.strip();
        while (clean.endsWith(";")) {
            clean = clean.substring(0, clean.length() - 1).strip();
        }
        if (clean.isEmpty()) {
            throw new BusinessException(400, "SQL 不能为空");
        }
        String upper = clean.toUpperCase();
        if (!(upper.startsWith("SELECT") || upper.startsWith("SHOW")
                || upper.startsWith("DESC") || upper.startsWith("EXPLAIN") || upper.startsWith("WITH"))) {
            throw new BusinessException(400, "仅允许 SELECT 只读查询");
        }
        if (clean.contains(";")) {
            throw new BusinessException(400, "不支持一次执行多条语句");
        }
        if (clean.contains("--") || clean.contains("/*")) {
            throw new BusinessException(400, "SQL 中不允许包含注释");
        }
        for (String keyword : new String[]{
                "INSERT", "UPDATE", "DELETE", "DROP", "ALTER", "CREATE", "TRUNCATE",
                "GRANT", "REVOKE", "SET", "USE", "CALL", "KILL", "LOCK", "OUTFILE"}) {
            if (upper.matches(".*\\b" + keyword + "\\b.*")) {
                throw new BusinessException(400, "仅允许只读查询，检测到 " + keyword + " 关键字");
            }
        }
        if (!upper.contains(" LIMIT ")) {
            clean = "SELECT * FROM (" + clean + ") data_agent_limited LIMIT " + MAX_ROWS;
        }
        return clean;
    }

    /** 把查询结果渲染成 Markdown 表格文本 */
    String renderTable(List<Map<String, Object>> rows) {
        if (rows.isEmpty()) {
            return "查询成功：没有匹配的数据。";
        }
        StringBuilder text = new StringBuilder();
        List<String> columns = new ArrayList<>(rows.get(0).keySet());
        text.append("| ").append(String.join(" | ", columns)).append(" |\n");
        text.append("|").append(" --- |".repeat(columns.size())).append("\n");
        for (Map<String, Object> row : rows) {
            List<String> cells = new ArrayList<>();
            for (String column : columns) {
                cells.add(String.valueOf(row.get(column)));
            }
            text.append("| ").append(String.join(" | ", cells)).append(" |\n");
        }
        text.append("共 ").append(rows.size()).append(" 行");
        if (rows.size() >= MAX_ROWS) {
            text.append("（已达显示上限，如需更多请缩小查询范围或增加条件）");
        }
        return text.toString();
    }
}
