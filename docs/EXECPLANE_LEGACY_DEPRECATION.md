# ExecPlane 旧协议弃用策略

## 当前协议基线

- Android 与参考服务端当前最低且唯一支持版本：ExecPlane `0.2`。
- `0.1` 不做静默兼容；握手版本不一致时连接不得上线。
- 新 WS 能力只能通过不透明 `dispatch` 增加，Android 不再增加业务 RPC。

## 已下线

自本次整改起，Android 不再通过旧协议提供以下模型工具路由：

- `shell_execute(sandbox=...)` / `exec`；
- `file_read(sandbox=...)` / `fs.read`；
- `file_write(sandbox=...)` / `fs.write`；
- `file_edit(sandbox=...)`；
- `read_image(sandbox=...)`。

远端执行必须使用 `sandbox_dispatch`，业务语义由服务端指令集声明。本地
`shell_execute`、`file_read/write/edit` 和 `read_image` 只操作 Android/PRoot 文件空间。

## 临时保留

`transfer.*` 仅供 `sandbox_file_push` / `sandbox_file_pull` 兼容使用，冻结在协议
`0.2`，不再扩展。通用 Resource 通道验收后，在下一个破坏性协议版本中删除。

参考服务端可暂时继续实现 `exec` 与 `fs.*`，用于旧版 App；这不代表当前 Android
客户端仍公开对应入口。部署方应以 `dispatch` capability 作为新架构最低要求。

## Resource 通道

Android 现通过 `ResourceDescriptor`（resourceId/name/size/sha256/mimeType）描述跨边界字节，
`ResourceChannel` 负责分块、校验、临时文件和失败清理。旧 `transfer.*` 仅是当前 0.2 服务端
的私有承载实现，不再暴露为 App 架构或模型业务语义。二进制不得 Base64 内嵌到 dispatch
payload 或工具结果。
