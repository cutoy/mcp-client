# MCP Java Demo 技术设计文档

## 1. 概述

实现一个 MCP（Model Context Protocol）全链路 Demo，通过三个独立的 Java 项目演示 MCP 的核心工作原理：用户自然语言提问 -> 大模型 function calling -> MCP Client 转发 -> MCP Server 执行数据库查询 -> 结果返回生成回答。

## 2. 架构总览

```
用户 --> mcp-host (8080) --> OpenAI 兼容 API
              |   ^
              |   | HTTP
              v   |
         mcp-client (8082) -- MCP (Streamable HTTP) --> mcp-server (8081) --> MySQL
```

三个独立 Spring Boot Maven 项目，HTTP 协议通信。

| 项目 | 端口 | 核心依赖 | 职责 |
|------|------|---------|------|
| mcp-server | 8081 | Spring Boot + MCP Java SDK v0.10.0 + HikariCP | 提供 `get_schema` / `query_database` 工具 |
| mcp-client | 8082 | Spring Boot + MCP Java SDK v0.10.0 | 管理 Server 连接，聚合多 Server 工具，对 Host 暴露 REST API |
| mcp-host | 8080 | Spring Boot + OkHttp | 用户入口，调用 OpenAI 兼容 API，通过 mcp-client 执行工具调用 |

## 3. mcp-server 设计

### 3.1 项目结构

```
mcp-server/
├── pom.xml
├── src/main/resources/application.properties
└── src/main/java/com/demo/mcpserver/
    ├── McpServerApplication.java
    ├── config/
    │   ├── DataSourceConfig.java
    │   └── McpServerConfig.java
    └── tools/
        ├── GetSchemaTool.java
        └── QueryDatabaseTool.java
```

### 3.2 Maven 依赖

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-web</artifactId>
</dependency>
<dependency>
    <groupId>io.modelcontextprotocol.sdk</groupId>
    <artifactId>mcp-spring-boot-starter-server-servlet</artifactId>
    <version>0.10.0</version>
</dependency>
<dependency>
    <groupId>com.zaxxer</groupId>
    <artifactId>HikariCP</artifactId>
</dependency>
<dependency>
    <groupId>com.mysql</groupId>
    <artifactId>mysql-connector-j</artifactId>
</dependency>
```

### 3.3 MCP 传输层

使用 Streamable HTTP（推荐），单端点 `/mcp`，在 `McpServerConfig` 中配置：

```java
@Bean
public HttpServletStreamableServerTransportProvider transportProvider() {
    return HttpServletStreamableServerTransportProvider.builder()
        .jsonMapper(new McpJsonMapper())
        .mcpEndpoint("/mcp")
        .build();
}
```

Client 侧对应使用 `HttpClientStreamableHttpTransport.builder("http://localhost:8081").endpoint("/mcp").build()`。

### 3.4 工具定义

#### get_schema

- **名称**: `get_schema`
- **描述**: 获取数据库表结构。不传参数返回所有表名列表；传入 table_name 返回该表的列详情。支持逗号分隔查询多个表
- **参数**: `table_name` (可选，String，支持逗号分隔多个表名)
- **输入 Schema**:

```json
{
  "type": "object",
  "properties": {
    "table_name": {
      "type": "string",
      "description": "要查看结构的表名，多个表名用逗号分隔，如 'users,orders'；不传则返回所有表名列表"
    }
  }
}
```

- **实现逻辑**:
  1. 参数为空时：执行 `SHOW TABLES`，返回 `["users", "orders", ...]`
  2. 传入表名时：解析逗号分隔的表名列表，对每个表分别执行 `SHOW FULL COLUMNS FROM {table_name}`，返回 `{table_name: [{column, type, ...}, ...], ...}` 结构

#### query_database

- **名称**: `query_database`
- **描述**: 执行只读 SQL 查询，仅允许 SELECT 语句
- **参数**: `sql` (必填，String)
- **输入 Schema**:

```json
{
  "type": "object",
  "properties": {
    "sql": {
      "type": "string",
      "description": "SELECT 查询语句"
    }
  },
  "required": ["sql"]
}
```

- **实现逻辑**:
  1. 校验 SQL：仅允许 SELECT，拒绝 INSERT/UPDATE/DELETE/DROP/ALTER 等
  2. 设置查询超时 30 秒
  3. 执行查询，返回 JSON 数组格式结果集
  4. 限制结果行数（如最多 1000 行），避免大结果集

### 3.5 数据库配置

```properties
# application.properties
server.port=8081

