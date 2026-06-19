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
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

@RestController
public class McpController {

    private static final Logger log = LoggerFactory.getLogger(McpController.class);

    private final ObjectMapper objectMapper;
    private final GetSchemaTool getSchemaTool;
    private final QueryDatabaseTool queryDatabaseTool;

    private final Map<String, ToolDefinition> tools = new ConcurrentHashMap<>();
    private final Map<String, java.util.function.Function<Map<String, Object>, ToolCallResult>> toolHandlers =
            new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, SseEmitter> sseSessions = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> sseSessionTimestamps = new ConcurrentHashMap<>();

    private boolean initialized = false;

    public McpController(ObjectMapper objectMapper, GetSchemaTool getSchemaTool, QueryDatabaseTool queryDatabaseTool) {
        this.objectMapper = objectMapper;
        this.getSchemaTool = getSchemaTool;
        this.queryDatabaseTool = queryDatabaseTool;

        registerTool(getSchemaTool.getDefinition(), getSchemaTool::execute);
        registerTool(queryDatabaseTool.getDefinition(), queryDatabaseTool::execute);

        Executors.newSingleThreadScheduledExecutor().scheduleAtFixedRate(
                this::cleanExpiredSseSessions, 60, 60, TimeUnit.SECONDS);
    }

    private void registerTool(ToolDefinition def, java.util.function.Function<Map<String, Object>, ToolCallResult> handler) {
        tools.put(def.getName(), def);
        toolHandlers.put(def.getName(), handler);
    }

    @PostMapping("/mcp")
    public Object handleMessage(@RequestBody Object body,
                                @RequestParam(value = "sessionId", required = false) String sessionId) {
        if (body instanceof List) {
            return handleBatch((List<?>) body, sessionId);
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> requestMap = (Map<String, Object>) body;
        JsonRpcRequest request = objectMapper.convertValue(requestMap, JsonRpcRequest.class);

        if (sessionId != null && sseSessions.containsKey(sessionId)) {
            return handleViaSse(request, sessionId);
        }

        return handleSingle(request);
    }

    private Object handleViaSse(JsonRpcRequest request, String sessionId) {
        if (request.isNotification()) {
            handleNotification(request);
            return Map.of();
        }
        try {
            Object response = handleMethod(request);
            sendSseEvent(sessionId, "message", objectMapper.writeValueAsString(response));
            return Map.of("status", "sent");
        } catch (Exception e) {
            log.error("SSE MCP error", e);
            Object error = JsonRpcResponse.error(request.getId(), -32603, e.getMessage());
            try {
                sendSseEvent(sessionId, "message", objectMapper.writeValueAsString(error));
            } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
                sendSseEvent(sessionId, "message", "{\"error\":\"internal error\"}");
            }
            return Map.of("status", "error");
        }
    }

    private void sendSseEvent(String sessionId, String event, String data) {
        SseEmitter emitter = sseSessions.get(sessionId);
        if (emitter != null) {
            try {
                emitter.send(SseEmitter.event().name(event).data(data));
            } catch (IOException e) {
                log.warn("Failed to send SSE event to session {}", sessionId);
                sseSessions.remove(sessionId);
            }
        }
    }

    @GetMapping(value = "/sse", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter establishSse() {
        String sessionId = UUID.randomUUID().toString();
        SseEmitter emitter = new SseEmitter(600_000L);
        sseSessions.put(sessionId, emitter);
        sseSessionTimestamps.put(sessionId, System.currentTimeMillis());

        try {
            emitter.send(SseEmitter.event()
                    .name("endpoint")
                    .data("/mcp?sessionId=" + sessionId));
        } catch (IOException e) {
            log.error("Failed to send endpoint event", e);
        }

        emitter.onCompletion(() -> {
            sseSessions.remove(sessionId);
            sseSessionTimestamps.remove(sessionId);
            log.info("SSE session {} completed", sessionId);
        });
        emitter.onTimeout(() -> {
            sseSessions.remove(sessionId);
            sseSessionTimestamps.remove(sessionId);
            log.info("SSE session {} timed out", sessionId);
        });

        log.info("SSE session {} established", sessionId);
        return emitter;
    }

    private void cleanExpiredSseSessions() {
        long now = System.currentTimeMillis();
        long ttl = 600_000;
        sseSessionTimestamps.entrySet().removeIf(entry -> {
            if (now - entry.getValue() > ttl) {
                sseSessions.remove(entry.getKey());
                log.info("SSE session {} expired", entry.getKey());
                return true;
            }
            return false;
        });
    }

    private List<Object> handleBatch(List<?> body, String sessionId) {
        List<Object> responses = new ArrayList<>();
        for (Object item : body) {
            @SuppressWarnings("unchecked")
            Map<String, Object> requestMap = (Map<String, Object>) item;
            JsonRpcRequest request = objectMapper.convertValue(requestMap, JsonRpcRequest.class);
            if (request.isNotification()) {
                handleNotification(request);
            } else {
                try {
                    responses.add(handleMethod(request));
                } catch (Exception e) {
                    responses.add(JsonRpcResponse.error(request.getId(), -32603, e.getMessage()));
                }
            }
        }
        return responses;
    }

    private Object handleSingle(JsonRpcRequest request) {
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
            case "ping":
                return handlePing(request);
            case "tools/list":
                return handleToolsList(request);
            case "tools/call":
                return handleToolsCall(request);
            case "resources/list":
                return handleResourcesList(request);
            case "prompts/list":
                return handlePromptsList(request);
            default:
                return JsonRpcResponse.error(request.getId(), -32601, "Method not found: " + method);
        }
    }

    private Object handleInitialize(JsonRpcRequest request) {
        log.info("Client initialize: {}", request.getParams());

        Map<String, Object> result = Map.of(
                "protocolVersion", "2024-11-05",
                "capabilities", Map.of(
                        "tools", Map.of(),
                        "resources", Map.of(),
                        "prompts", Map.of()
                ),
                "serverInfo", Map.of(
                        "name", "mysql-mcp-server",
                        "version", "1.0.0"
                )
        );

        return JsonRpcResponse.success(request.getId(), result);
    }

    private Object handlePing(JsonRpcRequest request) {
        return JsonRpcResponse.success(request.getId(), Map.of());
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

    private Object handleResourcesList(JsonRpcRequest request) {
        List<Map<String, Object>> resources = List.of(
                Map.of(
                        "uri", "mysql://database/schema",
                        "name", "Database Schema",
                        "description", "Complete MySQL database schema (tables and columns)",
                        "mimeType", "application/json"
                ),
                Map.of(
                        "uri", "mysql://database/query",
                        "name", "Query Interface",
                        "description", "Execute read-only SELECT queries against the database",
                        "mimeType", "application/json"
                )
        );
        return JsonRpcResponse.success(request.getId(), Map.of("resources", resources));
    }

    private Object handlePromptsList(JsonRpcRequest request) {
        List<Map<String, Object>> prompts = List.of(
                Map.of(
                        "name", "show_tables",
                        "description", "Show all database tables",
                        "arguments", List.of()
                ),
                Map.of(
                        "name", "describe_table",
                        "description", "Describe the structure of a table",
                        "arguments", List.of(
                                Map.of("name", "table_name", "description", "Name of the table", "required", true)
                        )
                ),
                Map.of(
                        "name", "query_data",
                        "description", "Query data from the database",
                        "arguments", List.of(
                                Map.of("name", "query", "description", "SQL query in natural language", "required", true)
                        )
                )
        );
        return JsonRpcResponse.success(request.getId(), Map.of("prompts", prompts));
    }
}
