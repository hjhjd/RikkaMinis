# Android 流式输出顺滑度整改 TODO

## 目标

消除当前“成批落字 + 逐词瀑布淡入”的卡顿观感，同时保留长文本、复杂 Markdown 和低端设备上的防 ANR/防 GC 保护。

## 施工原则

- 网络层和 Provider SSE 解析不动；问题集中在展示调度与动画。
- 文本只保留一个实际生效的发布节奏：Agent Loop 负责合并 token，side-channel 不再二次扣留已经合并的文本。
- 工具、思考和结构变化继续受 side-channel 保护，避免高频事件冲击主线程。
- 不恢复逐 token 重组，不引入 `animateContentSize`。
- 每一步补单元测试，并执行 Android JVM 测试、编译检查、`git diff --check`。

## TODO

### P0：去掉瀑布式动画

- [x] 将逐词错峰淡入改为“新增尾部整批淡入”。
- [x] 淡入时长由 350ms 降至约 120ms，取消 300ms stagger。
- [x] 大批量追加直接显示，避免积压动画追赶文本。
- [x] 将 FadeController 时钟改为可注入，补确定性单元测试。

### P0：统一文本发布节奏

- [x] 抽取唯一的流式文本 cadence 函数，避免两套长度分档漂移。
- [x] 缩短普通 prose 的刷新周期；长文本仍保留渐进降频。
- [x] Agent Loop 合并后的文本进入 side-channel 时立即发布，不再被第二层 throttle 扣留。
- [x] 保留 side-channel 对 thinking/tool 非文本更新的 trailing flush。

### P1：清理渲染层重复节流

- [x] 移除 legacy `StreamingMarkdownText` 内部的第二次延迟采样，统一信任 ViewModel cadence。
- [x] 将 FlatChat 展示采样从 80ms 调整到 50ms，减少额外量化延迟。
- [x] 更新过时注释，明确每层职责。

### P1：测试与验收

- [x] cadence 边界值测试。
- [x] FadeController：整批单范围、硬重置、大批跳过、动画结束测试。
- [x] 执行相关 JVM 单元测试。
- [x] 执行 Android Kotlin 编译。
- [x] 检查 `git diff --check` 和最终仓库状态。

## 验收标准

- 短回复文本约 80ms 一批，不再经历 VM 二次等待。
- 新增文字整批在约 120ms 内完成淡入，不出现逐词向下游追赶。
- 超大增量无动画，直接稳定展示。
- 长文本仍按长度降低发布频率，不能退化为逐 token Markdown 解析。
- 工具状态结构变化继续即时展示，思考流不会无节制刷新 UI。
