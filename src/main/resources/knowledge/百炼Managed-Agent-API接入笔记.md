# 百炼 Managed Agent API 接入笔记

本文记录 Java 服务接入阿里云百炼 Managed Agent 时的推荐调用方式、状态判断和实际遇到的问题，供后续开发与排查使用。

## 1. 基本概念

- Agent：百炼平台中已经发布的智能体配置。
- Session：一次可持续多轮交互的运行实例，绑定 Agent、运行环境和文件资源。
- Event：用户消息、模型回复、工具调用和状态变化等会话事件。
- Artifact：Agent 通过 `mark_artifacts` 明确标记的交付文件。
- File：通过文件 API 上传或由 Agent 生成的文件，使用 `file_id` 标识。

当前 Java SDK：

```xml
<dependency>
    <groupId>com.alibaba</groupId>
    <artifactId>dashscope-sdk-java</artifactId>
    <version>2.22.24</version>
</dependency>
```

Managed Agent Java API 要求 SDK 不低于 `2.22.24`。

## 2. 推荐调用链

### 2.1 首轮任务

1. 上传模板、项目数据等输入文件。
2. 轮询文件元数据，等待状态变为 `available`。
3. 创建 Session，并将输入文件挂载到 `/uploads` 目录。
4. 先建立 Session 的 SSE 事件流。
5. 发送简短用户指令，让 Agent 从挂载路径读取文件。
6. 持续消费 SSE，收集 `mark_artifacts` 产物。
7. 收到 `idle + end_turn` 后结束本轮。
8. 使用产物 `file_id` 下载文件。

### 2.2 同一 Session 的后续任务

Session 在 `idle + end_turn` 状态下可以继续发送普通消息，不需要重新创建 Session。

正文较长时不要把完整内容拼进文本消息。推荐方式：

1. 将正文编码为 UTF-8 文件并上传。
2. 等待文件状态变为 `available`。
3. 发送一条包含文本块和文件块的用户消息。
4. 文件块必须包含 `type=file`、`file_id` 和 `filename`。
5. 继续监听原 Session 的 SSE 事件。

文件内容块示例：

```json
{
  "type": "file",
  "file_id": "file_xxx",
  "filename": "招标文件人工定稿.md"
}
```

使用 SDK 构造消息时，可将文本块与文件块一起传给：

```java
ClientEvents.userMessage(contentBlocks)
```

## 3. Session 状态机

| 状态 | stop_reason | 含义 | 可执行操作 |
| --- | --- | --- | --- |
| `idle` | `null` | 新建会话，尚未运行 | 发送普通消息 |
| `idle` | `end_turn` | 当前轮正常结束 | 发送下一轮普通消息 |
| `idle` | `retries_exhausted` | 当前轮重试耗尽 | 可发送下一轮消息，但应记录本轮失败 |
| `idle` | `requires_action` | 等待工具审批 | 只能审批或中断，不能直接发送普通消息 |
| `running` | `null` | Agent 正在执行 | 继续监听或中断 |
| `terminated` | - | 不可恢复的终态 | 新建 Session |

不要把 `idle` 简单理解为“任务成功”。必须结合 `stop_reason` 判断。

## 4. SSE 调用规范

推荐顺序是先订阅 SSE，再发送事件，降低短任务事件遗漏的可能：

```java
try (AgentStudioEventStream stream = client.sessions().events()
        .stream(sessionId, timeoutMillis)) {
    client.sessions().events().send(
            sessionId, List.of(ClientEvents.userMessage(message)));
    for (Message event : stream) {
        // 解析事件并等待本轮结束
    }
}
```

需要处理的主要事件：

- `message`：Agent 文本消息。
- `tool_call`：工具调用请求。
- `tool_call_output`：工具执行结果，`mark_artifacts` 也从这里读取。
- `session_status`：Session 状态变化。
- `error`：运行期错误。

同一个 SSE 连接可能看到历史状态或上一轮事件。判断本轮结束时应结合事件时间，避免刚建立连接就把上一轮的 `idle + end_turn` 当成本轮结束。

## 5. 事件受理不等于开始执行

`POST /sessions/{session_id}/events` 返回 `200` 和事件数组，只表示消息已被服务端接收或回显，不表示 Agent 已经进入 `running`。

日志至少记录：

- `sessionId`
- `request_id`
- 服务端分配的用户事件 ID
- 当前轮接收的 SSE 事件数量
- 最终产物数量

如果控制台没有运行记录，应通过以下方式确认：

1. 查询 Session 的 `status`、`stop_reason` 和 `updated_at`。
2. 调用 Event List API 查询用户事件是否真正进入历史。
3. 使用日志中的用户事件 ID 与事件历史对照。

仅看到“事件已受理”日志不能证明 Agent 已经执行。

## 6. 文件上传规范

上传接口返回的初始状态通常是 `checking`，不能立即挂载或发送给 Agent。

