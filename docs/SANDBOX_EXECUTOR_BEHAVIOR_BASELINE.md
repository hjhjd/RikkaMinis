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
