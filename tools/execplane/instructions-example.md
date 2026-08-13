# VCPMinis 沙箱 AI 指令集

使用 Android 的 `sandbox_dispatch` 工具。必须把 `sandbox` 明确设为用户指定的沙箱名称，并将下面定义的指令完整写入 `payload`。Android 会逐字转发 UTF-8 payload，不解析、不补全、不转义，也不会在失败时改投 PRoot。

## 通用规则

- 只使用本文列出的指令，不要猜测不存在的 verb。
- 第一段 token 是指令名；`exec` 之后的全部内容由服务端原样交给 `/bin/sh -lc`。
- 复杂 Shell 优先使用多行格式，避免额外的 JSON/Shell 嵌套转义。
- 每次调用最长 3600 秒；长任务应设置合适的 `timeout`。
- 命令可能修改服务器文件。执行删除、覆盖、发布等高影响操作前，遵守用户授权范围。
- 本沙箱文件系统与 Android 内置 PRoot 不共享；不要用 Android 的 `file_read/file_write/file_edit` 假装访问这里。

## 查看帮助

```text
help
```

返回服务端当前支持的 DSL 摘要。

## 查看状态

```text
status
```

返回 DSL 版本、服务端工作目录、主机名、进程 ID 和支持的指令。

## 执行 Shell

单行格式：

```text
exec ls -la
```

多行格式：

```text
exec
cd /home/nova/workspace/RikkaMinis
git status --short
```

服务端会流式返回 stdout 和 stderr，并在最终结果尾部添加：

```text
[exit=<退出码> durationMs=<耗时>]
```

非零退出码不会被 Android 改写；应根据尾部退出码和输出判断是否成功。超时或取消会终止该 Shell 的完整进程组。

## 推荐施工流程

```text
status
```

```text
exec
cd /home/nova/workspace/RikkaMinis
git status --short
```

修改前先读取目标文件；修改后运行相关测试并再次检查 `git diff --check` 与 `git status --short`。如果脚本较长，先通过 `exec` 在服务端工作区写入脚本文件，再执行它。
