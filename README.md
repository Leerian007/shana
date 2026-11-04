# shana

一个以 Java 为核心的 AI 应用平台，第一阶段目标是跑通工程骨架，后续逐步演进为群聊 AI 助手：
接入云端 / 本地 / 自建微调模型，管理群聊上下文与长期记忆，支持 RAG、Agent 与工具调用。

当前已完成：工程骨架（分层结构、统一响应、异常处理、健康检查、本地基础设施编排）、
OpenAI 兼容协议的模型适配层、`POST /api/v1/chat` 问答链路、基于 PostgreSQL 的会话持久化、
以及可切换的 Redis 上下文。
**尚未实现**长期记忆、RAG、Agent 循环与群成员画像。

---

## 技术栈

| 分类 | 选型 | 版本 | 说明 |
| --- | --- | --- | --- |
| 语言 | Java | 21 (LTS) | 使用 record、switch 模式匹配等特性简化 DTO |
| 框架 | Spring Boot | 3.5.16 | 3.x 最新稳定版，兼容 JDK 21 |
| 构建 | Maven | 3.9+ | 多模块聚合工程，版本统一在父 POM 管理 |
| Web | Spring Web (MVC) | 由 BOM 管理 | REST 接口 |
| 校验 | Spring Validation | 由 BOM 管理 | Bean Validation 3.0 |
| 监控 | Spring Boot Actuator | 由 BOM 管理 | `/actuator/health`、`/actuator/info` |
| 数据访问 | MyBatis-Plus | 3.5.18 | `mybatis-plus-spring-boot3-starter` |
| 数据库 | PostgreSQL | 17 | 本地由 Docker Compose 提供 |
| 缓存 | Redis | 7 | 会话上下文热窗口（多实例共享），可切换启用 |
| 容器 | Docker / Docker Compose | 任意较新版本 | 仅用于本地中间件 |
| 测试 | JUnit 5 + Mockito + AssertJ | 由 BOM 管理 | 单元测试 + MockMvc 集成测试 |
| 工具 | Lombok | 由 BOM 管理 | 仅用于 `@Slf4j`、`@Getter`、`@RequiredArgsConstructor` |

版本策略：除 MyBatis-Plus 外，所有第三方依赖版本由 `spring-boot-dependencies` BOM 统一管理，
父 POM 中不重复声明版本号，避免版本漂移。

---

## 模块职责

| 模块 | 职责 | 依赖 |
| --- | --- | --- |
| `shana-common` | 统一响应 `Result`、错误码 `ErrorCode`、业务异常 `BusinessException` | 无 |
| `shana-model` | 统一模型调用入口 `LlmClient`、消息/请求/响应模型、`LlmClientRegistry` 路由 | common |
| `shana-core` | 核心业务接口与领域模型，编排模型/记忆/工具 | common, model, memory |
| `shana-infrastructure` | 数据访问（MyBatis-Plus + PostgreSQL）、Flyway 迁移、`ConversationMemory` 的数据库/Redis 实现 | common, core, memory |
| `shana-memory` | 群聊上下文与长期记忆管理 | common, model |
| `shana-agent` | Agent 循环、工具调用、任务编排（当前仅工具扩展点） | common, model |
| `shana-api` | Controller、VO、全局异常处理 | common, core |
| `shana-application` | 装配所有模块，提供启动类与配置，唯一的可执行 jar | 全部模块 |

依赖图（`──▶` 表示"依赖"，箭头指向被依赖方）：

```
shana-common            （基础类型，无业务依赖）

shana-model ──▶ common

shana-core  ──▶ model, memory, common

shana-infrastructure ──▶ core, memory   （由此传递依赖 model、common）
shana-memory         ──▶ model, common
shana-agent          ──▶ model, common
shana-api            ──▶ core   （由此传递依赖 model、common）

shana-application    ──▶ api, core, infrastructure, model, memory, agent
```

整体是一条单向链，不存在循环依赖；可用 `mvn dependency:tree -Dincludes=com.shana` 验证。

约束：

- `common` 不依赖任何业务模块，因此可以被所有模块引用；
- `core` 只定义接口与业务编排，不依赖 `infrastructure`（端口与实现分离）；
- `api` 只依赖 `core` 与 `common`，不认识 `infrastructure`、`model` 的具体实现；
- `application` 是所有模块的汇合点，只做装配与配置，不写业务代码。

