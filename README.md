# shana

一个以 Java 为核心的 AI 应用平台，第一阶段目标是跑通工程骨架，后续逐步演进为群聊 AI 助手：
接入云端 / 本地 / 自建微调模型，管理群聊上下文与长期记忆，支持 RAG、Agent 与工具调用。

当前已完成：工程骨架（分层结构、统一响应、异常处理、健康检查、本地基础设施编排）、
OpenAI 兼容协议的模型适配层、`POST /api/v1/chat` 问答链路、基于 PostgreSQL 的会话持久化、
可切换的 Redis 上下文、长期记忆（滚动摘要 + 关键信息抽取）、
**RAG**（文档切分 + Embedding + pgvector 向量检索），
以及 **Agent / 工具调用**（模型自主决定查数据，多轮往返后作答）。
**尚未实现** 群成员画像与多 Agent 协作。

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
| 向量存储 | pgvector | 0.7+ | PostgreSQL 扩展，HNSW 索引 + 余弦距离 |
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
| `shana-model` | 统一模型调用入口 `LlmClient`、`EmbeddingClient`、消息/请求/响应模型、`LlmClientRegistry` 路由 | common |
| `shana-rag` | RAG 领域层：文档切分、知识库编排、向量存储端口 | common, model |
| `shana-core` | 核心业务接口与领域模型，编排记忆/知识库，把"调模型 + 调工具"交给 Agent | common, model, memory, rag, agent |
| `shana-infrastructure` | 数据访问（MyBatis-Plus + PostgreSQL）、Flyway 迁移、`ConversationMemory` 与 `VectorStore` 的实现、需要真实资源的工具（群聊统计） | common, core, memory, rag, agent |
| `shana-memory` | 群聊上下文与长期记忆管理 | common, model |
| `shana-agent` | Agent 循环、工具扩展点与注册表、多轮工具调用编排 | common, model |
| `shana-api` | Controller、VO、全局异常处理 | common, core, rag |
| `shana-application` | 装配所有模块，提供启动类与配置，唯一的可执行 jar | 全部模块 |

依赖图（`──▶` 表示"依赖"，箭头指向被依赖方）：

```
shana-common            （基础类型，无业务依赖）

shana-model ──▶ common

shana-memory ──▶ model, common
shana-agent  ──▶ model, common

shana-core   ──▶ model, memory, rag, agent

shana-infrastructure ──▶ core, memory, rag, agent   （由此传递依赖 model、common）
shana-api            ──▶ core, rag   （由此传递依赖 model、common）

shana-application    ──▶ api, core, infrastructure, model, memory, agent
```

整体是一条单向链，不存在循环依赖；可用 `mvn dependency:tree -Dincludes=com.shana` 验证。

约束：

- `common` 不依赖任何业务模块，因此可以被所有模块引用；
- `core` 只定义接口与业务编排，不依赖 `infrastructure`（端口与实现分离）；
- `api` 只依赖 `core` 与 `common`，不认识 `infrastructure`、`model` 的具体实现；
- `application` 是所有模块的汇合点，只做装配与配置，不写业务代码。

`shana-memory` / `shana-agent` 与 `shana-core` 之间：`core` 单向依赖两者
（问答链路要读上下文，也要把"调模型 + 调工具"这一段交给 Agent），
而 `memory`、`agent` 都只依赖 `common` + `model`，不反向依赖 `core`，因此仍是单向链。

