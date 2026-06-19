# MCP Java Demo

基于 Java 的 MCP（Model Context Protocol）全链路 Demo，演示大模型通过 MCP 协议调用外部工具查询数据库的完整流程。

## 架构

```
用户 → mcp-host (8080) → OpenAI API
              │
              │ HTTP
              ▼
         mcp-client (8082) ── JSON-RPC ──→ mcp-server (8081) ──→ MySQL
```

| 项目 | 端口 | 职责 |
|------|------|------|
| mcp-server | 8081 | MCP Server，实现 JSON-RPC 2.0 协议，提供 get_schema / query_database 两个数据库工具 |
| mcp-client | 8082 | MCP 中间层，管理 Server 连接、聚合工具列表，对 Host 暴露 REST API |
| mcp-host | 8080 | 用户入口，调用 OpenAI 兼容 API，通过 Client 执行多轮 function call |

## 关键特性

- **JSON-RPC 2.0 MCP 协议**：initialize / ping / tools/list / tools/call / resources/list / prompts/list / 通知
- **多 Server 管理**：支持同时连接多个 MCP Server，工具聚合
- **多轮 function call**：大模型自主决策调用顺序，循环执行直到收敛
- **流式输出**：`/chat/stream` 端点，SSE 格式实时推送响应
- **对话历史**：Session 管理，支持多轮对话上下文保持（30分钟 TTL）
- **SQL 安全**：仅允许 SELECT，拦截危险关键字/函数，支持参数化查询
- **连接健壮**：客户端启动重试 + 指数退避 + 定期健康检查自动重连
- **OpenAI 重试**：API 调用失败自动重试 + 指数退避 + 超时控制
- **Web UI**：内置聊天界面（`/index.html`），支持流式/普通模式切换
- **Docker 部署**：多阶段构建 Dockerfile + docker-compose 一键部署
- **CI/CD**：GitHub Actions 自动编译 + 集成测试

## 交互流程

1. 用户发送自然语言问题到 mcp-host
2. mcp-host 从 mcp-client 获取可用工具列表
3. 将问题 + 工具列表发给 OpenAI
4. 大模型自主决定调用 get_schema（了解表结构）→ query_database（执行查询）
5. 每轮工具调用通过 mcp-client 转发到 mcp-server 执行
6. 结果返回大模型，生成最终回答

## 前置条件

- JDK 17+
- Maven 3.8+
- MySQL 5.7+（需要提前创建好数据库和表）

## 配置

所有敏感配置支持通过环境变量覆盖。

### mcp-server

编辑 `mcp-server/src/main/resources/application.properties`：

```properties
server.port=8081
mcp.db.host=${MCP_DB_HOST:localhost}
mcp.db.port=${MCP_DB_PORT:3306}
mcp.db.name=${MCP_DB_NAME:testdb}
mcp.db.username=${MCP_DB_USERNAME:root}
mcp.db.password=${MCP_DB_PASSWORD:your_password}
mcp.db.query.timeout=30
mcp.db.query.max.rows=1000
```

环境变量：

| 变量 | 说明 | 默认值 |
|------|------|--------|
| `MCP_DB_HOST` | 数据库主机 | localhost |
| `MCP_DB_PORT` | 数据库端口 | 3306 |
| `MCP_DB_NAME` | 数据库名 | testdb |
| `MCP_DB_USERNAME` | 用户名 | root |
| `MCP_DB_PASSWORD` | 密码 | - |

### mcp-client

编辑 `mcp-client/src/main/resources/application.properties`：

```properties
server.port=8082
mcp.servers[0].name=mysql-server
mcp.servers[0].url=http://localhost:8081
```

支持配置多个 MCP Server。每个 Server 可选配置 `tool-name-prefix` 为工具名添加前缀（如 `db_get_schema`），防止跨 Server 工具名冲突。

### mcp-host

编辑 `mcp-host/src/main/resources/application.properties`：

```properties
server.port=8080
openai.api.key=${OPENAI_API_KEY:sk-your-api-key}
openai.api.url=${OPENAI_API_URL:https://api.openai.com/v1}
mcp.client.url=${MCP_CLIENT_URL:http://localhost:8082}
mcp.max.rounds=10
mcp.system.prompt=You are a helpful assistant with access to a MySQL database.
mcp.session.ttl=30
openai.timeout=60
openai.retry.count=3
```

环境变量：

| 变量 | 说明 | 默认值 |
|------|------|--------|
| `OPENAI_API_KEY` | OpenAI API Key | - |
| `OPENAI_API_URL` | API 地址（支持代理/兼容接口） | https://api.openai.com/v1 |
| `MCP_CLIENT_URL` | mcp-client 地址 | http://localhost:8082 |