`shana-memory` / `shana-agent` 与 `shana-core` 之间：`core` 单向依赖 `memory`
（问答链路需要读取上下文），`memory` 只依赖 `common` + `model`，不反向依赖 `core`，
因此仍是单向链。`agent` 目前没有编译期依赖，等实现 Agent 循环时再按同样方向接入。

---

## 目录结构

```
shana/
├── pom.xml                     # 父 POM：模块、依赖版本、编译配置
├── README.md
├── .gitignore
├── .env.example                # 环境变量样例
├── docker-compose.yml          # PostgreSQL + Redis 本地开发编排
│
├── shana-common/
│   └── src/main/java/com/shana/common/
│       ├── api/                # Result、ErrorCode
│       └── exception/          # BusinessException
│
├── shana-model/
│   └── src/main/java/com/shana/model/
│       ├── LlmClient.java      # 统一模型调用接口
│       ├── LlmClientRegistry.java
│       ├── ChatMessage.java / ChatRequest.java / ChatResponse.java
│       └── adapter/            # 适配实现（当前为空，仅存放 package-info 说明）
│
├── shana-core/
│   └── src/main/java/com/shana/core/
│       └── health/             # 核心服务示例：SystemHealthService
│
├── shana-infrastructure/
│   └── src/main/java/com/shana/infrastructure/
│       └── config/MybatisPlusConfig.java
│
├── shana-memory/
│   └── src/main/java/com/shana/memory/
│       ├── ConversationMemory.java
│       └── InMemoryConversationMemory.java
│
├── shana-agent/
│   └── src/main/java/com/shana/agent/tool/
│       ├── Tool.java
│       └── ToolRegistry.java
│
├── shana-api/
│   └── src/main/java/com/shana/api/
│       ├── controller/HealthController.java
│       ├── vo/HealthVO.java
│       └── handler/GlobalExceptionHandler.java
│
└── shana-application/
    ├── src/main/java/com/shana/application/ShanaApplication.java
    ├── src/main/resources/application.yml
    ├── src/main/resources/application-dev.yml
    └── src/test/java/.../HealthApiIntegrationTest.java
```

---

## 环境要求

- JDK 21（`java -version` 需为 21）
- Maven 3.9+
- Docker + Docker Compose（仅启动本地数据库/缓存时需要，不启动也能跑应用）

确认 Maven 使用的 JDK：

```bash
mvn -v
# Java version: 21.x  ← 必须为 21；若为旧版本，请设置 JAVA_HOME
```

Windows 下设置：

```bash
set JAVA_HOME=C:\path\to\jdk-21
```

macOS / Linux：

```bash
export JAVA_HOME=/path/to/jdk-21
```

---

## 本地启动

### 1. 编译

```bash
# 全量编译 + 安装到本地仓库（跳过多模块依赖解析问题）
mvn clean install

# 只编译不测试
mvn clean install -DskipTests

# 只跑测试
mvn test
```

### 2. 配置模型密钥（可选）

没有 key 时应用照常启动，只是 `/api/v1/chat` 返回 `503`。要用真实模型，二选一：

```bash
# 方式一：环境变量（推荐，CI/生产都用这个）
export SHANA_LLM_API_KEY=sk-xxx

# 方式二：本地密钥文件（已加入 .gitignore，不会被提交）
# 在 shana-application/src/main/resources/ 下新建 application-secret.yml：
# shana:
#   llm:
#     openai-compatible:
#       api-key: sk-xxx
```

`application.yml` 通过 `spring.config.import: optional:classpath:application-secret.yml`
加载该文件，文件不存在时自动忽略，不影响其他人启动。

> ⚠️ 不要把 key 写进 `application.yml` 的默认值里（`${VAR:sk-xxx}`），
> 那等于把密钥写进 Git。密钥文件、`.env`、环境变量都在 `.gitignore` 保护范围内。

### 3. 启动应用（需要数据库）

```bash
# 方式一：Maven 启动
mvn -pl shana-application -am spring-boot:run

# 方式二：运行构建产物
java -jar shana-application/target/shana-application-0.1.0-SNAPSHOT.jar
```

默认端口 `8080`，默认 profile 为 `dev`。

启动时需要能连上 PostgreSQL：Flyway 会在启动阶段执行建表脚本。
连接信息通过环境变量传入（默认 `localhost:5432/shana`，账号 `shana`）：

```bash
export DB_HOST=localhost DB_PORT=5432 DB_NAME=shana
export DB_USER=shana DB_PASSWORD=你的密码
```

