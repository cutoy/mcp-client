package com.demo.mcpserver.tools;

public class ToolCallResult {

    private String content;
    private boolean isError;

    public ToolCallResult(String content, boolean isError) {
        this.content = content;
        this.isError = isError;
    }

    public String getContent() {
        return content;
    }

    public boolean isError() {
        return isError;
    }
}