## 编译

```bash
mvn compile -f mcp-server/pom.xml
mvn compile -f mcp-client/pom.xml
mvn compile -f mcp-host/pom.xml
```

## 启动

**必须按顺序启动**（Server → Client → Host）：

```bash
mvn spring-boot:run -f mcp-server/pom.xml

mvn spring-boot:run -f mcp-client/pom.xml

mvn spring-boot:run -f mcp-host/pom.xml
```

打包运行：

```bash
mvn package -f mcp-server/pom.xml -DskipTests
java -jar mcp-server/target/mcp-server-1.0.0-SNAPSHOT.jar
```

## Docker 部署

```bash
OPENAI_API_KEY=sk-xxx docker compose up -d
```

包含 MySQL 8.0 + 示例数据自动初始化。服务端口：8080（Host）、8082（Client）、8081（Server）、3307（MySQL）。

## 测试

### 测试 mcp-server MCP 协议

```bash
# 列出工具
curl -X POST http://localhost:8081/mcp \
  -H 'Content-Type: application/json' \
  -d '{"jsonrpc":"2.0","method":"tools/list","id":1}'

# 心跳
curl -X POST http://localhost:8081/mcp \
  -H 'Content-Type: application/json' \
  -d '{"jsonrpc":"2.0","method":"ping","id":1}'

# 资源列表
curl -X POST http://localhost:8081/mcp \
  -H 'Content-Type: application/json' \
  -d '{"jsonrpc":"2.0","method":"resources/list","id":2}'

# 提示模板
curl -X POST http://localhost:8081/mcp \
  -H 'Content-Type: application/json' \
  -d '{"jsonrpc":"2.0","method":"prompts/list","id":3}'

# 批处理请求
curl -X POST http://localhost:8081/mcp \
  -H 'Content-Type: application/json' \
  -d '[{"jsonrpc":"2.0","method":"ping","id":1},{"jsonrpc":"2.0","method":"tools/list","id":2}]'

# 调用 get_schema（所有表）
curl -X POST http://localhost:8081/mcp \
  -H 'Content-Type: application/json' \
  -d '{"jsonrpc":"2.0","method":"tools/call","params":{"name":"get_schema","arguments":{}},"id":2}'

# 调用 get_schema（指定表）
curl -X POST http://localhost:8081/mcp \
  -H 'Content-Type: application/json' \
  -d '{"jsonrpc":"2.0","method":"tools/call","params":{"name":"get_schema","arguments":{"table_name":"users,orders"}},"id":3}'

# 调用 query_database（普通 SQL）
curl -X POST http://localhost:8081/mcp \
  -H 'Content-Type: application/json' \
  -d '{"jsonrpc":"2.0","method":"tools/call","params":{"name":"query_database","arguments":{"sql":"SELECT * FROM users"}},"id":4}'

# 调用 query_database（参数化查询）
curl -X POST http://localhost:8081/mcp \
  -H 'Content-Type: application/json' \
  -d '{"jsonrpc":"2.0","method":"tools/call","params":{"name":"query_database","arguments":{"sql":"SELECT * FROM users WHERE id = ?","params":[1]}},"id":5}'
```

### 测试 mcp-client

```bash
curl http://localhost:8082/tools

curl -X POST http://localhost:8082/tools/call \
  -H 'Content-Type: application/json' \
  -d '{"serverName":"mysql-server","toolName":"get_schema","arguments":{}}'
```

### 全链路测试

```bash
# 普通模式
curl -X POST http://localhost:8080/chat \
  -H 'Content-Type: application/json' \
  -d '{"message":"查询所有用户及其订单数量"}'

# 多轮对话（带 session）
curl -X POST http://localhost:8080/chat \
  -H 'Content-Type: application/json' \
  -d '{"message":"有哪些用户","sessionId":"demo"}'

curl -X POST http://localhost:8080/chat \
  -H 'Content-Type: application/json' \
  -d '{"message":"他们的订单详情","sessionId":"demo"}'

# 流式模式
curl -N -X POST http://localhost:8080/chat/stream \
  -H 'Content-Type: application/json' \
  -d '{"message":"统计每个用户的订单总金额"}'
```

### Web UI

启动 mcp-host 后访问 `http://localhost:8080/index.html`，支持：
- Session ID 管理（多轮对话）
- 流式/普通模式切换
- 实时流式输出

## mcp-server 工具

| 工具 | 参数 | 说明 |
|------|------|------|
| get_schema | table_name（可选，逗号分隔） | 不传参返回所有表名；传入表名返回列结构 |
| query_database | sql（必填，仅 SELECT） + params（可选） | 执行只读查询，支持参数化查询防注入 |

