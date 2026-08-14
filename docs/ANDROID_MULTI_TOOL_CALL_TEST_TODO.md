# Android 多工具调用测试 TODO

## 目标

为“模型同一轮返回多个结构化工具调用”和“工具结果回传后继续下一轮调用”建立可重复的回归测试，覆盖 Provider 解析、调用顺序、ID/参数配对及界面数据展开。

## 施工清单

- [x] OpenAI Chat Completions：同一 SSE 响应按 index 交错流式返回两个 `tool_calls`，验证两个调用均完成且 ID、名称、参数不串线。
- [x] OpenAI 请求：验证启用工具时发送 `parallel_tool_calls=true`。
- [x] OpenAI Responses API：同一响应包含两个 `function_call` item，验证按 item ID 独立累积并完成。
- [x] Anthropic：同一消息连续返回两个 `tool_use` content block，验证两个调用均产生完整事件。
- [x] Gemini：同一 candidate 的 parts 中包含两个 `functionCall`，验证两个调用均产生开始与完成事件。
- [x] 对话块展开：验证同一 assistant message 中多个 `tool_use` 均保留原顺序并形成独立稳定 key；若现有 UI 构建器不适合 JVM 单测，先提取纯函数后测试。
- [x] 连续工具链协议闭环：使用三轮 MockWebServer 响应验证首轮多个调用结果全部序列化回传、第二轮继续调用、第三轮无工具调用时结束。
- [x] 明确执行层验收边界：本轮自动化覆盖 Provider 多调用解析、批量结果配对回传、跨轮继续与 UI 数据展开；`ChatViewModel` 对真实 Android/Room/工具副作用的整机联调归入设备集成测试，不为 JVM 测试改造生产架构。
- [x] 运行定向测试、完整 Android JVM 单测、`git diff --check`。

## 验收标准

1. 单轮至少两个工具调用不会丢失、覆盖或参数串线。
2. 每个 `tool_call_id` 与对应 `tool_result` 可一一配对。
3. UI 数据层为每个调用生成独立条目并保持流式到完成期间 key 稳定。
4. 工具结果进入历史后，agent loop 能发起下一轮模型请求。
5. 测试不依赖真实网络、API Key 或 Android 设备。


## 设备集成补充（非 JVM 阻塞项）

1. 使用支持 function calling 的模型要求同一轮读取两个互不相关的文件。
2. 确认界面同时出现两个独立工具块，状态依次进入运行和完成。
3. 要求模型根据两个结果继续调用第三个只读工具，并最终输出总结。
4. 确认停止、重试和会话重载后，工具块 ID、结果及顺序不变。

真实工具执行涉及 Android Context、Room、PRoot/WS 与外部副作用，放在设备集成层更合理；JVM 回归测试使用 MockWebServer，保持确定性且无需真实凭据。
