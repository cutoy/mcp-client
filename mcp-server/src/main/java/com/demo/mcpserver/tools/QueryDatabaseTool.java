package com.demo.mcpserver.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.util.*;
import java.util.regex.Pattern;

@Component
public class QueryDatabaseTool {

    private static final Logger log = LoggerFactory.getLogger(QueryDatabaseTool.class);

    private final DataSource dataSource;
    private final ObjectMapper objectMapper;
    private final ToolDefinition definition;

    @Value("${mcp.db.query.timeout:30}")
    private int queryTimeout;

    @Value("${mcp.db.query.max.rows:1000}")
    private int maxRows;

    public QueryDatabaseTool(DataSource dataSource, ObjectMapper objectMapper) {
        this.dataSource = dataSource;
        this.objectMapper = objectMapper;

        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("sql", ToolDefinition.buildSchemaParam(
                "sql", "string",
                "SELECT 查询语句",
                true));

        this.definition = new ToolDefinition(
                "query_database",
                "执行只读 SQL 查询（仅允许 SELECT 语句），返回 JSON 数组结果集",
                ToolDefinition.buildInputSchema(properties, List.of("sql"))
        );
    }

    public ToolDefinition getDefinition() {
        return definition;
    }

    public ToolCallResult execute(Map<String, Object> arguments) {
        log.info("query_database called with arguments: {}", arguments);
        try {
            if (arguments == null || !arguments.containsKey("sql")) {
                return new ToolCallResult("Error: missing required parameter 'sql'", true);
            }

            String sql = ((String) arguments.get("sql")).trim();

            String upperSql = sql.toUpperCase();
            if (!upperSql.startsWith("SELECT")) {
                return new ToolCallResult("Error: only SELECT statements are allowed", true);
            }

            String blocked = checkDangerousKeywords(upperSql);
            if (blocked != null) {
                return new ToolCallResult("Error: dangerous SQL keyword detected: " + blocked, true);
            }

            @SuppressWarnings("unchecked")
            List<Object> params = (List<Object>) arguments.get("params");

            List<Map<String, Object>> results = new ArrayList<>();
            try (Connection conn = dataSource.getConnection()) {

                if (params != null && !params.isEmpty()) {
                    try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
                        pstmt.setQueryTimeout(queryTimeout);
                        pstmt.setMaxRows(maxRows);
                        for (int i = 0; i < params.size(); i++) {
                            pstmt.setObject(i + 1, params.get(i));
                        }
                        try (ResultSet rs = pstmt.executeQuery()) {
                            results = readResultSet(rs);
                        }
                    }
                } else {
                    try (Statement stmt = conn.createStatement()) {
                        stmt.setQueryTimeout(queryTimeout);
                        stmt.setMaxRows(maxRows);
                        try (ResultSet rs = stmt.executeQuery(sql)) {
                            results = readResultSet(rs);
                        }
                    }
                }
            }

            String json = objectMapper.writeValueAsString(results);
            return new ToolCallResult(json, false);

        } catch (Exception e) {
            log.error("query_database execution failed", e);
            return new ToolCallResult("Error: " + e.getMessage(), true);
        }
    }

    private static final Pattern DANGEROUS_KEYWORDS = Pattern.compile(
            "\\b(INSERT|DELETE|DROP|ALTER|TRUNCATE|CREATE|GRANT|REVOKE|EXEC(UTE)?|CALL|REPLACE|RENAME|LOCK|UNLOCK|FLUSH|KILL|SHUTDOWN)\\b",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern DANGEROUS_FUNCTIONS = Pattern.compile(
            "\\b(LOAD_FILE|INTO\\s+(OUTFILE|DUMPFILE)|BENCHMARK|SLEEP)\\b",
            Pattern.CASE_INSENSITIVE);

    private String checkDangerousKeywords(String upperSql) {
        if (upperSql.contains(";")) {
            return "multi-statement (;)";
        }
        java.util.regex.Matcher m = DANGEROUS_KEYWORDS.matcher(upperSql);
        if (m.find()) {
            return m.group(1);
        }
        m = DANGEROUS_FUNCTIONS.matcher(upperSql);
        if (m.find()) {
            return m.group(1);
        }
        return null;
    }

    private List<Map<String, Object>> readResultSet(ResultSet rs) throws Exception {
        List<Map<String, Object>> results = new ArrayList<>();
        ResultSetMetaData rsmd = rs.getMetaData();
        int columnCount = rsmd.getColumnCount();
        while (rs.next()) {
            Map<String, Object> row = new LinkedHashMap<>();
            for (int i = 1; i <= columnCount; i++) {
                row.put(rsmd.getColumnLabel(i), rs.getObject(i));
            }
            results.add(row);
        }
        return results;
    }
}