## JSON-RPC 方法

| 方法 | 说明 |
|------|------|
| `initialize` | 握手，返回协议版本和能力声明 |
| `ping` | 心跳检测 |
| `tools/list` | 返回可用工具列表 |
| `tools/call` | 按名称调用工具 |
| `resources/list` | 返回可用资源 |
| `prompts/list` | 返回提示模板 |
| `notifications/initialized` | 客户端初始化完成通知 |

## SSE 传输

mcp-server 支持两种传输模式：

**普通模式**（默认）：HTTP POST 请求-响应

```bash
curl -X POST http://localhost:8081/mcp -H 'Content-Type: application/json' -d '{"jsonrpc":"2.0","method":"ping","id":1}'
```

**SSE 模式**：长连接 + 事件推送

```bash
# 建立 SSE 连接，获取 session 地址
curl -N http://localhost:8081/sse
# 返回: event:endpoint, data:/mcp?sessionId=xxx

# 通过 SSE session 发送请求
curl -X POST "http://localhost:8081/mcp?sessionId=xxx" \
  -H 'Content-Type: application/json' \
  -d '{"jsonrpc":"2.0","method":"tools/list","id":1}'
# 响应通过 SSE 流返回
```

### SQL 安全策略

- 仅允许 `SELECT` 开头语句
- 拦截多语句（`;`）
- 拦截危险关键字：INSERT, DELETE, DROP, ALTER, TRUNCATE, CREATE, GRANT, REVOKE, EXEC, CALL, REPLACE, RENAME, LOCK, FLUSH, KILL, SHUTDOWN
- 拦截危险函数：LOAD_FILE, INTO OUTFILE/DUMPFILE, BENCHMARK, SLEEP
- 参数化查询（PreparedStatement）支持

## API 端点

| 端点 | 方法 | 说明 |
|------|------|------|
| `POST /mcp` | JSON-RPC | mcp-server MCP 协议入口 |
| `POST /mcp?sessionId=` | JSON-RPC | SSE 会话下通过查询参数路由 |
| `GET /sse` | SSE | mcp-server SSE 传输（返回 endpoint 地址） |
| `GET /tools` | REST | mcp-client 聚合工具列表 |
| `POST /tools/call` | REST | mcp-client 转发工具调用 |
| `POST /chat` | REST | mcp-host 普通对话 |
| `POST /chat/stream` | SSE | mcp-host 流式对话 |
| `GET /index.html` | 静态 | 内置 Web UI |

## 项目结构

```
mcp-server/src/main/java/com/demo/mcpserver/
├── McpServerApplication.java
├── config/DataSourceConfig.java      # HikariCP + MySQL
├── controller/McpController.java     # JSON-RPC 2.0 /mcp 端点
├── protocol/JsonRpcRequest.java
├── protocol/JsonRpcResponse.java
└── tools/
    ├── GetSchemaTool.java
    ├── QueryDatabaseTool.java        # SQL 查询 + 安全检查
    ├── ToolDefinition.java
    └── ToolCallResult.java

mcp-client/src/main/java/com/demo/mcpclient/
├── McpClientApplication.java         # @EnableScheduling
├── config/
│   ├── ClientConfig.java
│   ├── ServerConfig.java             # mcp.servers[] 配置 + toolNamePrefix
│   └── ServerRegistry.java           # 连接管理 + 重试 + 健康检查
└── controller/
    └── McpClientController.java      # GET /tools, POST /tools/call

mcp-host/src/main/java/com/demo/mcphost/
├── McpHostApplication.java
├── controller/ChatController.java    # /chat + /chat/stream + session 管理
├── model/ChatRequest.java            # message + sessionId
├── model/ChatResponse.java
├── service/
│   ├── OpenAiService.java            # OpenAI API + 重试 + 流式
│   └── McpClientService.java
└── resources/static/index.html       # Web UI
```

## 环境变量速查

| 变量 | 服务 | 说明 |
|------|------|------|
| `OPENAI_API_KEY` | mcp-host | 大模型 API Key（必填） |
| `OPENAI_API_URL` | mcp-host | API 地址 |
| `MCP_CLIENT_URL` | mcp-host | mcp-client 地址 |
| `MCP_DB_HOST` | mcp-server | 数据库主机 |
| `MCP_DB_PORT` | mcp-server | 数据库端口 |
| `MCP_DB_NAME` | mcp-server | 数据库名 |
| `MCP_DB_USERNAME` | mcp-server | 数据库用户 |
| `MCP_DB_PASSWORD` | mcp-server | 数据库密码 |
