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

### mcp-server

编辑 `mcp-server/src/main/resources/application.properties`：

```properties
server.port=8081
mcp.db.host=localhost
mcp.db.port=3306
mcp.db.name=testdb
mcp.db.username=root
mcp.db.password=your_password
mcp.db.query.timeout=30
mcp.db.query.max.rows=1000
```

### mcp-client

编辑 `mcp-client/src/main/resources/application.properties`：

```properties
server.port=8082
mcp.servers[0].name=mysql-server
mcp.servers[0].url=http://localhost:8081
```

支持配置多个 MCP Server，逗号分隔。

### mcp-host

编辑 `mcp-host/src/main/resources/application.properties`：

```properties
server.port=8080
openai.api.key=sk-your-api-key
openai.api.url=https://api.openai.com/v1
mcp.client.url=http://localhost:8082
mcp.max.rounds=10
```

`openai.api.url` 支持任何 OpenAI 兼容接口（代理、国内模型等）。

## 编译

```bash
mvn compile -f mcp-server/pom.xml
mvn compile -f mcp-client/pom.xml
mvn compile -f mcp-host/pom.xml
```

## 启动

**必须按顺序启动**（Server → Client → Host）：

```bash
# 终端 1
mvn spring-boot:run -f mcp-server/pom.xml

# 终端 2
mvn spring-boot:run -f mcp-client/pom.xml

# 终端 3
mvn spring-boot:run -f mcp-host/pom.xml
```

打包运行（可选）：

```bash
mvn package -f mcp-server/pom.xml -DskipTests
java -jar mcp-server/target/mcp-server-1.0.0-SNAPSHOT.jar
```

## 测试

### 测试 mcp-server MCP 协议

```bash
# 列出工具
curl -X POST http://localhost:8081/mcp \
  -H 'Content-Type: application/json' \
  -d '{"jsonrpc":"2.0","method":"tools/list","id":1}'

# 调用 get_schema
curl -X POST http://localhost:8081/mcp \
  -H 'Content-Type: application/json' \
  -d '{"jsonrpc":"2.0","method":"tools/call","params":{"name":"get_schema","arguments":{}},"id":2}'

# 调用 query_database
curl -X POST http://localhost:8081/mcp \
  -H 'Content-Type: application/json' \
  -d '{"jsonrpc":"2.0","method":"tools/call","params":{"name":"query_database","arguments":{"sql":"SELECT * FROM users"}},"id":3}'
```

### 测试 mcp-client

```bash
# 查看聚合后的工具列表
curl http://localhost:8082/tools

# 通过 client 调用工具
curl -X POST http://localhost:8082/tools/call \
  -H 'Content-Type: application/json' \
  -d '{"serverName":"mysql-server","toolName":"get_schema","arguments":{}}'
```

### 全链路测试

```bash
curl -X POST http://localhost:8080/chat \
  -H 'Content-Type: application/json' \
  -d '{"message":"查询所有用户及其订单数量"}'
```

## mcp-server 工具

| 工具 | 参数 | 说明 |
|------|------|------|
| get_schema | table_name（可选，逗号分隔） | 不传参返回所有表名；传入表名返回列结构 |
| query_database | sql（必填，仅 SELECT） | 执行只读查询，30s 超时，最多 1000 行 |

## 项目结构

```
mcp-server/src/main/java/com/demo/mcpserver/
├── McpServerApplication.java
├── config/DataSourceConfig.java      # HikariCP + MySQL
├── controller/McpController.java     # JSON-RPC 2.0 /mcp 端点
├── protocol/JsonRpcRequest.java      # 请求/响应协议类
├── protocol/JsonRpcResponse.java
└── tools/
    ├── GetSchemaTool.java            # 表结构查询
    ├── QueryDatabaseTool.java        # SQL 查询（只读）
    ├── ToolDefinition.java
    └── ToolCallResult.java

mcp-client/src/main/java/com/demo/mcpclient/
├── McpClientApplication.java
├── config/
│   ├── ClientConfig.java             # RestTemplate + 启动初始化
│   ├── ServerConfig.java             # mcp.servers[] 配置读取
│   └── ServerRegistry.java           # 连接管理 + JSON-RPC 转发
└── controller/
    └── McpClientController.java      # GET /tools, POST /tools/call

mcp-host/src/main/java/com/demo/mcphost/
├── McpHostApplication.java
├── controller/ChatController.java    # POST /chat 多轮循环
├── model/ChatRequest.java
├── model/ChatResponse.java
└── service/
    ├── OpenAiService.java            # OpenAI 兼容 API 调用
    └── McpClientService.java         # HTTP 调用 client API
```
