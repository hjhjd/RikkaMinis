# Android PRoot Shell 模型决策

## 决策

保留方案 B：每个聊天 session 使用一个持久 `/bin/sh`，不改为每次调用独立进程。

## 方案 A 评估

独立进程能删除 marker framing、环境残留和大部分回收竞态，但会破坏现有可观察契约：

- `cd`、`export` 和 Shell 函数无法跨调用延续；
- Agent 安装工具、启动本地服务后，后续调用的行为与当前版本不同；
- 每次重建完整 PRoot tracer 和 bind mount argv，启动成本明显增加；
- 已有会话级串行、超时终止、输出 framing 和安全回收测试需要整体替换。

因此当前整改不采用方案 A。若未来切换，必须作为破坏性工具语义变更单独发布。

## 方案 B 契约

- 同一 session 的命令严格串行，并共享 cwd、`export`、Shell 函数和后台任务状态；
- 不同 session 使用不同 Shell，不共享上述状态；
- `shell_execute` 只代表 Android PRoot，不接受 WS sandbox；
- WS 仅通过 `sandbox_dispatch`，其 payload 不承诺 Shell 语义；
- timeout/cancel 会终止完整持久 Shell，返回后旧 guest 命令不再运行；
- 下一次调用按需创建干净 Shell；
- `PRootToolProvider.resetSession()` 可显式重置 session Shell 状态。
