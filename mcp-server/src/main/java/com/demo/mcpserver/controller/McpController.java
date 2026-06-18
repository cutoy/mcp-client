package com.demo.mcpserver.controller;

import com.demo.mcpserver.protocol.JsonRpcRequest;
import com.demo.mcpserver.protocol.JsonRpcResponse;
import com.demo.mcpserver.tools.GetSchemaTool;
import com.demo.mcpserver.tools.QueryDatabaseTool;
import com.demo.mcpserver.tools.ToolCallResult;
import com.demo.mcpserver.tools.ToolDefinition;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@RestController
public class McpController {

    private static final Logger log = LoggerFactory.getLogger(McpController.class);

    private final ObjectMapper objectMapper;
    private final GetSchemaTool getSchemaTool;
    private final QueryDatabaseTool queryDatabaseTool;

    private final Map<String, ToolDefinition> tools = new ConcurrentHashMap<>();
    private final Map<String, java.util.function.Function<Map<String, Object>, ToolCallResult>> toolHandlers =
            new ConcurrentHashMap<>();

    private boolean initialized = false;

    public McpController(ObjectMapper objectMapper, GetSchemaTool getSchemaTool, QueryDatabaseTool queryDatabaseTool) {
        this.objectMapper = objectMapper;
        this.getSchemaTool = getSchemaTool;
        this.queryDatabaseTool = queryDatabaseTool;

        registerTool(getSchemaTool.getDefinition(), getSchemaTool::execute);
        registerTool(queryDatabaseTool.getDefinition(), queryDatabaseTool::execute);
    }

    private void registerTool(ToolDefinition def, java.util.function.Function<Map<String, Object>, ToolCallResult> handler) {
        tools.put(def.getName(), def);
        toolHandlers.put(def.getName(), handler);
    }

    @PostMapping("/mcp")
    public Object handleMessage(@RequestBody JsonRpcRequest request) {
        log.info("MCP request: method={}, id={}", request.getMethod(), request.getId());

        try {
            if (request.isNotification()) {
                handleNotification(request);
                return Map.of();
            }
            return handleMethod(request);
        } catch (Exception e) {
            log.error("MCP error", e);
            return JsonRpcResponse.error(request.getId(), -32603, e.getMessage());
        }
    }

    private void handleNotification(JsonRpcRequest request) {
        String method = request.getMethod();
        if ("notifications/initialized".equals(method)) {
            initialized = true;
            log.info("Client initialized notification received");
        }
    }

    private Object handleMethod(JsonRpcRequest request) {
        String method = request.getMethod();

        switch (method) {
            case "initialize":
                return handleInitialize(request);
            case "tools/list":
                return handleToolsList(request);
            case "tools/call":
                return handleToolsCall(request);
            default:
                return JsonRpcResponse.error(request.getId(), -32601, "Method not found: " + method);
        }
    }

    private Object handleInitialize(JsonRpcRequest request) {
        log.info("Client initialize: {}", request.getParams());

        Map<String, Object> result = Map.of(
                "protocolVersion", "2024-11-05",
                "capabilities", Map.of("tools", Map.of()),
                "serverInfo", Map.of(
                        "name", "mysql-mcp-server",
                        "version", "1.0.0"
                )
        );

        return JsonRpcResponse.success(request.getId(), result);
    }

    private Object handleToolsList(JsonRpcRequest request) {
        List<Map<String, Object>> toolList = tools.values().stream()
                .map(ToolDefinition::toMap)
                .toList();

        Map<String, Object> result = Map.of("tools", toolList);
        return JsonRpcResponse.success(request.getId(), result);
    }

    @SuppressWarnings("unchecked")
    private Object handleToolsCall(JsonRpcRequest request) {
        Map<String, Object> params = request.getParams();
        if (params == null || !params.containsKey("name")) {
            return JsonRpcResponse.error(request.getId(), -32602, "Missing tool name");
        }

        String toolName = (String) params.get("name");
        Map<String, Object> arguments = (Map<String, Object>) params.get("arguments");

        ToolDefinition def = tools.get(toolName);
        if (def == null) {
            return JsonRpcResponse.error(request.getId(), -32602, "Unknown tool: " + toolName);
        }

        java.util.function.Function<Map<String, Object>, ToolCallResult> handler = toolHandlers.get(toolName);
        ToolCallResult callResult = handler.apply(arguments != null ? arguments : Map.of());

        Map<String, Object> content = Map.of(
                "type", "text",
                "text", callResult.getContent()
        );

        Map<String, Object> result = Map.of(
                "content", List.of(content),
                "isError", callResult.isError()
        );

        return JsonRpcResponse.success(request.getId(), result);
    }
}
