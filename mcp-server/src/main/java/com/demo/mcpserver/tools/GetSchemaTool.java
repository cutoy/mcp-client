package com.demo.mcpserver.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.util.*;

@Component
public class GetSchemaTool {

    private static final Logger log = LoggerFactory.getLogger(GetSchemaTool.class);

    private final DataSource dataSource;
    private final ObjectMapper objectMapper;
    private final ToolDefinition definition;

    public GetSchemaTool(DataSource dataSource, ObjectMapper objectMapper) {
        this.dataSource = dataSource;
        this.objectMapper = objectMapper;

        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("table_name", ToolDefinition.buildSchemaParam(
                "table_name", "string",
                "要查看结构的表名，多个表名用逗号分隔，如 'users,orders'；不传则返回所有表名列表",
                false));

        this.definition = new ToolDefinition(
                "get_schema",
                "获取数据库表结构。不传参数返回所有表名列表；传入 table_name 返回指定表的列详情（列名、类型、是否可空、默认值、注释）。支持逗号分隔查询多个表",
                ToolDefinition.buildInputSchema(properties, null)
        );
    }

    public ToolDefinition getDefinition() {
        return definition;
    }

    public ToolCallResult execute(Map<String, Object> arguments) {
        log.info("get_schema called with arguments: {}", arguments);
        try {
            String tableName = arguments != null ? (String) arguments.get("table_name") : null;

            if (tableName == null || tableName.isBlank()) {
                List<String> tables = getAllTables();
                String json = objectMapper.writeValueAsString(tables);
                return new ToolCallResult(json, false);
            } else {
                String[] tableNames = tableName.split(",");
                Map<String, List<Map<String, Object>>> result = new LinkedHashMap<>();
                for (String tn : tableNames) {
                    String trimmed = tn.trim();
                    if (!trimmed.isEmpty()) {
                        result.put(trimmed, getTableColumns(trimmed));
                    }
                }
                String json = objectMapper.writeValueAsString(result);
                return new ToolCallResult(json, false);
            }
        } catch (Exception e) {
            log.error("get_schema execution failed", e);
            return new ToolCallResult("Error: " + e.getMessage(), true);
        }
    }

    private List<String> getAllTables() throws Exception {
        List<String> tables = new ArrayList<>();
        try (Connection conn = dataSource.getConnection()) {
            DatabaseMetaData meta = conn.getMetaData();
            try (ResultSet rs = meta.getTables(conn.getCatalog(), null, "%", new String[]{"TABLE"})) {
                while (rs.next()) {
                    tables.add(rs.getString("TABLE_NAME"));
                }
            }
        }
        return tables;
    }

    private List<Map<String, Object>> getTableColumns(String tableName) throws Exception {
        List<Map<String, Object>> columns = new ArrayList<>();
        try (Connection conn = dataSource.getConnection()) {
            DatabaseMetaData meta = conn.getMetaData();
            try (ResultSet rs = meta.getColumns(conn.getCatalog(), null, tableName, "%")) {
                while (rs.next()) {
                    Map<String, Object> col = new LinkedHashMap<>();
                    col.put("column_name", rs.getString("COLUMN_NAME"));
                    col.put("type_name", rs.getString("TYPE_NAME"));
                    col.put("size", rs.getInt("COLUMN_SIZE"));
                    col.put("nullable", rs.getInt("NULLABLE") == DatabaseMetaData.columnNullable);
                    col.put("default_value", rs.getString("COLUMN_DEF"));
                    col.put("remarks", rs.getString("REMARKS"));
                    columns.add(col);
                }
            }
        }
        return columns;
    }
}
