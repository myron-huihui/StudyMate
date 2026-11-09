# StudyMate — AI 智能学习计划平台

纯 Java 后端，全部能力通过 **REST + SSE** 对外提供，接口文档用 **Swagger UI**，验收以 **curl 脚本** 完成。

## 技术栈

| 层 | 技术 |
|---|---|
| 语言/框架 | Java 17 · Spring Boot 3.4.5 · Spring Cloud 2024.0.2 |
| LLM | 百炼 `deepseek-v4.1-flash`（OpenAI 兼容协议）· Spring AI + LangChain4j 双策略 |
| Agent | LangGraph4j 条件/循环工作流（生成 → 审查 → 重写） |
| 向量检索 | PostgreSQL + pgvector（1024 维，HNSW 索引） |
| 全文检索 | Elasticsearch 8.x |
| 混合召回 | RRF（Reciprocal Rank Fusion）融合向量 + 全文 |
| 关系数据 | MySQL 8 · MyBatis-Plus |
| 缓存 | Redis（短期记忆 + 热点） |
| 知识库 | Tika 解析 → 分块 → 向量化 → pgVector + ES 双写 |
| MCP | Spring AI MCP Server（WebMVC + SSE）+ LangChain4j MCP Client（反向接入） |
| 网关 | Spring Cloud Gateway（JWT + Redis Lua 限流 + Trace） |
| 认证 | 自实现 OAuth2 授权码 + PKCE（JWT HS256 / 刷新令牌轮换 / family 吊销） |
| 流控 | Sentinel（QPS 5，blockHandler 与 fallback 分离） |
| 可观测 | OpenTelemetry + Micrometer + Prometheus + Jaeger + Grafana |
| 接口文档 | SpringDoc (Swagger UI) |

## 模块结构

```
studymate/
├── studymate-parent        # 父 POM（Spring Boot 3.4.5）
├── studymate-common        # 实体 / DTO / 枚举 / 异常 / 统一响应
├── studymate-dal           # MySQL · Redis · pgVector · ES 数据访问
├── studymate-ai            # LLM 调用 · 目标理解 · 计划生成 · 任务拆解 · RAG 混合检索
├── studymate-kb            # 文档清洗 · 分块 · 向量化 · 入库
├── studymate-monitor       # OpenTelemetry · Micrometer · Prometheus
├── studymate-api           # REST + SSE + Swagger 主应用（:18080）
├── studymate-gateway       # 网关：鉴权 + 限流 + OAuth2 PKCE（:19000）
├── studymate-mcp           # Spring AI MCP Server（:18090）
├── sql/                    # 建表脚本 · pgvector 初始化 · ES 索引模板
├── curl/                   # 验收脚本 verify.sh
├── docker-compose.yml      # 一键启动基础设施
└── prometheus.yml
```

## 快速启动

### 1. 启动基础设施

```bash
docker compose up -d
```

### 2. 初始化数据库

```bash
# MySQL
mysql -h 127.0.0.1 -u root -proot < sql/schema.sql

# PostgreSQL + pgvector
psql -U postgres -d studymate -f sql/pgvector_init.sql

# Elasticsearch 索引（可选，首次写入时自动创建）
curl -X PUT "http://localhost:9200/studymate_materials" \
  -H "Content-Type: application/json" \
  -d @sql/es_index_template.json
```

**可选依赖降级**：MySQL 与 Redis 为启动必需；PostgreSQL/pgVector 与 Elasticsearch 未就绪时，
向量/全文检索与知识库入库自动降级（记录告警），不影响 API、MCP、网关启动和其他接口。
健康检查据此分组：

| 端点 | 含义 |
|---|---|
| `GET /actuator/health/liveness` | 进程存活（`ping`） |
| `GET /actuator/health/readiness` | 就绪（仅要求 MySQL + Redis） |
| `GET /actuator/health` | 全量（含可选依赖，PG/ES 缺失时为 503） |

### 3. 设置环境变量

```bash
export BAILIAN_API_KEY="sk-xxxx"          # 百炼 DashScope API Key
export BAILIAN_BASE_URL="https://dashscope.aliyuncs.com/compatible-mode"
export JWT_SECRET="至少32字节的随机串"     # API 与 Gateway 必须一致
# 可选：Langfuse 全量上报
export LANGFUSE_ENABLED=true LANGFUSE_PUBLIC_KEY=pk-xxx LANGFUSE_SECRET_KEY=sk-xxx
```

### 4. 启动各服务

```bash
# 主应用
cd studymate && mvn -pl studymate-api spring-boot:run &

# 网关
cd studymate && mvn -pl studymate-gateway spring-boot:run &

# MCP
cd studymate && mvn -pl studymate-mcp spring-boot:run &
```

打包运行：

