#!/bin/bash

mcp-server \
  --spring.config.location=classpath:/application.properties \
  &
SERVER_PID=$!
echo "mcp-server started (PID: $SERVER_PID) on port 8081"

mcp-client \
  --spring.config.location=classpath:/application.properties \
  &
CLIENT_PID=$!
echo "mcp-client started (PID: $CLIENT_PID) on port 8082"

# mcp-host default
mcp-host \
  --spring.config.location=classpath:/application.properties \
  &
HOST_PID=$!
echo "mcp-host started (PID: $HOST_PID) on port 8080"

trap "kill $SERVER_PID $CLIENT_PID $HOST_PID 2>/dev/null" EXIT

echo ""
echo "MCP Demo running:"
echo "  Server: http://localhost:8081/mcp"
echo "  Client: http://localhost:8082/tools"
echo "  Host:   http://localhost:8080/chat"
echo ""
echo "Test: curl -X POST http://localhost:8080/chat -H 'Content-Type: application/json' -d '{\"message\":\"hello\"}'"
echo ""

wait