常见状态：

- `checking`：审核或解析中，继续等待。
- `available`：可以挂载或作为消息附件发送。
- `rejected`：文件未通过审核。
- `type_rejected`：文件类型不支持。

建议所有输入文件共用一个截止时间，避免多个文件逐个等待导致总超时成倍增加。

不要只依据上传接口返回成功就创建 Session。文件仍为 `checking` 时创建会话，可能出现会话未创建、挂载失败或任务长时间无事件。

## 7. 产物识别与下载

系统提示词应明确要求 Agent 调用 `mark_artifacts`。业务系统只接受被明确标记的交付文件，不要从普通聊天文本、沙箱路径或临时链接猜测产物。

`mark_artifacts` 的工具输出示例：

```json
{
  "marked": [
    {
      "path": "/mnt/session/outputs/项目-招标文件初稿.md",
      "description": "招标文件初稿",
      "file_id": "file_xxx"
    }
  ],
  "failed": []
}
```

解析规则：

1. 仅处理 `type=tool_call_output`。
2. 工具名称必须为 `mark_artifacts`。
3. 从 `output.marked[]` 读取 `file_id` 和 `path`。
4. 使用 `path` 的最后一段作为文件名。
5. 按业务阶段校验扩展名和文件名关键词。

Managed Agent 文件存在保留期限。需要长期下载的业务产物应在生成后立即下载并转存 OSS，数据库只保存 OSS ObjectKey，不应长期依赖百炼 `file_id`。

## 8. 常见问题与原因

### 8.1 第二轮接口返回成功，但控制台没有事件

表现：发送接口返回 `request_id` 和一条受理记录，但 Session 的 `updated_at` 没变化，Event List 中也不存在该用户事件。

优先检查是否把完整长文档直接放进文本消息。长正文应上传成文件并通过文件内容块发送，文本消息只保留任务说明。

### 8.2 错把上一轮结束事件当成本轮结束

建立 SSE 后可能先收到已有的 `idle + end_turn`。应记录本轮发送时间，只接受不早于本轮开始时间的结束事件。

### 8.3 `mark_artifacts` 不是最后一个事件

产物标记完成后，Agent 仍可能继续输出消息或调用工具。不能在看到 `mark_artifacts` 后立即断开，应继续等待本轮 `session_status=idle` 且 `stop_reason=end_turn`。

### 8.4 云端已有文件，但本地提示未返回产物

检查本地是否只识别普通消息，或者过早结束 SSE。产物来自 `tool_call_output` 中 `mark_artifacts` 的 JSON 字符串，需要再次解析内部 `output`。

### 8.5 文件一直处于 `checking`

不要创建 Session 或发送附件。继续查询文件状态直到 `available`，超过统一截止时间后明确失败。不要无限等待，也不要用睡眠重试叠加多个超时窗口。

### 8.6 同一 Session 能否多轮使用

可以。`idle + end_turn` 是正常的可交互状态，可以发送下一轮普通消息。只有 `terminated` 是不可恢复终态；`requires_action` 必须先处理审批或中断。

### 8.7 是否必须传 `session_thread_id`

普通主 Agent 消息不需要。该字段主要用于多 Agent 场景下定向到具体子线程。没有明确的子线程路由需求时，不要从历史事件中猜测并强行传入。

## 9. 错误处理原则

- `error` 事件应结束当前业务任务，并记录平台返回的错误码和消息。
- `requires_action` 如果业务没有审批能力，应明确失败，不要继续发送普通消息。
- 文件缺失、扩展名不符或产物数量不足时，业务任务不得标记完成。
- 下载或 OSS 保存任一产物失败时，审核任务应保持失败，不能只保存其中一个结果后标记成功。
- 不把工具局部失败一律当作 Session 失败；Agent 可能处理工具错误并继续完成任务。

## 10. 安全与配置

- API Key、AccessKey 和环境标识不要写入公开文档、日志或 Git。
- API Key 从本地配置或环境变量读取。
- 下载接口使用 Bearer Token，不把 Token 拼进 URL。
- 日志只记录资源 ID、状态和请求 ID，不打印文件正文及鉴权信息。
- 业务产物转存 OSS 后，通过后端鉴权下载接口返回，不直接暴露永久公网地址。

## 11. 官方文档

- [Managed Agents API 概览](https://help.aliyun.com/zh/model-studio/managed-agents/)
- [Session 与 Event API](https://help.aliyun.com/zh/model-studio/session-api/)
- [Managed Agents 会话操作](https://help.aliyun.com/zh/model-studio/managed-agents-session-operations)
- [Managed Agents 会话事件流](https://help.aliyun.com/zh/model-studio/managed-agents-event-stream)
- [发送 Event](https://help.aliyun.com/en/model-studio/event-post)
- [订阅 SSE Event Stream](https://help.aliyun.com/en/model-studio/event-sse-stream)
