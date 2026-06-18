package com.demo.mcpclient.controller;

import com.demo.mcpclient.config.ServerRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
public class McpClientController {

    private static final Logger log = LoggerFactory.getLogger(McpClientController.class);

    private final ServerRegistry registry;

    public McpClientController(ServerRegistry registry) {
        this.registry = registry;
    }

    @GetMapping("/tools")
    public List<Map<String, Object>> listTools() {
        log.info("GET /tools");
        return registry.listTools();
    }

    @PostMapping("/tools/call")
    public Map<String, Object> callTool(@RequestBody Map<String, Object> request) {
        String serverName = (String) request.get("serverName");
        String toolName = (String) request.get("toolName");
        @SuppressWarnings("unchecked")
        Map<String, Object> arguments = (Map<String, Object>) request.get("arguments");

        log.info("POST /tools/call server={} tool={} args={}", serverName, toolName, arguments);
        return registry.callTool(serverName, toolName, arguments);
    }
}
