package com.demo.mcpserver.tools;

import java.util.List;
import java.util.Map;

public class ToolDefinition {

    private String name;
    private String description;
    private Map<String, Object> inputSchema;

    public ToolDefinition(String name, String description, Map<String, Object> inputSchema) {
        this.name = name;
        this.description = description;
        this.inputSchema = inputSchema;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public Map<String, Object> getInputSchema() {
        return inputSchema;
    }

    public Map<String, Object> toMap() {
        return Map.of(
                "name", name,
                "description", description,
                "inputSchema", inputSchema
        );
    }

    public static Map<String, Object> buildSchemaParam(String name, String type, String description, boolean required) {
        Map<String, Object> schema = new java.util.LinkedHashMap<>();
        schema.put("type", type);
        schema.put("description", description);
        return schema;
    }

    public static Map<String, Object> buildInputSchema(Map<String, Object> properties, List<String> required) {
        Map<String, Object> schema = new java.util.LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        if (required != null && !required.isEmpty()) {
            schema.put("required", required);
        }
        return schema;
    }
}