`agent` 里没有一行数据库或 HTTP 代码：它只定义"工具长什么样"和"怎么循环"，
真正的工具实现放在 `infrastructure`——需要真实资源的东西属于资源所在的那一层。

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
│       ├── LlmClient.java / EmbeddingClient.java   # 对话与向量化两个独立入口
│       ├── LlmClientRegistry.java
│       ├── ChatMessage.java / ChatRequest.java / ChatResponse.java
│       ├── ToolCall.java / ToolSpec.java             # 工具调用的协议模型
│       └── adapter/            # OpenAI 兼容协议适配（对话 + embeddings + tools）
│
├── shana-rag/
│   └── src/main/java/com/shana/rag/
│       ├── KnowledgeBase.java / KnowledgeBaseImpl.java   # 知识库编排
│       ├── TextSplitter.java                             # 文档切分
│       ├── KnowledgePromptRenderer.java                  # 检索结果渲染成 system 消息
│       └── store/              # VectorStore 端口、检索结果、待入库片段
│
├── shana-core/
│   └── src/main/java/com/shana/core/
│       ├── chat/               # 问答编排：ChatService、ChatReply
│       ├── memory/             # 长期记忆编排：摘要生成、结果解析、prompt 渲染
│       └── health/             # 核心服务示例：SystemHealthService
│
├── shana-infrastructure/
│   └── src/main/java/com/shana/infrastructure/
│       ├── config/             # MyBatis-Plus 配置、知识库装配
│       ├── agent/              # 工具实现：群聊发言统计
│       └── persistence/        # 上下文 / 长期记忆 / 知识库实现，实体与 Mapper
│
├── shana-memory/
│   └── src/main/java/com/shana/memory/
│       ├── ConversationMemory.java          # 短期上下文端口（窗口）
│       ├── LongTermMemory.java              # 长期记忆端口（摘要 + 事实）
│       ├── ConversationArchive.java         # 历史归档端口（按游标扫描原文）
│       ├── MemorySnapshot.java / MemoryFact.java / FactKind.java
│       └── InMemoryConversationMemory.java
│
├── shana-agent/
│   └── src/main/java/com/shana/agent/
│       ├── Agent.java / AgentRequest.java / AgentResult.java
│       ├── ToolCallingAgent.java        # 多轮工具编排
│       └── tool/
│           ├── Tool.java                # 扩展点
│           ├── ToolContext.java         # 会话上下文（模型无法伪造）
│           └── ToolRegistry.java
│
├── shana-api/
│   └── src/main/java/com/shana/api/
│       ├── controller/         # HealthController、ChatController、KnowledgeController
│       ├── dto/ vo/            # 请求体与响应体
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

**向量化模型要单独配一套 key。** DeepSeek 只提供 `/chat/completions`，没有 `/embeddings`，
所以 RAG 用的 embedding 必须指向另一家（或本地 vLLM）：

```bash
# 例：硅基流动（bge-m3，1024 维）
export SHANA_EMBEDDING_BASE_URL=https://api.siliconflow.cn/v1
export SHANA_EMBEDDING_MODEL=BAAI/bge-m3
export SHANA_EMBEDDING_API_KEY=sk-xxx

# 例：阿里百炼（1024 维）
# export SHANA_EMBEDDING_BASE_URL=https://dashscope.aliyuncs.com/compatible-mode/v1
# export SHANA_EMBEDDING_MODEL=text-embedding-v3
```

不配这一组变量时，问答、记忆全都照常工作，只是知识库接口返回
`503 未配置向量化模型`——RAG 是可选能力，不参与启动判定。

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
> 长期记忆拆成三段测：`SummaryDraftParserTest`（模型返回的各种不听话）、
> `MemoryPromptRendererTest`（拼进 prompt 的样子）、`ConversationSummarizerTest`（何时触发、并发丢弃、失败不抛）。
> `@Async` 在真实容器里的线程切换没有自动化覆盖，靠手动联调观察日志。

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
- `shana-infrastructure/src/main/resources/db/migration/V3__create_long_term_memory.sql`（`conversation_summary` + `memory_fact`，见下方「长期记忆」）
- `shana-infrastructure/src/main/resources/db/migration/V4__create_rag_tables.sql`（`rag_document` + `rag_chunk`，含 pgvector 扩展，见下方「RAG」）

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

> 长期记忆（摘要 + 事实）只在 `jdbc` 下可用：`redis` / `memory` 模式没有完整历史，
> 相关 Bean 不会装配，问答链路照常工作，只是没有跨窗口记忆。

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

## 长期记忆：对话摘要与关键信息

上面解决的是"最近 20 条"，这一段解决"上周聊过什么"。两层记忆分工明确：

| 层 | 存什么 | 生命周期 | 谁在用 |
| --- | --- | --- | --- |
| 短期上下文 | 最近 `max-messages` 条原文 | 随窗口滚动 | 每次对话，逐字接话 |
| 长期记忆 | 滚动摘要 + 关键信息条目 | 长期累积 | 每次对话拼进 prompt；离线分析 |

