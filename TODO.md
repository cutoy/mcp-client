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
- [x] `query_database` 危险 SQL 关键字/函数深度检查（INSERT/DELETE/DROP/LOAD_FILE/SLEEP/BENCHMARK/多语句等）
- [x] `query_database` 查询超时（可配置 30s）
- [x] `query_database` 结果行数上限（可配置 1000 行）
- [x] 数据库连接完全可配置（host/port/db/username/password），从环境变量读取
- [x] HikariCP 连接池
- [x] 结构化日志输出

### mcp-client

- [x] 启动时自动连接已配置的 MCP Server
- [x] JSON-RPC `initialize` 握手 + `notifications/initialized` 通知
- [x] 多 Server 注册和连接管理（可配置列表）
- [x] 连接失败自动重试 + 指数退避（3次，2^n秒）
- [x] 工具列表聚合 — 从所有 Server 收集工具并标注来源
- [x] REST API: `GET /tools` 返回聚合工具列表（含 serverName）
- [x] REST API: `POST /tools/call` 按 serverName 转发工具调用
- [x] 连接失败不阻塞其他 Server

### mcp-host

- [x] OpenAI 兼容 API 调用（支持自定义 base URL）
- [x] API Key 从环境变量 `OPENAI_API_KEY` 读取
- [x] 从 Client 获取工具列表并转换为 OpenAI function calling 格式
- [x] 多轮 function call 循环（大模型自主决策调用顺序）
- [x] 循环上限保护（可配置，默认 10 轮）
- [x] 超过最大轮数时返回优雅错误信息（不再抛异常）
- [x] 可配置 System prompt（`mcp.system.prompt`）
- [x] 流式输出 `/chat/stream` 端点（SSE 格式，工具调用静默执行，最终响应流式返回）
- [x] 消息历史管理（assistant + tool 消息追加）
- [x] 工具结果自动回传大模型
- [x] 全链路结构化日志

### 文档与工具

- [x] 设计文档（`.monkeycode/specs/mcp-java-demo/design.md`）
- [x] 实施计划（`.monkeycode/specs/mcp-java-demo/tasklist.md`）
- [x] README（架构、配置、编译、测试说明）
- [x] 启动脚本 `start.sh`

---

## 未实现

### 协议完善

- [ ] SSE / Streamable HTTP 传输（mcp-server JSON-RPC 目前是普通 HTTP）
- [ ] Server `resources/list` 和 `prompts/list` 能力
- [ ] MCP 心跳 / ping 机制
- [ ] JSON-RPC 批处理请求（batch）

### 安全

- [ ] `query_database` SQL 参数化查询（当前仅校验 SELECT + 危险关键字拦截）
- [ ] MCP Server 访问认证（API Key / Token）

### 健壮性

- [ ] mcp-client 定期健康检查已连接 Server
- [ ] mcp-server 查询错误分类（语法错误 vs 连接错误 vs 超时）
- [x] mcp-host OpenAI API 调用失败重试 + 超时

### 功能增强

- [x] mcp-client 工具名加 `{serverName}/` 前缀，防止跨 Server 冲突
- [ ] mcp-server 查询结果缓存（短时间相同查询不重复执行）
- [ ] mcp-server 支持 PostgreSQL / SQLite（通过配置切换驱动）
- [ ] mcp-host 对话历史保持（多轮对话上下文）
- [ ] mcp-host Web UI 交互界面

### 工程化

- [ ] 单元测试
- [ ] 集成测试（全链路）
- [ ] Dockerfile / Docker Compose 一键部署
- [ ] GitHub Actions CI（编译 + 测试）
- [ ] 版本号自动管理