数据库还没准备好时，有三种选择：

```bash
# 1) 用 Docker 起一个（见下方 Docker Compose 章节）
# 2) 先建库建账号：CREATE DATABASE shana OWNER shana;
# 3) 临时不连库跑起来（上下文退回进程内，重启即丢）
SHANA_MEMORY_STORE=memory FLYWAY_ENABLED=false mvn -pl shana-application spring-boot:run
```

### 4. 验证

```bash
curl http://localhost:8080/api/v1/health
```

```json
{
  "code": 200,
  "message": "success",
  "data": {
    "status": "UP",
    "application": "shana",
    "version": "0.1.0-SNAPSHOT",
    "activeProfiles": ["dev"],
    "javaVersion": "21.0.2+13-58",
    "uptimeSeconds": 3
  }
}
```

Actuator：

```bash
curl http://localhost:8080/actuator/health
```

---

## 测试

分三层，边界刻意划清：

| 层次 | 位置 | 是否联网 | 说明 |
| --- | --- | --- | --- |
| 单元测试 | `shana-model` / `shana-core` / `shana-memory` / `shana-agent` / `shana-infrastructure` | 否 | 纯函数 + Mockito，毫秒级 |
| 集成测试 | `shana-application` | 否 | 启动 Spring 容器 + MockMvc，`ChatService` 可被 mock |
| 真实调用冒烟 | `LiveChatSmokeTest` | **是** | 真实 key、真实 token，默认跳过 |

> 集成测试统一跑在 `test` profile 下（`@ActiveProfiles("test")`），
> 该 profile 把会话存储切回内存实现并关闭 Flyway，
> 所以 `mvn test` 不需要本机有 PostgreSQL，也不需要 Redis。
> 数据库行为由 `JdbcConversationMemoryTest`（Mock 验证生成的 SQL 语义）、
> Redis 行为由 `RedisConversationMemoryTest`（Mock 验证发出的命令序列）与
> `ChatMessageCodecTest`（编解码往返）覆盖；
> 手动联调（启动应用 + 发消息 + 查表 / `redis-cli LRANGE`）负责剩下的部分。
> `RedisMemoryWiringTest` 单独验证 `store=redis` 时装配的是 Redis 实现，
> 不会和 JDBC、内存实现同时在场。

日常只需：

```bash
mvn clean install          # 单元 + 集成测试，全部离线
```

### 真实模型冒烟测试

```bash
# Windows Git Bash / Linux / macOS
SHANA_RUN_LIVE_TEST=1 mvn -pl shana-application test -Dtest=LiveChatSmokeTest
```

```bash
# Windows CMD
set SHANA_RUN_LIVE_TEST=1
mvn -pl shana-application test -Dtest=LiveChatSmokeTest
```

它会连发两轮对话（第一轮让模型记住"紫罗兰"，第二轮问它记的是什么），断言：

- 适配器已装配（条件装配生效）；
- 两轮都有非空回复；
- 第二轮 `promptTokens` 大于第一轮 —— 上下文真的被带上了，这是比"看回复内容"更硬的证据。

实测输出：

```
第一轮 → content=收到   | promptTokens=54 completionTokens=1
第二轮 → content=紫罗兰 | promptTokens=70 completionTokens=2
```

---

## 数据持久化

会话上下文默认存 PostgreSQL，进程重启后对话不丢。

### 表结构

由 Flyway 在启动时自动创建，脚本位置：

- `shana-infrastructure/src/main/resources/db/migration/V1__create_chat_message.sql`
- `shana-infrastructure/src/main/resources/db/migration/V2__add_sender_id_to_chat_message.sql`

```
chat_message
├── id               BIGSERIAL     主键，自增
├── conversation_id  VARCHAR(64)   会话/群聊标识，由调用方提供
├── role             VARCHAR(16)   system / user / assistant / tool（小写）
├── sender_id        VARCHAR(64)   发送者标识（群成员 ID），可空；V2 新增
├── content          TEXT
└── created_at       TIMESTAMPTZ   默认 now()
索引：idx_chat_message_conversation_id_desc (conversation_id, id DESC)
      idx_chat_message_sender_id           (conversation_id, sender_id, id DESC)
```

三个刻意的设计取舍：

1. **没有单独的 conversation 表**。当前没有任何会话级属性要存（群名、人设 prompt 都还没做），
   先建一张只有主键的空表属于过度设计。等需要按群配置时再加迁移脚本。