### 两张表

```sql
conversation_summary                 -- 一个群一版，主键即 conversation_id
├── summary             TEXT         -- 滚动摘要，300 字以内
└── covered_message_id  BIGINT       -- 已消化到哪条消息（游标 + 乐观锁）

memory_fact                          -- 追加式事实条目
├── kind      VARCHAR(16)            -- PERSON / PREFERENCE / DECISION / FACT
├── subject   VARCHAR(64)            -- 主体，通常是人名；无主体时为空串
├── content   VARCHAR(200)           -- 一句话事实
└── source_message_id BIGINT         -- 来自哪条消息，便于回查

UNIQUE (conversation_id, kind, subject, content)   -- 重复抽取自动被挡掉
```

**为什么摘要之外还要事实表**：摘要是有损压缩，滚动几次之后早期细节必然消失。
关键信息是追加式的、不参与滚动，所以"张三在学 Flink"不会因为后面聊了几百句就蒸发。

### 触发流程

```
POST /api/v1/chat
  → 写消息、读上下文、调模型、回写回复      （主链路，用户已拿到回复）
  → 提交后台任务（@Async，不阻塞、失败不影响回复）
        ├─ 未消化消息 < trigger-messages(20)？  → 直接返回
        ├─ 取游标之后的 batch-size(50) 条原文
        ├─ 调模型：已有摘要 + 新对话 → {summary, facts}
        ├─ 乐观更新游标（WHERE covered_message_id = 旧值）
        └─ 成功才写事实；游标没推进说明别的实例先做了，本次结果丢弃
```

### 四个设计取舍

1. **增量而不是全量重算。** 每次只喂"旧摘要 + 新一批消息"。全量重算更准，但成本随对话量线性涨，
   活跃群跑两周就喂不进上下文了。
2. **游标即乐观锁。** 多实例同时摘要同一个群时，只有把 `covered_message_id` 推进成功的那一个写库。
   代价是偶尔白跑一次模型调用，远好过旧摘要覆盖新摘要。
3. **失败绝不影响主链路。** 模型超时、返回坏 JSON、队列满，都只记日志。
   游标没动，下次对话会重新触发，不会丢数据。
4. **只依赖持久化存储。** Redis / 内存模式下没有完整历史，摘要不成立，
   相关 Bean 不会装配，链路照常工作（只是没有跨窗口记忆）。这是刻意的约束，不是遗漏。

### prompt 里的位置

```
[system] 你是 Shana……
[system] 【群聊长期记忆】摘要：…… / 已知信息：- 张三：在学 Flink
[user]   那 checkpoint 呢
[assistant] ……
```

长期记忆单独占一条 system，不拼进人设：摘要每次都会变，混在一起会让"助手是谁"
和"助手记得什么"缠成一团，也不好按需整段丢弃。事实最多取最新 10 条，避免稀释相关性。

### 验证

```sql
SELECT conversation_id, left(summary, 60), covered_message_id FROM conversation_summary;
SELECT kind, subject, content FROM memory_fact WHERE conversation_id = 'group-1' ORDER BY id DESC;
```

日志里会看到：`群聊 group-1 的长期记忆已更新：覆盖到消息 42，新增事实 3 条`。

相关配置：

| 配置 | 默认 | 说明 |
| --- | --- | --- |
| `shana.memory.summary.trigger-messages` | 20 | 攒够多少条未消化消息才触发 |
| `shana.memory.summary.batch-size` | 50 | 单次喂给模型的原文上限 |

---

## RAG：知识库与向量检索

前面两层记忆解决"聊过什么"，RAG 解决"资料里怎么写的"。三者互不替代：

| 层 | 来源 | 用途 |
| --- | --- | --- |
| 短期上下文 | 群聊消息（窗口内） | 接住刚才的话 |
| 长期记忆 | 群聊消息（滚动摘要 + 事实） | 记住这个群的历史 |
| **知识库** | **人上传的文档** | **回答"手册里怎么说"** |

### 三层结构