mcp.db.host=localhost
mcp.db.port=3306
mcp.db.name=testdb
mcp.db.username=root
mcp.db.password=your_password
mcp.db.query.timeout=30
mcp.db.query.max.rows=1000
```

使用 HikariCP 连接池管理连接。

### 3.6 Server 注册

```java
@Bean
public McpSyncServer mcpServer(HttpServletStreamableServerTransportProvider transport) {
    return McpServer.sync(transport)
        .serverInfo("mysql-mcp-server", "1.0.0")
        .capabilities(ServerCapabilities.builder().tools(true).build())
        .toolCall(getSchemaTool.definition(), getSchemaTool::execute)
        .toolCall(queryDatabaseTool.definition(), queryDatabaseTool::execute)
        .build();
}
```

## 4. mcp-client 设计

### 4.1 项目结构

```
mcp-client/
├── pom.xml
├── src/main/resources/application.properties
└── src/main/java/com/demo/mcpclient/
    ├── McpClientApplication.java
    ├── config/
    │   └── ClientConfig.java
    ├── registry/
    │   └── ServerRegistry.java
    └── controller/
        └── McpController.java
```

### 4.2 Maven 依赖

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-web</artifactId>
</dependency>
<dependency>
    <groupId>io.modelcontextprotocol.sdk</groupId>
    <artifactId>mcp-spring-boot-starter-client</artifactId>
    <version>0.10.0</version>
</dependency>
```

### 4.3 Server 注册与连接管理

`ServerRegistry` 负责：
1. 启动时读取 `mcp.servers[]` 配置
2. 为每个 Server 建立 `McpSyncClient` 连接
3. 维护 client 实例，支持按 serverName 查找

配置示例（`application.properties`）：
```properties
server.port=8082
mcp.servers[0].name=mysql-server
mcp.servers[0].url=http://localhost:8081
```

连接建立：
```java
McpClientTransport transport = HttpClientStreamableHttpTransport
    .builder(serverUrl)
    .endpoint("/mcp")
    .build();
McpSyncClient client = McpClient.sync(transport).build();
client.initialize(); // MCP 握手
```

### 4.4 REST API

#### GET /tools

聚合所有已注册 Server 的工具列表。

返回格式：
```json
[
  {
    "serverName": "mysql-server",
    "toolName": "get_schema",
    "description": "获取数据库表结构",
    "inputSchema": { ... }
  },
  {
    "serverName": "mysql-server",
    "toolName": "query_database",
    "description": "执行只读 SQL 查询",
    "inputSchema": { ... }
  }
]
```

#### POST /tools/call

转发工具调用到指定 Server。

请求：
```json
{
  "serverName": "mysql-server",
  "toolName": "query_database",
  "arguments": {
    "sql": "SELECT * FROM users"
  }
}
```

响应：
```json
{
  "content": [{"type": "text", "text": "[{\"id\":1,\"name\":\"Alice\"},...]"}],
  "isError": false
}
```

## 5. mcp-host 设计

### 5.1 项目结构

```
mcp-host/
├── pom.xml
├── src/main/resources/application.properties
└── src/main/java/com/demo/mcphost/
    ├── McpHostApplication.java
    ├── config/
    │   └── McpHostConfig.java
    ├── service/
    │   ├── OpenAiService.java
    │   └── McpClientService.java
    ├── controller/
    │   └── ChatController.java
    └── model/
        ├── ChatRequest.java
        └── ChatResponse.java
```

### 5.2 Maven 依赖

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-web</artifactId>
</dependency>
<dependency>
    <groupId>com.squareup.okhttp3</groupId>
    <artifactId>okhttp</artifactId>
</dependency>
<dependency>
    <groupId>com.fasterxml.jackson.core</groupId>
    <artifactId>jackson-databind</artifactId>