2. **不删除超窗口的历史消息**。内存实现为了省内存会淘汰旧消息，
   但数据库里的全量历史是长期记忆、群聊月度总结的原料。
   窗口只限制"读多少条"（`LIMIT`），不删数据。
3. **`sender_id` 只为留存，不回填历史、不造默认值**。V2 之前写入的消息确实没采集过发送者，
   补一个假值会让"真的没传"和"就是这个人"混在一起，直接污染后续统计。
   分析时用 `sender_id IS NOT NULL` 或 `role = 'user'` 过滤即可。
   该字段不参与问答链路，拼 prompt 时不会被读取。

### 实现切换

`ConversationMemory` 有三个实现，通过 `shana.memory.store` 三选一（互斥装配）：

| 配置 | 实现 | 数据位置 | 场景 |
| --- | --- | --- | --- |
| `jdbc`（默认） | `JdbcConversationMemory` | PostgreSQL，**全量历史** | 正常运行、需要事后分析 |
| `redis` | `RedisConversationMemory` | Redis LIST，**只留窗口内最近 N 条** | 多实例部署、上下文热数据 |
| `memory` | `InMemoryConversationMemory` | 进程堆内存 | 单元测试、离线演示 |

```bash
# 用 Redis 存上下文（多实例共享）。此时消息不再落库；
# 本机没有 PostgreSQL 时加 FLYWAY_ENABLED=false 跳过迁移，否则启动期会连库失败
SHANA_MEMORY_STORE=redis FLYWAY_ENABLED=false mvn -pl shana-application spring-boot:run

# 完全离线：不连库也不连 Redis
SHANA_MEMORY_STORE=memory FLYWAY_ENABLED=false mvn -pl shana-application spring-boot:run
```

### Redis 会话上下文

启用 `store=redis` 后，每个群聊对应一个 key：`shana:chat:{conversationId}`，
结构是 LIST，新消息 `LPUSH` 到头部、`LTRIM` 裁到 `shana.memory.max-messages` 条，
每次写入刷新 `shana.memory.ttl`（默认 `24h`）。读的时候反向遍历即时间正序。

三个必须知道的取舍：

1. **Redis 只存热窗口，不是历史存档。** 会话冷下来就会过期，超出窗口的消息直接丢弃。
   需要长期保留、按人统计、做月度总结的全量消息仍然只有 `jdbc` 实现会落库。
   换句话说：**Redis 替代的是 `memory` 的"上下文"角色，不能替代 PostgreSQL 的"存档"角色**。
2. **不做静默降级。** Redis 连不上时 `append` / `history` 直接抛异常，接口返回 503。
   这比"悄悄丢掉上下文、助手答得莫名其妙"更容易排查。
3. **写入不是事务。** `LPUSH`、`LTRIM`、`EXPIRE` 三条命令顺序执行，并发时最坏多留几条，
   下一次 `LTRIM` 会裁掉；为此上 Lua 脚本不划算。TTL 放在最后，避免留下永不过期的 key。

> 想同时要"多实例共享"和"全量落库"，需要一个"Redis 缓存 + PostgreSQL 回源"的组合实现，
> 属于独立的一步，当前未做——`store` 仍是三选一，不是叠加。

### 验证数据是否落库

```sql
-- 查看各会话的消息条数
SELECT conversation_id, count(*) FROM chat_message GROUP BY conversation_id;

-- 查看某个群最近的对话
SELECT id, role, sender_id, content, created_at FROM chat_message
WHERE conversation_id = 'group-1' ORDER BY id DESC LIMIT 10;

-- 按成员统计发言量（sender_id 为空的记录是 V2 之前的历史数据或未传 senderId 的消息）
SELECT sender_id, count(*) AS messages
FROM chat_message
WHERE conversation_id = 'group-1' AND sender_id IS NOT NULL
GROUP BY sender_id ORDER BY messages DESC;
```

---

## Docker Compose（PostgreSQL / Redis）

```bash
# 1. 准备环境变量
cp .env.example .env
# 按需修改 .env 中的密码

# 2. 启动
docker compose up -d

# 3. 查看状态
docker compose ps

# 4. 查看日志
docker compose logs -f postgres

# 5. 停止（数据保留在 volume 中）
docker compose down

# 6. 停止并删除数据（谨慎使用）
docker compose down -v
```

连接信息：