| 环节 | 实现 | 位置 |
| --- | --- | --- |
| 文档切分 | 段落 → 句子 → 硬切，块间重叠 | `shana-rag` / `TextSplitter` |
| 向量化 | OpenAI 兼容 `/embeddings`，分批 + 顺序还原 + 维度校验 | `shana-model` / `OpenAiCompatibleEmbeddingClient` |
| 存储与检索 | pgvector `vector(N)` + HNSW 余弦索引 | `shana-infrastructure` / `PgVectorStore` |

### 两张表

```sql
rag_document                                              -- 一篇文档一行
├── title        VARCHAR(200)
├── source       VARCHAR(500)                             -- 文件路径 / URL / 群号
├── content_hash CHAR(64)   UNIQUE                        -- 正文 SHA-256，上传幂等靠它
└── chunk_count  INT

rag_chunk                                                 -- 一个片段一行
├── document_id BIGINT  REFERENCES rag_document(id) ON DELETE CASCADE
├── chunk_index INT                                        -- 文档内顺序
├── content     TEXT
└── embedding   vector(1024)  NOT NULL                     -- 维度由配置注入

CREATE INDEX ... USING hnsw (embedding vector_cosine_ops);
```

三个刻意的取舍：

1. **不存原文**。原文没有任何查询用途，切片已覆盖全部内容；需要原文时从来源重新导入即可，
   内容指纹能保证不会重复入库。
2. **不用 IVFFlat**。IVFFlat 的聚类数依赖数据量，知识库从零开始时建索引几乎无效；
   HNSW 边写边查都能用。
3. **向量列不走 ORM**。`embedding` 用 `CAST(? AS vector)` 传字符串字面量，
   不引入 pgvector 的 JDBC 类型扩展——避开了 MyBatis-Plus 类型处理器的一堆坑。

### 入库流程

```
POST /api/v1/knowledge/documents
  → 校验 title / content
  → 确认向量化能力可用（缺配置直接 400，不白查一次库）
  → 正文 SHA-256 → 查重（命中直接返回已有 documentId，一次 embedding 都不调）
  → 切分：段落 → 句子 → 超长硬切，块间按 overlapChars 重叠
  → 分批向量化（默认 16 条/批）
  → 事务内写 rag_document + 全部 rag_chunk
```

并发上传同一份文档时，两个请求可能都过了查重。真正的防线是 `content_hash` 唯一索引 +
`INSERT ... ON CONFLICT DO NOTHING`：后到的那个会拿到 `created=false`，
既不重复写片段，也不报错——用唯一约束而不是"先查后插"，
是因为 PG 事务里一旦抛出约束冲突就进入 aborted 状态，catch 之后连查询都做不了。

### 检索与注入

```
POST /api/v1/chat
  → system 人设
  → 【群聊长期记忆】（若有）
  → 【参考资料】（若有命中且相似度达标）
  → 最近 20 条历史
  → 调模型
```

三个刻意的克制：

- **用当前这一句检索**，不是整段对话。整段会把之前的话题一起带进来，越聊越偏。
- **没有达标片段就完全不注入**，不留一句"参考资料：无"。空标题会诱导模型去猜。
- **检索失败只记日志**。它依赖外部嵌入服务，挂了不该让用户连话都说不了。

阈值 `shana.rag.min-score` 默认 0.35，是偏宽松的起点：总是召回不到就调低，
总是塞进无关内容就调高。`shana.rag.enabled=false` 可以整体关掉注入，
但入库/检索接口仍然可用——便于单独确认召回质量，再决定要不要接进问答。

### 接口

```bash
# 上传文档
curl -X POST http://localhost:8080/api/v1/knowledge/documents \
  -H "Content-Type: application/json" \
  -d '{"title":"Flink 运维手册","source":"docs/flink.md","content":"checkpoint 需要先配置 state backend……"}'
# → {"code":200,"data":{"documentId":1,"chunkCount":7,"duplicate":false}}

# 单独验证召回（不经过模型）
curl "http://localhost:8080/api/v1/knowledge/search?q=checkpoint%20怎么配&topK=4"
# → [{"documentId":1,"documentTitle":"Flink 运维手册","content":"...","score":0.83}]

# 删除文档（片段靠外键级联删除）
curl -X DELETE http://localhost:8080/api/v1/knowledge/documents/1
```

