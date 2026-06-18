# TODO

## 已实现

### mcp-server

- [x] JSON-RPC 2.0 MCP 协议端点（`POST /mcp`）
- [x] `initialize` 握手，返回协议版本和能力声明
- [x] `tools/list` 返回可用工具列表
- [x] `tools/call` 支持按工具名分发调用
- [x] `notifications/initialized` 通知处理
- [x] `get_schema` 工具 — 不传参返回所有表名，传入表名返回列详情
- [x] `get_schema` 逗号分隔多表查询（如 `users,orders`）
- [x] `query_database` 工具 — 任意 SELECT 查询
- [x] `query_database` SQL 校验 — 仅允许 SELECT
- [x] `query_database` 危险 SQL 关键字/函数深度检查
- [x] `query_database` 参数化查询（PreparedStatement，防止注入）
- [x] `query_database` 查询超时（可配置 30s）
- [x] `query_database` 结果行数上限（可配置 1000 行）
- [x] 数据库连接完全可配置，从环境变量读取
- [x] HikariCP 连接池
- [x] 结构化日志输出

### mcp-client

- [x] 启动时自动连接已配置的 MCP Server
- [x] JSON-RPC `initialize` 握手 + `notifications/initialized` 通知
- [x] 多 Server 注册和连接管理
- [x] 连接失败自动重试 + 指数退避
- [x] 定期健康检查已连接 Server（60s 间隔，自动重连）
- [x] 工具列表聚合 — 标注来源 serverName
- [x] 工具名支持 `tool-name-prefix` 前缀
- [x] REST API: `GET /tools`, `POST /tools/call`
- [x] 连接失败不阻塞其他 Server

### mcp-host

- [x] OpenAI 兼容 API 调用（支持自定义 base URL）
- [x] API Key 从环境变量 `OPENAI_API_KEY` 读取
- [x] 从 Client 获取工具列表并转换为 OpenAI function calling 格式
- [x] 多轮 function call 循环
- [x] 循环上限保护（可配置）
- [x] 超过最大轮数优雅报错
- [x] 可配置 System prompt
- [x] 流式输出 `/chat/stream`（SSE 格式）
- [x] 对话历史保持（session 管理，30分钟过期）
- [x] Web UI 交互界面（`/index.html`）
- [x] OpenAI API 调用重试 + 超时配置
- [x] 工具结果自动回传大模型
- [x] 全链路结构化日志

### 部署与工程

- [x] Dockerfile（多阶段构建，所有三个服务）
- [x] Docker Compose（MySQL + 三个服务，健康检查）
- [x] init.sql（示例数据库种子数据）
- [x] 设计文档 + 实施计划
- [x] README + 启动脚本

---

## 未实现

### 协议完善

- [ ] Server 端 SSE / Streamable HTTP 传输
- [ ] Server `resources/list` 和 `prompts/list` 能力
- [ ] MCP 心跳 / ping 机制
- [ ] JSON-RPC 批处理请求（batch）

### 安全

- [ ] MCP Server 访问认证（API Key / Token）
- [ ] 请求限流

### 功能增强

- [ ] mcp-server 查询结果缓存
- [ ] mcp-server 支持 PostgreSQL / SQLite
- [ ] mcp-host 请求日志持久化

### 工程化

- [ ] 单元测试
- [ ] 集成测试（全链路）
- [ ] GitHub Actions CI
- [ ] 版本号自动管理
