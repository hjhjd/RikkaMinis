# Android 网络层与 SSE 整改 TODO

## 目标

修复流式连接被截断后误判成功、Gemini 伪造正常结束、高频日志阻塞读取及工具参数 O(n²) 复制等问题，并统一三个 Provider 的 SSE 语义。

## 原则

- 只有协议明确终态才算成功；异常 EOF 统一映射为可重试错误。
- SSE 读取必须在 IO 调度器，不依赖调用者线程。
- 网络热路径不记录正文、思考或完整工具参数。
- 保留长推理所需的高 idle timeout，但单独限制响应头与首事件等待。
- 使用共享解析器，避免 OpenAI、Anthropic、Gemini 行为继续漂移。

## TODO

### P0：协议正确性与隐私
- [x] 新增共享 SSE event reader，支持 `data:` 可选空格、多行 data、注释和空行分帧。
- [x] Anthropic 必须收到 `message_delta/message_stop` 正常终态，否则 EOF 抛可重试错误。
- [x] Gemini 必须收到 finishReason，删除 EOF 时伪造 `end_turn`。
- [x] OpenAI 必须收到 `[DONE]` 或 Responses 明确完成事件，否则 EOF 抛可重试错误。
- [x] 删除逐事件 RAW SSE 正文日志和逐 token 诊断日志。

### P1：网络执行与超时
- [x] 三个 Provider 的阻塞请求/读取显式运行于 `Dispatchers.IO`。
- [x] Anthropic/Gemini 增加 30 秒响应头 watchdog。
- [x] 三个 Provider 增加首个 SSE 事件 watchdog，避免 200 headers 后永久无 body。
- [x] 保留现有连接池、取消链和长 idle read timeout。

### P1：工具参数性能
- [x] `ToolInputDelta` 改为真正增量，不再由 Provider 每片复制累计字符串。
- [x] 在 ChatViewModel 唯一累积工具参数，保留 UI 快照和诊断 ring 行为。
- [x] ToolCallComplete 继续携带权威最终 JSON。

### P1：测试与验收
- [x] 共享 reader：可选空格、多行 data、注释测试。
- [x] 三个 Provider：正文后异常 EOF 必须抛 `TransientError`。
- [x] Gemini：空/非法 200 body 不得成功。
- [x] Provider：无空格 `data:` 可正常解析。
- [x] 工具参数 delta 保持增量语义。
- [x] 执行 Provider 测试、完整 JVM 测试和 Kotlin 编译。
- [x] 执行 `git diff --check` 并复核仓库状态。

## 验收标准

- 部分正文断流不会被持久化为正常完整回复。
- Gemini 不再把空流伪装成 `end_turn`。
- SSE 热路径不输出用户正文或 thinking。
- 大工具参数不再每个网络片段复制完整累计字符串。
- 普通流式首字速度不退化，取消请求可及时关闭 socket。
