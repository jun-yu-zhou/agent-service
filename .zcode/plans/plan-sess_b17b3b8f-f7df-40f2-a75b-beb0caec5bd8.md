## 目标
把 procurement 内的 Redis 访问抽成全仓通用组件，其他模块可按需注入复用；本次只做**结构化抽取**，不接入新消费方、不引入分布式锁、不动 agile/imm 的轮询状态。

## 现状（已核实）
- 全仓 Redis 使用面**仅一处**：`procurement/service/ProcurementTaskRedisStore.java`（`@Repository`，注入 `StringRedisTemplate` + `ObjectMapper` + `ProcurementDocumentProperties`）。
- 其中通用的部分：重试包装 `redis(operation, Supplier)` 与 `RETRY_DELAYS_MS`、JSON 序列化读写、空值返回 empty；业务相关的部分：两个 key 前缀常量、TTL 来源、`TenderTaskState`/`DocumentVersionSnapshot` 两个 record、按 versionNumber 排序。
- 调用方只有两个（都在 procurement 内）：`TenderDocumentTaskService`（大量方法）、`TenderDocumentArtifactService`（仅 `findVersion`）；测试 `TenderDocumentArtifactServiceTest` 用 mock，`Docx4jMarkdownDocxRendererIntegrationTest` 直接注入该 Store。
- 仓库约定：构造注入 + `private final`；中文一句话 javadoc；record 承载值对象；无 lombok；基础设施封装既有落位为 `imm/gateway`。

## 1. 新增 `com.example.agentservice.redis.RedisJsonStore`
路径：`src/main/java/com/example/agentservice/redis/RedisJsonStore.java`，`@Component`，构造注入 `StringRedisTemplate` 与 `ObjectMapper`，**不依赖任何业务配置类**。API：

- `void save(String key, Object value, Duration ttl)` —— JSON 序列化后带 TTL 写入；序列化失败抛 `IllegalStateException("无法序列化 Redis 值")`，ttl 为空抛 `IllegalArgumentException`。
- `<T> Optional<T> find(String key, Class<T> type)` —— 键不存在或空串返回 `Optional.empty()`；反序列化失败抛 `IllegalStateException("无法读取 Redis 值")`。
- `List<String> keys(String pattern)` —— 按模式列举键（保持现有 `keys` 语义：当前命名空间为单任务前缀、数据量极小；如后续命名空间变大再换 SCAN）。
- `<T> T execute(String operation, Supplier<T> command)` —— 从原 Store 上移的重试包装：捕获 `DataAccessException`，按 500ms/1s/2s 重试三次，仍失败则抛出，中断时恢复中断标记并抛出；日志沿用中文说明（Lettuce 断线自动重连，只重试可安全重复的读写）。

## 2. 改造 `ProcurementTaskRedisStore` 为薄领域层
保留：类名、`@Repository`、两个 key 前缀常量、TTL（继续读 `ProcurementDocumentProperties.getTaskTtlHours()`，含 `Math.max(1, …)` 下限）、两个 record、**全部 public 方法签名与语义**（`saveTender`/`findTender`/`saveVersion`/`findVersion`/`findVersions`，含按 versionNumber 升序排序）。
改动：内部 IO 全部委托给 `RedisJsonStore`（`save`/`find`/`keys`），删除已上移的 `RETRY_DELAYS_MS`、`redis(...)`、以及模板化的序列化/反序列化私有方法；构造依赖由 3 个减为 2 个（`RedisJsonStore`、`ProcurementDocumentProperties`）。
因为 public API 与 record 不变，`TenderDocumentTaskService`、`TenderDocumentArtifactService` 与既有测试**零改动**。

## 3. 新增单测 `RedisJsonStoreTest`
路径：`src/test/java/com/example/agentservice/redis/RedisJsonStoreTest.java`，风格对齐现有纯单测（无 Spring、手写 `mock(...)`，参考 `TenderDocumentArtifactServiceTest`），覆盖：save 写入含 TTL、find 命中与未命中返回 empty、keys 透传模式、execute 在 `DataAccessException` 后重试并最终抛出、序列化失败抛 `IllegalStateException`。

## 4. 文档（让其他模块知道有这个东西）
`AGENT.md` 增加一句话说明：Redis JSON 存取统一走 `com.example.agentservice.redis.RedisJsonStore`（key 与 TTL 由业务方决定），业务包不要直接注入 `StringRedisTemplate`。放在现有公共组件描述段落附近。

## 明确不做（本次范围外）
- 不改 `JsonSchemaPromptUtils` 的进程内缓存、不替换 `TenderDocumentTaskService` 的 `intern()` JVM 锁、不动 agile/imm 轮询状态；
- 不新增 `app.redis-store.*` 配置键、不改 `application.yml` / `application-local.yml.example`（TTL 由调用方传入）；Redis 连接键 `spring.data.redis.*` 保持不变。

## 验证
- `mvn -s <settings> -Dtest=RedisJsonStoreTest,TenderDocumentExportTest,TenderDocumentControllerTest,TenderDocumentArtifactServiceTest,TenderTemplateUploadServiceTest test`（用临时 JAVA_HOME 调用 G:\apache-maven-3.9.9），预期新增单测 + 既有 9 用例全绿。
- grep 确认全仓除新组件外无类注入 `StringRedisTemplate`（`ProcurementTaskRedisStore` 已改为依赖 `RedisJsonStore`）。
- 不提交 git（除非另行要求）。