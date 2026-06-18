package com.demo.mcpclient.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class ServerRegistry {

    private static final Logger log = LoggerFactory.getLogger(ServerRegistry.class);

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final ServerConfig config;
    private final Map<String, ServerConnection> connections = new ConcurrentHashMap<>();
    private boolean initialized = false;

    public ServerRegistry(RestTemplate restTemplate, ObjectMapper objectMapper, ServerConfig config) {
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
        this.config = config;
    }

    public synchronized void initialize() {
        if (initialized) return;

        for (ServerConfig.ServerInfo server : config.getServers()) {
            connectWithRetry(server, 3);
        }
        initialized = true;
        log.info("Server registry initialized. Connected to {} servers", connections.size());
    }

    private void connectWithRetry(ServerConfig.ServerInfo server, int maxRetries) {
        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                log.info("Connecting to MCP server: {} at {} (attempt {}/{})", server.getName(), server.getUrl(), attempt, maxRetries);

                Map<String, Object> initRequest = Map.of(
                        "jsonrpc", "2.0",
                        "method", "initialize",
                        "params", Map.of(
                                "protocolVersion", "2024-11-05",
                                "capabilities", Map.of(),
                                "clientInfo", Map.of(
                                        "name", "mcp-client",
                                        "version", "1.0.0"
                                )
                        ),
                        "id", 1
                );

                Map<String, Object> response = restTemplate.postForObject(
                        server.getUrl() + "/mcp", initRequest, Map.class);

                if (response != null && response.containsKey("result")) {
                    connections.put(server.getName(), new ServerConnection(server.getName(), server.getUrl()));
                    log.info("Connected to MCP server: {} v{}",
                            server.getName(),
                            ((Map<?, ?>) response.get("result")).get("protocolVersion"));

                    Map<String, Object> initializedNotify = Map.of(
                            "jsonrpc", "2.0",
                            "method", "notifications/initialized"
                    );
                    restTemplate.postForObject(server.getUrl() + "/mcp", initializedNotify, Map.class);
                    return;
                } else {
                    log.warn("Failed to connect to MCP server: {} - response: {}", server.getName(), response);
                }
            } catch (Exception e) {
                log.warn("Failed to connect to MCP server: {} (attempt {}/{})", server.getName(), attempt, maxRetries, e);
            }

            if (attempt < maxRetries) {
                long delay = (long) Math.pow(2, attempt) * 1000;
                log.info("Retrying in {}ms...", delay);
                try {
                    Thread.sleep(delay);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        log.error("Failed to connect to MCP server: {} after {} attempts", server.getName(), maxRetries);
    }

    public List<Map<String, Object>> listTools() {
        List<Map<String, Object>> allTools = new ArrayList<>();

        for (Map.Entry<String, ServerConnection> entry : connections.entrySet()) {
            String serverName = entry.getKey();
            ServerConnection conn = entry.getValue();

            try {
                Map<String, Object> request = Map.of(
                        "jsonrpc", "2.0",
                        "method", "tools/list",
                        "id", System.currentTimeMillis()
                );

                Map<String, Object> response = restTemplate.postForObject(
                        conn.getUrl() + "/mcp", request, Map.class);

                if (response != null && response.containsKey("result")) {
                    Map<String, Object> result = (Map<String, Object>) response.get("result");
                    List<Map<String, Object>> tools = (List<Map<String, Object>>) result.get("tools");
                    if (tools != null) {
                        for (Map<String, Object> tool : tools) {
                            Map<String, Object> enriched = new LinkedHashMap<>(tool);
                            enriched.put("serverName", serverName);
                            String originalName = (String) tool.get("name");
                            if (config.getServerInfo(serverName) != null 
                                    && config.getServerInfo(serverName).getToolNamePrefix() != null) {
                                enriched.put("name", config.getServerInfo(serverName).getToolNamePrefix() + originalName);
                                enriched.put("originalName", originalName);
                            }
                            allTools.add(enriched);
                        }
                    }
                }
            } catch (Exception e) {
                log.error("Failed to list tools from server: {}", serverName, e);
            }
        }

        return allTools;
    }

    public Map<String, Object> callTool(String serverName, String toolName, Map<String, Object> arguments) {
        ServerConnection conn = connections.get(serverName);
        if (conn == null) {
            return Map.of("error", "Unknown server: " + serverName);
        }

        String actualToolName = toolName;
        ServerConfig.ServerInfo serverInfo = config.getServerInfo(serverName);
        if (serverInfo != null && serverInfo.getToolNamePrefix() != null
                && toolName.startsWith(serverInfo.getToolNamePrefix())) {
            actualToolName = toolName.substring(serverInfo.getToolNamePrefix().length());
        }

        try {
            Map<String, Object> request = Map.of(
                    "jsonrpc", "2.0",
                    "method", "tools/call",
                    "params", Map.of(
                            "name", actualToolName,
                            "arguments", arguments != null ? arguments : Map.of()
                    ),
                    "id", System.currentTimeMillis()
            );

            Map<String, Object> response = restTemplate.postForObject(
                    conn.getUrl() + "/mcp", request, Map.class);

            if (response != null && response.containsKey("result")) {
                return (Map<String, Object>) response.get("result");
            } else if (response != null && response.containsKey("error")) {
                return Map.of("error", response.get("error"));
            }
        } catch (Exception e) {
            log.error("Failed to call tool {} on server {}", toolName, serverName, e);
            return Map.of("error", e.getMessage());
        }

        return Map.of("error", "Unknown error");
    }

    public boolean hasConnection(String serverName) {
        return connections.containsKey(serverName);
    }

    @Scheduled(fixedDelay = 60_000, initialDelay = 60_000)
    public void healthCheck() {
        for (Map.Entry<String, ServerConnection> entry : connections.entrySet()) {
            String serverName = entry.getKey();
            ServerConnection conn = entry.getValue();
            try {
                Map<String, Object> ping = Map.of(
                        "jsonrpc", "2.0",
                        "method", "ping",
                        "id", System.currentTimeMillis()
                );
                restTemplate.postForObject(conn.getUrl() + "/mcp", ping, Map.class);
                log.debug("Health check OK: {}", serverName);
            } catch (Exception e) {
                log.warn("Health check failed for server: {} - {}", serverName, e.getMessage());
                connections.remove(serverName);
                log.info("Attempting to reconnect to server: {}", serverName);
                ServerConfig.ServerInfo serverInfo = config.getServerInfo(serverName);
                if (serverInfo != null) {
                    connectWithRetry(serverInfo, 1);
                }
            }
        }
    }

    private static class ServerConnection {
        private final String name;
        private final String url;

        ServerConnection(String name, String url) {
            this.name = name;
            this.url = url;
        }

        public String getName() {
            return name;
        }

        public String getUrl() {
            return url;
        }
    }
}
