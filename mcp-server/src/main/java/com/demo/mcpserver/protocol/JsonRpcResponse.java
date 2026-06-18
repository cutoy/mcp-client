package com.demo.mcpserver.protocol;

public class JsonRpcResponse {

    private String jsonrpc;
    private Object result;
    private JsonRpcError error;
    private Object id;

    public static JsonRpcResponse success(Object id, Object result) {
        JsonRpcResponse r = new JsonRpcResponse();
        r.jsonrpc = "2.0";
        r.id = id;
        r.result = result;
        return r;
    }

    public static JsonRpcResponse error(Object id, int code, String message) {
        JsonRpcResponse r = new JsonRpcResponse();
        r.jsonrpc = "2.0";
        r.id = id;
        r.error = new JsonRpcError(code, message);
        return r;
    }

    public String getJsonrpc() {
        return jsonrpc;
    }

    public Object getResult() {
        return result;
    }

    public JsonRpcError getError() {
        return error;
    }

    public Object getId() {
        return id;
    }

    public static class JsonRpcError {
        private int code;
        private String message;

        public JsonRpcError(int code, String message) {
            this.code = code;
            this.message = message;
        }

        public int getCode() {
            return code;
        }

        public String getMessage() {
            return message;
        }
    }
}
