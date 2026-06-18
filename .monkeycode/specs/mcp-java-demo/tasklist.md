# 需求实施计划

- [ ] 1. 创建 mcp-server 项目骨架
  - 使用 Maven 创建 Spring Boot 项目，配置 pom.xml 引入 spring-boot-starter-web、mcp-spring-boot-starter-server-servlet v0.10.0、HikariCP、mysql-connector-j
  - 创建 `McpServerApplication.java` 启动类（端口 8081）
  - 创建 `application.properties` 配置文件（mcp.db.* 数据库连接参数、查询超时、最大行数）

- [ ] 2. 实现 mcp-server 数据库连接配置
  - 创建 `DataSourceConfig.java`，读取 mcp.db.* 配置项，构建 HikariCP DataSource Bean
  - 支持 host、port、name、username、password 全配置化

- [ ] 3. 实现 mcp-server MCP 传输层和 Server 注册
  - 创建 `McpServerConfig.java`，配置 `HttpServletStreamableServerTransportProvider`，端点 `/mcp`
  - 构建 `McpSyncServer`，注册 serverInfo 和 capabilities(tools=true)
  - 将传输层注册为 Servlet

- [ ] 4. 实现 get_schema 工具
  - 创建 `GetSchemaTool.java`
  - 无参数时执行 `SHOW TABLES`，返回表名列表
  - 传入 table_name 时解析逗号分隔的表名，逐个执行 `SHOW FULL COLUMNS FROM {table}`，返回 `{table_name: [{Field, Type, Null, Key, Default, Extra, Comment}]}` 结构
  - 注册到 McpSyncServer

- [ ] 5. 实现 query_database 工具
  - 创建 `QueryDatabaseTool.java`
  - SQL 校验：仅允许 SELECT 开头（trim 后忽略大小写），拒绝 INSERT/UPDATE/DELETE/DROP/ALTER 等
  - 设置 Statement queryTimeout 为配置的 mcp.db.query.timeout 秒
  - 限制结果行数为 mcp.db.query.max.rows
  - 结果转为 JSON 数组返回
  - 注册到 McpSyncServer

- [ ] 6. 检查点 - mcp-server 可独立启动并验证
  - 确保 mcp-server 编译通过、端口 8081 启动成功
  - 验证 MCP 握手：curl POST /mcp initialize JSON-RPC 请求
  - 验证 tools/list 返回 get_schema 和 query_database
  - 如有疑问询问用户

- [ ] 7. 创建 mcp-client 项目骨架
  - 使用 Maven 创建 Spring Boot 项目，配置 pom.xml 引入 spring-boot-starter-web、mcp-spring-boot-starter-client v0.10.0
  - 创建 `McpClientApplication.java` 启动类（端口 8082）
  - 创建 `application.properties` 配置文件（mcp.servers[] 列表配置）

- [ ] 8. 实现 mcp-client Server 注册和连接管理
  - 创建 `ServerRegistry.java`
  - 启动时读取 mcp.servers[] 配置，为每个 Server 创建 `HttpClientStreamableHttpTransport` + `McpSyncClient`
  - 执行 client.initialize() 完成 MCP 握手
  - 维护 serverName -> McpSyncClient 映射
  - 连接失败时打印日志但不阻塞其他 Server

- [ ] 9. 实现 mcp-client REST API
  - 创建 `McpController.java`
  - `GET /tools`：遍历所有已连接 client，调用 listTools() 聚合，返回 `[{serverName, toolName, description, inputSchema}]`
  - `POST /tools/call`：接收 `{serverName, toolName, arguments}`，根据 serverName 找到对应 client，调用 callTool()，返回结果

- [ ] 10. 检查点 - mcp-client 可独立启动并验证
  - 确保 mcp-client 编译通过、端口 8082 启动成功（前提 mcp-server 已启动）
  - 验证 GET /tools 返回从 Server 聚合的工具列表
  - 验证 POST /tools/call get_schema 能返回表结构
  - 如有疑问询问用户

- [ ] 11. 创建 mcp-host 项目骨架
  - 使用 Maven 创建 Spring Boot 项目，配置 pom.xml 引入 spring-boot-starter-web、okhttp、jackson-databind
  - 创建 `McpHostApplication.java` 启动类（端口 8080）
  - 创建 `application.properties` 配置文件（openai.api.key、openai.api.url、mcp.client.url、mcp.max.rounds）

- [ ] 12. 实现 OpenAI 兼容 API 调用服务
  - 创建 `OpenAiService.java`
  - 封装 `chat(messages, tools)` 方法：POST 到 openai.api.url/chat/completions
  - 将 mcp-client tools 转换为 OpenAI function calling tools 格式（type: function, function: {name, description, parameters}）
  - 解析响应中的 tool_calls，返回结构化的 ChatResponse（含 content / toolCall 信息）

- [ ] 13. 实现 mcp-client HTTP 调用服务
  - 创建 `McpClientService.java`
  - `listTools()`：HTTP GET 调用 mcp-client /tools
  - `callTool(serverName, toolName, arguments)`：HTTP POST 调用 mcp-client /tools/call

- [ ] 14. 实现 Host 多轮 function call 循环
  - 创建 `ChatController.java`，`POST /chat` 接收 `{message}`
  - 初始化消息历史，从 McpClientService 获取工具列表
  - for 循环最多 mcp.max.rounds 轮：调 OpenAiService.chat()；无 tool_calls 则直接返回内容；有 tool_calls 则通过 McpClientService 执行，结果追加到消息历史
  - 超过最大轮数抛出异常

- [ ] 15. 检查点 - 全链路验证
  - 确保 mcp-host 编译通过、端口 8080 启动成功
  - POST /chat 发送自然语言问题，验证完整链路
  - 验证 get_schema 逗号分隔多表查询正常工作
  - 验证 query_database 只读限制（INSERT 应被拒绝）
  - 如有疑问询问用户