</dependency>
```

### 5.3 配置

```properties
server.port=8080
openai.api.key=sk-xxx
openai.api.url=https://api.openai.com/v1
mcp.client.url=http://localhost:8082
mcp.max.rounds=10
```

### 5.4 核心流程（多轮 function call 循环）

```java
@PostMapping("/chat")
public ChatResponse chat(@RequestBody ChatRequest request) {
    // 1. 获取可用工具
    List<ToolInfo> tools = mcpClientService.listTools();
    List<Map<String, Object>> openAiTools = convertToOpenAiTools(tools);

    // 2. 初始化消息列表
    List<Map<String, Object>> messages = new ArrayList<>();
    messages.add(Map.of("role", "user", "content", request.getMessage()));

    // 3. 多轮调用循环（最多 max.rounds 轮）
    for (int round = 0; round < maxRounds; round++) {
        ChatResponse response = openAiService.chat(messages, openAiTools);

        if (!response.hasToolCall()) {
            return response; // 大模型返回最终文本
        }

        // 4. 执行工具调用
        ToolResult result = mcpClientService.callTool(
            response.getToolCall().serverName(),
            response.getToolCall().functionName(),
            response.getToolCall().arguments()
        );

        // 5. 将工具调用和结果追加到消息历史
        messages.add(response.getAssistantMessage());
        messages.add(result.toToolMessage());
    }

    throw new RuntimeException("Exceeded max rounds: " + maxRounds);
}
```

大模型在第 1 轮可能调用 `get_schema`（不传参数），获取表名列表；第 2 轮调用 `get_schema(table_name="users,orders")` 一次性获取多表列结构；第 3 轮调用 `query_database(sql="SELECT ...")`；第 4 轮返回最终自然语言回答。

### 5.5 OpenAI 兼容 API 调用

`OpenAiService` 封装对 OpenAI 兼容接口的调用，支持：
- 自定义 `openai.api.url`（可指向代理或国内模型）
- System prompt 说明可用的 MCP 工具
- 将 mcp-client 返回的工具列表转换为 OpenAI function calling 的 tools 格式

## 6. 数据流全链路示意

```
1. 用户: "查询所有用户的订单数量"
   POST /chat -> mcp-host

2. mcp-host -> mcp-client: GET /tools
   -> 返回: [get_schema, query_database]

3. mcp-host -> OpenAI API: chat(userMessage, tools)
   -> 返回: function_call get_schema()

4. mcp-host -> mcp-client: POST /tools/call {get_schema, {}}
   mcp-client -> mcp-server: MCP tools/call get_schema
   mcp-server -> MySQL: SHOW TABLES
   -> 返回: ["users", "orders", "order_items"]

5. mcp-host -> OpenAI API: chat(history + toolResult, tools)
   -> 返回: function_call get_schema(table_name="users,orders")

6. mcp-host -> mcp-client -> mcp-server -> MySQL: SHOW FULL COLUMNS FROM users; SHOW FULL COLUMNS FROM orders
   -> 返回: {users: [{...}], orders: [{...}]}

7. mcp-host -> OpenAI API: chat(history + toolResult, tools)
   -> 返回: function_call query_database(sql="SELECT u.name, COUNT(o.id) FROM users u LEFT JOIN orders o ON u.id=o.user_id GROUP BY u.id")

8. mcp-host -> mcp-client -> mcp-server -> MySQL: SELECT ...
    -> 返回: [{"name":"Alice","count":5}, {"name":"Bob","count":3}]

9. mcp-host -> OpenAI API: chat(history + toolResult, tools)
    -> 返回: "根据查询结果，Alice 有 5 个订单，Bob 有 3 个订单..."
```

## 7. 关键设计决策

| 决策 | 说明 |
|------|------|
| Streamable HTTP 传输 | 比旧 SSE 更简洁，单端点 `/mcp`，SDK v0.10.0 推荐 |
| get_schema 多表查询 | 支持逗号分隔一次查询多个表结构，减少大模型往返轮数 |
| 工具名加前缀 | `{serverName}/{toolName}` 防冲突 |
| query_database 只读限制 | SQL 校验 + 30 秒超时 + 行数上限 |
| Host 多轮循环 | while 循环支持多轮 function call |
| 循环上限 10 轮 | 防止死循环 |
| 全链路日志 | 每个节点关键步骤打印 JSON 日志 |
| 数据库可配置 | application.properties 驱动 HikariCP |

## 8. 启动顺序

1. **MySQL** — 确保 MySQL 运行且数据库存在
2. **mcp-server** — `java -jar mcp-server.jar`（端口 8081）
3. **mcp-client** — `java -jar mcp-client.jar`（端口 8082），启动时连接 Server
4. **mcp-host** — `java -jar mcp-host.jar`（端口 8080）

## 9. 测试验证

```bash
# 直接测试 mcp-server（跳过 client/host）
curl -X POST http://localhost:8081/mcp \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","method":"tools/list","id":1}'

# 通过 mcp-client 测试
curl http://localhost:8082/tools
curl -X POST http://localhost:8082/tools/call \
  -H "Content-Type: application/json" \
  -d '{"serverName":"mysql-server","toolName":"get_schema","arguments":{}}'

# 全链路测试
curl -X POST http://localhost:8080/chat \
  -H "Content-Type: application/json" \
  -d '{"message":"查询所有用户的订单数量"}'
```
