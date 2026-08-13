# 示例沙箱 AI 指令集

使用 Android 的 `sandbox_dispatch` 工具，把 `payload` 原样发送到此沙箱。

此示例插件仅回显 payload。实际服务端可以在自己的 dispatch 插件里定义和解析任意 DSL；Android 不理解也不会改写这些语法。