`/search` 的存在是为了可观测：没有它，RAG 出问题时只能靠看模型回答猜
是"没检索到"还是"检索到了但模型没用"。

### pgvector 安装

V4 迁移会执行 `CREATE EXTENSION vector`，**没有这个扩展应用会启动失败**。

**推荐：用 compose 里的镜像**（`pgvector/pgvector:pg17`，自带扩展）：

```bash
docker compose up -d postgres
```

如果本机 5432 已经有一个原生 PostgreSQL（没有 pgvector），两种做法：

```bash
# 做法一：让 compose 的实例换个端口，应用连 5433
DB_PORT=5433 docker compose up -d postgres
DB_PORT=5433 mvn -pl shana-application spring-boot:run

# 做法二：给原生实例装扩展（Linux，需要编译）
#   git clone --branch v0.7.4 https://github.com/pgvector/pgvector.git
#   cd pgvector && make && make install
#   psql -d shana -c "CREATE EXTENSION vector;"
```

`CREATE EXTENSION` 需要超级用户权限。compose 里 `POSTGRES_USER` 创建的就是超级用户，
所以默认可用；托管数据库（RDS 等）通常需要单独授权。

> 临时跳过：`FLYWAY_ENABLED=false` 能让应用起来，但知识库接口会因缺表而失败，
> 只适合临时调别的东西。

### 换嵌入模型时会发生什么

`vector(N)` 的维度在迁移时写死（来自 `SHANA_EMBEDDING_DIM`）。换模型意味着维度可能变，
**已经应用过的迁移不会重跑**，所以必须手工处理：

```sql
-- 1. 删掉旧向量数据（不同模型的向量不可比，混着用检索结果会乱）
TRUNCATE rag_chunk;
-- 2. 改列类型（维度必须一致，否则要 DROP COLUMN 后重建）
ALTER TABLE rag_chunk ALTER COLUMN embedding TYPE vector(1536);
-- 3. 重建索引
DROP INDEX idx_rag_chunk_embedding;
CREATE INDEX idx_rag_chunk_embedding ON rag_chunk USING hnsw (embedding vector_cosine_ops);
```

然后改 `SHANA_EMBEDDING_DIM` 与 `SHANA_EMBEDDING_MODEL`，重新上传文档。
想避免这一步，可以在建库时就用最大的维度，但这会浪费存储且没有实际收益——
换模型本来就需要重算全部向量。

### 验证

```sql
-- 文档与片段规模
SELECT id, title, chunk_count, created_at FROM rag_document ORDER BY id DESC LIMIT 10;
SELECT document_id, count(*) FROM rag_chunk GROUP BY document_id;

-- 直接看某条向量的前几维（确认写入的不是全零）
SELECT id, left(content, 30), (embedding::real[])[1:3] FROM rag_chunk LIMIT 5;
```

日志里会看到：`知识库文档已入库: documentId=1, title=Flink 运维手册, chunks=7`。

相关配置：

| 配置 | 默认 | 说明 |
| --- | --- | --- |
| `shana.embedding.*` | 见 `application.yml` | 向量化模型地址、key、模型名、维度、批量 |
| `shana.rag.enabled` | true | 关掉后问答不检索，接口仍可用 |
| `shana.rag.chunk.max-chars` | 800 | 单块上限（字符数，中文≈token） |
| `shana.rag.chunk.overlap-chars` | 120 | 相邻块重叠 |
| `shana.rag.chunk.min-chars` | 50 | 尾块低于此值并入前一块 |
| `shana.rag.top-k` | 4 | 一次注入的片段数 |
| `shana.rag.min-score` | 0.35 | 余弦相似度下限 |

---

## Agent：工具调用

模型看不到数据库，也统计不了群里谁发言最多。**工具就是给它装的手和眼**：它自己判断要不要查、查什么，
代码只负责把结果递回去。

### 一次问答里发生了什么