```bash
mvn -DskipTests package
java -jar studymate-api/target/studymate-api-1.0.0-SNAPSHOT-exec.jar
java -jar studymate-gateway/target/studymate-gateway-1.0.0-SNAPSHOT.jar
java -jar studymate-mcp/target/studymate-mcp-1.0.0-SNAPSHOT.jar
```

> `studymate-api` 配置了 `classifier=exec`：可执行 fat jar 为 `*-exec.jar`，
> 普通 jar（不带后缀）保留为普通库，供 `studymate-mcp` 以依赖方式复用 AI / DAL / 业务服务，
> 两者并存互不覆盖。网关与 MCP 不对外提供库依赖，直接产出可执行 jar。

### 5. 验收

```bash
chmod +x curl/verify.sh
BAILIAN_API_KEY=sk-xxxx bash curl/verify.sh
```

## 核心 API

> 除注册/登录/OAuth2/健康检查外，所有接口都需要 `Authorization: Bearer <access_token>`。

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/v1/auth/register` | 注册（BCrypt 存储） |
| POST | `/api/v1/auth/login` | 登录，返回 access/refresh token |
| POST | `/api/v1/auth/refresh` | 刷新令牌（轮换，复用即吊销 family） |
| POST | `/oauth2/authorize` | 授权码 + PKCE（S256） |
| POST | `/oauth2/token` | `authorization_code` / `refresh_token` |
| POST | `/api/v1/plans` | 创建学习计划（Agent 工作流） |
| GET | `/api/v1/plans/{id}` | 获取计划详情（校验归属） |
| PATCH | `/api/v1/plans/{id}/status` | 更新计划状态 |
| GET | `/api/v1/tasks/today?planId=` | 今日任务（Redis 缓存） |
| GET | `/api/v1/tasks/plan/{planId}` | 计划全部任务 |
| GET | `/api/v1/tasks/day/{dayIndex}?planId=` | 指定天任务 |
| POST | `/api/v1/tasks/{taskId}/checkin` | 打卡（唯一键幂等） |
| GET | `/api/v1/tasks/progress/{planId}` | 进度摘要 |
| POST | `/api/v1/materials/recommend?goal=x` | 资料推荐（RAG 混合检索） |
| POST | `/api/v1/stream/plans` | SSE 流式创建计划（stage/delta/review/result/done/error） |
| POST | `/api/v1/memory/chat` | 带 memoryId 的多轮对话 |
| GET | `/api/v1/memory/{memoryId}/window` | Redis 记忆窗口 |
| GET | `/api/v1/memory/{memoryId}/history` | MySQL 全量历史 |
| POST | `/api/v1/kb/scan` / `/api/v1/kb/ingest` | 知识库离线入库 |
| GET | `/api/v1/kb/jobs` | 入库任务（可断点续跑） |
| GET | `/api/v1/mcp/servers` | 外部 MCP Server 列表 |
| GET | `/swagger-ui/index.html` | Swagger UI |

## MCP 工具

| 工具名 | 说明 |
|---|---|
| `create_study_plan` | 创建 AI 学习计划 |
| `get_plan` | 获取计划详情 |
| `get_daily_task` | 获取指定天任务 |
| `check_in_task` | 打卡 |
| `get_progress` | 进度摘要 |
| `recommend_materials` | 资料推荐 |

反向接入：在 `ai.mcp.servers` 中配置外部 MCP Server 后，可通过
`GET /api/v1/mcp/servers/{name}/tools` 与 `POST /api/v1/mcp/servers/{name}/tools/{tool}` 调用第三方工具，
调用指标为 `studymate_mcp_client_calls` / `studymate_mcp_client_latency`。

Spring AI MCP Server 默认以 SSE 暴露（`/mcp/sse` 握手、`/mcp/message` 回传），客户端配置示例：

```yaml
ai:
  mcp:
    enabled: true
    servers:
      - name: studymate-self
        url: http://localhost:18090/mcp/sse
        type: sse
        timeout-seconds: 30
```

## 可观测

- **Metrics**: `GET /actuator/prometheus`（Prometheus 抓取）
- **Tracing**: OpenTelemetry → Jaeger（`http://localhost:16686`）
- **Dashboard**: Grafana（`http://localhost:3000`，admin/admin）

## 架构数据流

```
用户 → Gateway(:19000) → API(:18080)
                          │
                          ├─ 目标理解 (LLM)
                          ├─ 计划生成 (LLM)
                          ├─ 任务拆解 (LLM)
                          ├─ 资料推荐 (RAG: pgVector + ES → RRF → LLM)
                          ├─ 打卡/进度 (MySQL + Redis)
                          │
              KB 离线: 文档 → Tika → 分块 → 向量化 → pgVector + ES
              MCP(:18090): 暴露上述能力为 MCP tools
```

## License

[MIT](LICENSE) © 2026 myron-huihui