| 服务 | 地址 | 账号 |
| --- | --- | --- |
| PostgreSQL | `localhost:5432` / 库名 `shana` | `.env` 中的 `DB_USER` / `DB_PASSWORD` |
| Redis | `localhost:6379` | 密码为 `.env` 中的 `REDIS_PASSWORD` |

应用侧读取的环境变量（含默认值，未设置时使用 `localhost` 与开发默认账号）：

```
DB_HOST DB_PORT DB_NAME DB_USER DB_PASSWORD
REDIS_HOST REDIS_PORT REDIS_PASSWORD
SERVER_PORT
```

> 注意：数据库已接入会话持久化，`management.health.db` 已开启，连不上时 Actuator 会如实报 `DOWN`。
> Redis 只在 `SHANA_MEMORY_STORE=redis` 时才是必需依赖，因此 `management.health.redis` 默认关闭，
> 避免默认的 `jdbc` 模式下本机没装 Redis 却报 `DOWN`。切到 Redis 模式后设
> `SHANA_REDIS_HEALTH=true` 打开这项检查。

---

## 接口约定

### 问答接口

`POST /api/v1/chat`：向指定会话发送一条用户消息，返回助手回复。

```bash
curl -X POST http://localhost:8080/api/v1/chat \
  -H "Content-Type: application/json" \
  -d '{"conversationId":"group-1","senderId":"u-1001","message":"你好"}'
```

```json
{
  "code": 200,
  "message": "success",
  "data": {
    "conversationId": "group-1",
    "content": "你好，我是 Shana。",
    "promptTokens": 42,
    "completionTokens": 18
  }
}
```

| 入参 | 校验 | 说明 |
| --- | --- | --- |
| `conversationId` | 非空、≤64 | 会话维度，用于隔离上下文 |
| `senderId` | 可选、≤64 | 发送者标识（群成员 ID）。**不参与生成回复**，只随消息落库供后续按人统计使用 |
| `message` | 非空、≤2000 | 用户消息正文 |

> `senderId` 是数据采集字段：它存在 `chat_message.sender_id` 里，拼 prompt 时不会被读取。
> 接入真实群聊时应当传入，否则该条消息无法归属到具体成员（历史数据不回填，也不造默认值）。

失败场景：参数不合法返回 `400`；没有可用模型适配器（未配置 api-key）或上游调用失败返回 `503`。
两种失败都使用统一响应结构，`data` 为 `null`。

### 统一响应

```json
{ "code": 200, "message": "success", "data": {} }
```

- `code` 与 HTTP 状态码保持一致（200 / 400 / 404 / 500 / 503），前端只需判断一处；
- 失败时 `data` 为 `null`，`message` 为可读的错误说明。

### 异常处理

`GlobalExceptionHandler` 覆盖：

| 异常 | HTTP | code | 说明 |
| --- | --- | --- | --- |
| `BusinessException` | 由错误码决定 | 错误码 | 可预期业务失败 |
| `MethodArgumentNotValidException` | 400 | 400 | `@RequestBody` 校验失败，返回字段级信息 |
| `ConstraintViolationException` | 400 | 400 | 方法参数约束校验失败 |
| `NoResourceFoundException` | 404 | 404 | 路径不存在 |
| 其它 `Exception` | 500 | 500 | 未预期异常，堆栈只写日志不外泄 |

---

## 模型扩展设计

业务代码只依赖 `LlmClient` 接口：

```java
public interface LlmClient {
    LlmProvider provider();          // 该实现负责的模型来源
    ChatResponse chat(ChatRequest request);
}
```

新增一个模型来源 = 新增一个实现类并注册为 Spring Bean，`LlmClientRegistry` 会自动按
`LlmProvider` 归类，业务代码无需改动：

| provider | 实现状态 | 典型场景 |
| --- | --- | --- |
| `OPENAI_COMPATIBLE` | ✅ `OpenAiCompatibleLlmClient` | OpenAI、DeepSeek、通义、vLLM |
| `OLLAMA` | 计划中 | 本地 Ollama |
| `LOCAL_FINE_TUNED` | 计划中 | 自建 LoRA/QLoRA 推理服务 |
| `CUSTOM` | 按需 | 其它第三方服务 |

### OpenAI 兼容适配器如何生效

```
shana.llm.provider                 → 选哪个 provider
shana.llm.openai-compatible.*      → 该 provider 的连接参数
```

