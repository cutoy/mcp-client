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
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.io.IOException;

@RestController
public class ChatController {

    private static final Logger log = LoggerFactory.getLogger(ChatController.class);

    private final OpenAiService openAiService;
    private final McpClientService mcpClientService;
    private final ObjectMapper objectMapper;
    private final ConcurrentHashMap<String, SessionEntry> sessions = new ConcurrentHashMap<>();

    @Value("${mcp.max.rounds:10}")
    private int maxRounds;

    @Value("${mcp.session.ttl:30}")
    private int sessionTtlMinutes;

    @Value("${mcp.system.prompt:You are a helpful assistant with access to a MySQL database. Use the available tools to query the database when needed.}")
    private String systemPrompt;

    public ChatController(OpenAiService openAiService, McpClientService mcpClientService, ObjectMapper objectMapper) {
        this.openAiService = openAiService;
        this.mcpClientService = mcpClientService;
        this.objectMapper = objectMapper;

        Executors.newSingleThreadScheduledExecutor().scheduleAtFixedRate(
                this::cleanExpiredSessions, 5, 5, TimeUnit.MINUTES);
    }

    @PostMapping("/chat")
    public ChatResponse chat(@RequestBody ChatRequest request) {
        log.info("POST /chat session={} message: {}", request.getSessionId(), request.getMessage());

        List<Map<String, Object>> tools = mcpClientService.listTools();
        log.info("Available tools: {}", tools.stream().map(t -> t.get("name")).toList());

        List<Map<String, Object>> messages = loadSession(request.getSessionId());

        for (int round = 0; round < maxRounds; round++) {
            log.info("Round {}: sending to OpenAI with {} messages", round + 1, messages.size());

            OpenAiService.OpenAiChatResponse response = openAiService.chat(messages, tools);

            if (!response.hasToolCall()) {
                messages.add(response.getRawMessage());
                saveSession(request.getSessionId(), messages);

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

        log.warn("Max rounds ({}) exceeded. Returning partial result.", maxRounds);
        saveSession(request.getSessionId(), messages);
        return new ChatResponse("Reached maximum " + maxRounds + " rounds of tool calls. "
                + "The query may be too complex or the model is stuck in a loop. "
                + "Last tool result: " + extractToolContentFromLastRound(messages));
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

    private String extractToolContentFromLastRound(List<Map<String, Object>> messages) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            Map<String, Object> msg = messages.get(i);
            if ("tool".equals(msg.get("role"))) {
                Object content = msg.get("content");
                return content != null ? content.toString() : "no content";
            }
        }
        return "no tool results available";
    }

    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chatStream(@RequestBody ChatRequest request) {
        SseEmitter emitter = new SseEmitter(300000L);

        new Thread(() -> {
            try {
                List<Map<String, Object>> tools = mcpClientService.listTools();
                log.info("Stream session={}: available tools: {}", request.getSessionId(),
                        tools.stream().map(t -> t.get("name")).toList());

                List<Map<String, Object>> messages = loadSession(request.getSessionId());
                messages.add(Map.of("role", "user", "content", request.getMessage()));

                int round = 0;
                for (; round < maxRounds; round++) {
                    OpenAiService.OpenAiChatResponse response = openAiService.chat(messages, tools);

                    if (!response.hasToolCall()) {
                        messages.add(response.getRawMessage());
                        saveSession(request.getSessionId(), messages);
                        emitter.send(SseEmitter.event().name("info").data("Generating response..."));

                        openAiService.chatStream(messages, tools,
                                chunk -> {
                                    try { emitter.send(SseEmitter.event().name("chunk").data(chunk)); }
                                    catch (IOException e) { log.error("SSE send failed", e); }
                                },
                                result -> {
                                    try { emitter.send(SseEmitter.event().name("done").data("")); emitter.complete(); }
                                    catch (IOException e) { log.error("SSE complete failed", e); }
                                },
                                error -> {
                                    try { emitter.send(SseEmitter.event().name("error").data(error)); emitter.complete(); }
                                    catch (IOException e) { log.error("SSE error failed", e); }
                                }
                        );
                        return;
                    }

                    OpenAiService.ToolCall toolCall = response.getToolCall();
                    emitter.send(SseEmitter.event().name("tool_call")
                            .data("Calling " + toolCall.getFunctionName() + "..."));

                    String toolName = toolCall.getFunctionName();
                    String serverName = findServerName(tools, toolName);
                    Map<String, Object> toolResult = mcpClientService.callTool(serverName, toolName, toolCall.getArguments());

                    Map<String, Object> assistantMessage = new HashMap<>(response.getRawMessage());
                    Map<String, Object> toolMessage = new HashMap<>();
                    toolMessage.put("role", "tool");
                    toolMessage.put("tool_call_id", toolCall.getId());
                    toolMessage.put("content", extractToolContent(toolResult));
                    messages.add(assistantMessage);
                    messages.add(toolMessage);
                }

                saveSession(request.getSessionId(), messages);
                emitter.send(SseEmitter.event().name("error")
                        .data("Reached maximum " + maxRounds + " rounds"));
                emitter.complete();
            } catch (Exception e) {
                log.error("Stream error", e);
                try { emitter.send(SseEmitter.event().name("error").data(e.getMessage())); emitter.complete(); }
                catch (IOException ex) { log.error("SSE error send failed", ex); }
            }
        }).start();

        return emitter;
    }

    private List<Map<String, Object>> newSessionMessages() {
        List<Map<String, Object>> messages = new ArrayList<>();
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            messages.add(Map.of("role", "system", "content", systemPrompt));
        }
        return messages;
    }

    private List<Map<String, Object>> loadSession(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return newSessionMessages();
        }
        SessionEntry entry = sessions.get(sessionId);
        if (entry == null) {
            return newSessionMessages();
        }
        entry.touch();
        log.info("Session {}: loaded {} previous messages", sessionId, entry.messages.size());
        return new ArrayList<>(entry.messages);
    }

    private void saveSession(String sessionId, List<Map<String, Object>> messages) {
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }
        sessions.put(sessionId, new SessionEntry(messages));
        log.info("Session {}: saved {} messages", sessionId, messages.size());
    }

    private void cleanExpiredSessions() {
        long now = System.currentTimeMillis();
        long ttl = (long) sessionTtlMinutes * 60 * 1000;
        sessions.entrySet().removeIf(entry -> {
            boolean expired = now - entry.getValue().lastAccess > ttl;
            if (expired) log.info("Session {} expired", entry.getKey());
            return expired;
        });
    }

    private static class SessionEntry {
        final List<Map<String, Object>> messages;
        volatile long lastAccess;

        SessionEntry(List<Map<String, Object>> messages) {
            this.messages = List.copyOf(messages);
            this.lastAccess = System.currentTimeMillis();
        }

        void touch() {
            lastAccess = System.currentTimeMillis();
        }
    }
}