```
POST /api/v1/chat
  → 组装 prompt（人设 + 长期记忆 + 检索资料 + 历史）
  → Agent 第 1 轮：模型返回 tool_calls
      → 执行 group_message_stats(conversationId=当前群)
      → 把 assistant(tool_calls) + tool(结果) 追加进消息列表
  → Agent 第 2 轮：模型基于查询结果作答
  → 只把最终回复写进对话记忆
```

单轮和多轮是**同一段循环**，差别只在转了几圈。不分开写是有原因的：
两条路径的错误处理、消息拼装规则稍有出入，就会变成"单轮好好的，一旦需要查两次就报错"。

### 三个关键约束

**① 会话 ID 不从模型参数里读。**
`ToolContext(conversationId, senderId)` 由调用方传入。如果让模型在 `arguments` 里填 `conversationId`，
它就可能（幻觉或被诱导）去查另一个群——一个只该读本群的工具，变成跨群泄露的入口。

**② 工具失败不中断对话。**
执行异常转成一条文本回填给模型，由它决定怎么向用户解释。抛异常就整段失败，等于模型一调错工具
用户就收不到回复。同理，模型编出不存在的工具时，把可用清单回给它，下一轮往往就能调对。

**③ 轮次有上限，到上限要能收尾。**
`shana.agent.max-rounds`（默认 4）到顶后，**撤掉工具再问一次**，逼模型用已有结果作答。
直接抛错会让用户白等一场却什么都没拿到。

### 工具扩展点

```java
public interface Tool {
    String name();                                   // 全局唯一，^[a-zA-Z0-9_-]{1,64}$
    String description();                            // 写清"什么时候该用"，这是模型唯一的判断依据
    default String parametersSchema() { ... }        // 参数 JSON Schema，构造期校验
    String execute(ToolContext context, String arguments);
}
```

新增工具只需实现接口并注册为 Bean，`ToolRegistry` 自动收集。构造期会做三件事：重名检测、
名字合法性校验、schema 可解析校验——**写错就启动失败**，而不是等到对话时才收到一句上游的 400。

内置工具：

| 工具 | 作用 | 参数 |
| --- | --- | --- |
| `group_message_stats` | 按 `sender_id` 统计当前群最近 N 天每位成员的发言条数 | `days`（1~90，默认 7） |

对外壳和参数的信任度是刻意区分的：`days` 越界会被夹到 1~90（模型给 1000 天也照查 90 天），
参数不是 JSON 就回落默认值。**参数不完美不该让整次回答失败**。

### 不落库的东西

`assistant(tool_calls)` 与 `tool` 消息**只在一次问答的内存里存在**。
原因是它们必须成对出现：tool 消息脱离上一条带 `tool_calls` 的 assistant 就毫无意义，
而对话记忆按条读取、窗口还会截断，单独存下来只会在下次请求时被上游拒绝
（`messages with role 'tool' must be a response to a preceding message with 'tool_calls'`）。

### 配置

| 配置 | 默认 | 说明 |
| --- | --- | --- |
| `shana.agent.enabled` | true | 关掉后模型拿不到任何工具，退化成纯对话。排查"是不是工具带偏了"时用 |
| `shana.agent.max-rounds` | 4 | 一次问答内最多几轮"模型 → 工具 → 模型" |

### 验证

```bash
# 问一个必须查数据的问题
curl -X POST http://localhost:8080/api/v1/chat -H "Content-Type: application/json" \
  -d '{"conversationId":"group-1","senderId":"u-1001","message":"这个群最近一周谁发言最多？"}'
```

预期 `data.toolsUsed` 为 `["group_message_stats"]`，日志里能看到：

```
本次回答调用了工具: conversationId=group-1, tools=[group_message_stats], rounds=2
```

`toolsUsed` 为空说明模型没查就直接答了。先检查工具说明是否写清楚"什么时候该用"——
模型不调工具，九成是描述让它没意识到该调。

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
| PostgreSQL（含 pgvector） | `localhost:5432` / 库名 `shana` | `.env` 中的 `DB_USER` / `DB_PASSWORD` |
| Redis | `localhost:6379` | 密码为 `.env` 中的 `REDIS_PASSWORD` |

> postgres 服务用的是 `pgvector/pgvector:pg17` 镜像，不是官方的 `postgres:17`。
> 换成官方镜像后 V4 迁移会因缺少 `vector` 扩展而失败，应用起不来。

