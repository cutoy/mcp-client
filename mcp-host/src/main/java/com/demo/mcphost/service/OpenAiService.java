package com.demo.mcphost.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.*;
import okhttp3.Response;
import okio.BufferedSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

@Service
public class OpenAiService {

    private static final Logger log = LoggerFactory.getLogger(OpenAiService.class);

    private final OkHttpClient client;
    private final ObjectMapper objectMapper;

    @Value("${openai.api.key}")
    private String apiKey;

    @Value("${openai.api.url}")
    private String apiUrl;

    @Value("${openai.timeout:60}")
    private int timeout;

    @Value("${openai.retry.count:3}")
    private int retryCount;

    public OpenAiService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.client = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(120, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .build();
    }

    public OpenAiChatResponse chat(List<Map<String, Object>> messages, List<Map<String, Object>> tools) {
        Exception lastException = null;
        for (int attempt = 0; attempt < retryCount; attempt++) {
            try {
                if (attempt > 0) {
                    long waitMs = (long) Math.pow(2, attempt) * 1000;
                    log.info("OpenAI retry attempt {}/{}, waiting {}ms", attempt + 1, retryCount, waitMs);
                    Thread.sleep(waitMs);
                }
                return doChatRequest(messages, tools);
            } catch (IOException e) {
                lastException = e;
                log.warn("OpenAI request attempt {} failed: {}", attempt + 1, e.getMessage());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        log.error("OpenAI API failed after {} attempts", retryCount, lastException);
        return new OpenAiChatResponse("Error: OpenAI API unavailable after " + retryCount + " attempts", null);
    }

    private OpenAiChatResponse doChatRequest(List<Map<String, Object>> messages, List<Map<String, Object>> tools) throws IOException {
        Map<String, Object> body = new HashMap<>();
        body.put("model", "gpt-4o");
        body.put("messages", messages);

        if (tools != null && !tools.isEmpty()) {
            List<Map<String, Object>> openAiTools = convertTools(tools);
            body.put("tools", openAiTools);
            body.put("tool_choice", "auto");
        }

        String json = objectMapper.writeValueAsString(body);
        log.debug("OpenAI request: {}", json);

        Request httpRequest = new Request.Builder()
                .url(apiUrl + "/chat/completions")
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .post(RequestBody.create(json, MediaType.get("application/json")))
                .build();

        try (Response response = client.newCall(httpRequest).execute()) {
            String responseBody = response.body() != null ? response.body().string() : "";
            log.debug("OpenAI response: {}", responseBody);

            if (!response.isSuccessful()) {
                log.error("OpenAI API error: {} - {}", response.code(), responseBody);
                throw new IOException("OpenAI API returned " + response.code() + ": " + responseBody);
            }

            @SuppressWarnings("unchecked")
            Map<String, Object> result = objectMapper.readValue(responseBody, Map.class);

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> choices = (List<Map<String, Object>>) result.get("choices");
            if (choices == null || choices.isEmpty()) {
                return new OpenAiChatResponse("No response from model", null);
            }

            Map<String, Object> choice = choices.get(0);
            Map<String, Object> message = (Map<String, Object>) choice.get("message");

            ToolCall toolCall = null;
            if (message != null && message.containsKey("tool_calls")) {
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> toolCalls = (List<Map<String, Object>>) message.get("tool_calls");
                if (toolCalls != null && !toolCalls.isEmpty()) {
                    Map<String, Object> tc = toolCalls.get(0);
                    Map<String, Object> func = (Map<String, Object>) tc.get("function");
                    toolCall = new ToolCall(
                            (String) tc.get("id"),
                            (String) func.get("name"),
                            objectMapper.readValue((String) func.get("arguments"), Map.class)
                    );
                }
            }

            String content = message != null && message.containsKey("content") && message.get("content") != null
                    ? (String) message.get("content")
                    : "";

            return new OpenAiChatResponse(
                    content,
                    toolCall,
                    message,
                    (String) choice.get("finish_reason")
            );
            }
    }

    private List<Map<String, Object>> convertTools(List<Map<String, Object>> mcpTools) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> tool : mcpTools) {
            Map<String, Object> openAiTool = new HashMap<>();
            openAiTool.put("type", "function");
            Map<String, Object> function = new HashMap<>();
            function.put("name", tool.get("name"));
            function.put("description", tool.get("description"));
            function.put("parameters", tool.get("inputSchema"));
            openAiTool.put("function", function);
            result.add(openAiTool);
        }
        return result;
    }

