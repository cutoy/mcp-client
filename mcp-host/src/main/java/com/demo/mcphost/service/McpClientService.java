package com.demo.mcphost.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;

@Service
public class McpClientService {

    private static final Logger log = LoggerFactory.getLogger(McpClientService.class);

    private final RestTemplate restTemplate;

    @Value("${mcp.client.url}")
    private String clientUrl;

    public McpClientService() {
        this.restTemplate = new RestTemplate();
    }

    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> listTools() {
        log.info("Fetching tools from mcp-client at {}", clientUrl);
        return restTemplate.getForObject(clientUrl + "/tools", List.class);
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> callTool(String serverName, String toolName, Map<String, Object> arguments) {
        log.info("Calling tool {} on server {} via mcp-client", toolName, serverName);

        Map<String, Object> request = Map.of(
                "serverName", serverName,
                "toolName", toolName,
                "arguments", arguments != null ? arguments : Map.of()
        );

        return restTemplate.postForObject(clientUrl + "/tools/call", request, Map.class);
    }
}
