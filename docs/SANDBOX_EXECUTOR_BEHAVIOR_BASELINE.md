# 沙箱执行行为基线与 Smoke Commands

## 行为矩阵

| 行为 | 本地 `shell_execute` | WS `sandbox_dispatch` |
|---|---|---|
| 执行位置 | Android PRoot | 显式稳定 sandbox ID |
| cwd/export/profile | 同 session 持久 `/bin/sh`；cwd/export 延续；不承诺 login profile | Android 不解释；由服务端指令集定义 |
| 后台任务 | 可延续到后续调用；重置/超时/取消会终止 Shell 进程组 | 由服务端定义；参考 DSL 取消完整进程组 |
| stdout/stderr | 合并为规范化 output | 流式事件保留服务端内容 |
| exit code | 本地结果保留；非零显示 | DSL 自行编码；参考 DSL附 `[exit=…]` |
| timeout/cancel | 停止整把持久 Shell，下一次重建 | 发送 cancel，不回退、不重放 |
| 截断 | Shell 128 KiB + 展示层限制，返回 truncated | 单事件 256 KiB、累计/最终 1 MiB，返回 truncated |
| 文件系统 | App/PRoot session 目录 | 与手机不共享；需 Resource/兼容 transfer |

## 自动化与设备复现

JVM 测试覆盖 marker/UTF-8、路径逃逸、输出限额、截断传播、稳定 mutex 与关闭回收。
设备级验证需覆盖：超时后副作用文件不再变化、空闲清理不终止长命令。

## Smoke Commands

```text
shell_execute: printf 'cwd=%s\n' "$PWD"; export MINIS_SMOKE=ok; cd /tmp
shell_execute: printf 'cwd=%s env=%s\n' "$PWD" "$MINIS_SMOKE"
shell_execute: printf out; printf err >&2; exit 7
shell_execute(timeout=1): trap '' TERM; while :; do echo x >> /tmp/minis-timeout-smoke; done
shell_execute: before=$(wc -c </tmp/minis-timeout-smoke); sleep 1; after=$(wc -c </tmp/minis-timeout-smoke); test "$before" = "$after"
sandbox_dispatch: help
sandbox_dispatch: status
sandbox_dispatch: exec\nprintf out\nprintf err >&2\nexit 7
sandbox_dispatch(timeout=1): exec\ntrap '' TERM\nwhile :; do :; done
```

## 阶段 7 统一展示契约

PRoot 与 WS 继续使用独立工具和执行协议，但现统一转换为 `ToolInvocationEvent`，并由
`ChatViewModel.consumeInvocationEvents` 消费。两者共享 75 ms UI 刷新节流、50,000 字符
有界预览尾部、单终态约束以及 timedOut/cancelled/truncated/sandboxName 展示字段。

- PRoot：结构化提供 exitCode 和 durationMs；sandboxId/name 为 `proot`。
- WS：不从 payload 或输出文本推断 exitCode；显示请求使用的稳定 sandbox ID。
- `shell_execute` 仍只执行本地 PRoot；`sandbox_dispatch` 仍拒绝 `proot` 且不解析 payload。
- Provider 通过 invocationId 注册取消句柄；UI 不接触 PersistentShell 或 WS request ID。

### 阶段 7 验收结果（2026-08-14）

- Android JVM 完整测试：通过；
- Debug APK：构建通过；
- Python ExecPlane：25 项通过；
- APK SHA-256：`f8bb70a1a25980f002d06f84385ba145f2e7430a48d43e4f379de76a15cf8c67`；
- 尚待设备复跑：PRoot/WS 并行取消、会话切换与前后台恢复；
- 尚待代码清理：移除两个旧包装函数，使 ChatViewModel 只保留通用事件消费入口。