应用侧读取的环境变量（含默认值，未设置时使用 `localhost` 与开发默认账号）：

```
DB_HOST DB_PORT DB_NAME DB_USER DB_PASSWORD
REDIS_HOST REDIS_PORT REDIS_PASSWORD
SHANA_LLM_API_KEY SHANA_LLM_BASE_URL SHANA_LLM_MODEL
SHANA_EMBEDDING_API_KEY SHANA_EMBEDDING_BASE_URL SHANA_EMBEDDING_MODEL SHANA_EMBEDDING_DIM
SHANA_MEMORY_STORE SHANA_MEMORY_TTL
SHANA_RAG_ENABLED SHANA_RAG_TOP_K SHANA_RAG_MIN_SCORE
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
    "completionTokens": 18,
    "toolsUsed": []
  }
}
```

`toolsUsed` 是这次回答实际调用过的工具名（按执行顺序）。问"群里谁最活跃"而模型真的查了库，
这里会是 `["group_message_stats"]`；为空说明它只是凭上下文作答。
排查"AI 答得不对"时先看这个字段——很多时候问题不在模型，而在于它压根没去查。

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

**4. 启动报 `extension "vector" is not available`**

当前 PostgreSQL 实例没装 pgvector。V4 迁移里的 `CREATE EXTENSION vector` 会直接失败，
应用起不来。三个选择：

```bash
# a. 用 compose 里的 pgvector 镜像（已在 5432 上跑着原生 PG 时换个端口）
DB_PORT=5433 docker compose up -d postgres
DB_PORT=5433 mvn -pl shana-application spring-boot:run

# b. 给原生实例装扩展（Linux 需编译，见「RAG → pgvector 安装」）
# c. 临时跳过迁移（知识库不可用，其余功能正常）
FLYWAY_ENABLED=false mvn -pl shana-application spring-boot:run
```

**5. 问答正常，但知识库接口返回 `未配置向量化模型`**

`shana.embedding.api-key` 为空。注意它不是 `SHANA_LLM_API_KEY`——DeepSeek 只有
`/chat/completions`，没有 `/embeddings`，两组 key 通常来自不同服务商。

**6. 上传文档报 `向量维度与配置不一致`**

`shana.embedding.dimension` 与模型实际输出的维度不符（如用 1536 维的
`text-embedding-3-small` 却配了 1024）。同时要改 `SHANA_EMBEDDING_DIM` 并重建向量表，
否则写库时会被 `vector(N)` 拒绝。

**7. 检索总是返回空**

先去掉阈值直接看分布：

```bash
curl "http://localhost:8080/api/v1/knowledge/search?q=你的问题&topK=10"
```

如果 `score` 普遍低于 0.35，说明阈值偏高，调 `SHANA_RAG_MIN_SCORE`；
如果一条都没有，检查文档是否真的入库（`SELECT count(*) FROM rag_chunk`）。

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
5. ~~**长期记忆**：对话摘要 + 关键信息抽取~~ ✅ 已完成（滚动摘要 + 追加式事实，异步、乐观并发）；
   待补：按相关性检索事实、摘要的定期重压缩（防止反复滚动后失真累积）。
6. ~~**RAG**：文档切分、Embedding、向量检索（PostgreSQL pgvector 或独立向量库）~~
   ✅ 已完成（TextSplitter + OpenAI 兼容 /embeddings + pgvector HNSW，检索结果独立注入 prompt）；
   待补：PDF/Word 解析、混合检索（向量 + 关键词）、rerank、多租户知识库隔离。
7. ~~**Agent / Tool Calling**：基于 `Tool` 扩展点实现单轮工具调用，再演进到多轮编排~~
   ✅ 已完成（`ToolCallingAgent` 多轮循环 + 轮次上限兜底 + 首个真实工具「群聊发言统计」）；
   待补：一次性并行调用、工具级超时、多 Agent 协作。
8. **群聊分析与月度总结**：批量任务 + 报表输出。
9. **流式输出**：把 `POST /api/v1/chat` 扩展为 SSE，边生成边返回，避免长时间等待。