    public static class OpenAiChatResponse {
        private final String content;
        private final ToolCall toolCall;
        private final Map<String, Object> rawMessage;
        private final String finishReason;

        public OpenAiChatResponse(String content, ToolCall toolCall) {
            this(content, toolCall, null, null);
        }

        public OpenAiChatResponse(String content, ToolCall toolCall, Map<String, Object> rawMessage, String finishReason) {
            this.content = content;
            this.toolCall = toolCall;
            this.rawMessage = rawMessage;
            this.finishReason = finishReason;
        }

        public String getContent() {
            return content;
        }

        public ToolCall getToolCall() {
            return toolCall;
        }

        public boolean hasToolCall() {
            return toolCall != null;
        }

        public Map<String, Object> getRawMessage() {
            return rawMessage;
        }

        public String getFinishReason() {
            return finishReason;
        }
    }

    public static class ToolCall {
        private final String id;
        private final String functionName;
        private final Map<String, Object> arguments;

        public ToolCall(String id, String functionName, Map<String, Object> arguments) {
            this.id = id;
            this.functionName = functionName;
            this.arguments = arguments;
        }

        public String getId() {
            return id;
        }

        public String getFunctionName() {
            return functionName;
        }

        public Map<String, Object> getArguments() {
            return arguments;
        }
    }

    public void chatStream(List<Map<String, Object>> messages, List<Map<String, Object>> tools,
                           Consumer<String> onChunk, Consumer<String> onComplete, Consumer<String> onError) {
        try {
            Map<String, Object> body = new HashMap<>();
            body.put("model", "gpt-4o");
            body.put("messages", messages);
            body.put("stream", true);

            if (tools != null && !tools.isEmpty()) {
                body.put("tools", convertTools(tools));
                body.put("tool_choice", "auto");
            }

            String json = objectMapper.writeValueAsString(body);
            log.debug("OpenAI stream request: {}", json);

            Request httpRequest = new Request.Builder()
                    .url(apiUrl + "/chat/completions")
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .post(RequestBody.create(json, MediaType.get("application/json")))
                    .build();

            try (Response response = client.newCall(httpRequest).execute()) {
                if (!response.isSuccessful()) {
                    onError.accept("OpenAI API error: " + response.code());
                    return;
                }

                BufferedSource source = response.body().source();
                String line;
                while ((line = source.readUtf8Line()) != null) {
                    if (line.startsWith("data: ")) {
                        String data = line.substring(6);
                        if ("[DONE]".equals(data)) {
                            onComplete.accept("");
                            return;
                        }

                        @SuppressWarnings("unchecked")
                        Map<String, Object> chunk = objectMapper.readValue(data, Map.class);
                        @SuppressWarnings("unchecked")
                        List<Map<String, Object>> choices = (List<Map<String, Object>>) chunk.get("choices");
                        if (choices != null && !choices.isEmpty()) {
                            Map<String, Object> choice = choices.get(0);
                            Map<String, Object> delta = (Map<String, Object>) choice.get("delta");
                            if (delta != null && delta.containsKey("content")) {
                                String content = (String) delta.get("content");
                                if (content != null && !content.isEmpty()) {
                                    onChunk.accept(content);
                                }
                            }
                        }
                    }
                }
                onComplete.accept("");
            }
        } catch (IOException e) {
            log.error("OpenAI stream failed", e);
            onError.accept("Error: " + e.getMessage());
        }
    }
}
