# agent-service 开发说明

## 项目定位

本项目是基于 Java 17、Spring Boot 3.0.2 和 AgentScope 的文档审查实验项目，核心代码集中在 `com.example.agentservice.agile`。当前主要流程包括：

- `DocAgent`：读取规则文件，提取采购需求指标并执行需求文件评审。
- `DocConcurrencyAgent`：使用 `qwen-doc-turbo` 并发执行投标文件专项审查，再生成汇总报告。
- `PDFReview`、`LongExtractBeforeReview`：PDF 上传、长文档事实抽取和报告汇总实验。
- `ImmBeforeReview`：使用 OSS/IMM 将 PDF 转成图片，再调用多模态模型审查。
- `ImmService` / `ImmServiceImpl`：提供 OSS/IMM PDF 转逐页图片服务，业务流程通过构造器注入使用。
- `BidControlReview`：使用 `qwen-doc-turbo` 审查招标文件控标风险，再使用 `qwen3.7-plus` 汇总为 Markdown 表格。

## 凭证配置

禁止在 Java、YAML、提示词、README 或测试数据中写入真实的 DashScope API Key、OSS AccessKey、AccessKey Secret 或带签名的 OSS URL。

提交到 Git 的配置文件是 `src/main/resources/application.yml`，不包含真实凭证。个人开发配置放在被 Git 忽略的 `src/main/resources/application-local.yml`，可复制 `application-local.yml.example` 后填写。

DashScope API Key 只从 `application-local.yml` 读取。其他非敏感配置的优先级从高到低为：

1. JVM 系统属性。
2. 操作系统环境变量。
3. `application-local.yml`。
4. `application.yml` 中的非敏感默认值。

独立运行各个 `main` 方法时，配置由 `AgentServiceConfig` 读取；Spring Boot 启动时，`application.yml` 会自动加载。常用环境变量如下：

| 配置 | 环境变量 |
| --- | --- |
| OSS Endpoint | `ALIYUN_OSS_ENDPOINT` |
| OSS Region | `ALIYUN_OSS_REGION` |
| OSS Bucket | `ALIYUN_OSS_BUCKET` |
| OSS AccessKey ID | `ALIYUN_OSS_ACCESS_KEY_ID` |
| OSS AccessKey Secret | `ALIYUN_OSS_ACCESS_KEY_SECRET` |
| IMM Endpoint | `ALIYUN_IMM_ENDPOINT` |
| IMM Project | `ALIYUN_IMM_PROJECT` |
| 规则文件 URL | `RULE_FILE_URL` |
| 需求文件 URL | `REQUIREMENT_FILE_URL` |

带 `Expires`、`Signature`、`OSSAccessKeyId` 的 URL 属于临时凭证或敏感测试数据，不要提交到仓库。测试文件应通过 main 参数传入，或放在本地配置文件中。

## 模型和 Formatter

- `qwen-doc-turbo`：通过 `QwenDocDashScopeChatFormatter` 传递 `doc_url`，负责文档读取、抽取和事实审查。
- `qwen-long`：通过 `QwenLongChatFormatter` 传递文件 ID，适合长文档实验。
- PDF 多模态流程使用 `PdfDashScopeChatFormatter` 或 IMM 转换后的图片 URL。
- `qwen3.7-plus`、`qwen3.8-max`：通过 OpenAI 兼容接口生成文本汇总报告。

IMM 转换统一调用 `ImmService.convertPdfsToImages`，返回包含文档名、页码和签名图片 URL 的 `ImmImagePage` 列表。不要在审查 Agent 中直接创建 OSS 或 IMM 客户端；新增流程应注入 `ImmService`。独立运行 `ImmBeforeReview` 时，main 会启动非 Web Spring 上下文并获取 `ImmService` Bean。

模型提示词应明确事实来源、输出格式和证据要求。法律、合规类结论必须要求模型给出法律文件和条款依据，并在无法核验时标记人工复核，不得由 Java 代码编造法律结论。

## 运行方式

各实验流程保留独立 `main` 方法，通常在 IntelliJ IDEA 中直接运行对应类。运行前在 `application-local.yml` 配置 DashScope API Key，其他配置可使用环境变量或本地 YAML。

编译命令：

```text
mvn -DskipTests compile
```

当前实验类可能会调用真实模型和 OSS/IMM 服务，编译不会触发网络审查；不要为了验证编译而自动运行 `main`。

## 修改约定

- 复用现有 Formatter、AgentScope Model 和提示词组织方式，避免重复实现协议转换。
- 业务代码只负责流程编排、模型调用、结果解析和输出；不要加入与提示词重复的 Java 风险判断兜底。
- 需要新增密钥时，先增加 `application.yml` 占位符、`AgentServiceConfig` 读取方法和 `.gitignore` 规则，再写调用代码。
- 真实文件 URL、模型原始响应和 token 日志只用于本地调试，不要提交到仓库。
- 修改模型时确认模型 ID、地域、流式要求、联网搜索能力和 Formatter 是否匹配。

## 提交前检查

提交前至少执行：

```text
rg -n --hidden -g '!target/**' -g '!\.git/**' "sk-[A-Za-z0-9]+|LTAI[A-Za-z0-9]+|OSSAccessKeyId[=]|access-key-secret" .
mvn -DskipTests compile
```

第一条命令不应在 Java、配置示例以外发现真实密钥或签名 URL；配置示例只能使用空值或环境变量占位符。