| 配置项 | 环境变量 | 默认值 |
| --- | --- | --- |
| `shana.llm.provider` | `SHANA_LLM_PROVIDER` | `OPENAI_COMPATIBLE` |
| `shana.llm.openai-compatible.base-url` | `SHANA_LLM_BASE_URL` | `https://api.deepseek.com/v1` |
| `shana.llm.openai-compatible.model` | `SHANA_LLM_MODEL` | `deepseek-chat` |
| `shana.llm.openai-compatible.api-key` | `SHANA_LLM_API_KEY` | 空 |
| `shana.llm.openai-compatible.temperature` | `SHANA_LLM_TEMPERATURE` | `0.7` |

适配器按 `api-key` 是否为空**条件装配**：没有 key 时不注册 Bean，应用照常启动，
`/api/v1/chat` 返回 `503 没有可用的模型适配器`。配上 key 后无需改任何代码即自动生效：

```bash
export SHANA_LLM_API_KEY=sk-xxx
# 换厂商只改 base-url 与 model，例如本地 Ollama：
# export SHANA_LLM_BASE_URL=http://localhost:11434/v1
# export SHANA_LLM_MODEL=qwen2.5:7b
```

失败翻译规则（全部抛 `BusinessException(SERVICE_UNAVAILABLE)`，保留原始异常为 cause）：

| 上游现象 | 提示信息 |
| --- | --- |
| 4xx / 5xx | `模型服务返回错误状态 401` |
| 连不上 / 超时 | `连接模型服务失败，请检查网络或 base-url 配置` |
| 响应非合法 JSON | `调用模型服务失败: ...` |
| 空 `choices` 或空 `content` | `模型服务未返回任何回复` / `缺少内容` |

---

## 编码约定

- Controller 只做参数接收与响应转换，业务逻辑放在 `core` 的 Service 中；
- DTO（入参）/ VO（出参）/ Domain（领域）/ Entity（持久化对象）不混用；
- 入参使用 Bean Validation 注解校验，异常统一由 `@RestControllerAdvice` 处理；
- 只在有价值的位置写注释，解释"为什么"，不复述代码；
- 不提前抽象：没有第二个实现时不建接口，没有实际用途时不建空类。

---

## 常见问题

**1. 启动日志出现 `No MyBatis mapper was found in '[com.shana.application]' package`**

正常现象。当前阶段还没有 Mapper，MyBatis-Plus 的自动扫描提前提示了一次；
第一个 Mapper 落地后该警告自动消失。

**2. Windows 控制台中文日志乱码**

Java 19+ 的 `System.out` 使用终端编码，中文 Windows 默认 GBK。
若在 Git Bash / 部分 IDE 终端看到乱码，启动时加参数：

```bash
java -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 -jar shana-application/target/shana-application-0.1.0-SNAPSHOT.jar
```

**3. `mvn` 报 `invalid target release: 21`**

Maven 用错了 JDK（看 `mvn -v` 输出的 Java version）。设置 `JAVA_HOME` 指向 JDK 21 即可。

---

## 后续开发规划

1. ~~**模型适配器**：实现 `OpenAiCompatibleLlmClient`~~ ✅ 已完成（覆盖绝大多数云端与自建推理服务）；
   待补：超时、重试、`OllamaLlmClient`。
2. ~~**群聊对话链路**：`ChatService` 编排"组装 system prompt + 取上下文 + 调模型 + 回写上下文"，
   对外提供 `POST /api/v1/chat`~~ ✅ 已完成。
3. ~~**持久化**：建表 + Flyway 迁移 + MyBatis-Plus Mapper，把上下文与消息落到 PostgreSQL~~ ✅ 已完成。
4. ~~**Redis 会话状态**：多实例共享上下文，替换 `InMemoryConversationMemory`~~
   ✅ 已完成（`SHANA_MEMORY_STORE=redis`；只承载窗口内的热上下文，全量历史仍由 PostgreSQL 负责）；
   待补：Redis + PostgreSQL 的组合实现（缓存回源，兼顾共享与存档）。
5. **长期记忆**：对话摘要 + 关键信息抽取。
6. **RAG**：文档切分、Embedding、向量检索（PostgreSQL pgvector 或独立向量库）。
7. **Agent / Tool Calling**：基于 `Tool` 扩展点实现单轮工具调用，再演进到多轮编排。
8. **群聊分析与月度总结**：批量任务 + 报表输出。
9. **流式输出**：把 `POST /api/v1/chat` 扩展为 SSE，边生成边返回，避免长时间等待。
