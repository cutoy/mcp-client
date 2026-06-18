package com.demo.mcphost.controller;

import com.demo.mcphost.model.ChatRequest;
import com.demo.mcphost.model.ChatResponse;
import com.demo.mcphost.service.OpenAiService;
import com.demo.mcphost.service.McpClientService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
public class ChatController {

    private static final Logger log = LoggerFactory.getLogger(ChatController.class);

    private final OpenAiService openAiService;
    private final McpClientService mcpClientService;
    private final ObjectMapper objectMapper;

    @Value("${mcp.max.rounds:10}")
    private int maxRounds;

    public ChatController(OpenAiService openAiService, McpClientService mcpClientService, ObjectMapper objectMapper) {
        this.openAiService = openAiService;
        this.mcpClientService = mcpClientService;
        this.objectMapper = objectMapper;
    }

    @PostMapping("/chat")
    public ChatResponse chat(@RequestBody ChatRequest request) {
        log.info("POST /chat message: {}", request.getMessage());

        List<Map<String, Object>> tools = mcpClientService.listTools();
        log.info("Available tools: {}", tools.stream().map(t -> t.get("name")).toList());

        List<Map<String, Object>> messages = new ArrayList<>();
        messages.add(Map.of("role", "user", "content", request.getMessage()));

        for (int round = 0; round < maxRounds; round++) {
            log.info("Round {}: sending to OpenAI with {} messages", round + 1, messages.size());

            OpenAiService.OpenAiChatResponse response = openAiService.chat(messages, tools);

            if (!response.hasToolCall()) {
                String content = response.getContent();
                if (content == null || content.isEmpty()) {
                    content = "No response from model";
                }
                return new ChatResponse(content);
            }

            OpenAiService.ToolCall toolCall = response.getToolCall();
            log.info("Round {}: tool call -> {} args={}", round + 1, toolCall.getFunctionName(), toolCall.getArguments());

            String toolName = toolCall.getFunctionName();
            String serverName = findServerName(tools, toolName);

            Map<String, Object> toolResult = mcpClientService.callTool(serverName, toolName, toolCall.getArguments());

            log.info("Round {}: tool result -> isError={}", round + 1, toolResult.get("isError"));

            Map<String, Object> assistantMessage = new HashMap<>(response.getRawMessage());

            Map<String, Object> toolMessage = new HashMap<>();
            toolMessage.put("role", "tool");
            toolMessage.put("tool_call_id", toolCall.getId());
            toolMessage.put("content", extractToolContent(toolResult));

            messages.add(assistantMessage);
            messages.add(toolMessage);
        }

        throw new RuntimeException("Exceeded max rounds: " + maxRounds + ". Tool call loop did not converge.");
    }

    private String findServerName(List<Map<String, Object>> tools, String toolName) {
        for (Map<String, Object> tool : tools) {
            if (toolName.equals(tool.get("name"))) {
                return (String) tool.get("serverName");
            }
        }
        return "unknown";
    }

    @SuppressWarnings("unchecked")
    private String extractToolContent(Map<String, Object> toolResult) {
        if (toolResult.containsKey("content")) {
            Object content = toolResult.get("content");
            if (content instanceof List) {
                List<Map<String, Object>> contentList = (List<Map<String, Object>>) content;
                if (!contentList.isEmpty()) {
                    Map<String, Object> first = contentList.get(0);
                    return (String) first.getOrDefault("text", content.toString());
                }
            }
            return content.toString();
        }
        if (toolResult.containsKey("error")) {
            return "Error: " + toolResult.get("error");
        }
        try {
            return objectMapper.writeValueAsString(toolResult);
        } catch (Exception e) {
            return toolResult.toString();
        }
    }
}
